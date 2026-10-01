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
let ws=null,reconnectTimer=null,backoff=250,generation=0,clockTimer=null,pingId=0,pings=new Map();
let audio=null,node=null,decoder=null,sabWriter=null,joined=false,hostOnline=false,currentEpoch=null;
let sampleRate=48000,channels=2,codec='opus',lastEpoch=null,lastSeq=null,lastOutputLatency=null;
const metrics={rttMs:null,clockOffsetMs:null,clockDriftPpm:null,clockConfidenceMs:null,bufferMs:0,targetDelayMs:400,recommendedDelayMs:400,lateFrames:0,decoderMs:0,underruns:0,resamplerPpm:0,hardResyncs:0,outputLatencyMs:null,baseLatencyMs:null,reconnects:0,overruns:0,staleDrops:0,decoder:'starting',audioState:'none',epoch:null,seq:null,serverInstanceId:null,serverRestarts:0,crossOriginIsolated:!!self.crossOriginIsolated};
self.__JLS_METRICS__=metrics;

function setState(s,k='warn'){ui.state.textContent=s;ui.dot.className='dot '+k}
function note(s){ui.note.textContent=s}
function wsURL(){
  const base=(location.protocol==='https:'?'wss:':'ws:')+'//'+location.host;
  const u=new URL(base+'/v1/ws/guest/'+encodeURIComponent(room));
  if(lastEpoch!==null&&lastSeq!==null){u.searchParams.set('epoch',lastEpoch);u.searchParams.set('seq',lastSeq)}
  return u.href;
}

function startDecoder(){
  if(decoder)return;
  decoder=new Worker('/decoder-worker.js');
  decoder.onmessage=e=>onDecoded(e.data||{});
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
  node=new AudioWorkletNode(audio,'jawahar-sync-processor',{numberOfOutputs:1,outputChannelCount:[2]});
  node.connect(audio.destination);node.port.onmessage=e=>onWorklet(e.data||{});
  node.port.postMessage({type:'config',sourceRate:sampleRate,hardResyncMs:100,maxPpm:300,deadbandMs:1});
  if(self.crossOriginIsolated&&typeof SharedArrayBuffer!=='undefined'){try{sabWriter=new SabWriter(node)}catch{sabWriter=null}}
  metrics.audioState=audio.state;sampleOutputClock();
  audio.addEventListener('statechange',()=>{
    metrics.audioState=audio.state;
    if((audio.state==='suspended'||audio.state==='interrupted')&&joined){ui.join.disabled=false;ui.join.textContent='RESUME LISTENING';setState('Playback interrupted','bad')}
    else if(audio.state==='running'&&joined&&hostOnline)setState('Listening','ok');
  });
}

function resetPlayout(reason){
  timeline.reset();currentEpoch=null;roomTimeline=null;outputMap=new OutputTimeMapper();sabWriter?.reset();node?.port.postMessage({type:'reset'});decoder?.postMessage({type:'reset'});
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
  ws.onopen=()=>{if(gen!==generation)return;backoff=250;ui.join.disabled=false;setState(hostOnline?'Host online':'Connected','ok');clockBurst();clearInterval(clockTimer);clockTimer=setInterval(sendClock,2000)};
  ws.onmessage=e=>{if(gen!==generation)return;if(typeof e.data==='string')onControl(e.data);else if(e.data instanceof ArrayBuffer){const t=performance.now();decoder.postMessage({type:'frame',buffer:e.data,generation:gen,arrivalPerfMs:t},[e.data])}};
  ws.onclose=()=>{if(gen!==generation)return;clearInterval(clockTimer);clockTimer=null;if(joined)resetPlayout('reconnect');setState(navigator.onLine===false?'Network offline':'Reconnecting…','bad');scheduleReconnect()};
  ws.onerror=()=>{};
}
function scheduleReconnect(){clearTimeout(reconnectTimer);metrics.reconnects++;const d=backoff;backoff=Math.min(5000,Math.round(backoff*1.7));reconnectTimer=setTimeout(connect,d)}
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
    const d=Number(tl.recommended_delay_ns);if(Number.isFinite(d)&&d>0){const ms=d/1e6;roomD.setTarget(ms);if(roomD.lastMs==null){roomD.current=roomD.target;roomD.lastMs=performance.now()}}
    decoder.postMessage({type:'init',codec,sampleRate,channels,wasmUrl:'/vendor/libopus-wasm/index.js'});node?.port.postMessage({type:'config',sourceRate:sampleRate});
    if(!hostOnline||m.reason==='host_offline'){resetPlayout('host-offline');setState('Host offline','bad')}else setState(joined?'Buffering…':'Host online',joined?'warn':'ok');
    return;
  }
  if(m.type==='hello'){
    hostOnline=!!m.hostOnline;sampleRate=Number(m.sampleRate)||48000;channels=Number(m.channels)||2;codec=m.codec||'pcm16le';
    const d=Math.max(150,Math.min(1000,Number(m.targetDelayMs)||400));roomD.current=roomD.target=d;roomD.lastMs=performance.now();
    decoder.postMessage({type:'init',codec,sampleRate,channels,wasmUrl:'/vendor/libopus-wasm/index.js'});node?.port.postMessage({type:'config',sourceRate:sampleRate});
    setState(hostOnline?(joined?'Buffering…':'Host online'):'Host offline',hostOnline?'ok':'bad');
    return;
  }
  if(m.type==='host-offline'){hostOnline=false;resetPlayout('host-offline');setState('Host offline','bad')}
  if(m.type==='host-online'){hostOnline=true;setState(joined?'Buffering…':'Host online',joined?'warn':'ok')}
}

function onDecoded(m){
  if(m.type==='decoder-ready'){metrics.decoder=m.mode;return}
  if(m.type==='decoder-error'){if(m.fatal){setState('Audio decoder unavailable','bad');note(m.message)}return}
  if(m.type!=='pcm-frame'||m.generation!==generation)return;
  metrics.decoderMs=m.decodeMs||0;metrics.seq=m.seq;if(!joined||!node||!audio||!clock.ready)return;
  const epoch=String(m.epoch);
  if(m.discontinuity){timeline.reset();sabWriter?.reset();node.port.postMessage({type:'reset'});metrics.hardResyncs++}
  if(currentEpoch===null)currentEpoch=epoch;else if(epoch!==currentEpoch){resetPlayout('epoch');currentEpoch=epoch}
  metrics.epoch=epoch;
  const ingressMs=Number(BigInt(m.serverNs))/1e6,pos=Number.isFinite(m.samplePosition)?m.samplePosition:null;
  let nominal=null;if(roomTimeline&&pos!=null)nominal=roomTimeline.originServerMs+(pos-roomTimeline.originSample)*1000/roomTimeline.sampleRate;
  const tr=timeline.ingest({epoch,seq:m.seq,serverMs:ingressMs,frames:m.frames,sampleRate:m.sampleRate||sampleRate,sampleIndex:pos,timelineServerMs:nominal});if(!tr.accepted)return;
  lastEpoch=epoch;lastSeq=String(m.seq);
  const nowD=performance.now(),age=clock.serverAtLocal(m.arrivalPerfMs)-tr.timelineServerMs,c=clock.snapshot();
  suggestedD.observe({networkAgeMs:age,decodeMs:m.decodeMs||0,clockConfidenceMs:c?.confidence95Ms||0,nowMs:nowD});
  const d=roomD.tick(nowD);metrics.targetDelayMs=d;metrics.recommendedDelayMs=suggestedD.currentMs;
  sampleOutputClock();
  const targetPerf=clock.localAtServer(targetServerTimeMs(tr.timelineServerMs,d));if(!Number.isFinite(targetPerf))return;
  const now=performance.now();if(targetPerf<now-1000){metrics.staleDrops++;return}
  let ct=outputMap.contextTimeForPerformance(targetPerf);if(!Number.isFinite(ct))ct=fallbackContextTimeForPerformance(audio.currentTime,now,targetPerf,bestFallbackLatency());
  const meta={targetFrame:Math.round(ct*audio.sampleRate),startSample:tr.startSample,frames:m.frames};
  if(targetPerf<now){metrics.lateFrames++;suggestedD.markLate(now,now-targetPerf)}
  if(sabWriter){if(!sabWriter.write(m.pcm,meta)){metrics.overruns++;metrics.hardResyncs++;sabWriter.reset();node.port.postMessage({type:'reset'});sabWriter.write(m.pcm,meta)}}
  else node.port.postMessage({type:'pcm',...meta,pcm:m.pcm},[m.pcm.buffer]);
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
  if(m.type==='metrics'){metrics.bufferMs=m.bufferMs;metrics.resamplerPpm=m.ppm;metrics.underruns=m.underruns;metrics.lateFrames=Math.max(metrics.lateFrames,m.lateFrames);metrics.hardResyncs=Math.max(metrics.hardResyncs,m.hardResyncs);metrics.overruns=Math.max(metrics.overruns,m.overruns)}
  else if(m.type==='underrun'){metrics.underruns=m.count;suggestedD.markLate(performance.now(),30);setState('Buffering…','warn')}
  else if(m.type==='late'){metrics.lateFrames=m.count;suggestedD.markLate(performance.now(),Math.abs(m.errorMs||0))}
  else if(m.type==='hard-resync'){metrics.hardResyncs=m.count;note('Large timing error corrected with a short crossfade.')}
  else if(m.type==='overrun')metrics.overruns=m.count;
}

async function unlock(){
  try{await ensureAudio();await audio.resume();if(audio.state!=='running')throw new Error('AudioContext '+audio.state);joined=true;ui.join.disabled=true;ui.join.textContent='LISTENING';resetPlayout('join');setState(hostOnline?'Buffering…':'Host offline',hostOnline?'warn':'bad');note('Live audio only. No YouTube or song download is needed on this device.')}
  catch(e){ui.join.disabled=false;ui.join.textContent='TAP TO LISTEN';setState('Tap required','warn');note('Audio could not start: '+(e?.message||e))}
}
ui.join.addEventListener('click',unlock);
ensureAudio().then(()=>{if(audio.state==='running'){joined=true;ui.join.disabled=true;ui.join.textContent='LISTENING'}else{ui.join.disabled=false;ui.join.textContent='TAP TO LISTEN'}}).catch(()=>{ui.join.disabled=false});

function resumeVisible(){
  if(!joined||!audio)return;
  audio.resume().then(()=>{if(audio.state!=='running')throw new Error('gesture');resetPlayout('resume');setState(hostOnline?'Buffering…':'Host offline',hostOnline?'warn':'bad')})
  .catch(()=>{ui.join.disabled=false;ui.join.textContent='RESUME LISTENING';setState('Tap to resume','warn')});
}
document.addEventListener('visibilitychange',()=>{if(document.visibilityState==='visible')resumeVisible();else if(joined)note('Background/lock-screen playback depends on the browser and OS; alignment will be rechecked on return.')});
document.addEventListener('freeze',()=>{try{ws?.close(4000,'page frozen')}catch{}});
document.addEventListener('resume',()=>{connect(true);resumeVisible()});
window.addEventListener('online',()=>connect());window.addEventListener('offline',()=>setState('Network offline','bad'));
try{navigator.mediaDevices?.addEventListener?.('devicechange',()=>{outputMap=new OutputTimeMapper();sampleOutputClock();if(joined){node?.port.postMessage({type:'reset'});timeline.reset();metrics.hardResyncs++}})}catch{}

function sendStats(){
  if(!ws||ws.readyState!==WebSocket.OPEN)return;
  const safe=lastSeq!==null&&BigInt(lastSeq)<=BigInt(Number.MAX_SAFE_INTEGER)?Number(lastSeq):0;
  try{ws.send(JSON.stringify({type:'listener_stats',v:1,last_sequence:safe,buffer_depth_ms:metrics.bufferMs,underruns:metrics.underruns,late_frames:metrics.lateFrames,recommended_delay_ms:metrics.recommendedDelayMs,resampler_ppm:metrics.resamplerPpm,hard_resyncs:metrics.hardResyncs}))}catch{}
}
function fmt(v){return Number.isFinite(v)?v.toFixed(1):'—'}
function render(){
  const c=clock.snapshot();
  ui.diag.textContent=[
    'RTT(min): '+fmt(metrics.rttMs)+' ms',
    'Clock offset(now): '+fmt(metrics.clockOffsetMs)+' ms',
    'Clock drift: '+fmt(metrics.clockDriftPpm)+' ppm',
    'Clock confidence: ±'+fmt(metrics.clockConfidenceMs)+' ms',
    'PCM queued: '+fmt(metrics.bufferMs)+' ms',
    'Room D: '+fmt(metrics.targetDelayMs)+' ms · recommended '+fmt(metrics.recommendedDelayMs)+' ms',
    'Decoder: '+metrics.decoder+' · '+fmt(metrics.decoderMs)+' ms',
    'Late: '+metrics.lateFrames+' · underruns: '+metrics.underruns,
    'Resampler: '+fmt(metrics.resamplerPpm)+' ppm',
    'Hard resyncs: '+metrics.hardResyncs+' · overruns: '+metrics.overruns,
    'outputLatency: '+fmt(metrics.outputLatencyMs)+' ms · baseLatency: '+fmt(metrics.baseLatencyMs)+' ms',
    'Reconnects: '+metrics.reconnects+' · stale drops: '+metrics.staleDrops,
    'SAB: '+(sabWriter?'yes':'no')+' · isolated: '+(metrics.crossOriginIsolated?'yes':'no'),
    'Audio: '+metrics.audioState+' · epoch: '+(metrics.epoch??'—')+' · seq: '+(metrics.seq??'—'),
    'Relay instance: '+(metrics.serverInstanceId?metrics.serverInstanceId.slice(0,8)+'…':'—')+' · restarts: '+metrics.serverRestarts,
    'Clock samples: '+(c?.accepted??0)+'/'+(c?.total??0)
  ].join('\n');
}
setInterval(sendStats,2000);setInterval(render,500);render();connect();