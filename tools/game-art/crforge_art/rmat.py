"""Supercell .rmat material files (sc3d) -> dict.

An .rmat is a FlatBuffer (no file identifier), reverse-engineered from Clash Royale's files::

    table Material { version: int (slot 0); base: string (slot 1); params: [Param] (slot 2);
                     shader: string (slot 5); blend: int (slot 6); slot 7: int; order: int (slot 8) }
    table Param    { name: string (slot 0); type: ubyte (slot 1); value: <table by type> (slot 2) }
    value tables:
        1 float (slot 0) | 2 vec2 | 3 vec3 | 4 vec4/colour: one float per component, x in slot 0,
        y in 1, z in 2, w in 3, each left out when 0 (the FlatBuffer default) | 5 int (slot 0)
        6 texture: string "sc3d/.../x.ktx" (slot 0) | 7 bool (slot 0)

``base`` names the material it overrides (e.g. ``sc3d/shared/materials/base_pbr.rmat``); params
override that material's shader variables of the same name. A base material (no ``base``) names
its ``shader`` (``uber_pbr``, ``vfx_mask``, ...). ``blend`` is 5 or 6 on effect materials: 6 on the
ones named ``*_add`` (additive), 5 on the others (alpha blended), INFERRED from the names; ``order``
is a small sort number on some effect materials (GUESS: draw order); slot 7 (0/1) is kept raw.
Types 2 and 5 are INFERRED from the vector-length pattern; unknown types are kept as their raw
bytes (hex).
"""
from __future__ import annotations

import struct

_FLOATS = {1: 1, 2: 2, 3: 3, 4: 4}


def _table_fields(b: bytes, pos: int) -> dict[int, int]:
    vt = pos - struct.unpack_from("<i", b, pos)[0]
    vl = struct.unpack_from("<H", b, vt)[0]
    out = {}
    for i in range((vl - 4) // 2):
        off = struct.unpack_from("<H", b, vt + 4 + 2 * i)[0]
        if off:
            out[i] = pos + off
    return out


def _string(b: bytes, pos: int) -> str:
    p = pos + struct.unpack_from("<I", b, pos)[0]
    n = struct.unpack_from("<I", b, p)[0]
    return b[p + 4:p + 4 + n].decode("utf-8", "replace")


def _value(b: bytes, kind: int, pos: int):
    f = _table_fields(b, pos)
    if kind in _FLOATS:
        n = _FLOATS[kind]
        v = [struct.unpack_from("<f", b, f[i])[0] if i in f else 0.0 for i in range(n)]
        return v[0] if n == 1 else v
    if kind == 5:
        return struct.unpack_from("<i", b, f[0])[0] if 0 in f else 0
    if kind == 6:
        return _string(b, f[0]) if 0 in f else ""
    if kind == 7:
        return bool(b[f[0]]) if 0 in f else False
    return {"type": kind, "raw": b[pos:pos + 16].hex()}


def load(data: bytes) -> dict:
    b = bytes(data)
    root = struct.unpack_from("<I", b, 0)[0]
    f = _table_fields(b, root)
    out: dict = {"version": struct.unpack_from("<i", b, f[0])[0] if 0 in f else 0,
                 "base": _string(b, f[1]) if 1 in f else None, "params": {}}
    if 2 in f:
        vec = f[2] + struct.unpack_from("<I", b, f[2])[0]
        for k in range(struct.unpack_from("<I", b, vec)[0]):
            ep = vec + 4 + 4 * k
            pf = _table_fields(b, ep + struct.unpack_from("<I", b, ep)[0])
            name = _string(b, pf[0]) if 0 in pf else f"#{k}"
            kind = b[pf[1]] if 1 in pf else 0
            if 2 in pf:
                vpos = pf[2] + struct.unpack_from("<I", b, pf[2])[0]
                out["params"][name] = _value(b, kind, vpos)
            else:
                out["params"][name] = None
    if 5 in f:
        out["shader"] = _string(b, f[5])
    if 6 in f:
        out["blend"] = struct.unpack_from("<i", b, f[6])[0]
    if 7 in f:
        out["slot7"] = struct.unpack_from("<i", b, f[7])[0]
    if 8 in f:
        out["order"] = struct.unpack_from("<i", b, f[8])[0]
    return out
