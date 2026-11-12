/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The bar's idle reference, the stuck arrows' clip frames and the ability icon's place. */
class PolishRulesTest {

  @Test
  @DisplayName("a unit's bar is measured from its tallest idle facing, not the side view alone")
  void barReferenceIsTheTallestIdleFacing(@TempDir Path dir) throws IOException {
    // idle1_1 (facing away, the sword raised) reaches 87 du up; idle1_5 (side) 45 du
    Path sc = Files.createDirectories(dir.resolve("sc"));
    Files.writeString(
        sc.resolve("chr_test.json"),
        """
        {"format":1,"file":"chr_test.sc","textures":[{"png":"chr_test_0.png","width":4,"height":4}],
         "exports":{"Test_idle1_1":3,"Test_idle1_5":4},
         "shapes":{"1":[{"tex":0,"xy":[-10,-87,10,-87,-10,0,10,0],"uv":[0,0,1,0,0,1,1,1]}],
                   "2":[{"tex":0,"xy":[-10,-45,10,-45,-10,0,10,0],"uv":[0,0,1,0,0,1,1,1]}]},
         "clips":{"3":{"fps":30,"bank":0,"children":[1],"names":[""],"blend":[0],
                       "frames":[[0,65535,65535]],"labels":{},"grid":null},
                  "4":{"fps":30,"bank":0,"children":[2],"names":[""],"blend":[0],
                       "frames":[[0,65535,65535]],"labels":{},"grid":null}},
         "banks":[{"matrices":[],"colors":[]}],"text":[]}
        """);
    ArtCache cache = ArtCache.locateIn(dir).orElseThrow();
    ScScene scene = cache.scene("sc/chr_test.sc");
    VisualLookup.Unit unit = new VisualLookup.Unit(scene, null, 1f);
    int[] idle = new int[9];
    Arrays.fill(idle, -1);
    idle[0] = scene.export("Test_idle1_1");
    idle[4] = scene.export("Test_idle1_5");
    unit.animations.put("idle", idle);
    float[] b = BattleHud.idleBounds(new HudArt(cache), unit);
    assertThat(b).isNotNull();
    assertThat(b[1]).isEqualTo(-87f);
    // with the Knight's HealthBarOffsetY 28 the bar stands 59 du up, over its head (45 du)
    assertThat(b[1] + 28).isLessThan(-45f);
  }

  @Test
  @DisplayName("a playOnce particle clip holds its last frame even where its emitter loops clips")
  void playOnceClipsHoldTheirLastFrame() {
    // Arrows_Hit_Ground: Arrow_enemy_ground_anim_*, 12 frames at 60 fps, labelled playOnce
    EffectDefs.Sprite stuck = new EffectDefs.Sprite(null, 1, 60, 12, true, false);
    assertThat(EffectLayer.particleFrame(stuck, true, 0.1f)).isEqualTo(6);
    assertThat(EffectLayer.particleFrame(stuck, true, 1.5f)).isEqualTo(11);
    EffectDefs.Sprite looping = new EffectDefs.Sprite(null, 1, 60, 12, false, false);
    assertThat(EffectLayer.particleFrame(looping, true, 0.5f)).isEqualTo(30);
    assertThat(EffectLayer.particleFrame(looping, false, 0.5f)).isEqualTo(11);
    EffectDefs.Sprite still = new EffectDefs.Sprite(null, 1, 60, 1, true, false);
    assertThat(EffectLayer.particleFrame(still, true, 2f)).isZero();
  }

  @Test
  @DisplayName("an ability icon goes at its icon field's box centre, the round back's centre")
  void abilityIconAtTheFieldBoxCentre() {
    // icon_ability's field 10 sits at (-11.15, 7.55) from the back's centre, its box -2..24 x
    // -23..6; icon_cast's field 15 at (-35, 33.3) at its first frame, its box -2..72 x -75..6
    float[] c = BattleHud.iconFieldCentre(10);
    assertThat(-11.15f + c[0]).isBetween(-0.5f, 0.5f);
    assertThat(7.55f + c[1]).isBetween(-1.5f, 0.5f);
    float[] cast = BattleHud.iconFieldCentre(15);
    assertThat(-35f + cast[0]).isBetween(-0.5f, 0.5f);
    assertThat(33.3f + cast[1]).isBetween(-1.5f, 0.5f);
    assertThat(BattleHud.iconFieldCentre(99)).containsExactly(0f, 0f);
  }
}
