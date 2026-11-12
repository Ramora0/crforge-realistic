/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.realistic.bridge.EntityView;
import org.crforge.realistic.bridge.UnitRole;
import org.crforge.realistic.bridge.UnitStatus;
import org.junit.jupiter.api.Test;

class GroundTroopStatesTest {

  private static final Set<String> ALL =
      Set.of("Skill", "SkillLoop", "Custom1", "Custom2", "Attack", "Attack2", "Idle", "Walk");

  private static EntityView unit(
      int id, String name, int side, int x, int y, int state, int shield, String... buffs) {
    return unit(id, name, side, x, y, state, shield, false, 0, 0, buffs);
  }

  private static EntityView unit(
      int id,
      String name,
      int side,
      int x,
      int y,
      int state,
      int shield,
      boolean hasTarget,
      int tx,
      int ty,
      String... buffs) {
    List<UnitStatus> statuses =
        java.util.Arrays.stream(buffs)
            .map(
                b -> {
                  String[] p = b.split("@");
                  return new UnitStatus(
                      UnitStatus.Kind.OTHER, p[0], 1000, 1000, p.length > 1 ? p[1] : null, side);
                })
            .toList();
    return new EntityView(
        id,
        EntityView.Kind.TROOP,
        side,
        name,
        11,
        x,
        y,
        500,
        100,
        100,
        shield,
        512,
        false,
        false,
        state,
        false,
        false,
        1200,
        0,
        5500,
        hasTarget,
        tx,
        ty,
        0,
        side == 0 ? 1 : -1,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        0,
        null,
        null,
        statuses,
        UnitRole.NONE,
        EntityView.Bar.HEALTH);
  }

  @Test
  void aCastPlaysTheSkillStateOrItsLoop() {
    GroundTroopStates s = new GroundTroopStates();
    var e = unit(1, "MiniPekkaHero", 0, 0, 0, GridEntityState.CASTING, 0);
    assertThat(s.ask(e, ALL::contains, 0).state()).isEqualTo("Skill");
    assertThat(s.ask(e, Set.of("SkillLoop")::contains, 0).state()).isEqualTo("SkillLoop");
    var idle = unit(1, "MiniPekkaHero", 0, 0, 0, GridEntityState.STANDING, 0);
    assertThat(s.ask(idle, ALL::contains, 0)).isEqualTo(GroundTroopStates.Ask.NONE);
  }

  @Test
  void theKnightsShieldSwitchesHisSelectors() {
    GroundTroopStates s = new GroundTroopStates();
    var shielded = unit(1, "KnightHero", 0, 0, 0, GridEntityState.ATTACKING, 300);
    assertThat(s.ask(shielded, ALL::contains, 0).parameters()[GroundTroopStates.SHIELD_PARAMETER])
        .isEqualTo(1);
    var plain = unit(1, "KnightHero", 0, 0, 0, GridEntityState.ATTACKING, 0);
    assertThat(s.ask(plain, ALL::contains, 0).parameters()[GroundTroopStates.SHIELD_PARAMETER])
        .isZero();
  }

  @Test
  void theValkyrieRunsThenSpinsAndTheElectroWizardsSkillSetsHisPropState() {
    GroundTroopStates s = new GroundTroopStates();
    String buff = "ValkyrieHero_Damage_Reduction_Buff";
    assertThat(
            s.ask(
                    unit(1, "ValkyrieHero", 1, 0, 0, GridEntityState.MOVING, 0, buff),
                    ALL::contains,
                    0)
                .state())
        .isEqualTo("SkillLoop");
    assertThat(
            s.ask(
                    unit(1, "ValkyrieHero", 1, 0, 0, GridEntityState.ATTACKING, 0, buff),
                    ALL::contains,
                    0)
                .state())
        .isEqualTo("Custom1");
    var skill =
        s.ask(
            unit(
                2,
                "ElectroWizardHero",
                0,
                0,
                0,
                GridEntityState.ATTACKING,
                0,
                "ElectroWizardHero_Ability_Buff"),
            ALL::contains,
            0);
    assertThat(skill.parameters()[GroundTroopStates.PROP_STATE]).isEqualTo(1);
    assertThat(skill.attack()).isEqualTo("Attack2");
    var recovery =
        s.ask(
            unit(
                2,
                "ElectroWizardHero",
                0,
                0,
                0,
                GridEntityState.STANDING,
                0,
                "ElectroWizardHero_Recovery_Buff"),
            ALL::contains,
            0);
    assertThat(recovery.state()).isEqualTo("Custom1");
  }

  @Test
  void theWizardLandsWithHisDropAndTheGiantSlapsToTheSideOfHisTarget() {
    GroundTroopStates s = new GroundTroopStates();
    s.ask(unit(3, "WizardHero_air", 1, 0, 0, GridEntityState.MOVING, 0), ALL::contains, 0);
    assertThat(
            s.ask(unit(3, "WizardHero", 1, 0, 0, GridEntityState.STANDING, 0), ALL::contains, 0)
                .once())
        .isEqualTo("Custom1");
    // a blue giant facing up the arena (+y) at his target; the stunned enemy on his right (+x)
    var giant = unit(4, "GiantHero", 0, 5000, 5000, GridEntityState.ATTACKING, 0, true, 5000, 7000);
    var target =
        unit(
            5,
            "Knight",
            1,
            6000,
            6000,
            GridEntityState.STANDING,
            0,
            "GiantHero_Slap_Stun@GiantHero #4");
    Map<Integer, Integer> slaps = s.slaps(List.of(giant, target));
    assertThat(slaps).containsEntry(4, 1);
    assertThat(s.ask(giant, ALL::contains, slaps.get(4)).once()).isEqualTo("Custom1");
    // the same stun is one slap
    assertThat(s.slaps(List.of(giant, target))).isEmpty();
    var left =
        unit(
            6,
            "Knight",
            1,
            4000,
            6000,
            GridEntityState.STANDING,
            0,
            "GiantHero_Slap_Stun@GiantHero #4");
    assertThat(s.slaps(List.of(giant, left))).containsEntry(4, -1);
  }
}
