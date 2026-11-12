/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.util.Arrays;

/**
 * One frame's draw list, in draw order: each polygon is a region of a texture ({@link
 * DrawDefs.Region}: its texture, triangulation and texture coordinates), drawn in a GL state
 * ({@link DrawDefs.State}: blending and shader), with its vertex colours (one for the polygon, or
 * one a vertex) and its vertex positions in frame pixels times {@link #SUBPIXELS}, y up (GL's
 * framebuffer convention, as the server's frame buffer is drawn).
 */
final class DrawFrame {

  /** Positions are in 1/16 of a frame pixel. */
  static final int SUBPIXELS = 16;

  int count;
  int[] state = new int[256];
  int[] region = new int[256];
  int[] verts = new int[256];

  /** Where the polygon's colours start in {@link #colors}; one colour or one a vertex. */
  int[] colorAt = new int[256];

  boolean[] oneColor = new boolean[256];

  /** Where the polygon's positions (x, y pairs) start in {@link #pos}. */
  int[] posAt = new int[256];

  int[] colors = new int[1024];
  int colorLen;
  int[] pos = new int[4096];
  int posLen;

  /** Polygons the recorder saw (before off-frame ones were culled). */
  int seen;

  /** Batch flushes (GL draw calls the server's own renderer made). */
  int flushes;

  void clear() {
    count = 0;
    colorLen = 0;
    posLen = 0;
    seen = 0;
    flushes = 0;
  }

  /** Starts a polygon; its colours and positions follow ({@link #color}, {@link #point}). */
  void begin(int stateId, int regionId, int n, boolean uniformColor) {
    if (count == state.length) {
      int c = count * 2;
      state = Arrays.copyOf(state, c);
      region = Arrays.copyOf(region, c);
      verts = Arrays.copyOf(verts, c);
      colorAt = Arrays.copyOf(colorAt, c);
      oneColor = Arrays.copyOf(oneColor, c);
      posAt = Arrays.copyOf(posAt, c);
    }
    state[count] = stateId;
    region[count] = regionId;
    verts[count] = n;
    oneColor[count] = uniformColor;
    colorAt[count] = colorLen;
    posAt[count] = posLen;
    count++;
    int needColors = colorLen + (uniformColor ? 1 : n);
    if (needColors > colors.length)
      colors = Arrays.copyOf(colors, Math.max(needColors, 2 * colors.length));
    int needPos = posLen + 2 * n;
    if (needPos > pos.length) pos = Arrays.copyOf(pos, Math.max(needPos, 2 * pos.length));
  }

  void color(int abgr) {
    colors[colorLen++] = abgr;
  }

  void point(int x, int y) {
    pos[posLen++] = x;
    pos[posLen++] = y;
  }

  /** Drops the polygon just begun (it fell outside the frame). */
  void dropLast() {
    count--;
    colorLen = colorAt[count];
    posLen = posAt[count];
  }

  int colorCount(int i) {
    return oneColor[i] ? 1 : verts[i];
  }

  /** Total vertices of the frame. */
  int vertexCount() {
    int n = 0;
    for (int i = 0; i < count; i++) n += verts[i];
    return n;
  }

  /** Whether polygon {@code i} of this frame and {@code k} of {@code o} have the same colours. */
  boolean sameColors(int i, DrawFrame o, int k) {
    if (oneColor[i] != o.oneColor[k] || verts[i] != o.verts[k]) return false;
    int n = colorCount(i);
    return Arrays.equals(
        colors, colorAt[i], colorAt[i] + n, o.colors, o.colorAt[k], o.colorAt[k] + n);
  }

  /** Whether polygon {@code i} of this frame and {@code k} of {@code o} are at the same place. */
  boolean samePositions(int i, DrawFrame o, int k) {
    int n = 2 * verts[i];
    return verts[i] == o.verts[k]
        && Arrays.equals(pos, posAt[i], posAt[i] + n, o.pos, o.posAt[k], o.posAt[k] + n);
  }

  /** Whether the two frames hold the same polygons (the codec's round trip). */
  boolean sameAs(DrawFrame o) {
    if (count != o.count) return false;
    for (int i = 0; i < count; i++) {
      if (state[i] != o.state[i] || region[i] != o.region[i]) return false;
      if (!sameColors(i, o, i) || !samePositions(i, o, i)) return false;
    }
    return true;
  }

  DrawFrame copy() {
    DrawFrame f = new DrawFrame();
    f.count = count;
    f.state = Arrays.copyOf(state, Math.max(1, count));
    f.region = Arrays.copyOf(region, Math.max(1, count));
    f.verts = Arrays.copyOf(verts, Math.max(1, count));
    f.colorAt = Arrays.copyOf(colorAt, Math.max(1, count));
    f.oneColor = Arrays.copyOf(oneColor, Math.max(1, count));
    f.posAt = Arrays.copyOf(posAt, Math.max(1, count));
    f.colors = Arrays.copyOf(colors, Math.max(1, colorLen));
    f.colorLen = colorLen;
    f.pos = Arrays.copyOf(pos, Math.max(1, posLen));
    f.posLen = posLen;
    f.seen = seen;
    f.flushes = flushes;
    return f;
  }
}
