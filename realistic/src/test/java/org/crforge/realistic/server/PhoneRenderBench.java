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
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.crforge.core.battle.GameData;
import org.crforge.core.battle.data.GameTables;
import org.crforge.realistic.bridge.BattleAdapter;
import org.crforge.realistic.bridge.BattleDecks;
import org.crforge.realistic.bridge.BattleFrame;
import org.crforge.realistic.bridge.BattleSession;
import org.crforge.realistic.bridge.ReplayFile;
import org.crforge.realistic.bridge.ReplayPlayer;
import org.crforge.realistic.render.art.ArtCache;

/**
 * The draw-list stream measured over a whole battle of two bots (fixed seeds): polygons, vertices
 * and bytes a frame, the capture and encode time, the textures a battle draws; and parity frames:
 * at the frames asked for, the server's own picture (the frame buffer) beside the frame's draw list
 * as a key frame, for the page to replay ({@code tools/phone-render/parity.py}).
 *
 * <p>{@code ./gradlew :realistic:phoneRenderBench -Pcrforge.assetSource=cdn --args="OUT_DIR [frames
 * 900,1800] [WxH] [--sc3d=DIR] [--models|--2d] [--replay=FILE]"}. Needs the art cache and a display
 * (a hidden window). With a 3D extraction the 3D rows (the blue deck's hero forms, the Mega Minion
 * and the Hero Tombstone; a replay's heroes) are sent as model draws the page renders itself, as
 * the server does ({@link ModelRecorder}); the parity frames then also write the model files they
 * use ({@code m3d/}). With {@code --2d} the stream draws their 2D stand-in instead, with {@code
 * --models} their model images (what the stream would carry with neither). With {@code --replay}
 * the battle is that replay's rather than two bots'.
 */
public final class PhoneRenderBench extends ApplicationAdapter {

  private static final int FPS = 30;

  private final Path out;
  private final Set<Integer> dumps;
  private final int width;
  private final int height;
  private final boolean models;
  private final boolean flat;
  private final Path replay;
  private ModelAssets assets;

  PhoneRenderBench(
      Path out,
      Set<Integer> dumps,
      int width,
      int height,
      boolean models,
      boolean flat,
      Path replay) {
    this.out = out;
    this.dumps = dumps;
    this.width = width;
    this.height = height;
    this.models = models;
    this.flat = flat;
    this.replay = replay;
  }

  public static void main(String[] args) throws IOException {
    System.setProperty("java.awt.headless", "true");
    boolean models = false;
    boolean flat = false;
    Path replay = null;
    List<String> positional = new ArrayList<>();
    for (String a : args) {
      if (a.startsWith("--sc3d=")) System.setProperty("crforge.sc3d", a.substring(7));
      else if (a.equals("--models")) models = true;
      else if (a.equals("--2d")) flat = true;
      else if (a.startsWith("--replay=")) replay = Paths.get(a.substring(9));
      else if (a.startsWith("--decks=")) System.setProperty("crforge.benchDecks", a.substring(8));
      else positional.add(a);
    }
    args = positional.toArray(new String[0]);
    Path out = Paths.get(args.length > 0 ? args[0] : "build/phone-render-bench");
    Files.createDirectories(out);
    Set<Integer> dumps = new TreeSet<>();
    if (args.length > 1) for (String s : args[1].split(",")) dumps.add(Integer.parseInt(s.trim()));
    int w = 720, h = 1560;
    if (args.length > 2) {
      String[] wh = args[2].split("x");
      w = Integer.parseInt(wh[0]);
      h = Integer.parseInt(wh[1]);
    }
    Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
    config.setTitle("phone render bench");
    config.setWindowedMode(320, 240);
    // placed, so no monitor is asked for its position (none may be awake), as the recorder does
    config.setWindowPosition(0, 0);
    config.setInitialVisible(false);
    config.useVsync(false);
    new Lwjgl3Application(new PhoneRenderBench(out, dumps, w, h, models, flat, replay), config);
  }

  @Override
  public void create() {
    try {
      run();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      Gdx.app.exit();
    }
  }

  private void run() throws IOException {
    GameTables tables = GameData.tables();
    ArtCache cache = ArtCache.locate().orElseThrow();
    TextureStore textures = new TextureStore(cache);
    DrawDefs defs = new DrawDefs(textures);
    DrawRecorder recorder = new DrawRecorder(defs);
    ReplayPlayer player = null;
    BattleSession session;
    if (replay != null) {
      ReplayFile file = ReplayFile.read(replay, tables);
      player = new ReplayPlayer(file, tables);
      session = player.getSession();
      if (session == null) {
        throw new IllegalStateException(
            "the replay cannot be played: " + String.join("; ", file.describe()));
      }
    } else if (System.getProperty("crforge.benchDecks") != null) {
      // two of the server's decks (MatchOptions), e.g. the hero decks
      String[] ids = System.getProperty("crforge.benchDecks").split(",");
      MatchOptions options = new MatchOptions(tables, cache.folder());
      MatchOptions.Deck a = options.deck(ids[0], "blue");
      MatchOptions.Deck b = options.deck(ids.length > 1 ? ids[1] : ids[0], "red");
      session = BattleSession.ladder(tables, a.cards(), a.slots(), b.cards(), b.slots());
    } else {
      session =
          BattleSession.ladder(
              tables,
              BattleDecks.BLUE,
              BattleDecks.blueSlots(),
              BattleDecks.RED,
              BattleDecks.redSlots());
    }
    Seat seat =
        new Seat(0, cache, tables, session, null, width, height, List.of("You", "Bot"), recorder);
    Bot blue = new Bot(0, 11);
    Bot red = new Bot(1, 22);
    List<String[]> heard = new ArrayList<>();
    // as the server records a draw list: the 3D models as model draws (or the 3D rows' 2D
    // stand-in, or, to measure, their model images)
    boolean phone3d = !models && !flat && seat.modelLibrary() != null;
    if (phone3d) {
      assets = new ModelAssets(seat.modelLibrary());
      seat.recordModels(assets);
    }
    seat.drawModels(models || phone3d);
    seat.tick(BattleAdapter.frame(session), false, heard);

    FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false);
    OrthographicCamera camera = new OrthographicCamera(width, height);
    camera.setToOrtho(false, width, height);
    ShapeRenderer shapes = new ShapeRenderer();
    ByteBuffer pixels = BufferUtils.newByteBuffer(width * height * 4);
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6).models(assets);
    DrawFrame[] ring = {new DrawFrame(), new DrawFrame()};
    int patchesSent = 0;
    long patchBytes = 0;
    long maxPatchFrame = 0;

    int ticks = 0;
    int outro = 0;
    boolean over = false;
    long bytes = 0;
    int maxBytes = 0;
    long rawBytes = 0;
    double recordMs = 0, encodeMs = 0, maxRecordMs = 0, maxEncodeMs = 0;
    int frames = 0;
    int maxPolys = 0, maxVerts = 0;
    long polys = 0, verts = 0, flushes = 0;
    long modelDraws = 0, modelBytes = 0;
    int maxModelDraws = 0;
    try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("frames.csv")))) {
      csv.println(
          "frame,tick,polygons,seen,vertices,flushes,raw,bytes,record_ms,encode_ms,patch_bytes,"
              + "model_draws,model_raw,effect_draws");
      for (int k = 0; ; k++) {
        double t = k * 1000.0 / FPS;
        // the ticks due by this frame's time, as the server steps them (20 a second)
        while (!over && (ticks + 1) * 50.0 <= t) {
          ticks++;
          if (outro == 0) {
            BattleFrame before = seat.current;
            if (player == null) {
              blue.decide(session, before);
              red.decide(session, before);
            }
            if (player != null ? player.step() : session.step()) {
              seat.tick(BattleAdapter.frame(session), false, heard);
            } else {
              outro = 1;
              seat.tick(null, true, heard);
            }
          } else {
            seat.tick(null, true, heard);
            if (++outro > 90) over = true;
          }
          heard.clear();
        }
        if (over) break;
        float alpha = (float) ((t - ticks * 50.0) / 50.0);
        boolean dump = dumps.contains(k);
        DrawFrame frame = ring[k & 1];
        long r0 = System.nanoTime();
        if (dump) fbo.begin();
        recorder.startFrame(frame, width, height, dump);
        seat.draw(alpha, camera, shapes, heard);
        recorder.endFrame();
        heard.clear();
        if (dump) {
          pixels.clear();
          Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
          Gdx.gl.glReadPixels(0, 0, width, height, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, pixels);
          fbo.end();
        }
        long r1 = System.nanoTime();
        byte[] packed = encoder.encode(frame, false);
        long r2 = System.nanoTime();
        if (dump) dump(k, frame, defs, textures, pixels);
        long frameTex = 0;
        for (; patchesSent < textures.patchCount(); patchesSent++) {
          frameTex += textures.png(textures.patch(patchesSent)).length;
        }
        patchBytes += frameTex;
        maxPatchFrame = Math.max(maxPatchFrame, frameTex);
        double rm = (r1 - r0) / 1e6, em = (r2 - r1) / 1e6;
        if (k > 30) {
          recordMs += rm;
          encodeMs += em;
          maxRecordMs = Math.max(maxRecordMs, rm);
          maxEncodeMs = Math.max(maxEncodeMs, em);
        }
        bytes += packed.length;
        rawBytes += encoder.rawBytes;
        modelDraws += frame.models.size();
        maxModelDraws = Math.max(maxModelDraws, frame.models.size());
        modelBytes += encoder.modelBytes;
        maxBytes = Math.max(maxBytes, packed.length);
        int v = frame.vertexCount();
        polys += frame.count;
        verts += v;
        flushes += frame.flushes;
        maxPolys = Math.max(maxPolys, frame.count);
        maxVerts = Math.max(maxVerts, v);
        frames++;
        csv.printf(
            Locale.ROOT,
            "%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%d,%d,%d,%d%n",
            k,
            ticks,
            frame.count,
            frame.seen,
            v,
            frame.flushes,
            encoder.rawBytes,
            packed.length,
            rm,
            em,
            frameTex,
            frame.models.size(),
            encoder.modelBytes,
            frame.models.stream().mapToInt(m -> m.fx.size()).sum());
      }
    }
    long texBytes = patchBytes;
    String summary =
        String.format(
            Locale.ROOT,
            "frames %d (%.0f s at %d fps), ticks %d%n"
                + "polygons a frame: avg %.0f max %d; vertices: avg %.0f max %d; GL draws (flushes)"
                + " avg %.0f%n"
                + "bytes a frame (deflated): avg %.0f max %d; raw avg %.0f%n"
                + "stream: %.2f Mbit/s at %d fps%n"
                + "record %.2f ms avg (%.1f max), encode %.2f ms avg (%.1f max)%n"
                + "regions %d, states %d; patches %d, %.1f MB as PNG, max %.0f KB in a frame;"
                + " pages %d (%.1f M texels); 3D models %s%n"
                + "model draws a frame: avg %.1f max %d; model section %.1f bytes a draw before"
                + " deflate; model files %.2f MB (%d models)%n",
            frames,
            frames / (double) FPS,
            FPS,
            ticks,
            polys / (double) frames,
            maxPolys,
            verts / (double) frames,
            maxVerts,
            flushes / (double) frames,
            bytes / (double) frames,
            maxBytes,
            rawBytes / (double) frames,
            bytes * 8.0 / (frames / (double) FPS) / 1e6,
            FPS,
            recordMs / Math.max(1, frames - 31),
            maxRecordMs,
            encodeMs / Math.max(1, frames - 31),
            maxEncodeMs,
            defs.regionCount(),
            defs.stateCount(),
            textures.patchCount(),
            texBytes / 1e6,
            maxPatchFrame / 1024.0,
            textures.pageCount() - 1,
            textures.texels() / 1e6,
            models ? "drawn as images" : phone3d ? "sent as model draws" : "off (2D stand-in)",
            modelDraws / (double) frames,
            maxModelDraws,
            modelDraws == 0 ? 0 : modelBytes / (double) modelDraws,
            assets == null ? 0 : assets.bytes(assetIds()) / 1e6,
            assets == null ? 0 : assetIds().size());
    System.out.print(summary);
    Files.writeString(out.resolve("summary.txt"), summary);
    encoder.dispose();
    shapes.dispose();
    fbo.dispose();
    seat.dispose();
    textures.dispose();
    cache.dispose();
  }

  /** The model files the battle's draws used. */
  private java.util.Set<String> assetIds() {
    java.util.Set<String> ids = new TreeSet<>();
    for (int i = 0; i < assets.refCount(); i++) ids.add(assets.ref(i).asset());
    return ids;
  }

  /**
   * Writes frame {@code k}: the server's picture ({@code k_server.png}, top row first), the frame
   * as a key frame ({@code k.bin}) and the textures it draws ({@code k_textures.json}, runtime ones
   * written as PNG beside it).
   */
  private void dump(int k, DrawFrame frame, DrawDefs defs, TextureStore textures, ByteBuffer pixels)
      throws IOException {
    byte[] rgba = new byte[width * height * 4];
    pixels.rewind();
    pixels.get(rgba);
    byte[] flipped = new byte[rgba.length];
    for (int y = 0; y < height; y++) {
      System.arraycopy(rgba, (height - 1 - y) * width * 4, flipped, y * width * 4, width * 4);
    }
    // opaque, as the canvas is
    for (int i = 3; i < flipped.length; i += 4) flipped[i] = (byte) 255;
    Files.write(out.resolve(k + "_server.png"), TextureStore.encodePng(width, height, flipped));
    DrawCodec.Encoder fresh = new DrawCodec.Encoder(defs, 6).models(assets);
    Files.write(out.resolve(k + ".bin"), fresh.encode(frame, true));
    fresh.dispose();
    if (assets != null && !frame.models.isEmpty()) {
      // the model files the frame's draws use, as the server serves them (/m3d/...)
      Path m3d = out.resolve("m3d");
      Files.createDirectories(m3d.resolve("model"));
      Files.createDirectories(m3d.resolve("tex"));
      float[][] u =
          org.crforge.realistic.render.art.model.ModelRenderer.sceneUniforms(assets.library());
      StringBuilder scene = new StringBuilder("{");
      String[] names = {"light", "camera", "direct", "ambient", "blue", "red"};
      for (int i = 0; i < names.length; i++) {
        if (i > 0) scene.append(',');
        scene.append('"').append(names[i]).append("\":[");
        for (int j = 0; j < u[i].length; j++) {
          if (j > 0) scene.append(',');
          scene.append(u[i][j]);
        }
        scene.append(']');
      }
      Files.writeString(m3d.resolve("scene.json"), scene.append('}'));
      for (ModelInstance mi : frame.models) {
        List<Integer> used = new ArrayList<>();
        for (int ref : mi.refs) used.add(ref);
        for (ModelInstance.Fx e : mi.fx) {
          used.add(e.ref());
          for (String t : e.textures()) {
            if (t != null) {
              Files.write(m3d.resolve("tex").resolve(t + ".png"), assets.texture(t).orElseThrow());
            }
          }
        }
        for (int ref : used) {
          String id = assets.ref(ref).asset();
          byte[] bin = assets.model(id).orElseThrow();
          Files.write(m3d.resolve("model").resolve(id + ".bin"), bin);
          for (String t : ModelAssets.textureIds(bin)) {
            Files.write(m3d.resolve("tex").resolve(t + ".png"), assets.texture(t).orElseThrow());
          }
        }
      }
    }
    StringBuilder json = new StringBuilder("[");
    Path dir = out.resolve(k + "_patches");
    Files.createDirectories(dir);
    for (int seq = 0; seq < textures.patchCount(); seq++) {
      TextureStore.Patch p = textures.patch(seq);
      TextureStore.Page page = textures.page(p.page());
      Path file = dir.resolve(seq + ".png");
      Files.write(file, textures.png(p));
      if (json.length() > 1) json.append(',');
      json.append(
          String.format(
              Locale.ROOT,
              "{\"page\":%d,\"plain\":%b,\"pw\":%d,\"ph\":%d,\"x\":%d,\"y\":%d,\"file\":\"%s\"}",
              p.page(),
              page.plain,
              page.width,
              page.height,
              p.x(),
              p.y(),
              file.toAbsolutePath()));
    }
    json.append(']');
    Files.writeString(out.resolve(k + "_textures.json"), json);
  }
}
