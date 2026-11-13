# crforge - https://github.com/voonhous/crforge
# SPDX-License-Identifier: Apache-2.0
"""Pixel parity of the phone server's draw-list stream (docs/phone-server.md).

Replays the parity frames PhoneRenderBench writes (the frame as a key frame, the texture patches
made so far) in the phone page's own player (index.html's crforgeDraw) in a headless browser, and
compares the picture with the server's own (its frame buffer), writing both and an amplified
difference beside them.

    ./gradlew :realistic:phoneRenderBench -Pcrforge.assetSource=cdn --args="OUT 1500,3000"
    uv run --with playwright --with pillow --with numpy tools/phone-render/parity.py OUT \
        [--browser chromium|webkit] [--scale 1]
"""

import argparse
import base64
import json
import pathlib
import struct
import sys

import numpy as np
from PIL import Image
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parents[2]
PAGE = ROOT / "realistic/src/main/resources/org/crforge/realistic/server/index.html"
CLEAR = [0.11, 0.13, 0.12]

SCRIPT = """
async ([frame, patches, w, h, scale, clear]) => {
  const c = document.createElement('canvas');
  c.width = w * scale; c.height = h * scale;
  c.style.cssText = 'position:fixed;left:0;top:0;z-index:99;width:' + w + 'px;height:' + h + 'px';
  document.body.appendChild(c);
  const D = window.crforgeDraw;
  if (!D.init(c)) return { error: 'no WebGL' };
  if (window.crforge3d && window.PARITY_DEBUG) Object.assign(window.crforge3d.debug, window.PARITY_DEBUG);
  for (const p of patches) {
    const png = new Uint8Array(await (await fetch(p.url)).arrayBuffer());
    const buf = new ArrayBuffer(24 + png.length), dv = new DataView(buf);
    dv.setUint8(0, 3); dv.setUint8(1, p.plain ? 1 : 0);
    dv.setInt32(4, p.page); dv.setInt32(8, p.pw); dv.setInt32(12, p.ph); dv.setInt32(16, p.x); dv.setInt32(20, p.y);
    new Uint8Array(buf, 24).set(png);
    await D.patch(buf);
  }
  const bin = new Uint8Array(await (await fetch(frame)).arrayBuffer());
  const f = await D.decode(bin);
  // the 3D models the frame draws: their files, as the page fetches them from the server
  if (window.crforge3d && f.models && f.models.length) await window.crforge3d.whenReady(f);
  const r = D.draw(f, w, h, clear);
  const px = D.readPixels();
  let s = '';
  for (let i = 0; i < px.px.length; i += 0x8000) s += String.fromCharCode.apply(null, px.px.subarray(i, i + 0x8000));
  return { w: px.w, h: px.h, data: btoa(s), draw: r, gl2: D.gl2, polys: f.count, models: (f.models || []).length,
    rects: (f.models || []).filter((m) => !m.shadow).map((m) => m.drawn), ua: navigator.userAgent };
}
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", type=pathlib.Path)
    ap.add_argument("--browser", default="chromium")
    ap.add_argument("--scale", type=int, default=1)
    ap.add_argument("--frames", default="", help="only these frames (comma separated)")
    ap.add_argument("--debug", default="", help="3D debug flags for the page, as JSON")
    a = ap.parse_args()
    frames = sorted(int(p.stem) for p in a.dir.glob("*.bin"))
    if a.frames:
        frames = [int(x) for x in a.frames.split(",")]
    page = PAGE.read_bytes()
    with sync_playwright() as pw:
        kind = getattr(pw, a.browser)
        args = ["--use-angle=metal", "--enable-gpu", "--mute-audio"] if a.browser == "chromium" else []
        browser = kind.launch(args=args)
        for k in frames:
            server = Image.open(a.dir / f"{k}_server.png").convert("RGB")
            w, h = server.size
            patches = json.loads((a.dir / f"{k}_textures.json").read_text())
            files = {}
            for i, p in enumerate(patches):
                p["url"] = f"http://parity.local/patch/{i}.png"
                files[f"/patch/{i}.png"] = pathlib.Path(p["file"])
            ctx = browser.new_context(viewport={"width": w, "height": h}, device_scale_factor=1)
            pg = ctx.new_page()

            def route(r):
                path = r.request.url.split("parity.local", 1)[1].split("?")[0]
                if path in ("/", "/index.html"):
                    return r.fulfill(body=page, content_type="text/html")
                if path in files:
                    return r.fulfill(body=files[path].read_bytes(), content_type="image/png")
                if path.startswith("/m3d/"):
                    f = a.dir / path.lstrip("/")
                    if f.is_file():
                        kind = "image/png" if path.endswith(".png") else "application/json" if path.endswith(".json") else "application/octet-stream"
                        return r.fulfill(body=f.read_bytes(), content_type=kind)
                    return r.fulfill(status=404, body="")
                if path == "/frame.bin":
                    return r.fulfill(body=(a.dir / f"{k}.bin").read_bytes(), content_type="application/octet-stream")
                return r.fulfill(status=404, body="")

            pg.route("http://parity.local/**", route)
            pg.on("console", lambda m: print("  [page]", m.text) if m.type in ("warning", "error") else None)
            if a.debug:
                pg.add_init_script(f"window.PARITY_DEBUG = {a.debug};")
            pg.goto("http://parity.local/?t=parity")
            res = pg.evaluate(SCRIPT, ["/frame.bin", patches, w, h, a.scale, CLEAR])
            if "error" in res:
                sys.exit(res["error"])
            px = np.frombuffer(base64.b64decode(res["data"]), dtype=np.uint8).reshape(res["h"], res["w"], 4)
            mine = Image.fromarray(np.ascontiguousarray(px[::-1, :, :3]))
            if a.scale != 1:
                mine = mine.resize((w, h), Image.LANCZOS)
            tag = f"{k}_{a.browser}" + (f"_x{a.scale}" if a.scale != 1 else "")
            mine.save(a.dir / f"{tag}_page.png")
            A = np.asarray(server, dtype=np.int16)
            B = np.asarray(mine, dtype=np.int16)
            d = np.abs(A - B).max(axis=2)
            Image.fromarray(np.clip(d * 8, 0, 255).astype(np.uint8)).save(a.dir / f"{tag}_diff.png")
            inside = ""
            if res.get("rects"):
                (a.dir / f"{tag}_models.json").write_text(json.dumps(res["rects"]))
                # the 3D models' own images: the pixels inside their rectangles (frame y is up)
                mask = np.zeros(d.shape, dtype=bool)
                for r in res["rects"]:
                    x0, y0, x1, y1 = (max(0, int(v / 16)) for v in r)
                    mask[max(0, h - y1):max(0, h - y0), x0:x1] = True
                if mask.any():
                    dm = d[mask]
                    inside = f"; in the model images ({mask.sum()} px): mean {dm.mean():.3f}, >8: {100 * (dm > 8).mean():.3f}%, >32: {100 * (dm > 32).mean():.3f}%"
            print(
                f"frame {k} [{a.browser}{' x' + str(a.scale) if a.scale != 1 else ''}]: {res['polys']} polygons, "
                f"{res['draw'].get('models', 0)}/{res['models']} models ({res['draw'].get('modelMs', 0):.1f} ms), "
                f"{res['draw']['draws']} draws, {res['draw']['ms']:.1f} ms; "
                f"mean |diff| {d.mean():.3f}, max {d.max()}, pixels off by >8: {100 * (d > 8).mean():.3f}%, "
                f">32: {100 * (d > 32).mean():.3f}% (webgl2 {res['gl2']}){inside}"
            )
            ctx.close()
        browser.close()


if __name__ == "__main__":
    main()
