/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import java.util.List;

/**
 * Poses a {@link Glb} model: samples its clips into each node's local translation, rotation and
 * scale (linear, rotations by normalized lerp on the shorter arc, step channels held), optionally
 * blends two clips (the state machine's cross-fades), composes the node tree in prefab space and
 * skins the mesh on the CPU (four weighted joints per vertex). Matrices are column-major 4x4.
 */
public final class Rig {

  private final Glb glb;
  private final int nodeCount;
  private final float[] t;
  private final float[] r;
  private final float[] s;
  private final float[] t2;
  private final float[] r2;
  private final float[] s2;
  private final float[] world;
  private final boolean[] done;
  private final float[] jointMatrices;

  public Rig(Glb glb) {
    this.glb = glb;
    nodeCount = glb.nodes().size();
    t = new float[nodeCount * 3];
    r = new float[nodeCount * 4];
    s = new float[nodeCount * 3];
    t2 = new float[nodeCount * 3];
    r2 = new float[nodeCount * 4];
    s2 = new float[nodeCount * 3];
    world = new float[nodeCount * 16];
    done = new boolean[nodeCount];
    int joints = glb.skin() == null ? 0 : glb.skin().joints().length;
    jointMatrices = new float[joints * 16];
  }

  public Glb glb() {
    return glb;
  }

  /**
   * Poses the model at {@code seconds} into {@code clip} (null for the rest pose), cross-faded from
   * {@code from} at {@code fromSeconds} by {@code 1 - blend} ({@code blend} 1 is the clip alone).
   */
  public void pose(Glb.Clip clip, float seconds, Glb.Clip from, float fromSeconds, float blend) {
    rest(t, r, s);
    if (clip != null) sample(clip, seconds, t, r, s);
    if (from != null && blend < 1f) {
      rest(t2, r2, s2);
      sample(from, fromSeconds, t2, r2, s2);
      float a = Math.max(0f, Math.min(1f, blend));
      for (int i = 0; i < nodeCount * 3; i++) {
        t[i] = t2[i] + (t[i] - t2[i]) * a;
        s[i] = s2[i] + (s[i] - s2[i]) * a;
      }
      for (int n = 0; n < nodeCount; n++) nlerp(r2, r, n * 4, a, r);
    }
    java.util.Arrays.fill(done, false);
    for (int n = 0; n < nodeCount; n++) world(n);
    Glb.Skin skin = glb.skin();
    if (skin != null) {
      float[] m = new float[16];
      for (int j = 0; j < skin.joints().length; j++) {
        mul(world, skin.joints()[j] * 16, skin.inverseBind(), j * 16, m);
        System.arraycopy(m, 0, jointMatrices, j * 16, 16);
      }
    }
  }

  private void rest(float[] tt, float[] rr, float[] ss) {
    List<Glb.Node> nodes = glb.nodes();
    for (int n = 0; n < nodeCount; n++) {
      Glb.Node node = nodes.get(n);
      System.arraycopy(node.translation(), 0, tt, n * 3, 3);
      System.arraycopy(node.rotation(), 0, rr, n * 4, 4);
      System.arraycopy(node.scale(), 0, ss, n * 3, 3);
    }
  }

  /** Samples every channel of the clip at that time (clamped to the clip) into the arrays. */
  static void sample(Glb.Clip clip, float seconds, float[] tt, float[] rr, float[] ss) {
    for (Glb.Channel c : clip.channels()) {
      float[] times = c.times();
      int n =
          switch (c.path()) {
            case "rotation" -> 4;
            case "translation", "scale" -> 3;
            default -> 0;
          };
      if (n == 0) continue;
      float[] target = n == 4 ? rr : c.path().equals("scale") ? ss : tt;
      int o = c.node() * n;
      int k = 0;
      while (k + 1 < times.length && times[k + 1] <= seconds) k++;
      float f = 0;
      if (!c.step() && k + 1 < times.length && times[k + 1] > times[k] && seconds > times[k]) {
        f = Math.min(1f, (seconds - times[k]) / (times[k + 1] - times[k]));
      }
      int a = k * n;
      int b = Math.min(k + 1, times.length - 1) * n;
      float[] v = c.values();
      if (n == 4) {
        float dot = v[a] * v[b] + v[a + 1] * v[b + 1] + v[a + 2] * v[b + 2] + v[a + 3] * v[b + 3];
        float sign = dot < 0 ? -1f : 1f;
        float len = 0;
        for (int i = 0; i < 4; i++) {
          float q = v[a + i] + (sign * v[b + i] - v[a + i]) * f;
          target[o + i] = q;
          len += q * q;
        }
        len = (float) Math.sqrt(len);
        if (len > 0) for (int i = 0; i < 4; i++) target[o + i] /= len;
      } else {
        for (int i = 0; i < n; i++) target[o + i] = v[a + i] + (v[b + i] - v[a + i]) * f;
      }
    }
  }

  /** {@code out[o..]} = normalized lerp from {@code a[o..]} to {@code b[o..]} by {@code f}. */
  private static void nlerp(float[] a, float[] b, int o, float f, float[] out) {
    float dot = a[o] * b[o] + a[o + 1] * b[o + 1] + a[o + 2] * b[o + 2] + a[o + 3] * b[o + 3];
    float sign = dot < 0 ? -1f : 1f;
    float len = 0;
    float[] q = new float[4];
    for (int i = 0; i < 4; i++) {
      q[i] = a[o + i] + (sign * b[o + i] - a[o + i]) * f;
      len += q[i] * q[i];
    }
    len = (float) Math.sqrt(len);
    for (int i = 0; i < 4; i++) out[o + i] = len > 0 ? q[i] / len : q[i];
  }

  private void world(int n) {
    if (done[n]) return;
    float[] local = new float[16];
    trs(t, n * 3, r, n * 4, s, n * 3, local);
    int p = glb.parent(n);
    if (p >= 0) {
      world(p);
      float[] m = new float[16];
      mul(world, p * 16, local, 0, m);
      System.arraycopy(m, 0, world, n * 16, 16);
    } else {
      System.arraycopy(local, 0, world, n * 16, 16);
    }
    done[n] = true;
  }

  /** The node's matrix in prefab space after the last {@link #pose}. */
  public float[] nodeMatrix(int node) {
    float[] m = new float[16];
    System.arraycopy(world, node * 16, m, 0, 16);
    return m;
  }

  /**
   * The primitive's vertices skinned (or placed by their node) in prefab space after the last
   * {@link #pose}: positions, normals and tangents (xyz plus the handedness w) into the arrays.
   */
  public void skin(int primitive, float[] pos, float[] nrm, float[] tan) {
    Glb.Primitive p = glb.primitives().get(primitive);
    int count = p.vertexCount();
    boolean skinned = p.joints() != null && p.weights() != null && glb.skin() != null;
    float[] m = new float[16];
    float[] node = skinned ? null : nodeMatrix(glb.primitiveNode(primitive));
    for (int v = 0; v < count; v++) {
      if (skinned) {
        java.util.Arrays.fill(m, 0f);
        for (int k = 0; k < 4; k++) {
          float w = p.weights()[v * 4 + k];
          if (w == 0f) continue;
          int j = p.joints()[v * 4 + k] * 16;
          for (int i = 0; i < 16; i++) m[i] += w * jointMatrices[j + i];
        }
      } else {
        System.arraycopy(node, 0, m, 0, 16);
      }
      transformPoint(m, p.positions(), v * 3, pos, v * 3);
      if (p.normals() != null && nrm != null) {
        transformDirection(m, p.normals(), v * 3, nrm, v * 3);
      }
      if (p.tangents() != null && tan != null) {
        float[] tv = new float[3];
        transformDirection(m, p.tangents(), v * 4, tv, 0);
        tan[v * 4] = tv[0];
        tan[v * 4 + 1] = tv[1];
        tan[v * 4 + 2] = tv[2];
        tan[v * 4 + 3] = p.tangents()[v * 4 + 3];
      }
    }
  }

  // ------------------------------------------------------------------------------------ matrices

  /** A translation-rotation(quaternion xyzw)-scale matrix, column-major. */
  static void trs(float[] tt, int to, float[] rr, int ro, float[] ss, int so, float[] out) {
    float x = rr[ro], y = rr[ro + 1], z = rr[ro + 2], w = rr[ro + 3];
    float sx = ss[so], sy = ss[so + 1], sz = ss[so + 2];
    out[0] = (1 - 2 * (y * y + z * z)) * sx;
    out[1] = 2 * (x * y + z * w) * sx;
    out[2] = 2 * (x * z - y * w) * sx;
    out[3] = 0;
    out[4] = 2 * (x * y - z * w) * sy;
    out[5] = (1 - 2 * (x * x + z * z)) * sy;
    out[6] = 2 * (y * z + x * w) * sy;
    out[7] = 0;
    out[8] = 2 * (x * z + y * w) * sz;
    out[9] = 2 * (y * z - x * w) * sz;
    out[10] = (1 - 2 * (x * x + y * y)) * sz;
    out[11] = 0;
    out[12] = tt[to];
    out[13] = tt[to + 1];
    out[14] = tt[to + 2];
    out[15] = 1;
  }

  /** {@code out = a[ao..] * b[bo..]}, column-major. */
  static void mul(float[] a, int ao, float[] b, int bo, float[] out) {
    for (int c = 0; c < 4; c++) {
      for (int rr = 0; rr < 4; rr++) {
        float v = 0;
        for (int k = 0; k < 4; k++) v += a[ao + k * 4 + rr] * b[bo + c * 4 + k];
        out[c * 4 + rr] = v;
      }
    }
  }

  static void transformPoint(float[] m, float[] in, int io, float[] out, int oo) {
    float x = in[io], y = in[io + 1], z = in[io + 2];
    out[oo] = m[0] * x + m[4] * y + m[8] * z + m[12];
    out[oo + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
    out[oo + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
  }

  static void transformDirection(float[] m, float[] in, int io, float[] out, int oo) {
    float x = in[io], y = in[io + 1], z = in[io + 2];
    float ox = m[0] * x + m[4] * y + m[8] * z;
    float oy = m[1] * x + m[5] * y + m[9] * z;
    float oz = m[2] * x + m[6] * y + m[10] * z;
    float len = (float) Math.sqrt(ox * ox + oy * oy + oz * oz);
    if (len > 0) {
      ox /= len;
      oy /= len;
      oz /= len;
    }
    out[oo] = ox;
    out[oo + 1] = oy;
    out[oo + 2] = oz;
  }
}
