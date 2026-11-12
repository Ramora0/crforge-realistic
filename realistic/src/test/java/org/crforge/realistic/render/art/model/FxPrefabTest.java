/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The effect prefabs' timelines, materials and node trees (docs §10.4). */
class FxPrefabTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path dir;

  // ------------------------------------------------------------------------------------ timeline

  @Test
  void timelineKeysHoldConstantsLinesAndEndValues() throws IOException {
    FxTimeline t =
        FxTimeline.parse(
            JSON.readTree(
                """
                {"anims": {"nodes": ["glow"], "data": [{"anims": [
                  {"id": "Model.mat0.mainColor.w", "value": {"_v": {"timelineValue": {"keys": [
                    {"e": 0.1},
                    {"t": 1, "s": 0.1, "e": 0.3, "lf": 2, "lt": 3},
                    {"s": 0.3, "e": 0.3, "c": 0.7}]}}}}]}]},
                 "total_time": 1}
                """));
    FxTimeline.Curve c = t.tracks().get("glow").curves().get("Model.mat0.mainColor.w");
    assertThat(c.at(0.05f)).isEqualTo(0f); // a key without a type: its constant, 0 left out
    assertThat(c.at(0.2f)).isCloseTo(2.5f, within(1e-4f)); // a line from lf to lt
    assertThat(c.at(0.9f)).isEqualTo(0.7f); // after the last key: its value
    assertThat(t.total()).isEqualTo(1f);
  }

  @Test
  void bezierEasingMeetsItsEndsAndTreatsMissingControlsAsTheLine() {
    float[] easeIn = {0.42f, 0f, 1f, 1f};
    assertThat(FxTimeline.ease(easeIn, 0f)).isEqualTo(0f);
    assertThat(FxTimeline.ease(easeIn, 1f)).isEqualTo(1f);
    assertThat(FxTimeline.ease(easeIn, 0.5f)).isLessThan(0.5f);
    float[] nan = {0.25f, Float.NaN, 0.75f, Float.NaN};
    assertThat(FxTimeline.ease(nan, 0.3f)).isCloseTo(0.3f, within(1e-3f));
  }

  // ------------------------------------------------------------------------------------ material

  @Test
  void materialMergesItsBaseChainAndTakesTimelineValues() throws IOException {
    write(
        "sc3d/materials/base.json",
        """
        {"version": 1, "base": null, "shader": "vfx_mask",
         "params": {"mainColor": [1, 1, 1, 1], "mainTex_UV": [1, 1, 0, 0], "mainTex": "",
                    "enableBillboard": false}}
        """);
    write(
        "sc3d/materials/glow.json",
        """
        {"version": 1, "base": "sc3d/base.rmat", "blend": 6, "order": 20,
         "params": {"mainColor": [0.5, 0.25, 1, 0.7], "enableBillboard": true,
                    "mainTex": "sc3d/t.ktx"},
         "textures": {"mainTex": "sc3d/textures/t.png"}}
        """);
    FxLibrary lib = library("{}", "{}");
    FxMaterial m = lib.material("sc3d/glow");
    assertThat(m.shader()).isEqualTo("vfx_mask");
    assertThat(m.additive()).isTrue();
    assertThat(m.order()).isEqualTo(20);
    assertThat(m.flag("enableBillboard")).isTrue();
    assertThat(m.param("mainColor")).containsExactly(0.5f, 0.25f, 1f, 0.7f);
    assertThat(m.param("mainTex_UV")).containsExactly(1f, 1f, 0f, 0f); // the base's
    assertThat(m.textures()).containsEntry("mainTex", "sc3d/textures/t.png");
    FxMaterial a = m.animated();
    a.set("mainColor.w", 3f);
    a.set("disv_amount", 0.4f);
    assertThat(a.param("mainColor")[3]).isEqualTo(3f);
    assertThat(a.param("disv_amount")[0]).isEqualTo(0.4f);
    assertThat(m.param("mainColor")[3]).isEqualTo(0.7f); // the material itself is unchanged
  }

  @Test
  void glowUniformsPackFresnelAndRimTimesTheirMultiplies() {
    FxMaterial m =
        new FxMaterial(
            "x",
            "uber_pbr",
            new java.util.HashMap<>(
                java.util.Map.of(
                    "fresnelColor", new float[] {1f, 0.5f, 0f, 0f},
                    "fr_colorMul", new float[] {2f},
                    "fr_weight", new float[] {0.9f},
                    "fr_IOR", new float[] {0.96f},
                    "rimLightColor", new float[] {1f, 1f, 1f, 0f},
                    "rl_colorMul", new float[] {2f},
                    "rl_weight", new float[] {0.6f},
                    "rl_pow", new float[] {0.8f},
                    "rimLightDir", new float[] {0f, 0f, 1f})),
            java.util.Map.of("enableFresnel", true, "enableRimLight", true),
            java.util.Map.of(),
            0,
            0);
    float[] g = FxRenderer.glow(m);
    assertThat(g).hasSize(14);
    assertThat(new float[] {g[0], g[1], g[2], g[3], g[4]}).containsExactly(2f, 1f, 0f, 0.9f, 0.96f);
    assertThat(new float[] {g[5], g[8], g[9]}).containsExactly(2f, 0.6f, 0.8f);
    // view-space +z (towards the viewer) is the camera's back direction in prefab space
    assertThat(g[11]).isCloseTo(0f, within(1e-5f));
    assertThat(g[12]).isCloseTo(ModelCamera.SIN, within(1e-5f));
    assertThat(g[13]).isCloseTo(ModelCamera.COS, within(1e-5f));
    FxMaterial plain =
        new FxMaterial(
            "y", "uber_pbr", java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), 0, 0);
    assertThat(FxRenderer.glow(plain)).isNull();
  }

  // -------------------------------------------------------------------------------------- prefab

  @Test
  void prefabPosesItsNodesByItsTimelineAndSpawnsItsEventPrefabs() throws IOException {
    write(
        "sc3d/prefabs/fx.json",
        """
        {"data": {"name": "root", "children": [
          {"name": "pos", "pos": [0, 0, 0], "components": [
            {"objectType": 5014, "timelines": [{"name": "t", "asset": "sc3d/tl | u"}]},
            {"objectType": 5016, "TimelineName": "t"}],
           "children": [
            {"name": "ring", "pos": [0, 0, 1], "scl": [2, 2, 2], "components": [
              {"objectType": 5001, "Model": "sc3d/mesh | u", "Layer": 2,
               "Geometries": [{"Name": "mesh", "MaterialOverride": "sc3d/mat | u"}]}]},
            {"name": "glow", "components": [
              {"objectType": 5018, "MaterialAsset": "sc3d/mat | u",
               "GeometryNames": ["Body_LOD1"]}]}]}]}}
        """);
    write(
        "sc3d/prefabs/sub.json",
        """
        {"data": {"name": "root", "children": [
          {"name": "n", "components": [{"objectType": 5001, "Model": "sc3d/mesh | u"}]}]}}
        """);
    write(
        "sc3d/timelines/tl.json",
        """
        {"anims": {"nodes": ["", "ring", "glow"], "data": [
          {"events": [{"Time": 0.5, "Effect": "sc3d/sub | u"}]},
          {"anims": [
            {"id": "scl_x", "value": {"_v": {"timelineValue": {"keys": [
              {"t": 1, "e": 1, "lf": 1, "lt": 3}]}}}},
            {"id": "Model.mat0.mainColor.w", "value": {"_v": {"timelineValue": {"keys": [
              {"c": 0.25}]}}}}]},
          {"anims": [
            {"id": "MaterialEffect.mat.fr_weight", "value": {"_v": {"timelineValue": {"keys": [
              {"c": 0.5}]}}}}]}]},
         "total_time": 1}
        """);
    write(
        "sc3d/materials/mat.json",
        """
        {"version": 1, "shader": "vfx_mask", "params": {"mainColor": [1, 1, 1, 1]}}
        """);
    FxLibrary lib =
        library(
            """
            {"sc3d/fx": {"json": "sc3d/prefabs/fx.json"}, "sc3d/sub": {"json": "sc3d/prefabs/sub.json"}}
            """,
            """
            {"sc3d/tl": "sc3d/timelines/tl.json"}
            """);
    FxPrefab p = lib.prefab("sc3d/fx");
    assertThat(p.duration()).isEqualTo(1f);
    assertThat(p.find(1, "ring")).isEqualTo(2);
    List<FxPrefab.Item> items = new ArrayList<>();
    List<FxPrefab.Glow> glows = new ArrayList<>();
    p.evaluate(0.25f, FxPrefab.identity(), null, items, glows, 0);
    assertThat(items).hasSize(1); // the event's prefab is not spawned yet
    FxPrefab.Item ring = items.get(0);
    assertThat(ring.model().layer()).isEqualTo(2);
    assertThat(ring.matrix()[0]).isCloseTo(1.5f, within(1e-5f)); // scl_x 1 -> 3 over 1 s
    assertThat(ring.matrix()[5]).isCloseTo(2f, within(1e-5f)); // scl_y as the node has it
    assertThat(ring.matrix()[14]).isCloseTo(1f, within(1e-5f));
    assertThat(ring.override().param("mainColor")[3]).isEqualTo(0.25f);
    assertThat(glows).hasSize(1);
    assertThat(glows.get(0).geometries()).containsExactly("Body_LOD1");
    assertThat(glows.get(0).material().param("fr_weight")[0]).isEqualTo(0.5f);
    items.clear();
    p.evaluate(0.75f, FxPrefab.identity(), null, items, new ArrayList<>(), 0);
    assertThat(items).extracting(i -> i.prefab()).containsExactly("sc3d/fx", "sc3d/sub");
  }

  @Test
  void eulerReadingIsRzRyRx() {
    float[] m = FxPrefab.trs(new float[] {1, 2, 3}, new float[] {0, 0, 90}, new float[] {2, 1, 1});
    // x scaled by 2 then turned 90 degrees about z: (2, 0, 0) -> (0, 2, 0)
    assertThat(m[0]).isCloseTo(0f, within(1e-5f));
    assertThat(m[1]).isCloseTo(2f, within(1e-5f));
    assertThat(new float[] {m[12], m[13], m[14]}).containsExactly(1f, 2f, 3f);
  }

  // ------------------------------------------------------------------------------------- helpers

  private void write(String rel, String text) throws IOException {
    Path p = dir.resolve(rel);
    Files.createDirectories(p.getParent());
    Files.writeString(p, text);
  }

  private FxLibrary library(String prefabs, String timelines) throws IOException {
    var index = JSON.createObjectNode();
    index.set("prefabs", JSON.readTree(prefabs));
    index.set("timelines", JSON.readTree(timelines));
    var mats = JSON.createObjectNode();
    mats.put("sc3d/glow", "sc3d/materials/glow.json");
    mats.put("sc3d/base.rmat", "sc3d/materials/base.json");
    mats.put("sc3d/mat", "sc3d/materials/mat.json");
    index.set("materials", mats);
    return new FxLibrary(new Sc3dLibrary(dir, index, List.of("*")));
  }
}
