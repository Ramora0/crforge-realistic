/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * The battle sounds of the art cache ({@code <cache>/audio}, written by {@code crforge-art extract
 * --audio}) as the browser plays them: a compact copy of {@code audio/index.json} (each event's
 * layers, their choices, pitch and volume, as {@code tools/game-art/crforge_art/audio.py} reads
 * them) and the samples, as the cache's .ogg or, for a browser that cannot decode Ogg Vorbis (iOS
 * Safari before 18.4), transcoded once to AAC (.m4a) by ffmpeg into a cache folder outside the art
 * cache.
 */
@Slf4j
public final class AudioCatalog {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Path audio;
  private final Path transcoded;
  private final JsonNode index;
  private final byte[] compact;
  private final ConcurrentHashMap<Path, Object> locks = new ConcurrentHashMap<>();

  private AudioCatalog(Path audio, Path transcoded, JsonNode index) {
    this.audio = audio;
    this.transcoded = transcoded;
    this.index = index;
    this.compact = compact(index).toString().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The sounds of an art cache's folder, or empty when it has none (extracted without {@code
   * --audio}).
   *
   * @param artFolder the art cache's content folder
   * @param transcodeRoot where transcoded samples are kept; a folder per art cache is made in it
   */
  public static Optional<AudioCatalog> open(Path artFolder, Path transcodeRoot) {
    Path audio = artFolder.resolve("audio");
    Path indexFile = audio.resolve("index.json");
    if (!Files.isRegularFile(indexFile)) return Optional.empty();
    try {
      JsonNode index = JSON.readTree(indexFile.toFile());
      if (index.path("format").asInt() != 2) {
        log.warn("{} is not of format 2: re-extract the audio", indexFile);
        return Optional.empty();
      }
      Path transcoded = transcodeRoot.resolve(artFolder.getFileName().toString());
      return Optional.of(new AudioCatalog(audio, transcoded, index));
    } catch (IOException e) {
      log.warn("Cannot read {}: {}", indexFile, e.getMessage());
      return Optional.empty();
    }
  }

  /** The compact index, as JSON bytes. */
  public byte[] compactIndex() {
    return compact;
  }

  /**
   * The compact form: {@code {"banks": {bank: {sample: seconds}}, "events": {"bank/event": {"v":
   * volume dB, "cd": cooldown s, "mi": max instances, "st": steal flags, "l": [{"s": start, "n":
   * length, "p": probability, "c": [[sample, weight, volume dB, pitch, random volume dB, random
   * pitch, min delay, max delay, loop 0|1], ...]}]}}}}.
   */
  static ObjectNode compact(JsonNode index) {
    ObjectNode out = JSON.createObjectNode();
    out.set("banks", index.path("banks").deepCopy());
    ObjectNode events = out.putObject("events");
    index
        .path("events")
        .properties()
        .forEach(
            e -> {
              JsonNode ev = e.getValue();
              ObjectNode c = events.putObject(e.getKey());
              c.put("v", round(ev.path("volume_db").asDouble(0)));
              c.put("cd", round(ev.path("cooldown").asDouble(0)));
              int mi = ev.path("max_instances").asInt(Integer.MAX_VALUE);
              if (mi < 1000) c.put("mi", mi);
              c.put("st", ev.path("steal").asInt(64));
              ArrayNode layers = c.putArray("l");
              for (JsonNode layer : ev.path("layers")) {
                ObjectNode l = layers.addObject();
                l.put("s", round(layer.path("start").asDouble(0)));
                l.put("n", round(layer.path("length").asDouble(0)));
                l.put("p", round(layer.path("probability").asDouble(1)));
                ArrayNode choices = l.putArray("c");
                for (JsonNode ch : layer.path("choices")) {
                  ArrayNode a = choices.addArray();
                  if (ch.path("sample").isNull() || ch.path("sample").isMissingNode()) a.addNull();
                  else a.add(ch.path("sample").asText());
                  a.add(round(ch.path("weight").asDouble(1)));
                  a.add(round(ch.path("volume_db").asDouble(0)));
                  a.add(round(ch.path("pitch").asDouble(0)));
                  a.add(round(ch.path("rand_volume_db").asDouble(0)));
                  a.add(round(ch.path("rand_pitch").asDouble(0)));
                  JsonNode d = ch.path("delay");
                  a.add(round(d.path(0).asDouble(0)));
                  a.add(round(d.path(1).asDouble(0)));
                  a.add(ch.path("loop").asBoolean(false) ? 1 : 0);
                }
              }
            });
    return out;
  }

  private static double round(double v) {
    return Math.round(v * 10000) / 10000.0;
  }

  /**
   * How long an event plays in seconds: its longest layer's start plus its longest sample (as
   * {@code audio.py}'s {@code _event_seconds}, without trimming a silent tail); 0 for an unknown
   * event.
   *
   * @param ref {@code soundbanks/<bank>/<event>} or {@code <bank>/<event>}
   */
  public double eventSeconds(String ref) {
    String key = key(ref);
    JsonNode ev = index.path("events").path(key);
    if (ev.isMissingNode()) return 0;
    String bank = key.substring(0, key.indexOf('/'));
    JsonNode names = index.path("banks").path(bank);
    double out = 0;
    for (JsonNode layer : ev.path("layers")) {
      for (JsonNode c : layer.path("choices")) {
        double len = names.path(c.path("sample").asText("")).asDouble(0);
        out = Math.max(out, layer.path("start").asDouble(0) + len);
      }
    }
    return out;
  }

  /** {@code soundbanks/<bank>/<event>} as the index keys it, {@code <bank>/<event>}. */
  static String key(String ref) {
    String[] parts = ref.replaceAll("^/+|/+$", "").split("/");
    if (parts.length < 2) return ref;
    return parts[parts.length - 2] + "/" + parts[parts.length - 1];
  }

  /**
   * A sample's bytes, or empty for none.
   *
   * @param bank the bank folder
   * @param file the sample's name with its extension: {@code .ogg} as extracted, {@code .m4a}
   *     transcoded on the first request
   */
  public Optional<byte[]> sample(String bank, String file) throws IOException {
    if (!safe(bank) || !safe(file)) return Optional.empty();
    String name;
    boolean aac;
    if (file.endsWith(".ogg")) {
      name = file.substring(0, file.length() - 4);
      aac = false;
    } else if (file.endsWith(".m4a")) {
      name = file.substring(0, file.length() - 4);
      aac = true;
    } else {
      return Optional.empty();
    }
    Path ogg = audio.resolve(bank).resolve(name + ".ogg").normalize();
    if (!ogg.startsWith(audio) || !Files.isRegularFile(ogg)) return Optional.empty();
    if (!aac) return Optional.of(Files.readAllBytes(ogg));
    Path m4a = transcoded.resolve(bank).resolve(name + ".m4a").normalize();
    if (!m4a.startsWith(transcoded)) return Optional.empty();
    if (!Files.isRegularFile(m4a)) {
      synchronized (locks.computeIfAbsent(m4a, k -> new Object())) {
        if (!Files.isRegularFile(m4a)) transcode(ogg, m4a);
      }
    }
    return Files.isRegularFile(m4a) ? Optional.of(Files.readAllBytes(m4a)) : Optional.empty();
  }

  /** A path segment with no separator and no parent reference. */
  static boolean safe(String segment) {
    return segment != null
        && !segment.isEmpty()
        && !segment.contains("/")
        && !segment.contains("\\")
        && !segment.contains("..")
        && !segment.contains("\0");
  }

  private static void transcode(Path ogg, Path m4a) throws IOException {
    Files.createDirectories(m4a.getParent());
    Path part = m4a.resolveSibling(m4a.getFileName() + ".part.m4a");
    Process p =
        new ProcessBuilder(
                "ffmpeg",
                "-v",
                "error",
                "-y",
                "-i",
                ogg.toString(),
                "-c:a",
                "aac",
                "-b:a",
                "128k",
                "-movflags",
                "+faststart",
                part.toString())
            .redirectErrorStream(true)
            .start();
    try {
      byte[] err = p.getInputStream().readAllBytes();
      if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
        log.warn("ffmpeg could not transcode {}: {}", ogg, new String(err, StandardCharsets.UTF_8));
        Files.deleteIfExists(part);
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      p.destroy();
      return;
    }
    Files.move(part, m4a, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
  }
}
