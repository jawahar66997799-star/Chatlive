# Jawahar Live Sync

Goal: host plays audio in the normal official YouTube Android app; guests eventually open one browser link and hear the host's live audio without opening YouTube.

This branch starts with the mandatory Phase-0 capture proof. It deliberately does not pretend the rest of the streaming stack is complete until the source PCM is proven on the target phone.

## Build

GitHub Actions builds the debug APK on every push to `youtube-live-sync`.

Local build if Android SDK is installed:

```bash
gradle :app:assembleDebug
```

APK path:

`app/build/outputs/apk/debug/app-debug.apk`
