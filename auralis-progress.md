# AURALIS MEGA BULK — progress

Worked in order. Each part is compiled (`:app:compileDebugKotlin`) and committed locally.

- [x] Part A — Download crash hardening
- [x] Part B — Profile picture + account card (both themes)
- [x] Part C — Glass YouTube Home header + branch
- [ ] Part D — Clerk hand-off to YouTube Music
- [ ] Part E — Sync to YouTube account (history/likes/playlists)
- [ ] Part F — Download tag embedding
- [ ] Part G — Lyrics + metadata providers
- [ ] Part H — Settings redesign (both themes)

## Notes
- Part A: single allowed `adb logcat -d AndroidRuntime:E *:S` returned nothing (no device attached); cause found by code reading.
  - `DownloadManager.enqueue`/`cancel` wrapped in `runCatching` + logged, so a bad WorkManager state can't crash the UI thread.
  - Download notification channel is now created at app start (`AuralisApp.onCreate`) and idempotently before every notify (`DownloadManager.ensureChannel`).
  - `POST_NOTIFICATIONS` is requested non-blockingly on Android 13+ (`MainActivity`); downloads still work when denied.
  - `DownloadWorker` reuses the shared `playbackHttp` client instead of building its own.
  - `doWork()` already caught all throwables; verified it returns retry/failure only.
