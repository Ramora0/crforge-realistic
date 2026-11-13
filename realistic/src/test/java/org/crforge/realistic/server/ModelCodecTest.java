/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The draw lists' 3D model draws ({@link ModelInstance}): what the encoder sends, the decoder makes
 * again, exactly, through part changes (mounts, form swaps), cross-fades, glows, frames skipped and
 * key frames; and a battle's worth of them costs a few bytes each.
 */
class ModelCodecTest {

  private final DrawDefs defs = new DrawDefs(new TextureStore(null));
  private final ModelAssets assets = new ModelAssets(null);

  private int[] refs(Random r, int n) {
    int[] ids = new int[n];
    for (int i = 0; i < n; i++) {
      float[] t = new float[16];
      for (int k = 0; k < 16; k++) t[k] = r.nextFloat();
      int[] skip = i % 3 == 0 ? new int[] {1, 4} : new int[0];
      ids[i] = assets.ref("asset" + (i % 4), t, i % 2 == 0, skip);
    }
    return ids;
  }

  private ModelInstance random(Random r, int[] refIds) {
    ModelInstance m = new ModelInstance();
    int parts = 1 + (r.nextInt(4) == 0 ? 1 : 0);
    m.parts(parts);
    for (int p = 0; p < parts; p++) {
      m.refs[p] = refIds[r.nextInt(refIds.length)];
      m.host[p] = p == 0 ? -1 : 0;
      m.hostNode[p] = p == 0 ? -1 : r.nextInt(80);
      m.clip[p] = r.nextInt(12);
      m.time[p] = r.nextInt(20_000);
      m.blend[p] = 65535;
    }
    m.shadow = r.nextBoolean();
    m.x = r.nextInt(720 * 16);
    m.y = r.nextInt(1560 * 16);
    m.kx = 27.5f;
    m.ky = 27.5f;
    m.heading = r.nextFloat() * 6f;
    m.blue = r.nextBoolean();
    m.clipPlane = r.nextBoolean();
    m.colorMul = new float[] {1, 1, 1, 1};
    m.light = new float[] {0.1f, 0.2f, 0.97f};
    m.rect = new int[] {m.x - 900, m.y - 300, m.x + 900, m.y + 2000};
    m.drawn = m.rect.clone();
    if (m.shadow) m.tint = new float[] {0.1f, 0.1f, 0.1f, 0.4f};
    if (!m.shadow && r.nextInt(3) == 0) m.fx.add(effect(r, refIds));
    return m;
  }

  /** An effect draw on a model (an aura, a glow plane). */
  private ModelInstance.Fx effect(Random r, int[] refIds) {
    float[] mat = new float[16];
    for (int k = 0; k < 16; k++) mat[k] = r.nextFloat();
    int shader = r.nextInt(3);
    float[] u = new float[shader == 2 ? 10 : 50];
    for (int k = 0; k < u.length; k++) u[k] = r.nextFloat();
    String[] tex = {"t" + r.nextInt(5), null, null, r.nextBoolean() ? "d1" : null, null};
    return new ModelInstance.Fx(
        refIds[r.nextInt(refIds.length)], mat, shader, r.nextBoolean(), r.nextBoolean(), u, tex);
  }

  /** The next frame's draw: a clip on, sometimes a cross-fade, a move, a squash, a glow. */
  private ModelInstance next(Random r, ModelInstance o, int[] refIds) {
    ModelInstance m = o.copy();
    for (int p = 0; p < m.parts(); p++) {
      m.time[p] += 136;
      if (r.nextInt(20) == 0) {
        m.fromClip[p] = m.clip[p];
        m.fromTime[p] = m.time[p];
        m.clip[p] = r.nextInt(12) + 1;
        m.time[p] = 0;
        m.blend[p] = 6000;
      } else if (m.blend[p] < 65535) {
        m.blend[p] = Math.min(65535, m.blend[p] + 9000);
        m.fromTime[p] += 136;
        if (m.blend[p] == 65535) m.fromClip[p] = m.fromTime[p] = 0;
      }
    }
    int dx = r.nextInt(40) - 20, dy = r.nextInt(40) - 20;
    m.x += dx;
    m.y += dy;
    for (int i = 0; i < 4; i++) {
      m.rect[i] += (i % 2 == 0 ? dx : dy) + (r.nextInt(5) == 0 ? r.nextInt(30) - 15 : 0);
      m.drawn[i] = m.rect[i];
    }
    if (r.nextInt(30) == 0) m.drawn[3] += 400; // a deploy squash
    if (r.nextInt(25) == 0) m.heading += 0.3f;
    if (!m.fx.isEmpty()) {
      // the effect's timeline moves it; now and then it ends
      m.fxTime += 136;
      ModelInstance.Fx e = m.fx.get(0);
      float[] mat = e.matrix().clone();
      mat[12] += 0.01f;
      m.fx =
          new ArrayList<>(
              List.of(
                  new ModelInstance.Fx(
                      e.ref(),
                      mat,
                      e.shader(),
                      e.additive(),
                      e.billboard(),
                      e.uniforms(),
                      e.textures())));
      if (r.nextInt(30) == 0) m.fx = new ArrayList<>();
    } else if (!m.shadow && r.nextInt(50) == 0) {
      m.fx = new ArrayList<>(List.of(effect(r, refIds)));
    }
    if (r.nextInt(40) == 0) m.colorMul[3] = r.nextFloat();
    if (r.nextInt(40) == 0) {
      // a glow on a primitive, or off again
      float[][] g = new float[6][];
      if (r.nextBoolean()) {
        g[2] = new float[ModelInstance.GLOW_FLOATS];
        for (int k = 0; k < g[2].length; k++) g[2][k] = r.nextFloat();
      }
      m.glows[0] = r.nextBoolean() ? g : null;
    }
    if (r.nextInt(60) == 0) {
      // a form swap or a dismount: the parts change
      ModelInstance swap = random(r, refIds);
      m.parts(swap.parts());
      System.arraycopy(swap.refs, 0, m.refs, 0, m.refs.length);
      System.arraycopy(swap.host, 0, m.host, 0, m.host.length);
      System.arraycopy(swap.hostNode, 0, m.hostNode, 0, m.hostNode.length);
      System.arraycopy(swap.clip, 0, m.clip, 0, m.clip.length);
      System.arraycopy(swap.time, 0, m.time, 0, m.time.length);
      System.arraycopy(swap.blend, 0, m.blend, 0, m.blend.length);
    }
    return m;
  }

  private DrawFrame frame(List<ModelInstance> models, int polygons) {
    DrawFrame f = new DrawFrame();
    // a few polygons for the draws to sit among
    int region = defs.region(-1, DrawDefs.QUAD, 4, new float[8], 0, 2, null);
    int state =
        defs.state(
            true,
            com.badlogic.gdx.graphics.GL20.GL_ONE,
            com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA,
            com.badlogic.gdx.graphics.GL20.GL_ONE,
            com.badlogic.gdx.graphics.GL20.GL_ONE_MINUS_SRC_ALPHA,
            DrawDefs.SHADER_PLAIN,
            0,
            0,
            0,
            0);
    for (int i = 0; i < polygons; i++) {
      f.begin(state, region, 4, true);
      f.color(-1);
      for (int k = 0; k < 4; k++) f.point(i * 10 + k, k);
    }
    int slot = 0;
    for (int i = 0; i < models.size(); i++) {
      ModelInstance m = models.get(i).copy();
      slot = Math.min(polygons, slot + (i % 3 == 0 ? 2 : 0));
      m.slot = slot;
      f.models.add(m);
    }
    return f;
  }

  @Test
  void modelDrawsDecodeToWhatWasEncoded() throws Exception {
    Random r = new Random(5);
    int[] refIds = refs(r, 9);
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6).models(assets);
    DrawCodec.Decoder decoder = new DrawCodec.Decoder();
    List<ModelInstance> models = new ArrayList<>();
    for (int i = 0; i < 24; i++) models.add(random(r, refIds));
    long bytes = 0;
    int counted = 0;
    for (int k = 0; k < 300; k++) {
      DrawFrame f = frame(models, 40);
      byte[] packed = encoder.encode(f, k % 100 == 0);
      DrawFrame back = decoder.decode(packed);
      assertThat(back.models).as("frame %d", k).isEqualTo(f.models);
      assertThat(back.sameAs(f)).as("frame %d", k).isTrue();
      if (k % 100 != 0) {
        bytes += encoder.modelBytes;
        counted++;
      }
      List<ModelInstance> after = new ArrayList<>();
      for (ModelInstance m : models) {
        if (r.nextInt(150) == 0) continue; // died
        after.add(next(r, m, refIds));
      }
      if (r.nextInt(10) == 0) after.add(r.nextInt(after.size() + 1), random(r, refIds));
      // units passing each other: the draw order changes
      if (after.size() > 2 && r.nextInt(5) == 0) {
        int a = r.nextInt(after.size()), b = r.nextInt(after.size());
        ModelInstance t = after.get(a);
        after.set(a, after.get(b));
        after.set(b, t);
      }
      models = after;
    }
    // the refs reached the decoder once each, with their definitions
    for (int id : refIds) {
      if (decoder.modelRefs.containsKey(id)) {
        assertThat(decoder.modelRefs.get(id).transform()).isEqualTo(assets.ref(id).transform());
        assertThat(decoder.modelRefs.get(id).skip()).isEqualTo(assets.ref(id).skip());
        assertThat(decoder.modelRefs.get(id).asset()).isEqualTo(assets.ref(id).asset());
      }
    }
    // a moving, animating draw costs a few bytes before deflate (and an effect a matrix more)
    assertThat(bytes / (double) counted / 24).isLessThan(60);
    encoder.dispose();
  }

  @Test
  void skippedFramesAndKeyFramesKeepTheChain() throws Exception {
    Random r = new Random(9);
    int[] refIds = refs(r, 5);
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6).models(assets);
    DrawCodec.Decoder decoder = new DrawCodec.Decoder();
    List<ModelInstance> models = new ArrayList<>();
    for (int i = 0; i < 6; i++) models.add(random(r, refIds));
    for (int k = 0; k < 80; k++) {
      DrawFrame f = frame(models, 10);
      // the server skips a slow page's frames: what it sends is coded against what it sent last
      if (k % 4 != 3) {
        assertThat(decoder.decode(encoder.encode(f, k % 17 == 0)).models).isEqualTo(f.models);
      }
      List<ModelInstance> after = new ArrayList<>();
      for (ModelInstance m : models) after.add(next(r, m, refIds));
      models = after;
    }
  }

  @Test
  void aFrameWithoutModelsAfterOneWithThemHasNone() throws Exception {
    Random r = new Random(1);
    int[] refIds = refs(r, 2);
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6).models(assets);
    DrawCodec.Decoder decoder = new DrawCodec.Decoder();
    assertThat(decoder.decode(encoder.encode(frame(List.of(random(r, refIds)), 3), false)).models)
        .hasSize(1);
    assertThat(decoder.decode(encoder.encode(frame(List.of(), 3), false)).models).isEmpty();
  }
}
