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

assert.match(player,/Precision playout produced silence/);

assert.match(player,/SAFE LOCAL/);

assert.match(worker,/levelOf\(pcm\)/);

assert.match(worker,/outputPeakDb/);

assert.match(worklet,/outputRmsDb/);
assert.match(worklet,/outputPeakDb/);
