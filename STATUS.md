# Jawahar Live Sync status

## Implemented

### Android host
- Android 10+ AudioPlaybackCapture through MediaProjection.
- YouTube and YouTube Music UID filtering.
- 48 kHz stereo PCM16 capture.
- Concentus Opus music encoder, 20 ms default frames, no DTX.
- JLS1/v1 packetizer.
- Secure WSS relay client with reconnect, live-edge stale discard and telemetry.
- Recorder recovery with discontinuity signaling.
- Runtime relay provisioning UI.
- CSV host logs and capture-health diagnostics.

### Relay
- Authenticated host and guest WebSockets.
- Canonical 64-byte JLS1/v1 parser.
- Epoch / sequence / sample-position validation.
- Shared server-monotonic timeline.
- Bounded retransmission / late-join ring.
- Reconnect/resume and stale-audio avoidance.
- Backpressure protection and per-client quotas.
- Four-timestamp guest clock exchange.
- Explicit server process identity for clock-reset safety.
- Room-wide adaptive common delay from listener telemetry.
- Protected metrics support and strict browser security headers.

### Browser guest
- One-link guest page.
- Preconnect before playback gesture.
- WebCodecs Opus optimization where available.
- Same-origin pinned libopus-wasm fallback.
- SharedArrayBuffer PCM ring when cross-origin isolation is available.
- AudioWorklet exact-frame scheduling.
- Clock regression and outlier filtering.
- Output latency mapping.
- PI micro-resampling and crossfaded hard resync.
- Reconnect/resume and relay-restart reset handling.

### Verification
- Android unit tests and APK CI.
- Go race tests / vet / build.
- JavaScript deterministic sync and asset tests.
- Kotlin/Go/JS shared golden-vector protocol tests.
- Synthetic host → relay → guest contract test.
- Security/source/dependency CI.
- Public Railway WSS smoke tooling.
- Synthetic load tooling up to large listener counts.

## Still requires physical measurement

The following must remain unclaimed until performed on actual devices:

1. Official YouTube / YouTube Music produces sustained non-zero capturable PCM on the target host phone.
2. Release APK → production relay → real browser audible playback.
3. Wi-Fi interruption and Wi-Fi↔cellular recovery on actual networks.
4. 30-minute and 2-hour Android capture/uplink soak.
5. Real browser background/lock-screen behavior.
6. Common-microphone listener-to-listener acoustic skew after calibration.

These are physical validation gates, not missing software components.
