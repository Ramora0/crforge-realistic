/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.realistic.bridge.BattleAdapter;
import org.crforge.realistic.bridge.BattleSession;
import org.crforge.realistic.bridge.EntityView;
import org.junit.jupiter.api.Test;

/** The finger's gestures turned into the session's plays, as the phone server applies them. */
class TapInputTest {

  private static final List<String> KNIGHTS = java.util.Collections.nCopies(8, "Knight");

  private static BattleSession session() {
    BattleSession s = BattleSession.ladder(GameData.tables(), KNIGHTS, KNIGHTS);
    // the opening elixir and the start cooldown
    for (int i = 0; i < 100; i++) s.step();
    return s;
  }

  private static PhoneLayout.Arena ownHalf() {
    return new PhoneLayout.Arena(3500, 8500, 3, 8);
  }

  private static int elixir(BattleSession s) {
    return s.match().side(0).wholeElixir();
  }

  @Test
  void tapACardThenTapTheArenaPlaysIt() {
    BattleSession s = session();
    TapInput in = new TapInput(s, 0);
    int before = elixir(s);
    assertThat(in.input("down", new PhoneLayout.Card(2), true).what()).isEqualTo("selected");
    assertThat(in.input("up", new PhoneLayout.Card(2), true).what()).isEqualTo("none");
    assertThat(in.selected()).isEqualTo(2);
    assertThat(in.input("down", ownHalf(), true).what()).isEqualTo("hover");
    assertThat(in.hover()).isEqualTo(ownHalf());
    assertThat(in.input("up", ownHalf(), true).what()).isEqualTo("played");
    assertThat(in.selected()).isEqualTo(-1);
    assertThat(s.isPending(0, 2)).isTrue();
    // the knight runs a second later, its cost taken
    for (int i = 0; i < 25; i++) s.step();
    assertThat(elixir(s)).isLessThan(before);
    assertThat(
            BattleAdapter.frame(s).entities().stream()
                .anyMatch(e -> e.side() == 0 && e.kind() == EntityView.Kind.TROOP))
        .isTrue();
  }

  @Test
  void dragACardOntoTheArenaPlaysItWhereItIsReleased() {
    BattleSession s = session();
    TapInput in = new TapInput(s, 0);
    in.input("down", new PhoneLayout.Card(0), true);
    assertThat(in.input("move", new PhoneLayout.Nothing(), true).what()).isEqualTo("none");
    assertThat(in.input("move", ownHalf(), true).what()).isEqualTo("hover");
    assertThat(in.input("up", ownHalf(), true).what()).isEqualTo("played");
    assertThat(s.isPending(0, 0)).isTrue();
  }

  @Test
  void aSecondTapOnTheSelectedCardDeselectsIt() {
    TapInput in = new TapInput(session(), 0);
    in.input("down", new PhoneLayout.Card(1), true);
    in.input("up", new PhoneLayout.Card(1), true);
    in.input("down", new PhoneLayout.Card(1), true);
    assertThat(in.input("up", new PhoneLayout.Card(1), true).what()).isEqualTo("deselected");
    assertThat(in.selected()).isEqualTo(-1);
    // a tap on the arena with nothing selected does nothing
    assertThat(in.input("down", ownHalf(), true).what()).isEqualTo("none");
    assertThat(in.input("up", ownHalf(), true).what()).isEqualTo("none");
  }

  @Test
  void aPlayTheGameRefusesIsRefusedWithItsReason() {
    BattleSession s = session();
    TapInput in = new TapInput(s, 0);
    // the enemy's half: a knight cannot go there
    PhoneLayout.Arena enemyHalf = new PhoneLayout.Arena(3500, 26500, 3, 26);
    in.input("down", new PhoneLayout.Card(0), true);
    TapInput.Result r = in.input("up", enemyHalf, true);
    // the game moves a drop on a closed tile to the nearest legal one: played, never across
    assertThat(r.what()).isIn("played", "refused");
    // spend the elixir, then a play is refused for want of it
    for (int slot = 1; slot < 4; slot++) {
      in.input("down", new PhoneLayout.Card(slot), true);
      in.input("up", ownHalf(), true);
    }
    in.input("down", new PhoneLayout.Card(slot0Free(s)), true);
    TapInput.Result last = in.input("up", ownHalf(), true);
    assertThat(last.what()).isEqualTo("refused");
    assertThat(last.message()).isNotBlank();
  }

  private static int slot0Free(BattleSession s) {
    for (int slot = 0; slot < 4; slot++) if (!s.isPending(0, slot)) return slot;
    return 0;
  }

  @Test
  void nothingIsPlayedWhileTheBattleDoesNotRun() {
    BattleSession s = session();
    TapInput in = new TapInput(s, 0);
    in.input("down", new PhoneLayout.Card(0), true);
    TapInput.Result r = in.input("up", ownHalf(), false);
    assertThat(r.what()).isEqualTo("refused");
    assertThat(s.isPending(0, 0)).isFalse();
  }

  @Test
  void anAbilityWithNoChampionIsRefused() {
    TapInput in = new TapInput(session(), 0);
    TapInput.Result r = in.input("down", new PhoneLayout.Ability(0), true);
    assertThat(r.what()).isEqualTo("refused");
  }
}
