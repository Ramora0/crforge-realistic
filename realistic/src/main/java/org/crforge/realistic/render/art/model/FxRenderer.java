/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Draws a prefab's effect geometry (models whose material is a {@code vfx_*} shader: planes, rings,
 * bowls) into a 3D model's image after its lit parts, and puts glow materials ({@code uber_pbr}'s
 * {@code FRESNEL} / {@code RIMLIGHT}) on its geometries (docs/realistic-rendering.md §10.4). {@link
 * #extras} makes the {@link ModelRenderer.Extras} of one image.
 *
 * <p>Billboards ({@code enableBillboard}, DATA: {@code vfx_mask.vert}'s {@code BILLBOARD}
 * variants): the vertex is placed at the node's origin plus {@code B · L · p}, {@code L} the node's
 * turn and scale and {@code B} the camera basis the shader builds from the inverse view: x the
 * horizontal perpendicular to the view direction (screen right), y the horizontal towards the
 * camera, z the camera's up. That the shader's {@code u_model} is the node's own turn is INFERRED
 * (it makes the battle's glow planes, authored upright facing +y, face the camera).
 */
@Slf4j
public final class FxRenderer implements Disposable {

  private final FxLibrary library;
  private final Map<Path, Gpu> gpus = new HashMap<>();
  private final Map<String, Texture> textures = new HashMap<>();
  private ShaderProgram mask;
  private ShaderProgram fresnel;
  private Texture white;
  private boolean failed;

  public FxRenderer(FxLibrary library) {
    this.library = library;
  }

  public FxLibrary library() {
    return library;
  }

  private static final class Gpu {
    Glb glb;
    float[][] nodeOf;
    List<Mesh> meshes = new ArrayList<>();
    List<float[]> vertices = new ArrayList<>();
  }

  private boolean ready() {
    if (failed) return false;
    if (mask != null) return true;
    ShaderProgram.pedantic = false;
    mask = new ShaderProgram(FxShaders.VERTEX, FxShaders.MASK);
    fresnel = new ShaderProgram(FxShaders.VERTEX, FxShaders.FRESNEL);
    for (ShaderProgram p : List.of(mask, fresnel)) {
      if (!p.isCompiled()) {
        log.warn("3D effect shader: {}", p.getLog());
        failed = true;
        return false;
      }
    }
    Pixmap px = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
    px.setColor(0xFFFFFFFF);
    px.fill();
    white = new Texture(px);
    px.dispose();
    return true;
  }

  private Gpu gpu(Path file) {
    return gpus.computeIfAbsent(
        file,
        f -> {
          Gpu g = new Gpu();
          g.glb = library.library().glb(f);
          Rig rig = new Rig(g.glb);
          rig.pose(null, 0, null, 0, 1);
          g.nodeOf = new float[g.glb.primitives().size()][];
          for (int i = 0; i < g.glb.primitives().size(); i++) {
            Glb.Primitive p = g.glb.primitives().get(i);
            g.nodeOf[i] = rig.nodeMatrix(g.glb.primitiveNode(i));
            Mesh mesh =
                new Mesh(
                    false,
                    p.vertexCount(),
                    p.indices().length,
                    new VertexAttributes(
                        new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                        new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
                        new VertexAttribute(
                            VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0")));
            short[] idx = new short[p.indices().length];
            for (int k = 0; k < idx.length; k++) idx[k] = (short) p.indices()[k];
            mesh.setIndices(idx);
            g.meshes.add(mesh);
            g.vertices.add(new float[p.vertexCount() * 8]);
          }
          return g;
        });
  }

  private Texture texture(String rel) {
    if (rel == null) return white;
    return textures.computeIfAbsent(
        rel,
        r -> {
          Path p = library.file(r);
          if (p == null) return white;
          Texture t =
              new Texture(new Pixmap(new com.badlogic.gdx.files.FileHandle(p.toFile())), true);
          t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
          t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
          return t;
        });
  }

  /** One effect draw: an item's model in the image's space with its material. */
  public record Draw(Path glb, float[] matrix, FxMaterial material, int layer) {}

  /**
   * The extras of one model image: the effect draws (models placed in the image's prefab space,
   * after the model's heading turn), the glows on the model's geometries ({@code partGlows} by part
   * index, a part's own material asset; {@code ownerGlows} on every part's named geometries), the
   * effect clock {@code time} (UV scrolls) and the image's fade {@code alpha}.
   */
  public ModelRenderer.Extras extras(
      List<Draw> draws,
      Map<Integer, FxMaterial> partGlows,
      List<FxPrefab.Glow> ownerGlows,
      float time) {
    List<Draw> sorted = new ArrayList<>(draws);
    sorted.sort(
        Comparator.comparingInt((Draw d) -> d.layer()).thenComparingInt(d -> d.material().order()));
    return new ModelRenderer.Extras() {
      @Override
      public float[] shading(int part, String geometry) {
        FxMaterial m = partGlows.get(part);
        float[] own = m == null ? null : glow(m);
        for (FxPrefab.Glow g : ownerGlows) {
          if (g.geometries().isEmpty() || g.geometries().contains(geometry)) {
            float[] o = glow(g.material());
            if (o != null) own = own == null ? o : merge(own, o);
          }
        }
        return own;
      }

      @Override
      public void bounds(ModelRenderer.Instance in, float[] b) {
        for (Draw d : sorted) {
          for (float[] p : place(d)) {
            for (int v = 0; v + 7 < p.length; v += 8) {
              float wx = in.x + in.unitsPerMetre * ModelCamera.screenX(p[v], p[v + 1], p[v + 2]);
              float wy =
                  in.y + in.lift + in.unitsPerMetre * ModelCamera.screenY(p[v], p[v + 1], p[v + 2]);
              b[0] = Math.min(b[0], wx);
              b[1] = Math.min(b[1], wy);
              b[2] = Math.max(b[2], wx);
              b[3] = Math.max(b[3], wy);
            }
          }
        }
      }

      @Override
      public void draw(ModelRenderer.Instance in, Matrix4 mvp) {
        if (sorted.isEmpty() || !ready()) return;
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
        Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
        Gdx.gl.glDepthMask(false);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_ONE, GL20.GL_ONE_MINUS_SRC_ALPHA);
        for (Draw d : sorted) drawOne(d, mvp, time, in.colorMul[3]);
        Gdx.gl.glDepthMask(true);
        Gdx.gl.glDisable(GL20.GL_BLEND);
      }
    };
  }

  private static float[] merge(float[] a, float[] b) {
    float[] o = a.clone();
    if (b[3] >= 0) System.arraycopy(b, 0, o, 0, 5);
    if (b[8] != 0) System.arraycopy(b, 5, o, 5, 9);
    return o;
  }

  /**
   * The glow uniforms of an {@code uber_pbr} material ({@link UberPbr#setGlow}), or null when it
   * enables neither term.
   */
  static float[] glow(FxMaterial m) {
    if (m == null || !"uber_pbr".equals(m.shader())) return null;
    boolean fr = m.flag("enableFresnel");
    boolean rl = m.flag("enableRimLight");
    if (!fr && !rl) return null;
    float[] o = new float[14];
    o[3] = -1f;
    if (fr) {
      float[] c = m.param("fresnelColor", 0, 0, 0, 0);
      float mul = m.param("fr_colorMul", 1f)[0];
      o[0] = c[0] * mul;
      o[1] = c[1] * mul;
      o[2] = c[2] * mul;
      o[3] = Math.max(0f, m.param("fr_weight", 0f)[0]);
      o[4] = m.param("fr_IOR", 1f)[0];
    }
    if (rl) {
      float[] c = m.param("rimLightColor", 0, 0, 0, 0);
      float mul = m.param("rl_colorMul", 1f)[0];
      o[5] = c[0] * mul;
      o[6] = c[1] * mul;
      o[7] = c[2] * mul;
      o[8] = m.param("rl_weight", 0f)[0];
      o[9] = m.param("rl_pow", 1f)[0];
      o[10] = m.param("rl_lowTolerance", 0f)[0];
      float[] d = viewToWorld(m.param("rimLightDir", 0, 0, 0));
      o[11] = d[0];
      o[12] = d[1];
      o[13] = d[2];
    }
    return o;
  }

  /** A view-space direction in prefab space (the inverse view's turn: right, up, back). */
  static float[] viewToWorld(float[] d) {
    float[] right = {-1f, 0f, 0f};
    float[] up = {0f, -ModelCamera.COS, ModelCamera.SIN};
    float[] back = {0f, ModelCamera.SIN, ModelCamera.COS};
    float[] o = new float[3];
    for (int i = 0; i < 3; i++) o[i] = right[i] * d[0] + up[i] * d[1] + back[i] * d[2];
    return o;
  }

  /** The billboard basis {x, y, z} columns (see the class comment). */
  static final float[][] BILLBOARD = {
    {-1f, 0f, 0f}, {0f, 1f, 0f}, {0f, -ModelCamera.COS, ModelCamera.SIN}
  };

  /** The draw's vertices placed: per primitive, {x, y, z, nx, ny, nz, u, v} each. */
  private List<float[]> place(Draw d) {
    Gpu g = gpu(d.glb());
    boolean billboard = d.material().flag("enableBillboard");
    float[] m = d.matrix();
    List<float[]> out = new ArrayList<>();
    for (int i = 0; i < g.glb.primitives().size(); i++) {
      Glb.Primitive p = g.glb.primitives().get(i);
      float[] nm = g.nodeOf[i];
      float[] full = FxPrefab.mul(m, nm);
      float[] v = g.vertices.get(i);
      float[] uv = p.uvs();
      for (int k = 0; k < p.vertexCount(); k++) {
        float[] q = new float[3];
        float[] n = new float[3];
        if (billboard) {
          float[] local = new float[3];
          // L · N · p, without the node's translation
          float[] lin = full.clone();
          lin[12] = lin[13] = lin[14] = 0f;
          Rig.transformPoint(lin, p.positions(), k * 3, local, 0);
          for (int a = 0; a < 3; a++) {
            q[a] =
                full[12 + a]
                    + BILLBOARD[0][a] * local[0]
                    + BILLBOARD[1][a] * local[1]
                    + BILLBOARD[2][a] * local[2];
          }
          if (p.normals() != null) {
            Rig.transformDirection(lin, p.normals(), k * 3, local, 0);
            for (int a = 0; a < 3; a++) {
              n[a] =
                  BILLBOARD[0][a] * local[0]
                      + BILLBOARD[1][a] * local[1]
                      + BILLBOARD[2][a] * local[2];
            }
          }
        } else {
          Rig.transformPoint(full, p.positions(), k * 3, q, 0);
          if (p.normals() != null) Rig.transformDirection(full, p.normals(), k * 3, n, 0);
        }
        int o = k * 8;
        v[o] = q[0];
        v[o + 1] = q[1];
        v[o + 2] = q[2];
        v[o + 3] = n[0];
        v[o + 4] = n[1];
        v[o + 5] = n[2];
        v[o + 6] = uv == null ? 0 : uv[k * 2];
        v[o + 7] = uv == null ? 0 : uv[k * 2 + 1];
      }
      out.add(v);
    }
    return out;
  }

  private void drawOne(Draw d, Matrix4 mvp, float time, float alpha) {
    FxMaterial m = d.material();
    Gpu g = gpu(d.glb());
    List<float[]> placed = place(d);
    boolean isFresnel = "vfx_fresnel_simple".equals(m.shader());
    ShaderProgram p = isFresnel ? fresnel : mask;
    p.bind();
    p.setUniformMatrix("u_mvp", mvp);
    p.setUniformf("u_additive", m.additive() ? 1f : 0f);
    p.setUniformf("u_alphaMul", alpha);
    if (isFresnel) {
      set4(p, "mainColor", m.param("mainColor", 0, 0, 0, 0));
      set4(p, "fnl_color", m.param("fnl_color", 0, 0, 0, 0));
      p.setUniformf("fnl_power", m.param("fnl_power", 1f)[0]);
      p.setUniformf("fnl_int", m.param("fnl_int", 1f)[0]);
      float[] cam = ModelCamera.cameraPosition();
      p.setUniformf("u_cameraPosition", cam[0], cam[1], cam[2], 1f);
    } else {
      boolean mask2 = "vfx_mask2".equals(m.shader());
      String[] tex = {"mainTex", "maskTex", "maskTex2", "distortionTex", "dissolveTex"};
      for (int k = tex.length - 1; k >= 0; k--) {
        texture(m.textures().get(tex[k])).bind(k);
        p.setUniformi(tex[k], k);
      }
      Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
      for (String v :
          new String[] {
            "mainTex_UV",
            "maskTex_UV",
            "mask_speedxy_pow_mul",
            "mask_rgba_selector",
            "distortionTex_UV",
            "dist_speedxy_wmain_wmask",
            "dist_rgba_selector",
            "dissolveTex_UV",
            "disv_rgba_selector",
            "mainColor"
          }) {
        set4(p, v, m.param(v, v.endsWith("_UV") ? new float[] {1, 1, 0, 0} : new float[4]));
      }
      float[] ds = m.param("disv_speedxy", 0, 0);
      float[] ms = m.param("main_speedxy", 0, 0);
      p.setUniformf("disv_speedxy", ds[0], ds.length > 1 ? ds[1] : 0);
      p.setUniformf("main_speedxy", ms[0], ms.length > 1 ? ms[1] : 0);
      p.setUniformf("disv_amount", m.param("disv_amount", 0f)[0]);
      p.setUniformf("disv_edge_hardness", m.param("disv_edge_hardness", 0f)[0]);
      p.setUniformf("u_time", time);
      p.setUniformf(
          "u_defines",
          m.textures().containsKey("distortionTex") ? 1f : 0f,
          m.flag("enableDissolve") ? 1f : 0f,
          m.flag("color_as_alpha") ? 1f : 0f,
          mask2 && m.flag("enableMask2Dissolve") ? 1f : 0f);
      p.setUniformf("u_mask2", mask2 ? 1f : 0f);
    }
    for (int i = 0; i < g.meshes.size(); i++) {
      g.meshes.get(i).setVertices(placed.get(i));
      g.meshes.get(i).render(p, GL20.GL_TRIANGLES);
    }
  }

  private static void set4(ShaderProgram p, String name, float[] v) {
    p.setUniformf(
        name,
        v.length > 0 ? v[0] : 0,
        v.length > 1 ? v[1] : 0,
        v.length > 2 ? v[2] : 0,
        v.length > 3 ? v[3] : 0);
  }

  @Override
  public void dispose() {
    for (Gpu g : gpus.values()) for (Mesh m : g.meshes) m.dispose();
    gpus.clear();
    for (Texture t : textures.values()) if (t != white) t.dispose();
    textures.clear();
    for (Disposable d : new Disposable[] {mask, fresnel, white}) if (d != null) d.dispose();
  }
}
