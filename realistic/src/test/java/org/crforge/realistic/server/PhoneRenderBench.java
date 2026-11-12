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
import org.crforge.realistic.render.art.ArtCache;

/**
 * The draw-list stream measured over a whole battle of two bots (fixed seeds): polygons, vertices
 * and bytes a frame, the capture and encode time, the textures a battle draws; and parity frames:
 * at the frames asked for, the server's own picture (the frame buffer) beside the frame's draw list
 * as a key frame, for the page to replay ({@code tools/phone-render/parity.py}).
 *
 * <p>{@code ./gradlew :realistic:phoneRenderBench -Pcrforge.assetSource=cdn --args="OUT_DIR [frames
 * 900,1800] [WxH] [--sc3d=DIR] [--models]"}. Needs the art cache and a display (a hidden window).
 * With {@code --sc3d} the blue deck's hero forms (the Mega Minion, the Hero Tombstone) have 3D
 * models; the stream draws their 2D stand-in as the server does, or with {@code --models} their
 * model images (what the stream would carry without that fallback).
 */
public final class PhoneRenderBench extends ApplicationAdapter {

  private static final int FPS = 30;

  private final Path out;
  private final Set<Integer> dumps;
  private final int width;
  private final int height;
  private final boolean models;

  PhoneRenderBench(Path out, Set<Integer> dumps, int width, int height, boolean models) {
    this.out = out;
    this.dumps = dumps;
    this.width = width;
    this.height = height;
    this.models = models;
  }

  public static void main(String[] args) throws IOException {
    System.setProperty("java.awt.headless", "true");
    boolean models = false;
    List<String> positional = new ArrayList<>();
    for (String a : args) {
      if (a.startsWith("--sc3d=")) System.setProperty("crforge.sc3d", a.substring(7));
      else if (a.equals("--models")) models = true;
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
    new Lwjgl3Application(new PhoneRenderBench(out, dumps, w, h, models), config);
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
    BattleSession session =
        BattleSession.ladder(
            tables,
            BattleDecks.BLUE,
            BattleDecks.blueSlots(),
            BattleDecks.RED,
            BattleDecks.redSlots());
    Seat seat =
        new Seat(0, cache, tables, session, null, width, height, List.of("You", "Bot"), recorder);
    Bot blue = new Bot(0, 11);
    Bot red = new Bot(1, 22);
    List<String[]> heard = new ArrayList<>();
    // as the server records a draw list: the 3D rows' 2D stand-in (or, to measure, their models)
    seat.drawModels(models);
    seat.tick(BattleAdapter.frame(session), false, heard);

    FrameBuffer fbo = new FrameBuffer(Pixmap.Format.RGBA8888, width, height, false);
    OrthographicCamera camera = new OrthographicCamera(width, height);
    camera.setToOrtho(false, width, height);
    ShapeRenderer shapes = new ShapeRenderer();
    ByteBuffer pixels = BufferUtils.newByteBuffer(width * height * 4);
    DrawCodec.Encoder encoder = new DrawCodec.Encoder(defs, 6);
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
    try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out.resolve("frames.csv")))) {
      csv.println(
          "frame,tick,polygons,seen,vertices,flushes,raw,bytes,record_ms,encode_ms,patch_bytes");
      for (int k = 0; ; k++) {
        double t = k * 1000.0 / FPS;
        // the ticks due by this frame's time, as the server steps them (20 a second)
        while (!over && (ticks + 1) * 50.0 <= t) {
          ticks++;
          if (outro == 0) {
            BattleFrame before = seat.current;
            blue.decide(session, before);
            red.decide(session, before);
            if (session.step()) {
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
            "%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%.2f,%d%n",
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
            frameTex);
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
                + " pages %d (%.1f M texels); 3D models %s%n",
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
            models ? "drawn as images" : "off (2D stand-in)");
    System.out.print(summary);
    Files.writeString(out.resolve("summary.txt"), summary);
    encoder.dispose();
    shapes.dispose();
    fbo.dispose();
    seat.dispose();
    textures.dispose();
    cache.dispose();
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
    DrawCodec.Encoder fresh = new DrawCodec.Encoder(defs, 6);
    Files.write(out.resolve(k + ".bin"), fresh.encode(frame, true));
    fresh.dispose();
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
