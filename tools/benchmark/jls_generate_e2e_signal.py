#!/usr/bin/env python3
"""
Generate an end-to-end latency reference WAV:
- click every second
- unique chirp every 10 seconds
- low-level steady reference tone

All timing is sample-exact in the generated WAV.
"""
from __future__ import annotations
import argparse, json, math, struct, wave
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--duration-s", type=int, default=120)
    ap.add_argument("--sample-rate", type=int, default=48000)
    ap.add_argument("--out", default="jls_e2e_reference.wav")
    ap.add_argument("--manifest", default="jls_e2e_reference.json")
    args = ap.parse_args()

    sr = args.sample_rate
    n = args.duration_s * sr
    data = [0.03 * math.sin(2 * math.pi * 440.0 * i / sr) for i in range(n)]
    events = []

    # 5 ms broadband-ish click, exactly on every integer second.
    click_len = max(1, int(0.005 * sr))
    for sec in range(args.duration_s):
        start = sec * sr
        for j in range(click_len):
            if start + j < n:
                env = 1.0 - j / click_len
                # Alternating sign makes the click easy to identify visually/correlatively.
                data[start + j] += 0.75 * env * (1.0 if j % 2 == 0 else -1.0)
        events.append({"type":"click","time_s":float(sec),"sample":start})

    # 120 ms frequency-coded chirp every 10 seconds.
    chirp_len = int(0.120 * sr)
    for sec in range(0, args.duration_s, 10):
        start = sec * sr
        f0 = 1200.0 + (sec // 10) * 37.0
        f1 = 3200.0 + (sec // 10) * 53.0
        dur = chirp_len / sr
        k = (f1 - f0) / dur
        for j in range(chirp_len):
            if start + j >= n:
                break
            t = j / sr
            phase = 2 * math.pi * (f0 * t + 0.5 * k * t * t)
            env = math.sin(math.pi * min(1.0, j / max(1, chirp_len - 1))) ** 2
            data[start + j] += 0.28 * env * math.sin(phase)
        events.append({
            "type":"chirp","time_s":float(sec),"sample":start,
            "f0_hz":f0,"f1_hz":f1,"duration_ms":120.0
        })

    with wave.open(args.out, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        out = bytearray()
        for x in data:
            v = max(-1.0, min(1.0, x))
            s = int(round(v * 32767.0))
            out.extend(struct.pack("<hh", s, s))
        w.writeframes(out)

    Path(args.manifest).write_text(json.dumps({
        "format":"jls-e2e-reference-v1",
        "sample_rate":sr,
        "channels":2,
        "duration_s":args.duration_s,
        "steady_tone_hz":440.0,
        "events":events
    }, indent=2), encoding="utf-8")
    print(f"Wrote {args.out} and {args.manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
