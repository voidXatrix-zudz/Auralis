# AURALIS MEGA BULK — progress

Worked in order. Each part is compiled (`:app:compileDebugKotlin`) and committed locally.

- [x] Part A — Download crash hardening
- [x] Part B — Profile picture + account card (both themes)
- [x] Part C — Glass YouTube Home header + branch
- [x] Part D — Clerk hand-off to YouTube Music
- [x] Part E — Sync to YouTube account (history/likes/playlists)
- [x] Part F — Download tag embedding
- [x] Part N — Android 12 NoSuchMethodError crash (desugaring + resolver fallback)
- [x] Part G — Lyrics + metadata providers
- [x] Part H — Settings redesign (both themes)
- [x] Part I — Next/previous always plays
- [x] Part J — Better artwork + lyrics matching
- [x] Part L — Artist photos
- [x] Part M — Racing stream resolvers

## Notes
- Part A: single allowed `adb logcat -d AndroidRuntime:E *:S` returned nothing (no device attached); cause found by code reading.
  - `DownloadManager.enqueue`/`cancel` wrapped in `runCatching` + logged, so a bad WorkManager state can't crash the UI thread.
  - Download notification channel is now created at app start (`AuralisApp.onCreate`) and idempotently before every notify (`DownloadManager.ensureChannel`).
  - `POST_NOTIFICATIONS` is requested non-blockingly on Android 13+ (`MainActivity`); downloads still work when denied.
  - `DownloadWorker` reuses the shared `playbackHttp` client instead of building its own.
  - `doWork()` already caught all throwables; verified it returns retry/failure only.
- Part N: core library desugaring enabled (`desugar_jdk_libs_nio` 2.1.5) so `URLDecoder.decode(String, Charset)` exists on API 26+.
  - `StreamResolver` catches `Throwable` (rethrows `CancellationException`), marks NewPipe unavailable on `LinkageError`, and falls back to the new `InnerTubePlayer` (ANDROID_VR → IOS → ANDROID_MUSIC).
  - Logs "resolved via <name> in <ms>"; `StreamResolverStatus` adds API level + active resolver to Logs diagnostics.
- Part G: provider architecture for lyrics + metadata.
  - `LyricsProvider` chain (LRCLIB, SimpMusic, NetEase, KuGou, BetterLyrics, YouTube Music, lyrics.ovh) with 8 s timeouts, matcher (title ≥0.8, artist overlap, ±3 s), winner + 7-day negative cache in the new `provider_cache` table (DB v9).
  - `MetadataRepository` (MusicBrainz + Cover Art Archive with 1 req/s, Deezer, iTunes, Last.fm via encrypted key).
  - UI: Edit tags "Fetch metadata" candidates (local writes tags; YouTube songs save a Room override applied to Now Playing/notification), lyrics "Try another source" + provider chip, Library "Auto-fix tags" (WorkManager batch, 1 lookup/s, fills empty fields).
- Part H: a new theme-aware settings module (`ui/settings/`) replaces both old settings bodies.
  - `SettingsHubScreen` (account card kept at top, quick "Music folders" removed → moved to Storage) + `SettingsSubScreen` routed off 8 new `Destination.Settings*` entries.
  - Shared composables `SettingsGroupCard/Row/SwitchRow/ValueRow/SectionLabel/HelperText` with Normal (`Surface`) and Glass (`GlassPanel`) branches.
  - New settings: Wi-Fi/Mobile streaming quality (Low/Normal/High/Best), Data saver, Stream source (Auto/NewPipe/InnerTube), Reduce blur and effects, Download lyrics with songs, Cache size.
  - Quality flows through `StreamResolver` cache key, `YouTubeRepository.resolveStreamBlocking/prefetchStream`, `NetworkQuality.effective`, `PlaybackService.resolveRemoteUri`; prefetch gated by `NetworkQuality.allowPrefetch`.
  - `reduceBlurAndEffects` forces `blurEnabled=false`/`ambientMotionEnabled=false` in the root `CompositionLocalProvider`.
  - Storage: cache-size radio, "Clear cache" w/ live size, "Remove all downloads" (red + confirm); ViewModel `clearCache`/`removeAllDownloads`/`moveLyricsProvider`.
- Part I: every user skip starts playback.
  - `PlaybackService.TransportPlayer` (the session player all controllers route through) sets an `autoPlayOnReady` latch and calls `play()` on `seekToNext*`/`seekToPrevious*`, so buttons, mini-player, notification, lock screen, Bluetooth/headset and Android Auto all resume from paused.
  - The service's player listener calls `play()` on `STATE_READY` if the latch is still set (lazy stream resolve), clears the latch on `onIsPlayingChanged`/user pause (`TransportPlayer.pause`/`setPlayWhenReady(false)`).
  - `onPlayerError` skips to the next song and keeps playing (max 3 in a row, logged) instead of stopping.
  - `PlaybackController.next()/previous()` also call `play()`; prefetch of the next URL was already in place, and the resolver cache is keyed by video id + quality, so a skip reuses it.
- Part J: real square artwork + smarter lyrics matching.
  - Artwork: `normalizeArtworkUrl(raw, sizePx)` requests the largest square from YouTube Music (`w1200-h1200`, `w544-h544` fallback); new `isVideoThumbnailUrl`. New `ArtworkResolver` looks up real covers via the metadata providers (Cover Art Archive/Deezer/iTunes, title ≥0.8 + artist ≥0.5) and caches the winner / 7-day negative in `provider_cache` kind `artwork`. `AppViewModel.ensureArtwork` upgrades a video thumbnail on the current + next song and pushes it to the notification via `PlaybackController.updateArtwork`. `ArtworkImage` now walks an ordered candidate list (primary → w544 → thumbnail) on load error; Coil `ContentScale.Crop` keeps everything square.
  - Local art: `MusicScanner` now falls back to `folder.jpg` → `cover.jpg` → `folder.png` (SAF `DocumentFile` siblings and a cached MediaStore query) via `ArtworkStore.saveFolderArt`, after embedded then MediaStore album art; nothing is written into the audio file.
  - Lyrics: `LyricsMatcher` gains `isSlowedOrSped`; `Lyrics` carries `provider`, `confidence` and `outOfSync`; `LyricsRepository.parseWithProvider` scores the LRC header, and slowed/sped-up songs keep synced lyrics only when `[length:]` still matches (else `outOfSync`).
  - Per-song lyrics offset (−5 s…+5 s) saved in `provider_cache` kind `lyrics_offset`; `AppViewModel.adjustTrackLyricsOffset`/`resetTrackLyricsOffset`; applied on top of the global offset. Lyrics "…" menu gains earlier/later/reset; the source chip shows provider + confidence %, and an "may be out of sync" chip appears when flagged.
- Part L: circular artist photos everywhere an artist is shown.
  - New `artist_images` Room table (DB v9→v10 + `MIGRATION_9_10`) caches positives and 7-day negatives keyed by `yt:<browseId>` or `name:<normalised artist>`.
  - `ArtistImageRepository` resolves in order: thumbnail already in hand → the artist-page header via `InnerTubeClient.artistImage(browseId)` (browse request) → Deezer public artist search (`picture_xl`) for artists without a YouTube id, only on a ≥0.75 name match. A `Semaphore(3)` gate keeps at most 3 lookups in flight; respects the Part H `fetchArtistPhotos`/`useDeezerForArtistPhotos` switches.
  - New `ArtistAvatar` composable + `LocalArtistImages` CompositionLocal (root-provided `ArtistImageResolver { viewModel.resolveArtistImage(...) }`); an initial-on-`primaryContainer` circle shows until the photo loads or when none exists.
  - Wired into Home artist cards, both Now Playing artist lines (small inline avatar), Library > Artists (Normal + Glass), and the artist collection sheet header (only when the opened card is a YouTube artist). "Clear lyrics & metadata cache" now also clears cached artist photos.
- Part M: racing stream resolvers.
  - `StreamResolver` now races `[NewPipe, InnerTube]`: the currently-preferred source (lower recent avg latency + higher success rate, rolling and persisted in DataStore via `ResolverStat`; NewPipe by default) starts first, the other begins if the first fails or after a 2 s hedge, first success wins and the loser is cancelled; an 8 s overall budget then logs and lets playback skip. Every win logs `resolved via <name> in <ms>`; the winner is cached (key still includes quality) so next-song prefetch uses the same race.
  - The Part H "Stream source" setting pins Auto (race) / NewPipe only / InnerTube only via `StreamSourceMode`; `InnerTubePlayer` is unchanged.
  - `PlaybackService` fallback: on an ExoPlayer 403/410/source error it re-resolves the same song with the other resolver (`resolveBlocking(..., force, bypassCache=true)`), seeks back to the same position and resumes, honouring a user pause — up to 2 retries per song, then the existing skip-to-next logic. The retry budget resets on every media-item transition.
