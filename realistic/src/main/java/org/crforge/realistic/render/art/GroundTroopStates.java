/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import java.util.HashMap;
import java.util.Map;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.realistic.bridge.EntityView;
import org.crforge.realistic.bridge.UnitStatus;

/**
 * What a 3D ground troop's state machine is asked for beyond its walk, idle, attack and deploy
 * (docs/realistic-rendering.md §10.2): its hero ability, read off what the simulator shows of it.
 * The client plays these through actions the core leaves to it ({@code
 * ActionRunForcedAnimationOnce}, {@code ActionSetAnimationModifier}); the core does run the
 * ability's cast ({@code CASTING}), its buffs and its row swaps, so each is told from those. The
 * state names are the ASMs' (DATA); which simulator sign stands for which is INFERRED.
 *
 * <ul>
 *   <li>Casting ({@code GridEntityState.CASTING}, the ability's {@code CastTime}): {@code Skill}
 *       (the Mini P.E.K.K.A's, Musketeer's, Wizard's, Electro Wizard's, Magic Archer's and Bowler's
 *       ability clips), else {@code SkillLoop}.
 *   <li>Hero Knight: animation parameter 0 is 1 while his ability's shield holds ({@code
 *       Knight_hero_SetMaxShield}): his {@code _shield} idle, walk and attacks.
 *   <li>Hero Valkyrie: while {@code ValkyrieHero_Damage_Reduction_Buff} holds she runs at her
 *       target ({@code SkillLoop}, {@code hero_valkyrie_run}) while moving, else spins ({@code
 *       Custom1}, {@code hero_valkyrie_ability_attack}).
 *   <li>Hero Electro Wizard: {@code PropState} (parameter 2) is 1 while {@code UNIT_CUSTOM_TAG_1}
 *       (his {@code _Ability_Buff} or {@code _Recovery_Buff}) holds, as {@code
 *       ElectroWizardHero_Select_Prop_Animations} sets it: his skill idle and walk; his attacks
 *       then are {@code Attack2} ({@code skill_attack_01}); the recovery ({@code
 *       ElectroWizardHero_Recovery_Buff}) plays {@code Custom1} ({@code skill_end}).
 *   <li>Hero Wizard: landing from his flight (the row {@code WizardHero_air} back to {@code
 *       WizardHero}) plays {@code Custom1} ({@code hero_wizard_drop01}).
 *   <li>Hero Giant: an enemy stunned by his slap ({@code GiantHero_Slap_Stun}, its source him)
 *       plays his slap, {@code Custom1} ({@code attack02}) or {@code Custom2} ({@code
 *       attack02_left}) by the side the target stands on ({@code
 *       GiantHero_Play_Slap_Animation_Right/Left}).
 * </ul>
 */
final class GroundTroopStates {

  /** The client's animation parameter the Hero Knight's Selectors read (none named: 0). */
  static final int SHIELD_PARAMETER = 0;

  /** {@code PropState}, the parameter {@code ActionSetAnimationModifier} sets. */
  static final int PROP_STATE = 2;

  /**
   * What to ask a unit's state machine for this frame.
   *
   * @param state the state to ask for instead of the simulator state's (null: the usual one)
   * @param attack the state an attack plays ({@code Attack} unless the ability changes it)
   * @param once a one-shot state to start now, or null
   * @param parameters the animation parameters by index, or null to leave them
   */
  record Ask(String state, String attack, String once, int[] parameters) {
    static final Ask NONE = new Ask(null, "Attack", null, null);
  }

  /** Each unit's row last frame (the Wizard's landing is its row swap). */
  private final Map<Integer, String> rows = new HashMap<>();

  /** The enemies stunned by a Hero Giant's slap, to the Giant, so each slap plays once. */
  private final Map<Integer, Integer> slaps = new HashMap<>();

  /**
   * The ask for a unit this frame.
   *
   * @param e the unit
   * @param has whether its state machine has a state
   * @param slapped the side of the unit an enemy its slap stunned stands on: -1 its left, 1 its
   *     right, 0 none this frame
   */
  Ask ask(EntityView e, java.util.function.Predicate<String> has, int slapped) {
    String previous = rows.put(e.id(), e.name());
    String state = null;
    String attack = "Attack";
    String once = null;
    int[] params = null;
    if (e.state() == GridEntityState.CASTING) {
      state = has.test("Skill") ? "Skill" : has.test("SkillLoop") ? "SkillLoop" : null;
    }
    switch (e.name()) {
      case "KnightHero" -> {
        params = new int[8];
        params[SHIELD_PARAMETER] = e.shield() > 0 ? 1 : 0;
      }
      case "ValkyrieHero" -> {
        if (status(e, "ValkyrieHero_Damage_Reduction_Buff")) {
          state = moving(e.state()) ? "SkillLoop" : "Custom1";
        }
      }
      case "ElectroWizardHero" -> {
        boolean skill = status(e, "ElectroWizardHero_Ability_Buff");
        boolean recovery = status(e, "ElectroWizardHero_Recovery_Buff");
        params = new int[8];
        params[PROP_STATE] = skill || recovery ? 1 : 0;
        if (skill) attack = "Attack2";
        if (recovery && state == null) state = "Custom1";
      }
      case "WizardHero" -> {
        if ("WizardHero_air".equals(previous)) once = "Custom1";
      }
      case "GiantHero" -> {
        if (slapped < 0) once = "Custom2";
        else if (slapped > 0) once = "Custom1";
      }
      default -> {}
    }
    if (state == null && attack.equals("Attack") && once == null && params == null) return Ask.NONE;
    return new Ask(state, attack, once, params);
  }

  /**
   * The Hero Giants that slapped this frame: each one whose {@code GiantHero_Slap_Stun} has newly
   * landed on an enemy, to the side of him it stands on (-1 his left, 1 his right).
   */
  Map<Integer, Integer> slaps(Iterable<EntityView> entities) {
    Map<Integer, EntityView> byId = new HashMap<>();
    for (EntityView e : entities) byId.put(e.id(), e);
    Map<Integer, Integer> out = new HashMap<>();
    java.util.Set<Integer> stunned = new java.util.HashSet<>();
    for (EntityView t : entities) {
      for (UnitStatus s : t.statuses()) {
        if (!s.name().equals("GiantHero_Slap_Stun") || s.source() == null) continue;
        stunned.add(t.id());
        if (slaps.containsKey(t.id())) continue;
        int hash = s.source().lastIndexOf('#');
        EntityView giant = null;
        try {
          giant = hash < 0 ? null : byId.get(Integer.parseInt(s.source().substring(hash + 1)));
        } catch (NumberFormatException ignored) {
          // a source without an id
        }
        if (giant == null) continue;
        slaps.put(t.id(), giant.id());
        out.put(giant.id(), side(giant, t));
      }
    }
    slaps.keySet().retainAll(stunned);
    return out;
  }

  /**
   * The side of {@code from} that {@code to} stands on: 1 its right, -1 its left, facing its target
   * or, without one, its heading (arena coordinates, y up the arena).
   */
  static int side(EntityView from, EntityView to) {
    float fx = from.hasTarget() ? from.targetX() - from.x() : from.headingX();
    float fy = from.hasTarget() ? from.targetY() - from.y() : from.headingY();
    if (fx == 0 && fy == 0) fy = from.side() == 0 ? 1 : -1;
    float cross = fx * (to.y() - from.y()) - fy * (to.x() - from.x());
    return cross > 0 ? -1 : 1;
  }

  /** Forgets the units no longer on the field. */
  void retain(java.util.Set<Integer> live) {
    rows.keySet().retainAll(live);
  }

  static boolean status(EntityView e, String name) {
    for (UnitStatus s : e.statuses()) if (s.name().equals(name)) return true;
    return false;
  }

  static boolean moving(int state) {
    return switch (state) {
      case GridEntityState.MOVING,
          GridEntityState.SPAWN_PATHFIND,
          GridEntityState.INGAME_PATHFIND,
          GridEntityState.ROUTE_FOLLOWING_ALTERNATE,
          GridEntityState.DASHING,
          GridEntityState.JUMPING ->
          true;
      default -> false;
    };
  }
}
