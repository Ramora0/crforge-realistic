/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.util.Arrays;

/**
 * One 3D model draw of a draw list (docs/phone-server.md, "3D models on the phone"): the page
 * renders the model itself, posed by the clip samples the server's state machines chose, into an
 * image of the rectangle {@link #rect} and composites it at {@link #slot} in the frame's draw
 * order, as the server's renderer composites its own image. A shadow draw is the model's planar
 * shadow mask, composited in {@link #tint}. Positions are in frame pixels times {@link
 * DrawFrame#SUBPIXELS}, y up.
 */
final class ModelInstance {

  /** Clip times are sent in 1/4096 s. */
  static final float TIME_SCALE = 4096f;

  /** The glow uniforms a primitive may carry ({@code UberPbr.setGlow}). */
  static final int GLOW_FLOATS = 14;

  /** The polygons of the frame drawn before it. */
  int slot;

  /** The planar shadow mask rather than the lit model. */
  boolean shadow;

  /** Per part: its model ref ({@link ModelAssets}). */
  int[] refs = new int[0];

  /** Per part: the earlier part it rides, or -1. */
  int[] host = new int[0];

  /** Per part: the node of its host it sits at, or -1 (then placed as an unmounted part). */
  int[] hostNode = new int[0];

  /** Per part: its clip (the binary's clip index plus one; 0 for the rest pose). */
  int[] clip = new int[0];

  /** Per part: the time into the clip, in 1/{@link #TIME_SCALE} s. */
  int[] time = new int[0];

  /** Per part: the clip cross-faded from (0 none) and its time. */
  int[] fromClip = new int[0];

  int[] fromTime = new int[0];

  /** Per part: the clip's weight against the one faded from, 0..65535. */
  int[] blend = new int[0];

  /** Per part: per primitive, its glow uniforms or null; null for a part without. */
  float[][][] glows = new float[0][][];

  /** The ground point (lifted, as the model is drawn), subpixels. */
  int x;

  int y;

  /** Frame pixels per prefab metre across (screen x is prefab -x) and up the screen. */
  float kx;

  float ky;

  /** The turn about prefab z, radians. */
  float heading;

  /** Drawn in the blue side's team colour (else red). */
  boolean blue;

  /** The prefab's clipping plane (z = 0) on. */
  boolean clipPlane;

  /** The object colour multiply (rgba) and add (rgb). */
  float[] colorMul = new float[4];

  float[] colorAdd = new float[3];

  /** Towards the light (the lit model), or the shadow's line (a shadow). */
  float[] light = new float[3];

  /** The rectangle the image is rendered over: x0, y0, x1, y1, subpixels. */
  int[] rect = new int[4];

  /** The rectangle it is composited over (a deploy filter's squash), subpixels. */
  int[] drawn = new int[4];

  /** A shadow's premultiplied colour (r, g, b, a). */
  float[] tint = new float[4];

  /**
   * An effect draw into the model's image after its lit parts ({@code FxRenderer}): an effect model
   * (a ref), its matrix in prefab space, its shader (0 {@code vfx_mask}, 1 {@code vfx_mask2}, 2
   * {@code vfx_fresnel_simple}), additive or blended, a billboard or not, its uniforms and its five
   * textures' ids (null for white).
   */
  record Fx(
      int ref,
      float[] matrix,
      int shader,
      boolean additive,
      boolean billboard,
      float[] uniforms,
      String[] textures) {

    @Override
    public boolean equals(Object other) {
      return other instanceof Fx o
          && ref == o.ref
          && Arrays.equals(matrix, o.matrix)
          && shader == o.shader
          && additive == o.additive
          && billboard == o.billboard
          && Arrays.equals(uniforms, o.uniforms)
          && Arrays.equals(textures, o.textures);
    }

    @Override
    public int hashCode() {
      return ref * 31 + Arrays.hashCode(matrix);
    }

    /** The same draw at another matrix. */
    Fx withMatrix(float[] m) {
      return new Fx(ref, m, shader, additive, billboard, uniforms, textures);
    }
  }

  /** The effect draws, in draw order. */
  java.util.List<Fx> fx = new java.util.ArrayList<>();

  /** The effect clock, in 1/{@link #TIME_SCALE} s. */
  int fxTime;

  int parts() {
    return refs.length;
  }

  /** Sizes the per-part arrays for {@code n} parts (contents cleared). */
  void parts(int n) {
    refs = new int[n];
    host = new int[n];
    hostNode = new int[n];
    clip = new int[n];
    time = new int[n];
    fromClip = new int[n];
    fromTime = new int[n];
    blend = new int[n];
    glows = new float[n][][];
  }

  ModelInstance copy() {
    ModelInstance m = new ModelInstance();
    m.slot = slot;
    m.shadow = shadow;
    m.refs = refs.clone();
    m.host = host.clone();
    m.hostNode = hostNode.clone();
    m.clip = clip.clone();
    m.time = time.clone();
    m.fromClip = fromClip.clone();
    m.fromTime = fromTime.clone();
    m.blend = blend.clone();
    m.glows = new float[glows.length][][];
    for (int i = 0; i < glows.length; i++) {
      if (glows[i] == null) continue;
      m.glows[i] = new float[glows[i].length][];
      for (int k = 0; k < glows[i].length; k++) {
        m.glows[i][k] = glows[i][k] == null ? null : glows[i][k].clone();
      }
    }
    m.x = x;
    m.y = y;
    m.kx = kx;
    m.ky = ky;
    m.heading = heading;
    m.blue = blue;
    m.clipPlane = clipPlane;
    m.colorMul = colorMul.clone();
    m.colorAdd = colorAdd.clone();
    m.light = light.clone();
    m.rect = rect.clone();
    m.drawn = drawn.clone();
    m.tint = tint.clone();
    m.fx = new java.util.ArrayList<>(fx);
    m.fxTime = fxTime;
    return m;
  }

  /** Whether the two part structures (refs, mounts) are the same. */
  boolean sameParts(ModelInstance o) {
    return Arrays.equals(refs, o.refs)
        && Arrays.equals(host, o.host)
        && Arrays.equals(hostNode, o.hostNode);
  }

  boolean sameGlows(ModelInstance o) {
    if (glows.length != o.glows.length) return false;
    for (int i = 0; i < glows.length; i++)
      if (!Arrays.deepEquals(glows[i], o.glows[i])) return false;
    return true;
  }

  @Override
  public boolean equals(Object other) {
    if (!(other instanceof ModelInstance o)) return false;
    return slot == o.slot
        && shadow == o.shadow
        && sameParts(o)
        && Arrays.equals(clip, o.clip)
        && Arrays.equals(time, o.time)
        && Arrays.equals(fromClip, o.fromClip)
        && Arrays.equals(fromTime, o.fromTime)
        && Arrays.equals(blend, o.blend)
        && sameGlows(o)
        && x == o.x
        && y == o.y
        && kx == o.kx
        && ky == o.ky
        && heading == o.heading
        && blue == o.blue
        && clipPlane == o.clipPlane
        && Arrays.equals(colorMul, o.colorMul)
        && Arrays.equals(colorAdd, o.colorAdd)
        && Arrays.equals(light, o.light)
        && Arrays.equals(rect, o.rect)
        && Arrays.equals(drawn, o.drawn)
        && Arrays.equals(tint, o.tint)
        && fx.equals(o.fx)
        && fxTime == o.fxTime;
  }

  @Override
  public int hashCode() {
    return Arrays.hashCode(refs) * 31 + x * 7 + y;
  }

  @Override
  public String toString() {
    return "ModelInstance[slot "
        + slot
        + (shadow ? " shadow" : "")
        + " refs "
        + Arrays.toString(refs)
        + " at "
        + x
        + ","
        + y
        + " clips "
        + Arrays.toString(clip)
        + " t "
        + Arrays.toString(time)
        + "]";
  }
}
