#!/usr/bin/env python3
"""
Jawahar Live Sync deterministic multi-listener network/clock simulator.

Evidence: every numeric result printed by this program is [SIMULATED].
No real-device or production measurements are implied.

Two models:
  current  - approximates the current relay/web prototype:
             fixed initial min-RTT clock offset, no periodic drift model,
             fixed common delay, no resampler, no hard-resync controller,
             no output-latency calibration.
  adaptive - reference validation model for the intended architecture:
             periodic clock refresh, drift correction, calibrated output
             latency, bounded resampler correction, and hard resync after
             large phase errors. This is NOT production code.
"""
from __future__ import annotations

import argparse
import json
import math
import random
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

EVIDENCE = "[SIMULATED]"
PACKET_MS = 20.0


def percentile(values: Sequence[float], q: float) -> float:
    if not values:
        return 0.0
    xs = sorted(values)
    if len(xs) == 1:
        return float(xs[0])
    pos = (len(xs) - 1) * q
    lo = int(math.floor(pos))
    hi = int(math.ceil(pos))
    if lo == hi:
        return float(xs[lo])
    w = pos - lo
    return float(xs[lo] * (1.0 - w) + xs[hi] * w)


def stats(values: Sequence[float]) -> Dict[str, float]:
    if not values:
        return {"p50": 0.0, "p95": 0.0, "p99": 0.0, "max": 0.0}
    return {
        "p50": percentile(values, 0.50),
        "p95": percentile(values, 0.95),
        "p99": percentile(values, 0.99),
        "max": max(values),
    }


@dataclass
class NetProfile:
    name: str
    up_ms: float
    down_ms: float
    jitter_ms: float
    loss: float
    burst_start: float
    burst_continue: float
    retransmit_rtt_mult: Tuple[float, float]


PROFILES = {
    "wifi": NetProfile("wifi", 12.0, 16.0, 5.0, 0.002, 0.0008, 0.35, (1.0, 2.0)),
    "good_cellular": NetProfile("good_cellular", 32.0, 42.0, 14.0, 0.006, 0.0015, 0.45, (1.0, 3.0)),
    "poor_cellular": NetProfile("poor_cellular", 92.0, 125.0, 45.0, 0.025, 0.004, 0.62, (1.0, 5.0)),
}


@dataclass
class Outage:
    start_ms: float
    duration_ms: float

    @property
    def end_ms(self) -> float:
        return self.start_ms + self.duration_ms


@dataclass
class Listener:
    ident: int
    profile: NetProfile
    clock_offset_ms: float
    clock_drift_ppm: float
    audio_drift_ppm: float
    up_asym_ms: float
    down_asym_ms: float
    output_latency_ms: float
    calibration_error_ms: float
    outage: Optional[Outage] = None
    best_clock_offset_ms: float = 0.0
    best_clock_rtt_ms: float = math.inf
    burst_bad: bool = False
    hol_until_ms: float = 0.0

    def local_perf(self, server_ms: float) -> float:
        return self.clock_offset_ms + server_ms * (1.0 + self.clock_drift_ppm / 1_000_000.0)

    def up_delay(self, rng: random.Random) -> float:
        return max(0.2, self.profile.up_ms + self.up_asym_ms + rng.gauss(0.0, self.profile.jitter_ms))

    def down_delay(self, rng: random.Random) -> float:
        return max(0.2, self.profile.down_ms + self.down_asym_ms + rng.gauss(0.0, self.profile.jitter_ms))

    def maybe_loss_event(self, rng: random.Random) -> bool:
        if self.burst_bad:
            if rng.random() > self.profile.burst_continue:
                self.burst_bad = False
            return True
        if rng.random() < self.profile.burst_start:
            self.burst_bad = True
            return True
        return rng.random() < self.profile.loss


@dataclass
class ListenerMetrics:
    ident: int
    profile: str
    clock_offset_ms: float
    clock_drift_ppm: float
    output_latency_ms: float
    clock_estimate_error_ms_at_start: float = 0.0
    late_packets: int = 0
    total_packets: int = 0
    underruns: int = 0
    underrun_ms: float = 0.0
    hard_resyncs: int = 0
    continuity: float = 1.0
    buffer_samples_ms: List[float] = field(default_factory=list)
    correction_samples_ppm: List[float] = field(default_factory=list)


def make_listeners(count: int, scenario: str, rng: random.Random, include_outages: bool = True) -> List[Listener]:
    listeners: List[Listener] = []
    kinds = ["wifi", "good_cellular", "poor_cellular"]
    for i in range(count):
        kind = kinds[i % len(kinds)] if scenario == "mixed" else scenario
        p = PROFILES[kind]
        clock_offset = rng.uniform(-250.0, 250.0)
        clock_drift = rng.uniform(-100.0, 100.0)
        audio_drift = rng.uniform(-100.0, 100.0)
        asym_limit = {"wifi": 5.0, "good_cellular": 18.0, "poor_cellular": 55.0}[kind]
        up_asym = rng.uniform(-asym_limit, asym_limit)
        down_asym = rng.uniform(-asym_limit, asym_limit)
        out_range = {
            "wifi": (18.0, 65.0),
            "good_cellular": (25.0, 95.0),
            "poor_cellular": (35.0, 145.0),
        }[kind]
        output_latency = rng.uniform(*out_range)
        calibration_error = rng.gauss(0.0, 0.45)

        outage = None
        if include_outages:
            if i % 7 == 1:
                outage = Outage(60_000.0 + i * 13.0, 500.0)
            elif i % 7 == 3:
                outage = Outage(120_000.0 + i * 11.0, 1_000.0)
            elif i % 7 == 5:
                outage = Outage(180_000.0 + i * 7.0, 3_000.0)

        listeners.append(Listener(
            ident=i,
            profile=p,
            clock_offset_ms=clock_offset,
            clock_drift_ppm=clock_drift,
            audio_drift_ppm=audio_drift,
            up_asym_ms=up_asym,
            down_asym_ms=down_asym,
            output_latency_ms=output_latency,
            calibration_error_ms=calibration_error,
            outage=outage,
        ))
    return listeners


def initial_clock_sync(listener: Listener, rng: random.Random, samples: int = 12) -> None:
    for k in range(samples):
        send_server = k * 120.0
        t0_local = listener.local_perf(send_server)
        up = listener.up_delay(rng)
        server_reply = send_server + up
        down = listener.down_delay(rng)
        recv_server = server_reply + down
        t3_local = listener.local_perf(recv_server)
        local_rtt = t3_local - t0_local
        est_offset = server_reply - ((t0_local + t3_local) / 2.0)
        if local_rtt < listener.best_clock_rtt_ms:
            listener.best_clock_rtt_ms = local_rtt
            listener.best_clock_offset_ms = est_offset


def periodic_clock_estimate(listener: Listener, server_ms: float, rng: random.Random) -> Tuple[float, float]:
    best_rtt = math.inf
    best_offset = 0.0
    for k in range(8):
        send_server = server_ms + k * 20.0
        t0_local = listener.local_perf(send_server)
        up = listener.up_delay(rng)
        srv = send_server + up
        down = listener.down_delay(rng)
        recv_server = srv + down
        t3_local = listener.local_perf(recv_server)
        rtt = t3_local - t0_local
        est = srv - (t0_local + t3_local) / 2.0
        if rtt < best_rtt:
            best_rtt = rtt
            best_offset = est
    return best_offset, best_rtt


def network_arrival(listener: Listener, server_send_ms: float, rng: random.Random) -> float:
    delay = listener.down_delay(rng)
    arrival = server_send_ms + delay

    # Baseline is WSS/TCP. Loss is approximated as retransmission delay and
    # head-of-line blocking, not as application-frame disappearance.
    if listener.maybe_loss_event(rng):
        rough_rtt = max(1.0, listener.profile.up_ms + listener.profile.down_ms)
        mult = rng.uniform(*listener.profile.retransmit_rtt_mult)
        listener.hol_until_ms = max(listener.hol_until_ms, arrival + rough_rtt * mult)
    arrival = max(arrival, listener.hol_until_ms)

    if listener.outage and listener.outage.start_ms <= arrival < listener.outage.end_ms:
        arrival = listener.outage.end_ms
        listener.hol_until_ms = max(listener.hol_until_ms, arrival)
    return arrival


def summarize_listener_metrics(ms: List[ListenerMetrics]) -> Dict[str, object]:
    late_pct = [100.0 * m.late_packets / max(1, m.total_packets) for m in ms]
    underrun_counts = [float(m.underruns) for m in ms]
    underrun_ms = [m.underrun_ms for m in ms]
    hard = [float(m.hard_resyncs) for m in ms]
    continuity_pct = [100.0 * m.continuity for m in ms]
    corrections = [abs(v) for m in ms for v in m.correction_samples_ppm]
    buffers = [v for m in ms for v in m.buffer_samples_ms]
    return {
        "late_packet_pct": stats(late_pct),
        "underrun_count": stats(underrun_counts),
        "underrun_ms": stats(underrun_ms),
        "hard_resync_count": stats(hard),
        "continuity_pct": stats(continuity_pct),
        "abs_resampler_correction_ppm": stats(corrections),
        "buffer_depth_ms": stats(buffers),
    }


def simulate(
    scenario: str,
    count: int,
    duration_s: float,
    seed: int,
    mode: str,
    target_delay_ms: float,
    include_outages: bool,
) -> Dict[str, object]:
    rng = random.Random(seed ^ sum(ord(c) for c in scenario) ^ (17 if mode == "adaptive" else 0))
    listeners = make_listeners(count, scenario, rng, include_outages=include_outages)
    for l in listeners:
        initial_clock_sync(l, rng)

    metrics = [
        ListenerMetrics(
            ident=l.ident,
            profile=l.profile.name,
            clock_offset_ms=l.clock_offset_ms,
            clock_drift_ppm=l.clock_drift_ppm,
            output_latency_ms=l.output_latency_ms,
        )
        for l in listeners
    ]
    for l, m in zip(listeners, metrics):
        true_offset_at_0 = -l.clock_offset_ms
        m.clock_estimate_error_ms_at_start = l.best_clock_offset_ms - true_offset_at_0

    frame_count = int(duration_s * 1000.0 / PACKET_MS)
    previous_end: List[Optional[float]] = [None] * count
    skew_samples: List[float] = []
    current_offsets = [l.best_clock_offset_ms for l in listeners]
    next_clock_refresh = [5_000.0] * count
    recovering = [False] * count

    # Acoustic calibration measures total relative physical arrival bias while all
    # devices schedule on the same server timeline. It therefore absorbs both
    # static clock-estimator bias and device output latency at the calibration
    # condition. device-0 is the arbitrary acoustic reference.
    calibration_advance_ms = [0.0] * count
    if mode == "adaptive":
        tcal = 2_000.0
        physical_bias = []
        for l, est_offset in zip(listeners, current_offsets):
            target_local = tcal - est_offset
            digital_server = (target_local - l.clock_offset_ms) / (1.0 + l.clock_drift_ppm / 1_000_000.0)
            physical_bias.append(digital_server + l.output_latency_ms - tcal)
        ref_bias = physical_bias[0]
        for i, l in enumerate(listeners):
            calibration_advance_ms[i] = (physical_bias[i] - ref_bias) + l.calibration_error_ms

    for frame_idx in range(frame_count):
        server_frame_ms = frame_idx * PACKET_MS
        physical_speaker_starts: List[float] = []

        for idx, (l, m) in enumerate(zip(listeners, metrics)):
            if mode == "adaptive" and server_frame_ms >= next_clock_refresh[idx]:
                refreshed, _ = periodic_clock_estimate(l, server_frame_ms, rng)
                # Smooth refreshes so jitter in one clock sample cannot step audio.
                current_offsets[idx] = 0.85 * current_offsets[idx] + 0.15 * refreshed
                next_clock_refresh[idx] += 5_000.0

            arrival = network_arrival(l, server_frame_ms, rng)
            local_arrival = l.local_perf(arrival)
            est_offset = current_offsets[idx]
            target_server = server_frame_ms + target_delay_ms
            target_local_perf = target_server - est_offset
            delay_local = target_local_perf - local_arrival

            if mode == "adaptive":
                target_local_perf -= calibration_advance_ms[idx]
                delay_local = target_local_perf - local_arrival

            digital_wait = max(0.0, delay_local) / (1.0 + l.audio_drift_ppm / 1_000_000.0)
            scheduled_digital_start = arrival + digital_wait

            if delay_local < 0.0:
                m.late_packets += 1
            m.total_packets += 1
            m.buffer_samples_ms.append(max(0.0, delay_local))

            if mode == "adaptive":
                correction = max(-250.0, min(250.0, -l.audio_drift_ppm))
                m.correction_samples_ppm.append(correction)
                effective_rate = 1.0 + (l.audio_drift_ppm + correction) / 1_000_000.0
                # A severely stale TCP/HOL frame is discarded. Keeping it would
                # permanently drag this listener behind the shared timeline.
                if delay_local < -80.0:
                    if not recovering[idx]:
                        m.hard_resyncs += 1
                        recovering[idx] = True
                    m.underrun_ms += PACKET_MS
                    continue
                if recovering[idx]:
                    previous_end[idx] = None
                    recovering[idx] = False
            else:
                m.correction_samples_ppm.append(0.0)
                effective_rate = 1.0 + l.audio_drift_ppm / 1_000_000.0

            start_digital = scheduled_digital_start
            prev_end = previous_end[idx]
            if prev_end is not None:
                phase_gap = start_digital - prev_end
                if mode == "adaptive" and phase_gap < -80.0:
                    # Cut stale backlog and jump forward to this frame.
                    m.hard_resyncs += 1
                elif start_digital < prev_end:
                    start_digital = prev_end
                if start_digital > prev_end + 0.05:
                    m.underruns += 1
                    m.underrun_ms += start_digital - prev_end

            frame_duration = PACKET_MS / effective_rate
            previous_end[idx] = start_digital + frame_duration
            physical_start = start_digital + l.output_latency_ms
            physical_speaker_starts.append(physical_start)

        # Skew is defined only for source frames rendered by every listener.
        # Dropped frames are penalized separately in continuity/hard-resync stats.
        if len(physical_speaker_starts) == count:
            skew_samples.append(max(physical_speaker_starts) - min(physical_speaker_starts))

    total_audio_ms = duration_s * 1000.0
    for m in metrics:
        m.continuity = max(0.0, 1.0 - m.underrun_ms / max(1.0, total_audio_ms))

    result = {
        "evidence": EVIDENCE,
        "seed": seed,
        "mode": mode,
        "scenario": scenario,
        "listeners": count,
        "duration_s": duration_s,
        "packet_ms": PACKET_MS,
        "target_delay_ms": target_delay_ms,
        "outages": "on" if include_outages else "off",
        "listener_to_listener_skew_ms": stats(skew_samples),
        "aggregate": summarize_listener_metrics(metrics),
        "listeners_detail": [asdict(m) | {
            "buffer_summary_ms": stats(m.buffer_samples_ms),
            "correction_summary_ppm": stats([abs(v) for v in m.correction_samples_ppm]),
            "late_pct": 100.0 * m.late_packets / max(1, m.total_packets),
            "continuity_pct": 100.0 * m.continuity,
        } for m in metrics],
    }
    for d in result["listeners_detail"]:
        d.pop("buffer_samples_ms", None)
        d.pop("correction_samples_ppm", None)
    return result


def markdown(results: List[Dict[str, object]]) -> str:
    lines = [
        "# JLS Network/Clock Simulation Report",
        "",
        "All quantitative values in this report are **[SIMULATED]**.",
        "",
        "| Mode | Scenario | Outages | N | Skew p50 ms | p95 | p99 | max | Late p95 % | Buffer p95 ms | Continuity p50 % | Hard resync max |",
        "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for r in results:
        s = r["listener_to_listener_skew_ms"]
        a = r["aggregate"]
        cont = a["continuity_pct"]
        lines.append(
            f"| {r['mode']} | {r['scenario']} | {r['outages']} | {r['listeners']} | "
            f"{s['p50']:.2f} | {s['p95']:.2f} | {s['p99']:.2f} | {s['max']:.2f} | "
            f"{a['late_packet_pct']['p95']:.3f} | {a['buffer_depth_ms']['p95']:.1f} | "
            f"{cont['p50']:.4f} | {a['hard_resync_count']['max']:.0f} |"
        )
    lines += [
        "",
        "Interpretation: current approximates the checked-in raw-PCM browser prototype; "
        "adaptive is a reference validation model, not proof that production implements those controls.",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listeners", type=int, default=20)
    ap.add_argument("--duration-s", type=float, default=300.0)
    ap.add_argument("--seed", type=int, default=20261001)
    ap.add_argument("--target-delay-ms", type=float, default=400.0)
    ap.add_argument("--outages", choices=["on", "off"], default="on")
    ap.add_argument("--mode", choices=["current", "adaptive", "both"], default="both")
    ap.add_argument("--scenario", choices=["mixed", "wifi", "good_cellular", "poor_cellular", "all"], default="all")
    ap.add_argument("--json-out", default="")
    ap.add_argument("--md-out", default="")
    args = ap.parse_args()

    if args.listeners < 20:
        raise SystemExit("Requirement: --listeners must be >= 20")

    modes = ["current", "adaptive"] if args.mode == "both" else [args.mode]
    scenarios = ["mixed", "wifi", "good_cellular", "poor_cellular"] if args.scenario == "all" else [args.scenario]
    results = []
    for mode in modes:
        for scenario in scenarios:
            results.append(simulate(
                scenario=scenario,
                count=args.listeners,
                duration_s=args.duration_s,
                seed=args.seed,
                mode=mode,
                target_delay_ms=args.target_delay_ms,
                include_outages=(args.outages == "on"),
            ))

    payload = {"evidence": EVIDENCE, "results": results}
    print(markdown(results))
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(payload, indent=2), encoding="utf-8")
    if args.md_out:
        Path(args.md_out).write_text(markdown(results), encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
