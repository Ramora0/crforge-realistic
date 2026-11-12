"""Checks that an art cache is complete enough for the realistic renderer (``crforge-art check``).

Reads ``<root>/current.json`` (or a content folder given directly) and checks, without loading any
art:

* required: the manifest, every scene JSON and atlas PNG it lists, the decoded tables the renderer
  reads (client tables, the effects tables, locations, tilemaps) and the battle scope's UI files;
* optional (a warning each): the HUD font, the sounds (``extract --audio``) and the 3D models
  (``sc3d``) with every ``.glb`` their index names.

Prints one line per check and a summary; the exit code is 0 when every required check passes.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

# files the renderer cannot draw a battle without (scene stems, as manifest.json names them)
REQUIRED_SCENES = ("ui", "ui_ability_button", "chr_knight", "building_tower")
# decoded tables, relative to the content folder
REQUIRED_TABLES = (
    "client/csv_logic/locations.csv",
    "client/csv_logic/decos.csv",
    "client/csv_client/health_bars.csv",
    "client/csv_client/client_globals.csv",
    "client/legacy_vfx/effects.json",
    "client/legacy_vfx/emitters.json",
)
REQUIRED_DIRS = ("locations", "tilemaps")
FONT = "fonts/supercell-magic_0.ttf"


@dataclass
class Report:
    folder: Path | None = None
    lines: list[str] = field(default_factory=list)
    problems: int = 0
    warnings: int = 0

    def ok(self, msg: str) -> None:
        self.lines.append(f"  ok    {msg}")

    def warn(self, msg: str) -> None:
        self.warnings += 1
        self.lines.append(f"  warn  {msg}")

    def fail(self, msg: str) -> None:
        self.problems += 1
        self.lines.append(f"  FAIL  {msg}")


def content_folder(root: Path) -> Path | None:
    """The content folder: ``root`` itself when it holds ``sc/``, else current.json's."""
    root = root.expanduser()
    if (root / "sc").is_dir():
        return root
    current = root / "current.json"
    if current.is_file():
        try:
            sha = json.loads(current.read_text())["content_sha"]
        except (ValueError, KeyError):
            return None
        folder = root / sha
        if (folder / "sc").is_dir():
            return folder
    return None


def _size(path: Path) -> int:
    return sum(p.stat().st_size for p in path.rglob("*") if p.is_file()) if path.exists() else 0


def check(root: Path) -> Report:
    r = Report()
    folder = content_folder(Path(root))
    if folder is None:
        r.fail(f"no art cache at {Path(root).expanduser()} (no current.json naming a folder with sc/): "
               "run crforge-art extract")
        return r
    r.folder = folder
    r.ok(f"cache {folder}")

    manifest_path = folder / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text())
    except (OSError, ValueError) as e:
        r.fail(f"manifest.json unreadable: {e}")
        return r
    r.ok(f"game version {manifest.get('version')}, content sha {manifest.get('content_sha')}, "
         f"scope {manifest.get('scope')}")

    files = manifest.get("files") or {}
    missing = []
    textures = 0
    for stem, entry in files.items():
        for rel in [entry.get("scene", "")] + list(entry.get("textures", [])):
            if not rel or not (folder / rel).is_file():
                missing.append(rel or stem)
        textures += len(entry.get("textures", []))
    if missing:
        r.fail(f"{len(missing)} files the manifest lists are missing, e.g. {missing[:3]}: "
               "extract again (add --force if they stay missing)")
    else:
        r.ok(f"{len(files)} scenes and {textures} atlases ({_size(folder / 'sc') / 1e6:.0f} MB)")
    absent = [s for s in REQUIRED_SCENES if s not in files]
    if absent:
        r.fail(f"scenes a battle needs are not extracted: {', '.join(absent)} "
               "(run extract with the default --scope battle)")
    if manifest.get("errors"):
        r.warn(f"{len(manifest['errors'])} files failed to extract: {sorted(manifest['errors'])[:5]}")
    if manifest.get("missing"):
        r.ok(f"{len(manifest['missing'])} referenced files not in the game files "
             "(normal: arenas that ship as later downloads)")

    for rel in REQUIRED_TABLES:
        if not (folder / rel).is_file():
            r.fail(f"{rel} missing: extract again")
    for rel in REQUIRED_DIRS:
        if not (folder / rel).is_dir() or not any((folder / rel).iterdir()):
            r.fail(f"{rel}/ missing or empty: extract again")
    if all((folder / rel).is_file() for rel in REQUIRED_TABLES):
        r.ok("client tables, effects tables, locations and tilemaps")

    if (folder / FONT).is_file():
        r.ok("HUD font")
    else:
        r.warn(f"{FONT} missing: the HUD falls back to a plain font")

    audio = folder / "audio" / "index.json"
    if audio.is_file():
        try:
            index = json.loads(audio.read_text())
            fmt = index.get("format")
            if fmt != 2:
                r.warn("audio/index.json is an old format: run extract --audio again")
            else:
                r.ok(f"sounds: {len(index.get('events', {}))} events in {len(index.get('banks', {}))} "
                     f"banks ({_size(folder / 'audio') / 1e6:.0f} MB)")
        except ValueError as e:
            r.warn(f"audio/index.json unreadable: {e}")
    else:
        r.warn("no sounds (optional): crforge-art extract ... --audio")

    sc3d = folder / "sc3d" / "index.json"
    if sc3d.is_file():
        try:
            index = json.loads(sc3d.read_text())
            models = index.get("models", {})
            gone = [m["glb"] for m in models.values() if m.get("glb") and not (folder / m["glb"]).is_file()]
            if gone:
                r.fail(f"{len(gone)} 3D models the sc3d index names are missing, e.g. {gone[:2]}: "
                       "run crforge-art sc3d --force")
            else:
                r.ok(f"3D models: {len(index.get('characters', {}))} rows, {len(models)} models "
                     f"({_size(folder / 'sc3d') / 1e6:.0f} MB)")
        except ValueError as e:
            r.fail(f"sc3d/index.json unreadable: {e}")
    else:
        r.warn("no 3D models (optional; hero forms and evolutions draw their 2D stand-in): "
               "crforge-art sc3d ...")
    return r


def main(root: Path) -> int:
    r = check(root)
    print("\n".join(r.lines))
    print()
    if r.problems:
        print(f"{r.problems} problem(s), {r.warnings} warning(s): the cache is not complete")
        return 1
    print(f"the cache is complete ({r.warnings} warning(s))")
    return 0
