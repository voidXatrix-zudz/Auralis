# RESUME NOTES — Show the last played song in the mini-player on app open

Branch: `resume-last-song` (created from `home-redesign`). Repo root: `C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL`.

## DO
- Restore **paused**, always. Show the pill instantly from stored display data.
- Support **local and YouTube** songs with the same checkpoint.
- Resolve YouTube streams **only when the user taps play**.
- Keep old snapshots loading; keep the Room queue working.
- Commit after each numbered step on branch `resume-last-song`.

## DON'T
- Don't use ADB/SDK. Don't autoplay at launch. Don't hold or save stream URLs.
- Don't wait for the library scan before showing the pill.
- Don't change dock/pill visuals or animations, Now Playing layout, Home, Library, lyrics, playlists, history, login, downloads or equalizer.
- Don't add libraries, permissions or a DB schema change unless unavoidable and documented.
- Don't show an error toast at launch for a failed restore.

## NEXT
- STEP 1: read + FINDINGS + verify R1–R7 + branch + this file. (done)
- STEP 2: checkpoint model + codec + tests. (done)
- STEP 3: one saver for local + YouTube (collector + background save). (done)
- STEP 4: restore at launch via virtual restored state; always paused. (done)

---

## STEP 1 — FINDINGS (one line per file, R1–R7 verdicts)

- `data/settings/PlaybackSnapshotRepository.kt` — DataStore `playback_snapshot`; `PlaybackSnapshot(trackId, positionMs, wasPlaying, shuffle, repeatMode)` only; no display data, no queue. Save writes 5 keys; read returns null when `track_id` missing.
- `ui/AppViewModel.kt` — Restore block (616–646) reads Room `queueDao().getAll()`, returns early when empty, reads the snapshot, waits `tracks.first { it.isNotEmpty() }` and rebuilds the queue from the **library only**; saver (658–675) writes the 5-field snapshot while `connected && trackId != null` (position rounded to 5 s); `localQueueIds` filters queue ids to library rows.
- `playback/PlaybackController.kt` — `attach` consumes the pending queue; `play` sets items at the wanted index; `restore` returns silently when the player is null; `start` builds `toMediaItem` (accepts `auralis-yt` scheme), `setMediaItems(index, positionMs)`, `prepare()`, then `play()/pause()`; `publish`/`publishPosition` mirror the Media3 timeline into `State` (trackId, queueIds, positionMs, durationMs, playing, connected, artworkUri).
- `playback/PlaybackService.kt` — Foreground `MediaLibraryService`; owns the `ExoPlayer`; YouTube items are `auralis-yt://<videoId>` and the `ResolvingDataSource` swaps in the signed URL **on the loading thread when `prepare()` loads** (so preparing a YouTube item = a network call). Service outlives the Activity.
- `data/db/Daos.kt` + `Entities.kt` — `QueueDao` is local-id only (`getAll/clear/insertAll/replaceAll`), `QueueItemEntity(position, trackId, originalPosition)`; `YouTubeRecentEntity` stores videoId/title/artist/album/duration/thumbnail/playedAt/playCount.
- `domain/model/Models.kt` — `Track` needs all fields; relevant: `id`, `uri`, `title`, `artist`, `album`, `durationMs`, `artKey`, `isRemote`, `artworkUrl`, `remoteId`; `isYouTubeTrack()` is `uri.startsWith("auralis-yt://")`.
- `ui/AuralisRoot.kt` — `playbackTrackId = playbackState.trackId`; `currentTrack = playbackTrackId?.let(trackById::get)`; `trackById = library + remoteTracks`; dock/mini-pill get `hasTrack = currentTrack != null`, `DockMetrics.height(currentTrack != null, …)`; Now Playing shows when `nowPlayingOpen && currentTrack != null` and gets `playback = playbackState` + `queue = queuedTracks`.
- `ui/components/AppDock.kt` — `DockMiniPill(style, …, track, playing, onTogglePlay, onNext, onOpen, artworkUri)` already renders art/title/artist/play/next; it appears whenever `hasTrack && track != null`. Unchanged.
- `ui/glass/GlassChrome.kt` — `GlassMiniPlayer`/`GlassNavBar` are legacy (the glass dock reuses the shared `DockMiniPill`); untouched.
- `MainActivity.kt` — `onStart` builds the `MediaController` and `playback.attach(future.get())`; `onStop` → `onAppBackgrounded()` then `playback.attach(null)` and releases the controller. `onResume` → `onAppResumed()`.
- `AppContainer.kt` — manual DI; `playbackSnapshot`, `playback`, `settings`, `database` are lazy singletons. **No application-level CoroutineScope** exists.
- `domain/model/AppSettings.kt` — flat DataStore settings data class (no scope field needed for the optional switch).

### R1–R7 verdicts
- **R1 TRUE** — `localQueueIds` drops every YouTube id; the Room queue is local-only, so a YouTube-only last song leaves `savedQueue` empty and the old restore returns before doing anything.
- **R2 TRUE** — the snapshot has no display data and the restore rebuilds through `library[it.trackId]`, so a YouTube id can never be reconstructed from an old snapshot.
- **R3 TRUE** — `tracks.first { it.isNotEmpty() }` suspends forever for an empty local library (YouTube-only user).
- **R4 TRUE** — `snapshot.wasPlaying` is passed to `restore`, so closing while playing autoplays on the next launch.
- **R5 TRUE** — the pill only shows when `currentTrack != null`, and `trackById` = library + `remoteTracks`, so a restored YouTube track must be placed in `_remoteTracks`.
- **R6 TRUE (nuance)** — `restore()` returns silently when the player is not attached. `toMediaItem` accepts the `auralis-yt://` marker, and the signed URL is resolved lazily by the service — **but `start()` calls `prepare()`, which triggers that resolution (network) at restore time**. Therefore a plain `restore()` at launch would do network work, so the **virtual restored state** is required (do not prepare until the user taps play).
- **R7 TRUE (partial)** — the saver runs only while `connected && trackId != null` (≤5 s old, position rounded to 5 s) and there is **no save in `onAppBackgrounded`**; swipe-away keeps at most the last 5 s window. We add the background save.

### Extra facts confirmed
- kotlinx.serialization is applied (`@Serializable`, generated `.serializer()`, `Json { ignoreUnknownKeys = true; encodeDefaults = true }` is already the project pattern in `YouTubeRepository`).
- Unit tests live under `app/src/test/java/com/auralis/app/...` and are plain JVM tests.
- The player outlives the Activity, so the restore must respect an existing session (`connected.trackId != null || queueIds.isNotEmpty()` → do nothing).

---

## STEP 2 — checkpoint model + storage (done)
- New `data/settings/PlaybackCheckpoint.kt`: `PlaybackSnapshotKeys`, `PlaybackSnapshot` (moved here, extended with `title/artist/album/durationMs/artworkUrl/isRemote/remoteId/queueJson/savedAt`), `@Serializable CheckpointTrack`, `PlaybackCheckpointCodec` (`encode/decode`, cap `MAX_QUEUE = 50`, malformed → empty), `playbackSnapshotFrom(prefs)`, `Track.toCheckpointTrack()`, `CheckpointTrack.toTrack(localUri)`.
- `PlaybackSnapshotRepository.kt` now writes/reads all keys (old 5 keys kept, defaults for the rest) and gained `clear()`.
- Remote ids are normalised to `yt:<videoId>` in `toTrack`; remote tracks get the lazy `auralis-yt://<videoId>` URI (no URL stored).
- Tests: `app/src/test/java/com/auralis/app/data/settings/PlaybackCheckpointTest.kt` — round-trip, cap 50, malformed → empty, blank/null → empty, old 5-key store loads, empty store → null, remote rebuild has no URL, local rebuild uses the library URI, Track↔checkpoint round-trip. All green via `:app:testDebugUnitTest`.

---

## STEP 3 — one saver for local + YouTube (done)
- `AppViewModel`'s old 5-field snapshot writer replaced by `CheckpointKey` + `savePlaybackCheckpoint()`: still fed by one collector on `container.playback.state` (filtered to `connected && trackId != null`), keyed on track/play/queue/shuffle/repeat + a 5 s `positionBucket` (`CHECKPOINT_POSITION_STEP_MS`), so identical states coalesce into one DataStore edit.
- `checkpointFor` resolves the current song from `tracks + _remoteTracks` (so a YouTube song saves its title/artist/album/duration/artwork), marks `isRemote` from the track or the `yt:` prefix, and stores `queueJson` = a window of ≤50 `CheckpointTrack`s centred on the current index (`checkpointWindow`).
- `onAppBackgrounded` now calls `savePlaybackCheckpoint()` after `liveSync.onAppBackground()`, so a swipe-away keeps the exact position; the Room `queueDao` saver above is untouched.
- No stream URL is written; `wasPlaying` is still stored for compatibility but the restore will ignore it and force pause.
- Compile green (`:app:compileDebugKotlin`).
  - NOTE: the working tree carries ~580 lines of unrelated pre-existing WIP in `AppViewModel.kt`, so this step was committed with **scoped hunk staging** (`git apply --cached` of only the resume hunks). Steps 4 and 5 touch `AppViewModel.kt`/`AuralisRoot.kt`, which also carry WIP — repeat the same scoped staging. The full working tree compiles; a commit of only these hunks cannot be compiled alone because the uncommitted WIP files still call the uncommitted WIP methods.

---

## STEP 4 — restore at launch, always paused (done)
- `publishRestoredPlayback()` replaces the old Room-queue restore. It reads the DataStore checkpoint, respects a live service session (`connected.trackId != null || queueIds.isNotEmpty()` → do nothing), builds the queue from the checkpoint's own `queueJson` (falling back to the Room queue + single track for a pre-display snapshot), and publishes a `RestoredPlayback` **without starting the player** — so nothing is prepared and no YouTube URL is resolved.
- `RestoredPlayback(queue, index, positionMs, durationMs, shuffle, repeatMode)` + `restoredPlayback: StateFlow` are new; the mini-player reads them until the real player has a track (step 5).
- Local rows start display-only, then are upgraded from the library (URI + tags + artwork) once it loads, dropping ids the library no longer has (`awaitLibrary()` = `withTimeoutOrNull(6 s)`); if all are gone, the placeholder is cleared. YouTube-only checkpoints skip the library wait entirely.
- Always paused: `wasPlaying` is ignored. `resumeRestored()` starts the real player (`PlaybackController.restore(..., wasPlaying = true)` when connected, else `play`) only on an explicit tap, then clears the placeholder. A collector clears it too once the real player owns a track, and `clearQueue()` clears it.
- Restored streamed songs are added to `_remoteTracks` so the dock/Now Playing/lyrics can resolve them by id.
- Failed checkpoint read logs **one** `AppLog.w` (no toast/crash).
- `CheckpointTrack.toTrack()` lost its unused `localUri` parameter (local rows come from the library instead); its test now asserts a display-only local Track. Full unit suite green: 268 tests, 0 failures.
