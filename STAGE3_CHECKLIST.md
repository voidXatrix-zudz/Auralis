# AURALIS Stage 3 Checklist

## Inspected existing implementation

- [x] Existing Kotlin/Compose architecture, package/application ID, navigation, Gradle setup, Room database and Media3 service inspected.
- [x] Playback already used the MediaSessionService-owned ExoPlayer timeline; notification, lock-screen and headset transport controls were already connected.
- [x] Audio focus was already requested through ExoPlayer audio attributes, and noisy audio-device disconnects already paused playback.
- [x] Queue append/remove/play/repeat/shuffle/album and playlist actions already existed. Queue reorder initially had only move-up/move-down controls.
- [x] Search initially matched track metadata only; no playback checkpoint or sleep timer existed.

## Stage 3 implementation

- [x] Add to Queue and Play Next are wired to the actual Media3 timeline; duplicate queued tracks are moved rather than duplicated.
- [x] Queue reordering supports long-press drag and retains move-up/move-down controls. Playback and Up Next continue to follow Media3 state.
- [x] Queue entries remain persisted through Room.
- [x] Added sleep timer presets (15, 30, 45, 60, 90 minutes) and custom durations from 1 to 720 minutes. The service owns the pause deadline.
- [x] Shuffle and repeat remain connected to Media3's player state.
- [x] Search matches title, filename, artist, album artist, album, genre, relative folder path and configured source name.
- [x] MediaSession remains the route for Android notification, lock-screen, Bluetooth and headset transport commands.
- [x] Added a local checkpoint for current track, position, play state, shuffle and repeat; restores a valid saved queue when no live service queue exists.

## Verification and known limits

- [x] `testDebugUnitTest` passed: 26 tests, 0 failures, 0 errors.
- [x] `assembleDebug` passed and generated the debug APK.
- [x] Installed on emulator `emulator-5554`, launched `com.auralis.app.debug`, and verified `MainActivity` reached the resumed state with no `FATAL EXCEPTION` in the captured logcat window.
- [ ] Actual audio, headset and Bluetooth hardware behavior was not exercised in this build environment.
- [ ] Resume automatically when a headset reconnects is not enabled; disconnect pause and MediaSession transport controls are supported.
- [ ] Checkpoint restore is best-effort and requires the saved tracks to remain present in the Room library.
- [ ] No dedicated automated integration tests currently cover the Media3 queue, drag gesture or sleep timer lifecycle.

No Stage 4 features were started.
