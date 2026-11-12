/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.crforge.core.battle.unit.ChampionController;
import org.crforge.realistic.bridge.BattleFrame;
import org.crforge.realistic.bridge.ViewOrientation;
import org.crforge.realistic.render.art.ArtProjection;
import org.crforge.realistic.render.art.BattleHud;
import org.junit.jupiter.api.Test;

/** Taps on the phone's frame mapped to hand cards, ability buttons and arena points. */
class PhoneLayoutTest {

  private static final int W = 720;
  private static final int H = 1560;
  private final PhoneLayout layout = PhoneLayout.of(W, H, 18000, 32000);

  /** A frame pixel (y up) as the page sends it: normalized, y down. */
  private PhoneLayout.Target tap(float px, float py, ViewOrientation view) {
    return layout.hit(px / W, 1f - py / H, view, null);
  }

  @Test
  void aTapOnEachCardOfTheHandSelectsThatSlot() {
    for (int slot = 0; slot < 4; slot++) {
      float[] box = BattleHud.handSlotBox(slot, W);
      float cx = (box[0] + box[2]) / 2;
      float cy = (box[1] + box[3]) / 2;
      assertThat(tap(cx, cy, ViewOrientation.SIDE_0)).isEqualTo(new PhoneLayout.Card(slot));
      assertThat(tap(cx, cy, ViewOrientation.SIDE_1)).isEqualTo(new PhoneLayout.Card(slot));
    }
  }

  @Test
  void theFourCardBoxesSitSideBySideInTheBottomPanel() {
    float[] first = BattleHud.handSlotBox(0, W);
    float[] last = BattleHud.handSlotBox(3, W);
    assertThat(first[0]).isGreaterThan(0f);
    assertThat(last[2]).isLessThan(W);
    for (int slot = 1; slot < 4; slot++) {
      assertThat(BattleHud.handSlotBox(slot, W)[0])
          .isCloseTo(
              BattleHud.handSlotBox(slot - 1, W)[2], org.assertj.core.data.Offset.offset(0.01f));
    }
    // the cards stand below the arena's first row
    assertThat(first[3]).isLessThan(layout.originY());
  }

  @Test
  void aTapOnTheArenaFindsThePointItIsDrawnAtInEitherView() {
    ArtProjection proj = layout.projection();
    for (ViewOrientation view : List.of(ViewOrientation.SIDE_0, ViewOrientation.SIDE_1)) {
      for (int[] p : new int[][] {{3500, 6500}, {14500, 25500}, {9000, 16000}, {500, 31500}}) {
        float px = proj.x(view, p[0]);
        float py = proj.y(view, p[1]);
        PhoneLayout.Target t = tap(px, py, view);
        assertThat(t).isInstanceOf(PhoneLayout.Arena.class);
        PhoneLayout.Arena a = (PhoneLayout.Arena) t;
        assertThat(Math.abs(a.x() - p[0])).as("x of %s", Arrays.toString(p)).isLessThanOrEqualTo(2);
        assertThat(Math.abs(a.y() - p[1])).as("y of %s", Arrays.toString(p)).isLessThanOrEqualTo(2);
        assertThat(a.tileX()).isEqualTo(p[0] / 1000);
        assertThat(a.tileY()).isEqualTo(p[1] / 1000);
        assertThat(a.tileCentre())
            .containsExactly(p[0] / 1000 * 1000 + 500, p[1] / 1000 * 1000 + 500);
      }
    }
  }

  @Test
  void eachPlayerTapsTheirOwnHalfAtTheBottom() {
    // just above the cards: the player's own baseline, side 0's row 0 or side 1's row 31
    float py = layout.originY() + 5;
    PhoneLayout.Arena blue = (PhoneLayout.Arena) tap(W / 2f, py, ViewOrientation.SIDE_0);
    PhoneLayout.Arena red = (PhoneLayout.Arena) tap(W / 2f, py, ViewOrientation.SIDE_1);
    assertThat(blue.tileY()).isEqualTo(0);
    assertThat(red.tileY()).isEqualTo(31);
  }

  @Test
  void aTapOnTheTopBarIsNothing() {
    assertThat(tap(W / 2f, H - 10, ViewOrientation.SIDE_0)).isInstanceOf(PhoneLayout.Nothing.class);
  }

  @Test
  void aTapOnAnAbilityButtonUsesItsSlotOnlyWhileItIsShown() {
    BattleFrame.AbilityView shown =
        new BattleFrame.AbilityView(
            "Tombstone", "Ability", 2, ChampionController.READY, 0, 0, -1, false, null);
    float[] box = BattleHud.abilityButtonBox(0, 1, W);
    float nx = (box[0] + box[2]) / 2 / W;
    float ny = 1f - (box[1] + box[3]) / 2 / H;
    java.util.List<BattleFrame.AbilityView> slots = Arrays.asList(null, shown);
    assertThat(layout.hit(nx, ny, ViewOrientation.SIDE_0, slots))
        .isEqualTo(new PhoneLayout.Ability(1));
    // with no champion on the field there is no button there
    assertThat(layout.hit(nx, ny, ViewOrientation.SIDE_0, Arrays.asList(null, null)))
        .isNotInstanceOf(PhoneLayout.Ability.class);
  }
}
