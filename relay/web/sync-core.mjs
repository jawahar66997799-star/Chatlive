export const MS_PER_S = 1000;

export function clamp(v, lo, hi) {
  return Math.min(hi, Math.max(lo, v));
}

export function quantile(values, q) {
  if (!values.length) return NaN;
  const a = [...values].sort((x, y) => x - y);
  const pos = clamp(q, 0, 1) * (a.length - 1);
  const lo = Math.floor(pos), hi = Math.ceil(pos), f = pos - lo;
  return a[lo] * (1 - f) + a[hi] * f;
}

export function makeNtpSample({ t0Ms, t3Ms, serverRecvNs, serverSendNs }) {
  const s1 = Number(serverRecvNs) / 1e6;
  const s2 = Number(serverSendNs ?? serverRecvNs) / 1e6;
  const serverWorkMs = Math.max(0, s2 - s1);
  const rttMs = Math.max(0, (t3Ms - t0Ms) - serverWorkMs);
  const localMidMs = (t0Ms + t3Ms) / 2;
  const serverMidMs = (s1 + s2) / 2;
  const offsetMs = serverMidMs - localMidMs;
  return { t0Ms, t3Ms, s1Ms: s1, s2Ms: s2, localMidMs, serverMidMs, rttMs, offsetMs, degraded: serverSendNs == null };
}

function weightedFit(points, xKey, yKey, wKey, forcedSlope = null) {
  if (!points.length) return null;
  let sw = 0, sx = 0, sy = 0;
  for (const p of points) {
    const w = p[wKey] ?? 1;
    sw += w; sx += w * p[xKey]; sy += w * p[yKey];
  }
  if (!(sw > 0)) return null;
  const mx = sx / sw, my = sy / sw;
  if (forcedSlope != null) return { intercept: my - forcedSlope * mx, slope: forcedSlope, xMean: mx, yMean: my, sw };
  let sxx = 0, sxy = 0;
  for (const p of points) {
    const w = p[wKey] ?? 1;
    const dx = p[xKey] - mx;
    sxx += w * dx * dx;
    sxy += w * dx * (p[yKey] - my);
  }
  const slope = sxx > 1e-9 ? sxy / sxx : 1;
  return { intercept: my - slope * mx, slope, xMean: mx, yMean: my, sw };
}

export class ClockModel {
  constructor({ maxSamples = 64, maxAgeMs = 120000, minWindowMs = 1500 } = {}) {
    this.maxSamples = maxSamples;
    this.maxAgeMs = maxAgeMs;
    this.minWindowMs = minWindowMs;
    this.samples = [];
    this.model = null;
  }

  reset() {
    this.samples = [];
    this.model = null;
  }

  addExchange(x) {
    const s = makeNtpSample(x);
    this.samples.push(s);
    if (this.samples.length > this.maxSamples) this.samples.splice(0, this.samples.length - this.maxSamples);
    const newest = s.localMidMs;
    this.samples = this.samples.filter(v => newest - v.localMidMs <= this.maxAgeMs);
    this.refit();
    return s;
  }

  refit() {
    if (!this.samples.length) { this.model = null; return; }
    const rtts = this.samples.map(s => s.rttMs);
    const minRtt = Math.min(...rtts);
    const gate = minRtt + Math.max(3, minRtt * 0.35);
    let usable = this.samples.filter(s => s.rttMs <= gate);
    if (usable.length < Math.min(4, this.samples.length)) {
      usable = [...this.samples].sort((a,b)=>a.rttMs-b.rttMs).slice(0, Math.min(8, this.samples.length));
    }
    const latest = this.samples[this.samples.length - 1].localMidMs;
    const weighted = usable.map(s => {
      const age = Math.max(0, latest - s.localMidMs);
      const ageWeight = Math.exp(-age / 45000);
      const rttWeight = 1 / (9 + s.rttMs * s.rttMs);
      return { ...s, w: ageWeight * rttWeight };
    });
    const span = Math.max(...weighted.map(s=>s.localMidMs)) - Math.min(...weighted.map(s=>s.localMidMs));
    let fit = weightedFit(weighted, 'localMidMs', 'serverMidMs', 'w', weighted.length < 4 || span < this.minWindowMs ? 1 : null);
    if (!fit) return;
    if (Math.abs((fit.slope - 1) * 1e6) > 1000) {
      fit = weightedFit(weighted, 'localMidMs', 'serverMidMs', 'w', 1);
    }
    let sw = 0, sse = 0;
    for (const p of weighted) {
      const pred = fit.intercept + fit.slope * p.localMidMs;
      const e = p.serverMidMs - pred;
      sw += p.w; sse += p.w * e * e;
    }
    const residualRms = Math.sqrt(sse / Math.max(sw, 1e-12));
    const asymmetryBound = minRtt / 2;
    const confidence95Ms = asymmetryBound + 1.96 * residualRms;
    this.model = {
      interceptMs: fit.intercept,
      slope: fit.slope,
      driftPpm: (fit.slope - 1) * 1e6,
      minRttMs: minRtt,
      confidence95Ms,
      residualRmsMs: residualRms,
      accepted: weighted.length,
      total: this.samples.length,
      degradedFraction: weighted.filter(x=>x.degraded).length / Math.max(1, weighted.length),
    };
  }

  get ready() { return !!this.model; }
  serverAtLocal(localMs) {
    if (!this.model) return NaN;
    return this.model.interceptMs + this.model.slope * localMs;
  }
  localAtServer(serverMs) {
    if (!this.model) return NaN;
    return (serverMs - this.model.interceptMs) / this.model.slope;
  }
  snapshot() { return this.model ? { ...this.model } : null; }
}

export class TimelineTracker {
  constructor() { this.reset(); }
  reset() {
    this.epoch = null; this.anchorServerMs = 0; this.anchorSample = 0;
    this.nextSample = 0; this.lastSeq = null; this.lastSeqKey = null; this.lastTimelineMs = null;
  }
  ingest({ epoch, seq, serverMs, frames, sampleRate, sampleIndex = null, timelineServerMs = null }) {
    if (this.epoch !== epoch) {
      this.reset(); this.epoch = epoch;
      this.anchorServerMs = timelineServerMs ?? serverMs;
      this.anchorSample = sampleIndex ?? 0;
      this.nextSample = this.anchorSample;
    }
    const seqKey = typeof seq === 'bigint' ? seq : BigInt(seq);
    if (this.lastSeqKey != null && seqKey <= this.lastSeqKey) return { accepted:false, reason: seqKey === this.lastSeqKey ? 'duplicate' : 'out-of-order' };
    const startSample = sampleIndex ?? this.nextSample;
    const tMs = timelineServerMs ?? (this.anchorServerMs + (startSample - this.anchorSample) * 1000 / sampleRate);
    this.lastSeq = seq; this.lastSeqKey = seqKey;
    this.nextSample = startSample + frames;
    this.lastTimelineMs = tMs;
    return { accepted:true, epoch, seq, startSample, timelineServerMs:tMs, frames };
  }
}

export class SlewValue {
  constructor({initial=400, floor=150, ceiling=1000, upPerSec=25, downPerSec=6}={}) {
    this.floor=floor; this.ceiling=ceiling; this.upPerSec=upPerSec; this.downPerSec=downPerSec;
    this.current=clamp(initial,floor,ceiling); this.target=this.current; this.lastMs=null;
  }
  setTarget(v) { this.target=clamp(Number(v)||this.current,this.floor,this.ceiling); return this.target; }
  reset(v=this.target, nowMs=null) {
    this.target=clamp(Number(v)||this.current,this.floor,this.ceiling);
    this.current=this.target;
    this.lastMs=Number.isFinite(nowMs)?nowMs:null;
    return this.current;
  }
  tick(nowMs) {
    if(this.lastMs==null){this.lastMs=nowMs;return this.current;}
    const dt=clamp((nowMs-this.lastMs)/1000,0,1); this.lastMs=nowMs;
    if(this.target>this.current) this.current=Math.min(this.target,this.current+this.upPerSec*dt);
    else this.current=Math.max(this.target,this.current-this.downPerSec*dt);
    return this.current;
  }
}

export class AdaptiveDelay {
  constructor({ initialMs=400, floorMs=150, ceilingMs=1000, safetyMs=35, upSlewMsPerS=25, downSlewMsPerS=6 } = {}) {
    this.currentMs = initialMs; this.targetMs = initialMs;
    this.floorMs=floorMs; this.ceilingMs=ceilingMs; this.safetyMs=safetyMs;
    this.upSlew=upSlewMsPerS; this.downSlew=downSlewMsPerS;
    this.networkAge=[]; this.decode=[]; this.lastTickMs=null;
    this.lateBoostUntilMs = -Infinity;
  }
  observe({ networkAgeMs, decodeMs=0, clockConfidenceMs=0, nowMs=0 }) {
    if (Number.isFinite(networkAgeMs) && networkAgeMs >= 0 && networkAgeMs < 5000) {
      this.networkAge.push(networkAgeMs); if (this.networkAge.length>160) this.networkAge.shift();
    }
    if (Number.isFinite(decodeMs) && decodeMs >= 0 && decodeMs < 1000) {
      this.decode.push(decodeMs); if (this.decode.length>160) this.decode.shift();
    }
    if (this.networkAge.length >= 8) {
      const n95 = quantile(this.networkAge, .95);
      const d95 = this.decode.length ? quantile(this.decode, .95) : 0;
      this.targetMs = clamp(n95 + d95 + Math.max(0, clockConfidenceMs) + this.safetyMs, this.floorMs, this.ceilingMs);
    }
    this.tick(nowMs);
  }
  markLate(nowMs=0, severityMs=0) {
    this.targetMs = clamp(Math.max(this.targetMs, this.currentMs + Math.min(100, 20 + Math.max(0,severityMs)*.25)), this.floorMs, this.ceilingMs);
    this.lateBoostUntilMs = Math.max(this.lateBoostUntilMs, nowMs + 5000);
  }
  tick(nowMs) {
    if (this.lastTickMs == null) { this.lastTickMs = nowMs; return this.currentMs; }
    const dt = clamp((nowMs - this.lastTickMs)/1000, 0, 2);
    this.lastTickMs = nowMs;
    if (this.targetMs > this.currentMs) {
      const rate = nowMs < this.lateBoostUntilMs ? Math.max(this.upSlew, 60) : this.upSlew;
      this.currentMs = Math.min(this.targetMs, this.currentMs + rate*dt);
    } else {
      this.currentMs = Math.max(this.targetMs, this.currentMs - this.downSlew*dt);
    }
    return this.currentMs;
  }
}

export class PIController {
  constructor({ deadbandMs=1, kpPpmPerMs=18, kiPpmPerMsS=0.35, maxPpm=300, integralLimitMsS=500 } = {}) {
    Object.assign(this,{deadbandMs,kpPpmPerMs,kiPpmPerMsS,maxPpm,integralLimitMsS});
    this.integral = 0; this.lastPpm=0;
  }
  reset(){ this.integral=0; this.lastPpm=0; }
  update(errorMs, dtSec) {
    if (!Number.isFinite(errorMs)) return 1;
    const e = Math.abs(errorMs) <= this.deadbandMs ? 0 : errorMs - Math.sign(errorMs)*this.deadbandMs;
    this.integral = clamp(this.integral + e*clamp(dtSec,0,1), -this.integralLimitMsS, this.integralLimitMsS);
    const ppm = clamp(this.kpPpmPerMs*e + this.kiPpmPerMsS*this.integral, -this.maxPpm, this.maxPpm);
    this.lastPpm = ppm;
    return 1 + ppm/1e6;
  }
}

export function classifyPhaseError(errorMs, { hardResyncMs=100, deadbandMs=1 } = {}) {
  if (!Number.isFinite(errorMs)) return 'invalid';
  if (Math.abs(errorMs) >= hardResyncMs) return 'hard-resync';
  if (Math.abs(errorMs) <= deadbandMs) return 'deadband';
  return 'micro-correct';
}

export function bufferErrorMs(actualDepthMs, targetDepthMs) {
  return actualDepthMs - targetDepthMs;
}

export class ReconnectBackoff {
  constructor({initialMs=250,multiplier=1.7,maxMs=5000}={}){this.initialMs=initialMs;this.multiplier=multiplier;this.maxMs=maxMs;this.currentMs=initialMs;}
  next(){const out=this.currentMs;this.currentMs=Math.min(this.maxMs,Math.round(this.currentMs*this.multiplier));return out;}
  reset(){this.currentMs=this.initialMs;}
}

export function targetServerTimeMs(frameTimelineServerMs, delayMs) {
  return frameTimelineServerMs + delayMs;
}

export class OutputTimeMapper {
  constructor({ maxSamples=48 }={}) { this.maxSamples=maxSamples; this.samples=[]; this.model=null; }
  add({ performanceTimeMs, contextTimeSec }) {
    if (![performanceTimeMs,contextTimeSec].every(Number.isFinite)) return;
    this.samples.push({x:performanceTimeMs,y:contextTimeSec*1000,w:1});
    if (this.samples.length>this.maxSamples) this.samples.shift();
    if (this.samples.length >= 2) {
      let fit=weightedFit(this.samples,'x','y','w');
      if (fit && Math.abs((fit.slope-1)*1e6) < 3000) this.model=fit;
    }
  }
  contextTimeForPerformance(perfMs) {
    if (!this.model) return NaN;
    return (this.model.intercept + this.model.slope*perfMs)/1000;
  }
  snapshot(){ return this.model ? {interceptMs:this.model.intercept,slope:this.model.slope,driftPpm:(this.model.slope-1)*1e6,samples:this.samples.length}:null; }
}

export function fallbackContextTimeForPerformance(audioCurrentTimeSec, nowPerfMs, targetPerfMs, latencySec=0) {
  return audioCurrentTimeSec + (targetPerfMs-nowPerfMs)/1000 - Math.max(0, latencySec||0);
}

export class ServerInstanceTracker {
  constructor(){ this.id=null; this.changes=0; }
  reset(){ this.id=null; this.changes=0; }
  observe(next){
    const id=typeof next==='string'?next.trim():'';
    if(!id)return {changed:false,initial:false,id:this.id};
    if(this.id===null){this.id=id;return {changed:false,initial:true,id};}
    if(this.id===id)return {changed:false,initial:false,id};
    const previous=this.id;this.id=id;this.changes++;
    return {changed:true,initial:false,id,previous,changes:this.changes};
  }
}


export const GUEST_PIPELINE_STATES = Object.freeze({
  CONNECTING:'CONNECTING',
  RELAY_CONNECTED:'RELAY_CONNECTED',
  WAITING_FOR_HOST:'WAITING_FOR_HOST',
  HOST_ONLINE:'HOST_ONLINE',
  WAITING_FOR_AUDIO:'WAITING_FOR_AUDIO',
  AUDIO_RECEIVING:'AUDIO_RECEIVING',
  DECODER_READY:'DECODER_READY',
  BUFFERING:'BUFFERING',
  CLOCK_LOCKED:'CLOCK_LOCKED',
  PLAYING:'PLAYING',
  NETWORK_LOST:'NETWORK_LOST',
  HOST_STALLED:'HOST_STALLED',
  NO_BINARY_AUDIO:'NO_BINARY_AUDIO',
  DECODER_FAILED:'DECODER_FAILED',
  AUDIOCONTEXT_SUSPENDED:'AUDIOCONTEXT_SUSPENDED',
  AUTOPLAY_BLOCKED:'AUTOPLAY_BLOCKED',
  BUFFER_UNDERRUN:'BUFFER_UNDERRUN',
  RESYNCING:'RESYNCING',
  INVALID_LINK:'INVALID_LINK',
});

const finiteAge=(now,at)=>Number.isFinite(at)&&at>0?Math.max(0,now-at):Infinity;
const stateResult=(code,label,reason,action,tone='warn')=>({code,label,reason,action,tone});

/**
 * Evidence-driven guest UX state. This intentionally separates "scheduled"
 * from "audibly rendering": PLAYING requires recent non-silent output evidence.
 * It is pure so every transition can be regression-tested without a browser.
 */
export function deriveGuestPipelineState(e={},nowMs=0){
  const S=GUEST_PIPELINE_STATES;
  const now=Number.isFinite(nowMs)?nowMs:0;
  const relayAge=finiteAge(now,e.lastRelayMessageAt);
  const binaryAge=finiteAge(now,e.lastBinaryAt);
  const hostAge=finiteAge(now,e.hostOnlineAt);
  const outputAge=finiteAge(now,e.lastAudibleOutputAt);
  const directAge=finiteAge(now,e.lastDirectAudibleAt);
  const outputAudible=!!e.outputAudible || outputAge<=1100 || directAge<=1100;
  const bufferMs=Math.max(0,Number(e.bufferMs)||0);
  const targetDelayMs=Math.max(0,Number(e.targetDelayMs)||0);
  const minUsefulBuffer=Math.max(20,Math.min(120,targetDelayMs>0?targetDelayMs*0.18:80));
  const relayFresh=relayAge<=3500;
  const binaryFrames=Math.max(0,Number(e.binaryFrames)||0);
  const pcmFrames=Math.max(0,Number(e.pcmFrames)||0);
  const audioState=String(e.audioContextState||'none');

  if(!e.hasRoom)
    return stateResult(S.INVALID_LINK,S.INVALID_LINK,'The guest link does not contain a valid room token.','Open a fresh guest link from the host.','bad');

  if(e.networkOnline===false)
    return stateResult(S.NETWORK_LOST,S.NETWORK_LOST,'This device is offline.','Restore network access; reconnect and live-edge resume are automatic.','bad');

  if(!e.relayOpen){
    if(e.everRelayConnected)
      return stateResult(S.NETWORK_LOST,S.NETWORK_LOST,'The relay connection was lost.','Reconnecting automatically; stale backlog will not be replayed.','bad');
    return stateResult(S.CONNECTING,S.CONNECTING,'Opening the secure live connection.','No action is needed unless this persists.','warn');
  }

  if(!relayFresh && e.everRelayConnected)
    return stateResult(S.NETWORK_LOST,S.NETWORK_LOST,'The relay socket is open but no fresh relay traffic is arriving.','Replacing the stale connection automatically.','bad');

  if(!e.relayStateKnown)
    return stateResult(S.RELAY_CONNECTED,S.RELAY_CONNECTED,'The relay transport is connected.','Waiting for authoritative room state.','warn');

  if(e.hostOnline!==true)
    return stateResult(S.WAITING_FOR_HOST,S.WAITING_FOR_HOST,'The relay is reachable, but the host is offline.','Start or reconnect Jawahar Live Sync on the host phone.','warn');

  if(e.decoderFailed)
    return stateResult(S.DECODER_FAILED,S.DECODER_FAILED,e.decoderError||'The Opus decoder failed to initialize or decode.','Reload once; if it persists, inspect decoder diagnostics.','bad');

  if(e.relayStreamState==='HOST_STALLED'){
    const capture=e.hostCaptureState?(' Host reports '+e.hostCaptureState+'.'):'';
    return stateResult(S.HOST_STALLED,S.HOST_STALLED,'The relay says the host socket is alive but live audio frames have stalled.'+capture,'The guest will stay at the live edge and resume automatically when frames return.','bad');
  }

  if(binaryFrames>0 && binaryAge>3500 && relayFresh)
    return stateResult(S.HOST_STALLED,S.HOST_STALLED,'The host is still marked online, but live audio packets stopped arriving.','The guest will stay at the live edge while the host/relay path recovers.','bad');

  if(binaryFrames===0){
    if(hostAge<=600)
      return stateResult(S.HOST_ONLINE,S.HOST_ONLINE,'The host has just come online.','Waiting for its first live audio packet.','warn');
    if(hostAge<=2600)
      return stateResult(S.WAITING_FOR_AUDIO,S.WAITING_FOR_AUDIO,'The host is online, but no binary audio has arrived yet.','Start audible media on the host.','warn');
    return stateResult(S.NO_BINARY_AUDIO,S.NO_BINARY_AUDIO,'The host is online, but the guest has received no binary audio stream.','Check host playback capture and relay uplink diagnostics.','bad');
  }

  if(!e.decoderReady)
    return stateResult(S.AUDIO_RECEIVING,S.AUDIO_RECEIVING,'Live binary audio packets are arriving.','Starting the Opus decoder.','warn');

  if(pcmFrames===0)
    return stateResult(S.DECODER_READY,S.DECODER_READY,'The decoder is ready, but it has not produced PCM yet.','Waiting for a decodable live audio frame.','warn');

  if(!e.decodedSignal)
    return stateResult(S.WAITING_FOR_AUDIO,S.WAITING_FOR_AUDIO,'Audio frames decode correctly, but the decoded signal is silent.','Play audible media on the host and verify non-zero capture level.','warn');

  if(audioState!=='running'){
    if(e.unlockAttempted)
      return stateResult(S.AUDIOCONTEXT_SUSPENDED,S.AUDIOCONTEXT_SUSPENDED,`Browser audio is ${audioState} after playback was enabled.`,'Tap RESUME LISTENING if the browser requires a new gesture.','bad');
    return stateResult(S.AUTOPLAY_BLOCKED,S.AUTOPLAY_BLOCKED,'Decoded live audio is ready, but browser autoplay policy has not released the audio output.','Tap TO LISTEN once.','warn');
  }

  if(Number.isFinite(e.resyncUntil)&&e.resyncUntil>now)
    return stateResult(S.RESYNCING,S.RESYNCING,e.resyncReason||'A large timing or output-route change is being corrected.','Keep listening; stale timing state is being discarded and rebuilt.','warn');

  if(outputAudible){
    const mode=e.continuityMode?'continuity fallback':'synchronized output';
    const syncSuffix=e.clockLocked?'Clock is locked.':'Audio is audible while precision clock lock finishes.';
    return stateResult(S.PLAYING,S.PLAYING,`Non-silent audio is rendering through the ${mode}. ${syncSuffix}`,'No action needed.','ok');
  }

  const underrunAge=finiteAge(now,e.lastUnderrunAt);
  if(underrunAge<=1200)
    return stateResult(S.BUFFER_UNDERRUN,S.BUFFER_UNDERRUN,'The output buffer ran dry before the next live PCM block arrived.','Continuity recovery is active; the jitter recommendation will increase.','bad');

  if(!e.clockLocked)
    return stateResult(S.BUFFERING,`BUFFERING ${Math.round(bufferMs)} ms`,'Decoded audio is available while the relay clock model is still converging.','Keeping a bounded live buffer while clock samples lock.','warn');

  if(!e.schedulerReady || (!e.continuityMode && bufferMs<minUsefulBuffer))
    return stateResult(S.BUFFERING,`BUFFERING ${Math.round(bufferMs)} ms`,`Clock is locked; the output scheduler is filling toward a safe live playout depth (target ${Math.round(targetDelayMs)} ms).`,'Playback will begin only after scheduled output has real audio to render.','warn');

  return stateResult(S.CLOCK_LOCKED,S.CLOCK_LOCKED,'Relay clock and room timeline are locked, and audio is scheduled for output.','Waiting for the first non-silent rendered output sample.','warn');
}
