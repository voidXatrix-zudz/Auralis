# GLASS_NOTES.md — `glass-dock-v2`

Branch: `glass-dock-v2` (from `black-background` @ `49af265`).
Goal: make the Glass dock + mini-player pill a *real* liquid-glass material (big, obvious
0–100 Live-blur / Dock-transparency response, heavy blur, colour bleed, bright thin rim, edge
refraction) **and** fix the launch glitches L1–L6. Kotlin + AGSL only. No new libs.

## DO
- Read the code first; write a FINDINGS line before touching a file.
- Commit after every numbered STEP. Stage ONLY the task hunks (unrelated WIP tree must stay dirty).
- Keep setting key names + stored 0–100 value ranges. No Room schema change.
- Keep: dock/pill shape, size, position, tab layout, icons, motion; Now Playing layout; lyrics;
  playlist/history sync; login; downloads; equalizer; Normal-theme visuals; package/version/signing.
- Tier may only: (a) turn blur off in Low, (b) change cost via `HazeInputScale.Fixed(scale)`.
- Blur: `MAX_BLUR_DP = 80f`, `radiusDp = 80 * s^1.15` rounded to 0.5 dp. Never scale radius by tier.
- Dock tint + frost live on `GlassKind.Dock`; keep `GlassKind.Surface` (Glass clarity) untouched.
- One material function `Modifier.dockGlassMaterial(style, shape)` in `LiquidGlass.kt`; used by the
  dock circles, the tab pill, the mini-player pill and the Appearance preview.
- Rim + sheen gated by the existing "Glass outlines" switch (off = no rim, sheen stays).
- `noiseFactor = 0` for dock and pill (no grey film).

## DON'T
- Don't commit the ~41 unrelated WIP files (lyrics/QrcParser, Clerk timers, popup blur, HomeCurator,
  per-account cache keys, docs/). Working tree stays dirty; only task hunks go into commits.
- Don't change the committed shape/size/position of the dock or pill; don't touch motion.
- Don't add libraries, C++/NDK/JNI, or Dart. Don't change package id / version / signing.
- Don't scale the blur radius by performance tier.
- Don't leave the "Reduced by Performance Mode" message (removed). Keep "limited" text only for
  Android <12 / Low / battery saver.
- Don't allocate a `RuntimeShader` per frame (reuse the cached one).
- Don't use `GlassTokens.Cream` as a dock fill wash at full strength (only the small frost/sheen).

## NEXT
- STEP 1 ✅ read + findings + verify W1–W5.
- STEP 2 ✅ mapping + unit tests (11 tests green).
- STEP 3 ✅ `dockGlassMaterial` + STEP 4 apply to dock/pill/circles (delegated).
- STEP 5 ✅ live preview + numeric labels (uses the production material).
- STEP 6 ✅ launch glitches L1–L6 (explicit Home load state, gated sign-in toasts, art
  placeholder/crossfade, Top Picks reveal, static logo first frame; L6 verified).
- STEP 7 self-review. STEP 8 final report.

## PROGRESS / DECISIONS
- **STEP 2 (done)** — `GlassMapping`: `MAX_BLUR_DP = 80f`; `blurDp(strength) = round0.5(80*s^1.15)`;
  `dockTintAlpha` piecewise {0.90,0.66,0.42,0.20,0.05}; `dockTintColor()` = Canvas+3% Cream;
  `dockFrostAlpha = 0.02+0.06*(1-s)`; `dockTextScrimAlpha = 0.16*(1-blurFraction)`;
  `dockSaturation = 1+0.30*blurFraction`; `DOCK_BRIGHTNESS = 0.03`; `DOCK_NO_BLUR_TINT_FLOOR = 0.50`;
  `hazeInputScale(r) = clamp(1/(1+r/28), 0.25, 1)`. `resolveDockGlass` drops the
  "Reduced by Performance Mode" message, allows dock refraction in High **and** Medium, and keeps
  "limited" only for Android <12 / Low / battery saver.
  - *Compat note*: two symbols are deliberately kept one step longer because their only remaining
    callers live in unrelated WIP: the 2-arg `blurDp(strength, scale)` overload (ignores `scale`,
    so the radius is still tier-independent) and `DOCK_NO_BLUR_MIN_FILL`. They are removed when the
    WIP call sites are retired. This keeps the WIP tree compiling without committing it.
- **STEP 3+4 (done)** — `Modifier.dockGlassMaterial(style, shape, …)` in `LiquidGlass.kt` is now the
  ONE dock material (SOLID + GLASS). GLASS: Haze blur at the tier-independent radius, `HazeInputScale`
  = `hazeInputScale(radius) * (Medium ? 0.8 : 1)`, warm tint + frost + adaptive scrim, `dockRim()`
  (Cream 55%→12%) gated by `outlines`, always-on `dockInnerSheen()` (Cream 8%→0 at 40% height), and
  an AGSL lens with `uEdge` (16dp), `uRefract`/`uChroma` (0.35/0.012 scaled 0.6×→1.0× by blur),
  `uSaturation` and `uBrightness` (+0.03). `dockLiquidGlass` deleted; AppDock's `dockSurface` /
  `dockControlCircle` are now thin delegates, so the tab pill, circles and mini-player pill all share
  the one material. Reused the cached `RuntimeShader` (no per-frame allocation).
- **STEP 5 (done)** — live Appearance preview in `SettingsSubPages.kt`. `AppearanceSettingsPage` builds
  `liveGlass = GlassSettings.fromPercent(...)` + `previewDock = resolveDockGlass(...)` from the page's
  own `settings`, so the sliders, the readout and the preview all read one snapped copy. Preview card =
  vivid backdrop (`hazeSource`) + the production `dockGlassMaterial` tab pill + a production
  `dockGlassMaterial` circle (no second backdrop sample) + a production `glassSurface(Surface)` chip so
  "Glass clarity" visibly moves its fill/hairline. Numeric labels: clarity %, dock %, `blur % · N dp`,
  and a "Preview · dock / blur / clarity" line. `limitedReason` from `resolveDockGlass` is shown under
  the preview (only Android <12 / Low / battery saver). No new libraries.
- **STEP 6 (done)** — launch glitches L1–L6, applied on the working tree and committed task-only.
  - **L1 explicit Home load state.** New `HomeLoadState { NotStarted, Loading, Loaded, Failed }` +
    `HomeUiState.loadState` (default `NotStarted`). `loadHome` sets `Loading` at fetch start and
    `Loaded` when the row jobs join; `loadSignedInHome` sets `Loading` then `Failed`/`Loaded` by
    outcome. `YouTubeHomeScreen` now draws the skeleton for `NotStarted`/`Loading` (a cold start can
    no longer flash "Nothing to play yet") and the empty state only when `Loaded`. The `Loading`
    write inside `loadLocalHome` lives in unrelated WIP (the whole function is WIP), so it rides
    with that tree, not this commit.
  - **L2 sign-in confirmation only for an active sign-in.** New `youtubeSignInStartedAt` +
    `beginYouTubeSignIn()`; `saveYouTubeSession` only emits "Signed in to YouTube Music" when the
    login dialog actually started a sign-in this session (silent restores stay quiet). `AuralisRoot`
    calls `beginYouTubeSignIn()` from the dialog's `onSession`. The matching Clerk "Signed in" gate
    is implemented in the working tree but its host block (the Clerk timeout/OAuth rework) is WIP, so
    it is intentionally *not* in this commit.
  - **L3 mini-player art.** `ArtworkImage` keeps the previous bitmap painted under the incoming one,
    fades the new art in over 150 ms (profile permitting, otherwise instant), turns Coil's own
    crossfade off, uses a neutral dark tile (`0xFF151518`, not pure black) for the flat mini slot,
    and adds an `onArtworkSettled` callback. The restored checkpoint's art is already requested
    immediately via the existing track-derived candidate fallback, so the pill never flashes empty.
  - **L4 Top Picks card.** Text/scrim reveal is alpha-animated (150 ms) and stays hidden until the
    art has painted (or no art exists), so a title no longer sits over an empty dark card at launch.
  - **L5 first frame.** `StartupSurface` (the frame drawn while the single settings read is in
    flight) now shows the static AURALIS wordmark on the black splash background — no extra blank
    Compose frame and no new splash animation. First *real* Home frame still uses the L1 skeleton.
  - **L6 verified**: no new launch toast/banner/animation was added; the only launch additions are
    the static logo (L5) and the silence of restored-session toasts (L2).

## FINDINGS
- **GlassControls.kt** — `GlassMapping` is the one 0..1→alpha/radius table. Currently
  `MAX_BLUR_DP = 40f`, `blurDp(strength, scale)` multiplies by the tier scale and rounds to whole
  dp; `dockFillAlpha` lerps 0.55→0.10 (no 0.90 end), `LEGIBILITY_SCRIM = 0.26`,
  `DOCK_NO_BLUR_MIN_FILL = 0.22`, `DOCK_BORDER_ALPHA = 0.16`. These four dock constants are what the
  rewrite replaces. Percent helpers (`percent`, `percentToInt`) and window/scrim constants are
  shared and stay.
- **DockGlassStyle.kt** — `resolveDockGlass(settings, glass, profile, systemReason, sdkInt)` builds
  `tintAlpha` (from `dockFillAlpha`), `blurDp` (tier-scaled), `blurEnabled`, `refractionEnabled`,
  `outlines`, `shadowScale`, `downsample`, `limitedReason`. Its `reason` `when` is where the
  "Reduced by Performance Mode" string lives (line 66) — must be dropped.
- **PerformanceProfile.kt** — `High`/`Medium`/`Low` presets expose `blurEnabled`, `blurScale`
  (1.0/0.6/0.0), `blurDownsample` (1.0/0.5/1.0), `refractionEnabled` (true/false/false),
  `glassSheenEnabled`, `shadowScale`. `blurScale` is the knob that must stop scaling the radius and
  start feeding the Haze input-scale cost factor instead.
- **LiquidGlass.kt** — `glassSurface(kind, …)` routes `GlassKind.Dock` → `dockLiquidGlass(…)`
  (fill from `dockFillAlpha`, fixed rim, `glassInnerSheen`, `lensRefraction` on 33+). `liquidGlass(…)`
  is the Surface path. `LensShader`/`AGSL_LENS` (uRefract/uChroma/uSaturation) is cached via
  `remember { LensShader.create() }`. `glassRim`/`glassInnerSheen` are the rim+sheen helpers. This is
  where `dockGlassMaterial` lands. Also carries WIP popup-blur code (`DialogBackdropBlur`) — leave it.
- **AppDock.kt** — GLASS path = `GlassDock` → `GlassNavPill` (+`glassBubble` lens), `GlassCircleButton`
  → `dockControlCircle`, `DockMiniPill` → `dockSurface`. `dockSurface`/`dockControlCircle` both
  branch SOLID vs GLASS and call `applyGlassSurface(kind = Dock, …)`; this duplicated glass branch is
  what `dockGlassMaterial` collapses. Dock transparency drives these fills only.
- **GlassChrome.kt** — `GlassMiniPlayer` uses `GlassPanel`/`GlassArtwork`; separate full-width bar,
  not the dock pill. Out of scope except for reuse of the same material if it routes through Dock.
- **AuralisRoot.kt** — `GlassSettings.fromPercent(dockGlassTransparency, glassClarity, dockGlassBlur)`
  → `resolveDockGlass` → `GlassOptics(saturation = 1.12 + 0.25*clarity)`; provided via
  `LocalGlassSettings/LocalGlassOptics/LocalDockGlassStyle/LocalHazeState`. Two `hazeSource` calls
  (zIndex 0 = player background, zIndex 1 = content pane) gated by `glassHazeAllowed`.
- **SettingsSubPages.kt** — LIVE Appearance UI = `AppearanceSettingsPage` (`Destination.SettingsAppearance`).
  Glass block: `SliderSetting("Glass clarity")`, `SliderSetting("Dock transparency")`, a **switch**
  `"Live blur"` (`dockGlassBlur > 0` → 60/0) and a separate `SliderSetting("Blur strength")`, plus
  `SettingsSwitchRow("Glass outlines")`. No live preview today.
- **GlassSettings.kt** (dead code path) — `GlassSettingsScreen` (+`AppearanceSection`,
  `DockGlassPreview`) holds the richer sliders/chips/preview but has **no callers** (only
  `screens/SettingsScreen.kt`, itself uncalled). Do not resurrect; the live preview must go where the
  user actually is.
- **build.gradle.kts / libs.versions.toml** — Haze pinned `1.6.7` (`dev.chrisbanes.haze:haze`).
  `HazeStyle(backgroundColor, tints, blurRadius, noiseFactor, fallbackTint)` + `hazeEffect { blurEnabled; inputScale = HazeInputScale.Fixed(..) }` are the supported knobs (already used). No colour-filter hook → saturation/brightness must ride the AGSL lens.

### W1–W5 verification
- W1 Live Appearance UI is `SettingsSubPages.kt` `AppearanceSettingsPage` (not the dead
  `GlassSettingsScreen`) — preview work goes there.
- W2 All dock glass funnels through `glassSurface(GlassKind.Dock)` → `dockLiquidGlass`; one rewrite
  point exists.
- W3 `blurScale` currently multiplies the *radius*; requirements demand it become an *input-scale
  cost* factor only.
- W4 The "limited"/"Reduced by Performance Mode" string is in `resolveDockGlass` reason `when`.
- W5 `LensShader` is remembered (not per-frame), so adding uniforms is cheap and safe.

## STEP 7 — SELF-REVIEW (10 yes/no)
1. Blur mapping = spec (`MAX_BLUR_DP = 80f`, `radiusDp = 80 * s^1.15` rounded to 0.5 dp), targets
   (0/25/50/75/100 %) within ±2 dp — **YES** (`GlassMappingTest`: 11 tests, 0 failures).
2. Blur radius never scaled by tier; tier only (a) turns blur off in Low, (b) sets
   `HazeInputScale.Fixed(scale)` with Medium ×0.8 — **YES**.
3. "Reduced by Performance Mode" removed; "limited" text only for Android <12 / Low / battery
   saver — **YES**.
4. Dock tint alpha = {0.90, 0.66, 0.42, 0.20, 0.05} (±0.03), tint = `Canvas` + 3 % Cream, frost =
   `0.02 + 0.06*(1-s)` — **YES**.
5. Adaptive text/icon scrim = `0.16*(1-blurFraction)` (never > 0.16); old `LEGIBILITY_SCRIM` gone
   from dock/pill (kept for `GlassKind.Surface` only); 0.50 tint floor only when blur is unavailable
   (Android <12 / Low) — **YES**.
6. Backdrop saturation = `1 + 0.30*blurFraction` (max 1.30) + brightness +0.03, via AGSL
   `uSaturation`/`uBrightness` (Haze has no colour-filter hook) — **YES**.
7. Lens/dispersion on dock circles + tab pill + mini pill; API 33+ in High **and** Medium, off in
   Low; stronger rim + sheen fallback below 33; edge band 16 dp, `uRefract ≈ 0.35`,
   `uChroma ≈ 0.012`, refraction 0.6×→1.0× by blur; never samples outside the layer; reuses the
   cached `RuntimeShader` — **YES**.
8. Rim = 1 dp Cream 55 %→12 % above content, gated by "Glass outlines"; inner sheen = Cream 8 %→0
   at 40 % height; `noiseFactor = 0` for dock/pill — **YES**.
9. One `Modifier.dockGlassMaterial(style, shape)` used by dock circles, tab pill, mini pill and the
   Appearance preview; duplicated glass code deleted; preview moves live per slider and shows the
   numeric value; "limited" text stays accurate — **YES**.
10. Launch glitches: L1 explicit `HomeLoadState`, L2 active-sign-in-only toasts, L3 art
    placeholder/crossfade, L4 Top Picks reveal, L5 static-logo first frame, L6 no new
    toast/banner/animation — **YES** (L2's Clerk half lives in the WIP block; see STEP 6 note).

No item answered "no"; nothing to re-fix.

## STEP 8 — FINAL REPORT

### Files changed (task-only commits STEP 1–6)
- `GLASS_NOTES.md`
- `app/src/main/java/com/auralis/app/ui/glass/GlassControls.kt` — mapping rewrite.
- `app/src/main/java/com/auralis/app/ui/glass/DockGlassStyle.kt` — `resolveDockGlass` (reasons, refraction tier).
- `app/src/main/java/com/auralis/app/ui/glass/LiquidGlass.kt` — `dockGlassMaterial`, AGSL lens uniforms.
- `app/src/main/java/com/auralis/app/ui/components/AppDock.kt` — delegates; mini-pill call site.
- `app/src/main/java/com/auralis/app/ui/settings/SettingsSubPages.kt` — live Appearance preview.
- `app/src/test/java/com/auralis/app/ui/glass/GlassMappingTest.kt` — 11 mapping tests.
- `app/src/main/java/com/auralis/app/ui/AppViewModel.kt` — L1/L2.
- `app/src/main/java/com/auralis/app/ui/screens/YouTubeHomeScreen.kt` — L1 render gating.
- `app/src/main/java/com/auralis/app/ui/components/ArtworkImage.kt` — L3.
- `app/src/main/java/com/auralis/app/ui/home/HomeCards.kt` — L4.
- `app/src/main/java/com/auralis/app/ui/AuralisRoot.kt` — L2 hook + L5 logo frame.

### Numeric tables
Blur radius (`s` = 0–100 %): 0→**0 dp**, 25→**16 dp**, 50→**36 dp**, 75→**57 dp**, 100→**80 dp**.
Dock tint alpha: 0→**0.90**, 25→**0.66**, 50→**0.42**, 75→**0.20**, 100→**0.05**.
Frost alpha (`0.02+0.06*(1-s)`): 0→**0.08**, 50→**0.05**, 100→**0.02**.
Text scrim (`0.16*(1-s)`, cap 0.16): 0→**0.16**, 50→**0.08**, 100→**0**.
Backdrop saturation (`1+0.30*s`, max 1.30): 0→**1.00**, 100→**1.30**; brightness **+0.03**.
Haze input scale (`clamp(1/(1+r/28), 0.25, 1)`): r=0→**1.00**, r=80→**0.259**.

### Haze APIs used (1.6.7)
- `HazeStyle(backgroundColor, tints, blurRadius, noiseFactor, fallbackTint)`
- `Modifier.hazeEffect(state) { blurEnabled = …; inputScale = HazeInputScale.Fixed(scale) }`
- `HazeState`, `Modifier.hazeSource(state)`, `HazeTint`
- (No colour-filter hook exists → saturation/brightness ride the AGSL lens.)

### Could not be verified (no device/emulator allowed)
- On-device look of the blur, colour bleed, rim, and AGSL refraction (compiled + unit-tested only).
- Real blur cost of the `HazeInputScale.Fixed` values and the Medium ×0.8 factor.
- Android <12 / Low / battery-saver "limited" path and the below-API-33 rim/sheen fallback (unit
  tests run with `SDK_INT = 0`, so tiers are passed explicitly).
- The launch-frame timing itself (L5) on a cold start.
- The committed tree does not build standalone — pre-existing: it depends on WIP-only symbols
  (`YouTubeDao.allRecents()`, `GlassMapping.MENU_BACKDROP_SCRIM`, `isYouTubeAuthenticated`, etc.).
- L2's Clerk "Signed in" gate is in the working tree but not separable into a task-only commit (its
  host block is unrelated WIP).
