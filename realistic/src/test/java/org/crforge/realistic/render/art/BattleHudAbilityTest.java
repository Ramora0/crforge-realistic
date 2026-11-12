/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import static org.assertj.core.api.Assertions.assertThat;

import org.crforge.core.battle.unit.ChampionController;
import org.crforge.realistic.bridge.BattleFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BattleHudAbilityTest {

  @Test
  @DisplayName("each button state shows the states clip's frame ability_button.toml labels it")
  void stateLabels() {
    assertThat(BattleHud.stateLabel(ChampionController.READY)).isZero();
    assertThat(BattleHud.stateLabel(ChampionController.DEPLOYING)).isZero();
    assertThat(BattleHud.stateLabel(ChampionController.LIMITED_AVAILABILITY)).isEqualTo(1);
    assertThat(BattleHud.stateLabel(ChampionController.NOT_ENOUGH_ELIXIR)).isEqualTo(2);
    assertThat(BattleHud.stateLabel(ChampionController.CASTING)).isEqualTo(3);
    assertThat(BattleHud.stateLabel(ChampionController.ON_COOLDOWN)).isEqualTo(22);
    assertThat(BattleHud.stateLabel(ChampionController.ALL_CHARGES_CONSUMED)).isEqualTo(22);
    assertThat(BattleHud.stateLabel(ChampionController.PENDING)).isEqualTo(22);
    assertThat(BattleHud.stateLabel(ChampionController.DISABLED)).isEqualTo(23);
    assertThat(BattleHud.stateLabel(ChampionController.TEMPORARILY_UNAVAILABLE)).isEqualTo(-1);
    assertThat(BattleHud.grayState(ChampionController.NOT_ENOUGH_ELIXIR)).isTrue();
    assertThat(BattleHud.grayState(ChampionController.READY)).isFalse();
  }

  @Test
  @DisplayName("the cooldown dial covers the cooldown left; out of charges it covers it all")
  void cooldownDial() {
    assertThat(BattleHud.cooldownLeft(ability(ChampionController.ON_COOLDOWN, 5000, 20000)))
        .isEqualTo(0.25f);
    assertThat(BattleHud.cooldownLeft(ability(ChampionController.ALL_CHARGES_CONSUMED, 0, 1000)))
        .isEqualTo(1f);
  }

  private static BattleFrame.AbilityView ability(int state, int cooldownMs, int fullMs) {
    return new BattleFrame.AbilityView(
        "MegaMinion", "MegaMinion_Teleport_Ability", 2, state, cooldownMs, fullMs, 0, false, null);
  }
}
