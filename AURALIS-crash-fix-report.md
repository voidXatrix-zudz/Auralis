# AURALIS crash-on-launch fix + diagnostics — final report

**App:** `com.auralis.app` (AURALIS) · versionName `1.2.0`, versionCode `2`
**Project:** `C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL`
**Build config:** `compileSdk=36`, `minSdk=26`, `targetSdk=35`, core-library desugaring ON, Room 2.7.2
**Untouched (per task rules):** Clerk behavior, the dock, Glass/Normal look, playback logic, YouTube logic, package/app ID, `gradle/libs.versions.toml`. No feature/UI changes except the crash screen.
**Debug APK (same application ID):** `C:\Users\Dipanjan\Desktop\AURALIS-crash-fix-v1.2.0.apk`
- 34,682,584 bytes · SHA-256 `AE3A0732A914B7FD29F2E15D47EA7A1B0AF058BC9BB0DFA8C2E802D16EF57A1D`

> No secret is logged anywhere in this work. Crash reports contain only a stack trace + coarse device facts.

---

## 1. Startup map (what runs, and what is now guarded)

`Application.attachBaseContext` → **`CrashHandler.install(this)`** runs *first* (before AppLog, Keystore, Room, Clerk). The `:crash` process is detected here and skips all init.

`Application.onCreate` — every step is wrapped in `runStep(name) { … }`, which on failure logs to `AppLog` **and** writes a line to `filesDir/crash/startup-warnings.txt`, then continues degraded (never silently swallowed):

| # | Step | Failure behavior if wrapped step throws |
|---|------|------------------------------------------|
| 1 | `AppLog.init` | logged, continue |
| 2 | `YouTubeSessionLog.init` | logged, continue |
| 3 | `configureImageLoader()` | logged, continue (no artwork) |
| 4 | `AppContainer(this)` | logged; app runs with a degraded/absent container |
| 5 | `clerk.initialize()` | logged; local-only playback still works |
| 6 | `DownloadGraph.container =` | logged, continue |
| 7 | `DownloadManager.ensureChannel()` | logged, continue |
| 8 | `SpotifyGraph.container =` | logged, continue |
| 9 | `youtube.warmUp()` | logged, continue |
| 10 | `autoScanner.start()` | logged, continue |
| 11 | `AutoScanWorker.schedule()` | logged, continue |

`MainActivity.onCreate` → `enableEdgeToEdge()` → `clerkAuth.attachActivity` (guarded) → `requestNotificationPermissionIfNeeded` (guarded) → **`container.databaseFailureOrNull()`** → if the DB cannot open, shows `DatabaseRecoveryScreen`; else `AuralisRoot(viewModel)`.
`onStart` connects the media controller (`future.get()` guarded). `onResume`/`onStop` → `autoScanner`/`viewModel`/`playback.attach` all guarded with `runCatching`.

Crash path: uncaught exception → `CrashHandler.handle` writes `filesDir/crash/crash-<ts>.txt` (full stack trace incl. every "Caused by" + device block), prunes to the newest 5, applies a 10 s crash-loop guard, launches `CrashReportActivity` (separate `:crash` process, plain Views) unless looping, then chains the previous handler so the platform still kills/restarts normally.

---

## 2. Version-specific risk table

Severity legend: **E** = lint error, **W** = warning. All required categories verified 0 after the fix.

| # | Risk (API) | Category | File:line | Fix |
|---|-----------|----------|-----------|-----|
| 1 | `MediaMetadataRetriever.METADATA_KEY_SAMPLERATE` (31) | InlinedApi | `data/sources/MusicScanner.kt:223` (const `:381`) | inlined literal `SAMPLE_RATE_KEY = 38` |
| 2 | `MediaStore.VOLUME_EXTERNAL_PRIMARY` (29) | InlinedApi | `data/sources/MusicScanner.kt:288` (const `:381`) | inlined literal `"external_primary"` |
| 3 | `MediaStore.VOLUME_EXTERNAL` (29) | InlinedApi | `data/lyrics/LyricsRepository.kt:386`; `data/sources/MusicScanner.kt:255` | inlined literal `"external"` |
| 4 | `AudioDeviceInfo.TYPE_BLE_HEADSET` (31), `TYPE_BLE_BROADCAST` (33) | InlinedApi | `ui/components/BluetoothOutput.kt:21-22`, guarded `:86-87` | inlined numeric consts `26` / `30` + `SDK_INT` guards |
| 5 | `MediaFormat.getInteger(k,d)` / `getLong(k,d)` (29) | NewApi | `domain/audio/AudioAnalyzer.kt:81-84,169-170,188-189` | new `intOr`/`longOr` helpers (`:435-439`) using API-16 single-arg getters |
| 6 | `MediaStore.createWriteRequest` (30) | NewApi | `ui/screens/Stage4Screens.kt:448` | added `SDK_INT >= R && uri.startsWith("content://media/")` guard |
| 7 | `ValueAnimator.getDurationScale()` (33) | NewApi | `ui/glass/GlassMotion.kt:23` | read `Settings.Global.ANIMATOR_DURATION_SCALE` from API 26 up |
| 8 | `android.graphics.RuntimeShader` (33) | NewApi | `ui/glass/LiquidGlass.kt:256,276` (+ guards `:219,231`) | `@RequiresApi(TIRAMISU)` + `SDK_INT` guard |
| 9 | Notification post without runtime permission (33) | MissingPermission | `core/Notifications.kt:17`; callers `data/download/DownloadManager.kt:269`, `data/metadata/AutoTagManager.kt:185`, `data/spotify/SpotifyImportManager.kt:307` | new `notifyIfPermitted()` checks `POST_NOTIFICATIONS` + `areNotificationsEnabled()` |
| 10 | `SessionResult.RESULT_ERROR_*` ints passed where `@SessionError` expected | WrongConstant | `playback/PlaybackService.kt:361,400` | use `SessionError.ERROR_BAD_VALUE` / `ERROR_NOT_SUPPORTED` (identical values) |
| 11 | `PendingIntent` missing mutability flag (31) | UnspecifiedImmutableFlag | `playback/PlaybackService.kt:243`; `data/download/DownloadManager.kt:242` | already `FLAG_IMMUTABLE` — verified, no change needed |
| 12 | Foreground-service type required (34) | ForegroundServiceType | `DownloadManager.kt:255`, `AutoTagManager.kt:169`, `SpotifyImportManager.kt:292`; manifest `:31` (`mediaPlayback`), `:53` (`dataSync`) | already typed — verified |
| 13 | `registerReceiver` export flag (34) | — | `ui/performance/DevicePerformanceMonitor.kt:35-39` | already `ContextCompat.RECEIVER_NOT_EXPORTED` — verified |
| 14 | Scoped storage (30) | ScopedStorage | MediaStore access is via `content://` + SAF tree URIs; write path guarded by #6 | 0 findings |
| 15 | ProtectedPermissions / `Range` / `ResourceType` / `DeprecatedApi` | — | — | 0 findings after fix |
| 16 | 16 KB page-size native alignment (15+) | — | all native libs | verified 16 KB-aligned (see §5) |
| 17 | Edge-to-edge enforcement (35/36) | — | `MainActivity.onCreate` uses `enableEdgeToEdge()`; `values-v27/themes.xml` sets `windowLayoutInDisplayCutoutMode` | handled |

**Java 21 / API 9–17 usage:** no `java.time`, records, sealed patterns, `String.formatted`, or other post-8 APIs used outside desugaring scope; core-library desugaring is ON and the module compiles to the configured toolchain. No findings.

---

## 3. Migration tests + results

- **Static simulator** (`tools/migration_check.py`): extracted all 27 `execSQL` statements from the 10 hand-written `Migration` objects in `data/db/AuralisDatabase.kt`, replayed them against a real SQLite built from `schemas/1.json`, and diffed the result against `schemas/11.json`.
  **Result: OK** — all 21 tables, every column (type + notNull), every index (columns + uniqueness) and every foreign key match v11. `playCount`'s `DEFAULT 0` is safe because the entity declares no `@ColumnInfo(defaultValue=…)`, which is the only case Room compares defaults.
- **Instrumentation test** added: `app/src/androidTest/java/com/auralis/app/data/db/AuralisDatabaseMigrationTest.kt`. For every start version 1→10 it creates the schema, seeds one `sources` row, runs `runMigrationsAndValidate(name, 11, true, *allMigrations)`, then queries **every** v11 table and asserts the seeded row survived.
- Wiring: `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`, `sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")`, and `androidTest` deps (`androidx.room:room-testing:2.7.2`, `androidx.test.ext:junit:1.2.1`, `androidx.test:runner:1.6.2`, `androidx.test:core:1.6.1`) declared directly (version catalog untouched).
- **Compiles:** `:app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL.
- **Executed on a device:** ❌ not run (see §7).
- No `fallbackToDestructiveMigration` is used.

---

## 4. Lint counts before/after

| | Errors | Warnings |
|---|---|---|
| Before (start of task) | 67 | 90 |
| **After** | **49** | **95** |

After-breakdown (from `lint-results-debug.xml`): **48 `UnsafeOptInUsageError`** (media3 `@UnstableApi`, all in `PlaybackService.kt`) + **1 `PropertyEscape`** (`local.properties` sdk.dir) = the only 49 errors. **Zero** errors remain in every required category: NewApi, InlinedApi, MissingPermission, WrongConstant, UnspecifiedImmutableFlag, ForegroundServiceType, ProtectedPermissions, ScopedStorage, DeprecatedApi, Range, ResourceType. No suppressions were added for those categories (the only `@Suppress`/`@SuppressLint` in the tree are pre-existing `UNCHECKED_CAST` / `SetJavaScriptEnabled`).

The warning count rose by 5 because of newly added crash/recovery code; those are ordinary `UseKtx`/`Recycle`/`ModifierParameter`-style advisories, not correctness issues.

---

## 5. `.so` 16 KB alignment

`tools/check_so_alignment.py` parsed the ELF program headers of every packaged native library:
`libandroidx.graphics.path.so` and `libdatastore_shared_counter.so` × `arm64-v8a, armeabi-v7a, x86, x86_64`.
**Result: every PT_LOAD segment has `p_align = 0x4000` (16384)** → all are 16 KB-page compatible.

---

## 6. Unit tests

`:app:testDebugUnitTest` → **BUILD SUCCESSFUL**, **167 tests, 0 failures, 0 errors, 0 skipped** (33 suites), including `logging/DiagnosticsReportTest.kt`.

---

## 7. What could NOT be verified

- **No on-device/emulator run.** Per your instruction I did not start adb/emulator. Two AVDs exist (`AuralisTest`, `auralis_test`) and the `emulator`/`adb` binaries are present, but no smoke test on API 26/29/31/33/35/36 was executed.
- **Therefore I make no claim that anything is "fixed on Android X".** The migration `MigrationTestHelper` test is written and compiles but has **not** been executed; run it with `./gradlew :app:connectedDebugAndroidTest`.
- **No crash log from the failing phones** was available, so the original non-Android-14 crash was addressed by hardening + making crashes visible, not by reproducing the exact stack.
- Static-only verification for: migration correctness (simulator), lint categories (lint run), `.so` alignment (ELF parse).

---

## 8. Exact crash-capture steps (you run the APK)

1. Install `AURALIS-crash-fix-v1.2.0.apk` on the phone that used to crash (Package Installer / `adb install -r` — same ID `com.auralis.app`, so it upgrades in place).
2. Launch the app. If it crashes, a plain report screen appears (unless the crash loops within 10 s). Tap **Copy** or **Share** and send the text. If no screen appears, the report is still on disk.
3. Pull the files (any of these):
   - `adb shell run-as com.auralis.app cat files/crash/$(ls -t files/crash | head -1)`
   - `adb shell run-as com.auralis.app ls -l files/crash/` then cat the newest `crash-*.txt`
   - `adb shell run-as com.auralis.app cat files/crash/startup-warnings.txt`
   For a non-debug build use **Android Studio → Device Explorer → `/data/data/com.auralis.app/files/crash/`**.
4. In-app alternative: **Settings → Logs & diagnostics** → the export now includes the **last crash** and the **startup warnings** (a "Last crash" entry is shown).
5. To reproduce a crash on demand (debug builds only): long-press the **"AURALIS <version>"** row in Settings → About, or the **"Logs & diagnostics"** title.

Send me the newest `crash-*.txt` (and `startup-warnings.txt`), and I can pinpoint the exact version-specific failure instead of relying on static analysis.
