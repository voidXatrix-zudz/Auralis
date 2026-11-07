# AURALIS

Offline-first Android music player built with Kotlin, Jetpack Compose, Media3, Room and DataStore.

## Build

Requirements: JDK 17 or newer and Android SDK 35 (Android Studio installs both). The debug build was verified with Android Studio's bundled JDK 21.

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

On macOS/Linux:

```shell
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk` (the debug application ID includes the `.debug` suffix).

## Stage 4 status

Compose screens for lyrics, equalizer, visualizer, Wrapped, metadata editing and
backup/restore are integrated and reachable from Settings → Tools and the Now Playing tool row.
Car Mode/Android Auto, Wrapped share cards, dynamic artwork themes and full drag gestures remain
deferred. See `IMPLEMENTATION_STATUS.md`, `CONTINUATION.md` and `TEST_REPORT.md`. Latest APK:
`output/AURALIS-latest.apk`.

## Stage 2 implementation

- MediaStore scan of on-device music after the Android audio permission is granted.
- SAF folder selection with persisted read access and recursive scans.
- Metadata extraction with Android MediaMetadataRetriever, including duration, artist, album, codec, sample rate, bitrate, channels and embedded artwork cached in app storage.
- Room persistence for sources and tracks, plus favorites, playlists, play history and current queue.
- Song, album, artist and genre browsing; instant title/artist/album/genre/folder search; favorites and playlist creation/addition.
- Media3 ExoPlayer playback queue with shuffle/repeat and a compact player in the app.
- MediaSessionService owned player for continued background playback and Android media notification / lock screen controls.
- Full Now Playing screen with seek, favorite, actual Media3 Up Next state and queue management (add, Play Next, remove, drag or button reorder, clear and play queued items). Albums and playlists can be added as a group.
- Sleep timer with 15–90 minute presets and a custom duration, scheduled by the playback service so it remains active when the UI closes.
- Queue, current track, playback position, play state, shuffle and repeat are checkpointed locally and restored when the service starts without a live queue.
- MediaSession transport controls support headset and Bluetooth play/pause/previous/next commands; audio focus is delegated to ExoPlayer and playback pauses when audio becomes noisy.
- Automatic visual performance profile using low-RAM status, available memory and CPU count; users can override it with High, Medium or Low in Settings. Low reduces crossfades and disables artwork crossfade. Audio playback quality is unaffected.
- Theme-aware Now Playing, queue, dialogs, settings and track rows; track titles and artists use separate, ellipsized lines.
- The mini-player is laid out directly above the floating navigation dock with system gesture insets applied to the dock container. The mini-player and navigation use profile-aware translucent surfaces, borders and shadows; high-performance Android 12+ devices can show a subtle blurred album-art backdrop.
- Queue artwork is referenced by thumbnail URI instead of copying image bytes into every queued MediaItem. Queue position updates are driven by Media3 timeline callbacks.

Playback of a particular codec depends on the Android device's installed platform decoders. MediaStore scanning requires `READ_MEDIA_AUDIO` on Android 13+ and `READ_EXTERNAL_STORAGE` on Android 12 and earlier; SAF folder access uses the user-granted URI permission.

Playback checkpoint restoration is best-effort and requires the saved tracks to remain in the local library. Android system/headset controls are provided through MediaSession; automatic resume on headset reconnection is not enabled.

The build uses the existing project's Gradle wrapper. Run `testDebugUnitTest` and `assembleDebug` from the project root. The verified debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Architecture

`app/src/main/java/com/auralis/app` keeps the existing manual dependency container, Compose navigation and design system. Room entities cover sources, tracks, lyrics, playlists, favorites, play history, search history and queue. Settings remain DataStore-backed.

## Licences

Manrope and Sora are SIL Open Font License 1.1 (see `licenses/`).
