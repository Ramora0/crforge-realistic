/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import java.util.Map;
import org.crforge.core.pathfinding.GridEntityState;

/**
 * Which state of a model's state machine a unit plays. The client numbers its animation states and
 * maps them to state machine names with {@code sc3d/vanilla/animation_states_remapping} (DATA, from
 * {@code assets.scdb}, named by {@code client_globals.csv VANILLA_3D_ACTOR_ANIMATION_REMAPPING});
 * the simulator's state picks the number (INFERRED, the 2D rule of docs/realistic-rendering.md
 * §1.3: moving walks, attacking attacks, deploying deploys, the rest idles).
 */
public final class AnimStates {

  private AnimStates() {}

  public static final int WALK = 0;
  public static final int IDLE = 1;
  public static final int ATTACK = 2;
  public static final int CHARGE = 3;
  public static final int DASH = 4;
  public static final int DEPLOY = 5;
  public static final int CUSTOM1 = 24;
  public static final int CUSTOM2 = 26;
  public static final int CUSTOM3 = 28;

  /** {@code animation_states_remapping}: the client's animation state to the ASM state name. */
  public static final Map<Integer, String> REMAPPING =
      Map.ofEntries(
          Map.entry(0, "Walk"),
          Map.entry(1, "Idle"),
          Map.entry(2, "Attack"),
          Map.entry(6, "Attack2"),
          Map.entry(7, "Attack3"),
          Map.entry(8, "Attack4"),
          Map.entry(4, "Dash"),
          Map.entry(12, "Skill"),
          Map.entry(17, "Skill"),
          Map.entry(16, "SkillLoop"),
          Map.entry(5, "Deploy"),
          Map.entry(24, "Custom1"),
          Map.entry(26, "Custom2"),
          Map.entry(27, "Warp"),
          Map.entry(3, "Charge"),
          Map.entry(13, "Dash2"),
          Map.entry(14, "Dash3"),
          Map.entry(15, "Dash4"),
          Map.entry(28, "Custom3"),
          Map.entry(29, "Walk2"));

  /** The ASM state name of a client animation state, or null when the table has none. */
  public static String name(int animState) {
    return REMAPPING.get(animState);
  }

  /**
   * The client animation state of an {@code ActionRunForcedAnimationOnce}'s {@code
   * CustomStateNumber} (1, 2, 3 to Custom1..3; INFERRED from the names), or -1.
   */
  public static int custom(int customStateNumber) {
    return switch (customStateNumber) {
      case 1 -> CUSTOM1;
      case 2 -> CUSTOM2;
      case 3 -> CUSTOM3;
      default -> -1;
    };
  }

  /** The client animation state a unit in that simulator state shows. */
  public static int of(int gridState) {
    return switch (gridState) {
      case GridEntityState.MOVING,
          GridEntityState.SPAWN_PATHFIND,
          GridEntityState.INGAME_PATHFIND,
          GridEntityState.ROUTE_FOLLOWING_ALTERNATE,
          GridEntityState.JUMPING ->
          WALK;
      case GridEntityState.ATTACKING -> ATTACK;
      case GridEntityState.DASHING -> DASH;
      case GridEntityState.DEPLOYING, GridEntityState.CLONE_SETUP -> DEPLOY;
      default -> IDLE;
    };
  }
}
