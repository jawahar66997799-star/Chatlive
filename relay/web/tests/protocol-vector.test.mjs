import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {dirname,resolve} from 'node:path';
import vm from 'node:vm';

const here=dirname(fileURLToPath(import.meta.url));
const web=resolve(here,'..');
const root=resolve(web,'..','..');
const [doc,source]=await Promise.all([
  readFile(resolve(root,'protocol','jls_v1_golden_vectors.json'),'utf8').then(JSON.parse),
  readFile(resolve(web,'decoder-worker.js'),'utf8'),
]);
const vector=doc.vectors[0];

function parseWithGuest(hex){
  const packetBytes=Array.from(Buffer.from(hex,'hex'));
  const context={self:{},packetBytes};
  vm.createContext(context);
  vm.runInContext(source+'\nself.__parsed=parseWireFrame(Uint8Array.from(packetBytes).buffer,7,123.5);',context);
  return context.self.__parsed;
}
function guestRejects(hex){
  const packetBytes=Array.from(Buffer.from(hex,'hex'));
  const context={self:{},packetBytes};
  vm.createContext(context);
  try{
    vm.runInContext(source+'\nself.__parsed=parseWireFrame(Uint8Array.from(packetBytes).buffer,7,123.5);',context);
    return false;
  }catch{
    return true;
  }
}

const parsed=parseWithGuest(vector.packet_hex);
assert.equal(parsed.kind,'opus');
assert.equal(parsed.meta.protocol,'JLS1/64');
assert.equal(parsed.meta.epoch,vector.epoch_u64);
assert.equal(parsed.meta.seq,vector.sequence_u64);
assert.equal(parsed.meta.captureNs,vector.capture_mono_ns_u64);
assert.equal(parsed.meta.samplePosition,Number(vector.sample_position_u64));
assert.equal(parsed.meta.serverNs,vector.relay_ingress_ns_u64);
assert.equal(parsed.meta.frameSamples,vector.frame_samples_u16);
assert.equal(parsed.meta.sampleRate,vector.sample_rate_u32);
assert.equal(parsed.meta.channels,vector.channels_u8);
assert.equal(parsed.meta.flags,vector.flags);
assert.equal(parsed.meta.layer,vector.layer_u8);
assert.equal(Buffer.from(parsed.payload).toString('hex'),vector.payload_hex);

for(const [name,offset,value] of [
  ['bad-version',4,2],
  ['bad-type',5,2],
  ['bad-header-length',7,63],
  ['wrong-codec',55,2],
  ['wrong-channels',54,1],
]){
  const bytes=Buffer.from(vector.packet_hex,'hex');
  bytes[offset]=value;
  assert.equal(guestRejects(bytes.toString('hex')),true,name+' must be rejected by the real guest parser');
}

console.log('JLS shared golden-vector guest parser tests: PASS');
