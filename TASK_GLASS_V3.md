# GLASS-BACKDROP-V3 — VERBATIM SPEC (from the user)

Branch: `glass-backdrop-v3` (created from `glass-opacity-fix` @ `4a9be27`). This file is the
source of truth for the task; keep it in sync if the user amends the spec.

> ROLE: Senior Android engineer (Kotlin, Jetpack Compose, graphics). Branch: glass-dock-v2.
> No ADB, SDK tools or emulator. I build and test myself. Do not guess. Read first, then edit.
> Never leave half-wired code. Keep AGENT_NOTES.md with DO / DON'T / DONE / NEXT and update it
> after every step. Re-read it whenever your context is summarised.

## THE PROBLEM TO SOLVE
Our glass settings (Dock transparency, Glass clarity, Glass outlines, blur) do not really drive
the UI. A setting changes in one place, a different piece of code keeps its own copy of the value,
and the screen does not change. Fix this at the root: ONE source of truth, ONE modifier, and EVERY
glass surface reads from them. No surface may have its own private blur, alpha or outline number.

## DECISIONS ALREADY MADE (do not revisit)
- The "Live blur" slider is removed. Blur is a fixed constant.
- If a separate "Blur strength" slider exists, remove it too. Blur is fixed.
- Remaining user settings: Dock transparency, Glass clarity, Glass outlines (on/off), plus the
  existing Performance mode (Low / Medium / High).
- Glassmorphic theme only. Normal theme must not change or pay any cost.
- Glass engine: Kyant0/backdrop (Apache-2.0). Do NOT copy BitChord or Echo-Music code (GPL-3.0).
  Write our own glue code.

## STEP 0 - READ AND AUDIT (no edits yet)
0.1 Read the whole project (GlassTokens, dockGlassMaterial, dock, mini-player pill, Search circle,
    selection indicator, Appearance settings screen, settings repo/store, MainViewModel/
    SettingsViewModel, root screen hosting pages + dock, every Dialog/AlertDialog/DropdownMenu/
    Popup/ModalBottomSheet, every Settings card/top bar that looks like glass).
0.2 Grep for: "liveBlur", "blurStrength", "blur(", "Modifier.blur", "RenderEffect", "glassClarity",
    "dockTransparency", "glassOutline", "outline", "alpha =", "hazeChild", "haze",
    "BlurredEdgeTreatment", "GlassTokens", "dockGlassMaterial", "Color.White.copy",
    "Color.Black.copy" — list every hit with file:line.
0.3 Build a consumer matrix in AGENT_NOTES.md: SURFACE | file | what it reads today | which
    setting it ignores.
0.4 Note the Compose BOM, Kotlin, minSdk, compileSdk, and how settings are stored.
0.5 Read the Kyant0 sources/README; verify the real API names and signatures of the version added.
    Do not trust this prompt for signatures.

## STEP 1 - ADD THE LIBRARY
1.1 Add io.github.kyant0:backdrop (latest stable compatible with our Compose BOM).
1.2 If the prebuilt AAR is binary-incompatible with our Compose version, vendor the library source
    into our own package, keeping its Apache-2.0 header on each file.
1.3 Add the attribution to our licences screen / README.
1.4 Confirm the project compiles before continuing. Commit.
1.5 Requirements: blur/colour effects need API 31+. Lens refraction + chromatic dispersion need
    API 33+. Handle lower APIs with fallbacks (see Step 5).

## STEP 2 - THE SINGLE SOURCE OF TRUTH
2.1 Create GlassConfig (immutable data class), the ONLY thing glass code may read:
      dockTransparency : Int   // 0..100, user setting
      glassClarity     : Int   // 0..100, user setting
      outlinesEnabled  : Boolean // user setting
      performance      : PerformanceMode // Low / Medium / High, existing setting
      sdkInt           : Int
      isGlassCapable   : Boolean // sdk >= 31
      hasLens          : Boolean // sdk >= 33 and performance != Low
2.2 Create GlassConfigRepository (reuse our existing settings storage):
      val config: StateFlow<GlassConfig>
      fun setDockTransparency(v: Int) / setGlassClarity(v: Int) / setOutlines(b: Boolean)
    It combines the stored values + performance mode into ONE StateFlow.
    Clamp every value to its range on read AND on write.
2.3 Expose it to the UI ONCE at the root:
      val config by repo.config.collectAsStateWithLifecycle()
      CompositionLocalProvider(LocalGlassConfig provides config) { ... }
    Use compositionLocalOf (NOT staticCompositionLocalOf), so a change recomposes only the readers.
    Nothing below the root may collect the setting on its own.
2.4 Fixed constants live in GlassTokens only (one place):
      BLUR_DP_HIGH = 10, BLUR_DP_MEDIUM = 10, BLUR_DP_LOW = 16
      LENS_HEIGHT_DP = 24, LENS_AMOUNT_DP = 24
      SATURATION = 1.5
      EFFECT_RESOLUTION_HIGH = 0.5, MEDIUM = 0.33, LOW = 0.25
      RIM_WIDTH_DP = 1, RIM_ALPHA_TOP_LEFT = 0.55, RIM_ALPHA_BOTTOM_RIGHT = 0.12
      TINT_COLOR_DOCK  = Vanilla Cream / Midnight Indigo blend, very light
      TINT_COLOR_PANEL = Midnight Indigo
    No literal blur, alpha or outline numbers anywhere else in the project.
2.5 Mapping functions (pure, in one file, unit-tested):
      dockTintAlpha(dockTransparency): Float
         t = dockTransparency/100f ; alpha = 0.02f + 0.98f * t.pow(1.4f)
         0 => almost clear but blur/lens/rim still visible; 100 => opaque.
         NEVER add a black or grey tint at any value.
      panelTintAlpha(glassClarity): Float
         clarity 100 = most see-through, 0 = most solid:
         alpha = 0.92f - 0.80f * (glassClarity/100f)  (range 0.12 .. 0.92)
      outlineAlpha(outlinesEnabled): if off => 0 (no rim, no hairline anywhere)
      blurDp(performance), effectScale(performance)
    Write unit tests: endpoints 0/50/100, monotonic, clamped, outlines off => 0.
2.6 Remove the Live blur (and Blur strength) setting everywhere: UI row, string, preference key,
    ViewModel field, repository field, every read. An old saved key must be ignored without a
    crash. No dead code, no unused resources. Commit.

## STEP 3 - THE BACKDROP SOURCE (most important to get right)
3.1 Create the backdrop: val appBackdrop = rememberLayerBackdrop(onDraw = ...)
3.2 The recording lambda MUST first draw an opaque background, then the content:
      drawRect(appBackgroundColor)   // our pure black
      drawContent()
    If the layer is transparent anywhere, glass shows sharp, unblurred text through it. This is
    the cause of our old "text shows through the dock" bug.
3.3 Apply Modifier.layerBackdrop(appBackdrop) to the container that holds ALL page content: Home,
    Explore, Library, Search, Settings, and the user's chosen player/app background (photo
    wallpaper must be inside the recorded layer).
3.4 The dock, mini-player and Search circle must be SIBLINGS drawn above that container, never
    children of it, or they sample themselves.
3.5 Provide appBackdrop through a CompositionLocal (LocalAppBackdrop).
3.6 Record the layer only when theme == Glassmorphic AND config.isGlassCapable. Normal theme: no
    layer, no cost.
3.7 Record once per frame. No allocations in the draw lambda.

## STEP 4 - THE ONE GLASS MODIFIER
4.1 Write ONE modifier, e.g. Modifier.auralisGlass(shape, role). Every glass surface in the app
    uses this modifier and nothing else. enum GlassRole { Dock, Panel }.
4.2 It reads ONLY LocalGlassConfig, LocalAppBackdrop and GlassTokens.
4.3 Core call (check real signatures in the version you added):
      drawBackdrop(backdrop = appBackdrop, shape = { shape }, effects = { colorControls(...);
        blur(blurPx); if (config.hasLens) lens(...) }, highlight = ..., shadow = ...,
        onDrawSurface = { drawRect(tintColor.copy(alpha = tintAlpha)) }, backdropScale = effectScale)
4.4 Every lambda/Shape/Color that depends on config must be remembered with the config values as
    keys. A missing key is exactly how "the setting changes but the UI does not" happens.
4.5 Slider drags update the glass LIVE on the same frame. No debounce that hides the change.
4.6 Never use more than one blur implementation. Delete Haze/other blur helpers from glass surfaces
    once they use this modifier (keep Haze only if used elsewhere for non-glass reasons; list them).

## STEP 5 - FALLBACKS BY CAPABILITY
 API 33+ and High/Medium : blur + saturation + lens + dispersion + rim + shadow
 API 33+ and Low         : blur + rim + shadow (no lens, blur 16dp, scale 0.25)
 API 31-32               : blur + rim + shadow (no lens)
 API < 31                : solid translucent tint (panel/dock alpha) + hairline, no crash, no
                           backdrop recording
In every mode the content behind the glass must be blurred, never sharp, and text behind must be
unreadable through the dock and pill.

## STEP 6 - APPLY IT TO EVERY SURFACE (use the consumer matrix from Step 0)
6.1 Dock pill, mini-player pill, Search circle -> auralisGlass(role = Dock). The selected-tab lens
    is a lighter glass pill that slides between tabs and refracts the dock glass. Build it with the
    same backdrop idea. It must follow the same outline setting and be smooth, interruptible and
    120 Hz friendly.
6.2 Menus, dialogs, sheets: often live in SEPARATE WINDOWS and cannot sample the main window's
    layer. For Glassmorphic, do ONE of the following, in preference order, consistently:
      a) Render as in-tree overlays inside the root Box (scrim + glass container above the page, as
         siblings of the layerBackdrop container) so auralisGlass(role = Panel) samples the real page.
      b) If not practical, keep the window but blur the page behind it while open (Modifier.blur on
         the page container, API 31+) and give the card a high panel tint alpha.
    Targets that MUST be fixed: Library "+" menu, Now Playing three-dot menu, "Connect YouTube Music"
    dialog, every AlertDialog, every bottom sheet, Audio output sheet, Queue sheet, History/Settings
    pickers.
6.3 Settings cards and top bars that look like glass -> role = Panel.
6.4 Text/icons on glass: cream colour, enough contrast on bright tiles (subtle text shadow if
    needed). Nothing may overlap the mini-player title or the nav labels.
6.5 After this step the consumer matrix must show EVERY glass surface using auralisGlass. Zero
    surfaces with private blur/alpha numbers.

## STEP 7 - THE SETTINGS SCREEN (Appearance, Glassmorphic UI)
7.1 Rows: Dock transparency (slider 0-100 + live number), Glass clarity (slider 0-100 + live
    number), Glass outlines (switch), Performance mode. Remove Live blur and Blur strength rows.
7.2 Each control writes through GlassConfigRepository only. No local copies, no "apply" button.
7.3 Add a LIVE PREVIEW card at the top of Appearance using the REAL auralisGlass modifier (one
    Dock-role pill and one Panel-role card) over a colourful image/gradient strip. Updates live.
7.4 Slider semantics:
      Dock transparency: only the dock/mini-player/Search circle fill. 0% = fully see-through
        (blur, lens and rim still visible). ~50% = translucent glass pane. 100% = opaque.
        Never a black or grey wash.
      Glass clarity: all Panel-role surfaces app-wide. Higher = more see-through.
      Glass outlines: removes/adds the rim highlight and hairline on EVERY glass surface.
7.5 Remove the old accent-colour and light/bright-mode options from the Glass settings if they
    still exist (they never worked). Dark is the fixed mode.

## STEP 8 - MIGRATION AND SAFETY
8.1 First launch after update: read old keys if present, map them once (old glass clarity / dock
    transparency carry over, clamped), delete the old keys.
8.2 Defaults: dockTransparency 35, glassClarity 60, outlines ON, performance = whatever the user
    already has.
8.3 No crash if a value is missing, out of range, or the device lacks RenderEffect.
8.4 Normal theme, lyrics, Now Playing layout, Home content, networking, YouTube/Spotify code, Clerk
    login, player logic, equalizer, downloads: DO NOT TOUCH. Do not rename packages. Do not add
    dependencies other than the backdrop lib.

## STEP 9 - PERFORMANCE
9.1 Never blur the whole screen. Only the dock, mini-player, Search circle, and open panels.
9.2 Remember Shapes, Colors, Brushes. No allocations inside draw lambdas.
9.3 Targets: High = 120 Hz, Medium = 90 Hz, Low = 60 Hz. If the frame budget is blown, lower the
    effect resolution scale before removing the effect.
9.4 Do not recompose the whole screen when a slider moves; only glass readers.

## STEP 10 - DIAGNOSTICS (because I cannot attach ADB)
10.1 Whenever GlassConfig changes, write ONE line to our in-app Logs screen:
     "GlassConfig dock=35 clarity=60 outlines=on perf=High lens=true blurDp=10".
10.2 On launch, log whether the backdrop layer is recording and its opacity mode ("layer opaque=true").
10.3 If a surface is created without the glass modifier in Glassmorphic theme, log a warning with
     the file name once (debug builds only).

## STEP 11 - TESTS AND SELF-REVIEW
11.1 Unit tests for every mapping function (2.5) and for the migration.
11.2 Self-review checklist: no liveBlur/blurStrength refs; no literal blur/alpha/outline numbers
     outside GlassTokens/mappings; recording lambda draws opaque bg first; dock/mini-player/Search
     outside the layerBackdrop container; every remember(...) depending on config has right keys;
     every Dialog/Popup/Menu/Sheet handled per 6.2; outlines OFF removes rim+hairline on ALL surfaces
     incl. selection lens; dock transparency never adds black/grey; clarity affects all Panel surfaces
     and not the dock; no crash on API 31/32/33+ and <31 fallback is tint only; Low has no lens;
     Normal theme unchanged and not recording a layer; licence attribution added; project compiles
     with no new warnings.
11.3 Commit one commit per step.

## STEP 12 - FINAL REPORT (short)
files changed; library version and whether vendored; final constants and values; completed consumer
matrix; every assumption; anything not finished and why; then a TEST LIST for the phone.

## RULES
- If something conflicts with the real code, trust the code, note it in AGENT_NOTES.md, and pick the
  safest option. Do not stop to ask.
- If a surface cannot be converted safely, convert everything else and list it clearly in the final
  report. Never leave it silently different.
- Tune only these three numbers if the look is off: BLUR_DP, LENS_AMOUNT_DP and the tint mapping.

---

# APPENDIX - VERIFIED Kyant0/backdrop API (2.0.1, from the published sources jar)

> SUPERSEDED for implementation: we ship **vendored backdrop 1.0.0** (see `AGENT_NOTES.md` §0.5 and
> `NOTICE`). 2.0.1 transitively requires Compose 1.12 / compileSdk 37 / AGP 9.1, which this project
> cannot use; every published AAR needs Compose ≥ 1.9.4. The 2.0.1 notes below remain for reference;
> the exact 1.0.0 signatures we use are in `AGENT_NOTES.md` §0.5.

Artifact: `io.github.kyant0:backdrop:2.0.1` (Apache-2.0). URL: github.com/Kyant0/AndroidLiquidGlass.
Transitive: `io.github.kyant0:shapes:1.2.1`, `org.jetbrains.annotations:26.1.0`, and JB Compose
1.12.0 android artifacts (`org.jetbrains.compose.ui:*:1.12.0`).
Kotlin: built with 2.4.10. Capability (androidMain Platform.kt):
`isRenderEffectSupported()` = SDK >= 31 (S); `isRuntimeShaderSupported()` = SDK >= 33 (Tiramisu).

Confirmed signatures (com.kyant.backdrop):
- `@Composable rememberLayerBackdrop(graphicsLayer = rememberGraphicsLayer(), onDraw: ContentDrawScope.() -> Unit = { drawContent() }): LayerBackdrop` (package `com.kyant.backdrop.backdrops`)
- `Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier`
- `Modifier.drawBackdrop(backdrop: Backdrop, shape: () -> Shape, effects: BackdropEffectScope.() -> Unit, highlight: (() -> Highlight?)? = { Highlight.Default }, shadow: (() -> Shadow?)? = { Shadow.Default }, innerShadow: (() -> InnerShadow?)? = null, layerBlock: (GraphicsLayerScope.() -> Unit)? = null, exportedBackdrop: LayerBackdrop? = null, onDrawBehind: (DrawScope.() -> Unit)? = null, onDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit = { it() }, onDrawSurface: (DrawScope.() -> Unit)? = null, onDrawFront: (DrawScope.() -> Unit)? = null): Modifier`
- `Modifier.drawPlainBackdrop(...)` — same minus highlight/shadow/innerShadow.
- Effects (`com.kyant.backdrop.effects`): `blur(radius: Float /*px*/, edgeTreatment: TileMode = Clamp)`, `colorControls(brightness=0f, contrast=1f, saturation=1f)`, `lens(refractionHeight: Float, refractionAmount: Float, depthEffect=false, chromaticAberration=false)`, `colorFilter(ColorFilter)`, `opacity(alpha)`, `vibrancy()`.
- `Highlight(width: Dp = 0.5.dp, blurRadius: Dp = width/2, alpha: Float = 1f, style: HighlightStyle = Default)`; `HighlightStyle.Default/Plain/Ambient`.
- `Shadow(radius: Dp = 24.dp, offset: DpOffset = (0, radius/6), color: Color = Black@0.1, alpha: Float = 1f, blendMode)`.

CONFLICTS with the spec (trust the code):
1. `drawBackdrop` has **no `backdropScale`** parameter → `EFFECT_RESOLUTION_*` / `effectScale()` has no
   direct API. Safest: keep effects at full resolution (document); optional later via `layerBlock`.
2. `shape`, `highlight`, `shadow` are **lambdas** (`() -> Shape`, `() -> Highlight?`, `() -> Shadow?`).
3. `lens` throws unless the shape is `RoundedRectangularShape` / `AbsoluteRoundedCornerShape` /
   `CornerBasedShape` → use `RoundedCornerShape`/`CircleShape` (CornerBasedShape) everywhere.
4. `blur()` radius is in **px** (convert dp→px via the effect scope's density), not dp.
5. The AAR targets JB Compose 1.12.0 vs our androidx BOM 2025.06.01 → binary compatibility must be
   tested (Step 1.2 allows vendoring if incompatible).
