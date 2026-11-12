/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.realistic.bridge.EntityView;
import org.crforge.realistic.render.art.model.AnimPlayer;
import org.crforge.realistic.render.art.model.AnimStates;
import org.crforge.realistic.render.art.model.Sc3dLibrary;

/**
 * What the 3D heroes' forms and fliers show beyond the plain state table
 * (docs/realistic-rendering.md §10.3): the models a prefab mounts on its bones, the animator
 * parameters their {@code Selector} states read, the states the simulator shows only through motion
 * (a warp, a charge, a cast), the forced animations the client runs on a row swap or a spawn, and
 * the reroll that hides the Barbarian Barrel hero. Everything here is read off what the core
 * already reports (states, rows, positions, shield, motion); the core is not changed for it.
 */
final class HeroForms {

  /**
   * A tick's travel (game units) past which a unit is warping rather than walking: the warp actions
   * fly 400 to 1500 units a tick ({@code MegaMinion_hero_teleport_action} Speed 1500, Acceleration
   * 400; DATA), the fastest walk or dash under 200 (INFERRED).
   */
  static final int WARP_STEP = 250;

  /** Animator parameter numbers ({@code Selector} {@code Parameter}; DATA, from the machines). */
  static final int PARAM_SHIELD = 0;

  static final int PARAM_PROP_STATE = 2;

  /** One model of a prefab with what it rides: -1 and null for the prefab's own models. */
  record PartSpec(Sc3dLibrary.ModelRef ref, int host, String bone) {}

  /** A unit's tracked motion and form. */
  private static final class Track {
    int x;
    int y;
    int tick = -1;
    int step;
    boolean cast;
    boolean rolling;
    int deploys;
    boolean wasDeploying;
  }

  private final Map<Integer, Track> tracks = new HashMap<>();

  /** Units that start a one-shot this frame (a spawn they made), by id: the state's name. */
  private final Map<Integer, String> cues = new HashMap<>();

  private final Set<Integer> seen = new HashSet<>();

  /**
   * The parts a prefab draws: its own models, then each mount point's rider models at their host
   * model's bone ({@link Sc3dLibrary#mounts}).
   */
  static List<PartSpec> parts(Sc3dLibrary library, Sc3dLibrary.Prefab prefab) {
    List<PartSpec> out = new ArrayList<>();
    for (Sc3dLibrary.ModelRef m : prefab.models()) out.add(new PartSpec(m, -1, null));
    int own = out.size();
    for (Sc3dLibrary.Mount mount : library.mounts(prefab)) {
      // the host is the prefab's model on the mount's node (its only model on every battle prefab)
      int host = own > 0 ? 0 : -1;
      if (host < 0) continue;
      for (Sc3dLibrary.ModelRef m : mount.rider().models()) {
        out.add(new PartSpec(m, host, mount.bone()));
      }
    }
    return out;
  }

  /** One entity as a tick leaves it: what {@link #observe} reads. */
  record Seen(int id, String name, int side, int x, int y, int state, boolean projectile) {}

  /**
   * Reads a tick (from the core's observer, so a recording started late has seen the battle so
   * far): each unit's travel in the tick (for {@link #warping}), the rerolls, and the spawns that
   * start a one-shot on their maker.
   */
  void observe(int tick, List<Seen> entities) {
    seen.clear();
    List<Seen> balloons = new ArrayList<>();
    List<Seen> barbarians = new ArrayList<>();
    for (Seen e : entities) {
      seen.add(e.id());
      if (e.projectile()) continue;
      if (e.name().equals("BalloonHero")) balloons.add(e);
      if (rerolls(e.name())) barbarians.add(e);
      Track t = tracks.computeIfAbsent(e.id(), k -> new Track());
      if (t.tick >= 0 && tick > t.tick) {
        long dx = e.x() - t.x;
        long dy = e.y() - t.y;
        t.step = (int) (Math.sqrt(dx * dx + dy * dy) / (tick - t.tick));
      }
      t.x = e.x();
      t.y = e.y();
      t.tick = tick;
      boolean deploying = e.state() == GridEntityState.DEPLOYING;
      if (deploying && !t.wasDeploying) {
        t.deploys++;
        // the Barbarian Barrel hero's reroll ends as it deploys again
        t.rolling = false;
      }
      t.wasDeploying = deploying;
      if (e.state() == GridEntityState.CASTING) t.cast = true;
    }
    for (Seen e : entities) {
      if (!e.projectile() || tracks.containsKey(-e.id())) continue;
      tracks.put(-e.id(), new Track());
      if (e.name().equals("BalloonHero_Skeletrooper_Projectile")) {
        // the Balloon hero's ability plays its Custom1 as it drops a Skeleton Trooper
        // (BalloonHero_Ability_Play_Spawn_Skeletrooper_Animation, with the projectile: DATA)
        Seen maker = nearest(balloons, e);
        if (maker != null) cues.put(maker.id(), AnimStates.name(AnimStates.CUSTOM1));
      } else if (e.name().equals("BarbLogHeroProjectileReRolling")) {
        // the Barbarian Barrel hero's reroll (ActionBarbBarrelHeroReRoll): it rolls in this
        // barrel until it deploys again (DATA: the action's ReRollProjectile, its
        // HideHealthbarWhileRolling and re-deploy; the hero not drawn meanwhile INFERRED)
        Seen maker = nearest(barbarians, e);
        if (maker != null) tracks.get(maker.id()).rolling = true;
      }
    }
    tracks.keySet().removeIf(id -> !seen.contains(Math.abs(id)));
  }

  private static Seen nearest(List<Seen> units, Seen to) {
    Seen best = null;
    long bestD = Long.MAX_VALUE;
    for (Seen u : units) {
      if (u.side() != to.side()) continue;
      long dx = u.x() - to.x();
      long dy = u.y() - to.y();
      long d = dx * dx + dy * dy;
      if (d < bestD) {
        bestD = d;
        best = u;
      }
    }
    return best;
  }

  static boolean rerolls(String row) {
    return row.startsWith("BarbLogBarbarianHero");
  }

  /** Whether the unit travelled a warp's distance in its last tick. */
  boolean warping(int id) {
    Track t = tracks.get(id);
    return t != null && t.step > WARP_STEP;
  }

  /** Whether the unit is rolling in its barrel (drawn by the projectile, not by its model). */
  boolean rolling(int id) {
    Track t = tracks.get(id);
    return t != null && t.rolling;
  }

  /** Whether the unit deploys a second time (the Barbarian Barrel hero after its reroll). */
  boolean redeploying(int id) {
    Track t = tracks.get(id);
    return t != null && t.deploys >= 2 && t.wasDeploying;
  }

  /**
   * The one-shot the unit starts now, or null: a spawn it made this frame ({@link #observe}). Taken
   * once.
   */
  String takeCue(int id) {
    return cues.remove(id);
  }

  /**
   * The forced animation ({@code ActionRunForcedAnimationOnce}) a row swap runs, as a state name,
   * or null (the swaps' actions DATA; the state each runs INFERRED from the machines):
   *
   * <ul>
   *   <li>the Hero Wizard landing ({@code WizardHero_air} back to {@code WizardHero}: {@code
   *       WizardHero_Play_Falling_Animation}) plays {@code Custom1}, its {@code drop01} clip;
   * </ul>
   */
  static String onSwap(String from, String to) {
    if (from.equals("WizardHero_air") && to.equals("WizardHero")) {
      return AnimStates.name(AnimStates.CUSTOM1);
    }
    return null;
  }

  /**
   * The forced animation a unit runs as it first appears, or null: the Dark Prince's rhino, left
   * behind as he dismounts, plays its {@code Custom1} ({@code hero_darkprince_rhino_ability}) while
   * {@code DarkPrinceHero_Mount_NoMove} holds it (the row's {@code
   * DarkPrinceHero_Mount_ForceCustomAnimationState}, Custom 1; its use on spawn INFERRED).
   */
  static String onSpawn(String row, int state) {
    if (row.equals("DarkPrinceHero_Mount") && state != GridEntityState.DEPLOYING) {
      return AnimStates.name(AnimStates.CUSTOM1);
    }
    return null;
  }

  /**
   * The state machine state a hero shows beyond {@link AnimStates#of}, or null to keep it: a cast
   * ({@code CASTING}) plays {@code Skill}; a warp ({@link #warping}) {@code Warp}; a moving unit
   * whose charge is complete {@code Charge}; the Barbarian Barrel hero's second deploy (after its
   * reroll) {@code Custom2} ({@code barblog_hero_set_re_deploy_animation}). Each only where the
   * machine has the state (INFERRED from the state names and {@code animation_states_remapping}).
   */
  String state(EntityView e, AnimPlayer pl, boolean charged) {
    String s = null;
    if (warping(e.id())) s = AnimStates.name(27);
    else if (e.state() == GridEntityState.CASTING) s = AnimStates.name(12);
    else if (charged && AnimStates.of(e.state()) == AnimStates.WALK) {
      s = AnimStates.name(AnimStates.CHARGE);
    }
    return s != null && pl.has(s) ? s : null;
  }

  /**
   * The state of a unit's attack in its sequence ({@code AttackSequence}: Attack, Attack2, Attack3
   * by the core's sequence index), where the machine has it.
   */
  static String attackState(AnimPlayer pl, int sequenceIndex) {
    String name =
        switch (sequenceIndex) {
          case 1 -> AnimStates.name(6);
          case 2 -> AnimStates.name(7);
          case 3 -> AnimStates.name(8);
          default -> null;
        };
    return name != null && pl.has(name) ? name : AnimStates.name(AnimStates.ATTACK);
  }

  /**
   * Sets the animator parameters the unit's {@code Selector} states read: the Dark Prince's shield
   * (parameter 0: 1 while it holds a shield; its selectors switch {@code _NoShield} at 0 and {@code
   * _Shield} at 1, the reading INFERRED from the names) and the Barbarian Barrel hero's {@code
   * PropState} (parameter 2: 1, the barrel on his back, until his ability has run, then 0: {@code
   * BarbLog_hero_select_modifier}, DATA).
   */
  void parameters(AnimPlayer pl, EntityView e, float now) {
    if (e.maxShield() > 0) pl.parameter(PARAM_SHIELD, e.shield() > 0 ? 1 : 0, now);
    if (rerolls(e.name())) {
      Track t = tracks.get(e.id());
      boolean played = t != null && t.cast && e.state() != GridEntityState.CASTING;
      pl.parameter(PARAM_PROP_STATE, played ? 0 : 1, now);
    }
  }
}
