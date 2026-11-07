# Test Report

Date: 2026-11-01 (Stage 4 UI integration)

## Automated

- `gradlew.bat assembleDebug --offline`: **BUILD SUCCESSFUL**.
- `gradlew.bat testDebugUnitTest --offline`: **BUILD SUCCESSFUL**, no failures.

## Delivered APK

`output/AURALIS-latest.apk` (22,873,595 bytes), copied from `app/build/outputs/apk/debug/app-debug.apk`.

## Emulator smoke test

- `adb install -r`: Success on `emulator-5554`.
- `MainActivity` launched and reported as `topResumedActivity`.
- Captured logcat contained no `FATAL EXCEPTION` / `AndroidRuntime` crash entries.

## Not tested on device

- Audible playback, EQ/BassBoost/Virtualizer, visualizer capture (needs mic permission + audio).
- MP3/FLAC tag write and MediaStore write-consent round-trip.
- Backup export/restore round-trip.
- Wrapped data accuracy with real listening history.
- Android Auto (not implemented).
