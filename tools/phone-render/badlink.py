# crforge - https://github.com/voonhous/crforge
# SPDX-License-Identifier: Apache-2.0
"""A bad Wi-Fi link between the phone page and the phone server, for tests (docs/phone-server.md).

A TCP proxy: the server's bytes to the page are held to a rate (a token bucket), every byte is
delayed by a base latency, and every few seconds the link stalls for a few hundred milliseconds to
over a second, as a congested 2.4 GHz Wi-Fi does.

    uv run python tools/phone-render/badlink.py --listen 8767 --to 8766 --mbit 5 --latency 20 \
        --spikes 300,1300 --every 2,6
"""

import argparse
import asyncio
import random
import time


async def pipe(reader, writer, rate, latency, stalls, label, counters):
    """Copies reader to writer: each chunk released latency after it was read (pipelined), after
    any stall, at most rate bytes a second (rate 0: unlimited)."""
    queue = asyncio.Queue()

    async def release():
        next_free = 0.0
        try:
            while True:
                due, data = await queue.get()
                if data is None:
                    break
                if stalls is not None:
                    due = max(due, stalls["until"])
                if rate:
                    next_free = max(next_free, due) + len(data) / rate
                    due = next_free
                wait = due - time.monotonic()
                if wait > 0:
                    await asyncio.sleep(wait)
                if stalls is not None and stalls["until"] > time.monotonic():
                    await asyncio.sleep(stalls["until"] - time.monotonic())
                writer.write(data)
                await writer.drain()
                counters[label] += len(data)
        except (ConnectionError, asyncio.CancelledError):
            pass
        finally:
            try:
                writer.close()
            except Exception:
                pass

    task = asyncio.create_task(release())
    try:
        while True:
            data = await reader.read(16384)
            if not data:
                break
            await queue.put((time.monotonic() + latency, data))
    except (ConnectionError, asyncio.CancelledError):
        pass
    await queue.put((0, None))
    await task


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", type=int, default=8767)
    ap.add_argument("--to", type=int, default=8766)
    ap.add_argument("--mbit", type=float, default=5.0)
    ap.add_argument("--latency", type=float, default=20.0, help="ms each way")
    ap.add_argument("--spikes", default="300,1300", help="stall ms, min,max (empty: none)")
    ap.add_argument("--every", default="2,6", help="seconds between stalls, min,max")
    a = ap.parse_args()
    rate = a.mbit * 1e6 / 8
    lat = a.latency / 1000
    stalls = {"until": 0.0}
    counters = {"down": 0, "up": 0}

    async def stall_loop():
        if not a.spikes:
            return
        lo, hi = (float(x) for x in a.spikes.split(","))
        e0, e1 = (float(x) for x in a.every.split(","))
        while True:
            await asyncio.sleep(random.uniform(e0, e1))
            ms = random.uniform(lo, hi)
            stalls["until"] = time.monotonic() + ms / 1000
            print(f"stall {ms:.0f} ms", flush=True)

    async def handle(cr, cw):
        try:
            sr, sw = await asyncio.open_connection("127.0.0.1", a.to)
        except OSError:
            cw.close()
            return
        await asyncio.gather(
            pipe(sr, cw, rate, lat, stalls, "down", counters),
            pipe(cr, sw, 0, lat, None, "up", counters),
        )

    async def report():
        last = 0
        while True:
            await asyncio.sleep(5)
            d = counters["down"]
            print(f"down {(d - last) * 8 / 5 / 1e6:.2f} Mbit/s", flush=True)
            last = d

    server = await asyncio.start_server(handle, "127.0.0.1", a.listen)
    asyncio.create_task(stall_loop())
    asyncio.create_task(report())
    print(f"bad link on 127.0.0.1:{a.listen} -> {a.to}: {a.mbit} Mbit/s, {a.latency} ms, stalls {a.spikes} ms", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
