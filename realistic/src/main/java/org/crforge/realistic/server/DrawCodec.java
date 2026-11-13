/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
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
 * with flag 2 (3D models, {@link ModelInstance}), then:
 * varint  new model refs, each: varint id, varint length and the asset id (ASCII), float32
 *         transform[16], byte shadow, varint primitives left out and their indices
 * varint  model draws, each: varint slot from the previous draw's, varint ((reference draw + 1)
 *         &lt;&lt; 1 | shadow) (0 + 1: none, coded against an empty draw), varint field mask, then the
 *         fields the mask names, against the reference draw's:
 *   1     parts: varint count, each: varint ref, host + 1, host node + 1 (then 2, 4, 8 follow,
 *         coded against zeros)
 *   2     each part's varint clip, from-clip       4  each part's zz time, zz from-time change
 *   8     each part's varint blend                 16 zz x, y change
 *   32    float32 kx, ky                           64 float32 heading
 *   128   byte flags (1 blue, 2 clipping plane)    256 float32 colour multiply[4], add[3]
 *   512   float32 light[3]                         1024 zz each rect corner's change (from x, y)
 *   2048  zz each drawn corner's change (from the rect's)
 *   4096  float32 tint[4]
 *   8192  glows: each part's varint primitives (0: none), varint glowing ones, each: varint
 *         primitive, float32[14]
 *   16384 effect draws: varint count, each: byte 2 (the reference draw's same one), 1 (it with
 *         float32 matrix[16]) or 0 then varint ref, float32 matrix[16], byte (shader | 4
 *         additive | 8 billboard), varint uniforms and float32 each, 5 textures (varint length,
 *         0 for none, and the id)
 *   32768 zz effect clock change (1/4096 s)
 * </pre>
 *
 * <p>The reference cursor: a polygon is matched to the first not yet used polygon of the reference
 * with the same region from the expected position on (else before it); the expected position
 * follows the last one matched.
 */
final class DrawCodec {

  static final int KEY = 1;

  /** The payload's flag for its 3D model section. */
  static final int MODELS = 2;

  private static final ModelInstance EMPTY = new ModelInstance();

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
    private ModelAssets models;
    private final BitSet refsSent = new BitSet();

    /** The model section's bytes of the last frame, before deflate. */
    int modelBytes;

    /** The size of the last frame before deflate. */
    int rawBytes;

    Encoder(DrawDefs defs, int level) {
      this.defs = defs;
      this.deflater = new Deflater(level, true);
    }

    /** The model refs the frames' 3D draws name (needed once a frame has any). */
    Encoder models(ModelAssets assets) {
      this.models = assets;
      return this;
    }

    /** Encodes a frame, as a key frame if asked or if nothing was sent yet. */
    byte[] encode(DrawFrame f, boolean key) {
      out.reset();
      boolean keyframe = key || reference == null;
      DrawFrame ref = keyframe ? new DrawFrame() : reference;
      if (keyframe) lastShape.clear();
      boolean withModels = !f.models.isEmpty();
      out.put((keyframe ? KEY : 0) | (withModels ? MODELS : 0));
      definitions(f);
      out.varint(f.count);
      ops(f, ref);
      int before = out.len;
      if (withModels) modelSection(f, ref);
      modelBytes = out.len - before;
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

    private void modelSection(DrawFrame f, DrawFrame ref) {
      BitSet fresh = new BitSet();
      for (ModelInstance m : f.models) {
        for (int r : m.refs) if (!refsSent.get(r)) fresh.set(r);
        for (ModelInstance.Fx e : m.fx) if (!refsSent.get(e.ref())) fresh.set(e.ref());
      }
      out.varint(fresh.cardinality());
      for (int id = fresh.nextSetBit(0); id >= 0; id = fresh.nextSetBit(id + 1)) {
        ModelAssets.Ref r = models.ref(id);
        out.varint(id);
        byte[] a = r.asset().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        out.varint(a.length);
        for (byte b : a) out.put(b);
        for (float v : r.transform()) out.f32(v);
        out.put(r.shadow() ? 1 : 0);
        out.varint(r.skip().length);
        for (int k : r.skip()) out.varint(k);
        refsSent.set(id);
      }
      out.varint(f.models.size());
      boolean[] used = new boolean[ref.models.size()];
      int prevSlot = 0;
      for (ModelInstance m : f.models) {
        out.varint(m.slot - prevSlot);
        prevSlot = m.slot;
        int r = matchModel(m, ref.models, used);
        out.varint(((r + 1) << 1) | (m.shadow ? 1 : 0));
        instance(m, r >= 0 ? ref.models.get(r) : EMPTY);
      }
    }

    /** The unused reference draw of the same parts and kind nearest to it, or -1. */
    private static int matchModel(ModelInstance m, List<ModelInstance> refs, boolean[] used) {
      int best = -1;
      long bestD = Long.MAX_VALUE;
      for (int i = 0; i < refs.size(); i++) {
        ModelInstance o = refs.get(i);
        if (used[i] || o.shadow != m.shadow || !Arrays.equals(o.refs, m.refs)) continue;
        long d = Math.abs((long) o.x - m.x) + Math.abs((long) o.y - m.y);
        if (d < bestD) {
          bestD = d;
          best = i;
        }
      }
      if (best >= 0) used[best] = true;
      return best;
    }

    private void instance(ModelInstance m, ModelInstance b) {
      int n = m.parts();
      boolean parts = !m.sameParts(b);
      int[] zeros = new int[n];
      int[] bClip = parts ? zeros : b.clip;
      int[] bFrom = parts ? zeros : b.fromClip;
      int[] bTime = parts ? zeros : b.time;
      int[] bFromTime = parts ? zeros : b.fromTime;
      int[] bBlend = parts ? zeros : b.blend;
      float[][][] bGlows = parts ? new float[n][][] : b.glows;
      int mask = 0;
      if (parts) mask |= 1;
      if (!Arrays.equals(m.clip, bClip) || !Arrays.equals(m.fromClip, bFrom)) mask |= 2;
      if (!Arrays.equals(m.time, bTime) || !Arrays.equals(m.fromTime, bFromTime)) mask |= 4;
      if (!Arrays.equals(m.blend, bBlend)) mask |= 8;
      if (m.x != b.x || m.y != b.y) mask |= 16;
      if (Float.compare(m.kx, b.kx) != 0 || Float.compare(m.ky, b.ky) != 0) mask |= 32;
      if (Float.compare(m.heading, b.heading) != 0) mask |= 64;
      if (m.blue != b.blue || m.clipPlane != b.clipPlane) mask |= 128;
      if (!Arrays.equals(m.colorMul, b.colorMul) || !Arrays.equals(m.colorAdd, b.colorAdd)) {
        mask |= 256;
      }
      if (!Arrays.equals(m.light, b.light)) mask |= 512;
      for (int i = 0; i < 4; i++) {
        if (m.rect[i] - (i % 2 == 0 ? m.x : m.y) != b.rect[i] - (i % 2 == 0 ? b.x : b.y)) {
          mask |= 1024;
        }
        if (m.drawn[i] - m.rect[i] != b.drawn[i] - b.rect[i]) mask |= 2048;
      }
      if (!Arrays.equals(m.tint, b.tint)) mask |= 4096;
      boolean glowsSame = true;
      for (int p = 0; p < n && glowsSame; p++) {
        glowsSame = Arrays.deepEquals(m.glows[p], bGlows[p]);
      }
      if (!glowsSame) mask |= 8192;
      if (!m.fx.equals(b.fx)) mask |= 16384;
      if (m.fxTime != b.fxTime) mask |= 32768;
      out.varint(mask);
      if ((mask & 1) != 0) {
        out.varint(n);
        for (int p = 0; p < n; p++) {
          out.varint(m.refs[p]);
          out.varint(m.host[p] + 1);
          out.varint(m.hostNode[p] + 1);
        }
      }
      if ((mask & 2) != 0) {
        for (int p = 0; p < n; p++) {
          out.varint(m.clip[p]);
          out.varint(m.fromClip[p]);
        }
      }
      if ((mask & 4) != 0) {
        for (int p = 0; p < n; p++) {
          out.zz(m.time[p] - bTime[p]);
          out.zz(m.fromTime[p] - bFromTime[p]);
        }
      }
      if ((mask & 8) != 0) for (int p = 0; p < n; p++) out.varint(m.blend[p]);
      if ((mask & 16) != 0) {
        out.zz(m.x - b.x);
        out.zz(m.y - b.y);
      }
      if ((mask & 32) != 0) {
        out.f32(m.kx);
        out.f32(m.ky);
      }
      if ((mask & 64) != 0) out.f32(m.heading);
      if ((mask & 128) != 0) out.put((m.blue ? 1 : 0) | (m.clipPlane ? 2 : 0));
      if ((mask & 256) != 0) {
        for (float v : m.colorMul) out.f32(v);
        for (float v : m.colorAdd) out.f32(v);
      }
      if ((mask & 512) != 0) for (float v : m.light) out.f32(v);
      if ((mask & 1024) != 0) {
        for (int i = 0; i < 4; i++) {
          int o = i % 2 == 0 ? m.x : m.y;
          int bo = i % 2 == 0 ? b.x : b.y;
          out.zz((m.rect[i] - o) - (b.rect[i] - bo));
        }
      }
      if ((mask & 2048) != 0) {
        for (int i = 0; i < 4; i++) out.zz((m.drawn[i] - m.rect[i]) - (b.drawn[i] - b.rect[i]));
      }
      if ((mask & 4096) != 0) for (float v : m.tint) out.f32(v);
      if ((mask & 8192) != 0) {
        for (int p = 0; p < n; p++) {
          float[][] g = m.glows[p];
          out.varint(g == null ? 0 : g.length);
          if (g == null) continue;
          int lit = 0;
          for (float[] x : g) if (x != null) lit++;
          out.varint(lit);
          for (int k = 0; k < g.length; k++) {
            if (g[k] == null) continue;
            out.varint(k);
            for (int j = 0; j < ModelInstance.GLOW_FLOATS; j++) out.f32(g[k][j]);
          }
        }
      }
      if ((mask & 16384) != 0) {
        out.varint(m.fx.size());
        for (int i = 0; i < m.fx.size(); i++) {
          ModelInstance.Fx e = m.fx.get(i);
          ModelInstance.Fx o = i < b.fx.size() ? b.fx.get(i) : null;
          if (e.equals(o)) {
            out.put(2);
            continue;
          }
          if (o != null && e.withMatrix(o.matrix()).equals(o)) {
            out.put(1);
            for (float v : e.matrix()) out.f32(v);
            continue;
          }
          out.put(0);
          out.varint(e.ref());
          for (float v : e.matrix()) out.f32(v);
          out.put(e.shader() | (e.additive() ? 4 : 0) | (e.billboard() ? 8 : 0));
          out.varint(e.uniforms().length);
          for (float v : e.uniforms()) out.f32(v);
          for (String t : e.textures()) {
            byte[] a =
                t == null ? new byte[0] : t.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.varint(a.length);
            for (byte x : a) out.put(x);
          }
        }
      }
      if ((mask & 32768) != 0) out.zz(m.fxTime - b.fxTime);
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
    final Map<Integer, ModelAssets.Ref> modelRefs = new HashMap<>();
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
      if ((flags & MODELS) != 0) modelSection(in, f, ref);
      reference = f;
      return f;
    }

    private void modelSection(Reader in, DrawFrame f, DrawFrame ref) {
      for (int k = in.varint(); k > 0; k--) {
        int id = in.varint();
        byte[] a = new byte[in.varint()];
        for (int i = 0; i < a.length; i++) a[i] = (byte) in.u8();
        float[] t = new float[16];
        for (int i = 0; i < 16; i++) t[i] = in.f32();
        boolean shadow = in.u8() != 0;
        int[] skip = new int[in.varint()];
        for (int i = 0; i < skip.length; i++) skip[i] = in.varint();
        modelRefs.put(
            id,
            new ModelAssets.Ref(
                id, new String(a, java.nio.charset.StandardCharsets.US_ASCII), t, shadow, skip));
      }
      int count = in.varint();
      int slot = 0;
      for (int k = 0; k < count; k++) {
        slot += in.varint();
        int head = in.varint();
        int r = (head >>> 1) - 1;
        ModelInstance m = r >= 0 ? ref.models.get(r).copy() : EMPTY.copy();
        m.slot = slot;
        m.shadow = (head & 1) != 0;
        int mask = in.varint();
        int bx = m.x, by = m.y;
        int[] rectRel = new int[4];
        int[] drawnRel = new int[4];
        for (int i = 0; i < 4; i++) {
          rectRel[i] = m.rect[i] - (i % 2 == 0 ? bx : by);
          drawnRel[i] = m.drawn[i] - m.rect[i];
        }
        if ((mask & 1) != 0) {
          int n = in.varint();
          m.parts(n);
          for (int p = 0; p < n; p++) {
            m.refs[p] = in.varint();
            m.host[p] = in.varint() - 1;
            m.hostNode[p] = in.varint() - 1;
          }
        }
        int n = m.parts();
        if ((mask & 2) != 0) {
          for (int p = 0; p < n; p++) {
            m.clip[p] = in.varint();
            m.fromClip[p] = in.varint();
          }
        }
        if ((mask & 4) != 0) {
          for (int p = 0; p < n; p++) {
            m.time[p] += in.zz();
            m.fromTime[p] += in.zz();
          }
        }
        if ((mask & 8) != 0) for (int p = 0; p < n; p++) m.blend[p] = in.varint();
        if ((mask & 16) != 0) {
          m.x += in.zz();
          m.y += in.zz();
        }
        if ((mask & 32) != 0) {
          m.kx = in.f32();
          m.ky = in.f32();
        }
        if ((mask & 64) != 0) m.heading = in.f32();
        if ((mask & 128) != 0) {
          int fl = in.u8();
          m.blue = (fl & 1) != 0;
          m.clipPlane = (fl & 2) != 0;
        }
        if ((mask & 256) != 0) {
          for (int i = 0; i < 4; i++) m.colorMul[i] = in.f32();
          for (int i = 0; i < 3; i++) m.colorAdd[i] = in.f32();
        }
        if ((mask & 512) != 0) for (int i = 0; i < 3; i++) m.light[i] = in.f32();
        if ((mask & 1024) != 0) for (int i = 0; i < 4; i++) rectRel[i] += in.zz();
        for (int i = 0; i < 4; i++) m.rect[i] = rectRel[i] + (i % 2 == 0 ? m.x : m.y);
        if ((mask & 2048) != 0) for (int i = 0; i < 4; i++) drawnRel[i] += in.zz();
        for (int i = 0; i < 4; i++) m.drawn[i] = drawnRel[i] + m.rect[i];
        if ((mask & 4096) != 0) for (int i = 0; i < 4; i++) m.tint[i] = in.f32();
        if ((mask & 8192) != 0) {
          for (int p = 0; p < n; p++) {
            int len = in.varint();
            if (len == 0) {
              m.glows[p] = null;
              continue;
            }
            m.glows[p] = new float[len][];
            for (int lit = in.varint(); lit > 0; lit--) {
              int prim = in.varint();
              float[] g = new float[ModelInstance.GLOW_FLOATS];
              for (int j = 0; j < g.length; j++) g[j] = in.f32();
              m.glows[p][prim] = g;
            }
          }
        }
        if ((mask & 16384) != 0) {
          List<ModelInstance.Fx> before = m.fx;
          m.fx = new ArrayList<>();
          for (int e = 0, fxCount = in.varint(); e < fxCount; e++) {
            int how = in.u8();
            if (how == 2) {
              m.fx.add(before.get(e));
              continue;
            }
            if (how == 1) {
              float[] mat = new float[16];
              for (int i = 0; i < 16; i++) mat[i] = in.f32();
              m.fx.add(before.get(e).withMatrix(mat));
              continue;
            }
            int fxRef = in.varint();
            float[] mat = new float[16];
            for (int i = 0; i < 16; i++) mat[i] = in.f32();
            int fl = in.u8();
            float[] u = new float[in.varint()];
            for (int i = 0; i < u.length; i++) u[i] = in.f32();
            String[] tex = new String[5];
            for (int i = 0; i < 5; i++) {
              byte[] a = new byte[in.varint()];
              for (int j = 0; j < a.length; j++) a[j] = (byte) in.u8();
              tex[i] =
                  a.length == 0 ? null : new String(a, java.nio.charset.StandardCharsets.US_ASCII);
            }
            m.fx.add(
                new ModelInstance.Fx(fxRef, mat, fl & 3, (fl & 4) != 0, (fl & 8) != 0, u, tex));
          }
        }
        if ((mask & 32768) != 0) m.fxTime += in.zz();
        f.models.add(m);
      }
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
