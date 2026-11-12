/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The draw-list stream's coding: a frame is sent as the changes from the last frame sent to that
 * page (or whole, a key frame), then deflated (raw deflate, which browsers inflate with {@code
 * DecompressionStream("deflate-raw")}). The page's decoder in {@code index.html} mirrors {@link
 * Decoder}.
 *
 * <p>The payload, before deflate (varints are unsigned LEB128, "zz" ones zigzag signed):
 *
 * <pre>
 * byte    flags (1: key frame: the reference is empty)
 * varint  new states, each: varint id, byte blend on, varint src, dst, srcAlpha, dstAlpha,
 *         byte shader, float32 add r, g, b, float32 gray
 * varint  new regions, each: varint id, varint texture, byte kind, varint vertices,
 *         float32 u, v a vertex, and for kind 2 (triangles) varint index count, varint indices
 * varint  polygons, then ops until that many polygons are made:
 *   0x00..0x3F  RUN: the next (op & 63) + 1 polygons of the reference, unchanged
 *   0x40 | f    MATCH: a polygon of the reference changed; f: 1 a reference jump follows (zz,
 *               from the expected one), 2 a state (varint), 4 colours, 8 a move (zz dx, dy of
 *               vertex 0), 16 the other vertices' offsets from the reference's after the move (zz)
 *   0x80 | f    NEW: f: 1 a state (varint) else the previous polygon's, 2 a colour a vertex,
 *               4 the shape (vertex offsets) of the region's last NEW polygon; then varint
 *               region, colours, zz vertex 0 from the previous polygon's vertex 0, and unless 4,
 *               zz each further vertex from the one before
 * colours: 4 bytes each (ABGR as the batch packs them), one or one a vertex (byte flag 1 first
 *          in a MATCH: one a vertex)
 * </pre>
 *
 * <p>The reference cursor: a polygon is matched to the first not yet used polygon of the reference
 * with the same region from the expected position on (else before it); the expected position
 * follows the last one matched.
 */
final class DrawCodec {

  static final int KEY = 1;

  private DrawCodec() {}

  /** One page's encoder: what it has been sent. Not thread safe. */
  static final class Encoder {
    private final DrawDefs defs;
    private final BitSet statesSent = new BitSet();
    private final BitSet regionsSent = new BitSet();
    private final Map<Integer, int[]> lastShape = new HashMap<>();
    private final Deflater deflater;
    private final Buf out = new Buf();
    private DrawFrame reference;
    private byte[] packed = new byte[1 << 16];

    /** The size of the last frame before deflate. */
    int rawBytes;

    Encoder(DrawDefs defs, int level) {
      this.defs = defs;
      this.deflater = new Deflater(level, true);
    }

    /** Encodes a frame, as a key frame if asked or if nothing was sent yet. */
    byte[] encode(DrawFrame f, boolean key) {
      out.reset();
      boolean keyframe = key || reference == null;
      DrawFrame ref = keyframe ? new DrawFrame() : reference;
      if (keyframe) lastShape.clear();
      out.put(keyframe ? KEY : 0);
      definitions(f);
      out.varint(f.count);
      ops(f, ref);
      rawBytes = out.len;
      reference = f;
      return deflate(out.data, out.len);
    }

    /** Forgets the reference: the next frame is a key frame. */
    void reset() {
      reference = null;
    }

    void dispose() {
      deflater.end();
    }

    private void definitions(DrawFrame f) {
      BitSet newStates = new BitSet();
      BitSet newRegions = new BitSet();
      for (int i = 0; i < f.count; i++) {
        if (!statesSent.get(f.state[i])) newStates.set(f.state[i]);
        if (!regionsSent.get(f.region[i])) newRegions.set(f.region[i]);
      }
      out.varint(newStates.cardinality());
      for (int id = newStates.nextSetBit(0); id >= 0; id = newStates.nextSetBit(id + 1)) {
        DrawDefs.State s = defs.state(id);
        out.varint(id);
        out.put(s.blend() ? 1 : 0);
        out.varint(s.src());
        out.varint(s.dst());
        out.varint(s.srcAlpha());
        out.varint(s.dstAlpha());
        out.put(s.shader());
        out.f32(s.addR());
        out.f32(s.addG());
        out.f32(s.addB());
        out.f32(s.gray());
        statesSent.set(id);
      }
      out.varint(newRegions.cardinality());
      for (int id = newRegions.nextSetBit(0); id >= 0; id = newRegions.nextSetBit(id + 1)) {
        DrawDefs.Region r = defs.region(id);
        out.varint(id);
        out.varint(r.texture());
        out.put(r.kind());
        out.varint(r.verts());
        for (float v : r.uv()) out.f32(v);
        if (r.kind() == DrawDefs.TRIANGLES) {
          out.varint(r.indices().length);
          for (short s : r.indices()) out.varint(s & 0xFFFF);
        }
        regionsSent.set(id);
      }
    }

    private void ops(DrawFrame f, DrawFrame ref) {
      Map<Integer, int[]> where = occurrences(ref);
      Map<Integer, Integer> nextFree = new HashMap<>();
      boolean[] used = new boolean[ref.count];
      int expected = 0;
      int run = 0;
      int prevState = -1;
      int prevX = 0, prevY = 0;
      for (int i = 0; i < f.count; i++) {
        int m = match(f.region[i], expected, ref, where, nextFree, used);
        if (m >= 0
            && m == expected
            && f.state[i] == ref.state[m]
            && f.sameColors(i, ref, m)
            && f.samePositions(i, ref, m)) {
          used[m] = true;
          expected = m + 1;
          if (++run == 64) {
            out.put(run - 1);
            run = 0;
          }
        } else {
          if (run > 0) {
            out.put(run - 1);
            run = 0;
          }
          if (m >= 0) {
            used[m] = true;
            matchOp(f, i, ref, m, expected);
            expected = m + 1;
          } else {
            newOp(f, i, prevState, prevX, prevY);
          }
        }
        prevState = f.state[i];
        prevX = f.pos[f.posAt[i]];
        prevY = f.pos[f.posAt[i] + 1];
      }
      if (run > 0) out.put(run - 1);
    }

    private void matchOp(DrawFrame f, int i, DrawFrame ref, int m, int expected) {
      int flags = 0;
      if (m != expected) flags |= 1;
      if (f.state[i] != ref.state[m]) flags |= 2;
      if (!f.sameColors(i, ref, m)) flags |= 4;
      int n = f.verts[i];
      int a = f.posAt[i];
      int b = ref.posAt[m];
      int dx = f.pos[a] - ref.pos[b];
      int dy = f.pos[a + 1] - ref.pos[b + 1];
      if (dx != 0 || dy != 0) flags |= 8;
      boolean residual = false;
      for (int k = 1; k < n && !residual; k++) {
        residual =
            f.pos[a + 2 * k] - ref.pos[b + 2 * k] != dx
                || f.pos[a + 2 * k + 1] - ref.pos[b + 2 * k + 1] != dy;
      }
      if (residual) flags |= 16;
      out.put(0x40 | flags);
      if ((flags & 1) != 0) out.zz(m - expected);
      if ((flags & 2) != 0) out.varint(f.state[i]);
      if ((flags & 4) != 0) {
        out.put(f.oneColor[i] ? 0 : 1);
        colors(f, i);
      }
      if ((flags & 8) != 0) {
        out.zz(dx);
        out.zz(dy);
      }
      if (residual) {
        for (int k = 1; k < n; k++) {
          out.zz(f.pos[a + 2 * k] - ref.pos[b + 2 * k] - dx);
          out.zz(f.pos[a + 2 * k + 1] - ref.pos[b + 2 * k + 1] - dy);
        }
      }
    }

    private void newOp(DrawFrame f, int i, int prevState, int prevX, int prevY) {
      int n = f.verts[i];
      int a = f.posAt[i];
      int[] shape = new int[2 * (n - 1)];
      for (int k = 1; k < n; k++) {
        shape[2 * k - 2] = f.pos[a + 2 * k] - f.pos[a];
        shape[2 * k - 1] = f.pos[a + 2 * k + 1] - f.pos[a + 1];
      }
      int[] last = lastShape.get(f.region[i]);
      boolean sameShape = last != null && Arrays.equals(last, shape);
      int flags = 0;
      if (f.state[i] != prevState) flags |= 1;
      if (!f.oneColor[i]) flags |= 2;
      if (sameShape) flags |= 4;
      out.put(0x80 | flags);
      if ((flags & 1) != 0) out.varint(f.state[i]);
      out.varint(f.region[i]);
      colors(f, i);
      out.zz(f.pos[a] - prevX);
      out.zz(f.pos[a + 1] - prevY);
      if (!sameShape) {
        for (int k = 1; k < n; k++) {
          out.zz(f.pos[a + 2 * k] - f.pos[a + 2 * k - 2]);
          out.zz(f.pos[a + 2 * k + 1] - f.pos[a + 2 * k - 1]);
        }
        lastShape.put(f.region[i], shape);
      }
    }

    private void colors(DrawFrame f, int i) {
      int c = f.colorCount(i);
      for (int k = 0; k < c; k++) out.i32(f.colors[f.colorAt[i] + k]);
    }

    private byte[] deflate(byte[] data, int len) {
      deflater.reset();
      deflater.setInput(data, 0, len);
      deflater.finish();
      int n = 0;
      while (!deflater.finished()) {
        if (n == packed.length) packed = Arrays.copyOf(packed, packed.length * 2);
        n += deflater.deflate(packed, n, packed.length - n);
      }
      return Arrays.copyOf(packed, n);
    }
  }

  /** The polygons of {@code ref} by region, in order. */
  private static Map<Integer, int[]> occurrences(DrawFrame ref) {
    Map<Integer, Integer> counts = new HashMap<>();
    for (int i = 0; i < ref.count; i++) counts.merge(ref.region[i], 1, Integer::sum);
    Map<Integer, int[]> where = new HashMap<>();
    Map<Integer, Integer> fill = new HashMap<>();
    for (int i = 0; i < ref.count; i++) {
      int r = ref.region[i];
      int[] list = where.computeIfAbsent(r, k -> new int[counts.get(k)]);
      int at = fill.merge(r, 1, Integer::sum) - 1;
      list[at] = i;
    }
    return where;
  }

  /**
   * The reference polygon a polygon of {@code region} is matched to: the expected one if it is of
   * that region, else the first unused one of the region from the expected position on, else the
   * first unused one before it; -1 for none.
   */
  private static int match(
      int region,
      int expected,
      DrawFrame ref,
      Map<Integer, int[]> where,
      Map<Integer, Integer> nextFree,
      boolean[] used) {
    if (expected < ref.count && !used[expected] && ref.region[expected] == region) {
      return expected;
    }
    int[] list = where.get(region);
    if (list == null) return -1;
    int lo = 0, hi = list.length;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (list[mid] < expected) lo = mid + 1;
      else hi = mid;
    }
    for (int k = lo; k < list.length; k++) if (!used[list[k]]) return list[k];
    int from = nextFree.getOrDefault(region, 0);
    for (int k = from; k < lo; k++) {
      if (!used[list[k]]) {
        nextFree.put(region, k);
        return list[k];
      }
    }
    nextFree.put(region, lo);
    return -1;
  }

  /** The page's decoder, in Java: the round trip's other half (tests). Not thread safe. */
  static final class Decoder {
    final Map<Integer, DrawDefs.State> states = new HashMap<>();
    final Map<Integer, DrawDefs.Region> regions = new HashMap<>();
    private final Map<Integer, int[]> lastShape = new HashMap<>();
    private final Inflater inflater = new Inflater(true);
    private DrawFrame reference;

    DrawFrame decode(byte[] deflated) throws DataFormatException {
      inflater.reset();
      inflater.setInput(deflated);
      ByteArrayOutputStream raw = new ByteArrayOutputStream();
      byte[] chunk = new byte[1 << 16];
      while (!inflater.finished()) {
        int n = inflater.inflate(chunk);
        if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
        raw.write(chunk, 0, n);
      }
      Reader in = new Reader(raw.toByteArray());
      int flags = in.u8();
      boolean key = (flags & KEY) != 0;
      if (key) lastShape.clear();
      DrawFrame ref = key || reference == null ? new DrawFrame() : reference;
      for (int s = in.varint(); s > 0; s--) {
        int id = in.varint();
        boolean blend = in.u8() != 0;
        int src = in.varint(), dst = in.varint(), sa = in.varint(), da = in.varint();
        int shader = in.u8();
        float r = in.f32(), g = in.f32(), b = in.f32(), gray = in.f32();
        states.put(id, new DrawDefs.State(id, blend, src, dst, sa, da, shader, r, g, b, gray));
      }
      for (int s = in.varint(); s > 0; s--) {
        int id = in.varint();
        int tex = in.varint();
        int kind = in.u8();
        int n = in.varint();
        float[] uv = new float[2 * n];
        for (int k = 0; k < uv.length; k++) uv[k] = in.f32();
        short[] idx = null;
        if (kind == DrawDefs.TRIANGLES) {
          idx = new short[in.varint()];
          for (int k = 0; k < idx.length; k++) idx[k] = (short) in.varint();
        }
        regions.put(id, new DrawDefs.Region(id, tex, kind, n, uv, idx, -1));
      }
      int count = in.varint();
      DrawFrame f = new DrawFrame();
      int expected = 0;
      int prevState = -1, prevX = 0, prevY = 0;
      while (f.count < count) {
        int op = in.u8();
        if (op < 0x40) {
          for (int k = 0; k <= op; k++) {
            copyMoved(f, ref, expected, ref.state[expected], null, 0, 0, null);
            expected++;
          }
        } else if (op < 0x80) {
          int m = expected;
          if ((op & 1) != 0) m = expected + in.zz();
          int state = (op & 2) != 0 ? in.varint() : ref.state[m];
          int[] colors = null;
          if ((op & 4) != 0) {
            boolean each = in.u8() != 0;
            colors = new int[each ? ref.verts[m] : 1];
            for (int k = 0; k < colors.length; k++) colors[k] = in.i32();
          }
          int dx = 0, dy = 0;
          if ((op & 8) != 0) {
            dx = in.zz();
            dy = in.zz();
          }
          int[] residual = null;
          if ((op & 16) != 0) {
            residual = new int[2 * (ref.verts[m] - 1)];
            for (int k = 0; k < residual.length; k++) residual[k] = in.zz();
          }
          copyMoved(f, ref, m, state, colors, dx, dy, residual);
          expected = m + 1;
        } else {
          int state = (op & 1) != 0 ? in.varint() : prevState;
          int region = in.varint();
          int n = regions.get(region).verts();
          boolean each = (op & 2) != 0;
          f.begin(state, region, n, !each);
          for (int k = 0; k < (each ? n : 1); k++) f.color(in.i32());
          int x = prevX + in.zz();
          int y = prevY + in.zz();
          f.point(x, y);
          if ((op & 4) != 0) {
            int[] shape = lastShape.get(region);
            for (int k = 1; k < n; k++) f.point(x + shape[2 * k - 2], y + shape[2 * k - 1]);
          } else {
            int[] shape = new int[2 * (n - 1)];
            int px = x, py = y;
            for (int k = 1; k < n; k++) {
              px += in.zz();
              py += in.zz();
              f.point(px, py);
              shape[2 * k - 2] = px - x;
              shape[2 * k - 1] = py - y;
            }
            lastShape.put(region, shape);
          }
        }
        int last = f.count - 1;
        prevState = f.state[last];
        prevX = f.pos[f.posAt[last]];
        prevY = f.pos[f.posAt[last] + 1];
      }
      reference = f;
      return f;
    }

    private static void copyMoved(
        DrawFrame f, DrawFrame ref, int m, int state, int[] colors, int dx, int dy, int[] res) {
      int n = ref.verts[m];
      if (colors == null) {
        f.begin(state, ref.region[m], n, ref.oneColor[m]);
        for (int k = 0; k < ref.colorCount(m); k++) f.color(ref.colors[ref.colorAt[m] + k]);
      } else {
        f.begin(state, ref.region[m], n, colors.length == 1);
        for (int c : colors) f.color(c);
      }
      int b = ref.posAt[m];
      for (int k = 0; k < n; k++) {
        int rx = k == 0 || res == null ? 0 : res[2 * k - 2];
        int ry = k == 0 || res == null ? 0 : res[2 * k - 1];
        f.point(ref.pos[b + 2 * k] + dx + rx, ref.pos[b + 2 * k + 1] + dy + ry);
      }
    }
  }

  /** A growable byte buffer with the payload's encodings. */
  static final class Buf {
    byte[] data = new byte[1 << 16];
    int len;

    void reset() {
      len = 0;
    }

    void put(int b) {
      if (len == data.length) data = Arrays.copyOf(data, data.length * 2);
      data[len++] = (byte) b;
    }

    void varint(int v) {
      while ((v & ~0x7F) != 0) {
        put((v & 0x7F) | 0x80);
        v >>>= 7;
      }
      put(v);
    }

    void zz(int v) {
      varint((v << 1) ^ (v >> 31));
    }

    void i32(int v) {
      put(v);
      put(v >>> 8);
      put(v >>> 16);
      put(v >>> 24);
    }

    void f32(float f) {
      i32(Float.floatToRawIntBits(f));
    }
  }

  /** Reads what {@link Buf} writes. */
  static final class Reader {
    private final byte[] data;
    private int at;

    Reader(byte[] data) {
      this.data = data;
    }

    int u8() {
      return data[at++] & 0xFF;
    }

    int varint() {
      int v = 0;
      int shift = 0;
      while (true) {
        int b = u8();
        v |= (b & 0x7F) << shift;
        if ((b & 0x80) == 0) return v;
        shift += 7;
      }
    }

    int zz() {
      int v = varint();
      return (v >>> 1) ^ -(v & 1);
    }

    int i32() {
      return u8() | u8() << 8 | u8() << 16 | u8() << 24;
    }

    float f32() {
      return Float.intBitsToFloat(i32());
    }
  }
}
