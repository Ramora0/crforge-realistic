/*
 * crforge-realistic - the game's own look for crforge's battle simulator
 * SPDX-License-Identifier: Apache-2.0
 */

package org.crforge.realistic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.realistic.bridge.DataSelection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RealisticLauncherTest {

  @Test
  @DisplayName(
      "--bottom names a side by number or by its colour in the default view; --pov is read")
  void bottomSideAndPlayerView() {
    assertThat(RealisticLauncher.bottomSide(new String[] {})).isZero();
    assertThat(RealisticLauncher.bottomSide(new String[] {"--bottom", "1"})).isEqualTo(1);
    assertThat(RealisticLauncher.bottomSide(new String[] {"--bottom", "red"})).isEqualTo(1);
    assertThat(RealisticLauncher.bottomSide(new String[] {"--bottom", "Blue"})).isZero();
    assertThatThrownBy(() -> RealisticLauncher.bottomSide(new String[] {"--bottom", "top"}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(RealisticLauncher.playerView(new String[] {"--record", "a.mp4", "--pov"})).isTrue();
    assertThat(RealisticLauncher.playerView(new String[] {"--player-view"})).isTrue();
    assertThat(RealisticLauncher.playerView(new String[] {"--record", "a.mp4"})).isFalse();
  }

  @Test
  @DisplayName("--size takes WIDTHxHEIGHT; --record and --replay name their files")
  void sizeAndFiles() {
    assertThat(RealisticLauncher.size(null, 540, 1170)).containsExactly(540, 1170);
    assertThat(RealisticLauncher.size("1080x2340", 540, 1170)).containsExactly(1080, 2340);
    assertThatThrownBy(() -> RealisticLauncher.size("big", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RealisticLauncher.size("0x10", 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(RealisticLauncher.recordArgument(new String[] {"--record", "out.mp4"}))
        .contains(Paths.get("out.mp4"));
    assertThat(RealisticLauncher.replayArgument(new String[] {"--replay", "r.json"}))
        .contains(Paths.get("r.json"));
    assertThatThrownBy(() -> RealisticLauncher.replayArgument(new String[] {"--replay"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("the example replay opens on the tables of the data version it was recorded on")
  void exampleReplayOpensOnItsOwnData() {
    GameTables tables = GameData.tables();
    Path folder = Paths.get(System.getProperty("crforge.gameTables"));
    DataSelection.Choice choice =
        new DataSelection.Choice(folder.getParent(), folder.getFileName().toString(), null, null);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    var opened =
        ReplayOpener.open(
            Paths.get(System.getProperty("crforge.exampleReplay")),
            choice,
            tables,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));

    assertThat(opened).isNotNull();
    assertThat(err.toString(StandardCharsets.UTF_8)).isEmpty();
    // played on its own data, built beside the data version's (crforge-data.lock's compatible)
    assertThat(opened.replay().refusals()).isEmpty();
    assertThat(opened.replay().playable()).isTrue();
    assertThat(opened.replay().dataNamed()).isTrue();
    assertThat(opened.tables().contentSha())
        .isEqualTo(opened.replay().capture().orElseThrow().contentSha());
  }
}
