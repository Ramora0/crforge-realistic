/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.utils.Disposable;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Draws the game's UI movie clips for the {@link BattleHud}: like {@link ScDrawer}, but it applies
 * the colour transforms' additive part (the health bars' fills and the HUD panels are coloured that
 * way), knows the clips' child names (read from the scene JSON) and lets the caller hide, re-frame
 * or move a named child, or draw its own content (text, card icons) in the child's place.
 */
@Slf4j
final class HudArt implements Disposable {

  /** A hook's answer meaning "do not draw this child". */
  static final int SKIP = Integer.MIN_VALUE;

  /** What the caller decides for each named child as the walk reaches it. */
  interface Hook {
    /**
     * Called for each child of a clip (also unnamed ones, with "" as the name). {@code local} is
     * the child's matrix in its parent and may be changed. Returns the frame to play the child at
     * ({@code frame} to inherit the parent's), or {@link #SKIP}.
     */
    default int child(String name, int id, Affine2 local, int frame) {
      return frame;
    }

    /** Called for a text field child, {@code world} mapping its display units to world pixels. */
    default void text(String name, int id, Affine2 world, float alpha) {}

    /**
     * For a child about to be drawn: the share (0 to 1) of a dial about the child's origin to draw
     * of it, the part that runs counter-clockwise back from twelve o'clock (a cooldown's part still
     * to run, which its dial's mask covers), or a negative number to draw it all.
     */
    default float sweep(String name) {
      return -1f;
    }
  }

  static final Hook NONE = new Hook() {};

  private static final int MAX_DEPTH = 24;
  private static final int MAX_VERTS = 2048;

  private static final String VERTEX =
      "attribute vec4 a_position;\n"
          + "attribute vec4 a_color;\n"
          + "attribute vec2 a_texCoord0;\n"
          + "uniform mat4 u_projTrans;\n"
          + "varying vec4 v_color;\n"
          + "varying vec2 v_texCoords;\n"
          + "void main() {\n"
          + "  v_color = a_color;\n"
          + "  v_color.a = v_color.a * (255.0/254.0);\n"
          + "  v_texCoords = a_texCoord0;\n"
          + "  gl_Position = u_projTrans * a_position;\n"
          + "}\n";

  /**
   * Premultiplied texture times premultiplied vertex colour, plus the additive colour, then mixed
   * toward its own luminance by {@code u_gray}: the client's {@code Grayscale} state of a button
   * the player cannot afford (ability_button.toml), for the hand's unaffordable cards too.
   */
  private static final String FRAGMENT =
      "#ifdef GL_ES\nprecision mediump float;\n#endif\n"
          + "varying vec4 v_color;\n"
          + "varying vec2 v_texCoords;\n"
          + "uniform sampler2D u_texture;\n"
          + "uniform vec3 u_add;\n"
          + "uniform float u_gray;\n"
          + "void main() {\n"
          + "  vec4 c = v_color * texture2D(u_texture, v_texCoords);\n"
          + "  c.rgb = min(c.rgb + u_add * c.a, vec3(c.a));\n"
          + "  c.rgb = mix(c.rgb, vec3(dot(c.rgb, vec3(0.299, 0.587, 0.114))), u_gray);\n"
          + "  gl_FragColor = c;\n"
          + "}\n";

  /** What HudArt reads of a scene's JSON beyond {@link ScScene}: child names and text fields. */
  private record SceneInfo(Map<Integer, String[]> names, java.util.Set<Integer> texts) {}

  private final ArtCache cache;
  private final Map<String, SceneInfo> infos = new HashMap<>();
  private final float[] verts = new float[MAX_VERTS * 5];
  private final short[] tris = new short[MAX_VERTS * 3];
  private final Affine2[] stack = new Affine2[MAX_DEPTH + 1];
  private final Affine2[] locals = new Affine2[MAX_DEPTH + 1];
  private final float[][] muls = new float[MAX_DEPTH + 1][4];
  private final float[][] adds = new float[MAX_DEPTH + 1][3];
  private ShaderProgram shader;
  private PolygonSpriteBatch batch;
  private float addR = -1, addG = -1, addB = -1;
  private float gray = -1;
  private int currentBlend = -1;

  /**
   * While a swept child is drawn: the convex pieces (x, y pairs in world pixels) of the dial's part
   * its shapes are clipped to; null when nothing is swept.
   */
  private List<float[]> sweepPieces;

  private final float[] clipA = new float[64 * 4];
  private final float[] clipB = new float[64 * 4];
  private final float[] swept = new float[MAX_VERTS * 5];
  private final short[] sweptTris = new short[MAX_VERTS * 3];

  // bounds accumulation (when measuring instead of drawing)
  private boolean measuring;
  private float minX, minY, maxX, maxY;

  HudArt(ArtCache cache) {
    this.cache = cache;
    for (int i = 0; i < stack.length; i++) {
      stack[i] = new Affine2();
      locals[i] = new Affine2();
    }
  }

  /** Starts drawing on {@code batch} (which must not be drawing). */
  void begin(PolygonSpriteBatch batch, com.badlogic.gdx.math.Matrix4 projection) {
    this.batch = batch;
    if (shader == null) {
      ShaderProgram.pedantic = false;
      shader = new ShaderProgram(VERTEX, FRAGMENT);
      if (!shader.isCompiled()) {
        log.warn("HUD shader failed: {}", shader.getLog());
        shader.dispose();
        shader = null;
      }
    }
    batch.setProjectionMatrix(projection);
    if (shader != null) batch.setShader(shader);
    batch.begin();
    currentBlend = -1;
    addR = addG = addB = -1;
    gray = -1;
    setBlend(ScDrawer.BLEND_NORMAL);
    setAdd(0, 0, 0);
    setGray(0f);
  }

  void end() {
    batch.end();
    batch.setShader(null);
    batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
  }

  PolygonSpriteBatch batch() {
    return batch;
  }

  /**
   * How far what is drawn next is turned to grey, 0 (its colours) to 1 (its luminance), until
   * changed again; {@link #begin} resets it to 0.
   */
  void setGray(float amount) {
    if (shader == null) return;
    amount = Math.max(0f, Math.min(1f, amount));
    if (amount == gray) return;
    batch.flush();
    gray = amount;
    shader.setUniformf("u_gray", amount);
  }

  /** Switches the batch to straight-alpha drawing (fonts), no additive colour. */
  void straightAlpha() {
    setAdd(0, 0, 0);
    if (currentBlend != 100) {
      batch.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
      currentBlend = 100;
    }
  }

  /** The child names of the clips of a scene, read once from its JSON. */
  String[] names(ScScene scene, int clipId) {
    return info(scene).names().get(clipId);
  }

  /**
   * Whether object {@code id} of a clip is a mask modifier rather than something drawn: neither a
   * shape, a clip nor a text field. Within a frame the modifiers come in threes around a mask: the
   * first starts the mask (the child after it is the mask's shape), the second its masked content,
   * the third ends it. False for every id of a scene whose text fields are not listed.
   */
  private boolean isModifier(ScScene scene, int id) {
    java.util.Set<Integer> texts = info(scene).texts();
    return !texts.isEmpty()
        && !texts.contains(id)
        && scene.shape(id) == null
        && scene.clip(id) == null;
  }

  private SceneInfo info(ScScene scene) {
    return infos.computeIfAbsent(scene.folder().resolve(scene.file()).toString(), k -> read(scene));
  }

  private SceneInfo read(ScScene scene) {
    Map<Integer, String[]> out = new HashMap<>();
    java.util.Set<Integer> texts = new java.util.HashSet<>();
    String stem = scene.file();
    int slash = stem.lastIndexOf('/');
    if (slash >= 0) stem = stem.substring(slash + 1);
    if (stem.endsWith(".sc")) stem = stem.substring(0, stem.length() - 3);
    Path json = scene.folder().resolve(stem + ".json");
    if (!Files.isRegularFile(json)) return new SceneInfo(out, texts);
    try (JsonParser p = new JsonFactory().createParser(json.toFile())) {
      if (p.nextToken() != JsonToken.START_OBJECT) return new SceneInfo(out, texts);
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        String field = p.currentName();
        p.nextToken();
        if ("text".equals(field) && p.currentToken() == JsonToken.START_ARRAY) {
          while (p.nextToken() != JsonToken.END_ARRAY) {
            if (p.currentToken() == JsonToken.VALUE_NUMBER_INT) texts.add(p.getIntValue());
            else p.skipChildren();
          }
          continue;
        }
        if (!"clips".equals(field)) {
          p.skipChildren();
          continue;
        }
        while (p.nextToken() == JsonToken.FIELD_NAME) {
          int id = Integer.parseInt(p.currentName());
          p.nextToken(); // clip object
          while (p.nextToken() == JsonToken.FIELD_NAME) {
            String key = p.currentName();
            p.nextToken();
            if ("names".equals(key) && p.currentToken() == JsonToken.START_ARRAY) {
              List<String> list = new ArrayList<>();
              while (p.nextToken() != JsonToken.END_ARRAY) list.add(p.getValueAsString(""));
              out.put(id, list.toArray(new String[0]));
            } else {
              p.skipChildren();
            }
          }
        }
      }
    } catch (IOException | RuntimeException e) {
      log.warn("Cannot read the clip names of {}: {}", json, e.getMessage());
    }
    return new SceneInfo(out, texts);
  }

  /** Draws object {@code id} at {@code frame}, {@code hook} deciding for its named children. */
  void draw(ScScene scene, int id, int frame, Affine2 transform, Color tint, Hook hook) {
    if (scene == null || id < 0) return;
    stack[0].set(transform);
    float[] m = muls[0];
    m[0] = tint.r;
    m[1] = tint.g;
    m[2] = tint.b;
    m[3] = tint.a;
    adds[0][0] = adds[0][1] = adds[0][2] = 0;
    walk(scene, id, frame, 0, ScDrawer.BLEND_NORMAL, hook == null ? NONE : hook);
    if (!measuring) setBlend(ScDrawer.BLEND_NORMAL);
  }

  /**
   * The bounds (minX, minY, maxX, maxY) in the object's display units of object {@code id} at
   * {@code frame}, with the hook's choices applied; null when nothing is drawn.
   */
  float[] bounds(ScScene scene, int id, int frame, Hook hook) {
    measuring = true;
    minX = minY = Float.POSITIVE_INFINITY;
    maxX = maxY = Float.NEGATIVE_INFINITY;
    try {
      draw(scene, id, frame, new Affine2(), Color.WHITE, hook);
    } finally {
      measuring = false;
    }
    return minX > maxX ? null : new float[] {minX, minY, maxX, maxY};
  }

  private void walk(ScScene scene, int id, int frame, int depth, int blend, Hook hook) {
    if (depth >= MAX_DEPTH || muls[depth][3] <= 0.002f) return;
    ScScene.Command[] commands = scene.shape(id);
    if (commands != null) {
      for (ScScene.Command c : commands) drawCommand(scene, c, depth, blend);
      return;
    }
    ScScene.Clip clip = scene.clip(id);
    if (clip == null || clip.frameCount() == 0) {
      if (!measuring) hook.text("", id, stack[depth], muls[depth][3]);
      return;
    }
    String[] childNames = names(scene, id);
    int[] elements = clip.frames()[Math.floorMod(frame, clip.frameCount())];
    ScScene.Bank bank = scene.bank(clip.bank());
    // 1 after a mask's start (its shape follows, not drawn), 2 in its content; the content is
    // drawn unclipped (the HUD's clips keep it inside the mask where they are drawn)
    int maskStep = 0;
    for (int k = 0; k + 2 < elements.length; k += 3) {
      int child = elements[k];
      int mi = elements[k + 1];
      int ci = elements[k + 2];
      if (child < 0 || child >= clip.children().length) continue;
      if (isModifier(scene, clip.children()[child])) {
        maskStep = (maskStep + 1) % 3;
        continue;
      }
      if (maskStep == 1) continue;
      Affine2 local = locals[depth + 1];
      local.idt();
      if (mi != ScScene.IDENTITY && bank != null && mi * 6 + 5 < bank.matrices().length) {
        float[] mm = bank.matrices();
        int o = mi * 6;
        local.m00 = mm[o];
        local.m10 = mm[o + 1];
        local.m01 = mm[o + 2];
        local.m11 = mm[o + 3];
        local.m02 = mm[o + 4];
        local.m12 = mm[o + 5];
      }
      String name = childNames != null && child < childNames.length ? childNames[child] : "";
      int childId = clip.children()[child];
      int childFrame = hook.child(name, childId, local, frame);
      if (childFrame == SKIP) continue;
      Affine2 m = stack[depth + 1];
      m.set(stack[depth]).mul(local);
      float[] pm = muls[depth];
      float[] pa = adds[depth];
      float[] cm = muls[depth + 1];
      float[] ca = adds[depth + 1];
      System.arraycopy(pm, 0, cm, 0, 4);
      System.arraycopy(pa, 0, ca, 0, 3);
      if (ci != ScScene.IDENTITY && bank != null && ci * 7 + 6 < bank.colors().length) {
        int[] cc = bank.colors();
        int o = ci * 7;
        cm[0] = pm[0] * cc[o] / 255f;
        cm[1] = pm[1] * cc[o + 1] / 255f;
        cm[2] = pm[2] * cc[o + 2] / 255f;
        cm[3] = pm[3] * cc[o + 3] / 255f;
        ca[0] = pa[0] + pm[0] * cc[o + 4] / 255f;
        ca[1] = pa[1] + pm[1] * cc[o + 5] / 255f;
        ca[2] = pa[2] + pm[2] * cc[o + 6] / 255f;
      }
      if (scene.shape(childId) == null && scene.clip(childId) == null) {
        if (!measuring) hook.text(name, childId, m, cm[3]);
        continue;
      }
      int childBlend =
          child < clip.blend().length && clip.blend()[child] != 0 ? clip.blend()[child] : blend;
      float share = measuring || sweepPieces != null ? -1f : hook.sweep(name);
      if (share >= 0f) {
        if (share <= 0.001f) continue;
        sweepPieces = dial(m, share);
        try {
          walk(scene, childId, childFrame, depth + 1, childBlend, hook);
        } finally {
          sweepPieces = null;
        }
        continue;
      }
      walk(scene, childId, childFrame, depth + 1, childBlend, hook);
    }
  }

  /** The dial's radius in the swept child's display units: past any HUD dial's corners. */
  private static final float DIAL_RADIUS = 400f;

  /**
   * The part of a dial about {@code m}'s origin that runs {@code share} of a turn counter-clockwise
   * back from twelve o'clock in the child's own units (y down), as convex pieces in world pixels.
   */
  private static List<float[]> dial(Affine2 m, float share) {
    List<float[]> pieces = new ArrayList<>();
    float end = (float) (Math.PI * 2);
    float start = end * (1f - Math.min(1f, share));
    int steps = 6; // per piece of at most a quarter turn
    for (float a0 = start; a0 < end - 1e-4f; a0 += (float) (Math.PI / 2)) {
      float a1 = Math.min(end, a0 + (float) (Math.PI / 2));
      float[] piece = new float[(steps + 2) * 2];
      piece[0] = m.m02;
      piece[1] = m.m12;
      for (int i = 0; i <= steps; i++) {
        double a = a0 + (a1 - a0) * i / steps;
        // clockwise from twelve o'clock with y down
        float x = (float) (Math.sin(a) * DIAL_RADIUS);
        float y = (float) (-Math.cos(a) * DIAL_RADIUS);
        piece[2 + i * 2] = m.m00 * x + m.m01 * y + m.m02;
        piece[3 + i * 2] = m.m10 * x + m.m11 * y + m.m12;
      }
      pieces.add(piece);
    }
    return pieces;
  }

  private void drawCommand(ScScene scene, ScScene.Command c, int depth, int blend) {
    int n = c.xy().length / 2;
    if (n < 3 || n > MAX_VERTS) return;
    Affine2 m = stack[depth];
    float[] xy = c.xy();
    if (measuring) {
      for (int i = 0; i < n; i++) {
        float x = m.m00 * xy[i * 2] + m.m01 * xy[i * 2 + 1] + m.m02;
        float y = m.m10 * xy[i * 2] + m.m11 * xy[i * 2 + 1] + m.m12;
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
      }
      return;
    }
    float[] mul = muls[depth];
    float[] add = adds[depth];
    float a = Math.min(1f, mul[3]);
    if (a <= 0.002f) return;
    Texture texture = cache.texture(scene, c.texture());
    float packed =
        Color.toFloatBits(
            Math.min(1f, mul[0]) * a, Math.min(1f, mul[1]) * a, Math.min(1f, mul[2]) * a, a);
    float[] uv = c.uv();
    for (int i = 0; i < n; i++) {
      float x = xy[i * 2];
      float y = xy[i * 2 + 1];
      int o = i * 5;
      verts[o] = m.m00 * x + m.m01 * y + m.m02;
      verts[o + 1] = m.m10 * x + m.m11 * y + m.m12;
      verts[o + 2] = packed;
      verts[o + 3] = uv[i * 2];
      verts[o + 4] = uv[i * 2 + 1];
    }
    int t = 0;
    for (int i = 0; i + 2 < n; i++) {
      tris[t++] = (short) i;
      tris[t++] = (short) (i + 1);
      tris[t++] = (short) (i + 2);
    }
    setBlend(blend);
    setAdd(add[0], add[1], add[2]);
    if (sweepPieces != null) {
      drawSwept(texture, packed, t);
      return;
    }
    batch.draw(texture, verts, 0, n * 5, tris, 0, t);
  }

  /**
   * Draws the triangles {@code tris[0..count)} of {@link #verts} clipped to the sweep's pieces
   * (Sutherland-Hodgman against each convex piece), positions and texture coordinates alike.
   */
  private void drawSwept(Texture texture, float packed, int count) {
    int out = 0;
    int outTris = 0;
    for (float[] piece : sweepPieces) {
      int corners = piece.length / 2;
      // the piece's winding, so "inside" is the same side of every edge
      float area = 0;
      for (int i = 0; i < corners; i++) {
        int j = (i + 1) % corners;
        area += piece[i * 2] * piece[j * 2 + 1] - piece[j * 2] * piece[i * 2 + 1];
      }
      float sign = Math.signum(area);
      for (int k = 0; k + 2 < count; k += 3) {
        int n = 0;
        for (int v = 0; v < 3; v++) {
          int o = tris[k + v] * 5;
          clipA[n * 4] = verts[o];
          clipA[n * 4 + 1] = verts[o + 1];
          clipA[n * 4 + 2] = verts[o + 3];
          clipA[n * 4 + 3] = verts[o + 4];
          n++;
        }
        float[] src = clipA;
        float[] dst = clipB;
        for (int e = 0; e < corners && n > 0; e++) {
          float ax = piece[e * 2];
          float ay = piece[e * 2 + 1];
          float bx = piece[((e + 1) % corners) * 2];
          float by = piece[((e + 1) % corners) * 2 + 1];
          int m = 0;
          for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float di = sign * ((bx - ax) * (src[i * 4 + 1] - ay) - (by - ay) * (src[i * 4] - ax));
            float dj = sign * ((bx - ax) * (src[j * 4 + 1] - ay) - (by - ay) * (src[j * 4] - ax));
            if (di >= 0 && m < 63) {
              System.arraycopy(src, i * 4, dst, m * 4, 4);
              m++;
            }
            if ((di >= 0) != (dj >= 0) && m < 63) {
              float f = di / (di - dj);
              for (int c = 0; c < 4; c++) {
                dst[m * 4 + c] = src[i * 4 + c] + (src[j * 4 + c] - src[i * 4 + c]) * f;
              }
              m++;
            }
          }
          float[] swap = src;
          src = dst;
          dst = swap;
          n = m;
        }
        if (n < 3 || out + n > MAX_VERTS || outTris + (n - 2) * 3 > sweptTris.length) continue;
        for (int i = 0; i < n; i++) {
          int o = (out + i) * 5;
          swept[o] = src[i * 4];
          swept[o + 1] = src[i * 4 + 1];
          swept[o + 2] = packed;
          swept[o + 3] = src[i * 4 + 2];
          swept[o + 4] = src[i * 4 + 3];
        }
        for (int i = 1; i + 1 < n; i++) {
          sweptTris[outTris++] = (short) out;
          sweptTris[outTris++] = (short) (out + i);
          sweptTris[outTris++] = (short) (out + i + 1);
        }
        out += n;
      }
    }
    if (outTris > 0) batch.draw(texture, swept, 0, out * 5, sweptTris, 0, outTris);
  }

  private void setAdd(float r, float g, float b) {
    if (shader == null) return;
    r = Math.min(1f, r);
    g = Math.min(1f, g);
    b = Math.min(1f, b);
    if (r == addR && g == addG && b == addB) return;
    batch.flush();
    addR = r;
    addG = g;
    addB = b;
    shader.setUniformf("u_add", r, g, b);
  }

  private void setBlend(int blend) {
    int mode =
        switch (blend) {
          case ScDrawer.BLEND_ADD, ScDrawer.BLEND_SCREEN, ScDrawer.BLEND_MULTIPLY -> blend;
          default -> ScDrawer.BLEND_NORMAL;
        };
    if (mode == currentBlend) return;
    currentBlend = mode;
    switch (mode) {
      case ScDrawer.BLEND_ADD -> batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE);
      case ScDrawer.BLEND_SCREEN ->
          batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_COLOR);
      case ScDrawer.BLEND_MULTIPLY ->
          batch.setBlendFunction(GL20.GL_DST_COLOR, GL20.GL_ONE_MINUS_SRC_ALPHA);
      default -> batch.setBlendFunction(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }
  }

  @Override
  public void dispose() {
    if (shader != null) shader.dispose();
    shader = null;
  }
}
