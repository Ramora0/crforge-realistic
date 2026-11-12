/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The access token, and the web server refusing requests without it. */
class AccessTest {

  @Test
  void aNewTokenIsWellFormedAndNewEachTime() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      String t = Access.newToken();
      assertThat(Access.wellFormed(t)).isTrue();
      assertThat(t).hasSize(22);
      seen.add(t);
    }
    assertThat(seen).hasSize(50);
  }

  @Test
  void onlyTheTokenItselfIsAllowed() {
    Access a = new Access("secretToken_42");
    assertThat(a.matches("secretToken_42")).isTrue();
    assertThat(a.matches("secretToken_4")).isFalse();
    assertThat(a.matches("secretToken_43")).isFalse();
    assertThat(a.matches("")).isFalse();
    assertThat(a.matches(null)).isFalse();
    assertThat(a.allows(Map.of("t", "secretToken_42"), null)).isTrue();
    assertThat(a.allows(Map.of("t", "nope"), null)).isFalse();
    assertThat(a.allows(Map.of(), null)).isFalse();
    assertThat(a.allows(Map.of(), "a=1; crforge_t=secretToken_42; b=2")).isTrue();
    assertThat(a.allows(Map.of(), "crforge_t=other")).isFalse();
  }

  @Test
  void aMalformedTokenIsRefused() {
    assertThat(Access.wellFormed("short")).isFalse();
    assertThat(Access.wellFormed("has space in it")).isFalse();
    assertThat(Access.wellFormed("semi;colon;token")).isFalse();
    assertThatThrownBy(() -> new Access("bad token")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ServeOptions.parse(new String[] {"--serve", "--token", "x"}))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void theWebSocketAcceptKeyIsTheRfcs() {
    // RFC 6455, section 1.3
    assertThat(WebServer.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
        .isEqualTo("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
  }

  @Test
  void aRequestHeadIsRead() throws IOException {
    String head = "GET /audio/a%20b/c.ogg?t=abc%2Bd&x=1 HTTP/1.1\r\nHost: h\r\nCookie: k=v\r\n\r\n";
    WebServer.Request r =
        WebServer.readRequest(new ByteArrayInputStream(head.getBytes(StandardCharsets.UTF_8)));
    assertThat(r.method()).isEqualTo("GET");
    assertThat(r.path()).isEqualTo("/audio/a b/c.ogg");
    assertThat(r.query()).containsEntry("t", "abc+d").containsEntry("x", "1");
    assertThat(r.header("COOKIE")).isEqualTo("k=v");
  }

  @Test
  void theServerRefusesThePageAndTheSocketWithoutTheToken() throws IOException {
    Access access = new Access("letMeIn_123");
    WebServer.Handler handler =
        new WebServer.Handler() {
          @Override
          public WebServer.Response http(WebServer.Request req) {
            if (!access.allows(req.query(), req.header("cookie"))) {
              return WebServer.Response.text(401, "no");
            }
            return WebServer.Response.text(200, "yes");
          }

          @Override
          public WebServer.Listener socket(WebServer.Request req) {
            if (!access.allows(req.query(), req.header("cookie"))) return null;
            return new WebServer.Listener() {
              @Override
              public void open(WebServer.Socket socket) {
                socket.sendText("hi");
              }

              @Override
              public void text(WebServer.Socket socket, String text) {}

              @Override
              public void closed(WebServer.Socket socket) {}
            };
          }
        };
    try (WebServer web = WebServer.start("127.0.0.1", 0, handler)) {
      int port = web.port();
      assertThat(get(port, "/", "")).startsWith("HTTP/1.1 401");
      assertThat(get(port, "/?t=wrong_token_1", "")).startsWith("HTTP/1.1 401");
      assertThat(get(port, "/?t=letMeIn_123", "")).startsWith("HTTP/1.1 200");
      assertThat(get(port, "/", "Cookie: crforge_t=letMeIn_123\r\n")).startsWith("HTTP/1.1 200");
      String upgrade =
          "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n"
              + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n";
      assertThat(get(port, "/ws", upgrade)).startsWith("HTTP/1.1 401");
      String opened = get(port, "/ws?t=letMeIn_123", upgrade);
      assertThat(opened).startsWith("HTTP/1.1 101").contains("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=");
    }
  }

  /** Sends one request and reads what comes back within a short wait. */
  private static String get(int port, String target, String headers) throws IOException {
    try (Socket s = new Socket("127.0.0.1", port)) {
      s.setSoTimeout(1500);
      OutputStream out = s.getOutputStream();
      out.write(
          ("GET " + target + " HTTP/1.1\r\nHost: x\r\n" + headers + "Connection: close\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      InputStream in = s.getInputStream();
      byte[] buf = new byte[4096];
      StringBuilder sb = new StringBuilder();
      try {
        int n;
        while ((n = in.read(buf)) > 0) {
          sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
          if (sb.indexOf("\r\n\r\n") >= 0 && sb.toString().startsWith("HTTP/1.1 101")) break;
        }
      } catch (java.net.SocketTimeoutException e) {
        // what came is enough
      }
      return sb.toString();
    }
  }
}
