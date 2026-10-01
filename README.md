# Jawahar Live Sync

Jawahar Live Sync is a live synchronized-audio system for a host Android phone and browser listeners.

The host plays audio in the official YouTube / YouTube Music Android app. The Android host app uses Android's permitted playback-capture + MediaProjection path, encodes captured PCM to Opus, and streams timestamped JLS v1 frames to a relay. Guests open one HTTPS link; the browser decodes Opus and schedules PCM on a shared server timeline through AudioWorklet.

## Repository state

- Android host: implemented and CI-built.
- Canonical wire protocol: JLS1 / v1 / 64-byte big-endian audio header.
- Go relay: implemented with auth, bounded replay ring, reconnect/resume, clock sync, adaptive room delay, backpressure and metrics.
- Browser guest: implemented with WebCodecs-when-available + same-origin vendored Opus fallback, SharedArrayBuffer ring, AudioWorklet, clock regression, drift correction and hard resync.
- Railway production deployment: used for integration testing.
- Cross-component contract tests, security audit and synthetic load tests: implemented.

Physical speaker-sync and official YouTube capture remain device-dependent gates and must not be claimed as measured until run on real devices.

See `SYNC_README.md`, `STATUS.md`, `TESTPLAN.md`, and `relay/README.md`.
