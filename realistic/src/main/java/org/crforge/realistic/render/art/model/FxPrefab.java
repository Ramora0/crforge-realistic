/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A prefab's node tree as the effects need it (docs/realistic-rendering.md §10.4): each node's
 * {@code pos}, {@code rot} (Euler degrees, {@code Rz·Ry·Rx} as the extractor reads them) and {@code
 * scl}; its models ({@code 5001}, with their geometry material overrides), material assets ({@code
 * 5018}: on a model's node they apply to that model, on a node without one to the named geometries
 * of the model the prefab is attached to), nested prefabs ({@code 5006}), bone attachments ({@code
 * 5012}), and the timeline it plays ({@code 5014} names the timelines, {@code 5016} picks one and
 * whether it loops). {@link #evaluate} poses it at a time: the timeline's curves set node
 * transforms and material parameters, its events spawn sub-prefabs at their nodes.
 */
public final class FxPrefab {

  /** A node: its parent (-1 for the root), name, rest transform and bone attachment. */
  record Node(String name, int parent, float[] pos, float[] rot, float[] scl, String bone) {}

  /** A model on a node. */
  public record Model(
      int node,
      String path,
      Path glb,
      String asm,
      Map<String, String> overrides,
      List<String> assets,
      List<List<String>> assetGeometries,
      int layer,
      boolean shadow,
      int minDeviceCap) {}

  /** A material asset on a node without a model (applied to its owner's named geometries). */
  record Effect(int node, String material, List<String> geometries) {}

  /** A nested prefab on a node. */
  record Child(int node, String prefab) {}

  /** The timeline a node plays. */
  record Play(int node, String timeline, boolean loop) {}

  /** A model posed: its matrix (prefab space) and the materials its timeline animates. */
  public record Item(
      Model model, float[] matrix, FxMaterial override, FxMaterial asset, String prefab) {}

  /** A material put on the owner's geometries (all when the list is empty) while it plays. */
  public record Glow(List<String> geometries, FxMaterial material) {}

  /** Bone matrices of the model the prefab is attached to (prefab space), null when unknown. */
  public interface Bones {
    float[] bone(String name);
  }

  /** Effects drawn on a high-end device (the render settings' {@code DeviceCap} 2, DATA). */
  static final int DEVICE_CAP = 2;

  private final String path;
  private final List<Node> nodes = new ArrayList<>();
  private final List<Model> models = new ArrayList<>();
  private final List<Effect> effects = new ArrayList<>();
  private final List<Child> children = new ArrayList<>();
  private final List<Play> plays = new ArrayList<>();
  private final FxLibrary library;
  private float duration = -1;

  private FxPrefab(String path, FxLibrary library) {
    this.path = path;
    this.library = library;
  }

  public String path() {
    return path;
  }

  public List<Model> models() {
    return models;
  }

  /** Whether the prefab shows anything an effect pass draws (models, glows, nested prefabs). */
  public boolean isEmpty() {
    return models.isEmpty() && effects.isEmpty() && children.isEmpty() && plays.isEmpty();
  }

  static FxPrefab parse(String path, JsonNode json, FxLibrary library) {
    FxPrefab p = new FxPrefab(path, library);
    p.visit(json.has("data") ? json.path("data") : json, -1);
    return p;
  }

  private static float[] vec(JsonNode n, float d) {
    float[] v = {d, d, d};
    for (int i = 0; i < 3 && i < n.size(); i++) v[i] = (float) n.get(i).asDouble(d);
    return v;
  }

  private static String ref(JsonNode n) {
    String s = n.asText("");
    s = s.split("\\|")[0].trim();
    return s.isEmpty() ? null : s;
  }

  private void visit(JsonNode n, int parent) {
    String bone = null;
    for (JsonNode c : n.path("components")) {
      if (c.path("objectType").asInt() == 5012) bone = c.path("BoneName").asText(null);
    }
    int index = nodes.size();
    nodes.add(
        new Node(
            n.path("name").asText(""),
            parent,
            vec(n.path("pos"), 0f),
            vec(n.path("rot"), 0f),
            vec(n.path("scl"), 1f),
            bone));
    JsonNode comps = n.path("components");
    String asm = null;
    boolean hasModel = false;
    for (JsonNode c : comps) {
      if (c.path("objectType").asInt() == 5003) asm = ref(c.path("AsmAsset"));
      if (c.path("objectType").asInt() == 5001 && ref(c.path("Model")) != null) hasModel = true;
    }
    Map<String, String> timelineNames = new HashMap<>();
    for (JsonNode c : comps) {
      if (c.path("objectType").asInt() == 5014) {
        for (JsonNode t : c.path("timelines")) {
          timelineNames.put(t.path("name").asText(""), ref(t.path("asset")));
        }
      }
    }
    for (JsonNode c : comps) {
      int type = c.path("objectType").asInt();
      if (type == 5001 && ref(c.path("Model")) != null) {
        String model = ref(c.path("Model"));
        Map<String, String> over = new HashMap<>();
        for (JsonNode g : c.path("Geometries")) {
          String m = ref(g.path("MaterialOverride"));
          if (m != null) over.put(g.path("Name").asText(""), m);
        }
        List<String> assets = new ArrayList<>();
        List<List<String>> geoms = new ArrayList<>();
        for (JsonNode a : comps) {
          if (a.path("objectType").asInt() == 5018 && ref(a.path("MaterialAsset")) != null) {
            assets.add(ref(a.path("MaterialAsset")));
            List<String> g = new ArrayList<>();
            for (JsonNode x : a.path("GeometryNames")) g.add(x.asText());
            geoms.add(g);
          }
        }
        models.add(
            new Model(
                index,
                model,
                library == null ? null : library.glb(model),
                asm,
                over,
                assets,
                geoms,
                c.path("Layer").asInt(0),
                c.path("ShadowVisible").asBoolean(false),
                c.path("MinDeviceCap").asInt(-1)));
      } else if (type == 5018 && !hasModel && ref(c.path("MaterialAsset")) != null) {
        List<String> g = new ArrayList<>();
        for (JsonNode x : c.path("GeometryNames")) g.add(x.asText());
        effects.add(new Effect(index, ref(c.path("MaterialAsset")), g));
      } else if (type == 5006 && ref(c.path("Prefab")) != null) {
        children.add(new Child(index, ref(c.path("Prefab"))));
      } else if (type == 5016) {
        String t = timelineNames.get(c.path("TimelineName").asText(""));
        if (t != null) plays.add(new Play(index, t, c.path("Loop").asBoolean(false)));
      }
    }
    for (JsonNode ch : n.path("children")) visit(ch, index);
  }

  /**
   * How long the prefab plays, seconds: its longest timeline (with the sub-prefabs its events spawn
   * and its nested prefabs), {@link Float#POSITIVE_INFINITY} when one loops, 0 when it has none.
   */
  public float duration() {
    if (duration >= 0) return duration;
    duration = 0;
    float d = 0;
    for (Play p : plays) {
      FxTimeline t = library.timeline(p.timeline());
      if (t == null) continue;
      if (p.loop()) {
        d = Float.POSITIVE_INFINITY;
        continue;
      }
      d = Math.max(d, t.total());
      for (FxTimeline.Track track : t.tracks().values()) {
        for (FxTimeline.Event ev : track.events()) {
          FxPrefab sub = ev.effect() == null ? null : library.prefab(ev.effect());
          if (sub != null) d = Math.max(d, ev.time() + sub.duration());
        }
      }
    }
    for (Child c : children) {
      FxPrefab sub = library.prefab(c.prefab());
      if (sub != null) d = Math.max(d, sub.duration());
    }
    duration = d;
    return d;
  }

  /** The node at {@code rel} ({@code a/b}) below {@code base}, or -1. */
  int find(int base, String rel) {
    if (rel.isEmpty()) return base;
    int at = base;
    for (String part : rel.split("/")) {
      int next = -1;
      for (int i = 0; i < nodes.size(); i++) {
        if (nodes.get(i).parent() == at && nodes.get(i).name().equals(part)) {
          next = i;
          break;
        }
      }
      if (next < 0) return -1;
      at = next;
    }
    return at;
  }

  /**
   * Poses the prefab at {@code t} seconds under {@code parent} (prefab space of what it is attached
   * to), adding its models (and its nested and spawned prefabs') to {@code items} and its glows to
   * {@code glows}. {@code bones} places bone attachments (none without it).
   */
  public void evaluate(
      float t, float[] parent, Bones bones, List<Item> items, List<Glow> glows, int depth) {
    if (depth > 4) return;
    int n = nodes.size();
    float[][] pos = new float[n][];
    float[][] rot = new float[n][];
    float[][] scl = new float[n][];
    boolean[] hidden = new boolean[n];
    Map<Integer, Map<String, Float>> modelProps = new HashMap<>();
    Map<Integer, Map<String, Float>> assetProps = new HashMap<>();
    List<float[]> spawns = new ArrayList<>(); // {node, start}
    List<String> spawnPaths = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Node nd = nodes.get(i);
      pos[i] = nd.pos().clone();
      rot[i] = nd.rot().clone();
      scl[i] = nd.scl().clone();
    }
    for (Play p : plays) {
      FxTimeline tl = library.timeline(p.timeline());
      if (tl == null) continue;
      float tt = t;
      if (p.loop() && tl.total() > 0) tt = t % tl.total();
      for (var e : tl.tracks().entrySet()) {
        int node = find(p.node(), e.getKey());
        if (node < 0) continue;
        for (var c : e.getValue().curves().entrySet()) {
          String id = c.getKey();
          float v = c.getValue().at(tt);
          switch (id) {
            case "x" -> pos[node][0] = v;
            case "y" -> pos[node][1] = v;
            case "z" -> pos[node][2] = v;
            case "scl_x" -> scl[node][0] = v;
            case "scl_y" -> scl[node][1] = v;
            case "scl_z" -> scl[node][2] = v;
            case "pitch" -> rot[node][0] = v;
            case "roll" -> rot[node][1] = v;
            case "yaw" -> rot[node][2] = v;
            case "visible" -> hidden[node] = v < 0.5f;
            default -> {
              if (id.startsWith("Model.mat0.")) {
                modelProps.computeIfAbsent(node, k -> new HashMap<>()).put(id.substring(11), v);
              } else if (id.startsWith("MaterialEffect.mat.")) {
                assetProps.computeIfAbsent(node, k -> new HashMap<>()).put(id.substring(19), v);
              }
            }
          }
        }
        for (FxTimeline.Event ev : e.getValue().events()) {
          if (ev.effect() != null && tt >= ev.time()) {
            spawns.add(new float[] {node, tt - ev.time()});
            spawnPaths.add(ev.effect());
          }
        }
      }
    }
    // world matrices
    float[][] world = new float[n][];
    for (int i = 0; i < n; i++) {
      Node nd = nodes.get(i);
      float[] local = trs(pos[i], rot[i], scl[i]);
      float[] base = nd.parent() < 0 ? parent : world[nd.parent()];
      if (nd.bone() != null && bones != null) {
        float[] b = bones.bone(nd.bone());
        if (b != null) {
          // the node follows the bone's position, keeping its parents' turn and scale (INFERRED:
          // the battle's attachments set IgnoreBoneScale, most IgnoreBoneRotation); the bones
          // are given in the space the prefab is placed in
          base = base.clone();
          base[12] = b[12];
          base[13] = b[13];
          base[14] = b[14];
        }
      }
      world[i] = mul(base, local);
      if (nd.parent() >= 0 && hidden[nd.parent()]) hidden[i] = true;
    }
    for (Model m : models) {
      if (hidden[m.node()] || m.minDeviceCap() > DEVICE_CAP) continue;
      FxMaterial over = null;
      if (!m.overrides().isEmpty()) {
        over = library.material(m.overrides().values().iterator().next());
        Map<String, Float> props = modelProps.get(m.node());
        if (over != null && props != null) {
          over = over.animated();
          for (var e : props.entrySet()) over.set(e.getKey(), e.getValue());
        }
      }
      FxMaterial asset = null;
      if (!m.assets().isEmpty()) {
        asset = library.material(m.assets().get(0));
        Map<String, Float> props = assetProps.get(m.node());
        if (asset != null && props != null) {
          asset = asset.animated();
          for (var e : props.entrySet()) asset.set(e.getKey(), e.getValue());
        }
      }
      items.add(new Item(m, world[m.node()], over, asset, path));
    }
    for (Effect e : effects) {
      if (hidden[e.node()]) continue;
      FxMaterial mat = library.material(e.material());
      if (mat == null) continue;
      Map<String, Float> props = assetProps.get(e.node());
      if (props != null) {
        mat = mat.animated();
        for (var p : props.entrySet()) mat.set(p.getKey(), p.getValue());
      }
      glows.add(new Glow(e.geometries(), mat));
    }
    for (Child c : children) {
      if (hidden[c.node()]) continue;
      FxPrefab sub = library.prefab(c.prefab());
      if (sub != null) sub.evaluate(t, world[c.node()], bones, items, glows, depth + 1);
    }
    for (int i = 0; i < spawns.size(); i++) {
      FxPrefab sub = library.prefab(spawnPaths.get(i));
      if (sub == null) continue;
      float st = spawns.get(i)[1];
      float d = sub.duration();
      if (d > 0 && st > d) continue;
      sub.evaluate(st, world[(int) spawns.get(i)[0]], bones, items, glows, depth + 1);
    }
  }

  // ------------------------------------------------------------------------------------ matrices

  static float[] identity() {
    return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  static float[] mul(float[] a, float[] b) {
    float[] out = new float[16];
    Rig.mul(a, 0, b, 0, out);
    return out;
  }

  /** {@code T · Rz · Ry · Rx · S} of a node (the extractor's Euler reading), column-major. */
  public static float[] trs(float[] p, float[] rDeg, float[] s) {
    double x = Math.toRadians(rDeg[0]), y = Math.toRadians(rDeg[1]), z = Math.toRadians(rDeg[2]);
    float cx = (float) Math.cos(x), sx = (float) Math.sin(x);
    float cy = (float) Math.cos(y), sy = (float) Math.sin(y);
    float cz = (float) Math.cos(z), sz = (float) Math.sin(z);
    // R = Rz Ry Rx, rows
    float r00 = cz * cy, r01 = cz * sy * sx - sz * cx, r02 = cz * sy * cx + sz * sx;
    float r10 = sz * cy, r11 = sz * sy * sx + cz * cx, r12 = sz * sy * cx - cz * sx;
    float r20 = -sy, r21 = cy * sx, r22 = cy * cx;
    return new float[] {
      r00 * s[0],
      r10 * s[0],
      r20 * s[0],
      0,
      r01 * s[1],
      r11 * s[1],
      r21 * s[1],
      0,
      r02 * s[2],
      r12 * s[2],
      r22 * s[2],
      0,
      p[0],
      p[1],
      p[2],
      1
    };
  }
}
