#!/usr/bin/env python3
"""Sweep JLS common playout delay. Every numeric result is [SIMULATED]."""
from __future__ import annotations
import argparse, json
from pathlib import Path
from jls_network_clock_sim import simulate

DELAYS = [150.0, 250.0, 400.0, 600.0, 800.0, 1000.0]

def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument("--listeners",type=int,default=20)
    ap.add_argument("--duration-s",type=float,default=120.0)
    ap.add_argument("--seed",type=int,default=20261001)
    ap.add_argument("--scenarios",default="mixed,poor_cellular")
    ap.add_argument("--outages",choices=["on","off"],default="off")
    ap.add_argument("--json-out",default="")
    args=ap.parse_args()
    if args.listeners < 20:
        raise SystemExit("Requirement: --listeners must be >= 20")
    scenarios=[x.strip() for x in args.scenarios.split(",") if x.strip()]
    results=[]
    print("# JLS Adaptive Common-Delay Sweep")
    print()
    print("All values are **[SIMULATED]**.")
    print()
    print("| Scenario | Outages | D ms | skew p50 ms | p95 | p99 | max | late p95 % | continuity p50 % | hard resync max |")
    print("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for scenario in scenarios:
        for d in DELAYS:
            r=simulate(scenario,args.listeners,args.duration_s,args.seed,"adaptive",d,args.outages=="on")
            results.append(r)
            s=r["listener_to_listener_skew_ms"]; a=r["aggregate"]
            print(f"| {scenario} | {args.outages} | {d:.0f} | {s['p50']:.2f} | {s['p95']:.2f} | {s['p99']:.2f} | {s['max']:.2f} | "
                  f"{a['late_packet_pct']['p95']:.3f} | {a['continuity_pct']['p50']:.4f} | {a['hard_resync_count']['max']:.0f} |")
    payload={"evidence":"[SIMULATED]","delays_ms":DELAYS,"outages":args.outages,"results":results}
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(payload,indent=2),encoding="utf-8")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
