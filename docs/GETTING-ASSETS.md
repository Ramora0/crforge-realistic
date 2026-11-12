# Getting the assets

This repository draws battles with Clash Royale's own art, animations, 3D models, fonts and sounds. Those belong to Supercell, so **none of them are in this repository and none may ever be published here**. Each user extracts them from **their own** installation of the game into a private cache on their own computer, with the extractor in `tools/game-art` (`crforge-art`). This page walks through it from scratch.

You need two separate things, and only the second one comes from your phone:

| What | Where it comes from | Where it goes |
|---|---|---|
| the game tables the simulator runs on (card stats, ...) | crforge's build fetches them from the game's asset CDN when you pass `-Pcrforge.assetSource=cdn` | `~/.crforge/tables/<data version>/` |
| the art, 3D models, fonts and sounds | **your** phone's installation, through `crforge-art` | `~/.crforge/art/<content sha>/` |

Contents:

1. [What you need](#1-what-you-need)
2. [Set up adb and USB debugging](#2-set-up-adb-and-usb-debugging)
3. [Get the APKs off your phone](#3-get-the-apks-off-your-phone)
4. [Run the extraction](#4-run-the-extraction)
5. [Check the cache and make a first video](#5-check-the-cache-and-make-a-first-video)
6. [Game updates and versions](#6-game-updates-and-versions)
7. [Troubleshooting](#7-troubleshooting)
8. [Legal and ethics](#8-legal-and-ethics)

## 1. What you need

- **An Android phone with Clash Royale installed from Google Play.** Update the game to the latest version in Play, then **open it once** and let it finish its download screen, so the game and its asset packs are complete. You do not need to play.
  - Only the global Google Play edition (package `com.supercell.clashroyale`) is supported.
  - **iPhone / iOS: not supported.** An App Store app bundle is encrypted, and the extractor reads Android packages only. If you only have an iPhone, you need an Android device (a friend's phone where you install the game yourself counts; a borrowed APK file does not).
  - **Emulators: not needed and not supported.** Clash Royale refuses to run on stock Android emulators, and you do not need it to run: the extractor only reads the APK files, which come straight off a phone.
- **A USB cable** that carries data (some charging cables do not).
- **adb** (Android's platform-tools), section 2.
- **[uv](https://docs.astral.sh/uv/)** for the Python extractor. uv fetches a suitable Python itself (the extractor needs Python 3.11 or newer). Install: `curl -LsSf https://astral.sh/uv/install.sh | sh` (macOS, Linux) or `powershell -ExecutionPolicy ByPass -c "irm https://astral.sh/uv/install.ps1 | iex"` (Windows).
- **Java 21** for the renderer (macOS: `brew install openjdk@21`; Linux: your distribution's `openjdk-21-jdk`; Windows: e.g. Eclipse Temurin 21). Gradle can also download a JDK 21 for the build itself.
- **ffmpeg** on the `PATH` to record videos and mix their sound (`brew install ffmpeg`, `apt install ffmpeg`, `winget install ffmpeg`).
- **libvorbis**, only for the sounds (`--audio`): `brew install libvorbis` (macOS), `apt install libvorbis0a` (Debian/Ubuntu).
- **Disk**: about 1.5 GB for the cache, plus the APKs while you extract (several hundred MB; you can delete them afterwards).

The extractor and renderer are developed and tested on macOS (Apple Silicon). The steps below give the Linux and Windows equivalents; they should work there, but have had less testing.

## 2. Set up adb and USB debugging

### Install adb

- **macOS**: `brew install --cask android-platform-tools`
- **Linux** (Debian/Ubuntu): `sudo apt install adb` (Fedora: `sudo dnf install android-tools`). If the phone is not seen, your distribution may need udev rules for Android devices: `sudo apt install android-sdk-platform-tools-common`.
- **Windows**: `winget install Google.PlatformTools`, or download "SDK Platform-Tools for Windows" from [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools), unzip it, and add the folder to your `PATH`. Some phones need their maker's USB driver on Windows (for Pixels, the "Google USB Driver").

Check: `adb version` prints a version.

### Turn on USB debugging on the phone

1. **Settings > About phone**, tap **Build number** seven times. The phone says you are now a developer. (On some phones it is under **Software information**.)
2. **Settings > System > Developer options** (or **Settings > Developer options**), turn on **USB debugging**.
3. Connect the phone with the cable. If it asks what the USB connection is for, choose **File transfer**.
4. On your computer, run `adb devices`. The phone shows a dialog **"Allow USB debugging?"** with your computer's key fingerprint: tick **Always allow from this computer** and tap **Allow**. This is the "authorize this computer" step.
5. Run `adb devices` again. You should see one line ending in `device`:

   ```
   List of devices attached
   R58N12ABCDE     device
   ```

   `unauthorized` means step 4 is not done yet; see [Troubleshooting](#7-troubleshooting). With several devices attached, note the serial (the first column) to pass to the commands below.

You can turn USB debugging off again when you are done.

## 3. Get the APKs off your phone

Android installs Clash Royale as several APK files ("splits"). **Every split must be given to the extractor**, because the game's files are spread across them: the extractor reads the `assets/` folder of every APK you pass and merges them into one index. Most of the art (the `.sc` files and their textures, the `sc3d` models) is in the install-time asset pack, `split_install_time_asset_pack.apk`; `base.apk` holds the rest of the game's files; the `split_config.*` APKs hold native code, language and screen-density resources, carry little or nothing the extractor needs, and are harmless to pass. If a split is missing, the extraction fails or comes out incomplete.

### Easy path: let the extractor do it

```bash
cd tools/game-art
uv sync                                  # once: installs the extractor's dependencies
uv run crforge-art extract --adb         # or: --adb SERIAL with several devices attached
```

`--adb` runs `adb shell pm path com.supercell.clashroyale`, pulls every split into a temporary folder, extracts, and deletes the temporary copies. Go on with section 4 (you can add its options, e.g. `--audio`, to this command). Each command that takes `--adb` pulls the APKs again; to pull them once, use the manual path.

### By hand

1. List the splits:

   ```bash
   adb shell pm path com.supercell.clashroyale
   ```

   ```
   package:/data/app/~~Xy...==/com.supercell.clashroyale-Ab...==/base.apk
   package:/data/app/~~Xy...==/com.supercell.clashroyale-Ab...==/split_config.arm64_v8a.apk
   package:/data/app/~~Xy...==/com.supercell.clashroyale-Ab...==/split_config.en.apk
   package:/data/app/~~Xy...==/com.supercell.clashroyale-Ab...==/split_config.xhdpi.apk
   package:/data/app/~~Xy...==/com.supercell.clashroyale-Ab...==/split_install_time_asset_pack.apk
   ```

   (The config split names depend on your phone's CPU, language and screen.)

2. Pull each one into a folder **outside this repository**:

   ```bash
   # macOS / Linux
   mkdir -p ~/cr-apks
   for p in $(adb shell pm path com.supercell.clashroyale | sed 's/^package://' | tr -d '\r'); do
     adb pull "$p" ~/cr-apks/
   done
   ```

   ```powershell
   # Windows (PowerShell)
   mkdir $HOME\cr-apks
   adb shell pm path com.supercell.clashroyale | ForEach-Object { adb pull ($_ -replace '^package:','').Trim() "$HOME\cr-apks\" }
   ```

3. Check that you have `base.apk` and `split_install_time_asset_pack.apk` (and the config splits).

4. Extract from them (section 4), passing **all** of them:

   ```bash
   uv run crforge-art extract --apk ~/cr-apks/*.apk
   ```

### `.xapk` / `.apkm` bundles

The extractor also accepts one bundle file (`.xapk`, `.apkm`, `.apks`: a zip of the split APKs): `uv run crforge-art extract --apk clash.xapk`. Make the bundle **from your own installation**, for example with an app on your phone that exports installed apps as a split-APK bundle. Do not download the game's APKs or bundles from third-party sites: they are someone else's copy, may be modified, and are not yours to use.

## 4. Run the extraction

All commands run in `tools/game-art` (`cd tools/game-art`, then `uv sync` once). Replace `--adb` with `--apk ~/cr-apks/*.apk` if you pulled the APKs by hand.

| Step | Command | Time (10-core laptop) | Disk |
|---|---|---|---|
| 2D art, tables, fonts, arenas | `uv run crforge-art extract --adb` | about 20 s, plus the pull | about 1.3 GB |
| the battle sounds | add `--audio` to the line above (or run it again with `--audio`: finished files are skipped) | longer than the art: the sound banks are decoded (minutes at most) | about 50 MB |
| the 3D models | `uv run crforge-art sc3d --adb --scope battle` | about 5 s, plus the pull | about 66 MB |

So, for everything in one go:

```bash
uv run crforge-art extract --adb --audio
uv run crforge-art sc3d --adb --scope battle
```

What each part is for:

- **`extract`** (required): every `.sc` file a battle draws (troops, buildings, towers, arenas, effects, the in-battle UI) as scene JSON plus atlas PNGs, the client tables and effects tables the renderer reads, the arena layouts and tilemaps, and the HUD font. `--scope battle` is the default; `--scope all` takes every `.sc` file in the game (much larger, not needed).
- **`--audio`** (optional): the battle sound banks as Ogg files plus an index. The phone server plays them in the browser, and `crforge-art audio` mixes them into a recorded video. Without them videos are silent and the phone page plays no sounds.
- **`sc3d`** (optional, recommended): the units the game only has as 3D models (hero forms such as the Hero Tombstone, Ronin, Minion Giant, several evolutions) as glTF `.glb` files. Without them those units are drawn with a 2D stand-in. `--scope battle` takes the models the battle tables name; `--scope all` every model (about 460 MB).

### Where it writes

```
~/.crforge/art/
  current.json            {"content_sha": "...", "version": "..."}: the newest extraction
  <content sha>/          one folder per game content version
    manifest.json  sc/  client/  locations/  tilemaps/  fonts/  audio/  sc3d/
```

The content sha is the game's own content version (`assets/fingerprint.json`). The renderer reads the folder `current.json` names.

To keep the cache elsewhere:

- extractor: `--out DIR` on `extract`, `sc3d` and `check` (the cache root, holding `current.json`);
- renderer: `-Pcrforge.art=DIR` on a `./gradlew :realistic:run` command (or `-Dcrforge.art=DIR` when you run the Java program yourself). `DIR` is a cache root or one `<content sha>` folder;
- the 3D models alone: `--sc3d DIR` (a folder holding `index.json`), or `--sc3d off` to draw without them.

Never point the cache into this repository's folder.

## 5. Check the cache and make a first video

**Check the cache** (reads the manifest and the files it lists, loads no art):

```bash
uv run crforge-art check
```

```
  ok    cache /Users/you/.crforge/art/ccd3a776...
  ok    game version 16.635.1, content sha ccd3a776..., scope battle
  ok    356 scenes and 396 atlases (1245 MB)
  ok    client tables, effects tables, locations and tilemaps
  ok    HUD font
  ok    sounds: 1473 events in 207 banks (47 MB)
  ok    3D models: 56 rows, 47 models (69 MB)

the cache is complete (0 warning(s))
```

A `FAIL` line says what is missing and what to run; `warn` lines are the optional parts (sounds, 3D models). The exit code is 0 when the cache is usable.

**Make a first still and video** from the repository's root (the first run builds the game tables, which needs the network once):

```bash
./gradlew :realistic:run -Pcrforge.assetSource=cdn \
    --args="--replay crforge/examples/replays/ladder.json --record first.png --from 60"
./gradlew :realistic:run -Pcrforge.assetSource=cdn \
    --args="--replay crforge/examples/replays/ladder.json --record first.mp4 --seconds 20"
cd tools/game-art && uv run crforge-art audio ../../first.mp4    # with --audio: first_audio.mp4
```

A good result: a phone-shaped 1080x2340 frame. The arena fills the middle: green grass on a tile grid, a river with two bridges across the centre, the blue side's king and princess towers at the bottom and red's at the top, with health numbers over the towers. Troops are animated sprites (or 3D models for the hero forms), walking, attacking and casting effects. The HUD frames it: the time left and a player bar at the top, the four hand cards, the next card and the pink elixir bar at the bottom. The example replay is about 2.5 minutes; the full video ends with the crowns and the end screen.

Signs of a problem: the log says `No game art cache at ...` (the cache is not where the renderer looks: section 4), the recorder stops with `no game art cache`, the frame is dark or only the HUD shows (an incomplete extraction: run `check`), hero forms are flat 2D images (no `sc3d` extraction, or `--sc3d off`).

Recordings are made from Supercell's art: they are yours to watch, not to commit (the `.gitignore` refuses `.png` and `.mp4` files). See section 8 before sharing one.

## 6. Game updates and versions

**After a game update**, update the game in Play, open it once, and extract again:

```bash
uv run crforge-art extract --adb --audio
uv run crforge-art sc3d --adb --scope battle
uv run crforge-art check
```

A new game version has a new content sha, so it goes into a new folder and `current.json` moves to it; the old folder stays and can be deleted by hand. Within the same content version the extraction is incremental: files already extracted are skipped. Pass `--force` (and `--force-audio` for the sounds) to redo them, for example after updating this repository's extractor, whose notes may say an older extraction should be redone.

**Two versions are in play**, and they do not have to match:

- the **art cache** is the game version on your phone (`current.json`'s `version`, e.g. 16.635.1);
- the **game tables** are the data version crforge's build makes from the CDN (crforge's `crforge-data.lock`, `version=`, e.g. 16.635.8; `-Pcrforge.dataVersion=<v>` picks another built version).

The renderer looks the art up by the tables' row names, so a near version works. A card the game added after your extraction has no art in your cache (it is drawn with a fallback or not at all): update the game and extract again. Art newer than the tables is harmless. A replay is played on the data version it was recorded on when that version's tables are built (crforge builds `crforge-data.lock`'s `compatible=` versions beside the main one), and refused otherwise. In crforge's own debug visualizer the **V** key cycles through the built data versions; this repository has no V key: choose the version with `-Pcrforge.dataVersion=<v>`.

## 7. Troubleshooting

| Symptom | What to do |
|---|---|
| `adb devices` lists nothing | Use a data cable, choose File transfer on the phone, try another USB port. Linux: udev rules (section 2). Windows: the phone maker's USB driver. Then `adb kill-server && adb devices`. |
| `adb devices` says `unauthorized` | Unlock the phone and accept the "Allow USB debugging?" dialog. No dialog: Developer options > **Revoke USB debugging authorizations**, unplug, `adb kill-server`, plug in again and accept. |
| `more than one device/emulator` | Pass the serial: `crforge-art extract --adb SERIAL` (serials from `adb devices`). |
| `com.supercell.clashroyale is not installed on the device` | Install Clash Royale from Google Play on that phone. Other editions (other package names) are not supported. |
| `assets/fingerprint.json not found in the source` | Not the game's APKs, or `base.apk` was left out: pass every split. |
| Many `referenced file ... is not in the game files` warnings, or `check` fails on scenes | The asset pack split is missing: make sure `split_install_time_asset_pack.apk` is among the files (or pull again with `--adb`). One or two such warnings are normal: some arenas download later inside the game and are not in the APKs. |
| `--audio` fails to load libvorbis | Install libvorbis (section 1). |
| `crforge-art check` reports `FAIL` | Do what the line says; usually `extract` again, with `--force` if files stay missing. |
| The renderer logs `No game art cache at ...` | The cache is elsewhere: `-Pcrforge.art=DIR` (section 4). |
| After a game update, extraction lists `FAILED` files or the renderer draws a unit wrongly | Supercell changes its file formats from time to time. Keep using the previous folder (`-Pcrforge.art=~/.crforge/art/<old sha>`) and open an issue naming the game version and the failing files. Do not attach the files themselves. |
| 3D units look wrong after updating this repository | Redo the models: `crforge-art sc3d --adb --force`. |
| `no asset source to build the game tables from` | Add `-Pcrforge.assetSource=cdn` to the `./gradlew` command (that is for the game tables, not the art). |

## 8. Legal and ethics

- Clash Royale and all of its art, animations, models, sounds, fonts and data are Supercell's property. This project is an unofficial fan project, **not affiliated with, endorsed, sponsored or approved by Supercell**.
- Extract only from an installation **you** own, for your **personal, non-commercial** use, and follow [Supercell's Fan Content Policy](https://supercell.com/en/fan-content-policy/) and [Terms of Service](https://supercell.com/en/terms-of-service/).
- **Never commit, upload or share the extracted files** (the cache, the APKs, single images, models or sounds), here or anywhere else. That includes screenshots, frames and recordings in this repository, its issues and pull requests. This repository's `.gitignore` excludes the usual asset locations and file types (`*.apk`, `*.xapk`, `*.sc`, `*.png`, `*.glb`, `*.ogg`, `*.mp4`, ...) as a safeguard, not as permission; keep the cache outside the repository (the default `~/.crforge/art` is).
- If you share a video you made, it must be fan content the policy allows (for example non-commercial, clearly unofficial). When in doubt, do not share it.
- The phone server streams Supercell's art: use it with your own devices and friends, keep its access token private and never leave a public tunnel open (docs/phone-server.md).
