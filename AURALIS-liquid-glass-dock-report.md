# AURALIS — Liquid-Glass Dock (v1.2.0, debug)

**Scope:** Rebuild the Glassmorphic dock pill, Search circle and mini-player pill with a real
"liquid glass" material. Layout, items, icons, labels, animations, the Normal theme and the
Midnight-Indigo / Vanilla-Cream palette are untouched.

**Artifact:** `C:\Users\Dipanjan\Desktop\AURALIS-liquid-glass-dock-v1.2.0.apk`

| Field | Value |
|---|---|
| Package | `com.auralis.app` (versionCode 2, versionName 1.2.0) |
| Size | 37,903,252 bytes |
| SHA-256 | `6AD8242257B5E5A1B69932540A21A20E7FA2BA13F97009091599B98AEBD875D5` |
| Build type | debug |
| Build result | `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:assembleDebug` → **BUILD SUCCESSFUL** (exit 0) |
| Unit tests | 167 tests / 33 suites / **0 failures, 0 errors, 0 skipped** (unchanged from baseline) |

## What was wrong before (screenshot 1)

The grey wash was not a blur that failed silently — it was **two non-blur layers stacked on top of
the artwork**:

1. `GlassMapping.LEGIBILITY_SCRIM = 0.26f` (a flat grey scrim), and
2. Haze's `fallbackSurface`/`noiseFactor = 0.04f` with a **cream** fill at up to `0.55` alpha.

On top of that, two SDK gates meant the backdrop was never sampled at all below Android 12:
`AuralisRoot.glassHazeAllowed` required `SDK_INT >= 31`, and Haze's own default
(`HazeDefaults.blurEnabled` → `isBlurEnabledByDefault()` → `SDK_INT >= 31`) disables blur and draws
`ScrimBlurEffect` instead of a real blur.

## What changed

### 1. `ui/glass/LiquidGlass.kt` — new dock-only material `dockLiquidGlass(...)`

`Modifier.glassSurface` now branches on `kind`:

- `GlassKind.Surface` → **unchanged `liquidGlass(...)` path** (cards, rows, chips, dialogs, Now
  Playing, Settings). Same fill, scrim, blur and hairline as before.
- `GlassKind.Dock` (dock pill, mini-player, Search circle, dock control circles) → new
  `dockLiquidGlass(...)`.

`dockLiquidGlass` layers, bottom → top:

1. **Soft shadow** — owned by the caller (`dockSurface` / `dockControlCircle`).
2. **Haze blur**, clipped to the surface shape, sampling the content pane behind it. `tints` are
   empty and `noiseFactor = 0f` so Haze contributes *only* the blurred backdrop — no grey film.
   `blurEnabled = true` is set explicitly in the effect scope so Haze actually blurs instead of
   falling back to a scrim.
3. **Faint indigo fill** — `GlassTokens.Indigo.copy(alpha = fill)`. Colour is constant; alpha is the
   only thing the Dock-transparency slider moves. No white/cream fill anywhere in this family.
4. **Inner top highlight + one curved specular sheen** (`glassInnerSheen`, size-cached, two cheap
   gradients, one layer).
5. **Thin gradient rim** (`glassRim`) — bright on the top-left and bottom-right corners, near-invisible
   on the other diagonal. Fixed optical alpha `GlassMapping.DOCK_BORDER_ALPHA = 0.16f`, so the rim does
   not fade with the transparency slider.

Only what is **behind** the surface is blurred; icons, labels and artwork are children and stay crisp.

### 2. `ui/components/AppDock.kt`

- `dockSurface` glass shadow softened: `14dp/0.40/0.60` → `10dp/0.20/0.28`.
- `dockControlCircle` shadow softened (`6dp/0.30/0.45` → `5dp/0.18/0.24`) and gained
  `blurBackdrop` — `false` for the mini-player play/next circles, which sit *inside* the already
  blurred pill, so we don't sample the backdrop a second time (no overdraw). The Search circle and
  free-standing dock circles keep `blurBackdrop = true`.
- **Active-tab lens is now a clear glass bubble**, not a solid cream capsule: new
  `Modifier.glassBubble` (faint cream 0.10 lens + gradient rim + inner sheen, **no second blur**),
  softer shadow, and the active icon/label tint changed from `Indigo` on cream to `Cream` on glass so
  contrast holds on the dark translucent pill.
- Removed the duplicated radial glow `Box` inside `GlassCircleButton` (the material now carries the
  sheen), avoiding a double highlight.

### 3. `ui/glass/GlassControls.kt` / `DockGlassStyle.kt`

- Dock fill endpoints: `0% → 0.55` (dense) … `100% → 0.10` (see-through); colour always indigo.
  (Previous floor was `0.04`; raised so labels stay legible over bright art at max see-through.)
- Added `DOCK_BORDER_ALPHA = 0.16f` (fixed dock rim) and `DOCK_NO_BLUR_MIN_FILL = 0.22f` (denser
  indigo when no backdrop blur is available, so the dock still reads as smoked glass instead of
  washing out).

### 4. `ui/AuralisRoot.kt`

- Removed the `Build.VERSION.SDK_INT >= 31` gate from `glassHazeAllowed`. Haze 1.6.7 uses
  `RenderEffect` on API 31+ and `RenderScriptBlurEffect.createOrNull(...)` on API 26–30 (verified by
  decompiling `HazeEffectNode_androidKt.updateBlurEffectIfNeeded`), so the dock can now blur on every
  supported API level. The source is still gated by `themeStyle == GLASSMORPHIC && profile.blurEnabled`.

## Settings semantics (as requested)

| Control | Effect now |
|---|---|
| **Dock transparency** | Moves **only** the dock family's fill alpha (0.55 dense → 0.10 see-through). Colour never changes. Rim is fixed. |
| **Live blur** | Sets the real Haze blur radius on dock / Search circle / mini-player (0 → 40dp, scaled by the performance profile). |
| **Glass clarity** | Still drives the hairline + fill of **every other** surface only (`GlassKind.Surface`). |

## Honest verification status

**Verified (on this machine, in code and build):**

- The project compiles for `debug`.
- All 167 unit tests pass with no regression.
- The debug APK builds and is signed/packaged; size and SHA-256 recorded above.
- By reading the compiled Haze 1.6.7 bytecode: on API 31+ the dock uses `RenderEffectBlurEffect`; on
  API 26–30, with `blurEnabled = true`, it uses `RenderScriptBlurEffect`, falling back to a scrim
  only if RenderScript cannot be created.
- The `GlassKind.Surface` path is byte-for-byte the same code it was before this change.

**NOT verified (no Android runtime / ADB on this Windows machine):**

- How the material actually *looks* on a device — the real blur, the indigo tint over album art, the
  rim/sheen, and the clear-glass active lens. I cannot see rendering here.
- Text contrast over bright and dark album art on real hardware.
- Smoothness at 120 Hz — "one blur layer per surface, no overdraw" is a **design property** I
  implemented (nested mini-player controls skip blur; the lens carries no blur), not a measured
  frame-rate claim.
- Behaviour on API 26–30 devices with the relaxed Haze gate: RenderScript blur works but is slower
  than RenderEffect, and I could not benchmark it. If an older device feels heavy, the Live-blur
  slider and the performance profile still cap/disable it.

I have **not** claimed the visual result matches the target screenshot — that needs your eyes on the
device. Install `AURALIS-liquid-glass-dock-v1.2.0.apk` and check the dock over the app background, an
uploaded photo and album art.

## Risks / notes for testing

- If the dock looks *too* dense, lower **Dock transparency** (toward 100%); if too transparent,
  raise it toward 0%. Indigo alpha range is 0.10–0.55.
- If it looks flat (no blur), check **Live blur** is above ~10% and the active theme is
  Glassmorphic; below Android 12 the blur runs through RenderScript.
- The Search circle and free-standing dock circles each blur the backdrop; the mini-player's
  play/next circles deliberately do not (they sit on the blurred pill).
