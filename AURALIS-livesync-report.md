# AURALIS — Part C (Live Sync) + A–F work package

Build: `.\gradlew.bat :app:assembleDebug` — **BUILD SUCCESSFUL**
APK: `app\build\outputs\apk\debug\app-debug.apk` (34,682,584 bytes)
Copy: `C:\Users\Dipanjan\Desktop\AURALIS-livesync-v1.2.0.apk`
SHA-256: `FDD7F6D81B5F2445C497AAEE981CB655E872DB31B62B54B34C149C2C778D1DBF`

applicationId `com.auralis.app` (unchanged) · versionCode `2` · versionName `1.2.0` → installs in place over the previous debug build (same debug signature). No package/applicationId change.

Verification performed (no device):
- `:app:compileDebugKotlin` → BUILD SUCCESSFUL.
- `:app:testDebugUnitTest` → 167 tests, 0 failures, 0 errors, 0 skipped (results dated after the last source edit).
- `:app:assembleDebug` → BUILD SUCCESSFUL (re-run with output redirected to a file; an earlier "exit 1" was a broken-pipe artefact of `Select-Object -First`, not a compile error).
- APK inspected: `classes*.dex` contain `LiveSyncManager`, `LiveSyncWorker` and `LiveIndicatorKt`.
- APK timestamp (15:16:07) is later than the newest edited source (15:15:19).

---

## Part C — Live sync

### Files added
| File | Purpose |
| --- | --- |
| `data/youtube/LiveSyncState.kt` | `LiveSyncState` enum, `LiveSyncUiState`, internal `PendingLikeAction`. |
| `data/youtube/LiveSyncManager.kt` | The polling engine (fast / medium / slow lanes, backoff, network, WorkManager tier). |
| `data/youtube/LiveSyncWorker.kt` | `CoroutineWorker` + `LiveSyncGraph` handle for the background tier. |
| `ui/components/LiveIndicator.kt` | `LiveDot` — Cream dot, dimmed when not live, "Reconnect" on auth expiry, one-shot pulse. |

### Files changed
`AppContainer.kt` (adds `liveSync`), `AuralisApp.kt` (`LiveSyncGraph.container`), `MainActivity.kt` (`onAppBackgrounded` on `onStop`), `playback/PlaybackService.kt` (`setPlaybackActive` on `onIsPlayingChanged`), `ui/AppViewModel.kt` (`liveSyncUi`, start/stop collector, playlist mirror, auth-expired nudge, pending-like note, `setLiveSyncEnabled`, `syncLiveNow`, `onAppBackgrounded`), `ui/AuralisRoot.kt` (pass engine state to Library + Settings, expired prompt), `ui/settings/SettingsHub.kt` (`SettingsActions` fields), `ui/settings/SettingsSubPages.kt` (Live sync section), `domain/model/AppSettings.kt`, `data/settings/SettingsRepository.kt`, `data/db/Daos.kt` (`likedRows`, `upsertLikes`, `deleteLikes`, `applyLikeSync`), `data/youtube/YtSong.kt` (`likedAt` param), `data/youtube/YouTubeRepository.kt` (`likedSongsSyncResult`), `data/youtube/InnerTubeClient.kt` (`likedSongsFirstPageResult`), `ui/screens/LibraryScreen.kt` + `ui/glass/GlassScreens.kt` (Live dot on Liked Songs tile + section header), `ui/screens/TrackList.kt` + `ui/glass/GlassLists.kt` (`animateItem`).

### Implemented intervals
| Lane | Foreground | While playing (background) | Low performance mode | Paused |
| --- | --- | --- | --- | --- |
| Fast (liked page) | 4 s + jitter | 10 s | 8 s | Battery Saver (foreground), offline |
| Medium (playlists) | 30 s + jitter | 60 s | 60 s | offline |
| Slow (full reconcile) | 5 min, plus app start and after sign-in | 5 min | 5 min | offline |
| WorkManager | 15 min periodic, network-constrained, `KEEP` | — | — | cancelled when integration off / live sync off / logout |

Backoff: 4→8→16→32→60 s (capped), reset on success; 429/403 force the ≥60 s cap; 401/403 set `AuthExpired`, stop the engine and cancel the worker.

### Migration
**None — no Room schema change.** Already present in v11: `youtube_likes.likedAt` (`YouTubeLikeEntity`, ordered `likedAt DESC`), `playlist_yt_entries.position`, `videoId` PK, `playlistId` in the unique composite index. No `version` bump, no `ALTER TABLE`, no data wipe.
SQL the prompts asked about would be `ALTER TABLE youtube_likes ADD COLUMN likedAt INTEGER NOT NULL DEFAULT 0` / `CREATE INDEX ...` — **not needed**; the columns and keys already exist.

### Deviations and why
1. **No schema migration** (above) — smallest safe option; the required columns/indices already exist.
2. **Fast lane fetches one VLLM page.** `InnerTubeClient.likedSongsFirstPageResult()` reuses `browseRoot("VLLM")` + `collectSongsDeep` without following continuations. The slow lane uses the full paginating `likedSongsResult()` (existing `pagedSongs`, page cap 500).
3. **Medium lane forces a fresh read.** The library cache TTL is 60 min, far longer than the 30 s lane, so `likedPlaylists(force = true)` is used and the signature diff decides what reaches the UI.
4. **Account playlists are not in Room.** They live only in `AppViewModel._youtubePlaylists`, so the medium lane publishes to a `LiveSyncManager.accountPlaylists` StateFlow that the ViewModel mirrors; the open playlist is refetched by `retryYouTubePlaylist()` only when its title/count changed. Nothing is ever deleted or duplicated locally, and playlists created in AURALIS are never touched (match by YouTube playlist id).
5. **Tier 2 keeps the Media3 FGS.** No second notification or FGS is created; the manager simply keeps polling at 10 s/60 s while the existing service reports `isPlaying`.
6. **Timestamp emission throttled to 10 s.** Avoids an AuralisRoot recomposition on every 4-second poll; genuine state changes still emit immediately.
7. **Low performance mode** is read from the saved `performanceProfile == LOW`, not from Battery Saver (Battery Saver *pauses* the fast lane instead, as required).

---

## Parts D / E / F
- **D:** `collectAsStateWithLifecycle` everywhere; `LiveDot` uses a single remembered `Animatable` and a `graphicsLayer` deferred lambda (no per-frame allocation, no blur); `LiveSyncUiState` is a data class so unchanged values do not emit; `animateItem()` on keyed track rows (plain + glass) with `key = { it.id }`; hash pre-check skips the DB work.
- **E:** no TODO/commented blocks added; new files use consistent naming; no new heavy dependency (WorkManager was already declared).
- **F:** exact commands and real results above.

---

## Known issues / notes
1. **Cross-tab scroll restoration is unchanged (not added).** The header is now the leading item of the same `LazyColumn`, so the existing scroll behaviour is preserved, but there was no saveable-state holder across bottom-nav tabs before and none was added (adding one inside `AnimatedContent` risks duplicate-key crashes during fast tab switches). Filter/section state still resets on tab switch, as before.
2. **`LiveDot` ticks the Settings "Last synced" text every 5 s** via `produceState`; it is inside a lazy item, so the ticker stops when scrolled away.
3. **Auth-expired nudge reuses the existing connect dialog** (title "Session expired", button "Sign in") and the same 48-hour "Don't ask again" snooze; it is offered once per expiry.
4. **Background work (WorkManager) is best-effort**, as Android allows; the foreground/playback lanes are the reliable path.
5. `BuildConfig.versionCode` stays `2`, so the new APK overwrites the previous debug install; a release build would need a version bump.
