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
- STEP 2 mapping + unit tests. STEP 3 `dockGlassMaterial`. STEP 4 apply to dock/pill/circles.
- STEP 5 preview + labels. STEP 6 launch glitches L1–L6. STEP 7 self-review. STEP 8 final report.

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
