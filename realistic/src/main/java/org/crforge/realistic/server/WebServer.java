/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import lombok.extern.slf4j.Slf4j;

/**
 * A small HTTP/1.1 server with WebSocket upgrades (RFC 6455), on one port so a tunnel needs one
 * forward. Each connection runs on a virtual thread. It serves GET and HEAD only; the handler
 * decides every route and checks access.
 */
@Slf4j
public final class WebServer implements Closeable {

  /** One request: its path, its decoded query and its headers (names lower case). */
  public record Request(
      String method, String path, Map<String, String> query, Map<String, String> headers) {

    public String header(String name) {
      return headers.get(name.toLowerCase(Locale.ROOT));
    }
  }

  /** One response; {@code headers} are added to the defaults. */
  public record Response(int status, String contentType, byte[] body, Map<String, String> headers) {

    public static Response of(int status, String contentType, byte[] body) {
      return new Response(status, contentType, body, Map.of());
    }

    public static Response text(int status, String text) {
      return of(status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }
  }

  /** What a WebSocket's owner hears; called on the connection's reading thread. */
  public interface Listener {
    void open(Socket socket);

    void text(Socket socket, String text);

    default void binary(Socket socket, byte[] data) {}

    void closed(Socket socket);
  }

  /** The routes. */
  public interface Handler {
    /** The response to a plain request, never null. */
    Response http(Request request);

    /** The listener of a WebSocket opened at the request, or null to refuse it (401). */
    Listener socket(Request request);
  }

  private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
  private static final int MAX_HEADER = 16 * 1024;
  private static final int MAX_MESSAGE = 1 << 20;

  private final ServerSocket server;
  private final Handler handler;
  private volatile boolean running = true;

  private WebServer(ServerSocket server, Handler handler) {
    this.server = server;
    this.handler = handler;
  }

  /**
   * Starts listening.
   *
   * @param host the address to bind, such as 127.0.0.1 or 0.0.0.0
   * @param port the port
   */
  public static WebServer start(String host, int port, Handler handler) throws IOException {
    ServerSocket ss = new ServerSocket();
    ss.setReuseAddress(true);
    ss.bind(new InetSocketAddress(InetAddress.getByName(host), port), 64);
    WebServer ws = new WebServer(ss, handler);
    Thread.ofPlatform().daemon().name("web-accept").start(ws::acceptLoop);
    return ws;
  }

  public int port() {
    return server.getLocalPort();
  }

  private void acceptLoop() {
    while (running) {
      try {
        java.net.Socket s = server.accept();
        s.setTcpNoDelay(true);
        Thread.ofVirtual().name("web-conn").start(() -> serve(s));
      } catch (IOException e) {
        if (running) log.warn("accept: {}", e.getMessage());
      }
    }
  }

  @Override
  public void close() {
    running = false;
    try {
      server.close();
    } catch (IOException e) {
      // closing
    }
  }

  private void serve(java.net.Socket s) {
    try (s) {
      InputStream in = new BufferedInputStream(s.getInputStream(), 16 * 1024);
      OutputStream out = s.getOutputStream();
      while (running) {
        Request req = readRequest(in);
        if (req == null) return;
        if ("websocket".equalsIgnoreCase(req.header("upgrade"))) {
          upgrade(s, in, out, req);
          return;
        }
        Response res;
        try {
          res = handler.http(req);
        } catch (RuntimeException e) {
          log.warn("{} {}: {}", req.method(), req.path(), e.toString());
          res = Response.text(500, "error");
        }
        boolean close = "close".equalsIgnoreCase(req.header("connection"));
        write(out, req, res, close);
        if (close) return;
      }
    } catch (SocketException | EOFException e) {
      // the peer went away
    } catch (IOException e) {
      log.debug("connection: {}", e.getMessage());
    }
  }

  /** Reads a request head, or null at the end of the stream. */
  static Request readRequest(InputStream in) throws IOException {
    String line = readLine(in);
    while (line != null && line.isEmpty()) line = readLine(in);
    if (line == null) return null;
    String[] parts = line.split(" ");
    if (parts.length < 3) throw new IOException("bad request line");
    Map<String, String> headers = new LinkedHashMap<>();
    int total = line.length();
    for (String h = readLine(in); h != null && !h.isEmpty(); h = readLine(in)) {
      total += h.length();
      if (total > MAX_HEADER) throw new IOException("headers too large");
      int colon = h.indexOf(':');
      if (colon > 0) {
        headers.put(
            h.substring(0, colon).trim().toLowerCase(Locale.ROOT), h.substring(colon + 1).trim());
      }
    }
    String target = parts[1];
    int q = target.indexOf('?');
    String path = q < 0 ? target : target.substring(0, q);
    Map<String, String> query = q < 0 ? Map.of() : parseQuery(target.substring(q + 1));
    return new Request(
        parts[0].toUpperCase(Locale.ROOT),
        URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8),
        query,
        headers);
  }

  static Map<String, String> parseQuery(String q) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String kv : q.split("&")) {
      if (kv.isEmpty()) continue;
      int eq = kv.indexOf('=');
      String k = eq < 0 ? kv : kv.substring(0, eq);
      String v = eq < 0 ? "" : kv.substring(eq + 1);
      out.put(
          URLDecoder.decode(k, StandardCharsets.UTF_8),
          URLDecoder.decode(v, StandardCharsets.UTF_8));
    }
    return out;
  }

  private static String readLine(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    while (true) {
      int c = in.read();
      if (c < 0) return sb.isEmpty() ? null : sb.toString();
      if (c == '\n') break;
      if (c != '\r') sb.append((char) c);
      if (sb.length() > MAX_HEADER) throw new IOException("line too long");
    }
    return sb.toString();
  }

  private static void write(OutputStream out, Request req, Response res, boolean close)
      throws IOException {
    byte[] body = res.body() == null ? new byte[0] : res.body();
    boolean gzip =
        body.length > 1024
            && res.contentType() != null
            && (res.contentType().startsWith("text/")
                || res.contentType().startsWith("application/json")
                || res.contentType().startsWith("application/javascript"))
            && String.valueOf(req.header("accept-encoding")).contains("gzip");
    if (gzip) {
      ByteArrayOutputStream bos = new ByteArrayOutputStream(body.length / 4);
      try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
        gz.write(body);
      }
      body = bos.toByteArray();
    }
    StringBuilder head = new StringBuilder();
    head.append("HTTP/1.1 ").append(res.status()).append(' ').append(reason(res.status()));
    head.append("\r\nContent-Length: ").append(body.length);
    if (res.contentType() != null) head.append("\r\nContent-Type: ").append(res.contentType());
    if (gzip) head.append("\r\nContent-Encoding: gzip");
    // the token travels in URLs: never let a page send it on as a referrer
    head.append("\r\nReferrer-Policy: no-referrer");
    head.append("\r\nX-Content-Type-Options: nosniff");
    if (!res.headers().containsKey("Cache-Control")) head.append("\r\nCache-Control: no-store");
    res.headers().forEach((k, v) -> head.append("\r\n").append(k).append(": ").append(v));
    head.append(close ? "\r\nConnection: close" : "\r\nConnection: keep-alive");
    head.append("\r\n\r\n");
    out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
    if (!"HEAD".equals(req.method())) out.write(body);
    out.flush();
  }

  private static String reason(int status) {
    return switch (status) {
      case 101 -> "Switching Protocols";
      case 200 -> "OK";
      case 302 -> "Found";
      case 400 -> "Bad Request";
      case 401 -> "Unauthorized";
      case 404 -> "Not Found";
      case 405 -> "Method Not Allowed";
      case 503 -> "Service Unavailable";
      default -> status < 400 ? "OK" : "Error";
    };
  }

  // ---------------------------------------------------------------- WebSocket

  /** The {@code Sec-WebSocket-Accept} value of a key. */
  static String acceptKey(String key) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      byte[] d = sha1.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1));
      return Base64.getEncoder().encodeToString(d);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private void upgrade(java.net.Socket s, InputStream in, OutputStream out, Request req)
      throws IOException {
    String key = req.header("sec-websocket-key");
    Listener listener = key == null ? null : handler.socket(req);
    if (listener == null) {
      write(out, req, Response.text(key == null ? 400 : 401, "refused"), true);
      return;
    }
    String head =
        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Accept: "
            + acceptKey(key)
            + "\r\n\r\n";
    out.write(head.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
    Socket socket = new Socket(s, out);
    Thread.ofVirtual().name("ws-writer").start(socket::writeLoop);
    listener.open(socket);
    try {
      socket.readLoop(in, listener);
    } finally {
      socket.shut();
      listener.closed(socket);
    }
  }

  /**
   * One open WebSocket. Text messages (and ordered binary ones, {@link #sendOrdered}) are queued
   * and all sent in order; a JPEG frame ({@link #sendBinary}) replaces one still waiting to be
   * sent, so a slow link drops frames rather than queueing them.
   */
  public static final class Socket {
    private final java.net.Socket raw;
    private final OutputStream out;
    private final ArrayDeque<byte[]> texts = new ArrayDeque<>();
    private byte[] frame;
    private volatile boolean open = true;
    private long framesReplaced;
    private long queuedBytes;

    /** Anything the owner keeps with the socket (a seat, a client's state). */
    public volatile Object attachment;

    Socket(java.net.Socket raw, OutputStream out) {
      this.raw = raw;
      this.out = out;
    }

    public boolean isOpen() {
      return open;
    }

    public String remote() {
      return String.valueOf(raw.getRemoteSocketAddress());
    }

    public void sendText(String text) {
      byte[] f = encode(0x1, text.getBytes(StandardCharsets.UTF_8));
      synchronized (this) {
        if (!open) return;
        texts.add(f);
        notifyAll();
      }
    }

    /**
     * Queues a binary message, in place of one still waiting.
     *
     * @return false when it replaced a waiting one
     */
    public boolean sendBinary(byte[] data) {
      byte[] f = encode(0x2, data);
      synchronized (this) {
        if (!open) return true;
        boolean replaced = frame != null;
        if (replaced) framesReplaced++;
        frame = f;
        notifyAll();
        return !replaced;
      }
    }

    /**
     * Queues a binary message in order with the text ones, never replaced: a draw-list frame (coded
     * against the one before it) or a texture patch.
     */
    public void sendOrdered(byte[] data) {
      byte[] f = encode(0x2, data);
      synchronized (this) {
        if (!open) return;
        texts.add(f);
        queuedBytes += f.length;
        notifyAll();
      }
    }

    /** The bytes of ordered messages queued and not yet written. */
    public synchronized long queuedBytes() {
      return queuedBytes;
    }

    public synchronized long framesReplaced() {
      return framesReplaced;
    }

    public void close() {
      synchronized (this) {
        if (!open) return;
        texts.add(encode(0x8, new byte[] {0x03, (byte) 0xe8}));
        notifyAll();
      }
    }

    void shut() {
      synchronized (this) {
        open = false;
        notifyAll();
      }
      try {
        raw.close();
      } catch (IOException e) {
        // closing
      }
    }

    private void writeLoop() {
      try {
        while (true) {
          byte[] next;
          boolean closing;
          synchronized (this) {
            while (open && texts.isEmpty() && frame == null) wait();
            if (!open) return;
            if (!texts.isEmpty()) {
              next = texts.poll();
              if ((next[0] & 0x0f) == 0x2) queuedBytes -= next.length;
            } else {
              next = frame;
              frame = null;
            }
            closing = (next[0] & 0x0f) == 0x8;
          }
          out.write(next);
          out.flush();
          if (closing) {
            shut();
            return;
          }
        }
      } catch (IOException | InterruptedException e) {
        shut();
      }
    }

    private void readLoop(InputStream in, Listener listener) throws IOException {
      ByteArrayOutputStream message = new ByteArrayOutputStream();
      int messageOp = 0;
      while (open) {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) return;
        boolean fin = (b0 & 0x80) != 0;
        int op = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7f;
        if (len == 126) len = (readByte(in) << 8) | readByte(in);
        else if (len == 127) {
          len = 0;
          for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        if (len > MAX_MESSAGE) throw new IOException("message too large");
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        switch (op) {
          case 0x8 -> {
            close();
            return;
          }
          case 0x9 -> {
            byte[] pong = encode(0xA, payload);
            synchronized (this) {
              texts.add(pong);
              notifyAll();
            }
          }
          case 0xA -> {}
          case 0x0, 0x1, 0x2 -> {
            if (op != 0) {
              messageOp = op;
              message.reset();
            }
            message.write(payload);
            if (message.size() > MAX_MESSAGE) throw new IOException("message too large");
            if (fin) {
              byte[] all = message.toByteArray();
              message.reset();
              if (messageOp == 0x1) listener.text(this, new String(all, StandardCharsets.UTF_8));
              else listener.binary(this, all);
            }
          }
          default -> throw new IOException("unknown opcode " + op);
        }
      }
    }

    private static int readByte(InputStream in) throws IOException {
      int b = in.read();
      if (b < 0) throw new EOFException();
      return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
      int off = 0;
      while (off < buf.length) {
        int n = in.read(buf, off, buf.length - off);
        if (n < 0) throw new EOFException();
        off += n;
      }
    }

    /** One unmasked, unfragmented frame (a server's frames are never masked). */
    static byte[] encode(int op, byte[] payload) {
      int n = payload.length;
      int head = n < 126 ? 2 : n < 65536 ? 4 : 10;
      byte[] f = new byte[head + n];
      f[0] = (byte) (0x80 | op);
      if (n < 126) {
        f[1] = (byte) n;
      } else if (n < 65536) {
        f[1] = 126;
        f[2] = (byte) (n >>> 8);
        f[3] = (byte) n;
      } else {
        f[1] = 127;
        for (int i = 0; i < 8; i++) f[2 + i] = (byte) ((long) n >>> (8 * (7 - i)));
      }
      System.arraycopy(payload, 0, f, head, n);
      return f;
    }
  }
}
