# Jawahar Live Sync relay + guest web

One Go process serves the production relay and guest player.

## Endpoints

- `/v1/ws/host` — authenticated Android Opus uplink.
- `/v1/ws/guest/<guest-token>` — authenticated guest stream/control WebSocket.
- `/r/<guest-token>` — one-link guest UI.
- `/v1/clock` — authenticated HTTP clock compatibility endpoint.
- `/healthz` / `/readyz` — health.
- `/metrics` — Prometheus-style metrics; protect with `JLS_METRICS_TOKEN`.

## Wire format

Binary audio uses canonical **JLS1 protocol v1**:

- 64-byte big-endian header.
- Opus, 48 kHz, stereo.
- 10 ms or 20 ms frames.
- 64-bit epoch, sequence, capture timestamp and sample position.
- relay stamps monotonic ingress time.
- shared golden vector: `../protocol/jls_v1_golden_vectors.json`.

Guest-facing JSON serializes the random 64-bit epoch as an exact decimal string to avoid JavaScript integer precision loss.

## Timing model

The relay establishes the room timeline from the first accepted sample-position/server-time anchor. All listeners schedule each source sample against that shared timeline plus one bounded room-wide delay.

Listener telemetry recommends delay; the relay aggregates fresh recommendations and slews the common delay within configured bounds. The browser separately applies clock estimation, output-time mapping, bounded micro-resampling and hard resync for large phase errors.

## Required secrets

- `JLS_ROOM_ID`
- `JLS_HOST_SECRET`
- `JLS_GUEST_TOKEN`

Use cryptographically random values. Never commit them.

Recommended production hardening:

- `JLS_METRICS_TOKEN` — random 32+ character Bearer token.
- `JLS_TRUST_PROXY_HEADERS=true` only behind a deployment edge known to overwrite `X-Real-IP`.
- WSS/HTTPS only.

## Build

The Dockerfile uses Go 1.26.6 and committed module checksums.

```bash
go test -race ./...
go vet ./...
go build ./...
```

The included browser assets are self-contained; the Opus fallback is vendored under `web/vendor/libopus-wasm/` and does not require a runtime CDN.
