/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.BufferUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.crforge.realistic.render.art.ArtCache;

/**
 * The textures the page draws with: the parts of the server's textures that draw lists use, packed
 * into pages ({@value #PAGE} pixels square) as they are first drawn. A battle draws about a tenth
 * of the atlases it touches (the HUD a few cards of the UI atlases, a unit the animations it
 * plays), so a page is sent only those parts, as patches: a rectangle of a source texture (plus a
 * margin of {@value #PAD} texels for filtering and mipmaps) copied 1:1 into a page.
 *
 * <p>Two kinds of page: art (an atlas of the art cache: sent with straight alpha as the file holds
 * it, premultiplied by the page on upload as {@code ArtCache} premultiplies it, sampled through
 * mipmaps) and plain (a texture made at run time, a font's glyph pages: read back from GL and sent
 * as held, sampled linearly). Texture 0 is a white texel the page makes itself (the placement
 * overlay's flat shapes).
 *
 * <p>Pages and patches are numbered for the store's life; a page is sent each patch once, in order
 * ({@link #patchCount}, {@link #patch}). Nothing is written to disk.
 */
@Slf4j
final class TextureStore {

  /** The white texel every page makes for itself. */
  static final int WHITE = 0;

  static final int PAGE = 2048;
  static final int PAD = 4;

  /** A texture the server draws with, by its content (a font made again is the same source). */
  record Source(int id, int width, int height, boolean plain, Path file, byte[] rgba) {}

  /** A page: {@code plain} as {@link Source#plain()}. */
  static final class Page {
    final int id;
    final boolean plain;
    final int width;
    final int height;

    /** The skyline: the lowest free y of each column. */
    private final int[] sky;

    Page(int id, boolean plain, int width, int height) {
      this.id = id;
      this.plain = plain;
      this.width = width;
      this.height = height;
      this.sky = new int[width];
    }

    /** A free place for w x h (bottom-left skyline, on a 4 texel grid), as {x, y}, or null. */
    int[] fit(int w, int h) {
      if (w > width || h > height) return null;
      int bestX = -1, bestY = Integer.MAX_VALUE;
      // the highest column under each window of w columns: a sliding maximum
      int[] dq = new int[width];
      int head = 0, tail = 0;
      for (int i = 0; i < width; i++) {
        while (tail > head && sky[dq[tail - 1]] <= sky[i]) tail--;
        dq[tail++] = i;
        int x = i - w + 1;
        if (x < 0) continue;
        while (dq[head] < x) head++;
        if ((x & 3) != 0) continue;
        int y = sky[dq[head]];
        if (y + h <= height && y < bestY) {
          bestY = y;
          bestX = x;
        }
      }
      if (bestX < 0) return null;
      int top = ceil4(bestY + h);
      for (int i = bestX; i < Math.min(width, bestX + ceil4(w)); i++) sky[i] = top;
      return new int[] {bestX, bestY};
    }
  }

  /** A rectangle of a source copied to a page. */
  record Patch(int seq, int page, int x, int y, int w, int h, int source, int sx, int sy) {}

  /** Where a region's texture coordinates go: the page and the coordinates in it. */
  record Placed(int page, float[] uv, int patch) {}

  private final ArtCache cache;
  private final Map<Texture, Integer> sourceIds = new IdentityHashMap<>();
  private final Map<String, Integer> sourceByKey = new HashMap<>();
  private final List<Source> sources = new ArrayList<>();
  private final List<Page> pages = new ArrayList<>();
  private final List<Patch> patches = new ArrayList<>();
  private final Map<Integer, List<Patch>> bySource = new HashMap<>();
  private final Map<Integer, byte[]> patchPng = new HashMap<>();
  private long texels;

  /** Decoded atlases, the few last used (their pixels are native memory). */
  private final LinkedHashMap<Integer, Pixmap> decoded = new LinkedHashMap<>(8, 0.75f, true);

  TextureStore(ArtCache cache) {
    this.cache = cache;
    pages.add(new Page(WHITE, true, 1, 1));
  }

  /**
   * The source id of a texture drawn on the GL thread; a texture not from the art cache is read
   * back the first time.
   */
  int source(Texture texture) {
    if (texture == null) return -1;
    Integer id = sourceIds.get(texture);
    if (id != null) return id;
    Path file = cache == null ? null : cache.sourceOf(texture);
    String key;
    byte[] rgba = null;
    if (file != null) {
      key = file.toString();
    } else {
      rgba = readBack(texture);
      key = hash(texture.getWidth() + "x" + texture.getHeight(), rgba);
    }
    int sid = source(key, texture.getWidth(), texture.getHeight(), file, rgba);
    sourceIds.put(texture, sid);
    return sid;
  }

  /**
   * The source id of a texture by its content's key: an atlas {@code file} (straight alpha), or
   * pixels held as drawn ({@code rgba}, rows in upload order).
   */
  int source(String key, int width, int height, Path file, byte[] rgba) {
    Integer known = sourceByKey.get(key);
    if (known == null) {
      synchronized (this) {
        known = sources.size();
        sources.add(new Source(known, width, height, file == null, file, rgba));
      }
      sourceByKey.put(key, known);
    }
    return known;
  }

  /** Forgets the GL textures seen (a battle's fonts are disposed with it); their sources stay. */
  void forgetObjects() {
    sourceIds.clear();
  }

  /**
   * Places a region of {@code source} (u, v pairs) in a page, packing the part of the source it
   * covers there unless a patch already holds it. Called on the GL thread as regions are made.
   */
  Placed place(int source, float[] uv, int at, int stride, int n) {
    if (source < 0) return new Placed(WHITE, new float[2 * n], -1);
    Source s = sources.get(source);
    float u0 = Float.MAX_VALUE, v0 = Float.MAX_VALUE, u1 = -Float.MAX_VALUE, v1 = -Float.MAX_VALUE;
    for (int i = 0; i < n; i++) {
      u0 = Math.min(u0, uv[at + i * stride]);
      u1 = Math.max(u1, uv[at + i * stride]);
      v0 = Math.min(v0, uv[at + i * stride + 1]);
      v1 = Math.max(v1, uv[at + i * stride + 1]);
    }
    // the texels the region samples, its margin, on a 4 texel grid (mip levels 1 and 2 align)
    int x0 = Math.max(0, floor4((int) Math.floor(u0 * s.width()) - PAD));
    int y0 = Math.max(0, floor4((int) Math.floor(v0 * s.height()) - PAD));
    int x1 = Math.min(s.width(), ceil4((int) Math.ceil(u1 * s.width()) + PAD));
    int y1 = Math.min(s.height(), ceil4((int) Math.ceil(v1 * s.height()) + PAD));
    Patch p = null;
    for (Patch q : bySource.getOrDefault(source, List.of())) {
      if (q.sx() <= x0 && q.sy() <= y0 && q.sx() + q.w() >= x1 && q.sy() + q.h() >= y1) {
        p = q;
        break;
      }
    }
    if (p == null) p = pack(s, x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
    Page page = pages.get(p.page());
    float[] out = new float[2 * n];
    for (int i = 0; i < n; i++) {
      double tu = uv[at + i * stride] * (double) s.width() - p.sx() + p.x();
      double tv = uv[at + i * stride + 1] * (double) s.height() - p.sy() + p.y();
      out[2 * i] = (float) (tu / page.width);
      out[2 * i + 1] = (float) (tv / page.height);
    }
    return new Placed(p.page(), out, p.seq());
  }

  private Patch pack(Source s, int sx, int sy, int w, int h) {
    int[] at = null;
    Page page = null;
    if (w <= PAGE && h <= PAGE) {
      for (Page p : pages) {
        if (p.id == WHITE || p.plain != s.plain() || p.width != PAGE) continue;
        at = p.fit(w, h);
        if (at != null) {
          page = p;
          break;
        }
      }
    }
    synchronized (this) {
      if (page == null) {
        // a new page; a part larger than a page gets a page of its own size
        int pw = Math.max(PAGE, w), ph = Math.max(PAGE, h);
        page = new Page(pages.size(), s.plain(), pw, ph);
        pages.add(page);
        texels += (long) pw * ph;
        at = page.fit(w, h);
      }
      Patch p = new Patch(patches.size(), page.id, at[0], at[1], w, h, s.id(), sx, sy);
      patches.add(p);
      bySource.computeIfAbsent(s.id(), k -> new ArrayList<>()).add(p);
      return p;
    }
  }

  private static int floor4(int v) {
    return v & ~3;
  }

  private static int ceil4(int v) {
    return (v + 3) & ~3;
  }

  synchronized int patchCount() {
    return patches.size();
  }

  synchronized Patch patch(int seq) {
    return patches.get(seq);
  }

  synchronized Page page(int id) {
    return pages.get(id);
  }

  synchronized int pageCount() {
    return pages.size();
  }

  /**
   * The texels of the pages made so far (the page's GPU memory, before mipmaps, is 4 bytes each).
   */
  synchronized long texels() {
    return texels;
  }

  /** A patch's pixels as PNG (straight alpha), made once. Any thread. */
  byte[] png(Patch p) {
    synchronized (patchPng) {
      byte[] done = patchPng.get(p.seq());
      if (done != null) return done;
    }
    Source s;
    synchronized (this) {
      s = sources.get(p.source());
    }
    byte[] rgba = new byte[p.w() * p.h() * 4];
    if (s.rgba() != null) {
      for (int y = 0; y < p.h(); y++) {
        System.arraycopy(
            s.rgba(), ((p.sy() + y) * s.width() + p.sx()) * 4, rgba, y * p.w() * 4, p.w() * 4);
      }
    } else {
      synchronized (decoded) {
        Pixmap pm = decoded.get(s.id());
        if (pm == null) {
          pm = new Pixmap(new FileHandle(s.file().toFile()));
          if (pm.getFormat() != Pixmap.Format.RGBA8888) {
            Pixmap c = new Pixmap(pm.getWidth(), pm.getHeight(), Pixmap.Format.RGBA8888);
            c.setBlending(Pixmap.Blending.None);
            c.drawPixmap(pm, 0, 0);
            pm.dispose();
            pm = c;
          }
          decoded.put(s.id(), pm);
          while (decoded.size() > 4) {
            var eldest = decoded.entrySet().iterator().next();
            eldest.getValue().dispose();
            decoded.remove(eldest.getKey());
          }
        }
        ByteBuffer px = pm.getPixels();
        for (int y = 0; y < p.h(); y++) {
          px.position(((p.sy() + y) * s.width() + p.sx()) * 4);
          px.get(rgba, y * p.w() * 4, p.w() * 4);
        }
        px.position(0);
      }
    }
    byte[] png;
    try {
      png = encodePng(p.w(), p.h(), rgba);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    synchronized (patchPng) {
      patchPng.put(p.seq(), png);
    }
    return png;
  }

  void dispose() {
    synchronized (decoded) {
      decoded.values().forEach(Pixmap::dispose);
      decoded.clear();
    }
  }

  static byte[] encodePng(int w, int h, byte[] rgba) throws IOException {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    int[] row = new int[w];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int o = (y * w + x) * 4;
        row[x] =
            (rgba[o + 3] & 0xFF) << 24
                | (rgba[o] & 0xFF) << 16
                | (rgba[o + 1] & 0xFF) << 8
                | (rgba[o + 2] & 0xFF);
      }
      img.setRGB(0, y, w, 1, row, 0, w);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "png", out);
    return out.toByteArray();
  }

  /** A texture's level 0 as GL holds it, rows in upload order, through a scratch framebuffer. */
  private static byte[] readBack(Texture texture) {
    int w = texture.getWidth();
    int h = texture.getHeight();
    IntBuffer prev = BufferUtils.newIntBuffer(16);
    Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, prev);
    int fb = Gdx.gl.glGenFramebuffer();
    Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, fb);
    Gdx.gl.glFramebufferTexture2D(
        GL20.GL_FRAMEBUFFER,
        GL20.GL_COLOR_ATTACHMENT0,
        GL20.GL_TEXTURE_2D,
        texture.getTextureObjectHandle(),
        0);
    ByteBuffer px = BufferUtils.newByteBuffer(w * h * 4);
    Gdx.gl.glPixelStorei(GL20.GL_PACK_ALIGNMENT, 1);
    Gdx.gl.glReadPixels(0, 0, w, h, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, px);
    Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, prev.get(0));
    Gdx.gl.glDeleteFramebuffer(fb);
    byte[] rgba = new byte[w * h * 4];
    px.get(rgba);
    return rgba;
  }

  private static String hash(String name, byte[]... data) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      md.update(name.getBytes(StandardCharsets.UTF_8));
      for (byte[] d : data) md.update(d);
      return java.util.HexFormat.of().formatHex(md.digest(), 0, 10);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
