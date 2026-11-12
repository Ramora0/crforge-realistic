/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The definitions draw lists refer to by number, interned once for the server's life: the regions
 * of textures polygons are cut from and the GL states they are drawn in. A page is sent each
 * definition once, with the first frame that uses it ({@link DrawCodec}). Appended to on the GL
 * thread, read by the encoders' threads.
 */
final class DrawDefs {

  /** A triangle strip: triangles (i, i+1, i+2), as {@code ScDrawer} cuts its shapes. */
  static final int STRIP = 0;

  /** A quad of two triangles (0, 1, 2) and (2, 3, 0), as sprites and glyphs are drawn. */
  static final int QUAD = 1;

  /** Any triangles, by their vertex indices. */
  static final int TRIANGLES = 2;

  /** The libgdx default shader: texture times vertex colour. */
  static final int SHADER_PLAIN = 0;

  /** Texture times vertex colour plus an added colour ({@code u_add}; ScDrawer, EffectDrawer). */
  static final int SHADER_ADD = 1;

  /** As {@link #SHADER_ADD}, clamped to the alpha and greyed by {@code u_gray} (HudArt). */
  static final int SHADER_HUD = 2;

  /** The texture's coverage times the vertex colour: a shadow (ScDrawer's silhouette). */
  static final int SHADER_SILHOUETTE = 3;

  /**
   * A polygon's texture (a page of {@link TextureStore}), triangulation and texture coordinates in
   * the page.
   *
   * @param uv u, v a vertex
   * @param indices the triangles' vertex indices for {@link #TRIANGLES}, else null
   * @param patch the texture patch holding its texels ({@link TextureStore#patch}), -1 for none
   */
  record Region(int id, int texture, int kind, int verts, float[] uv, short[] indices, int patch) {}

  /**
   * How a polygon is drawn: blending (glBlendFuncSeparate's four factors, or none) and the shader
   * with its uniforms.
   */
  record State(
      int id,
      boolean blend,
      int src,
      int dst,
      int srcAlpha,
      int dstAlpha,
      int shader,
      float addR,
      float addG,
      float addB,
      float gray) {}

  private record RegionKey(int texture, int kind, int[] bits, int hash) {
    @Override
    public boolean equals(Object o) {
      return o instanceof RegionKey k
          && k.hash == hash
          && k.texture == texture
          && k.kind == kind
          && Arrays.equals(k.bits, bits);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  private record StateKey(
      boolean blend,
      int src,
      int dst,
      int srcAlpha,
      int dstAlpha,
      int shader,
      float addR,
      float addG,
      float addB,
      float gray) {}

  private final TextureStore textures;
  private final List<Region> regions = new ArrayList<>();
  private final List<State> states = new ArrayList<>();
  private final Map<RegionKey, Integer> regionIds = new HashMap<>();
  private final Map<StateKey, Integer> stateIds = new HashMap<>();

  DrawDefs(TextureStore textures) {
    this.textures = textures;
  }

  TextureStore textures() {
    return textures;
  }

  /**
   * The id of a region of a source texture ({@link TextureStore#source}, -1 for none: the white
   * texel), interned: {@code uv} holds {@code verts} u, v pairs from {@code uvAt}, and {@code
   * indices} (for {@link #TRIANGLES}) the triangles' local vertex indices. A new region is placed
   * in a page ({@link TextureStore#place}); its definition holds the page and the coordinates
   * there.
   */
  synchronized int region(
      int texture, int kind, int verts, float[] uv, int uvAt, int uvStride, short[] indices) {
    int extra = indices == null ? 0 : indices.length;
    int[] bits = new int[2 * verts + extra + 1];
    bits[0] = verts;
    for (int i = 0; i < verts; i++) {
      bits[1 + 2 * i] = Float.floatToIntBits(uv[uvAt + i * uvStride]);
      bits[2 + 2 * i] = Float.floatToIntBits(uv[uvAt + i * uvStride + 1]);
    }
    for (int i = 0; i < extra; i++) bits[1 + 2 * verts + i] = indices[i];
    int hash = (Arrays.hashCode(bits) * 31 + texture) * 31 + kind;
    RegionKey key = new RegionKey(texture, kind, bits, hash);
    Integer id = regionIds.get(key);
    if (id != null) return id;
    int next = regions.size();
    TextureStore.Placed placed = textures.place(texture, uv, uvAt, uvStride, verts);
    regions.add(
        new Region(
            next,
            placed.page(),
            kind,
            verts,
            placed.uv(),
            indices == null ? null : indices.clone(),
            placed.patch()));
    regionIds.put(key, next);
    return next;
  }

  synchronized int state(
      boolean blend,
      int src,
      int dst,
      int srcAlpha,
      int dstAlpha,
      int shader,
      float addR,
      float addG,
      float addB,
      float gray) {
    StateKey key =
        new StateKey(blend, src, dst, srcAlpha, dstAlpha, shader, addR, addG, addB, gray);
    Integer id = stateIds.get(key);
    if (id != null) return id;
    int next = states.size();
    states.add(
        new State(next, blend, src, dst, srcAlpha, dstAlpha, shader, addR, addG, addB, gray));
    stateIds.put(key, next);
    return next;
  }

  synchronized Region region(int id) {
    return regions.get(id);
  }

  synchronized State state(int id) {
    return states.get(id);
  }

  synchronized int regionCount() {
    return regions.size();
  }

  synchronized int stateCount() {
    return states.size();
  }
}
