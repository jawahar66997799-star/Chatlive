import asyncio
import json
import os
import struct
import time
import urllib.error
import urllib.request

import websockets

base = os.environ["PUBLIC_BASE"].rstrip("/")
wsbase = ("wss://" + base[len("https://"):]) if base.startswith("https://") else base
room = os.environ["ROOM"]
host_secret = os.environ["HOST_SECRET"]
guest_token = os.environ["GUEST_TOKEN"]
expect_protected_metrics = os.environ.get("EXPECT_PROTECTED_METRICS", "1") == "1"
wire = ">4sBBBBQQQQQIHBBBBHI"


def http_get(path):
    req = urllib.request.Request(base + path, headers={"User-Agent": "JLS-final-smoke/1"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        return resp.status, dict(resp.headers.items()), resp.read()


def smoke_http_assets():
    status, headers, body = http_get("/healthz")
    health = json.loads(body)
    if status != 200 or health.get("ok") is not True or health.get("protocol") != 1:
        raise RuntimeError("healthz failed")
    instance = health.get("server_instance_id")
    if not isinstance(instance, str) or len(instance) < 16:
        raise RuntimeError("healthz missing server_instance_id")
    csp = headers.get("Content-Security-Policy", "")
    if "default-src 'self'" not in csp or "object-src 'none'" not in csp:
        raise RuntimeError("restrictive CSP missing")

    checks = {
        "guest_html": f"/r/{guest_token}",
        "player": "/player.js",
        "decoder_worker": "/decoder-worker.js",
        "opus_index": "/vendor/libopus-wasm/index.js",
        "opus_generated": "/vendor/libopus-wasm/generated/libopus.generated.mjs",
    }
    sizes = {}
    contents = {}
    for name, path in checks.items():
        s, _, data = http_get(path)
        if s != 200 or not data:
            raise RuntimeError(f"asset failed: {name}")
        sizes[name] = len(data)
        contents[name] = data

    if b"./generated/libopus.generated.mjs" not in contents["opus_index"]:
        raise RuntimeError("vendored Opus import graph incomplete")
    if b"cdn.jsdelivr.net" in contents["player"] or b"cdn.jsdelivr.net" in contents["decoder_worker"]:
        raise RuntimeError("runtime CDN decoder reference found")

    metrics_protected = None
    try:
        http_get("/metrics")
        metrics_protected = False
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            metrics_protected = True
        else:
            raise
    if expect_protected_metrics and metrics_protected is not True:
        raise RuntimeError("metrics endpoint is not protected")

    return instance, csp, sizes, metrics_protected


async def main():
    instance, csp, asset_sizes, metrics_protected = smoke_http_assets()
    # Deliberately above JavaScript's Number.MAX_SAFE_INTEGER so the public
    # control plane must preserve it as an exact decimal string.
    epoch = 0x7F123456789ABCDE
    async with websockets.connect(wsbase + "/v1/ws/host", open_timeout=15, ping_interval=None) as host:
        await host.send(json.dumps({
            "type":"hello_host","v":1,"room_id":room,"host_secret":host_secret,
            "epoch":epoch,"codec":"opus","sample_rate":48000,"channels":2,
            "layer":0,"frame_samples":960
        }))
        host_ack = json.loads(await asyncio.wait_for(host.recv(), 10))
        if host_ack.get("server_instance_id") != instance:
            raise RuntimeError("host ack server_instance_id mismatch")

        async with websockets.connect(wsbase + "/v1/ws/guest/" + guest_token, open_timeout=15, ping_interval=None) as guest:
            state = json.loads(await asyncio.wait_for(guest.recv(), 10))
            if state.get("server_instance_id") != instance:
                raise RuntimeError("guest state server_instance_id mismatch")
            if state.get("epoch") != str(epoch):
                raise RuntimeError(f"guest state epoch is not exact JSON-safe string: {state.get('epoch')!r}")

            t0 = time.monotonic_ns()
            await guest.send(json.dumps({"type":"clock_req","v":1,"id":"smoke","t0_guest_ns":t0}))
            clock = None
            binary = 0
            clock_deadline = time.monotonic() + 10
            while time.monotonic() < clock_deadline and clock is None:
                msg = await asyncio.wait_for(guest.recv(), 2)
                if isinstance(msg, bytes):
                    binary += 1
                else:
                    parsed = json.loads(msg)
                    if parsed.get("type") == "clock_resp":
                        clock = parsed
            if clock is None:
                raise RuntimeError("clock_resp not received")
            if clock.get("server_instance_id") != instance:
                raise RuntimeError("clock server_instance_id mismatch")

            payload = bytes(120)
            for seq in range(1, 13):
                hdr = struct.pack(
                    wire,b"JLS1",1,1,0,64,epoch,seq,time.monotonic_ns(),
                    (seq-1)*960,0,48000,960,2,1,0,0,len(payload),0
                )
                await host.send(hdr + payload)
                await asyncio.sleep(0.02)

            deadline = time.monotonic() + 3
            while time.monotonic() < deadline and binary < 1:
                msg = await asyncio.wait_for(guest.recv(), 1)
                if isinstance(msg, bytes):
                    binary += 1

            print("PUBLIC_WSS_SMOKE", json.dumps({
                "host_ack": host_ack.get("type") == "hello_host_ack",
                "guest_state": state.get("type") == "state",
                "clock_resp": clock.get("type") == "clock_resp",
                "binary_frames": binary,
                "epoch": epoch,
                "server_instance_id": instance,
                "metrics_protected": metrics_protected,
                "csp_present": bool(csp),
                "asset_sizes": asset_sizes,
            }, sort_keys=True), flush=True)


asyncio.run(main())
