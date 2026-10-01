import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {dirname,resolve} from 'node:path';

const here=dirname(fileURLToPath(import.meta.url));
const web=resolve(here,'..');
const [html,player,worker,worklet,core]=await Promise.all([
  readFile(resolve(web,'index.html'),'utf8'),
  readFile(resolve(web,'player.js'),'utf8'),
  readFile(resolve(web,'decoder-worker.js'),'utf8'),
  readFile(resolve(web,'worklet.js'),'utf8'),
  readFile(resolve(web,'sync-core.mjs'),'utf8'),
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
assert.match(player,/visibilitychange/);
assert.match(worker,/JLS1\/64/);
assert.match(worker,/webcodecs-opus/);
assert.match(worker,/wasm-opus/);
assert.match(worklet,/hard-resync/);
assert.match(worklet,/crossfade/);
assert.match(worklet,/maxPpm/);
assert.match(core,/class ClockModel/);
assert.match(core,/class AdaptiveDelay/);
assert.match(core,/class PIController/);
assert.match(core,/class OutputTimeMapper/);

console.log('JLS guest asset/protocol smoke tests: PASS');
