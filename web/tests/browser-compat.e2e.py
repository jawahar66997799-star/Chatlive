import asyncio
import json
import math
import os
import struct
import subprocess
import time
import urllib.request

import opuslib
import websockets
from playwright.async_api import async_playwright

ROOM = "room_browser_compat_0123456789abcdef0123456789abcd"
HOST_SECRET = "host_browser_compat_0123456789abcdef0123456789abcd"
GUEST_TOKEN = "guest_browser_compat_0123456789abcdef"
PORT = 18081
EPOCH = 0x5E123456789ABCDE
FRAME_SAMPLES = 960
SAMPLE_RATE = 48000
BROWSER_NAME = os.environ.get("JLS_BROWSER", "firefox").lower()

def make_pcm_frame(frame_index: int) -> bytes:
    out = bytearray()
    base = frame_index * FRAME_SAMPLES
    for i in range(FRAME_SAMPLES):
        s = int(0.28 * 32767 * math.sin(2 * math.pi * 523.25 * (base + i) / SAMPLE_RATE))
        out += struct.pack("<hh", s, s)
    return bytes(out)

def make_jls_frame(seq: int, sample_pos: int, payload: bytes) -> bytes:
    b = bytearray(64 + len(payload))
    b[0:4] = b"JLS1"; b[4] = 1; b[5] = 1; b[6] = 1 if seq == 1 else 0; b[7] = 64
    struct.pack_into(">Q", b, 8, EPOCH)
    struct.pack_into(">Q", b, 16, seq)
    struct.pack_into(">Q", b, 24, time.monotonic_ns())
    struct.pack_into(">Q", b, 32, sample_pos)
    struct.pack_into(">Q", b, 40, 0)
    struct.pack_into(">I", b, 48, SAMPLE_RATE)
    struct.pack_into(">H", b, 52, FRAME_SAMPLES)
    b[54] = 2; b[55] = 1; b[56] = 0; b[57] = 0
    struct.pack_into(">H", b, 58, len(payload))
    struct.pack_into(">I", b, 60, 0)
    b[64:] = payload
    return bytes(b)

async def wait_health():
    deadline = time.time() + 15
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/healthz", timeout=1) as r:
                if r.status == 200: return
        except Exception:
            pass
        await asyncio.sleep(.15)
    raise RuntimeError("relay healthz did not become ready")

async def main():
    relay_bin = os.environ.get("JLS_RELAY_BIN", "/tmp/jls-relay")
    env = os.environ.copy()
    env.update({
        "PORT": str(PORT), "JLS_ROOM_ID": ROOM, "JLS_HOST_SECRET": HOST_SECRET,
        "JLS_GUEST_TOKEN": GUEST_TOKEN, "JLS_COMMON_DELAY_MS": "500",
        "JLS_COMMON_DELAY_MIN_MS": "150", "JLS_COMMON_DELAY_MAX_MS": "1000",
        "JLS_JOIN_GUARD_MS": "100", "JLS_MAX_GUESTS": "10",
        "JLS_TRUST_PROXY_HEADERS": "false",
    })
    relay = subprocess.Popen([relay_bin], env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    browser = None
    host = None
    try:
        await wait_health()
        encoder = opuslib.Encoder(SAMPLE_RATE, 2, opuslib.APPLICATION_AUDIO)
        encoder.bitrate = 128000
        host = await websockets.connect(f"ws://127.0.0.1:{PORT}/v1/ws/host", max_size=2**20)
        await host.send(json.dumps({
            "type":"hello_host","v":1,"room_id":ROOM,"host_secret":HOST_SECRET,
            "epoch":EPOCH,"codec":"opus","sample_rate":SAMPLE_RATE,
            "channels":2,"layer":0,"frame_samples":FRAME_SAMPLES
        }))
        ack = json.loads(await asyncio.wait_for(host.recv(), timeout=3))
        assert ack.get("type") == "hello_host_ack", ack

        async with async_playwright() as p:
            browser_type = getattr(p, BROWSER_NAME)
            launch_kwargs = {"headless": True}
            if BROWSER_NAME == "chromium":
                launch_kwargs["args"] = ["--autoplay-policy=user-gesture-required", "--no-sandbox"]
            browser = await browser_type.launch(**launch_kwargs)
            page = await browser.new_page()
            page_errors = []
            page.on("pageerror", lambda e: page_errors.append(str(e)))
            await page.goto(f"http://127.0.0.1:{PORT}/r/{GUEST_TOKEN}", wait_until="domcontentloaded")
            await page.wait_for_selector("#join", state="visible")

            caps = await page.evaluate("""() => ({
              secureContext: self.isSecureContext,
              audioContext: !!(self.AudioContext || self.webkitAudioContext),
              audioWorkletNode: typeof AudioWorkletNode !== 'undefined',
              audioDecoder: typeof AudioDecoder !== 'undefined',
              sharedArrayBuffer: typeof SharedArrayBuffer !== 'undefined',
              crossOriginIsolated: !!self.crossOriginIsolated,
              getOutputTimestamp: !!(self.AudioContext && AudioContext.prototype.getOutputTimestamp),
              outputLatency: !!(self.AudioContext && 'outputLatency' in AudioContext.prototype),
              baseLatency: !!(self.AudioContext && 'baseLatency' in AudioContext.prototype),
            })""")
            assert caps["audioContext"], caps

            join = page.locator("#join")
            if await join.is_enabled():
                await join.click()

            async def send_audio():
                for i in range(1, 181):
                    payload = encoder.encode(make_pcm_frame(i - 1), FRAME_SAMPLES)
                    await host.send(make_jls_frame(i, (i - 1) * FRAME_SAMPLES, payload))
                    await asyncio.sleep(.020)

            sender = asyncio.create_task(send_audio())
            await page.wait_for_function(
                "() => window.__JLS_METRICS__ && window.__JLS_METRICS__.binaryFrames >= 3",
                timeout=10000,
            )
            await page.wait_for_function(
                "() => { const m=window.__JLS_METRICS__; return m && m.decoder !== 'starting' && m.decoder !== 'opus-unavailable' && m.pcmFrames >= 2; }",
                timeout=12000,
            )
            await sender
            metrics = await page.evaluate("() => ({...window.__JLS_METRICS__})")
            reason = await page.locator("#reason").inner_text()
            assert metrics["decoder"] != "opus-unavailable", metrics
            assert metrics["pcmFrames"] >= 2, metrics
            assert metrics["pipelineState"] != "DECODER_FAILED", metrics
            assert reason.strip(), reason
            assert not page_errors, page_errors
            print("BROWSER_COMPAT_SMOKE PASS")
            print(json.dumps({
                "browser": BROWSER_NAME,
                "capabilities": caps,
                "decoder": metrics["decoder"],
                "binaryFrames": metrics["binaryFrames"],
                "pcmFrames": metrics["pcmFrames"],
                "pipelineState": metrics["pipelineState"],
                "pipelineReason": metrics["pipelineReason"],
                "audioState": metrics["audioState"],
                "workletAlive": metrics["workletAlive"],
                "outputRmsDb": metrics["outputRmsDb"],
            }, indent=2))
    finally:
        if host:
            try: await host.close()
            except Exception: pass
        if browser:
            try: await browser.close()
            except Exception: pass
        relay.terminate()
        try:
            out, _ = relay.communicate(timeout=3)
        except subprocess.TimeoutExpired:
            relay.kill(); out, _ = relay.communicate()
        print("RELAY_LOG_BEGIN")
        print(out[-8000:])
        print("RELAY_LOG_END")

if __name__ == "__main__":
    asyncio.run(main())
