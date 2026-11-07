# Changelog

## Stage 4 UI integration

- Added Equalizer, Visualizer, AURALIS Wrapped, Metadata Editor and Backup/Restore screens.
- Wired all screens into Compose navigation and a Settings "Tools" section.
- Now Playing: lyrics panel with active-line highlight, auto-scroll and SAF import; tool row for
  equalizer, visualizer, Wrapped, backup and tag editing; double-tap seek gesture.
- Declared `RECORD_AUDIO` for the platform Visualizer.

## Stage 4 partial (backend foundation)

- Added LRC parsing and local sidecar/SAF lyrics discovery with Room caching.
- Added real platform Equalizer/BassBoost/Virtualizer attached to the ExoPlayer audio session.
- Added artwork dominant-colour extraction and MP3/FLAC tag read/write with backup and verification.
- Added play-event logging (Room v2 migration) and monthly/yearly listening aggregation.
- Added hardware Visualizer capture, volume crossfade and
  output-device reconnect handling.
- Added versioned SAF JSON backup/restore for settings, playlists, favorites, history, lyrics and events.
- Migrated playback service to MediaLibraryService (Android Auto UI/manifest still pending).
- UI screens for these backends, gestures, Wrapped sharing and Auto remain unfinished; see
  `IMPLEMENTATION_STATUS.md` and `CONTINUATION.md`.

## Stage 3 playback enhancements

- Connected Play Next from track menus on Home, Search and Library to the service-owned Media3 timeline; existing queued items are moved instead of duplicated.
- Added long-press drag reordering to the queue while retaining accessible move-up/move-down controls and actual timeline synchronization.
- Added folder/source name search alongside track, artist, album and genre search.
- Added sleep timer presets and a custom 1–720 minute duration. The playback service pauses ExoPlayer when the timer expires, even if the UI closes.
- Persisted the Media3 queue in Room and checkpointed current track, playback position, play state, shuffle and repeat in DataStore for best-effort process restart restoration.
- Retained MediaSession headset/Bluetooth transport commands, ExoPlayer audio focus handling and pause-on-audio-device-disconnect behavior.
- Verification: `testDebugUnitTest` and `assembleDebug` completed successfully; see `STAGE3_CHECKLIST.md` for inspected scope and remaining limitations.

## AURALIS brand identity

- Renamed visible app branding, launcher label, About text and project title to AURALIS while preserving the application ID and Kotlin package for compatibility.
- Replaced the launcher mark with an original silver waveform symbol with a violet center on a dark field; configured adaptive, legacy vector and Android 13 monochrome launcher assets.
- Set the Android 12+ splash screen background and animated icon to the AURALIS brand assets.

## UI layout and glass polish

- Moved the mini-player and floating navigation into the main vertical layout, keeping the player above the dock and respecting status/navigation insets.
- Refined the mini-player into a theme-aware glass card with rounded corners, a fine border, album-art atmosphere and API 31+ high-profile blur; lower tiers use an opaque, low-cost surface.
- Added an explicit gear icon and Settings label to the top bar, with readable theme-aware colors and a larger touch target.
- Wrapped Settings in a themed surface and set its heading color explicitly so dark mode does not inherit an incorrect default.
- Reduced scroll-list bottom padding because the navigation dock now occupies layout space instead of covering content.

## AURALIS stability and queue refinement

- Canceled scans now propagate coroutine cancellation, and MediaStore/SAF loops check cancellation while scanning. The existing scan mutex and bounded Room writes remain in place.
- Queue contents and Up Next now come from the service-owned Media3 timeline. Added queue append, duplicate prevention, remove, reorder, immediate play, and clear actions; album and playlist groups can be queued.
- Playback timeline events keep the Now Playing queue synchronized. Position updates no longer rebuild the full queue every 500 ms.
- Playback queue metadata references cached artwork thumbnail files instead of loading artwork byte arrays for every MediaItem.
- Fixed the bounds-only BitmapFactory pass so valid embedded MP3/FLAC covers are not discarded before thumbnail decoding.
- Added an Automatic / High / Medium / Low visual performance preference. Automatic estimates device tier from low-RAM status, RAM and CPU count, and considers hardware acceleration. Manual selection is saved in DataStore. Audio playback settings are not changed.
- Now Playing, queue and track rows use theme colors. Track rows have fixed artwork size, text spacing and separate ellipsized title and artist lines.
- Added profile-aware screen and mini-player transitions plus artwork crossfade for non-Low profiles.
- The media service now configures audio focus and pauses when headphones or Bluetooth audio disconnect.
- Added unit coverage for automatic profile selection, manual overrides and hardware-acceleration fallback.

## Glassmorphic theme (unreleased, uncompiled)
- Added a second complete visual style, selectable in Settings > Theme style. Normal remains the default and is unchanged.
- New: `ui/glass/*` (kit, Home, Library, Search, track rows, Now Playing + queue, Settings, top bar, nav bar, mini-player), `ui/theme/GlassTheme.kt`, `ui/components/GlassBackdrop.kt`, `ui/components/ThemeStyleSelector.kt`, `domain/model/ThemeStyle.kt`.
- Persisted with DataStore key `theme_style`. See GLASS_THEME.md.
