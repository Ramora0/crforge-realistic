/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.server;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import lombok.extern.slf4j.Slf4j;
import org.crforge.realistic.render.art.model.Glb;
import org.crforge.realistic.render.art.model.Sc3dLibrary;

/**
 * The 3D models the page draws itself (docs/phone-server.md, "3D models on the phone"): the model
 * refs the draw lists name by number (a model file placed by its prefab transform, with its shadow
 * flag and the geometry left out), and each model as a compact binary the page fetches over HTTP
 * ({@code /m3d/model/<id>.bin}: its node tree, skin, clips, primitives and materials, deflated)
 * with its textures ({@code /m3d/tex/<id>.png}, the PNGs the extraction holds, by content hash).
 * Interned for the server's life on the GL thread; the binaries are built on a thread of their own
 * and served from any.
 */
@Slf4j
final class ModelAssets {

  /** The binary's format; a new one gives every model a new id (the page caches by id). */
  static final String FORMAT = "CRM1";

  /** A model ref as the draw lists define it. */
  record Ref(int id, String asset, float[] transform, boolean shadow, int[] skip) {}

  private final Sc3dLibrary library;
  private final List<Ref> refs = new ArrayList<>();
  private final Map<Object, Integer> refIds = new HashMap<>();
  private final Map<Path, String> assetIds = new HashMap<>();
  private final Map<Path, List<String>> clipNames = new HashMap<>();
  private final Map<String, CompletableFuture<byte[]>> models = new ConcurrentHashMap<>();
  private final Map<String, byte[]> textures = new ConcurrentHashMap<>();
  private final ExecutorService builder;

  /** Bytes of the model binaries and textures built so far. */
  volatile long builtBytes;

  /**
   * @param library the extraction the models come from (null in tests: refs only)
   */
  ModelAssets(Sc3dLibrary library) {
    this.library = library;
    this.builder =
        library == null
            ? null
            : Executors.newSingleThreadExecutor(
                r -> {
                  Thread t = new Thread(r, "model-assets");
                  t.setDaemon(true);
                  return t;
                });
  }

  Sc3dLibrary library() {
    return library;
  }

  private record RefKey(Path glb, List<Float> transform, boolean shadow, Set<String> effects) {}

  /**
   * The id of a model ref (interned), its model's binary built ahead. GL thread: {@code glb} is the
   * model the renderer has read.
   */
  synchronized int ref(Sc3dLibrary.ModelRef ref, Set<String> effects, Glb glb) {
    List<Float> t = new ArrayList<>();
    for (float f : ref.transform()) t.add(f);
    RefKey key = new RefKey(ref.glb(), t, ref.shadow(), Set.copyOf(effects));
    Integer id = refIds.get(key);
    if (id != null) return id;
    String asset = asset(ref.glb(), glb);
    List<Integer> skip = new ArrayList<>();
    if (!effects.isEmpty()) {
      for (int i = 0; i < glb.primitives().size(); i++) {
        int node = glb.primitiveNode(i);
        if (node >= 0 && effects.contains(glb.nodes().get(node).name())) skip.add(i);
      }
    }
    int next = refs.size();
    refs.add(
        new Ref(
            next,
            asset,
            ref.transform().clone(),
            ref.shadow(),
            skip.stream().mapToInt(Integer::intValue).toArray()));
    refIds.put(key, next);
    return next;
  }

  /** A ref defined by hand (tests). */
  synchronized int ref(String asset, float[] transform, boolean shadow, int[] skip) {
    int next = refs.size();
    refs.add(new Ref(next, asset, transform.clone(), shadow, skip.clone()));
    return next;
  }

  synchronized Ref ref(int id) {
    return refs.get(id);
  }

  synchronized int refCount() {
    return refs.size();
  }

  /** The model's clip by name as the binary orders them (by name), plus one; 0 for none. */
  synchronized int clip(Path glbFile, String name) {
    if (name == null) return 0;
    List<String> names = clipNames.get(glbFile);
    if (names == null) return 0;
    int i = java.util.Collections.binarySearch(names, name);
    return i < 0 ? 0 : i + 1;
  }

  /** The asset id of a model file, its binary built on the builder thread. */
  private String asset(Path file, Glb glb) {
    String id = assetIds.get(file);
    if (id != null) return id;
    String rel = library == null ? file.toString() : library.folder().relativize(file).toString();
    long size = 0, time = 0;
    try {
      size = Files.size(file);
      time = Files.getLastModifiedTime(file).toMillis();
    } catch (IOException e) {
      // a name without the file's stamp
    }
    id = hash((FORMAT + "|" + rel + "|" + size + "|" + time).getBytes(StandardCharsets.UTF_8));
    assetIds.put(file, id);
    clipNames.put(file, new ArrayList<>(new TreeSet<>(glb.clips().keySet())));
    if (builder != null) {
      String assetId = id;
      List<String> names = clipNames.get(file);
      models.put(
          id,
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  byte[] b = build(glb, names);
                  builtBytes += b.length;
                  return b;
                } catch (RuntimeException e) {
                  log.warn("model {} ({}): {}", assetId, rel, e.toString());
                  throw e;
                }
              },
              builder));
    }
    return id;
  }

  private final Map<Path, String> fileTextures = new HashMap<>();

  /** The texture id of an effect's texture file (sent as it is), or null. GL thread. */
  synchronized String fileTexture(Path file) {
    if (file == null) return null;
    String id = fileTextures.get(file);
    if (id != null) return id;
    try {
      byte[] png = Files.readAllBytes(file);
      id = hash(png);
      if (textures.putIfAbsent(id, png) == null) builtBytes += png.length;
    } catch (IOException e) {
      id = null;
    }
    fileTextures.put(file, id);
    return id;
  }

  /** The model binary of that id (waits while it is built), or empty. */
  Optional<byte[]> model(String id) {
    CompletableFuture<byte[]> f = models.get(id);
    if (f == null) return Optional.empty();
    try {
      return Optional.of(f.join());
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  /** A texture's PNG by id, or empty. */
  Optional<byte[]> texture(String id) {
    return Optional.ofNullable(textures.get(id));
  }

  /** The model binaries' and their textures' total size, for the ids given (models only). */
  long bytes(java.util.Collection<String> modelIds) {
    long n = 0;
    Set<String> tex = new TreeSet<>();
    for (String id : modelIds) {
      byte[] b = model(id).orElse(null);
      if (b == null) continue;
      n += b.length;
      tex.addAll(textureIds(b));
    }
    for (String t : tex) n += texture(t).map(x -> x.length).orElse(0);
    return n;
  }

  void dispose() {
    if (builder != null) builder.shutdownNow();
  }

  static String hash(byte[] bytes) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-1").digest(bytes);
      return HexFormat.of().formatHex(d, 0, 8);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ------------------------------------------------------------------------------- the binary

  /**
   * A model as the page reads it (little-endian), deflated (raw deflate):
   *
   * <pre>
   * "CRM1"
   * u16 nodes; each: i16 parent, f32 translation[3], rotation[4] (xyzw), scale[3]
   * u16 joints; u16 joint nodes; f32 inverse bind matrices (16 each, column-major)
   * u16 clips (by name); each: f32 length, u16 channels; each: u16 node, u8 path (0
   *     translation, 1 rotation, 2 scale), u8 step, u32 keys, f32 times; rotation: i16 xyzw
   *     (value · 32767); translation and scale: f32 min[3], f32 step[3], u16 values (min + v · step)
   * u16 textures; each: u8 length, the id (ASCII)
   * u16 primitives; each: i16 node, u32 vertices, u32 indices, u8 flags (1 normals, 2 tangents,
   *     4 uvs, 8 skinned, 16 32-bit indices), i8 textures[5] (diffuse, mra, normal, team, reflection;
   *     -1 none), f32 metalness, roughness, emissiveStrength; f32 positions[3n]; i16 normals[3n]
   *     (· 32767); i16 tangents[4n] (xyz · 32767, w ±32767); f32 uvs[2n]; u8 joints[4n], u16
   *     weights[4n] (· 65535); indices (u16 or u32)
   * </pre>
   */
  byte[] build(Glb glb, List<String> clipOrder) {
    Out o = new Out();
    o.bytes(FORMAT.getBytes(StandardCharsets.US_ASCII));
    List<Glb.Node> nodes = glb.nodes();
    o.u16(nodes.size());
    for (int n = 0; n < nodes.size(); n++) {
      Glb.Node node = nodes.get(n);
      o.u16(glb.parent(n));
      for (int i = 0; i < 3; i++) o.f32(node.translation()[i]);
      for (int i = 0; i < 4; i++) o.f32(node.rotation()[i]);
      for (int i = 0; i < 3; i++) o.f32(node.scale()[i]);
    }
    Glb.Skin skin = glb.skin();
    int joints = skin == null ? 0 : skin.joints().length;
    o.u16(joints);
    for (int j = 0; j < joints; j++) o.u16(skin.joints()[j]);
    for (int j = 0; j < joints * 16; j++) o.f32(skin.inverseBind()[j]);
    o.u16(clipOrder.size());
    for (String name : clipOrder) {
      Glb.Clip clip = glb.clips().get(name);
      o.f32(clip.length());
      List<Glb.Channel> channels = new ArrayList<>();
      for (Glb.Channel c : clip.channels()) {
        if (pathCode(c.path()) < 0) continue;
        // a channel that holds one value is that value (lossless); at the rest value, none at all
        Glb.Channel k = constant(c);
        if (k != null && k.node() < nodes.size() && Arrays.equals(k.values(), rest(nodes, k))) {
          continue;
        }
        channels.add(k != null ? k : c);
      }
      o.u16(channels.size());
      for (Glb.Channel c : channels) {
        int path = pathCode(c.path());
        int n = path == 1 ? 4 : 3;
        int keys = c.times().length;
        o.u16(c.node());
        o.u8(path);
        o.u8(c.step() ? 1 : 0);
        o.u32(keys);
        for (float t : c.times()) o.f32(t);
        float[] v = c.values();
        if (path == 1) {
          for (int i = 0; i < keys * 4; i++) o.i16(Math.round(clamp(v[i]) * 32767f));
        } else {
          float[] min = new float[n];
          float[] step = new float[n];
          for (int k = 0; k < n; k++) {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (int i = 0; i < keys; i++) {
              lo = Math.min(lo, v[i * n + k]);
              hi = Math.max(hi, v[i * n + k]);
            }
            min[k] = lo;
            step[k] = hi > lo ? (hi - lo) / 65535f : 0f;
          }
          for (int k = 0; k < n; k++) o.f32(min[k]);
          for (int k = 0; k < n; k++) o.f32(step[k]);
          for (int i = 0; i < keys; i++) {
            for (int k = 0; k < n; k++) {
              int q = step[k] == 0 ? 0 : Math.round((v[i * n + k] - min[k]) / step[k]);
              o.u16(Math.max(0, Math.min(65535, q)));
            }
          }
        }
      }
    }
    // textures: the model's images and its materials' side textures, by content
    List<String> texIds = new ArrayList<>();
    Map<String, Integer> texIndex = new HashMap<>();
    List<int[]> primTex = new ArrayList<>();
    for (Glb.Primitive p : glb.primitives()) {
      Glb.Material m = p.material() >= 0 ? glb.materials().get(p.material()) : null;
      int[] t = new int[5];
      Arrays.fill(t, -1);
      if (m != null) {
        t[0] = textureIndex(image(glb, m.baseColor()), true, texIds, texIndex);
        t[1] = textureIndex(image(glb, m.orm()), false, texIds, texIndex);
        t[2] = textureIndex(image(glb, m.normal()), false, texIds, texIndex);
        t[3] = textureIndex(side(m.extras(), "teamColorTex"), false, texIds, texIndex);
        t[4] = textureIndex(side(m.extras(), "reflectioinTex"), false, texIds, texIndex);
      }
      primTex.add(t);
    }
    o.u16(texIds.size());
    for (String id : texIds) {
      byte[] b = id.getBytes(StandardCharsets.US_ASCII);
      o.u8(b.length);
      o.bytes(b);
    }
    o.u16(glb.primitives().size());
    for (int pi = 0; pi < glb.primitives().size(); pi++) {
      Glb.Primitive p = glb.primitives().get(pi);
      int n = p.vertexCount();
      boolean skinned = p.joints() != null && p.weights() != null && skin != null;
      boolean wide = n > 65535;
      int flags =
          (p.normals() != null ? 1 : 0)
              | (p.tangents() != null ? 2 : 0)
              | (p.uvs() != null ? 4 : 0)
              | (skinned ? 8 : 0)
              | (wide ? 16 : 0);
      o.u16(glb.primitiveNode(pi));
      o.u32(n);
      o.u32(p.indices().length);
      o.u8(flags);
      for (int t : primTex.get(pi)) o.u8(t);
      Glb.Material m = p.material() >= 0 ? glb.materials().get(p.material()) : null;
      float[] params = params(m);
      for (float f : params) o.f32(f);
      for (float f : p.positions()) o.f32(f);
      if (p.normals() != null) for (float f : p.normals()) o.i16(Math.round(clamp(f) * 32767f));
      if (p.tangents() != null) {
        float[] t = p.tangents();
        for (int v = 0; v < n; v++) {
          for (int k = 0; k < 3; k++) o.i16(Math.round(clamp(t[v * 4 + k]) * 32767f));
          o.i16(t[v * 4 + 3] < 0 ? -32767 : 32767);
        }
      }
      if (p.uvs() != null) for (float f : p.uvs()) o.f32(f);
      if (skinned) {
        for (int j : p.joints()) o.u8(j);
        for (float w : p.weights()) o.u16(Math.max(0, Math.min(65535, Math.round(w * 65535f))));
      }
      for (int i : p.indices()) {
        if (wide) o.u32(i);
        else o.u16(i);
      }
    }
    Deflater d = new Deflater(9, true);
    d.setInput(o.out.toByteArray());
    d.finish();
    ByteArrayOutputStream z = new ByteArrayOutputStream();
    byte[] chunk = new byte[1 << 16];
    while (!d.finished()) z.write(chunk, 0, d.deflate(chunk));
    d.end();
    return z.toByteArray();
  }

  /**
   * The quality opaque colour (diffuse) textures are sent at as JPEG (0: as their PNG). A model is
   * a few hundred pixels on the screen at most; the data textures (MRA, normal, team mask,
   * reflection) stay lossless PNG, as JPEG's colour subsampling mixes their channels (the shading
   * then differed twice as much in the parity check); so do textures with transparency.
   */
  static final float JPEG_QUALITY = 0.92f;

  /** An opaque PNG as JPEG, or null (it has transparency, or cannot be read). */
  static byte[] jpeg(byte[] png) {
    try {
      java.awt.image.BufferedImage img =
          javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
      if (img == null) return null;
      int w = img.getWidth(), h = img.getHeight();
      if (img.getColorModel().hasAlpha()) {
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        for (int p : argb) if ((p >>> 24) != 0xFF) return null;
      }
      java.awt.image.BufferedImage rgb =
          new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
      rgb.getGraphics().drawImage(img, 0, 0, null);
      javax.imageio.ImageWriter writer =
          javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
      javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(JPEG_QUALITY);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (var ios = javax.imageio.ImageIO.createImageOutputStream(out)) {
        writer.setOutput(ios);
        writer.write(null, new javax.imageio.IIOImage(rgb, null, null), param);
      } finally {
        writer.dispose();
      }
      byte[] b = out.toByteArray();
      return b.length < png.length ? b : null;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** The channel as one key when every key holds the same value, else null. */
  private static Glb.Channel constant(Glb.Channel c) {
    int n = c.path().equals("rotation") ? 4 : 3;
    float[] v = c.values();
    if (c.times().length < 2 || v.length < n) return null;
    for (int i = n; i < v.length; i++) if (v[i] != v[i % n]) return null;
    return new Glb.Channel(
        c.node(), c.path(), new float[] {c.times()[0]}, Arrays.copyOf(v, n), c.step());
  }

  private static float[] rest(List<Glb.Node> nodes, Glb.Channel c) {
    Glb.Node node = nodes.get(c.node());
    return switch (c.path()) {
      case "rotation" -> node.rotation();
      case "scale" -> node.scale();
      default -> node.translation();
    };
  }

  /** The texture ids a model binary names (inflated and read up to its texture list). */
  static List<String> textureIds(byte[] deflated) {
    java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
    inf.setInput(deflated);
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    byte[] chunk = new byte[1 << 16];
    try {
      while (!inf.finished()) {
        int n = inf.inflate(chunk);
        if (n == 0 && inf.needsInput()) break;
        raw.write(chunk, 0, n);
      }
    } catch (java.util.zip.DataFormatException e) {
      return List.of();
    } finally {
      inf.end();
    }
    ByteBuffer b = ByteBuffer.wrap(raw.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
    b.position(4);
    int nodes = b.getShort() & 0xFFFF;
    b.position(b.position() + nodes * 42);
    int joints = b.getShort() & 0xFFFF;
    b.position(b.position() + joints * 2 + joints * 64);
    int clips = b.getShort() & 0xFFFF;
    for (int c = 0; c < clips; c++) {
      b.getFloat();
      int channels = b.getShort() & 0xFFFF;
      for (int k = 0; k < channels; k++) {
        b.getShort();
        int path = b.get();
        b.get();
        int keys = b.getInt();
        int skip = keys * 4 + (path == 1 ? keys * 8 : 24 + keys * 6);
        b.position(b.position() + skip);
      }
    }
    int count = b.getShort() & 0xFFFF;
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      byte[] s = new byte[b.get() & 0xFF];
      b.get(s);
      ids.add(new String(s, StandardCharsets.US_ASCII));
    }
    return ids;
  }

  private static int pathCode(String path) {
    return switch (path) {
      case "translation" -> 0;
      case "rotation" -> 1;
      case "scale" -> 2;
      default -> -1;
    };
  }

  private static float clamp(float v) {
    return Math.max(-1f, Math.min(1f, v));
  }

  /** {metalness, roughness, emissiveStrength}, as {@code ModelRenderer} reads them. */
  private static float[] params(Glb.Material m) {
    if (m == null) return new float[] {0f, 1f, 0f};
    JsonNode f = m.extras().path("variables").path("floats");
    return new float[] {
      (float) f.path("metalness").asDouble(0),
      (float) f.path("roughness").asDouble(1),
      (float) f.path("emissiveStrength").asDouble(1)
    };
  }

  private static byte[] image(Glb glb, int index) {
    if (index < 0 || index >= glb.images().size()) return null;
    return glb.images().get(index);
  }

  private byte[] side(JsonNode extras, String slot) {
    Optional<Path> p = library == null ? Optional.empty() : library.sideTexture(extras, slot);
    if (p.isEmpty()) return null;
    try {
      return Files.readAllBytes(p.get());
    } catch (IOException e) {
      return null;
    }
  }

  private int textureIndex(
      byte[] png, boolean colour, List<String> ids, Map<String, Integer> index) {
    if (png == null) return -1;
    String id = hash(png);
    Integer i = index.get(id);
    if (i != null) return i;
    if (!textures.containsKey(id)) {
      byte[] sent = colour && JPEG_QUALITY > 0 ? jpeg(png) : null;
      if (sent == null) sent = png;
      if (textures.putIfAbsent(id, sent) == null) builtBytes += sent.length;
    }
    ids.add(id);
    index.put(id, ids.size() - 1);
    return ids.size() - 1;
  }

  /** A little-endian byte writer. */
  private static final class Out {
    final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);

    void u8(int v) {
      out.write(v);
    }

    void u16(int v) {
      out.write(v);
      out.write(v >>> 8);
    }

    void i16(int v) {
      u16(v & 0xFFFF);
    }

    void u32(int v) {
      u16(v & 0xFFFF);
      u16(v >>> 16);
    }

    void f32(float f) {
      u32(Float.floatToRawIntBits(f));
    }

    void bytes(byte[] b) {
      out.write(b, 0, b.length);
    }
  }
}
