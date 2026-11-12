/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * The renderer's batch, recording what it draws: each flush (one GL draw call of the server's
 * renderer) becomes polygons of a {@link DrawFrame}, with the texture, blending, shader and shader
 * uniforms they were drawn with, positions mapped through the batch's matrices to frame pixels.
 * Everything the realistic view, its effects, HUD and fonts draw goes through this one batch.
 *
 * <p>While recording it draws too ({@code rasterize}, for the JPEG stream and the parity check) or
 * only records. Outside a frame it is an ordinary batch.
 */
@Slf4j
final class DrawRecorder extends PolygonSpriteBatch {

  private static final Field VERTEX_INDEX = field("vertexIndex");
  private static final Field TRIANGLE_INDEX = field("triangleIndex");
  private static final Field LAST_TEXTURE = field("lastTexture");
  private static final Field VERTICES = field("vertices");
  private static final Field TRIANGLES = field("triangles");

  private final DrawDefs defs;
  private final TextureStore textures;
  private final float[] vertices;
  private final short[] triangles;
  private final Map<ShaderProgram, Integer> shaderKinds = new IdentityHashMap<>();
  private final FloatBuffer uniform = BufferUtils.newFloatBuffer(16);
  private final Matrix4 combined = new Matrix4();
  private int width;
  private int height;
  private DrawFrame frame;
  private boolean rasterize = true;
  private boolean warnedShape;

  DrawRecorder(DrawDefs defs) {
    super(8000);
    this.defs = defs;
    this.textures = defs.textures();
    try {
      this.vertices = (float[]) VERTICES.get(this);
      this.triangles = (short[]) TRIANGLES.get(this);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Field field(String name) {
    try {
      Field f = PolygonSpriteBatch.class.getDeclaredField(name);
      f.setAccessible(true);
      return f;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("PolygonSpriteBatch." + name + " is not there", e);
    }
  }

  /**
   * Starts recording a frame of {@code width} x {@code height} pixels into {@code into} (cleared);
   * with {@code rasterize} the draws are made as well.
   */
  void startFrame(DrawFrame into, int width, int height, boolean rasterize) {
    into.clear();
    this.frame = into;
    this.width = width;
    this.height = height;
    this.rasterize = rasterize;
  }

  /** Stops recording (what is still batched is recorded first). */
  void endFrame() {
    if (isDrawing()) flush();
    frame = null;
    rasterize = true;
  }

  boolean recording() {
    return frame != null;
  }

  boolean rasterizing() {
    return frame == null || rasterize;
  }

  @Override
  public void flush() {
    if (frame == null) {
      super.flush();
      return;
    }
    try {
      int vi = VERTEX_INDEX.getInt(this);
      if (vi == 0) return;
      int ti = TRIANGLE_INDEX.getInt(this);
      Texture texture = (Texture) LAST_TEXTURE.get(this);
      record(texture, vi / 5, ti);
      if (rasterize) {
        super.flush();
      } else {
        VERTEX_INDEX.setInt(this, 0);
        TRIANGLE_INDEX.setInt(this, 0);
      }
    } catch (IllegalAccessException e) {
      throw new IllegalStateException(e);
    }
  }

  private void record(Texture texture, int vertexCount, int indexCount) {
    frame.flushes++;
    int tex = textures.source(texture);
    int state = currentState();
    combined.set(getProjectionMatrix()).mul(getTransformMatrix());
    int t = 0;
    int next = 0;
    while (t < indexCount) {
      int base = triangles[t] & 0xFFFF;
      if (base != next) {
        // not a polygon of consecutive vertices: the rest as one list of triangles
        polygonOfTriangles(tex, state, t, indexCount, next, vertexCount);
        return;
      }
      if (t + 5 < indexCount
          && (triangles[t + 1] & 0xFFFF) == base + 1
          && (triangles[t + 2] & 0xFFFF) == base + 2
          && (triangles[t + 3] & 0xFFFF) == base + 2
          && (triangles[t + 4] & 0xFFFF) == base + 3
          && (triangles[t + 5] & 0xFFFF) == base) {
        polygon(tex, state, DrawDefs.QUAD, base, 4, null);
        t += 6;
        next = base + 4;
        continue;
      }
      int k = 0;
      while (t + 3 * k + 2 < indexCount
          && (triangles[t + 3 * k] & 0xFFFF) == base + k
          && (triangles[t + 3 * k + 1] & 0xFFFF) == base + k + 1
          && (triangles[t + 3 * k + 2] & 0xFFFF) == base + k + 2) {
        k++;
      }
      if (k == 0) {
        polygonOfTriangles(tex, state, t, indexCount, next, vertexCount);
        return;
      }
      polygon(tex, state, DrawDefs.STRIP, base, k + 2, null);
      t += 3 * k;
      next = base + k + 2;
    }
  }

  /** The remaining triangles (from index {@code t}) as one polygon of their vertices. */
  private void polygonOfTriangles(
      int tex, int state, int t, int indexCount, int first, int vertexCount) {
    int lo = Integer.MAX_VALUE;
    for (int i = t; i < indexCount; i++) lo = Math.min(lo, triangles[i] & 0xFFFF);
    lo = Math.min(lo, first);
    int n = vertexCount - lo;
    short[] local = new short[indexCount - t];
    for (int i = t; i < indexCount; i++) local[i - t] = (short) ((triangles[i] & 0xFFFF) - lo);
    if (!warnedShape) {
      warnedShape = true;
      log.debug(
          "A draw of {} triangles over {} vertices recorded as a triangle list",
          local.length / 3,
          n);
    }
    polygon(tex, state, DrawDefs.TRIANGLES, lo, n, local);
  }

  private void polygon(int tex, int state, int kind, int base, int n, short[] indices) {
    frame.seen++;
    int o = base * 5;
    int region = defs.region(tex, kind, n, vertices, o + 3, 5, indices);
    int c0 = Float.floatToRawIntBits(vertices[o + 2]);
    boolean uniform = true;
    for (int i = 1; i < n && uniform; i++) {
      uniform = Float.floatToRawIntBits(vertices[o + i * 5 + 2]) == c0;
    }
    frame.begin(state, region, n, uniform);
    if (uniform) frame.color(c0);
    else for (int i = 0; i < n; i++) frame.color(Float.floatToRawIntBits(vertices[o + i * 5 + 2]));
    float[] m = combined.val;
    float sx = width * 0.5f * DrawFrame.SUBPIXELS;
    float sy = height * 0.5f * DrawFrame.SUBPIXELS;
    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
    for (int i = 0; i < n; i++) {
      float x = vertices[o + i * 5];
      float y = vertices[o + i * 5 + 1];
      float nx = m[Matrix4.M00] * x + m[Matrix4.M01] * y + m[Matrix4.M03];
      float ny = m[Matrix4.M10] * x + m[Matrix4.M11] * y + m[Matrix4.M13];
      int px = Math.round((nx + 1f) * sx);
      int py = Math.round((ny + 1f) * sy);
      frame.point(px, py);
      minX = Math.min(minX, px);
      maxX = Math.max(maxX, px);
      minY = Math.min(minY, py);
      maxY = Math.max(maxY, py);
    }
    int w = width * DrawFrame.SUBPIXELS;
    int h = height * DrawFrame.SUBPIXELS;
    if (maxX <= 0 || maxY <= 0 || minX >= w || minY >= h) frame.dropLast();
  }

  /**
   * Flat triangles (x, y pairs in the {@code projection}'s world, three a triangle) in a straight
   * alpha colour, blended as ShapeRenderer's are: the placement overlay.
   */
  void shapeTriangles(
      float[] xy, int count, float r, float g, float b, float a, Matrix4 projection) {
    if (frame == null) return;
    frame.flushes++;
    int state =
        defs.state(
            true,
            GL20.GL_SRC_ALPHA,
            GL20.GL_ONE_MINUS_SRC_ALPHA,
            GL20.GL_SRC_ALPHA,
            GL20.GL_ONE_MINUS_SRC_ALPHA,
            DrawDefs.SHADER_PLAIN,
            0,
            0,
            0,
            0);
    float[] uv = new float[6];
    int region = defs.region(-1, DrawDefs.STRIP, 3, uv, 0, 2, null);
    int color = Float.floatToRawIntBits(com.badlogic.gdx.graphics.Color.toFloatBits(r, g, b, a));
    float[] m = projection.val;
    float sx = width * 0.5f * DrawFrame.SUBPIXELS;
    float sy = height * 0.5f * DrawFrame.SUBPIXELS;
    for (int t = 0; t + 5 < count; t += 6) {
      frame.seen++;
      frame.begin(state, region, 3, true);
      frame.color(color);
      for (int i = 0; i < 3; i++) {
        float x = xy[t + 2 * i];
        float y = xy[t + 2 * i + 1];
        float nx = m[Matrix4.M00] * x + m[Matrix4.M01] * y + m[Matrix4.M03];
        float ny = m[Matrix4.M10] * x + m[Matrix4.M11] * y + m[Matrix4.M13];
        frame.point(Math.round((nx + 1f) * sx), Math.round((ny + 1f) * sy));
      }
    }
  }

  /** The state the batch draws in now: its blending, shader kind and the shader's uniforms. */
  private int currentState() {
    ShaderProgram shader = getShader();
    int kind = shaderKinds.computeIfAbsent(shader, DrawRecorder::kindOf);
    float ar = 0, ag = 0, ab = 0, gray = 0;
    if (kind == DrawDefs.SHADER_ADD || kind == DrawDefs.SHADER_HUD) {
      int loc = shader.fetchUniformLocation("u_add", false);
      if (loc >= 0) {
        uniform.clear();
        Gdx.gl.glGetUniformfv(shader.getHandle(), loc, uniform);
        ar = uniform.get(0);
        ag = uniform.get(1);
        ab = uniform.get(2);
      }
    }
    if (kind == DrawDefs.SHADER_HUD) {
      int loc = shader.fetchUniformLocation("u_gray", false);
      if (loc >= 0) {
        uniform.clear();
        Gdx.gl.glGetUniformfv(shader.getHandle(), loc, uniform);
        gray = uniform.get(0);
      }
    }
    return defs.state(
        isBlendingEnabled(),
        getBlendSrcFunc(),
        getBlendDstFunc(),
        getBlendSrcFuncAlpha(),
        getBlendDstFuncAlpha(),
        kind,
        ar,
        ag,
        ab,
        gray);
  }

  /** Which of the renderer's shaders this is, by its fragment program. */
  static int kindOf(ShaderProgram shader) {
    String f = shader.getFragmentShaderSource();
    if (f.contains("u_gray")) return DrawDefs.SHADER_HUD;
    if (f.contains("u_add")) return DrawDefs.SHADER_ADD;
    if (f.replace(" ", "").contains("texture2D(u_texture,v_texCoords).a")) {
      return DrawDefs.SHADER_SILHOUETTE;
    }
    return DrawDefs.SHADER_PLAIN;
  }
}
