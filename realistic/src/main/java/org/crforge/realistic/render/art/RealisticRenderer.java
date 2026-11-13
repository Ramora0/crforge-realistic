/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.math.Affine2;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Disposable;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.crforge.core.battle.data.GameRow;
import org.crforge.core.battle.data.GameTables;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.realistic.bridge.BattleFrame;
import org.crforge.realistic.bridge.EntityView;
import org.crforge.realistic.bridge.UnitStatus;
import org.crforge.realistic.bridge.ViewOrientation;
import org.crforge.realistic.render.art.model.AnimPlayer;
import org.crforge.realistic.render.art.model.AnimStates;
import org.crforge.realistic.render.art.model.Asm;
import org.crforge.realistic.render.art.model.Glb;
import org.crforge.realistic.render.art.model.ModelCamera;
import org.crforge.realistic.render.art.model.ModelRenderer;
import org.crforge.realistic.render.art.model.Sc3dLibrary;

/**
 * Draws a battle frame with the game's own art from the {@link ArtCache}, following
 * docs/realistic-rendering.md: each troop, building, tower and projectile as the movie clip its
 * table row names ({@code FileName} with {@code BlueExportName} / {@code RedExportName}), the
 * animation from the unit's state (§1.3), attack clips lined up with the hit (§1.4), the facing
 * export from the logic heading (§2), towers as base, rider and top (§5), projectiles by their
 * flight (§6), silhouette shadows and the deploy filter (§8). What the cache lacks is skipped.
 */
public final class RealisticRenderer implements Disposable {

  private static final float TICK_SECONDS = 0.05f;
  private static final float SHADOW_ALPHA = 0.38f;
  private static final int LAYER_GROUND_SHADOW = -1;
  private static final int LAYER_GROUND = 0;
  private static final int LAYER_AIR = 1;

  /** The king tower's clip: frame 0 idle, 1..96 waking (3.2 s at 30 fps), 97 active. */
  private static final int KING_ACTIVE_FRAME = 97;

  private final ArtCache cache;
  private final PolygonSpriteBatch batch;
  private final ScDrawer drawer;
  private final VisualLookup lookup;
  private final Map<Long, AnimState> anims = new HashMap<>();
  private final Affine2 transform = new Affine2();
  private final Affine2 local = new Affine2();
  private final Color tint = new Color();
  private ArtProjection proj;
  private Map<Integer, int[]> attacks = Map.of();
  private Map<Integer, int[]> flights = Map.of();

  /** The tick each king tower's activating run started, by entity id. */
  private final Map<Integer, Integer> kingWoke = new HashMap<>();

  /**
   * Each character's motion ({@code BattleAdapter.unitMotion}: height, dash time, charge, dash
   * wind-up, landing hold) after the last tick and the tick before it, by entity id.
   */
  private Map<Integer, int[]> motions = Map.of();

  private Map<Integer, int[]> motionsBefore = Map.of();

  /**
   * Each attached rider's mount and its place among the mount's riders ({@code {mountId, index}}),
   * by rider id, after the last tick.
   */
  private Map<Integer, int[]> mounts = Map.of();

  /** The dashes a chained dash (the Golden Knight's) has made, by entity id. */
  private final Map<Integer, Integer> dashLinks = new HashMap<>();

  /** Tells the renderer when each king tower starts waking (its clip's activation frames). */
  public org.crforge.core.battle.unit.WorldObserver observer() {
    return new org.crforge.core.battle.unit.WorldObserver() {
      @Override
      public void activation(
          int tick,
          org.crforge.core.battle.unit.TowerEntity king,
          org.crforge.core.battle.unit.ActivationEvent event) {
        if (event.kind() == org.crforge.core.battle.unit.ActivationEvent.Kind.ACTIVATING_STARTED) {
          kingWoke.putIfAbsent(king.getId(), tick);
        }
      }

      @Override
      public void afterPostHooks(
          int tick,
          List<org.crforge.core.battle.unit.WorldEntity> present,
          List<org.crforge.core.battle.projectile.ProjectileEntity> projectiles) {
        Map<Integer, int[]> m = new HashMap<>();
        Map<Integer, int[]> r = new HashMap<>();
        for (var w : present) {
          if (w instanceof org.crforge.core.battle.unit.CharacterEntity c) {
            int[] v = org.crforge.realistic.bridge.BattleAdapter.unitMotion(c);
            if (v != null) m.put(c.getId(), v);
            var parent = c.getParent();
            if (parent != null)
              r.put(c.getId(), new int[] {parent.getId(), parent.riders().indexOf(c)});
          }
        }
        motionsBefore = motions;
        motions = m;
        mounts = r;
        if (models != null) {
          List<HeroForms.Seen> seen = new ArrayList<>();
          for (var w : present) {
            var v = w.getView();
            if (v == null || w.getData() == null) continue;
            seen.add(
                new HeroForms.Seen(
                    w.getId(),
                    w.getData().name(),
                    w.side(),
                    v.getX(),
                    v.getY(),
                    v.getState(),
                    false));
          }
          for (var p : projectiles) {
            seen.add(
                new HeroForms.Seen(
                    p.getId(), p.getData().name(), p.getSide(), p.getX(), p.getY(), -1, true));
          }
          heroForms.observe(tick, seen);
        }
        if (modelEffects != null) modelEffects.afterTick(tick, present);
      }

      @Override
      public void areaEffectSpawned(
          int tick,
          org.crforge.core.battle.spawn.SpawnHost owner,
          String action,
          int phase,
          org.crforge.core.battle.spawn.SpawnHost source,
          org.crforge.core.battle.unit.AreaEffectEntity areaEffect) {
        if (modelEffects != null && owner instanceof org.crforge.core.battle.unit.WorldEntity w) {
          modelEffects.spawned(tick, w.getId(), action);
        }
      }

      @Override
      public void chainDashed(
          int tick,
          org.crforge.core.battle.unit.CharacterEntity unit,
          org.crforge.core.battle.unit.WorldEntity next,
          int count,
          int x,
          int y) {
        if (unit != null) dashLinks.put(unit.getId(), count);
      }

      @Override
      public void chainDashEnded(
          int tick,
          org.crforge.core.battle.unit.CharacterEntity unit,
          int count,
          org.crforge.core.pathfinding.target.TargetView reference) {
        if (unit != null) dashLinks.remove(unit.getId());
      }
    };
  }

  /**
   * A ground character's height above the ground this frame, game units: the core's live height (a
   * dash's height profile, a river jump's arc) interpolated between the last two ticks.
   */
  public float groundHeight(int id, float tickAlpha) {
    int[] now = motions.get(id);
    if (now == null) return 0f;
    int[] before = motionsBefore.get(id);
    float z0 = before == null ? now[0] : before[0];
    return Math.max(0f, z0 + (now[0] - z0) * tickAlpha);
  }

  private final Color shadowColor = new Color(0f, 0f, 100 / 255f, 0.5f);
  private EffectLayer effects;

  /**
   * The effect layer whose damage flashes ({@code filter_damage}) the units show, and whose Object
   * layer on the ground the units sort among.
   */
  public void setEffects(EffectLayer effects) {
    this.effects = effects;
    if (effects != null) effects.sortObjectsWithUnits();
  }

  private float effectClock;

  /** The effect layer's clock this frame (seconds), for the damage flashes. */
  public void setEffectClock(float seconds) {
    this.effectClock = seconds;
  }

  /**
   * The arena's shadow colour: the location row's {@code ShadowR/G/B} (0..255, absent = 0) and
   * {@code ShadowA} (percent).
   */
  public void setShadowColor(int r, int g, int b, int alphaPercent) {
    shadowColor.set(r / 255f, g / 255f, b / 255f, Math.max(0, Math.min(100, alphaPercent)) / 100f);
  }

  public RealisticRenderer(ArtCache cache) {
    this(cache, new PolygonSpriteBatch(8000));
  }

  /**
   * A renderer drawing through {@code batch} (which it owns and disposes): the phone server passes
   * a batch that records the draws as well as, or instead of, rasterizing them.
   */
  public RealisticRenderer(ArtCache cache, PolygonSpriteBatch batch) {
    this.cache = cache;
    this.batch = batch;
    this.drawer = new ScDrawer(batch, cache);
    this.lookup = new VisualLookup(cache);
    this.models = Sc3dLibrary.locate(cache.folder()).map(ModelRenderer::new).orElse(null);
    this.modelEffects = models == null ? null : new ModelEffects(models);
  }

  /** The 3D projectiles and effects (docs/realistic-rendering.md §10.4), with the models. */
  private final ModelEffects modelEffects;

  // ------------------------------------------------------------------------------- 3D models

  /** The 3D models (docs/realistic-rendering.md §10), or null when none are configured. */
  private final ModelRenderer models;

  /** Each 3D unit's animation: its row, prefab, state machine players, swap time, forced state. */
  private static final class ModelState {
    String row;
    Sc3dLibrary.Prefab prefab;
    AnimPlayer[] players;
    List<HeroForms.PartSpec> parts = List.of();
    boolean deployed;
    boolean redeployed;
    float fadeFrom = -1;
    float queenSeen = -1;

    /** Where it was last drawn (its pose for a Death clip after the core removes it). */
    float lastX;

    float lastY;
    float lastLift;
    float lastShadowLift = Float.NaN;
    float lastHeading;
    boolean lastBlue;
    int lastLayer;

    /** When its Death clip started (the first frame without it), or -1. */
    float diedAt = -1;
  }

  private final Map<Integer, ModelState> modelStates = new HashMap<>();

  /** The ground troops' ability states (docs §10.2) and this frame's Hero Giant slaps. */
  private final GroundTroopStates troopStates = new GroundTroopStates();

  private Map<Integer, Integer> slapsNow = Map.of();

  /** The 3D heroes' forms, mounts, warps and rerolls (§10.3). */
  private final HeroForms heroForms = new HeroForms();

  private Matrix4 projection;

  /**
   * Whether 3D models are drawn this frame. The phone server's draw lists turn them off while it
   * records (docs/phone-server.md): a model reaches the batch as an image rendered into one shared
   * frame buffer each frame, which a draw list could only carry as a new texture every frame, so
   * the 3D rows fall back to their 2D stand-in there. The models' state (forms, effects) is still
   * tracked from the core's ticks.
   */
  private boolean drawModels = true;

  /** Turns the 3D models on or off for the frames drawn from now (on by default). */
  public void setDrawModels(boolean on) {
    drawModels = on;
  }

  /**
   * The 3D models' renderer, or null without an extraction: the phone server sends its draws to the
   * page instead ({@link ModelRenderer#setRemote}).
   */
  public ModelRenderer models() {
    return models;
  }

  /** Whether 3D models are configured and drawn now. */
  private boolean modelsShown() {
    return models != null && drawModels;
  }

  /** Whether the row is drawn as a 3D model (its prefab's models, or nothing for a dummy). */
  public boolean drawsModel(String row) {
    return modelsShown() && prefabFor(row) != null;
  }

  /** The row's 3D prefab: by the index's list, else by its own {@code PrefabAsset}; or null. */
  private Sc3dLibrary.Prefab prefabFor(String row) {
    return models.library().prefabOf(row, text(lookup.unitRow(row), "PrefabAsset"));
  }

  /**
   * A 3D unit's height off the ground this frame, game units, as its model is lifted: the core's
   * live height (a flier's flying height, a leap), else its row's {@code FlyingHeight} for a flier.
   */
  public float modelHeight(EntityView e, float tickAlpha) {
    float h = groundHeight(e.id(), tickAlpha);
    if (e.air() && h <= 0f) h = integer(lookup.unitRow(e.name()), "FlyingHeight", 0);
    return h;
  }

  /** Whether a 3D unit is not drawn now (the Barbarian Barrel hero rolling in its barrel). */
  public boolean modelHidden(int id) {
    return modelsShown() && heroForms.rolling(id);
  }

  /**
   * A 3D row's health bar pivot ({@code hudoffset}) as display units up the screen from its ground
   * point, or NaN for a row drawn in 2D.
   */
  public float modelHudHeight(String row) {
    if (!modelsShown()) return Float.NaN;
    Sc3dLibrary.Prefab p = prefabFor(row);
    if (p == null) return Float.NaN;
    return p.hudOffset() * ModelCamera.SIN * models.library().environment().duPerMetre();
  }

  /**
   * Display units per pixel of the 3D stage ({@code VANILLA_3D_PPM} pixels per metre against the
   * display units per metre): what a 3D row's {@code HealthBarOffsetY} is multiplied by.
   */
  public float modelOffsetScale() {
    if (models == null) return 1f;
    Sc3dLibrary.Environment env = models.library().environment();
    return 0.5f * env.duPerMetre() / ModelCamera.DU_PER_METRE;
  }

  private final ModelRenderer.Compositor compositor =
      new ModelRenderer.Compositor() {
        @Override
        public void image(
            com.badlogic.gdx.graphics.Texture texture,
            float x,
            float y,
            float w,
            float h,
            float u,
            float v,
            float u2,
            float v2,
            Color tint,
            com.badlogic.gdx.graphics.glutils.ShaderProgram shader,
            float offsetU,
            float offsetV) {
          drawer.drawImage(texture, x, y, w, h, u, v, u2, v2, tint, shader, offsetU, offsetV);
        }

        @Override
        public void pause() {
          drawer.end();
        }

        @Override
        public void resume() {
          drawer.begin();
        }
      };

  public ArtCache cache() {
    return cache;
  }

  public ScDrawer drawer() {
    return drawer;
  }

  /**
   * The per-tick state the view does not carry: attack timers ({@code {attackTimerMs,
   * attackSequenceIndex, loadTimerMs}}) and projectile flights ({@code {z, startX, startY}}), by
   * entity id (see {@code BattleAdapter.attackTimers} / {@code projectileFlights}).
   */
  public void setTickData(Map<Integer, int[]> attacks, Map<Integer, int[]> flights) {
    this.attacks = attacks == null ? Map.of() : attacks;
    this.flights = flights == null ? Map.of() : flights;
  }

  private static final class AnimState {
    String action = "";
    float actionStart;
    float facingDegrees;
    float deployStart = -1;
    float activatedAt = -1;
    int heldFrame;
    int state = -1;
    float stateStart;
    float jumpEnd = -1;
  }

  /** One clip to draw this frame. */
  private record Pose(
      ScScene scene,
      int id,
      int frame,
      float x,
      float groundY,
      float lift,
      float scale,
      boolean flip,
      float rotation,
      float[] filter,
      float alpha,
      VisualLookup.Unit shadowOf,
      List<ScDrawer.Override> overrides,
      float sortY,
      int layer,
      ModelRenderer.Instance model) {

    Pose(
        ScScene scene,
        int id,
        int frame,
        float x,
        float groundY,
        float lift,
        float scale,
        boolean flip,
        float rotation,
        float[] filter,
        float alpha,
        VisualLookup.Unit shadowOf,
        List<ScDrawer.Override> overrides,
        float sortY,
        int layer) {
      this(
          scene, id, frame, x, groundY, lift, scale, flip, rotation, filter, alpha, shadowOf,
          overrides, sortY, layer, null);
    }
  }

  /**
   * Draws the entities of {@code frame}, positions interpolated from {@code previous} (may be null)
   * by {@code alpha} in [0, 1].
   */
  public void render(
      BattleFrame frame,
      BattleFrame previous,
      float alpha,
      GameTables tables,
      ViewOrientation view,
      ArtProjection proj,
      Matrix4 projection) {
    this.proj = proj;
    this.projection = projection;
    lookup.setTables(tables);
    if (modelEffects != null) modelEffects.setTables(tables);
    float seconds =
        (previous == null
                ? frame.tick()
                : previous.tick() + (frame.tick() - previous.tick()) * alpha)
            * TICK_SECONDS;
    Map<Integer, EntityView> before = new HashMap<>();
    if (previous != null) for (EntityView e : previous.entities()) before.put(e.id(), e);

    Map<Integer, EntityView> now = new HashMap<>();
    for (EntityView e : frame.entities()) now.put(e.id(), e);
    if (models != null) slapsNow = troopStates.slaps(frame.entities());
    List<Pose> poses = new ArrayList<>();
    for (EntityView e : frame.entities()) {
      // a rider sits on its mount: drawn as part of it (mountRiders)
      int[] mount = mounts.get(e.id());
      if (mount != null && now.containsKey(mount[0])) continue;
      EntityView p = before.get(e.id());
      float gx = p == null ? e.x() : p.x() + (e.x() - p.x()) * alpha;
      float gy = p == null ? e.y() : p.y() + (e.y() - p.y()) * alpha;
      float sx = proj.x(view, gx);
      float sy = proj.y(view, gy);
      switch (e.kind()) {
        case PROJECTILE -> poseProjectile(poses, e, p, alpha, sx, sy, view);
        case AREA_EFFECT -> {} // drawn by the EffectLayer
        default -> {
          int n = poses.size();
          poseCharacter(poses, e, sx, sy, seconds, alpha, view);
          mountRiders(poses, n, e, now, sx, sy, seconds, alpha, view);
        }
      }
    }
    if (modelsShown()) poseDeaths(poses, now, seconds);
    poses.sort(
        Comparator.comparingInt(Pose::layer)
            .thenComparing(Comparator.comparingDouble((Pose q) -> -q.sortY())));

    batch.setProjectionMatrix(projection);
    drawer.begin();
    drawer.setSilhouette(true);
    for (Pose q : poses) {
      if (q.model() != null) {
        models.drawShadow(q.model(), projection, shadowColor, compositor);
      } else if (q.shadowOf() != null) {
        drawShadow(q);
      }
    }
    drawer.setSilhouette(false);
    // the effects' Object layer on the ground goes y-sorted among the ground poses (§7)
    float band = effects == null ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
    for (Pose q : poses) {
      if (band != Float.NEGATIVE_INFINITY && q.layer() >= LAYER_GROUND) {
        float to = q.layer() == LAYER_GROUND ? q.sortY() : Float.NEGATIVE_INFINITY;
        objectEffects(to, band, view);
        band = to;
      }
      drawPose(q);
    }
    if (band != Float.NEGATIVE_INFINITY) objectEffects(Float.NEGATIVE_INFINITY, band, view);
    drawer.end();
    if (anims.size() > 4 * frame.entities().size() + 64) {
      Set<Long> live = new HashSet<>();
      for (EntityView e : frame.entities()) {
        for (long k = 0; k < 4; k++) live.add(e.id() * 4L + k);
      }
      anims.keySet().retainAll(live);
    }
    if (modelStates.size() > frame.entities().size() + 64) {
      Set<Integer> live = new HashSet<>();
      for (EntityView e : frame.entities()) live.add(e.id());
      modelStates.keySet().retainAll(live);
      troopStates.retain(live);
    }
    if (modelEffects != null) {
      Set<Integer> live = new HashSet<>();
      for (EntityView e : frame.entities()) live.add(e.id());
      modelEffects.retain(live);
    }
  }

  /** The effects' ground Object-layer parts whose screen y lies in (lo, hi]. */
  private void objectEffects(float lo, float hi, ViewOrientation view) {
    if (effects.drawObjects(drawer, proj, view, effectClock, lo, hi)) drawer.setSilhouette(false);
  }

  // ---------------------------------------------------------------------------------------- units

  private void poseCharacter(
      List<Pose> out,
      EntityView e,
      float x,
      float y,
      float seconds,
      float tickAlpha,
      ViewOrientation view) {
    int state = e.state();
    // a troop the Fisherman's hook pulls (FOLLOWING_REMOVED) or a Fisherman it drags to a building
    // (FOLLOWING_REMOVED_BUILDING) is still on the arena, drawn where the hook has it
    if (state == GridEntityState.WAITING_TO_DEPLOY) return;
    boolean blue = view.blue(e.side());
    if (modelsShown()) {
      Sc3dLibrary.Prefab prefab = prefabFor(e.name());
      if (prefab != null) {
        poseModel(out, e, prefab, x, y, seconds, tickAlpha, view);
        return;
      }
    }
    VisualLookup.Unit unit = lookup.unit(e.name(), blue);
    if (unit == null) return;
    // a crown tower at 0 hit points is already its ruin (the effect layer draws it)
    if (e.kind() == EntityView.Kind.TOWER && e.maxHitPoints() > 0 && e.hitPoints() <= 0) return;
    float alpha = e.hidden() ? 0.35f : 1f;
    float scale = proj.pixelsPerDisplayUnit() * unit.scale;
    // a ground unit off the ground (a dash's leap, a jump over the river) is lifted by the core's
    // height and drawn over the ground units it passes; its shadow stays on the ground
    float height = e.air() ? 0f : groundHeight(e.id(), tickAlpha);
    float lift = e.air() ? proj.height(unit.flyingHeight) : proj.height(height);
    int layer = e.air() || height > 0 ? LAYER_AIR : LAYER_GROUND;
    AnimState st =
        anims.computeIfAbsent(
            e.id() * 4L,
            k -> {
              AnimState s = new AnimState();
              s.facingDegrees = blue ? 0f : 180f; // towards the enemy until the logic turns it
              return s;
            });
    updateFacing(st, e, view);

    if (unit.isTowerLike()) {
      ScScene.Clip base = unit.scene.clip(unit.baseExport);
      List<ScDrawer.Override> overrides = null;
      int f = 0;
      boolean flip = false;
      int turret = base == null ? -1 : base.childNamed("turret");
      int kingDummy = base == null ? -1 : base.childNamed("king_dummy");
      if (turret >= 0 || kingDummy >= 0) {
        // the king tower: the clip's timeline is its waking (from the core's activating run),
        // the turret's its aim
        Integer woke = kingWoke.get(e.id());
        if (woke != null && st.activatedAt < 0) st.activatedAt = woke * TICK_SECONDS;
        if (st.activatedAt >= 0) {
          f = Math.min(KING_ACTIVE_FRAME, 1 + (int) ((seconds - st.activatedAt) * base.fps()));
        }
        overrides = new ArrayList<>();
        if (turret >= 0) {
          int n = unit.scene.frameCount(base.children()[turret]);
          int[] rf = halfCircleFrame(st.facingDegrees, n);
          overrides.add(new ScDrawer.Override("turret", null, -1, rf[0], rf[1] == 1));
        }
        if (kingDummy >= 0) {
          ScScene kings = cache.scene("sc/chr_king.sc");
          int king = kings == null ? -1 : kings.export(blue ? "King_blue" : "King_red");
          if (king >= 0) overrides.add(new ScDrawer.Override("king_dummy", kings, king, f, false));
        }
      } else if (unit.rotationOnTimeline && unit.rotationFrames > 1) {
        int[] rf = halfCircleFrame(st.facingDegrees, unit.rotationFrames);
        f = rf[0];
        flip = rf[1] == 1;
      } else if (base != null) {
        f = (int) Math.floor(seconds * base.fps());
      }
      out.add(
          new Pose(
              unit.scene,
              unit.baseExport,
              f,
              x,
              y,
              lift,
              scale,
              flip,
              0,
              null,
              alpha,
              unit,
              overrides,
              y,
              layer));
      if (unit.attached != null) {
        float riderLift = lift + proj.height(unit.attachedHeight);
        AnimState rs = anims.computeIfAbsent(e.id() * 4L + 1, k -> new AnimState());
        rs.facingDegrees = st.facingDegrees;
        poseAnimated(
            out,
            e,
            unit.attached,
            rs,
            x,
            y,
            riderLift,
            seconds,
            tickAlpha,
            alpha,
            null,
            y - 0.01f,
            layer);
      }
      if (unit.topExport >= 0) {
        out.add(
            new Pose(
                unit.scene,
                unit.topExport,
                0,
                x,
                y,
                lift,
                scale,
                false,
                0,
                null,
                alpha,
                null,
                null,
                y - 0.02f,
                layer));
      }
      return;
    }
    // a hidden unit (underground, invisible) casts no shadow
    poseAnimated(
        out,
        e,
        unit,
        st,
        x,
        y,
        lift,
        seconds,
        tickAlpha,
        alpha,
        e.hidden() ? null : unit,
        y,
        layer);
  }

  /**
   * The forced animations ({@code ActionRunForcedAnimationOnce}, whose run the core leaves to the
   * client) of the Hero Tombstone, told from what the core does show: row swaps ({@code
   * ActionChangeGameObjectData}). The {@code CustomStateNumber}s are DATA; reading them off the
   * swaps is INFERRED.
   *
   * <ul>
   *   <li>The visual turning into the broken tomb ({@code TombstoneHero_visual_dummy_break_tomb}:
   *       the tombstone died) plays {@code Tombstone_hero_play_break_animation}, 1.
   *   <li>The passive Tomb Queen turning active ({@code Tombstone_hero_SetData}, the ability) plays
   *       {@code Tombstone_hero_play_skeletonqueen_deploy_animation}, 1; then its side's tomb
   *       visual ({@code TombstoneHero_visual_dummy_start_monster_spawn_group}, broken or not)
   *       plays {@code Tombstone_hero_play_scatter_animation_dummy}, 2, and fades.
   * </ul>
   */
  static int forcedOnSwap(String row) {
    return switch (row) {
      case "TombstoneHero_broken_visual_dummy", QUEEN_ACTIVE -> 1;
      default -> 0;
    };
  }

  static final String QUEEN_ACTIVE = "TombstoneHero_Monster_Active";

  static boolean isTombVisual(String row) {
    return row.equals("TombstoneHero_visual_dummy")
        || row.equals("TombstoneHero_broken_visual_dummy");
  }

  /** When each side's Tomb Queen rose (its swap to {@link #QUEEN_ACTIVE}), seconds. */
  private final Map<Integer, Float> queenRose = new HashMap<>();

  /**
   * Seconds after the swap to the broken tomb that the scatter path fades it ({@code
   * Tombstone_hero_fade_out_filter}: {@code filter_fadeout} at 1000 ms for {@code OverrideDuration}
   * 1500 ms; DATA) and removes it (1800 ms, the core's kill).
   */
  static final float SCATTER_FADE_START = 1.0f;

  static final float SCATTER_FADE_LENGTH = 1.5f;

  /** Poses a unit drawn by its 3D prefab (docs/realistic-rendering.md §10). */
  private void poseModel(
      List<Pose> out,
      EntityView e,
      Sc3dLibrary.Prefab prefab,
      float x,
      float y,
      float seconds,
      float tickAlpha,
      ViewOrientation view) {
    boolean blue = view.blue(e.side());
    ModelState ms = modelStates.computeIfAbsent(e.id(), k -> new ModelState());
    if (ms.row == null) ms.queenSeen = seconds; // a queen risen before this tomb is not its
    int forced = 0;
    // a hero's one-shots: a row swap's or a spawn's forced animation, a cue from a spawn it made
    String once =
        ms.row == null
            ? HeroForms.onSpawn(e.name(), e.state())
            : ms.row.equals(e.name()) ? null : HeroForms.onSwap(ms.row, e.name());
    String cue = heroForms.takeCue(e.id());
    if (cue != null) once = cue;
    if (ms.row != null && !ms.row.equals(e.name())) {
      forced = forcedOnSwap(e.name());
      if (e.name().equals(QUEEN_ACTIVE)) queenRose.put(e.side(), seconds);
    }
    Float rose = queenRose.get(e.side());
    if (isTombVisual(e.name()) && rose != null && rose > ms.queenSeen) {
      // the Queen rose: its tomb scatters (Custom2) and fades
      ms.queenSeen = rose;
      forced = 2;
      ms.fadeFrom = rose + SCATTER_FADE_START;
    }
    ms.row = e.name();
    if (ms.prefab != prefab) {
      ms.prefab = prefab;
      AnimPlayer[] old = ms.players;
      List<HeroForms.PartSpec> oldParts = ms.parts;
      ms.parts = HeroForms.parts(models.library(), prefab);
      ms.players = new AnimPlayer[ms.parts.size()];
      for (int i = 0; i < ms.players.length; i++) {
        Sc3dLibrary.ModelRef ref = ms.parts.get(i).ref();
        Asm asm = models.library().asm(ref.asm());
        if (asm == null) continue;
        Map<String, Float> lengths = new HashMap<>();
        for (Glb.Clip c : models.library().glb(ref.glb()).clips().values()) {
          lengths.put(c.name(), c.length());
        }
        ms.players[i] = new AnimPlayer(asm, lengths, e.id());
        // a swap to another prefab of the same model (a hero's form) goes on from the old pose
        if (old != null && i < old.length && i < oldParts.size()) {
          if (oldParts.get(i).ref().glb().equals(ref.glb())) ms.players[i].adopt(old[i], seconds);
        }
      }
    }
    if (prefab.models().isEmpty()) return; // a logic dummy: nothing to draw
    // the Barbarian Barrel hero rolling in its barrel: the rolling projectile shows it
    if (heroForms.rolling(e.id())) return;
    AnimState st =
        anims.computeIfAbsent(
            e.id() * 4L,
            k -> {
              AnimState s = new AnimState();
              s.facingDegrees = blue ? 0f : 180f;
              return s;
            });
    updateFacing(st, e, view);
    GameRow row = lookup.unitRow(e.name());
    boolean building =
        row != null
            && row.columns().get("IsBuilding") != null
            && row.columns().get("IsBuilding").asBoolean(false);
    int animState = AnimStates.of(e.state());
    float[] mul = {1f, 1f, 1f, e.hidden() ? 0.35f : 1f};
    float[] deployScale = null;
    float[] add = {0f, 0f, 0f};
    float lift = 0f;
    if (animState == AnimStates.DEPLOY && heroForms.redeploying(e.id()) && !ms.redeployed) {
      // the reroll's second deploy (barblog_hero_set_re_deploy_animation: Custom2)
      ms.redeployed = true;
      st.deployStart = seconds;
      once = AnimStates.name(AnimStates.CUSTOM2);
    }
    if (animState == AnimStates.DEPLOY) {
      if (!ms.deployed) {
        ms.deployed = true;
        st.deployStart = seconds;
        for (AnimPlayer pl : ms.players) if (pl != null) pl.play("Deploy", seconds);
      }
      // the row's 2D deploy filter (a drop-in and fade) on the model: its fade and its drop
      float[] f = modelDeployFilter(row, seconds - st.deployStart);
      if (f != null) {
        mul[3] *= f[6];
        lift -= f[5] * proj.pixelsPerDisplayUnit();
        // its squash and stretch on the model's image (the legendary drop's thin streak)
        deployScale = new float[] {f[0], f[3]};
      }
    } else {
      ms.deployed = true;
    }
    if (ms.fadeFrom >= 0 && seconds > ms.fadeFrom) {
      mul[3] *= Math.max(0f, 1f - (seconds - ms.fadeFrom) / SCATTER_FADE_LENGTH);
    }
    float[] flash = withDamageFlash(null, e.id(), effectClock);
    if (flash != null && flash.length >= 13) {
      add[0] = flash[7];
      add[1] = flash[8];
      add[2] = flash[9];
      mul[0] = flash[10];
      mul[1] = flash[11];
      mul[2] = flash[12];
    }
    List<ModelRenderer.Part> parts = new ArrayList<>();
    int[] motion = motions.get(e.id());
    boolean charged = motion != null && motion[2] >= DashArt.CHARGE_COMPLETE;
    // the ground troops' hero ability states (docs §10.2), asked once for the unit: a state any of
    // its models has
    GroundTroopStates.Ask ask =
        troopStates.ask(
            e,
            name -> {
              for (AnimPlayer p : ms.players) if (p != null && p.has(name)) return true;
              return false;
            },
            slapsNow.getOrDefault(e.id(), 0));
    // a forced one-shot: a form's swap, spawn, cue or redeploy (§10.3), else a troop's (§10.2)
    String oneShot = once != null ? once : ask.once();
    for (int i = 0; i < ms.parts.size(); i++) {
      AnimPlayer pl = ms.players[i];
      AnimPlayer.Sample sample = null;
      if (pl != null) {
        if (ask.parameters() != null) {
          for (int k = 0; k < ask.parameters().length; k++) pl.setParameter(k, ask.parameters()[k]);
        }
        heroForms.parameters(pl, e, seconds);
        if (forced > 0) {
          pl.play(AnimStates.name(AnimStates.custom(forced)), seconds);
        }
        if (oneShot != null) pl.play(oneShot, seconds);
        String want = AnimStates.name(animState == AnimStates.DEPLOY ? AnimStates.IDLE : animState);
        // a warp, cast or charge (§10.3); a troop's ability state wins but for a warp (§10.2)
        String hero = heroForms.state(e, pl, charged);
        if (ask.state() != null && animState != AnimStates.DEPLOY && !heroForms.warping(e.id())) {
          hero = ask.state();
        }
        if (hero != null) want = hero;
        if (hero == null && animState == AnimStates.ATTACK) {
          // a troop's ability attack (the Electro Wizard's Attack2), else the attack sequence's
          int[] atk = attacks.get(e.id());
          String attack =
              !"Attack".equals(ask.attack()) && pl.has(ask.attack())
                  ? ask.attack()
                  : HeroForms.attackState(pl, atk == null ? 0 : atk[1]);
          float t = modelAttackTime(pl, attack, e, tickAlpha);
          sample = t >= 0 ? pl.at(attack, t) : null;
          if (sample == null) want = AnimStates.name(AnimStates.IDLE);
        }
        if (sample == null) {
          pl.want(want, seconds);
          sample = pl.sample(seconds);
        }
      }
      HeroForms.PartSpec spec = ms.parts.get(i);
      parts.add(new ModelRenderer.Part(spec.ref(), sample, spec.host(), spec.bone()));
    }
    float heading =
        building
            ? 0f
            : ModelCamera.headingRadians(
                (float) Math.sin(Math.toRadians(st.facingDegrees)),
                (float) Math.cos(Math.toRadians(st.facingDegrees)));
    // the core's live height: a flier's flying height (FlyingHeight, or the Hero Wizard rising and
    // landing through ActionGroundToAir), a ground unit's leap; the row's FlyingHeight without one
    float height = modelHeight(e, tickAlpha);
    float shadowLift = lift;
    lift += proj.height(height);
    int layer = e.air() || height > 0 ? LAYER_AIR : LAYER_GROUND;
    // VisualLayer GROUND (the broken Hero Tombstone): on the ground, under the units standing on it
    if ("GROUND".equals(text(row, "VisualLayer"))) layer = LAYER_GROUND_SHADOW;
    ModelRenderer.Instance in =
        new ModelRenderer.Instance(
            prefab,
            parts,
            x,
            y,
            lift,
            // the prefab's own scale only: the row's Scale column is the 2D art's (INFERRED: the
            // Hero Tombstone's skeletons, Scale 130, stand 0.83 tiles tall in a real battle, as
            // their model does unscaled)
            proj.pixelsPerDisplayUnit() * models.library().environment().duPerMetre(),
            heading,
            blue,
            mul,
            add);
    in.imageScale = deployScale;
    // a unit off the ground casts its shadow on the ground below it (§8, §10.3)
    if (height > 0f) in.shadowLift = shadowLift;
    // its 3D effects and glows (§10.4)
    if (modelEffects != null) modelEffects.attach(in, e.id(), seconds, effectClock);
    out.add(
        new Pose(null, -1, 0, x, y, lift, 1f, false, 0, null, mul[3], null, null, y, layer, in));
    ms.lastX = x;
    ms.lastY = y;
    ms.lastLift = lift;
    ms.lastShadowLift = in.shadowLift;
    ms.lastHeading = heading;
    ms.lastBlue = blue;
    ms.lastLayer = layer;
  }

  /**
   * The units the core removed whose state machine has a {@code Death} state (the Hero Mini
   * P.E.K.K.A, the Hero Ice Golem): their Death clip, once, where they last stood (INFERRED: the
   * core removes a unit as it dies, the client plays the clip; the row's {@code DeathEffect} plays
   * as for any unit).
   */
  private void poseDeaths(List<Pose> out, Map<Integer, EntityView> now, float seconds) {
    for (Map.Entry<Integer, ModelState> en : modelStates.entrySet()) {
      ModelState ms = en.getValue();
      if (now.containsKey(en.getKey()) || ms.players == null || ms.prefab == null) continue;
      boolean any = false;
      for (AnimPlayer pl : ms.players) any |= pl != null && pl.has("Death");
      if (!any) continue;
      if (ms.diedAt < 0) {
        ms.diedAt = seconds;
        for (AnimPlayer pl : ms.players) if (pl != null) pl.play("Death", seconds);
      }
      List<ModelRenderer.Part> parts = new ArrayList<>();
      boolean playing = false;
      for (int i = 0; i < ms.parts.size(); i++) {
        AnimPlayer pl = ms.players[i];
        AnimPlayer.Sample sample = null;
        if (pl != null && pl.has("Death")) {
          float t = seconds - ms.diedAt;
          // the clip's last pose holds a moment, then the unit is gone
          playing |= t < pl.length("Death") + 0.4f;
          sample = pl.at("Death", t);
        }
        // a mounted part (§10.3) stays on its host's bone
        HeroForms.PartSpec spec = ms.parts.get(i);
        parts.add(new ModelRenderer.Part(spec.ref(), sample, spec.host(), spec.bone()));
      }
      if (!playing) continue;
      ModelRenderer.Instance in =
          new ModelRenderer.Instance(
              ms.prefab,
              parts,
              ms.lastX,
              ms.lastY,
              ms.lastLift,
              proj.pixelsPerDisplayUnit() * models.library().environment().duPerMetre(),
              ms.lastHeading,
              ms.lastBlue,
              new float[] {1f, 1f, 1f, 1f},
              new float[] {0f, 0f, 0f});
      // a flier dies where it flew, its shadow on the ground below (§10.3)
      in.shadowLift = ms.lastShadowLift;
      out.add(
          new Pose(
              null,
              -1,
              0,
              ms.lastX,
              ms.lastY,
              ms.lastLift,
              1f,
              false,
              0,
              null,
              1f,
              null,
              null,
              ms.lastY,
              ms.lastLayer,
              in));
    }
  }

  /** The row's deploy filter ({@code DeployBaseAnimExportName}) at that time, or null. */
  private float[] modelDeployFilter(GameRow row, float elapsed) {
    String name = text(row, "DeployBaseAnimExportName");
    ScScene fx = lookup.effectsScene();
    if (name == null || name.isEmpty() || fx == null) return null;
    VisualLookup.Unit u = new VisualLookup.Unit(fx, row, 1f);
    u.deployFilter = fx.export(name);
    return deployFilter(u, elapsed);
  }

  /**
   * Seconds into the model's Attack state so its {@code action_frame} marker meets the logic hit,
   * or -1 between hits (the 2D rule of §1.4 with the state machine's marker).
   */
  private float modelAttackTime(AnimPlayer pl, String attack, EntityView e, float tickAlpha) {
    int[] atk = attacks.get(e.id());
    float length = pl.length(attack);
    if (atk == null || length <= 0) return atk == null ? 0f : -1f;
    GameRow row = lookup.unitRow(e.name());
    int hs = integer(row, "VisualHitSpeed", 0);
    if (hs <= 0) hs = integer(row, "HitSpeed", 1000);
    if (hs <= 0) return 0f;
    float af = pl.marker(attack, "action_frame");
    if (af < 0) af = length * 0.6f;
    float hsS = hs / 1000f;
    float rate = af > hsS ? af / hsS : 1f;
    float t = Math.max(0, atk[0] - 50f * (1f - tickAlpha)) / 1000f;
    float p = t % hsS;
    float toHit = hsS - p;
    if (toHit * rate <= af) return af - toHit * rate;
    if (p * rate < length - af) return af + p * rate;
    return -1f;
  }

  /**
   * Draws the riders attached to a mount (the Ram Rider on her Ram, the Goblin Giant's Spear
   * Goblins) at the mount clip's attach points: rider {@code i} replaces the mount clip's child
   * named {@code child<i+1>} (an empty placeholder in every action and facing of {@code
   * chr_ram_rider_dl.sc}'s {@code ram}), so it bobs, leaps and sorts with the mount as the game
   * composites it, playing its own action and facing. A rider whose mount clip has no such child is
   * drawn on the ground at the mount.
   */
  private void mountRiders(
      List<Pose> out,
      int from,
      EntityView mount,
      Map<Integer, EntityView> now,
      float x,
      float y,
      float seconds,
      float tickAlpha,
      ViewOrientation view) {
    if (mounts.isEmpty()) return;
    for (Map.Entry<Integer, int[]> m : mounts.entrySet()) {
      if (m.getValue()[0] != mount.id()) continue;
      EntityView rider = now.get(m.getKey());
      if (rider == null) continue;
      // a 3D rider (the Ice Wizard hero's ice chunks) is its own model, at its mount's place
      Sc3dLibrary.Prefab riderPrefab = !modelsShown() ? null : prefabFor(rider.name());
      if (riderPrefab != null) {
        poseModel(out, rider, riderPrefab, x, y, seconds, tickAlpha, view);
        continue;
      }
      boolean blue = view.blue(rider.side());
      VisualLookup.Unit unit = lookup.unit(rider.name(), blue);
      if (unit == null) continue;
      Pose host = out.size() > from ? out.get(out.size() - 1) : null;
      ScScene.Clip hostClip =
          host == null || host.scene() == null ? null : host.scene().clip(host.id());
      String socket = "child" + (m.getValue()[1] + 1);
      AnimState st = anims.computeIfAbsent(rider.id() * 4L, k -> new AnimState());
      updateFacing(st, rider, view);
      List<Pose> one = new ArrayList<>(1);
      int layer = host == null ? LAYER_GROUND : host.layer();
      poseAnimated(one, rider, unit, st, x, y, 0, seconds, tickAlpha, 1f, null, y, layer);
      if (one.isEmpty()) continue;
      Pose r = one.get(0);
      if (hostClip == null || hostClip.childNamed(socket) < 0) {
        out.add(r); // no attach point: on the ground at the mount
        continue;
      }
      List<ScDrawer.Override> overrides =
          host.overrides() == null ? new ArrayList<>() : new ArrayList<>(host.overrides());
      // the rider's own facing inside the mount's (mirrored) space
      overrides.add(
          new ScDrawer.Override(socket, r.scene(), r.id(), r.frame(), r.flip() != host.flip()));
      out.set(
          out.size() - 1,
          new Pose(
              host.scene(),
              host.id(),
              host.frame(),
              host.x(),
              host.groundY(),
              host.lift(),
              host.scale(),
              host.flip(),
              host.rotation(),
              host.filter(),
              host.alpha(),
              host.shadowOf(),
              overrides,
              host.sortY(),
              host.layer()));
    }
  }

  private static void updateFacing(AnimState st, EntityView e, ViewOrientation view) {
    float hx = e.headingX(), hy = e.headingY();
    if (e.kind() == EntityView.Kind.TOWER && e.hasTarget()) {
      // a crown tower is no character: the core never turns its facing (only the Royal Chef's
      // throw does), so its turret and rider aim at the reference it holds
      hx = e.targetX() - e.x();
      hy = e.targetY() - e.y();
    }
    float fx = view.x((float) (e.x() + hx)) - view.x((float) e.x());
    float fy = view.y((float) (e.y() + hy)) - view.y((float) e.y());
    if (fx * fx + fy * fy < 1e-4f) return;
    st.facingDegrees = ArtRules.facingDegrees(fx, fy);
  }

  /** A turret's timeline frame (0-based) for its facing over a mirrored half circle. */
  private static int[] halfCircleFrame(float theta, int n) {
    int[] f = ArtRules.facingExport(theta, n);
    return new int[] {f[0] - 1, f[1]};
  }

  private void poseAnimated(
      List<Pose> out,
      EntityView e,
      VisualLookup.Unit unit,
      AnimState st,
      float x,
      float y,
      float lift,
      float seconds,
      float tickAlpha,
      float alpha,
      VisualLookup.Unit shadowOf,
      float sortY,
      int layer) {
    float scale = proj.pixelsPerDisplayUnit() * unit.scale;
    String action;
    float clipTime = -1; // seconds into the clip when timed to the logic, else looped from start
    float[] filter = null;
    int state = e.state();
    if (state != st.state) {
      if (st.state == GridEntityState.JUMPING) st.jumpEnd = seconds;
      st.state = state;
      st.stateStart = seconds;
    }
    int[] motion = motions.get(e.id());
    switch (state) {
      case GridEntityState.MOVING,
          GridEntityState.SPAWN_PATHFIND,
          GridEntityState.INGAME_PATHFIND,
          GridEntityState.ROUTE_FOLLOWING_ALTERNATE,
          GridEntityState.STANDING -> {
        float windup = motion == null ? 0 : (motion[3] + 50f * (1f - tickAlpha)) / 1000f;
        float landing = st.jumpEnd < 0 ? -1 : seconds - st.jumpEnd;
        if (motion != null && motion[3] > 0) {
          // a dash's wind-up: the unit stands, crouching into a leap at its end (§1.6)
          float t = unit.has("jump") ? DashArt.windupTime(clipOf(unit, "jump"), windup) : -1;
          action = t >= 0 ? "jump" : "idle";
          clipTime = t;
        } else if (landing >= 0 && unit.has("landing") && landing < clipLength(unit, "landing")) {
          action = "landing"; // the evolved Royal Hogs' landing after a jump over the river
          clipTime = landing;
        } else if (state == GridEntityState.STANDING) {
          action = "idle";
        } else {
          boolean charged = motion != null && motion[2] >= DashArt.CHARGE_COMPLETE;
          action = charged && unit.has("charge") ? "charge" : "run";
        }
      }
      case GridEntityState.DASHING -> {
        if (unit.has("jump") && unit.integer("JumpHeight", 0) > 0) {
          // a leap (Mega Knight): the clip's jump part over the dash's constant time, its recover
          // part over the landing hold
          action = "jump";
          clipTime =
              DashArt.leapTime(
                  clipOf(unit, "jump"),
                  seconds - st.stateStart,
                  unit.integer("DashConstantTime", 0) / 1000f,
                  motion == null ? 0 : motion[4] - 50f * (1f - tickAlpha),
                  unit.integer("DashLandingTime", 0));
        } else if (unit.has("dash")) {
          action = DashArt.dashVariant(unit::has, dashLinks.getOrDefault(e.id(), 0));
        } else {
          action = unit.has("charge") ? "charge" : "run";
        }
      }
      case GridEntityState.JUMPING -> {
        if (unit.has("jump")) {
          // a jump over the river with a clip of its own (Royal Hogs): its landing frame at the
          // landing, the jump lasting the distance over the jump speed
          action = "jump";
          clipTime =
              DashArt.riverJumpTime(
                  clipOf(unit, "jump"),
                  seconds - st.stateStart,
                  motion == null ? 0 : motion[5],
                  unit.integer("JumpSpeed", 0));
        } else {
          action = "run";
        }
      }
      case GridEntityState.COMPONENTS_DISABLED, GridEntityState.FOLLOWING_REMOVED_BUILDING -> {
        // the Fisherman held while his hook pulls (or drags him): his loading clip's pull
        float t = DashArt.pullTime(clipOf(unit, "loading"), seconds - st.stateStart);
        action = t >= 0 ? "loading" : "idle";
        clipTime = t;
      }
      case GridEntityState.CASTING, GridEntityState.ABILITY_FOLLOW_UP ->
          action = unit.has("ability") ? "ability" : "idle";
      case GridEntityState.DEPLOYING, GridEntityState.CLONE_SETUP -> {
        if (st.deployStart < 0) st.deployStart = seconds;
        action = unit.has("deploy") ? "deploy" : "idle";
        filter = deployFilter(unit, seconds - st.deployStart);
      }
      case GridEntityState.ATTACKING -> {
        int[] atk = attacks.get(e.id());
        String clipName = "attack";
        if (atk != null && atk[1] > 0 && unit.has("attack" + (atk[1] + 1))) {
          clipName = "attack" + (atk[1] + 1);
        }
        // the timer is the entity's, so is the hit speed: a crown tower's rider (the Tower
        // Princess, HitSpeed 1200) shoots at the tower row's pace (PrincessTower, 800)
        VisualLookup.Unit pace = lookup.unit(e.name(), true);
        float t =
            atk == null
                ? -1f
                : attackClipTime(unit, pace == null ? unit : pace, clipName, atk, tickAlpha);
        if (atk == null) {
          action = clipName; // no timer to line up with: loop the clip
        } else if (t >= 0) {
          action = clipName;
          clipTime = t;
        } else {
          action = "idle";
        }
      }
      default -> action = "idle";
    }
    if (state != GridEntityState.DEPLOYING && state != GridEntityState.CLONE_SETUP) {
      st.deployStart = -1;
    }
    filter = withDamageFlash(filter, e.id(), effectClock);
    if (!unit.has(action)) action = unit.has("idle") ? "idle" : action;
    if (e.shield() > 0 && unit.has("shield:" + action)) action = "shield:" + action;
    int n = unit.directions(action);
    int[] df = ArtRules.facingExport(st.facingDegrees, Math.max(1, n));
    int id = unit.animation(action, df[0]);
    if (id < 0) id = unit.anyExport;
    if (id < 0) return;
    if (!action.equals(st.action)) {
      st.action = action;
      st.actionStart = seconds;
    }
    ScScene.Clip clip = unit.scene.clip(id);
    int frame = 0;
    if (clip != null && clip.frameCount() > 1) {
      float t = clipTime >= 0 ? clipTime : seconds - st.actionStart;
      frame = (int) Math.floor(Math.max(0, t) * clip.fps());
      if (clipTime >= 0) frame = Math.min(frame, clip.frameCount() - 1);
    }
    if (e.hasStatus(UnitStatus.Kind.STUNNED) || e.hasStatus(UnitStatus.Kind.FROZEN)) {
      frame = st.heldFrame;
    } else {
      st.heldFrame = frame;
    }
    out.add(
        new Pose(
            unit.scene,
            id,
            frame,
            x,
            y,
            lift,
            scale,
            df[1] == 1,
            0,
            filter,
            alpha,
            shadowOf,
            null,
            sortY,
            layer));
  }

  private static ScScene.Clip clipOf(VisualLookup.Unit unit, String action) {
    int id = unit.animation(action, 5);
    return id < 0 ? null : unit.scene.clip(id);
  }

  private static float clipLength(VisualLookup.Unit unit, String action) {
    ScScene.Clip c = clipOf(unit, action);
    return c == null || c.fps() <= 0 ? 0f : c.frameCount() / (float) c.fps();
  }

  /**
   * Seconds into the attack clip so its {@code action_frame} meets the logic hit, or -1 for the
   * ready (idle) pose between hits (§1.4).
   */
  private static float attackClipTime(
      VisualLookup.Unit unit, VisualLookup.Unit pace, String action, int[] atk, float tickAlpha) {
    int id = unit.animation(action, 5);
    ScScene.Clip clip = id < 0 ? null : unit.scene.clip(id);
    if (clip == null || atk == null) return 0f;
    int hs = pace.integer("VisualHitSpeed", 0);
    if (hs <= 0) hs = pace.integer("HitSpeed", 1000);
    if (hs <= 0) return 0f;
    float fps = clip.fps();
    int afFrame = clip.labelFrame("action_frame");
    float length = clip.frameCount() / fps;
    float af = afFrame >= 0 ? afFrame / fps : length * 0.6f;
    float hsS = hs / 1000f;
    float rate = af > hsS ? af / hsS : 1f;
    float t = Math.max(0, atk[0] - 50f * (1f - tickAlpha)) / 1000f; // the timer is the newer tick's
    float p = t % hsS;
    float toHit = hsS - p;
    if (toHit * rate <= af) return af - toHit * rate;
    if (p * rate < length - af) return af + p * rate;
    return -1f;
  }

  /**
   * The unit's filter with its damage flash (the effect layer's {@code filter_damage} frame:
   * matrix, alpha, additive colour, multiplier) composed on top, or the filter alone.
   */
  private float[] withDamageFlash(float[] filter, int id, float seconds) {
    float[] d = effects == null ? null : effects.damageFilter(id, seconds);
    if (d == null) return filter;
    float[] base = filter == null ? new float[] {1, 0, 0, 1, 0, 0, 1} : filter;
    // (base o flash): the flash acts in the unit's local space after the deploy filter
    float a = base[0] * d[0] + base[2] * d[1];
    float b = base[1] * d[0] + base[3] * d[1];
    float c = base[0] * d[2] + base[2] * d[3];
    float dd = base[1] * d[2] + base[3] * d[3];
    float tx = base[0] * d[4] + base[2] * d[5] + base[4];
    float ty = base[1] * d[4] + base[3] * d[5] + base[5];
    float[] out = {a, b, c, dd, tx, ty, base[6] * d[6], 0, 0, 0, 1, 1, 1};
    if (d.length >= 13) System.arraycopy(d, 7, out, 7, 6);
    return out;
  }

  /** The deploy filter's matrix and alpha at that time, or null once it has played (§8). */
  private float[] deployFilter(VisualLookup.Unit unit, float elapsed) {
    ScScene fx = lookup.effectsScene();
    if (fx == null || unit.deployFilter < 0) return null;
    ScScene.Clip c = fx.clip(unit.deployFilter);
    if (c == null || c.frameCount() == 0) return null;
    int f = (int) Math.floor(elapsed * c.fps());
    if (f >= c.frameCount()) return null;
    int[] el = c.frames()[Math.max(0, f)];
    if (el.length < 3) return null;
    ScScene.Bank bank = fx.bank(c.bank());
    float[] m = {1, 0, 0, 1, 0, 0, 1};
    if (bank != null && el[1] != ScScene.IDENTITY && el[1] * 6 + 5 < bank.matrices().length) {
      System.arraycopy(bank.matrices(), el[1] * 6, m, 0, 6);
    }
    if (bank != null && el[2] != ScScene.IDENTITY && el[2] * 7 + 6 < bank.colors().length) {
      m[6] = bank.colors()[el[2] * 7 + 3] / 255f;
    }
    return m;
  }

  // ---------------------------------------------------------------------------------- projectiles

  private void poseProjectile(
      List<Pose> out,
      EntityView e,
      EntityView before,
      float tickAlpha,
      float x,
      float y,
      ViewOrientation view) {
    if (modelEffects != null && drawModels && poseModelProjectile(out, e, x, y, view)) return;
    VisualLookup.Projectile p = lookup.projectile(e.name(), view.blue(e.side()));
    if (p == null) return;
    int[] flight = flights.get(e.id());
    float z = flight == null ? 0 : flight[0];
    float progress = 0.5f;
    float sx = x, sy = y;
    if (flight != null) {
      float dx = e.aimX() - flight[1], dy = e.aimY() - flight[2];
      float total = (float) Math.sqrt(dx * dx + dy * dy);
      float ddx = e.x() - flight[1], ddy = e.y() - flight[2];
      progress = total > 1 ? Math.min(1f, (float) Math.sqrt(ddx * ddx + ddy * ddy) / total) : 1f;
      sx = proj.x(view, flight[1]);
      sy = proj.y(view, flight[2]);
    }
    float ax = proj.x(view, e.aimX()), ay = proj.y(view, e.aimY());
    float angle;
    if ((ax - sx) * (ax - sx) + (ay - sy) * (ay - sy) >= 1f) {
      angle = (float) Math.toDegrees(Math.atan2(ay - sy, ax - sx));
    } else {
      float hx = view.x((float) (e.x() + e.headingX())) - view.x((float) e.x());
      float hy = view.y((float) (e.y() + e.headingY())) - view.y((float) e.y());
      angle = (float) Math.toDegrees(Math.atan2(hy * 0.811f, hx));
    }
    ScScene.Clip clip = p.scene().clip(p.export());
    int n = clip == null ? 1 : clip.frameCount();
    int frame =
        n > 100
            ? (int) (e.lifeMs() / 1000f * (clip == null ? 30 : clip.fps()))
            : Math.round(progress * (n - 1));
    float scale = proj.pixelsPerDisplayUnit() * p.scale();
    out.add(
        new Pose(
            p.scene(),
            p.export(),
            frame,
            x,
            y,
            proj.height(z),
            scale,
            false,
            angle,
            null,
            1f,
            null,
            null,
            y,
            LAYER_AIR));
    if (p.shadow() >= 0) {
      JsonNode noRotate = p.row() == null ? null : p.row().columns().get("ShadowDisableRotate");
      float shadowAngle = noRotate != null && noRotate.asBoolean(false) ? 0 : angle;
      out.add(
          new Pose(
              p.scene(),
              p.shadow(),
              frame,
              x,
              y,
              0,
              scale,
              false,
              shadowAngle,
              null,
              0.7f,
              null,
              null,
              y,
              LAYER_GROUND_SHADOW));
    }
  }

  /**
   * Poses a projectile drawn with its 3D prefab ({@link ModelEffects}): turned along its flight
   * from its start to its aim, raised to its height. False when it is drawn in 2D.
   */
  private boolean poseModelProjectile(
      List<Pose> out, EntityView e, float x, float y, ViewOrientation view) {
    int[] flight = flights.get(e.id());
    float z = flight == null ? 0 : flight[0];
    float fx = 0, fy = 0, travelled = 0;
    if (flight != null) {
      fx = view.x((float) e.aimX()) - view.x((float) flight[1]);
      fy = view.y((float) e.aimY()) - view.y((float) flight[2]);
      float dx = e.x() - flight[1], dy = e.y() - flight[2];
      travelled = (float) Math.sqrt(dx * dx + dy * dy);
    }
    if (fx * fx + fy * fy < 1f) {
      fx = view.x((float) (e.x() + e.headingX())) - view.x((float) e.x());
      fy = view.y((float) (e.y() + e.headingY())) - view.y((float) e.y());
    }
    float seconds = effectClock;
    ModelRenderer.Instance in =
        modelEffects.projectile(
            e,
            x,
            y,
            proj.height(z),
            fx,
            fy,
            travelled,
            z,
            proj.pixelsPerDisplayUnit(),
            view.blue(e.side()),
            seconds,
            effectClock);
    if (in == null) return false;
    out.add(new Pose(null, -1, 0, x, y, 0f, 1f, false, 0, null, 1f, null, null, y, LAYER_AIR, in));
    return true;
  }

  // ---------------------------------------------------------------------------------------- draw

  private void drawPose(Pose q) {
    if (q.model() != null) {
      models.draw(q.model(), projection, compositor);
      return;
    }
    transform.setToTranslation(q.x(), q.groundY() + q.lift());
    if (q.rotation() != 0) transform.rotate(q.rotation());
    transform.scale(q.flip() ? -q.scale() : q.scale(), -q.scale());
    float[] f = q.filter();
    if (f != null) {
      // the filter acts in the unit's own (unmirrored) space
      if (q.flip()) transform.scale(-1, 1);
      applyFilter(f);
      if (q.flip()) transform.scale(-1, 1);
    }
    float a = q.alpha() * (f != null ? f[6] : 1f);
    tint.set(1, 1, 1, a);
    if (f != null && f.length >= 13) {
      tint.set(f[10], f[11], f[12], a);
      drawer.setNextAdd(f[7], f[8], f[9]);
    }
    if (q.overrides() != null) {
      drawer.draw(q.scene(), q.id(), q.frame(), transform, tint, q.overrides());
    } else {
      drawer.draw(q.scene(), q.id(), q.frame(), transform, tint);
    }
  }

  private void applyFilter(float[] f) {
    local.m00 = f[0];
    local.m10 = f[1];
    local.m01 = f[2];
    local.m11 = f[3];
    local.m02 = f[4];
    local.m12 = f[5];
    transform.mul(local);
  }

  /** The unit's current frame again as a dark, flattened silhouette at its ground point (§8). */
  private void drawShadow(Pose q) {
    VisualLookup.Unit u = q.shadowOf();
    float sxs = u.integer("ShadowScaleX", 90) / 100f;
    float sys = u.integer("ShadowScaleY", 90) / 100f;
    float skew = u.integer("ShadowSkew", 25) / 100f;
    float ox = u.integer("ShadowX", 0);
    float oy = u.integer("ShadowY", 0);
    transform.setToTranslation(q.x(), q.groundY());
    transform.scale(q.scale(), -q.scale());
    transform.translate(ox, oy);
    // cast towards the viewer: the silhouette mirrored below the feet, its far end leaning left
    local.idt();
    local.m01 = -skew; // x -= skew * y, y down after the mirror
    transform.mul(local);
    transform.scale(sxs, -sys);
    if (q.flip()) transform.scale(-1, 1);
    // a unit still dropping in (the deploy filter's fade) casts a shadow as faint as itself
    float fade = q.filter() != null && q.filter().length >= 7 ? q.filter()[6] : 1f;
    tint.set(shadowColor.r, shadowColor.g, shadowColor.b, shadowColor.a * q.alpha() * fade * fade);
    if (q.overrides() != null) {
      drawer.draw(q.scene(), q.id(), q.frame(), transform, tint, q.overrides());
    } else {
      drawer.draw(q.scene(), q.id(), q.frame(), transform, tint);
    }
  }

  static String text(GameRow row, String column) {
    if (row == null) return null;
    JsonNode v = row.columns().get(column);
    return v == null || v.isNull() ? null : v.asText();
  }

  static int integer(GameRow row, String column, int fallback) {
    if (row == null) return fallback;
    JsonNode v = row.columns().get(column);
    return v == null || !v.canConvertToInt() ? fallback : v.asInt();
  }

  @Override
  public void dispose() {
    if (models != null) models.dispose();
    if (modelEffects != null) modelEffects.dispose();
    batch.dispose();
  }
}
