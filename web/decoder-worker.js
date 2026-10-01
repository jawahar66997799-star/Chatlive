let config = { sampleRate: 48000, channels: 2, codec: 'pcm16le', wasmUrl: '/vendor/libopus-wasm/index.js' };
let webDecoder = null;
let wasmDecoder = null;
let decoderMode = 'pcm';
let pendingMeta = new Map();
let nextWebTimestampUs = 1;
let decoderInitPromise = Promise.resolve();
function postError(message, fatal=false) { self.postMessage({ type:'decoder-error', message:String(message), fatal, mode:decoderMode }); }

function levelOf(pcm){
  if(!pcm?.length)return {rmsDb:-120,peakDb:-120};
  let sum=0,peak=0;
  for(let i=0;i<pcm.length;i++){
    const v=Number(pcm[i])||0,a=Math.abs(v);
    sum+=v*v;if(a>peak)peak=a;
  }
  const rms=Math.sqrt(sum/pcm.length);
  const db=v=>v>1e-6?Math.max(-120,20*Math.log10(v)):-120;
  return {rmsDb:db(rms),peakDb:db(peak)};
}
async function initDecoder(next) {
  const desired = { ...config, ...next };
  const sameConfig =
    desired.codec === config.codec &&
    desired.sampleRate === config.sampleRate &&
    desired.channels === config.channels;

  const ready =
    (desired.codec === 'opus' && decoderMode === 'webcodecs-opus' && !!webDecoder) ||
    (desired.codec === 'opus' && decoderMode === 'wasm-opus-local' && !!wasmDecoder) ||
    ((desired.codec === 'pcm16le' || desired.codec === 'pcm') && decoderMode === 'pcm-worker');

  config = desired;
  if (sameConfig && ready) return;

  webDecoder?.close?.(); webDecoder = null; wasmDecoder?.free?.(); wasmDecoder = null;
  pendingMeta.clear(); nextWebTimestampUs = 1;
  if (config.codec === 'pcm16le' || config.codec === 'pcm') { decoderMode='pcm-worker'; self.postMessage({type:'decoder-ready',mode:decoderMode}); return; }
  if (config.codec !== 'opus') throw new Error(`Unsupported codec ${config.codec}`);
  if (typeof AudioDecoder !== 'undefined') {
    try {
      const wc={codec:'opus',sampleRate:config.sampleRate,numberOfChannels:config.channels};
      const support=await AudioDecoder.isConfigSupported(wc);
      if(support.supported){
        webDecoder=new AudioDecoder({output:a=>handleWebCodecsOutput(a),error:e=>postError(`WebCodecs: ${e?.message||e}`)});
        webDecoder.configure(support.config||wc); decoderMode='webcodecs-opus'; self.postMessage({type:'decoder-ready',mode:decoderMode}); return;
      }
    } catch(e){ postError(`WebCodecs probe failed; falling back to WASM: ${e?.message||e}`); }
  }
  let lastWasmError=null;
  const url='/vendor/libopus-wasm/index.js';
  try {
    const mod=await import(url);
    if(typeof mod.createDecoder!=='function')throw new Error('createDecoder export missing');
    wasmDecoder=await mod.createDecoder({sampleRate:config.sampleRate,channels:config.channels});
    decoderMode='wasm-opus-local'; self.postMessage({type:'decoder-ready',mode:decoderMode}); return;
  }catch(e){lastWasmError=e;}
  decoderMode='opus-unavailable'; postError(`No same-origin Opus decoder available: ${lastWasmError?.message||lastWasmError}`,true);
}
function parseWireFrame(buffer,generation,arrivalPerfMs){
  const dv=new DataView(buffer);
  if(dv.byteLength<64)throw new Error('short JLS1 v1 audio frame');
  const magic=String.fromCharCode(dv.getUint8(0),dv.getUint8(1),dv.getUint8(2),dv.getUint8(3));
  if(magic!=='JLS1')throw new Error(`unknown frame magic ${magic}`);
  const version=dv.getUint8(4),messageType=dv.getUint8(5),flags=dv.getUint8(6),headerBytes=dv.getUint8(7);
  if(version!==1)throw new Error(`unsupported JLS1 protocol version ${version}`);
  if(messageType!==1)throw new Error(`unsupported JLS1 binary message type ${messageType}`);
  if(headerBytes!==64)throw new Error(`invalid JLS1 audio header length ${headerBytes}`);
  if((flags&~0x03)!==0)throw new Error(`unsupported JLS1 audio flags ${flags}`);
  if(dv.getUint8(57)!==0||dv.getUint32(60,false)!==0)throw new Error('JLS1 reserved header bytes must be zero');

  const epoch=dv.getBigUint64(8,false),seq=dv.getBigUint64(16,false),captureNs=dv.getBigUint64(24,false),samplePosition=dv.getBigUint64(32,false),relayIngressNs=dv.getBigUint64(40,false);
  const sr=dv.getUint32(48,false),frameSamples=dv.getUint16(52,false),ch=dv.getUint8(54),codecId=dv.getUint8(55),layer=dv.getUint8(56),payloadLen=dv.getUint16(58,false);
  if(epoch===0n)throw new Error('JLS1 epoch must be non-zero');
  if(sr!==48000)throw new Error(`unsupported JLS1 sample rate ${sr}`);
  if(frameSamples!==480&&frameSamples!==960)throw new Error(`unsupported JLS1 frame sample count ${frameSamples}`);
  if(ch!==2)throw new Error(`unsupported JLS1 channel count ${ch}`);
  if(codecId!==1)throw new Error(`unsupported JLS1 codec id ${codecId}`);
  if(layer>1)throw new Error(`unsupported JLS1 layer ${layer}`);
  if(payloadLen<1)throw new Error('JLS1 payload must be non-empty');
  if(64+payloadLen!==dv.byteLength)throw new Error('JLS1 protocol payload length mismatch');

  return {kind:'opus',payload:buffer.slice(64),meta:{protocol:'JLS1/64',generation,arrivalPerfMs,epoch:String(epoch),seq:String(seq),serverNs:String(relayIngressNs),captureNs:String(captureNs),samplePosition:Number(samplePosition),frameSamples,frames:frameSamples,sampleRate:sr,channels:ch,flags,layer,discontinuity:!!(flags&1)},sampleRate:sr,channels:ch,durationUs:Math.round(frameSamples*1e6/sr)};
}
function handleWebCodecsOutput(audioData){
  const started=performance.now(),key=Number(audioData.timestamp),meta=pendingMeta.get(key);pendingMeta.delete(key);if(!meta){audioData.close();return;}
  const frames=audioData.numberOfFrames,channels=Math.min(2,audioData.numberOfChannels),planar=[];
  try{for(let c=0;c<channels;c++){const a=new Float32Array(frames);audioData.copyTo(a,{planeIndex:c,format:'f32-planar'});planar.push(a);}if(!planar.length)throw new Error('AudioData has no channels');
    const pcm=new Float32Array(frames*2);for(let i=0;i<frames;i++){pcm[i*2]=planar[0][i];pcm[i*2+1]=(planar[1]||planar[0])[i];}
    const level=levelOf(pcm);
    self.postMessage({type:'pcm-frame',...meta,frames,channels:2,sampleRate:audioData.sampleRate,decodeMs:performance.now()-started,decodedRmsDb:level.rmsDb,decodedPeakDb:level.peakDb,pcm},[pcm.buffer]);
  }catch(e){postError(e?.message||e);}finally{audioData.close();}
}
async function decodeOpus(msg){
  const started=performance.now(),packet=new Uint8Array(msg.payload);
  if(decoderMode==='webcodecs-opus'&&webDecoder){
    const durationUs=msg.durationUs||20000,ts=nextWebTimestampUs;
    nextWebTimestampUs+=durationUs;
    pendingMeta.set(ts,msg.meta||{});
    webDecoder.decode(new EncodedAudioChunk({type:'key',timestamp:ts,duration:durationUs,data:packet}));
    return;
  }
  if(decoderMode.startsWith('wasm-opus')&&wasmDecoder){
    try{const decoded=wasmDecoder.decodeFloat(packet),f32=decoded instanceof Float32Array?decoded:new Float32Array(decoded.buffer??decoded),frames=Math.floor(f32.length/Math.max(1,config.channels)),pcm=new Float32Array(frames*2);
      for(let i=0;i<frames;i++){const l=f32[i*config.channels],rr=config.channels>1?f32[i*config.channels+1]:l;pcm[i*2]=l;pcm[i*2+1]=rr;}
      const level=levelOf(pcm);
      self.postMessage({type:'pcm-frame',...(msg.meta||{}),frames,channels:2,sampleRate:config.sampleRate,decodeMs:performance.now()-started,decodedRmsDb:level.rmsDb,decodedPeakDb:level.peakDb,pcm},[pcm.buffer]);
    }catch(e){postError(e?.message||e);}return;
  }
  postError('Opus frame received before a decoder became available',true);
}
function queueDecoderInit(next){
  decoderInitPromise = decoderInitPromise
    .catch(()=>{})
    .then(()=>initDecoder(next));
  return decoderInitPromise;
}

self.onmessage=async e=>{
  const m=e.data||{};
  try{
    if(m.type==='init')return await queueDecoderInit(m);
    if(m.type==='reset'){
      await decoderInitPromise.catch(()=>{});
      pendingMeta.clear();nextWebTimestampUs=1;
      if(webDecoder){
        try{
          webDecoder.reset();
          webDecoder.configure({codec:'opus',sampleRate:config.sampleRate,numberOfChannels:config.channels});
        }catch(e){
          webDecoder?.close?.();webDecoder=null;decoderMode='starting';
          await queueDecoderInit({...config});
        }
      }
      try{wasmDecoder?.reset?.()}catch{}
      self.postMessage({type:'decoder-reset-ready',mode:decoderMode});
      return;
    }
    if(m.type==='frame'){
      await decoderInitPromise.catch(()=>{});
      const started=performance.now(),parsed=parseWireFrame(m.buffer,m.generation,m.arrivalPerfMs);
      if(parsed.kind==='pcm'){parsed.out.decodeMs=performance.now()-started;self.postMessage(parsed.out,[parsed.out.pcm.buffer]);return;}
      if(config.codec!=='opus'||config.sampleRate!==parsed.sampleRate||config.channels!==parsed.channels||decoderMode==='pcm-worker'||decoderMode==='pcm'){
        await queueDecoderInit({codec:'opus',sampleRate:parsed.sampleRate,channels:parsed.channels,wasmUrl:config.wasmUrl});
      }
      return await decodeOpus({payload:parsed.payload,durationUs:parsed.durationUs,meta:parsed.meta});
    }
    if(m.type==='opus-frame'){
      await decoderInitPromise.catch(()=>{});
      return await decodeOpus(m);
    }
  }catch(err){postError(err?.message||err);}
};