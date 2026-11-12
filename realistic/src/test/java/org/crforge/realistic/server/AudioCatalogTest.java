/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioCatalogTest {

  private static final String INDEX =
      """
      {"format": 2, "banks": {"b": {"s1": 0.5, "s2": 1.25}},
       "events": {"b/b_hit": {"path": "event:/x", "volume_db": -3.0, "max_instances": 2147483647,
         "steal": 64, "cooldown": 0.1, "layers": [{"start": 0.2, "length": 1.0, "probability": 0.5,
         "choices": [{"sample": "s1", "weight": 0.5, "volume_db": -6.0, "pitch": 1.0,
           "rand_volume_db": 2.0, "rand_pitch": 0.5, "delay": [0.0, 0.1], "loop": false},
          {"sample": "s2", "weight": 0.5, "volume_db": 0.0, "pitch": 0.0, "rand_volume_db": 0.0,
           "rand_pitch": 0.0, "delay": [0.0, 0.0], "loop": true}]}]}}}
      """;

  @Test
  void theCompactIndexKeepsWhatThePagePlays() throws IOException {
    JsonNode c = AudioCatalog.compact(new ObjectMapper().readTree(INDEX));
    JsonNode ev = c.path("events").path("b/b_hit");
    assertThat(ev.path("v").asDouble()).isEqualTo(-3.0);
    assertThat(ev.path("cd").asDouble()).isEqualTo(0.1);
    assertThat(ev.has("mi")).isFalse(); // no limit
    JsonNode layer = ev.path("l").path(0);
    assertThat(layer.path("s").asDouble()).isEqualTo(0.2);
    assertThat(layer.path("p").asDouble()).isEqualTo(0.5);
    assertThat(layer.path("c").path(0).toString())
        .isEqualTo("[\"s1\",0.5,-6.0,1.0,2.0,0.5,0.0,0.1,0]");
    assertThat(layer.path("c").path(1).path(8).asInt()).isEqualTo(1);
    assertThat(c.path("banks").path("b").path("s2").asDouble()).isEqualTo(1.25);
  }

  @Test
  void samplesAreServedFromTheCacheOnlyAndEventsTimed(@TempDir Path dir) throws IOException {
    Path art = dir.resolve("art");
    Files.createDirectories(art.resolve("audio/b"));
    Files.writeString(art.resolve("audio/index.json"), INDEX);
    Files.write(art.resolve("audio/b/s1.ogg"), new byte[] {1, 2, 3});
    Files.writeString(art.resolve("secret.txt"), "no");
    AudioCatalog a = AudioCatalog.open(art, dir.resolve("transcoded")).orElseThrow();
    assertThat(a.sample("b", "s1.ogg")).hasValueSatisfying(b -> assertThat(b).hasSize(3));
    assertThat(a.sample("b", "missing.ogg")).isEmpty();
    assertThat(a.sample("..", "secret.txt")).isEmpty();
    assertThat(a.sample("b", "..%2Fsecret.ogg")).isEmpty();
    assertThat(a.sample("b", "s1.wav")).isEmpty();
    assertThat(a.eventSeconds("soundbanks/b/b_hit")).isEqualTo(1.45);
    assertThat(a.eventSeconds("soundbanks/b/none")).isZero();
    assertThat(AudioCatalog.key("soundbanks/b/b_hit")).isEqualTo("b/b_hit");
  }

  @Test
  void aCacheWithoutAudioHasNone(@TempDir Path dir) {
    assertThat(AudioCatalog.open(dir, dir.resolve("t"))).isEmpty();
  }
}
