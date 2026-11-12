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
- [ ] Part I — Next/previous always plays
- [ ] Part J — Better artwork + lyrics matching
- [ ] Part L — Artist photos
- [ ] Part M — Racing stream resolvers

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
