# AURALIS: Normal + Glassmorphic themes

**Status: written but NOT compiled or run.** The environment that produced this had no Android SDK or Gradle/Maven access. Only a parse-level syntax check of the Kotlin files was done (no syntax errors); type-checking and runtime behaviour are unverified. Expect to fix a few compile errors on the first build.

## How it works
- `ThemeStyle { NORMAL, GLASSMORPHIC }` is stored in DataStore (`theme_style`, default NORMAL) inside `AppSettings`.
- `AuralisTheme` (ui/theme/GlassTheme.kt) wraps the app. NORMAL calls the original `AuralisAppTheme` with the same arguments. GLASSMORPHIC provides its own colours, shapes, thin typography and `AuralisExtras`, and `LocalThemeStyle`.
- Each existing screen starts with a 3-line check: if the style is glass, call the glass version (same parameters and callbacks) and return; otherwise the original code runs unchanged. Playback, queue, lyrics, equalizer, scanning and ViewModels are not touched and not duplicated.
- Switch in Settings > Theme style (both themes show the selector).

## Glass screens (new, native Compose; design from the Lovable prototype tokens)
Home (artwork carousels, folder chips, song list), Library (tabs, albums, artists, genres, favorites, playlists), Search (field, empty/no-results states), Now Playing (large artwork, ambience, quality badge, waveform seek bar, controls, tools, up next), Queue (drag/long-press reorder, move, remove, clear), Settings, top bar, floating nav bar, mini-player. Dialogs, menus, sheets and switches pick up the glass colours and shapes from the theme.
Lyrics (LiquidLyricsScreen), Equalizer, Wrapped, Metadata editor, Backup and About are restyled by the glass theme tokens and backdrop only; they were not given bespoke glass layouts.

## Files
New: `domain/model/ThemeStyle.kt`, `ui/theme/GlassTheme.kt`, `ui/components/GlassBackdrop.kt`, `ui/components/ThemeStyleSelector.kt`, `ui/glass/{GlassKit,GlassLists,GlassScreens,GlassNowPlaying,GlassChrome,GlassSettings}.kt`.
Modified (small): `ui/theme/Theme.kt` (one `private` to `internal`), `domain/model/AppSettings.kt`, `data/settings/SettingsRepository.kt`, `ui/AuralisRoot.kt`, `ui/components/FloatingNavBar.kt`, `ui/screens/{HomeScreen,TrackList,LibraryScreen,SearchScreen,NowPlayingScreen,SettingsScreen}.kt`, `.github/workflows/android.yml` (artifact renamed), `CHANGELOG.md`.

## Build (Windows, Android Studio)
1. Unzip, open the folder in Android Studio, let Gradle sync (JDK 17). If it asks for `local.properties`/SDK, accept the defaults.
2. Build > Build APK(s), or in a terminal in the project root: `.\gradlew.bat assembleDebug`.
3. APK: `app\build\outputs\apk\debug\app-debug.apk`. Install: `adb install -r app-debug.apk` (same application id `com.auralis.app.debug`, so it updates your current debug build).
No Android Studio? Push the folder to a GitHub repo; the included workflow builds it and uploads the `AURALIS-debug-apk` artifact (Actions tab).

## Test checklist (all unverified)
- [ ] Fresh/upgraded install is Normal and looks identical to your current app.
- [ ] Settings > Theme style > Glassmorphic switches every tab, the mini-player, nav bar and Now Playing; restart keeps it; switch back restores Normal.
- [ ] Playback, notification and lock-screen controls, equalizer, queue reorder, lyrics, sleep timer and scanning behave the same in both themes.
- [ ] Performance Low gives a flat obsidian background; scrolling stays smooth.

## Known limitations / not done
- Not compiled; possible issues include import or API mismatches in the new files.
- Glass is always dark; Dark/Light and accent options apply to Normal only.
- No recent-searches list or loading skeletons (the app has no persistence for them); no compact/spacious toggle in Library.
- The seek bar's bars are decorative, not the audio's real waveform. Normal's slider is unchanged.
- System font at light weights (Manrope not bundled). Real backdrop blur is not used (the mini-player is tinted by artwork colour instead).
- Backup export does not include the theme choice; restore leaves the current choice unchanged.
- Existing unit tests were not run.

## Getting the APK without Android Studio (GitHub Actions)
1. Create a new private GitHub repository and upload this project's contents (the folder containing `gradlew`, `app/`, `.github/`) to the `main` branch.
2. Open the repo's Actions tab > "Android CI" > Run workflow (it also runs on every push).
3. When it finishes, download the `AURALIS-debug-apk` artifact (a ZIP containing `app-debug.apk`). If it fails, download `build-log` and send me the errors.
