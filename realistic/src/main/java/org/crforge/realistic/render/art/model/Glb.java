/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One model the sc3d extractor ({@code tools/game-art crforge-art sc3d}) wrote as a standard glTF
 * 2.0 binary: its node tree, its one skinned or static mesh (positions, normals, tangents, texture
 * coordinates, joints, weights, triangles), its skin, its animation clips and its embedded textures
 * (PNG). The extractor's {@code zup_to_yup} root, which turns the game's Z-up prefab space into
 * glTF's Y-up for viewers, is left out: every transform here is in prefab space (Z up), as the
 * game's prefabs place their models (docs/realistic-rendering.md §10).
 */
public final class Glb {

  /** The extractor's viewer root (Z-up prefab space to glTF Y-up), dropped. */
  static final String ZUP_ROOT = "zup_to_yup";

  /** A node of the tree: its rest transform and its children. */
  public record Node(
      String name, float[] translation, float[] rotation, float[] scale, int[] children) {}

  /** A triangle list with its per-vertex streams; joints and weights are null when unskinned. */
  public record Primitive(
      float[] positions,
      float[] normals,
      float[] tangents,
      float[] uvs,
      int[] joints,
      float[] weights,
      int[] indices,
      int material) {

    public int vertexCount() {
      return positions.length / 3;
    }
  }

  /** A skin: its joint nodes and their inverse bind matrices (column-major, 16 each). */
  public record Skin(int[] joints, float[] inverseBind) {}

  /** One animated property of a node over time. */
  public record Channel(int node, String path, float[] times, float[] values, boolean step) {}

  /** An animation clip: its channels and its length in seconds. */
  public record Clip(String name, List<Channel> channels, float length) {}

  /** A material: its texture image indices (-1 when absent) and its extras (the game's params). */
  public record Material(String name, int baseColor, int normal, int orm, JsonNode extras) {}

  private final List<Node> nodes = new ArrayList<>();
  private final int[] parents;
  private final List<Primitive> primitives = new ArrayList<>();
  private final List<Integer> primitiveNodes = new ArrayList<>();
  private Skin skin;
  private final Map<String, Clip> clips = new HashMap<>();
  private final List<byte[]> images = new ArrayList<>();
  private final List<Material> materials = new ArrayList<>();

  private final JsonNode json;
  private final ByteBuffer bin;

  private Glb(JsonNode json, ByteBuffer bin) {
    this.json = json;
    this.bin = bin;
    JsonNode ns = json.path("nodes");
    parents = new int[ns.size()];
    java.util.Arrays.fill(parents, -1);
    for (int i = 0; i < ns.size(); i++) {
      JsonNode n = ns.get(i);
      int[] children = ints(n.path("children"));
      for (int c : children) parents[c] = i;
      nodes.add(
          new Node(
              n.path("name").asText(""),
              floats(n.path("translation"), new float[] {0, 0, 0}),
              floats(n.path("rotation"), new float[] {0, 0, 0, 1}),
              floats(n.path("scale"), new float[] {1, 1, 1}),
              children));
    }
    // the viewer root is no parent in prefab space
    for (int i = 0; i < parents.length; i++) {
      if (parents[i] >= 0 && ZUP_ROOT.equals(nodes.get(parents[i]).name())) parents[i] = -1;
    }
    for (int i = 0; i < ns.size(); i++) {
      JsonNode n = ns.get(i);
      if (!n.has("mesh")) continue;
      JsonNode mesh = json.path("meshes").get(n.path("mesh").asInt());
      for (JsonNode p : mesh.path("primitives")) {
        JsonNode a = p.path("attributes");
        primitives.add(
            new Primitive(
                accessorFloats(a.path("POSITION")),
                accessorFloats(a.path("NORMAL")),
                accessorFloats(a.path("TANGENT")),
                accessorFloats(a.path("TEXCOORD_0")),
                accessorInts(a.path("JOINTS_0")),
                accessorFloats(a.path("WEIGHTS_0")),
                accessorInts(p.path("indices")),
                p.path("material").asInt(-1)));
        primitiveNodes.add(i);
      }
      if (n.has("skin") && skin == null) {
        JsonNode s = json.path("skins").get(n.path("skin").asInt());
        skin = new Skin(ints(s.path("joints")), accessorFloats(s.path("inverseBindMatrices")));
      }
    }
    for (JsonNode an : json.path("animations")) {
      List<Channel> channels = new ArrayList<>();
      float length = 0;
      for (JsonNode c : an.path("channels")) {
        JsonNode sampler = an.path("samplers").get(c.path("sampler").asInt());
        float[] times = accessorFloats(sampler.path("input"));
        float[] values = accessorFloats(sampler.path("output"));
        if (times == null || values == null || times.length == 0) continue;
        length = Math.max(length, times[times.length - 1]);
        channels.add(
            new Channel(
                c.path("target").path("node").asInt(),
                c.path("target").path("path").asText(),
                times,
                values,
                "STEP".equals(sampler.path("interpolation").asText("LINEAR"))));
      }
      String name = an.path("name").asText("");
      clips.put(name, new Clip(name, channels, length));
    }
    for (JsonNode im : json.path("images")) {
      JsonNode bv = json.path("bufferViews").get(im.path("bufferView").asInt());
      byte[] data = new byte[bv.path("byteLength").asInt()];
      bin.duplicate().position(bv.path("byteOffset").asInt(0)).get(data);
      images.add(data);
    }
    for (JsonNode m : json.path("materials")) {
      JsonNode pbr = m.path("pbrMetallicRoughness");
      materials.add(
          new Material(
              m.path("name").asText(""),
              textureImage(pbr.path("baseColorTexture")),
              textureImage(m.path("normalTexture")),
              textureImage(pbr.path("metallicRoughnessTexture")),
              m.path("extras")));
    }
  }

  /** Reads a {@code .glb} file. */
  public static Glb read(Path file) {
    try {
      return parse(Files.readAllBytes(file));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Parses the bytes of a {@code .glb} file (glTF binary: a JSON chunk and a BIN chunk). */
  public static Glb parse(byte[] bytes) {
    ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    if (b.getInt(0) != 0x46546C67) throw new IllegalArgumentException("not a glTF binary");
    int off = 12;
    JsonNode json = null;
    ByteBuffer bin = null;
    while (off + 8 <= bytes.length) {
      int len = b.getInt(off);
      int type = b.getInt(off + 4);
      if (type == 0x4E4F534A) {
        try {
          json =
              new ObjectMapper().readTree(new String(bytes, off + 8, len, StandardCharsets.UTF_8));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      } else if (type == 0x004E4942) {
        bin = b.slice(off + 8, len).order(ByteOrder.LITTLE_ENDIAN);
      }
      off += 8 + len;
    }
    if (json == null || bin == null) throw new IllegalArgumentException("glb without JSON or BIN");
    return new Glb(json, bin);
  }

  private int textureImage(JsonNode ref) {
    if (ref.isMissingNode() || !ref.has("index")) return -1;
    JsonNode t = json.path("textures").get(ref.path("index").asInt());
    return t == null ? -1 : t.path("source").asInt(-1);
  }

  private static float[] floats(JsonNode a, float[] fallback) {
    if (!a.isArray()) return fallback;
    float[] out = new float[a.size()];
    for (int i = 0; i < out.length; i++) out[i] = (float) a.get(i).asDouble();
    return out;
  }

  private static int[] ints(JsonNode a) {
    int[] out = new int[a.isArray() ? a.size() : 0];
    for (int i = 0; i < out.length; i++) out[i] = a.get(i).asInt();
    return out;
  }

  private static int components(String type) {
    return switch (type) {
      case "SCALAR" -> 1;
      case "VEC2" -> 2;
      case "VEC3" -> 3;
      case "VEC4" -> 4;
      case "MAT4" -> 16;
      default -> throw new IllegalArgumentException("accessor type " + type);
    };
  }

  /** An accessor's values as floats (normalized integers scaled to 0..1 or -1..1). */
  private float[] accessorFloats(JsonNode index) {
    if (!index.canConvertToInt()) return null;
    JsonNode a = json.path("accessors").get(index.asInt());
    int n = components(a.path("type").asText());
    int count = a.path("count").asInt();
    int type = a.path("componentType").asInt();
    boolean normalized = a.path("normalized").asBoolean(false);
    float[] out = new float[count * n];
    read(a, n, count, type, (i, v) -> out[i] = normalized ? normalize(type, v) : (float) v);
    return out;
  }

  private int[] accessorInts(JsonNode index) {
    if (!index.canConvertToInt()) return null;
    JsonNode a = json.path("accessors").get(index.asInt());
    int n = components(a.path("type").asText());
    int count = a.path("count").asInt();
    int[] out = new int[count * n];
    read(a, n, count, a.path("componentType").asInt(), (i, v) -> out[i] = (int) v);
    return out;
  }

  private static float normalize(int type, double v) {
    return switch (type) {
      case 5121 -> (float) (v / 255.0);
      case 5123 -> (float) (v / 65535.0);
      case 5120 -> (float) Math.max(-1.0, v / 127.0);
      case 5122 -> (float) Math.max(-1.0, v / 32767.0);
      default -> (float) v;
    };
  }

  private interface Sink {
    void put(int index, double value);
  }

  private void read(JsonNode a, int n, int count, int type, Sink sink) {
    JsonNode bv = json.path("bufferViews").get(a.path("bufferView").asInt());
    int size =
        switch (type) {
          case 5120, 5121 -> 1;
          case 5122, 5123 -> 2;
          default -> 4;
        };
    int base = bv.path("byteOffset").asInt(0) + a.path("byteOffset").asInt(0);
    int stride = bv.path("byteStride").asInt(0);
    if (stride == 0) stride = size * n;
    for (int i = 0; i < count; i++) {
      for (int c = 0; c < n; c++) {
        int o = base + i * stride + c * size;
        double v =
            switch (type) {
              case 5120 -> bin.get(o);
              case 5121 -> bin.get(o) & 0xFF;
              case 5122 -> bin.getShort(o);
              case 5123 -> bin.getShort(o) & 0xFFFF;
              case 5125 -> bin.getInt(o) & 0xFFFFFFFFL;
              default -> bin.getFloat(o);
            };
        sink.put(i * n + c, v);
      }
    }
  }

  public List<Node> nodes() {
    return nodes;
  }

  /** The node's parent in prefab space, or -1 for a root (the viewer root is no parent). */
  public int parent(int node) {
    return parents[node];
  }

  public List<Primitive> primitives() {
    return primitives;
  }

  /** The node that carries the primitive (its transform applies unless the mesh is skinned). */
  public int primitiveNode(int primitive) {
    return primitiveNodes.get(primitive);
  }

  public Skin skin() {
    return skin;
  }

  public Map<String, Clip> clips() {
    return clips;
  }

  public List<byte[]> images() {
    return images;
  }

  public List<Material> materials() {
    return materials;
  }
}
