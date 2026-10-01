import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath,pathToFileURL} from 'node:url';
import {dirname,resolve} from 'node:path';

const here=dirname(fileURLToPath(import.meta.url));
const web=resolve(here,'..');
const [html,player,worker,worklet,core,opusIndex,opusGenerated,opusLicense]=await Promise.all([
  readFile(resolve(web,'index.html'),'utf8'),
  readFile(resolve(web,'player.js'),'utf8'),
  readFile(resolve(web,'decoder-worker.js'),'utf8'),
  readFile(resolve(web,'worklet.js'),'utf8'),
  readFile(resolve(web,'sync-core.mjs'),'utf8'),
  readFile(resolve(web,'vendor/libopus-wasm/index.js'),'utf8'),
  readFile(resolve(web,'vendor/libopus-wasm/generated/libopus.generated.mjs'),'utf8'),
  readFile(resolve(web,'vendor/libopus-wasm/LICENSE.txt'),'utf8'),
]);

assert.match(html,/type="module"\s+src="\/player\.js"/);
assert.match(player,/\/v1\/ws\/guest\//);
assert.match(player,/searchParams\.set\('epoch'/);
assert.match(player,/searchParams\.set\('seq'/);
assert.match(player,/type:'clock_req'/);
assert.match(player,/t0_guest_ns/);
assert.match(player,/type:'listener_stats'/);
assert.match(player,/getOutputTimestamp/);
assert.match(player,/outputLatency/);
assert.match(player,/baseLatency/);
assert.match(player,/SharedArrayBuffer/);
assert.match(player,/roomDNeedsAuthoritativeSnap/);
assert.match(player,/roomD\.reset\(ms,performance\.now\(\)\)/);
assert.match(player,/visibilitychange/);
assert.match(worker,/JLS1\/64/);
assert.match(worker,/webcodecs-opus/);
assert.match(worker,/wasm-opus/);
assert.match(worker,/\/vendor\/libopus-wasm\/index\.js/);
assert.doesNotMatch(worker,/cdn\.jsdelivr\.net/);
assert.match(worker,/sameConfig/);
assert.match(worker,/decoderInitPromise/);
assert.match(worker,/queueDecoderInit/);
assert.match(worker,/if\s*\(sameConfig\s*&&\s*ready\)\s*return/);
assert.doesNotMatch(player,/cdn\.jsdelivr\.net/);
assert.match(opusIndex,/createDecoder/);
assert.ok(opusGenerated.length>10000,'vendored generated libopus module unexpectedly small');
assert.match(opusLicense,/MIT License/);
const opusModule=await import(pathToFileURL(resolve(web,'vendor/libopus-wasm/index.js')).href);
assert.equal(typeof opusModule.createDecoder,'function');
const opusDecoder=await opusModule.createDecoder({sampleRate:48000,channels:2});
assert.equal(typeof opusDecoder.decodeFloat,'function');
opusDecoder.free?.();
assert.match(worklet,/hard-resync/);
assert.match(worklet,/crossfade/);
assert.match(worklet,/maxPpm/);
assert.match(core,/class ClockModel/);
assert.match(core,/class AdaptiveDelay/);
assert.match(core,/class PIController/);
assert.match(core,/class OutputTimeMapper/);
assert.match(core,/class ServerInstanceTracker/);
assert.match(core,/reset\(v=this\.target, nowMs=null\)/);

console.log('JLS guest asset/protocol smoke tests: PASS');

assert.match(player,/decodedRmsDb/);

assert.match(player,/outputRmsDb/);

assert.match(player,/fallbackPlayback/);

assert.match(player,/Precision sync is not producing output/);

assert.match(player,/SAFE LOCAL/);

assert.match(worker,/levelOf\(pcm\)/);


assert.match(worklet,/outputRmsDb/);
assert.match(worklet,/outputPeakDb/);

assert.match(worker,/decodedRmsDb/);
assert.match(worker,/decodedPeakDb/);

assert.match(player,/function scheduleSafeLocal/);
assert.match(player,/function shouldForceSafeLocal/);
assert.match(player,/clock not ready/);
assert.match(player,/no precision frames scheduled/);
assert.match(player,/Playout gate:/);
assert.doesNotMatch(player,/silentOutputForMs>1200\s*&&\s*metrics\.scheduledFrames>=20/);

assert.match(player,/function repairJoinState/);
assert.match(player,/function diagnosePipeline/);
assert.match(player,/function applyDiagnosisState/);
assert.match(player,/applyDiagnosisState\(diagnosis\)/);
assert.match(player,/RELAY_DISCONNECTED:\['Relay disconnected · retrying','warn'\]/);
assert.match(player,/NO_HOST_AUDIO:\['No host audio','warn'\]/);
assert.match(player,/CLOCK_CALIBRATING:\['Calibrating synchronization…','warn'\]/);
assert.match(player,/SCHEDULER_BLOCKED:\['Recovering output scheduler','warn'\]/);
assert.match(player,/OUTPUT_SILENT:\['Output silent · recovering','bad'\]/);
assert.match(player,/ui\.note\.textContent=diagnosis\.code\+'[^']*'/);
assert.match(player,/JOIN_STATE_STUCK/);
assert.match(player,/SCHEDULER_BLOCKED/);
assert.match(player,/OUTPUT_SILENT/);
assert.match(player,/Pipeline: relay/);
assert.match(player,/Self-heals:/);
assert.match(player,/repairJoinState\(\);[\s\S]*if\(!audio\|\|!node\)/);

assert.match(player,/startup scheduler bootstrap/);
assert.match(player,/AudioContext\.running is the browser's authoritative proof/);
assert.doesNotMatch(player,/if\s*\(!joined\)\s*\{\s*metrics\.playoutGate='waiting for audio join';\s*return;/);


assert.doesNotMatch(player,/decoder\?\.postMessage\(\{type:'reset'\}\)/,
  'ordinary playout reset must not reset the Opus decoder');
assert.match(worker,/webDecoder\.reset\(\);[\s\S]*webDecoder\.configure\(\{codec:'opus'/,
  'explicit WebCodecs reset must immediately reconfigure the decoder');
assert.match(worker,/type:'decoder-reset-ready'/);


assert.match(player,/numberOfInputs:0/);
assert.match(player,/workletAlive/);
assert.match(player,/workletProcessorErrors/);
assert.match(player,/schedulerErrors/);
assert.match(player,/function rebuildAudioEngine/);
assert.match(player,/function scheduleDirectBuffer/);
assert.match(player,/DIRECT BUFFER playing/);
assert.match(player,/sabWriter\.write\(m\.pcm,meta\)/);
assert.match(worklet,/processQuanta/);


assert.match(player,/function startTransportWatchdog/);
assert.match(player,/relayAge>3500/);
assert.match(player,/transportWatchdogReconnects/);
assert.match(player,/Transport age: relay/);
assert.match(player,/SAFE LOCAL must be an independent continuity path/);
assert.match(player,/const ok=scheduleDirectBuffer\(m,reason\)/);
{
  const a=player.indexOf('function scheduleSafeLocal');
  const b=player.indexOf('function shouldForceSafeLocal',a);
  assert.ok(a>=0&&b>a,'scheduleSafeLocal body not found');
  const safeLocalBody=player.slice(a,b);
  assert.doesNotMatch(
    safeLocalBody,
    /sabWriter\.write\(m\.pcm,meta\)/,
    'safe-local continuity path must not depend on the precision AudioWorklet/SAB scheduler'
  );
  assert.match(safeLocalBody,/scheduleDirectBuffer\(m,reason\)/);
}


assert.match(player,/function holdWakeLock/);
assert.match(player,/navigator\.wakeLock\.request\('screen'\)/);
assert.match(player,/Wake lock:/);


assert.equal((player.match(/function startTransportWatchdog\(/g)||[]).length,1,
  'guest must have exactly one transport watchdog implementation');
assert.doesNotMatch(
  player,
  /ws\.onclose[\s\S]{0,500}resetPlayout\('reconnect'\)/,
  'transient WebSocket close must preserve queued audio instead of flushing playout'
);
assert.match(player,/buffered audio is preserved while reconnecting automatically/);

// Continuity-first regression guards.

assert.match(player,/directNextTime=now\+\.700/);
assert.match(player,/relayAge>3500/);
assert.match(player,/setInterval\(sendClock,1000\)/);
assert.match(player,/navigator\.connection\?\.addEventListener\?\.\('change'/);
assert.match(player,/healthyOutput\?'Listening':'Preparing audio…'/);
assert.doesNotMatch(player,/else setState\(joined\?'Buffering…':'Host online'/);
assert.match(player,/backoff=Math\.min\(800/);
assert.match(player,/underrunBurstCount>=3/);
assert.match(player,/forceContinuityOnNextPcm/);
assert.match(player,/underrun burst continuity/);
assert.doesNotMatch(player,/setState\('Buffering…'/);
