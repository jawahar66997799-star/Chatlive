# Phase 0 real-device test plan

This phase proves whether the official YouTube Android app exposes playback PCM to a normal third-party Android app on the user's exact device.

## Required run

1. Install the Phase-0 APK.
2. Open **Jawahar Live Sync**.
3. Tap **START SYNC TEST**.
4. Grant RECORD_AUDIO and Android screen/audio capture consent.
5. Tap **OPEN YOUTUBE** and play normal YouTube audio.
6. Confirm the persistent notification remains present.
7. Return to Jawahar Live Sync after several minutes and check the state.

A successful audible YouTube test should show **CAPTURE_OK** and a moving dBFS meter.

## Test matrix

| Test | Result | Notes |
|---|---|---|
| YouTube foreground, screen on, 10 min | | |
| Pause / resume | | |
| Seek | | |
| Change video | | |
| Playlist next/previous | | |
| YouTube ad transition | | |
| Picture-in-picture | | |
| Host app backgrounded | | |
| Screen locked | | |
| Screen off | | |
| Media volume = 0 | | |
| Wired headphones | | |
| Bluetooth output | | |
| Incoming phone/VoIP interruption | | |
| Battery saver | | |
| 30-minute soak | | |
| 2-hour soak | | |
| YouTube Music | | |

## Expected Android 15 QPR1+ behavior

Modern Android stops MediaProjection when the device is locked. Record the exact state transition and whether the service receives MediaProjection.onStop().

## Send back

Use **SHARE LATEST LOG** and return the CSV. The log contains device model, Android build, YouTube version, capture health, PCM level, active-playback signal, sample count, and read faults.
