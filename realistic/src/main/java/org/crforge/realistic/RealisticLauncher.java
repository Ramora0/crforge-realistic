/*
 * crforge-realistic - the game's own look for crforge's battle simulator
 * SPDX-License-Identifier: Apache-2.0
 */

package org.crforge.realistic;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.crforge.core.battle.data.GameTables;
import org.crforge.realistic.bridge.DataSelection;
import org.crforge.realistic.render.art.CinematicRecorder;
import org.crforge.realistic.render.art.model.Sc3dLibrary;
import org.crforge.realistic.server.PhoneServer;
import org.crforge.realistic.server.ServeOptions;
import org.crforge.realistic.window.RealisticWindow;

/**
 * Starts the realistic view of crforge's battle simulator in one of three modes:
 *
 * <ul>
 *   <li>{@code --replay FILE --record OUT.mp4} records a replay offscreen to a video (or {@code
 *       .png}: one still), framed like a phone: {@code [--size 1080x2340] [--fps 60] [--from
 *       SECONDS] [--seconds N] [--bottom 0|1|blue|red] [--pov | --player-view] [--location
 *       PvP_royal]};
 *   <li>{@code --serve} plays battles in a phone's browser ({@link ServeOptions} for its flags);
 *   <li>otherwise a window: a replay ({@code --replay FILE}) or a live battle against the built-in
 *       bot, drawn as the recorder draws it ({@link RealisticWindow} for its keys and flags).
 * </ul>
 *
 * <p>In every mode {@code --sc3d <folder|off>} names the 3D models the sc3d extractor wrote
 * (default: the art cache's own) and {@code --sc3d-rows <prefixes|*>} the rows drawn with them.
 *
 * <p>The game tables are those crforge's build makes ({@link DataSelection}: {@code ./gradlew
 * :realistic:run} passes their folder); the art is the cache {@code crforge-art} extracts from your
 * own installation ({@code -Dcrforge.art}, else {@code ~/.crforge/art}; docs/GETTING-ASSETS.md).
 */
public final class RealisticLauncher {

  /** The exit code when the game tables are missing or unreadable. */
  static final int NO_TABLES = 1;

  /** The exit code when the replay given cannot be read. */
  static final int NO_REPLAY = 2;

  /** The exit code when the arguments cannot be read. */
  static final int BAD_ARGUMENTS = 3;

  /** The exit code when the phone server's arguments cannot be read. */
  static final int NO_SERVER = 4;

  static final String REPLAY_ARGUMENT = "--replay";

  static final String RECORD_ARGUMENT = "--record";

  private RealisticLauncher() {}

  public static void main(String[] args) {
    if (Arrays.asList(args).contains("--help") || Arrays.asList(args).contains("-h")) {
      usage(System.out);
      return;
    }
    // --sc3d <folder|off>: the 3D models the sc3d extractor wrote (default: the art cache's own);
    // --sc3d-rows <prefixes|*>: the rows drawn with them (default: those checked, DEFAULT_ROWS)
    String sc3d = option(args, "--sc3d");
    if (sc3d != null) System.setProperty(Sc3dLibrary.PROPERTY, sc3d);
    String sc3dRows = option(args, "--sc3d-rows");
    if (sc3dRows != null) System.setProperty(Sc3dLibrary.ROWS_PROPERTY, sc3dRows);
    String location = option(args, "--location");

    DataSelection.Choice choice = DataSelection.ofProcess();
    GameTables tables = loadTables(choice, System.out, System.err);
    if (tables == null) {
      System.exit(NO_TABLES);
      return;
    }

    if (ServeOptions.wanted(args)) {
      // --serve: the phone server (docs/phone-server.md); it plays its own battles
      ServeOptions serve;
      try {
        serve = ServeOptions.parse(args);
      } catch (IllegalArgumentException e) {
        System.err.println(e.getMessage());
        System.exit(NO_SERVER);
        return;
      }
      PhoneServer.launch(tables, serve, System.out);
      return;
    }

    Optional<Path> replayFile;
    int bottom;
    try {
      replayFile = replayArgument(args);
      bottom = bottomSide(args);
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(BAD_ARGUMENTS);
      return;
    }
    ReplayOpener.Opened replay = null;
    if (replayFile.isPresent()) {
      replay = ReplayOpener.open(replayFile.get(), choice, tables, System.out, System.err);
      if (replay == null) {
        System.exit(NO_REPLAY);
        return;
      }
    }

    Optional<Path> record = recordArgument(args);
    if (record.isPresent()) {
      if (replay == null) {
        System.err.println(RECORD_ARGUMENT + " records a replay: name it with " + REPLAY_ARGUMENT);
        System.exit(BAD_ARGUMENTS);
        return;
      }
      if (!replay.replay().playable()) {
        System.err.println("The replay cannot be played; nothing to record");
        System.exit(NO_REPLAY);
        return;
      }
      if (location != null) System.setProperty("crforge.art.location", location);
      record(replay, record.get(), bottom, args);
      return;
    }

    RealisticWindow.Settings settings;
    try {
      int[] size = size(option(args, "--size"), 540, 1170);
      settings =
          new RealisticWindow.Settings(
              size[0],
              size[1],
              bottom,
              playerView(args) || replay == null,
              location,
              Optional.ofNullable(option(args, "--deck")).orElse("blue"),
              Optional.ofNullable(option(args, "--opponent-deck")).orElse("red"));
    } catch (IllegalArgumentException e) {
      System.err.println(e.getMessage());
      System.exit(BAD_ARGUMENTS);
      return;
    }
    RealisticWindow.launch(
        replay == null ? tables : replay.tables(),
        replay == null ? null : replay.replay(),
        settings);
  }

  /** The lines {@code --help} prints. */
  static void usage(PrintStream out) {
    for (String line :
        List.of(
            "crforge-realistic: the game's own look for crforge's battle simulator",
            "",
            "  ./gradlew :realistic:run -Pcrforge.assetSource=cdn --args=\"...\"",
            "",
            "Record a replay to a video (or a .png still):",
            "  --replay FILE --record OUT.mp4 [--size 1080x2340] [--fps 60] [--from SECONDS]",
            "      [--seconds N] [--bottom 0|1|blue|red] [--pov | --player-view] [--location ROW]",
            "Play from your phone's browser (docs/phone-server.md):",
            "  --serve [--port 8765] [--lan] [--token X] [--size 720x1560] [--fps 30]",
            "      [--quality 70] [--location PvP_royal] [--audio-cache DIR] [--render list|jpeg]",
            "A window: a replay, or (without --replay) a live battle against the bot:",
            "  [--replay FILE] [--size 540x1170] [--bottom 0|1|blue|red] [--pov]",
            "      [--location ROW] [--deck ID] [--opponent-deck ID]",
            "  keys: SPACE pause, U player's view, F flip, +/- speed, . step (paused),",
            "        R restart, ESC quit; click a card, then the arena, to play it",
            "Every mode: [--sc3d DIR|off] [--sc3d-rows PREFIXES|*]")) {
      out.println(line);
    }
  }

  /**
   * Loads the chosen game tables and prints what was loaded, or prints why nothing was.
   *
   * @return the tables, or null when none are chosen or they cannot be read
   */
  static GameTables loadTables(DataSelection.Choice choice, PrintStream out, PrintStream err) {
    if (choice.problem() != null) {
      err.println(choice.problem());
      return null;
    }
    GameTables tables;
    try {
      tables = GameTables.load(choice.folder());
    } catch (RuntimeException e) {
      List<String> versions = DataSelection.versions(choice.root());
      err.println(
          "Cannot read the game tables of data version "
              + choice.version()
              + " at "
              + choice.folder().toAbsolutePath().normalize()
              + ": "
              + e.getMessage()
              + "; data versions built: "
              + (versions.isEmpty() ? "none" : String.join(", ", versions)));
      return null;
    }
    for (String line : DataSelection.describe(choice, tables)) out.println(line);
    return tables;
  }

  /**
   * Records the replay with the game's art to a video or a still, offscreen, in a hidden window.
   */
  private static void record(ReplayOpener.Opened replay, Path out, int bottom, String[] args) {
    int[] wh = size(option(args, "--size"), 1080, 2340);
    int fps = Integer.parseInt(Optional.ofNullable(option(args, "--fps")).orElse("60"));
    float from = Float.parseFloat(Optional.ofNullable(option(args, "--from")).orElse("0"));
    float seconds = Float.parseFloat(Optional.ofNullable(option(args, "--seconds")).orElse("600"));
    var settings =
        new CinematicRecorder.Settings(
            out, wh[0], wh[1], fps, from, seconds, bottom, playerView(args));
    Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
    config.setTitle("crforge-realistic - recording");
    config.setWindowedMode(320, 240);
    // a position of its own: no monitor is asked for (a sleeping display has none)
    config.setWindowPosition(0, 0);
    config.setInitialVisible(false);
    config.useVsync(false);
    config.setForegroundFPS(0);
    new Lwjgl3Application(
        new CinematicRecorder(replay.replay(), replay.tables(), settings), config);
  }

  /** The file the arguments name with {@value #REPLAY_ARGUMENT}. */
  static Optional<Path> replayArgument(String[] args) {
    for (int i = 0; i < args.length; i++) {
      if (REPLAY_ARGUMENT.equals(args[i])) {
        if (i + 1 >= args.length || args[i + 1].isBlank()) {
          throw new IllegalArgumentException(REPLAY_ARGUMENT + " names no replay file");
        }
        return Optional.of(Paths.get(args[i + 1]));
      }
    }
    return Optional.empty();
  }

  /** The video (or {@code .png} still) the arguments name with {@value #RECORD_ARGUMENT}. */
  static Optional<Path> recordArgument(String[] args) {
    String v = option(args, RECORD_ARGUMENT);
    return v == null ? Optional.empty() : Optional.of(Paths.get(v));
  }

  static String option(String[] args, String name) {
    for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return args[i + 1];
    return null;
  }

  /** Whether the arguments ask for the player's view ({@code --pov} or {@code --player-view}). */
  static boolean playerView(String[] args) {
    List<String> list = Arrays.asList(args);
    return list.contains("--pov") || list.contains("--player-view");
  }

  /**
   * The side {@code --bottom} names: 0 or 1, or "blue" (side 0) or "red" (side 1) as the default
   * view (side 0 at the bottom) colours them; 0 when it is not given.
   */
  static int bottomSide(String[] args) {
    String v = option(args, "--bottom");
    if (v == null) return 0;
    return switch (v.toLowerCase(Locale.ROOT)) {
      case "0", "blue" -> 0;
      case "1", "red" -> 1;
      default -> throw new IllegalArgumentException("--bottom takes 0, 1, blue or red, not " + v);
    };
  }

  /** {@code WIDTHxHEIGHT}, or the default when it is not given. */
  static int[] size(String value, int width, int height) {
    if (value == null) return new int[] {width, height};
    String[] wh = value.toLowerCase(Locale.ROOT).split("x");
    try {
      int w = Integer.parseInt(wh[0]);
      int h = Integer.parseInt(wh[1]);
      if (wh.length == 2 && w > 0 && h > 0) return new int[] {w, h};
    } catch (RuntimeException e) {
      // below
    }
    throw new IllegalArgumentException("--size takes WIDTHxHEIGHT, not " + value);
  }
}
