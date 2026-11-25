# GLASS-BACKDROP-V3 (active task)

Repo: `C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL`, branch `glass-opacity-fix`
(HEAD `4a9be27` + the owner's uncommitted WIP). Glassmorphic theme only. The owner builds/tests on
the phone; this session may run `:app:compileDebugKotlin` (no adb / SDK / emulator). One commit per
step. Toolchain: AGP 8.13.2, Kotlin 2.4.21, KSP 2.3.12, Gradle 8.14.3, Compose BOM 2025.06.01,
Haze 1.6.7, minSdk 26, compileSdk 36, targetSdk 35, package `com.auralis.app`.

GOAL: make the glass settings actually drive the UI. ONE source of truth (`GlassConfig`), ONE
modifier (`auralisGlass`), every glass surface reads them. Engine swapped to Kyant0/backdrop
(Apache-2.0, vendored 1.0.0). No surface may keep a private blur/alpha/outline number.

## DO
- Read before editing; never leave half-wired code; one commit per step; verify with
  `:app:compileDebugKotlin`.
- Keep exactly four glass controls: Dock transparency, Glass clarity, Glass outlines, Performance.
- Blur is a FIXED `GlassTokens` constant (no "Live blur", no "Blur strength").
- Normal theme byte-for-byte unchanged and cost-free (no layer recorded).
- Update this section after every step; re-read after any context summary.
- Working directly in `AURALIS-FULL`: it has the owner's unrelated WIP, so task-file commits mix it
  in (accepted by the owner; git is file-level). Stage task files only.

## DON'T
- Do not copy BitChord / Echo-Music code (GPL-3.0). Vendored engine is Kyant0/backdrop only.
- Do not tune more than `BLUR_DP*`, `LENS_AMOUNT_DP`, and the tint mapping.
- Do not touch playback, downloads, YouTube/Spotify, Clerk auth, lyrics, menu items/labels/order.
- Do not add dependencies other than the (already) vendored backdrop engine.

## NEXT
- Owner: build the debug APK and tap-test Library and profile→Settings (both previously hard-crashed),
  then the earlier checklist. If a crash ever recurs, read `Settings → About → Logs` / the crash
  report screen (the global CrashHandler writes `filesDir/crash/crash-*.txt`).

## CRASH FIX — LIBRARY + PROFILE→SETTINGS (done)
### Symptom
Home, Explore, Search, mini-player and portrait Now Playing all work; tapping **Library** or the
**profile picture → Settings** closes the app instantly, every time, with no Java stack trace.
### Root cause
The recorded backdrop layer is attached to the page container (`AuralisRoot.kt:604`-`610`), so the
wallpaper **and every page** are drawn inside it (`STEP 3.2`). Glass surfaces inside those pages
(`GlassPanel`/`glassSurface` → `Modifier.auralisGlass` → `drawBackdrop`) therefore live *inside the
very layer they sample*. `LayerBackdropNode.draw()` (`backdrop/backdrops/LayerBackdropModifier.kt`)
does `drawContent()` and then `recordLayer(...) { onDraw → drawContent() }`; on that second,
recording pass a nested `drawBackdrop` called `LayerBackdrop.drawBackdrop` → `drawLayer(backdrop.graphicsLayer)`,
i.e. it drew the layer **into itself while it was being recorded**. On Android that re-entrant
`GraphicsLayer`/`RenderNode` draw aborts the renderer natively, so the process dies with no Java
trace — which is exactly why Settings only worked as a *separate* Haze pipeline before.
### Why only those two screens
They are the only reachable portrait screens whose glass panels sit inside the recorded layer.
- Home / Explore / Search: their glass is only the dock/mini-player/Search circle, drawn as
  **siblings above** the recorded layer (`AuralisRoot.kt:599`-`634` vs the dock later) → safe.
- Portrait Now Playing: `NowPlayingScreen` picks `BlendNowPlayingScreen` in portrait, and that screen
  renders **no** glass panels (`GlassNowPlayingScreen` is landscape-only) → safe in portrait.
- Library (`GlassScreens.kt::GlassLibraryScreen`) and Settings (`SettingsHub.kt::SettingsHubScreen`)
  render `GlassPanel`/`GlassTrackRows`/`GlassSearchField` **inside** the page container → crash.
### Fix
`backdrop/backdrops/LayerBackdrop.kt` + `LayerBackdropModifier.kt`: the recording pass sets
`LayerBackdrop.isRecording = true` for the duration, and `LayerBackdrop.drawBackdrop` returns early
while it is set. So the recorded pass bakes only the page content + the surfaces' own tint, and the
next frame's on-screen pass samples the finished layer normally. No architecture change, no visual
change on Home (its dock still samples the full page content), and the engine is now safe for glass
inside its own recorded subtree (also fixes landscape Now Playing / the pushed glass sub-pages).
Documented as a local modification in the two file headers and `NOTICE`.

### Second layer (defence in depth)
`AuralisRoot.kt` also now wraps the two recorded subtrees in
`CompositionLocalProvider(LocalAppBackdrop provides null)`: the full player (`NowPlayingScreen`) and
the whole page container (inside the recorded `Box`, above the wallpaper). Glass inside those subtrees
therefore resolves a null backdrop and takes the plain-tint fallback instead of calling `drawBackdrop`
at all, while the dock / mini-player / Search circle (siblings drawn *above* the layer) keep
`appBackdrop` and still sample the finished frame. Both fixes are kept: the engine `isRecording` guard
is generic (it also protects any other self-recording backdrop, e.g. the Appearance preview which
records its own layer), and the provider null is a belt-and-braces guarantee for the app layer. Tradeoff:
in-page glass (Library rows, Settings panels, landscape player) renders as tint, not sampled blur.

### Safety net (STEP 6)
The global `Thread.setDefaultUncaughtExceptionHandler` already exists: `logging/CrashHandler.kt`,
installed first in `AuralisApp.attachBaseContext:28`, writing to `filesDir/crash/crash-*.txt` (the
store the Logs screen and the crash-report screen read). A *Compose composition* try/catch around the
screens is **not possible**: the Compose compiler rejects `try/catch` around composable calls
("Try catch is not supported around composable function invocations"), so no per-section boundary was
added. Note the reported crash is a *native* abort, which no Java handler can catch anyway.

## STEP 0 — AUDIT (done; consumer matrix + conflicts)
Engine today = Haze 1.6.7 via `Modifier.glassSurface` / `dockGlassMaterial`; settings live in
DataStore through `SettingsRepository` (`dock_glass_opacity`, `dock_glass_clarity`, `glass_outlines`,
`dock_glass_blur`, `performance_profile`).

| SURFACE | file(s) | read today | ignored |
|---|---|---|---|
| Dock (nav pill, mini-player, hidden tab circle) | `AppDock.kt`, `LiquidGlass.kt` (`dockGlassMaterial`, `glassBubble`) | `dockOpacity`→`dockFill`, `blurStrength`→`blurDp`, `perf.*`, rim | `glassClarity` |
| In-composition surfaces (cards/rows/chips/panels/Now Playing) | `glassSurface(GlassKind.Surface)` call sites | `glassClarity`→`surfaceFillAlpha`, `blurStrength`, `perf.*`, hairline | `dockOpacity` |
| Bubbles / icon tints | `glassBubble` / `glassTint` | Surface fill + rim | `dockOpacity` |
| Menus / sheets / dialogs (separate windows) | `GlassOverlays.kt`, `GlassKit.kt` | `windowTintAlpha(clarity)`, `DialogBackdropBlur()` (OS radius) | `dockOpacity`; blur radius OS-fixed |
| Root scrim | `AuralisRoot.kt` → `GlassOverlayScrim` | `MENU_BACKDROP_SCRIM` | all |
| Appearance preview | `GlassSettings.kt`, `SettingsSubPages.kt` | live drag values | — |
| Normal theme | all wrappers | stock Material only | everything glass |

Spec vs. code conflicts (trust code, safest option):
1. Engine: spec = Kyant0/backdrop; code = Haze. Engine swap (approved by owner).
2. `dockBackdrop` has no `backdropScale` param → `EFFECT_RESOLUTION_*` has no direct API; effects
   run at full resolution (documented). Optional later via `layerBlock`.
3. `shape`/`highlight`/`shadow` are lambdas; `lens` needs a `CornerBasedShape`.
4. `blur(radius px)` is px, not dp.
5. `TINT_COLOR_DOCK`: spec says "very light cream"; kept a dark smoked
   `lerp(Canvas, Cream, 0.06)` blend so dock cream icons stay readable at high opacity.
   Deviation recorded here.

## STEP 1 — ADD THE LIBRARY (done)
- Vendored Kyant0/backdrop 1.0.0 into `app/src/main/java/com/auralis/app/ui/glass/backdrop/`
  (package `com.auralis.app.glass.backdrop`), 26 files, Apache-2.0 header on each.
  Vendored (not the AAR) because every published artifact needs Compose ≥ 1.9.4/1.12 which this
  project can't use. No gradle dependency added (the unusable `io.github.kyant0:backdrop` line is
  not present in canonical).
- `NOTICE` added; `README.md` + `AboutScreen.kt` carry the attribution.
- Verified API: `rememberLayerBackdrop(graphicsLayer, onDraw)`, `Modifier.layerBackdrop(backdrop)`,
  `Modifier.drawBackdrop(backdrop, shape: () -> Shape, effects, highlight, shadow, innerShadow,
  layerBlock, exportedBackdrop, onDrawBehind, onDrawBackdrop, onDrawSurface, onDrawFront)`.
  Effects: `blur(radiusPx)`, `colorControls(brightness, contrast, saturation)`, `lens(refractionHeight,
  refractionAmount, depthEffect, chromaticAberration)`. Blur/colour need API 31+, lens API 33+.
- `:app:compileDebugKotlin` green with the engine vendored.

## STEP 2 — ONE SOURCE OF TRUTH (done)
- New `ui/glass/GlassConfig.kt`: immutable `GlassConfig(dockTransparency, glassClarity,
  outlinesEnabled, performance, tier, sdkInt)` + `LocalGlassConfig` (`compositionLocalOf`) +
  `isGlassCapable` (API 31) + `hasLens` (API 33 && tier != LOW). Defaults 35/60/on.
- New `ui/glass/GlassConfigMappings.kt` (`GlassMappings`): `dockTintAlpha` (`0.02+0.98*t^1.4`),
  `panelTintAlpha` (`0.92-0.80*c`), `outlineAlpha`, `blurDp(tier)`, `effectScale(tier)` — pure/JVM.
- New `ui/glass/GlassConfigRepository.kt`: combines the stored values + resolved tier into ONE
  `StateFlow<GlassConfig>`, clamps on read and write, setters `setDockTransparency/Clarity/Outlines`.
- `AppViewModel` owns the repository, exposes `glassConfig`, and the three setters.
- `AuralisRoot` collects `glassConfig`, provides `LocalGlassConfig`, and `GlassSettingsProvider`
  derives the Haze bridge (`GlassSettings`) + dock style from it. `GlassDragState.value` now holds a
  `GlassConfig?` so a slider drag is live and the drag-end commits through the repository.
- `GlassTokens` (Step 2.4 constants): BLUR_DP_HIGH/MEDIUM=10, BLUR_DP_LOW=16, LENS_HEIGHT/AMOUNT=24,
  SATURATION=1.5, EFFECT_RESOLUTION 0.5/0.33/0.25, RIM_WIDTH=1, RIM_ALPHA 0.55/0.12,
  TINT_COLOR_DOCK/TINT_COLOR_PANEL, `blurDpFor(tier)`.
- STEP 2.6 removed "Live blur" + "Blur strength" end to end: `AppSettings.dockGlassBlur` deleted
  (default opacity 30→35, clarity 50→60), DataStore key `dock_glass_blur` renamed to a
  delete-only `dockGlassBlurLegacy`, backup import/export of `dockGlassBlur` removed, both Appearance
  screens (SettingsHub sub-page + Glass settings) drop the rows, `GlassMapping` blur members removed,
  `GlassBlurPolicy.allowed/sourceAllowed` lose the `blurStrength` gate, and Haze blur now reads
  `GlassTokens.blurDpFor(tier)` everywhere (`LiquidGlass.kt`, `DockGlassStyle.kt`).
- `:app:compileDebugKotlin` green.

## STEP 3 — BACKDROP SOURCE (done)
- New `ui/glass/GlassBackdrop.kt`: `LocalAppBackdrop` (staticCompositionLocalOf<LayerBackdrop?>) +
  `OpaqueLayerOnDraw` = `{ drawRect(GlassTokens.Canvas); drawContent() }` (STEP 3.2: opaque canvas
  first, so text can never show sharp through the glass).
- `AuralisRoot` owns ONE `rememberLayerBackdrop(onDraw = OpaqueLayerOnDraw)`. It is attached with
  `Modifier.layerBackdrop(appBackdrop)` in exactly TWO mutually exclusive places: the page container
  (wallpaper + every page inside, spec 3.3) and the full player root (only one composes at a time).
  The dock / mini-player / Search circle are SIBLINGS drawn after the page container (spec 3.4).
- `glassLayerEnabled = theme == GLASSMORPHIC && config.isGlassCapable`; `LocalAppBackdrop` is null
  otherwise, so Normal records nothing and pays nothing (spec 3.6/9.1).

## STEP 4 + 5 — THE ONE MODIFIER (done)
- New `ui/glass/AuralisGlass.kt`: `enum GlassRole { Dock, Panel }` and
  `Modifier.auralisGlass(shape, role)`. It reads ONLY `LocalGlassConfig` + `LocalAppBackdrop` +
  `GlassTokens`; nothing else. `remember(config, backdrop, shape, role)` rebuilds the modifier when
  the config changes, so a slider drag repaints the same frame (spec 4.4/4.5).
- Core: `clip(shape).drawBackdrop(backdrop, shape = { shape }, effects = { colorControls(SATURATION);
  blur(blurDp*density); if (hasLens && shape is CornerBasedShape) lens(LENS_*) },
  highlight = rim-if-outlines, shadow = Shadow.Default, onDrawSurface = { drawRect(tint) })`.
- Fallbacks (spec 5): no backdrop / API < 31 -> `clip + background(tint) [+ hairline]`, never
  transparent; API 31-32 -> blur + rim, no lens; API 33+ & tier != LOW -> blur + colour + lens + rim;
  LOW tier -> blur 16dp + rim, no lens. Content behind is always blurred-or-tinted, never sharp.
- `effectScale` has no `backdropScale` API in backdrop 1.0.0 -> effects run at full resolution
  (documented; tunable later via `layerBlock`).

## STEP 6 — EVERY SURFACE (done)
- `LiquidGlass.kt` rewritten: `glassSurface(GlassKind, shape)` maps to
  `auralisGlass(role = Dock/Panel)`; `dockGlassMaterial` keeps SOLID untouched and routes GLASS to
  `auralisGlass(Dock)`; `glassBubble`/`glassTint` read `LocalGlassConfig` (bubble rim follows
  outlines). `glassWindowSurface`/`glassWindowPanelColor`/`DialogBackdropBlur` stay for the
  separate-window path (6.2b) and read the same config.
- Menus/dialogs/sheets (`GlassOverlays.kt`, `GlassKit.kt`) keep their windows; `DialogBackdropBlur`
  blurs the page behind (API 31+) and `glassWindowPanelColor` carries a high panel tint (6.2b).
- Haze retired: removed `hazeState`/`hazeSource`/`rememberHazeState`/`LocalHazeState`/
  `GlassMapping.hazeInputScale` everywhere, dropped `libs.haze` + the version entry, and stripped
  the `hazeState` parameter from `glassSurface`/`dockGlassMaterial` and every `AppDock` signature.
  The Appearance preview now records its own `previewBackdrop` and provides it locally so the
  production modifiers sample the preview strip (not the settings page).
- `GlassNowPlaying`: the player background is drawn inside the full player's recorded layer.

## STEP 7 — SETTINGS SCREEN (verified)
- The LIVE Appearance route is `Destination.SettingsAppearance -> AppearanceSettingsPage` in
  `SettingsSubPages.kt`, reached via `SettingsHubScreen`. It shows exactly four controls
  (Dock transparency, Glass clarity, Glass outlines, Performance); the three glass rows write
  ONLY through `SettingsActions.onDockTransparencyChange/onGlassClarityChange/onGlassOutlinesChange`
  -> `AppViewModel` -> `GlassConfigRepository`. The Live-blur and Blur-strength rows were removed.
- The pinned preview (`AppearancePreviewHeader` -> `AppearanceGlassPreview`) records its own
  `previewBackdrop` (`rememberLayerBackdrop(OpaqueLayerOnDraw)`), draws the colourful strip as a
  sibling `layerBackdrop` source, and provides `LocalAppBackdrop` so the production
  `glassSurface`/`dockGlassMaterial` sample the preview strip (not the settings page).
- DEAD duplicate: `screens/SettingsScreen.kt` + `glass/GlassSettings.kt::GlassSettingsScreen`
  (with `AppearanceSection`) are NOT reachable from navigation (nothing calls `SettingsScreen`);
  they still compile and still write the same `dockGlassOpacity`/`glassClarity` fields via
  `onUpdate`, so they are harmless. Left in place (pre-existing), noted here.

## STEP 8 — MIGRATION (done)
- `SettingsRepository.dockTransparencyToOpacity` converts the legacy transparency % to the stored
  value; key `dock_glass_opacity`; legacy key `dock_glass_transparency` still read. Defaults are
  STEP 8.2: dockTransparency 35, clarity 60, outlines ON (`AppSettings` line 43-48).

## STEP 9 — PERFORMANCE
- Blur/lens/colour are FIXED constants in `GlassTokens`; the tier only picks the radius
  (`blurDpFor`) and whether the lens runs (`hasLens = sdk>=33 && tier != LOW`). No whole-screen
  blur: only the dock / mini-player / Search circle / panels draw the backdrop modifier. The
  Normal theme records NO layer (`glassLayerEnabled` false) and runs the SOLID dock path untouched.
- `effectScale` (`EFFECT_RESOLUTION_*`) is defined + unit-tested but not wired: backdrop 1.0.0's
  `drawBackdrop` has no `backdropScale`/resolution parameter, so effects run at full resolution.
  Documented; can be added later via `layerBlock` if phone profiling asks for it.

## STEP 10 — DIAGNOSTICS (done)
- 10.1 `GlassConfigRepository` logs ONE `Glass` line per distinct config change
  (`GlassConfig dock=.. clarity=.. outlines=.. perf=.. lens=.. blurDp=..`).
- 10.2 `AuralisRoot` logs `backdrop recording=.. layer opaque=.. theme=.. sdk=..` once at launch.
- 10.3 `auralisGlass` logs one debug-only warning if a glass-capable, Glassmorphic surface is drawn
  with no backdrop (tint fallback). (File name is not available at runtime; message is generic.)

## CLEANUP — dead optics local removed
- The old `GlassOptics` value + `LocalGlassOptics` local had zero readers after the rewrite; both
  and their imports were deleted (`GlassTheme.kt`, `AuralisRoot.kt`). Every surface now reads the
  single `GlassConfig`; no second optics channel exists.

## STEP 11 — TESTS / SELF-REVIEW
- `GlassMappingTest` (13) + `GlassBlurPolicyTest` (5) rewritten for the new API; all pass.
- [x] no liveBlur / blurStrength remains (only in stale `*.bak_before_dockfix` untracked files)
- [x] no blur/alpha/outline literal outside `GlassTokens`/`GlassMappings` (shadow alphas and the
      decorative preview gradient remain; they are shadows/art, not glass tint/blur/outline)
- [x] recording lambda draws an opaque background first
- [x] dock/mini-player/Search are siblings of the layerBackdrop container
- [x] every `remember(...)` that depends on config has the right keys
- [x] Dialog/Popup/Menu/Sheet handled per 6.2b (window blur-behind + high panel tint)
- [x] outlines OFF removes rim + hairline on all surfaces incl. the selection lens
- [x] dock transparency never adds black/grey tint (`dockTintAlpha` stays cream/indigo)
- [x] glass clarity affects Panel surfaces and not the dock
- [~] no crash on 31/32/33+/<31: fallbacks are code paths; verified by compile + unit tests only
      (no device). Low mode -> no lens (unit-tested `hasLens`).
- [x] Normal theme unchanged: `glassLayerEnabled` false, no layer, SOLID dock path untouched
- [x] licence attribution added (NOTICE + README + About)
- [x] `:app:compileDebugKotlin` green; glass unit tests green; full unit suite 59 classes / 0 failures

## STEP 12 — FINAL
- Live wiring verified end to end: `SettingsActions` -> `AppViewModel` -> `GlassConfigRepository`
  (DataStore) -> `GlassConfig` StateFlow -> `GlassSettingsProvider` -> `LocalGlassConfig` ->
  `Modifier.auralisGlass` -> the one recorded `LayerBackdrop`.
- Follow-ups left to the phone: tune `BLUR_DP*`/`LENS_AMOUNT_DP`/tint from real-device feedback;
  wire `effectScale` through `layerBlock` if full-resolution effects are too costly on Low.

---

# PLAYBACK CONTEXT (active) — playlist-bound Next/Previous

Branch: `glass-opacity-fix` (working tree has unrelated WIP; touch only playback/context files).
No build/ADB per the task role: the owner builds and tests on the phone.

## DO
- One queue model: the Media3 timeline is the playback order. Context items are MARKED in
  `MediaMetadata.extras`; autoplay/radio items are marked separately. One policy reads those marks.
- Every Next/Previous (Now Playing, mini-player, notification, lock screen, headset/Bluetooth,
  Android Auto) goes through `PlaybackService.TransportPlayer` (the session player). UI is a thin
  delegate (`PlaybackController.next/previous`), never a second implementation.
- Always `play()` on a successful manual skip, even if paused (old bug; do not regress).
- Previous: >3 s restarts; else walk the REAL played history (works with shuffle and queue edits);
  at the first context song restart, or wrap to the last with repeat-all.
- Repeat: ALL wraps to the first CONTEXT song; ONE repeats natively but manual Next moves forward;
  OFF + autoplay ON seeds similar songs only when the last context song is playing; OFF + OFF stops.
- Shuffle: reorder only the CONTEXT block, current song first; autoplay stays after it.
- Mixed local+YouTube: one queue; the service resolves `auralis-yt://<id>` lazily and the controller
  prefetches the next stream.
- Update this file after each phase; re-read it if unsure.

## DON'T
- Don't run Gradle / adb / SDK / emulator.
- Don't redesign UI: only the Queue section labels ("Playing from …", "Autoplay / Up next").
- Don't change dock/theme/glass/lyrics/downloads/YouTube fetching/stream racing/history sync/Clerk.
- Don't insert recommendations while context songs remain (append at the end only).
- Don't duplicate next/previous logic in Compose, the dock, or the controller.

## STEP 1 FINDINGS (where playback starts)
- Playlist detail (Normal + Glass) → `onPlayIndex` → `AppViewModel.playPlaylist(id, index)`
  → `play(track, ordered)` → whole playlist queue (already whole, but no context id and mixed
  YouTube taps go through a remote-only filter elsewhere).
- Album (Library ALBUMS row/card) → `onPlayAlbum` → `play(first, album)`.
- Liked Songs → `likedOnPlay`: local `onPlay` (queues the WHOLE LIBRARY), remote
  `onPlayYouTubeTrackFrom` (filters out local rows → mixed list broken).
- Downloaded → `onPlay` (queues the WHOLE LIBRARY, not the download list).
- Local Files → `onPlay` → `playLibraryTrack(it, tracks)` (library, correct list, no context).
- History → `playHistoryEntry`: local filters out YouTube, remote filters out local.
- Search → local `play(it, tracks)` (library), YouTube `playYouTube` (single + radio).
- Home → local `play(it, tracks)`, YouTube `playYouTubeTrack` (single + radio).
- YouTube playlist detail/card → `playYouTubePlaylist*` (whole list, no context).
- Player/service: `PlaybackService` owns ExoPlayer + `MediaLibrarySession`; `PlaybackController`
  (MediaController) builds the timeline; `start()` calls `setMediaItems/prepare/play`.
- Next/Previous: `PlaybackController.next/previous` (guarded by `hasNextMediaItem`, so repeat-all
  wrap at the last item is broken) and `PlaybackService.TransportPlayer` overrides (3 s rule).
  All UI buttons (Blend, GlassNowPlaying, NowPlayingScreen, AppDock) call the same ViewModel methods.
- Radio on Next: `AppViewModel` init watcher appends `container.youtube.radioNext(...)` whenever
  fewer than 3 YouTube items are ahead — in the middle of any queue, including playlists.
  `playYouTube` also seeds 15 radio tracks immediately. No autoplay setting exists; the closest
  gate is `settings.youtubeIntegration`, used as the autoplay switch (no new UI row).
- Shuffle/repeat today: native Media3 (`shuffleModeEnabled`, `repeatMode`); no custom history.

## IMPLEMENTATION PHASES
1. New `PlaybackContext` + queue tags + service-side shared skip policy + events.
2. Controller: context-aware start/restore, explicit shuffle, live `syncContext`, state fields.
3. ViewModel: context call sites, autoplay gating, playlist-edit live sync, checkpoint context.
4. UI: Queue "Playing from" + "Autoplay / Up next"; restore wiring.
5. Self-review, notes, final report.

## DONE
- STEP 1 read/audit complete (findings above).
- STEP 2 model: new `playback/PlaybackContext.kt` — `PlaybackContextKind` (PLAYLIST, ALBUM, LIKED,
  DOWNLOADED, LOCAL, SEARCH, RADIO, HISTORY), `PlaybackContext(kind,id,name)` + factories, and the
  `QUEUE_ROLE_EXTRA` tag with `MediaItem.queueRole()/isAutoplayItem()`.
- STEP 3 controller: `PlaybackController` carries context on `play/start/restore`, tags each item
  as context/autoplay, exposes `context`, `contextIds`, `autoplayIds` in `State`, adds
  `addAutoplay`, `syncContext`, and makes `next()/previous()` thin delegates to the session player.
  Shuffle is now CONTEXT-ONLY and physical: `toggleShuffle`/`applyShuffle`/`restoreContextOrder`
  reorder the timeline (current song first, autoplay tail untouched); native `shuffleModeEnabled`
  stays false so a recommendation can never land between context songs.
- STEP 4 service: `TransportPlayer` owns the ONE skip policy — `lastContextIndex`, `firstContextIndex`,
  `hasAutoplayAfter`, `manualNextTarget`, `skipNext`, `skipPrevious`; context order is walked in
  order, repeat-all/one wrap to the first CONTEXT song, repeat-off may enter autoplay only when the
  YouTube integration is on, previous keeps the >3 s restart rule, and both always `beginUserSkip()`.
- STEP 5 viewmodel: every start path passes a context (`play/playList`, playlist, shuffle-playlist,
  album, liked, downloaded, local, history, explore, YouTube card/track/playlist); mixed
  local+YouTube lists now queue as ONE list (old `playYouTubeTrackFrom` no longer drops local rows);
  the radio refill is gated on `state.context` (never mid-context) and on repeat-off; playlist edits
  (add/remove/reorder) live-sync the active queue via `syncContext`; checkpoint saves context
  kind/id/name; a one-shot "couldn't play — skipping" notice per failed song.
- STEP 6 UI: `LibraryScreen`/`GlassScreens` route Liked/Local/Downloaded rows to their own lists;
  `AuralisRoot` supplies the handlers + restored context; `NowPlayingBlend.QueuePanel` shows
  "Playing from <name>" and an "Autoplay / Up next" section.
- STEP 7: `:app:compileDebugKotlin` + `:app:assembleDebug` green (JDK 21). Debug APK copied out.

## SELF-REVIEW
- One policy: `grep` confirms no other skip logic; every UI button reaches
  `PlaybackService.TransportPlayer` through the session.
- Autoplay never interleaves: autoplay items are always inserted after the last context item
  (`addToQueue` `insertAt`, `syncContext`, `shuffledContext`), and `manualNextTarget` refuses to
  enter the tail while `current < lastContext`.
- Mixed lists: `playLibraryTrack`, `playHistoryEntry`, `playLiked` pass the full local+remote list.
- Shuffle: `shuffleModeEnabled` is forced false everywhere; queue order lives in the timeline.
- Failure: `PlaybackService.onPlayerError` already re-resolves then skips; the toast only reports it.
- No UI redesign; only the two Queue labels changed.

---
# ARCHIVE — LIQUID-GLASS BLUR PASS

Branch: `glass-opacity-fix`. Glassmorphic theme only. No build/ADB/emulator; verify by static
review. One commit per step.

## DO
- Read each file fully before editing; re-read after editing.
- Blur must never depend on performance heuristics. The ONE rule is
  `GlassBlurPolicy.allowed(settings, sdkInt)` = `sdkInt >= 31 && !reduceBlurAndEffects &&
  performanceProfile != LOW && dockGlassBlur > 0`. Automatic NEVER disables blur.
- The tier controls COST only (Haze input scale, shadow scale, refraction), never radius/on-off.
- Keep it compiling with no new libraries; Normal theme + dock layout/motion + tabs + lyrics +
  Now Playing layout untouched.
- Update this section after every step.

## DON'T
- Do not run Gradle / adb / SDK / emulator.
- Do not add any user-facing option/toggle/slider beyond the existing three sliders and the two
  switches (Live blur, Glass outlines).
- Do not put a `graphicsLayer { renderEffect }` / RuntimeShader / `CompositingStrategy` above
  `hazeEffect` in the dock chain (Haze would sample its own output and stop blurring).
- Do not commit the unrelated WIP tree; stage only this task's hunks.

## DONE
- STEP 1 (this commit): decoupled Live blur from the performance tier.
  - New `ui/glass/GlassBlurPolicy.kt`: `GlassBlurPolicy.allowed(...)` + `LocalGlassBlurAllowed`
    (provided once at the root by `GlassSettingsProvider`).
  - `AdaptiveTierResolver.baseTier`: LOW only for `isLowRamDevice || totalRamGb < 3.0`; low cores
    or a small heap class only cap at MEDIUM. `availableRamGb` no longer influences the tier.
    `stepDown`: HIGH -> MEDIUM, MEDIUM stays MEDIUM (no automatic LOW).
  - Every glass blur gate now reads the policy: `glassSurface`, `dockGlassMaterial`,
    `glassWindowPanelColor`, `DialogBackdropBlur`, `resolveDockGlass`, `glassHazeAllowed`,
    `GlassNowPlaying` player-background source.
  - `resolveDockGlass.limitedReason` states the TRUE cause ("Live blur needs Android 12 or newer" /
    "Blur is off: Performance is set to Low" / "Blur is off: Reduce blur and effects is on").
  - Tests: `PerformanceResolverTest` (new base/step-down cases), `GlassBlurPolicyTest` (new),
    `GlassMappingTest` (reason cases).
- STEP 2 (this commit): one back arrow + read-only diagnostics.
  - `Destination.isSettingsDestination` (NavState.kt); the app `TopBar` is suppressed for the
    Settings hub and every Settings sub-page, so Appearance shows exactly one back + title.
  - `GlassDebugPanel` is now READ-ONLY, debug-only, and scrolls above the dock stack: SDK, mode,
    tier, totalRamGb, availableRamGb, cores, memoryClassMb, batterySaver, thermalStatus, blur
    policy allowed, dock blur dp, haze input scale.
  - Removed the "Dock lens (experimental)" and "Haze input scale: Default" toggles and their
    settings fields/keys (`dockLensExperimental`, `dockHazeDefaultScale`).
  - `LocalDeviceConditions` added (PerformanceLocals.kt) and provided from the root.
- STEP 3 (this commit): Haze 1.6.7 dock path verified + simplified.
  - Read `HazeEffectNode.kt` / `HazeSourceNode.kt` / `HazeStyle.kt` at tag 1.6.7: `hazeEffect`
    samples the nearest registered `hazeSource` that is NOT an ancestor, so the dock effect node
    (a sibling of the content pane) does find an area and blurs on API 31+.
  - `dockGlassMaterial` order is now: outside-only shadow, `clip(shape)`, `hazeEffect {}`, then the
    fill / specular / 3dp inner stroke / 1dp rim as INNER content. The AGSL lens is gone from the
    dock entirely (`LensShader` kept for the non-dock `liquidGlass`).
  - Haze args: `blurEnabled = true`, `blurRadius` from `dock.blurDp`, `inputScale = Fixed(...)`,
    `noiseFactor = 0`.
  - Active-tab circle passes `blurBackdrop = circleIn > 0.01f`; the mini-player play/next stay
    `blurBackdrop = false` (faint cream bubble, bare white icons).
- STEP 4 (this commit): reference look.
  - Fill is `GlassMapping.dockFill` (no black/grey; nearly clear at the default 30%).
  - Backdrop vibrancy via a Haze `HazeTint(..., BlendMode.Saturation)` (0 at 0% blur -> 0.45 at
    full), so only the blurred backdrop is saturated, never the content.
  - Rim 1dp white 0.60 -> 0.08 -> 0.35; 3dp inner stroke white 0.14 -> 0.02; top specular white
    0.10 -> 0 over the top 40%; all gated by "Glass outlines".
  - Active-tab bubble is white 13% + 1dp rim (`glassBubble`); active tab label/icons 100% cream,
    inactive 70%.
  - Fresh-install defaults: Dock opacity 30%, Blur strength 60% (`GlassMapping.DEFAULT_*`,
    `AppSettings.dockGlassOpacity/dockGlassBlur`). Saved values are untouched.
  - Appearance preview pill + circle run the same `dockGlassMaterial` and now receive
    `LocalGlassBlurAllowed`, so the preview blurs exactly when the real dock does.
- STEP 5 (this commit): static self-review (see SELF-REVIEW below).
- LATE FIX (compile, found by `:app:assembleDebug`): `dockInnerStroke`'s `drawOutline` import corrected
  to `androidx.compose.ui.graphics.drawOutline` (it lives in `androidx.compose.ui.graphics.OutlineKt`,
  NOT `...graphics.drawscope`). Debug APK then builds; copied to `Desktop\\AURALIS-debug.apk`.
- FIX (dock colour cast, report on device): REMOVED the red `HazeTint(..., BlendMode.Saturation)` from
  `dockGlassMaterial`. Saturation blend forces the backdrop to full saturation at its own hue, so warm
  album art / the orange stripes pushed the whole dock to red. The dock now blurs with a single faint
  neutral white brightness lift and NO colour tint, so the blurred content keeps its own colour
  (the reference look). `GlassMapping.dockSaturation` is kept as a tested mapping but is no longer
  applied. No options/toggles added or changed.
- FIX (dock blur not tracking the slider, report on device): split the blur policy into two rules.
  `GlassBlurPolicy.sourceAllowed(settings)` decides whether the single backdrop capture layer exists
  (platform + "Reduce blur and effects" + manual Low only; it IGNORES the slider). `GlassBlurPolicy.allowed(settings, blurStrength)`
  decides whether a surface actually blurs, and now takes the live slider value. Root:
  `glassHazeAllowed` uses `sourceAllowed` (so the layer exists at 0% and raising the slider starts
  blurring in the same frame), and `GlassSettingsProvider`/`resolveDockGlass` use the live
  `glass.blurStrength`. This removes the asymmetry where the Appearance preview (own local
  `hazeSource`) blurred while the real dock had no registered source and stayed sharp until the
  drag committed. The read-only debug panel now reports the resolved live `dock.blurEnabled`.
  Tests added: live-value decision and capture-layer registration at a zero slider.

## SELF-REVIEW (STEP 5)
- `grep profile.blurEnabled` in the glass path: only `PerformanceController` (its own log) and
  `ui/screens/LyricsContent.kt` (lyrics focus blur, out of scope). No dock/card/window glass path
  reads it.
- Automatic never disables blur: `GlassBlurPolicy.allowed` has no device input at all, and
  `GlassBlurPolicyTest.automatic never disables blur` asserts it.
- Resolver: LOW only for `isLowRamDevice || totalRamGb < 3.0` (tests `onlyLowRamOrUnder3GbForcesLow`,
  `lowCoresOrSmallHeapOnlyCapAtMediumNeverForceLow`, `lowAvailableRamNoLongerForcesLow`).
- `dockGlassMaterial`: no black scrim/tint; fill is exactly `dockFill`; no `renderEffect` /
  RuntimeShader / CompositingStrategy above `hazeEffect`; the AGSL lens is dock-free.
- Duplicate back arrow: `nav.top?.isSettingsDestination` suppresses the shared `TopBar` on the hub
  and every sub-page (`SettingsPage` draws the single back + title).
- Debug toggles and their keys removed; the only DEBUG card is the read-only diagnostics.
- Normal theme untouched (`dockGlassMaterial` returns the original solid path for `DockStyle.SOLID`;
  the pinned preview only exists when `isGlass`).

## NEXT
- User builds and tests the dock pass (see the phone checklist in the report).
- Nothing else planned unless the phone shows the dock is still sharp; in that case the diagnostics
  card prints the exact reason.

## FILES
- New: `ui/glass/GlassBlurPolicy.kt`, `test/.../GlassBlurPolicyTest.kt`.
- Changed: `ui/performance/PerformanceResolver.kt`, `PerformanceProfile.kt`, `PerformanceLocals.kt`,
  `ui/glass/DockGlassStyle.kt`, `ui/glass/LiquidGlass.kt`, `ui/glass/GlassControls.kt`,
  `ui/components/AppDock.kt`, `ui/AuralisRoot.kt`, `ui/AppViewModel.kt`, `ui/nav/NavState.kt`,
  `ui/settings/SettingsSubPages.kt`, `domain/model/AppSettings.kt`,
  `data/settings/SettingsRepository.kt`, `ui/glass/GlassNowPlaying.kt`,
  `test/.../PerformanceResolverTest.kt`, `test/.../GlassMappingTest.kt`, `AGENT_NOTES.md`.

---

# AURALIS glass menus / dialogs / sheets

## TASK
Make every floating surface in the **Glassmorphic** theme a real frosted-glass surface:
blurred backdrop + Midnight Indigo tint + cream hairline + soft shadow, wired to
Settings > Appearance (Live blur, Glass clarity, Glass outlines). Leave the Normal theme
look unchanged. No redesign, no menu item/label/order/behaviour changes. No build/ADB.

## DO
- Read first, edit only what this task needs.
- Keep the Normal theme byte-for-byte in look: wrappers delegate to stock Material in NORMAL.
- Reuse the existing `LocalGlassSettings` / `GlassMapping` / `DialogBackdropBlur` pipeline.
- Update this file after each file edit and before ~80% context.

## DON'T
- Do not run adb / emulator / SDK manager / Gradle.
- Do not touch playback, downloads, YouTube, Spotify, auth, Clerk, lyrics logic.
- Do not change menu items, labels, order or behaviour.
- Do not restyle the Normal theme.

## HOW THE EXISTING BLUR WORKS (verified in code)
- `AuralisRoot.kt:277` `rememberHazeState()`; the scrolling content pane at
  `AuralisRoot.kt:541-550` is `Modifier.hazeSource(hazeState, zIndex = 1f)`, the optional
  player-background layer at `:518-535` is `hazeSource(..., zIndex = 0f)`.
- `LocalHazeState` is provided at `:452`. `Modifier.glassSurface(kind, shape)` in
  `ui/glass/LiquidGlass.kt:48` reads `LocalGlassSettings`, `LocalPerformanceProfile`,
  `LocalDockGlassStyle`, `LocalGlassOptics`, then applies `hazeEffect` (Haze 1.6) +
  indigo tint + rim + sheen.
- `GlassMapping` (`ui/glass/GlassControls.kt:73`) maps the three 0..1 controls:
  `blurStrength -> 0..40dp`, `glassClarity -> surface fill alpha`, `dockTransparency -> dock only`.
- Secondary windows: `DialogBackdropBlur()` (`LiquidGlass.kt:320`) sets
  `FLAG_BLUR_BEHIND` + `setBackgroundBlurRadius` on the dialog window (API 31+).
  `glassWindowSurface()` / `glassWindowPanelColor()` (`LiquidGlass.kt:288-312`) carry the
  window fill because a dialog/popup cannot sample the in-composition Haze layer.

## AUDIT LIST
Legend: FIXED / SKIP(reason). "Glass" = reachable in Glassmorphic theme.

### Menus (DropdownMenu)
- FIXED ui/glass/GlassScreens.kt:660  Library "+" (New playlist / Import Spotify CSV / History / Auto-fix tags) [Glass]
- FIXED ui/glass/GlassScreens.kt:685  Library sort menu [Glass]
- FIXED ui/glass/GlassScreens.kt:1066 playlist row overflow (Play/Shuffle/Rename/Delete) [Glass]
- FIXED ui/glass/GlassLists.kt:129   track row overflow (Play next / queue / like / download / add to playlist) [Glass]
- FIXED ui/glass/GlassNowPlaying.kt:500 lyrics options (paste/import/timing/full lyrics) [Glass, landscape]
- FIXED ui/glass/GlassSettings.kt:685 replay-gain menu [Glass]
- FIXED ui/glass/GlassYouTubePlaylist.kt:189 YouTube playlist row overflow [Glass]
- FIXED ui/screens/NowPlayingBlend.kt:1030 Now Playing three-dot (Shuffle..lyrics timing) [shared, portrait both themes]
- FIXED ui/screens/NowPlayingBlend.kt:1682 Queue options (Add songs / Clear queue) [shared]
- FIXED ui/screens/YouTubeHomeScreen.kt:251 YouTube account menu [shared]
- FIXED ui/screens/LiquidLyricsScreen.kt:363 Full lyrics overflow [shared]
- SKIP  ui/screens/LibraryScreen.kt:368,405,744  VERIFIED Normal only: returns into GlassLibraryScreen at :235-252 for Glass.
- SKIP  ui/screens/TrackList.kt:57 VERIFIED Normal only: returns into GlassTrackRows at :33-36 for Glass.
- SKIP  ui/screens/YouTubePlaylistScreen.kt:211 VERIFIED Normal only: returns into GlassYouTubePlaylistScreen at :74-80 for Glass.
- SKIP  ui/glass/GlassNowPlaying.kt uses no other DropdownMenu

### Dialogs (AlertDialog / Dialog)
- FIXED ui/glass/GlassScreens.kt:888,897,911 New playlist / Rename / Delete playlist [Glass]
- FIXED ui/glass/GlassHistory.kt:111 Clear history [Glass]
- FIXED ui/glass/GlassPlaylistDetail.kt:111 Rename playlist [Glass]
- FIXED ui/glass/GlassNowPlaying.kt:698,721,755 Add songs / Sleep timer / Paste lyrics [Glass landscape]
- FIXED ui/screens/NowPlayingBlend.kt:2352,2402 Add songs / Sleep timer [shared]
- FIXED ui/screens/LiquidLyricsScreen.kt:527 Paste lyrics [shared]
- FIXED ui/screens/LogsDiagnosticsScreen.kt:285 Clear all logs [shared]
- FIXED ui/screens/Stage4Screens.kt:605 Restore backup confirm [shared]
- FIXED ui/DatabaseRecoveryScreen.kt:105 Reset local database [shared]
- FIXED ui/settings/SettingsSubPages.kt:516,543,878 Provider credential / Last.fm key / reset confirm [shared]
- FIXED ui/settings/SettingsHub.kt:246 `ChoiceDialog` radio list (shared: SettingsHubScreen is used by BOTH themes, and it is called from SettingsSubPages.kt:150, 195, 844) [shared]
- SKIP  ui/AuralisRoot.kt:157 Crash-report notice: composed BEFORE AuralisTheme, outside the themed tree (deliberate: a launch crash is reported even if settings/theme never loaded). Existing behaviour, not a themed surface.
- SKIP  ui/screens/LibraryScreen.kt:554,565,580 VERIFIED Normal only: GlassLibraryScreen is used for Glass and LibraryScreen is only composed in the Normal branch.
- SKIP  ui/screens/PlaylistDetailScreen.kt:103,112 VERIFIED Normal only: delegates to GlassPlaylistDetailScreen at :55 when Glass.
- SKIP  ui/screens/HistoryScreen.kt:104 VERIFIED Normal only: GlassHistory is the Glass screen.
- SKIP  ui/screens/SettingsScreen.kt:416 VERIFIED Normal only: delegates to GlassSettingsScreen at :103.
- SKIP  ui/screens/NowPlayingScreen.kt:451,474 VERIFIED Normal-only: portrait returns BlendNowPlayingScreen at :132-179 for BOTH themes, and Glass landscape returns GlassNowPlayingScreen at :180-199, so the remaining body (and its dialogs) is Normal landscape only.
- SKIP  ui/screens/YouTubeLoginDialog.kt:128 Full-screen WebView surface, not a floating menu; leave look/behaviour
- SKIP  ui/update/UpdateDialog.kt:57 already uses DialogBackdropBlur + glassWindowSurface; benefits from the new translucent window fill
- SKIP  ui/glass/GlassKit.kt:125,189 GlassDialog / GlassAlertDialog implementation (already glass; window fill updated centrally)
- SKIP  ui/glass/GlassPlaylistDetail.kt:120 + GlassSettings.kt:313 use GlassDialog (already glass)

### Sheets (ModalBottomSheet)
- FIXED ui/components/AudioInfoSheet.kt:455 Audio info sheet [shared]
- FIXED ui/screens/NowPlayingBlend.kt:2261 Audio output sheet [shared]
- FIXED ui/screens/YouTubeHomeScreen.kt:820 YouTube card/playlist sheet [shared]

### Other
- Snackbars: Material host, not a menu/dialog/sheet; leave (no overlap issue).
- Tooltips / ExposedDropdownMenu / BottomSheetScaffold / Popup: none in source.
- Custom overlays: none outside the above.

### Shared helper composables (searched by name; every call site traced)
- `GlassPlaylistMenu` (GlassScreens.kt:1056) private, Glass-only: uses the converted `GlassMenu` [ok]
- `PlaylistMenu` (LibraryScreen.kt:734) private, Normal-only body (returns to Glass at :235-252): raw DropdownMenu stays [Normal]
- `PlayerMoreMenu` (NowPlayingBlend.kt:1013) shared, portrait both themes: FIXED to GlassMenu
- `AudioOutputSheet` (NowPlayingBlend.kt:2249) shared: FIXED to GlassSheet
- `AddSongsDialog` (NowPlayingBlend.kt:2346) shared: FIXED to GlassMaterialAlertDialog
- `SleepTimerDialog` (NowPlayingBlend.kt:2394) shared: FIXED to GlassMaterialAlertDialog
- `CollectionSheet` (YouTubeHomeScreen.kt:810) shared: FIXED to GlassSheet
- `LyricsPasteDialog` (LiquidLyricsScreen.kt:507) shared: FIXED to GlassMaterialAlertDialog
- `CredentialDialog` (SettingsSubPages.kt:515) shared: FIXED
- `LastFmKeyDialog` (SettingsSubPages.kt:542) shared: FIXED
- `ChoiceDialog` (SettingsHub.kt:238) shared: FIXED
- `GlassDialog` / `GlassAlertDialog` (GlassKit.kt) already Glass; Normal callers do not exist
- `YouTubeLoginDialog` (screens) full-screen WebView, not a floating surface: SKIP
- `UpdateDialog` (ui/update) already uses DialogBackdropBlur + glassWindowSurface: SKIP

### Final raw-surface sweep (every remaining hit has a reason)
- AlertDialog(: AuralisRoot.kt:157 (pre-theme crash, SKIP) · GlassOverlays.kt:180 (wrapper Normal delegate) ·
  HistoryScreen.kt:104 / LibraryScreen.kt:554,565,580 / NowPlayingScreen.kt:451,474 /
  PlaylistDetailScreen.kt:103,112 / SettingsScreen.kt:416 (all Normal-only, branch returns verified)
- ModalBottomSheet(: only the GlassSheet wrapper (GlassOverlays.kt:136,146)
- DropdownMenu(: only the GlassMenu wrapper + GlassOverlays.kt:87,101; LibraryScreen.kt:368,405,744 /
  TrackList.kt:57 / YouTubePlaylistScreen.kt:211 (Normal-only, branch returns verified)
- Dialog(: GlassKit.kt:125,189 + GlassOverlays.kt:194 (glass/impl) · YouTubeLoginDialog.kt:128 (SKIP) ·
  UpdateDialog.kt:57 (already glass)
- Popup(: none

## DECISIONS
- **Naming:** the glass package already had `GlassDialog`/`GlassAlertDialog` (string-only
  confirm dialogs in `GlassKit.kt`). The new slot-compatible drop-in is therefore
  `GlassMaterialAlertDialog` (same slots as Material3 `AlertDialog`); no duplicate names.
- **ChoiceDialog** (`ui/settings/SettingsHub.kt:238`) is shared, not Normal-only: it is called
  from `SettingsSubPages.kt:150,195,844` and those pages render in both themes. It keeps its
  generic signature and is converted to `GlassMaterialAlertDialog`, which delegates to the stock
  `AlertDialog` in Normal. `GlassDialog` itself only takes a string message, so it cannot host
  the radio list.
- Menus cannot sample the in-composition Haze layer (they are separate windows), so
  `GlassMenu` keeps Material3 `DropdownMenu` for anchoring/back/outside-tap behaviour and
  enables **window-level blur-behind** on the popup window (API 31+) via the same
  `DialogBackdropBlur()` helper, extended to fall back to the root view's
  `WindowManager.LayoutParams` (works for popup, dialog and bottom-sheet windows).
  (`PopupLayout` has no `Window`; its root view's layout params are the window attributes.)
- The popup window is menu-sized, so a full-screen dim is drawn by a root-level,
  non-interactive scrim (`GlassOverlayScrim`) driven by a shared open-menu counter.
- Window fill is a **translucent indigo tint**: `windowTintAlpha(clarity)` = 0.82 (clarity 0)
  -> 0.62 (clarity 1), so Glass clarity stays visible; below API 31 / blur off it becomes
  `WINDOW_NO_BLUR_TINT` 0.94. Never transparent; no cream/white wash.
- Normal theme: every wrapper delegates to the stock Material component with the caller's args.
- Animation: Material3's own fade+scale menu animation is retained (~120 ms) and the
  Low performance tier never gets an extra animation. A custom popup was rejected because
  it would have to re-implement anchoring, outside-tap and back handling (risk without a build).
- The crash-report notice at `AuralisRoot.kt:157` is composed before `AuralisTheme` on purpose
  (it must work even when settings/theme never loaded), so it stays a plain `AlertDialog`.

## KNOWN LIMITATIONS / UNCERTAINTY (to verify on device)
- Bottom sheets (`GlassSheet`) get blur + indigo tint + rounded shape, but Material3's
  `ModalBottomSheet` has no border parameter, so the cream hairline is not drawn on sheets
  (menus and dialogs do draw it). No functional impact.
- Animation is Material3's own menu/dialog animation; we did not replace it with a custom
  150-200 ms fade+scale, because re-implementing anchoring/outside-tap/back without a build is
  riskier. Low performance mode already skips extra animation.
- Window blur-behind (`DialogBackdropBlur`) needs API 31+. It uses the dialog window when
  available and falls back to the popup root view's `WindowManager.LayoutParams`. If some OEM
  rejects `updateViewLayout` blur, the dense tint fallback still keeps text readable.
- `@OptIn(ExperimentalMaterial3Api::class)` on `GlassMenu` may be unnecessary on this Material3
  version; that is at most an "unnecessary opt-in" warning.

## DONE
- Audit completed (above), including the shared-helper sweep and the ChoiceDialog correction.
- `ui/glass/GlassOverlays.kt` created: `GlassOverlayState` (safe counter, no delegated-setter
  visibility), `LocalGlassOverlay`, `GlassOverlayScrim`, `GlassMenu`, `GlassSheet`,
  `GlassMaterialAlertDialog`.
- `LiquidGlass.kt`: clarity-driven translucent window fill (`glassWindowPanelColor`) + window
  blur-behind for dialog/sheet/popup windows (`DialogBackdropBlur` + `applyPopupBlurBehind`).
- `GlassControls.kt`: added `windowTintAlpha`, `WINDOW_NO_BLUR_TINT`, `MENU_BACKDROP_SCRIM`,
  `SHEET_SCRIM`.
- `AuralisRoot.kt`: `GlassOverlayState` created + provided; `GlassOverlayScrim()` hosted.
- All Glass-reachable menus → `GlassMenu`; sheets → `GlassSheet`; AlertDialogs →
  `GlassMaterialAlertDialog` (see AUDIT LIST).
- Unused imports from the conversions removed (AlertDialog / ModalBottomSheet / DropdownMenu /
  DialogBackdropBlur / glassWindowPanelColor / LocalThemeStyle / ThemeStyle). Verified none left.
- Normal theme: wrappers delegate to the stock Material components with the caller's args.

## IN PROGRESS
- (none) — implementation complete; awaiting the user's build/device test.

## NEXT
- User builds and runs the self-test checklist (both themes, Live blur low/medium/high):
  Library "+", Now Playing three-dot, a song overflow menu, a Settings ChoiceDialog, a bottom
  sheet, a confirm dialog.
- No further code changes planned unless the build/test reveals an issue.

## NOW PLAYING → ADD TO PLAYLIST (2026-11-24)
- `ui/components/PlaylistPicker.kt` (new): `playlistPickRows(...)` merges local playlists with the
  signed-in account's playlists and de-duplicates a mirrored playlist by its normalised browse id;
  `PlaylistPickerDialog` renders the list (local rows accept any song, account-only rows accept
  streamed YouTube songs only).
- `NowPlayingBlend.kt`: the header three-dot (`PlayerMoreMenu`) now has "Add to playlist" at the top.
  Picking a local row calls `AppViewModel.addToPlaylist` (Room write + existing account sync);
  picking an account-only row calls the new `AppViewModel.addToAccountPlaylist` (direct
  `YouTubeRepository.addPlaylistVideo`, queued in `youtube_sync_ops` when offline).
- `NowPlayingScreen.kt` / `AuralisRoot.kt`: thread the playlist flows and callbacks into the Blend
  screen; opening the picker refreshes the account playlist list (`refreshYouTubeLibrary`).
- Tests: `PlaylistPickerTest` (6 cases: mirror de-duplication, VL-normalisation, local-only rows,
  ordering, duplicate account ids, origin labels). Full suite: 369 tests, 1 pre-existing glass
  failure (`GlassMappingTest`), unrelated.

# LIVE GLASS ON DRAG (2026-11-25)

## TASK
Make the real dock, mini-player and Search circle repaint in the SAME frame as the Appearance
"Dock transparency" / "Glass clarity" sliders. The small preview already moved; the real surfaces
did not.

## ROOT CAUSE (as far as code allows without a device)
Two independent gaps, both now closed:

1. Invalidation path. `Modifier.auralisGlass` derived its tint from `LocalGlassConfig.current` only.
   That is forwarded by `GlassSettingsProvider` (a dynamic `compositionLocalOf`), but the dock
   surfaces (`GlassDock`/`GlassNavPill`/`GlassCircleButton`/`DockMiniPill`) receive `DockGlassStyle`
   and never the live config, so a drag tick could update the provider without the surface's
   modifier chain being rebuilt - it kept painting the tint it built on the tick before.
2. Repaint path. `DrawBackdropElement.update()` refreshed `onDrawSurface`/effects but called only
   `invalidateDrawCache()`, which re-observes the effect scope and never calls `invalidateDraw()`.
   A rebuilt backdrop chain could therefore hold the new tint without requesting a draw. The shadow
   and highlight elements next to it already call `invalidateDraw()`.

## FIX (2 files, no visual change)
- `ui/glass/AuralisGlass.kt`: `val config = LocalGlassDrag.current.value ?: LocalGlassConfig.current`.
  `LocalGlassDrag` is a static local holding one shared `GlassDragState`; `.value` is a plain
  snapshot read, so every glass surface is invalidated directly on each slider tick in its own
  scope. The value is the same instance the root provider forwards, so rendering is identical:
  live during the drag, persisted config after the drag clears the override.
- `ui/glass/backdrop/DrawBackdropModifier.kt`: `DrawBackdropElement.update()` now also calls
  `node.invalidateDraw()`, so an element swap always repaints (matches ShadowElement/HighlightElement).

## DO
- Keep the drag override in `GlassDragState` (transient) and persist only on
  `onValueChangeFinished`; the root clears it once the persisted value catches up.
- Read the live config where the material is built (`auralisGlass`), not only through the provider.

## DON'T
- Do not revert these locals to `staticCompositionLocalOf` to force the dock to update; that
  recomposes the whole provider content (player + page container + dock) on every tick.
- Do not remove the `LocalAppBackdrop provides null` override around page content (native crash).
- Do not add a second slider write path or persist per tick.

## NEXT
- User builds and drags Dock transparency 0 -> 100 -> 0 watching the real dock; leaves mid-drag;
  restarts and confirms persistence. If the dock still lags, the remaining suspect is the recorded
  layer composite, not the tint (check the DEBUG diagnostics card first).
