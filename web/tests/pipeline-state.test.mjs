import assert from 'node:assert/strict';
import {deriveGuestPipelineState,GUEST_PIPELINE_STATES as S,deriveGuestContinuityState,GUEST_CONTINUITY_STATES as C} from '../sync-core.mjs';

const now=10000;
const healthy={
  hasRoom:true,networkOnline:true,relayOpen:true,everRelayConnected:true,
  relayStateKnown:true,lastRelayMessageAt:9900,hostOnline:true,hostOnlineAt:1000,
  binaryFrames:20,lastBinaryAt:9900,decoderReady:true,decoderFailed:false,
  pcmFrames:20,decodedSignal:true,audioContextState:'running',unlockAttempted:true,
  clockLocked:true,bufferMs:400,targetDelayMs:850,schedulerReady:true,
  outputAudible:false,lastAudibleOutputAt:0,lastDirectAudibleAt:0,
  continuityMode:false,lastUnderrunAt:0,resyncUntil:0,
};
const code=e=>deriveGuestPipelineState({...healthy,...e},now).code;

assert.equal(code({relayOpen:false,everRelayConnected:false}),S.CONNECTING);
assert.equal(code({relayStateKnown:false}),S.RELAY_CONNECTED);
assert.equal(code({hostOnline:false}),S.WAITING_FOR_HOST);
assert.equal(code({binaryFrames:0,hostOnlineAt:9600}),S.HOST_ONLINE);
assert.equal(code({binaryFrames:0,hostOnlineAt:8500}),S.WAITING_FOR_AUDIO);
assert.equal(code({binaryFrames:0,hostOnlineAt:1000}),S.NO_BINARY_AUDIO);
assert.equal(code({decoderReady:false}),S.AUDIO_RECEIVING);
assert.equal(code({pcmFrames:0}),S.DECODER_READY);
assert.equal(code({decodedSignal:false}),S.WAITING_FOR_AUDIO);
assert.equal(code({clockLocked:false,bufferMs:55}),S.BUFFERING);
assert.match(deriveGuestPipelineState({...healthy,clockLocked:false,bufferMs:55},now).label,/BUFFERING 55 ms/);
assert.equal(code({clockLocked:true,bufferMs:400,schedulerReady:true}),S.CLOCK_LOCKED);
assert.equal(code({outputAudible:true}),S.PLAYING);

assert.equal(code({networkOnline:false}),S.NETWORK_LOST);
assert.equal(code({relayOpen:false,everRelayConnected:true}),S.NETWORK_LOST);
assert.equal(code({lastRelayMessageAt:5000}),S.NETWORK_LOST);
assert.equal(code({lastBinaryAt:5000,lastRelayMessageAt:9900}),S.HOST_STALLED);
assert.equal(code({decoderFailed:true,decoderError:'boom'}),S.DECODER_FAILED);
assert.equal(code({audioContextState:'suspended',unlockAttempted:true}),S.AUDIOCONTEXT_SUSPENDED);
assert.equal(code({audioContextState:'suspended',unlockAttempted:false}),S.AUTOPLAY_BLOCKED);
assert.equal(code({lastUnderrunAt:9500,outputAudible:false}),S.BUFFER_UNDERRUN);
assert.equal(code({resyncUntil:11000,resyncReason:'route change'}),S.RESYNCING);

// The false-positive regression that motivated this state machine:
// successful scheduling/buffering must never claim PLAYING without rendered signal.
assert.notEqual(code({schedulerReady:true,bufferMs:850,outputAudible:false,lastAudibleOutputAt:0}),S.PLAYING);

// Recent observed worklet/direct output is enough to truthfully report PLAYING.
assert.equal(code({lastAudibleOutputAt:9500}),S.PLAYING);
assert.equal(code({lastDirectAudibleAt:9500,continuityMode:true}),S.PLAYING);

// If decoded data is silent, output state cannot be promoted to playback.
assert.equal(code({decodedSignal:false,outputAudible:true}),S.WAITING_FOR_AUDIO);

console.log('JLS guest pipeline-state tests: PASS');


const continuity=e=>deriveGuestContinuityState({...healthy,relayStreamState:'AUDIO_FLOWING',...e},now).code;
assert.equal(continuity({relayOpen:false,everRelayConnected:true}),C.RECONNECTING);
assert.equal(continuity({relayStreamState:'CONNECTED_NO_HOST',hostOnline:false}),C.CONNECTED_NO_HOST);
assert.equal(continuity({relayStreamState:'HOST_CONNECTED_NO_AUDIO',hostOnline:true,binaryFrames:0}),C.HOST_CONNECTED_NO_AUDIO);
assert.equal(continuity({relayStreamState:'HOST_STALLED',hostOnline:true}),C.HOST_STALLED);
assert.equal(continuity({relayStreamState:'AUDIO_FLOWING',hostOnline:true,outputAudible:false,lastAudibleOutputAt:0,lastDirectAudibleAt:0}),C.AUDIO_FLOWING);
assert.equal(continuity({relayStreamState:'AUDIO_FLOWING',hostOnline:true,outputAudible:true}),C.LIVE);
assert.equal(continuity({relayStreamState:'AUDIO_FLOWING',hostOnline:true,guestTooSlowUntil:11000}),C.GUEST_TOO_SLOW);
