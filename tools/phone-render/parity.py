# crforge - https://github.com/voonhous/crforge
# SPDX-License-Identifier: Apache-2.0
"""Pixel parity of the phone server's draw-list stream (docs/phone-server.md).

Replays the parity frames PhoneRenderBench writes (the frame as a key frame, the texture patches
made so far) in the phone page's own player (index.html's crforgeDraw) in a headless browser, and
compares the picture with the server's own (its frame buffer), writing both and an amplified
difference beside them.

    ./gradlew :desktop:phoneRenderBench -Pcrforge.assetSource=cdn --args="OUT 1500,3000"
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
PAGE = ROOT / "desktop/src/main/resources/org/crforge/desktop/server/index.html"
CLEAR = [0.11, 0.13, 0.12]

SCRIPT = """
async ([frame, patches, w, h, scale, clear]) => {
  const c = document.createElement('canvas');
  c.width = w * scale; c.height = h * scale;
  c.style.cssText = 'position:fixed;left:0;top:0;z-index:99;width:' + w + 'px;height:' + h + 'px';
  document.body.appendChild(c);
  const D = window.crforgeDraw;
  if (!D.init(c)) return { error: 'no WebGL' };
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
  const r = D.draw(f, w, h, clear);
  const px = D.readPixels();
  let s = '';
  for (let i = 0; i < px.px.length; i += 0x8000) s += String.fromCharCode.apply(null, px.px.subarray(i, i + 0x8000));
  return { w: px.w, h: px.h, data: btoa(s), draw: r, gl2: D.gl2, polys: f.count, ua: navigator.userAgent };
}
"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("dir", type=pathlib.Path)
    ap.add_argument("--browser", default="chromium")
    ap.add_argument("--scale", type=int, default=1)
    a = ap.parse_args()
    frames = sorted(int(p.stem) for p in a.dir.glob("*.bin"))
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
                if path == "/frame.bin":
                    return r.fulfill(body=(a.dir / f"{k}.bin").read_bytes(), content_type="application/octet-stream")
                return r.fulfill(status=404, body="")

            pg.route("http://parity.local/**", route)
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
            print(
                f"frame {k} [{a.browser}{' x' + str(a.scale) if a.scale != 1 else ''}]: {res['polys']} polygons, "
                f"{res['draw']['draws']} draws, {res['draw']['ms']:.1f} ms; "
                f"mean |diff| {d.mean():.3f}, max {d.max()}, pixels off by >8: {100 * (d > 8).mean():.3f}%, "
                f">32: {100 * (d > 32).mean():.3f}% (webgl2 {res['gl2']})"
            )
            ctx.close()
        browser.close()


if __name__ == "__main__":
    main()
