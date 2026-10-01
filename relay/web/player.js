import {ClockModel,TimelineTracker,AdaptiveDelay,SlewValue,OutputTimeMapper,ServerInstanceTracker,targetServerTimeMs,fallbackContextTimeForPerformance} from './sync-core.mjs';

const q=s=>document.querySelector(s);
const ui={room:q('#room'),state:q('#state'),dot:q('#dot'),join:q('#join'),note:q('#note'),diag:q('#diagText')};
const room=location.pathname.startsWith('/r/')?decodeURIComponent(location.pathname.slice(3)):(new URLSearchParams(location.search).get('room')||'');
ui.room.textContent=room?(room.length>12?room.slice(0,6)+'…'+room.slice(-4):'private room'):'invalid link';

const clock=new ClockModel(),timeline=new TimelineTracker();
const suggestedD=new AdaptiveDelay({initialMs:400,floorMs:150,ceilingMs:1000});
const roomD=new SlewValue({initial:400,floor:150,ceiling:1000,upPerSec:.25,downPerSec:.15});
const serverTracker=new ServerInstanceTracker();
let outputMap=new OutputTimeMapper(),roomTimeline=null;
let ws=null,reconnectTimer=null,backoff=100,generation=0,clockTimer=null,pingId=0,pings=new Map();
let lastRelayMessageAt=0,lastBinaryAt=0,transportWatchdogTimer=null;
let audio=null,node=null,decoder=null,sabWriter=null,joined=false,hostOnline=false,currentEpoch=null,wakeLock=null;
let audioEngineRebuilds=0,audioEngineRebuilding=false;
let directGain=null,directNextTime=null,directSources=new Set();
let roomDNeedsAuthoritativeSnap=true;
let fallbackPlayback=false,fallbackNextTargetFrame=null,decodedAudibleSince=null;
let fallbackEnteredAt=null,precisionRecoveryGraceUntil=0,lastPrecisionRecoveryAttempt=0;
let sampleRate=48000,channels=2,codec='opus',lastEpoch=null,lastSeq=null,lastOutputLatency=null;
const metrics={rttMs:null,clockOffsetMs:null,clockDriftPpm:null,clockConfidenceMs:null,bufferMs:0,targetDelayMs:400,recommendedDelayMs:400,lateFrames:0,decoderMs:0,decodedRmsDb:-120,decodedPeakDb:-120,outputRmsDb:-120,outputPeakDb:-120,workletActive:false,workletAlive:false,workletQuanta:0,workletProcessorErrors:0,schedulerErrors:0,lastSchedulerError:'',audioEngineRebuilds:0,directPlayback:false,directSources:0,directScheduledFrames:0,scheduledFrames:0,fallbackPlayback:false,playoutGate:'starting',faultCode:'STARTING',faultMessage:'Starting guest pipeline',faultAction:'Wait for the room to connect.',selfHeals:0,underruns:0,resamplerPpm:0,hardResyncs:0,outputLatencyMs:null,baseLatencyMs:null,reconnects:0,transportWatchdogReconnects:0,lastRelayAgeMs:null,lastBinaryAgeMs:null,overruns:0,staleDrops:0,decoder:'starting',audioState:'none',epoch:null,seq:null,serverInstanceId:null,serverRestarts:0,crossOriginIsolated:!!self.crossOriginIsolated};
self.__JLS_METRICS__=metrics;

function setState(s,k='warn'){ui.state.textContent=s;ui.dot.className='dot '+k}
function note(s){ui.note.textContent=s}

function repairJoinState(){
  if(!audio||!node||audio.state!=='running')return false;
  if(joined)return true;
  // AudioContext.running is the browser's authoritative proof that playback
  // permission has been granted. Never discard decoded PCM because a UI flag
  // missed the transition.
  joined=true;
  metrics.selfHeals++;
  metrics.playoutGate='audio join auto-recovered';
  ui.join.disabled=true;
  ui.join.textContent='LISTENING';
  if(hostOnline)setState('Listening','ok');
  note('Audio join state recovered automatically. Live playback is starting.');
  return true;
}

function diagnosePipeline(){
  const wsOpen=!!ws&&ws.readyState===WebSocket.OPEN;
  const decodedAudible=metrics.decodedRmsDb>-70;
  const outputAudible=metrics.outputRmsDb>-90||metrics.workletActive||(metrics.directPlayback&&metrics.directSources>0);

  let code='OK',message='Live audio pipeline is healthy.',action='No action needed.';

  if(!room){
    code='INVALID_LINK';message='Guest room token is missing.';action='Open a fresh guest link from the host.';
  }else if(!wsOpen){
    code='RELAY_DISCONNECTED';message='Browser is not connected to the relay.';action='Check network access and wait for reconnect.';
  }else if(!hostOnline){
    code='HOST_OFFLINE';message='Relay is connected but the host is offline.';action='Start Jawahar Live Sync on the host phone.';
  }else if(metrics.decoder==='opus-unavailable'){
    code='DECODER_UNAVAILABLE';message='Opus decoder could not start.';action='Reload the page or use a current Chrome/Edge/Firefox browser.';
  }else if(metrics.decoder==='starting'){
    code='DECODER_STARTING';message='Audio decoder is still starting.';action='Wait a moment.';
  }else if(!decodedAudible){
    code='NO_HOST_AUDIO';message='Packets are arriving, but decoded audio is silent.';action='Play audible media on the host and verify CAPTURE_OK / non-zero dBFS.';
  }else if(!audio||!node){
    code='AUDIO_ENGINE_NOT_READY';message='Decoded audio exists but the browser audio engine is not ready.';action='Tap TO LISTEN once; if needed reload the page.';
  }else if(audio.state!=='running'){
    code='AUDIO_GESTURE_REQUIRED';message='Decoded audio exists but browser playback is '+audio.state+'.';action='Tap RESUME LISTENING / TAP TO LISTEN.';
  }else if(!joined){
    code='JOIN_STATE_STUCK';message='Browser audio is running but the join state is stuck.';action='Automatic repair is being attempted now.';
  }else if(!clock.ready){
    code='CLOCK_CALIBRATING';message='Audio is decoded and joined; the synchronization clock is still calibrating.';action='Wait briefly; safe local playback will engage if needed.';
  }else if(metrics.scheduledFrames===0){
    code='SCHEDULER_BLOCKED';message='Audio is decoded but no PCM frames have reached the output scheduler.';action='Automatic safe-playback recovery is being attempted.';
  }else if(!outputAudible&&decodedAudible){
    code='OUTPUT_SILENT';message='Audio is decoded and scheduled, but the browser output is silent.';action='Automatic safe local playback is being attempted; also check device volume/output route.';
  }else if(metrics.fallbackPlayback){
    code='SAFE_LOCAL';message='Audio is playing in safe local mode while precision sync recovers.';action='Keep listening; precision mode will remain secondary to continuity.';
  }

  metrics.faultCode=code;metrics.faultMessage=message;metrics.faultAction=action;
  return {code,message,action};
}

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
    if((audio.state==='suspended'||audio.state==='interrupted')&&joined){ui.join.disabled=false;ui.join.textContent='RESUME LISTENING';setState('Playback interrupted','bad')}
    else if(audio.state==='running'&&joined&&hostOnline)setState('Listening','ok');
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
    ui.join.disabled=true;ui.join.textContent='LISTENING';
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
  ws.onopen=()=>{if(gen!==generation)return;backoff=100;lastRelayMessageAt=performance.now();lastBinaryAt=0;ui.join.disabled=false;setState(hostOnline&&joined?'Listening':(hostOnline?'Host online':'Connected'),'ok');clockBurst();clearInterval(clockTimer);clockTimer=setInterval(sendClock,1000);startTransportWatchdog()};
  ws.onmessage=e=>{if(gen!==generation)return;lastRelayMessageAt=performance.now();if(typeof e.data==='string')onControl(e.data);else if(e.data instanceof ArrayBuffer){const t=performance.now();lastBinaryAt=t;decoder.postMessage({type:'frame',buffer:e.data,generation:gen,arrivalPerfMs:t},[e.data])}};
  ws.onclose=()=>{
    if(gen!==generation)return;
    clearInterval(clockTimer);clockTimer=null;stopTransportWatchdog();
    // Continuity-first reconnect: do NOT flush the AudioWorklet/direct queue.
    // Keep lastEpoch/lastSeq so the relay recovery ring can replay only the gap.
    setState(navigator.onLine===false?'Network offline':'Reconnecting…',navigator.onLine===false?'bad':'warn');
    note('Connection interrupted; buffered audio is preserved while reconnecting automatically.');
    scheduleReconnect();
  };
  ws.onerror=()=>{};
}
function scheduleReconnect(){clearTimeout(reconnectTimer);metrics.reconnects++;const d=backoff;backoff=Math.min(2000,Math.max(100,Math.round(backoff*1.55)));reconnectTimer=setTimeout(()=>connect(),d)}
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
    hostOnline=!!m.host_online;const tl=m.timeline||{};
    if(m.epoch!=null){const announced=String(m.epoch);if(currentEpoch!==null&&announced!==currentEpoch)resetPlayout('epoch');currentEpoch=announced;metrics.epoch=announced;}
    sampleRate=Number(tl.sample_rate)||sampleRate;channels=Number(tl.channels)||channels;codec=tl.codec||'opus';
    const timelineReady=m.timeline_ready!==false&&Number(tl.origin_server_ns)>0;
    if(timelineReady&&tl.origin_server_ns!=null&&tl.origin_sample_position!=null)roomTimeline={originServerMs:Number(tl.origin_server_ns)/1e6,originSample:Number(tl.origin_sample_position),sampleRate};else roomTimeline=null;
    const d=Number(tl.recommended_delay_ns);if(Number.isFinite(d)&&d>0){const ms=d/1e6;if(roomDNeedsAuthoritativeSnap||roomD.lastMs==null){roomD.reset(ms,performance.now());roomDNeedsAuthoritativeSnap=false}else roomD.setTarget(ms)}
    decoder.postMessage({type:'init',codec,sampleRate,channels,wasmUrl:'/vendor/libopus-wasm/index.js'});node?.port.postMessage({type:'config',sourceRate:sampleRate});
    if(!hostOnline||m.reason==='host_offline'){
      resetPlayout('host-offline');
      setState('Host offline','bad');
    }else if(joined){
      // A normal state refresh (including adaptive-delay updates) is not a
      // buffering event. Preserve Listening while the audio pipeline is alive.
      const healthyOutput =
        metrics.directPlayback ||
        metrics.workletActive ||
        metrics.bufferMs > 40 ||
        metrics.scheduledFrames > 0;
      setState(healthyOutput?'Listening':'Preparing audio…',healthyOutput?'ok':'warn');
    }else{
      setState('Host online','ok');
    }
    return;
  }
  if(m.type==='hello'){
    hostOnline=!!m.hostOnline;sampleRate=Number(m.sampleRate)||48000;channels=Number(m.channels)||2;codec=m.codec||'pcm16le';
    const d=Math.max(150,Math.min(1000,Number(m.targetDelayMs)||400));roomD.reset(d,performance.now());roomDNeedsAuthoritativeSnap=false;
    decoder.postMessage({type:'init',codec,sampleRate,channels,wasmUrl:'/vendor/libopus-wasm/index.js'});node?.port.postMessage({type:'config',sourceRate:sampleRate});
    if(!hostOnline)setState('Host offline','bad');
    else if(joined)setState((metrics.directPlayback||metrics.workletActive||metrics.bufferMs>40||metrics.scheduledFrames>0)?'Listening':'Preparing audio…',(metrics.directPlayback||metrics.workletActive||metrics.bufferMs>40||metrics.scheduledFrames>0)?'ok':'warn');
    else setState('Host online','ok');
    return;
  }
  if(m.type==='host-offline'){hostOnline=false;resetPlayout('host-offline');setState('Host offline','bad')}
  if(m.type==='host-online'){
    hostOnline=true;
    if(joined)setState((metrics.directPlayback||metrics.workletActive||metrics.bufferMs>40||metrics.scheduledFrames>0)?'Listening':'Preparing audio…',(metrics.directPlayback||metrics.workletActive||metrics.bufferMs>40||metrics.scheduledFrames>0)?'ok':'warn');
    else setState('Host online','ok');
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
  directSources.clear();directNextTime=null;metrics.directSources=0;metrics.directPlayback=false;
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
    metrics.playoutGate='DIRECT BUFFER playing: '+reason;
    if(hostOnline)setState('Listening · compatibility mode','ok');
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
    if(hostOnline)setState('Listening · continuity mode','ok');
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
  if(m.type==='decoder-ready'){metrics.decoder=m.mode;return}
  if(m.type==='decoder-error'){if(m.fatal){setState('Audio decoder unavailable','bad');note(m.message)}return}
  if(m.type!=='pcm-frame'||m.generation!==generation)return;
  metrics.decoderMs=m.decodeMs||0;metrics.seq=m.seq;
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
  if(m.discontinuity){timeline.reset();sabWriter?.reset();node.port.postMessage({type:'reset'});metrics.hardResyncs++}
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
  if(hostOnline&&audio.state==='running')setState('Listening','ok');
}

function bestFallbackLatency(){const o=Number(audio?.outputLatency),b=Number(audio?.baseLatency);if(Number.isFinite(o)&&o>0)return o;if(Number.isFinite(b)&&b>0)return b;return 0}
function sampleOutputClock(){
  if(!audio)return;
  try{if(typeof audio.getOutputTimestamp==='function'){const t=audio.getOutputTimestamp();if(Number.isFinite(t?.performanceTime)&&t.performanceTime>0&&Number.isFinite(t?.contextTime))outputMap.add({performanceTimeMs:t.performanceTime,contextTimeSec:t.contextTime})}}catch{}
  const o=Number(audio.outputLatency),b=Number(audio.baseLatency);metrics.outputLatencyMs=Number.isFinite(o)?o*1000:null;metrics.baseLatencyMs=Number.isFinite(b)?b*1000:null;
  if(lastOutputLatency!=null&&metrics.outputLatencyMs!=null&&Math.abs(metrics.outputLatencyMs-lastOutputLatency)>10&&joined){outputMap=new OutputTimeMapper();timeline.reset();node?.port.postMessage({type:'reset'});metrics.hardResyncs++;note('Audio output route changed; re-aligning.')}
  if(metrics.outputLatencyMs!=null)lastOutputLatency=metrics.outputLatencyMs;
}
function onWorklet(m){
  if(m.type==='metrics'){
    metrics.bufferMs=m.bufferMs;metrics.resamplerPpm=m.ppm;metrics.underruns=m.underruns;metrics.lateFrames=Math.max(metrics.lateFrames,m.lateFrames);metrics.hardResyncs=Math.max(metrics.hardResyncs,m.hardResyncs);metrics.overruns=Math.max(metrics.overruns,m.overruns);
    metrics.outputRmsDb=Number.isFinite(m.outputRmsDb)?m.outputRmsDb:-120;metrics.outputPeakDb=Number.isFinite(m.outputPeakDb)?m.outputPeakDb:-120;metrics.workletActive=!!m.active;
    metrics.workletAlive=true;metrics.workletQuanta=Number(m.processQuanta)||metrics.workletQuanta;
  }
  else if(m.type==='underrun'){
    metrics.underruns=m.count;
    suggestedD.markLate(performance.now(),80);
    metrics.playoutGate='underrun recovery';
    if(metrics.directPlayback||metrics.bufferMs>0)setState('Listening · recovering','ok');
    else setState('Recovering audio…','warn');
  }
  else if(m.type==='late'){metrics.lateFrames=m.count;suggestedD.markLate(performance.now(),Math.abs(m.errorMs||0))}
  else if(m.type==='hard-resync'){metrics.hardResyncs=m.count;note('Large timing error corrected with a short crossfade.')}
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
  try{await ensureAudio();await audio.resume();if(audio.state!=='running')throw new Error('AudioContext '+audio.state);joined=true;void holdWakeLock();ui.join.disabled=true;ui.join.textContent='LISTENING';resetPlayout('join');setState(hostOnline?'Preparing audio…':'Host offline',hostOnline?'warn':'bad');note('Live audio only. No YouTube or song download is needed on this device.')}
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
    setState(hostOnline?'Listening':'Host offline',hostOnline?'ok':'bad')
  })
  .catch(()=>{ui.join.disabled=false;ui.join.textContent='RESUME LISTENING';setState('Tap to resume','warn')});
}
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible'){void holdWakeLock();resumeVisible()}else if(joined)note('Background/lock-screen playback depends on the browser and OS; alignment will be rechecked on return.')});
document.addEventListener('freeze',()=>{try{ws?.close(4000,'page frozen')}catch{}});
document.addEventListener('resume',()=>{connect(true);resumeVisible()});
window.addEventListener('online',()=>connect(true));
window.addEventListener('offline',()=>setState('Network offline','bad'));
try{
  navigator.connection?.addEventListener?.('change',()=>{
    // A Wi-Fi/cellular route change can leave an apparently-open TCP socket
    // stranded on the old path. Replace it immediately and resume from the
    // bounded relay ring instead of waiting for the OS TCP timeout.
    if(ws?.readyState===WebSocket.OPEN)connect(true);
    else connect();
  });
}catch{}
try{navigator.mediaDevices?.addEventListener?.('devicechange',()=>{outputMap=new OutputTimeMapper();sampleOutputClock();if(joined){node?.port.postMessage({type:'reset'});timeline.reset();metrics.hardResyncs++}})}catch{}

function sendStats(){
  if(!ws||ws.readyState!==WebSocket.OPEN)return;
  const safe=lastSeq!==null&&BigInt(lastSeq)<=BigInt(Number.MAX_SAFE_INTEGER)?Number(lastSeq):0;
  try{ws.send(JSON.stringify({type:'listener_stats',v:1,last_sequence:safe,buffer_depth_ms:metrics.bufferMs,underruns:metrics.underruns,late_frames:metrics.lateFrames,recommended_delay_ms:metrics.recommendedDelayMs,resampler_ppm:metrics.resamplerPpm,hard_resyncs:metrics.hardResyncs}))}catch{}
}
function fmt(v){return Number.isFinite(v)?v.toFixed(1):'—'}
function render(){
  repairJoinState();
  const diagnosis=diagnosePipeline();
  const c=clock.snapshot();
  const wsOk=!!ws&&ws.readyState===WebSocket.OPEN;
  const decodeOk=metrics.decoder!=='starting'&&metrics.decoder!=='opus-unavailable'&&metrics.decodedRmsDb>-70;
  const audioOk=!!audio&&!!node&&audio.state==='running'&&joined;
  const scheduleOk=metrics.scheduledFrames>0;
  const outputOk=metrics.outputRmsDb>-90||metrics.workletActive;
  ui.diag.textContent=[
    'DIAGNOSIS: '+diagnosis.code,
    'Cause: '+diagnosis.message,
    'Action: '+diagnosis.action,
    'Pipeline: relay '+(wsOk?'✓':'✗')+' | host '+(hostOnline?'✓':'✗')+' | decode '+(decodeOk?'✓':'✗')+' | audio '+(audioOk?'✓':'✗')+' | schedule '+(scheduleOk?'✓':'✗')+' | output '+(outputOk?'✓':'✗'),
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
setInterval(()=>{
  render();
  if(metrics.faultCode&&metrics.faultCode!=='OK'&&metrics.faultCode!=='SAFE_LOCAL'&&metrics.faultCode!=='DECODER_STARTING'){
    if(!ui.note.textContent.includes(metrics.faultCode)){
      ui.note.textContent=metrics.faultCode+': '+metrics.faultMessage+' '+metrics.faultAction;
    }
  }
},500);
render();connect();