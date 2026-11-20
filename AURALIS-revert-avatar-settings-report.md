# AURALIS — revert: profile photo → Settings (and Library header)

**Project:** `C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL` (unchanged package `com.auralis.app`)
**Build:** `.\gradlew.bat :app:assembleDebug` → **BUILD SUCCESSFUL**
**Debug APK:** `C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL\app\build\outputs\apk\debug\app-debug.apk`
**Copy:** `C:\Users\Dipanjan\Desktop\AURALIS-revert-avatar-settings-v1.2.0.apk` (34,682,584 bytes)
**SHA-256:** `A12C6D512667427E39C8EA1BE0D2B5506F6E212AEDD88A2C3FB8FB28C647FAE1`
applicationId `com.auralis.app` · versionCode `2` · versionName `1.2.0` → installs in place over the previous debug build.

---

## 1. The good state (source used)

Git HEAD (`5c8bad2`, **2026-11-12**) is **not** usable: the last session's changes — and a large body of other uncommitted work (crash-fix, Explore, YouTube playlist screens, …) — are all sitting in the working tree on top of it. So I did **not** revert to HEAD.

The reliable pre-live-bridge baseline is the backup taken just before the last session:

> **`C:\Users\Dipanjan\Downloads\Workspace - Copy\AURALIS-FULL\...`** (and its zip), dated **2026-11-19 23:16** — *after* all committed work and the Explore/playlist work, but *before* both the crash-fix session (Nov 20 14:24) and the live-bridge session (Nov 20 14:34).

I diffed that folder against the current tree, file by file. For the UI files this revert touches, the only differences were exactly the live-bridge session's changes (nothing else), so those files could be restored from the baseline wholesale. `AuralisRoot.kt` and `SettingsSubPages.kt` also contained crash-fix changes, so those were edited surgically instead of overwritten.

---

## 2. What was restored / removed

### 4.1 Profile photo → Settings (both themes)
- Glass theme Home (`GlassAvatarButton`): `onClick` → `Destination.Settings` (was `Destination.Account`). `ui/AuralisRoot.kt`.
- Library avatar — normal theme: the header avatar is back to `Modifier.size(36.dp) … clickable { onOpenSettings() }`, and `AuralisRoot` passes `onOpenSettings = { viewModel.push(Destination.Settings) }`. `ui/screens/LibraryScreen.kt`.
- Library avatar — glass theme: same, back to `onOpenSettings()`. `ui/glass/GlassScreens.kt`.
- The YouTube-Home account avatar (normal theme) was never changed by the last session — it still opens the Switch-account/Sign-out menu.

### 4.2 Account page removed
- Deleted `ui/settings/AccountScreen.kt`.
- Removed `data object Account : Destination` from `ui/nav/NavState.kt`.
- Removed the `Destination.Account -> AccountScreen(...)` branch and the `ownsHeader` special-case from `AuralisRoot.kt`; removed the `AccountScreen` import.
- No `Destination.Account`, `AccountScreen`, or `onOpenAccount` references remain. No Account strings/resources existed.

### 4.3 Settings main page
Left as-is (it was not damaged): back arrow, "Settings" title, "Search settings", the profile card (photo / name / email / Sign out), the **SETTINGS** label, and all category cards — History, Playback & Quality, Metadata & Lyrics, Downloads, YouTube Music, Appearance, Performance, Storage, Backup, About — each opening its sub-page. `ui/settings/SettingsHub.kt` is theme-aware, so both Normal and Glassmorphic are covered.

### 4.4 History row in Settings (the one addition)
`SettingsCategory("History", "Songs you played", Icons.Rounded.History, Destination.History, 1)` — the **first row of the first category card**, directly under the profile card / SETTINGS label. It opens the existing `Destination.History` screen (`HistoryScreen`). `ui/settings/SettingsHub.kt`. It is the only place a History entry was added; it does not intercept the avatar.

### 4.5 Library header restored
`ui/screens/LibraryScreen.kt` and `ui/glass/GlassScreens.kt` were restored from the baseline: pinned header, "Your Library" title row with the **History pill** (glass: History chip), the "+" menu, search, sort row and filter chips as they were, with the tile/playlist list back to its previous padding. `ui/screens/TrackList.kt` and `ui/glass/GlassLists.kt` were restored too (the `header` parameter introduced by the last session is gone).

### 4.6 Live sync kept
Everything from Part C is retained: `LiveSyncManager`, `LiveSyncWorker`, `LiveIndicator`, the Settings → YouTube Music "Live sync" switch, "Last synced" and "Sync now". The only live-sync pieces that lived inside the reverted files were re-applied onto the restored baseline:
- `LiveDot` on the **Liked Songs** tile (row + card) and next to the Liked Songs section title, in both themes (`LibraryScreen.kt`, `GlassScreens.kt`).
- `Modifier.animateItem()` on the track rows (`TrackList.kt`, `GlassLists.kt`).
It does not depend on the Account page or the Library header changes.

---

## 3. Files changed

| File | Change |
| --- | --- |
| `ui/nav/NavState.kt` | removed `Destination.Account` |
| `ui/AuralisRoot.kt` | avatar → Settings; removed Account branch, `onOpenAccount`, `AccountScreen` import; `ownsHeader` → `glassSettings` |
| `ui/settings/SettingsHub.kt` | History-row subtitle → "Songs you played" |
| `ui/screens/LibraryScreen.kt` | restored baseline header; re-added LiveDot |
| `ui/glass/GlassScreens.kt` | restored baseline header; re-added LiveDot |
| `ui/screens/TrackList.kt` | restored baseline; re-added `animateItem()` |
| `ui/glass/GlassLists.kt` | restored baseline; re-added `animateItem()` |
| `ui/settings/AccountScreen.kt` | **deleted** |

Untouched (kept): `ui/settings/SettingsSubPages.kt`, `ui/AppViewModel.kt`, `data/youtube/LiveSync*.kt`, `ui/components/LiveIndicator.kt`, `AppContainer.kt`, `AuralisApp.kt`, `MainActivity.kt`, playback, downloads, equalizer, Clerk, themes.

---

## 4. Regressions found

None. The navigation graph still has Home, Explore, Library, Search, Settings + all sub-pages, History, Now Playing, Equalizer, Logs, Downloads (only the broken `Account` route was removed). Back behavior is unchanged, both themes are fine, and there are no dangling references to the removed Account page or to the Library History pill.

---

## 5. Verification (no device — you test the APK)

- `:app:compileDebugKotlin` → **BUILD SUCCESSFUL**.
- `:app:testDebugUnitTest` → **167 tests, 0 failures, 0 errors, 0 skipped**.
- `:app:assembleDebug` → **BUILD SUCCESSFUL**; APK built 16:01:10, newest source edit 16:00:19.
- APK `classes*.dex` inspected: contains `LiveSyncManager`, `LiveSyncWorker`, `LiveIndicatorKt`; **does not** contain `AccountScreen`.
- Verified **by reading the final code** (no ADB): Library and glass Library avatars call `onOpenSettings()`; the glass Home avatar pushes `Destination.Settings`; the Settings History row targets `Destination.History`; no `Destination.Account` anywhere.

Not verified on a running device (per your preference I did not start ADB/emulator): the on-screen result of tapping the avatar, and that every Settings sub-page opens.
