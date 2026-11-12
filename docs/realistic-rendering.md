# Realistic rendering

crforge's desktop module can draw a battle with the game's own art, sounds and HUD in place of the debug visualizer's shapes. The art is never part of this repository: it is extracted from the user's own Clash Royale installation into a local cache, the way the game tables are built from the game's files (see [Game tables](game-tables.md)).

<p align="center"><i>Arena, towers, troops, shadows, effects and HUD below are the game's own movie clips, played by the simulator.</i></p>

## Using it

1. **Build the art cache** from an installation (once per game version). The tool lives in `tools/game-art` (Python, [uv](https://docs.astral.sh/uv/)):

   ```bash
   cd tools/game-art
   uv run crforge-art extract --adb                        # a phone with the game, over adb
   uv run crforge-art extract --apk base.apk split_*.apk   # or the APKs (or an .xapk/.apkm bundle)
   uv run crforge-art extract --assets path/to/assets      # or an already-extracted assets/ folder
   # add --audio to also decode the battle sounds (needs libvorbis: brew install libvorbis)
   ```

   It writes `~/.crforge/art/<content sha>/` (about 1.3 GB): each SC2 file's scene graph as JSON and its atlases as PNG, the client tables, the arena location files and, with `--audio`, the sounds. Nothing is pre-rendered: the desktop plays the clips itself. `tools/game-art/README.md` has the details and the legal note: the art is Supercell's, for personal use under its Fan Content Policy, and must never be committed or redistributed.

2. **In the visualizer**, press **X** (or start it with `--args="--realistic"`) to swap the debug shapes for the game's art. The debug overlays and inspector keep working on top. Press **U** (or start with `--pov`) for the player's view: the HUD of the side at the bottom, as its own phone shows a live battle. The other side's hand and elixir panel goes, and the realistic view puts that side's name in the live top bar at the arena's top left. **F** flips the view, and with it whose view it is.

3. **Record a video** of a replay, offscreen, framed like a phone (1080x2340, 60 fps, HUD included):

   ```bash
   ./gradlew :desktop:run --args="--replay examples/replays/ladder.json --record battle.mp4"
   #   [--size 1080x2340] [--fps 60] [--from SECONDS] [--seconds N] [--bottom 0|1|blue|red]
   #   [--pov | --player-view] [--location PvP_royal] [--sc3d <folder>|off] [--sc3d-rows <prefixes>|*]
   #   a .png target writes one still at --from
   cd tools/game-art && uv run crforge-art audio ../../battle.mp4   # adds the game's sounds and music
   ```

   Positions are interpolated between the battle's 50 ms ticks, so the video plays smoothly at 60 fps.

   The Hero Tombstone's rows are drawn with their 3D models when `crforge-art sc3d` has extracted them: by default from `<art cache>/sc3d`, or from the folder `--sc3d` names (`off` keeps the 2D stand-ins); `--sc3d-rows` widens the rows drawn in 3D (row name prefixes, `*` for all, unchecked against the game). See §10.1.

   `--bottom` picks whose game view it is: side 0 (`blue`, the default) or side 1 (`red`). The colours are those of the default view; whichever side is at the bottom is drawn blue. The default HUD is the replay HUD, with both sides' hands and elixir. `--pov` records the player's view instead: the bottom side's hand, next card, elixir and ability buttons as they are; no hand, next card, elixir or ability buttons for the top side; and the live battle's top bar (§8, *The player's view*). The opponent's name is the replay's (`battle.avatar<side>.name`), or "Opponent" when the replay names no player.

4. **Play from your phone**: `./gradlew :desktop:run --args="--serve"` streams the realistic view of a live battle to a phone's browser and takes your taps back ([phone server](phone-server.md)).

## How it is drawn

`desktop/src/main/java/org/crforge/desktop/render/art`:

| Class | Role |
|---|---|
| `ArtCache`, `ScScene` | find the cache; read a scene JSON (shapes, movie clips, matrix banks) |
| `ScDrawer` | play a movie clip at a frame: each child at its matrix and colour transform (multiply and add), each shape as textured triangle strips, the clip's blend modes |
| `ArtProjection` | the arena art's anisotropic projection (§3) |
| `ArenaScene` | the arena from its location file: background plus decorations (§4) |
| `RealisticRenderer` | every troop, building, tower and projectile: animation, facing, shadows, deploy filter (§1, 2, 5, 6, 8) |
| `EffectLayer`, `EffectDefs`, `EffectDrawer` | the effects table's effects with a particle simulator, tower ruins, and the sounds the effects start (§7) |
| `BattleHud`, `HudArt`, `HudFonts` | health bars, tower bars, hands, elixir, ability buttons, timer and crowns from the game's UI art; the replay HUD or the player's view (§8) |
| `CinematicRecorder` | `--record`: the offscreen video and its sound events |
| `MatchEnd` | crown sounds, the freeze at the match's end and the replay end screen (§9) |
| `RealisticView` | the visualizer's X mode |
| `model/*` (`Sc3dLibrary`, `Glb`, `Rig`, `Asm`, `AnimPlayer`, `ModelCamera`, `UberPbr`, `ModelRenderer`) | 3D units from the sc3d extraction: rig, state machines, the battle camera, the game's `uber_pbr` shader, compositing into the 2D draw order (§10.1) |

The rest of this document is the evidence behind the rules those classes follow.

# What to draw and where

This spec covers the realistic-render mode, which draws each simulated entity with the game's own SC2 movie clips. For every entity it says which export to play, at which frame, where to put it, how big to draw it and in what order. Each rule carries a confidence label:

- **DATA**: read directly from a game table or an `.sc` file.
- **INFERRED**: a pattern that holds across many units, or that a column name implies.
- **GUESS**: no evidence yet. The rule says what would confirm it.

Sources (paths in the game's assets, as the art cache keeps them):

| Name | Path |
|---|---|
| sc | `assets/sc/*.sc`, read with `tools/game-art` `crforge_art.sc2` |
| tables | the game tables the simulator runs (`~/.crforge/tables/<version>/*.json`) |
| toml | `assets/csv_logic`, decoded into the art cache's `client/csv_logic` |
| vfx | `assets/legacy_vfx/effects.json` and `emitters.json` (the cache's `client/legacy_vfx`) |

Display units (**du**) are the SC2 coordinate units. Clip matrices and shapes are in du, and 1 du is one pixel at 1x.

---

## 1. Troop animations

### 1.1 Export names

**Rule.** For a row and a team colour, the base name is `BlueExportName` when the entity's side is drawn blue (`ViewOrientation.blue(side)`), else `RedExportName`. The clip for an action, direction and team is:

```
<base>_<action><n>_<dir>      e.g. Knight_attack1_3, chr_musketeer_blue_run1_9, hog_rider_red_idle1_5
```

`<action><n>` is the action name plus a variant number, for example `attack1`, `attack2` or `run1`. `<dir>` is 1..N (see §2). Look it up in the sc file named by the row's `FileName` column. When `<base>_<action>1_<dir>` is missing, fall back in this order:

1. `idle1`.
2. `<base>` itself, used as a single clip with no action or direction. Buildings such as `building_cannon`, `tesla1_blue` and `KingTower_blue` work this way.

**Evidence.**

- The 613 character and building rows that have a `FileName` all resolve to an existing `.sc` file.
- `CustomAnimationPostfix` values are `_ability1_`, `_landing1_` and `_attack2_`. They show that the client builds the name as `base + "_" + action + "_" + dir`.
- Blue and red bases differ per unit and are not derivable:

  | Unit | Blue base | Red base |
  |---|---|---|
  | Knight | `Knight` | `Knight_enemy` |
  | Archers | `archer1` | `archer1_enemy` |
  | Musketeer | `chr_musketeer_blue` | `chr_musketeer` |
  | Giant | `giant1` | `giant1_enemy` |
  | Hog Rider | `hog_rider` | `hog_rider_red` |
  | Skeleton | `skeleton1` | `skeleton1` |

  Always read both columns.
- Warning: a blue base can be a prefix of the red one (`Knight`, `Knight_enemy`). Match exports by the exact pattern `^<base>_<action>\d*_\d+$`, not by `startswith`.

**Confidence.** DATA.

**Hero rows (INFERRED).** A hero row (`TombstoneHero`, `KnightHero`, `TombstoneHeroSkeleton`, …) has an empty `FileName` and export names: the game draws it with the 3D model its `PrefabAsset` names (`sc3d/...`), which the 2D art cache does not hold (`crforge-art sc3d` now extracts these models as glTF, §10). The Hero Tombstone's rows are drawn with their 3D models when an sc3d extraction is present (§10.1). Any other such row, when it has a `PrefabAsset` and a `Base` (`BUILDING.Tombstone`, `CHARACTER.Skeleton`), is drawn with its `Base` row's SC2 art, followed recursively. A row without a `Base` stays undrawn: the Hero Tombstone's `TombstoneHero_visual_dummy`, `TombstoneHero_Monster_Passive` and its Skeleton Queen (`TombstoneHero_Monster_Active`) have no SC2 art at all. The hero deploy effects themselves (`vfx_tombstone_hero_deploy`, `vfx_megaminion_hero_deploy`) are SC2 and are drawn as the data gives them: tall pillars (about 250 du, 8 tiles) and a dark navy back column (`vmc_megaminion_hero_deploy_backglow`, colour multiplier 0,13,51) for about a second.

### 1.2 Which actions exist

These counts are over the rows' blue exports. Variants of one card (`_crazy_*`, `_chaos_*`, `_Chess`) are counted separately.

| Action | Rows | Typical frames (median) | fps | Labels (frame index, 0-based) |
|---|---|---|---|---|
| `idle1` | 469 | 29 (102 rows have a 1-frame idle) | 24 / 30 / 60 (103 / 243 / 123) | usually none; some `action_frame1/2` |
| `attack1` | 460 | 30 | same mix | `action_frame`: the hit or launch moment; ~4000 clips |
| `run1` | 451 | 28 | same mix | `action_frame1`, `action_frame2`: the two footfalls |
| `attack2`, `attack3`, `attack4` | 37 / 8 / 1 | 24 | 30 / 60 | `action_frame` |
| `deploy1` | 21 | 38 | | `action_frame` or `action_frame1/2` |
| `charge1` | 18 | 16 | | `action_frame1/2` (Prince, Dark Prince, Battle Ram, Ram Rider) |
| `ability1` | 18 | 23 | | `action_frame` (champions, Monk, Golden Knight, Archer Queen…) |
| `jump1` | 16 | 85 | | `charge_start/end`, `jump_start/end`, `recover_start/end` (Mega Knight, Royal Hogs) |
| `dash1` to `dash4` | 10 | 27 | | `action_frame` (Bandit, Golden Knight, Guardienne) |
| `attacking1` | 6 | 62 | | `action_frame`, `action_frame_end` (Executioner: axe out and back) |
| `loading1` | 6 | 96 | | `loop_start/end`, `throw_*`, `pull_*` (Fisherman) |
| `reborn1` | 7 | | | Phoenix egg hatch |
| `die1`, `falling1` | 4 | | | Suspicious Bush only |
| `idle2`, `run2`, `idlebreak1` | 1–2 | | | Chef, Monk |

There are no general `death` clips. A death is an effect (`DeathEffect`, §7). Deploy clips are rare, and the usual deploy look comes from `DeployBaseAnimExportName` (§8).

The fps value is per clip (`MovieClip.fps`). All directions of one action share the frame count and label frames, with 31 exceptions out of 1584 action sets. Blue and red share timing, with 41 exceptions out of 1559.

Examples, using direction 1 of the blue export:

| Unit | idle1 | run1 | attack1 | HitSpeed / LoadTime |
|---|---|---|---|---|
| Knight (`chr_knight.sc`, Scale 98) | 1 f @30 | 26 f @30, steps at 0 and 13 | 35 f @30, `action_frame` 22 (733 ms) | 1200 / 700 |
| Archer (`chr_archer.sc`, 87) | 1 @24 | 16 @24, steps 5 and 13 | 24 @24, af 14 (583 ms) | 900 / 400 |
| Musketeer (`chr_musketeer.sc`, 100) | 1 @30 | 28 @30 | 30 @30, af 14 (467 ms) | 1000 / 300 |
| Giant (`chr_giant.sc`, 100) | 35 @30 | 30 @30 | 28 @30, af 13 (433 ms) | 1500 / 1000 |
| Hog Rider (`chr_hog_rider.sc`, 95) | 1 @24 | 17 @24, step 15 | 20 @24, af 12 (500 ms) | 1600 / 1000 |
| Tower Princess (`chr_princess.sc`) | 30 @24 | 18 @24 | 40 @24, af 26 (1083 ms) | 1200 / 1000 |
| Minion (`chr_minion.sc`, 85, flying) | 16 @24 | 16 @24 | 14 @24, af 10 | 1200 / 700 |

**Confidence.** DATA.

### 1.3 Simulator state to action

`EntityView.state` is `GridEntityState` (`core/.../pathfinding/GridEntityState.java`).

| state | name | Draw |
|---|---|---|
| 0 | STANDING | `idle1`, looped |
| 1, 6, 7, 15 | MOVING, SPAWN_PATHFIND, INGAME_PATHFIND, ROUTE_FOLLOWING_ALTERNATE | `run1`, looped at native fps. If `speed == 0` for the tick, draw `idle1`, unless the row has `DontStopMoveAnim` (Golem, Ice Golem): keep `run1` |
| 2 | ATTACKING | `attack<k>`, timed to the hit (§1.4). Between hits, after the clip ends: `idle1`. If `AnimationsKeepLastFrame` is set: hold the last attack frame |
| 3 | DASHING | a leap (row `JumpHeight` > 0 and a `jump1` clip: Mega Knight, its evolution): `jump1` timed to the dash (§1.6); else `dash1` (chained dashes take `dash1`…`dash4` in turn), else `charge1`, else `run1` |
| 4, 11, 8 | DEPLOYING, WAITING_TO_DEPLOY, CLONE_SETUP | `deploy1` if present, stretched over `DeployTime` (row `DeployAnimationOverride` names another action, e.g. `_reborn1_`); else `idle1`, with the deploy base animation under it (§8). WAITING_TO_DEPLOY units are not visible yet in the game (GUESS: draw nothing until DEPLOYING) |
| 5 | JUMPING | `jump1` if present (Royal Hogs), its landing frame `action_frame1` at the landing (§1.6); else `run1`. Lifted by the core's arc either way |
| 10, 16 | CASTING, ABILITY_FOLLOW_UP | `ability1` if present (or the row's `CustomAnimationPostfix`), else `idle1` |
| 9 | MORPHING | `idle1` |
| 12, 13 | FOLLOWING_REMOVED(_BUILDING) | only the Fisherman's hook sets them (`ProjectileFlight`): a troop it pulls (12) is drawn `idle1` where the hook has it, with its bar; a Fisherman it drags to a building (13) as in 14 |
| 14 | COMPONENTS_DISABLED | the Fisherman held while his hook pulls: `loading1`'s `pull_start`…`pull_end` at the clip's rate, held on its last frame; else `idle1` |
| -1 | any non-character | n/a |

Two further rules apply to every state:

- A charging unit (Prince, Dark Prince, Battle Ram, Ram Rider's Ram, evolved Royal Recruits) uses `charge1` instead of `run1` once its charge is complete (`chargeProgress` ≥ 10000, read through `BattleAdapter.unitMotion`), until the charge is lost.
- A unit winding up a dash (`dashWindupMs` > 0: Bandit, Mega Knight, …) stands: `idle1`, or the crouch of its `jump1` (§1.6).
- A stunned or frozen unit (`statuses` STUNNED/FROZEN) holds its current frame: playback rate 0.

**Evidence.**

- The action names and their counts are in §1.2. The state numbers are in `GridEntityState`.
- `DontStopMoveAnim` is set on 8 rows, all of them Golem or Ice Golem variants. That implies the default is to stop the run clip when the unit does not move.
- `AnimationsKeepLastFrame` is set on 11 rows (Berserker, Little Prince, Mega Knight evo, Dagger Duchess).
- `DeployAnimationOverride = "_reborn1_"` is on PhoenixNoRespawn.

**Confidence.** The actions that exist are DATA. The mapping is INFERRED from the state names and column names. The stun freeze and the WAITING_TO_DEPLOY handling are GUESSes.

### 1.4 Attack timing: line the `action_frame` up with the logic hit

**What the clip shows.** `Knight_attack1_5` starts and ends in the ready pose, sword forward (frames 0–6 and 24–34). Frames 9–21 are the wind-up, with the sword raised behind. Frame 22, the `action_frame`, is the strike. So `action_frame` is the moment of impact (melee) or release (ranged), and the clip runs ready → wind-up → impact → ready.

**Model in the core (DATA, `AttackTimerAdvance.java`).**

- The attack timer `t` (`TargetingState.attackTimerMs`) starts at `LoadTime` when the unit locks on (less any load countdown still running).
- `t` grows by 50 ms each tick, scaled by buffs.
- A hit fires each time `t` crosses a multiple of `HitSpeed`.
- So the first hit comes `HitSpeed - LoadTime` after the lock (Knight: 500 ms), and later hits come every `HitSpeed`.
- `attackSequenceIndex` selects `attack1`, `attack2` or `attack3`. Monk and Little Prince have `AttackSequence = [0,1,2]`, and Archer EV1's `ActionSetAttackSequenceIndex AttackIndex=1` selects its `attack2`.

**What the client does (INFERRED).** It matches the clip's `action_frame` to the hit. The column `DisableAttackAnimationFrameMatching = true` exists to turn that matching off on 2 hero attack actions (`electro_wizard_hero.toml`), so frame matching is the default.

Other columns fit that reading:

- `VisualHitSpeed` (Inferno Dragon 1200 against HitSpeed 400, and Mighty Miner) replays the clip at a slower cadence than the hits. The game needs it because the clip is otherwise played once per hit.
- `TryToFinishAttackAnimation` and `TryToFinishAttackAnimationBeforeNextAttack` (Valkyrie, Mega Knight evo) say the clip is normally cut off when the attack stops or the next one begins.
- In 76 of 421 attack clips the clip is longer than `HitSpeed`. In 21 the `action_frame` comes later than `HitSpeed` (e.g. Magic Archer `EliteArcher`: action frame at 1333 ms, HitSpeed 1100). So the playback rate must sometimes go above 1.

**Rule.**

Definitions:

- `af = action_frame / fps`, in seconds.
- `L = frames / fps`, the clip length.
- `HS = HitSpeed` in seconds; use `VisualHitSpeed` when the row has one.
- `p = (t mod HS)`: the time since the last hit boundary.
- `toHit = HS - p`: the time until the next hit.

Each frame:

1. **Wind-up.** If `toHit <= af`, show clip time `c = af - toHit`. The clip then reaches `action_frame` exactly when the hit fires.
   - If the unit only just started attacking and `af > HS - LoadTime` (the time available for the first hit), start the clip at `af - toHit`, skipping its start, or play the wind-up faster at `rate = af / (HS - LoadTime)`. Both look acceptable; the GUESS is that the game skips.
   - If `af > HS` (Magic Archer, Elixir Golem…), use `rate = af / HS` for the whole clip.
2. **Follow-through.** Otherwise, if a hit happened within the last `(L - af)` seconds, show `c = af + p`. The clip plays out after the hit. A new wind-up overrides it, unless `TryToFinishAttackAnimationBeforeNextAttack` is set.
3. **Otherwise**, show `idle1`, the ready pose.

Examples:

| Unit | HS | First hit after lock | Clip start | Clip end |
|---|---|---|---|---|
| Knight | 1.2 s | 0.5 s | 0.733 s before each hit (first time: skip 0.233 s) | 0.433 s after the hit; then idle for 1.2 − 0.733 − 0.433 = 0.033 s |
| Giant | 1.5 s | | 0.433 s before the hit | after a 0.5 s follow-through; idle for the remaining 0.57 s |

**Whose hit speed (DATA).** `HS` is the hit speed of the entity whose timer it is, not of the clip's row. A crown tower's rider plays its own row's clip at the tower row's pace: the Princess Tower fires `TowerPrincessProjectile` every `HitSpeed 800` ms (the core's launches in the example replay: one per 16 ticks), while the `TowerPrincess` character row says 1200. With 40 f @24 and `action_frame` 26 (1.083 s > 0.8 s) her `attack1` plays at rate 1.35, restarting at each hit and reaching the release frame as the arrow leaves (checked over 22–32 s of the example replay: 13 launches, 13 arrows drawn, 13 `card_tower_archertower_attack` sounds, the release frame within 20 ms of each launch). Timed at 1200 ms she showed a release every 1.2 s, out of step with the arrows. The King tower has no attack clip: only its turret aims (§5.2) and `King_attack` plays at each launch.

To implement this, add `attackTimerMs`, `hitSpeedMs` (the effective one) and `attackSequenceIndex` to `EntityView`. They come from `TargetingState.getAttackTimerMs()`, `TargetingConfig.hitSpeed()` and `getAttackSequenceIndex()`. A per-entity "hit landed this tick" event also works for the follow-through, but the wind-up needs `toHit` in advance.

**Confidence.** The core model is DATA. Frame matching is INFERRED (a column name plus the timing data). The exact handling when the time is too short is a GUESS. Frame-stepping a 30 fps capture of a Knight against a tower would confirm it.

### 1.5 Run, idle and footfall labels

- Loop `run1` and `idle1` at the clip fps. **DATA** (fps); INFERRED (looping).
- `run1` labels `action_frame1` and `action_frame2` are the two footfalls, about half a cycle apart. That is where `MoveEffect` (e.g. `knight_steps`: dust particles and a step sound, Layer Ground) is played.
- Fixed-rate playback looks right. The Knight covers 0.87 tiles per run cycle at Speed 60 (1 tile/s). Scaling the rate by the unit's current speed share keeps slows and rage consistent. That scaling is a GUESS.

### 1.6 Dashes, leaps, river jumps and charges

**What the core gives (DATA, read-only through `BattleAdapter.unitMotion`).** Per character: its live height `z + heightOffset` (`GridEntity.getZTotal`), the dash time left, the charge progress, the dash wind-up left and the landing hold. The renderer reads them each tick in its `WorldObserver.afterPostHooks` and interpolates the height between the last two ticks; the effect layer listens to `dashStarted`, `dashLanded`, `movementStateRequested` (JUMPING → other: a river jump landing) and `chargeCompleted`.

- **Leap (Mega Knight).** The wind-up runs `DashCooldown` (900 ms) with the unit still, then DASHING for `DashConstantTime` (800 ms) in the air, then a landing hold, still DASHING, until it walks (`DashLandingTime` 300: the hold reads 100…250, about 200 ms). The core's height profile is piecewise linear: 2/3 of `JumpHeight` at a quarter of the constant time, `JumpHeight` (3000) at half, 2/3 at three quarters, 0 at the end (`RouteFollower.dashVisit`). The example replay's red Mega Knight leaps at 13.40, 15.60, 86.70, 88.60 and 90.50 s (battle ticks 268, 312, 1734, 1772, 1810).
- **River jump (Hog Rider, Prince, Dark Prince, Ram, Royal Hogs, …, `JumpEnabled`).** JUMPING while it crosses water at `JumpSpeed` (game units per tick); the arc `(p − p²/64) · JumpHeight / 16` (`p` up to 32) is sampled into the height offset, peaking at about `JumpHeight` (4000) on a long jump (`RouteFollower.jumpVisit`). Speed during both is `JumpSpeed` (DATA, `SpeedBudget`).

**Drawing (§3.4 for the factor).**

| What | Rule | Confidence |
|---|---|---|
| Lift | the sprite at the ground point raised by the core's height × `ArtProjection.DU_PER_HEIGHT_UNIT` (as flying units and projectiles); the shadow stays at the ground point; the bar rises with it. A rider attached to a mount is drawn inside the mount's clip (§3.5), so it rises with it | the height is DATA, the factor INFERRED (§3.4). A hog's 4000 lifts it 70 du, higher than a Balloon flies (52 du): check against a capture |
| Layer | a ground unit off the ground is drawn on the air layer, over the ground units it passes | INFERRED (the core moves it off the ground layer for contact, `GridEntity.getZTotal`) |
| Mega Knight `jump1` (85 f @60: `charge_start` 0, `charge_end` 13, `jump_start` 14, `jump_end` 53, `recover_start` 54, `recover_end` 84) | the crouch (0–13) at the clip's rate over the last 233 ms of the wind-up (idle before); the flight (14–53) stretched over `DashConstantTime`; the recover (54–84) stretched over the landing hold. The evolution's 30 fps clip is stretched the same way | labels DATA; mapping INFERRED from the labels and the columns; the crouch's placement at the wind-up's end is a GUESS |
| `dash1` (Bandit, Boss Bandit, Golden Knight, the Little Prince's guard) | from the dash's start at the clip's rate. The Golden Knight's chained dashes take `dash1`…`dash4` in turn by the chain's count (`chainDashed`) | INFERRED; the turn order is a GUESS |
| Royal Hogs' `jump1` (35 f @60, `action_frame1` 29) | played so `action_frame1` comes as the jump lands: duration = jump distance / `JumpSpeed` ticks | INFERRED (the label as the landing) |
| Evolved Royal Hogs' `landing1` | once, after the jump ends | GUESS |
| `charge1` | while the charge is complete (above) | INFERRED (state and column names) |

**Effects (`EffectLayer`, sounds into the sounds file as other unit effects).**

| Column | When | Example | Confidence |
|---|---|---|---|
| `DashStartEffect` | at the dash's start (`dashStarted`), where the unit stood | `mega_knight_dash_start`: `card_legendary_mega_knight_charge` and the `assassin_buff` glow | INFERRED: the evolution's `vfx_megaknight_evo_dash_start` has parts at `Time` 800 = `DashConstantTime`, the landing, so the effect starts at the leap |
| `DashEffect` | at the dash's start, following the unit (`FollowParent`) until it lands | `mega_knight_dashing` (a sound), `assassin_dashing` (a ground trail) | INFERRED |
| `LandingEffect` | at a dash's landing (`dashLanded`) and a river jump's (JUMPING → other) | `mega_knight_dash_end` (`mega_knight_deploy_compile` at 25 %, `ShakeScreen` 350), `hog_rider_landing` | DATA (core events); the column on the `JumpEnabled` rows ties it to the jump |
| `DashFilter` | on the unit's sprite from the dash's start while it is DASHING (cut when it walks) | `filter_mega_knight_jump` (90 f @60: alpha pulsing 1 → 0.6, then from frame 50 a ±1 du jitter), `filter_bandit_charge` (60 f) | the clips are DATA. Starting at the dash is INFERRED: the Mega Knight's jitter starts at frame 50 (833 ms), as it lands (800 ms). It makes the Mega Knight translucent in the air; confirm against a capture |
| `ChargeEffect` | when the charge completes (`chargeCompleted`), following the unit; its `Filter` component (`filter_protection`) on the unit | `prince_charge`: dust, `card_epic_prince_charge`, the `effect_charge` badge | INFERRED |

**Not handled.** The Fisherman's throw (`loading1` `throw_*`): his special shows `attack1`. The Mega Knight evolution's and the Golden Knight's art was not checked in a recording (no battle had them). `AttackDashTime` (Bats) is a lunge inside the attack, not a state. The Royal Ghost, Mini P.E.K.K.A, Monk and Mighty Miner have no dash or jump state (the Monk's and the Mighty Miner's abilities are CASTING, §1.3); the Goblin Barrel is a projectile (§6).

---

## 2. Facing directions

**Rule (the 9-direction standard, used by 416 of the ~470 rows with directional idles):**

- Exports `_1` to `_9` cover the right half of the circle in 22.5° steps:
  - `1` = facing up the screen, away from the viewer.
  - `5` = facing right.
  - `9` = facing down the screen, toward the viewer.
- The left half reuses the same exports mirrored horizontally (scale x by -1 about the clip origin).

Take the facing vector in **drawn screen space with y pointing up the screen**. In the desktop's window coordinates, whose y grows up:

```
fx = view.dx(headingX)          // ViewOrientation mirrors the width in the view of side 0
fy = view.dy(headingY)          // and the length in the view of side 1
θ  = atan2(fx, fy) in degrees   // 0 = up, +90 = right, ±180 = down
N  = number of direction exports for this action
step = 180 / (N - 1)            // 22.5 for 9, 10.588 for 18, 45 for 5
dir   = round(|θ| / step) + 1   // 1..N
flipX = θ < 0
```

Facing input: `headingX/Y` is the logic **facing**, not just the velocity, so use it as it is.

- At creation, `CharacterEntity` sets it to `(0, ±256)` toward the enemy, so a fresh unit shows `dir 1` at the bottom and `dir 9` at the top.
- While the unit targets, `CharacterEntity.turnToward(reference)` turns it to the vector to the reference. Crown towers are not characters: their facing stays `(0, 0)` (only the Royal Chef's throw calls `TowerEntity` `turn()`), so a tower's turret and rider aim at the reference it holds (`targetX/Y`), keeping the last aim without one (DATA, from the core code and a facing dump of the King tower).
- Movement updates it, except in the `FACING_GATE_MASK` states (standing, attacking, deploying…).

So during an attack it already points at the target (DATA, from the core code). Only when it is (0,0), which happens when a reference stands on the unit's own spot, keep the last facing.

Use the game-space (unsquashed) vector, not one squashed by the 0.81 y factor of §3. The direction sprites are 3D renders of the model turned by `k·22.5°` on the ground plane, so the index is a ground angle (INFERRED). Using the squashed vector would shift some 45° headings by one index.

**Variants (DATA counts; visual checks):**

| Directions | Units | Notes |
|---|---|---|
| 9 | almost everything (idle 416, run 414, attack 406) | `knight_run.png`, `hog_run.png`, `musk_attack.png`, `giant_run.png` confirm 1 = up, 5 = right, 9 = down |
| 18 | Sparky (`ZapMachine*`), Cannon Cart (`MovingCannon`, `BrokenCannon`), Flying Machine (`DartBarrell`), Cannon evo, Mini Zap Machine | still a half circle: 1 = up, 9 and 10 straddle right, 18 = down (`mcannon18.png`), so `step = 180/17`, with mirroring. These rows also have `RotateAngleSpeed` (600 or 224 °/s?) or turn smoothly |
| 5 | Mortar, Barbarian Launcher (`building_mortar.sc`), Goblin Cannon | `step = 45`, mirrored. The clips are near-identical (`mortar5.png`) |
| 2 | Suspicious Bush | 2 variants, not directions |
| 1 | Balloon, Skeleton Balloon, Inferno Tower | no direction; never flip |
| 0 (single clip) | Cannon, Tesla, Bomb Tower, King/Princess towers, most buildings | Cannon (`building_cannon`, 19 frames) and King Tower (`KingTower_blue`, child `turret` with 36 frames) carry the turret angle **on the timeline**. `HasRotationOnTimeline = true` on 12 character rows and 16 building rows. Cannon: `frame = round(|θ|/10)`, 0 = up, 9 = right, 18 = down; flip for left (`cannon_full.png`). `TurretMovement` (5, 10, 15) is the turret's angular step (INFERRED) |

**Confidence.** The 9-direction mapping is DATA, checked visually on Knight, Hog Rider, Musketeer and Giant. Mirroring is DATA: no left-facing exports exist. Ground-angle versus screen-angle is INFERRED. The facing source is DATA (core code). The client may smooth turns, as rows with `RotateAngleSpeed` suggest; snapping the index each frame is fine.

The Lava Hound flying north (ladder replay, 66–80 s and 131–148 s) keeps one heading (`(53, 250)` to `(57, 249)`, export 2 mirrored) every tick: its sway is the `run1` clip's own wing flap (18 frames @24), not a facing flip, so no smoothing is applied.

---

## 3. Scale and anchor

### 3.1 Arena display units per tile

Measured by the Q4 work on the arena art; see §4 for the method.

- 1 tile is **29.75 du across** (x) and **24.12 du along the length** (y). The arena art is a 2D picture taken by a tilted camera, so the length is squashed to 0.811 of the width (= cos φ, camera tilt φ ≈ 35.8°).
- Display origin of the arena export: x = 0 is the centre line (game x = 9000); y = 0 is the far edge (game y = 32000 in the bottom player's frame); y grows down the screen.

```
// gx, gy: game units after ViewOrientation (bottom player's baseline at gy = 0)
du_x = (gx - 9000)  * 0.02975
du_y = (32000 - gy) * 0.02412 - 3      // the -3 is good to about 2 du
```

### 3.2 Sprite size

**Rule.** A character or building clip is drawn in the same du space as the arena. It is scaled **uniformly** by `Scale / 100` (the row's `Scale` column, default 100) about its origin. The y squash applies to positions only, never to sprites, whose art already carries the perspective. The same holds for projectiles and area effects with their own `Scale` columns; see §6 and §7.

On screen, with `P` pixels per tile horizontally:

```
s       = P / 29.75                     // screen px per du
spriteM = translate(screenX, screenY) · scale(s · Scale/100 · (flipX ? -1 : 1), s · Scale/100)
screenX = P * gx/1000                   // after ViewOrientation
screenY = P * 0.811 * gy/1000           // the tile is drawn 0.811 as tall as wide; y as your canvas has it
```

The current desktop renderer uses square tiles (`RenderConstants.TILE_PIXELS`). Realistic mode should draw tiles `P × 0.811·P` so that the arena art lines up. If the arena art is instead stretched to fit square tiles, it is distorted, and the sprites must be stretched the same way (23% taller) to fit it.

**Evidence for `Scale`.** These are blue `idle1_5` bounds, at all frames, in du:

| Unit | Scale | Raw size w × h | Scaled w × h |
|---|---|---|---|
| Valkyrie | 140 | 20 × 38 | 29 × 53 |
| Skeleton | 130 | 16 × 24 | 20 × 32 |
| Spear Goblin | 133 | 31 × 34 | 42 × 46 |
| Knight | 98 | | 84 × 61 (sword included) |
| Giant | 100 | 57 × 109 | |
| P.E.K.K.A | 106 | | 63 × 99 |
| Golem | 130 | | 91 × 105 |

The rows with Scale 130–140 are the ones whose raw art is visibly undersized: raw Valkyrie is about half the Knight's height. After `Scale/100` the sizes match the game (Valkyrie ≈ Knight, Skeleton small, Golem large). For scale against the tile size, a Giant is about 1.9 tiles wide and 4.5 tile rows tall.

### 3.3 Anchor

**Rule.** The clip origin (0,0) is the unit's ground point under its feet, which is the collision circle's centre. Place it at the projected game position and draw nothing extra.

**Evidence.**

- In renders with clip (0,0) marked, it sits at the feet or the base centre for all directions.
- The crai renderer documents the same thing ("origin is … the unit's ground anchor").
- Pre-rendered sheets store it per frame as `ax, ay`.

**Confidence.** The scale numbers are DATA, measured. `Scale/100` is INFERRED (strong). The anchor is DATA, checked visually.

### 3.4 Heights: flying units and attached characters

**Rule (INFERRED).** A logic height `h` in game units (`FlyingHeight`, `AttachedCharacterHeight`, a projectile's z) lifts the sprite by `h · 0.01742` du up the screen. That is the orthographic camera factor `0.02975 · sin φ`, with cos φ = 0.811.

**Evidence.**

- The Tower Princess composited on `StarTower_base` stands on the tower's wooden floor at dy −34 to −38 du (§5.1). With `AttachedCharacterHeight 2200` that gives 2200 × 0.01742 = 38.
- An earlier reading from the King tower's `turret` child (ty −57) is not a height measurement: the same child sits at −33 in `KingTower_red`.

Examples:

| Unit | FlyingHeight | Lift |
|---|---|---|
| Minion | 1500 | 26 du |
| Balloon | 3000 | 52 du |
| Baby Dragon | 3500 | 61 du |

The ground point, and with it the shadow (§8), stays at the logic position.

### 3.5 Riders on mounts (Ram Rider, Goblin Giant)

**Rule.** A rider the core attached to a mount (`SpawnAttach`: `Ram` → `RamRider`, `GoblinGiant` → `SpearGoblinGiant`; `CharacterEntity.getParent()`, its place in the mount's `riders()`) is not drawn at its own logic position. It replaces the mount clip's child named `child<i+1>` for rider `i`, as the king tower's `king_dummy` takes the King (§5.2): drawn at that child's matrix in that frame, at its place in the mount's draw order, playing its own action and facing (mirrored relative to the mount). It inherits the mount's lift (river jumps), filters and shadow. A mount clip without the child draws the rider on the ground at the mount.

**Evidence.**

- Every `ram_*` and `ram_red_*` action and facing of `chr_ram_rider_dl.sc` has one named child, `child1` (clip 519: an empty placeholder whose own child 499 is missing, i.e. a stripped marker). Its matrix moves with the ram's gait: (0.7, −28.5 → −27.3) du in `run1_5`, (1.5, −26.25) in `idle1_5`, swinging −5.75 … 11.1 du in x through `attack1_5`. The blue clips list it second (over the body's back part, under the head and horns), the red ones third. **DATA.**
- The Goblin Giant's clips have `child1` and `child2`, for its two Spear Goblins. **DATA.** Child `i+1` for rider `i` is **INFERRED** from the names.
- The core moves the rider to its mount's previous position each tick, one step behind (60–130 game units). Drawn at its own position, the Ram Rider stood on the ground at the ram's feet, half hidden by the ram. **DATA** (example replay, 134–150 s).
- The `rider` clips are authored around their seat (the origin), and look seated on the ram's back at `child1` (stills at 135.9 s). The rider row's offset columns are absent (no `AttachedCharacterHeight` on `Ram`). **INFERRED.**
- Hog Rider–style units are one sprite (`hog_rider`), so they need none of this.

**Independent cross-check of §3.1.** The spell radius rings in `effects.sc` measure about 30 du of horizontal radius per tile of `Radius`:

| Ring | Radius | Horizontal radius |
|---|---|---|
| Poison | 3.5 tiles | 105.2 du |
| Freeze | 3 tiles | 88.9 du |
| Rage | 3 tiles | 97 du |
| Zap | 2.5 tiles | 68 du |

Every ring is an ellipse with height/width 0.81–0.82: poison 85.9/105.2, freeze 71.6/88.9, zap 55/68.2. Both numbers match 29.75 du/tile and 24.12/29.75 = 0.811. **DATA.**


---

## 4. Arena background

### 4.1 Which art makes up an arena

**Rule.** Follow this chain:

1. `arenas.csv`: the arena row's `PvpLocation` names a row of `locations.csv`.
2. That row's `FileName` names `locations/<x>.csv` under `assets/locations/`.
3. The file is LZMA-compressed with Supercell's old header: insert 4 zero bytes after the 9-byte header, then decode it as LZMA "alone". `crforge_art.lzma_tables` and `crharness.gamedata` both do this.
4. The decoded file holds:
   - `FileName`: one (sc, ExportName) pair, the **ground**.
   - `Objects`: a list of decoration names, each with one or more (x, y) positions in game units.
5. Each decoration is a row of `csv_logic/decos.csv`. That row gives `FileName`, `ExportName`, `Layer` (Ground, Object or Above) and its own `Shadow*` columns.

Draw order:

1. The ground export.
2. `Layer=Ground` decorations.
3. `Layer=Object` decorations, y-sorted together with the units.
4. `Layer=Above` decorations. Skip the `foldable*Frame` pieces, which are frames for foldable phones.

| Battle | Location file | Ground | Decorations |
|---|---|---|---|
| Training Camp (`arenas.csv` TrainingCamp; `locations.csv` TrainingCamp / FirstMatch) | `locations/training_arena.csv` | `sc/arena_training.sc` / `training_area_bg` | about 40 kinds (rocks, `training_cliff*`, trees, fences) |
| Ladder, Arena 1 (`PvP_goblin`), **recommended default** | `locations/goblin_arena.csv` | `sc/level_goblin_arena.sc` / `goblin_bgr` | 30 kinds (stands, roof shades red at top and blue at bottom, trees, water wheel) |
| Legendary (`PvP_legendary`) | `legendary_arena.csv` | `sc/level_legendary_arena.sc` / `LegendaryArena_ground_layer` | 23 |
| Bone, Royal, Champion | `bone_arena.csv`, `royal_arena.csv`, `champion_arena.csv` | `bone_arena_bgr`, `royal_base_bgr`, `champion_base_01` | – |

Every ground export checked has a single frame, so the ground is static and the water is not animated.

crforge never picks a location; nothing in core or desktop reads `locations`. Use `PvP_goblin` for Ladder and `TrainingCamp` for Training Camp.

**Other layers, from the same `locations.csv` row:**

- Clouds drift over everything:
  - `CloudFileName = sc/effects.sc`, `CloudExportName = cloud_1` (some arenas use `asteroid_shadow_anim_01`).
  - `CloudCount = 4`, scale 54–114 %, speed 25–154, alpha 7–10, random flip.
- `ShadowR/G/B/A` and `ShadowOffsetX/Y` are a per-arena shadow tint and offset, used for §8 shadows. Training Camp has A = 100 and offset (60, 100).
- `OverlaySC`/`OverlayExportName` and `arenas.csv` `BackgroundSWF` are empty for these arenas.

**Confidence.** DATA.

### 4.2 Lining the art up with the 18×32 tile grid

**Rule.**

- The location art uses du with y pointing down, and its `Objects` coordinates are in the same frame.
- The art is **always** authored with blue at the bottom: the blue roofs are at the bottom, and the `goblin_blue_tower` decoration is at ly 38500. The game never mirrors it. Draw it the same way whichever side is at the bottom; only unit positions go through `ViewOrientation`.

With `lx = v.x(gx)` and `ly = 32000 - v.y(gy)` ("screen tiles": x from 0 to 18000 left to right, y from 0 to 32000 top to bottom):

```
du_x = (lx - 9000) * 0.02975      // 29.75 du per tile across
du_y =  ly         * 0.02412 - 3  // 24.12 du per tile down (the -3 is good to about 2 du)
```

- Display origin (0, 0) is the middle of the far (top) edge of the arena.
- The playfield is 535.5 × 772 du.
- The river centre is at du y ≈ 383.
- The bridges sit at tiles 2.55–4.5 across, as in the tilemap's bridge cells 2500–4500.

**Evidence.**

- **Training Camp tower pads.** The tower pads in `training_area_bg` were picked out as sand-coloured regions.
  - King pads are centred at (0.5, 71.2) and (0.4, 698.4) and measure 116.5 × 97 du, a 4-tile footprint.
  - Princess pads are centred at x −164.2 / +162 and y 157.3 / 615, and measure about 88 × 71.5 du, a 3-tile footprint.
  - Spawn group `King_PrincessTowers` puts the kings at (9000, 3000/29000) and the princesses at x 3500/14500, y 6500/25500.
  - So the x scale is 326.6 du over 11 tiles = 29.7, and the y scale is 627.2 du over 26 tiles = 24.12 (and 457.8 du over 19 tiles = 24.09).
- **Goblin arena checkerboard.** A period and phase fit of the tile checker in `goblin_bgr` gives:
  - Tx = 29.75. One tile edge lies on the centre line.
  - Ty = 24.15 (top half) and 24.02 (bottom half). The river art is slightly off 2 tiles.
  - The far edge is at −3.6 and the near edge at 768.8.
- **Full composite.** Ground, decorations and a red 18×32 grid line up with the checker, river, bridges and pads. All six towers drawn at their logic positions sit on their pads (`q45/goblin_full.png`, `towers_on_goblin.png`).
- **Independent check: the spell rings in §3.**

**Confidence.** INFERRED from measurement, to about 0.05 du per tile. Camera tilt φ = acos(0.811) ≈ 35.8°.

---

## 5. Towers

The tower's logic position, put through §4.2, is the sprite origin. It lands on the pad centre (checked visually). Colour comes from the screen side: the bottom side uses the Blue exports.

### 5.1 Princess tower (building row `PrincessTower`)

**Rule.** Draw these at the tower point, in this order:

1. `building_tower.sc` `StarTower_base_{blue|red}`: 1 frame, bounds [−54, −65, 54, 49] du.
2. The attached character `TowerPrincess`: `sc/chr_princess.sc`, base `princess_tower` (blue) or `princess_tower_red` (red).
   - Actions `idle1` (30 f), `attack1` (40 f @24, `action_frame` 26) and `run1`, each with directions `_1`…`_9`. The §2 rule applies, using the tower's `headingX/Y`, which the core turns toward the target.
   - Lift it by `AttachedCharacterHeight 2200 × 0.01742 ≈ 38 du`.
3. `StarTower_top_{blue|red}` at the tower point with no offset: the front battlement, which hides the princess's lower body.

The base is 108 du wide against a 3-tile footprint of 89 du, because the battlements overhang.

**Evidence.**

- `buildings.json` PrincessTower: `FileName sc/building_tower.sc`, Blue/Red(Top)ExportName as above, `AttachedCharacter TowerPrincess`, `AttachedCharacterHeight 2200`, `DeathEffect TowerExplode`.
- `characters.json` TowerPrincess.
- Composites at dz 0, −25, −34, −45 and −53: the princess stands on the floor at −34 to −38 (`q45/pt_comp.png`).

**Confidence.** The exports and their order are DATA. The −38 offset is INFERRED.

### 5.2 King tower (building row `KingTower`) and the King

**Rule.**

- **Tower clip.** `building_tower.sc` `KingTower_{blue|red}`: 98 frames @30, with labels:

  | Label | Frames | Meaning |
  |---|---|---|
  | `idle` | 0 | hatch closed |
  | `start` | 1–96 | the hatch opens and the cannon rises |
  | `stop` | 97 | cannon out |

  Play it like this:
  - Inactive: hold frame 0.
  - On activation: play 1 → 97 at 30 fps. That takes 3.2 s; `king_tower.toml` uses `ActionDuration 3300`.
  - Active: hold frame 97.
- **Child `turret`.** It sits at (0, −57) on blue and (0, −33) on red. Its 36 frames are **rotation, not time** (`HasRotationOnTimeline = true`):
  - 0 = muzzle up the screen.
  - 18 = right.
  - ~35 = down.
  - About 5° per frame. For angles on the left, use the mirrored frame and flip x.
  - Same scheme as the Cannon's 19 frames at 10° (§2). Aim it at the tower's reference (`targetX/Y`): the core never turns a crown tower's `headingX/Y` (§2).
  - Apply the parent frame's colour transforms. They hide the turret while the tower is idle.
- **Child `king_dummy`.** An empty placeholder with the identity matrix. Draw the King there, at its place in the frame's element order: blue frames list the king after the turret, red ones before it.
  - The King is `sc/chr_king.sc` `King_blue` (seen from behind) or `King_red` (facing the camera).
  - It has 98 frames with the same labels, played in lockstep with the tower.
  - There is **no extra offset**: its bounds run from y −107 to 2, so the height is baked into the art.
  - Frames 0 and 97 contain a nested `king_idle` child (86 or 99 frames) that loops on its own clock.
  - The King has no attack animation. Only the cannon fires (`KingProjectile`).
- **Size check.** 120 du wide against 4 tiles = 119 du. The front edge at +50 sits at 2 × 24.12 = 48.
- **Gap in the view.** `EntityView` has no activation phase. Core already raises `ActivationEvent` (`ACTIVATING_STARTED` / `ACTIVATING_FINISHED`) in `TowerEntity`, so add a `kingPhase` and the ms elapsed.

**Evidence.**

- `buildings.json` KingTower: `HasRotationOnTimeline true`, `TurretMovement 5`, `AttachedCharacterHeight 2400`, no `AttachedCharacter`.
- `king_tower.toml`: `WaitForKingTowerActivation` → `ActionDuration 3300` → `KingTowerActivationEffect` (sound only).
- Clip trees and composites: `q45/king_blue.png`, `king_chr.png`, `turret_a.png`, `turret_b.png`, `king_comp.png`.

**Confidence.**

- Exports, labels, children and the activation length are DATA.
- The King at `king_dummy` with no offset is INFERRED. The name and the composite agree, but no table links the tower to `chr_king.sc`; the client probably hard-codes it.
- The turret angle mapping is INFERRED from viewing the frames.

### 5.3 Destroyed towers

**Rule.** When a tower dies:

1. Play its `DeathEffect`.
2. Stop drawing the tower and its character.
3. Draw the ruin at the tower point:
   - **Princess:** `TowerExplode` → emitter `Tower_destroyed_ground` → `building_tower.sc` `Tower_destroyed_ground1`, Ground layer.
   - **King:** `King_player_die` (bottom) or `King_enemy_die` (top) → emitter `KingTower_destroyed_ground` → `Tower_destroyed_ground2`, Base layer.
   - The ruin's particle lives 800000 ms, so it is effectively permanent.

`princesstower_destroyed`, `kingtower_destroyed` and the `_water` variants exist, but the Default skin does not reference them (`tower_skin_sets.csv` Default: `KingDeathFx King_player_die`, `PrincessDeathFx TowerExplode`). `kingtower_destroyed` is the shark skin's ruin (emitter `shark_destroyed_ground`); the Default skin's own ruin emitters are `DefaultTowerKing_base` / `KingTower_destroyed_ground` → `Tower_destroyed_ground2` and `DefaultTowerPrincess_base` / `Tower_destroyed_ground` → `Tower_destroyed_ground1`. So the King's ruin is `Tower_destroyed_ground2` (91 × 88 du against the Princess's 81 × 73), drawn about 3 tiles wide on the King's 4-tile pad; the King tower's own clip is 120 du wide.

**Sounds.** `TowerExplode` plays `card_common_mortar_die` (sample `building_destroyed_05`). `King_player_die` / `King_enemy_die` play `card_tower_kingtower_player_die` / `_enemy_die`, whose FMOD events hold a multi instrument with an **empty** playlist: the King's fall is silent in the banks. The crown sound and the end horn come from the match end (§9).

**Attack pace.** The princess's `attack1` is timed by the tower's `HitSpeed` (800), not her own row's (1200): §1.4.

**Health bars.** The tower rows carry their own: Princess `HealthBar Tower`, `HealthBarOffsetYBlue/Red` 62 / −12; King `Summoner`, 170 / −30.

**Confidence.** DATA.

---

## 6. Projectiles

**What to draw (DATA, `projectiles.json`).**

- Sprite: the row's `FileName` + `ExportName`. 283 of 312 rows have one, nearly all in `sc/effects.sc`.
  - For the red side use `RedExportName` when present (46 rows).
  - `Scale` is a percent (92 rows, 60–200; default 100).
  - Ignore the prestige columns (skins).
  - `PrefabAsset` (8 rows) is 3D. Six of those have an SC2 `ExportName` fallback.
- Shadow: `ShadowExportName` (229 rows, usually `projectile_arrow_shadow`), from the same file, drawn at the ground point. Red version: `RedShadowExportName` (7 rows).

**Where to draw.**

- The core flies a projectile at height `z` on a parabola (`core/.../projectile/ProjectileFlight.java` `arcHeight`): `z = sz + (aimZ − sz)·t/total − Gravity/2·(t − total)·t`. `ConstantHeight` (42 rows) flies level.
- Draw the shadow at (x, y) and the sprite at (x, y) lifted by `z · 0.01742` du (§3.4).
- `EntityView` has no z, start or progress today. `ProjectileEntity` has `getZ()`, `getStartX/Y/Z()` and the aim, so add `z`, `startX`, `startY` and `aimZ` to the view.

**Orientation.**

- Rotate the clip in 2D so that its authored axis follows the direction of travel on screen. Use the ground track start→aim, or the frame-to-frame screen motion including z.
  - Arrow and fireball are authored pointing +x (right).
  - `projectile_spear_360` is authored pointing +y (down).
- Rotate the shadow too, unless `ShadowDisableRotate` (6 rows).
- Frames do not encode yaw:
  - **15-frame arc clips** (`projectile_arrow_basic(_enemy)`, `fireball_projectile1`, `dragon_projectile`, 60 fps) are a pitch sweep over the arc. Use `frame = round(progress · 14)` with `progress = |pos − start| / |aim − start|`. Do not play them at their fps.
  - **`use360Frames = true`** (33 rows: `projectile_spear_360` 33 f, `projectile_rocket` 60 f, `projectile_firespirit` 24 f) is a periodic tumble. Use `frame = round(progress · (n − 1))`.
  - **1-frame clips** (`projectile_cannonball_small`, used by Musketeer and King tower) need no frame choice.
  - **Long clips** (`log_roll_projectile`, 408 f) are real loops: play them at fps.
- `MinimumLengthForVisualAngleCalculation` (3 rows) hints that the client computes a "visual angle" from motion.

**Effects (§7).**

- `TrailEffect` (209 rows): a looping effect attached to the projectile.
- `HitEffect` (286): a one-shot at the impact point.
- `DeathEffect` (26): when removed without a hit.
- The shooter's `ProjectileEffect`: at launch (muzzle).

| Row | Sprite | Shadow | Gravity | Trail | Hit |
|---|---|---|---|---|---|
| MusketeerProjectile | `projectile_cannonball_small` (1 f) | arrow shadow | 0 (straight) | – | `cannon_hit` (particles) |
| ArcherArrow, TowerPrincessProjectile | `projectile_arrow_basic_enemy` (15 f), both sides | arrow shadow | 60 | – | `ArrowHit` (sound only) |
| ArrowsSpell (spell Arrows: 10 per wave × 3 waves, 200 ms apart; the core spawns them) | `projectile_arrow_basic_enemy` | arrow shadow | 50 | `arrows_trail` (no art) | `ArrowHitGround` → emitter `Arrows_Hit_Ground`: one of `Arrow_enemy_ground_anim_1..6` (arrows stuck in the ground), 1.5–1.9 s, 500 ms fade, Ground layer |
| FireballSpell | `fireball_projectile1` (15 f) | arrow shadow | 50 | `vfx_fireball_trail` (4 emitters, loop) | `Fireball_explosion` (10 emitters over Ground/Base/Shadow/Object/Above, Scale 85, ShakeScreen) |
| RocketSpell | `projectile_rocket` (360 frames-style, 60 f) | `projectile_shadow_rocket` | 50 | `Rocket_Emitter` | `Rocket_explosion` |

**Confidence.** The columns, exports, chains and height formula are DATA. The frames being pitch or tumble rather than yaw, and so needing a 2D rotation, is INFERRED from rendered sheets: the arrow goes end-on at frame 12 and reverses at 13–14, and the fireball peaks at frames 7–8. The progress→frame mapping is a GUESS; confirm it against a capture of an arrow.

---

## 7. Effects, area effects, spells

**The effects table** is `assets/legacy_vfx/effects.json`. It holds 2443 effects, keyed by exactly the names the rows use (`knight_hit`, `Fireball_explosion`, …).

Effect keys:

| Key | Meaning |
|---|---|
| `RenderableComponents[]` | the parts to play |
| `Scale` | % |
| `Loop` | 491 effects |
| `EnemyVersion` | 326 effects. Use that effect instead when the owner is drawn red |
| `FollowParent` | 301 effects. Attach to the entity |
| `ShakeScreen` | 292 effects |
| `ForcedLength` | |
| `PivotClip` | |
| `PrestigeVersion*` | skins: ignore |

Component `Type` values:

| Type | Count | Notes |
|---|---|---|
| ParticleEmitter | 5262 | |
| SWF | 1718 | `FileName` + `ExportName` |
| Effect | 564 | nested effect name: recurse |
| Filter | 123 | applied to the unit's sprite, §8 |
| Prefab | 9 | 3D: skip |

Each component also carries:

| Key | Meaning |
|---|---|
| `Layer` | draw layer |
| `Time` | start delay in ms |
| `RenderableScale` | % |
| `Sound` | sound to play |

**SWF components without `ExportName`** (1211 of 1718) have nothing to draw: 1080 carry only a `Sound`, and 131 carry nothing. So most character hit and attack-start effects draw nothing (`knight_hit`, `knight_attack_start`, `hog_rider_hit`). **INFERRED.**

**Playing an effect.**

- **SWF:** play the export from frame 0 at its clip fps.
  - Position: the effect position, or the entity's when `FollowParent`.
  - Scale: `Scale/100 · RenderableScale/100`.
  - After `Time` ms of delay, play once and drop it after the last frame. With `Loop`, loop it while the owner lives.
- **Filter:** see §8.
- **ParticleEmitter:** defined in `legacy_vfx/emitters.json` (2465 emitters).
  - Schema:
    - `ParticleExportName[]` / `ParticleResource[]`: a random export per particle, mostly from `sc/effects.sc`.
    - `ParticleCount`.
    - Emitter life `MinLife..MaxLife`; spawn interval `ParticleMinInterval..MaxInterval`; particle life `ParticleMinLife..MaxLife`.
    - Angle `ParticleMinAngle..MaxAngle`, speed `ParticleMinSpeed..MaxSpeed`.
    - Z: `ParticleMinVelocityZ..MaxVelocityZ`, `ParticleGravity`, `ParticleStartZ`.
    - Spawn ring `ParticleMinRadius..MaxRadius`.
    - Scale `StartScale → EndScale`; fades `FadeInDuration` / `FadeOutDuration`.
    - `RotateToDirection`, `LoopParticleClip`, `Inertia`, `TrailSWF`, `ParticleSpecificFollow`.
  - A plain 2.5D emitter (≈150 lines) on the same sprite renderer can play these.
  - Minimal fallback: skip emitters and play the SWF components and the area art only. Most hit and death puffs will then be missing; they are about 70% of all components.

**Layers.**

| Layer | Components |
|---|---|
| Object | 3202 |
| Above | 1760 |
| Base | 1651 |
| Ground | 765 |
| GroundAbility | 179 |
| Shadow | 80 |
| HealthBars | 1 |

Order, bottom to top:

1. arena
2. **Base**: radius rings, decals
3. **Ground**: dust, stuck arrows
4. **GroundAbility**
5. **Shadow**
6. **Object**: y-sorted together with the units
7. **Above**: glows, sparks, bolts
8. **HealthBars**

Ordering is INFERRED from what sits on each layer. Base versus Ground is a GUESS.

The renderer draws the Object layer of effects on the ground (not lifted) in the same y-sort as the ground units, buildings and towers: an Object part is drawn just before the first unit nearer the viewer than the effect's anchor, so a death puff behind a tower stays behind it. Object parts of lifted effects (a flyer's death) draw with the Above layer, over the units.

**Death puff size (GUESS).** At face value `Death_player`'s purple puff (`Death_enemy_puff`: 12 additive `explosion_purple_glow1` glows at 23–30 %, a clip that peaks 243 × 182 du at frame 12) stacks into a cloud about 1.5 tiles wide, larger than the game's. A troop's or building's `DeathEffect` is drawn at 0.7 of its scale (`EffectLayer.DEATH_SCALE`).

**Reflected attacks (Electro Giant).** The core reports each reflect (`WorldObserver.reflected`; one that struck back carries the buff it gave). Each plays, from the reflecting unit's row:

- `ReflectedAttackTargetedEffect` (`electro_giant_beam`: SWF `electro_wiz_beam`, `FlashWidth 80`, `PivotClip attack_pivot`, Above, sound `electro_wizard_beam`) as `ReflectedAttackTargetedEffectSources` (2) beams. Each runs from the `attack_pivot_<n>` child of the unit's idle clip at its facing (the coils on its back) to the unit struck. The clip is authored along +x from 1 to 82 du, so it is rotated to the target and stretched along x by distance / `FlashWidth`, its thickness as authored (INFERRED). Both ends follow their units while the clip plays (23 frames @30).
- `ReflectedAttackEffect` (`electro_giant_beam_hit`: sparks, puff, `vmc_electrowizard_def_v_hit`, sound `electro_wizard_hit`) at the unit struck.
- The `ReflectedAttackBuff`'s `FilterFile`/`FilterExportName` on the unit struck (`ZapFreeze`: `filter_cold`, a blue tint 29 frames @60, about its 500 ms).

The struck point is lifted to a flyer's `FlyingHeight`, or a crown tower's `AttachedCharacterHeight` (its shooter, GUESS).

**Columns that reference effects.**

| Column | Table | When, where |
|---|---|---|
| `SpawnEffect` | characters | on deploy, at the unit (`knight_deploy` = sound + `Deploy_ground_fX_mediumSize` dust, delayed 300 ms) |
| `AttackStartEffect` | characters | when an attack clip starts, at the unit |
| `ProjectileEffect` | characters | at launch, at the shooter (`musketeer_attack`: muzzle fire and smoke, Above) |
| `DamageEffect` | characters | when this unit's hit lands, at the target |
| `DeathEffect` | characters | at the death point. `knight_die` → `Death_player` (elixir burst + Base splat) + sword particle. **No death clip: remove the sprite and play this** |
| `ReflectedAttackTargetedEffect`, `ReflectedAttackEffect` | characters | each reflect that strikes back: beams from the reflecting unit's pivots, a hit on the unit struck (see below) |
| `MoveEffect` / `LoopMoveEffect` | characters | footsteps, on the `run1` `action_frame1/2` labels (Ground) |
| `LandingEffect` | characters (55) | end of a jump or dash |
| `ContinuousEffect` | characters, buildings (88 rows) | from the moment the unit has deployed, for as long as it lives (see below) |
| `TrailEffect` / `HitEffect` / `DeathEffect` | projectiles | §6 |
| `OneShotEffect` | area_effect_objects (65) | once when created, at its centre |
| `LoopingEffect` | area_effect_objects (61) | loop for `lifetimeMs` |
| `ScaledEffect` | area_effect_objects (108) | ground art (Base), loops, **scaled to `Radius`** |
| `SpawnEffect`, `HitEffect` | area_effect_objects | when it spawns something / on each unit it hits |
| `FileName`/`ExportName` | area_effect_objects (3) | its own sprite (`evo_cannon_crosshair`) |
| `Effect` | spells_*.csv | at cast (`Cast_RainOfArrows`, `CharacterDeploy`: sound only) |

**Continuous effects.** A troop's or building's `ContinuousEffect` starts when the unit leaves DEPLOYING (or when it first appears, for a unit that never deploys) and is played like any effect: an effect with `Loop` follows the unit and ends when the unit is gone, and one without `Loop` plays once at that moment. The column is DATA; starting it at the end of the deploy is INFERRED; playing a non-looping one once is a GUESS (`ram_steps` on `SkeletonKing`, `Guardienne_steps`, `wall_breaker_cont_effect`, which draws nothing). Examples:

- `ElectroGiantZapAreaEffect` (`FollowParent`, `Loop`): emitter `electro_giant_zap_area_ground` (one `ghost_effect_glow_shadow` glow, 300 → 250 %, Ground), `ps_electrogiant_zaparea` (Base, `RenderableScale` 78: one particle of `sc/vfx_autochess.sc` `vmc_electrogiant_def_v_zaparea_elec`, `LoopParticleClip`, `ParticleSpecificFollow`) and `electro_giant_zap_area_border` (Object: up to 38 `electricity` sparks on a ring).
- The field's disc is four quarter-disc shapes of ±128 du squashed to 0.817 in y, its soft edge at about 98 du; at 78 % that is about 77 du, 2.6 tiles. The row's `ReflectedAttackRadius` is 2000 (2 tiles); nothing in the data scales the effect to it, so it is drawn at its authored size (DATA; whether the game rescales it is unverified).
- Others: `golem_cont_effect` (fireflies), `phoenix_idle` / `Egg_blue_phoenix_idle` (`EnemyVersion`s), `icespirit_continuous_fx`, `firespirit_continuous_fx_01`, `healspirit_cont`, `elixir_golem_continuous`, `skeletons_king_aura`. `goblin_hut_cont_effect` has no components.

`ParticleSpecificFollow` (12 emitters) keeps each live particle on the effect's moving anchor; other particles stay where they were emitted. INFERRED from the name.

**Spell chains (DATA).**

- **Arrows:** spell → 30 × `ArrowsSpell` projectiles → `ArrowHitGround` (§6).
- **Fireball:** spell → `FireballSpell` → `Fireball_explosion`.
- **Zap:** AEO `Zap` (life 1 ms) → `OneShotEffect` `vfx_zap_polish` (red: `vfx_zap_polish_enemy`). That is the bolts `zap_bolt1/2` (Above), glow and smoke, plus Filter `filter_target` (white flash on hit units).
- **Poison:** AEO `Poison` (8000 ms, R 3500).
  - OneShot `Spell_poison_effect`.
  - Looping `Spell_poison_loop` (skull particles).
  - Scaled `Spell_poison_ground` = SWF `Poison_radius` (509 f @60, Base) + `spell_poison_radius_blue` (390 f @30, Base). Red: `Spell_poison_ground_red`.
- **Freeze:** AEO `Freeze` (3500 ms, R 3000).
  - Looping `Spell_freeze_loop`.
  - Scaled `Spell_freeze_ground` = `freeze_effect_ground` + `spell_freeze_radius_blue` (Base) + snow particles (Ground).

**ScaledEffect size.** Scale the art uniformly so that its ring's horizontal half-width (in du, from the first frame's bounds) equals `Radius/1000 · 29.75` du. Rows share one effect at different radii (`Spell_freeze_ground`: 3000 and 40000), so the scale must come from the radius. The rings are authored at about 30 du per tile (§3), so at the authored radius the scale is about 1. **INFERRED.**

**sc3d.** `assets/sc3d/characters/` holds 3D assets for about 50 units (knight, archer, giant, hogrider, musketeer, princess, pekka, …). Every character and projectile `FileName` still exists as SC2, so SC2 art exists for all of them. Only 9 Prefab effect components and 8 projectile `PrefabAsset`s are 3D-only. **DATA.**

### Abilities and warps (`EffectLayer.abilities`)

The same rules for every unit's ability, driven by the core's `abilityRequested` observer event. The simulator core is not changed for rendering, and it reports no warp start, so the warp rows below (`WarpPositionEffect`, `WarpTargetEffect`) are not drawn yet: the unit flies with no beam, sound or tint.

| What | When and where | Confidence |
|---|---|---|
| The row's `AbilityPendingEffect` (`Heal_aura`) | while an ability requested with `now == false` waits; it ends as the unit enters CASTING or leaves | INFERRED (column name) |
| The ability row's `DeployedEffect` (`vfx_hero_ability_active_m`; the champions' `ability_charging_*`) | as the cast starts (a request with `now`, or a pending one entering CASTING), following the unit at its flying height | INFERRED: the champions' effects are named "charging", and `CastTime` is the charge |
| The ability row's `DeployedClip` | not drawn: the champions' is `champion_select_ring_rampup_*` and the heroes' `filter_invisible`, which read as the HUD ability button's clip | GUESS |
| A warp action's `WarpPositionEffect` | where the unit starts | INFERRED |
| A warp action's `WarpTargetEffect` (`vfx_megaminion_hero_ability_dash`) | its SWF has `FlashWidth 80` and `PivotClip attack_pivot_1`, so it is a beam as the reflect beams are: from the unit's pivot (at its flying height) to the warp's destination, following the unit as it flies; its sound (`card_hero_megaminion_skill_cast`) at the start; its Filter component (`vmc_megaminion_hero_dash_filter`, multiply (128, 128, 255) for 20 frames then back to white by frame 29) on the unit | the columns and clips DATA; the beam reading INFERRED |

**The Mega Minion hero's teleport (DATA, core).** `MegaMinion_Teleport_Ability` (CastTime 250, TriggerDelay 200) → `ActionMegaMinionHeroAbility` → `ActionWarpCharacter` `MegaMinion_hero_teleport_action` (Speed 1500, Acceleration 400 game units a tick): the core (`WarpRun`, traced against a reference battle) flies it 400, ~850, ~1260 units in three ticks onto its marked target, the arrival group spawning `MegaMinion_hero_Damage_Buff` (Invisible, `filter_invisible`, removed on its next attack, which `ActionSetInstantHit` fires at once) and its `MegaMinionSpit_DoubleDamage`. `ReturnDelay 3500` later `MegaMinion_hero_return_action` flies it back the same way. In the example replay: cast at 15.1 s, flight 15.3–15.45 s, return flight 19.05–19.2 s (the user's "teleport at 0:19"). The jump is the data's warp, not a drawing fault. Its beam, sound and tint need a warp-start report from the core, which is not changed for rendering, so they are not drawn.

**Filter placeholders (INFERRED).** A Filter clip whose matrix is the same on every frame and not the identity only sizes its placeholder: the hero files' filters hold a 32 du square scaled 3.88 × 4.38 throughout, where `effects.sc`'s filters start at identity. Such a matrix is not applied to the unit; its colour is. A target filter's multiply tint now also holds through a damage flash (before, only its additive colour did).

---

## 8. Shadows, flying units, deploy and damage filters, team colours, health bars, draw order

### Shadows

- No body-shadow export exists for normal characters. The shadow exports in `chr_*.sc` are projectile shadows only.
- Draw the unit's current frame again as a dark silhouette: RGB → 0, alpha about 0.35–0.4, blurred if cheap (`client_globals` `SHADOW_BLUR_DISTANCE = 170`). Place it at the ground point, under the Object layer.
- Transform in du about the clip origin:
  1. `scale(ShadowScaleX/100, ShadowScaleY/100)`
  2. horizontal skew `x += ShadowSkew/100 · (−y)`
  3. translate `(ShadowX, ShadowY)`
- Typical values (ScaleX / ScaleY / Skew / Y):

  | Units | ScaleX | ScaleY | Skew | ShadowY |
  |---|---|---|---|---|
  | Knight, Hog Rider | 85 | 85 | 35 | −10 |
  | Musketeer | 80 | 80 | 35 | −10 |
  | Archer | 75 | 75 | 25 | −15 |
  | Giant | 85 | 80 | 25 | 0 |
  | Princess tower (ShadowX −5) | 102 | 70 | 13 | 5 |
  | Cannon | 90 | −90 | 10 | 10 (ShadowX −7) |

- Flyers use a negative `ShadowScaleY` (−60: Minion, Baby Dragon, Lava Hound; 40 rows). The silhouette is flipped vertically and drawn at the **ground** point, not under the lifted sprite.
- Dedicated shadow sprites:
  - `ShadowCustom` / `ShadowCustomLow` (7 rows: `bat_shadow`, `flying_machine_shadow`).
  - `BlueShadowExportName` / `RedShadowExportName` (Cannon EV1).
  - Projectiles: `ShadowExportName`.

**Confidence.** The columns are DATA. "A silhouette of the sprite" is INFERRED. The skew sign, alpha and blur are GUESSes; check one in-game screenshot.

### Flying units

`air` is `FlyingHeight > 0`. Draw the sprite at the ground point lifted `FlyingHeight · 0.01742` du (§3.4). Examples:

| Unit | FlyingHeight | Lift |
|---|---|---|
| Minion | 1500 | 26 du |
| Balloon | 3000 | 52 du |
| Baby Dragon | 3500 | 61 du |
| Lava Hound | 4000 | 70 du |

Use the same factor for projectile z. The values are DATA; the factor is INFERRED from one tower data point.

### Deploy filter

`DeployBaseAnimExportName` names a clip in `sc/effects.sc`:

| Export | Rows |
|---|---|
| `filter_deploy_unit_default` | 336 |
| `filter_deploy_unit_legendary` | 124 |
| `filter_deploy_unit_miner` | 10 |
| … | |

It is a **filter**: a clip with one placeholder child, shape 193 (a goblin). Its per-frame matrix and colour transform are applied to the real unit sprite, pre-multiplied in the unit's local space with the origin at the feet.

`filter_deploy_unit_default` has 35 frames at 60 fps (≈ 583 ms):

| Frames | Matrix | Colour |
|---|---|---|
| 0–14, drop-in | `[0.868, 0, 0, 1.319, 0, ty]`, ty −64.95 → 0 | alpha 102 → 255, additive (150,150,150) → (25,25,25) |
| 15–34, landing squash | scale (1.10, 1.11), (1.33, 0.91) … → (1, 1) | none |

Play it from the first `DEPLOYING` tick. Then show `idle1` until the unit leaves DEPLOYING. Draw nothing during `WAITING_TO_DEPLOY`. The 300 ms `Time` on the deploy dust lines up with the landing at frame ≈ 14.

**Confidence.** The transforms are DATA. The filter semantics are INFERRED. The start alignment against `DeployTime` is a GUESS.

### Deploy clock

`sc/ui.sc` has `troopDeployTimer_player` (drawn for the side drawn blue) and `troopDeployTimer_enemy`: 324 frames at 30 fps, label `end` at 299. Frames 0–299 are one full turn of the hand with the wedge filling behind it (the hand at −80° at frame 0, 0° at 75, 90° at 150; the dial marks appear at 75, 152 and 218); 299–323 hold the full dial, flash and shrink it away; frame 323 is empty. **DATA.**

- **One clock per card play** that puts units down, at the placement's point (`Standard1v1Battle.Play.result()`), however many units the card makes (Skeleton Army, Minions, Guards, a Tombstone hero's three rows). A spell puts no units down and has none. The real replay clip shows one clock in the middle of a Skeleton Army and one for a building (a Tombstone). **DATA** (reference clip).
- It appears when the play's units start deploying (a Miner's when it surfaces at the point), and frames 0–`end` are stretched over the longest `DeployTime` of its units: in the real clip a Tombstone's (1000 ms) dial makes one full turn in about a second, flashes, holds for about 0.7 s and shrinks. After `end` the clip plays on at its own 30 fps. If all the play's units die first, the clock goes with them. **INFERRED** from the reference clip (15 fps).
- The earlier version keyed clocks on the units seen deploying and played only the last `DeployTime` seconds of the 10 s clip: a 1 s deploy showed frames 269–299, an almost full, static dial that vanished with no flash or shrink, which did not read as a clock.

### Damage flash

- `DamageExportName` is `filter_damage` (367 rows) or `filter_damage_wobble` (106). The real exports carry a direction suffix:
  - `filter_damage_{down,up,left,right}`: 25 f.
  - `filter_damage_wobble_*`: 50 f.
- Pick the suffix from the screen direction of the hit, from attacker to unit.
- `filter_damage_down` frames 0–4 go from scale (1.09, 0.912) with ty +4 back to identity. The colour add is (17,8,0) throughout.
- `filter_target` (white add 150 → 35 over 30 f) is Zap's flash.
- This needs a per-unit "was hit this tick" event. `AreaHitLog` and the damage numbers may already give it.

**Confidence.** DATA for the transforms; INFERRED for the trigger.

### Team colours

The bottom side of the `ViewOrientation` is blue/"player". **DATA.**

- Characters: `BlueExportName` / `RedExportName`.
- Projectiles: `RedExportName` / `RedShadowExportName`, else the same art for both.
- Effects: `EnemyVersion`, and `_blue` / `_red` art pairs such as `spell_poison_radius_blue/red`.

### Health bars

- `csv_client/health_bars.csv` maps the row's `HealthBar` column (Medium 233 rows, Small 104, High 60, Tower, Shield, `*_EV1`, `High_2_Segments`, …) to exports in `sc/ui.sc`:
  - Units: `hp_player_small/medium/high` and `hp_enemy_*`.
  - Towers: `hp_player_tower_withNumber` and `hp_enemy_tower_withNumber`.
  - Segments: `SegmentsCount` with `hp_bar_divider_player/enemy`.
  - Visibility: `ShowEnemyAlways = true` for units. Own bars are hidden until damaged (`ShowOwnAlways` only for Tower and Summoner). The HUD reads both columns per bar class: a unit at full health on the side drawn blue (`ViewOrientation`, so `--bottom` flips it) shows no bar and no level badge; one on the side drawn red shows its badge alone (`NoDamageEnemyExportName`, `hp_enemy_number`). "Damaged" is hit points under the maximum, a shield under its maximum (broken or not), or a timer bar. **DATA.**
  - The real reference clip (a replay viewed in the game) shows the bottom side's full-health troops with badges too (spear goblins, skeletons, both "13"/"15"): the replay viewer seems to treat both sides as not its own. The recorder follows the table and the owner's rule for a player's view: no badge on the bottom side's full-health troops.
- Each export is a composite with named children:
  - `bar`: a 100-frame clip. **frame = round((1 − hp/max) · 99)**: 0 is full, 99 is empty. **DATA**, from the bounds.
  - `level`: a text field.
  - `downgrade` and `chef_upgrade`: hide them.
  - The tower bar adds `crown` and `hpNumber`.
- Sizes in du:

  | Bar | Size |
  |---|---|
  | small | ≈ 34×18 |
  | medium | ≈ 48×18 |
  | high | ≈ 65×18 |
  | tower | ≈ 93×23 |

- `healthbars.toml` (`Healthbar_Badge_*`, `Healthbar_Bar_*`) is a newer split version of the same art.
- **Placement (INFERRED):** `bar y = top of the unit's idle1 sprite bounds + HealthBarOffsetY` (du, +y down). 220 rows set it, mostly −10; Knight's +28 cancels its raised sword. `HealthBarOffsetYBlue/Red` override it per side. Flying units add their lift.

### Hand cards

An unaffordable card in the hand is turned to its luminance (grey), with no wash toward white. The client's ability button has `Grayscale = true` for its `not_enough_elixir` state (`csv_client/ability_button.toml`); the hand card (`spell_card_ingame` in `sc/ui.sc`) has no disabled frame or colour of its own, so it is assumed to use the same state. **INFERRED.**

### The player's view (`--pov`, key U)

The replay HUD (`Replay_HUD_player_bottomMiddle` / `Replay_HUD_enermy_topMiddle`, `HUD_topRight_replay`) shows both hands. A live battle's HUD is a different set of exports in `sc/ui.sc`; the player's view draws it for the view's bottom side (`ViewOrientation.bottomSide()`, so `--bottom` and F choose the player).

| Export (ui.sc) | What it holds | Used |
|---|---|---|
| `HUD_topLeft` (1654) | `darken` (a soft black gradient at 20%), an unnamed plate behind the badge (the same soft shape at 50%), `clan_icon` (a badge placeholder, shape 705), `enemyName` (Supercell-Magic 26, pink `0xffff3399`, left-aligned), `enemyClan` (SC CCBackBeat 18), and one of `enemyTrophies` / `enemyRankedRating` / `enemyRankedLeague` (trophy, rating or league icon with a number) below | the darken, the plate and `enemyName`, anchored at the top left corner. **DATA** |
| `HUD_topRight` (1692) | the clock's box, `timeLeft`, `TID_TIME_LEFT` and `elixirRegen` (the x2 badge), at -101..5 du from its origin: the top right corner | in place of `HUD_topRight_replay`, which stands under the replay's top panel. **DATA**; the overtime label is drawn as the replay HUD draws it |
| `HUD_rightMiddle` (1676) | the crowns, at the right edge level with the river | the same in both HUDs. **DATA** |
| `HUD_player` (1777) | the live bottom panel: a full-width `darken`, `panel`, four `slots`, the elixir bar with its own numbers, `nextCard1`, `NextSpellTimer`, and `ability_location` | not used: the bottom hand stays the replay panel's, as it is in the replay HUD. `ability_location` places the ability buttons (below) |

- **Not drawn, no data (DATA that the replay lacks):** the replay carries no clan, trophies, ranked rating or league for a player, so `enemyClan`, `clan_icon` and the three number rows are hidden rather than invented. A player without a name in the replay is shown as "Opponent".
- **INFERRED:** the bars are anchored at the same top inset as the replay HUD's top panel (the phone's status bar), and the arena keeps the replay framing. Nothing in the data gives a live camera that differs.
- **GUESS:** the plate behind the badge is drawn even without a badge. It is faint: the shape's texture is a soft black at most about 47% alpha, times the clip's 50%.
- **Interactive view:** the workspace hides the top side's hand panel and its elixir, and `RealisticView` draws `HUD_topLeft` at the arena's top left at 18.6 tiles to the phone's 600 du. The visualizer keeps its own clock and crowns.

### Ability buttons

Champions' and heroes' ability buttons, from `sc/ui_ability_button.sc` `ability_ingame_button`. In the replay HUD both sides have them; in the player's view only the bottom side does. A slot's state is the core's `ChampionController` state (`BattleFrame.AbilityView`, which also carries the ability row and the full cooldown).

- **The clip (DATA).** Root labels: `appear` 0..32, `loop` 32, `disappear` 33..51, at 30 fps. The child `states` has labels `available` 0, `limited_time` 1, `not_enough_elixir` 2, `cast` 3..21, `cool_down` 22, `disabled` 23 and `deploying` 24. Its children are:
  - `darken`, a 40% shadow;
  - `pulse` (a white ring, 29 frames) and `pulse_red` (21 frames), both looped;
  - `bg` (blue) and `bg_grey`;
  - `icon_ability`, whose text field `icon` is the icon's place, scaled 1, 0.92, 0.7 or 0.5 with the state;
  - `ability_button_mask`, a pale veil at 52%;
  - `elixir_count` and `elixir_count_grey`: the drop and the cost, text 11 and 14;
  - `charge` with `charge_count` (text 12);
  - `timer`, the limited time's count, which is not filled;
  - `elixir_cooldown`: `cooldown_bg`, a navy disc with a yellow ring, and `cooldown_mask`, the same disc without the ring;
  - `icon_cast`, a second icon place animated over the cast.
- **State to frame (DATA, `csv_client/ability_button.toml`).**

  | States | Frame |
  |---|---|
  | ChampionUnknown / ChampionAbsent | Alpha 0: the button plays `disappear`, and `appear` when the champion comes back |
  | Ready, ChampionDeploying | `available` |
  | LimitedAvailability | `limited_time` |
  | NotEnoughElixir | `not_enough_elixir`, Grayscale |
  | ChampionCasting | `cast` |
  | OnCooldown, AllChargesConsumed, ChampionPending | `cool_down` |
  | Disabled, NoYetAvailable | `disabled`, Grayscale |
  | TemporarilyUnavailable | no label: the frame it had |

  The client's `Ready` sound (`ui_battle_general_ability_button_ready`) is not played.
- **The cast frames (INFERRED).** The core gives `ChampionCasting` only to a copy that casts with charges left. A Mega Minion hero (MaxCharges 1) goes from Ready straight to AllChargesConsumed. The `cast` frames run from the available look into `cool_down`, so they are played on every use: a change from `available` or `limited_time` to `cast` or `cool_down`.
- **The cooldown dial (INFERRED).** `cooldown_mask` covers the share of the cooldown still to run, `cooldownMs / cooldownFullMs`: the part counter-clockwise back from twelve o'clock. As the cooldown runs, `cooldown_bg`'s yellow ring shows clockwise from twelve. Out of charges, or with a use pending, the whole mask shows. The direction is a GUESS.
- **Icon (DATA).** `character_abilities` `IconSWF` / `IconExportName` give the icon: heroes' `sc/ability_icon_<hero>.sc` and champions' `sc/ui_spells.sc` `icon_ability_*`. It is drawn at the `icon` field's transform, between the button's back and what lies over it. `HideChargesTextField` hides the charge count. The cost is the core's ability cost. The elixir bar drops when the core charges it (Mega Minion: 2 at 15.05 s in the example replay).
- **Placement.** `HUD_player`'s `ability_location` has the labels `left_align`, `right_align`, `both_sides` and `top_debug`. Its markers (`ability_left` and `ability_right`, 22 du dots) sit on the panel's top edge: in `right_align` at x 166.2 and 275.75, the right one at the right edge of the hand's slots. `client_globals.csv` `ABILITY_BUTTON_CONFIG = 1` is read as `right_align` (INFERRED; the settings popup `ability_button_placement` offers left, right and both). The button clip's round back spans -84..-4 du from its origin, so its origin is its lower right corner, and it is put on the marker (INFERRED). The buttons shown stand from the right in slot order, 109.55 du apart (DATA). On the replay panel, the right edge is the panel contents' right edge, 277 du, and the bottom is the panel's top, -134 du (INFERRED, by analogy). Which marker a lone button takes is a GUESS: the outer one.
- **The top side's buttons (GUESS).** The replay HUD shows no live layout for the opponent. Its buttons are turned half a turn with its view, under its panel at the left, clear of the replay clock, which stands under the panel at the right. Their top is at the panel's `darken` strip (151 du), below its `Next:` label.

### Shader FX

`ShaderFXBase/Top = Outline_Blue` appears on only 8 rows (an outline). Skip it.

### Draw order (INFERRED)

1. Arena.
2. Effect layers Base, then Ground, then GroundAbility.
3. Shadows: unit silhouettes, projectile shadows and Shadow-layer particles.
4. Ground units, buildings, towers and Object-layer effects, **sorted by screen y, far first**. A tower draws base, then attached character, then top (§5).
5. Air units and projectiles in flight, sorted by y.
6. Above-layer effects.
7. Health bars.

`VisualLayer = GROUND` exists on a single row (a decoration dummy that draws on the ground).

---

## 9. Crowns and the end of the match

**The core's end (DATA, `LadderMatch`).** `end()` runs once the match is decided (three crowns, or the time up with unequal crowns): `ended` is set, the timeline freezes, attack timers are held and hits refused (`BattleWorld.matchEnded`), and the winner is the side with more crowns. The battle keeps stepping for `endScreenDelayMs` (4 s in this data): the circle from a fallen king kills its side's units and buildings over the first half. Then the session is `over`. The frame's `timeMs` and the sides' elixir still run on after `end()`; the match clock the HUD shows does not.

**What the recorder shows (`MatchEnd`).**

| When | Shown | Heard | Label |
|---|---|---|---|
| A side's crowns go up | the tower's `DeathEffect` and ruin (§5.3) | `sound_get_star_player` when the bottom side takes it, `sound_get_star_enemy` (a crowd boo) when the top side does | INFERRED: crowns are "stars" in the client's names (`StarTower_*`), and the two rows sit with the battle sounds in `sounds.csv` |
| `ended` | the HUD's clock, elixir and its rate hold their values at the end; each unit stands `idle1` at its position and facing at the end; the losing side's units still die to the circle; destroyed towers have no bar | `Gameend Horn` (`music_jingle_general_gameend_horn`); the battle music stops (a `{"t", "end": true}` line in the sounds file); no countdown or music change after it | freeze: INFERRED from the core (timeline frozen, attacks held); units idle in place: GUESS; horn at any end: INFERRED from its name |
| `over` (4 s later) | `sc/ui_battle_end.sc` `replay_battle_end_new` over the arena, scaled so its `screen_area` (768 × 1024 du) fills the width: the 50% black backdrop (stretched to the phone's height), `player_01` (bottom side) and `player_02` panels sliding in (`appear_start..appear_end`), each won crown popping in (`crown_k`: `crown_appear_start..end`, 0.75 s + 0.4 s per crown), then `winner_start..winner_end` and `winner_txt` ("Winner!", text field 280/283) on the winner's panel; `draw` on a draw | `Win` / `Lose` / `Draw` (`music.csv`, from the bottom side's view) at its start; `sound_crown_appear_1..3` (pitched +0/+2/+4 semitones in the bank) as crowns 1..3 pop | the clip and labels are DATA; their timing, hiding the buttons, taunts, names, trophies and league, and the jingle's moment are GUESSes |
| `over` + 4.5 s | the recording stops (`OUTRO_TICKS = 90`) | | ours |

Not used: `sound_get_crown_1..3` (the same sample pitched like `crown_appear`), `First_Crown` / `Second_Crown` / `Third_Crown` (listed beside the achievement and chest sounds, likely menu sounds), `Pre_result` / `Post_result_loop` music, and the `win_battle_confetti_blue/red(_top)` effects (70 confetti particles at 100–200 speed, up to 800 z speed under -150 gravity, 10–15 s): where and when the client starts them is not in the data, and burst over the winner's king they hang over it as a cloud.

**The start jingle (INFERRED).** `MUSIC_START_TIMES` starts `BattleMusic` at 180 s left, when the clock starts; the location's `Sound` jingle belongs to the intro banners before it. A recording starts at the clock, so the mixer starts the music when the jingle's sound ends: its length up to its last audible point (above -40 dBFS), so its silent tail leaves no gap (`audio.battle_schedule`).

**Thinning the mix (INFERRED, ours; `audio.py`).** The banks' own per-event limits come first (`max_instances`, stealing, `cooldown`: Skeleton steps 4 instances and 0.15 s; Archer arrow hits 2 and 0.1 s). On top of them:

- footsteps and wing flaps (`*_steps`, `*_step`, `*_wing`) start at most every 0.3 s per event and play 4 dB quieter;
- voice lines (`*_vo`, e.g. the Miner's grunt) at most every 2 s per event;
- each still-playing instance of an event lowers a new one by 2 dB (at most 6 dB).

The game's 3D attenuation, buses' snapshots and ducking are not in the parsed data; these rules stand in for them.

## 10. 3D assets (sc3d)

The rows that the game draws only in 3D are listed in §1.1 (hero forms, `Ronin`, `MinionGiant`, evolutions with a `PrefabAsset`). `crforge-art sc3d` (see `tools/game-art/README.md`) extracts them as standard glTF 2.0 into `<cache>/<sha>/sc3d/`. The renderer draws the Hero Tombstone's rows with them (§10.1), the ground troops (§10.2), the fliers and model swaps (§10.3), and the 3D projectiles and effects (§10.4); the other 3D rows keep their 2D stand-in (§1.1) until each is checked against the real game.

**Where the data is (DATA).**

- `assets/assets.scdb` is a SQLite database with tables `meta(path, assetType, assetFilename, uuid, …)` and `asset(path, data, …)`.
- Prefabs (`ScrollRuntimeScene`), animation state machines (`AssetASMData`) and timelines are JSON in `asset.data`. References are written `"path | uuid"`.
- `PrefabAsset = "sc3d/.../hero_prefab_x_battle"` names a prefab. Its node tree holds the following components (`objectType`):
  - 5001 Model: `.glb`, plus `Geometries[].MaterialOverride` (`.rmat`);
  - 5003 `AsmAsset`;
  - 5007 light;
  - 5019 `PivotPoints` (`hudoffset`);
  - 5006 nested `Prefab`;
  - 5018 `MaterialAsset`;
  - 5004/5008 particles and movie clips;
  - 5014/5016 timelines.
- The battle prefabs name the `lod1` models. `lod0` appears only in the `_collection` (menu) prefabs.

**File formats.**

| File | Format | Label |
|---|---|---|
| `.glb` | glTF binary container; the JSON chunk is replaced by a `FLA2` chunk: the glTF 2.0 schema as a FlatBuffer (field order in `gltf_flat.fbs`). It decodes to sane JSON for all 1437 files, and every accessor fits its bufferView | DATA |
| `SC_odin_format` | node parents, interleaved vertex streams, materials and packed animation in a glTF extension (`odin.py` docstring) | DATA (decodes and validates for all files) |
| vertex formats 30/29/22/12/9/3/36 | float3 / float2 / unorm16×2 / snorm8×4 / unorm8×4 / u8×4 / packed weights (11+11+10 bits, /4095). Normals come out unit length, weights sum to 1 and joints stay below the joint count | INFERRED, checked by the validator on all 341 models |
| UV (format 22) | two uint16 over 0..2 (value / 32767.5), v down as glTF's, not flipped. Read as 0..1 and flipped (the earlier reading), every battle model's UVs fill only the quarter u < 0.5, v > 0.5 of its atlas and land on the wrong parts of it (the Hero Tombstone came out grey where it is gold and wood); read so, they fill the atlas, a texture repeats, and the Hero Tombstone matches real battle captures part for part | INFERRED, against captures (§10.1) |
| UV (format 29) | float32, v down, not flipped | INFERRED (by analogy with format 22; used by tiled effect meshes) |
| packed clips | int16 stream per node: `n` keys, then `−r` = hold r frames. Rotation is xyzw·32767; translation and scale are base + value·multiplier. Every one of the 1088 clips consumes exactly its `dataSize` | stream layout DATA. "Hold" INFERRED: the step after a hold is as large as a normal frame step, so held frames are not interpolated gaps |
| `.ktx` | KTX2, ASTC 4×4 / 6×6 / 8×8 (vkFormat 157–172), no supercompression, `KTXorientation = rd` | DATA |
| `_n` | X in RGB, Y in alpha, Z rebuilt | INFERRED (R=G=B on every normal map). Whether +Y is "up" (OpenGL) or "down" is a GUESS: a lit render would settle it |
| `_mra` | R metallic, G roughness, B ambient occlusion | INFERRED (gold parts have high R) |
| `_spec` (`teamColorTex`) | R ≈ a specular/brightness mask, G/B small masks | GUESS. It is kept as a side PNG |
| `.rmat` | FlatBuffer `{version, base, params[{name, type, value}]}` with types 1 float, 2–4 float vectors, 6 texture path, 7 bool. All 1900 files decode | DATA (type 2 is INFERRED) |

**Space and units.**

- **Prefab space is Z-up (DATA).** Each model's top node `CHARACTER` rotates its Y-up mesh by +90° about X, and `hudoffset` pivots lie on +z (0.84–3.3). The `.glb` adds a `zup_to_yup` root for viewers. Drop that root to work in prefab space.
- **Facing (INFERRED).** In prefab space the characters face −Y: on every rigged character, the `L_*` bones sit at +x. In the glTF output they face +Z, glTF's front. Tombstone-like buildings are turned 180° about z by their prefab.
- **Prefab node transforms (DATA).** Each node has `pos`, `rot` in Euler degrees, and `scl`. The character models are scaled 0.67–1.0 and are turned only about z. The Euler order matters only for VFX nodes and is a GUESS (x, then y, then z): `index.json` `models[].transform` uses it.
- **Scale: 50 du per prefab unit (INFERRED, measured; §10.1).** The earlier guess of 1 unit ≈ 1 tile was wrong: it makes the Hero Tombstone 1.3 tiles wide, where real battles show it 2.3 tiles wide.

**The battle environment (DATA).** The extractor records it in `index.json` `environment`:

- `client_globals.csv`: `VANILLA_3D_ACTOR_ENV` = `sc3d/vanilla/maps/arn_default/prefab_arn_default`, `VANILLA_3D_ACTOR_ANIMATION_REMAPPING`, `VANILLA_3D_ACTOR_COLORS` 0x0B318D (blue) and 0xA90707 (red), `VANILLA_3D_PPM` 100, `VANILLA_3D_PPM_OF_SHADOW` 50, `VANILLA_3D_SKEW_SHADOW_ENABLE` false, `BLOB_SHADOW_RADIUS_FACTOR` 0.45, `BLOB_SHADOW_SOFT_EDGE` 0.35.
- `arn_default`: a `camera_root` turned −36.8° about x, the camera 20 units out on its z, turned (−90, −180, 0): `IsOrtho`, `OrthoWidth 27.8`, `OrthoHeight 49.39`, `BloomThreshold 1.13`. A `light` (`MainLight`): rot (−60, −180, −41), intensity 2, white, ambient (132, 115, 106), `AmbientSpecIntensity 2.452`.
- Each battle prefab also has its own `sun`: rot (−80.6, 0, −32.6), intensity 3, grey 128, ambient (136, 169, 139) for the Hero Tombstone, `AmbientSpecIntensity 3.678`.
- `animation_states_remapping`: 0 Walk, 1 Idle, 2 Attack, 3 Charge, 4 Dash, 5 Deploy, 6–8 Attack2–4, 12/17 Skill, 13–15 Dash2–4, 16 SkillLoop, 24/26/28 Custom1–3, 27 Warp, 29 Walk2.

**The shader (DATA).** `assets/sc3d/shaders/*.shader` are FlatBuffers holding plain GLSL ES 3.00 sources (a length-prefixed string after each variant's header). `uber_pbr.frag` holds 256 variants, variant `i` built with the define mask `255 − i`; bit 0 `CLIP_PLANE`, 1 `FRESNEL`, 2 `LOD1`, 3 `NEW_UBER`, 4 `RIMLIGHT`, 5 `SHADOWMAP`, 6 `SHADOWMAP_SHADOW_SAMPLERS`, 7 `TINT` (the define list is a vector in the file header; the masks run 255, 254, … with the variants). What a variant does:

- Lighting in linear space; the diffuse texture is ASTC sRGB (vkFormat 172), the MRA, normal and team textures are UNORM (171, 165). The output goes to sRGB through a fixed polynomial of square roots.
- Direct light: Lambert diffuse plus GGX specular (roughness⁴ in D, a Schlick-like G with k = r²·0.798, a Fresnel term towards `min(1 − r, F0)`), masked by the team texture's red channel and the MRA's occlusion, times `u_dirLightColor`.
- Ambient: `u_sceneAmbientColor.rgb` times the albedo and occlusion; an equirectangular `reflectioinTex` (`sky_mipmaps0`), its mip by roughness, times the red mask, `F` and `u_sceneAmbientColor.w`.
- `teamColorTex`: red = specular and reflection mask, green = emission (`emissiveStrength`), blue = where the team colour `u_color` multiplies the albedo (`TINT`).
- `TINT` also brings `color_mul` / `color_add` (an object's flash or fade); `CLIP_PLANE` discards below `u_clipPlane.w` in world z (the prefabs' `ClippingPlaneEnabled`: a model rising out of the ground is cut at it); `LOD1` is a cheaper path without the normal map and reflection; `RIMLIGHT` and `FRESNEL` add terms weighted by `rl_*` and `fr_*` material floats.
- `planar_shadow.vert` flattens a model onto z = 0 along `u_lightDirectionTime` (`p − L·p.z/L.z`), scaled about a centre by a per-instance shadow shape; `planar_shadow.frag` writes coverage where the vertex is above the clipping plane; `shadow_2d_composite` sums four taps of that mask at ±`u_offset`, caps at 1 and multiplies a colour `u_c`; `custom_sprite_shadow` draws such a texture into the 2D stage. `blob_shadow.frag` is a soft disc (alpha `1 − smoothstep(1 − soft, 1, 2|uv − ½|)`).

### 10.1 Drawing a 3D unit (`render/art/model`)

`Sc3dLibrary` reads the index, `Glb` the models, `Rig` poses and skins them on the CPU (four joints a vertex, the extractor's `zup_to_yup` root left out), `Asm` and `AnimPlayer` run the state machines, `ModelCamera` holds the camera, `UberPbr` the ported shader and `ModelRenderer` draws. `RealisticRenderer` draws a row with its prefab when the library holds the row's `PrefabAsset` and the row is one of `--sc3d-rows` (by default `Sc3dLibrary.DEFAULT_ROWS`: the Hero Tombstone's rows, `TombstoneHero*`, the ground troops', §10.2, and the fliers', §10.3; plus `FX_ROWS`, the 3D projectiles and effects, §10.4, when `--sc3d-rows` is not given); a prefab without a model (a logic dummy) draws nothing. Without an extraction (`--sc3d <folder>`, default `<art cache>/sc3d`, `--sc3d off`) every row keeps its 2D stand-in.

| Rule | Label | Evidence |
|---|---|---|
| A model is rendered on its own through the battle camera into an image (with depth, at twice the screen's resolution, then box-filtered down) and the 2D batch composites it at its unit's place in the y-sorted draw order, as `PPM` and `custom_sprite_shadow` suggest the game does | INFERRED | the shaders and globals above |
| Placement: the prefab origin at the unit's ground point (the 2D projection, §3–4) | INFERRED | the Hero Tombstone's base centred on its collision circle (overlay check) |
| Camera: orthographic, 36.8° from the vertical, on the +Y side; screen right is prefab −X, screen up is the ground's −Y, heights rise by sin 36.8° | the tilt DATA (`arn_default`), the roll INFERRED (the only one that puts the ground's far side up the screen, and the Euler reading Rx·Ry·Rz with +Y forward that makes the camera node look at its root) | the door's length over its width and the headstone's height match a capture within 2–4% |
| The 2D arena keeps its own 0.811 squash (§3.1) for positions; only the model's shape uses 36.8° | INFERRED | |
| Scale: 50 du per metre, half of `VANILLA_3D_PPM` (100); the prefab's own model scale applies; the row's `Scale` column does not | INFERRED, measured | the Hero Tombstone's door is 1.03 tiles wide in our render and 1.02 in a capture, its headstone 1.36 and 1.34 tiles tall; its base is 67 du against the 2D Tombstone's 65.5. The skeletons (`Scale` 130) stand 0.83 tiles tall in a capture, as their model does unscaled (1.09 with the column). Heroes reset `Scale` to 100 where their base row differs, so the column may still matter to something else |
| Facing: characters turned about z by the logic heading from their default −Y (up the screen); buildings not turned (a red Hero Tombstone faces the viewer too) | INFERRED | prefab data (§10); captures of blue tombstones |
| Shading: the `TINT | CLIP_PLANE | NEW_UBER` variant of `uber_pbr`, ported line for line to GLSL 1.20 | the code DATA; the variant INFERRED (the material sets `enablNewUber`, the prefab its clipping plane; `LOD1` left out as the high-end path) | |
| Light for shading: `arn_default`'s `MainLight`, its colour × intensity and its ambient as 0..1 factors (not decoded from sRGB), `AmbientSpecIntensity` in the ambient's w | INFERRED | the headstone face in a capture is (156, 125, 99); ours (156, 125, 99) with this light, (189, 172, 134) with the prefab's sun (its green ambient), darker with decoded colours |
| Team colour: `VANILLA_3D_ACTOR_COLORS` as plain 0..1 factors (not decoded from sRGB, as the light colours), multiplied in where the team texture's blue says | values DATA, use INFERRED | the Hero Knight's cape is (75, 115, 170) in a real battle; ours (55, 95, 150) so, (30, 45, 110) with the colour decoded. The Hero Tombstone's mask is a small cloth patch |
| Shadow: the planar shadow, flattened along the prefab's own `sun`, the mask at half the model's resolution (`PPM_OF_SHADOW` 50), four taps, tinted by the arena's shadow colour. The sun's rotation is read as yaw and pitch (`Rz · Ry · Rx`, `ModelCamera.shadowLight`): its x angle −80.6 is the light's angle below the horizon, so a shadow reaches 0.17 m out per metre of height, down (towards the viewer) and to the left, and lies mostly under its model | the shaders DATA; the light and its reading INFERRED | captures show the 3D units' shadows to the left, as the sun casts them (the environment light would cast them right). A real grass battle shows the Hero Tombstone's shadow only as a faint band low beside its base, and the Hero Knight's as a soft blob down and to the left under him: both as this reading casts them. The other order (`Rx · Ry · Rz`, the camera's) tilts the sun to 56° and threw the headstone's shadow a tile to the left at its own height, about 40% dark. `VANILLA_3D_SKEW_SHADOW_ENABLE` is false: no 2D-style skewed silhouette. The blob shadow's globals exist; no capture shows a disc |
| Animation: the simulator state through `AnimStates` to the remapped state name: moving Walk, attacking Attack (its `action_frame` marker lined up with the hit as §1.4), deploying the one-shot Deploy at the first DEPLOYING tick, the rest Idle (the `DefaultState`); `Random` states pick a sub-state each pass from the unit id; `TransInTime` cross-fades; a transition state holds while its target is asked for | the table and machines DATA; the mapping INFERRED (§1.3) | the tombstone rises out of the ground (its Deploy clip, cut at the clipping plane) in its first 0.5 s |
| Forced animations (`ActionRunForcedAnimationOnce`, which the core leaves to the client) read off the core's row swaps: the visual turning into `TombstoneHero_broken_visual_dummy` plays Custom1 (the break); `TombstoneHero_Monster_Passive` turning into `TombstoneHero_Monster_Active` plays the Queen's Custom1 (her rise) and makes its side's tomb play Custom2 (the scatter, then `SpawnLoop`) and fade from 1 s over 1.5 s (`filter_fadeout`, `OverrideDuration` 1500) until the core removes it at 1.8 s | the actions DATA; reading them off swaps INFERRED | `tombstone_hero.toml` |
| `VisualLayer = GROUND` (the broken tomb): drawn on the ground, under the units | DATA | |
| Health bar: at the prefab's `hudoffset` pivot (its height × sin 36.8° × 50 du) plus `HealthBarOffsetY` at half scale (3D stage pixels, two to a du); the bar class from `OnStartingClientActions` (`Healthbar_High`); the champion crown badge (`healthbar_player_champion`) where the row's starting actions enable `ActiveChampion`; the passive Queen's bar hidden while its tombstone stands | INFERRED | the bar centre sits 60 du above the Hero Tombstone's ground point in a capture: 30 from its 1.0 pivot and 30 from `HealthBarOffsetYBlue` −60 |

**Checked against the real game** (captures of real battles from TV Royale and gameplay videos, kept out of the repository): the Hero Tombstone's size, angle, colours, bar, badge and crown; the skeletons' size; the Tomb Queen's look and her shadow's side.

**Still differs or is unverified.**

- The shadow's light is read differently from the shading light: the shading keeps `forward`'s `Rx · Ry · Rz` reading of `arn_default`'s light, matched to the captures' colours; under the shadow's yaw-pitch reading that light would come from 60° up, from the screen's left and the near side. The per-instance shadow shape (`a_shadowShape`: a scale about a centre) is left at none: the rows' `ShadowScaleX/Y` are the 2D silhouettes' and some are negative (fliers, `ElectroGiant_EV1`). The Tomb Queen's shadow in an ice capture reaches further left than the sun's short shadow does; her raised hammer's shadow lies below it there.
- The broken tomb's break and scatter, the Queen's rise and the deploy were not seen in a capture at the frame level.
- No bloom (`BloomEnabled` on high-end devices, threshold 1.13; §10.4). Rim light, Fresnel and the 3D effects of the projectiles and hero/evolution abilities are drawn (§10.4); the Hero Tombstone's own `vfx_*` effects (the countdown, the Queen's rise) are not drawn in 3D: their 2D effects are drawn as before.
- The passive Queen's ability timer (`quest_timer`, `Tombstone_hero_Ability_Timer_Bar`) is drawn as the plain timer bar.
- The game's own hitches: no MSAA (the 2× render stands in), no per-frame skinning on the GPU.

**What other models need.** Their row in `--sc3d-rows` (or `*`), then a check against captures: heroes with Attack and Walk states play through the same table; dashes, charges, skills and warps (`Dash*`, `Charge`, `Skill`, `Warp`) need their simulator states mapped; riders, multi-model prefabs (`BowlerHero_SiegeProjectile`) and the `vfx_*` prefabs (particles, `MovieClip` components) are not drawn; models with `MaterialOverride`s or other shaders (`uber`, effect materials) need those shaders ported; flying heroes need their lift checked.

**The phone server.** It records the renderer's 2D draws into draw lists. A 3D model reaches the batch as one image per model and one per shadow, rendered anew each frame into a shared frame buffer, which a draw list could only carry as a new texture every frame; so while a frame is recorded for draw lists the 3D rows (and their 3D projectiles and effects) fall back to their 2D stand-in (`RealisticRenderer.setDrawModels`, [phone server: 3D units](phone-server.md#the-picture-drawn-on-the-phone)). Video (JPEG) pages see the models. Drawing them on the phone would mean sending the model, its clip and time and rendering it there (WebGL with the same shader).

### 10.2 Ground troops (`GroundTroopStates`)

The rows `Sc3dLibrary.GROUND_TROOP_ROWS` are drawn in 3D by default, each named whole (a trailing `$` in `--sc3d-rows`): the heroes `KnightHero`, `MiniPekkaHero`, `ValkyrieHero`, `GiantHero`, `IceGolemiteHero`, `MusketeerHero`, `WizardHero` (the ground form; `WizardHero_air` is the fliers'), `IceWizardHero`, `ElectroWizardHero`, `EliteArcherHero` (the Magic Archer), `BowlerHero`, `GoblinHero`/`GoblinHero_Base`; `Ronin`; the evolutions `Giant_EV1`, `AngryBarbarian_EV1`/`_EV1_2`, `ElectroGiant_EV1`; and the units their abilities make: `MusketeerTurret`, `EliteArcherHero_Dummy` (the decoy), `IceWizardHero_IceCube` (the snowman), `IceWizardHeroFloatingCube` (the ice chunks, a rider of the Ice Wizard), `Goblin_dummy` (the unarmoured goblins) and `GoblinHero_Flag_Building` (the banner). Their projectiles and `vfx_*` prefabs are not drawn here.

| Rule | Label | Evidence |
|---|---|---|
| Size, light, shading and placement as §10.1, unchanged: 50 du per metre and the prefab's own model scale | INFERRED (§10.1) | the Hero Giant is 4.2 tiles wide in our render and 4.4 in a real battle; Giant over Ice Golem 1.4 against 1.5; the Hero Knight's shoulders over the bridge 0.70 against 0.68 |
| `Selector` states play the sub-state their animation parameter selects (`Parameter`, 0 when none is named; `SwitchValues`), `Sequence` states their sub-states in turn, the last repeating; these nest with `Random` (the Hero Knight's `Attack` is a Selector over two Randoms), and an attack's time, length and `action_frame` read through them to the clip | the ASMs DATA; the parameter's default INFERRED | without them the Knight, Electro Wizard and Elite Barbarians stood in their bind pose |
| A cast (`GridEntityState.CASTING`, the ability's `CastTime`) plays `Skill`, else `SkillLoop` | INFERRED (the `Skill` clips are the abilities': `hero_minipekka_ability`, `hero_bowler_ability`, …) | the core's cast lengths: Knight 1.2 s, Mini P.E.K.K.A 0.95 s, Bowler 2.5 s |
| Hero Knight: parameter 0 is 1 while his shield holds (`Knight_hero_SetMaxShield`, `EntityView.shield`), playing his `_shield` idle, walk and attacks | INFERRED (no action sets it; the Selectors' `Basic`/`_shield` pairs) | |
| Hero Valkyrie: while `ValkyrieHero_Damage_Reduction_Buff` holds, `SkillLoop` (`hero_valkyrie_run`) when moving, else `Custom1` (`hero_valkyrie_ability_attack`, the spin) | INFERRED (the forced animation `ValkyrieHero_Whirlwind_Animation` names no state) | the core: the buff with `ValkyrieHero_Charge` while she runs at her target, then `ValkyrieHero_AttackChain` |
| Hero Electro Wizard: `PropState` (parameter 2) is 1 while `UNIT_CUSTOM_TAG_1` holds (`_Ability_Buff` or `_Recovery_Buff`), as `ElectroWizardHero_Select_Prop_Animations` sets it; his attacks are then `Attack2`; the recovery buff plays `Custom1` (`skill_end`) | the action DATA; the attack and recovery states INFERRED | |
| Hero Wizard: landing from his flight (row `WizardHero_air` back to `WizardHero`) plays `Custom1` (`hero_wizard_drop01`) | INFERRED (`WizardHero_Play_Falling_Animation`) | |
| Hero Giant: an enemy newly stunned by `GiantHero_Slap_Stun` (its source him) plays `Custom1` (`attack02`) when it stands on his right, `Custom2` (`attack02_left`) on his left, facing his target | the actions DATA (`GiantHero_Play_Slap_Animation_Right` 1, `_Left` 2); the side test INFERRED | |
| A unit whose ASM has `Death` (the Hero Mini P.E.K.K.A, the Hero Ice Golem) plays it once where it stood when the core removes it, holding its last pose 0.4 s | INFERRED | |
| A row with no index entry of its own takes its `PrefabAsset` column (`AngryBarbarian_EV1_2` inherits it from `AngryBarbarian_EV1`) | DATA (the tables) | the extractor's index lists rows by name only |
| The deploy filter (`DeployBaseAnimExportName`) scales the model's image about its ground point as well as fading and dropping it: the evolved Elite Barbarians' `filter_deploy_unit_legendary` starts 248 du up as a 0.28 × 1.64 streak and lands in 0.23 s, under their purple deploy effect | the filter DATA; applying it to the 3D image INFERRED | |
| A 3D rider (the ice chunks) is drawn as its own model at its mount's place | INFERRED | |
| A hero row (one naming an `Ability`) carries the champion crown badge; the enemy badge's level field (`ui.sc` text 15941) is filled | INFERRED | real battles show the crown over the Hero Knight's, Ice Golem's and Giant's level |

**Checked against the real game** (frames of gameplay videos, kept out of the repository; `contact_sheet.png` in the work notes): the Hero Knight (cape colour, sword hand, size, shadow), the Hero Giant and Ice Golem (sizes, colours), the Valkyrie, Wizard, Ice Wizard and snowman, Magic Archer and decoy, Goblins and banner and the evolved Electro Giant at the level of their look in a battle. The Bowler, Electro Wizard, Ronin, Musketeer's turret, evolved Giant and Elite Barbarians had no usable close capture.

**Not drawn or not settled.**

- The forced animations the core leaves to the client and shows no sign of: the Goblins banner's drop and activation (`Custom1`, `Skill`), the Ice Wizard's ice chunks' `Custom1–3`, the decoy's hit (`Custom1`), Ronin's deflects (`Custom1`, `Deploy_Deflect`), the Mini P.E.K.K.A's level-5 `Custom1`; the evolved Elite Barbarians' `PropState` (a spear ready, `AngryBarbarian_EV_has_projectile`, a variable the view does not carry); the evolved Giant's `Attack2–4`.
- The deploy filter's additive colour (its white flash) is not applied to the model.
- The heroes' own 3D effects (`Effect` events in the ASMs, the `vfx_*` prefabs) are the effects scope; their 2D effects play as before.
- What the simulator lacks: no event for `ActionRunForcedAnimationOnce` / `ActionSetAnimationModifier`, and the Elite Barbarians' projectile counter is not in `EntityView`; each sign above is read off states, buffs, shields and row swaps instead.

### 10.3 Fliers and model swaps

The 3D fliers (`MegaMinionHero`, `BalloonHero`, `MinionGiant`, `WizardHero_air`), the Balloon's `BalloonHero_Bomb` and `SkeletonTrooper`, and the heroes whose model changes with their state (`DarkPrinceHero*`, `BerserkerHero*`, `BarbLogBarbarianHero*`) are in the `--sc3d-rows` default. `HeroForms` holds what they show beyond the state table of §10.1; it reads the core's ticks through the renderer's observer (so a recording started late has seen the battle), never a change to the core.

| Rule | Label | Evidence |
|---|---|---|
| **Flying height.** A model is lifted by the core's live height (`getZTotal`, `BattleAdapter.unitMotion`), `FlyingHeight` for a flier, through the 2D factor (§3.4: `h · 0.01742` du); the row's `FlyingHeight` without one. The Hero Wizard thus rises and lands with `ActionGroundToAir` (z 0 → 2625 → 3500) rather than jumping | the factor INFERRED (§3.4); the fit DATA | The 3D fliers are modelled standing on the ground (posed idle: Mega Minion z 0.07–1.80 m, Minion Giant 0.08–2.08, flying Wizard 0.22–1.79, Balloon 0.39–3.03), so the game lifts them. `BalloonHero_Bomb`'s clip starts its fall at z 2.11–2.60 m; 3000 units lift 52 du = 1.74 m (30 du a metre up the screen), which puts the Balloon's basket bottom at 0.39 + 1.74 = 2.13 m: the bomb leaves the basket |
| **Shadow on the ground.** A model off the ground (`height > 0`) draws its shadow at its ground point (`Instance.shadowLift`), not at its lifted body, as the 2D shadows do (§8). The planar shadow itself is unchanged | INFERRED | the 2D rule; the shadow algorithm (light, darkness, offset) is §10.1's |
| **Shadow light**: the flier's shadow falls along its prefab's own sun read as yaw and pitch (§10.1, `ModelCamera.shadowLight`), as the ground troops' do. Under that reading the Balloon hero's sun (-55.7, -174.9, 110.6) is an ordinary light 55° up, so it casts a shadow with no stand-in (an earlier `groundLight`, which swapped in the battle prefabs' common sun for a light from below the horizon under the old Rx·Ry·Rz reading, is gone) | INFERRED | 48 of the 50 battle prefabs carry the common sun (-80.6, 0, -32.6); the Balloon's two differ |
| **Health bar** of a 3D flier at its `hudoffset` plus its live height (before, a row without 2D art, the Minion Giant, put it at its feet) | INFERRED | §10.1's bar rule |
| **Champion crown** over a 3D hero form's bar where its row holds the champion's `Ability` (not only where its starting actions enable `ActiveChampion`, as for the Hero Tombstone); the enemy crown badge's `level` field (15941) drawn as the enemy bar's | INFERRED | real battles show the crown and level over the Mega Minion, Balloon, Wizard, Dark Prince, Berserker and Barbarian Barrel heroes; none over the rhino or a Skeleton Trooper |
| **Team colour** `VANILLA_3D_ACTOR_COLORS` as 0..1 factors, not decoded from sRGB (as §10.1's row) | INFERRED | the blue Hero Balloon's panels in a real battle (a YouTube thumbnail) are a mid blue, about (60, 110, 220); decoded, 0x0B318D gave navy, about (25, 45, 120), raw (90, 115, 155) |
| **Mount points** (`objectType 5022 MountPoints`: `BoneName`, `Rider` prefab). The extractor records them (`index.json prefabs[].mounts`) and extracts the rider prefabs; the rider's models are posed every frame at the host model's bone: host placement × bone matrix × rider model transform, rendered with the host in one image | the component DATA; "rider at the bone" INFERRED from its names | The mounted Dark Prince (`hero_prefab_darkprince_mounted_battle`) is the prince's model with `hero_prefab_darkprince_rhino_battle` at his `mountbone_s`; the bear-form Berserker is hers with `hero_prefab_berserker_bearmask_battle`. Seen from behind, the mounted prince sits on the rhino's back as in a real capture |
| **Selector states** (`Type Selector`, `SubStates`, `SwitchValues`, `Parameter`, 0 when absent) play the sub-state whose switch value is the parameter's value, cross-fading when it changes. Parameter 0 is 1 while the unit holds a shield (the Dark Prince's `_Shield`/`_NoShield`); parameter 2 is `PropState`, 1 (the barrel) until the Barbarian Barrel hero's ability has run, then 0 (`BarbLog_hero_select_modifier`) | the states and the PropState action DATA; parameter 0 as the shield INFERRED (its names) | Before, the selector states had no clip: the Dark Prince and the Barbarian Barrel hero stood in their bind pose |
| **Clips with SSC groups.** A clip file may keep `X:SSC` (Maya segment scale compensate) above joint X; the model has no such node. The extractor folds the group into X (X' = SSC·X per frame), and a joint whose translation is neither keyed nor based keeps its rest one | INFERRED, checked | the Dark Prince's mounted walks and walking attacks, the rhino's walk and the Tomb Queen's attack zeroed 13–34 joint translations: the rhino walked 0.7 m long instead of 2.2, the prince sank 1 m. Folded, every clip's posed bounds match its idle's |
| **Form swaps** (`ActionChangeGameObjectData`: Berserker ↔ bear form, Dark Prince mounted → walking, Hero Wizard ground ↔ air, Barbarian Barrel → naked). The new prefab's players take over from the old ones when the model is the same: the same clip goes on at its time (the Berserker's `Skill` across her swap), another cross-fades from the old pose (the state's `TransInTime`, else 0.1 s, GUESS) | INFERRED | no pop at the swap frames of the clips below |
| **Casts**: `CASTING` plays `Skill` where the machine has it (the Mega Minion, the dismounting Dark Prince, the Berserker's transformation, the Wizard's take-off, the Barbarian Barrel hero climbing into its barrel) | INFERRED (`animation_states_remapping` 12/17 Skill) | |
| **Charge**: a moving unit whose charge is complete (`chargeProgress`) plays `Charge` (the mounted Dark Prince, the rhino) | INFERRED | |
| **Attack sequence**: `Attack`, `Attack2`, `Attack3` by the core's sequence index (the Berserker's `AttackSequence` 0, 1, 2), each lined up with its own marker | INFERRED (remapping 2, 6, 7) | |
| **Warp**: a tick's travel past 250 units plays `Warp` (the Mega Minion's `hero_megaminion_ability02`) | the speeds DATA (`MegaMinion_hero_teleport_action` Speed 1500, Acceleration 400: 400, ~850, ~1260 units a tick); the threshold INFERRED (walks and dashes stay under 200) | The core reports no warp start or end (§7, *Abilities and warps*), so the warp is read off the position jump; its beam, sound and tint are still not drawn |
| **Forced animations** the client runs (`ActionRunForcedAnimationOnce`) read off what the core shows: the Balloon hero dropping a Skeleton Trooper (the `BalloonHero_Skeletrooper_Projectile` appearing by it) plays `Custom1` (`BalloonHero_Ability_Play_Spawn_Skeletrooper_Animation`); the Hero Wizard landing (`WizardHero_air` → `WizardHero`) `Custom1`, `drop01` (`WizardHero_Play_Falling_Animation`); the rhino left by the dismount (`DarkPrinceHero_Mount` appearing outside a deploy) `Custom1` (`DarkPrinceHero_Mount_ForceCustomAnimationState`); the Barbarian Barrel hero's second deploy `Custom2` (`barblog_hero_set_re_deploy_animation`) | the actions DATA; reading them off spawns and swaps INFERRED | the rhino action is referenced by no table action: its use on spawn is a GUESS |
| **Reroll** (`ActionBarbBarrelHeroReRoll`): from the `BarbLogHeroProjectileReRolling` projectile's appearance until the hero deploys again, its model and bar are not drawn (the rolling barrel is the projectile's, §6/§7) | the action's `ReRollProjectile`, `HideHealthbarWhileRolling` DATA; the hiding INFERRED | the core moves the unit to the landing point at once while the barrel rolls |
| **Effect materials**: geometry whose `MaterialOverride` derives from a `vfx_*` base (`vfx_mask2_base`: ramps, masks, dissolve) is left out | INFERRED | the Hero Wizard's flame wings showed as flat grey slabs with the model's own material; real battles show orange flames |

**Checked against the real game** (YouTube thumbnails and gameplay frames, kept out of the repository): the Hero Wizard's white robe and his flight; the mounted Dark Prince from behind and the red one from the front, the rhino left behind; the Berserker and her golden spirit bear; the Barbarian Barrel hero standing in his barrel; the blue Hero Balloon's colours, size against the princess tower and its bar; the Mega Minion hero's look.

**Still differs or is unverified.**

- The Hero Wizard's flame wings and other `vfx_*` override materials on a unit's own model are not drawn: their shaders are ported now (§10.4), but only effect prefabs' models are drawn with them, not a unit model's `MaterialOverride` geometry; rim light and Fresnel overrides (the Balloon's pilot, the bear mask) draw with the plain PBR (§10.4's `FRESNEL`/`RIMLIGHT` terms apply only to effect rows' glows).
- The Mega Minion's warp: no beam, sound or tint, and the `Warp` clip only where a tick's travel shows it (the core reports no warp event).
- The rolling barrel during the reroll and the Skeleton Trooper's glide are projectiles (§6/§7), not drawn here; the trooper on the ground is.
- The flier shadows' length and darkness follow §10.1's planar shadow; a tall model (the Balloon) casts a long one.
- The rhino's `Custom1` on spawn and parameter 0 as the shield are guesses; `WizardHero` (the ground form) and its rise are drawn by its own rows' rules.

### 10.4 3D projectiles and effects (`ModelEffects`, `Fx*`)

`ModelEffects` draws the projectiles whose row names a 3D prefab and the effect rows (`ActionPlayEffect` with a `PrefabAsset`) on 3D units. `FxLibrary` reads the extraction's effect side, `FxPrefab` holds a prefab's node tree and poses it, `FxTimeline` evaluates its timeline, `FxMaterial` merges a material with its base chain, `FxShaders` holds the ported effect shaders and `FxRenderer` draws them into the model's image. By default (`Sc3dLibrary.FX_ROWS`, added to `DEFAULT_ROWS` when `--sc3d-rows` is not given) the rows below draw in 3D; an effect plays only on a unit drawn in 3D.

**What the extractor adds (DATA).** `crforge-art sc3d` now also writes:

- an `.rmat` vector value as one float slot per component (x in slot 0 … w in 3), each left out when 0. The earlier reading took them as one inline struct, which shifted or garbled every vector holding a 0 (`mask_rgba_selector` read `[1, 2.5e-44, 2.9e26, …]`, `mask_speedxy_pow_mul` `[0, 0, 0, 0]` where the file says `(0, 0, 1, 1)`);
- each material's base chain (`vfx_mask_base.rmat` …), with its `shader` (root slot 5), `blend` (slot 6) and `order` (slot 8);
- the `.ktx` textures an effect material names, as PNGs (`textures` in its JSON);
- the prefabs' timelines (`sc3d/timelines/`), particle emitters (`sc3d/particles/`, `MovieClipEmitterData`) and the sub-prefabs timeline events spawn;
- per prefab: `children` (nested prefabs and their placement), `materialEffects` (material assets on a node without a model), `particles` (emitters relative to their bone), `timelinePlay`, and each model's `materialAssets`.

**Rows drawn.**

| Row | What | Label |
|---|---|---|
| `BowlerHeroProjectile` | the boulder model (`hero_bowler_projectile_lod1_rig`), its state machine's `Walk` (the roll clip) from launch | DATA |
| `BowlerHero_SiegeProjectile`, `_CloseRange` | the boulder, its timeline (`tml_hero_bowler_longshot_projectile`: pitch −80° → 2000° over 4 s, scale 0.68 → 0.85; the rim light `mat_rim_ingame` with the timeline's colour and weight), the long shot's trail, glow and back-glow planes | DATA |
| `AngryBarbarian_EV1_RangedProjectile` | the spear and its nested `vfx_elite_barbarians_evo_projectile_battle` (the fresnel spear, the bowl waves, back-glows, trail), the prefab timeline's 30° tilt and 0.9 → 0.75 scale | DATA |
| `BalloonHero_Skeletrooper_Projectile` | the skydiver's glide loop and its spawn timeline (scale, pitch, drop) | DATA |
| `BarbLogHeroProjectile`, `BarbLogHeroProjectileRolling` | the Barbarian in his barrel, the fall (`Fall`, from frame 13 at 0.7×) and the roll clips | DATA |
| `Giant_EV1_Play_Aura_On_Character` | `vfx_giant_evo_buff_aura` on the evolved Giant while the core runs it (Looping) | DATA |
| `ElectroGiant_EV1_Play_Pulse_Effect` | `vfx_electrogiant_evo_skill`: its timeline spawns the boombox (at the `case_s` bone) and the soundwave rings | DATA; the trigger INFERRED (below) |
| `ElectroWizardHero_Continuous_Aura` | the two aura discs while the ability runs | DATA; mostly particles in the game (not drawn) |
| `BowlerHero_3DFlashEffect` | `mat_glow` (Fresnel) pulsing on `Bowler_LOD1` for `OverrideDuration` 7.3 s | DATA |
| `EliteArcherHero_Play_Triple_Shot_Effect` | `mat_glow` on the bow and arrow while the triple shot is ready | DATA |
| `AngryBarbarian_EV1_ReadyIndicator` | `mat_glow_elite_barbarians_spear` on `weapon2_lod1` for 0.75 s, 200 ms after its wait activates | DATA |

Not drawn in 3D: `IceWizardHeroFloatingCube` (the ice chunks) and `BalloonHero_Bomb` are a character and a building, not projectiles (the unit drawers own them); `BarbLogHeroProjectileReRolling` names no `PrefabAsset`; `Bowler_hero_play_rock_glow` (`vfx_glow_mat_bowler_boulder_in_range`) is run by no row of the battle data.

**Projectiles (INFERRED unless marked).** The prefab is placed at the projectile's ground point, raised to its height (the core's `z`, by the 2D projection's height over `sin 36.8° · du per metre`, so its planar shadow falls on the ground along the sun) and turned about z from its start towards its aim (the heading rule of §10.1). It is tilted nose-up along its arc (the slope of its height over the ground it covered since the last frame). Time is the seconds since it first appeared. The 2D art and shadow of the row are not drawn.

**Timelines.** Keys (`s`, `e`, type `t`: none constant `c`, 1 a line `lf → lt`, 2 a cubic-bezier easing `b = [x1, y1, x2, y2, from, to]`; every number left out is 0): DATA. The easing read as CSS `cubic-bezier`, a held end value after the last key and `NaN` controls as the line: INFERRED. Ids: `z`, `scl_x|y|z`, `pitch|yaw|roll` (read as the node's rotation x, z, y: INFERRED), `visible`, `Model.mat0.<param>[.x|y|z|w]` (the model's override material), `MaterialEffect.mat.<param>` (the node's material asset). A track's node path is below the node that plays the timeline (`5016 TimelineName` picks one of the `5014` list, `Loop`). Events with an `Effect` spawn that prefab at their node from their time for its own duration.

**Effect lifetimes.** From the core's action holders (read only, `ActionHolder.running()` / `queued()`): an effect row that keeps a run (`Looping`, `LinkLifeToActionLife`) plays while it is listed, at most its `OverrideDuration`; a `Looping` one replays its timeline each time it ends (INFERRED from the flag); a delayed one-shot plays from the tick the queue says (`ActionDelay`); a one-shot the core starts and drops in the same call is not visible to the view, so `ElectroGiant_EV1_Play_Pulse_Effect` starts with the spawn its group runs beside it (`ElectroGiant_EV1_Spawn_Pulse_AEO`, `WorldObserver.areaEffectSpawned`; INFERRED). A one-shot lasts its prefab's duration (its timelines and spawned prefabs). Effects on a unit are not turned with it (INFERRED: the auras and glow planes are authored for the camera); bone attachments (`5012`) follow the unit's posed bone position, keeping their parents' turn and scale (INFERRED).

**Drawing.** An effect model (a model whose override material is not `uber_pbr`) is drawn into the unit's (or projectile's) image after its lit parts, depth-tested against them without writing depth, sorted by `Layer` then the material's `order`, premultiplied: an alpha-blended material (`blend` 5) writes `(rgb·a, a)`, an additive one (`blend` 6, every `*_add` material; INFERRED from the names) `(rgb·a, 0)`, so the composite over the arena adds it. A material asset with no model (`materialEffects`) or on a model's node sets `uber_pbr`'s glow terms on the named geometries (all of them when none is named). `MinDeviceCap` up to 2 is drawn (the high-end device, as `render_pipeline_settings` enables bloom from `DeviceCap` 2). An image wider than 2048 pixels is rendered at a lower resolution instead of being cut.

**Shaders (DATA, ported line for line; the variant choice INFERRED).**

- `vfx_mask` (defines `DISTORTION`, `DISSOLVE`, `COLOR_AS_ALPHA`; variant `i` has the mask `7 − i`, bit 0 `COLOR_AS_ALPHA`, 1 `DISSOLVE`, 2 `DISTORTION`, read off what each samples): colour `mainColor` (times `mainTex`, or `mainTex.r` as alpha with `color_as_alpha`), alpha times `clamp(pow(max(mask·selector, 0.001), pow)·mul)` and the dissolve's `smoothstep(h, 1−h, clamp(d + 1 − 2·amount))`; each UV is `uv·UV.xy + UV.zw + speed·time`. `DISSOLVE` follows `enableDissolve`, `DISTORTION` a `distortionTex`. Output not converted (display colours). `vfx_mask2` adds `maskTex2` mixing the dissolve out (`enableMask2Dissolve`).
- `vfx_fresnel_simple`: `mainColor + fnl_color · pow(1 − N·V, fnl_power) · fnl_int`, both decoded from sRGB and the sum encoded back by the shader's polynomials; alpha `fnl_color.w` (the vertex colour taken as 1).
- Billboards (`enableBillboard`, `vfx_mask.vert`'s `BILLBOARD` variants): the vertex is the node origin plus `B · L · p`, `B` the basis the shader builds from the inverse view (x the horizontal perpendicular to the view, y the horizontal towards the camera, z the camera's up). That the shader's `u_model` is the node's own turn and scale is INFERRED (it makes the glow planes, authored upright facing +y, face the camera).
- `uber_pbr` `FRESNEL` and `RIMLIGHT` (variant 100 against 118): Fresnel `F(|N·V|, fr_IOR) · fr_weight` mixes towards `fresnelColor · fr_colorMul` and adds to the alpha (clamped to 0.05..1); the rim adds `rimLightColor · rl_colorMul · pow(smoothstep(rl_lowTolerance, 1, (1 − N·V)(1 − max(N·dir, 0))), rl_pow) · rl_weight` masked by the team texture's red and the occlusion, `dir` = `rimLightDir` (view space) turned to world space by the inverse view. The colour multiply is in the shader when its `scroll_disable_uniform_opt` constant is set, else (INFERRED) before upload: the same result. A zero direction (the long shot's timeline sets `rimLightDir` to 0) leaves the facing term out (INFERRED: GLSL's `normalize(0)` is undefined). Off by default: in the battle data only the Bowler's long-shot boulder carries a rim material (`mat_rim_ingame`); no other battle model enables rim light or Fresnel outside these glows.

**Bloom: not drawn.** The data has it: `render_pipeline_settings` sets `BloomEnabled` for `DeviceCap` 2, `arn_default`'s camera `BloomThreshold` 1.129, and `bloom_downsample` (a 5-tap box with a soft-knee threshold on luminance), `bloom_upsample` (a tent) and `bloom_composite` (`source + bloom · u_params.x`) are in the shaders. It runs on the 3D stage's HDR target, over 1.13 in display units: here each model is its own 8-bit image, clamped at 1, so nothing reaches the threshold, and the composite's strength (`u_params.x`) and knee (`u_params.y`) are not in the data. A faithful port needs a float 3D stage; a glow guessed on the clamped images would not follow the data.

**Not drawn yet.** Particle emitters (`5004`, `MovieClipEmitterData`: 2D movie clips from `sc/vfx_autochess`, `sc/chr_electrogiant_evolution` …, spawn rate, bursts, lifetime curves): the Electro Giant's `vfx_electrogiant_evo_ball` is only particles, and the Electro Wizard's aura, the Giant's sparks and the soundwave sparks have particle parts. The eye material overrides of the barrel projectiles (`mat_heroes_glowy_eyes_pupils`, a lit override) keep the model's own material.

**Checked against.** Our own renders only (no real-game capture of these effects was found; the wiki pages refused automated reads). The Hero Bowler's ability duration in a guide (7.3 s after its June 2026 change) matches `BowlerHero_3DFlashEffect`'s `OverrideDuration` 7300.

## What `EntityView` needs in addition

| Field | Source in core | For |
|---|---|---|
| `attackTimerMs`, effective `hitSpeedMs`, `attackSequenceIndex` | `TargetingState.getAttackTimerMs()`, `TargetingConfig.hitSpeed()`, `getAttackSequenceIndex()` | lining attack clips up with hits (§1.4), `attack2`/`attack3` |
| height, dash time, charge progress, dash wind-up, landing hold (done: `BattleAdapter.unitMotion`, read by the renderer's observer) | `GridEntity.getZTotal()`, `MovementState`, `TargetingState.getDashWindupMs()`, `getBlockCountdownMs()` | lift, `jump1`/`charge1` timing (§1.6) |
| projectile `z`, `startX/Y`, `aimZ` | `ProjectileEntity.getZ()`, `getStartX/Y/Z()` | arc height, frame choice, shadow (§6) |
| king activation phase + elapsed ms | `ActivationEvent` in `TowerEntity` | the King tower clip (§5.2) |
| per-tick events: hit landed (attacker → target), death, projectile launch and impact, spawn | battle events, or the renderer diffs frames | effects (§7), damage filter (§8) |

## Open questions and guesses

1. **What attack timing does when time is short.** Frame matching itself is INFERRED. What happens when the wind-up has less time than `action_frame` (the first hit, or `af > HitSpeed`) is a GUESS: skip versus speed up. Frame-step a 30 fps capture of Knight, Giant and Magic Archer hitting a tower to settle it.
2. **The height factor 0.01742 du per game unit.** It is a camera model checked against one composite (the Princess on her tower). Check it against a flying unit and its shadow in a screenshot.
3. **Ground angle versus screen angle** for the direction index (§2). The two differ only near the bucket edges.
4. **Projectile frame mapping** (pitch sweep versus progress) and the rotation source (ground track versus screen motion including z).
5. **Shadow look:** the skew sign, alpha and blur, plus the per-arena `ShadowR/G/B/A` and `ShadowOffsetX/Y` from the location row.
6. **Base versus Ground layer order**, and how the deploy filter's start lines up with `DeployTime`.
7. **Run playback rate under slows and rage.** Fixed fps is the safe default.
8. **18 and 5 direction units.** The 18-direction step of 180/17 matched the Cannon Cart visually. The 5-direction Mortar clips are almost identical, so 45° is unverified. Sparky's 18 frames read ambiguously.
9. **`RotateAngleSpeed` and `TurretMovement` units** are not established: deg/s versus a turret step.
10. **King ↔ `chr_king.sc` link.** No table names it; it is assumed hard-coded in the client.
11. **Particle emitters** are about 70% of effect components. Without an emitter, most hit, death and trail visuals are missing. The SWF-only parts (stuck arrows, spell rings, zap bolts) are the cheap wins.
12. **Not done:** the Training Camp decoration pass was not composited; only its ground pads were measured. The ruin exports were not rendered. Hero, evo and `sc3d`-only art is extracted (§10); only the Hero Tombstone's is drawn in 3D (§10.1).
13. **Lava Pups' glow.** Each `chr_lava_pups.sc` clip draws over the body a glow (shape 1, a 24 du soft disc scaled about 2.3×, covering the whole pup) with blend 8 (add) and the colour transform `mul (18, 18, 18)`, `alpha 187`, `add (255, 255, 151)`: a pale-yellow additive wash at 0.73 that turns the orange-brown body yellow-orange. The transform's byte order is DATA (identity is `255,255,255,a,0,0,0` 343 times in `effects.sc`, and `filter_cold` is a blue add), and the drawer applies it as Flash does (`rgb·mul + add·a`, then added). So the bright pups are drawn as the data says; whether the game's shader treats an additive child's added colour differently is unverified.
14. **Dashes and jumps (§1.6).** Check against a capture: whether the Mega Knight is translucent in the air (its `DashFilter`'s alpha) or the filter belongs to the wind-up; where its crouch falls; the river jump's height (4000 game units lift a hog 70 du); and the dash clips' turn order on the Golden Knight.
15. **Abilities and warps (§7).** Whether `DeployedEffect` belongs to the cast (as drawn) and `DeployedClip` to the HUD button, and whether the Mega Minion hero's dash is the beam drawn from it to its destination: compare with a capture of the hero's teleport. The rider's offset inside `child1` is taken as none (§3.5).
