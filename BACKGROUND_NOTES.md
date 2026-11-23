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
3. **STEP 3** — transitions: fade-through (out ~90 ms, in ~140 ms, tiny scale/slide), scaled by
   Performance mode; no ghosting / flash; preserve scroll + state. Commit.
4. **STEP 4** — one `AuralisLogo` composable on `ic_logo_header`, same position + size everywhere;
   remove `AuralisMark` (the duplicate white three-line mark). Commit.
5. **STEP 5** — Library header: drop the History chip (both themes); keep Settings > History + the
   "+" menu item; "Your Library" fits one line at font scale 1.3 / 360 dp. Commit.
6. **STEP 6** — Appearance (Glass): hide Mode chips + Accent circles; Glass subtitle
   "Theme and player background"; hide Accent/Mode from Settings search when Glass. Commit.
7. **STEP 7** — mini-player art on every page / theme, ~96 px, dark neutral placeholder. Commit.
8. **STEP 8** — glass legibility sweep (Cream-on-black contrast, no leftover indigo washes). Commit.
9. **STEP 9** — `MetadataFiller`: dedupe ids, 30 s deadline, delays 1/3/6/10/10 s, concurrency 3,
   cancel on leave / background, persist success, never blank-overwrite, "Unknown artist" finally.
   Commit.
10. **STEP 10** — self-review yes/no checklist; fix every "no". Commit.
11. **STEP 11** — final report (below / in the chat reply).

---

## STEP LOG

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

## SELF-REVIEW CHECKLIST (STEP 10 — yes/no, must all be yes)

- [ ] Glass: root black, all pages transparent, Home no longer paints its own black.
- [ ] Glass: no indigo background left anywhere; `Indigo` only on cream fills.
- [ ] ColorScheme background/surface/surfaceContainerLowest = black; surfaces re-based off Canvas.
- [ ] Scrims black-based; switch OFF track Cream14; splash + StartupSurface black.
- [ ] Normal theme byte-for-byte unchanged.
- [ ] One logo composable, same position/size; `AuralisMark` gone.
- [ ] Library: no History chip (both themes); Settings + "+" History intact; title one line @1.3.
- [ ] Appearance: Mode/Accent hidden for Glass, kept for Normal; subtitle + search correct.
- [ ] Mini-player art on every page/theme, ~96 px, dark neutral placeholder, no text bleed.
- [ ] Metadata retry: one helper, dedupe, 30 s, 1/3/6/10/10, concurrency 3, cancel, persist, no
  blank overwrite, "Unknown artist" finally.
- [ ] Transitions fade-through, performance-scaled, no ghosting; scroll/state preserved.
- [ ] Build + unit tests green; only task hunks staged; no WIP committed.

## FINAL REPORT (STEP 11)

_(filled in at the end.)_
