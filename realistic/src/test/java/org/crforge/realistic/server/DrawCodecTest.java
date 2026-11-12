/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.badlogic.gdx.graphics.GL20;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The draw-list stream's coding: what the encoder sends, the decoder makes again, exactly. */
class DrawCodecTest {

  private final TextureStore store = new TextureStore(null);
  private final DrawDefs defs = new DrawDefs(store);

  /** A polygon of a test frame. */
  private record Poly(int state, int region, int[] colors, int[] pos) {}

  private DrawFrame frame(List<Poly> polys) {
    DrawFrame f = new DrawFrame();
    for (Poly p : polys) {
      int n = p.pos().length / 2;
      f.begin(p.state(), p.region(), n, p.colors().length == 1);
      for (int c : p.colors()) f.color(c);
      for (int i = 0; i < n; i++) f.point(p.pos()[2 * i], p.pos()[2 * i + 1]);
    }
    return f;
  }

  private int[] regions(Random r, int count) {
    int[] ids = new int[count];
    for (int i = 0; i < count; i++) {
      int kind = i % 3;
      int n = kind == DrawDefs.QUAD ? 4 : 3 + r.nextInt(6);
      float[] uv = new float[2 * n];
      for (int k = 0; k < uv.length; k++) uv[k] = r.nextFloat();
      short[] idx = null;
      if (kind == DrawDefs.TRIANGLES) {
        idx = new short[3 * (n - 2)];
        for (int k = 0; k < idx.length; k++) idx[k] = (short) r.nextInt(n);
      }
      ids[i] = defs.region(-1, kind, n, uv, 0, 2, idx);
    }
    return ids;
  }

  private int[] states(Random r, int count) {
    int[] ids = new int[count];
    for (int i = 0; i < count; i++) {
      ids[i] =
          defs.state(
              i % 5 != 0,
              GL20.GL_ONE,
              i % 2 == 0 ? GL20.GL_ONE_MINUS_SRC_ALPHA : GL20.GL_ONE,
              GL20.GL_ONE,
              GL20.GL_ONE_MINUS_SRC_ALPHA,
              i % 4,
              r.nextFloat(),
              r.nextFloat(),
              r.nextFloat(),
              i % 4 == DrawDefs.SHADER_HUD ? r.nextFloat() : 0);
    }
    return ids;
  }

  private Poly random(Random r, int[] regionIds, int[] stateIds) {
    int region = regionIds[r.nextInt(regionIds.length)];
    int n = defs.region(region).verts();
    int[] colors = new int[r.nextInt(4) == 0 ? n : 1];
    for (int k = 0; k < colors.length; k++) colors[k] = r.nextInt();
    int[] pos = new int[2 * n];
    int x = r.nextInt(720 * 16 + 4000) - 2000, y = r.nextInt(1560 * 16 + 4000) - 2000;
    for (int k = 0; k < n; k++) {
      pos[2 * k] = x + r.nextInt(2000) - 1000;
      pos[2 * k + 1] = y + r.nextInt(2000) - 1000;
    }
    return new Poly(stateIds[r.nextInt(stateIds.length)], region, colors, pos);
  }

  /** The next frame of a scene: most polygons as they were, some moved, changed, added, gone. */
  private List<Poly> next(Random r, List<Poly> polys, int[] regionIds, int[] stateIds) {
    List<Poly> out = new ArrayList<>();
    for (Poly p : polys) {
      int roll = r.nextInt(100);
      if (roll < 3) continue; // gone
      if (roll < 6) out.add(random(r, regionIds, stateIds)); // something new before it
      int[] pos = p.pos().clone();
      int[] colors = p.colors();
      int state = p.state();
      if (roll >= 50 && roll < 75) {
        // moved
        int dx = r.nextInt(64) - 32, dy = r.nextInt(64) - 32;
        for (int k = 0; k < pos.length; k += 2) {
          pos[k] += dx;
          pos[k + 1] += dy;
        }
      } else if (roll >= 75 && roll < 85) {
        // turned or scaled: every vertex its own way
        for (int k = 0; k < pos.length; k++) pos[k] += r.nextInt(9) - 4;
      } else if (roll >= 85 && roll < 90) {
        colors = new int[r.nextBoolean() ? 1 : pos.length / 2];
        for (int k = 0; k < colors.length; k++) colors[k] = r.nextInt();
      } else if (roll >= 90 && roll < 93) {
        state = stateIds[r.nextInt(stateIds.length)];
      }
      out.add(new Poly(state, p.region(), colors, pos));
    }
    // a few swapped in draw order (units passing each other)
    for (int k = 0; k < 3 && out.size() > 2; k++) {
      int a = r.nextInt(out.size()), b = r.nextInt(out.size());
      Poly t = out.get(a);
      out.set(a, out.get(b));
      out.set(b, t);
    }
    return out;
  }

  @Test
  void framesDecodeToWhatWasEncoded() throws Exception {
    Random r = new Random(42);
    int[] regionIds = regions(r, 60);
    int[] stateIds = states(r, 12);
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6);
    DrawCodec.Decoder decoder = new DrawCodec.Decoder();
    List<Poly> polys = new ArrayList<>();
    for (int i = 0; i < 400; i++) polys.add(random(r, regionIds, stateIds));
    long keyBytes = 0, deltaBytes = 0;
    int deltas = 0;
    for (int k = 0; k < 120; k++) {
      DrawFrame f = frame(polys);
      boolean key = k % 50 == 0;
      byte[] packed = encoder.encode(f, key);
      if (key) keyBytes += packed.length;
      else {
        deltaBytes += packed.length;
        deltas++;
      }
      DrawFrame back = decoder.decode(packed);
      assertThat(back.sameAs(f)).as("frame %d", k).isTrue();
      polys = next(r, polys, regionIds, stateIds);
    }
    // the deltas (half the polygons moved or changed a frame here) are smaller than a key frame
    assertThat(deltaBytes / deltas).isLessThan(keyBytes / 3);
    encoder.dispose();
  }

  @Test
  void anUnchangedFrameIsAFewBytes() throws Exception {
    Random r = new Random(7);
    int[] regionIds = regions(r, 20);
    int[] stateIds = states(r, 4);
    List<Poly> polys = new ArrayList<>();
    for (int i = 0; i < 1000; i++) polys.add(random(r, regionIds, stateIds));
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6);
    DrawCodec.Decoder decoder = new DrawCodec.Decoder();
    decoder.decode(encoder.encode(frame(polys), false));
    byte[] same = encoder.encode(frame(polys), false);
    assertThat(same.length).isLessThan(32);
    assertThat(decoder.decode(same).sameAs(frame(polys))).isTrue();
  }

  @Test
  void definitionsAreSentOnceAndAgainAfterAReset() throws Exception {
    Random r = new Random(3);
    int[] regionIds = regions(r, 10);
    int[] stateIds = states(r, 3);
    List<Poly> polys = new ArrayList<>();
    for (int i = 0; i < 50; i++) polys.add(random(r, regionIds, stateIds));
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6);
    DrawCodec.Decoder decoder = new DrawCodec.Decoder();
    decoder.decode(encoder.encode(frame(polys), true));
    int known = decoder.regions.size();
    assertThat(known).isPositive();
    // a key frame later needs no definitions again: a decoder that only saw the first has them
    byte[] key = encoder.encode(frame(polys), true);
    assertThat(decoder.decode(key).sameAs(frame(polys))).isTrue();
    // a new page (reconnected) gets an encoder of its own, which sends them all
    DrawCodec.Encoder fresh = new DrawCodec.Encoder(defs, 6);
    DrawCodec.Decoder page = new DrawCodec.Decoder();
    assertThat(page.decode(fresh.encode(frame(polys), false)).sameAs(frame(polys))).isTrue();
    assertThat(page.regions).hasSize(known);
  }

  @Test
  void skippedFramesDoNotBreakTheChain() throws Exception {
    // the server skips a slow page's frames: each one it sends is coded against the last it sent
    Random r = new Random(11);
    int[] regionIds = regions(r, 30);
    int[] stateIds = states(r, 6);
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6);
    DrawCodec.Decoder decoder = new DrawCodec.Decoder();
    List<Poly> polys = new ArrayList<>();
    for (int i = 0; i < 200; i++) polys.add(random(r, regionIds, stateIds));
    for (int k = 0; k < 60; k++) {
      DrawFrame f = frame(polys);
      if (k % 3 != 1) assertThat(decoder.decode(encoder.encode(f, false)).sameAs(f)).isTrue();
      polys = next(r, polys, regionIds, stateIds);
    }
  }

  @Test
  void regionsArePackedIntoPagesTexelForTexel() throws Exception {
    // a 64x32 source: each texel's colour names its place
    int w = 64, h = 32;
    byte[] rgba = new byte[w * h * 4];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int o = (y * w + x) * 4;
        rgba[o] = (byte) x;
        rgba[o + 1] = (byte) y;
        rgba[o + 2] = 7;
        rgba[o + 3] = (byte) 255;
      }
    }
    int src = store.source("test", w, h, null, rgba);
    // a quad over texels 10..20 x 4..12
    float[] uv = {10f / w, 4f / h, 20f / w, 4f / h, 20f / w, 12f / h, 10f / w, 12f / h};
    TextureStore.Placed a = store.place(src, uv, 0, 2, 4);
    TextureStore.Patch patch = store.patch(a.patch());
    TextureStore.Page page = store.page(a.page());
    assertThat(page.width).isEqualTo(TextureStore.PAGE);
    assertThat(page.plain).isTrue();
    // the patch holds the region and its margin, on the 4 texel grid
    assertThat(patch.sx()).isLessThanOrEqualTo(10 - TextureStore.PAD);
    assertThat(patch.sy()).isEqualTo(0);
    assertThat(patch.sx() + patch.w()).isGreaterThanOrEqualTo(20 + TextureStore.PAD);
    // the page's coordinates land on the same texels
    for (int i = 0; i < 4; i++) {
      double px = a.uv()[2 * i] * page.width - patch.x() + patch.sx();
      double py = a.uv()[2 * i + 1] * page.height - patch.y() + patch.sy();
      assertThat(px).isCloseTo(uv[2 * i] * w, org.assertj.core.data.Offset.offset(1e-3));
      assertThat(py).isCloseTo(uv[2 * i + 1] * h, org.assertj.core.data.Offset.offset(1e-3));
    }
    // and the patch's pixels are the source's
    var img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(store.png(patch)));
    assertThat(img.getWidth()).isEqualTo(patch.w());
    int argb = img.getRGB(3, 5);
    assertThat((argb >> 16) & 0xFF).isEqualTo(patch.sx() + 3);
    assertThat((argb >> 8) & 0xFF).isEqualTo(patch.sy() + 5);
    // a region inside that patch reuses it; one elsewhere gets a patch of its own
    float[] inside = {12f / w, 5f / h, 14f / w, 5f / h, 14f / w, 7f / h, 12f / w, 7f / h};
    assertThat(store.place(src, inside, 0, 2, 4).patch()).isEqualTo(a.patch());
    float[] far = {50f / w, 20f / h, 60f / w, 20f / h, 60f / w, 30f / h, 50f / w, 30f / h};
    TextureStore.Placed b = store.place(src, far, 0, 2, 4);
    assertThat(b.patch()).isNotEqualTo(a.patch());
    TextureStore.Patch pb = store.patch(b.patch());
    // packed side by side, not overlapping
    boolean apart =
        pb.x() >= patch.x() + patch.w()
            || patch.x() >= pb.x() + pb.w()
            || pb.y() >= patch.y() + patch.h()
            || patch.y() >= pb.y() + pb.h();
    assertThat(apart).isTrue();
  }
}
