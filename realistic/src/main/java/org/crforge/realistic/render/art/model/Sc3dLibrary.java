/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The 3D models the sc3d extractor wrote ({@code crforge-art sc3d}, its {@code index.json}): which
 * prefab a table row's {@code PrefabAsset} names, the models it places (their {@code .glb}, prefab
 * transform and state machine), its light and its {@code hudoffset} pivot
 * (docs/realistic-rendering.md §10).
 *
 * <p>The folder is the system property {@value #PROPERTY}: a folder holding {@code index.json} (the
 * extractor's {@code <content sha>/sc3d}) or the content folder above it, or {@code off}. Without
 * it, the art cache's own {@code sc3d} folder is used when the extractor wrote one there. Which
 * rows are drawn in 3D is {@value #ROWS_PROPERTY}: comma-separated row name prefixes (a name ending
 * in {@code $} is matched whole), {@code *} for every row; by default the rows checked against the
 * real game ({@link #DEFAULT_ROWS}). Other rows keep their 2D stand-in.
 */
@Slf4j
public final class Sc3dLibrary {

  public static final String PROPERTY = "crforge.sc3d";
  public static final String ROWS_PROPERTY = "crforge.sc3d.rows";

  /**
   * The ground troops' rows checked against real battles (docs/realistic-rendering.md §10.2), each
   * by its exact name ({@code $}): the heroes, Ronin and the evolutions with a 3D prefab, and the
   * units their abilities make (the Musketeer's turret, the Magic Archer's decoy, the Ice Wizard's
   * snowman and ice chunks, the Goblins' unarmoured goblins and banner).
   */
  public static final String GROUND_TROOP_ROWS =
      "KnightHero$,MiniPekkaHero$,ValkyrieHero$,GiantHero$,IceGolemiteHero$,MusketeerHero$,"
          + "MusketeerTurret$,WizardHero$,IceWizardHero$,IceWizardHero_IceCube$,"
          + "IceWizardHeroFloatingCube$,ElectroWizardHero$,EliteArcherHero$,EliteArcherHero_Dummy$,"
          + "BowlerHero$,GoblinHero$,GoblinHero_Base$,Goblin_dummy$,GoblinHero_Flag_Building$,"
          + "Ronin$,Giant_EV1$,AngryBarbarian_EV1$,AngryBarbarian_EV1_2$,ElectroGiant_EV1$";

  /**
   * The fliers and model swaps checked against real battles (docs/realistic-rendering.md §10.3), by
   * prefix: the flying heroes, the Balloon's Skeleton Trooper, the Minion Giant, the flying Wizard,
   * and the heroes whose prefab swaps with their form.
   */
  public static final String FLIER_ROWS =
      "MegaMinionHero,BalloonHero,SkeletonTrooper,MinionGiant,WizardHero_air,"
          + "DarkPrinceHero,BerserkerHero,BarbLogBarbarianHero";

  /** The rows drawn in 3D by default: the Hero Tombstone's, the ground troops', the fliers'. */
  public static final String DEFAULT_ROWS = "TombstoneHero," + GROUND_TROOP_ROWS + "," + FLIER_ROWS;

  /**
   * The 3D projectiles and effects drawn by default besides {@link #DEFAULT_ROWS} (see {@link
   * FxPrefab}): their rows draw only where they fly or where their owner is drawn in 3D.
   */
  public static final String FX_ROWS =
      "BowlerHeroProjectile,BowlerHero_SiegeProjectile,AngryBarbarian_EV1_RangedProjectile,"
          + "BalloonHero_Skeletrooper_Projectile,BarbLogHeroProjectile,"
          + "AngryBarbarian_EV1_ReadyIndicator,BowlerHero_3DFlashEffect,"
          + "ElectroGiant_EV1_Play_Pulse_Effect,ElectroWizardHero_Continuous_Aura,"
          + "EliteArcherHero_Play_Triple_Shot_Effect,Giant_EV1_Play_Aura_On_Character";

  /** A prefab's directional light ({@code objectType 5007}). */
  public record Light(
      float[] rotation, float intensity, float[] color, float[] ambient, float ambientSpecular) {}

  /** A model a prefab places: its file, its prefab transform (column-major) and state machine. */
  public record ModelRef(Path glb, float[] transform, String asm, boolean shadow) {}

  /**
   * How battles show 3D actors (the index's {@code environment}, DATA): the light of the {@code
   * VANILLA_3D_ACTOR_ENV} prefab ({@code arn_default}'s {@code MainLight}), its camera root's tilt,
   * the display units per metre ({@code VANILLA_3D_PPM} halved, INFERRED, see {@link ModelCamera}),
   * the team colours ({@code VANILLA_3D_ACTOR_COLORS}, blue then red) and the blob shadow's radius
   * factor and soft edge ({@code BLOB_SHADOW_RADIUS_FACTOR}, {@code BLOB_SHADOW_SOFT_EDGE}).
   */
  public record Environment(
      Light light,
      float tiltDegrees,
      float duPerMetre,
      int blueTeam,
      int redTeam,
      float blobRadiusFactor,
      float blobSoftEdge) {

    /** The values the extractor finds in this data version, for an index written without them. */
    public static final Environment DEFAULT =
        new Environment(
            new Light(
                new float[] {-60f, -179.9999f, -41f},
                2f,
                new float[] {1f, 1f, 1f},
                new float[] {132 / 255f, 115 / 255f, 106 / 255f},
                2.452f),
            ModelCamera.TILT_DEGREES,
            ModelCamera.DU_PER_METRE,
            0x0B318D,
            0xA90707,
            0.45f,
            0.35f);
  }

  /** A prefab: its models (none for a logic dummy), light, health bar pivot height and clipping. */
  public record Prefab(
      String path, List<ModelRef> models, Light light, float hudOffset, boolean clipPlane) {}

  private final Path folder;
  private final JsonNode index;
  private final List<String> rows;
  private final Map<String, Optional<Prefab>> prefabs = new HashMap<>();
  private final Map<Path, Glb> glbs = new HashMap<>();
  private final Map<String, Optional<Asm>> asms = new HashMap<>();
  private final Map<ModelRef, Set<String>> effectGeometry = new IdentityHashMap<>();
  private Environment environment;

  Sc3dLibrary(Path folder, JsonNode index, List<String> rows) {
    this.folder = folder;
    this.index = index;
    this.rows = rows;
  }

  /** The library the properties configure, next to the art cache folder {@code artFolder}. */
  public static Optional<Sc3dLibrary> locate(Path artFolder) {
    String configured = System.getProperty(PROPERTY);
    if (configured != null && configured.trim().equalsIgnoreCase("off")) return Optional.empty();
    List<Path> candidates = new ArrayList<>();
    if (configured != null && !configured.isBlank()) {
      Path p = Paths.get(configured);
      candidates.add(p);
      candidates.add(p.resolve("sc3d"));
    } else if (artFolder != null) {
      candidates.add(artFolder.resolve("sc3d"));
    }
    for (Path c : candidates) {
      Path idx = c.resolve("index.json");
      if (!Files.isRegularFile(idx)) continue;
      try {
        JsonNode json = new ObjectMapper().readTree(idx.toFile());
        String r = System.getProperty(ROWS_PROPERTY, DEFAULT_ROWS + "," + FX_ROWS);
        List<String> rows = new ArrayList<>();
        for (String s : r.split(",")) if (!s.isBlank()) rows.add(s.trim());
        log.info("3D models: {} (rows {})", c, rows);
        // the index's paths are relative to the content folder above sc3d/
        return Optional.of(new Sc3dLibrary(c.getParent(), json, rows));
      } catch (IOException e) {
        log.warn("Cannot read {}: {}", idx, e.getMessage());
      }
    }
    if (configured != null && !configured.isBlank()) {
      log.warn("No sc3d index.json at {}", configured);
    }
    return Optional.empty();
  }

  /** Whether the row is one of those drawn in 3D. */
  public boolean drawsRow(String row) {
    for (String r : rows) {
      // a name ending in $ is the row itself, any other a prefix
      boolean match =
          r.endsWith("$") ? row.equals(r.substring(0, r.length() - 1)) : row.startsWith(r);
      if (r.equals("*") || match) return index.path("characters").has(row);
    }
    return false;
  }

  /** The prefab a row draws in 3D, or null when it is not drawn in 3D. */
  public Prefab prefabOf(String row) {
    if (!drawsRow(row)) return null;
    JsonNode list = index.path("characters").path(row);
    if (!list.isArray() || list.isEmpty()) return null;
    return prefab(list.get(0).asText());
  }

  /**
   * The prefab of a row drawn in 3D that the index does not list by name but whose {@code
   * PrefabAsset} it holds: a row that inherits it from its {@code Base} ({@code
   * AngryBarbarian_EV1_2}, the evolved Elite Barbarians' second, from {@code AngryBarbarian_EV1}).
   */
  public Prefab prefabOf(String row, String prefabAsset) {
    Prefab p = prefabOf(row);
    if (p != null || prefabAsset == null || prefabAsset.isEmpty() || !named(row)) return p;
    return prefab(prefabAsset);
  }

  /** Whether the rows option names the row ({@link #drawsRow} without the index's own list). */
  private boolean named(String row) {
    for (String r : rows) {
      if (r.equals("*")) return true;
      if (r.endsWith("$") ? row.equals(r.substring(0, r.length() - 1)) : row.startsWith(r)) {
        return true;
      }
    }
    return false;
  }

  /**
   * A mount point of a prefab ({@code objectType 5022 MountPoints}, the index's {@code mounts}):
   * the prefab {@code rider} posed each frame at the bone {@code bone} of the model on node {@code
   * node} (the mounted Dark Prince's rhino at his {@code mountbone_s}, the Berserker's bear mask).
   */
  public record Mount(String node, String bone, Prefab rider) {}

  /**
   * The geometry (mesh node names) of a model its prefab draws with an effect material ({@code
   * Geometries[].MaterialOverride} whose {@code .rmat} derives from a {@code vfx_*} base: the Hero
   * Wizard's flame wings, {@code vfx_mask2_base}). Those shaders (ramps, masks, dissolves) are not
   * ported, so the geometry is left out rather than drawn with the model's own material (where it
   * shows as flat grey slabs).
   */
  public Set<String> effectGeometry(ModelRef ref) {
    return effectGeometry.getOrDefault(ref, Set.of());
  }

  private boolean effectMaterial(String path) {
    String rel = index.path("materials").path(path).asText("");
    if (rel.isEmpty()) return false;
    try {
      String base =
          new ObjectMapper().readTree(folder.resolve(rel).toFile()).path("base").asText("");
      return base.substring(base.lastIndexOf('/') + 1).startsWith("vfx_");
    } catch (IOException e) {
      return false;
    }
  }

  /** The prefab's mount points whose rider prefab the index holds (none for an older index). */
  public List<Mount> mounts(Prefab prefab) {
    if (prefab == null) return List.of();
    List<Mount> out = new ArrayList<>();
    for (JsonNode m : index.path("prefabs").path(prefab.path()).path("mounts")) {
      Prefab rider = prefab(m.path("prefab").asText(""));
      if (rider != null)
        out.add(new Mount(m.path("node").asText(""), m.path("bone").asText(""), rider));
    }
    return out;
  }

  /** The prefab at that asset path (as {@code PrefabAsset} names it), or null. */
  public Prefab prefab(String path) {
    return prefabs.computeIfAbsent(path, p -> Optional.ofNullable(buildPrefab(p))).orElse(null);
  }

  private Prefab buildPrefab(String path) {
    JsonNode p = index.path("prefabs").path(path);
    if (p.isMissingNode()) return null;
    List<ModelRef> models = new ArrayList<>();
    for (JsonNode m : p.path("models")) {
      String glb = m.path("glb").asText("");
      if (glb.isEmpty()) continue;
      float[] t = new float[16];
      JsonNode tr = m.path("transform");
      for (int i = 0; i < 16; i++) t[i] = (float) tr.path(i).asDouble(i % 5 == 0 ? 1 : 0);
      ModelRef ref =
          new ModelRef(
              folder.resolve(glb),
              t,
              m.path("asm").isNull() ? null : m.path("asm").asText(null),
              m.path("shadow").asBoolean(true));
      models.add(ref);
      Set<String> effects = new HashSet<>();
      m.path("materialOverrides")
          .fields()
          .forEachRemaining(
              f -> {
                if (effectMaterial(f.getValue().asText(""))) effects.add(f.getKey());
              });
      if (!effects.isEmpty()) effectGeometry.put(ref, Set.copyOf(effects));
    }
    Light light = null;
    for (JsonNode l : p.path("lights")) {
      light = light(l);
      break;
    }
    float hud = (float) p.path("pivots").path("hudoffset").path(2).asDouble(0);
    boolean clip = clipPlane(p.path("json").asText(""));
    return new Prefab(path, List.copyOf(models), light, hud, clip);
  }

  private boolean clipPlane(String json) {
    if (json.isEmpty()) return false;
    try {
      JsonNode root = new ObjectMapper().readTree(folder.resolve(json).toFile()).path("data");
      for (JsonNode c : root.path("components")) {
        if (c.path("ClippingPlaneEnabled").asBoolean(false)) return true;
      }
    } catch (IOException e) {
      log.debug("Cannot read prefab {}: {}", json, e.getMessage());
    }
    return false;
  }

  private static float[] rgb(JsonNode c) {
    return new float[] {
      c.path("r").asInt(255) / 255f, c.path("g").asInt(255) / 255f, c.path("b").asInt(255) / 255f
    };
  }

  /** The battle environment of the index, or the defaults for an index without one. */
  public Environment environment() {
    if (environment == null) environment = readEnvironment(index.path("environment"));
    return environment;
  }

  static Environment readEnvironment(JsonNode env) {
    Environment d = Environment.DEFAULT;
    if (env.isMissingNode() || env.isEmpty()) return d;
    Light light = d.light();
    for (JsonNode l : env.path("lights")) {
      light = light(l);
      if (l.path("MainLight").asBoolean(false)) break;
    }
    float tilt = d.tiltDegrees();
    for (JsonNode c : env.path("cameras")) {
      JsonNode root = c.path("chain").path(0).path("rot").path(0);
      if (root.isNumber()) tilt = (float) -root.asDouble();
    }
    JsonNode g = env.path("globals");
    float ppm = (float) g.path("VANILLA_3D_PPM").path(0).asDouble(2 * d.duPerMetre());
    JsonNode colors = g.path("VANILLA_3D_ACTOR_COLORS");
    return new Environment(
        light,
        tilt,
        ppm / 2f,
        colors.path(0).asInt(d.blueTeam()),
        colors.path(1).asInt(d.redTeam()),
        (float) g.path("BLOB_SHADOW_RADIUS_FACTOR").path(0).asDouble(d.blobRadiusFactor()),
        (float) g.path("BLOB_SHADOW_SOFT_EDGE").path(0).asDouble(d.blobSoftEdge()));
  }

  private static Light light(JsonNode l) {
    return new Light(
        new float[] {
          (float) l.path("rot").path(0).asDouble(),
          (float) l.path("rot").path(1).asDouble(),
          (float) l.path("rot").path(2).asDouble()
        },
        (float) l.path("Intensity").asDouble(1),
        rgb(l.path("Color")),
        rgb(l.path("Ambient")),
        (float) l.path("AmbientSpecIntensity").asDouble(1));
  }

  /** The content folder the index's paths are relative to. */
  public Path folder() {
    return folder;
  }

  /** The extractor's index ({@code index.json}). */
  public JsonNode index() {
    return index;
  }

  /** The model file, read once. */
  public Glb glb(Path file) {
    return glbs.computeIfAbsent(file, Glb::read);
  }

  /** The state machine at that asset path, or null. */
  public Asm asm(String path) {
    if (path == null) return null;
    return asms.computeIfAbsent(
            path,
            p -> {
              String rel = index.path("asm").path(p).asText("");
              if (rel.isEmpty()) return Optional.empty();
              try {
                return Optional.of(
                    Asm.parse(new ObjectMapper().readTree(folder.resolve(rel).toFile())));
              } catch (IOException e) {
                log.warn("Cannot read state machine {}: {}", rel, e.getMessage());
                return Optional.empty();
              }
            })
        .orElse(null);
  }

  /** A side texture of a material ({@code extras.sideTextures}), as a file, if extracted. */
  public Optional<Path> sideTexture(JsonNode extras, String slot) {
    String rel = extras.path("sideTextures").path(slot).asText("");
    if (rel.isEmpty()) return Optional.empty();
    Path p = folder.resolve(rel);
    return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
  }

  @Override
  public String toString() {
    return "Sc3dLibrary["
        + folder
        + ", rows "
        + String.join(",", rows).toLowerCase(Locale.ROOT)
        + "]";
  }
}
