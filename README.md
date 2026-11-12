# crforge-realistic

The game's own look for [crforge](https://github.com/voonhous/crforge), the open Clash Royale battle simulator: a renderer that draws crforge's battles with Clash Royale's art, animations, 3D models, HUD and sounds, extracted from **your own** installation.

- **Record** a replay to a phone-framed video (1080x2340, 60 fps, interpolated between ticks), with the game's sounds mixed in.
- **Play from your phone's browser**: the battle and the renderer run on your computer, the phone draws the picture and sends your taps back, against a built-in bot or a friend ([docs/phone-server.md](docs/phone-server.md)).
- **3D units**: the hero forms, evolutions and other units the game only has as 3D models, rigged and animated from the game's own state machines.
- **A window** to watch a replay or play the bot on your computer.

It depends only on crforge's battle core (the `core` module, pinned to one commit as a git submodule), so changes to crforge's desktop visualizer or anything else never affect it.

## No game assets here, and no screenshots

Clash Royale's art, models, sounds and fonts belong to Supercell. **None of them are in this repository, and none may be added**: no extracted files, no APKs, and also no screenshots, frames or videos, since every one of them is Supercell's art. That is why this README has no pictures. Each user extracts the assets from their own installation into a private cache (`~/.crforge/art`) with the extractor in `tools/game-art`, following **[docs/GETTING-ASSETS.md](docs/GETTING-ASSETS.md)** step by step. The `.gitignore` refuses the usual asset files as a safeguard.

This is an unofficial fan project, not affiliated with, endorsed or sponsored by Supercell. Use it for personal, non-commercial purposes under [Supercell's Fan Content Policy](https://supercell.com/en/fan-content-policy/).

## Quickstart

You need git, Java 21 (`JAVA_HOME` pointing at it, or let Gradle download one), [uv](https://docs.astral.sh/uv/), ffmpeg, and an Android phone with Clash Royale for the assets.

```bash
# 1. clone with the crforge submodule
git clone --recursive https://github.com/Ramora0/crforge-realistic.git
cd crforge-realistic
# (cloned without --recursive? run: git submodule update --init)

# 2. extract the assets from your own phone: read docs/GETTING-ASSETS.md first
cd tools/game-art
uv run crforge-art extract --adb --audio
uv run crforge-art sc3d --adb --scope battle
uv run crforge-art check
cd ../..

# 3. build and test (the first run builds crforge's game tables from the game's CDN)
./gradlew build -Pcrforge.assetSource=cdn

# 4. record a video of crforge's example replay, then mix in the sounds
./gradlew :realistic:run -Pcrforge.assetSource=cdn \
    --args="--replay crforge/examples/replays/ladder.json --record battle.mp4"
(cd tools/game-art && uv run crforge-art audio ../../battle.mp4)    # writes battle_audio.mp4

# 5. play from your phone: open the printed URL (same Wi-Fi: add --lan)
./gradlew :realistic:run -Pcrforge.assetSource=cdn --args="--serve"
```

`-Pcrforge.assetSource=cdn` is crforge's own setting: where its build fetches the game tables from (put `crforge.assetSource=cdn` in `~/.gradle/gradle.properties` to stop typing it). On Windows use `gradlew.bat`. Paths in `--args` are relative to the repository's root.

## Commands

All modes are `./gradlew :realistic:run -Pcrforge.assetSource=cdn --args="..."` (`--args="--help"` lists them):

| Mode | Arguments |
|---|---|
| record a replay to a video, or a `.png` still | `--replay FILE --record OUT.mp4 [--size 1080x2340] [--fps 60] [--from SECONDS] [--seconds N] [--bottom 0\|1\|blue\|red] [--pov \| --player-view] [--location PvP_royal]` |
| serve to a phone | `--serve [--port 8765] [--lan] [--token X] [--size 720x1560] [--fps 30] [--quality 70] [--location PvP_royal] [--audio-cache DIR] [--render list\|jpeg]` |
| a window: a replay, or (without `--replay`) a live battle against the bot | `[--replay FILE] [--size 540x1170] [--bottom 0\|1\|blue\|red] [--pov] [--location ROW] [--deck ID] [--opponent-deck ID]`; keys SPACE pause, U player's view, F flip, +/- speed, . step, R restart, ESC quit; click a card, then the arena |
| every mode | `[--sc3d DIR\|off] [--sc3d-rows PREFIXES\|*]`; `-Pcrforge.art=DIR` for an art cache elsewhere; `-Pcrforge.dataVersion=<v>` for another built data version |

More in [docs/realistic-rendering.md](docs/realistic-rendering.md) (how every unit, effect and HUD element is drawn, with the evidence) and [docs/phone-server.md](docs/phone-server.md).

## Layout

```
crforge/              git submodule: voonhous/crforge, pinned (the battle core and its game-table build)
realistic/            the Gradle module
  src/main/java/org/crforge/realistic/
    RealisticLauncher, ReplayOpener    the launcher and its modes
    render/art/ (and model/)           the realistic renderer, effects, HUD, recorder, 3D models
    server/                            the phone server, its bot and its draw-list stream
    window/                            the desktop window
    bridge/                            the crforge desktop classes this code is built on (below)
  src/main/resources/.../server/index.html   the phone page
  src/test/java/...                    the tests
tools/game-art/       crforge-art: the extractor (Python, uv)
tools/phone-render/   the phone page's headless checks (e2e.py, parity.py, badlink.py)
tools/port/           scripts that port patches from the crforge fork (CONTRIBUTING.md)
docs/                 GETTING-ASSETS.md, realistic-rendering.md (incl. §10, the sc3d models), phone-server.md
```

## How the build uses crforge

`settings.gradle.kts` includes `crforge/` as a [composite build](https://docs.gradle.org/current/userguide/composite_builds.html): crforge's own build runs unchanged, with its own conventions, and its `:core` project stands in for the dependency `org.crforge:core` (and its test fixtures, for the tests). Nothing else of crforge is built. Tasks that need the game tables (`run`, `test`) depend on crforge's `:tables:gameTables`, which builds them into `~/.crforge/tables/<data version>` once, with crforge's settings: `-Pcrforge.assetSource` (required), `-Pcrforge.dataVersion` (default: `crforge/crforge-data.lock`) and `-Pcrforge.cache` (default `~/.crforge`). Gradle passes `-P` properties to the included build as well.

## Relationship to crforge

The code was written in the fork [Ramora0/crforge](https://github.com/Ramora0/crforge), branch `realistic-render`, inside crforge's desktop module, and moved here at commit `7f6d6bcf` (on top of voonhous/crforge `15f9aaac`, the commit the submodule is pinned to). crforge's owner prefers not to carry a visual copy of the game in crforge, and this repository should not have to follow crforge's desktop changes, so:

- **crforge is used unmodified**, only its `core` module, through the submodule. crforge's own repository is not changed.
- **Copied from crforge's desktop module** into `org.crforge.realistic.bridge` (Apache-2.0, license headers kept, credited in [NOTICE](NOTICE)), because the renderer reads the battle through them: `BattleSession` (742 lines), `BattleAdapter` (550), `ReplayFile` (438), `ReplayPlayer` (344), `ViewOrientation` (253), `RenderConstants` (242), `LandingTiles` (237), `DataSelection` (221), `EntityView` (164), `BattleDecks` (110), `BattleFrame` (108), `AreaHitLog` (102), `UnitRole` (87), `UnitStatus` (69), `ActionMeter` (36): about 3,700 lines, with the fork's additions to them. Changes: the package; `BattleSession` without crforge's model opponent (which would pull in crforge's `env` module and ONNX Runtime); `ReplayFile` without crforge's `ViewState`. Their crforge tests came along.
- **Not carried over**: the fork's hooks in crforge's desktop (the `--realistic`, `--record`, `--serve` flags in `DesktopLauncher`, the X/U keys in `BattleRenderer`, `ViewState`, `WorkspaceAction`, `BattleWorkspace`, the debug and replay screens). `RealisticLauncher` and `RealisticWindow` replace them; the debug visualizer's overlays and inspector stay crforge's. Crawl outputs (`.jsonl` replay archives) and the V data-version key are crforge desktop features not carried over.

### Bumping the crforge submodule

```bash
git -C crforge fetch origin
git -C crforge checkout <new commit>          # a commit of voonhous/crforge
./gradlew build -Pcrforge.assetSource=cdn     # compiles the bridge against the new core, runs the tests
```

If the core's API changed, the bridge classes may need the same edits crforge made to their originals: `git -C crforge diff <old> <new> -- desktop/src/main/java/org/crforge/desktop/battle desktop/src/main/java/org/crforge/desktop/replay` shows them, and `tools/port/port-range.sh crforge <old>..<new> --check` tries to apply them here (CONTRIBUTING.md). Compare `gradle/libs.versions.toml` with `crforge/gradle/libs.versions.toml` (Jackson especially), record a still of the example replay before and after to see what changed, then `git add crforge` and commit. The data version comes with the submodule (`crforge/crforge-data.lock`), so a bump may build new tables on its first run.

## License

[Apache License 2.0](LICENSE), as crforge. See [NOTICE](NOTICE) for what comes from crforge and for the game's trademarks. The license covers this repository's code and documents only; it grants nothing over Supercell's game or its assets.
