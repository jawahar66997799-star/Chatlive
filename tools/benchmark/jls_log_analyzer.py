#!/usr/bin/env python3
"""
JLS multi-format log analyzer.

Reads Android CSV telemetry plus relay/browser JSON, JSONL, CSV, or simple text.
The operator MUST declare evidence provenance so every quantitative report is
tagged [MEASURED], [SIMULATED], [LITERATURE], or [ESTIMATE].
"""
from __future__ import annotations
import argparse, csv, json, math, re
from pathlib import Path
from typing import Any, Dict, Iterable, List, Sequence


def pct(xs: Sequence[float], q: float) -> float:
    if not xs: return 0.0
    ys=sorted(xs); p=(len(ys)-1)*q; lo=int(math.floor(p)); hi=int(math.ceil(p))
    return float(ys[lo] if lo==hi else ys[lo]*(hi-p)+ys[hi]*(p-lo))


def summary(xs: Sequence[float]) -> Dict[str,float]:
    return {"p50":pct(xs,.5),"p95":pct(xs,.95),"p99":pct(xs,.99),"max":max(xs) if xs else 0.0}


def num(v: Any):
    try:
        if v is None or v == "": return None
        return float(v)
    except Exception:
        return None


def first_num(e: Dict[str,Any], names: Sequence[str]):
    for n in names:
        if n in e:
            x=num(e[n])
            if x is not None: return x
    return None


def first_str(e: Dict[str,Any], names: Sequence[str]):
    for n in names:
        if n in e and e[n] is not None: return str(e[n])
    return ""


def load_file(path: Path) -> List[Dict[str,Any]]:
    text=path.read_text(encoding="utf-8",errors="replace")
    lines=text.splitlines()
    body="\n".join(x for x in lines if not x.lstrip().startswith("#")).strip()
    if not body: return []

    if path.suffix.lower()==".json":
        obj=json.loads(body)
        if isinstance(obj,list): return [x for x in obj if isinstance(x,dict)]
        if isinstance(obj,dict):
            for k in ("events","rows","records","logs"):
                if isinstance(obj.get(k),list): return [x for x in obj[k] if isinstance(x,dict)]
            return [obj]

    if path.suffix.lower() in (".jsonl",".ndjson"):
        out=[]
        for line in body.splitlines():
            try:
                x=json.loads(line)
                if isinstance(x,dict): out.append(x)
            except Exception: pass
        return out

    # CSV auto-detection.
    if "," in body.splitlines()[0]:
        try:
            return [dict(r) for r in csv.DictReader(body.splitlines())]
        except Exception:
            pass

    # Text fallback: preserve line and extract common key=value pairs.
    out=[]
    for i,line in enumerate(body.splitlines()):
        e={"line":line,"line_no":i+1}
        for k,v in re.findall(r"([A-Za-z_][A-Za-z0-9_.-]*)=([^\s,]+)",line):
            e[k]=v
        m=re.search(r"\b(\d+(?:\.\d+)?)\s*ms\b",line)
        if m: e.setdefault("time_ms",m.group(1))
        out.append(e)
    return out


def analyze(events: List[Dict[str,Any]], gap_threshold_ms: float) -> Dict[str,Any]:
    times=[]; seqs=[]; states=[]; event_types=[]
    buffers=[]; drifts=[]; skews=[]; latencies=[]; rtts=[]; late_values=[]; read_faults=[]
    reconnects=0
    for e in events:
        t=first_num(e,["elapsed_ms","timestamp_ms","ts_ms","time_ms","server_ms","serverTimeMs","t_ms"])
        if t is not None: times.append(t)
        s=first_num(e,["seq","sequence","sequence_number"])
        if s is not None: seqs.append(int(s))
        st=first_str(e,["health","state","status"])
        typ=first_str(e,["type","event","event_type"])
        if st: states.append(st)
        if typ: event_types.append(typ)
        blob=(st+" "+typ+" "+str(e.get("line",""))).lower()
        if "reconnect" in blob or "connected_after" in blob: reconnects += 1

        for names,target in [
            (["buffer_ms","bufferDepthMs","buffer_depth_ms","targetDelayMs"],buffers),
            (["clock_drift_ppm","drift_ppm","clockDriftPpm"],drifts),
            (["skew_ms","listener_skew_ms","speaker_skew_ms"],skews),
            (["latency_ms","e2e_latency_ms","relay_latency_ms"],latencies),
            (["rtt_ms","relay_rtt_ms","clock_rtt_ms"],rtts),
            (["late_frames","late_packets","late_count","by_ms"],late_values),
            (["read_faults","dropped_reads","readFaults"],read_faults),
        ]:
            x=first_num(e,names)
            if x is not None: target.append(x)

    times_sorted=sorted(times)
    gaps=[]
    for a,b in zip(times_sorted,times_sorted[1:]):
        d=b-a
        if d>gap_threshold_ms: gaps.append(d)

    seq_loss=0; seq_resets=0
    if seqs:
        prev=seqs[0]
        for s in seqs[1:]:
            if s>prev+1: seq_loss += s-prev-1
            elif s<=prev: seq_resets += 1
            prev=s

    transitions=[]
    prev=None
    for s in states:
        if s!=prev:
            transitions.append({"from":prev,"to":s})
            prev=s

    return {
        "events":len(events),
        "time_span_ms": (max(times)-min(times)) if times else 0.0,
        "gaps_over_threshold_count":len(gaps),
        "gap_ms":summary(gaps),
        "sequence_missing_frames":seq_loss,
        "sequence_resets_or_reorders":seq_resets,
        "reconnect_mentions":reconnects,
        "state_transition_count":len(transitions),
        "state_transitions":transitions,
        "buffer_ms":summary(buffers),
        "clock_drift_ppm":summary([abs(x) for x in drifts]),
        "skew_ms":summary([abs(x) for x in skews]),
        "latency_ms":summary(latencies),
        "rtt_ms":summary(rtts),
        "late_metric":summary(late_values),
        "read_faults":summary(read_faults),
    }


def md_report(tag: str, reports: List[Dict[str,Any]]) -> str:
    out=["# JLS Log Analyzer Report","",f"Evidence tag: **{tag}**",""]
    for r in reports:
        a=r["analysis"]
        out += [
            f"## {r['file']}",
            "",
            f"- {tag} events: {a['events']}",
            f"- {tag} span: {a['time_span_ms']:.2f} ms",
            f"- {tag} gaps over threshold: {a['gaps_over_threshold_count']}; gap p95 {a['gap_ms']['p95']:.2f} ms; max {a['gap_ms']['max']:.2f} ms",
            f"- {tag} missing sequence frames: {a['sequence_missing_frames']}; resets/reorders: {a['sequence_resets_or_reorders']}",
            f"- {tag} reconnect mentions: {a['reconnect_mentions']}",
            f"- {tag} buffer p50/p95/p99/max: {a['buffer_ms']['p50']:.2f}/{a['buffer_ms']['p95']:.2f}/{a['buffer_ms']['p99']:.2f}/{a['buffer_ms']['max']:.2f} ms",
            f"- {tag} abs drift p95: {a['clock_drift_ppm']['p95']:.3f} ppm",
            f"- {tag} abs skew p95/p99/max: {a['skew_ms']['p95']:.3f}/{a['skew_ms']['p99']:.3f}/{a['skew_ms']['max']:.3f} ms",
            f"- {tag} latency p50/p95/p99/max: {a['latency_ms']['p50']:.2f}/{a['latency_ms']['p95']:.2f}/{a['latency_ms']['p99']:.2f}/{a['latency_ms']['max']:.2f} ms",
            "",
        ]
    return "\n".join(out)


def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument("files",nargs="+")
    ap.add_argument("--evidence",required=True,choices=["MEASURED","SIMULATED","LITERATURE","ESTIMATE"])
    ap.add_argument("--gap-threshold-ms",type=float,default=1500.0)
    ap.add_argument("--json-out",default="")
    ap.add_argument("--md-out",default="")
    args=ap.parse_args()
    tag=f"[{args.evidence}]"
    reports=[]
    for fn in args.files:
        p=Path(fn); ev=load_file(p)
        reports.append({"file":str(p),"analysis":analyze(ev,args.gap_threshold_ms)})
    payload={"evidence":tag,"gap_threshold_ms":args.gap_threshold_ms,"reports":reports}
    print(md_report(tag,reports))
    if args.json_out: Path(args.json_out).write_text(json.dumps(payload,indent=2),encoding="utf-8")
    if args.md_out: Path(args.md_out).write_text(md_report(tag,reports),encoding="utf-8")
    return 0


if __name__=="__main__":
    raise SystemExit(main())
