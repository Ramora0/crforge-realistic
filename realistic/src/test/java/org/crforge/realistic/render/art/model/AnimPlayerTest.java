/*
 * crforge - https://github.com/voonhous/crforge
 * SPDX-License-Identifier: Apache-2.0
 * Porting this code? Please cite this file and the commit you read: see the README.
 */

package org.crforge.realistic.render.art.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.crforge.core.pathfinding.GridEntityState;
import org.junit.jupiter.api.Test;

class AnimPlayerTest {

  /** The Hero Tombstone's state machine (asm_hero_tombstone_battle), abridged. */
  private static final String TOMBSTONE =
      """
      {"States": [
        {"Type": "AnimClip", "Name": "BasicIdle",
         "Animation": "sc3d/characters/hero/hero_tombstone/animation/hero_tombstone_idle | x",
         "StartFrame": 0, "EndFrame": 23},
        {"Type": "AnimClip", "Name": "IdleBreak",
         "Animation": "sc3d/characters/hero/hero_tombstone/animation/hero_tombstone_idle | x"},
        {"Type": "Random", "Name": "Idle", "SubStates": ["BasicIdle", "BasicIdle", "BasicIdle",
          "BasicIdle", "BasicIdle", "BasicIdle", "BasicIdle", "IdleBreak"]},
        {"Type": "AnimClip", "Name": "Deploy", "BaseSpeed": 1.4,
         "Animation": "sc3d/characters/hero/hero_tombstone/animation/hero_tombstone_deploy | x"}],
       "DefaultState": "Idle"}
      """;

  /** The broken tomb's (asm_hero_tombstone_broken_battle). */
  private static final String BROKEN =
      """
      {"States": [
        {"Type": "AnimClip", "Name": "Custom1", "Animation": "a/hero_tombstone_destroyed_deploy"},
        {"Type": "AnimClip", "Name": "BasicIdle", "Animation": "a/hero_tombstone_destroyed_idle",
         "TransInTime": 0.2},
        {"Type": "AnimClip", "Name": "Custom2", "Animation": "a/hero_tombstone_destroyed_spawn"},
        {"Type": "AnimClip", "Name": "SpawnLoop", "Loop": true,
         "Animation": "a/hero_tombstone_destroyed_spawn", "StartFrame": 29, "EndFrame": 30}],
       "Transitions": [{"SourceState": "Custom2", "TargetState": "BasicIdle",
         "TransitionState": "SpawnLoop"}],
       "DefaultState": "BasicIdle"}
      """;

  /** The Hero Knight's (asm_hero_knight_battle), abridged: Selectors over Randoms. */
  private static final String KNIGHT =
      """
      {"States": [
        {"Type": "AnimClip", "Name": "Attack1", "Animation": "a/knight_attack1_noshield",
         "Events": [{"Event": "Marker", "Name": "action_frame", "Frame": 12}]},
        {"Type": "AnimClip", "Name": "Attack2", "Animation": "a/knight_attack2_noshield",
         "Events": [{"Event": "Marker", "Name": "action_frame", "Frame": 12}]},
        {"Type": "AnimClip", "Name": "IdleBasic", "Loop": true, "Animation": "a/knight_idle_noshield"},
        {"Type": "AnimClip", "Name": "Idle_shield", "Loop": true, "Animation": "a/knight_idle"},
        {"Type": "AnimClip", "Name": "Attack1_shield", "Animation": "a/knight_attack1"},
        {"Type": "Random", "Name": "AttackBasic", "SubStates": ["Attack1", "Attack2"]},
        {"Type": "Selector", "Name": "Attack", "SubStates": ["AttackBasic", "Attack1_shield"],
         "SwitchValues": [0, 1]},
        {"Type": "Selector", "Name": "Idle", "Loop": true, "SubStates": ["IdleBasic", "Idle_shield"],
         "Parameter": 2, "SwitchValues": [0, 1]},
        {"Type": "AnimClip", "Name": "VictoryStart", "Animation": "a/victory_start"},
        {"Type": "AnimClip", "Name": "VictoryLoop", "Loop": true, "Animation": "a/victory_loop"},
        {"Type": "Sequence", "Name": "Victory", "SubStates": ["VictoryStart", "VictoryLoop"]}],
       "DefaultState": "Idle"}
      """;

  private static Asm asm(String json) throws Exception {
    return Asm.parse(new ObjectMapper().readTree(json));
  }

  @Test
  void theStateMachineReadsClipsRangesSpeedsAndTransitions() throws Exception {
    Asm a = asm(TOMBSTONE);
    assertThat(a.defaultState()).isEqualTo("Idle");
    assertThat(a.states().get("Deploy").clip()).isEqualTo("hero_tombstone_deploy");
    assertThat(a.states().get("Deploy").speed()).isEqualTo(1.4f);
    assertThat(a.states().get("BasicIdle").endFrame()).isEqualTo(23);
    assertThat(a.states().get("Idle").random()).isTrue();
    assertThat(asm(BROKEN).after()).containsEntry("Custom2", "SpawnLoop");
  }

  @Test
  void theDeployPlaysOnceAtItsSpeedThenTheIdlePicks() throws Exception {
    // deploy 21 frames (0.7 s) at 1.4: 0.5 s; idle 63 frames, BasicIdle its first 23
    AnimPlayer p =
        new AnimPlayer(
            asm(TOMBSTONE), Map.of("hero_tombstone_deploy", 0.7f, "hero_tombstone_idle", 2.1f), 7);
    p.play("Deploy", 10f);
    p.want("Idle", 10f);
    AnimPlayer.Sample s = p.sample(10.25f);
    assertThat(s.clip()).isEqualTo("hero_tombstone_deploy");
    assertThat(s.seconds()).isCloseTo(0.35f, within(1e-4f));
    p.want("Idle", 10.6f);
    s = p.sample(10.6f);
    assertThat(p.state()).isEqualTo("Idle");
    assertThat(s.clip()).isEqualTo("hero_tombstone_idle");
    // each idle pass is a BasicIdle (23 frames) or an IdleBreak (the whole clip)
    Set<Float> seen = new HashSet<>();
    for (float t = 10.6f; t < 60f; t += 0.05f) {
      p.want("Idle", t);
      s = p.sample(t);
      assertThat(s.clip()).isEqualTo("hero_tombstone_idle");
      seen.add(s.seconds() > 23 / 30f + 1e-3f ? 1f : 0f);
    }
    assertThat(seen).containsExactlyInAnyOrder(0f, 1f);
  }

  @Test
  void theRandomPicksAreTheSameForTheSameUnit() throws Exception {
    Map<String, Float> lengths = Map.of("hero_tombstone_deploy", 0.7f, "hero_tombstone_idle", 2.1f);
    AnimPlayer a = new AnimPlayer(asm(TOMBSTONE), lengths, 5000011);
    AnimPlayer b = new AnimPlayer(asm(TOMBSTONE), lengths, 5000011);
    for (float t = 0; t < 30f; t += 0.1f) {
      a.want("Idle", t);
      b.want("Idle", t);
      assertThat(a.sample(t)).isEqualTo(b.sample(t));
    }
  }

  @Test
  void theScatterEndsInItsHeldLoopThroughTheTransition() throws Exception {
    // destroyed_spawn: 30 frames (1 s); SpawnLoop holds its frames 29..30
    AnimPlayer p =
        new AnimPlayer(
            asm(BROKEN),
            Map.of(
                "hero_tombstone_destroyed_deploy", 1f,
                "hero_tombstone_destroyed_idle", 1f,
                "hero_tombstone_destroyed_spawn", 1f),
            1);
    p.want("Idle", 20f); // no Idle here: the default, BasicIdle
    assertThat(p.state()).isEqualTo("BasicIdle");
    p.play("Custom2", 20.5f);
    assertThat(p.sample(21f).clip()).isEqualTo("hero_tombstone_destroyed_spawn");
    p.want("Idle", 21.6f);
    AnimPlayer.Sample s = p.sample(21.6f);
    assertThat(p.state()).isEqualTo("SpawnLoop");
    assertThat(s.seconds()).isBetween(29 / 30f, 30 / 30f + 1e-4f);
    // and stays there while the unit idles
    p.want("Idle", 23f);
    assertThat(p.sample(23f).clip()).isEqualTo("hero_tombstone_destroyed_spawn");
    assertThat(p.state()).isEqualTo("SpawnLoop");
  }

  @Test
  void anAttackIsPosedAtTheTimeItsMarkerMeetsTheHit() throws Exception {
    String skeleton =
        """
        {"States": [
          {"Type": "AnimClip", "Name": "Idle", "Animation": "a/sk_idle"},
          {"Type": "AnimClip", "Name": "Attack", "Animation": "a/sk_attack",
           "Events": [{"Event": "Marker", "Name": "action_frame", "Frame": 12}]}],
         "DefaultState": "Idle"}
        """;
    AnimPlayer p = new AnimPlayer(asm(skeleton), Map.of("sk_idle", 1f, "sk_attack", 1f), 1);
    assertThat(p.marker("Attack", "action_frame")).isCloseTo(0.4f, within(1e-4f));
    assertThat(p.at("Attack", 0.4f).seconds()).isCloseTo(0.4f, within(1e-4f));
    assertThat(p.at("Idle", 0f)).isNotNull();
    assertThat(p.at("Nothing", 0f)).isNull();
  }

  @Test
  void theSimulatorStatesMapThroughTheRemappingTable() {
    assertThat(AnimStates.name(AnimStates.of(GridEntityState.MOVING))).isEqualTo("Walk");
    assertThat(AnimStates.name(AnimStates.of(GridEntityState.ATTACKING))).isEqualTo("Attack");
    assertThat(AnimStates.name(AnimStates.of(GridEntityState.DEPLOYING))).isEqualTo("Deploy");
    assertThat(AnimStates.name(AnimStates.of(GridEntityState.STANDING))).isEqualTo("Idle");
    assertThat(AnimStates.name(AnimStates.custom(1))).isEqualTo("Custom1");
    assertThat(AnimStates.name(AnimStates.custom(2))).isEqualTo("Custom2");
    assertThat(AnimStates.custom(9)).isEqualTo(-1);
  }

  @Test
  void aSelectorPlaysTheSubStateItsParameterSelects() throws Exception {
    Map<String, Float> lengths =
        Map.of("knight_idle_noshield", 1f, "knight_idle", 1.5f, "knight_attack1", 1f);
    AnimPlayer p = new AnimPlayer(asm(KNIGHT), lengths, 3);
    p.want("Idle", 0f);
    assertThat(p.sample(0.2f).clip()).isEqualTo("knight_idle_noshield");
    // the Idle Selector reads parameter 2 (the JSON's Parameter)
    p.setParameter(2, 1);
    assertThat(p.sample(0.4f).clip()).isEqualTo("knight_idle");
    // the Attack Selector names no Parameter: index 0
    p.setParameter(0, 1);
    assertThat(p.at("Attack", 0.1f).clip()).isEqualTo("knight_attack1");
  }

  @Test
  void anAttackThroughASelectorAndARandomPicksAgainForEachHit() throws Exception {
    Map<String, Float> lengths =
        Map.of("knight_attack1_noshield", 1f, "knight_attack2_noshield", 1f);
    AnimPlayer p = new AnimPlayer(asm(KNIGHT), lengths, 11);
    // the marker reads through the Selector and the Random to the clip
    assertThat(p.marker("Attack", "action_frame")).isCloseTo(0.4f, within(1e-4f));
    assertThat(p.length("Attack")).isEqualTo(1f);
    Set<String> clips = new HashSet<>();
    for (int hit = 0; hit < 12; hit++) {
      for (float t = 0f; t < 0.9f; t += 0.3f) clips.add(p.at("Attack", t).clip());
    }
    assertThat(clips)
        .containsExactlyInAnyOrder("knight_attack1_noshield", "knight_attack2_noshield");
  }

  @Test
  void aSequencePlaysItsSubStatesInTurnTheLastRepeating() throws Exception {
    Map<String, Float> lengths = Map.of("victory_start", 1f, "victory_loop", 0.5f);
    AnimPlayer p = new AnimPlayer(asm(KNIGHT), lengths, 1);
    p.want("Victory", 0f);
    assertThat(p.sample(0.5f).clip()).isEqualTo("victory_start");
    AnimPlayer.Sample s = p.sample(1.2f);
    assertThat(s.clip()).isEqualTo("victory_loop");
    assertThat(s.seconds()).isCloseTo(0.2f, within(1e-4f));
    p.want("Victory", 3.1f);
    s = p.sample(3.1f);
    assertThat(s.clip()).isEqualTo("victory_loop");
    assertThat(s.seconds()).isCloseTo(0.1f, within(1e-3f));
  }

  /** The mounted Dark Prince's selectors (asm_hero_darkprince_mounted_battle), abridged. */
  private static final String SELECTORS =
      """
      {"States": [
        {"Type": "AnimClip", "Name": "Idle_NoShield", "Animation": "a/idle_noshield"},
        {"Type": "AnimClip", "Name": "Idle_Shield", "Animation": "a/idle", "TransInTime": 0.2},
        {"Type": "Selector", "Name": "Idle", "SubStates": ["Idle_NoShield", "Idle_Shield"],
         "SwitchValues": [0, 1]},
        {"Type": "AnimClip", "Name": "Attack_NoShield", "Animation": "a/attack_noshield",
         "Events": [{"Event": "Marker", "Name": "action_frame", "Frame": 6}]},
        {"Type": "AnimClip", "Name": "Attack_Shield", "Animation": "a/attack",
         "Events": [{"Event": "Marker", "Name": "action_frame", "Frame": 15}]},
        {"Type": "Selector", "Name": "Attack", "SubStates": ["Attack_NoShield", "Attack_Shield"],
         "SwitchValues": [0, 1]},
        {"Type": "AnimClip", "Name": "Walk_Prop", "Animation": "a/walk_prop"},
        {"Type": "AnimClip", "Name": "Walk_Origin", "Animation": "a/walk"},
        {"Type": "Selector", "Name": "Walk", "Loop": true, "Parameter": 2,
         "SubStates": ["Walk_Prop", "Walk_Origin"], "SwitchValues": [1, 0]}],
       "DefaultState": "Idle"}
      """;

  private static final Map<String, Float> SELECTOR_CLIPS =
      Map.of(
          "idle_noshield",
          1f,
          "idle",
          1f,
          "attack_noshield",
          1f,
          "attack",
          1f,
          "walk_prop",
          1f,
          "walk",
          1f);

  @Test
  void aSelectorCrossFadesWhenItsParameterChanges() throws Exception {
    Asm a = asm(SELECTORS);
    assertThat(a.states().get("Walk").selector()).isTrue();
    assertThat(a.states().get("Walk").parameter()).isEqualTo(2);
    assertThat(a.states().get("Walk").select(1)).isEqualTo("Walk_Prop");
    assertThat(a.states().get("Idle").parameter()).isZero();
    AnimPlayer p = new AnimPlayer(a, SELECTOR_CLIPS, 1);
    p.want("Idle", 0f);
    assertThat(p.sample(0.1f).clip()).isEqualTo("idle_noshield");
    // the shield comes up: the selector cross-fades to its other sub-state
    p.parameter(0, 1, 0.5f);
    AnimPlayer.Sample s = p.sample(0.6f);
    assertThat(s.clip()).isEqualTo("idle");
    assertThat(s.fromClip()).isEqualTo("idle_noshield");
    assertThat(s.blend()).isCloseTo(0.5f, within(1e-3f));
    // a selector's attack has its sub-state's marker and clip
    assertThat(p.marker("Attack", "action_frame")).isCloseTo(0.5f, within(1e-4f));
    assertThat(p.at("Attack", 0.2f).clip()).isEqualTo("attack");
    p.parameter(2, 1, 1f);
    p.want("Walk", 1f);
    assertThat(p.sample(1.5f).clip()).isEqualTo("walk_prop");
  }

  @Test
  void aSwappedMachineGoesOnFromTheOldPose() throws Exception {
    String bear =
        """
        {"States": [
          {"Type": "AnimClip", "Name": "Idle", "Animation": "a/bear_idle"},
          {"Type": "AnimClip", "Name": "Skill", "Animation": "a/bear_skill"}],
         "DefaultState": "Idle"}
        """;
    String human =
        """
        {"States": [
          {"Type": "AnimClip", "Name": "Idle", "Animation": "a/idle"},
          {"Type": "AnimClip", "Name": "Skill", "Animation": "a/bear_skill"}],
         "DefaultState": "Idle"}
        """;
    Map<String, Float> clips = Map.of("bear_idle", 1f, "bear_skill", 2f, "idle", 1f);
    AnimPlayer old = new AnimPlayer(asm(human), clips, 1);
    old.want("Skill", 10f);
    // the same clip in the new machine goes on at the old time
    AnimPlayer next = new AnimPlayer(asm(bear), clips, 1);
    next.adopt(old, 10.5f);
    next.want("Skill", 10.5f);
    AnimPlayer.Sample s = next.sample(10.75f);
    assertThat(s.clip()).isEqualTo("bear_skill");
    assertThat(s.seconds()).isCloseTo(0.75f, within(1e-4f));
    assertThat(s.fromClip()).isNull();
    // another clip cross-fades from the old pose
    AnimPlayer back = new AnimPlayer(asm(human), clips, 1);
    back.adopt(next, 11f);
    back.want("Idle", 11f);
    s = back.sample(11.05f);
    assertThat(s.clip()).isEqualTo("idle");
    assertThat(s.fromClip()).isEqualTo("bear_skill");
    assertThat(s.blend()).isLessThan(1f);
  }
}
