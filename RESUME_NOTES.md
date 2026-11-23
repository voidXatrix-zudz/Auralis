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
