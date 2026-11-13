/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.BufferUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.crforge.core.battle.data.GameTables;
import org.crforge.realistic.bridge.BattleAdapter;
import org.crforge.realistic.bridge.BattleFrame;
import org.crforge.realistic.bridge.BattleSession;
import org.crforge.realistic.render.art.ArtCache;

/**
 * Plays crforge in a phone's browser: the battle and the realistic renderer run here, offscreen in
 * a hidden window as the recorder does, and each player's view is streamed to the page as JPEG
 * frames over a WebSocket; the page sends the player's taps back, as normalized screen points
 * mapped here through the HUD's layout and the arena's projection, and plays the sounds the view
 * hears, which this server names with their battle times and serves from the art cache.
 *
 * <p>Started by {@code --serve} ({@link ServeOptions}). It listens on 127.0.0.1 unless {@code
 * --lan} is given, and every request must carry the access token ({@link Access}).
 *
 * <p>A battle is played against the built-in {@link Bot} (side 1), or against a second phone
 * ("friend" mode: the second page to join takes side 1, and the battle starts once both have tapped
 * to start). A battle pauses while its players are all away.
 */
@Slf4j
public final class PhoneServer extends ApplicationAdapter implements WebServer.Handler {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final long TICK_NANOS = 50_000_000L;

  /** Ticks drawn after the battle's end (4.5 s: the end screen plays in them). */
  private static final int OUTRO_TICKS = 90;

  /**
   * How many frames a page may have unacknowledged before it is skipped: about a quarter of a
   * second at 30 fps, so a Wi-Fi delay spike is ridden out by the page's playout buffer instead of
   * costing frames (and, through {@link #adapt}, quality).
   */
  private static final int WINDOW = 8;

  /** The frame message's header: type, frame, tick, input, encode time, quality, capture time. */
  static final int HEADER = 20;

  /** Message types (a binary message's first byte): a JPEG frame, a draw-list frame, a patch. */
  static final int JPEG_FRAME = 1;

  static final int LIST_FRAME = 2;
  static final int PATCH = 3;

  /**
   * Bytes a second of texture patches sent ahead of need (1.2 Mbit/s), while the page's link keeps
   * up: a fraction of a slow link, and a few seconds' head start on a unit's first appearance.
   */
  static final double PREFETCH_RATE = 150_000;

  /** A texture patch message's header: type, flags, page, page size, place. */
  static final int PATCH_HEADER = 24;

  /**
   * The page texels a texture store may hold before a new battle starts a fresh one (the pages a
   * page keeps in GPU memory: 6 pages of 2048, about 130 MB with mipmaps).
   */
  static final long TEXEL_BUDGET = 6L * TextureStore.PAGE * TextureStore.PAGE;

  private final GameTables tables;
  private final ServeOptions options;
  private final Access access;
  private final ArtCache cache;
  private final MatchOptions matchOptions;
  private final AudioCatalog audio;
  private final byte[] page;
  private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

  // ---- GL thread state
  private final List<Client> clients = new ArrayList<>();
  private Match match;
  private FrameBuffer fbo;
  private OrthographicCamera camera;
  private ShapeRenderer shapes;
  private ByteBuffer pixels;
  private WebServer web;

  /** The textures and definitions draw lists refer to; a new one when it grows too large. */
  private TextureStore store;

  private DrawDefs defs;
  private int generation;

  /**
   * The 3D models pages render themselves (docs/phone-server.md, "3D models on the phone"), for the
   * server's life; null without a 3D extraction or with {@code --models 2d}.
   */
  private ModelAssets modelAssets;

  /** The scene uniforms of the models, as {@code /m3d/scene.json}. */
  private volatile byte[] sceneJson;

  /** One page connected. */
  static final class Client {
    final WebServer.Socket socket;
    int side = -1;
    boolean started;
    int sent;
    int acked;
    long skipped;

    /** When each recent frame went to the socket (by frame number), for the ack times we log. */
    final long[] sentNanos = new long[64];

    /** How the page is sent its picture: {@link ServeOptions#LIST} or {@link ServeOptions#JPEG}. */
    volatile String mode;

    /** The page's draw-list encoder (what it has been sent), the patches sent and their store. */
    DrawCodec.Encoder encoder;

    /** The texture patches the page has been sent (of its generation's store). */
    java.util.BitSet patchesHave = new java.util.BitSet();

    /** How far through the battle's prefetch list the page has been taken. */
    int prefetched;

    /** Whether the page renders the 3D models (else it asks for their 2D stand-in). */
    volatile boolean models3d = true;

    /** How many of the battle's models to preload the page has been told of. */
    int modelsTold;

    /** The bytes of prefetch the page may be sent now ({@link #PREFETCH_RATE}), and since when. */
    double prefetchAllowance;

    long prefetchAt = System.nanoTime();

    int generation = -1;
    volatile boolean keyNext = true;

    long ackCount;
    double ackSumMs;
    double ackMaxMs;
    long skippedLogged;

    Client(WebServer.Socket socket) {
      this.socket = socket;
    }

    void send(ObjectNode message) {
      socket.sendText(message.toString());
    }
  }

  /** What a battle is: who plays it, on what. */
  record Setup(boolean friend, String deck, String opponentDeck, String location) {}

  enum Phase {
    WAITING,
    RUNNING,
    OUTRO,
    OVER
  }

  /** One battle and its two seats. */
  final class Match {
    final Setup setup;
    final BattleSession session;
    final Bot bot;
    final Seat[] seats = new Seat[2];
    final BattleSchedule schedule;
    final Encoding[] encoding = new Encoding[2];
    Phase phase = Phase.WAITING;
    long lastTick;
    long startAt;
    int outro;
    boolean musicStopped;

    /** The battle music playing, for a page that joins late. */
    String music;

    /** The dry run that finds the textures the battle will draw; null once done. */
    Rehearsal rehearsal;

    /** Texture patches the battle will likely draw, in the order the dry run drew them. */
    final List<Integer> prefetch = java.util.Collections.synchronizedList(new ArrayList<>());

    /**
     * The 3D models (asset ids) the battle will likely draw, in the order the dry run drew them.
     */
    final List<String> models = new ArrayList<>();

    final java.util.Set<Integer> modelRefsSeen = new java.util.HashSet<>();

    final String[] stateSent = new String[2];

    Match(Setup setup) {
      this.setup = setup;
      MatchOptions.Deck mine = matchOptions.deck(setup.deck(), "blue");
      MatchOptions.Deck theirs = matchOptions.deck(setup.opponentDeck(), "red");
      session =
          BattleSession.ladder(tables, mine.cards(), mine.slots(), theirs.cards(), theirs.slots());
      bot = setup.friend() ? null : new Bot(1, System.nanoTime());
      List<String> names = setup.friend() ? List.of("Player 1", "Player 2") : List.of("You", "Bot");
      String location =
          matchOptions.hasLocation(setup.location()) ? setup.location() : options.location();
      for (int side = 0; side < 2; side++) {
        if (side == 1 && !setup.friend()) continue;
        seats[side] =
            new Seat(
                side,
                cache,
                tables,
                session,
                location,
                options.width(),
                options.height(),
                names,
                new DrawRecorder(defs));
        encoding[side] = new Encoding(side);
        seats[side].recordModels(assets(seats[side]));
      }
      schedule = BattleSchedule.read(cache.folder(), seats[0].locationName());
      rehearsal = new Rehearsal(this, mine, theirs, location, names);
      BattleFrame first = BattleAdapter.frame(session);
      List<String[]> ignored = new ArrayList<>();
      for (Seat s : seats) if (s != null) s.tick(first, false, ignored);
    }

    String location() {
      return seats[0].locationName();
    }

    void dispose() {
      if (rehearsal != null) rehearsal.dispose();
      rehearsal = null;
      for (Seat s : seats) if (s != null) s.dispose();
      for (Encoding e : encoding) if (e != null) e.pool.shutdownNow();
    }
  }

  /**
   * A dry run of a battle's two decks: two bots play them for {@value #REHEARSAL_TICKS} ticks,
   * drawn offscreen into the draw-list recorder and never shown, a few ticks of work at a time on
   * the GL thread. The texture patches its frames use are what the battle will likely draw (the
   * units of both decks walking, fighting and dying, their spells): pages are sent them ahead,
   * while their link is idle, so a unit's first appearance does not stall the stream.
   */
  static final int REHEARSAL_TICKS = 2400;

  final class Rehearsal {
    final Match match;
    final BattleSession session;
    final Seat seat;
    final Bot blue = new Bot(0, 7);
    final Bot red = new Bot(1, 8);
    final DrawFrame scratch = new DrawFrame();
    final java.util.BitSet seen = new java.util.BitSet();
    int ticks;
    boolean done;

    Rehearsal(
        Match match,
        MatchOptions.Deck mine,
        MatchOptions.Deck theirs,
        String location,
        List<String> names) {
      this.match = match;
      session =
          BattleSession.ladder(tables, mine.cards(), mine.slots(), theirs.cards(), theirs.slots());
      seat =
          new Seat(
              0,
              cache,
              tables,
              session,
              location,
              options.width(),
              options.height(),
              names,
              new DrawRecorder(defs));
      // the rehearsal fetches what draw lists use: the 3D models (their files, preloaded by the
      // pages), else the 3D rows' 2D stand-in
      ModelRecorder rec = seat.recordModels(assets(seat));
      if (rec != null) {
        rec.refUsed =
            ref -> {
              if (match.modelRefsSeen.add(ref)) {
                String asset = modelAssets.ref(ref).asset();
                if (!match.models.contains(asset)) match.models.add(asset);
              }
            };
      } else {
        seat.drawModels(false);
      }
      seat.tick(BattleAdapter.frame(session), false, new ArrayList<>());
    }

    /** Runs for about {@code nanos}. */
    void run(long nanos) {
      long end = System.nanoTime() + nanos;
      List<String[]> ignored = new ArrayList<>();
      while (!done && System.nanoTime() < end) {
        blue.decide(session, seat.current);
        red.decide(session, seat.current);
        if (!session.step() || ++ticks >= REHEARSAL_TICKS) {
          finish();
          return;
        }
        seat.tick(BattleAdapter.frame(session), false, ignored);
        ignored.clear();
        if (ticks % 3 != 0) continue;
        seat.recorder().startFrame(scratch, options.width(), options.height(), false);
        seat.draw(0.5f, camera, shapes, ignored);
        seat.recorder().endFrame();
        ignored.clear();
        for (int i = 0; i < scratch.count; i++) {
          int patch = defs.region(scratch.region[i]).patch();
          if (patch >= 0 && !seen.get(patch)) {
            seen.set(patch);
            match.prefetch.add(patch);
          }
        }
      }
    }

    private void finish() {
      done = true;
      log.info(
          "Dry run: {} ticks, {} texture patches to send ahead, {} 3D models to preload",
          ticks,
          match.prefetch.size(),
          match.models.size());
      dispose();
    }

    void dispose() {
      seat.dispose();
    }
  }

  /** A seat's encoder: one thread, a frame at a time, and the JPEG stream's adaptive quality. */
  final class Encoding {
    final int side;
    final ExecutorService pool;
    final FrameEncoder encoder = new FrameEncoder(options.width(), options.height());
    final AtomicBoolean busy = new AtomicBoolean();
    volatile int quality = options.quality();

    /** The page's picture setting: the quality and frame rate it asks for at most. */
    int maxQuality = options.quality();

    int fps = options.fps();
    int seq;
    long nextFrame;
    long lastSkipCheck = System.nanoTime();
    long skipsSeen;
    long quietSince = System.nanoTime();
    // statistics
    double encodeMs;
    double drawMs;
    double readMs;
    double bytes;
    int frames;
    // the draw-list stream's
    double recordMs;
    double listEncodeMs;
    double listBytes;
    double patchBytes;
    int listMax;
    int listFrames;
    int polygons;

    Encoding(int side) {
      this.side = side;
      this.pool =
          Executors.newSingleThreadExecutor(
              r -> {
                Thread t = new Thread(r, "jpeg-" + side);
                t.setDaemon(true);
                return t;
              });
    }
  }

  public PhoneServer(GameTables tables, ServeOptions options, ArtCache cache) {
    this.tables = tables;
    this.options = options;
    this.cache = cache;
    this.access = new Access(options.token() != null ? options.token() : Access.newToken());
    this.matchOptions = new MatchOptions(tables, cache.folder());
    this.audio = AudioCatalog.open(cache.folder(), options.audioCache()).orElse(null);
    try (InputStream in = PhoneServer.class.getResourceAsStream("index.html")) {
      this.page = in.readAllBytes();
    } catch (IOException | NullPointerException e) {
      throw new IllegalStateException("the page is missing from the build", e);
    }
  }

  /**
   * Starts the server and its hidden window; returns when the window closes.
   *
   * @param out where the URLs to open are printed
   */
  public static void launch(GameTables tables, ServeOptions options, PrintStream out) {
    System.setProperty("java.awt.headless", "true");
    ArtCache cache =
        ArtCache.locate()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no game art cache: build it with tools/game-art (crforge-art extract)"));
    PhoneServer server = new PhoneServer(tables, options, cache);
    server.start(out);
    Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
    config.setTitle("CRForge - phone server");
    config.setWindowedMode(320, 240);
    config.setWindowPosition(0, 0);
    config.setInitialVisible(false);
    config.useVsync(false);
    config.setForegroundFPS(120);
    config.setIdleFPS(120);
    new Lwjgl3Application(server, config);
  }

  private void start(PrintStream out) {
    String host = options.lan() ? "0.0.0.0" : "127.0.0.1";
    try {
      web = WebServer.start(host, options.port(), this);
    } catch (IOException e) {
      throw new IllegalStateException(
          "cannot listen on " + host + ":" + options.port() + ": " + e.getMessage(), e);
    }
    for (String line : urls(options, access.token(), lanAddresses())) out.println(line);
    if (audio == null) {
      out.println(
          "  (no sounds: the art cache has no audio; extract it with crforge-art extract --audio)");
    }
  }

  /** The startup lines: the URLs to open and how to reach the server from a phone. */
  static List<String> urls(ServeOptions options, String token, List<String> lan) {
    List<String> lines = new ArrayList<>();
    int port = options.port();
    lines.add("");
    lines.add("crforge phone server on port " + port + " (token " + token + ")");
    lines.add("  this Mac:    http://127.0.0.1:" + port + "/?t=" + token);
    if (options.lan()) {
      for (String ip : lan) lines.add("  your phone:  http://" + ip + ":" + port + "/?t=" + token);
      if (lan.isEmpty()) lines.add("  (no LAN address found)");
      lines.add("  Phone and Mac on the same Wi-Fi; anyone on it who has the URL can play.");
    } else {
      lines.add("  Listening on 127.0.0.1 only. For your phone, either:");
      lines.add("   - restart with --lan (same Wi-Fi), or");
      lines.add(
          "   - tunnel it: tailscale serve "
              + port
              + "  /  ngrok http "
              + port
              + "  /  cloudflared tunnel --url http://localhost:"
              + port);
      lines.add("     then open <tunnel URL>/?t=" + token);
    }
    lines.add("");
    return lines;
  }

  static List<String> lanAddresses() {
    List<String> out = new ArrayList<>();
    try {
      for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!ni.isUp() || ni.isLoopback()) continue;
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
          if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
            out.add(a.getHostAddress());
          }
        }
      }
    } catch (IOException e) {
      // none
    }
    return out;
  }

  // ---------------------------------------------------------------- HTTP

  @Override
  public WebServer.Response http(WebServer.Request req) {
    if (!req.method().equals("GET") && !req.method().equals("HEAD")) {
      return WebServer.Response.text(405, "GET only");
    }
    if (!access.allows(req.query(), req.header("cookie"))) {
      return WebServer.Response.text(
          401, "crforge: open the URL the server printed, with its ?t= token");
    }
    String path = req.path();
    if (path.equals("/") || path.equals("/index.html")) {
      return new WebServer.Response(
          200,
          "text/html; charset=utf-8",
          page,
          Map.of(
              "Set-Cookie",
              Access.cookieName()
                  + "="
                  + access.token()
                  + "; Path=/; Max-Age=2592000; HttpOnly; SameSite=Strict"));
    }
    if (path.equals("/audio/index.json")) {
      if (audio == null) return WebServer.Response.text(404, "no audio");
      return new WebServer.Response(
          200,
          "application/json",
          audio.compactIndex(),
          Map.of("Cache-Control", "private, max-age=3600"));
    }
    if (path.startsWith("/audio/") && audio != null) {
      String[] parts = path.substring("/audio/".length()).split("/");
      if (parts.length == 2) {
        try {
          Optional<byte[]> bytes = audio.sample(parts[0], parts[1]);
          if (bytes.isPresent()) {
            String type = parts[1].endsWith(".m4a") ? "audio/mp4" : "audio/ogg";
            return new WebServer.Response(
                200, type, bytes.get(), Map.of("Cache-Control", "private, max-age=86400"));
          }
        } catch (IOException e) {
          log.warn("sample {}: {}", path, e.getMessage());
        }
      }
      return WebServer.Response.text(404, "no such sample");
    }
    if (path.startsWith("/m3d/")) return modelRoute(path);
    if (path.equals("/favicon.ico") || path.startsWith("/apple-touch-icon")) {
      // no icon: the game's art is not for a home screen icon
      return WebServer.Response.of(204, null, new byte[0]);
    }
    log.info("404 {}", path);
    return WebServer.Response.text(404, "not found");
  }

  /**
   * The 3D models' files: {@code /m3d/scene.json} (the light, camera and team colours), {@code
   * /m3d/model/<id>.bin} (a model, {@link ModelAssets#build}) and {@code /m3d/tex/<id>.png} (a
   * texture). Ids are content stamps, so the files are cached as immutable.
   */
  private WebServer.Response modelRoute(String path) {
    ModelAssets a = modelAssets;
    if (path.equals("/m3d/scene.json")) {
      byte[] scene = sceneJson;
      if (scene == null) return WebServer.Response.text(404, "no 3D models");
      return new WebServer.Response(
          200, "application/json", scene, Map.of("Cache-Control", "no-cache"));
    }
    if (a == null) return WebServer.Response.text(404, "no 3D models");
    String id = path.substring(path.lastIndexOf('/') + 1);
    int dot = id.indexOf('.');
    if (dot > 0) id = id.substring(0, dot);
    Optional<byte[]> bytes = Optional.empty();
    String type = "application/octet-stream";
    if (path.startsWith("/m3d/model/")) {
      bytes = a.model(id);
    } else if (path.startsWith("/m3d/tex/")) {
      bytes = a.texture(id);
      boolean jpeg = bytes.isPresent() && bytes.get().length > 2 && (bytes.get()[0] & 0xFF) == 0xFF;
      type = jpeg ? "image/jpeg" : "image/png";
    }
    if (bytes.isEmpty()) return WebServer.Response.text(404, "no such model file");
    return new WebServer.Response(
        200, type, bytes.get(), Map.of("Cache-Control", "private, max-age=31536000, immutable"));
  }

  @Override
  public WebServer.Listener socket(WebServer.Request req) {
    if (!req.path().equals("/ws") || !access.allows(req.query(), req.header("cookie"))) return null;
    return new WebServer.Listener() {
      @Override
      public void open(WebServer.Socket socket) {
        Client c = new Client(socket);
        socket.attachment = c;
        queue.add(() -> connected(c));
      }

      @Override
      public void text(WebServer.Socket socket, String text) {
        JsonNode msg;
        try {
          msg = JSON.readTree(text);
        } catch (IOException e) {
          return;
        }
        Client c = (Client) socket.attachment;
        String type = msg.path("type").asText("");
        if (type.equals("ping")) {
          ObjectNode pong = JSON.createObjectNode().put("type", "pong");
          pong.set("c", msg.path("c"));
          socket.sendText(pong.toString());
          return;
        }
        queue.add(() -> message(c, msg));
      }

      @Override
      public void closed(WebServer.Socket socket) {
        Client c = (Client) socket.attachment;
        queue.add(() -> clients.remove(c));
      }
    };
  }

  // ---------------------------------------------------------------- GL thread

  @Override
  public void create() {
    int w = options.width();
    int h = options.height();
    fbo = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false);
    camera = new OrthographicCamera(w, h);
    camera.setToOrtho(false, w, h);
    shapes = new ShapeRenderer();
    pixels = BufferUtils.newByteBuffer(w * h * 4);
    newStore();
    match = new Match(new Setup(false, "blue", "red", options.location()));
    log.info(
        "Ready: {}x{} at up to {} fps, pictures as {} by default (JPEG quality {}), arena {}",
        w,
        h,
        options.fps(),
        options.render().equals(ServeOptions.LIST) ? "draw lists" : "JPEG",
        options.quality(),
        match.location());
  }

  /**
   * The server's 3D model assets, made with the first seat that has a 3D extraction; null without
   * one or with {@code --models 2d}.
   */
  private ModelAssets assets(Seat seat) {
    if (!options.models3d()) return null;
    if (modelAssets == null) {
      var library = seat.modelLibrary();
      if (library == null) return null;
      modelAssets = new ModelAssets(library);
      float[][] u = org.crforge.realistic.render.art.model.ModelRenderer.sceneUniforms(library);
      ObjectNode scene = JSON.createObjectNode();
      String[] names = {"light", "camera", "direct", "ambient", "blue", "red"};
      for (int i = 0; i < names.length; i++) {
        ArrayNode a = scene.putArray(names[i]);
        for (float f : u[i]) a.add(f);
      }
      scene.put("tilt", org.crforge.realistic.render.art.model.ModelCamera.TILT_DEGREES);
      sceneJson = scene.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
      log.info("3D models: drawn by the pages (from {})", library.folder());
    }
    return modelAssets;
  }

  /** Tells the pages of the battle's models to preload that they have not been told of. */
  private void tellModels() {
    if (match == null) return;
    List<String> list = match.models;
    for (Client c : clients) {
      if (c.side < 0 || c.modelsTold >= list.size() || !listMode(c) || !c.models3d) continue;
      ObjectNode m = JSON.createObjectNode().put("type", "models");
      ArrayNode a = m.putArray("assets");
      for (int i = c.modelsTold; i < list.size(); i++) a.add(list.get(i));
      c.modelsTold = list.size();
      c.send(m);
    }
  }

  /** A fresh texture store: pages fill up as battles draw new art. */
  private void newStore() {
    if (store != null) store.dispose();
    store = new TextureStore(cache);
    defs = new DrawDefs(store);
    generation++;
  }

  private void connected(Client c) {
    clients.add(c);
    c.mode = options.render();
    ObjectNode hello = JSON.createObjectNode().put("type", "hello");
    hello.put("width", options.width()).put("height", options.height()).put("fps", options.fps());
    hello.put("render", options.render());
    ArrayNode clear = hello.putArray("clear");
    for (float f : Seat.CLEAR) clear.add(f);
    hello.put("audio", audio != null);
    hello.put("models3d", options.models3d());
    hello.set("options", matchOptions.json(JSON));
    c.send(hello);
  }

  private void ack(Client c, int f) {
    if (f > c.acked) {
      long sent = c.sentNanos[f & 63];
      if (sent != 0) {
        double ms = (System.nanoTime() - sent) / 1e6;
        c.ackCount++;
        c.ackSumMs += ms;
        c.ackMaxMs = Math.max(c.ackMaxMs, ms);
      }
    }
    c.acked = Math.max(c.acked, f);
  }

  /** The page's own view of the stream, every couple of seconds, beside the server's. */
  private void telemetry(Client c, JsonNode msg) {
    log.info(
        "page {}: {} | server: ack {} ms avg, {} ms max over {} frames; skipped {}; replaced {};"
            + " quality {}",
        c.socket.remote(),
        msg.path("s").asText(),
        String.format("%.0f", c.ackCount == 0 ? 0 : c.ackSumMs / c.ackCount),
        String.format("%.0f", c.ackMaxMs),
        c.ackCount,
        c.skipped - c.skippedLogged,
        c.socket.framesReplaced(),
        match == null || c.side < 0 ? -1 : match.encoding[c.side].quality);
    c.ackCount = 0;
    c.ackSumMs = c.ackMaxMs = 0;
    c.skippedLogged = c.skipped;
  }

  private void message(Client c, JsonNode msg) {
    if (!clients.contains(c)) return;
    switch (msg.path("type").asText("")) {
      case "join" -> join(c, msg.path("seat").asText("auto"));
      case "start" -> {
        c.started = true;
        sendState(true);
      }
      case "ack" -> ack(c, msg.path("f").asInt());
      case "tele" -> telemetry(c, msg);
      case "down", "move", "up" -> input(c, msg);
      case "ability" -> ability(c, msg);
      case "picture" -> picture(c, msg);
      case "new" -> newBattle(msg);
      default -> {}
    }
  }

  /** Seats a page: side 0 against the bot; in friend mode the first free side. */
  private void join(Client c, String wanted) {
    c.side = seatFor(c, wanted);
    ObjectNode seat = JSON.createObjectNode().put("type", "seat").put("side", c.side);
    c.send(seat);
    sendSetup(c);
    if (match.music != null) {
      c.send(JSON.createObjectNode().put("type", "music").put("ref", match.music).put("delay", 0));
    }
    sendState(true);
  }

  private int seatFor(Client c, String wanted) {
    if (!match.setup.friend()) return 0;
    if (wanted.equals("0") || wanted.equals("1")) return Integer.parseInt(wanted);
    boolean zeroTaken = false;
    for (Client o : clients) if (o != c && o.side == 0) zeroTaken = true;
    return zeroTaken ? 1 : 0;
  }

  private void sendSetup(Client c) {
    ObjectNode m = JSON.createObjectNode().put("type", "setup");
    m.put("friend", match.setup.friend());
    m.put("deck", matchOptions.deck(match.setup.deck(), "blue").id());
    m.put("opponentDeck", matchOptions.deck(match.setup.opponentDeck(), "red").id());
    m.put("location", match.location());
    c.send(m);
  }

  private void newBattle(JsonNode msg) {
    Setup setup =
        new Setup(
            msg.path("friend").asBoolean(false),
            msg.path("deck").asText(null),
            msg.path("opponentDeck").asText(null),
            msg.path("location").asText(null));
    Match old = match;
    old.dispose();
    store.forgetObjects();
    if (store.texels() > TEXEL_BUDGET) {
      // the pages pages keep hold the art of the battles so far: start afresh, the pages drop
      // theirs
      log.info(
          "Texture pages: {} M texels in {} pages, starting afresh",
          store.texels() / 1_000_000,
          store.pageCount() - 1);
      newStore();
    }
    match = new Match(setup);
    for (Client c : clients) {
      c.keyNext = true;
      c.prefetched = 0;
      c.modelsTold = 0;
    }
    log.info("New battle: {} on {}", setup.friend() ? "friend" : "bot", match.location());
    // seats anew: against the bot everyone sees side 0; with a friend the pages share it out
    for (Client c : clients) c.side = -1;
    for (Client c : clients) {
      c.side = seatFor(c, "auto");
      c.send(JSON.createObjectNode().put("type", "seat").put("side", c.side));
      sendSetup(c);
    }
    sendState(true);
  }

  private void input(Client c, JsonNode msg) {
    Seat seat = seatOf(c);
    if (seat == null) return;
    long t0 = System.nanoTime();
    TapInput.Result r =
        seat.input(
            msg.path("type").asText(),
            (float) msg.path("x").asDouble(),
            (float) msg.path("y").asDouble(),
            match.phase == Phase.RUNNING);
    seat.lastInputSeq = Math.max(seat.lastInputSeq, msg.path("seq").asInt());
    reply(c, msg, r, t0);
  }

  /**
   * The page's picture setting, for its seat's stream: at most the server's own quality and frame
   * rate (a tunnel or a phone network wants fewer, smaller frames than a home Wi-Fi).
   */
  private void picture(Client c, JsonNode msg) {
    String mode = msg.path("mode").asText(c.mode);
    if (mode.equals(ServeOptions.LIST) || mode.equals(ServeOptions.JPEG)) {
      if (!mode.equals(c.mode)) c.keyNext = true;
      c.mode = mode;
    }
    if (msg.has("models")) c.models3d = msg.path("models").asBoolean(true);
    if (c.side < 0 || match.encoding[c.side] == null) return;
    Encoding enc = match.encoding[c.side];
    enc.maxQuality =
        Math.max(20, Math.min(options.quality(), msg.path("quality").asInt(options.quality())));
    enc.fps = Math.max(5, Math.min(options.fps(), msg.path("fps").asInt(options.fps())));
    enc.quality = Math.min(enc.quality, enc.maxQuality);
  }

  private void ability(Client c, JsonNode msg) {
    Seat seat = seatOf(c);
    if (seat == null) return;
    long t0 = System.nanoTime();
    TapInput.Result r = seat.ability(msg.path("slot").asInt(), match.phase == Phase.RUNNING);
    seat.lastInputSeq = Math.max(seat.lastInputSeq, msg.path("seq").asInt());
    reply(c, msg, r, t0);
  }

  private void reply(Client c, JsonNode msg, TapInput.Result r, long t0) {
    if (r.what().equals("none") || r.what().equals("hover")) return;
    ObjectNode m = JSON.createObjectNode().put("type", "input");
    m.put("seq", msg.path("seq").asInt());
    m.put("result", r.what());
    if (r.message() != null) m.put("message", r.message());
    m.put("serverMs", (System.nanoTime() - t0) / 1e6);
    m.put("tick", match.session.tick());
    c.send(m);
    sendState(false);
  }

  private Seat seatOf(Client c) {
    if (c.side < 0 || match == null) return null;
    return match.seats[c.side];
  }

  @Override
  public void render() {
    for (Runnable r; (r = queue.poll()) != null; ) {
      try {
        r.run();
      } catch (RuntimeException e) {
        log.warn("request failed", e);
      }
    }
    if (match == null) return;
    if (match.rehearsal != null) {
      // more of it while the battle waits to start
      match.rehearsal.run(match.phase == Phase.WAITING ? 12_000_000L : 3_000_000L);
      if (match.rehearsal.done) match.rehearsal = null;
    }
    tellModels();
    long now = System.nanoTime();
    advance(now);
    for (int side = 0; side < 2; side++) {
      Seat seat = match.seats[side];
      if (seat != null) stream(seat, match.encoding[side], now);
    }
  }

  private boolean listMode(Client c) {
    return ServeOptions.LIST.equals(c.mode);
  }

  /** Whether the players a battle needs have tapped to start. */
  private boolean playersReady() {
    boolean[] ready = new boolean[2];
    for (Client c : clients) if (c.started && c.side >= 0) ready[c.side] = true;
    return match.setup.friend() ? ready[0] && ready[1] : ready[0];
  }

  private void advance(long now) {
    Match m = match;
    boolean ready = playersReady();
    switch (m.phase) {
      case WAITING -> {
        if (!ready) {
          m.startAt = 0;
          return;
        }
        if (m.startAt == 0) {
          // a second for the start jingle before the clock runs
          m.startAt = now + 1_000_000_000L;
          cues(m.schedule.advance(180, false));
        }
        if (now < m.startAt) return;
        m.phase = Phase.RUNNING;
        m.lastTick = now;
        sendState(true);
      }
      case RUNNING -> {
        if (!ready) {
          // everyone left: the battle waits for them
          m.lastTick = now;
          return;
        }
        int steps = 0;
        while (now - m.lastTick >= TICK_NANOS && steps < 4 && m.phase == Phase.RUNNING) {
          m.lastTick += TICK_NANOS;
          step(m);
          steps++;
        }
        if (now - m.lastTick > 4 * TICK_NANOS) m.lastTick = now; // fell behind: catch up no more
      }
      case OUTRO -> {
        int steps = 0;
        while (now - m.lastTick >= TICK_NANOS && steps < 4 && m.phase == Phase.OUTRO) {
          m.lastTick += TICK_NANOS;
          outroTick(m);
          steps++;
        }
        if (now - m.lastTick > 4 * TICK_NANOS) m.lastTick = now;
      }
      case OVER -> {}
    }
  }

  private void step(Match m) {
    BattleFrame before = m.seats[0].current;
    if (m.bot != null) m.bot.decide(m.session, before);
    if (!m.session.step()) {
      m.phase = Phase.OUTRO;
      m.outro = 0;
      outroTick(m);
      return;
    }
    BattleFrame frame = BattleAdapter.frame(m.session);
    for (Seat s : m.seats) {
      if (s == null) continue;
      List<String[]> heard = new ArrayList<>();
      int selectedBefore = s.hand.selected();
      String cardBefore =
          selectedBefore < 0 ? null : nameOf(m.session.handCard(s.side, selectedBefore));
      s.tick(frame, false, heard);
      // the selected card left its slot (played, or the slot refilled): drop the selection
      if (selectedBefore >= 0
          && !java.util.Objects.equals(
              cardBefore, nameOf(m.session.handCard(s.side, selectedBefore)))) {
        s.cancelSelection();
      }
      sounds(s.side, heard);
    }
    if (!m.seats[0].ended()) {
      double left = frame.overtime() ? 0 : Math.max(0, 180 - frame.timeMs() / 1000.0);
      cues(m.schedule.advance(left, frame.overtime()));
    } else if (!m.musicStopped) {
      m.musicStopped = true;
      broadcastMusic(null, 0);
    }
    sendState(false);
  }

  private static String nameOf(org.crforge.core.battle.match.MatchCard card) {
    return card == null ? null : card.name();
  }

  private void outroTick(Match m) {
    for (Seat s : m.seats) {
      if (s == null) continue;
      List<String[]> heard = new ArrayList<>();
      s.cancelSelection();
      s.tick(null, true, heard);
      sounds(s.side, heard);
    }
    if (!m.musicStopped) {
      m.musicStopped = true;
      broadcastMusic(null, 0);
    }
    if (++m.outro >= OUTRO_TICKS) {
      m.phase = Phase.OVER;
      sendState(true);
    }
  }

  // ---------------------------------------------------------------- sounds and state

  private void sounds(int side, List<String[]> heard) {
    if (heard.isEmpty()) return;
    ObjectNode m = JSON.createObjectNode().put("type", "snd");
    ArrayNode list = m.putArray("list");
    for (String[] s : heard) {
      if (s[1] == null || s[1].isEmpty()) continue;
      list.addArray().add(Double.parseDouble(s[0])).add(s[1]);
    }
    if (list.isEmpty()) return;
    String text = m.toString();
    for (Client c : clients) if (c.side == side) c.socket.sendText(text);
  }

  private void cues(List<BattleSchedule.Cue> cues) {
    double delay = 0;
    for (BattleSchedule.Cue cue : cues) {
      if (cue.music()) {
        broadcastMusic(cue.ref(), delay);
      } else {
        // the start jingle plays before the battle music
        if (cue.ref().equals(match.schedule.startJingle()) && audio != null) {
          delay = audio.eventSeconds(cue.ref());
        }
        ObjectNode m = JSON.createObjectNode().put("type", "snd");
        m.putArray("list").addArray().add(match.session.tick() * 0.05).add(cue.ref());
        String text = m.toString();
        for (Client c : clients) if (c.side >= 0) c.socket.sendText(text);
      }
    }
  }

  private void broadcastMusic(String ref, double delay) {
    match.music = ref;
    ObjectNode m = JSON.createObjectNode().put("type", "music");
    if (ref == null) m.putNull("ref");
    else m.put("ref", ref);
    m.put("delay", delay);
    String text = m.toString();
    for (Client c : clients) if (c.side >= 0) c.socket.sendText(text);
  }

  /** Sends each seat's state to its pages when it changed (or always, with {@code force}). */
  private void sendState(boolean force) {
    for (int side = 0; side < 2; side++) {
      Seat seat = match.seats[side];
      if (seat == null) continue;
      ObjectNode m = JSON.createObjectNode().put("type", "state");
      m.put("phase", match.phase.name().toLowerCase(java.util.Locale.ROOT));
      m.put("side", side);
      m.put("friend", match.setup.friend());
      m.put("selected", seat.hand.selected());
      BattleFrame.SideView me = seat.me();
      BattleFrame.SideView them = seat.them();
      if (me != null) m.put("elixir", me.wholeElixir()).put("crowns", me.crowns());
      if (them != null) m.put("opponentCrowns", them.crowns());
      boolean[] present = new boolean[2];
      boolean[] ready = new boolean[2];
      for (Client c : clients) {
        if (c.side >= 0) {
          present[c.side] = true;
          ready[c.side] |= c.started;
        }
      }
      m.put(
          "opponent",
          !match.setup.friend()
              ? "bot"
              : ready[1 - side] ? "ready" : present[1 - side] ? "joined" : "away");
      m.put("ready", ready[side]);
      if (seat.current != null && seat.current.ended()) {
        int w = seat.current.winner();
        m.put("result", w < 0 ? "draw" : w == side ? "win" : "lose");
      }
      String key = m.toString();
      if (!force && key.equals(match.stateSent[side])) continue;
      match.stateSent[side] = key;
      for (Client c : clients) if (c.side == side) c.socket.sendText(key);
    }
  }

  // ---------------------------------------------------------------- frames

  private void stream(Seat seat, Encoding enc, long now) {
    List<Client> viewers = new ArrayList<>();
    for (Client c : clients) {
      if (c.side == seat.side && c.socket.isOpen()) viewers.add(c);
    }
    if (viewers.isEmpty()) return;
    long interval = 1_000_000_000L / enc.fps;
    // the end screen is still: a few frames a second keep a page that joins up to date
    if (match.phase == Phase.OVER || match.phase == Phase.WAITING) interval = 250_000_000L;
    if (now < enc.nextFrame || enc.busy.get()) return;
    List<Client> ready = new ArrayList<>();
    for (Client c : viewers) {
      if (c.sent - c.acked < WINDOW) ready.add(c);
      else c.skipped++;
    }
    adapt(enc, viewers, now);
    if (ready.isEmpty()) return;
    enc.nextFrame = Math.max(enc.nextFrame + interval, now);
    float alpha = 1f;
    if (match.phase == Phase.RUNNING || match.phase == Phase.OUTRO) {
      alpha = Math.min(1f, Math.max(0f, (now - match.lastTick) / (float) TICK_NANOS));
    }
    List<Client> jpeg = new ArrayList<>();
    List<Client> list = new ArrayList<>();
    for (Client c : ready) (listMode(c) ? list : jpeg).add(c);
    boolean rasterize = !jpeg.isEmpty();
    DrawFrame frame = list.isEmpty() ? null : new DrawFrame();
    DrawRecorder recorder = seat.recorder();
    long t0 = System.nanoTime();
    if (rasterize) fbo.begin();
    if (frame != null) recorder.startFrame(frame, options.width(), options.height(), rasterize);
    // a draw list carries the 3D models for its pages to render (a JPEG viewer sharing the frame
    // sees them drawn here); with a page that asks for the 2D stand-in, or no 3D models, the 3D
    // rows' 2D stand-in (which a JPEG viewer then sees too)
    boolean models3d = seat.modelRecorder() != null;
    for (Client c : list) models3d &= c.models3d;
    if (seat.modelRecorder() != null) seat.modelRecorder().setOn(models3d);
    seat.drawModels(frame == null || models3d);
    List<String[]> heard = new ArrayList<>();
    seat.draw(alpha, camera, shapes, heard);
    if (frame != null) recorder.endFrame();
    long t1 = System.nanoTime();
    byte[] rgba = null;
    if (rasterize) {
      pixels.clear();
      Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
      Gdx.gl.glReadPixels(
          0, 0, options.width(), options.height(), GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
      fbo.end();
      rgba = new byte[pixels.capacity()];
      pixels.get(rgba);
    }
    long t2 = System.nanoTime();
    sounds(seat.side, heard);
    int seq = ++enc.seq;
    int tick = seat.current == null ? 0 : seat.current.tick();
    int input = seat.lastInputSeq;
    // the moment the frame shows (its interpolation point), on the server's clock in ms: the page
    // shows frames at these intervals, not at the network's
    int capturedMs = (int) (now / 1_000_000L);
    for (Client c : ready) c.sent = seq;
    // the patches the frame may need (made while it was recorded), and their store
    List<Integer> prefetch = match.prefetch;
    boolean waiting = match.phase == Phase.WAITING;
    DrawDefs frameDefs = defs;
    int frameGeneration = generation;
    for (Client c : list) {
      if (c.generation != frameGeneration) {
        // a fresh store (or a new page): the page drops its pages and definitions
        if (c.encoder != null) c.encoder.dispose();
        c.encoder = new DrawCodec.Encoder(frameDefs, 6).models(modelAssets);
        c.patchesHave = new java.util.BitSet();
        c.prefetched = 0;
        c.generation = frameGeneration;
        c.send(JSON.createObjectNode().put("type", "reset"));
      }
    }
    enc.busy.set(true);
    double drawMs = (t1 - t0) / 1e6;
    double readMs = (t2 - t1) / 1e6;
    byte[] pixelsCopy = rgba;
    enc.pool.execute(
        () -> {
          try {
            if (pixelsCopy != null) {
              long e0 = System.nanoTime();
              byte[] jpegBytes = enc.encoder.encode(pixelsCopy, enc.quality);
              double encodeMs = (System.nanoTime() - e0) / 1e6;
              byte[] msg = new byte[HEADER + jpegBytes.length];
              ByteBuffer b = ByteBuffer.wrap(msg);
              b.put((byte) JPEG_FRAME).putInt(seq).putInt(tick).putInt(input);
              b.putShort((short) Math.min(Short.MAX_VALUE, Math.round(encodeMs * 10)));
              b.put((byte) enc.quality).putInt(capturedMs);
              System.arraycopy(jpegBytes, 0, msg, HEADER, jpegBytes.length);
              long sentAt = System.nanoTime();
              for (Client c : jpeg) {
                c.sentNanos[seq & 63] = sentAt;
                c.socket.sendBinary(msg);
              }
              stats(enc, drawMs, readMs, encodeMs, jpegBytes.length);
            }
            if (frame != null) {
              sendList(
                  enc,
                  frame,
                  list,
                  frameDefs,
                  prefetch,
                  waiting,
                  seq,
                  tick,
                  input,
                  capturedMs,
                  drawMs);
            }
          } catch (RuntimeException e) {
            log.warn("frame encoding failed", e);
          } finally {
            enc.busy.set(false);
          }
        });
  }

  /**
   * Sends a draw-list frame to the pages that take them, each coded against the last frame it was
   * sent, after the texture patches it has not been sent yet. On the seat's encoder thread.
   */
  private void sendList(
      Encoding enc,
      DrawFrame frame,
      List<Client> pages,
      DrawDefs frameDefs,
      List<Integer> prefetch,
      boolean waiting,
      int seq,
      int tick,
      int input,
      int capturedMs,
      double recordMs) {
    long patchBytes = 0;
    int maxBytes = 0;
    long totalBytes = 0;
    double encodeMs = 0;
    TextureStore frameStore = frameDefs.textures();
    java.util.BitSet regions = new java.util.BitSet();
    for (int i = 0; i < frame.count; i++) regions.set(frame.region[i]);
    for (Client c : pages) {
      if (c.encoder == null) continue;
      // the patches the frame draws that the page lacks, first
      for (int r = regions.nextSetBit(0); r >= 0; r = regions.nextSetBit(r + 1)) {
        int patch = frameDefs.region(r).patch();
        if (patch < 0 || c.patchesHave.get(patch)) continue;
        c.patchesHave.set(patch);
        byte[] m = patchMessage(frameStore, frameStore.patch(patch));
        patchBytes += m.length;
        c.socket.sendOrdered(m);
      }
      long e0 = System.nanoTime();
      boolean key = c.keyNext;
      c.keyNext = false;
      byte[] payload = c.encoder.encode(frame, key);
      double ms = (System.nanoTime() - e0) / 1e6;
      encodeMs = Math.max(encodeMs, ms);
      byte[] msg = new byte[HEADER + payload.length];
      ByteBuffer b = ByteBuffer.wrap(msg);
      b.put((byte) LIST_FRAME).putInt(seq).putInt(tick).putInt(input);
      b.putShort((short) Math.min(Short.MAX_VALUE, Math.round(ms * 10)));
      b.put((byte) (key ? DrawCodec.KEY : 0)).putInt(capturedMs);
      System.arraycopy(payload, 0, msg, HEADER, payload.length);
      c.sentNanos[seq & 63] = System.nanoTime();
      c.socket.sendOrdered(msg);
      maxBytes = Math.max(maxBytes, msg.length);
      totalBytes += msg.length;
      // then, while the link keeps up (frames acknowledged at once, nothing queued), a patch or
      // two the battle will likely draw
      long nowNanos = System.nanoTime();
      c.prefetchAllowance =
          Math.min(64_000, c.prefetchAllowance + (nowNanos - c.prefetchAt) / 1e9 * PREFETCH_RATE);
      c.prefetchAt = nowNanos;
      // before the battle starts (a few frames a second, nothing moving) as fast as the link
      // takes them; during it at PREFETCH_RATE
      int sentAhead = 0;
      while (sentAhead < (waiting ? 64 : 2)
          && (waiting || c.prefetchAllowance > 0)
          && c.sent - c.acked <= (waiting ? 1 : 3)
          && c.socket.queuedBytes() < (waiting ? 131_072 : 16_384)
          && c.prefetched < prefetch.size()) {
        int patch = prefetch.get(c.prefetched++);
        if (c.patchesHave.get(patch)) continue;
        c.patchesHave.set(patch);
        byte[] m = patchMessage(frameStore, frameStore.patch(patch));
        patchBytes += m.length;
        if (!waiting) c.prefetchAllowance -= m.length;
        c.socket.sendOrdered(m);
        sentAhead++;
      }
    }
    synchronized (enc) {
      enc.listFrames++;
      enc.recordMs += recordMs;
      enc.listEncodeMs += encodeMs;
      enc.listBytes += pages.isEmpty() ? 0 : totalBytes / (double) pages.size();
      enc.listMax = Math.max(enc.listMax, maxBytes);
      enc.patchBytes += patchBytes;
      enc.polygons += frame.count;
      if (enc.listFrames >= 300) {
        log.info(
            "side {}: {} draw-list frames, record {} ms, encode {} ms, {} polygons, {} KB a frame"
                + " ({} KB max), patches {} KB; texture pages {} ({} M texels)",
            enc.side,
            enc.listFrames,
            String.format("%.2f", enc.recordMs / enc.listFrames),
            String.format("%.2f", enc.listEncodeMs / enc.listFrames),
            enc.polygons / enc.listFrames,
            String.format("%.1f", enc.listBytes / enc.listFrames / 1024),
            String.format("%.1f", enc.listMax / 1024.0),
            String.format("%.0f", enc.patchBytes / 1024),
            frameStore.pageCount() - 1,
            frameStore.texels() / 1_000_000);
        enc.listFrames = 0;
        enc.recordMs = enc.listEncodeMs = enc.listBytes = enc.patchBytes = 0;
        enc.listMax = 0;
        enc.polygons = 0;
      }
    }
  }

  /** A texture patch's message: its header and the PNG. */
  static byte[] patchMessage(TextureStore store, TextureStore.Patch p) {
    TextureStore.Page page = store.page(p.page());
    byte[] png = store.png(p);
    byte[] msg = new byte[PATCH_HEADER + png.length];
    ByteBuffer b = ByteBuffer.wrap(msg);
    b.put((byte) PATCH).put((byte) (page.plain ? 1 : 0)).putShort((short) 0);
    b.putInt(p.page()).putInt(page.width).putInt(page.height).putInt(p.x()).putInt(p.y());
    System.arraycopy(png, 0, msg, PATCH_HEADER, png.length);
    return msg;
  }

  /**
   * The stream's quality follows the slowest page: down 5 while a page's window stays full, back up
   * 2 after 3 s without a skip.
   */
  private void adapt(Encoding enc, List<Client> viewers, long now) {
    if (now - enc.lastSkipCheck < 500_000_000L) return;
    enc.lastSkipCheck = now;
    long skips = 0;
    for (Client c : viewers) skips += c.skipped;
    if (skips > enc.skipsSeen + 2) {
      enc.quality = Math.max(Math.min(30, enc.maxQuality), enc.quality - 5);
      enc.quietSince = now;
    } else if (now - enc.quietSince > 3_000_000_000L && enc.quality < enc.maxQuality) {
      enc.quality = Math.min(enc.maxQuality, enc.quality + 2);
      enc.quietSince = now;
    }
    enc.skipsSeen = skips;
  }

  private void stats(Encoding enc, double drawMs, double readMs, double encodeMs, int bytes) {
    synchronized (enc) {
      enc.frames++;
      enc.drawMs += drawMs;
      enc.readMs += readMs;
      enc.encodeMs += encodeMs;
      enc.bytes += bytes;
      if (enc.frames >= 300) {
        log.info(
            "side {}: {} frames, draw {} ms, read {} ms, JPEG {} ms, {} KB a frame at quality {}",
            enc.side,
            enc.frames,
            String.format("%.1f", enc.drawMs / enc.frames),
            String.format("%.1f", enc.readMs / enc.frames),
            String.format("%.1f", enc.encodeMs / enc.frames),
            String.format("%.0f", enc.bytes / enc.frames / 1024),
            enc.quality);
        enc.frames = 0;
        enc.drawMs = enc.readMs = enc.encodeMs = enc.bytes = 0;
      }
    }
  }

  @Override
  public void dispose() {
    if (web != null) web.close();
    if (match != null) match.dispose();
    if (fbo != null) fbo.dispose();
    if (shapes != null) shapes.dispose();
    if (store != null) store.dispose();
    if (modelAssets != null) modelAssets.dispose();
    for (Client c : clients) if (c.encoder != null) c.encoder.dispose();
    cache.dispose();
  }
}
