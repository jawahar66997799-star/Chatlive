#!/usr/bin/env python3
from __future__ import annotations
import argparse, json, math, random
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path

EVIDENCE='[SIMULATED]'
STEP_MS=20.0

def quantile(vals,q):
    if not vals: return 0.0
    a=sorted(vals); pos=q*(len(a)-1); lo=int(math.floor(pos)); hi=int(math.ceil(pos))
    if lo==hi: return float(a[lo])
    f=pos-lo; return float(a[lo]*(1-f)+a[hi]*f)

def stats(vals):
    if not vals: return {'p50':0.0,'p95':0.0,'p99':0.0,'max':0.0}
    return {'p50':quantile(vals,.5),'p95':quantile(vals,.95),'p99':quantile(vals,.99),'max':max(vals)}

@dataclass
class GuestAdaptive:
    current: float=400.0; target: float=400.0
    floor: float=150.0; ceiling: float=1000.0; safety: float=35.0
    up: float=25.0; down: float=6.0
    network: deque=field(default_factory=lambda: deque(maxlen=160))
    decode: deque=field(default_factory=lambda: deque(maxlen=160))
    last_ms: float|None=None; late_boost_until: float=-1e18
    def tick(self, now):
        if self.last_ms is None:
            self.last_ms=now; return self.current
        dt=max(0.0,min(2.0,(now-self.last_ms)/1000.0)); self.last_ms=now
        if self.target>self.current:
            rate=max(self.up,60.0) if now<self.late_boost_until else self.up
            self.current=min(self.target,self.current+rate*dt)
        else:
            self.current=max(self.target,self.current-self.down*dt)
        return self.current
    def observe(self, age, decode_ms, confidence_ms, now):
        if 0 <= age < 5000: self.network.append(age)
        if 0 <= decode_ms < 1000: self.decode.append(decode_ms)
        if len(self.network)>=8:
            n95=quantile(self.network,.95); d95=quantile(self.decode,.95) if self.decode else 0.0
            self.target=max(self.floor,min(self.ceiling,n95+d95+max(0.0,confidence_ms)+self.safety))
        self.tick(now)
    def mark_late(self, now, severity):
        bump=min(100.0,20.0+max(0.0,severity)*.25)
        self.target=max(self.floor,min(self.ceiling,max(self.target,self.current+bump)))
        self.late_boost_until=max(self.late_boost_until,now+5000.0)

@dataclass
class GuestRoomD:
    current: float=400.0; target: float=400.0
    floor: float=150.0; ceiling: float=1000.0
    up: float=.25; down: float=.15; last_ms: float|None=None
    def on_state(self,target,now):
        self.target=max(self.floor,min(self.ceiling,float(target)))
        if self.last_ms is None:
            self.current=self.target; self.last_ms=now
    def tick(self,now):
        if self.last_ms is None:
            self.last_ms=now; return self.current
        dt=max(0.0,min(1.0,(now-self.last_ms)/1000.0)); self.last_ms=now
        if self.target>self.current: self.current=min(self.target,self.current+self.up*dt)
        else: self.current=max(self.target,self.current-self.down*dt)
        return self.current

@dataclass
class RelayRoomD:
    current: float=400.0
    dmin:float=150.0; dmax:float=1000.0; ttl_ms:float=10000.0; update_ms:float=1000.0
    up_per_s:float=.25; down_per_s:float=.15
    last_adjust_ms:float=0.0; stats_map:dict=field(default_factory=dict)
    last_dir:int=0; direction_changes:int=0
    def remove(self,lid): self.stats_map.pop(lid,None)
    def update_stat(self,lid,rec,now):
        self.stats_map[lid]=(max(self.dmin,min(self.dmax,float(rec))),now)
        return self._adjust(now)
    def _adjust(self,now):
        self.stats_map={k:v for k,v in self.stats_map.items() if not(now>v[1] and now-v[1]>self.ttl_ms)}
        if not self.stats_map: return False,None
        if self.last_adjust_ms==0:
            self.last_adjust_ms=now; return False,None
        if now<=self.last_adjust_ms or now-self.last_adjust_ms<self.update_ms: return False,None
        vals=sorted(v[0] for v in self.stats_map.values())
        idx=max(0,min(len(vals)-1,int(math.ceil(.95*len(vals)))-1))
        target=max(self.dmin,min(self.dmax,vals[idx]))
        cur=self.current
        if abs(target-cur)<10.0:
            self.last_adjust_ms=now; return False,target
        elapsed=(now-self.last_adjust_ms)/1000.0; self.last_adjust_ms=now
        if target>cur: nxt=min(target,cur+self.up_per_s*elapsed); direction=1
        else: nxt=max(target,cur-self.down_per_s*elapsed); direction=-1
        nxt=max(self.dmin,min(self.dmax,nxt))
        if nxt!=cur:
            if self.last_dir and direction!=self.last_dir: self.direction_changes+=1
            self.last_dir=direction
            self.current=nxt
            return True,target
        return False,target

@dataclass
class Listener:
    lid:int; profile:str; rng:random.Random
    adaptive:GuestAdaptive=field(default_factory=GuestAdaptive)
    roomd:GuestRoomD=field(default_factory=GuestRoomD)
    online:bool=True; stats_phase_ms:float=0.0; next_stats_ms:float=0.0
    late:int=0; frames:int=0; hard:int=0; disconnected_ms:float=0.0
    calibration_residual_ms:float=0.0
    hol_until_ms:float=0.0

PROFILES={
    'wifi': {'age':35,'jitter':5,'conf':14,'decode':3},
    'good': {'age':85,'jitter':15,'conf':37,'decode':4},
    'poor': {'age':220,'jitter':55,'conf':110,'decode':6},
    'j50': {'age':80,'jitter':50,'conf':45,'decode':4},
    'j200': {'age':120,'jitter':200,'conf':100,'decode':5},
    'loss': {'age':70,'jitter':20,'conf':40,'decode':4},
}

def profile_for(scenario,i,t):
    if scenario=='mixed': return ['wifi','good','poor'][i%3]
    if scenario=='clean_wifi': return 'wifi'
    if scenario=='good_cellular': return 'good'
    if scenario=='poor_cellular': return 'poor'
    if scenario in ('jitter50','jitter200','packet_loss'):
        if t<30000 or t>=90000: return 'good'
        return {'jitter50':'j50','jitter200':'j200','packet_loss':'loss'}[scenario]
    if scenario=='short_outage': return 'good'
    if scenario=='reconnect_live_edge':
        return 'j200' if 30000 <= t < 60000 else 'good'
    return 'wifi'

def age_sample(listener,scenario,t):
    p=PROFILES[profile_for(scenario,listener.lid,t)]
    age=max(0.0,listener.rng.gauss(p['age'],p['jitter']))
    if scenario=='packet_loss' and 30000<=t<90000:
        if listener.rng.random()<0.05:
            listener.hol_until_ms=max(listener.hol_until_ms,t+listener.rng.uniform(120,420))
        if listener.hol_until_ms>t:
            age+=listener.hol_until_ms-t
    if scenario=='short_outage' and 60000<=t<61000:
        age+=61000-t+500
    return age,p['decode'],p['conf']

def run_scenario(scenario,count=20,duration_s=180,seed=20261001,late_join=True):
    relay=RelayRoomD()
    listeners=[]
    for i in range(count):
        rr=random.Random(seed*1009+i*7919+sum(map(ord,scenario)))
        l=Listener(i,profile_for(scenario,i,0),rr)
        l.stats_phase_ms=(i*97)%2000
        l.next_stats_ms=2000+l.stats_phase_ms
        l.calibration_residual_ms=rr.gauss(0,.45)
        l.roomd.on_state(relay.current,0)
        listeners.append(l)
    joiner=None; join_time=90000.0
    relay_hist=[]; local_spread=[]; cohort_spread=[]; planned_spread=[]; tracking_error=[]; joiner_gap=[]; local_d_values=[]
    relay_targets=[]; broadcast_count=0; first_recovery_to_10=None; resume_lateness_proxy=[]
    stress_end=90000.0 if scenario in ('jitter50','jitter200','packet_loss') else (61000.0 if scenario in ('short_outage','reconnect_live_edge') else None)
    steps=int(duration_s*1000/STEP_MS)
    for k in range(steps):
        t=k*STEP_MS
        if late_join and joiner is None and t>=join_time:
            i=count; rr=random.Random(seed*1009+i*7919+sum(map(ord,scenario)))
            joiner=Listener(i,'wifi',rr)
            joiner.stats_phase_ms=(i*97)%2000; joiner.next_stats_ms=t+2000+joiner.stats_phase_ms
            joiner.calibration_residual_ms=rr.gauss(0,.45)
            joiner.roomd.on_state(relay.current,t)
            listeners.append(joiner)
        for l in listeners:
            if scenario=='reconnect_live_edge' and l.lid in (0,1,2):
                if 60000<=t<61000:
                    if l.online:
                        l.online=False; relay.remove(l.lid)
                    l.disconnected_ms+=STEP_MS
                    continue
                elif t>=61000 and not l.online:
                    l.online=True
                    resume_lateness_proxy.append(max(0.0, relay.current-l.roomd.current-150.0))
                    l.roomd.on_state(relay.current,t)
                    l.next_stats_ms=t+2000+(l.lid*97)%2000
            if not l.online: continue
            age,decode,conf=age_sample(l,scenario,t)
            l.adaptive.observe(age,decode,conf,t)
            d=l.roomd.tick(t)
            lateness=age+decode-d
            if lateness>0:
                l.late+=1; l.adaptive.mark_late(t,lateness)
                if lateness>=100: l.hard+=1
            l.frames+=1
            if t+1e-9>=l.next_stats_ms:
                changed,target=relay.update_stat(l.lid,l.adaptive.current,t)
                if target is not None: relay_targets.append(target)
                l.next_stats_ms+=2000
                if changed:
                    broadcast_count+=1
                    for g in listeners:
                        if g.online: g.roomd.on_state(relay.current,t)
        online=[l for l in listeners if l.online]
        if online:
            ds=[l.roomd.current for l in online]; local_d_values.extend(ds)
            local_spread.append(max(ds)-min(ds))
            cohort=[l.roomd.current for l in online if l is not joiner]
            if cohort: cohort_spread.append(max(cohort)-min(cohort))
            planned=[l.roomd.current+l.calibration_residual_ms for l in online]
            planned_spread.append(max(planned)-min(planned))
            tracking_error.extend(abs(relay.current-l.roomd.current) for l in online)
            if joiner is not None and joiner.online:
                cohort=[l.roomd.current for l in online if l is not joiner]
                if cohort: joiner_gap.append(abs(joiner.roomd.current-sum(cohort)/len(cohort)))
        relay_hist.append(relay.current)
        if stress_end is not None and t>=stress_end and first_recovery_to_10 is None:
            online=[l for l in listeners if l.online]
            if online and max(abs(relay.current-l.roomd.current) for l in online)<=10:
                first_recovery_to_10=(t-stress_end)/1000
    orig=listeners[:count]
    late_pct=sum(l.late for l in orig)/max(1,sum(l.frames for l in orig))*100
    hard=sum(l.hard for l in orig)
    tail_n=max(1,int(60000/STEP_MS)); relay_tail=relay_hist[-tail_n:]
    relay_total_variation=sum(abs(b-a) for a,b in zip(relay_hist,relay_hist[1:]))
    return {
        'evidence':EVIDENCE,'scenario':scenario,'listeners_initial':count,'late_join_probe':bool(late_join),'duration_s':duration_s,
        'relay_D_ms':stats(relay_hist),'relay_D_final_ms':relay.current,'relay_D_last60_range_ms':max(relay_tail)-min(relay_tail),'relay_D_total_variation_ms':relay_total_variation,
        'relay_target_ms':stats(relay_targets),'relay_direction_changes':relay.direction_changes,'relay_broadcasts':broadcast_count,
        'guest_local_D_ms':stats(local_d_values),'guest_local_D_spread_ms':stats(local_spread),'existing_cohort_D_spread_ms':stats(cohort_spread),'planned_deadline_spread_ms':stats(planned_spread),
        'guest_tracking_error_abs_ms':stats(tracking_error),'late_frame_pct':late_pct,'hard_resync_proxy_events':hard,
        'late_join_vs_existing_D_gap_ms':stats(joiner_gap),'recovery_to_within_10ms_of_relay_s':first_recovery_to_10,
        'resume_first_frame_lateness_proxy_ms':stats(resume_lateness_proxy),'disconnected_ms_total':sum(l.disconnected_ms for l in orig),
    }

def fixed_baseline(scenario,count=20,duration_s=180,seed=20261001):
    late=0; frames=0; hard=0
    for i in range(count):
        rr=random.Random(seed*1009+i*7919+sum(map(ord,scenario)))
        l=Listener(i,profile_for(scenario,i,0),rr)
        for k in range(int(duration_s*1000/STEP_MS)):
            t=k*STEP_MS
            if scenario=='reconnect_live_edge' and i in (0,1,2) and 60000<=t<61000: continue
            age,decode,conf=age_sample(l,scenario,t)
            lateness=age+decode-400; frames+=1
            if lateness>0:
                late+=1
                if lateness>=100: hard+=1
    return {'late_frame_pct':100*late/max(frames,1),'hard_resync_proxy_events':hard,'D_ms':400.0}

def markdown(results):
    lines=['# JLS Final Controller Simulation','', 'All quantitative values are **[SIMULATED]**. Network distributions are model inputs, not field measurements.','',
           '| Scenario | Relay D final ms | Relay D max | Local D p50 | Guest D spread max | Tracking error p95 | Late % | Hard-resync proxy | Late-join gap max | Resume late proxy max | Fixed-400 late % |',
           '|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
    for r in results:
        a=r['adaptive']; f=r['fixed']
        lines.append(f"| {r['scenario']} | {a['relay_D_final_ms']:.1f} | {a['relay_D_ms']['max']:.1f} | {a['guest_local_D_ms']['p50']:.1f} | {a['guest_local_D_spread_ms']['max']:.1f} | {a['guest_tracking_error_abs_ms']['p95']:.1f} | {a['late_frame_pct']:.3f} | {a['hard_resync_proxy_events']} | {a['late_join_vs_existing_D_gap_ms']['max']:.1f} | {a['resume_first_frame_lateness_proxy_ms']['max']:.1f} | {f['late_frame_pct']:.3f} |")
    return '\n'.join(lines)+'\n'

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--duration-s',type=float,default=180); ap.add_argument('--listeners',type=int,default=20); ap.add_argument('--seed',type=int,default=20261001); ap.add_argument('--json-out',default=''); ap.add_argument('--md-out',default='')
    a=ap.parse_args()
    scenarios=['clean_wifi','good_cellular','poor_cellular','mixed','jitter50','jitter200','packet_loss','short_outage','reconnect_live_edge']
    out=[{'scenario':s,'adaptive':run_scenario(s,a.listeners,a.duration_s,a.seed,True),'fixed':fixed_baseline(s,a.listeners,a.duration_s,a.seed)} for s in scenarios]
    payload={'evidence':EVIDENCE,'model':'production constants at youtube-live-sync 5726c9960c7cd1c5a714722fa012af7beca53a99','results':out}
    print(markdown(out))
    if a.json_out: Path(a.json_out).write_text(json.dumps(payload,indent=2),encoding='utf-8')
    if a.md_out: Path(a.md_out).write_text(markdown(out),encoding='utf-8')

if __name__=='__main__': main()
