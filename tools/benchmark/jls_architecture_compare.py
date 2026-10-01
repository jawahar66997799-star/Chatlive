#!/usr/bin/env python3
"""
Workstream 4 comparative architecture model.

All numeric output from this tool is [SIMULATED] unless a row explicitly
contains a literature/estimate annotation. The model compares engineering
classes; it does not pretend to execute Snapcast, BeatSync, WebRTC, etc.
"""
from __future__ import annotations
import argparse, json, math, random
from dataclasses import dataclass
from pathlib import Path

E="[SIMULATED]"
HEAD="fe36e2904dd8cbfcd4eb9af402f4c684305ba2d3"

@dataclass(frozen=True)
class Scenario:
    name:str; jitter_ms:float=5; loss:float=0; outage_ms:float=0; burst_ms:float=0
    route_change:bool=False; reconnect:str=""

SCENARIOS=[
    Scenario("steady_wifi",5),
    Scenario("jitter_50",50),
    Scenario("jitter_200",200),
    Scenario("loss_1pct",20,.01),
    Scenario("loss_5pct",35,.05),
    Scenario("burst_loss",45,.08,0,350),
    Scenario("outage_0_5s",15,0,500),
    Scenario("outage_1s",15,0,1000),
    Scenario("outage_3s",15,0,3000),
    Scenario("wifi_to_cellular",45,.01,600,0,True),
    Scenario("host_reconnect",20,0,800,0,False,"host"),
    Scenario("relay_restart",20,0,900,0,False,"relay"),
    Scenario("guest_reconnect",20,0,500,0,False,"guest"),
    Scenario("background_resume",30,0,1200,0,False,"background"),
]

ARCH={
"A_JLS_WSS":{
 "label":"A Current JLS WSS timestamped Opus","live":True,"transport":"tcp","buffer":400,
 "ttfa":520,"base_skew":4.0,"output_sd":3.0,"reconnect":180,"media_kbps":178,
 "cpu":"low-medium","memory_kb_peer":72,"fanout":"O(N) relay writes","scale_max":250,
 "notes":"Current browser precision path; direct safe-local fallback may trade sync for continuity."
},
"B_SNAPCAST":{
 "label":"B Snapcast-style server-clock buffered playout","live":True,"transport":"tcp","buffer":1000,
 "ttfa":1120,"base_skew":0.25,"output_sd":0.25,"reconnect":350,"media_kbps":175,
 "cpu":"low-medium","memory_kb_peer":64,"fanout":"O(N) server writes","scale_max":1000,
 "notes":"Native low-level audio reference model; its sub-ms literature result is not transferable to WebAudio."
},
"C_BEATSYNC":{
 "label":"C BeatSync-style scheduled preloaded playback","live":False,"transport":"preloaded","buffer":999999,
 "ttfa":300,"base_skew":2.0,"output_sd":2.0,"reconnect":0,"media_kbps":0,
 "cpu":"low during playback","memory_kb_peer":0,"fanout":"O(N) control; media via object/CDN preload","scale_max":1000,
 "notes":"Warm playback only. Cold file fetch/decode/load gate excluded from TTFA."
},
"D_WEBRTC":{
 "label":"D WebRTC live Opus media track","live":True,"transport":"webrtc","buffer":80,
 "ttfa":420,"base_skew":12.0,"output_sd":7.0,"reconnect":700,"media_kbps":165,
 "cpu":"medium-high","memory_kb_peer":180,"fanout":"SFU O(N); direct broadcaster O(N) uplink","scale_max":1000,
 "notes":"NetEq/FEC/PLC favor continuity; receiver jitter buffers are autonomous."
},
"E_P2P":{
 "label":"E BeatSync-P2P/Trystero scheduled playback","live":False,"transport":"preloaded","buffer":999999,
 "ttfa":350,"base_skew":3.0,"output_sd":2.5,"reconnect":0,"media_kbps":0,
 "cpu":"grows with peers","memory_kb_peer":220,"fanout":"peer mesh/file transfer; worst O(N^2)","scale_max":10,
 "notes":"Warm playback only; cold peer file transfer excluded; mesh scaling deliberately capped."
},
"F_HYBRID":{
 "label":"F WSS control + standard WebRTC audio track","live":True,"transport":"webrtc","buffer":100,
 "ttfa":470,"base_skew":8.0,"output_sd":5.0,"reconnect":650,"media_kbps":165,
 "cpu":"medium-high","memory_kb_peer":190,"fanout":"WSS O(N) control + SFU O(N) media","scale_max":1000,
 "notes":"Shared control clock helps actions but standard RTP playout is still UA-jitter-buffer controlled."
},
}

def q(a,p):
    if not a:return 0.0
    a=sorted(a);x=(len(a)-1)*p;lo=int(x);hi=min(len(a)-1,lo+1);f=x-lo
    return a[lo]*(1-f)+a[hi]*f

def stat(a):
    return {"p50":q(a,.5),"p95":q(a,.95),"p99":q(a,.99),"max":max(a) if a else 0}

def run_one(key,cfg,s,seed=20261002,n=5000):
    r=random.Random(seed+sum(map(ord,key+s.name)))
    ttfa=[];skew=[];und=[];reconn=[];stale=[];hard=[];buf=[]
    for _ in range(n):
        network=max(0,r.gauss(25,max(2,s.jitter_ms/3)))
        t=cfg["ttfa"]+network
        u=0;h=0;st=0
        b=cfg["buffer"]

        if cfg["transport"]=="preloaded":
            # Network disturbances do not affect already-decoded current-track audio.
            rec = 0 if s.outage_ms or s.route_change or s.reconnect else 0
            sk=max(0,abs(r.gauss(cfg["base_skew"],cfg["output_sd"])))
            ttfa.append(max(0,r.gauss(t,40)));skew.append(sk);und.append(0);reconn.append(rec);stale.append(0);hard.append(0);buf.append(b)
            continue

        if cfg["transport"]=="tcp":
            # TCP preserves bytes but loss/burst can cause HOL. JLS explicitly drops stale
            # fanout/replay; Snapcast absorbs more with its intentionally deep buffer.
            hol = s.burst_ms + (s.loss*2200) + max(0,s.jitter_ms-20)*0.35
            if s.loss>0 and r.random()<min(.95,s.loss*7): hol += r.uniform(40,220)
            stall=max(s.outage_ms,hol)
            uncovered=max(0,stall-b)
            u = 1 if uncovered>20 else 0
            h = int(uncovered/100) if key=="A_JLS_WSS" else int(uncovered/250)
            st = 0 if key=="A_JLS_WSS" else max(0,stall-b)*.1
            rec = (stall + cfg["reconnect"]) if (s.outage_ms or s.route_change or s.reconnect) else 0
            # JLS continuity fallback sacrifices precise sync during repeated underrun.
            fallback_penalty=(min(90,uncovered*.08) if key=="A_JLS_WSS" and uncovered>0 else 0)
            sk=abs(r.gauss(cfg["base_skew"]+fallback_penalty,cfg["output_sd"]+s.jitter_ms*.006))
            if u: t += min(uncovered,1500)
        else:
            # WebRTC NetEq-like abstraction: adaptive jitter buffer + PLC/FEC absorbs most
            # random loss; standard media receiver timing remains independently adaptive.
            target=min(4000,max(cfg["buffer"],40+s.jitter_ms*1.15+s.loss*1400))
            b=target
            outage=s.outage_ms
            effective_loss=max(0,s.loss-.025) # abstract FEC/PLC benefit
            u = int((effective_loss*100)>r.uniform(0,4))
            if outage>b:
                u += 1
            rec=(outage+cfg["reconnect"]) if (outage or s.route_change or s.reconnect) else 0
            # independent jitter buffers diverge more as conditions worsen
            sk=abs(r.gauss(cfg["base_skew"]+s.jitter_ms*.12+s.loss*300,cfg["output_sd"]+s.jitter_ms*.05))
            h=0
            st=0
            if outage: t+=min(900,outage*.2)

        ttfa.append(t);skew.append(sk);und.append(u);reconn.append(rec);stale.append(st);hard.append(h);buf.append(b)

    return {
      "architecture":key,"scenario":s.name,"evidence":E,
      "ttfa_ms":stat(ttfa),"skew_ms":stat(skew),
      "underrun_probability_pct":100*sum(1 for x in und if x>0)/len(und),
      "mean_underrun_events":sum(und)/len(und),
      "reconnect_ms":stat(reconn),"stale_backlog_ms":stat(stale),
      "hard_resync_mean":sum(hard)/len(hard),"buffer_ms":stat(buf),
    }

def scaling(key,cfg):
    rows=[]
    for n in [2,10,50,100,1000]:
        applicable=n<=cfg["scale_max"]
        if key=="E_P2P":
            connections=n*(n-1)//2 if applicable else None
            server_mbps=0
            host_mbps=None
            memory_mb=(n-1)*cfg["memory_kb_peer"]/1024 if applicable else None
        elif key in ("C_BEATSYNC",):
            connections=n
            server_mbps=0.02*n/1000 # control only placeholder estimate
            host_mbps=0
            memory_mb=None
        elif key=="D_WEBRTC":
            connections=n
            server_mbps=cfg["media_kbps"]*n/1000 if applicable else None
            host_mbps=(cfg["media_kbps"]*min(n,10)/1000) if n<=10 else cfg["media_kbps"]/1000
            memory_mb=cfg["memory_kb_peer"]*n/1024 if applicable else None
        elif key=="F_HYBRID":
            connections=n
            server_mbps=cfg["media_kbps"]*n/1000 if applicable else None
            host_mbps=cfg["media_kbps"]/1000
            memory_mb=cfg["memory_kb_peer"]*n/1024 if applicable else None
        else:
            connections=n
            server_mbps=cfg["media_kbps"]*n/1000 if applicable else None
            host_mbps=cfg["media_kbps"]/1000 if applicable else None
            memory_mb=(2.0+cfg["memory_kb_peer"]*n/1024) if applicable else None
        rows.append({"listeners":n,"applicable":applicable,"connections_or_edges":connections,
                     "server_egress_mbps_est":server_mbps,"host_uplink_mbps_est":host_mbps,
                     "working_memory_mb_est":memory_mb})
    return rows

def md(results,scales):
    focus={"steady_wifi","jitter_50","jitter_200","loss_5pct","outage_1s","outage_3s","wifi_to_cellular"}
    lines=[
      "# JLS comparative architecture simulation",
      "",
      f"Production JLS source pin: {HEAD}",
      "",
      "Every number in the tables is **[SIMULATED]** from an architecture-class model with **[ESTIMATE]** parameters. This is not a measured benchmark of third-party products.",
      "",
      "## Representative scenario matrix",
      "",
      "| Architecture | Scenario | TTFA p95 ms | Skew p50/p95/p99 ms | Underrun episode % | Reconnect p95 ms | Stale backlog p95 ms | Buffer p50 ms | Hard-resync mean |",
      "|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    by={(r["architecture"],r["scenario"]):r for r in results}
    for key,cfg in ARCH.items():
      for s in SCENARIOS:
        if s.name not in focus:continue
        r=by[(key,s.name)]
        lines.append(f"| {key} | {s.name} | {r['ttfa_ms']['p95']:.0f} | {r['skew_ms']['p50']:.1f}/{r['skew_ms']['p95']:.1f}/{r['skew_ms']['p99']:.1f} | {r['underrun_probability_pct']:.1f}% | {r['reconnect_ms']['p95']:.0f} | {r['stale_backlog_ms']['p95']:.0f} | {r['buffer_ms']['p50']:.0f} | {r['hard_resync_mean']:.2f} |")
    lines += ["","## Scaling model","","| Architecture | N | Applicable | Edges/connections | Server egress Mbps est | Host uplink Mbps est | Working memory MB est |","|---|---:|---|---:|---:|---:|---:|"]
    for key,rows in scales.items():
      for x in rows:
        def f(v):return "—" if v is None else (f"{v:.2f}" if isinstance(v,float) else str(v))
        lines.append(f"| {key} | {x['listeners']} | {'yes' if x['applicable'] else 'no'} | {f(x['connections_or_edges'])} | {f(x['server_egress_mbps_est'])} | {f(x['host_uplink_mbps_est'])} | {f(x['working_memory_mb_est'])} |")
    lines += ["","Warm TTFA for preloaded BeatSync/P2P excludes cold media fetch/decode. Standard WebRTC rows assume an SFU at larger listener counts; direct broadcaster fanout does not scale to 50–1000."]
    return "\n".join(lines)+"\n"

def main():
    ap=argparse.ArgumentParser();ap.add_argument("--json-out",default="");ap.add_argument("--md-out",default="");ap.add_argument("--samples",type=int,default=5000)
    a=ap.parse_args()
    results=[run_one(k,c,s,n=a.samples) for k,c in ARCH.items() for s in SCENARIOS]
    scales={k:scaling(k,c) for k,c in ARCH.items()}
    payload={"evidence":"[SIMULATED]","source_sha":HEAD,"architectures":ARCH,"results":results,"scaling":scales}
    report=md(results,scales);print(report)
    if a.json_out:Path(a.json_out).write_text(json.dumps(payload,indent=2),encoding="utf-8")
    if a.md_out:Path(a.md_out).write_text(report,encoding="utf-8")
if __name__=="__main__":main()
