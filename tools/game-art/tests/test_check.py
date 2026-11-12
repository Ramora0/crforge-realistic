"""crforge-art check on a hand-made cache (no game files needed)."""
import json

from crforge_art import check


def _cache(tmp_path, *, drop=None):
    root = tmp_path / "art"
    folder = root / "abc"
    files = {}
    for stem in check.REQUIRED_SCENES:
        (folder / "sc").mkdir(parents=True, exist_ok=True)
        (folder / "sc" / f"{stem}.json").write_text("{}")
        (folder / "sc" / f"{stem}_0.png").write_bytes(b"x")
        files[stem] = {"scene": f"sc/{stem}.json", "textures": [f"sc/{stem}_0.png"]}
    for rel in check.REQUIRED_TABLES:
        (folder / rel).parent.mkdir(parents=True, exist_ok=True)
        (folder / rel).write_text("x")
    for rel in check.REQUIRED_DIRS:
        (folder / rel).mkdir(parents=True, exist_ok=True)
        (folder / rel / "a.csv").write_text("x")
    (folder / "manifest.json").write_text(json.dumps(
        {"format": 1, "content_sha": "abc", "version": "1.2.3", "scope": "battle", "files": files,
         "errors": {}, "missing": []}))
    (root / "current.json").write_text(json.dumps({"content_sha": "abc", "version": "1.2.3"}))
    if drop:
        (folder / drop).unlink()
    return root


def test_a_complete_cache_passes_with_the_optional_parts_as_warnings(tmp_path):
    r = check.check(_cache(tmp_path))
    assert r.problems == 0
    assert r.warnings == 3  # no font, no sounds, no 3D models
    assert check.main(tmp_path / "art") == 0


def test_a_missing_atlas_or_table_fails(tmp_path):
    assert check.check(_cache(tmp_path / "a", drop="sc/ui_0.png")).problems == 1
    assert check.check(_cache(tmp_path / "b", drop="client/legacy_vfx/effects.json")).problems == 1


def test_no_cache_fails(tmp_path):
    assert check.main(tmp_path / "nothing") == 1


def test_a_missing_3d_model_fails(tmp_path):
    root = _cache(tmp_path)
    sc3d = root / "abc" / "sc3d"
    sc3d.mkdir()
    (sc3d / "index.json").write_text(json.dumps(
        {"characters": {"Ronin": {}}, "models": {"m": {"glb": "sc3d/models/m.glb"}}}))
    assert check.check(root).problems == 1
    (sc3d / "models").mkdir()
    (sc3d / "models" / "m.glb").write_bytes(b"glTF")
    assert check.check(root).problems == 0
