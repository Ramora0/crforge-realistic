/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The sounds and music the game times itself in a battle, as {@code
 * tools/game-art/crforge_art/audio.py}'s {@code battle_schedule} reads them from the client tables:
 * {@code client_globals} SOUNDS_PVP at SOUND_START_TIMES (the 60 s and 30 s warnings, the last ten
 * seconds' countdown) and MUSICS_PVP at MUSIC_START_TIMES (the battle music and its 60 s and 30 s
 * loops, named in {@code music.csv}), the location's start jingle, and at overtime the location's
 * {@code ExtraTimeMusic} and the extra-time jingle. A live battle is fed its clock tick by tick and
 * hands back what starts.
 */
public final class BattleSchedule {

  /** One cue: a sound, or a music change (a looping music event; null ref stops the music). */
  public record Cue(boolean music, String ref) {}

  static final String EXTRA_TIME_JINGLE =
      "soundbanks/ui_battle_general/ui_battle_general_extra_time_jingle";

  private final List<double[]> soundTimes = new ArrayList<>();
  private final List<String> sounds = new ArrayList<>();
  private final List<double[]> musicTimes = new ArrayList<>();
  private final List<String> musics = new ArrayList<>();
  private final String startJingle;
  private final String overtimeMusic;
  private double lastLeft = Double.NaN;
  private boolean overtime;
  private boolean started;

  BattleSchedule(
      List<Double> soundAt,
      List<String> sounds,
      List<Double> musicAt,
      List<String> musics,
      String startJingle,
      String overtimeMusic) {
    for (int i = 0; i < sounds.size() && i < soundAt.size(); i++) {
      soundTimes.add(new double[] {soundAt.get(i)});
      this.sounds.add(sounds.get(i));
    }
    for (int i = 0; i < musics.size() && i < musicAt.size(); i++) {
      if (musics.get(i) == null) continue;
      musicTimes.add(new double[] {musicAt.get(i)});
      this.musics.add(musics.get(i));
    }
    this.startJingle = startJingle;
    this.overtimeMusic = overtimeMusic;
  }

  /** The schedule of a location, read from an art cache's client tables; empty when absent. */
  public static BattleSchedule read(Path artFolder, String location) {
    Path client = artFolder.resolve("client");
    List<String[]> globals = csv(client.resolve("csv_client/client_globals.csv"));
    Map<String, String> musicRefs = new HashMap<>();
    for (String[] r : csv(client.resolve("csv_client/music.csv"))) {
      if (r.length >= 3 && !r[2].isEmpty() && !musicRefs.containsKey(r[0])) {
        String bank = Path.of(r[1]).getFileName().toString().replace(".bank", "");
        musicRefs.put(r[0], "soundbanks/" + bank + "/" + r[2].substring(r[2].lastIndexOf('/') + 1));
      }
    }
    String jingle = null;
    String extra = null;
    List<String[]> locations = csv(client.resolve("csv_logic/locations.csv"));
    if (!locations.isEmpty()) {
      String[] header = locations.get(0);
      int sound = indexOf(header, "Sound");
      int extraTime = indexOf(header, "ExtraTimeMusic");
      for (String[] r : locations) {
        if (r.length > 0 && r[0].equals(location)) {
          jingle = cell(r, sound);
          extra = cell(r, extraTime);
        }
      }
    }
    List<String> sounds = array(globals, "SOUNDS_PVP", "StringArray");
    List<Double> times = numbers(array(globals, "SOUND_START_TIMES", "NumberArray"));
    // the times list has one more entry (180, the start) than the sounds: the last ones pair
    if (times.size() > sounds.size())
      times = times.subList(times.size() - sounds.size(), times.size());
    List<String> musicNames = array(globals, "MUSICS_PVP", "StringArray");
    List<Double> musicTimes = numbers(array(globals, "MUSIC_START_TIMES", "NumberArray"));
    List<String> musics = new ArrayList<>();
    for (String m : musicNames) musics.add(musicRefs.get(m));
    String overtimeMusic = musicRefs.get(extra == null || extra.isEmpty() ? "Sudden_death" : extra);
    return new BattleSchedule(times, sounds, musicTimes, musics, jingle, overtimeMusic);
  }

  /** The location's start jingle, or null. */
  public String startJingle() {
    return startJingle;
  }

  /**
   * What starts as the clock moves on to a tick: at the first call, the start jingle and the music
   * playing at that time; then every sound and music whose time left is crossed; at overtime, its
   * music and jingle.
   *
   * @param secondsLeft the regulation time left, in seconds (0 in overtime)
   * @param inOvertime whether the match is in overtime
   */
  public List<Cue> advance(double secondsLeft, boolean inOvertime) {
    List<Cue> out = new ArrayList<>();
    if (!started) {
      started = true;
      if (secondsLeft >= 179.5 && startJingle != null && !startJingle.isEmpty()) {
        out.add(new Cue(false, startJingle));
      }
      String playing = null;
      double best = Double.MAX_VALUE;
      for (int i = 0; i < musicTimes.size(); i++) {
        double at = musicTimes.get(i)[0];
        if (secondsLeft <= at + 1e-6 && at < best) {
          best = at;
          playing = musics.get(i);
        }
      }
      if (playing != null && !inOvertime) out.add(new Cue(true, playing));
      lastLeft = secondsLeft;
    } else if (!inOvertime) {
      for (int i = 0; i < soundTimes.size(); i++) {
        double at = soundTimes.get(i)[0];
        if (lastLeft > at && secondsLeft <= at) out.add(new Cue(false, sounds.get(i)));
      }
      for (int i = 0; i < musicTimes.size(); i++) {
        double at = musicTimes.get(i)[0];
        if (lastLeft > at && secondsLeft <= at) out.add(new Cue(true, musics.get(i)));
      }
      lastLeft = secondsLeft;
    }
    if (inOvertime && !overtime) {
      overtime = true;
      if (overtimeMusic != null) out.add(new Cue(true, overtimeMusic));
      out.add(new Cue(false, EXTRA_TIME_JINGLE));
    }
    return out;
  }

  // ---------------------------------------------------------------- client CSV

  private static List<String[]> csv(Path file) {
    List<String[]> out = new ArrayList<>();
    try {
      String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      for (String line : text.split("\r?\n")) out.add(split(line));
    } catch (IOException e) {
      // no schedule from a missing table
    }
    return out;
  }

  /** A client_globals array: the named row and its unnamed continuation rows. */
  static List<String> array(List<String[]> rows, String name, String column) {
    List<String> out = new ArrayList<>();
    if (rows.isEmpty()) return out;
    int col = indexOf(rows.get(0), column);
    if (col < 0) return out;
    boolean inside = false;
    for (int i = 2; i < rows.size(); i++) {
      String[] r = rows.get(i);
      String key = r.length > 0 ? r[0] : "";
      if (!key.isEmpty()) inside = key.equals(name);
      String v = cell(r, col);
      if (inside && v != null && !v.isEmpty()) out.add(v);
    }
    return out;
  }

  private static List<Double> numbers(List<String> values) {
    List<Double> out = new ArrayList<>();
    for (String v : values) {
      try {
        out.add(Double.parseDouble(v));
      } catch (NumberFormatException e) {
        // skipped
      }
    }
    return out;
  }

  private static String cell(String[] r, int i) {
    return i >= 0 && i < r.length ? r[i] : null;
  }

  private static int indexOf(String[] header, String column) {
    for (int i = 0; i < header.length; i++) if (header[i].trim().equals(column)) return i;
    return -1;
  }

  static String[] split(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
            cur.append('"');
            i++;
          } else quoted = false;
        } else cur.append(c);
      } else if (c == '"') quoted = true;
      else if (c == ',') {
        cells.add(cur.toString());
        cur.setLength(0);
      } else cur.append(c);
    }
    cells.add(cur.toString());
    return cells.toArray(new String[0]);
  }
}
