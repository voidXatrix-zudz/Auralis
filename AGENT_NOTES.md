# GLASS-OPACITY-FIX (active)

Branch: `glass-opacity-fix` (from `glass-dock-v2`). Glassmorphic theme only. No build/ADB/emulator;
verify by static review. One commit per step.

## DO
- Read each file fully before editing; re-read after editing.
- Rename "Dock transparency" -> "Dock opacity" end to end (model, DataStore, backup, tests, UI).
- New persisted key `dockGlassOpacity` (0..100). Migrate once: new = 100 - old `dockGlassTransparency`.
- ONE `GlassMapping.dockFill(o): Color` (frost 0..0.5, frost->solid 0.5..1, alpha 0->1).
- Keep it compiling with no new libraries; Normal theme + dock layout/motion untouched.
- Update this section after every step.

## DON'T
- Do not run Gradle / adb / SDK / emulator.
- Do not touch Normal theme, dock layout/animation, tabs, lyrics, Now Playing.
- No black tint/scrim in the dock; no `DOCK_NO_BLUR_TINT_FLOOR` / `DOCK_NO_BLUR_MIN_FILL`.
- Do not commit the unrelated WIP tree; stage only this task's hunks.
- Do not mention or edit unrelated files.

## DONE
- STEP 1 (this commit): renamed "Dock transparency" -> "Dock opacity" end to end.
  - `GlassSettings.dockOpacity`, `DEFAULT_DOCK_OPACITY = 0.50f` / `_PERCENT = 50`.
  - ONE `GlassMapping.dockFill(o)` (frost alpha 0..0.10 up to 0.5, smoothstep frost->`DOCK_SOLID`
    alpha 0.10..1.00 after); deleted the 4 tint/frost/scrim fns + `DOCK_NO_BLUR_TINT_FLOOR` /
    `DOCK_NO_BLUR_MIN_FILL`; black scrim gone from `dockGlassMaterial`; `fill.alpha >= 0.98`
    skips Haze + lens; `blurBackdrop = false` controls draw only a 10% cream bubble + rim.
  - Persisted `dock_glass_opacity` with one-time migration via `dockTransparencyToOpacity`
    (30 -> 70, 100 -> 0); backup exports the new key and still imports old backups inverted.
  - `DockGlassStyle.fillAlpha` + `opacity`, `GlassOptics.fillAlpha`, slider "Dock opacity".
  - Unit tests: `dockFill` endpoints/monotonic/no-0.04-step + migration cases.
- STEP 2 (this commit): dock no longer runs the AGSL lens by default.
  - `dockGlassMaterial`/`dockSurface` default `refraction = false`; the lens only runs when the
    DEBUG `dockLensExperimental` flag is on, and that path sets `CompositingStrategy.Offscreen`
    on its `graphicsLayer` before the shader.
  - Haze block sets `blurEnabled`, an explicit `blurRadius` from `dock.blurDp`, and
    `HazeInputScale.Fixed(...)`; the DEBUG "Haze input scale: Default" switch uses `Auto`.
  - Brightness lift is a 4% additive white Haze tint (no AGSL); `dockSaturation` is now
    `1.0 + 0.6 * blur` and only the experimental lens uses it.
  - Elevation shadow is skipped while the fill is translucent (< 0.6 alpha) so it cannot darken
    the pane from underneath.
  - Hidden active-tab circle passes `blurBackdrop = circleIn > 0.01f` (no wasted blur pass).
  - Collapsible DEBUG "Glass diagnostics" card at the bottom of Appearance with four lines
    (SDK/tier/profile blur, haze allowed + dp, input scale + lens, fill + opacity) and the two
    DEBUG switches.
- STEP 3 (this commit): slider drags no longer persist per tick.
  - `SliderSetting` gained `onChangeFinished`; the three glass sliders write only
    `GlassDragState.value` while moving and persist once via `actions.onUpdate` at drag end.
  - `LocalGlassSettings`, `LocalDockGlassStyle` and `LocalGlassOptics` are now
    `compositionLocalOf`, so only their readers are invalidated.
  - AuralisRoot resolves the live glass values inside a small `GlassSettingsProvider` composable
    that reads the drag state itself, so a tick no longer recomposes the root tree.
  - The dock `hazeStyle` is remembered on the 0.5dp-snapped radius.

## NEXT
- STEP 4: visible, pinned preview.
- STEP 5: self-review checklist in this file.

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
