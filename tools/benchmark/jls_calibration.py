#!/usr/bin/env python3
"""
Physical speaker calibration for Jawahar Live Sync.

generate:
  creates unique chirp WAVs plus a manifest with shared-timeline schedule times.

analyze:
  reads one reference-microphone WAV, cross-correlates each expected chirp,
  computes relative physical arrival offsets, and writes a calibration profile.

All offsets derived from a real recording should be tagged [MEASURED] by the
operator only after verifying the recording/device mapping. This script itself
prints [ANALYSIS-OF-RECORDING] to avoid fabricating provenance.
"""
from __future__ import annotations

import argparse
import json
import math
import struct
import wave
from array import array
from pathlib import Path
from typing import Dict, List, Sequence, Tuple

TARGET_CORR_HZ = 4000


def write_wav(path: Path, samples: Sequence[float], sr: int) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        frames = bytearray()
        for x in samples:
            v = max(-1.0, min(1.0, x))
            frames.extend(struct.pack("<h", int(round(v * 32767.0))))
        w.writeframes(frames)


def read_wav_mono(path: Path) -> Tuple[int, List[float]]:
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        ch = w.getnchannels()
        width = w.getsampwidth()
        n = w.getnframes()
        raw = w.readframes(n)
    if width != 2:
        raise ValueError("Only PCM16 WAV is supported")
    vals = array("h")
    vals.frombytes(raw)
    if struct.pack("=h", 1) != struct.pack("<h", 1):
        vals.byteswap()
    out = []
    if ch == 1:
        out = [v / 32768.0 for v in vals]
    else:
        for i in range(0, len(vals), ch):
            out.append(sum(vals[i:i+ch]) / (32768.0 * ch))
    return sr, out


def tukey_envelope(i: int, n: int, edge: float = 0.15) -> float:
    if n <= 1:
        return 1.0
    x = i / (n - 1)
    if x < edge:
        return 0.5 * (1.0 - math.cos(math.pi * x / edge))
    if x > 1.0 - edge:
        return 0.5 * (1.0 - math.cos(math.pi * (1.0 - x) / edge))
    return 1.0


def chirp(sr: int, duration_ms: float, f0: float, f1: float) -> List[float]:
    n = int(sr * duration_ms / 1000.0)
    dur = n / sr
    k = (f1 - f0) / max(dur, 1e-9)
    out = []
    for i in range(n):
        t = i / sr
        phase = 2.0 * math.pi * (f0 * t + 0.5 * k * t * t)
        out.append(0.75 * math.sin(phase) * tukey_envelope(i, n))
    return out


def downsample(xs: Sequence[float], factor: int) -> List[float]:
    if factor <= 1:
        return list(xs)
    return [xs[i] for i in range(0, len(xs), factor)]


def normalized_corr_at(signal: Sequence[float], template: Sequence[float], start: int) -> float:
    n = len(template)
    if start < 0 or start + n > len(signal):
        return -1.0
    dot = 0.0
    se = 0.0
    te = 0.0
    for j in range(n):
        a = signal[start + j]
        b = template[j]
        dot += a * b
        se += a * a
        te += b * b
    if se <= 1e-12 or te <= 1e-12:
        return -1.0
    return dot / math.sqrt(se * te)


def find_peak(signal: Sequence[float], template: Sequence[float], lo: int, hi: int) -> Tuple[int, float]:
    hi = min(hi, len(signal) - len(template))
    lo = max(0, lo)
    if hi < lo:
        raise ValueError("Search window outside recording")
    best_i = lo
    best = -2.0
    for i in range(lo, hi + 1):
        c = normalized_corr_at(signal, template, i)
        if c > best:
            best = c
            best_i = i
    return best_i, best


def command_generate(args: argparse.Namespace) -> int:
    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    devices = []
    for i in range(args.devices):
        # Distinct bands keep chirps identifiable when room reflections are present.
        f0 = 900.0 + i * 73.0
        f1 = 2400.0 + i * 91.0
        if f1 >= args.sample_rate * 0.45:
            raise SystemExit("Too many devices for requested sample rate/frequency plan")
        samples = chirp(args.sample_rate, args.duration_ms, f0, f1)
        fn = f"chirp_device_{i:02d}.wav"
        write_wav(out / fn, samples, args.sample_rate)
        devices.append({
            "device_id": f"device-{i:02d}",
            "chirp_file": fn,
            "f0_hz": f0,
            "f1_hz": f1,
            "scheduled_server_ms": args.start_ms + i * args.slot_ms,
            "duration_ms": args.duration_ms,
        })
    manifest = {
        "format": "jls-calibration-manifest-v1",
        "sample_rate": args.sample_rate,
        "reference_device_id": "device-00",
        "instructions": "Schedule each chirp at scheduled_server_ms on the shared server timeline while one microphone records every speaker.",
        "devices": devices,
    }
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(f"Generated {args.devices} chirps and manifest in {out}")
    return 0


def load_template(base: Path, item: Dict[str, object], corr_sr: int) -> List[float]:
    sr, xs = read_wav_mono(base / str(item["chirp_file"]))
    factor = max(1, round(sr / corr_sr))
    return downsample(xs, factor)


def command_analyze(args: argparse.Namespace) -> int:
    manifest_path = Path(args.manifest)
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    rec_sr, rec = read_wav_mono(Path(args.recording))
    factor = max(1, round(rec_sr / TARGET_CORR_HZ))
    corr_sr = rec_sr / factor
    rec_ds = downsample(rec, factor)

    devices = manifest["devices"]
    base = manifest_path.parent
    ref_id = args.reference or manifest.get("reference_device_id", devices[0]["device_id"])
    ref_item = next(d for d in devices if d["device_id"] == ref_id)
    ref_tpl = load_template(base, ref_item, int(corr_sr))

    # First anchor: full-recording search for the reference chirp.
    ref_index, ref_score = find_peak(rec_ds, ref_tpl, 0, max(0, len(rec_ds) - len(ref_tpl)))
    ref_detected_ms = 1000.0 * ref_index / corr_sr
    ref_sched_ms = float(ref_item["scheduled_server_ms"])

    rows = []
    for item in devices:
        tpl = load_template(base, item, int(corr_sr))
        sched = float(item["scheduled_server_ms"])
        expected = ref_detected_ms + (sched - ref_sched_ms)
        span = args.search_window_ms
        lo = int((expected - span) * corr_sr / 1000.0)
        hi = int((expected + span) * corr_sr / 1000.0)
        idx, score = find_peak(rec_ds, tpl, lo, hi)
        detected_ms = 1000.0 * idx / corr_sr
        residual = detected_ms - sched
        rows.append({
            "device_id": item["device_id"],
            "scheduled_server_ms": sched,
            "detected_recording_ms": detected_ms,
            "correlation": score,
            "residual_recording_minus_schedule_ms": residual,
        })

    ref_row = next(r for r in rows if r["device_id"] == ref_id)
    ref_residual = ref_row["residual_recording_minus_schedule_ms"]
    for row in rows:
        relative = row["residual_recording_minus_schedule_ms"] - ref_residual
        row["relative_speaker_arrival_offset_ms"] = relative
        # Positive relative means this speaker arrived late at the microphone.
        row["recommended_playout_advance_ms"] = relative
        row["quality"] = "ok" if row["correlation"] >= args.min_correlation else "review"

    profile = {
        "format": "jls-acoustic-calibration-profile-v1",
        "provenance": "[ANALYSIS-OF-RECORDING]",
        "recording": str(Path(args.recording)),
        "reference_device_id": ref_id,
        "reference_correlation": ref_score,
        "input_latency_note": "One recorder is shared by all devices, so constant recorder input latency cancels in relative offsets.",
        "geometry_note": "Offsets are physical arrival at this microphone position; moving devices or changing audio routes can invalidate the profile.",
        "devices": rows,
        "manual_nudge_ms": {r["device_id"]: 0.0 for r in rows},
    }
    Path(args.out).write_text(json.dumps(profile, indent=2), encoding="utf-8")
    print(json.dumps(profile, indent=2))
    if any(r["quality"] != "ok" for r in rows):
        print("WARNING: one or more chirps had weak correlation; repeat or inspect before tagging [MEASURED].")
        return 2
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    sp = ap.add_subparsers(dest="cmd", required=True)

    g = sp.add_parser("generate")
    g.add_argument("--devices", type=int, default=20)
    g.add_argument("--sample-rate", type=int, default=48000)
    g.add_argument("--duration-ms", type=float, default=120.0)
    g.add_argument("--start-ms", type=float, default=2000.0)
    g.add_argument("--slot-ms", type=float, default=550.0)
    g.add_argument("--out-dir", default="calibration_chirps")
    g.set_defaults(func=command_generate)

    a = sp.add_parser("analyze")
    a.add_argument("--manifest", required=True)
    a.add_argument("--recording", required=True)
    a.add_argument("--reference", default="")
    a.add_argument("--search-window-ms", type=float, default=220.0)
    a.add_argument("--min-correlation", type=float, default=0.20)
    a.add_argument("--out", default="jls_calibration_profile.json")
    a.set_defaults(func=command_analyze)

    args = ap.parse_args()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
