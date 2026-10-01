#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import random
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

EVIDENCE = "[SIMULATED]"
HEAD_SHA = "0ed0da1d66dd1ed22e5baae0fc6fa46c827a3bfb"
FRAME_MS = 20.0

ROOM_D_INITIAL = 400.0
ROOM_D_MIN = 150.0
ROOM_D_MAX = 1000.0
RELAY_STATS_TTL_MS = 10000.0
RELAY_UPDATE_MS = 1000.0
RELAY_HYSTERESIS_MS = 10.0
RELAY_UP_MS_S = 0.25
RELAY_DOWN_MS_S = 0.15
GUEST_ROOM_UP_MS_S = 0.25
GUEST_ROOM_DOWN_MS_S = 0.15
JOIN_GUARD_MS = 150.0
STATS_INTERVAL_MS = 2000.0

REC_HISTORY = 160
REC_SAFETY_MS = 35.0
REC_UP_MS_S = 25.0
REC_DOWN_MS_S = 6.0
REC_LATE_UP_MS_S = 60.0
REC_LATE_BOOST_MS = 5000.0
HARD_RESYNC_MS = 100.0


def quantile(values: Sequence[float], q: float) -> float:
    if not values:
        return 0.0
    xs = sorted(values)
    if len(xs) == 1:
        return float(xs[0])
    p = (len(xs) - 1) * min(1.0, max(0.0, q))
    lo, hi = int(math.floor(p)), int(math.ceil(p))
    if lo == hi:
        return float(xs[lo])
    f = p - lo
    return float(xs[lo] * (1.0 - f) + xs[hi] * f)


def stats(values: Sequence[float]) -> Dict[str, float]:
    if not values:
        return {"p50": 0.0, "p95": 0.0, "p99": 0.0, "max": 0.0}
    return {
        "p50": quantile(values, 0.50),
        "p95": quantile(values, 0.95),
        "p99": quantile(values, 0.99),
        "max": max(values),
    }


@dataclass(frozen=True)
class Profile:
    name: str
    age_mean_ms: float
    jitter_sd_ms: float
    decode_mean_ms: float
    decode_sd_ms: float
    clock_conf_ms: float
    clock_residual_sd_ms: float
    output_residual_sd_ms: float


PROFILES = {
    "wifi": Profile("wifi", 32.0, 5.0, 3.0, 0.8, 10.0, 1.5, 2.5),
    "good": Profile("good", 85.0, 18.0, 4.0, 1.0, 28.0, 4.0, 3.5),
    "poor": Profile("poor", 235.0, 65.0, 6.0, 1.5, 95.0, 12.0, 5.0),
    "j50": Profile("j50", 75.0, 50.0, 4.0, 1.0, 45.0, 7.0, 3.5),
    "j200": Profile("j200", 120.0, 200.0, 5.0, 1.3, 110.0, 22.0, 4.0),
}


@dataclass
class GuestAdaptive:
    current_ms: float = ROOM_D_INITIAL
    target_ms: float = ROOM_D_INITIAL
    network_age: deque = field(default_factory=lambda: deque(maxlen=REC_HISTORY))
    decode: deque = field(default_factory=lambda: deque(maxlen=REC_HISTORY))
    last_ms: Optional[float] = None
    late_boost_until_ms: float = -1e18
    observe_count: int = 0

    def tick(self, now_ms: float) -> float:
        if self.last_ms is None:
            self.last_ms = now_ms
            return self.current_ms
        dt = max(0.0, min(2.0, (now_ms - self.last_ms) / 1000.0))
        self.last_ms = now_ms
        if self.target_ms > self.current_ms:
            rate = REC_LATE_UP_MS_S if now_ms < self.late_boost_until_ms else REC_UP_MS_S
            self.current_ms = min(self.target_ms, self.current_ms + rate * dt)
        else:
            self.current_ms = max(self.target_ms, self.current_ms - REC_DOWN_MS_S * dt)
        return self.current_ms

    def observe(self, age_ms: float, decode_ms: float, clock_conf_ms: float, now_ms: float) -> None:
        if 0 <= age_ms < 5000:
            self.network_age.append(age_ms)
        if 0 <= decode_ms < 1000:
            self.decode.append(decode_ms)
        self.observe_count += 1
        # Production recomputes on every decoded frame. Recomputing every five
        # 20ms samples preserves the same 160-frame history and 2s telemetry
        # cadence while reducing benchmark-only CPU cost. The controller slew
        # still ticks on every 20ms media step.
        if len(self.network_age) >= 8 and self.observe_count % 5 == 0:
            self.target_ms = max(
                ROOM_D_MIN,
                min(
                    ROOM_D_MAX,
                    quantile(self.network_age, 0.95)
                    + (quantile(self.decode, 0.95) if self.decode else 0.0)
                    + max(0.0, clock_conf_ms)
                    + REC_SAFETY_MS,
                ),
            )
        self.tick(now_ms)

    def mark_late(self, now_ms: float, severity_ms: float) -> None:
        bump = min(100.0, 20.0 + max(0.0, severity_ms) * 0.25)
        self.target_ms = max(
            ROOM_D_MIN,
            min(ROOM_D_MAX, max(self.target_ms, self.current_ms + bump)),
        )
        self.late_boost_until_ms = max(self.late_boost_until_ms, now_ms + REC_LATE_BOOST_MS)


@dataclass
class GuestRoomD:
    current_ms: float = ROOM_D_INITIAL
    target_ms: float = ROOM_D_INITIAL
    last_ms: Optional[float] = None

    def on_state(self, relay_d_ms: float, now_ms: float, initialize: bool = False) -> None:
        self.target_ms = max(ROOM_D_MIN, min(ROOM_D_MAX, relay_d_ms))
        if self.last_ms is None or initialize:
            self.current_ms = self.target_ms
            self.last_ms = now_ms

    def tick(self, now_ms: float) -> float:
        if self.last_ms is None:
            self.last_ms = now_ms
            return self.current_ms
        dt = max(0.0, min(1.0, (now_ms - self.last_ms) / 1000.0))
        self.last_ms = now_ms
        if self.target_ms > self.current_ms:
            self.current_ms = min(self.target_ms, self.current_ms + GUEST_ROOM_UP_MS_S * dt)
        else:
            self.current_ms = max(self.target_ms, self.current_ms - GUEST_ROOM_DOWN_MS_S * dt)
        return self.current_ms


@dataclass
class RelayRoomD:
    current_ms: float = ROOM_D_INITIAL
    last_adjust_ms: float = 0.0
    stats_map: Dict[int, Tuple[float, float]] = field(default_factory=dict)
    last_target_ms: float = ROOM_D_INITIAL
    direction: int = 0
    direction_changes: int = 0

    def reset_process(self) -> None:
        self.current_ms = ROOM_D_INITIAL
        self.last_adjust_ms = 0.0
        self.stats_map.clear()
        self.last_target_ms = ROOM_D_INITIAL
        self.direction = 0

    def remove(self, lid: int) -> None:
        self.stats_map.pop(lid, None)

    def reset_idle(self) -> None:
        # Mirrors Room.cleanupIfIdle at current production HEAD: adaptive
        # listener history is cleared and commonDelay returns to configured baseline.
        self.current_ms = ROOM_D_INITIAL
        self.last_adjust_ms = 0.0
        self.stats_map.clear()
        self.last_target_ms = ROOM_D_INITIAL
        self.direction = 0

    def update(self, lid: int, recommended_ms: float, now_ms: float):
        self.stats_map[lid] = (
            max(ROOM_D_MIN, min(ROOM_D_MAX, recommended_ms)),
            now_ms,
        )
        expired = [
            k
            for k, (_, updated) in self.stats_map.items()
            if now_ms > updated and now_ms - updated > RELAY_STATS_TTL_MS
        ]
        for k in expired:
            self.stats_map.pop(k, None)
        changed, target = self.adjust(now_ms)
        return changed, target, expired

    def target(self) -> float:
        if not self.stats_map:
            return self.current_ms
        vals = sorted(v[0] for v in self.stats_map.values())
        idx = max(0, min(len(vals) - 1, int(math.ceil(0.95 * len(vals))) - 1))
        return max(ROOM_D_MIN, min(ROOM_D_MAX, vals[idx]))

    def adjust(self, now_ms: float):
        if not self.stats_map:
            return False, self.current_ms
        if self.last_adjust_ms == 0.0:
            self.last_adjust_ms = now_ms
            self.last_target_ms = self.target()
            return False, self.last_target_ms
        if now_ms <= self.last_adjust_ms or now_ms - self.last_adjust_ms < RELAY_UPDATE_MS:
            return False, self.last_target_ms

        target = self.target()
        self.last_target_ms = target
        cur = self.current_ms
        if abs(target - cur) < RELAY_HYSTERESIS_MS:
            self.last_adjust_ms = now_ms
            return False, target

        elapsed_s = (now_ms - self.last_adjust_ms) / 1000.0
        self.last_adjust_ms = now_ms
        if target > cur:
            nxt = min(target, cur + RELAY_UP_MS_S * elapsed_s)
            direction = 1
        else:
            nxt = max(target, cur - RELAY_DOWN_MS_S * elapsed_s)
            direction = -1
        nxt = max(ROOM_D_MIN, min(ROOM_D_MAX, nxt))
        if nxt == cur:
            return False, target
        if self.direction and direction != self.direction:
            self.direction_changes += 1
        self.direction = direction
        self.current_ms = nxt
        return True, target


@dataclass
class Listener:
    lid: int
    rng: random.Random
    role: str = "normal"
    online: bool = True
    send_stats: bool = True
    adaptive: GuestAdaptive = field(default_factory=GuestAdaptive)
    room_d: GuestRoomD = field(default_factory=GuestRoomD)
    next_stats_ms: float = STATS_INTERVAL_MS
    static_clock_bias_ms: float = 0.0
    static_output_bias_ms: float = 0.0
    frames: int = 0
    late_frames: int = 0
    hard_resyncs: int = 0
    underruns: int = 0
    underrun_duration_ms: float = 0.0
    offline_duration_ms: float = 0.0
    in_underrun: bool = False
    clock_conf_samples: List[float] = field(default_factory=list)
    recommendation_samples: List[float] = field(default_factory=list)
    local_d_samples: List[float] = field(default_factory=list)
    hol_until_ms: float = 0.0
    server_restart_resets: int = 0
    reconnects: int = 0


def make_listener(lid: int, seed: int, initial_profile: str, role: str = "normal") -> Listener:
    rng = random.Random(seed * 1000003 + lid * 7919 + sum(ord(c) for c in initial_profile))
    p = PROFILES[initial_profile]
    l = Listener(lid=lid, rng=rng, role=role)
    l.static_clock_bias_ms = rng.gauss(0.0, p.clock_residual_sd_ms)
    l.static_output_bias_ms = rng.gauss(0.0, p.output_residual_sd_ms)
    l.next_stats_ms = STATS_INTERVAL_MS + ((lid * 97) % 1900)
    l.room_d.on_state(ROOM_D_INITIAL, 0.0, initialize=True)
    return l


def profile_name(scenario: str, listener: Listener, now_ms: float) -> str:
    t = now_ms / 1000.0
    lid = listener.lid
    if scenario == "clean_wifi":
        return "wifi"
    if scenario == "good_cellular":
        return "good"
    if scenario == "poor_cellular":
        return "poor"
    if scenario == "mixed":
        return ("wifi", "good", "poor")[lid % 3]
    if scenario == "jitter50":
        return "j50"
    if scenario == "jitter200":
        return "j200"
    if scenario in ("packet_loss", "short_outage", "reconnect_live_edge"):
        return "good"
    if scenario == "wifi_to_good":
        return "wifi" if t < 60 else "good"
    if scenario == "good_to_poor":
        return "good" if t < 60 else "poor"
    if scenario == "clean_to_j50":
        return "wifi" if t < 60 else "j50"
    if scenario == "clean_to_j200":
        return "wifi" if t < 60 else "j200"
    if scenario in ("one_poor_joins", "several_poor_join"):
        return "poor" if listener.role == "poor_joiner" else "wifi"
    if scenario in ("poor_leave_disconnect", "poor_stats_stale"):
        return "poor" if listener.role == "poor_member" else "wifi"
    if scenario == "server_restart":
        return "poor"
    return "wifi"


def sample_path(listener: Listener, scenario: str, now_ms: float):
    p = PROFILES[profile_name(scenario, listener, now_ms)]
    age = max(0.0, listener.rng.gauss(p.age_mean_ms, p.jitter_sd_ms))
    decode = max(0.0, listener.rng.gauss(p.decode_mean_ms, p.decode_sd_ms))

    if scenario == "packet_loss" and 60000 <= now_ms < 120000:
        if listener.rng.random() < 0.05:
            listener.hol_until_ms = max(listener.hol_until_ms, now_ms + listener.rng.uniform(120, 450))
        if listener.hol_until_ms > now_ms:
            age += listener.hol_until_ms - now_ms

    physical_error = (
        listener.static_clock_bias_ms
        + listener.static_output_bias_ms
        + listener.rng.gauss(0.0, p.clock_residual_sd_ms * 0.20)
    )
    return age, decode, p.clock_conf_ms, physical_error


def init_scenario(scenario: str, count: int, seed: int) -> List[Listener]:
    listeners = []
    if scenario in ("poor_leave_disconnect", "poor_stats_stale"):
        healthy = max(1, count - 4)
        for i in range(count):
            role = "normal" if i < healthy else "poor_member"
            listeners.append(make_listener(i, seed, "wifi" if role == "normal" else "poor", role))
        return listeners
    for i in range(count):
        dummy = Listener(i, random.Random(0))
        listeners.append(make_listener(i, seed, profile_name(scenario, dummy, 0.0)))
    return listeners


def add_joiners(listeners, n, seed, now_ms, relay_d):
    base = max((l.lid for l in listeners), default=-1) + 1
    for j in range(n):
        l = make_listener(base + j, seed, "poor", "poor_joiner")
        l.next_stats_ms = now_ms + STATS_INTERVAL_MS + ((l.lid * 97) % 1900)
        l.room_d = GuestRoomD()
        l.room_d.on_state(relay_d, now_ms, initialize=True)
        listeners.append(l)


def online_rule(scenario: str, l: Listener, now_ms: float) -> bool:
    if scenario == "short_outage":
        return not (60000 <= now_ms < 61250)
    if scenario == "reconnect_live_edge" and l.lid < 3:
        return not (60000 <= now_ms < 61250)
    if scenario == "poor_leave_disconnect" and l.role == "poor_member":
        return now_ms < 60000
    return True


def run_scenario(scenario: str, count: int, duration_s: float, seed: int) -> Dict[str, object]:
    relay = RelayRoomD()
    listeners = init_scenario(scenario, count, seed)

    relay_d_hist = []
    relay_target_hist = []
    deadline_spread_hist = []
    acoustic_skew_hist = []
    tracking_error_hist = []
    clock_conf_hist = []

    room_broadcasts = 0
    expired_ids = []
    disconnect_removed = []
    pre_join_target = None
    max_target_after_join = None
    server_restart_done = False
    restart_pre_d = None
    restart_post_d = None
    idle_cleanup_done = False
    idle_cleanup_pre_d = None
    idle_cleanup_post_d = None
    resume_guard_margin = []
    joined = False
    previous_online = {l.lid: True for l in listeners}

    transition_ms = 60000.0 if scenario in {
        "wifi_to_good", "good_to_poor", "clean_to_j50", "clean_to_j200",
        "one_poor_joins", "several_poor_join", "poor_leave_disconnect",
        "poor_stats_stale", "packet_loss", "short_outage", "reconnect_live_edge",
    } else None

    total_steps = int(duration_s * 1000.0 / FRAME_MS)
    for step in range(total_steps):
        now_ms = step * FRAME_MS

        if scenario == "one_poor_joins" and not joined and now_ms >= 60000:
            pre_join_target = relay.last_target_ms
            add_joiners(listeners, 1, seed, now_ms, relay.current_ms)
            joined = True
        elif scenario == "several_poor_join" and not joined and now_ms >= 60000:
            pre_join_target = relay.last_target_ms
            add_joiners(listeners, 4, seed, now_ms, relay.current_ms)
            joined = True

        if scenario == "server_restart" and not server_restart_done and now_ms >= 90000:
            restart_pre_d = relay.current_ms
            relay.reset_process()
            restart_post_d = relay.current_ms
            for l in listeners:
                l.server_restart_resets += 1
                l.room_d.on_state(relay.current_ms, now_ms, initialize=False)
                l.next_stats_ms = now_ms + STATS_INTERVAL_MS + ((l.lid * 97) % 1900)
            server_restart_done = True

        if scenario == "idle_cleanup_reset" and not idle_cleanup_done and now_ms >= 90000:
            idle_cleanup_pre_d = relay.current_ms
            relay.reset_idle()
            idle_cleanup_post_d = relay.current_ms
            # cleanup is only legal with zero guests; these model listeners are
            # treated as a fresh later join after cleanup and receive baseline D.
            for l in listeners:
                l.adaptive = GuestAdaptive()
                l.room_d = GuestRoomD()
                l.room_d.on_state(relay.current_ms, now_ms, initialize=True)
                l.next_stats_ms = now_ms + STATS_INTERVAL_MS + ((l.lid * 97) % 1900)
            idle_cleanup_done = True

        frame_deadlines = []
        frame_physical = []

        for l in list(listeners):
            desired_online = online_rule(scenario, l, now_ms)
            was_online = previous_online.get(l.lid, True)

            if was_online and not desired_online:
                if scenario == "poor_leave_disconnect" and l.role == "poor_member":
                    relay.remove(l.lid)
                    disconnect_removed.append((l.lid, now_ms))
                if scenario in ("short_outage", "reconnect_live_edge"):
                    l.reconnects += 1

            if not was_online and desired_online:
                l.room_d.on_state(relay.current_ms, now_ms, initialize=False)
                l.next_stats_ms = now_ms + STATS_INTERVAL_MS + ((l.lid * 97) % 1900)
                resume_guard_margin.append(JOIN_GUARD_MS + l.room_d.current_ms - relay.current_ms)

            previous_online[l.lid] = desired_online
            l.online = desired_online
            if not l.online:
                # Deliberate membership removal is not an audio continuity failure.
                if not (scenario == "poor_leave_disconnect" and l.role == "poor_member"):
                    l.offline_duration_ms += FRAME_MS
                l.in_underrun = False
                continue

            if scenario == "poor_stats_stale" and l.role == "poor_member" and now_ms >= 60000:
                l.send_stats = False

            age, decode, clock_conf, physical_error = sample_path(l, scenario, now_ms)
            l.clock_conf_samples.append(clock_conf)
            clock_conf_hist.append(clock_conf)

            l.adaptive.observe(age, decode, clock_conf, now_ms)
            local_d = l.room_d.tick(now_ms)
            l.recommendation_samples.append(l.adaptive.current_ms)
            l.local_d_samples.append(local_d)

            lateness = age + decode - local_d
            l.frames += 1
            if lateness > 0:
                l.late_frames += 1
                l.adaptive.mark_late(now_ms, lateness)
                if not l.in_underrun:
                    l.underruns += 1
                    l.in_underrun = True
                l.underrun_duration_ms += FRAME_MS
            else:
                l.in_underrun = False

            if lateness >= HARD_RESYNC_MS:
                l.hard_resyncs += 1

            frame_deadlines.append(local_d)
            frame_physical.append(local_d + physical_error)

            if l.send_stats and now_ms + 1e-9 >= l.next_stats_ms:
                changed, target, expired = relay.update(l.lid, l.adaptive.current_ms, now_ms)
                expired_ids.extend((eid, now_ms) for eid in expired)
                l.next_stats_ms += STATS_INTERVAL_MS
                if changed:
                    room_broadcasts += 1
                    for g in listeners:
                        if g.online:
                            g.room_d.on_state(relay.current_ms, now_ms, initialize=False)
                if joined:
                    max_target_after_join = target if max_target_after_join is None else max(max_target_after_join, target)

        if frame_deadlines:
            deadline_spread_hist.append(max(frame_deadlines) - min(frame_deadlines))
            tracking_error_hist.extend(abs(relay.current_ms - d) for d in frame_deadlines)
        if len(frame_physical) >= 2:
            acoustic_skew_hist.append(max(frame_physical) - min(frame_physical))

        relay_d_hist.append(relay.current_ms)
        relay_target_hist.append(relay.last_target_ms)

    participants = [l for l in listeners if l.frames or l.offline_duration_ms]
    total_frames = sum(l.frames for l in participants)
    late_frames = sum(l.late_frames for l in participants)
    underrun_events = sum(l.underruns for l in participants)
    underrun_ms = sum(l.underrun_duration_ms for l in participants)
    offline_ms = sum(l.offline_duration_ms for l in participants)
    hard = sum(l.hard_resyncs for l in participants)
    potential_audio_ms = max(1.0, duration_s * 1000.0 * len(participants))
    continuity = max(0.0, 1.0 - (underrun_ms + offline_ms) / potential_audio_ms)

    underbuffer_ms = 0.0
    recovery_to_10_s = None
    if transition_ms is not None:
        start_idx = int(transition_ms / FRAME_MS)
        for i in range(start_idx, len(relay_d_hist)):
            gap = relay_target_hist[i] - relay_d_hist[i]
            if gap > 10:
                underbuffer_ms += FRAME_MS
            elif recovery_to_10_s is None:
                recovery_to_10_s = (i * FRAME_MS - transition_ms) / 1000.0

    tail_n = max(1, int(60000 / FRAME_MS))
    tail = relay_d_hist[-tail_n:]
    total_variation = sum(abs(b - a) for a, b in zip(relay_d_hist, relay_d_hist[1:]))

    join_target_delta = None
    if pre_join_target is not None and max_target_after_join is not None:
        join_target_delta = max_target_after_join - pre_join_target

    return {
        "evidence": EVIDENCE,
        "scenario": scenario,
        "duration_s": duration_s,
        "initial_listener_count": count,
        "final_listener_count": len(listeners),
        "relay_D_ms": stats(relay_d_hist),
        "relay_D_final_ms": relay.current_ms,
        "relay_D_last60_range_ms": max(tail) - min(tail) if tail else 0.0,
        "relay_D_total_variation_ms": total_variation,
        "relay_target_ms": stats(relay_target_hist),
        "relay_target_final_ms": relay.last_target_ms,
        "relay_direction_changes": relay.direction_changes,
        "relay_broadcasts": room_broadcasts,
        "deadline_spread_ms": stats(deadline_spread_hist),
        "modeled_acoustic_skew_ms": stats(acoustic_skew_hist),
        "relay_guest_tracking_error_ms": stats(tracking_error_hist),
        "late_frame_pct": 100.0 * late_frames / max(1, total_frames),
        "underrun_events": underrun_events,
        "underrun_duration_ms": underrun_ms,
        "hard_resync_proxy_events": hard,
        "continuity_pct": 100.0 * continuity,
        "clock_confidence_ms": stats(clock_conf_hist),
        "underbuffer_after_transition_s": underbuffer_ms / 1000.0,
        "recovery_to_target_within_10ms_s": recovery_to_10_s,
        "join_target_delta_ms": join_target_delta,
        "expired_stat_events": len(expired_ids),
        "first_expired_stat_ms": expired_ids[0][1] if expired_ids else None,
        "disconnect_stat_removals": len(disconnect_removed),
        "server_restart_pre_D_ms": restart_pre_d,
        "server_restart_post_D_ms": restart_post_d,
        "server_restart_guest_resets": sum(l.server_restart_resets for l in participants),
        "idle_cleanup_pre_D_ms": idle_cleanup_pre_d,
        "idle_cleanup_post_D_ms": idle_cleanup_post_d,
        "resume_guard_margin_ms": stats(resume_guard_margin),
        "offline_duration_ms_total": offline_ms,
        "listener_recommendation_ms": stats([x for l in participants for x in l.recommendation_samples]),
    }


SCENARIOS = [
    "clean_wifi", "good_cellular", "poor_cellular", "mixed",
    "jitter50", "jitter200", "packet_loss", "short_outage", "reconnect_live_edge",
    "wifi_to_good", "good_to_poor", "clean_to_j50", "clean_to_j200",
    "one_poor_joins", "several_poor_join",
    "poor_leave_disconnect", "poor_stats_stale", "server_restart", "idle_cleanup_reset",
]


def markdown(results: List[Dict[str, object]]) -> str:
    lines = [
        "# JLS Workstream 4 Final-Delta Simulation",
        "",
        "Production source pin: " + HEAD_SHA,
        "",
        "All quantitative values below are [SIMULATED]. Network/clock/output distributions are model inputs, not device measurements.",
        "",
        "| Scenario | Relay D final | Target final | D-target gap | Deadline spread p95 | Acoustic skew p95* | Late % | Underrun s | Hard-resync proxy | Continuity % | Clock conf p95 | Underbuffer after transition s |",
        "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for r in results:
        gap = float(r["relay_target_final_ms"]) - float(r["relay_D_final_ms"])
        lines.append(
            f"| {r['scenario']} | {r['relay_D_final_ms']:.2f} ms | "
            f"{r['relay_target_final_ms']:.2f} ms | {gap:.2f} ms | "
            f"{r['deadline_spread_ms']['p95']:.3f} ms | "
            f"{r['modeled_acoustic_skew_ms']['p95']:.2f} ms | "
            f"{r['late_frame_pct']:.3f}% | {r['underrun_duration_ms']/1000.0:.2f} | "
            f"{r['hard_resync_proxy_events']} | {r['continuity_pct']:.3f}% | "
            f"{r['clock_confidence_ms']['p95']:.1f} ms | "
            f"{r['underbuffer_after_transition_s']:.1f} |"
        )
    lines += [
        "",
        "* Acoustic skew is a sensitivity simulation using explicit residual clock/output-path assumptions. It is not a microphone result.",
        "",
        "## Transition diagnostics",
        "",
        "| Scenario | D last60 range | Direction changes | Join target delta | Expired stats | First expiry | Disconnect removals | Restart D pre -> post | Resume guard p05-ish note |",
        "|---|---:|---:|---:|---:|---:|---:|---|---|",
    ]
    for r in results:
        if r["scenario"] not in {"one_poor_joins","several_poor_join","poor_leave_disconnect","poor_stats_stale","server_restart","idle_cleanup_reset","short_outage","reconnect_live_edge","good_to_poor","clean_to_j200","packet_loss"}:
            continue
        join_delta = "—" if r["join_target_delta_ms"] is None else f"{r['join_target_delta_ms']:.1f} ms"
        expiry = "—" if r["first_expired_stat_ms"] is None else f"{r['first_expired_stat_ms']/1000:.1f} s"
        restart = "—" if r["server_restart_pre_D_ms"] is None else f"{r['server_restart_pre_D_ms']:.1f} -> {r['server_restart_post_D_ms']:.1f} ms"
        if r["idle_cleanup_pre_D_ms"] is not None:
            restart = f"idle {r['idle_cleanup_pre_D_ms']:.1f} -> {r['idle_cleanup_post_D_ms']:.1f} ms"
        guard = "—" if r["resume_guard_margin_ms"]["max"] == 0 else f"p50 {r['resume_guard_margin_ms']['p50']:.2f} ms, min not modeled"
        lines.append(f"| {r['scenario']} | {r['relay_D_last60_range_ms']:.2f} ms | {r['relay_direction_changes']} | {join_delta} | {r['expired_stat_events']} | {expiry} | {r['disconnect_stat_removals']} | {restart} | {guard} |")
    lines += [
        "",
        "Production room-D theoretical slew times are [ESTIMATE]: +100 ms takes 400 s; -100 ms takes 666.7 s, before hysteresis and telemetry effects.",
    ]
    return "\n".join(lines) + "\n"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listeners", type=int, default=20)
    ap.add_argument("--duration-s", type=float, default=240.0)
    ap.add_argument("--seed", type=int, default=20261001)
    ap.add_argument("--scenario", choices=SCENARIOS + ["all"], default="all")
    ap.add_argument("--json-out", default="")
    ap.add_argument("--md-out", default="")
    args = ap.parse_args()
    if args.listeners < 20:
        raise SystemExit("--listeners must be >= 20")

    names = SCENARIOS if args.scenario == "all" else [args.scenario]
    results = [run_scenario(name, args.listeners, args.duration_s, args.seed) for name in names]
    payload = {
        "evidence": EVIDENCE,
        "production_sha": HEAD_SHA,
        "model_inputs_are_estimates": True,
        "results": results,
    }
    report = markdown(results)
    print(report)
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(payload, indent=2), encoding="utf-8")
    if args.md_out:
        Path(args.md_out).write_text(report, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
