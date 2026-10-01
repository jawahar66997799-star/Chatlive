import asyncio
import json
import os
import struct
import time
import urllib.request

import websockets

HOST = os.environ["TARGET_HOST"]
ROOM = os.environ["ROOM"]
HOST_SECRET = os.environ["HOST_SECRET"]
GUEST_TOKEN = os.environ["GUEST_TOKEN"]
METRICS_TOKEN = os.environ.get("METRICS_TOKEN", "")
BASE = f"ws://{HOST}:8080"
WIRE = ">4sBBBBQQQQQIHBBBBHI"

async def host_task(stop, ready):
    try:
        async with websockets.connect(BASE + "/v1/ws/host", max_size=65536, ping_interval=None, open_timeout=15) as ws:
            epoch = (time.monotonic_ns() & ((1 << 63) - 1)) or 1
            await ws.send(json.dumps({
                "type": "hello_host", "v": 1, "room_id": ROOM, "host_secret": HOST_SECRET,
                "epoch": epoch, "codec": "opus", "sample_rate": 48000, "channels": 2,
                "layer": 0, "frame_samples": 960
            }))
            ack = await asyncio.wait_for(ws.recv(), 10)
            print("HOST_ACK", ack, flush=True)
            ready.set()
            seq = 0
            sample = 0
            payload = bytes(120)
            next_t = time.perf_counter()
            while not stop.is_set():
                cap = time.monotonic_ns()
                hdr = struct.pack(
                    WIRE, b"JLS1", 1, 1, 0, 64, epoch, seq, cap, sample, 0,
                    48000, 960, 2, 1, 0, 0, len(payload), 0
                )
                await ws.send(hdr + payload)
                seq += 1
                sample += 960
                next_t += 0.020
                await asyncio.sleep(max(0, next_t - time.perf_counter()))
    except Exception as exc:
        print("HOST_ERROR", type(exc).__name__, str(exc), flush=True)
        ready.set()

async def guest(index, duration):
    uri = f"{BASE}/v1/ws/guest/{GUEST_TOKEN}"
    try:
        async with websockets.connect(uri, max_size=65536, ping_interval=None, open_timeout=20, close_timeout=2) as ws:
            await asyncio.wait_for(ws.recv(), 10)
            await ws.send(json.dumps({
                "type": "clock_req", "v": 1, "id": str(index),
                "t0_guest_ns": time.monotonic_ns()
            }))
            end = time.perf_counter() + duration
            binary_frames = 0
            text_frames = 1
            while time.perf_counter() < end:
                remain = end - time.perf_counter()
                try:
                    msg = await asyncio.wait_for(ws.recv(), min(1.0, max(0.01, remain)))
                    if isinstance(msg, bytes):
                        binary_frames += 1
                    else:
                        text_frames += 1
                except asyncio.TimeoutError:
                    pass
            return 1, binary_frames, text_frames, ""
    except Exception as exc:
        return 0, 0, 0, f"{type(exc).__name__}:{str(exc)[:120]}"

async def scenario(n, duration):
    started = time.perf_counter()
    results = await asyncio.gather(*(guest(i, duration) for i in range(n)))
    elapsed = time.perf_counter() - started
    connected = sum(x[0] for x in results)
    binary_frames = sum(x[1] for x in results)
    errors = {}
    for item in results:
        if not item[0]:
            errors[item[3]] = errors.get(item[3], 0) + 1
    print("SCENARIO", json.dumps({
        "guests": n,
        "connected": connected,
        "failed": n - connected,
        "elapsed_s": round(elapsed, 3),
        "binary_frames": binary_frames,
        "errors": errors,
    }, sort_keys=True), flush=True)

async def main():
    stop = asyncio.Event()
    ready = asyncio.Event()
    host = asyncio.create_task(host_task(stop, ready))
    await asyncio.wait_for(ready.wait(), 20)
    await asyncio.sleep(1)
    for n, duration in [(1, 8), (10, 8), (50, 8), (100, 8), (1000, 12)]:
        await scenario(n, duration)
        await asyncio.sleep(1)
    stop.set()
    await asyncio.sleep(0.5)
    if not host.done():
        host.cancel()
    try:
        print("RELAY_METRICS_BEGIN", flush=True)
        req = urllib.request.Request(f"http://{HOST}:8080/metrics")
        if METRICS_TOKEN:
            req.add_header("Authorization", "Bearer " + METRICS_TOKEN)
        print(urllib.request.urlopen(req, timeout=10).read().decode(), flush=True)
        print("RELAY_METRICS_END", flush=True)
    except Exception as exc:
        print("METRICS_ERROR", repr(exc), flush=True)

asyncio.run(main())
