/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServeOptionsTest {

  @Test
  void theDefaultsListenOnThisMachineOnly() {
    ServeOptions o = ServeOptions.parse(new String[] {"--serve"});
    assertThat(ServeOptions.wanted(new String[] {"--serve"})).isTrue();
    assertThat(ServeOptions.wanted(new String[] {"--realistic"})).isFalse();
    assertThat(o.lan()).isFalse();
    assertThat(o.port()).isEqualTo(ServeOptions.DEFAULT_PORT);
    assertThat(o.token()).isNull();
    assertThat(o.width()).isEqualTo(720);
    assertThat(o.render()).isEqualTo(ServeOptions.LIST);
    assertThat(o.height()).isEqualTo(1560);
    assertThat(o.fps()).isEqualTo(30);
  }

  @Test
  void theOptionsAreRead() {
    ServeOptions o =
        ServeOptions.parse(
            new String[] {
              "--serve",
              "--lan",
              "--port",
              "9000",
              "--token",
              "abcdefgh12",
              "--size",
              "540x1170",
              "--fps",
              "24",
              "--quality",
              "60",
              "--location",
              "PvP_ice"
            });
    assertThat(o.lan()).isTrue();
    assertThat(o.port()).isEqualTo(9000);
    assertThat(o.token()).isEqualTo("abcdefgh12");
    assertThat(o.width()).isEqualTo(540);
    assertThat(o.height()).isEqualTo(1170);
    assertThat(o.fps()).isEqualTo(24);
    assertThat(o.quality()).isEqualTo(60);
    assertThat(o.location()).isEqualTo("PvP_ice");
  }

  @Test
  void aBadValueIsNamed() {
    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"--serve", "--port", "x"}))
        .hasMessageContaining("--port");
    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"--serve", "--size", "big"}))
        .hasMessageContaining("--size");
    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"--serve", "--fps", "500"}))
        .hasMessageContaining("--fps");
    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"--serve", "--render", "png"}))
        .hasMessageContaining("--render");
    assertThat(ServeOptions.parse(new String[] {"--serve", "--render", "jpeg"}).render())
        .isEqualTo(ServeOptions.JPEG);
    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"--serve", "--token"}))
        .hasMessageContaining("--token");
  }

  @Test
  void theStartupLinesCarryTheTokenAndTheWaysToAPhone() {
    ServeOptions local = ServeOptions.parse(new String[] {"--serve"});
    String text = String.join("\n", PhoneServer.urls(local, "tok12345", List.of("192.168.1.5")));
    assertThat(text).contains("http://127.0.0.1:8765/?t=tok12345").contains("--lan");
    assertThat(text).contains("tailscale serve 8765").contains("ngrok http 8765");
    assertThat(text).doesNotContain("192.168.1.5");
    ServeOptions lan = ServeOptions.parse(new String[] {"--serve", "--lan"});
    String lanText = String.join("\n", PhoneServer.urls(lan, "tok12345", List.of("192.168.1.5")));
    assertThat(lanText).contains("http://192.168.1.5:8765/?t=tok12345");
  }
}
