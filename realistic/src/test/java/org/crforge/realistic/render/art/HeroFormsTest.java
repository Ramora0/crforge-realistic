/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.crforge.core.pathfinding.GridEntityState;
import org.crforge.realistic.render.art.model.AnimPlayer;
import org.crforge.realistic.render.art.model.Asm;
import org.junit.jupiter.api.Test;

class HeroFormsTest {

  private static HeroForms.Seen unit(int id, String row, int x, int y, int state) {
    return new HeroForms.Seen(id, row, 0, x, y, state, false);
  }

  private static HeroForms.Seen projectile(int id, String row, int x, int y) {
    return new HeroForms.Seen(id, row, 0, x, y, -1, true);
  }

  @Test
  void aTickOfWarpTravelIsAWarp() {
    HeroForms h = new HeroForms();
    h.observe(10, List.of(unit(1, "MegaMinionHero", 3000, 10000, GridEntityState.ATTACKING)));
    h.observe(11, List.of(unit(1, "MegaMinionHero", 3030, 10040, GridEntityState.MOVING)));
    assertThat(h.warping(1)).isFalse();
    // MegaMinion_hero_teleport_action: 400, then ~850 units a tick
    h.observe(12, List.of(unit(1, "MegaMinionHero", 3130, 10428, GridEntityState.STANDING)));
    assertThat(h.warping(1)).isTrue();
  }

  @Test
  void theBarbarianBarrelHeroRollsFromItsBarrelUntilItDeploysAgain() {
    HeroForms h = new HeroForms();
    String row = "BarbLogBarbarianHero";
    h.observe(1, List.of(unit(1, row, 3500, 14000, GridEntityState.DEPLOYING)));
    h.observe(21, List.of(unit(1, row, 3500, 14000, GridEntityState.CASTING)));
    assertThat(h.rolling(1)).isFalse();
    h.observe(
        28,
        List.of(
            unit(1, row, 3500, 14000, GridEntityState.CASTING),
            projectile(9, "BarbLogHeroProjectileReRolling", 3500, 13150)));
    assertThat(h.rolling(1)).isTrue();
    h.observe(
        40,
        List.of(
            unit(1, row, 3500, 15900, GridEntityState.MOVING),
            projectile(9, "BarbLogHeroProjectileReRolling", 3500, 15000)));
    assertThat(h.rolling(1)).isTrue();
    h.observe(43, List.of(unit(1, row, 3500, 15950, GridEntityState.DEPLOYING)));
    assertThat(h.rolling(1)).isFalse();
    assertThat(h.redeploying(1)).isTrue();
  }

  @Test
  void theBalloonDropsATrooperWithItsCustom1() {
    HeroForms h = new HeroForms();
    h.observe(
        5,
        List.of(
            unit(1, "BalloonHero", 3500, 15000, GridEntityState.MOVING),
            unit(2, "BalloonHero", 14500, 15000, GridEntityState.MOVING),
            projectile(7, "BalloonHero_Skeletrooper_Projectile", 3450, 16800)));
    assertThat(h.takeCue(1)).isEqualTo("Custom1");
    assertThat(h.takeCue(1)).isNull();
    assertThat(h.takeCue(2)).isNull();
  }

  @Test
  void theFormsSwapsAndSpawnsRunTheirForcedAnimations() {
    assertThat(HeroForms.onSwap("WizardHero_air", "WizardHero")).isEqualTo("Custom1");
    assertThat(HeroForms.onSwap("WizardHero", "WizardHero_air")).isNull();
    assertThat(HeroForms.onSpawn("DarkPrinceHero_Mount", GridEntityState.MOVING))
        .isEqualTo("Custom1");
    assertThat(HeroForms.onSpawn("DarkPrinceHero", GridEntityState.DEPLOYING)).isNull();
  }

  @Test
  void anAttackSequencePlaysItsNumberedAttacks() throws Exception {
    Asm asm =
        Asm.parse(
            new ObjectMapper()
                .readTree(
                    """
                    {"States": [{"Name": "Idle", "Animation": "a/i"},
                      {"Name": "Attack", "Animation": "a/a1"},
                      {"Name": "Attack2", "Animation": "a/a2"}], "DefaultState": "Idle"}
                    """));
    AnimPlayer p = new AnimPlayer(asm, Map.of("i", 1f, "a1", 1f, "a2", 1f), 1);
    assertThat(HeroForms.attackState(p, 0)).isEqualTo("Attack");
    assertThat(HeroForms.attackState(p, 1)).isEqualTo("Attack2");
    // no Attack3 here: the first
    assertThat(HeroForms.attackState(p, 2)).isEqualTo("Attack");
  }
}
