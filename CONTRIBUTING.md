# Contributing

## Never commit game assets

No file extracted from the game, no APK, and no screenshot, frame, video or sound made from the game's art, in commits, issues or pull requests (README, "No game assets here"). Before a commit that adds files, check what goes in:

```bash
git ls-files | sed -E 's/.*\.([^./]+)$/\1/;t;s/.*/(none)/' | sort | uniq -c   # every file type
git ls-files -z | xargs -0 du -k | sort -rn | head                            # the largest files
```

Expected types: `java`, `kts`, `py`, `md`, `html`, `fbs`, `json` (only the two replay fixtures in `realistic/src/test/resources/replays`), `toml`, `lock`, `sed`, `sh`, `txt` and the Gradle wrapper (`gradle-wrapper.jar`, `gradlew`). Nothing larger than about 200 KB except `docs/realistic-rendering.md` and `tools/game-art/uv.lock`.

## Build, format, test

```bash
./gradlew build -Pcrforge.assetSource=cdn          # compile, spotlessCheck, tests
./gradlew spotlessApply                            # google-java-format 1.25.2, as crforge
(cd tools/game-art && uv run pytest -q)            # the extractor's tests (most skip without APKs)
```

Checks that need the art cache and a display, never part of `build`:

```bash
# the window: plays a card against the bot, writes a frame, closes
./gradlew :realistic:run -Pcrforge.assetSource=cdn -Pcrforge.windowSmoke=/tmp/window.png
# the phone server, driven by a headless phone browser (start the server first, with a port and token)
./gradlew :realistic:run -Pcrforge.assetSource=cdn --args="--serve --port 8766 --token localtest1"
uv run --with playwright python tools/phone-render/e2e.py "http://127.0.0.1:8766/?t=localtest1" --seconds 25
# the draw-list stream over a bot battle, and its pixel parity in the page's own player
./gradlew :realistic:phoneRenderBench -Pcrforge.assetSource=cdn --args="build/phone-render-bench 1500,3000"
uv run --with playwright --with pillow --with numpy python tools/phone-render/parity.py build/phone-render-bench
```

Write the outputs outside the repository or under `build/` (ignored), and do not share them.

## Porting a patch from the crforge fork

This code was moved from the fork Ramora0/crforge (branch `realistic-render`, `7f6d6bcf`), where it lived in crforge's desktop module. Paths and packages map mechanically; subpackages and class names are unchanged:

| crforge fork | here |
|---|---|
| `desktop/src/{main,test}/java/org/crforge/desktop/render/art/` (incl. `model/`) | `realistic/src/{main,test}/java/org/crforge/realistic/render/art/` |
| package `org.crforge.desktop.render.art[.model]` | `org.crforge.realistic.render.art[.model]` |
| `desktop/src/{main,test}/java/org/crforge/desktop/server/` | `realistic/src/{main,test}/java/org/crforge/realistic/server/` |
| package `org.crforge.desktop.server` | `org.crforge.realistic.server` |
| `desktop/src/main/resources/org/crforge/desktop/server/index.html` | `realistic/src/main/resources/org/crforge/realistic/server/index.html` |
| `org.crforge.desktop.battle.{BattleSession, BattleAdapter, BattleFrame, EntityView, BattleDecks, LandingTiles, UnitStatus, UnitRole, ActionMeter, AreaHitLog}` | `org.crforge.realistic.bridge.*` (same names) |
| `org.crforge.desktop.render.{ViewOrientation, RenderConstants}` | `org.crforge.realistic.bridge.*` |
| `org.crforge.desktop.replay.{ReplayFile, ReplayPlayer}` | `org.crforge.realistic.bridge.*` |
| `org.crforge.desktop.DataSelection` | `org.crforge.realistic.bridge.DataSelection` |
| `tools/game-art/`, `tools/phone-render/`, `docs/realistic-rendering.md`, `docs/phone-server.md` | the same paths |
| `DesktopLauncher` (`--record`, `--serve`, `--sc3d` handling) | `org.crforge.realistic.RealisticLauncher` (by hand) |

The mapping is in two sed scripts (extended regular expressions, BSD or GNU sed): `tools/port/paths.sed` (file paths) and `tools/port/packages.sed` (package names and `package` lines).

**A range of commits**, e.g. the fork's `fix/polish3d` on top of `7f6d6bcf`:

```bash
tools/port/port-range.sh /path/to/crforge 7f6d6bcf..fix/polish3d --check   # does it apply?
tools/port/port-range.sh /path/to/crforge 7f6d6bcf..fix/polish3d           # git am: one commit each, authors kept
./gradlew spotlessApply build -Pcrforge.assetSource=cdn
```

It takes only the files this repository holds and lists the others it leaves out (changes to `DesktopLauncher`, `BattleRenderer` and the other desktop files are redone by hand here, if they matter).

**One patch file**:

```bash
git -C /path/to/crforge format-patch --stdout -1 <commit> -- desktop/src tools docs > in.patch
tools/port/port-patch.sh < in.patch > out.patch
git am -3 out.patch          # or git apply out.patch
```

**By hand with sed**: `sed -E -f tools/port/paths.sed -f tools/port/packages.sed`. For example, `sed -E -i '' -f tools/port/packages.sed File.java` (BSD sed) renames the packages in a copied file.

After a port, `./gradlew spotlessApply`: the package move changes how a few imports sort (the bridge classes now share one package), so a hunk touching an import block may need that one-line fix by hand. Changes crforge's desktop classes got for the realistic view (a new field in `BattleFrame`, a helper in `BattleAdapter`) port the same way into `bridge/`.
