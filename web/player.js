import {ClockModel,TimelineTracker,AdaptiveDelay,SlewValue,OutputTimeMapper,ServerInstanceTracker,targetServerTimeMs,fallbackContextTimeForPerformance,deriveGuestPipelineState,deriveGuestContinuityState} from './sync-core.mjs';

const q=s=>document.querySelector(s);
const ui={room:q('#room'),state:q('#state'),reason:q('#reason'),dot:q('#dot'),join:q('#join'),note:q('#note'),diag:q('#diagText')};
const room=location.pathname.startsWith('/r/')?decodeURIComponent(location.pathname.slice(3)):(new URLSearchParams(location.search).get('room')||'');
ui.room.textContent=room?(room.length>12?room.slice(0,6)+'…'+room.slice(-4):'private room'):'invalid link';

const clock=new ClockModel(),timeline=new TimelineTracker();
const suggestedD=new AdaptiveDelay({initialMs:850,floorMs:400,ceilingMs:1000});
const roomD=new SlewValue({initial:850,floor:400,ceiling:1000,upPerSec:.25,downPerSec:.15});
const serverTracker=new ServerInstanceTracker();
let outputMap=new OutputTimeMapper(),roomTimeline=null;
let ws=null,reconnectTimer=null,backoff=100,generation=0,clockTimer=null,pingId=0,pings=new Map(),stableOpenTimer=null;
let lastRelayMessageAt=0,lastBinaryAt=0,transportWatchdogTimer=null;
let relayStateKnown=false,everRelayConnected=false,hostOnlineAt=0,firstBinaryAt=0,binaryFrames=0,lastPcmAt=0,pcmFrames=0;
let decoderFailed=false,decoderError='',unlockAttempted=false,resyncUntil=0,resyncReason='',guestTooSlowUntil=0;
let lastAudibleOutputAt=0,lastDirectAudibleAt=0,directAudibleFromPerf=0,directAudibleUntilPerf=0,currentPipeline=null,pipelineStateSince=0,pipelineTransitions=0;
let audio=null,node=null,decoder=null,sabWriter=null,joined=false,hostOnline=false,currentEpoch=null,wakeLock=null;
let relayStreamState='CONNECTED_NO_HOST',hostCaptureState='';
let audioEngineRebuilds=0,audioEngineRebuilding=false;
let directGain=null,directNextTime=null,directSources=new Set();
let roomDNeedsAuthoritativeSnap=true;
let fallbackPlayback=false,fallbackNextTargetFrame=null,decodedAudibleSince=null;
let fallbackEnteredAt=null,precisionRecoveryGraceUntil=0,lastPrecisionRecoveryAttempt=0;
let underrunWindowStart=0,underrunBurstCount=0,forceContinuityOnNextPcm=false,lastUnderrunAt=0;
let sampleRate=48000,channels=2,codec='opus',lastEpoch=null,lastSeq=null,lastOutputLatency=null;
const metrics={rttMs:null,clockOffsetMs:null,clockDriftPpm:null,clockConfidenceMs:null,bufferMs:0,targetDelayMs:850,recommendedDelayMs:850,lateFrames:0,decoderMs:0,decodedRmsDb:-120,decodedPeakDb:-120,outputRmsDb:-120,outputPeakDb:-120,workletActive:false,workletAlive:false,workletQuanta:0,workletProcessorErrors:0,schedulerErrors:0,lastSchedulerError:'',audioEngineRebuilds:0,directPlayback:false,directSources:0,directScheduledFrames:0,scheduledFrames:0,fallbackPlayback:false,playoutGate:'starting',faultCode:'STARTING',faultMessage:'Starting guest pipeline',faultAction:'Wait for the room to connect.',selfHeals:0,underruns:0,underrunBursts:0,continuityRecoveries:0,resamplerPpm:0,hardResyncs:0,outputLatencyMs:null,baseLatencyMs:null,reconnects:0,transportWatchdogReconnects:0,lastRelayAgeMs:null,lastBinaryAgeMs:null,overruns:0,staleDrops:0,decoder:'starting',audioState:'none',epoch:null,seq:null,serverInstanceId:null,serverRestarts:0,relayStreamState:'CONNECTED_NO_HOST',hostCaptureState:'',crossOriginIsolated:!!self.crossOriginIsolated};
Object.assign(metrics,{pipelineState:'CONNECTING',pipelineLabel:'CONNECTING',pipelineReason:'Opening the secure live connection.',pipelineAction:'',pipelineStateSinceMs:0,pipelineTransitions:0,binaryFrames:0,pcmFrames:0,clockLocked:false});
self.__JLS_METRICS__=metrics;

function setState(s,k='warn'){
  // Older event handlers may still emit hints, but once the canonical pipeline
  // exists they are diagnostics only and can never replace the truthful state.
  metrics.legacyStateHint=String(s||'');
  if(currentPipeline){applyPipelineState();return;}
  if(/^Listening\b/i.test(String(s||'')))return;
  ui.state.textContent=s;ui.dot.className='dot '+k;
}
function note(s){ui.note.textContent=s}

function markResync(reason,durationMs=1000){
  const now=performance.now();
  resyncUntil=Math.max(resyncUntil,now+Math.max(250,durationMs));
  resyncReason=String(reason||'Timing is being realigned.');
}
function isClockLocked(){
  const c=clock.snapshot();
  if(!c||c.accepted<4||c.total<4||!Number.isFinite(c.confidence95Ms))return false;
  const maxConfidence=Math.max(25,Math.min(150,(Number(roomD.current)||400)*.25));
  return c.confidence95Ms<=maxConfidence&&Number.isFinite(c.driftPpm);
}
function directOutputAudible(now=performance.now()){
  const active=!!audio&&audio.state==='running'&&directAudibleFromPerf>0&&now>=directAudibleFromPerf&&now<=directAudibleUntilPerf;
  if(active)lastDirectAudibleAt=now;
  return active;
}
function resetAudioEvidence(){
  firstBinaryAt=0;lastBinaryAt=0;binaryFrames=0;lastPcmAt=0;pcmFrames=0;
  lastAudibleOutputAt=0;lastDirectAudibleAt=0;directAudibleFromPerf=0;directAudibleUntilPerf=0;
  metrics.binaryFrames=0;metrics.pcmFrames=0;
}
function pipelineEvidence(now=performance.now()){
  return {
    hasRoom:!!room,
    networkOnline:navigator.onLine!==false,
    relayOpen:!!ws&&ws.readyState===WebSocket.OPEN,
    everRelayConnected,relayStateKnown,lastRelayMessageAt,
    hostOnline,hostOnlineAt,relayStreamState,hostCaptureState,
    binaryFrames,lastBinaryAt,
    decoderReady:metrics.decoder!=='starting'&&metrics.decoder!=='opus-unavailable'&&!decoderFailed,
    decoderFailed,decoderError,pcmFrames,lastPcmAt,
    decodedSignal:metrics.decodedPeakDb>-90||metrics.decodedRmsDb>-90,
    audioContextState:audio?.state||'none',unlockAttempted,
    clockLocked:isClockLocked(),bufferMs:metrics.bufferMs,targetDelayMs:metrics.targetDelayMs,
    schedulerReady:metrics.scheduledFrames>0&&(metrics.workletAlive||metrics.directPlayback),
    outputAudible:(lastAudibleOutputAt>0&&now-lastAudibleOutputAt<=1100)||directOutputAudible(now),
    lastAudibleOutputAt,lastDirectAudibleAt,lastUnderrunAt,
    resyncUntil,resyncReason,
    continuityMode:metrics.fallbackPlayback||metrics.directPlayback,guestTooSlowUntil,
  };
}
function applyPipelineState(now=performance.now()){
  const evidence=pipelineEvidence(now);
  const p=deriveGuestPipelineState(evidence,now);
  const continuity=deriveGuestContinuityState(evidence,now);
  if(!currentPipeline||currentPipeline.code!==p.code){pipelineTransitions++;pipelineStateSince=now;}
  currentPipeline=p;
  metrics.pipelineState=p.code;metrics.pipelineLabel=p.label;metrics.pipelineReason=p.reason;metrics.pipelineAction=p.action;
  metrics.pipelineStateSinceMs=pipelineStateSince;metrics.pipelineTransitions=pipelineTransitions;metrics.binaryFrames=binaryFrames;metrics.pcmFrames=pcmFrames;metrics.clockLocked=isClockLocked();
  metrics.continuityState=continuity.code;
  ui.state.textContent=continuity.label;ui.dot.className='dot '+(continuity.tone==='ok'?'ok':continuity.tone==='bad'?'bad':'');
  if(ui.reason)ui.reason.textContent=p.code+': '+p.reason;
  ui.note.textContent=continuity.action+(p.code!==continuity.code?' '+p.action:'');
  if(continuity.code==='LIVE'){ui.join.disabled=true;ui.join.textContent='AUDIO ENABLED';}
  else if(p.code==='AUTOPLAY_BLOCKED'){ui.join.disabled=false;ui.join.textContent='TAP TO LISTEN';}
  else if(p.code==='AUDIOCONTEXT_SUSPENDED'){ui.join.disabled=false;ui.join.textContent='RESUME AUDIO';}
  else if(audio?.state==='running'&&joined){ui.join.disabled=true;ui.join.textContent='AUDIO ENABLED';}
  else if(p.code==='INVALID_LINK'){ui.join.disabled=true;}
  return p;
}
function showContinuityState(){applyPipelineState();}
function repairJoinState(){
  if(!audio||!node||audio.state!=='running')return false;
  if(joined)return true;
  joined=true;metrics.selfHeals++;metrics.playoutGate='audio join auto-recovered';
  ui.join.disabled=true;ui.join.textContent='AUDIO ENABLED';
  note('Audio join state recovered automatically. Live playback is starting.');
  applyPipelineState();
  return true;
}
function diagnosePipeline(){
  const p=applyPipelineState();
  metrics.faultCode=p.code;metrics.faultMessage=p.reason;metrics.faultAction=p.action;
  return {code:p.code,message:p.reason,action:p.action};
}
function applyDiagnosisState(){applyPipelineState();}

function wsURL(){
  const base=(location.protocol==='https:'?'wss:':'ws:')+'//'+location.host;
  const u=new URL(base+'/v1/ws/guest/'+encodeURIComponent(room));
  if(lastEpoch!==null&&lastSeq!==null){u.searchParams.set('epoch',lastEpoch);u.searchParams.set('seq',lastSeq)}
  return u.href;
}

function startDecoder(){
  if(decoder)return;
  decoder=new Worker('/decoder-worker.js');
  decoder.onmessage=e=>{
    try{onDecoded(e.data||{})}
    catch(err){
      metrics.schedulerErrors++;metrics.lastSchedulerError=String(err?.stack||err?.message||err);
      metrics.playoutGate='scheduler exception';
      note('SCHEDULER_EXCEPTION: '+metrics.lastSchedulerError.slice(0,240));
      if(metrics.decodedRmsDb>-70)void rebuildAudioEngine('scheduler exception');
    }
  };
  decoder.postMessage({type:'init',codec,sampleRate,channels,wasmUrl:'/vendor/libopus-wasm/index.js'});
}
startDecoder();

class SabWriter{
  constructor(n,cap=192000){
    this.n=n;this.cap=cap;this.pcmSab=new SharedArrayBuffer(cap*2*4);this.ctrlSab=new SharedArrayBuffer(16);
    this.pcm=new Float32Array(this.pcmSab);this.ctrl=new Int32Array(this.ctrlSab);
    n.port.postMessage({type:'sab-init',pcmSab:this.pcmSab,ctrlSab:this.ctrlSab,capacityFrames:cap});
  }
  reset(){Atomics.store(this.ctrl,0,0);Atomics.store(this.ctrl,1,0)}
  write(pcm,m){
    let w=Atomics.load(this.ctrl,0),r=Atomics.load(this.ctrl,1);
    if(w<r){this.reset();w=0;r=0}
    if(m.frames>this.cap-(w-r))return false;
    const s=w%this.cap,f=Math.min(m.frames,this.cap-s);
    this.pcm.set(pcm.subarray(0,f*2),s*2);if(f<m.frames)this.pcm.set(pcm.subarray(f*2),0);
    Atomics.store(this.ctrl,0,w+m.frames);
    this.n.port.postMessage({type:'sab-block',targetFrame:m.targetFrame,startSample:m.startSample,frames:m.frames,absStart:w});
    return true;
  }
}

async function ensureAudio(){
  if(audio&&node)return;
  const AC=self.AudioContext||self.webkitAudioContext;if(!AC)throw new Error('Web Audio unsupported');
  try{if(navigator.audioSession&&'type' in navigator.audioSession)navigator.audioSession.type='playback'}catch{}
  try{audio=new AC({sampleRate:48000,latencyHint:'interactive'})}catch{audio=new AC({latencyHint:'interactive'})}
  await audio.audioWorklet.addModule('/worklet.js');
  node=new AudioWorkletNode(audio,'jawahar-sync-processor',{numberOfInputs:0,numberOfOutputs:1,outputChannelCount:[2],channelCount:2,channelCountMode:'explicit',channelInterpretation:'speakers'});
  node.connect(audio.destination);node.port.onmessage=e=>onWorklet(e.data||{});
  node.addEventListener('processorerror',()=>{
    metrics.workletProcessorErrors++;metrics.playoutGate='AudioWorklet processor error';
    note('WORKLET_PROCESSOR_ERROR: rebuilding browser audio engine automatically.');
    void rebuildAudioEngine('worklet processor error');
  });
  node.port.postMessage({type:'config',sourceRate:sampleRate,hardResyncMs:100,maxPpm:300,deadbandMs:1});
  if(self.crossOriginIsolated&&typeof SharedArrayBuffer!=='undefined'){try{sabWriter=new SabWriter(node)}catch{sabWriter=null}}
  metrics.audioState=audio.state;sampleOutputClock();
  audio.addEventListener('statechange',()=>{
    metrics.audioState=audio.state;
    if((audio.state==='suspended'||audio.state==='interrupted')&&joined){ui.join.disabled=false;ui.join.textContent='RESUME AUDIO'}
    applyPipelineState();
  });
}

async function rebuildAudioEngine(reason){
  if(audioEngineRebuilding)return;
  audioEngineRebuilding=true;
  try{
    stopDirectPlayback();try{node?.disconnect()}catch{}
    try{await audio?.close?.()}catch{}
    node=null;audio=null;sabWriter=null;directGain=null;outputMap=new OutputTimeMapper();fallbackNextTargetFrame=null;
    metrics.workletAlive=false;metrics.workletActive=false;
    await ensureAudio();
    await audio.resume();
    if(audio.state!=='running')throw new Error('AudioContext '+audio.state);
    joined=true;audioEngineRebuilds++;metrics.audioEngineRebuilds=audioEngineRebuilds;metrics.selfHeals++;
    ui.join.disabled=true;ui.join.textContent='AUDIO ENABLED';
    metrics.playoutGate='audio engine rebuilt: '+reason;
    note('Browser audio engine rebuilt automatically. Live playback is resuming.');
  }catch(err){
    metrics.schedulerErrors++;metrics.lastSchedulerError='rebuild: '+String(err?.message||err);
    ui.join.disabled=false;ui.join.textContent='TAP TO LISTEN';
    setState('Tap to recover audio','warn');
  }finally{audioEngineRebuilding=false}
}

function resetPlayout(reason){
  timeline.reset();currentEpoch=null;outputMap=new OutputTimeMapper();fallbackNextTargetFrame=null;decodedAudibleSince=null;
  if(reason==='host-offline'||reason==='epoch'||reason==='server-restart')resetAudioEvidence();
  if(reason==='epoch'||reason==='server-restart'||reason==='resume')markResync(reason,1200);
  fallbackPlayback=false;metrics.fallbackPlayback=false;fallbackEnteredAt=null;precisionRecoveryGraceUntil=0;lastPrecisionRecoveryAttempt=0;
  stopDirectPlayback();sabWriter?.reset();node?.port.postMessage({type:'reset'});
  // Playout resets must never tear down the Opus decoder. In WebCodecs,
  // AudioDecoder.reset() returns the decoder to an unconfigured state; doing
  // that on the user's Listen gesture caused decoded audio to stop before any
  // PCM reached the scheduler. Transport generation/epoch checks already
  // discard stale decoded output safely.
  if(reason==='server-restart'||reason==='reconnect'||reason==='host-offline')roomTimeline=null;
  if(reason==='epoch')note('Host started a fresh stream. Re-aligning…');
  else if(reason==='server-restart')note('Relay restarted. Rebuilding the clock and live timeline…');
  else note('Re-aligning to the live timeline…');
}

function observeServerInstance(id){
  const obs=serverTracker.observe(id);
  if(obs.id)metrics.serverInstanceId=obs.id;
  if(!obs.changed)return false;
  metrics.serverRestarts=serverTracker.changes;
  clock.reset();pings.clear();lastEpoch=null;lastSeq=null;
  roomDNeedsAuthoritativeSnap=true;
  resetPlayout('server-restart');
  return true;
}

function connect(force=false){
  clearTimeout(reconnectTimer);
  if(!room){setState('Invalid guest link','bad');note('Ask the host for a fresh Jawahar Live Sync link.');ui.join.disabled=true;return;}
  if(!force&&ws&&(ws.readyState===WebSocket.OPEN||ws.readyState===WebSocket.CONNECTING))return;
  if(force&&ws){try{ws.close(4001,'refresh transport')}catch{}}
  generation++;const gen=generation;
  clock.reset();pings.clear();
  metrics.rttMs=null;metrics.clockOffsetMs=null;metrics.clockDriftPpm=null;metrics.clockConfidenceMs=null;
  setState(ws?'Reconnecting…':'Connecting…','warn');
  try{ws=new WebSocket(wsURL())}catch{scheduleReconnect();return}
  ws.binaryType='arraybuffer';
  ws.onopen=()=>{if(gen!==generation)return;lastRelayMessageAt=performance.now();everRelayConnected=true;ui.join.disabled=false;clockBurst();clearInterval(clockTimer);clockTimer=setInterval(sendClock,1000);clearTimeout(stableOpenTimer);stableOpenTimer=setTimeout(()=>{if(gen===generation&&ws?.readyState===WebSocket.OPEN)backoff=100},5000);startTransportWatchdog();applyPipelineState()};
  ws.onmessage=e=>{if(gen!==generation)return;lastRelayMessageAt=performance.now();if(typeof e.data==='string')onControl(e.data);else if(e.data instanceof ArrayBuffer){const t=performance.now();lastBinaryAt=t;if(!firstBinaryAt)firstBinaryAt=t;binaryFrames++;metrics.binaryFrames=binaryFrames;decoder.postMessage({type:'frame',buffer:e.data,generation:gen,arrivalPerfMs:t},[e.data])}};
  ws.onclose=e=>{
    if(gen!==generation)return;
    clearInterval(clockTimer);clockTimer=null;clearTimeout(stableOpenTimer);stableOpenTimer=null;stopTransportWatchdog();
    // Continuity-first reconnect: do NOT flush the AudioWorklet/direct queue.
    // Keep lastEpoch/lastSeq so the relay recovery ring can replay only the gap.
    const carryingAudio=joined&&audio?.state==='running'&&(metrics.directPlayback||metrics.workletActive||metrics.bufferMs>80);
    const tooSlow=String(e?.reason||'').includes('GUEST_TOO_SLOW');
    if(tooSlow){
      guestTooSlowUntil=performance.now()+2500;
      metrics.faultCode='GUEST_TOO_SLOW';
      setState('GUEST_TOO_SLOW · rejoining live','warn');
      note('This listener fell behind the live edge. Stale queued audio was dropped and a fresh live-edge connection is starting.');
    }else if(navigator.onLine===false){
      setState(carryingAudio?'RECONNECTING · buffered audio playing':'RECONNECTING · network offline',carryingAudio?'warn':'bad');
      note('Network interrupted; buffered audio is preserved while reconnecting automatically.');
    }else{
      setState(carryingAudio?'RECONNECTING · buffered audio playing':'RECONNECTING','warn');
      note('Connection interrupted; recovery is automatic and stale backlog will not be replayed.');
    }
    applyPipelineState();
    scheduleReconnect();
  };
  ws.onerror=()=>{};
}
function scheduleReconnect(){clearTimeout(reconnectTimer);metrics.reconnects++;const base=backoff;const d=Math.max(70,Math.round(base*(.65+Math.random()*.7)));backoff=Math.min(2500,Math.max(100,Math.round(backoff*1.6)));reconnectTimer=setTimeout(()=>connect(),d)}
function stopTransportWatchdog(){clearInterval(transportWatchdogTimer);transportWatchdogTimer=null}
function startTransportWatchdog(){
  stopTransportWatchdog();
  transportWatchdogTimer=setInterval(()=>{
    if(!ws||ws.readyState!==WebSocket.OPEN)return;
    const now=performance.now();
    const relayAge=lastRelayMessageAt?now-lastRelayMessageAt:Infinity;
    const binaryAge=lastBinaryAt?now-lastBinaryAt:null;
    metrics.lastRelayAgeMs=Number.isFinite(relayAge)?relayAge:null;
    metrics.lastBinaryAgeMs=Number.isFinite(binaryAge)?binaryAge:null;
    // Clock replies arrive every ~1.5 s even during silence. If the socket goes
    // half-open across Wi-Fi/cellular handover, replace it proactively instead
    // of waiting for the browser/TCP timeout.
    if(relayAge>3500){
      metrics.transportWatchdogReconnects++;metrics.selfHeals++;
      note('Transport stalled; reconnecting automatically at the live edge.');
      connect(true);
    }
  },1000);
}
function clockBurst(){for(let i=0;i<12;i++)setTimeout(sendClock,i*120)}
function sendClock(){
  if(!ws||ws.readyState!==WebSocket.OPEN)return;
  const id=String(++pingId),t0=performance.now();pings.set(id,t0);if(pings.size>64)pings.delete(pings.keys().next().value);
  ws.send(JSON.stringify({type:'clock_req',v:1,id,t0_guest_ns:Math.round(t0*1e6)}));
}

function useClock(t0,t3,t1,t2){
  clock.addExchange({t0Ms:t0,t3Ms:t3,serverRecvNs:t1,serverSendNs:t2});
  const c=clock.snapshot();if(!c)return;
  metrics.rttMs=c.minRttMs;metrics.clockDriftPpm=c.driftPpm;metrics.clockConfidenceMs=c.confidence95Ms;metrics.clockOffsetMs=clock.serverAtLocal(performance.now())-performance.now();
}
function onControl(text){
  let m;try{m=JSON.parse(text)}catch{return}
  if(m.type==='clock_resp'){
    observeServerInstance(m.server_instance_id);
    const id=String(m.id),t0=pings.get(id);if(t0==null)return;pings.delete(id);useClock(t0,performance.now(),m.t1_server_ns,m.t2_server_ns);return;
  }
  if(m.type==='pong'){
    const id=String(m.id),t0=pings.get(id);if(t0==null)return;pings.delete(id);const sn=m.server_ns??m.serverNs;if(sn!=null)useClock(t0,performance.now(),sn,null);return;
  }
  if(m.type==='state'){
    observeServerInstance(m.server_instance_id);
    relayStateKnown=true;const wasHostOnline=hostOnline;hostOnline=!!m.host_online;if(hostOnline&&!wasHostOnline)hostOnlineAt=performance.now();if(!hostOnline)hostOnlineAt=0;const tl=m.timeline||{};
    relayStreamState=String(m.stream_state||(hostOnline?(m.timeline_ready===false?'HOST_CONNECTED_NO_AUDIO':'AUDIO_FLOWING'):'CONNECTED_NO_HOST'));
    hostCaptureState=String(m.host_capture_state||'');
    metrics.relayStreamState=relayStreamState;metrics.hostCaptureState=hostCaptureState;
    if(m.epoch!=null){const announced=String(m.epoch);if(currentEpoch!==null&&announced!==currentEpoch)resetPlayout('epoch');currentEpoch=announced;metrics.epoch=announced;}
    sampleRate=Number(tl.sample_rate)||sampleRate;channels=Number(tl.channels)||channels;codec=tl.codec||'opus';
    const timelineReady=m.timeline_ready!==false&&Number(tl.origin_server_ns)>0;
    if(timelineReady&&tl.origin_server_ns!=null&&tl.origin_sample_position!=null)roomTimeline={originServerMs:Number(tl.origin_server_ns)/1e6,originSample:Number(tl.origin_sample_position),sampleRate};else roomTimeline=null;
    const d=Number(tl.recommended_delay_ns);if(Number.isFinite(d)&&d>0){const ms=d/1e6;if(roomDNeedsAuthoritativeSnap||roomD.lastMs==null){roomD.reset(ms,performance.now());roomDNeedsAuthoritativeSnap=false}else roomD.setTarget(ms)}
    decoder.postMessage({type:'init',codec,sampleRate,channels,wasmUrl:'/vendor/libopus-wasm/index.js'});node?.port.postMessage({type:'config',sourceRate:sampleRate});
    if(relayStreamState==='CONNECTED_NO_HOST'||!hostOnline||m.reason==='host_offline'){
      resetPlayout('host-offline');
      showContinuityState(false);
    }else if(relayStreamState==='HOST_STALLED'||relayStreamState==='HOST_CONNECTED_NO_AUDIO'){
      showContinuityState(false);
    }else if(joined){
      const healthyOutput=metrics.directPlayback||metrics.workletActive||metrics.bufferMs>40;
      showContinuityState(healthyOutput);
    }else{
      showContinuityState(false);
    }
    return;
  }
  if(m.type==='hello'){
    relayStateKnown=true;const wasHostOnline=hostOnline;hostOnline=!!m.hostOnline;if(hostOnline&&!wasHostOnline)hostOnlineAt=performance.now();if(!hostOnline)hostOnlineAt=0;relayStreamState=hostOnline?'AUDIO_FLOWING':'CONNECTED_NO_HOST';metrics.relayStreamState=relayStreamState;sampleRate=Number(m.sampleRate)||48000;channels=Number(m.channels)||2;codec=m.codec||'pcm16le';
    const d=Math.max(150,Math.min(1000,Number(m.targetDelayMs)||400));roomD.reset(d,performance.now());roomDNeedsAuthoritativeSnap=false;
    decoder.postMessage({type:'init',codec,sampleRate,channels,wasmUrl:'/vendor/libopus-wasm/index.js'});node?.port.postMessage({type:'config',sourceRate:sampleRate});
    if(!hostOnline)showContinuityState(false);
    else if(joined)showContinuityState(metrics.directPlayback||metrics.workletActive||metrics.bufferMs>40);
    else showContinuityState(false);
    return;
  }
  if(m.type==='host-offline'){relayStateKnown=true;hostOnline=false;hostOnlineAt=0;relayStreamState='CONNECTED_NO_HOST';metrics.relayStreamState=relayStreamState;resetPlayout('host-offline');applyPipelineState()}
  if(m.type==='host-online'){
    relayStateKnown=true;const wasHostOnline=hostOnline;hostOnline=true;if(!wasHostOnline)hostOnlineAt=performance.now();relayStreamState='HOST_CONNECTED_NO_AUDIO';metrics.relayStreamState=relayStreamState;
    showContinuityState(false);
  }
}

function ensureDirectGain(){
  if(!audio)return null;
  if(directGain)return directGain;
  directGain=audio.createGain();directGain.gain.value=1;directGain.connect(audio.destination);
  return directGain;
}

function stopDirectPlayback(){
  for(const s of directSources){try{s.stop()}catch{} try{s.disconnect()}catch{}}
  directSources.clear();directNextTime=null;directAudibleFromPerf=0;directAudibleUntilPerf=0;metrics.directSources=0;metrics.directPlayback=false;
  try{directGain?.disconnect()}catch{} directGain=null;
}

function scheduleDirectBuffer(m,reason='worklet unavailable'){
  if(!audio||audio.state!=='running'||!m?.pcm||!Number.isFinite(m.frames)||m.frames<=0)return false;
  try{
    const sourceRate=Number(m.sampleRate)||sampleRate||48000;
    const buffer=audio.createBuffer(2,m.frames,sourceRate);
    const l=buffer.getChannelData(0),r=buffer.getChannelData(1);
    for(let i=0;i<m.frames;i++){l[i]=m.pcm[i*2]||0;r[i]=m.pcm[i*2+1]||0}
    const src=audio.createBufferSource();src.buffer=buffer;src.connect(ensureDirectGain());
    const now=audio.currentTime;
    if(!Number.isFinite(directNextTime)||directNextTime<now+.120)directNextTime=now+.700;
    const start=directNextTime;directNextTime+=m.frames/sourceRate;
    directSources.add(src);metrics.directSources=directSources.size;metrics.directPlayback=true;metrics.directScheduledFrames++;
    src.onended=()=>{directSources.delete(src);metrics.directSources=directSources.size;try{src.disconnect()}catch{}};
    src.start(start);
    if((Number(m.decodedPeakDb)||metrics.decodedPeakDb)>-90){
      const perfNow=performance.now(),startPerf=perfNow+Math.max(0,start-now)*1000,endPerf=startPerf+(m.frames/sourceRate)*1000;
      if(directAudibleUntilPerf<perfNow)directAudibleFromPerf=startPerf;
      directAudibleUntilPerf=Math.max(directAudibleUntilPerf,endPerf);
    }
    metrics.playoutGate='DIRECT BUFFER playing: '+reason;
    if(hostOnline)showContinuityState(true,' · compatibility mode');
    return true;
  }catch(err){
    metrics.schedulerErrors++;metrics.lastSchedulerError='direct-buffer: '+String(err?.message||err);
    note('DIRECT_BUFFER_ERROR: '+metrics.lastSchedulerError.slice(0,240));
    return false;
  }
}

function enterSafeLocal(reason){
  if(fallbackPlayback)return;
  fallbackPlayback=true;metrics.fallbackPlayback=true;fallbackNextTargetFrame=null;
  fallbackEnteredAt=performance.now();lastPrecisionRecoveryAttempt=0;
  sabWriter?.reset();node?.port.postMessage({type:'reset'});
  metrics.playoutGate='SAFE LOCAL: '+reason;
  note('Precision sync is not producing output; using safe local playback while the clock recovers.');
}

function exitSafeLocalForPrecision(targetPerf,reason='clock/timeline recovered'){
  const now=performance.now();
  if(metrics.directPlayback&&directGain&&audio){
    try{
      const t=audio.currentTime;
      directGain.gain.cancelScheduledValues(t);
      directGain.gain.setValueAtTime(directGain.gain.value,t);
      directGain.gain.linearRampToValueAtTime(0,t+.06);
      setTimeout(()=>stopDirectPlayback(),90);
    }catch{stopDirectPlayback()}
  }
  fallbackPlayback=false;metrics.fallbackPlayback=false;fallbackNextTargetFrame=null;
  fallbackEnteredAt=null;lastPrecisionRecoveryAttempt=now;
  precisionRecoveryGraceUntil=now+Math.max(1200,(Number.isFinite(targetPerf)?Math.max(0,targetPerf-now):0)+700);
  timeline.reset();sabWriter?.reset();node?.port.postMessage({type:'reset'});
  metrics.selfHeals++;
  metrics.playoutGate='precision recovery';
  setState('Re-aligning…','warn');
  note('Safe local playback recovered. Rejoining synchronized precision playout.');
}

function maybeRecoverPrecisionFromSafeLocal(m,now=performance.now()){
  if(!fallbackPlayback||!joined||!audio||audio.state!=='running'||!clock.ready||!roomTimeline||!metrics.workletAlive)return false;
  if(fallbackEnteredAt==null||now-fallbackEnteredAt<1200)return false;
  if(lastPrecisionRecoveryAttempt&&now-lastPrecisionRecoveryAttempt<750)return false;

  const pos=Number.isFinite(m.samplePosition)?m.samplePosition:null;
  if(pos==null)return false;
  const nominal=roomTimeline.originServerMs+(pos-roomTimeline.originSample)*1000/roomTimeline.sampleRate;
  const d=roomD.tick(now);
  const targetPerf=clock.localAtServer(targetServerTimeMs(nominal,d));
  lastPrecisionRecoveryAttempt=now;
  if(!Number.isFinite(targetPerf))return false;

  // Re-enter precision only when this live frame has a usable future deadline.
  // Too-far-future means the clock/timeline is still unstable; already-late
  // means continuity mode should keep carrying audio until the next opportunity.
  const lead=targetPerf-now;
  if(lead<40||lead>1500)return false;

  exitSafeLocalForPrecision(targetPerf,'shared timeline recovered');
  return true;
}

function scheduleSafeLocal(m,reason='watchdog'){
  if(!joined||!audio||audio.state!=='running')return false;
  enterSafeLocal(reason);

  // SAFE LOCAL must be an independent continuity path. Do not send fallback
  // audio through the same AudioWorklet/SAB scheduler that may be the fault.
  // BufferSource playback is less precise, but it keeps sound continuous while
  // precision synchronization recovers in parallel.
  const ok=scheduleDirectBuffer(m,reason);
  if(ok){
    metrics.scheduledFrames++;
    lastEpoch=String(m.epoch);lastSeq=String(m.seq);
    metrics.playoutGate='SAFE LOCAL direct playback';
    if(hostOnline)showContinuityState(true,' · continuity mode');
  }
  return ok;
}

function shouldForceSafeLocal(now=performance.now()){
  if(fallbackPlayback)return true;
  if(now<precisionRecoveryGraceUntil)return false;
  if(decodedAudibleSince==null)return false;
  if(now-decodedAudibleSince<600)return false;
  return metrics.outputRmsDb<=-90 && !metrics.workletActive;
}

function onDecoded(m){
  if(m.type==='decoder-ready'){metrics.decoder=m.mode;decoderFailed=false;decoderError='';applyPipelineState();return}
  if(m.type==='decoder-error'){if(m.fatal){decoderFailed=true;decoderError=String(m.message||'decoder failed');metrics.decoder='opus-unavailable';applyPipelineState()}return}
  if(m.type!=='pcm-frame'||m.generation!==generation)return;
  lastPcmAt=performance.now();pcmFrames++;metrics.pcmFrames=pcmFrames;metrics.decoderMs=m.decodeMs||0;metrics.seq=m.seq;
  metrics.decodedRmsDb=Number.isFinite(m.decodedRmsDb)?m.decodedRmsDb:-120;
  metrics.decodedPeakDb=Number.isFinite(m.decodedPeakDb)?m.decodedPeakDb:-120;
  if(metrics.decodedRmsDb>-70){
    if(decodedAudibleSince==null)decodedAudibleSince=performance.now();
  }else decodedAudibleSince=null;

  // A browser can report a running AudioContext while the UI/join flag missed
  // the transition. Do not throw away decoded audio in that contradictory state.
  repairJoinState();

  if(!audio||!node){metrics.playoutGate='audio engine not ready';return;}
  if(audio.state!=='running'){
    joined=false;
    metrics.playoutGate='AudioContext '+audio.state;
    ui.join.disabled=false;
    ui.join.textContent='TAP TO LISTEN';
    return;
  }
  // Running Web Audio is authoritative. Repair a stale join/UI flag inline.
  repairJoinState();
  const gateNow=performance.now();

  if(lastUnderrunAt&&gateNow-lastUnderrunAt>3000){
    underrunWindowStart=0;underrunBurstCount=0;lastUnderrunAt=0;
  }
  if(forceContinuityOnNextPcm&&decodedAudibleSince!=null){
    forceContinuityOnNextPcm=false;
    if(scheduleSafeLocal(m,'underrun burst continuity')){
      metrics.continuityRecoveries++;
      return;
    }
  }

  // Continuity-first bootstrap: the first clearly audible decoded frame must
  // always reach an output scheduler immediately. Precision sync can take over
  // after the clock/timeline is ready; audible continuity is never gated by it.
  if(metrics.scheduledFrames===0 && decodedAudibleSince!=null){
    if(scheduleSafeLocal(m,'startup scheduler bootstrap')){
      metrics.selfHeals++;
      return;
    }
  }

  if(fallbackPlayback){
    if(!maybeRecoverPrecisionFromSafeLocal(m,gateNow)){
      scheduleSafeLocal(m,'continuity fallback');
      return;
    }
  }

  if(!clock.ready){
    metrics.playoutGate='waiting for clock';
    if(shouldForceSafeLocal(gateNow))scheduleSafeLocal(m,'clock not ready');
    return;
  }
  const epoch=String(m.epoch);
  if(m.discontinuity){timeline.reset();sabWriter?.reset();node.port.postMessage({type:'reset'});metrics.hardResyncs++;markResync('Host stream discontinuity; re-anchoring the live timeline.',900)}
  if(currentEpoch===null)currentEpoch=epoch;else if(epoch!==currentEpoch){resetPlayout('epoch');currentEpoch=epoch}
  metrics.epoch=epoch;
  const ingressMs=Number(BigInt(m.serverNs))/1e6,pos=Number.isFinite(m.samplePosition)?m.samplePosition:null;
  let nominal=null;if(roomTimeline&&pos!=null)nominal=roomTimeline.originServerMs+(pos-roomTimeline.originSample)*1000/roomTimeline.sampleRate;
  const tr=timeline.ingest({epoch,seq:m.seq,serverMs:ingressMs,frames:m.frames,sampleRate:m.sampleRate||sampleRate,sampleIndex:pos,timelineServerMs:nominal});
  if(!tr.accepted){
    metrics.playoutGate='timeline '+(tr.reason||'rejected');
    if(shouldForceSafeLocal(gateNow))scheduleSafeLocal(m,'timeline '+(tr.reason||'rejected'));
    return;
  }
  lastEpoch=epoch;lastSeq=String(m.seq);
  const nowD=performance.now(),age=clock.serverAtLocal(m.arrivalPerfMs)-tr.timelineServerMs,c=clock.snapshot();
  suggestedD.observe({networkAgeMs:age,decodeMs:m.decodeMs||0,clockConfidenceMs:c?.confidence95Ms||0,nowMs:nowD});
  const d=roomD.tick(nowD);metrics.targetDelayMs=d;metrics.recommendedDelayMs=suggestedD.currentMs;
  sampleOutputClock();
  const targetPerf=clock.localAtServer(targetServerTimeMs(tr.timelineServerMs,d));
  if(!Number.isFinite(targetPerf)){
    metrics.playoutGate='invalid target clock';
    if(shouldForceSafeLocal(gateNow))scheduleSafeLocal(m,'invalid target clock');
    return;
  }
  const now=performance.now();
  if(targetPerf<now-1000){
    metrics.staleDrops++;metrics.playoutGate='precision target stale';
    if(shouldForceSafeLocal(now))scheduleSafeLocal(m,'precision target stale');
    return;
  }
  let ct=outputMap.contextTimeForPerformance(targetPerf);if(!Number.isFinite(ct))ct=fallbackContextTimeForPerformance(audio.currentTime,now,targetPerf,bestFallbackLatency());
  let meta={targetFrame:Math.round(ct*audio.sampleRate),startSample:tr.startSample,frames:m.frames};
  if(targetPerf<now){metrics.lateFrames++;suggestedD.markLate(now,now-targetPerf)}

  const silentOutputForMs=decodedAudibleSince==null?0:now-decodedAudibleSince;
  if(!fallbackPlayback && now>=precisionRecoveryGraceUntil && silentOutputForMs>600 && metrics.outputRmsDb<=-90 && !metrics.workletActive){
    enterSafeLocal(metrics.scheduledFrames===0?'no precision frames scheduled':'precision output silent');
  }

  if(fallbackPlayback){
    scheduleSafeLocal(m,'watchdog');
    return;
  }else if(sabWriter){
    if(!sabWriter.write(m.pcm,meta)){
      metrics.overruns++;metrics.hardResyncs++;sabWriter.reset();node.port.postMessage({type:'reset'});
      sabWriter.write(m.pcm,meta);
    }
  }else node.port.postMessage({type:'pcm',...meta,pcm:m.pcm},[m.pcm.buffer]);
  metrics.scheduledFrames++;
  metrics.playoutGate='precision scheduled';
  if(hostOnline&&audio.state==='running')showContinuityState(true);
}

function bestFallbackLatency(){const o=Number(audio?.outputLatency),b=Number(audio?.baseLatency);if(Number.isFinite(o)&&o>0)return o;if(Number.isFinite(b)&&b>0)return b;return 0}
function sampleOutputClock(){
  if(!audio)return;
  try{if(typeof audio.getOutputTimestamp==='function'){const t=audio.getOutputTimestamp();if(Number.isFinite(t?.performanceTime)&&t.performanceTime>0&&Number.isFinite(t?.contextTime))outputMap.add({performanceTimeMs:t.performanceTime,contextTimeSec:t.contextTime})}}catch{}
  const o=Number(audio.outputLatency),b=Number(audio.baseLatency);metrics.outputLatencyMs=Number.isFinite(o)?o*1000:null;metrics.baseLatencyMs=Number.isFinite(b)?b*1000:null;
  if(lastOutputLatency!=null&&metrics.outputLatencyMs!=null&&Math.abs(metrics.outputLatencyMs-lastOutputLatency)>10&&joined){outputMap=new OutputTimeMapper();timeline.reset();node?.port.postMessage({type:'reset'});metrics.hardResyncs++;markResync('Audio output route changed; rebuilding output timing.',1200);note('Audio output route changed; re-aligning.')}
  if(metrics.outputLatencyMs!=null)lastOutputLatency=metrics.outputLatencyMs;
}
function onWorklet(m){
  if(m.type==='metrics'){
    metrics.bufferMs=m.bufferMs;metrics.resamplerPpm=m.ppm;metrics.underruns=m.underruns;metrics.lateFrames=Math.max(metrics.lateFrames,m.lateFrames);metrics.hardResyncs=Math.max(metrics.hardResyncs,m.hardResyncs);metrics.overruns=Math.max(metrics.overruns,m.overruns);
    metrics.outputRmsDb=Number.isFinite(m.outputRmsDb)?m.outputRmsDb:-120;metrics.outputPeakDb=Number.isFinite(m.outputPeakDb)?m.outputPeakDb:-120;metrics.workletActive=!!m.active;
    if(metrics.outputPeakDb>-90||metrics.outputRmsDb>-90)lastAudibleOutputAt=performance.now();
    metrics.workletAlive=true;metrics.workletQuanta=Number(m.processQuanta)||metrics.workletQuanta;
  }
  else if(m.type==='underrun'){
    const now=performance.now();
    metrics.underruns=m.count;
    lastUnderrunAt=now;
    if(!underrunWindowStart||now-underrunWindowStart>2500){underrunWindowStart=now;underrunBurstCount=0}
    underrunBurstCount++;
    suggestedD.markLate(now,Math.min(250,60+underrunBurstCount*30));
    metrics.playoutGate='underrun continuity recovery';
    // One isolated underrun is not a user-visible buffering event. Repeated
    // underruns trigger the independent direct-output continuity path on the
    // next decoded PCM frame while precision sync repairs in parallel.
    if(underrunBurstCount>=3){
      forceContinuityOnNextPcm=true;
      metrics.underrunBursts++;
      metrics.selfHeals++;
      showContinuityState(true,' · continuity recovery');
    }else if(hostOnline&&joined){
      showContinuityState(true);
    }
  }
  else if(m.type==='late'){metrics.lateFrames=m.count;suggestedD.markLate(performance.now(),Math.abs(m.errorMs||0))}
  else if(m.type==='hard-resync'){metrics.hardResyncs=m.count;markResync('Large timing error corrected with a short crossfade.',900);note('Large timing error corrected with a short crossfade.')}
  else if(m.type==='overrun')metrics.overruns=m.count;
}

async function holdWakeLock(){
  try{
    if(!('wakeLock' in navigator)||document.visibilityState!=='visible')return;
    if(wakeLock&&!wakeLock.released)return;
    wakeLock=await navigator.wakeLock.request('screen');
    wakeLock.addEventListener('release',()=>{wakeLock=null});
  }catch{}
}
async function unlock(){
  unlockAttempted=true;
  try{await ensureAudio();await audio.resume();if(audio.state!=='running')throw new Error('AudioContext '+audio.state);joined=true;void holdWakeLock();ui.join.disabled=true;ui.join.textContent='AUDIO ENABLED';resetPlayout('join');showContinuityState(false);note('Live audio only. No YouTube or song download is needed on this device.')}
  catch(e){ui.join.disabled=false;ui.join.textContent='TAP TO LISTEN';setState('Tap required','warn');note('Audio could not start: '+(e?.message||e))}
}
ui.join.addEventListener('click',unlock);
ensureAudio().then(()=>{
  if(audio.state==='running'){
    repairJoinState();
  }else{
    joined=false;ui.join.disabled=false;ui.join.textContent='TAP TO LISTEN';
  }
}).catch(()=>{joined=false;ui.join.disabled=false;ui.join.textContent='TAP TO LISTEN'});

function resumeVisible(){
  if(!joined||!audio)return;
  const wasRunning=audio.state==='running';
  audio.resume().then(()=>{
    if(audio.state!=='running')throw new Error('gesture');
    if(!wasRunning)resetPlayout('resume');
    showContinuityState(hostOnline&&audio.state==='running')
  })
  .catch(()=>{ui.join.disabled=false;ui.join.textContent='RESUME AUDIO';setState('Tap to resume','warn')});
}
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'){void holdWakeLock();resumeVisible()}else if(joined)note('Background/lock-screen playback depends on the browser and OS; alignment will be rechecked on return.')});
document.addEventListener('freeze',()=>{try{ws?.close(4000,'page frozen')}catch{}});
document.addEventListener('resume',()=>{connect(true);resumeVisible()});
window.addEventListener('online',()=>{connect(true);applyPipelineState()});
window.addEventListener('offline',()=>applyPipelineState());
try{
  navigator.connection?.addEventListener?.('change',()=>{
    // A Wi-Fi/cellular route change can leave an apparently-open TCP socket
    // stranded on the old path. Replace it immediately and resume from the
    // bounded relay ring instead of waiting for the OS TCP timeout.
    if(ws?.readyState===WebSocket.OPEN)connect(true);
    else connect();
  });
}catch{}
try{navigator.mediaDevices?.addEventListener?.('devicechange',()=>{outputMap=new OutputTimeMapper();sampleOutputClock();if(joined){node?.port.postMessage({type:'reset'});timeline.reset();metrics.hardResyncs++;markResync('Audio output device changed; rebuilding output timing.',1200)}})}catch{}

function sendStats(){
  if(!ws||ws.readyState!==WebSocket.OPEN)return;
  const safe=lastSeq!==null&&BigInt(lastSeq)<=BigInt(Number.MAX_SAFE_INTEGER)?Number(lastSeq):0;
  try{ws.send(JSON.stringify({type:'listener_stats',v:1,last_sequence:safe,buffer_depth_ms:metrics.bufferMs,underruns:metrics.underruns,late_frames:metrics.lateFrames,recommended_delay_ms:metrics.recommendedDelayMs,resampler_ppm:metrics.resamplerPpm,hard_resyncs:metrics.hardResyncs}))}catch{}
}
function fmt(v){return Number.isFinite(v)?v.toFixed(1):'—'}
function render(){
  repairJoinState();
  const diagnosis=diagnosePipeline();
  applyDiagnosisState(diagnosis);
  const c=clock.snapshot();
  const wsOk=!!ws&&ws.readyState===WebSocket.OPEN;
  const decodeOk=metrics.decoder!=='starting'&&metrics.decoder!=='opus-unavailable'&&metrics.decodedRmsDb>-70;
  const audioOk=!!audio&&!!node&&audio.state==='running'&&joined;
  const scheduleOk=metrics.scheduledFrames>0;
  const outputOk=(lastAudibleOutputAt>0&&performance.now()-lastAudibleOutputAt<=1100)||directOutputAudible();
  ui.diag.textContent=[
    'STATE: '+metrics.pipelineState+' · '+metrics.pipelineLabel,
    'STATE AGE: '+fmt(performance.now()-metrics.pipelineStateSinceMs)+' ms · transitions '+metrics.pipelineTransitions,
    'DIAGNOSIS: '+diagnosis.code,
    'Cause: '+diagnosis.message,
    'Action: '+diagnosis.action,
    'Continuity: '+(metrics.continuityState||'—'),
    'Pipeline: relay '+(wsOk?'✓':'✗')+' | host '+(hostOnline?'✓':'✗')+' | stream '+relayStreamState+' | decode '+(decodeOk?'✓':'✗')+' | audio '+(audioOk?'✓':'✗')+' | schedule '+(scheduleOk?'✓':'✗')+' | output '+(outputOk?'✓':'✗'),
    'Host capture: '+(hostCaptureState||'—'),
    'Self-heals: '+metrics.selfHeals,
    'RTT(min): '+fmt(metrics.rttMs)+' ms',
    'Clock offset(now): '+fmt(metrics.clockOffsetMs)+' ms',
    'Clock drift: '+fmt(metrics.clockDriftPpm)+' ppm',
    'Clock confidence: ±'+fmt(metrics.clockConfidenceMs)+' ms',
    'PCM queued: '+fmt(metrics.bufferMs)+' ms',
    'Room D: '+fmt(metrics.targetDelayMs)+' ms · recommended '+fmt(metrics.recommendedDelayMs)+' ms',
    'Decoder: '+metrics.decoder+' · '+fmt(metrics.decoderMs)+' ms',
    'Decoded level: '+fmt(metrics.decodedRmsDb)+' dBFS · peak '+fmt(metrics.decodedPeakDb)+' dBFS',
    'Output level: '+fmt(metrics.outputRmsDb)+' dBFS · peak '+fmt(metrics.outputPeakDb)+' dBFS · active '+(metrics.workletActive?'yes':'no'),
    'Worklet: '+(metrics.workletAlive?'alive':'NO HEARTBEAT')+' · quanta '+metrics.workletQuanta+' · processor errors '+metrics.workletProcessorErrors,
    'Scheduler: '+metrics.scheduledFrames+' frames · errors '+metrics.schedulerErrors+(metrics.lastSchedulerError?' · '+metrics.lastSchedulerError.slice(0,120):''),
    'Direct fallback: '+(metrics.directPlayback?'ON':'off')+' · active sources '+metrics.directSources+' · frames '+metrics.directScheduledFrames,
    'Fallback: '+(metrics.fallbackPlayback?'SAFE LOCAL':'precision')+' · engine rebuilds '+metrics.audioEngineRebuilds,
    'Playout gate: '+metrics.playoutGate,
    'Late: '+metrics.lateFrames+' · underruns: '+metrics.underruns,
    'Resampler: '+fmt(metrics.resamplerPpm)+' ppm',
    'Hard resyncs: '+metrics.hardResyncs+' · overruns: '+metrics.overruns,
    'outputLatency: '+fmt(metrics.outputLatencyMs)+' ms · baseLatency: '+fmt(metrics.baseLatencyMs)+' ms',
    'Reconnects: '+metrics.reconnects+' · watchdog: '+metrics.transportWatchdogReconnects+' · stale drops: '+metrics.staleDrops,
    'Transport age: relay '+fmt(metrics.lastRelayAgeMs)+' ms · audio '+fmt(metrics.lastBinaryAgeMs)+' ms',
    'Wake lock: '+(wakeLock&&!wakeLock.released?'held':'not held'),
    'SAB: '+(sabWriter?'yes':'no')+' · isolated: '+(metrics.crossOriginIsolated?'yes':'no'),
    'Audio: '+metrics.audioState+' · epoch: '+(metrics.epoch??'—')+' · seq: '+(metrics.seq??'—'),
    'Relay instance: '+(metrics.serverInstanceId?metrics.serverInstanceId.slice(0,8)+'…':'—')+' · restarts: '+metrics.serverRestarts,
    'Clock samples: '+(c?.accepted??0)+'/'+(c?.total??0)
  ].join('\n');
}
setInterval(sendStats,2000);
setInterval(()=>{ render(); },500);
render();connect();