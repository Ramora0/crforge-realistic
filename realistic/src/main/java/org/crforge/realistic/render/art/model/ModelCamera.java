/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

/**
 * How the game's battle camera sees a 3D actor, in prefab space (Z up, metres), and how that space
 * meets the 2D arena's display units (docs/realistic-rendering.md §10).
 *
 * <ul>
 *   <li>The camera (DATA, {@code client_globals VANILLA_3D_ACTOR_ENV} = {@code
 *       sc3d/vanilla/maps/arn_default/prefab_arn_default}): orthographic, its root turned {@value
 *       #TILT_DEGREES}° about x, the camera 20 units out along the root's z, so it sits above the
 *       +Y side looking down at 36.8° from the vertical.
 *   <li>Its roll (INFERRED: the only one that shows the ground's far side at the top of the
 *       screen): screen right is prefab −X, screen up is the ground's −Y, and heights rise by sin
 *       of the tilt.
 *   <li>Its scale (INFERRED): {@value #DU_PER_METRE} arena display units per metre, half of {@code
 *       client_globals VANILLA_3D_PPM} (100). The Hero Tombstone's base is 1.34 m across, 67 du,
 *       against the 2D Tombstone's 65.5 du and 2.3 tiles in a real battle capture.
 * </ul>
 */
public final class ModelCamera {

  private ModelCamera() {}

  /** The camera root's turn about x, degrees from looking straight down. */
  public static final float TILT_DEGREES = 36.8f;

  /** The camera's distance from the actor along its axis (prefab units). */
  public static final float DISTANCE = 20f;

  /** Arena display units per prefab metre. */
  public static final float DU_PER_METRE = 50f;

  public static final float SIN = (float) Math.sin(Math.toRadians(TILT_DEGREES));
  public static final float COS = (float) Math.cos(Math.toRadians(TILT_DEGREES));

  /** Screen x of a prefab point, metres (x right). */
  public static float screenX(float x, float y, float z) {
    return -x;
  }

  /** Screen y of a prefab point, metres (y up the screen). */
  public static float screenY(float x, float y, float z) {
    return -y * COS + z * SIN;
  }

  /** How far a prefab point is towards the camera, metres (more is nearer). */
  public static float towardsCamera(float x, float y, float z) {
    return y * SIN + z * COS;
  }

  /** The camera's position relative to the actor's ground point (prefab space). */
  public static float[] cameraPosition() {
    return new float[] {0f, DISTANCE * SIN, DISTANCE * COS};
  }

  /**
   * The turn about prefab z (radians, counter-clockwise seen from above) that points a model's
   * default facing (−Y, up the screen) along a screen direction ({@code fx} right, {@code fy} up).
   */
  public static float headingRadians(float fx, float fy) {
    return (float) Math.atan2(-fx, fy);
  }

  /**
   * The direction a node with these Euler degrees points its +Y axis, the engine's forward: {@code
   * Rx · Ry · Rz · (0, 1, 0)} (INFERRED: the reading under which {@code arn_default}'s camera node,
   * 20 units out on its root's z and turned (-90, -180, 0), looks at the root with the ground's far
   * side up the screen).
   */
  public static float[] forward(float rx, float ry, float rz) {
    float[] v = {0f, 1f, 0f};
    v = rotate(v, 'z', rz);
    v = rotate(v, 'y', ry);
    v = rotate(v, 'x', rx);
    return v;
  }

  static float[] rotate(float[] v, char axis, float degrees) {
    double a = Math.toRadians(degrees);
    float c = (float) Math.cos(a);
    float s = (float) Math.sin(a);
    return switch (axis) {
      case 'x' -> new float[] {v[0], c * v[1] - s * v[2], s * v[1] + c * v[2]};
      case 'y' -> new float[] {c * v[0] + s * v[2], v[1], -s * v[0] + c * v[2]};
      default -> new float[] {c * v[0] - s * v[1], s * v[0] + c * v[1], v[2]};
    };
  }

  /** The unit vector towards a light whose node has these Euler degrees (it shines along +Y). */
  public static float[] towardsLight(float rx, float ry, float rz) {
    float[] f = forward(rx, ry, rz);
    return new float[] {-f[0], -f[1], -f[2]};
  }

  /**
   * The line a shadow-casting light (a battle prefab's {@code sun}) falls along, read as yaw and
   * pitch: {@code Rz · Ry · Rx · (0, 1, 0)}, the pitch applied in the light's own frame, so the
   * node's x angle is the light's angle below the horizon (the {@code sun}'s −80.6° is a light
   * 80.6° up, 9.4° off the vertical) and its z angle the compass turn. INFERRED: the reading under
   * which the Hero Tombstone's shadow is the short band low beside its base that real grass battles
   * show, and the Hero Knight's a blob down and to the left under him; the other order ({@link
   * #forward}) tilts the sun to 56° and throws the headstone's shadow a tile to the left at its own
   * height. The planar shadow ({@code planar_shadow.vert}: {@code p − L · p.z / L.z}) does not
   * depend on the line's sign.
   */
  public static float[] shadowLight(float rx, float ry, float rz) {
    float[] v = {0f, 1f, 0f};
    v = rotate(v, 'x', rx);
    v = rotate(v, 'y', ry);
    v = rotate(v, 'z', rz);
    return v;
  }
}
