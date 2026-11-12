/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.crforge.core.battle.GameData;
import org.crforge.realistic.bridge.BattleAdapter;
import org.crforge.realistic.bridge.BattleDecks;
import org.crforge.realistic.bridge.BattleSession;
import org.junit.jupiter.api.Test;

/** The built-in opponent plays a whole battle through the player's own path. */
class BotTest {

  @Test
  void twoBotsPlayABattleToItsEnd() {
    BattleSession s =
        BattleSession.ladder(
            GameData.tables(),
            BattleDecks.BLUE,
            BattleDecks.blueSlots(),
            BattleDecks.RED,
            BattleDecks.redSlots());
    Bot blue = new Bot(0, 1);
    Bot red = new Bot(1, 2);
    List<String> plays = new ArrayList<>();
    for (int tick = 0; tick < 20 * 400 && !s.isOver(); tick++) {
      var frame = BattleAdapter.frame(s);
      String a = blue.decide(s, frame);
      String b = red.decide(s, frame);
      if (a != null) plays.add("blue " + a);
      if (b != null) plays.add("red " + b);
      s.step();
    }
    assertThat(s.getHalted()).isNull();
    assertThat(blue.plays()).isGreaterThan(10);
    assertThat(red.plays()).isGreaterThan(10);
    // it defends and attacks, not only one of them
    assertThat(plays).anyMatch(p -> p.contains("defend"));
    assertThat(plays).anyMatch(p -> p.contains("attack"));
  }

  @Test
  void theBotDoesNothingBeforeItsFirstDecision() {
    BattleSession s = BattleSession.ladder(GameData.tables());
    Bot bot = new Bot(1, 3);
    for (int tick = 0; tick < Bot.FIRST_DECISION; tick++) {
      assertThat(bot.decide(s, BattleAdapter.frame(s))).isNull();
      s.step();
    }
  }
}
