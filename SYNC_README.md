# Jawahar Live Sync — architecture and build

## Product flow

Official YouTube / YouTube Music Android app
→ Android AudioPlaybackCapture + MediaProjection
→ 48 kHz stereo PCM16
→ Opus music encoding
→ JLS1/v1 timestamped WebSocket uplink
→ Go relay / bounded recovery ring
→ authenticated browser guest
→ Opus decode
→ PCM SharedArrayBuffer
→ AudioWorklet
→ shared server-timeline playout.

The host's direct phone speaker is not part of the synchronized listener group; use mute/headphones when measuring guest-to-guest speaker alignment.

## Android

Minimum Android version: Android 10 / API 29.

Build:

```bash
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`

Relay provisioning is entered at runtime through **SETUP RELAY**. Host credentials are not committed into the repository or baked into the default APK.

## Relay

Canonical endpoints:

- `/v1/ws/host`
- `/v1/ws/guest/<guest-token>`
- `/r/<guest-token>`
- `/healthz`
- `/metrics` (Bearer-protected when `JLS_METRICS_TOKEN` is configured)

The canonical audio packet is documented by `protocol/jls_v1_golden_vectors.json` and tested in Kotlin, Go and JavaScript.

## Guest synchronization

The guest establishes an NTP-style server monotonic clock model, derives each frame's nominal server timeline from epoch + sample position, applies one room-wide common delay, maps the target server time into AudioContext time, and lets the AudioWorklet perform bounded phase correction.

Large timing errors trigger a short crossfaded hard resync rather than allowing stale audio to accumulate.

## Important Android limitation

On Android 15 QPR1+ MediaProjection is stopped when the device locks. The host should remain unlocked during a live room unless device testing proves a supported alternative.

## Evidence discipline

Simulation, CI, public-WSS smoke tests and synthetic load tests are not substitutes for real-device measurements. Real YouTube capture, long-duration continuity and physical acoustic skew remain marked unmeasured until the release APK is tested on target hardware.
