import asyncio
import json
import math
import os
import struct
import subprocess
import time
import urllib.request
from pathlib import Path

import opuslib
import websockets
from playwright.async_api import async_playwright

ROOM = "room_browser_e2e_0123456789abcdef0123456789abcdef"
HOST_SECRET = "host_browser_e2e_0123456789abcdef0123456789abcdef"
GUEST_TOKEN = "guest_browser_e2e_0123456789abcdef"
PORT = 18080
EPOCH = 0x6F123456789ABCDE
FRAME_SAMPLES = 960
SAMPLE_RATE = 48000

def make_pcm_frame(frame_index: int) -> bytes:
    out = bytearray()
    base = frame_index * FRAME_SAMPLES
    for i in range(FRAME_SAMPLES):
        s = int(0.30 * 32767 * math.sin(2 * math.pi * 440.0 * (base + i) / SAMPLE_RATE))
        out += struct.pack("<hh", s, s)
    return bytes(out)

def make_jls_frame(seq: int, sample_pos: int, payload: bytes) -> bytes:
    b = bytearray(64 + len(payload))
    b[0:4] = b"JLS1"
    b[4] = 1
    b[5] = 1
    b[6] = 1 if seq == 1 else 0
    b[7] = 64
    struct.pack_into(">Q", b, 8, EPOCH)
    struct.pack_into(">Q", b, 16, seq)
    struct.pack_into(">Q", b, 24, time.monotonic_ns())
    struct.pack_into(">Q", b, 32, sample_pos)
    struct.pack_into(">Q", b, 40, 0)
    struct.pack_into(">I", b, 48, SAMPLE_RATE)
    struct.pack_into(">H", b, 52, FRAME_SAMPLES)
    b[54] = 2
    b[55] = 1
    b[56] = 0
    b[57] = 0
    struct.pack_into(">H", b, 58, len(payload))
    struct.pack_into(">I", b, 60, 0)
    b[64:] = payload
    return bytes(b)

async def wait_health():
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/healthz", timeout=1) as r:
                if r.status == 200:
                    return
        except Exception:
            pass
        await asyncio.sleep(0.15)
    raise RuntimeError("relay healthz did not become ready")

async def main():
    relay_bin = os.environ.get("JLS_RELAY_BIN", "/tmp/jls-relay")
    env = os.environ.copy()
    env.update({
        "PORT": str(PORT),
        "JLS_ROOM_ID": ROOM,
        "JLS_HOST_SECRET": HOST_SECRET,
        "JLS_GUEST_TOKEN": GUEST_TOKEN,
        "JLS_COMMON_DELAY_MS": "220",
        "JLS_COMMON_DELAY_MIN_MS": "120",
        "JLS_COMMON_DELAY_MAX_MS": "800",
        "JLS_JOIN_GUARD_MS": "80",
        "JLS_MAX_GUESTS": "10",
        "JLS_TRUST_PROXY_HEADERS": "false",
    })
    relay = subprocess.Popen([relay_bin], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    browser = None
    try:
        await wait_health()
        encoder = opuslib.Encoder(SAMPLE_RATE, 2, opuslib.APPLICATION_AUDIO)
        encoder.bitrate = 144000

        host = await websockets.connect(f"ws://127.0.0.1:{PORT}/v1/ws/host", max_size=2**20)
        await host.send(json.dumps({
            "type": "hello_host", "v": 1, "room_id": ROOM, "host_secret": HOST_SECRET,
            "epoch": EPOCH, "codec": "opus", "sample_rate": SAMPLE_RATE,
            "channels": 2, "layer": 0, "frame_samples": FRAME_SAMPLES
        }))
        ack = json.loads(await asyncio.wait_for(host.recv(), timeout=3))
        assert ack.get("type") == "hello_host_ack", ack

        async with async_playwright() as p:
            browser = await p.chromium.launch(
                headless=True,
                args=["--autoplay-policy=user-gesture-required", "--no-sandbox"]
            )
            page = await browser.new_page()
            page_errors = []
            console_errors = []
            page.on("pageerror", lambda e: page_errors.append(str(e)))
            page.on("console", lambda m: console_errors.append(m.text) if m.type == "error" else None)

            await page.goto(f"http://127.0.0.1:{PORT}/r/{GUEST_TOKEN}", wait_until="domcontentloaded")
            await page.wait_for_selector("#join", state="visible")
            join = page.locator("#join")
            if await join.is_enabled():
                await join.click()
            else:
                # Some Chromium/headless environments allow Web Audio to start
                # immediately. In that legal case the UI already shows LISTENING.
                await page.wait_for_function(
                    "() => window.__JLS_METRICS__ && window.__JLS_METRICS__.audioState === 'running'",
                    timeout=5000,
                )

            async def send_audio():
                for i in range(1, 401):
                    pcm = make_pcm_frame(i - 1)
                    payload = encoder.encode(pcm, FRAME_SAMPLES)
                    await host.send(make_jls_frame(i, (i - 1) * FRAME_SAMPLES, payload))
                    await asyncio.sleep(0.020)

            sender = asyncio.create_task(send_audio())

            await page.wait_for_function(
                "() => window.__JLS_METRICS__ && window.__JLS_METRICS__.decodedRmsDb > -60",
                timeout=10000,
            )
            await page.wait_for_function(
                "() => { const m=window.__JLS_METRICS__; return m && m.audioState==='running' && m.workletAlive && m.workletQuanta>5 && m.scheduledFrames>3 && m.outputRmsDb>-80; }",
                timeout=12000,
            )
            metrics = await page.evaluate("() => ({...window.__JLS_METRICS__})")
            diag = await page.locator("#diagText").inner_text()
            await sender

            assert metrics["scheduledFrames"] > 3, metrics
            assert metrics["workletAlive"] is True, metrics
            assert metrics["workletQuanta"] > 5, metrics
            assert metrics["outputRmsDb"] > -80, metrics
            assert metrics["schedulerErrors"] == 0, metrics
            assert metrics["workletProcessorErrors"] == 0, metrics
            assert not page_errors, page_errors
            print("BROWSER_PLAYOUT_E2E PASS")
            print(json.dumps({
                "audioState": metrics["audioState"],
                "decodedRmsDb": metrics["decodedRmsDb"],
                "outputRmsDb": metrics["outputRmsDb"],
                "scheduledFrames": metrics["scheduledFrames"],
                "workletQuanta": metrics["workletQuanta"],
                "fallbackPlayback": metrics["fallbackPlayback"],
                "playoutGate": metrics["playoutGate"],
                "diag": diag,
                "consoleErrors": console_errors,
            }, indent=2))
        await host.close()
    finally:
        if browser:
            try:
                await browser.close()
            except Exception:
                pass
        relay.terminate()
        try:
            out, _ = relay.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            relay.kill()
            out, _ = relay.communicate()
        print("RELAY_LOG_BEGIN")
        print(out[-12000:])
        print("RELAY_LOG_END")

if __name__ == "__main__":
    asyncio.run(main())
