import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile,readdir,stat} from 'node:fs/promises';
import {dirname,relative,resolve} from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';

const here=dirname(fileURLToPath(import.meta.url));
const relayWeb=resolve(here,'..');
const repoRoot=resolve(here,'..','..','..');
const mirrorWeb=resolve(repoRoot,'web');
const vendor=resolve(relayWeb,'vendor','libopus-wasm');
const expectedTarballSha256='f645938adcd3381dcae9827ef71248218eaeb8308b6339bd568d1c43a64c5ef8';
const expectedIntegrity='sha512-dt2u6KyBe1IONzZA2Q9TXQbmLsZ6l1vLqgt4n9XC/sE70BegLgwC/hcbZp41aJgURAqalYdnPg0wGSI2IwHyvA==';

const sha256=b=>createHash('sha256').update(b).digest('hex');

async function walk(root){
  const out=[];
  async function rec(dir){
    for(const ent of await readdir(dir,{withFileTypes:true})){
      const p=resolve(dir,ent.name);
      if(ent.isDirectory()) await rec(p);
      else if(ent.isFile()) out.push(relative(root,p).replaceAll('\\','/'));
    }
  }
  await rec(root);
  return out.sort();
}

const metadata=JSON.parse(await readFile(resolve(vendor,'PACKAGE_METADATA.json'),'utf8'));
assert.equal(metadata.name,'libopus-wasm');
assert.equal(metadata.version,'0.4.1');
assert.equal(metadata.license,'MIT');
assert.equal(metadata.npmTarballSha256,expectedTarballSha256);
assert.equal(metadata.npmDistIntegrity,expectedIntegrity);
assert.deepEqual(metadata.browserRuntimeFiles,['index.js','generated/libopus.generated.mjs']);
assert.equal(metadata.wasmPackaging,'inline in generated/libopus.generated.mjs');
assert.equal(metadata.wasmAuditSidecar,'generated/libopus.generated.wasm');
assert.equal(metadata.wasmAuditSidecarRuntimeRequired,false);

const runtimeFiles=metadata.browserRuntimeFiles.map(p=>resolve(vendor,p));
for(const p of runtimeFiles){
  assert.ok((await stat(p)).size>0,'runtime vendor file must be non-empty: '+p);
}
const wasmPath=resolve(vendor,metadata.wasmAuditSidecar);
const wasm=await readFile(wasmPath);
assert.ok(wasm.byteLength>1024,'WASM audit sidecar must be non-empty');
assert.deepEqual([...wasm.subarray(0,4)],[0x00,0x61,0x73,0x6d],'WASM magic');

const [indexSource,generatedSource,workerSource,playerSource,license,thirdParty,manifestText]=await Promise.all([
  readFile(resolve(vendor,'index.js'),'utf8'),
  readFile(resolve(vendor,'generated/libopus.generated.mjs'),'utf8'),
  readFile(resolve(relayWeb,'decoder-worker.js'),'utf8'),
  readFile(resolve(relayWeb,'player.js'),'utf8'),
  readFile(resolve(vendor,'LICENSE.txt'),'utf8'),
  readFile(resolve(vendor,'THIRD_PARTY_NOTICES.md'),'utf8'),
  readFile(resolve(vendor,'MANIFEST.sha256'),'utf8'),
]);

assert.match(indexSource,/from\s+["']\.\/generated\/libopus\.generated\.mjs["']/);
assert.match(generatedSource,/function\s+findWasmBinary\(\)\{return binaryDecode\(/);
assert.match(workerSource,/\/vendor\/libopus-wasm\/index\.js/);
for(const [name,source] of [['decoder-worker.js',workerSource],['player.js',playerSource],['vendor/index.js',indexSource],['vendor/generated/libopus.generated.mjs',generatedSource]]){
  assert.doesNotMatch(source,/https?:\/\//i,name+' must not contain a runtime HTTP(S) decoder import');
}
assert.match(license,/MIT License/);
assert.match(thirdParty,/libopus/i);
assert.match(thirdParty,/BSD-style license/i);

const manifest=new Map();
for(const line of manifestText.trim().split(/\r?\n/)){
  const m=line.match(/^([0-9a-f]{64})\s+\.\/(.+)$/);
  assert.ok(m,'invalid SHA-256 manifest line: '+line);
  manifest.set(m[2],m[1]);
}
for(const rel of ['LICENSE.txt','PACKAGE_METADATA.json','THIRD_PARTY_NOTICES.md','index.js','generated/libopus.generated.mjs','generated/libopus.generated.wasm']){
  const bytes=await readFile(resolve(vendor,rel));
  assert.equal(manifest.get(rel),sha256(bytes),'manifest SHA mismatch for '+rel);
}

const imported=[...indexSource.matchAll(/\bfrom\s+["']([^"']+)["']/g)].map(m=>m[1]);
assert.deepEqual(imported,['./generated/libopus.generated.mjs']);
for(const spec of imported){
  const target=resolve(vendor,spec);
  assert.ok((await stat(target)).size>0,'imported vendor module missing: '+spec);
}
const mod=await import(pathToFileURL(resolve(vendor,'index.js')).href+'?graph='+Date.now());
assert.equal(typeof mod.createDecoder,'function','decoder export missing');

const relayFiles=await walk(relayWeb);
const mirrorFiles=await walk(mirrorWeb);
assert.deepEqual(mirrorFiles,relayFiles,'root web/ must be an exact file mirror of relay/web/');
for(const rel of relayFiles){
  const [a,b]=await Promise.all([readFile(resolve(relayWeb,rel)),readFile(resolve(mirrorWeb,rel))]);
  assert.equal(sha256(b),sha256(a),'mirror content mismatch: '+rel);
}

console.log('JLS Opus vendor/self-containment audit: PASS');
