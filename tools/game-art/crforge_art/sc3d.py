"""Extracts the game's 3D (sc3d) characters into standard glTF 2.0 files in the art cache.

Inputs (all inside the game's assets/ folder):
  assets.scdb             SQLite asset database: ``meta`` (path, assetType, assetFilename, uuid) and
                          ``asset`` (path, data). Prefabs (``ScrollRuntimeScene``), animation state
                          machines (``AssetASMData``) and timelines are JSON in ``asset.data``.
  csv_logic/**.toml       ``PrefabAsset = "sc3d/.../hero_prefab_x_battle"`` per character/building row
  sc3d/**.glb             FLA2 flat glTF (flatgltf.py) + SC_odin_format (odin.py): models and clips
  sc3d/**.ktx             KTX2 ASTC textures (ktx2.py)
  sc3d/**.rmat            material overrides (rmat.py)

Output, under ``<cache>/<content_sha>/sc3d/``:
  index.json              see ``INDEX_DOC`` below
  models/<path>.glb       one self-contained .glb per model (mesh, skin, embedded textures, and every
                          animation clip that drives its skeleton, one glTF animation per clip file)
  textures/<path>.png     textures a model's material names but glTF has no slot for (team colour
                          mask, reflection, opacity), referenced from the material's ``extras``
  prefabs/<path>.json     the prefab JSON as stored in assets.scdb
  asm/<path>.json         animation state machines (state name -> clip, frame range, speed, events)
  materials/<path>.json   decoded .rmat material overrides referenced by prefabs

Only files under ``sc3d/`` are written; the 2D cache (manifest.json, current.json, sc/...) is not
touched.
"""
from __future__ import annotations

import csv
import io
import json
import os
import posixpath
import re
import sqlite3
import tempfile
import time
import traceback
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image

from . import flatgltf, ktx2, lzma_tables, odin, rmat
from .sources import Source

INDEX_FORMAT = 1
INDEX_DOC = """\
index.json:
{"format": 1, "content_sha", "version", "updated", "scope",
 "characters": {"<toml row name>": ["<prefab path>", ...]},          # rows with a PrefabAsset
 "prefabs": {"<prefab path>": {
     "json": "sc3d/prefabs/<path>.json",
     "models": [{"node": "model", "path": "sc3d/.../x_lod1_rig", "glb": "sc3d/models/...glb",
                 "transform": [16 floats, column-major, prefab root <- model node],
                 "asm": "<asm path>" | null, "materialOverrides": {"<geometry>": "<rmat path>"}}],
     "lights": [{"node", "rot", "Intensity", "Color", "Ambient", "AmbientSpecIntensity"}],
     "pivots": {"hudoffset": [x, y, z]},  "prefabs": ["<nested prefab path>"],
     "materials": ["<MaterialAsset rmat path>"], "missing": [...],
     "children": [{"node", "path", "transform"}],                   # nested prefabs, placed
     "mounts": [{"node", "bone", "prefab"}],                        # mount points (5022): a rider
                                                                    # prefab posed at the bone
     "materialEffects": [{"node", "path", "geometries"}],           # material assets without a model
     "particles": [{"node", "path", "bone", "transform", ...}],     # emitters, relative to their bone
     "timelines": ["<timeline path>"], "timelinePlay": [{"node", "name", "loop"}]}},
     # models[] also carry "materialAssets": [{"path", "geometries"}] (5018 on the model's node)
 "models": {"<model path>": {"glb", "source", "bytes", "triangles", "vertices", "joints",
     "clips": [{"name", "source", "frames", "fps", "tracks", "dropped_tracks", "inAsm"}],
     "textures": {"<ktx path>": "<png path or 'embedded'>"}, "missing": [...], "clip_errors": {...}}},
 "asm": {"<asm path>": "sc3d/asm/<path>.json"},
 "materials": {"<rmat path>": "sc3d/materials/<path>.json"},   # with their base materials; a
     # material's .ktx parameters are written as PNGs, listed in its "textures": {param: png path}
 "timelines": {"<timeline path>": "sc3d/timelines/<path>.json"},   # AssetPrefabTimeline JSON
 "particles": {"<emitter path>": "sc3d/particles/<path>.json"},    # MovieClipEmitterData JSON
 "environment": {                                                    # how battles show 3D actors
     "globals": {"VANILLA_3D_ACTOR_ENV": [...], "VANILLA_3D_PPM": [...], ...},   # client_globals rows
     "prefab": "<VANILLA_3D_ACTOR_ENV prefab>", "json": "sc3d/prefabs/<path>.json",
     "lights": [...as in prefabs], "cameras": [{"node", "chain": [{"name", "pos", "rot"}], ...camera}],
     "animStates": [{"AnimState": n, "AsmStateName": name}]},       # VANILLA_3D_ACTOR_ANIMATION_REMAPPING
 "errors": {"<path>": "<message>"}}
All paths are relative to the <content_sha>/ folder. Prefab space is Z-up, metres-like units,
rotations are Euler degrees (see docs/realistic-rendering.md "3D assets").
"""

SCDB = "assets.scdb"
PREFAB_TABLES = ("csv_logic/",)
_PREFAB_RE = re.compile(r'^\s*PrefabAsset\s*=\s*"([^"]+)"', re.M)
_SECTION_RE = re.compile(r"^\s*\[([^\]]+)\]\s*$")


def _ref(s: str | None) -> str | None:
    """'sc3d/a/b | <uuid>' -> 'sc3d/a/b'."""
    if not s:
        return None
    return s.split("|")[0].strip() or None


# --------------------------------------------------------------------------- asset database
class AssetDB:
    def __init__(self, src: Source, tmp: Path):
        if not src.has(SCDB):
            raise SystemExit(f"{SCDB} not found in the game files: the 3D assets ship in "
                             "split_install_time_asset_pack.apk, pass it too")
        path = src.local_path(SCDB, tmp)
        self.con = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
        self.meta = {r[0]: (r[1], r[2]) for r in self.con.execute("select path, assetType, assetFilename from meta")}

    def data(self, path: str) -> bytes | None:
        r = self.con.execute("select data from asset where path=?", (path,)).fetchone()
        return r[0] if r and r[0] else None

    def json(self, path: str):
        d = self.data(path)
        return json.loads(d) if d else None

    def filename(self, path: str, ext: str) -> str:
        m = self.meta.get(path)
        return m[1] if m and m[1] else path + ext

    def paths(self, asset_type: str, prefix: str = "") -> list[str]:
        return sorted(p for p, (t, _) in self.meta.items() if t == asset_type and p.startswith(prefix))

    def close(self):
        self.con.close()


# --------------------------------------------------------------------------- table references
def table_prefabs(tables: dict[str, str]) -> dict[str, list[str]]:
    """{row name: [PrefabAsset, ...]} from the decoded logic tables (toml sections, csv columns)."""
    out: dict[str, list[str]] = {}
    for rel, text in sorted(tables.items()):
        if not rel.startswith(PREFAB_TABLES):
            continue
        if rel.endswith(".toml"):
            section = ""
            for line in text.splitlines():
                m = _SECTION_RE.match(line)
                if m:
                    section = m.group(1).split(".", 1)[-1]
                    continue
                m = _PREFAB_RE.match(line)
                if m and m.group(1).startswith("sc3d/"):
                    lst = out.setdefault(section or posixpath.basename(rel), [])
                    if m.group(1) not in lst:
                        lst.append(m.group(1))
        elif rel.endswith(".csv") and "PrefabAsset" in text[:4096]:
            rows = list(csv.reader(io.StringIO(text)))
            if not rows or "PrefabAsset" not in rows[0]:
                continue
            ci = rows[0].index("PrefabAsset")
            for row in rows[2:]:
                if len(row) > ci and row[ci].startswith("sc3d/"):
                    out.setdefault(row[0], []).append(row[ci])
    return out


def read_tables(src: Source) -> dict[str, str]:
    tables = {}
    for rel in src.names("csv_logic/"):
        if rel.endswith((".toml", ".csv")):
            try:
                tables[rel] = lzma_tables.decode(src.read(rel)).decode("utf-8", "replace")
            except Exception:  # noqa: BLE001
                continue
    return tables


ENV_GLOBALS = ("VANILLA_3D_ENABLE", "VANILLA_3D_ACTOR_ENV", "VANILLA_3D_ACTOR_ANIMATION_REMAPPING",
               "VANILLA_3D_ACTOR_COLORS", "VANILLA_3D_PPM", "VANILLA_3D_PPM_OF_SHADOW",
               "VANILLA_3D_SKEW_SHADOW_ENABLE", "BLOB_SHADOW_SOFT_EDGE", "BLOB_SHADOW_RADIUS_FACTOR")


def client_globals(src: Source, names=ENV_GLOBALS) -> dict[str, list]:
    """{name: [values]} of ``csv_client/client_globals.csv`` rows: each row's non-empty cells (int,
    float, bool or text), a nameless row continuing the arrays of the row above it."""
    rel = "csv_client/client_globals.csv"
    if not src.has(rel):
        return {}
    try:
        text = lzma_tables.decode(src.read(rel)).decode("utf-8", "replace")
    except Exception:  # noqa: BLE001
        return {}
    out: dict[str, list] = {}
    current = None
    for row in list(csv.reader(io.StringIO(text)))[2:]:
        if not row:
            continue
        if row[0].strip():
            current = row[0].strip()
        if current not in names:
            continue
        for cell in row[1:]:
            c = cell.strip()
            if not c:
                continue
            if c.upper() in ("TRUE", "FALSE"):
                v = c.upper() == "TRUE"
            else:
                try:
                    v = int(c, 0)
                except ValueError:
                    try:
                        v = float(c)
                    except ValueError:
                        v = c
            out.setdefault(current, []).append(v)
    return out


def environment(db: "AssetDB", src: Source, root: Path) -> dict:
    """The 3D actors' battle environment: the client_globals that steer it, the
    ``VANILLA_3D_ACTOR_ENV`` prefab's camera and lights, and the animation state remapping."""
    g = client_globals(src)
    env: dict = {"globals": g}
    prefab = next((v for v in g.get("VANILLA_3D_ACTOR_ENV", []) if isinstance(v, str)), None)
    if prefab:
        pj = db.json(prefab)
        if pj is not None:
            prel = f"sc3d/prefabs/{prefab[len('sc3d/'):]}.json"
            _write_json(root / prel, pj)
            summ = walk_prefab(pj)
            env.update({"prefab": prefab, "json": prel, "lights": summ["lights"],
                        "cameras": summ.get("cameras", [])})
    remap = next((v for v in g.get("VANILLA_3D_ACTOR_ANIMATION_REMAPPING", []) if isinstance(v, str)), None)
    if remap:
        rj = db.json(remap)
        if rj is not None:
            env["animStates"] = [{"AnimState": s.get("AnimState", 0), "AsmStateName": s.get("AsmStateName")}
                                 for s in rj.get("AnimationStates") or []]
    return env


# --------------------------------------------------------------------------- prefab walking
def euler_deg_to_matrix(rot) -> np.ndarray:
    """Prefab ``rot`` (degrees) -> 3x3. Order GUESS: R = Rz * Ry * Rx (applied x, then y, then z)."""
    x, y, z = np.radians(np.asarray(rot, dtype=np.float64))
    cx, sx, cy, sy, cz, sz = np.cos(x), np.sin(x), np.cos(y), np.sin(y), np.cos(z), np.sin(z)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return rz @ ry @ rx


def prefab_node_matrix(n: dict) -> np.ndarray:
    m = np.eye(4)
    m[:3, :3] = euler_deg_to_matrix(n.get("rot", [0, 0, 0])) * np.asarray(n.get("scl", [1, 1, 1]), float)[None, :]
    m[:3, 3] = n.get("pos", [0, 0, 0])
    return m


def walk_prefab(pj: dict) -> dict:
    """Summary of a prefab JSON: models, lights, pivots, nested prefabs, material assets, and the
    mount points (5022: a prefab, ``Rider``, posed at a bone of the node's model each frame)."""
    res = {"models": [], "lights": [], "pivots": {}, "prefabs": [], "materials": [], "timelines": [],
           "mounts": []}
    pivots = []
    chain: list[dict] = []

    def visit(node: dict, parent: np.ndarray):
        chain.append({k: node.get(k) for k in ("name", "pos", "rot")})
        mtx = parent @ prefab_node_matrix(node)
        name = node.get("name", "")
        comps = node.get("components") or []
        asm = next((_ref(c.get("AsmAsset")) for c in comps if c.get("objectType") == 5003), None)
        has_model = any(c.get("objectType") == 5001 and _ref(c.get("Model")) for c in comps)
        bone = next((c for c in comps if c.get("objectType") == 5012), None)
        if bone is not None:
            bones.append((len(chain), bone.get("BoneName"), mtx))
        for c in comps:
            t = c.get("objectType")
            if t == 5001 and _ref(c.get("Model")):
                over = {g.get("Name", ""): _ref(g.get("MaterialOverride")) for g in c.get("Geometries") or []
                        if _ref(g.get("MaterialOverride"))}
                assets = [{"path": _ref(x.get("MaterialAsset")), "geometries": x.get("GeometryNames") or []}
                          for x in comps if x.get("objectType") == 5018 and _ref(x.get("MaterialAsset"))]
                res["models"].append({"node": name, "path": _ref(c["Model"]),
                                      "transform": [float(v) for v in mtx.T.reshape(-1)],
                                      "asm": asm, "materialOverrides": over,
                                      "shadow": bool(c.get("ShadowVisible", False)), "layer": c.get("Layer"),
                                      "minDeviceCap": c.get("MinDeviceCap"), "matLodCap": c.get("MatLODCap"),
                                      **({"materialAssets": assets} if assets else {})})
            elif t == 5018 and _ref(c.get("MaterialAsset")) and not has_model:
                # a material asset on a node without a model: applied to the geometries it names on
                # the model the prefab is attached to (a glow on its owner)
                res.setdefault("materialEffects", []).append(
                    {"node": name, "path": _ref(c["MaterialAsset"]), "geometries": c.get("GeometryNames") or [],
                     "minDeviceCap": c.get("MinDeviceCap")})
                res["materials"].append(_ref(c["MaterialAsset"]))
            elif t == 5004 and _ref(c.get("Particle")):
                b = bones[-1] if bones else None
                rel = np.linalg.inv(b[2]) @ mtx if b is not None else mtx
                res.setdefault("particles", []).append(
                    {"node": name, "path": _ref(c["Particle"]), "bone": b[1] if b is not None else None,
                     "transform": [float(v) for v in rel.T.reshape(-1)],
                     **{k: c[k] for k in ("Layer", "MinDeviceCap", "FollowAngle", "FollowScale", "Basis",
                                          "SimulationSpace") if k in c}})
            elif t == 5016:
                res.setdefault("timelinePlay", []).append(
                    {"node": name, "name": c.get("TimelineName"), "loop": bool(c.get("Loop", False))})
            elif t == 5007:
                res["lights"].append({"node": name, "rot": node.get("rot"), **{k: v for k, v in c.items()
                                                                               if k != "objectType"}})
            elif t == 5006 and _ref(c.get("Prefab")):
                res["prefabs"].append(_ref(c["Prefab"]))
                res.setdefault("children", []).append(
                    {"node": name, "path": _ref(c["Prefab"]), "transform": [float(v) for v in mtx.T.reshape(-1)]})
            elif t == 5022:
                for mp in c.get("MountPoints") or []:
                    if _ref(mp.get("Rider")):
                        res["mounts"].append({"node": name, "bone": mp.get("BoneName"),
                                              "prefab": _ref(mp["Rider"])})
            elif t == 5018 and _ref(c.get("MaterialAsset")):
                res["materials"].append(_ref(c["MaterialAsset"]))
            elif t == 5019:
                pivots.extend(p.get("PivotName") for p in c.get("PivotPoints") or [])
            elif t == 5014:
                res["timelines"].extend(_ref(x.get("asset")) for x in c.get("timelines") or [])
            elif t == 5011:
                res.setdefault("cameras", []).append(
                    {"node": name, "chain": [dict(x) for x in chain[1:]],
                     **{k: v for k, v in c.items() if k != "objectType"}})
        for ch in node.get("children") or []:
            visit(ch, mtx)
        if bones and bones[-1][0] == len(chain):
            bones.pop()
        chain.pop()
        if name:
            positions[name] = [float(v) for v in mtx[:3, 3]]

    positions: dict[str, list[float]] = {}
    bones: list[tuple] = []  # (depth, bone name, node matrix) of the enclosing bone attachments
    visit(pj.get("data", pj), np.eye(4))
    res["pivots"] = {p: positions[p] for p in pivots if p in positions}
    for m in res["models"]:
        res["materials"].extend(v for v in m["materialOverrides"].values())
    return res


def timeline_effects(tj: dict) -> list[str]:
    """The prefabs a timeline's events spawn (``"Effect": "sc3d/... | uuid"``)."""
    out = []
    for d in ((tj or {}).get("anims") or {}).get("data") or []:
        for e in (d or {}).get("events") or []:
            p = _ref(e.get("Effect")) if isinstance(e.get("Effect"), str) else None
            if p and p.startswith("sc3d/") and p not in out:
                out.append(p)
    return out


def asm_clips(asm: dict) -> list[dict]:
    """[{state, clip asset path, StartFrame, EndFrame, BaseSpeed}] of an animation state machine."""
    out = []

    def visit(states):
        for s in states or []:
            if _ref(s.get("Animation")):
                out.append({"state": s.get("Name"), "clip": _ref(s["Animation"]), "start": s.get("StartFrame"),
                            "end": s.get("EndFrame"), "speed": s.get("BaseSpeed")})
            visit(s.get("States"))
    visit((asm or {}).get("States"))
    return out


# --------------------------------------------------------------------------- textures
def _png(img: np.ndarray) -> bytes:
    if img.ndim == 3 and img.shape[2] == 4 and (img[:, :, 3] == 255).all():
        img = img[:, :, :3]
    bio = io.BytesIO()
    Image.fromarray(np.ascontiguousarray(img)).save(bio, format="PNG", compress_level=6)
    return bio.getvalue()


def normal_map(rgba: np.ndarray) -> np.ndarray:
    """The game's two-channel normal maps (X in RGB, Y in A) -> standard tangent-space RGB."""
    x = rgba[:, :, 0].astype(np.float32) / 127.5 - 1.0
    y = rgba[:, :, 3].astype(np.float32) / 127.5 - 1.0
    z = np.sqrt(np.clip(1.0 - x * x - y * y, 0.0, 1.0))
    n = np.stack([x, y, z], 2)
    n /= np.maximum(np.linalg.norm(n, axis=2, keepdims=True), 1e-6)
    return np.clip((n * 0.5 + 0.5) * 255.0 + 0.5, 0, 255).astype(np.uint8)


def orm_map(rgba: np.ndarray) -> np.ndarray:
    """MRA (R metallic, G roughness, B ambient occlusion) -> glTF ORM (R occlusion, G roughness, B metallic)."""
    return np.ascontiguousarray(rgba[:, :, [2, 1, 0]])


def is_two_channel_normal(rgba: np.ndarray) -> bool:
    r, g, b = (rgba[:, :, i].astype(np.int16) for i in range(3))
    return bool(np.abs(r - g).max() <= 2 and np.abs(r - b).max() <= 2)


# --------------------------------------------------------------------------- model building
@dataclass
class Ctx:
    src: Source
    db: AssetDB
    root: Path            # <cache>/<sha>
    force: bool = False
    log: object = print
    ktx_cache: dict = field(default_factory=dict)
    side_textures: dict = field(default_factory=dict)  # ktx path -> png rel path

    def ktx(self, path: str) -> np.ndarray:
        if path not in self.ktx_cache:
            if len(self.ktx_cache) > 64:
                self.ktx_cache.clear()
            self.ktx_cache[path] = ktx2.decode(self.src.read(path))
        return self.ktx_cache[path]


GLTF_SLOTS = {"diffuseTex": "baseColor", "normalTex": "normal", "mraTex": "orm"}


def _tex_ref(v: str) -> tuple[str, str]:
    path, _, opt = v.partition("#")
    return path, opt


def _wrap(opt: str) -> int:
    return {"clamp": 33071, "mirror": 33648}.get(opt, 10497)


def make_material_cb(ctx: Ctx, report: dict):
    images: dict[tuple[str, str], int] = {}

    def texture(b: odin.Builder, g: dict, path: str, role: str, wrap: int) -> int | None:
        key = (path, role)
        if key not in images:
            if not ctx.src.has(path):
                report.setdefault("missing", []).append(path)
                images[key] = None
                return None
            rgba = ctx.ktx(path)
            if role == "normal":
                img = normal_map(rgba) if is_two_channel_normal(rgba) else rgba[:, :, :3]
            elif role == "orm":
                img = orm_map(rgba)
            else:
                img = rgba
            view = b.view(_png(img))
            g.setdefault("images", []).append({"bufferView": view, "mimeType": "image/png",
                                               "name": posixpath.basename(path)[:-4] + ("" if role == "baseColor" else f"_{role}")})
            images[key] = len(g["images"]) - 1
            report.setdefault("textures", {})[path] = "embedded"
        src_i = images[key]
        if src_i is None:
            return None
        samplers = g.setdefault("samplers", [])
        smp = {"magFilter": 9729, "minFilter": 9987, "wrapS": wrap, "wrapT": wrap}
        if smp not in samplers:
            samplers.append(smp)
        tex = {"source": src_i, "sampler": samplers.index(smp)}
        texs = g.setdefault("textures", [])
        if tex not in texs:
            texs.append(tex)
        return texs.index(tex)

    def side_texture(path: str) -> str | None:
        if path in ctx.side_textures:
            return ctx.side_textures[path]
        rel = None
        if ctx.src.has(path):
            rel = "sc3d/textures/" + path[len("sc3d/"):-len(".ktx")] + ".png"
            dst = ctx.root / rel
            if ctx.force or not dst.exists():
                _atomic_write(dst, _png(ctx.ktx(path)))
            report.setdefault("textures", {})[path] = rel
        else:
            report.setdefault("missing", []).append(path)
        ctx.side_textures[path] = rel
        return rel

    def cb(m: dict, b: odin.Builder, g: dict) -> dict:
        v = m.get("variables") or {}
        texs = v.get("textures") or {}
        floats = v.get("floats") or {}
        mat: dict = {"name": m.get("name", "")}
        pbr: dict = {}
        side = {}
        for k, val in texs.items():
            if not val:
                continue
            path, opt = _tex_ref(val)
            slot = GLTF_SLOTS.get(k)
            if slot:
                ti = texture(b, g, path, slot, _wrap(opt))
                if ti is None:
                    continue
                if slot == "baseColor":
                    pbr["baseColorTexture"] = {"index": ti}
                elif slot == "normal":
                    mat["normalTexture"] = {"index": ti}
                elif slot == "orm":
                    pbr["metallicRoughnessTexture"] = {"index": ti}
                    mat["occlusionTexture"] = {"index": ti}
            else:
                rel = side_texture(path)
                if rel:
                    side[k] = rel
        if "metallicRoughnessTexture" in pbr:
            pbr["metallicFactor"] = 1.0
            pbr["roughnessFactor"] = 1.0
        else:
            pbr["metallicFactor"] = float(floats.get("metalness", 0.0))
            pbr["roughnessFactor"] = float(floats.get("roughness", 1.0))
        mat["pbrMetallicRoughness"] = pbr
        extras = {"shader": m.get("shader"), "blendMode": m.get("blendMode"), "constants": m.get("constants"),
                  "variables": v}
        if side:
            extras["sideTextures"] = side
        mat["extras"] = extras
        if "opacityTex" in texs:
            mat["alphaMode"] = "MASK"
        return mat

    return cb


def effect_texture(ctx: Ctx, path: str) -> str | None:
    """A texture an effect material names (mask, dissolve, trail), as a PNG beside the models."""
    path = _tex_ref(path)[0]
    if path in ctx.side_textures:
        return ctx.side_textures[path]
    rel = None
    if ctx.src.has(path):
        rel = "sc3d/textures/" + path[len("sc3d/"):-len(".ktx")] + ".png"
        dst = ctx.root / rel
        if ctx.force or not dst.exists():
            _atomic_write(dst, _png(ctx.ktx(path)))
    ctx.side_textures[path] = rel
    return rel


def _atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def _write_json(path: Path, obj) -> None:
    _atomic_write(path, (json.dumps(obj, indent=1) + "\n").encode())


def clip_tracks(src: Source, glb_rel: str) -> tuple[dict, list[str]] | None:
    """(animation info from odin.convert, node names of the clip file) or None if no animation."""
    g, b = flatgltf.load(src.read(glb_rel))
    anim = (odin._ext(g) or {}).get("animation")
    if not anim:
        return None
    tracks = odin.decode_packed_animation(odin.Source(g, b), anim)
    return ({"tracks": tracks, "fps": anim.get("frameRate") or 30.0, "frames": anim.get("keyframeCount"),
             "firstFrame": anim.get("firstFrame", 0), "lastFrame": anim.get("lastFrame")},
            odin.node_names(g))


def _qmul(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    """Quaternion products a*b of (F,4) xyzw arrays."""
    ax, ay, az, aw = a[:, 0], a[:, 1], a[:, 2], a[:, 3]
    bx, by, bz, bw = b[:, 0], b[:, 1], b[:, 2], b[:, 3]
    return np.stack([aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
                     aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz], axis=1)


def _qrot(q: np.ndarray, v: np.ndarray) -> np.ndarray:
    """(F,3) vectors v turned by the (F,4) xyzw quaternions q."""
    u, w = q[:, :3], q[:, 3:4]
    t = 2 * np.cross(u, v)
    return v + w * t + np.cross(u, t)


def fold_ssc(tracks: list[dict], names: list[str]) -> list[dict]:
    """Folds each ``X:SSC`` track (Maya's segment scale compensate group a clip file keeps above a
    joint, which the model does not have) into joint ``X``'s own: X' = SSC * X, per frame. Left as
    is, X takes its translation from the clip (zero, as it sits at the group's origin) and the group
    is dropped, so the joint collapses onto its parent (the Dark Prince's walks, the rhino's). A
    joint whose translation neither is keyed nor has a base value (all zero) keeps its rest one: its
    translation is None, and no channel is written for it.
    """
    by_name = {names[t["node"]]: t for t in tracks if 0 <= t["node"] < len(names)}
    out = []
    for t in tracks:
        n = names[t["node"]] if 0 <= t["node"] < len(names) else ""
        if n.endswith(":SSC"):
            continue
        g = by_name.get(n + ":SSC")
        if g is not None:
            f = max(t["frames"], g["frames"])

            def pad(a, f=f):
                return np.concatenate([a, np.repeat(a[-1:], f - len(a), axis=0)]) if len(a) < f else a[:f]
            gt, gr, gs = (pad(g[k].astype(np.float64)) for k in ("translation", "rotation", "scale"))
            xt, xr, xs = (pad(t[k].astype(np.float64)) for k in ("translation", "rotation", "scale"))
            t = dict(t, frames=f, flags=t["flags"] | g["flags"],
                     translation=(gt + _qrot(gr, gs * xt)).astype(np.float32),
                     rotation=_qmul(gr, xr).astype(np.float32), scale=(gs * xs).astype(np.float32))
        if not t["flags"] & odin.FLAG_TRANS and not np.any(t["translation"]):
            # no translation keyed and none in the base: the joint keeps its rest translation
            t = dict(t, translation=None)
        out.append(t)
    return out


def candidate_clips(ctx: Ctx, model_rel: str) -> list[str]:
    """Clip .glb files next to a model: <character dir>/anim*/*.glb (the model sits in <character dir>/model/)."""
    d = posixpath.dirname(model_rel)
    if posixpath.basename(d).startswith("model"):
        d = posixpath.dirname(d)
    out = []
    for rel in ctx.src.names(d + "/"):
        if rel.endswith(".glb") and "/anim" in rel[len(d):]:
            out.append(rel)
    return out


Y_UP_ROOT = "zup_to_yup"


def add_y_up_root(g: dict) -> None:
    """Wraps the scene in a node rotating -90 degrees about X.

    The models are authored Y-up, but their top node (``CHARACTER``) turns them into the game's
    Z-up prefab space. glTF is Y-up, so without this wrapper every viewer shows them lying on
    their back. To place a model in prefab space, skip this node (use its children).
    """
    nodes = g.setdefault("nodes", [])
    scene = g.setdefault("scenes", [{"nodes": []}])[g.get("scene", 0)]
    roots = list(scene.get("nodes", []))
    skinned = [r for r in roots if "skin" in nodes[r]]  # placed by their joints: stay scene roots
    nodes.append({"name": Y_UP_ROOT, "rotation": [-0.7071067811865476, 0.0, 0.0, 0.7071067811865476],
                  "children": [r for r in roots if r not in skinned],
                  "extras": {"note": "Z-up prefab space -> glTF Y-up; drop this node for prefab space"}})
    scene["nodes"] = [len(nodes) - 1] + skinned
    g["scene"] = 0


def build_model(ctx: Ctx, model_path: str, asm_clip_paths: list[str]) -> dict:
    rel = ctx.db.filename(model_path, ".glb")
    out_rel = "sc3d/models/" + rel[len("sc3d/"):]
    report: dict = {"glb": out_rel, "source": rel}
    g0, b0 = flatgltf.load(ctx.src.read(rel))
    cb = make_material_cb(ctx, report)
    g, bin_data, _ = odin.convert(g0, b0, material_cb=cb, name=posixpath.basename(rel)[:-4])
    builder = odin.Builder.resume(g, bin_data)  # the clips go after the model's data
    names = odin.node_names(g)
    by_name: dict[str, int] = {}
    for i, n in enumerate(names):
        if "skin" not in g["nodes"][i]:  # glTF ignores a skinned mesh node's transform: no channels
            by_name.setdefault(n, i)
    prefix = re.sub(r"(_lod\d+)?(_rig)?(_lod\d+)?$", "", posixpath.basename(rel)[:-4])
    seen = set()
    clips = []
    asm_set = {ctx.db.filename(p, ".glb") for p in asm_clip_paths}
    for crel in list(dict.fromkeys(list(asm_set) + candidate_clips(ctx, rel))):
        if crel in seen or not ctx.src.has(crel):
            if crel not in seen and crel in asm_set:
                report.setdefault("missing", []).append(crel)
            continue
        seen.add(crel)
        try:
            r = clip_tracks(ctx.src, crel)
        except Exception as e:  # noqa: BLE001
            report.setdefault("clip_errors", {})[crel] = f"{type(e).__name__}: {e}"
            continue
        if r is None:
            continue
        info, cnames = r
        tracks = fold_ssc(info["tracks"], cnames)
        matched = [t for t in tracks if 0 <= t["node"] < len(cnames) and cnames[t["node"]] in by_name]
        if not matched:
            continue
        if crel not in asm_set and (len(matched) < 0.9 * len(tracks)
                                    or not posixpath.basename(crel).startswith(prefix)):
            continue  # a clip of another rig (or of a sub-model sharing bone names) in the same folder
        cname = posixpath.basename(crel)[:-4]
        a = odin.animation_to_gltf(
            builder, matched, info["fps"], cname,
            node_map=lambda k, cn=cnames: by_name.get(cn[k]),
            extras={"source": crel, "frameRate": info["fps"], "firstFrame": info["firstFrame"],
                    "lastFrame": info["lastFrame"], "inAsm": crel in asm_set})
        if a:
            g.setdefault("animations", []).append(a)
            clips.append({"name": cname, "source": crel, "frames": info["frames"], "fps": info["fps"],
                          "tracks": len(matched), "dropped_tracks": len(tracks) - len(matched),
                          "inAsm": crel in asm_set})
    add_y_up_root(g)
    data = builder.data()
    g["buffers"] = [{"byteLength": len(data)}]
    glb = flatgltf.write_glb(g, data)
    _atomic_write(ctx.root / out_rel, glb)
    tris = sum(g["accessors"][p["indices"]]["count"] // 3 for m in g.get("meshes", []) for p in m["primitives"])
    verts = sum(g["accessors"][p["attributes"]["POSITION"]]["count"] for m in g.get("meshes", [])
                for p in m["primitives"] if "POSITION" in p["attributes"])
    report.update({"bytes": len(glb), "triangles": tris, "vertices": verts,
                   "joints": sum(len(s.get("joints", [])) for s in g.get("skins", [])), "clips": clips})
    return report


# --------------------------------------------------------------------------- driver
@dataclass
class Result:
    root: Path
    models: int = 0
    skipped: int = 0
    clips: int = 0
    errors: dict[str, str] = field(default_factory=dict)
    seconds: float = 0.0


def run(src: Source, out: Path, scope: str = "battle", only: list[str] | None = None, force: bool = False,
        log=print) -> Result:
    t0 = time.time()
    fp = src.fingerprint()
    root = Path(out).expanduser() / fp["sha"]
    res = Result(root=root)
    index_path = root / "sc3d" / "index.json"
    old = {}
    if index_path.exists():
        try:
            old = json.loads(index_path.read_text())
        except ValueError:
            old = {}
    log(f"sc3d: game version {fp['version']}  content sha {fp['sha']}")
    log(f"sc3d: cache {root / 'sc3d'}")
    with tempfile.TemporaryDirectory(prefix="crforge-sc3d-") as td:
        db = AssetDB(src, Path(td))
        ctx = Ctx(src, db, root, force=force, log=log)
        chars = table_prefabs(read_tables(src))
        if scope == "all":
            prefab_list = db.paths("ScrollRuntimeScene", "sc3d/")
        else:
            prefab_list = sorted({p for v in chars.values() for p in v})
        if only:
            want = {w.lower() for w in only}

            def wanted(p: str) -> bool:  # a table row name, or a substring of the prefab path
                rows = {r.lower() for r, v in chars.items() if p in v}
                return bool(rows & want) or any(w in p.lower() for w in want)
            prefab_list = [p for p in prefab_list if wanted(p)]
        # prefabs (+ nested ones)
        prefabs: dict[str, dict] = {}
        todo = list(prefab_list)
        while todo:
            p = todo.pop(0)
            if p in prefabs:
                continue
            pj = db.json(p)
            if pj is None:
                prefabs[p] = {"missing": [p]}
                res.errors[p] = "prefab not in assets.scdb"
                continue
            prel = f"sc3d/prefabs/{p[len('sc3d/'):] if p.startswith('sc3d/') else p}.json"
            _write_json(root / prel, pj)
            summ = walk_prefab(pj)
            summ["json"] = prel
            prefabs[p] = summ
            todo.extend(summ["prefabs"])
            todo.extend(m["prefab"] for m in summ["mounts"])
            for t in summ.get("timelines") or []:  # prefabs a timeline's events spawn
                todo.extend(timeline_effects(db.json(t)))
        # animation state machines and material overrides
        asm_index, asm_by_path = {}, {}
        mat_index, tl_index, ps_index = {}, {}, {}
        for p, s in prefabs.items():
            for m in s.get("models", []):
                a = m.get("asm")
                if a and a not in asm_by_path:
                    aj = db.json(a)
                    asm_by_path[a] = aj
                    if aj is not None:
                        arel = f"sc3d/asm/{a[len('sc3d/'):]}.json"
                        _write_json(root / arel, aj)
                        asm_index[a] = arel
            mats = list(s.get("materials", []))
            while mats:
                r = mats.pop(0)
                if r in mat_index:
                    continue
                frel = r if r.endswith(".rmat") else db.filename(r, ".rmat")
                if src.has(frel):
                    mrel = f"sc3d/materials/{frel[len('sc3d/'):-len('.rmat')]}.json"
                    try:
                        mj = rmat.load(src.read(frel))
                        texs = {k: effect_texture(ctx, v) for k, v in mj["params"].items()
                                if isinstance(v, str) and v.endswith(".ktx")}
                        if any(texs.values()):
                            mj["textures"] = {k: v for k, v in texs.items() if v}
                        _write_json(root / mrel, mj)
                        mat_index[r] = mrel
                        if mj.get("base"):  # the base material: its shader and default parameters
                            mats.append(mj["base"])
                    except Exception as e:  # noqa: BLE001
                        res.errors[frel] = f"{type(e).__name__}: {e}"
            for t in s.get("timelines", []):
                if t in tl_index:
                    continue
                tj = db.json(t)
                if tj is not None:
                    trel = f"sc3d/timelines/{t[len('sc3d/'):]}.json"
                    _write_json(root / trel, tj)
                    tl_index[t] = trel
            for pt in s.get("particles", []):
                pp = pt["path"]
                if pp in ps_index:
                    continue
                pj2 = db.json(pp)
                if pj2 is not None:
                    prel2 = f"sc3d/particles/{pp[len('sc3d/'):]}.json"
                    _write_json(root / prel2, pj2)
                    ps_index[pp] = prel2
        # models: each with the clips of every ASM that drives it
        model_clips: dict[str, list[str]] = {}
        for s in prefabs.values():
            for m in s.get("models", []):
                lst = model_clips.setdefault(m["path"], [])
                for c in asm_clips(asm_by_path.get(m.get("asm")) or {}):
                    if c["clip"] not in lst:
                        lst.append(c["clip"])
        models_index = dict((old.get("models") or {})) if not force else {}
        total = len(model_clips)
        for k, (mp, clip_paths) in enumerate(sorted(model_clips.items()), 1):
            rel = db.filename(mp, ".glb")
            out_glb = root / ("sc3d/models/" + rel[len("sc3d/"):])
            if not force and out_glb.exists() and mp in models_index:
                res.skipped += 1
                continue
            if not src.has(rel):
                res.errors[mp] = f"model {rel} not in the game files"
                log(f"[{k}/{total}] {mp}  MISSING")
                continue
            t1 = time.time()
            try:
                rep = build_model(ctx, mp, clip_paths)
            except Exception as e:  # noqa: BLE001
                res.errors[mp] = f"{type(e).__name__}: {e}"
                log(f"[{k}/{total}] {mp}  FAILED: {e}\n{traceback.format_exc(limit=4)}")
                continue
            models_index[mp] = rep
            res.models += 1
            res.clips += len(rep["clips"])
            log(f"[{k}/{total}] {posixpath.basename(mp)}  {rep['triangles']} tris, {rep['joints']} joints, "
                f"{len(rep['clips'])} clips, {rep['bytes'] / 1e6:.1f} MB, {time.time() - t1:.1f}s")
        env = environment(db, src, root)
        db.close()
    for s in prefabs.values():
        for m in s.get("models", []):
            m["glb"] = (models_index.get(m["path"]) or {}).get("glb")
    index = {
        "format": INDEX_FORMAT, "content_sha": fp["sha"], "version": fp["version"],
        "updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "scope": scope if not only else "only",
        "characters": {r: v for r, v in sorted(chars.items())},
        "prefabs": {**(old.get("prefabs") or {}), **dict(sorted(prefabs.items()))},
        "models": dict(sorted(models_index.items())),
        "asm": {**(old.get("asm") or {}), **asm_index},
        "materials": {**(old.get("materials") or {}), **mat_index},
        "timelines": {**(old.get("timelines") or {}), **tl_index},
        "particles": {**(old.get("particles") or {}), **ps_index},
        "environment": env or old.get("environment") or {},
        "errors": res.errors,
    }
    _write_json(index_path, index)
    res.seconds = time.time() - t0
    return res
