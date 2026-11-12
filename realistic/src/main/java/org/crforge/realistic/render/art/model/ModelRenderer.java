/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Mesh;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttribute;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.Disposable;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Draws posed 3D models into the 2D arena as the game composites its 3D actors: each model is
 * rendered on its own (with a depth buffer, at twice the screen's resolution) through the battle
 * camera ({@link ModelCamera}) with the ported {@code uber_pbr} shader ({@link UberPbr}), and the
 * image is drawn by the 2D batch at the actor's place in the y-sorted draw order. Its shadow is the
 * planar shadow mask, rendered at half that resolution ({@code client_globals
 * VANILLA_3D_PPM_OF_SHADOW} 50 against {@code VANILLA_3D_PPM} 100), blurred and tinted by the
 * arena's shadow colour.
 */
@Slf4j
public final class ModelRenderer implements Disposable {

  /** Render scale over screen pixels (a 2x2 box filter when the image is drawn). */
  static final int SUPERSAMPLE = 2;

  private static final int MAX_SIZE = 2048;

  /** Draws an image through the 2D batch at its place in the draw order. */
  public interface Compositor {
    /**
     * Draws {@code texture}'s region {@code (u, v)..(u2, v2)} over the world rectangle at {@code
     * (x, y)} of size {@code w x h}, premultiplied colour {@code tint}, with {@code shader} (null
     * for the plain image) and its {@code u_offset} uniform.
     */
    void image(
        Texture texture,
        float x,
        float y,
        float w,
        float h,
        float u,
        float v,
        float u2,
        float v2,
        Color tint,
        ShaderProgram shader,
        float offsetU,
        float offsetV);

    /** Ends the batch's drawing (before the GL state changes). */
    void pause();

    /** Starts it again. */
    void resume();
  }

  /**
   * What a model's image carries besides its lit parts (docs/realistic-rendering.md §10.4): the
   * {@code FRESNEL} / {@code RIMLIGHT} terms of a part's geometry (a glow material on it), more
   * room in the image, and draws after the lit parts into the same image and depth (3D effects).
   */
  public interface Extras {
    /**
     * The glow uniforms of part {@code part}'s geometry {@code geometry} (see UberPbr), or null.
     */
    float[] shading(int part, String geometry);

    /** Grows the world bounds {x0, y0, x1, y1} of the image to what the extras draw. */
    void bounds(Instance in, float[] b);

    /** Draws into the bound image (depth test on), {@code mvp} from prefab to clip space. */
    void draw(Instance in, Matrix4 mvp);
  }

  /**
   * One model of a prefab posed: its clip sample; a mounted one ({@link Sc3dLibrary.Mount}) also
   * names the earlier part it rides ({@code host}, -1 for none) and that part's bone it sits at.
   */
  public record Part(Sc3dLibrary.ModelRef ref, AnimPlayer.Sample sample, int host, String bone) {

    public Part(Sc3dLibrary.ModelRef ref, AnimPlayer.Sample sample) {
      this(ref, sample, -1, null);
    }
  }

  /**
   * A prefab posed at a place: its parts, its ground point and lift (world units), world units per
   * prefab metre, its turn about z, its side (drawn blue or red) and the object colour transform.
   */
  public static final class Instance {
    final Sc3dLibrary.Prefab prefab;
    final List<Part> parts;
    final float x;
    final float y;
    final float lift;
    final float unitsPerMetre;
    final float heading;
    final boolean blue;
    final float[] colorMul;
    final float[] colorAdd;

    /**
     * The lift (world units up the screen) its shadow is drawn at; NaN for the model's own. A flier
     * casts its shadow on the ground below it, not at its body (INFERRED, as the 2D shadows: §8).
     */
    public float shadowLift = Float.NaN;

    float[][] posed;
    float[][] normals;

    /**
     * A 2D filter's scale of the drawn image {x, y} about the ground point (a deploy filter's
     * squash and stretch, as the stage would scale the actor's image), or null.
     */
    public float[] imageScale;

    float[][] tangents;

    /** The image's extras (glows, 3D effects), or null. */
    public Extras extras;

    public List<Part> parts() {
      return parts;
    }

    /** The turn about z (radians) the parts are drawn with. */
    public float heading() {
      return heading;
    }

    public Instance(
        Sc3dLibrary.Prefab prefab,
        List<Part> parts,
        float x,
        float y,
        float lift,
        float unitsPerMetre,
        float heading,
        boolean blue,
        float[] colorMul,
        float[] colorAdd) {
      this.prefab = prefab;
      this.parts = parts;
      this.x = x;
      this.y = y;
      this.lift = lift;
      this.unitsPerMetre = unitsPerMetre;
      this.heading = heading;
      this.blue = blue;
      this.colorMul = colorMul;
      this.colorAdd = colorAdd;
    }
  }

  private final Sc3dLibrary library;
  private final Map<Path, Gpu> gpus = new HashMap<>();
  private final Map<Path, Texture> sideTextures = new HashMap<>();
  private ShaderProgram lit;
  private ShaderProgram shadow;
  private ShaderProgram composite;
  private FrameBuffer image;
  private FrameBuffer shadowImage;
  private Texture white;
  private Texture black;
  private boolean failed;
  private final Matrix4 mvp = new Matrix4();
  private final IntBuffer ints = BufferUtils.newIntBuffer(16);

  public ModelRenderer(Sc3dLibrary library) {
    this.library = library;
  }

  public Sc3dLibrary library() {
    return library;
  }

  /** A model's GPU side: its rig, mesh per primitive and textures per material. */
  private static final class Gpu {
    Rig rig;
    List<Mesh> meshes = new ArrayList<>();
    List<float[]> vertices = new ArrayList<>();
    List<Texture[]> textures = new ArrayList<>();
    List<float[]> params = new ArrayList<>();
  }

  private boolean ready() {
    if (failed) return false;
    if (lit != null) return true;
    ShaderProgram.pedantic = false;
    lit = new ShaderProgram(UberPbr.VERTEX, UberPbr.fragment(true));
    if (!lit.isCompiled()) {
      log.info("uber_pbr without textureLod: {}", lit.getLog());
      lit.dispose();
      lit = new ShaderProgram(UberPbr.VERTEX, UberPbr.fragment(false));
    }
    shadow = new ShaderProgram(UberPbr.SHADOW_VERTEX, UberPbr.SHADOW_FRAGMENT);
    composite = new ShaderProgram(UberPbr.COMPOSITE_VERTEX, UberPbr.COMPOSITE_FRAGMENT);
    for (ShaderProgram p : List.of(lit, shadow, composite)) {
      if (!p.isCompiled()) {
        log.warn("3D model shader: {}", p.getLog());
        failed = true;
        return false;
      }
    }
    white = solid(0xFFFFFFFF);
    black = solid(0x000000FF);
    return true;
  }

  private static Texture solid(int rgba) {
    Pixmap p = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
    p.setColor(rgba);
    p.fill();
    Texture t = new Texture(p);
    p.dispose();
    return t;
  }

  private Gpu gpu(Path file) {
    return gpus.computeIfAbsent(
        file,
        f -> {
          Glb glb = library.glb(f);
          Gpu g = new Gpu();
          g.rig = new Rig(glb);
          for (Glb.Primitive p : glb.primitives()) {
            int n = p.vertexCount();
            Mesh mesh =
                new Mesh(
                    false,
                    n,
                    p.indices().length,
                    new VertexAttributes(
                        new VertexAttribute(VertexAttributes.Usage.Position, 3, "a_position"),
                        new VertexAttribute(VertexAttributes.Usage.Normal, 3, "a_normal"),
                        new VertexAttribute(VertexAttributes.Usage.Tangent, 4, "a_tangent"),
                        new VertexAttribute(
                            VertexAttributes.Usage.TextureCoordinates, 2, "a_texCoord0")));
            short[] idx = new short[p.indices().length];
            for (int i = 0; i < idx.length; i++) idx[i] = (short) p.indices()[i];
            mesh.setIndices(idx);
            g.meshes.add(mesh);
            g.vertices.add(new float[n * 12]);
            Glb.Material m = p.material() >= 0 ? glb.materials().get(p.material()) : null;
            g.textures.add(textures(glb, m));
            g.params.add(params(m));
          }
          return g;
        });
  }

  private Texture[] textures(Glb glb, Glb.Material m) {
    Texture diffuse = m == null ? white : image(glb, m.baseColor(), white);
    Texture orm = m == null ? white : image(glb, m.orm(), white);
    Texture normal = m == null ? null : image(glb, m.normal(), null);
    if (normal == null) normal = solid(0x8080FFFF);
    Texture team =
        m == null
            ? black
            : library.sideTexture(m.extras(), "teamColorTex").map(this::side).orElse(black);
    Texture refl =
        m == null
            ? black
            : library.sideTexture(m.extras(), "reflectioinTex").map(this::side).orElse(black);
    return new Texture[] {diffuse, orm, normal, team, refl};
  }

  /** {metalness, roughness, emissiveStrength} of the material's game parameters. */
  private static float[] params(Glb.Material m) {
    if (m == null) return new float[] {0f, 1f, 0f};
    var f = m.extras().path("variables").path("floats");
    return new float[] {
      (float) f.path("metalness").asDouble(0),
      (float) f.path("roughness").asDouble(1),
      (float) f.path("emissiveStrength").asDouble(1)
    };
  }

  private static Texture image(Glb glb, int index, Texture fallback) {
    if (index < 0 || index >= glb.images().size()) return fallback;
    byte[] png = glb.images().get(index);
    Pixmap p = new Pixmap(png, 0, png.length);
    return texture(p);
  }

  private Texture side(Path file) {
    return sideTextures.computeIfAbsent(
        file, f -> texture(new Pixmap(new com.badlogic.gdx.files.FileHandle(f.toFile()))));
  }

  private static Texture texture(Pixmap p) {
    Texture t = new Texture(p, true);
    t.setFilter(Texture.TextureFilter.MipMapLinearLinear, Texture.TextureFilter.Linear);
    t.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
    p.dispose();
    return t;
  }

  // ------------------------------------------------------------------------------------- posing

  /** Poses every part and places its vertices in the prefab root's space, turned by the heading. */
  private void pose(Instance in) {
    if (in.posed != null) return;
    List<float[]> pos = new ArrayList<>();
    List<float[]> nrm = new ArrayList<>();
    List<float[]> tan = new ArrayList<>();
    float c = (float) Math.cos(in.heading);
    float s = (float) Math.sin(in.heading);
    float[] turn = {c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    // a mounted part's base: its host's placement times the host bone's posed matrix
    float[][] mountBase = new float[in.parts.size()][];
    for (int pi = 0; pi < in.parts.size(); pi++) {
      Part part = in.parts.get(pi);
      Gpu g = gpu(part.ref().glb());
      Glb glb = g.rig.glb();
      AnimPlayer.Sample sm = part.sample();
      Glb.Clip clip = sm == null || sm.clip() == null ? null : glb.clips().get(sm.clip());
      Glb.Clip from = sm == null || sm.fromClip() == null ? null : glb.clips().get(sm.fromClip());
      g.rig.pose(
          clip,
          sm == null ? 0 : sm.seconds(),
          from,
          sm == null ? 0 : sm.fromSeconds(),
          sm == null ? 1 : sm.blend());
      float[] m = new float[16];
      Rig.mul(mountBase[pi] != null ? mountBase[pi] : turn, 0, part.ref().transform(), 0, m);
      for (int pj = pi + 1; pj < in.parts.size(); pj++) {
        Part rider = in.parts.get(pj);
        if (rider.host() != pi) continue;
        int bone = nodeNamed(glb, rider.bone());
        if (bone < 0) continue;
        mountBase[pj] = new float[16];
        Rig.mul(m, 0, g.rig.nodeMatrix(bone), 0, mountBase[pj]);
      }
      for (int i = 0; i < glb.primitives().size(); i++) {
        int n = glb.primitives().get(i).vertexCount();
        float[] p = new float[n * 3];
        float[] nn = new float[n * 3];
        float[] tt = new float[n * 4];
        g.rig.skin(i, p, nn, tt);
        for (int v = 0; v < n; v++) {
          Rig.transformPoint(m, p, v * 3, p, v * 3);
          Rig.transformDirection(m, nn, v * 3, nn, v * 3);
          float[] t3 = new float[3];
          Rig.transformDirection(m, tt, v * 4, t3, 0);
          tt[v * 4] = t3[0];
          tt[v * 4 + 1] = t3[1];
          tt[v * 4 + 2] = t3[2];
        }
        pos.add(p);
        nrm.add(nn);
        tan.add(tt);
      }
    }
    in.posed = pos.toArray(new float[0][]);
    in.normals = nrm.toArray(new float[0][]);
    in.tangents = tan.toArray(new float[0][]);
  }

  /** Whether the primitive's mesh node is drawn with an effect material (left out). */
  private static boolean effectPrimitive(Gpu g, int primitive, Set<String> effects) {
    if (effects.isEmpty()) return false;
    Glb glb = g.rig.glb();
    int node = glb.primitiveNode(primitive);
    return node >= 0 && effects.contains(glb.nodes().get(node).name());
  }

  /** The index of the model's node of that name, or -1. */
  static int nodeNamed(Glb glb, String name) {
    if (name == null) return -1;
    List<Glb.Node> nodes = glb.nodes();
    for (int i = 0; i < nodes.size(); i++) if (name.equals(nodes.get(i).name())) return i;
    return -1;
  }

  /** The world bounds {x0, y0, x1, y1} of the posed model (or its shadow) on screen. */
  static float[] bounds(Instance in, float[][] posed, float[] towardsLight) {
    return bounds(in, posed, towardsLight, in.lift);
  }

  /** The bounds as drawn {@code lift} world units up the screen from the ground point. */
  static float[] bounds(Instance in, float[][] posed, float[] towardsLight, float lift) {
    float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
    float k = in.unitsPerMetre;
    for (float[] p : posed) {
      for (int v = 0; v + 2 < p.length; v += 3) {
        float x = p[v], y = p[v + 1], z = p[v + 2];
        if (towardsLight != null) {
          if (Math.abs(towardsLight[2]) < 1e-3f) continue;
          x -= towardsLight[0] * z / towardsLight[2];
          y -= towardsLight[1] * z / towardsLight[2];
          z = 0;
        }
        float wx = in.x + k * ModelCamera.screenX(x, y, z);
        float wy = in.y + lift + k * ModelCamera.screenY(x, y, z);
        x0 = Math.min(x0, wx);
        x1 = Math.max(x1, wx);
        y0 = Math.min(y0, wy);
        y1 = Math.max(y1, wy);
      }
    }
    return new float[] {x0, y0, x1, y1};
  }

  /** prefab -> clip space of an image covering the world rectangle {@code b}. */
  private void mvp(Instance in, float[] b) {
    mvp(in, b, in.lift);
  }

  /** prefab -> clip space with the prefab drawn {@code lift} world units up the screen. */
  private void mvp(Instance in, float[] b, float lift) {
    float k = in.unitsPerMetre;
    float bw = b[2] - b[0];
    float bh = b[3] - b[1];
    float zr = k * 40f;
    float[] v = mvp.val;
    java.util.Arrays.fill(v, 0f);
    // nx = 2 (x + k * -X - x0) / bw - 1
    v[Matrix4.M00] = -2f * k / bw;
    v[Matrix4.M03] = 2f * (in.x - b[0]) / bw - 1f;
    // ny = 2 (y + lift + k (-Y cos + Z sin) - y0) / bh - 1
    v[Matrix4.M11] = -2f * k * ModelCamera.COS / bh;
    v[Matrix4.M12] = 2f * k * ModelCamera.SIN / bh;
    v[Matrix4.M13] = 2f * (in.y + lift - b[1]) / bh - 1f;
    // nz: nearer the camera is smaller
    v[Matrix4.M21] = -k * ModelCamera.SIN / zr;
    v[Matrix4.M22] = -k * ModelCamera.COS / zr;
    v[Matrix4.M33] = 1f;
  }

  // -------------------------------------------------------------------------------------- draw

  /** Screen pixels per world unit of the batch's projection in the bound viewport. */
  private float pixelsPerUnit(Matrix4 projection) {
    ints.clear();
    Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, ints);
    return Math.abs(projection.val[Matrix4.M00]) * ints.get(2) / 2f;
  }

  /** Draws the posed prefab's image through the compositor. */
  public void draw(Instance in, Matrix4 projection, Compositor out) {
    if (in.parts.isEmpty() || !ready()) return;
    pose(in);
    float[] b = bounds(in, in.posed, null);
    if (in.extras != null) in.extras.bounds(in, b);
    if (!(b[2] > b[0]) || !(b[3] > b[1])) return;
    float ppu = pixelsPerUnit(projection) * SUPERSAMPLE;
    float pad = 2f / ppu * SUPERSAMPLE;
    b[0] -= pad;
    b[1] -= pad;
    b[2] += pad;
    b[3] += pad;
    // an image larger than the cap (a wide effect) is rendered coarser rather than cut
    float most = Math.max(b[2] - b[0], b[3] - b[1]) * ppu;
    if (most > MAX_SIZE) ppu *= (MAX_SIZE - 1) / most;
    int w = Math.min(MAX_SIZE, (int) Math.ceil((b[2] - b[0]) * ppu));
    int h = Math.min(MAX_SIZE, (int) Math.ceil((b[3] - b[1]) * ppu));
    // the image's world size is its pixel size over the scale (whole pixels)
    b[2] = b[0] + w / ppu;
    b[3] = b[1] + h / ppu;
    out.pause();
    int[] saved = binding();
    image = target(image, w, h, true);
    bind(image, w, h);
    Gdx.gl.glClearColor(0, 0, 0, 0);
    Gdx.gl.glClearDepthf(1f);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
    Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);
    Gdx.gl.glDepthFunc(GL20.GL_LEQUAL);
    Gdx.gl.glDepthMask(true);
    Gdx.gl.glDisable(GL20.GL_BLEND);
    Gdx.gl.glDisable(GL20.GL_CULL_FACE);
    mvp(in, b);
    lit.bind();
    lit.setUniformMatrix("u_mvp", mvp);
    setScene(lit, in);
    Sc3dLibrary.Environment env = library.environment();
    float[] team = rgb(in.blue ? env.blueTeam() : env.redTeam());
    lit.setUniformf("u_color", team[0], team[1], team[2], 1f);
    lit.setUniformf("u_clipPlane", 0f, 0f, 1f, in.prefab.clipPlane() ? 0f : -1e6f);
    lit.setUniformf("color_mul", in.colorMul[0], in.colorMul[1], in.colorMul[2], in.colorMul[3]);
    lit.setUniformf("color_add", in.colorAdd[0], in.colorAdd[1], in.colorAdd[2], 0f);
    lit.setUniformf("u_srgbDiffuse", 1f);
    int prim = 0;
    int partIndex = -1;
    for (Part part : in.parts) {
      partIndex++;
      Gpu g = gpu(part.ref().glb());
      Set<String> effects = library.effectGeometry(part.ref());
      for (int i = 0; i < g.meshes.size(); i++, prim++) {
        if (effectPrimitive(g, i, effects)) continue;
        upload(g, i, in.posed[prim], in.normals[prim], in.tangents[prim]);
        Glb gl = g.rig.glb();
        int node = gl.primitiveNode(i);
        UberPbr.setGlow(
            lit,
            in.extras == null || node < 0
                ? null
                : in.extras.shading(partIndex, gl.nodes().get(node).name()));
        Texture[] t = g.textures.get(i);
        String[] names = {"diffuseTex", "mraTex", "normalTex", "teamColorTex", "reflectioinTex"};
        for (int k = names.length - 1; k >= 0; k--) {
          t[k].bind(k);
          lit.setUniformi(names[k], k);
        }
        float[] pm = g.params.get(i);
        lit.setUniformf("metalness", pm[0]);
        lit.setUniformf("roughness", pm[1]);
        lit.setUniformf("emissiveStrength", pm[2]);
        g.meshes.get(i).render(lit, GL20.GL_TRIANGLES);
      }
    }
    if (in.extras != null) in.extras.draw(in, mvp);
    Gdx.gl.glActiveTexture(GL20.GL_TEXTURE0);
    Gdx.gl.glDepthMask(true);
    Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
    Gdx.gl.glEnable(GL20.GL_BLEND);
    restore(saved);
    out.resume();
    Texture tex = image.getColorBufferTexture();
    tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    if (in.imageScale != null) {
      float gx = in.x;
      float gy = in.y + in.lift;
      b[0] = gx + (b[0] - gx) * in.imageScale[0];
      b[2] = gx + (b[2] - gx) * in.imageScale[0];
      b[1] = gy + (b[1] - gy) * in.imageScale[1];
      b[3] = gy + (b[3] - gy) * in.imageScale[1];
    }
    out.image(
        tex,
        b[0],
        b[1],
        b[2] - b[0],
        b[3] - b[1],
        0,
        0,
        w / (float) tex.getWidth(),
        h / (float) tex.getHeight(),
        Color.WHITE,
        null,
        0,
        0);
  }

  /**
   * Draws the posed prefab's planar shadow (DATA: {@code planar_shadow.vert}, the model flattened
   * onto the ground along the light; {@code shadow_2d_composite}, the mask summed over four taps
   * and tinted): the mask rendered at half the model's resolution ({@code VANILLA_3D_PPM_OF_SHADOW}
   * 50 against {@code VANILLA_3D_PPM} 100), drawn in {@code color} (the arena's shadow colour,
   * straight alpha). The light it falls along is the prefab's own {@code sun} (INFERRED: real
   * battles show the 3D units' shadows to the left, as the sun casts them; the environment's light,
   * which colours the model, would cast them to the right), its rotation read as yaw and pitch
   * ({@link ModelCamera#shadowLight}): 80.6° up, so a shadow lies mostly under its model and shows
   * as a short band down and to the left.
   */
  public void drawShadow(Instance in, Matrix4 projection, Color color, Compositor out) {
    if (in.parts.isEmpty() || !ready()) return;
    boolean any = false;
    for (Part p : in.parts) any |= p.ref().shadow();
    if (!any) return;
    pose(in);
    Sc3dLibrary.Light sun =
        in.prefab.light() != null ? in.prefab.light() : library.environment().light();
    float[] l = shadowLine(sun);
    float lift = Float.isNaN(in.shadowLift) ? in.lift : in.shadowLift;
    float[] b = bounds(in, in.posed, l, lift);
    if (!(b[2] > b[0]) || !(b[3] > b[1])) return;
    float ppu = pixelsPerUnit(projection) * SUPERSAMPLE / 2f;
    float pad = 3f / ppu;
    b[0] -= pad;
    b[1] -= pad;
    b[2] += pad;
    b[3] += pad;
    int w = Math.min(MAX_SIZE, (int) Math.ceil((b[2] - b[0]) * ppu));
    int h = Math.min(MAX_SIZE, (int) Math.ceil((b[3] - b[1]) * ppu));
    b[2] = b[0] + w / ppu;
    b[3] = b[1] + h / ppu;
    out.pause();
    int[] saved = binding();
    shadowImage = target(shadowImage, w, h, false);
    bind(shadowImage, w, h);
    Gdx.gl.glClearColor(0, 0, 0, 0);
    Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
    Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);
    Gdx.gl.glDisable(GL20.GL_BLEND);
    Gdx.gl.glDisable(GL20.GL_CULL_FACE);
    mvp(in, b, lift);
    shadow.bind();
    shadow.setUniformMatrix("u_mvp", mvp);
    shadow.setUniformf("u_lightDirectionTime", l[0], l[1], l[2], 0f);
    shadow.setUniformf("u_clipPlane", 0f, 0f, 1f, in.prefab.clipPlane() ? 0f : -1e6f);
    int prim = 0;
    for (Part part : in.parts) {
      Gpu g = gpu(part.ref().glb());
      Set<String> effects = library.effectGeometry(part.ref());
      for (int i = 0; i < g.meshes.size(); i++, prim++) {
        if (!part.ref().shadow() || effectPrimitive(g, i, effects)) continue;
        upload(g, i, in.posed[prim], in.normals[prim], in.tangents[prim]);
        g.meshes.get(i).render(shadow, GL20.GL_TRIANGLES);
      }
    }
    Gdx.gl.glEnable(GL20.GL_BLEND);
    restore(saved);
    out.resume();
    Texture tex = shadowImage.getColorBufferTexture();
    tex.setFilter(Texture.TextureFilter.Linear, Texture.TextureFilter.Linear);
    float a = color.a * in.colorMul[3];
    Color tint = new Color(color.r * a, color.g * a, color.b * a, a);
    out.image(
        tex,
        b[0],
        b[1],
        b[2] - b[0],
        b[3] - b[1],
        0,
        0,
        w / (float) tex.getWidth(),
        h / (float) tex.getHeight(),
        tint,
        composite,
        0.5f / tex.getWidth(),
        0.5f / tex.getHeight());
  }

  /**
   * The scene uniforms: the environment's main light ({@code arn_default}'s {@code light}, not the
   * prefab's own {@code sun}: INFERRED, its warm white light and neutral ambient match the real
   * battle's colours where the prefab sun's green ambient does not), the camera on its axis.
   */
  private void setScene(ShaderProgram p, Instance in) {
    Sc3dLibrary.Light light = library.environment().light();
    float[] l = towardsLight(light);
    p.setUniformf("u_lightDirectionTime", l[0], l[1], l[2], 0f);
    float[] cam = ModelCamera.cameraPosition();
    p.setUniformf("u_cameraPosition", cam[0], cam[1], cam[2], 1f);
    float[] dir = directColor(light);
    p.setUniformf("u_dirLightColor", dir[0], dir[1], dir[2], 1f);
    float[] amb = ambientColor(light);
    p.setUniformf("u_sceneAmbientColor", amb[0], amb[1], amb[2], amb[3]);
  }

  /** Towards the prefab's light, or straight down from the camera's side without one. */
  static float[] towardsLight(Sc3dLibrary.Light light) {
    if (light == null) return new float[] {0f, 0.6f, 0.8f};
    float[] r = light.rotation();
    return ModelCamera.towardsLight(r[0], r[1], r[2]);
  }

  /** The line the light's shadow falls along ({@link ModelCamera#shadowLight}), z kept positive. */
  static float[] shadowLine(Sc3dLibrary.Light light) {
    if (light == null) return new float[] {0f, 0.6f, 0.8f};
    float[] r = light.rotation();
    float[] v = ModelCamera.shadowLight(r[0], r[1], r[2]);
    return v[2] < 0 ? new float[] {-v[0], -v[1], -v[2]} : v;
  }

  /**
   * The directional light colour: the light's colour times its intensity (INFERRED: the colour as a
   * 0..1 factor, not decoded from sRGB).
   */
  static float[] directColor(Sc3dLibrary.Light light) {
    if (light == null) return new float[] {1f, 1f, 1f};
    float[] c = light.color();
    float i = light.intensity();
    return new float[] {c[0] * i, c[1] * i, c[2] * i};
  }

  /** The ambient colour and, in w, the reflection strength ({@code AmbientSpecIntensity}). */
  static float[] ambientColor(Sc3dLibrary.Light light) {
    if (light == null) return new float[] {0.5f, 0.5f, 0.5f, 1f};
    float[] a = light.ambient();
    return new float[] {a[0], a[1], a[2], light.ambientSpecular()};
  }

  /**
   * The team colour as the shader multiplies it: {@code VANILLA_3D_ACTOR_COLORS} as plain 0..1
   * factors, as the lights' colours are, not decoded from sRGB (INFERRED: the Hero Knight's cape
   * and the blue Hero Balloon's panels, about (60, 110, 220), match real battles so; decoded from
   * sRGB they came out a dark navy; §10.2, §10.3).
   */
  static float[] rgb(int rgb) {
    return new float[] {
      ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f
    };
  }

  private static void upload(Gpu g, int i, float[] pos, float[] nrm, float[] tan) {
    Glb.Primitive p = g.rig.glb().primitives().get(i);
    float[] v = g.vertices.get(i);
    int n = p.vertexCount();
    float[] uv = p.uvs();
    for (int k = 0; k < n; k++) {
      int o = k * 12;
      v[o] = pos[k * 3];
      v[o + 1] = pos[k * 3 + 1];
      v[o + 2] = pos[k * 3 + 2];
      v[o + 3] = nrm[k * 3];
      v[o + 4] = nrm[k * 3 + 1];
      v[o + 5] = nrm[k * 3 + 2];
      v[o + 6] = tan[k * 4];
      v[o + 7] = tan[k * 4 + 1];
      v[o + 8] = tan[k * 4 + 2];
      v[o + 9] = tan[k * 4 + 3];
      v[o + 10] = uv == null ? 0 : uv[k * 2];
      v[o + 11] = uv == null ? 0 : uv[k * 2 + 1];
    }
    g.meshes.get(i).setVertices(v);
  }

  private static FrameBuffer target(FrameBuffer fb, int w, int h, boolean depth) {
    if (fb != null && fb.getWidth() >= w && fb.getHeight() >= h) return fb;
    int nw = Math.max(w, fb == null ? 256 : fb.getWidth());
    int nh = Math.max(h, fb == null ? 256 : fb.getHeight());
    if (fb != null) fb.dispose();
    return new FrameBuffer(Pixmap.Format.RGBA8888, nw, nh, depth);
  }

  /**
   * The bound framebuffer and viewport, read before a framebuffer is made (making one binds the
   * default framebuffer afterwards).
   */
  private int[] binding() {
    ints.clear();
    Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, ints);
    int previous = ints.get(0);
    ints.clear();
    Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, ints);
    return new int[] {previous, ints.get(0), ints.get(1), ints.get(2), ints.get(3)};
  }

  /** Binds the image's framebuffer and its viewport. */
  private static void bind(FrameBuffer fb, int w, int h) {
    Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, fb.getFramebufferHandle());
    Gdx.gl.glViewport(0, 0, w, h);
  }

  private static void restore(int[] saved) {
    Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, saved[0]);
    Gdx.gl.glViewport(saved[1], saved[2], saved[3], saved[4]);
  }

  @Override
  public void dispose() {
    for (Gpu g : gpus.values()) {
      for (Mesh m : g.meshes) m.dispose();
      for (Texture[] ts : g.textures) {
        for (Texture t : ts)
          if (t != white && t != black && !sideTextures.containsValue(t)) t.dispose();
      }
    }
    gpus.clear();
    sideTextures.values().forEach(Texture::dispose);
    sideTextures.clear();
    for (Disposable d :
        new Disposable[] {lit, shadow, composite, image, shadowImage, white, black}) {
      if (d != null) d.dispose();
    }
  }
}
