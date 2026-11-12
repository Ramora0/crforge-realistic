/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BattleScheduleTest {

  private static BattleSchedule schedule() {
    return new BattleSchedule(
        List.of(60.0, 30.0, 10.0, 1.0),
        List.of("warn60", "warn30", "count10", "count1"),
        List.of(180.0, 60.0, 30.0),
        List.of("music", "music60", "music30"),
        "jingle",
        "suddenDeath");
  }

  @Test
  void theBattleOpensWithItsJingleAndMusic() {
    List<BattleSchedule.Cue> cues = schedule().advance(180, false);
    assertThat(cues)
        .containsExactly(
            new BattleSchedule.Cue(false, "jingle"), new BattleSchedule.Cue(true, "music"));
  }

  @Test
  void eachTimeLeftIsCuedOnceAsTheClockCrossesIt() {
    BattleSchedule s = schedule();
    s.advance(180, false);
    List<BattleSchedule.Cue> all = new ArrayList<>();
    for (double left = 179.95; left >= 0; left -= 0.05) all.addAll(s.advance(left, false));
    assertThat(all)
        .containsExactly(
            new BattleSchedule.Cue(false, "warn60"),
            new BattleSchedule.Cue(true, "music60"),
            new BattleSchedule.Cue(false, "warn30"),
            new BattleSchedule.Cue(true, "music30"),
            new BattleSchedule.Cue(false, "count10"),
            new BattleSchedule.Cue(false, "count1"));
    List<BattleSchedule.Cue> overtime = s.advance(0, true);
    assertThat(overtime)
        .containsExactly(
            new BattleSchedule.Cue(true, "suddenDeath"),
            new BattleSchedule.Cue(false, BattleSchedule.EXTRA_TIME_JINGLE));
    assertThat(s.advance(0, true)).isEmpty();
  }

  @Test
  void aBattleJoinedLateHearsTheMusicOfItsTimeWithoutTheJingle() {
    List<BattleSchedule.Cue> cues = schedule().advance(45, false);
    assertThat(cues).containsExactly(new BattleSchedule.Cue(true, "music60"));
  }

  @Test
  void theClientGlobalsArraysAreRead() {
    List<String[]> rows =
        List.of(
            new String[] {" ", "StringArray", "NumberArray"},
            new String[] {"String", "StringArray", "IntArray"},
            new String[] {"OTHER", "x", ""},
            new String[] {"SOUNDS_PVP", "a", ""},
            new String[] {"", "b", ""},
            new String[] {"SOUND_START_TIMES", "", "60"},
            new String[] {"", "", "30"});
    assertThat(BattleSchedule.array(rows, "SOUNDS_PVP", "StringArray")).containsExactly("a", "b");
    assertThat(BattleSchedule.array(rows, "SOUND_START_TIMES", "NumberArray"))
        .containsExactly("60", "30");
    assertThat(BattleSchedule.split("\"a,b\",c,\"d\"\"e\"")).containsExactly("a,b", "c", "d\"e");
  }
}
