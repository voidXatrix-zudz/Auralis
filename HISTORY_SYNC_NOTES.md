# History Sync — working notes

Real two-way YouTube Music history sync. Branch: `history-sync`.

- **Outbound (A):** songs played in AURALIS report to the account's YouTube/YouTube Music history.
- **Inbound (B):** songs played elsewhere on YouTube Music show up in AURALIS History + Home Recents.
- **Visibility (C):** failures are never silent; Settings + Logs show a readable status.

This file is the running record. Update it after **every** step and re-read it (plus the task prompt)
after any summary/compaction.

---

## DO

- Read the whole project structure first; open every section-2 file fully. Do not guess.
- Keep changes consistent with surrounding structure, naming, style and patterns.
- Clean code only: no dead, commented-out or duplicate code.
- Keep `HISTORY_SYNC_NOTES.md` current (DO / DON'T / NEXT / FINDINGS) after every step.
- Commit after each numbered step, on branch `history-sync` (branched off the current branch).
- Stage **only** the task hunks (`git apply --cached --recount` of the step's hunks); the tree
  carries ~41 unrelated pre-existing WIP changes that must never be committed.
- Log only HTTP codes and short status words. Never log cookies, tokens, SAPISID, authorization
  headers or full tracking URLs.
- Reuse one collector, one history rule. The reporter owns policy; `PlaybackHistoryTracker` owns
  the "really listened" clock. Do not duplicate either.
- Outbound must never delay tap-to-play or touch stream resolving; it runs after playback starts.

## DON'T

- No ADB / SDK / emulator / device commands. File edits only (+ optional Gradle compile check).
- Do not change UI layouts, the dock, mini-player, Home, Library, Explore, Now Playing, lyrics,
  playlists sync, login/Clerk, downloads, equalizer, package name, application id, signing or
  version code. The APK must install over the existing one.
- No new libraries, no new permissions, no Room schema change. If a schema change is unavoidable:
  bump the DB version, add a migration and explain it here.
- Do not log secrets (see DO).
- Do not leave the permanent "history reporting off for this run" kill switch after the first
  400/401/403; use bounded, time-based backoff instead.
- A tracking-GET refusal must **not** expire the session (`affectsSession = false`).
- Do not delete local history rows because YouTube stopped returning them.

## NEXT

- **Progress on `history-sync`** (one commit per step):
  1. `04a5123` record DO/DON'T/NEXT and findings.
  2. `5636bce` outbound: real `PlaybackReportResult` + correct tracking pings.
  3. `a9b9a8c` outbound: three-ping policy + reporter tests.
  4. `4202a3b` outbound: report from an application-level scope + status state.
  5. `5bbb841` inbound: merge rule, failure flag, metadata backfill, background hook.
  6. `b9a69dc` status UI: one Settings row + a Logs card ("Last sent … last pulled …").
  7. `07e6066` self-review: a failed pull is now a real failure (not an empty page).
- **STEP 8** — final report (written below / in the chat reply).


## FINDINGS

One line per section-2 file, then the verdicts.

- `data/youtube/InnerTubeClient.kt` — keyless WEB_REMIX client. `reportPlayback(videoId, seconds):
  Boolean` (l.758-789) posts `player`, reads `playbackTracking.{videostatsPlaybackUrl,
  videostatsWatchtimeUrl}.baseUrl`, GETs both once via `getBlank`; `getBlank` (l.839-852) swallows
  the HTTP code entirely; `randomCpn` (l.854) mints a 16-char nonce; `trackingUrl` (l.825) appends
  `cpn/ver/c/fmt/cver` and, for watchtime, `cmt/st/et/state`. `playbackReportingDisabled` (l.108-110,
  780-783) is a permanent per-run kill switch. `songMetadata(videoId)` (l.796) also posts `player`.
  `historySongs()` (l.596) is `runCatching {…}.getOrDefault(emptyList())`. Session expiry is gated by
  `affectsSession` (default false) — the player/tracking/player paths already pass false.
- `data/youtube/YouTubePlaybackReporter.kt` — policy: reports once when `watchedSeconds >=
  min(30 s, half title)`; a `videoId@playStart` set de-dupes; retries up to 3× with 30 s backoff; the
  `report` lambda returns Boolean.
- `data/youtube/YouTubeHistorySync.kt` — `sync()` fetches, then merges via pure `mergeHistory`; keeps
  local `playCount`, keeps the existing `playedAt` for known rows, imports new rows with count 1; never
  throws and never deletes.
- `data/youtube/YouTubeRepository.kt` — `historySongs()` cached with `HISTORY_TTL_MS = 5 min`;
  `reportPlayback(videoId, seconds)` just forwards to `innerTube`.
- `data/youtube/InnerTubePlayer.kt` — keyless stream resolver on `www.youtube.com/…/player` with
  device clients (ANDROID_VR/IOS/ANDROID_MUSIC), **no cookies**, only reads `streamingData`. It never
  returns `playbackTracking`.
- `data/youtube/StreamProvider.kt` / `StreamResolver.kt` — race over NewPipe/InnerTube/YouTube.js.
  Stream resolution is anonymous: the resolved `player` response is **not** usable for tracking.
- `data/youtube/YouTubeJsEngine.kt` / `YouTubeJsProvider.kt` / `assets/youtubejs/api.js` — YouTube.js
  third provider, `generate_session_locally` (guest), only resolves audio + search + basic metadata;
  exposes no `playbackTracking`.
- `data/youtube/LiveSyncManager.kt` — three polling lanes (fast/medium/slow) + a 15-min WorkManager
  tier; `setPlaybackActive` slows the cadence while playing; exposes `LiveSyncUiState`. This is where
  an inbound "once per background cycle (≥15 min)" hook belongs.
- `data/youtube/LiveSyncState.kt` — `LiveSyncState` enum + `LiveSyncUiState(state,lastSyncedAt,
  newItemsPulse)`; a clean precedent for a `PlaybackReportingState` StateFlow.
- `data/youtube/LiveSyncWorker.kt` — `LiveSyncGraph.container` bridges the worker to `AppContainer`;
  `doWork()` calls `container.liveSync.runBackgroundSync()`.
- `playback/PlaybackHistoryTracker.kt` — the app's one "really listened" clock: `HISTORY_MIN_MS =
  10 s`, ignores pauses and forward seeks, resets on track change, counts a re-listen; `listenedMs`
  is the honest listened time to reuse for the watchtime ping.
- `playback/PlaybackController.kt` — `State(connected,trackId,positionMs,durationMs,playing,…)`
  published ~every 500 ms; a `Player.Listener` republishes on `onMediaItemTransition`, so a
  transition/repeat can be observed. Track id for a YouTube song is `yt:<videoId>`.
- `playback/PlaybackService.kt` — owns the Media3 player; `scope` is `SupervisorJob + Main.immediate`;
  `onIsPlayingChanged` already calls `container.liveSync.setPlaybackActive`.
- `ui/AppViewModel.kt` — `youtubePlaybackReporter = YouTubePlaybackReporter(viewModelScope){…}`
  (l.341); the sole collector (l.695-732) is on `viewModelScope`, keys a new play on `id !=
  lastReportedTrackId`, passes `watchedSeconds = positionMs/1000`; `youtubeHistorySync` +
  `syncYouTubeHistory()` (l.1438) single-flight; a 5-min foreground loop (l.579-593); resume triggers
  (l.2648); `youtubeRecents`/`youtubeHistory`/`mergedHistory` read `youtube_recents`.
- `AppContainer.kt` — manual DI; **no application-level `CoroutineScope` today**; `liveSync` builds
  its own `SupervisorJob + Dispatchers.Default` scope internally.
- `data/db/Entities.kt` — `YouTubeRecentEntity(videoId PK,title,artist,album,durationMs,
  thumbnailUrl,playedAt,playCount)`, table `youtube_recents`, index on `playedAt`.
- `data/db/Daos.kt` — `YouTubeDao.observeRecents/observeHistory/playCountOf/upsertRecent/
  allRecents/upsertRecents/clearRecents`. No per-row metadata patch beyond `upsertRecent`.
- `data/db/Mappers.kt` — `YouTubeRecentEntity.toDomain()`, `DownloadEntity.toDomain()` (id =
  `yt:<videoId>`, uri = local file), `Track.toYouTubeRecentEntity()`.
- `domain/model/AppSettings.kt` — `youtubeSyncHistory = true` (default), `youtubeIntegration`,
  `liveSyncEnabled`, `liveSyncLastSyncedAt`.
- `ui/settings/SettingsSubPages.kt` — "Sync my listening to YouTube Music" switch (l.686) with
  subtitle "Report plays to your account"; `liveSyncStatus(...)` (l.923) is the readable-status
  precedent. `SettingsHub.kt` `SettingsActions` is the plumbing for values + callbacks.
- `ui/screens/HistoryScreen.kt` / `ui/glass/GlassHistory.kt` — History renders `mergedHistory`.
- `ui/screens/LogsDiagnosticsScreen.kt` — reads `AppLog.entries()` + `YouTubeSessionLog.entries()`;
  where a "History sync" status entry belongs.
- `logging/AppLog.kt` — redacts URIs/paths/emails before writing; the right sink for one-line,
  code-only sync status.
- `logging/YouTubeSessionLog.kt` — 300-line persisted account-event log; sanitized lines only.
- `data/youtube/YtSong.kt` — `YOUTUBE_TRACK_PREFIX = "yt:"`, `isYouTubeTrack()` keys off the
  `auralis-yt://` uri (downloads are `yt:` ids with a file uri), `YtSong.toTrack()`.

### Outbound verdicts

- **O1 — CONFIRMED.** `playbackReportingDisabled` (l.108-110) is set true on the first 400/401/403
  (l.780-783) and never cleared for the rest of the run → every later play silently skipped.
- **O2 — CONFIRMED.** `getBlank` (l.839-852) runs the GET inside `runCatching {}` and returns Unit;
  it never inspects the HTTP code. `reportPlayback` then returns `!playbackUrl.isNullOrBlank() ||
  !watchtimeUrl.isNullOrBlank()` — a *fake* success that is true whenever the tracking URLs merely
  existed in the response, regardless of whether the pings were accepted.
- **O3 — CONFIRMED.** A second, **signed** `player` request is required. The stream-resolve
  `player` calls (`InnerTubePlayer`, NewPipe, YouTube.js) are anonymous/device clients and never
  carry `playbackTracking`. Worse, the reporting `player` payload omits
  `playbackContext.contentPlaybackContext.{signatureTimestamp,html5Preference,referer}`, which the
  reference implementation shows is the *only* gate: without a current `signatureTimestamp` Google
  answers HTTP 200 `UNPLAYABLE` and simply omits `playbackTracking`, so this returns false forever.
- **O4 — CONFIRMED.** Only one `playbackUrl` GET and one `watchtimeUrl` GET are sent, in the same
  call, at the moment the 30 s threshold is crossed. No start ping (~2 s), no `atr`, no final ping on
  end/track change/pause; `state` is always `playing` and never `final=1`.
- **O5 — CONFIRMED.** The reporter is built with `viewModelScope` (l.341) and its only collector is a
  `viewModelScope.launch` (l.695). A locked/backgrounded queue therefore loses reports.
- **O6 — CONFIRMED.** A new play is keyed only on `id != lastReportedTrackId`; `playStartMs` (the
  reporter's `playStartKey`) changes only with the id. Repeat-one / replaying the same song never
  starts a new report. Also `watchedSeconds = positionMs/1000` is the playhead, not real listened
  seconds.
- **O7 — NOT A BUG.** A downloaded YouTube song's id **is** `yt:<videoId>`
  (`DownloadEntity.toDomain()`, Mappers.kt l.110) and it carries `remoteId`. So the collector's
  `id.startsWith("yt:")` already includes downloads — which matches the requirement that downloads
  report. The real guard must stay uri-based (`isYouTubeTrack()`) for *resolving streams*, but for
  *reporting* the `yt:` id is correct. Local files never have a `yt:` id, so they never report.
- **O8 — CONFIRMED.** No readable status for history reporting anywhere; the Settings switch only
  says "Report plays to your account" and there is no "Last sent"/"Last pulled"/refusal line.

### Inbound verdicts

- **I1 — CONFIRMED (partially).** `YouTubeHistorySync.sync()` does merge into `youtube_recents`, and
  `syncYouTubeHistory()` is called on resume (l.2648) plus a 5-min foreground loop (l.579-593). The
  background lane does **not** refresh history (only liked songs/playlists), so a play made elsewhere
  while the app is closed is only folded in on the next foreground.
- **I2 — CONFIRMED.** `historySongs()` (client l.596 + repo) and `sync()` both swallow errors and
  return empty, which is indistinguishable from "nothing played". No error flag, no log line.
- **I3 — CONFIRMED.** `mergeHistory` sets `playedAt = previous?.playedAt ?: fetchedAt`, so a song the
  app already knows is never moved up even when YouTube shows it as the most recent play (played on
  another device). It needs the surface-higher rule: bump `playedAt` forward when the fetched page's
  order is newer, without inflating `playCount` and without deleting.
- **I4 — CONFIRMED (gap).** History/Recents do read `youtube_recents` and rows render title/artist/
  art from the stored columns. But a row first created by `recordYouTubePlay` with no metadata stores
  `title = videoId`, and nothing backfills it — `songMetadata(videoId)` exists but is not wired into
  the sync path.
- **I5 — CONFIRMED (accepted).** `mergeHistory` returns only fetched rows and `upsertRecents` leaves
  local-only rows untouched, so a song deleted from YouTube history stays locally. This is the
  intended behaviour; no deletion.

### STEP 5 decisions

- `mergeHistory` keeps a known row's real timestamp and only *lifts forward* the rows the fetched
  page ranks above a locally newer one (right-to-left pass, `playedAt = below.playedAt + 1`). An
  "elsewhere" play therefore surfaces without re-stamping the whole page to "now" (no drift across
  repeat syncs) and without touching any play count.
- `sync()` returns `HistorySyncResult(merged, failed, backfilled)`; a failed fetch is logged once as
  `AppLog.w` instead of masquerading as "nothing played".
- Placeholder rows (title still equal to the video id) are filled from `YouTubeRepository.metadata`
  (InnerTube `songMetadata`), capped at `METADATA_LIMIT = 10` per pass.
- Background hook: `LiveSyncManager.runBackgroundSync()` (the ≥15-min WorkManager tier) runs one
  extra history merge, gated by `youtubeSyncHistory`. It only fires when live sync + integration are
  on; the foreground 5-minute timer and resume cover the rest.
- The inbound unit tests stay on the pure merge/backfill helpers (`mergeHistory`, `metadataTargets`,
  `withMetadata`); the thin `sync()`/DAO wiring is intentionally not faked, matching this file's
  existing test boundary.

### STEP 6 decisions

- `PlaybackReportingState` gained the inbound half (`lastPulledAt`, `pullFailed`) so one state flow
  and one line describe both directions; `PlaybackReportingCoordinator.noteHistoryPull(failed)` is
  the single writer, called from `syncYouTubeHistory()`.
- The status is one `SettingsRow` directly under the existing switch (no layout change beyond the
  row itself) plus one `HistorySyncCard` in Logs & diagnostics, both fed by the pure
  `historySyncSummary(state, now)`. Wording is only ages and short words: "Last sent 2m ago · last
  pulled 1h ago · refused (HTTP 403)". No URLs, no secrets.
- The switch label is the acceptance wording, "Sync my listening to YouTube Music" (was "Sync
  listening history").
- `ageText(...)` is extracted from `liveSyncStatus` so the two status lines cannot drift; the
  existing live-sync string is byte-for-byte unchanged (covered by the new `HistorySyncSummaryTest`,
  289 tests green).

### STEP 7 decisions (self-review)

- Two real defects were found and fixed:
  1. `YouTubeHistorySync.fetch` returned a bare list and the repository's `historySongs()` swallows
     every error, so `HistorySyncResult.failed` could never be true — a dead flag. The mirror now
     fetches through `InnerTubeClient.historySongsResult()` / `YouTubeRepository.historySongsResult()`
     (the existing `cachedResult` pattern), so a rejected or offline pull is a genuine failure.
  2. The removed `reportPlayback` was still linked from the `songMetadata` KDoc; the dangling link
     now points at `openPlayback`.
- Commit hygiene: the step-6 commit was amended to include `PlaybackReporting.kt` +
  `PlaybackReportingCoordinator.kt`, which had been left unstaged and would not have compiled
  (`historySyncSummary` reads `lastPulledAt`/`pullFailed`). The step-6 commit is now self-contained.
- Cache-key note: `YouTubeRepository.historySongsResult()` deliberately reuses the key
  `yt_history:<session>` that `historySongs()` has **at the committed base**, because the working tree
  also carries an unrelated, uncommitted per-account cache-key change (`accountSuffix()`). When that
  change is committed, `historySongsResult()` must adopt the same suffix so the two share one row and
  one account's history can never be served to another.
- Verified: no task hunk is left unstaged in any shared file; no WIP symbol (clerk timers, glass
  helpers, `accountSuffix`, `lastHomeFromCache`, `HomeCurator`, …) appears added anywhere in the
  branch diff; `289` unit tests pass; only one reporter and one coordinator exist.

