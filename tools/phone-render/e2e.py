# crforge - https://github.com/voonhous/crforge
# SPDX-License-Identifier: Apache-2.0
"""Plays the phone server's page in a headless browser shaped like a phone and reports what it got.

Opens the page at an iPhone's size (402x874 CSS pixels at 3x), taps to play, plays a card now and
then, and reports the stream as the page received it (WebSocket bytes a second, frames, texture
patches) and as it showed it (its telemetry: frames shown a second, the longest gap between them,
draw time). With --throttle MBIT,MS Chromium's network emulation holds the link to that rate and
latency; run it through badlink.py for delay spikes.

    uv run --with playwright python tools/phone-render/e2e.py http://127.0.0.1:8766/?t=TOKEN \
        [--seconds 60] [--browser chromium|webkit] [--picture draw|high|medium|low] [--throttle 5,100]
        [--screenshot FILE]
"""

import argparse
import json
import re
import statistics
import time

from playwright.sync_api import sync_playwright


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("url")
    ap.add_argument("--seconds", type=float, default=60)
    ap.add_argument("--browser", default="chromium")
    ap.add_argument("--picture", default="draw")
    ap.add_argument("--throttle", default="")
    ap.add_argument("--screenshot", default="")
    a = ap.parse_args()
    recv = []  # (time, bytes, type)
    teles = []
    with sync_playwright() as pw:
        kind = getattr(pw, a.browser)
        args = ["--use-angle=metal", "--enable-gpu", "--ignore-gpu-blocklist", "--mute-audio"] if a.browser == "chromium" else []
        browser = kind.launch(args=args)
        ctx = browser.new_context(viewport={"width": 402, "height": 874}, device_scale_factor=3,
                                  is_mobile=a.browser == "chromium", has_touch=True)
        page = ctx.new_page()
        page.add_init_script(f"try {{ localStorage.setItem('crforge.picture', '{a.picture}'); }} catch (e) {{}}")
        # silent: no Web Audio (a headless browser can play through the computer's speakers)
        page.add_init_script("window.AudioContext = undefined; window.webkitAudioContext = undefined;")
        if a.throttle:
            if a.browser != "chromium":
                raise SystemExit("--throttle needs chromium")
            mbit, ms = (float(x) for x in a.throttle.split(","))
            cdp = ctx.new_cdp_session(page)
            cdp.send("Network.enable")
            cdp.send("Network.emulateNetworkConditions", {
                "offline": False, "latency": ms,
                "downloadThroughput": mbit * 1e6 / 8, "uploadThroughput": mbit * 1e6 / 8})

        def on_ws(ws):
            def got(payload):
                t = time.monotonic()
                if isinstance(payload, (bytes, bytearray)):
                    recv.append((t, len(payload), payload[0]))
                else:
                    recv.append((t, len(payload.encode()), 0))

            def sent(payload):
                if isinstance(payload, str) and '"tele"' in payload:
                    teles.append(json.loads(payload)["s"])

            ws.on("framereceived", got)
            ws.on("framesent", sent)

        page.on("websocket", on_ws)
        page.on("console", lambda m: m.type in ("error", "warning") and print("console:", m.text))
        t0 = time.monotonic()
        page.goto(a.url)
        page.wait_for_selector("#startBtn:not([disabled])", timeout=120000)
        print(f"start button ready after {time.monotonic() - t0:.1f} s")
        page.click("#startBtn")
        tapped = 0
        while time.monotonic() - t0 < a.seconds:
            time.sleep(4)
            # a card, then a spot on our side of the arena
            box = page.evaluate("(() => { const c = [...document.querySelectorAll('canvas')].find(c => c.style.display !== 'none'); const r = c.getBoundingClientRect(); return [r.left, r.top, r.width, r.height]; })()")
            x0, y0, w, h = box
            slot = 0.30 + 0.15 * (tapped % 4)
            page.mouse.click(x0 + w * slot, y0 + h * 0.925)
            time.sleep(0.3)
            page.mouse.click(x0 + w * (0.3 + 0.4 * (tapped % 2)), y0 + h * 0.62)
            tapped += 1
        if a.screenshot:
            page.screenshot(path=a.screenshot)
        browser.close()
    # the stream as received
    t_end = recv[-1][0] if recv else t0
    total = sum(b for _, b, _ in recv)
    by_type = {}
    for _, b, k in recv:
        by_type.setdefault(k, [0, 0])
        by_type[k][0] += 1
        by_type[k][1] += b
    names = {0: "text", 1: "jpeg", 2: "draw list", 3: "patch"}
    print(f"received {total / 1e6:.2f} MB in {t_end - t0:.0f} s")
    for k, (n, b) in sorted(by_type.items()):
        print(f"  {names.get(k, k)}: {n} messages, {b / 1e6:.2f} MB, {b / max(1, n) / 1024:.1f} KB each")
    frames = [(t, b) for t, b, k in recv if k in (1, 2)]
    if frames:
        sizes = [b for _, b in frames]
        print(f"  frame size avg {statistics.mean(sizes) / 1024:.2f} KB, max {max(sizes) / 1024:.1f} KB")
        # Mbit/s a second, after the first 15 s (the textures' first burst)
        start = frames[0][0] + 15
        secs = {}
        for t, b, _ in recv:
            if t >= start:
                secs[int(t - start)] = secs.get(int(t - start), 0) + b
        if secs:
            rates = [v * 8 / 1e6 for v in secs.values()]
            print(f"  after 15 s: {statistics.mean(rates):.2f} Mbit/s avg, {max(rates):.2f} max (a second)")
    # what the page showed
    shown, gaps, mbit = [], [], []
    for s in teles[2:]:
        m = re.search(r"shown ([\d.]+) fps, longest gap (\d+) ms", s)
        if m:
            shown.append(float(m.group(1)))
            gaps.append(int(m.group(2)))
    if shown:
        print(f"page: shown {statistics.mean(shown):.1f} fps avg (min {min(shown):.1f}); longest gap {max(gaps)} ms,"
              f" median of 2 s windows {statistics.median(gaps)} ms")
    for s in teles[-2:]:
        print("tele:", s[:400])


if __name__ == "__main__":
    main()
