/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.action.ActionHolder;
import org.crforge.core.battle.action.ActionInstance;
import org.crforge.core.battle.action.RowAction;
import org.crforge.core.battle.data.GameAction;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.battle.unit.WorldEntity;
import org.crforge.realistic.bridge.EntityView;
import org.crforge.realistic.render.art.model.AnimPlayer;
import org.crforge.realistic.render.art.model.Asm;
import org.crforge.realistic.render.art.model.FxLibrary;
import org.crforge.realistic.render.art.model.FxMaterial;
import org.crforge.realistic.render.art.model.FxPrefab;
import org.crforge.realistic.render.art.model.FxRenderer;
import org.crforge.realistic.render.art.model.Glb;
import org.crforge.realistic.render.art.model.ModelCamera;
import org.crforge.realistic.render.art.model.ModelRenderer;
import org.crforge.realistic.render.art.model.Rig;
import org.crforge.realistic.render.art.model.Sc3dLibrary;

/**
 * The 3D projectiles and effects of the realistic view (docs/realistic-rendering.md §10.4).
 *
 * <ul>
 *   <li>A projectile whose row names a 3D prefab ({@code PrefabAsset}, drawn when the row is one of
 *       {@code --sc3d-rows}) is drawn as that prefab: its models, its state machine's default state
 *       from launch, its timeline (a boulder's roll, a spear's tilt), its nested effect prefabs,
 *       turned along its flight, raised to its height and tilted along its arc.
 *   <li>An effect row ({@code ActionPlayEffect} with a {@code PrefabAsset}) plays on the unit that
 *       runs it while that unit is drawn in 3D: its effect models in the unit's image, its material
 *       assets on the unit's named geometries (a glow), its timeline from its start. A row that
 *       keeps a run ({@code Looping} / {@code LinkLifeToActionLife}) plays while the core lists the
 *       run, at most its {@code OverrideDuration}; a delayed one starts when the core's queue says;
 *       a row the core starts and drops at once is started by the spawn its group makes beside it
 *       ({@link #TRIGGERS}, INFERRED from the group).
 * </ul>
 */
public final class ModelEffects {

  private static final float TICK_SECONDS = 0.05f;

  /**
   * Effect rows the core runs and drops in one go, by the spawn row their group runs beside them
   * (DATA: {@code ElectroGiant_EV1_Pulse_Group}'s sub-actions).
   */
  static final Map<String, String> TRIGGERS =
      Map.of("ElectroGiant_EV1_Spawn_Pulse_AEO", "ElectroGiant_EV1_Play_Pulse_Effect");

  private final ModelRenderer models;
  private final FxLibrary fx;
  private final FxRenderer renderer;
  private GameTables tables;

  /** An effect playing on a unit: its row, prefab, start and end (seconds). */
  record Playing(String row, FxPrefab prefab, float start, float end, boolean loop) {}

  /** The effects playing on each unit, by entity id (written by the observer's tick). */
  private final Map<Integer, Map<String, Playing>> playing = new HashMap<>();

  /** The tick each queued effect row is due, by entity id and row. */
  private final Map<Integer, Map<String, Integer>> due = new HashMap<>();

  /** Each 3D projectile's launch time (seconds), state machine and last arc point. */
  private static final class Flight {
    float start = -1;
    AnimPlayer[] players;
    float lastD = Float.NaN;
    float lastZ;
    float pitch;
  }

  private final Map<Integer, Flight> flights = new HashMap<>();
  private final Map<java.nio.file.Path, Rig> rigs = new HashMap<>();

  ModelEffects(ModelRenderer models) {
    this.models = models;
    this.fx = new FxLibrary(models.library());
    this.renderer = new FxRenderer(fx);
  }

  void setTables(GameTables tables) {
    this.tables = tables;
  }

  Sc3dLibrary library() {
    return models.library();
  }

  // ------------------------------------------------------------------------------- the core side

  /** Whether the row is an effect row drawn in 3D (in the rows and with an effect prefab). */
  private boolean isEffectRow(String row) {
    if (!models.library().drawsRow(row)) return false;
    FxPrefab p = fx.prefabOfRow(row);
    return p != null && !p.isEmpty();
  }

  /**
   * After each tick: the effect rows running or queued on each present unit (the core's action
   * holders, read only).
   */
  void afterTick(int tick, List<WorldEntity> present) {
    float now = tick * TICK_SECONDS;
    Set<Integer> alive = new HashSet<>();
    for (WorldEntity w : present) {
      alive.add(w.getId());
      ActionHolder h = w.madeActionHolder();
      Map<String, Playing> on = playing.get(w.getId());
      Set<String> running = new HashSet<>();
      if (h != null) {
        for (ActionInstance inst : h.running()) {
          if (inst.getAction() instanceof RowAction r && isEffectRow(r.name())) {
            running.add(r.name());
          }
        }
        for (ActionHolder.Queued q : h.queued()) {
          if (q.action() instanceof RowAction r && isEffectRow(r.name())) {
            due.computeIfAbsent(w.getId(), k -> new HashMap<>())
                .put(r.name(), tick + Math.max(0, q.ticks()));
          }
        }
      }
      for (String row : running) {
        if (on == null) on = playing.computeIfAbsent(w.getId(), k -> new HashMap<>());
        Playing p = on.get(row);
        if (p == null || p.end() < now) start(w.getId(), row, now, true);
      }
      // a queued one-shot whose time came
      Map<String, Integer> d = due.get(w.getId());
      if (d != null) {
        for (var it = d.entrySet().iterator(); it.hasNext(); ) {
          var e = it.next();
          if (e.getValue() <= tick && !running.contains(e.getKey())) {
            start(w.getId(), e.getKey(), e.getValue() * TICK_SECONDS, false);
            it.remove();
          }
        }
      }
      // a lasting one the core no longer runs ends now
      if (on != null) {
        for (var it = on.entrySet().iterator(); it.hasNext(); ) {
          var e = it.next();
          Playing p = e.getValue();
          if (lasting(p.row()) && !running.contains(p.row()) && p.start() < now) {
            it.remove();
          } else if (p.end() < now) {
            it.remove();
          }
        }
      }
    }
    playing.keySet().retainAll(alive);
    due.keySet().retainAll(alive);
  }

  /** A spawn row ran on a unit: the one-shot effect its group plays beside it starts. */
  void spawned(int tick, int ownerId, String action) {
    String row = TRIGGERS.get(action);
    if (row != null && isEffectRow(row)) start(ownerId, row, tick * TICK_SECONDS, false);
  }

  private boolean lasting(String row) {
    GameAction a = action(row);
    if (a == null) return false;
    String flags = a.fields().path("EffectFlags").asText("");
    return flags.contains("Looping") || flags.contains("LinkLifeToActionLife");
  }

  private GameAction action(String row) {
    if (tables == null) return null;
    try {
      return tables.action(row);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void start(int id, String row, float now, boolean lasting) {
    FxPrefab p = fx.prefabOfRow(row);
    if (p == null) return;
    float end;
    GameAction a = action(row);
    int override = a == null ? 0 : a.fields().path("OverrideDuration").asInt(0);
    if (override > 0) {
      end = now + override / 1000f;
    } else if (lasting) {
      end = Float.POSITIVE_INFINITY;
    } else {
      float d = p.duration();
      end = now + (d > 0 && d < Float.POSITIVE_INFINITY ? d : 1f);
    }
    String flags = a == null ? "" : a.fields().path("EffectFlags").asText("");
    playing
        .computeIfAbsent(id, k -> new HashMap<>())
        .put(row, new Playing(row, p, now, end, flags.contains("Looping")));
  }

  // ------------------------------------------------------------------------------ the draw side

  /**
   * Puts the effects playing on a 3D unit into its image: their effect models (not turned with the
   * unit: INFERRED, the battle's auras and glow planes are authored for the camera) and their glows
   * on its geometries, bone attachments at the unit's posed bones.
   */
  void attach(ModelRenderer.Instance in, int id, float seconds, float clock) {
    Map<String, Playing> on = playing.get(id);
    if (on == null || on.isEmpty()) return;
    List<FxPrefab.Item> items = new ArrayList<>();
    List<FxPrefab.Glow> glows = new ArrayList<>();
    FxPrefab.Bones bones = bones(in);
    for (Playing p : on.values()) {
      float t = seconds - p.start();
      if (t < 0 || seconds > p.end()) continue;
      // a Looping effect plays its timeline again each time it ends (INFERRED from the flag)
      float d = p.prefab().duration();
      if (p.loop() && d > 0 && d < Float.POSITIVE_INFINITY) t %= d;
      p.prefab().evaluate(t, identity(), bones, items, glows, 0);
    }
    List<FxRenderer.Draw> draws = new ArrayList<>();
    for (FxPrefab.Item it : items) addDraw(draws, it, null);
    if (draws.isEmpty() && glows.isEmpty()) return;
    in.extras = renderer.extras(draws, Map.of(), glows, clock);
  }

  /** The unit's bones in its image's space (its first model's rig at its posed sample). */
  private FxPrefab.Bones bones(ModelRenderer.Instance in) {
    return name -> {
      List<ModelRenderer.Part> parts = partsOf(in);
      if (parts == null || parts.isEmpty()) return null;
      ModelRenderer.Part part = parts.get(0);
      Glb glb = models.library().glb(part.ref().glb());
      int node = -1;
      for (int i = 0; i < glb.nodes().size(); i++) {
        if (glb.nodes().get(i).name().equals(name)) {
          node = i;
          break;
        }
      }
      if (node < 0) return null;
      Rig rig = rigs.computeIfAbsent(part.ref().glb(), f -> new Rig(glb));
      AnimPlayer.Sample sm = part.sample();
      Glb.Clip clip = sm == null || sm.clip() == null ? null : glb.clips().get(sm.clip());
      Glb.Clip from = sm == null || sm.fromClip() == null ? null : glb.clips().get(sm.fromClip());
      rig.pose(
          clip,
          sm == null ? 0 : sm.seconds(),
          from,
          sm == null ? 0 : sm.fromSeconds(),
          sm == null ? 1 : sm.blend());
      float[] m = new float[16];
      mul(turn(headingOf(in)), part.ref().transform(), m);
      float[] out = new float[16];
      mul(m, rig.nodeMatrix(node), out);
      return out;
    };
  }

  private static List<ModelRenderer.Part> partsOf(ModelRenderer.Instance in) {
    return in.parts();
  }

  private static float headingOf(ModelRenderer.Instance in) {
    return in.heading();
  }

  private void addDraw(List<FxRenderer.Draw> out, FxPrefab.Item it, float[] pre) {
    FxPrefab.Model m = it.model();
    if (m.glb() == null || it.override() == null) return;
    if ("uber_pbr".equals(it.override().shader())) return; // a lit override: not an effect
    float[] mtx = it.matrix();
    if (pre != null) {
      float[] o = new float[16];
      mul(pre, mtx, o);
      mtx = o;
    }
    out.add(new FxRenderer.Draw(m.glb(), mtx, it.override(), m.layer()));
  }

  /**
   * The 3D instance of a projectile drawn with its prefab, or null when it is not (no prefab in the
   * rows, or no model).
   *
   * @param x screen x of the projectile (world units)
   * @param y screen y of its ground point
   * @param height its height (world units up the screen, the 2D projection's)
   * @param fx its flight along the width ({@code view} oriented, game units), {@code fy} up the
   *     screen; zero for none
   * @param travelled game units from its start along the ground, for its arc's slope
   * @param z its height in game units
   */
  ModelRenderer.Instance projectile(
      EntityView e,
      float x,
      float y,
      float height,
      float fx0,
      float fy0,
      float travelled,
      float z,
      float pixelsPerDisplayUnit,
      boolean blue,
      float seconds,
      float clock) {
    if (!models.library().drawsRow(e.name())) return null;
    FxPrefab prefab = fx.prefabOfRow(e.name());
    Sc3dLibrary.Prefab lib = models.library().prefabOf(e.name());
    if (prefab == null || lib == null) return null;
    Flight f = flights.computeIfAbsent(e.id(), k -> new Flight());
    if (f.start < 0) f.start = seconds;
    float t = Math.max(0f, seconds - f.start);
    // its arc's slope: nose up while it climbs (INFERRED: the game's 3D projectiles fly
    // nose-first along their flight)
    if (!Float.isNaN(f.lastD) && travelled - f.lastD > 1f) {
      f.pitch = (float) Math.atan2(z - f.lastZ, travelled - f.lastD);
    }
    f.lastD = travelled;
    f.lastZ = z;
    float unitsPerMetre = pixelsPerDisplayUnit * models.library().environment().duPerMetre();
    float liftMetres = height / (unitsPerMetre * ModelCamera.SIN);
    // the prefab raised to its height and tilted along its arc about its origin
    float[] place =
        FxPrefab.trs(
            new float[] {0, 0, liftMetres},
            new float[] {(float) -Math.toDegrees(f.pitch), 0, 0},
            new float[] {1, 1, 1});
    List<FxPrefab.Item> items = new ArrayList<>();
    List<FxPrefab.Glow> glows = new ArrayList<>();
    prefab.evaluate(t, place, null, items, glows, 0);
    List<ModelRenderer.Part> parts = new ArrayList<>();
    Map<Integer, FxMaterial> partGlows = new HashMap<>();
    List<FxRenderer.Draw> draws = new ArrayList<>();
    float heading =
        fx0 * fx0 + fy0 * fy0 > 1e-6f
            ? ModelCamera.headingRadians(fx0, fy0)
            : (blue ? 0f : (float) Math.PI);
    float[] turn = turn(heading);
    for (FxPrefab.Item it : items) {
      FxPrefab.Model m = it.model();
      if (m.glb() == null) continue;
      boolean effect = it.override() != null && !"uber_pbr".equals(it.override().shader());
      if (effect) {
        addDraw(draws, it, turn);
        continue;
      }
      if (f.players == null) f.players = new AnimPlayer[items.size()];
      int idx = parts.size();
      AnimPlayer.Sample sample = null;
      if (m.asm() != null && idx < f.players.length) {
        if (f.players[idx] == null) {
          Asm asm = models.library().asm(m.asm());
          if (asm != null) {
            Map<String, Float> lengths = new HashMap<>();
            for (Glb.Clip c : models.library().glb(m.glb()).clips().values()) {
              lengths.put(c.name(), c.length());
            }
            f.players[idx] = new AnimPlayer(asm, lengths, e.id());
          }
        }
        if (f.players[idx] != null) {
          f.players[idx].want(null, t); // the machine's default state, from launch
          sample = f.players[idx].sample(t);
        }
      }
      if (it.asset() != null) partGlows.put(idx, it.asset());
      parts.add(
          new ModelRenderer.Part(
              new Sc3dLibrary.ModelRef(m.glb(), it.matrix(), m.asm(), m.shadow()), sample));
    }
    if (parts.isEmpty()) return null;
    float[] mul = {1f, 1f, 1f, 1f};
    float[] add = {0f, 0f, 0f};
    ModelRenderer.Instance in =
        new ModelRenderer.Instance(lib, parts, x, y, 0f, unitsPerMetre, heading, blue, mul, add);
    if (!draws.isEmpty() || !partGlows.isEmpty() || !glows.isEmpty()) {
      in.extras = renderer.extras(draws, partGlows, glows, clock);
    }
    return in;
  }

  /** Forgets the projectiles and units no longer in the frame. */
  void retain(Set<Integer> ids) {
    flights.keySet().retainAll(ids);
  }

  // ------------------------------------------------------------------------------------ matrices

  private static float[] identity() {
    return new float[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  private static float[] turn(float heading) {
    float c = (float) Math.cos(heading);
    float s = (float) Math.sin(heading);
    return new float[] {c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  private static void mul(float[] a, float[] b, float[] out) {
    for (int c = 0; c < 4; c++) {
      for (int r = 0; r < 4; r++) {
        float v = 0;
        for (int k = 0; k < 4; k++) v += a[k * 4 + r] * b[c * 4 + k];
        out[c * 4 + r] = v;
      }
    }
  }

  void dispose() {
    renderer.dispose();
  }
}
