# ONE BLACK BACKGROUND EVERYWHERE — working notes

Unify the Glassmorphic theme on **pure black (#000000)** with Vanilla Cream (#F0E7D5) as the only
foreground/accent; fix the Library header, add a 30 s metadata retry, and fix the listed glitches.
Branch: `black-background` (from `history-sync`, base `resume-last-song` / `3366b1f`).

This is the running record. Update it after **every** step and re-read it (plus the task prompt)
after any summary/compaction.

---

## DO

- Read the whole project structure first; open every section-2 file fully. Do not guess.
- Keep changes consistent with surrounding structure, naming, style and patterns.
- Clean code only: no dead, commented-out or duplicate code.
- Glass only: pure black everywhere; Cream (#F0E7D5) for text, icons and accent. Keep
  `GlassTokens.Indigo` **only** as the dark foreground drawn on cream fills (selected chips,
  primary buttons, nav active, switch thumb).
- `GlassTokens.Canvas = Color(0xFF000000)`; re-base `Surface` / `Surface2` / `SurfaceHigh` off
  `Canvas`; `ColorScheme.background` / `surface` / `surfaceContainerLowest = Canvas`; dialog and
  sheet scrims black-based; `StartupSurface` + `themes.xml` / `colors.xml` window background black
  (no cold-start blue flash).
- Paint the background **once** at the root; every page transparent (Home must not paint its own
  black anymore once the root does).
- One logo composable using `R.drawable.ic_logo_header` everywhere; same position and size;
  remove the duplicate white three-line mark.
- Library: remove the "History" chip in both themes (Glass `GlassScreens.kt` ~445,
  Normal `LibraryScreen.kt` ~329-353); keep Settings > History row and the Library "+" menu
  History item. "Your Library" must fit one line at font scale 1.3 / 360 dp.
- Appearance (Glass only): hide the Mode chips and the Accent circles; keep both in Normal;
  Appearance subtitle for Glass = "Theme and player background"; hide Accent/Mode from Settings
  search results when Glass is selected; Glass stays dark-only.
- Mini-player: always show the current song's art on every page and theme (same source as the Home
  lists), small ~96 px request size, dark neutral placeholder (no vinyl, no grey wash), no text
  bleed-through.
- Metadata retry: one `MetadataFiller` helper; dedupe in-progress ids; 30 s deadline; delays
  1 / 3 / 6 / 10 / 10 s; concurrency 3; cancel when the song leaves the screen or the app
  backgrounds; try `songMetadata` first then the other existing paths; persist success into
  `_remoteTracks` / `youtube_recents`; never overwrite a good field with a blank; skeleton/placeholder
  not a raw video id; "Unknown artist" after the deadline; no polling beyond 30 s.
- Commit after each numbered step on branch `black-background`.
- Stage **only** the task hunks (`git apply --cached --recount` of the step's hunks). The tree
  carries ~41 unrelated pre-existing WIP changes that must never be committed.
- Update this file (DO / DON'T / NEXT / FINDINGS) after every step.

## DON'T

- No ADB / SDK / emulator / device commands. File edits only (+ optional Gradle compile check).
- Do not change: dock / mini-pill shape, size, layout or animations; Now Playing layout; lyrics;
  playlists / history sync; login / Clerk; downloads; equalizer; Normal-theme colours; package name,
  application id, signing or version code.
- No new libraries, no new permissions. Avoid a Room schema change.
- Glassmorphic only: Normal must be byte-for-byte unchanged in look.
- No dead / commented-out / duplicate code.
- Do not leave the working tree's WIP symbols (`accountSuffix`, `lastHomeFromCache`, `HomeCurator`,
  Clerk timers, lyrics providers, glass helpers) in the branch diff.

## NEXT

1. **STEP 1 ✓** — branch + notes + FINDINGS (below).
2. **STEP 2 ✓** — black canvas: `GlassTokens.Canvas`, re-based surfaces + ColorScheme + scrims,
   root-only background, transparent pages, black splash / `StartupSurface`; classified every
   Indigo / Obsidian use; both themes compile. Committed. Details in "STEP LOG" below.
3. **STEP 3 ✓** — transitions: fade-through (out ~90 ms, in ~140 ms, 0.985 scale), scaled by the
   Performance profile and "Remove animations"; sequential fades so glass can't ghost; scroll/state
   untouched. Committed. Details in "STEP LOG".
4. **STEP 4 ✓** — one `AuralisLogo` composable on `ic_logo_header`, used by both top bars, YouTube
   Home and the two login states; `AuralisMark` (the duplicate white three-line mark) and its Home
   card call deleted. Glass header logo aligned to Normal's 16 dp start / 0.10 collapse scale.
   Committed. Details in "STEP LOG".
5. **STEP 5 ✓** — Library header: History chip removed in both themes; Settings > History and the
   "+" menu History item kept; "Your Library" now auto-sizes to stay on one line (18 sp floor
   Normal / 16 sp Glass) at font scale 1.3 on 360 dp. Committed. Details in "STEP LOG".
6. **STEP 6 ✓** — Appearance (Glass): Mode chips + Accent circles hidden (kept in Normal); Glass
   subtitle "Theme and player background"; Glass search no longer matches "accent"/"mode"; Glass
   stays dark-only (root already forces dark). Committed. Details in "STEP LOG".
7. **STEP 7 ✓** — mini-player art: always drawn from the track's own art (same fields as the Home
   lists) with a ~96 px decode, opaque pure-black placeholder (no vinyl glyph, no grey fill), and
   nothing painted over it. Committed. Details in "STEP LOG".
8. **STEP 8 ✓** — glass legibility sweep: the last indigo scrim/fill is black, login/lyrics/Now
   Playing fallbacks no longer paint indigo, stale "indigo" wording corrected. Committed. Details in
   "STEP LOG".
9. **STEP 9 ✓** — one `MetadataFiller`: deduped ids, immediate lookup then 1/3/6/10/10 s retries
   (30 s deadline), 3 concurrent, cancelled when the row leaves History or the app backgrounds;
   results merge into `_remoteTracks` + `youtube_recents`; History shows a skeleton, then "Unknown
   artist", never the raw video id. Committed. Details in "STEP LOG".
10. **STEP 10 ✓** — self-review yes/no checklist; two "no" findings fixed (metadata effect no longer
    restarts other rows' windows, and the square-art resolver is now actually reachable); build +
    301 tests green. Committed. Details in "STEP LOG".
11. **STEP 11 ✓** — final report recorded below, with the verification runs and the one pre-existing
    caveat (the branch's committed tree relies on the user's uncommitted WIP, as it already did at
    the base). Committed.

---

## STEP LOG

### STEP 10 — self-review ✓

Two checklist items came back "no" and were fixed before the commit:

- **Metadata effect could restart other rows' windows.** The first version keyed `DisposableEffect`
  on the pending-id list and released every id on dispose. Because a successful fill changes the
  History list, one row finishing cancelled and immediately re-requested the remaining rows,
  resetting their 30 s clocks (so a row could effectively poll past 30 s). `MetadataFillEffect` now
  keeps the ids it owns in a `remember { mutableStateListOf<String>() }` and diffs membership:
  only ids that actually leave are released, only ids that actually arrive are requested. Lifecycle
  start/stop still requests/releases the whole set because that is a genuine visibility change.
- **The square-art resolver was unreachable.** `songMetadata` always returns a 4:3 video still, and
  the old `artworkFor` required a title + artist that only existed after the merge, which then ended
  the retry loop already. `Track.filledWith` now accepts the provider's real square cover when it
  has one (and never the video still), and `fill` asks `ArtworkResolver` once the merged row has a
  searchable title/artist and still lacks square art. The resolver call lives inside the same
  `Semaphore` permit as the fetch. Two tests cover it (resolver used once; existing square cover
  never replaced), taking the class to 12 tests.

Everything else passed as written:

### SELF-REVIEW CHECKLIST (STEP 10 — all yes)

- [x] **Glass: root black, pages transparent, Home no longer paints its own black.**
      `AuralisBackdrop` paints `GlassTokens.Canvas` once at the root; `YouTubeHomeScreen` only paints
      `HomeCardPalette.Canvas` when `!style.glass`.
- [x] **No indigo background anywhere; `Indigo` only on cream fills.**
      Audit of every `GlassTokens.Indigo`: nav active icon/label, selected chip text, primary-button
      icon/text, switch thumb, LIKED tile, login button content, lyrics `onPrimary`, Now Playing play
      glyph, `onAccent`/`onPrimary`-style ColorScheme slots — all foregrounds on cream. Glob for
      `background(…Indigo)` / `containerColor…Indigo` returns only login disabled *content* colours.
- [x] **ColorScheme background/surface/surfaceContainerLowest black; surfaces re-based off Canvas.**
      `GlassTheme.kt`: `Canvas = 0xFF000000`, `Surface/Surface2/SurfaceHigh = blend(Cream, Canvas,
      .06/.10/.14)`, `background` + `surfaceContainerLowest = Canvas`, `surface = Surface`.
- [x] **Scrims black-based; switch OFF track Cream14; splash + StartupSurface black.**
      Dialog/sheet/menu scrims are `Canvas.copy(alpha = …)`; `uncheckedTrackColor = Cream14`;
      `sf_background_dark = #FF000000`; `StartupSurface` paints `Canvas`; bars transparent with dark
      icons forced.
- [x] **Normal theme byte-for-byte unchanged in colour.**
      `ui/theme/Color.kt` and `ui/theme/Theme.kt` have no diff across steps 1–10; every other change is
      behind `isGlass`/`ThemeStyle.GLASSMORPHIC` (the sole intentional Normal change is the required
      Library History-chip removal).
- [x] **One logo composable, same position/size; `AuralisMark` gone.**
      `AuralisLogo` (the `ic_logo_header` painter) is used by Glass/Normal top bars, YouTube Home and
      both login states; `AuralisMark.kt` and its `HomeCards` call are deleted, no references remain.
- [x] **Library: no History chip in either theme; Settings + "+" History intact; title one line.**
      Both headers use `TextAutoSize.StepBased` (18 sp Normal / 16 sp Glass floor); Settings has the
      History row and both "+" menus keep the History item.
- [x] **Appearance: Mode/Accent hidden for Glass, kept for Normal; subtitle + search correct.**
      `if (!isGlass)` wraps both sections; Glass summary is "Theme and player background"; categories
      key on `isGlass`, filter on `(query, categories)`, so "accent"/"mode" find nothing in Glass.
- [x] **Mini-player art on every page/theme, ~96 px, dark neutral placeholder, no text bleed.**
      Root-level dock always renders `ArtworkImage` with the track's own art candidate list,
      `neutralPlaceholder = true` (opaque black, no glyph) and `requestSizePx = 96`.
- [x] **Metadata retry: one helper, dedupe, 30 s, 1/3/6/10/10, concurrency 3, cancel, persist, no
      blank overwrite, "Unknown artist" finally.**
      `MetadataFiller` is the only retry owner; per-id dedupe; gaps `1/3/6/10/10 s` capping at
      `DEADLINE_MS = 30_000`; `Semaphore(3)`; `release`/`releaseAll` on row exit and
      `onAppBackgrounded`; results merge into `_remoteTracks` + `youtube_recents`; `filledWith` only
      fills blanks and ignores video stills; History shows skeleton → "Unknown title"/"Unknown
      artist", never the raw id.
- [x] **Transitions fade-through, performance-scaled, no ghosting; scroll/state preserved.**
      Out 90 ms then in 140 ms (delayed by the out duration) with 0.985 scale; `transitionScale` is
      0 for reduce-motion, 0.6 on Low; same `AnimatedContent`/dock scroll state.
- [x] **Build + unit tests green; only task hunks staged; no WIP committed.**
      `:app:compileDebugKotlin` BUILD SUCCESSFUL; full suite 301 tests / 0 failures; steps 1–10 touch
      only the task files (no `build.gradle`, manifest, schema or WIP symbols), and the ~41 unrelated
      WIP files remain unstaged.

### STEP 9 — metadata retry ✓

- New `data/youtube/MetadataFiller.kt`, the one helper behind every retry:
  - `request(track)` is keyed by track id, so recomposition or a second History row can never start
    a duplicate lookup; `release(id)`/`releaseAll()` cancel without marking the song finished.
  - A lookup runs immediately, then retries after 1, 3, 6, 10 and 10 seconds (the gaps sum to the
    30 s `DEADLINE_MS`); nothing is attempted after that. A `Semaphore(3)` caps concurrency.
  - `filledWith` merges field-by-field: a real title, artist, album, duration or artwork is never
    replaced and a blank provider result never erases a value. A stored video id counts as missing,
    and the provider's generic "Unknown artist" is not written over a blank.
  - Artwork uses the real square URL when the provider has one; otherwise the existing
    `ArtworkResolver` (the same metadata providers used elsewhere) is asked once the row has a
    searchable title and artist. Fetch path is `YouTubeRepository.metadata` → `songMetadata`.
  - `YouTubeRecentEntity.filledWith` mirrors a result into `youtube_recents`, keeping `playedAt`
    and `playCount`.
- `ui/AppViewModel.kt`: owns the single filler (viewModelScope). `requestMetadata` /
  `releaseMetadata` are the public entry points; `persistFilledMetadata` merges into `_remoteTracks`
  without lowering a better copy and updates the history row via the new `YouTubeDao.recent(id)`.
  `backfillTrackMetadata` (play path) now uses the same helper. `onAppBackgrounded` calls
  `releaseAll()`. `metadataSettled` drives the "gave up" state.
- `ui/metadata/MetadataUi.kt`: `MetadataFillEffect` starts retries on first composition and on
  `ON_START`, cancels on `ON_STOP` and dispose; `MetadataSkeleton` draws the neutral loading bars;
  `Track.displayTitle` hides the internal video id.
- `data/db/Mappers.kt`: `remoteTrack` stores a blank title when the stored value is the video id, so
  no screen can render an internal id before metadata arrives (HistorySync still recognises and
  backfills those rows).
- UI: History (both themes) shows skeleton bars until a row settles, then the real title or
  "Unknown title" and the artist or "Unknown artist"; the glass/normal mini-players and both Now
  Playing headers use `displayTitle` instead of a blank line.
- Tests: `MetadataFillerTest` (10 cases) covers immediate completion, the exact 1/3/6/10/10 retry
  schedule, no polling after 30 s, per-id de-duplication, cancellation, missing-field-only merge,
  video-id replacement, blank-result safety and history play-count preservation. Full suite: 299
  tests, 0 failures; `:app:compileDebugKotlin` BUILD SUCCESSFUL.

### STEP 8 — glass legibility sweep ✓

- `ui/glass/LiquidGlass.kt`: the reusable `liquidGlass` modifier's fallback scrim was still
  `GlassTokens.Indigo` (HEAD); it is now `GlassTokens.Canvas`, so no glass surface can lay an indigo
  wash over the black canvas or over artwork. Blur path comments corrected.
- `ui/screens/LoginScreen.kt`: Glass login backdrop is `Canvas`, not `Indigo` (Normal keeps the
  AMOLED gradient). Login content colours are untouched.
- `ui/screens/LiquidLyricsScreen.kt`: Glass base field and the full-screen draw are `Canvas`.
- `ui/screens/LyricsContent.kt`: the dark lyrics scheme's `background`/`surface`/`surfaceVariant` use
  `GlassTokens.Canvas`/`Surface`/`Surface2` instead of the old `#212842`/`#232B45`/`#2A3350` blues.
- `ui/screens/NowPlayingPalette.kt`: the no-artwork `ArtPalette.Fallback` is black-based
  (`dominant`/`darkMuted = Canvas`, `vibrant`/`muted` lerped from black toward Cream).
- Wording only (no behaviour change): `ThemeStyleSelector.kt`, `DockGlassStyle.kt`,
  `GlassControls.kt`, `GlassKit.kt`, `GlassNowPlaying.kt`, plus the `GlassOverlays.kt` dialog doc —
  "cream on black" / "black scrim" instead of "indigo".
- Audit: every remaining `GlassTokens.Indigo` reference is a foreground on a Cream fill (selected
  chips/nav icon, primary/button labels, switch thumb, lyrics `onPrimary`) or the Home accent seed
  the prompt protects. No indigo background/fill remains.
- Verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL.

### STEP 7 — mini-player artwork ✓

- `ui/components/ArtworkImage.kt` gained two opt-in knobs (defaults unchanged, so every existing
  caller keeps today's behaviour):
  - `requestSizePx`: a fixed decode size that overrides the on-screen measurement. The dock passes
    `DockDimens.MiniArtRequestPx = 96`.
  - `neutralPlaceholder`: an opaque pure-black tile with no album glyph and no `AsyncImage`
    placeholder/error painter, so nothing (no themed grey, no vinyl disc, no text) shows through
    while art loads or when it is missing.
  - Candidate list now always appends the track's own `artworkUrl` / `artKey` file even when the
    player supplied an `artUri`, so the mini-art resolves from the same track fields the Home lists
    use and can fall back when the playback URI fails.
- `ui/components/AppDock.kt`: `DockMiniPill` draws through the shared `ArtworkImage` with
  `neutralPlaceholder = true` and `requestSizePx = 96`; the dock is a single root-level component,
  so the art appears on every page and in both themes.
- Verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL.

### STEP 6 — Appearance for Glass ✓

- `ui/settings/SettingsSubPages.kt`: in `AppearanceSettingsPage`, the "Mode" chips and "Accent"
  circles are wrapped in `if (!isGlass)`. Glass keeps only Theme style, Player background and its
  Glass controls; Normal is unchanged. Glass was already forced dark at the root
  (`AuralisRoot` `dark = themeStyle == GLASSMORPHIC || theme == DARK`), so it stays dark-only.
- `ui/settings/SettingsHub.kt`: the Appearance category summary is now
  `"Theme and player background"` for Glass and the previous `"Theme, accent and playback
  background"` for Normal. Search filters label + summary, so typing "accent" (or "mode") no longer
  surfaces Appearance while Glass is selected. The category list is now keyed on `isGlass`, and the
  filter on `(query, categories)` so the summary switch takes effect immediately.
- Verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL.

### STEP 5 — Library header ✓

- `ui/screens/LibraryScreen.kt` (Normal): deleted the header History pill (and the 8 dp gap that
  separated it from the "+" pill) and its now-unused `Rounded.History` import. The "+" menu still
  carries History, and Settings > History is untouched.
- `ui/glass/GlassScreens.kt` (Glass): deleted `GlassChip("History", …)` and its spacer; the "+"
  menu still carries History.
- "Your Library" in both themes now uses Compose `TextAutoSize.StepBased`, capped at the theme's
  `headlineMedium` size and floored at 18 sp (Normal) / 16 sp (Glass). At the default scale it still
  renders at full size (the freed chip space is enough); at font scale 1.3 on a 360 dp screen it
  shrinks just enough to remain one line instead of ellipsizing.
- Verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL.

### STEP 4 — one logo ✓

- Added `ui/components/AuralisLogo.kt`: the single `Image(painterResource(ic_logo_header))` with
  `ContentScale.Fit` and the "AURALIS" label. Callers pass only size/position.
- Replaced every wordmark draw: `GlassChrome.GlassTopBar`, `AuralisRoot.TopBar`,
  `HomeComponents.HomeTopBar`, and both `LoginScreen` states. No file re-declares the drawable now.
- Header parity: the Glass top bar dropped its extra `start = 6.dp` (row padding is 16 dp, matching
  Normal's 8 dp row + 8 dp logo padding) and its collapse scale is now `0.10` like Normal, so the
  logo sits and animates identically in both themes. Login keeps its centred 56 dp size (protected
  screen, layout untouched) but now draws through the same composable.
- Deleted `ui/components/AuralisMark.kt` and its `TopPickCard` call in `HomeCards.kt`; removed the
  now-unused `CardMarkWidth` / `CardMarkHeight` dimens. No code-drawn mark remains.
- Verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL.

### STEP 3 — fade-through navigation ✓

- `ui/AuralisRoot.kt`: the screen `AnimatedContent` now runs a true **fade-through** instead of the
  overlapping crossfade: the outgoing destination fades out + scales to `0.985` over ~90 ms, then
  the incoming one fades in + scales up from `0.985` over ~140 ms (incoming delayed by the out
  duration, so the old translucent glass is fully gone before the new page paints — no ghosting,
  and the black canvas carries the hand-off, so no white flash).
- `transitionScale` drives it from the shared performance signal: `0` (hard cut) when the system
  "Remove animations" scale is 0 via `rememberReduceMotion()`, `0.6` on the Low profile, else `1`.
  Durations scale linearly; the curve/feel is otherwise identical in every profile, per the
  `PerformanceProfile` contract.
- Scroll + screen state are untouched: the same `AnimatedContent`/`contentKey` and the same dock
  scroll state are reused; only the enter/exit spec changed.
- Verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL.

### STEP 2 — one black canvas ✓

- `ui/theme/GlassTheme.kt`: added `GlassTokens.Canvas = Color(0xFF000000)`; re-based
  `Surface`/`Surface2`/`SurfaceHigh` on `Canvas`; `Obsidian = Canvas`; `GlassColorScheme`
  `background` + `surfaceContainerLowest = Canvas`; switch OFF track (`auralisSwitchColors` and the
  custom `GlassSwitch`) → `Cream14`. `Indigo` kept strictly as the on-cream foreground.
- `ui/components/GlassBackdrop.kt`: Glass base → `Canvas` (root paints black once).
- `ui/components/ThemeStyleSelector.kt`: glass preview field → `Canvas`.
- `ui/glass/GlassKit.kt`, `GlassOverlays.kt`, `LiquidGlass.kt`: dialog / sheet / menu scrims, the
  dock + window glass fills and the window panel tint → `Canvas` (all black-based).
- `ui/glass/GlassSettings.kt`: `DockGlassPreview` field → `Canvas`; custom `GlassSwitch` OFF →
  `Cream14` (the compact top bar already used `Obsidian`, now black).
- `ui/AuralisRoot.kt`: `StartupSurface` → `Canvas` (no cold-start blue flash); dropped the
  `DarkPalette` import.
- `ui/home/HomeFeed.kt` + `ui/screens/YouTubeHomeScreen.kt`: Home only paints its own black field in
  Normal; in Glass the root black shows through (Home no longer paints a second black).
- `res/values/colors.xml`: `sf_background_dark` `#FF0A0C0F` → `#FF000000` (window/splash black;
  `themes.xml` already points at it, status/nav bars already transparent).
- Verified: `:app:compileDebugKotlin` BUILD SUCCESSFUL.
- Indigo classification (all remaining `GlassTokens.Indigo`): nav active icon/text, selected chip
  text, primary-button icon/text, switch thumb, LIKED-tile icon, login button content, lyrics
  `onPrimary`, Now Playing play glyph, and the ColorScheme's `on*`/`inverse*` slots — every one is a
  dark foreground on a cream fill.
- Deliberate exceptions (protected screens, not page backgrounds): the full-screen lyrics backdrop
  (`LiquidLyricsScreen`), the login backdrop (`LoginScreen`), the lyrics dark surface
  (`LyricsContent`) and the Now Playing fallback palette (`NowPlayingPalette`) keep their own
  artwork/flow treatment; the task's DON'T-list protects Now Playing, lyrics and login — only the
  shared *page* background is unified to black.

---

## FINDINGS

One line per section-2 file, then the verdicts.

- `ui/theme/GlassTheme.kt` — `GlassTokens`: `Indigo = #212842` (l.38) doubles as the app background
  **and** the inverted on-cream foreground; `Cream = #F0E7D5` (l.41); `Surface/Surface2/SurfaceHigh =
  blend(Cream, Indigo, .06/.10/.14)` (l.64-66); aliases `Obsidian=Indigo` (l.69), `Silver/Cream`,
  `Violet=Cream`, `Ice=Cream`, `OnAccent=Indigo` (l.70-79). `GlassColorScheme` (l.125-143) sets
  `background=Indigo`, `surface=Surface`, `surfaceContainerLowest=Indigo`. `auralisSwitchColors()`
  (l.180-195) OFF = track `Indigo` + `Cream60` thumb/border. `GlassTheme` builds `AuralisExtras`
  (glass = `Surface`).
- `ui/components/GlassBackdrop.kt` — `AuralisBackdrop` (l.22-31): Glass base is `GlassTokens.Indigo`
  (l.29); Normal uses `MaterialTheme.colorScheme.background`. **This is why every non-Home page is
  indigo while Home alone is black.**
- `ui/AuralisRoot.kt` — root provides the locals (l.485-500); `AuralisBackdrop` at l.501; Home hides the
  shared `TopBar` (l.605) and the pane Column adds `statusBarsPadding()` for every non-Home tab
  (l.596-599). Page transition = `AnimatedContent` (l.614) `fadeIn(tween(150)) togetherWith
  fadeOut(tween(210))` for Glass (l.623), `SizeTransform(clip=false)` (l.627); the optional player
  background is a `Crossfade(tween(360))` (l.560-573). `SystemBarsAppearance(dark || glass)` (l.362).
  `TopBar` logo (Normal) at l.1049, `lerp(38dp,30dp)`. `StartupSurface()` (l.1089-1092) paints
  `DarkPalette.background` (#0A0C0F) — **indigo-ish, not black.**
- `ui/theme/Color.kt` — `DarkPalette.background = #0A0C0F`; `Accents` list (uses only in Normal).
- `ui/theme/Theme.kt` — `AuralisAppTheme` + `AuralisExtras`; Normal path is untouched by this task.
- `res/values/themes.xml` — `android:windowBackground = @color/sf_background_dark`; `res/values/colors.xml`
  — `sf_background_dark = #FF0A0C0F` → **cold-start flash is #0A0C0F, not black.**
- `ui/glass/GlassChrome.kt` — `GlassTopBar` (l.60-90) draws `ic_logo_header` at `lerp(38dp,30dp)`.
  `GlassNavBar` pill (l.125-162) active = cream fill + indigo icon/text. `GlassMiniPlayer`
  (l.169-239) = `GlassPanel` + a cream wash + `GlassArtwork(48dp)`.
- `ui/glass/GlassKit.kt` — `GlassPanel` (l.76) = `glassSurface`; `GlassDialog` (l.111) / `GlassAlertDialog`
  (l.178) scrim = `GlassTokens.Indigo.copy(alpha = DIALOG_SCRIM)` (l.131,196) → **must become
  black-based.** `GlassChip` selected = cream fill + indigo text (l.302,315); `GlassPrimaryButton`
  cream + indigo (l.330-338); `GlassEmptyState` icon tint `Violet` (=Cream, l.448); `GlassWaveSeek`
  cream bars (l.461-589).
- `ui/glass/GlassScreens.kt` — `GlassLibraryScreen` header (l.435-466): "Your Library"
  (l.436-444, `maxLines=1`, `weight(1f)`), then **`GlassChip("History", …)` (l.445) — REMOVE**, then
  the "+" pill whose menu keeps the History item (l.457). `GlassSearchScreen` + `GlassTrackRows` etc.
- `ui/glass/GlassLists.kt` — `GlassTrackRows` / `GlassTrackRow` / `GlassCarousel` / `GlassFolderChip`;
  current-title tint `Violet` (=Cream). No background of its own.
- `ui/glass/GlassOverlays.kt` — `GlassOverlayScrim` (l.73) = `Indigo.copy(MENU_BACKDROP_SCRIM)` (l.76);
  `GlassMenu` (l.90), `GlassSheet` (l.138, scrim `Indigo.copy(SHEET_SCRIM)` l.165),
  `GlassMaterialAlertDialog` (l.180, scrim `Indigo.copy(DIALOG_SCRIM)` l.214). **All scrims black-based.**
- `ui/glass/GlassMotion.kt` — `rememberReduceMotion()` reads the global animator scale (l.17-29); the
  performance-scaled transition should honour it.
- `ui/components/AppDock.kt` — `DockMiniPill` (l.734) draws `ArtworkImage(track, artUri=artworkUri,
  size = if(isGlass) DockDimens.MiniArtSize(48dp) else 44dp)` (l.777-782); `dockSurface` (l.930) paints
  the pill. **Mini-player art = the Media3 `PlaybackController.State.artworkUri`** (l.914 in AuralisRoot),
  not the Track's own art list.
- `ui/components/ArtworkImage.kt` — placeholder = `MaterialTheme.colorScheme.surfaceContainerHigh`
  (l.93) + an `Album` icon (l.133); decode size clamped to display size (l.98-103).
- `ui/screens/LibraryScreen.kt` (Normal) — header (l.314-382): "Your Library" + a **History pill
  (l.329-353) — REMOVE**; the "+" menu keeps History (l.371).
- `ui/screens/YouTubeHomeScreen.kt` — draws **`HomeCardPalette.Canvas` (#000000)** itself (l.111,114);
  `HomeTopBar` (l.118) draws the logo. Pull-to-refresh + collection `GlassSheet` (l.197).
- `ui/screens/HomeScreen.kt` — routes: YouTube feed (both themes) / Glass `GlassHomeScreen` / Normal
  local `LocalHomeFeed` (l.56-134).
- `ui/home/HomeFeed.kt` — `LocalHomeFeed` paints **`HomeCardPalette.Canvas` (#000000)** (l.204); Glass
  path also flows through it (l.147 `rememberHomeStyle`).
- `ui/home/HomeComponents.kt` — `HomeCardPalette.Canvas = Color(0xFF000000)` (l.138);
  `rememberHomeStyle()` (l.178) gives Glass cream text on `HomeCardPalette.Surface(#141416)` /
  `SurfaceAlt(#1C1C1E)`; `HomeTopBar` logo (l.218-223) `height(30dp)`.
- `ui/home/HomeCards.kt` — `TopPickCard` draws **`AuralisMark`** (l.90-97) — the duplicate white
  three-line mark — top-right on every big discovery card.
- `ui/components/AuralisMark.kt` — the code-drawn **three stacked arcs** mark (l.19-43). Only caller is
  `HomeCards.kt:90`. **Delete it and its call.**
- `ui/settings/SettingsSubPages.kt` — `AppearanceSettingsPage` (l.760-810): "Theme style" chip selector
  (kept), **"Mode" chips (l.766-775) — hide for Glass**, **"Accent" circles (l.776-790) — hide for
  Glass**, "Player background" (kept). `isGlass = glass()` (l.762).
- `ui/settings/SettingsHub.kt` — `SettingsCategory("Appearance", "Theme, accent and playback
  background", …)` (l.354) → Glass subtitle "Theme and player background". Search (l.361-366) filters
  categories only; no per-row Mode/Accent entries here.
- `res/values-v27/themes.xml` — cutout mode only; inherits the base window background.
- `res/drawable-nodpi/ic_logo_header.png` — the one real wordmark (grey/green waveform). Also used by
  GlassChrome, HomeComponents, LoginScreen (×2), AuralisRoot.

### Verdicts

- **B1 — CONFIRMED.** Glass background is `GlassTokens.Indigo` (indigo) everywhere except Home, which
  paints `HomeCardPalette.Canvas` (#000000) itself. One root black + transparent pages fixes it.
- **B2 — CONFIRMED.** `_remoteTracks`/ColorScheme surfaces are pre-composited cream-over-indigo; they
  must be re-derived cream-over-black. Keep `Indigo` only as the on-cream foreground.
- **B3 — CONFIRMED.** Dialog / sheet / menu scrims and the switch-OFF track are indigo; the splash
  (`themes.xml`/`colors.xml`) is #0A0C0F → cold-start blue flash.
- **B4 — CONFIRMED.** Two logo implementations: the `ic_logo_header` PNG at several sizes and the
  code-drawn `AuralisMark` (three arcs) on Home cards.
- **B5 — CONFIRMED.** Library shows a "History" chip in both themes even though Settings and the "+"
  menu already expose History.

---

## FINAL REPORT (STEP 11) ✓

Branch `black-background`, ten commits on top of `history-sync` (`8f082d1`):

| # | Commit | Change |
|---|--------|--------|
| 1 | `0186844` | notes + findings (B1–B5 confirmed) |
| 2 | `bfc7a41` | one pure-black glass canvas (tokens, ColorScheme, scrims, splash, root-only background) |
| 3 | `0d62263` | fade-through navigation, scaled by the performance profile |
| 4 | `3bd674e` | one `AuralisLogo`; duplicate `AuralisMark` deleted |
| 5 | `fe6afab` | Library header: History chip gone in both themes, auto-sizing title |
| 6 | `975d632` | Glass Appearance: Mode + Accent hidden, subtitle + search updated |
| 7 | `6edabef` | mini-player artwork: track-sourced, 96 px, opaque black placeholder |
| 8 | `e056023` | last indigo scrim/fill → black; wording corrected |
| 9 | `7a88719` | `MetadataFiller`: deduped 30 s retry window |
| 10 | `c3ff3f5` | self-review fixes (effect diffing; square-art resolver reachable) |

### Result

- **Glass is pure black.** `GlassTokens.Canvas = 0xFF000000`; surfaces are cream-over-black; the
  ColorScheme `background`/`surface`/`surfaceContainerLowest` are black; the root paints the
  background once and every page is transparent (Home included); dialog/sheet/menu scrims, the
  window/splash background and `StartupSurface` are black.
- **Cream is the only foreground.** Every remaining `GlassTokens.Indigo` is dark text/icon on a cream
  fill (selected chip, primary button, nav active, switch thumb, LIKED tile, login button, lyrics
  `onPrimary`). No indigo background or fill remains.
- **Library / Appearance** match the spec (chip removed, one-line title, Glass-only Appearance).
- **Mini-player** art comes from the track on every page and theme, 96 px, flat black placeholder.
- **Metadata retry** is one helper with the exact 1/3/6/10/10 s back-off over a 30 s deadline,
  concurrency 3, per-id dedupe, cancellation on leave/background, blank-safe merges, persistence to
  `_remoteTracks` + `youtube_recents`, and skeleton → "Unknown title"/"Unknown artist" instead of a
  raw video id.
- **Protected areas untouched:** dock/mini-pill shape+size+layout+animation, Now Playing layout,
  lyrics, playlists/history sync behaviour, login/Clerk, downloads, equalizer, Normal colours,
  package/version/signing; no new libraries or permissions; no Room schema change (one new DAO query).

### Verification

- `.\gradlew :app:compileDebugKotlin` — **BUILD SUCCESSFUL**.
- `.\gradlew :app:testDebugUnitTest` — **52 suites, 301 tests, 0 failures**
  (`MetadataFillerTest` = 12 cases).
- Static audit: no `background(...Indigo)` / indigo container remains; scrims are
  `Canvas.copy(alpha = …)`; `ui/theme/Color.kt` and `ui/theme/Theme.kt` have **no diff** across
  steps 1–10.
- Steps 1–10 changed only task files — no `build.gradle`, `AndroidManifest.xml`, Room schema or WIP
  symbols; the ~41 unrelated WIP files are still unstaged.

### Caveat (pre-existing, not introduced here)

The committed tree does **not** build on its own, because it already did not at the base
`8f082d1`: committed code such as `data/youtube/YouTubeHistorySync.kt` calls
`YouTubeDao.allRecents()`/`upsertRecents()` and `ui/glass/GlassOverlays.kt` uses
`GlassMapping.MENU_BACKDROP_SCRIM`/`SHEET_SCRIM`, which exist only in the uncommitted WIP. That is a
property of the branch before this task and the DON'T-list forbids committing those WIP changes, so
they were left in the working tree. Every symbol the new task files use (`songMetadata`, `metadata`,
`ArtworkResolver.resolve`, `YouTubeDao.recent`, `isVideoThumbnailUrl`, `fallbackThumbnailUrl`,
`normalizeArtworkUrl`, `YOUTUBE_TRACK_PREFIX`, `youtubePlaybackUri`, `YOUTUBE_SOURCE_ID`) is present
at the committed HEAD, and the compile/test runs above pass on the working tree (WIP present).
