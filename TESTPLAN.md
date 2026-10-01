# Jawahar Live Sync — final real-device release test plan

Use the exact final APK and production relay revision recorded in the final release audit.

## Host capture gate

1. Install the final APK.
2. Open **Jawahar Live Sync**.
3. Open **SETUP RELAY** and enter the private production provisioning values.
4. Tap **START** and grant RECORD_AUDIO plus Android MediaProjection consent.
5. Open official YouTube and play normal media audio.
6. Verify the host reports non-zero PCM / **CAPTURE_OK**, Opus packets encoded, relay **CONNECTED**, bytes uploaded and stable send-buffer depth.
7. Repeat with YouTube Music if required.

## End-to-end guest gate

1. Open the guest link on at least two independent browser devices.
2. Join playback when the browser requires a gesture.
3. Verify both listeners receive continuous audio.
4. Confirm relay clock is ready and the browser reports a shared room delay.
5. Confirm no repeated hard-resync loop or growing stale backlog.

## Required physical matrix

| Test | Required evidence |
|---|---|
| YouTube foreground, screen on, 10 min | host CSV + guest diagnostics |
| Pause / resume | no stale replay |
| Seek / change video | recovery/discontinuity behavior |
| Playlist/ad transition | continuity notes |
| Host app backgrounded | capture/uplink continuity |
| Screen lock | expected MediaProjection stop on affected Android versions |
| Wired / Bluetooth route | output-route reset behavior |
| Wi-Fi interruption 0.5 s / 1 s / 3 s | recovery time |
| Wi-Fi → cellular | reconnect/resume, stale discard |
| Guest refresh / reconnect | live-edge resume |
| Late guest join | starts on future common deadline |
| Browser background / resume | real platform behavior |
| 30-minute soak | capture/uplink/guest logs |
| 2-hour soak | capture/uplink/guest logs |
| Thermal / battery saver | host health telemetry |

## Acoustic synchronization gate

Use a common microphone recording all listener speakers. Run the calibration chirp / cross-correlation tooling and report listener-to-listener skew as **MEASURED** data.

Do not convert simulated targets into measured claims. Record p50, p95, p99 and max skew after calibration.

## Release evidence to return

- Host CSV.
- Browser diagnostics from each listener.
- Relay metrics/log excerpt.
- Common-microphone recording.
- Device/browser/OS versions.
- Network conditions for each failure test.
