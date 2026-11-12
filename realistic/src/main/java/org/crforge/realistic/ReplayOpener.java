/*
 * crforge-realistic - the game's own look for crforge's battle simulator
 * SPDX-License-Identifier: Apache-2.0
 */

package org.crforge.realistic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.replay.ReplayCapture;
import org.crforge.realistic.bridge.DataSelection;
import org.crforge.realistic.bridge.ReplayFile;

/**
 * Opens a replay file on the tables of the data it was recorded on, as crforge's desktop launcher
 * does (its {@code openReplay}, re-implemented thinly here without its data version switcher): a
 * replay whose capture block names a content sha other than the loaded tables' is read and played
 * on the built version with that sha, which the launcher then uses. When no built version has it,
 * the loaded tables stay and the replay is refused, never played on other data. A replay without a
 * block is read on the loaded tables, its data version marked assumed. Plain replay files only (a
 * crawl's {@code .jsonl} output is not read here).
 */
final class ReplayOpener {

  private static final ObjectMapper JSON = new ObjectMapper();

  /** A replay and the tables it is played on. */
  record Opened(ReplayFile replay, GameTables tables) {}

  private ReplayOpener() {}

  /**
   * Reads the replay and prints its description (its battle header and every reason it is refused).
   *
   * @param file the replay file
   * @param choice the tables chosen (their root holds the other built versions)
   * @param loaded the chosen version's tables
   * @return the replay, refused or playable, and its tables; null when the file is not JSON
   */
  static Opened open(
      Path file, DataSelection.Choice choice, GameTables loaded, PrintStream out, PrintStream err) {
    JsonNode document;
    try {
      document = JSON.readTree(Files.readAllBytes(file));
    } catch (IOException | RuntimeException e) {
      err.println(
          "Cannot read the replay at " + file.toAbsolutePath().normalize() + ": " + e.getMessage());
      return null;
    }
    GameTables tables = loaded;
    String tablesRefusal = null;
    Optional<ReplayCapture> capture = ReplayCapture.of(document);
    if (capture.isPresent() && !capture.get().contentSha().equals(loaded.contentSha())) {
      String sha = capture.get().contentSha();
      tablesRefusal =
          "no data version built in "
              + choice.root().toAbsolutePath().normalize()
              + " has the content sha "
              + sha
              + " the replay was recorded on; it is not played on other data";
      for (String version : DataSelection.versions(choice.root())) {
        Path folder = choice.root().resolve(version);
        if (!sha.equals(DataSelection.contentSha(folder).orElse(null))) continue;
        try {
          tables = GameTables.load(folder);
          tablesRefusal = null;
          out.println(
              "the replay names content sha "
                  + sha
                  + ": switched from data version "
                  + loaded.version()
                  + " to "
                  + version
                  + " ("
                  + folder.toAbsolutePath().normalize()
                  + ")");
        } catch (RuntimeException e) {
          tablesRefusal =
              "cannot read the tables of data version "
                  + version
                  + " at "
                  + folder.toAbsolutePath().normalize()
                  + ", whose content sha the replay names: "
                  + e.getMessage();
        }
        break;
      }
    }
    ReplayFile replay;
    try {
      replay = ReplayFile.parse(file, 0, document, tables, tablesRefusal);
    } catch (RuntimeException e) {
      err.println(
          "Cannot read the replay at " + file.toAbsolutePath().normalize() + ": " + e.getMessage());
      return null;
    }
    for (String line : replay.describe()) out.println(line);
    return new Opened(replay, tables);
  }
}
