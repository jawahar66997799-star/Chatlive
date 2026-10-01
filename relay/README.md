# Relay + guest web

One process serves:

- `/ws/host?room=ROOM` — Android host PCM uplink
- `/ws/listen?room=ROOM` — guest realtime stream + clock sync
- `/r/ROOM` — one-link guest UI
- `/healthz` — health check

## Deploy

Build with the included Dockerfile. Railway/Render/Fly-compatible platforms can deploy `relay/` as the service root.

## Current wire format

Host uploads raw PCM16 stereo at 48 kHz. The relay attaches epoch, sequence and server monotonic time, then guests schedule PCM through AudioWorklet at a common target delay.

Raw PCM is intentionally the first end-to-end implementation because it removes codec uncertainty while synchronization is measured. The production optimization path is Opus/WebTransport after the end-to-end gate passes.
