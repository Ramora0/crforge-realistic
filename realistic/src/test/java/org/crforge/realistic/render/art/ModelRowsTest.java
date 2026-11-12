/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.crforge.core.battle.Shipped;
import org.junit.jupiter.api.Test;

/** The renderer's rules for the Hero Tombstone's rows, read off the shipped tables. */
class ModelRowsTest {

  @Test
  void aHeroRowWithoutAHealthBarColumnTakesTheBarItsClientActionStarts() {
    assertThat(BattleHud.clientBarClass(Shipped.unitRow("TombstoneHero"))).isEqualTo("High");
    assertThat(BattleHud.clientBarClass(Shipped.unitRow("TombstoneHeroSkeleton")))
        .isEqualTo("Small");
    assertThat(BattleHud.clientBarClass(Shipped.unitRow("Knight"))).isNull();
  }

  @Test
  void thePassiveQueensBarStaysHiddenWhileItsTombstoneStands() {
    var passive =
        new org.crforge.realistic.bridge.EntityView(
            1,
            org.crforge.realistic.bridge.EntityView.Kind.TROOP,
            0,
            "TombstoneHero_Monster_Passive",
            11,
            0,
            0,
            0,
            1,
            1,
            0,
            0,
            false,
            false,
            0,
            false,
            false,
            0,
            0,
            0,
            false,
            0,
            0,
            0,
            0,
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
            java.util.List.of(),
            null,
            org.crforge.realistic.bridge.EntityView.Bar.TIMER);
    assertThat(BattleHud.hiddenUnderItsTomb(passive, Set.of("0|TombstoneHero"))).isTrue();
    assertThat(BattleHud.hiddenUnderItsTomb(passive, Set.of("1|TombstoneHero"))).isFalse();
    assertThat(BattleHud.hiddenUnderItsTomb(passive, Set.of())).isFalse();
  }

  @Test
  void rowSwapsTellTheForcedAnimations() {
    assertThat(RealisticRenderer.forcedOnSwap("TombstoneHero_broken_visual_dummy")).isEqualTo(1);
    assertThat(RealisticRenderer.forcedOnSwap("TombstoneHero_Monster_Active")).isEqualTo(1);
    assertThat(RealisticRenderer.forcedOnSwap("Knight")).isZero();
    assertThat(RealisticRenderer.isTombVisual("TombstoneHero_visual_dummy")).isTrue();
    assertThat(RealisticRenderer.isTombVisual("TombstoneHero")).isFalse();
  }
}
