# AURALIS — Performance / Smoothness Report

**Version:** 1.2.0 (versionCode 2) · **Package:** `com.auralis.app`
**Date:** 2026-11-20
**Scope:** make every Performance mode (High / Medium / Low) feel smooth and "creamy" without
changing features, look, colours, layout, typography, the dock, glass, playback, YouTube,
lyrics, downloads or login.

---

## 0. Honest measurement status — READ FIRST

**No performance number in this report was measured on a device.** The machine this was built on
has **no Android SDK and no ADB** (`adb` not found; `ANDROID_HOME` / `ANDROID_SDK_ROOT` empty), so
`dumpsys gfxinfo`, frame-timing, jank percentage, and "Settings open time" could not be captured.

| Metric | Before | After | How obtained |
|---|---|---|---|
| Janky frames % | **not measured** | **not measured** | needs `dumpsys gfxinfo` on device |
| 95th percentile frame time | **not measured** | **not measured** | needs a frame-timing trace / Perfetto |
| 99th percentile frame time | **not measured** | **not measured** | needs a frame-timing trace / Perfetto |
| Settings open time | **not measured** | **not measured** | needs a macrobenchmark / `am start -W` |
| Refresh rate actually applied | **not measured** | **not measured** | needs `dumpsys SurfaceFlinger` on device |

What *was* verified here, and the only things claimed:

- `:app:compileDebugKotlin` — **exit 0**
- `:app:testDebugUnitTest` — **exit 0**, **175 tests / 0 failures / 0 errors** (was 167 → +8 new
  refresh-rate tests)
- `:app:assembleDebug` — **exit 0**
- `:app:assembleRelease` — **exit 0** (proves the baseline profile packages)
- Unit-level proof of the refresh-rate *selection maths* (see Step 1)

Everything below is either (a) a mechanical change with a stated reason, or (b) unit-tested logic.
No frame-time improvement is claimed, because it was not measured.

---

## 1. Performance modes — the requirement, restated

- **High = 120 Hz, Medium = 90 Hz, Low = 60 Hz.**
- Same features, same look, same animations in all three.
- Only the *cost* of expensive extras changes, and backdrop blur is **never switched off** while the
  user has Live blur on — only its radius scales.
- The app must **never hard-code a rate**: it reads `Display.supportedModes` and asks for the
  fastest mode **at or below** the target, so a 60 Hz phone runs all three modes at 60 Hz and a
  90 Hz phone caps High at 90 Hz.

### Single source of truth

`PerformanceProfile` (`ui/performance/PerformanceProfile.kt`) remains the one place that describes
how much visual work is allowed. The user preference feeds the resolver; the resolved profile is
the source of truth at render time (read through `LocalPerformanceProfile`).

---

## 2. What changed, step by step

### Step 1 — Refresh-rate targeting (the core deliverable)

**New file:** `ui/performance/RefreshRateController.kt`

- `RefreshRateTargeting.targetHz(tier)` → High 120, Medium 90, Low 60.
- `RefreshRateTargeting.pickModeId(modes, targetHz)` → the fastest supported mode **≤ target**
  (with a 0.5 Hz float tolerance); if the panel can't reach the target it falls back to the
  *slowest* mode so it never asks for more than the panel can do; returns `null` for no modes.
- `applyRefreshRate(activity, tier)` sets `WindowManager.LayoutParams.preferredDisplayModeId`
  (the framework's own mode-switch path) and keeps `preferredRefreshRate` consistent as a fallback
  for vendors that ignore the mode id. Both are cheap attribute writes — **no activity restart**.
  All wrapped in `try/catch` so a device that rejects the attribute keeps its current rate rather
  than crashing.
- `RefreshRateEffect(tier)` applies it: immediately, on every tier change, **on every resume**
  (the system may reset the mode while backgrounded) and **on display change** (dock/undock,
  external display, panel self-switch) via a `DisplayManager.DisplayListener`.
- Reads the panel through `activity.display` (API 30+) or `windowManager.defaultDisplay` below.

**Wired in:** `ui/AuralisRoot.kt` — `RefreshRateEffect(profile.tier)` runs **before** the startup
gate, so the request is in force while the app is still on its first frame.

> Note on the alternative: on API 30+ the platform also allows `Surface.setFrameRate(...)`. That
> needs the window's `Surface` (not directly reachable from Compose), so the standard, restart-free
> `preferredDisplayModeId` window-attribute path was used instead, with `preferredRefreshRate` as a
> belt-and-braces fallback. The unit tests cover the *selection* logic; the actual switch is
> unverified here (no device).

**New test:** `app/src/test/java/com/auralis/app/RefreshRateTargetingTest.kt` (8 tests) proves:
tier→Hz mapping; exact pick when the panel supports the target; never asking above target (60/90
panel asked for 120 → 90); a 60 Hz panel runs every tier at 60; the slowest mode wins when the
target is below every mode; float-noise tolerance (59.99 Hz counts as 60); empty modes → `null`/0;
`pickRefreshRate` matches the chosen id.

### Step 1.7 — Per-mode cost budget

`ui/performance/PerformanceProfile.kt`:

| Knob | High | Medium (before → after) | Low (before → after) |
|---|---|---|---|
| `blurEnabled` | true | true | **false → true** |
| `blurScale` | 1.0 | 0.6 → **0.7** | **0.0 → 0.4** |
| `blurDownsample` | 1.0 | 0.5 | **1.0 → 0.5** |
| `shadowScale` | 1.0 | 0.7 | 0.35 |
| `artworkMaxPx` | 2048 | 1280 | 640 |

Blur radius now scales High 100% → Medium 70% → Low 40% and **is never zero** while Live blur is
on (matches the budget). `PerformanceResolverTest.presetsReduceCostFromHighToLow` was updated to
assert the new budget.

**Follow-through so Low stays cheap even with blur on:**
- `ui/glass/DockGlassStyle.kt` — `blurEnabled` no longer gates on API ≥ 31 (Haze blurs via
  `RenderEffect` on 12+ and RenderScript below), so the reason string is honest and the material
  reads as glass in every profile. Confirmed this value only feeds the debug log and the
  `refractionEnabled` flag.
- `ui/screens/LiquidLyricsScreen.kt` and `ui/glass/GlassNowPlaying.kt` — the *full-screen* artwork
  blur (the single most expensive effect) is now gated on `backgroundBlurEnabled` (off in Low and
  Medium) rather than on Live blur, so turning blur on in Low does **not** suddenly pay for a
  full-screen capture.

### Steps 2–6 — Motion, deferral, list hygiene

- **Dock / mini-player animations** (`ui/components/AppDock.kt`): the two animated `.offset(x = …)`
  reads were moved into the deferred `.offset { IntOffset(…roundToPx(), 0) }` layout lambda, and the
  Glass lens's travel `stretch` was moved into `graphicsLayer { }`. The dock morph spring no longer
  recomposes those boxes on every frame. `androidx.compose.ui.unit.IntOffset` imported.
- **Settings switch thumb** (`ui/glass/GlassSettings.kt`): `.offset(x = thumbOffset)` → deferred
  `.offset { IntOffset(thumbOffset.roundToPx(), 0) }`.
- **Per-destination state retention** (`ui/AuralisRoot.kt`): the root already used a custom `NavState`
  with `AnimatedContent(contentKey = { it })` and **no `SaveableStateHolder`**, so switching tabs
  disposed the outgoing screen and reset scroll positions / screen-local state, forcing a full
  re-layout and artwork re-decode on return. Added `rememberSaveableStateHolder()` and wrapped the
  screen content in `SaveableStateProvider(state)` keyed on the same `NavState` the transition
  already keys on. Returning to a tab is now instant. No navigation semantics changed.
- **`contentType` on hot lazy lists**: `listContentTypes` existed in the profile but was **dead
  code** (never read). Added `ui/performance/ListContentTags.kt` (`PerformanceProfile.listContentType`)
  and applied it to the shared glass track rows/carousels (`GlassLists.kt`), Home's song list and
  carousels (`GlassScreens.kt`), and History (`GlassHistory.kt`, `HistoryScreen.kt`).
- **Stable keys on data-driven lists** (previously missing `key =`, so identity was positional):
  Library tiles grid/row + Artists (`LibraryScreen.kt`), Glass library tiles grid/row + Artists
  (`GlassScreens.kt`), Home folder list (`HomeScreen.kt`). Keys are `type`/`kind`/`id`/artist name —
  all unique within their list.

Not required here (already clean, per the code survey): no `Modifier.composed` anywhere; no lazy
list nested inside a `verticalScroll`; `collectAsStateWithLifecycle` used throughout the root; the
dock spring already applied via `graphicsLayer`.

### Step 4.1 — Artwork cache

`AuralisApp.kt`: Coil memory cache raised **15% → 25%** of the app heap (Coil's own default
ceiling). Disk cache and the size-clamped, remembered request building in `ArtworkImage.kt` were
already in place and left alone. (No ≤150 ms crossfade change was needed — crossfade is
profile-gated and already short.)

### Step 7 — Startup profile

- Added `androidx.profileinstaller:profileinstaller:1.4.1` (`gradle/libs.versions.toml`,
  `app/build.gradle.kts`) so the packaged baseline profile is installed on device.
- Added **`app/src/main/baseline-prof.txt`** — a hand-authored *seed* covering app start, theme,
  nav, dock, the tab screens, Settings and Now Playing. It is explicitly commented as a seed that
  **must be regenerated on real hardware** with the Baseline Profile Generator; it was written
  without a device and is best-effort, not measured.
  - Verified packaging: a release build (`:app:assembleRelease`, exit 0) contains
    `assets/dexopt/baseline.prof` (**13,093 bytes**) and `baseline.profm`. Debuggable builds
    deliberately omit profiles (AGP skips them for debug variants), so the debug APK has no
    baseline profile — that is expected, not a bug.
  - Format note: class rules must be bare descriptors (`Lcom/foo/Bar;`); H/S/P flags are only valid
    on method rules. The first draft used `HSPL…;` and the release parser rejected it — fixed to
    bare class rules before the release build succeeded.
- **R8/minify is left off.** The project's own note defers minification until the Room/Media3 keep
  rules are verified on device; that is unverifiable here, so shipping a possibly-broken release
  build was rejected. Keep rules were not touched.

### Step 8 — Data layer

- **Indexes** (`data/db/Entities.kt`): added `playlist_entries(playlistId, position)`,
  `playlist_yt_entries(playlistId, position)`, `play_history(playCount)`, `youtube_likes(likedAt)`
  to cover the hot `ORDER BY` clauses. Existing indexes were not duplicated.
- **Migration** (`data/db/AuralisDatabase.kt`): version **11 → 12**, new `MIGRATION_11_12` creates
  each index with `IF NOT EXISTS` — additive only, no table/column/row touched. Registered in
  `create()` and added to `MIGRATIONS`; `AuralisDatabaseMigrationTest.LATEST` bumped to 12. The
  generated schema `app/schemas/…/12.json` was produced by KSP and its index names match the
  migration exactly.
  > The migration test is an **instrumentation** test — it runs on a device and was **not executed
  > here**.
- **Off the main thread** (`ui/AppViewModel.kt`): every Room-derived `StateFlow` now runs its
  `.map { … toDomain() }` on `Dispatchers.Default` via `flowOn`. Previously `stateIn(viewModelScope)`
  collected on `Dispatchers.Main.immediate`, so whole-table `toDomain()`, `associateBy` over all
  tracks, and the liked-songs filter ran on the UI thread. Only the finished immutable list now
  crosses back to the UI thread.
- Search debounce (300 ms) and the Room `Flow` sources were already in place.

---

## 3. What was deliberately left unchanged, and why

- **Playback / YouTube / InnerTube / NewPipe / lyrics / Clerk login** — out of scope by the brief.
  `runBlocking` in `PlaybackService`/`StreamResolver`/`YouTubeRepository`, `Thread.sleep` in
  `InnerTubeClient`, and the infinite transitions in `LiquidLyricsScreen` were **left alone**; they
  are all in out-of-scope code, and pausing them was not worth a behaviour change that cannot be
  verified without a device. (Compose already stops the frame clock when the activity is stopped, so
  background infinite animations do not actually keep drawing.)
- **R8 / minification** — left off (see Step 7); cannot validate Room/Media3 keep rules without a
  device.
- **Colours, spacing, typography, icons, dock design, glass look, animations and durations** — not
  touched. Blur radius scales by mode, which is the requested budget, but the material, tint, rim,
  sheen and every animation curve/duration are identical across modes.
- **`Surface.setFrameRate`** — not used (see Step 1); the window-attribute path is equally
  restart-free and standard.

---

## 4. Verification

| Command | Result |
|---|---|
| `.\gradlew.bat :app:compileDebugKotlin` | exit 0 |
| `.\gradlew.bat :app:testDebugUnitTest` | exit 0 — **175 tests, 0 failures, 0 errors** |
| `.\gradlew.bat :app:assembleDebug` | exit 0 |
| `.\gradlew.bat :app:assembleRelease` | exit 0 (unsigned; confirms the baseline profile is packaged) |

**Debug APK**
- Path: `C:\Users\Dipanjan\Desktop\AURALIS-1.2.0-smooth.apk`
- Size: **37,582,922 bytes**
- SHA-256: `1286AB5B7388E22473D26F7F076957EB94890BF6DB90B11A37A902F3CB400104`

**Release APK (verification only, unsigned)**
- `app/build/outputs/apk/release/app-release-unsigned.apk` — 27,205,357 bytes, contains the packaged
  baseline profile. No `keystore.properties` was present, so it is unsigned; this build exists only
  to prove the baseline profile and release pipeline work.

> The build emits a pre-existing `D8: An error occurred when parsing kotlin metadata … newer Kotlin
> than R8` **warning** (Kotlin 2.4.21 vs this AGP's bundled R8). It is a warning only — the build
> succeeds and the APK is produced.

### Files touched by this task

New: `ui/performance/RefreshRateController.kt`, `ui/performance/ListContentTags.kt`,
`app/src/main/baseline-prof.txt`, `app/src/test/.../RefreshRateTargetingTest.kt`.

Changed: `ui/performance/PerformanceProfile.kt`, `ui/AuralisRoot.kt`, `ui/AppViewModel.kt`,
`AuralisApp.kt`, `ui/glass/DockGlassStyle.kt`, `ui/glass/GlassNowPlaying.kt`,
`ui/screens/LiquidLyricsScreen.kt`, `ui/glass/GlassLists.kt`, `ui/glass/GlassScreens.kt`,
`ui/glass/GlassHistory.kt`, `ui/screens/HistoryScreen.kt`, `ui/screens/LibraryScreen.kt`,
`ui/screens/HomeScreen.kt`, `ui/components/AppDock.kt`, `ui/glass/GlassSettings.kt`,
`data/db/Entities.kt`, `data/db/AuralisDatabase.kt`,
`app/src/androidTest/.../AuralisDatabaseMigrationTest.kt`, `app/src/test/.../PerformanceResolverTest.kt`,
`app/build.gradle.kts`, `gradle/libs.versions.toml`.

---

## 5. How to verify on a device (when one is available)

1. Install `AURALIS-1.2.0-smooth.apk`.
2. `adb shell dumpsys SurfaceFlinger | findstr /i "refresh"` while the app is foregrounded and the
   mode is switched Settings → Performance: expect 120 / 90 / 60 (clamped to the panel).
3. `adb shell dumpsys gfxinfo com.auralis.app reset` → scroll Home/Library → `adb shell dumpsys
   gfxinfo com.auralis.app` for janky % and 95th/99th percentile frame times.
4. Regenerate the baseline profile on device and commit it (replacing the seed).
5. Run `.\gradlew.bat :app:connectedDebugAndroidTest` to execute `AuralisDatabaseMigrationTest`
   (validates the 11 → 12 index migration).
