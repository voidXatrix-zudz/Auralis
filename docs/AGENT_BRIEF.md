# AURALIS — Agent Brief

Persistent working memory for this repository. **Code is truth.** If a doc disagrees with code, record it here and follow the code. Re-read this file after any summary/compaction and update `DONE` / `NEXT` after every step.

Last full read: 2026-11-28. No code changed. Nothing built, installed or run.

---

## PROJECT FACTS

- **What it is:** AURALIS, an Android local-first music player with an optional YouTube Music connection, synced/word-timed lyrics, offline downloads, and a glassmorphic UI.
- **Root:** `C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL` (git repo, single `:app` module).
- **Identity:** package `com.auralis.app`; `minSdk 26`, `targetSdk 35`, `compileSdk 36`; `versionCode 4`, `versionName 1.5.1`.
- **Toolchain:** AGP 8.13.2, Kotlin 2.4.21, KSP 2.3.12, Compose BOM 2025.06.01, Media3 1.7.1, Room 2.7.2, DataStore 1.1.7, WorkManager 2.10.2, Coil 3.2.0, OkHttp 4.12.0, NewPipeExtractor v0.27.1, Clerk 1.4.1, QuickJS 1.0.15; JDK 21.
- **Size:** ~240 Kotlin files / ~2.1 MB, dominated by `ui/AppViewModel.kt` (4,533 lines), `data/youtube/InnerTubeClient.kt` (~110 KB), `ui/screens/NowPlayingBlend.kt` (~121 KB), `ui/AuralisRoot.kt`, `ui/glass/GlassNowPlaying.kt`, `ui/components/AppDock.kt`, `playback/PlaybackService.kt`.

### Architecture map

- **Process/entry:** `AuralisApp` (Application; skips the container in the `:crash` process; best-effort `runStep` startup; opens the DB early for the recovery screen) → `AppContainer` (manual DI singleton) → `MainActivity` (Compose host; connects/detaches a `MediaController` to `PlaybackService` in `onStart`/`onStop`) → `AuralisRoot` → `AppViewModel`.
- **Navigation/UI shell:** one Activity; `NavState(tab + back stack)` with `Tab { HOME, EXPLORE, SEARCH, LIBRARY, EQUALIZER, SETTINGS }` and a sealed `Destination`. `AuralisRoot` owns theme, glass config, performance profile, nav, dock, Now Playing overlay, update dialog and global snackbar. `AppDock` is one component shared by both themes (`DockStyle.SOLID` / `GLASS`).
- **State:** `ui/AppViewModel.kt` is the central state owner: library, playback mirror, queue/checkpoint, YouTube search/home/auth/likes/history, lyrics, downloads, metadata, Spotify import, settings, update. Screens are mostly stateless composables fed from it.
- **Playback:** `PlaybackService : MediaLibraryService` owns ExoPlayer, `MediaSession`, notification, Android Auto browse tree, sleep timer, audio effects, crossfade, and like/shuffle custom commands. `PlaybackController` is the UI bridge. Queue/next/shuffle policy is **service-owned** in `TransportPlayer`.
- **Persistence:** Room `AuralisDatabase` v11 (migrations 1→11, schemas exported to `app/schemas`); DataStore `SettingsRepository` (all keys/defaults/gates/migrations), `PlaybackSnapshotRepository`, `SecretStore`; Room is the source for library/queue/playlists/history/lyrics/provider cache.
- **Data domains:** `sources/` (MediaStore + SAF `MusicScanner`, `AutoScanWorker`, `LibraryAutoScanner`), `lyrics/` (local + 18 catalog providers), `youtube/` (`InnerTubeClient`, `StreamResolver`, `YouTubeRepository`, `LiveSyncManager`, auth/session/healing, playback reporting, history/search sync), `download/` (WorkManager + `ChunkedDownloader` + `AudioTagWriter`), `metadata/` (MusicBrainz/Deezer/iTunes/Last.fm + tag read/write), `backup/` (versioned SAF JSON), `spotify/` (CSV import), `auth/` (Clerk), `update/` (GitHub releases), `logging/` (`AppLog`, crash handler, diagnostics).
- **Tests:** `app/src/test` has ~26 JVM test files (lyrics providers/parsers, performance resolver, settings codec, nav, format, EQ, DB rules, queue/shuffle helpers); `app/src/androidTest` has one Room migration test. `androidTest` assets include the exported schemas.
- **Vendored code:** the Kyant0 liquid-glass backdrop lives under `app/src/main/java/com/auralis/app/ui/glass/backdrop/**` (the task text says `.../app/glass/backdrop/`; the real path is under `ui/glass/`). Treat it as third-party: do not edit unless explicitly told.

### Glass system (10 lines)

1. `GlassConfig` is the single source of truth; it is provided once at the root via `LocalGlassConfig` (plus `LocalGlassDrag` for live slider previews). Nothing below the root reads DataStore or hardcodes blur/alpha/outline values.
2. One modifier entry point: `Modifier.auralisGlass(shape, role)` with `GlassRole.Dock | Panel`.
3. Tokens live in `ui/theme/GlassTheme.kt` (`GlassTokens`: black `Canvas`, `Cream`, `Indigo`, alpha steps, `BLUR_DP_*`).
4. **Dock family:** dock tab pill, dock circles, mini-player pill, Search circle, Appearance preview.
5. **Panels:** cards, rows, chips, dialogs, Now Playing surfaces.
6. `GlassConfigRepository` combines persisted transparency/clarity/outlines with the resolved performance tier; sliders preview through `GlassDragState` and persist only on drag end.
7. Blur gate: `GlassBlurPolicy.allowed = sdk >= 31 && !reduceBlurAndEffects`. API < 31 (or a null backdrop) falls back to a clipped translucent tint plus an optional 1 dp cream hairline.
8. There are **two parallel mapping objects**: `GlassMappings` (`auralisGlass`; `dockTintAlpha = 0.02 + 0.98t^1.4`, `panelTintAlpha = 0.92 - 0.80c`) and `GlassMapping` (`LiquidGlass`/`GlassKit`/windows; `dockFill` cream→`DOCK_SOLID`, `surfaceFillAlpha 0.40→0.04`). This duplication is fragile.
9. Performance tier never turns glass blur off; it removes secondary effects (refraction/sheen/glow/background blur). Automatic may step down one tier with a 30 s hysteresis.
10. `AuralisRoot` attaches the recorded layer only when `themeStyle == GLASSMORPHIC && glassConfig.isGlassCapable`; dock siblings stay outside the recorded layer to avoid self-sampling; Now Playing records into the same layer with `LocalAppBackdrop = null`.
11. Settings hub and sub-pages use `SettingsLiquidGlass` with a Settings-local backdrop that records only the player background or ambient cream glows; page content samples that sibling layer.

### Lyrics pipeline (10 lines)

1. Load order: **USER → EMBEDDED (cached, then real tag) → sidecar LRC → discovered `.lrc` → online providers**.
2. USER lyrics always win and are never auto-replaced; EMBEDDED/LRC are returned early only when they carry timing.
3. Remote/streamed tracks skip local-file discovery; only the DB cache and online providers apply.
4. Online: all enabled providers are queried in parallel, ordered by remembered preferred provider → word-capable → `alwaysLast` → user order → provider priority → health/latency.
5. Aggregator ranks **WORD > LINE > PLAIN**; word results can show immediately at `WORD_FAST_SCORE`, line-synced after `WORD_GRACE_MS`, plain after `PLAIN_FALLBACK_MS`, all bounded by `BACKGROUND_BUDGET_MS`.
6. Results only ever upgrade (`shouldUpgradeLyrics`); cross-provider text agreement can reject a word-timed outlier.
7. Provider answers are cached per track in `ProviderCache` (positive and negative) plus an LRU parsed cache; `retry` clears both for that song.
8. Catalog has 18 ids; defaults enable 11; order/enabled gating is `lyrics_provider_defaults_v2` (until any `update()` writes, defaults apply).
9. Parsers: `LrcParser` (multi-timestamp, `[offset:]`, inline `<mm:ss.xx>` word tags), `QrcParser`, `TtmlParser`, `LrclibParser`; `LyricsMatcher` / `LyricsPrecision` / `LyricsQuality` score title, duration and text agreement.
10. Per-song offset is clamped to ±5 s; a manual provider pick is remembered per track; "Try another" skips the current provider and ignores local copies.

### Five riskiest edit areas

1. **`playback/PlaybackService.kt` + `PlaybackController.kt` + `TransportPlayer`** — service-owned queue/next/repeat/shuffle/autoplay policy, anchored shuffle permutation, Media3 timeline roles, notification/Bluetooth/Auto all share this one path.
2. **Glass system** — `GlassConfig`/`LocalGlassConfig`, the two mapping objects, `GlassRole.Dock` vs `Panel`, blur/fallback gates, and the vendored backdrop. Small changes can desynchronise dock and panels or crash the renderer.
3. **`ui/AppViewModel.kt`** — 4,533-line central state hub with many `StateFlow`s, request generations, queue epochs and checkpoint writes; easy to introduce races or stale-state bugs.
4. **Lyrics stack** — `LyricsRepository` staged parallel aggregator, provider catalog ids/order/enabled set, negative cache, parser/matcher scoring. Provider changes can silently downgrade quality or poison caches.
5. **YouTube stream stack** — `InnerTubeClient` (~110 KB), `StreamResolver` three-way race (NewPipe / InnerTube / YouTube.js), signed-URL expiry/fallback, `ResolvingDataSource` blocking bridge, download re-resolve. Fragile against upstream changes.

Honourable mention: **Room schema/migrations v11** — every entity/DAO change needs a migration + schema export, and the migration test only covers the chain, not new logic.

---

## DO

- Read this brief first, then the exact files you will touch; grep for every call site before editing.
- Keep changes minimal and in the surrounding style; preserve existing comments/structure.
- Preserve the single-source patterns: `GlassConfig`/`LocalGlassConfig`, service-owned playback policy, `SettingsRepository` gates, provider catalog ids, DataStore keys.
- Prefer the existing helper/extension over a new abstraction; no new dependencies.
- Add JVM unit tests only where the project already tests; keep Android-only code out of JVM tests.
- Update this brief (`DONE`, `NEXT`, `OPEN QUESTIONS`) after each step.
- If a task contradicts the code, stop that part, finish the non-conflicting parts, and report the blocker.

## DON'T

- Don't edit `*.bak_*`, `app/build/**`, generated code, or `app/src/main/java/com/auralis/app/ui/glass/backdrop/**` (vendored Kyant0 engine) unless explicitly told.
- Don't copy code from other apps; visual/behavioural references only.
- Don't run adb, an emulator, Android SDK tools, or build the APK unless explicitly asked.
- Don't touch other docs.
- Don't create a second source of truth for glass, playback, settings or lyrics provider order.
- Don't casually change Media3 queue roles/autoplay tagging, anchored shuffle, or the `auralis-yt://` resolving contract.
- Don't rename public composables/classes or reformat files as a drive-by.

---

## DONE

- Read root/build wiring: `README.md`, `CHANGELOG.md`, `settings.gradle.kts`, root `build.gradle.kts`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`.
- Read app entry/DI: `AuralisApp.kt`, `AppContainer.kt`, `MainActivity.kt`, `CrashReportActivity.kt`.
- Read domain models: `AppSettings.kt`, `Models.kt`, `ThemeStyle.kt`, `StreamQuality.kt`, `PerformanceMode.kt`; core `Format.kt`, `Notifications.kt`.
- Read persistence: `SettingsRepository.kt` (keys/defaults/gates/migrations), `AuralisDatabase.kt` (v11, 1→11), `Entities.kt`, `Daos.kt`, `Mappers.kt`, `PlaybackCheckpoint.kt`, `PlaybackSnapshotRepository.kt`.
- Read glass: `GlassConfig.kt`, `GlassConfigMappings.kt`, `AuralisGlass.kt`, `GlassBlurPolicy.kt`, `DockGlassStyle.kt`, `GlassControls.kt`, `GlassTheme.kt` (`GlassTokens`), `LiquidGlass.kt`, `GlassConfigRepository.kt`, `GlassKit.kt` (structure), backdrop inventory.
- Read lyrics: `LyricsProvider.kt`, `LyricsPrecision.kt`, `LyricsMatcher.kt`, `LyricsQuality.kt`, `LyricsRepository.kt`, `OnlineLyricsProviders.kt` + `providers/*`, `LrcParser.kt`, `LrcParser`-adjacent parsers, `EmbeddedLyrics.kt`, `LrclibClient.kt`/`LrclibParser.kt`, `QrcParser.kt`, `TtmlParser.kt`.
- Read playback: `PlaybackService.kt`, `PlaybackController.kt`, `PlaybackContext.kt`, `PlaybackHistoryTracker.kt`, `PlaybackTiming.kt`, `ShuffleOrders.kt`; skimmed `AudioEffectsEngine.kt`, `CrossfadeController.kt`.
- Read UI shell: `AppViewModel.kt` fully, `AuralisRoot.kt` fully, `AppDock.kt` fully, `NavState.kt`; skimmed `NowPlayingBlend.kt`/`NowPlayingScreen.kt`/`GlassNowPlaying.kt`/`GlassScreens.kt` structure.
- Read support: `PerformanceResolver.kt`, `PerformanceProfile.kt`, `DownloadManager.kt`, `BackupRepository.kt` (export/restore contract), `AppLog.kt`, `MusicScanner.kt` structure, `StreamResolver.kt` fully, `YouTubeRepository`/`InnerTubeClient`/`LiveSyncManager` structure, tests/assets/res inventory.
- Created `docs/AGENT_BRIEF.md`. No production code changed; nothing built or run.
- Applied `auralis-player-glass-controls.patch` (`git apply`, clean; tree is all CRLF). Changed `ui/glass/AuralisGlass.kt` (added `lens: Boolean = true` to `auralisGlass`/`buildAuralisGlass` + remember keys, gated the lens effect), created `ui/glass/PlayerGlass.kt` (`Modifier.playerGlass`), edited `ui/screens/NowPlayingBlend.kt` (own recorded background layer + `LocalAppBackdrop` provider in the portrait branch, `playerGlass` on star/more circle, queue pill, selected bottom icon and slider track, slider defaults `Color.Unspecified`/white 80%, bottom-icon tints 0.80/0.96, `PlayerMoreMenu` provides `LocalAppBackdrop = null`). Proofread sections 2–6; no build/adb/emulator run.
- Applied `auralis-settings-liquid-glass.patch` after a clean `git apply --check`. Added `ui/glass/SettingsGlass.kt`; wrapped the Settings hub and sub-pages with the Settings-local background layer, switched shared Settings back buttons to dock-circle glass, and made the hub search pill opt into liquid glass. Reviewed imports and confirmed the recorded subtree contains only the player background image or ambient cream glows; no build or device checks run.

- Built the debug APK on request: `gradlew.bat assembleDebug` with JDK 21 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot`; system Java is 25, which this Gradle/AGP combo should not use). `BUILD SUCCESSFUL in 49s`, 0 errors. Artifact: `app/build/outputs/apk/debug/app-debug.apk` (37,272,349 bytes; SHA-256 `2C815D92…F7349D`), copied to `C:\Users\Dipanjan\Desktop\AURALIS-debug-1.5.1.apk`. Non-fatal D8 warnings about Kotlin metadata (R8 8.13 vs Kotlin 2.4.21). Not installed or run.

## NEXT

- Await the owner's on-device result for the Now Playing glass controls. If asked to adjust: re-read this brief + `PlayerGlass.kt`/`AuralisGlass.kt`/the portrait branch of `NowPlayingBlend.kt`, then make the smallest clean change. Do not touch landscape/`GlassNowPlaying`, the dock, `GlassTokens`, `AuralisRoot`, the vendored backdrop, or the player sheets/dialogs.
- Await on-device review of Settings liquid glass on Android 13+, API 31/32 and Normal/older-device fallback. Do not build or use adb unless explicitly asked.

## OPEN QUESTIONS

- Should `docs/AGENT_BRIEF.md` be committed, or kept local/untracked?
- Is the target always this `AURALIS-FULL` tree, or a separate checkout/worktree?
- For UI work, which theme is the visual reference: Glassmorphic (current code default) or Normal?
- What API floor should visual verification target (API 31+ blur vs API 26 fallback)?
- Are YouTube/network/provider changes allowed to touch the live request paths, or should they stay read-only?

---

## DOC vs CODE DISAGREEMENTS (code wins)

1. **No `docs/` directory existed** before this file; there is no `AGENT_NOTES.md`, `GLASS_NOTES.md`, `GLASS_THEME.md`, `IMPLEMENTATION_STATUS.md` or `TASK_*.md`. Only root `README.md` + `CHANGELOG.md` exist.
2. **Theme default:** `ThemeStyle.kt` KDoc says "NORMAL is the default"; `CHANGELOG.md` says "Normal remains the default". Code: `AppSettings.themeStyle = ThemeStyle.GLASSMORPHIC`, and `SettingsRepository` uses that default until `theme_style_user_selected` is true.
3. **Blur values:** `DockGlassStyle.kt` KDoc says "10 dp High/Medium, 16 dp Low"; `GlassTokens` has `BLUR_DP_HIGH = 8f`, `BLUR_DP_MEDIUM = 8f`, `BLUR_DP_LOW = 16f`.
4. **Android Auto:** `CHANGELOG.md` says "Android Auto UI/manifest still pending". Code has a full `MediaLibraryService` browse tree and an exported `MediaSessionService`, but no `androidx.car.app` automotive manifest entry — so the browse backend exists, the car app shell does not.
5. **Stage 4 status:** `CHANGELOG.md` says UI screens for Equalizer/Wrapped/Metadata/Backup/Auto "remain unfinished"; those screens now exist and are wired in `AuralisRoot` (`EqualizerScreen`, `WrappedScreen`, `MetadataEditorScreen`, `BackupScreen`, etc.). Stale status text.
6. **Glass backdrop naming:** `CHANGELOG.md` points at `ui/components/GlassBackdrop.kt`; there is also `ui/glass/GlassBackdrop.kt` and the vendored `ui/glass/backdrop/**`. Three similarly named things.
7. **Vendored path:** the task text names `app/src/main/java/com/auralis/app/glass/backdrop/`; the actual tree is `app/src/main/java/com/auralis/app/ui/glass/backdrop/`.
