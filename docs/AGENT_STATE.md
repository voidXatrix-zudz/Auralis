# AGENT_STATE — TASK_YT_HOME

> Live notebook. Fixed format. Updated after every step and before any >100-line edit.
> This file is NOT the task spec — the spec is `TASK_YT_HOME.md` (READ-ONLY, never edit/delete).

- **Task file:** `TASK_YT_HOME.md` (found at repo root; user said `docs/TASK_YT_HOME.md` — path differs, content read in full, file left untouched / read-only).
- **Repo:** `C:\Users\Dipanjan\Downloads\Workspace\AURALIS-FULL` — branch `now-playing-blend`, HEAD `392911a`.
- **Working tree:** dirty with the previous (completed, uncommitted) lyrics-providers task. Do not disturb those changes.
- **Last update:** all steps done (Fix 1–4 + STEP 6–9); build/tests green; report written.

---

## 0. DECISION (resolved)

**Scope = Path A: extend the existing, committed YouTube Home** (`ui/screens/YouTubeHomeScreen.kt`
+ `YouTubeRepository.homeShelves()` + `HomeShelfLayout`). The task's parallel architecture
(`data/youtube/home/*`, `ui/home/YouTubeHomeContent.kt`) is **NOT** built: it would duplicate a
working pipeline and two Home UIs, violating Quality Rules 1 & 3. The task's *behaviour* is the goal,
not its file names. Files are created/edited minimally to reach that behaviour.

**PRIORITY FIX runs before STEP 6–8.** The user's actual complaint: Home shows **worldwide
famous songs / generic rows** instead of the user's own YouTube Music recommendations, and after
uninstall+reinstall the personalised Home does not come back. Root cause (to confirm by reading code,
not assume): when `youTubeSignedIn()` is false OR the session is invalid, `loadHome()` falls through
to `homeSections()` (the generic World/India charts), so a logged-out/invalid session silently shows
generic content. `youTubeSignedIn()` only checks `auth.snapshot()?.signedIn` (stored cookie), not a
live authenticated call.

### PRIORITY FIX — Fix 1–4

**Fix 1 — login-state truth.** One `isYouTubeAuthenticated()` that is true only when the stored cookie
carries `SAPISID` / `__Secure-3PAPISID` **and** an authenticated call succeeds. All Home / history /
likes / playlists use the ONE existing authenticated request path (`InnerTubeClient.headerBuilder()` +
`sign()` already send Cookie, `SAPISIDHASH`, `X-Goog-AuthUser`, `X-Goog-Visitor-Id`, Origin/Referer/UA).
On 401/403 or a logged-out response: mark the session expired and show the reconnect prompt — **never**
silently fall back to the generic feed.

**Fix 2 — logged-out / reinstall.** Not authenticated ⇒ do **not** show worldwide charts as
recommendations. Show one clean top card "Connect YouTube Music to see your own recommendations"
(with a Connect button → existing login flow), and below it the old local/recent Home. After a
successful login: clear the cached Home feed + cached history, re-fetch `FEmusic_home` +
`FEmusic_history` with the new session, replace Home — no restart. Feed cache is **per account**
(key includes sign-in state today; must be cleared/shared correctly so no other account's cache shows).
Reinstall ⇒ user only needs to sign in again (one tap via the existing Connect prompt; the 48 h
`youtubeConnectPromptSnooze` already exists).

**Fix 3 — only personalised sections, clean Home.** Show the sections YouTube returns for the
logged-in account, in YouTube's order. Hide non-music shelves (podcasts/shorts/ads/"explore charts"),
hide any section with <3 items, dedupe songs across sections (keep first), cap each shelf at 20 items,
skip items with no art/title. Recents = YouTube's own "Listen again"/recents shelf (NOT the app's plain
history dump). Add a small "Your most played" row (top 10 by play count from merged history) only when
it has ≥5 songs. Pull-to-refresh replaces the list.

**Fix 4 — proof in the app.** Write into the existing in-app Logs screen (`AppLog` + `SessionLogSanitizer`
style lines): authenticated yes/no, SAPISID found yes/no, sections returned count, section titles,
items kept/skipped, feed source network/cache. Add an explicit line when the feed looks generic.
(No titles/ids/account identifiers — the logger redacts those.)

### After the PRIORITY FIX
STEP 6 (two toggles: "Personalized Home" ON, "Sync my listening to YouTube Music" ON),
STEP 7 (`YouTubePlaybackReporter` — REQUIRED), STEP 8 (`FEmusic_history` fetch + merge + refresh on
app open/resume/5 min), fallback to old Home when a toggle is off / logged out. Keep STEP 5 design
differences; do not copy the reference design.

---

## 1. GROUND RULES (from the task, do not violate)

Do not touch: dock/navigation, Now Playing, lyrics, stream resolving (NewPipeExtractor/InnerTube/yt-dlp race),
downloads, Clerk login, Settings layout, Equalizer, package name, existing Room data.
Reuse existing helpers — never create a second one. Smallest possible edits to existing files;
new logic in NEW files. No `!!` on network data; all JSON optional-safe; no main-thread network/disk.
If a Room table is added: Migration + version bump (no destructive migration). No adb/emulator/SDK tools.
Do not copy BitChord (GPL-3.0). Keep both themes in sync (shared logic; only colors/surfaces differ).

---

## 2. STEP STATUS

| Step | What | Status |
|------|------|--------|
| 0 | Read whole project + write plan | **DONE** |
| 1 | `data/youtube/home/HomeModels.kt` | **SKIPPED (Path A)** — reuses `YtShelf`/`YtCard`/`HomeRow` |
| 2 | `YouTubeHomeApi.kt` + `YouTubeHomeParser.kt` | **SKIPPED (Path A)** — reuses `InnerTubeClient.homeShelves()` |
| 3 | `YouTubeHomeRepository.kt` (Flow<HomeState>, cache, loadMore, refresh) | **SKIPPED (Path A)** — reuses `YouTubeRepository` |
| 4 | `ui/home/YouTubeHomeContent.kt` + 1 hook per theme Home | **SKIPPED (Path A)** — extends `YouTubeHomeScreen` (both themes already) |
| 5 | Design pass (both themes) | **DONE (light pass)** — Connect card + clean row order use shared logic + existing tokens |
| 6 | Two settings toggles | **DONE** — `personalizedHome` (default ON) + "Sync my listening to YouTube Music" (existing `youtubeSyncHistory`, default ON) |
| 7 | `YouTubePlaybackReporter.kt` | **DONE** — new file, wired to the existing `reportPlayback` |
| 8 | History merge (FEmusic_history) | **DONE** — new `YouTubeHistorySync.kt` + DAO list upsert; app open/resume/5-min |
| 9 | Self-check | **DONE** — code-read pass + `:app:testDebugUnitTest :app:assembleDebug` green |

---

## 3. CODEBASE MAP (verified facts, reuse targets)

### InnerTube layer — `app/src/main/java/com/auralis/app/data/youtube/`
- `InnerTubeClient.kt`
  - `class InnerTubeClient(http: OkHttpClient, auth: () -> YouTubeAuth?, onAuthExpired, playlistCap, onSessionCookies, refreshSession, transport)`
  - Base `BASE = "https://music.youtube.com/youtubei/v1"`; `BROWSE_URL = "$BASE/browse"` (L1952–1965); `BROWSE_HOME = "FEmusic_home"` (L2004).
  - **Already has** `suspend fun homeShelves(): YouTubeLoadResult<List<YtShelf>>` (L543) and `homeShelvesContinuation(token)` (L591) → they POST `/browse` with `clientContext()`.
  - `clientContext()` (L1238) is **private**: builds `WEB_REMIX` context (`CLIENT_NAME="WEB_REMIX"`, `CLIENT_ID="67"`, `CLIENT_VERSION="1.20250101.00.00"`, hl/gl, visitorData, `user.onBehalfOfUser=dataSyncId`).
  - `browseRoot(browseId)` (L847) is the reusable shape: `buildJsonObject { put("context", clientContext()); put("browseId", browseId) }`.
  - Request headers via `headerBuilder()` (L1099) — sets UA, `X-Youtube-Client-Name: 67`, `X-Youtube-Client-Version`, Cookie+Authorization through private `sign()` (L1133) → `SAPISIDHASH <ts>_<sha1("<ts> <SAPISID> https://music.youtube.com")>`, `X-Goog-AuthUser`.
  - `internal fun sapisidHash(sapisid, timestampSeconds, origin="https://music.youtube.com")` (L2052) — reusable.
  - `suspend fun reportPlayback(videoId, watchedSeconds)` (L758) — **already** reads `videostatsPlaybackUrl`/`videostatsWatchtimeUrl` and GETs them.
  - JSON lib in this layer: **kotlinx.serialization.json** (`buildJsonObject`, `Json { ignoreUnknownKeys = true }`).
- `InnerTubePlayer.kt` — `InnerTubePlayer(http).audioFormats(videoId)` (only parsed formats; raw player response discarded, not cached).
- `YouTubeAuthStore.kt` — `YouTubeAuth(cookie, visitorData, dataSyncId, pageId, authUser, clientVersion, ...) { val sapisid; val signedIn; val hasSession }`; `auth: StateFlow<YouTubeAuth?>`, `readError`, `hasBlob`, `snapshot()`.
- `YouTubeRepository.kt` — owns an `InnerTubeClient`. Already: `homeFeed()`, `homeShelves(force=false)` (Room-cached, `HOME_TTL_MS`, `homeCacheKey(signedIn)`), `homeContinuation(token)`, `historySongs()` (cached `HISTORY_TTL_MS = 5 min`), `librarySongs`, `reportPlayback`, `modeSongs`/`moodSongs`, `browseSongs`, `search`, `radio`.
- `HomeShelfLayout.kt` — `enum HomeRowStyle{AUTO,TRACKS,LARGE_CARDS,SQUARE_TILES,WIDE_CARDS,CIRCLE_CARDS}`, `HomeShelfBlock`, `SignedInHomeLayout`, `classifyShelf()`, `buildSignedInLayout()`, `looksLikeMoodShelf()`, `cardDisplay()`.
- `MusicTrackFilter.kt` — `isMusicTrack`, `filterMusicTracks`, `normalizePlaylistBrowseId`.
- `YtSong.kt` — `YOUTUBE_TRACK_PREFIX="yt:"`, `YOUTUBE_PLAYBACK_SCHEME="auralis-yt"`, `YtSong.toTrack()`, `Track.isYouTubeTrack()`, `youtubePlaybackUri(videoId)`.

### Existing Home UI (a full YouTube Home ALREADY EXISTS — see §4)
- `ui/screens/YouTubeHomeScreen.kt` (852 lines) — `fun YouTubeHomeScreen(state: HomeUiState, accountName, avatarUrl, signedIn, onOpen, onRefresh, onSelectCategory, onPlayTrack, onOpenCard, onLike, onLoadMore, onCloseCollection, onSignIn, onSignOut, onSwitchAccount, onOpenExplore, modifier)`. PullToRefreshBox + LazyColumn + category chips + per-row LazyRow + collection bottom sheet. Renders in BOTH themes.
- `ui/screens/HomeScreen.kt` L56–77 — when `youtubeEnabled` renders `YouTubeHomeScreen` before the theme branch.
- `ui/glass/GlassScreens.kt` — `GlassHomeScreen` (local-library Home; not used when YouTube integration is on).
- `AppViewModel.kt` — `HomeCategory{FOR_YOU,TRENDING,NEW_RELEASES,CHILL,WORKOUT,FOCUS}` (L116), `HomeRow` (L131), `HomeUiState` (L154), `loadHome()`→`loadSignedInHome()` (L1231) uses `homeShelves()` + `historySongs()` + `buildSignedInLayout()`; `refreshYouTubeHome`, `selectHomeCategory`, `openHomeCard`, `loadMoreHomeRow`, `closeHomeCollection`, `playYouTubeTrack`.
- `ui/AuralisRoot.kt` L742–743 — Home call site (passes `youtubeEnabled = settings.youtubeIntegration`, all the YouTube callbacks).

### Settings / DataStore
- `domain/model/AppSettings.kt` — plain data class, all defaults. Existing YT fields: `youtubeIntegration=false` (L62), `youtubeSyncHistory=true` (L66), `youtubeSyncLikes`, `youtubeSyncPlaylists`, `liveSyncEnabled`, `youtubeSongsOnly`.
- `data/settings/SettingsRepository.kt` — `Keys` (L44), `toSettings()` decode (L224), `write()` encode (L299), `update(transform)` (L182). Pattern: `val x = booleanPreferencesKey("x")` + `x = this[Keys.x] ?: d.x` + `this[Keys.x]=s.x`.
- `ui/settings/SettingsSubPages.kt` — `SettingsSubScreen(...)` dispatcher (L119); `YouTubeMusicSettingsPage(settings, actions, onBack)` (L579). `ui/settings/SettingsHub.kt` — `SettingsActions` (L73), `glass()` (L121).
- `ui/AuralisRoot.kt` L388–429 — `SettingsActions(...)` construction.

### Auth / login gate
- `AppViewModel.youtubeAuth = container.youtubeAuth.auth` (L254); sign-in gate `youtubeAuth?.signedIn == true`.

### History + playback
- Room v11, schemas exported. Local: `PlayHistoryEntity(trackId PK, lastPlayedAt, playCount)` + `HistoryDao`. YouTube: `YouTubeRecentEntity(videoId PK, title, artist, album, durationMs, thumbnailUrl, playedAt, playCount)` + `YouTubeDao.observeHistory()/upsertRecent()/clearRecents()`.
- `AppViewModel.mergedHistory` (L297–314) = local rows (real timestamps) + `youtubeHistory` (room mirror), sorted by `playedAt`. `HistoryEntry(track, playedAt, playCount, videoId)`.
- `ui/screens/HistoryScreen.kt` / `ui/glass/GlassHistory.kt` — render `List<HistoryEntry>`; do not redesign.
- Playback: `PlaybackController(context)`, `state: StateFlow<State>` (trackId, playing, positionMs, durationMs), 500 ms ticker (L78/publishPosition L370). `play(tracks, index)`, `addToQueue`, `playNext`.
- `AppViewModel.kt` L607–627 — single `playback.state.collect` → `PlaybackHistoryTracker(HISTORY_MIN_MS=10_000)` → `recordYouTubePlay(id, positionMs)` (L3205) → `youtube.reportPlayback(videoId, positionMs/1000)` gated by `settings.youtubeSyncHistory && youTubeSignedIn()`. **Reporting already exists.**
- `AppViewModel.onAppResumed()/onAppBackgrounded()` (L2216) ← `MainActivity.onResume/onStop`. `LiveSyncManager` has foreground coroutine loops incl. a 5-min `slowLoop`.

### Theme tokens
- `ui/theme/GlassTheme.kt` — `GlassTokens.Indigo = #212842` (L38), `Cream = #F0E7D5` (L41), radii; `LocalThemeStyle = staticCompositionLocalOf { ThemeStyle.NORMAL }` (L173).
- `ui/theme/Theme.kt` — `Auralis.extras` (`surface2`, `mutedForeground`, `border`, ...). `ui/theme/Color.kt` — palettes + `Accents`.
- Read style: `LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC`.
- Dock inset: `ui/components/AppDock.kt` — `LocalDockBottomInset` (L160), `DockMetrics.height/glassHeight` (L100). Glass lists use `PaddingValues(bottom = 24.dp + LocalDockBottomInset.current)`; Normal content uses `Modifier.dockBottomSpace(...)` in `AuralisRoot`.
- Reusable UI: `ArtworkImage(track, modifier, shape, artUri)`, `ArtistAvatar(...)`, `GlassPanel/GlassChip/GlassTrackRows`, `TrackRows(...)`, `SectionTitle(...)`, `PullToRefreshBox` (Material3).

---

## 4. BLOCKER / KEY FINDING (needs a decision before STEP 1)

**The repo already contains a working, committed "personalized YouTube Music Home" that covers most of
this task's goal, implemented with a different architecture than the task file requests.**

- It exists and is **live** (rendered when `settings.youtubeIntegration` is on), in **both themes**:
  `ui/screens/YouTubeHomeScreen.kt` ← `ui/screens/HomeScreen.kt:56` (before the per-theme branch).
- It already fetches `FEmusic_home` shelves via `YouTubeRepository.homeShelves()` →
  `InnerTubeClient.homeShelves()` and classifies them with `HomeShelfLayout.buildSignedInLayout()`.
- It already has: pull-to-refresh, loading skeletons, inline error+Retry, category chips, Top Picks /
  Moods / Recents / card rows, an artist/collection bottom sheet, and playback/like wiring.
- It already reports plays to YouTube Music (`recordYouTubePlay` → `InnerTubeClient.reportPlayback`,
  using `videostatsPlaybackUrl`/`videostatsWatchtimeUrl`), gated by `youtubeSyncHistory` + sign-in.
- History is already merged for the History screen (local `play_history` + Room mirror `youtube_recents`),
  but the YouTube half is written **only by in-app playback** — it does **not** yet fetch `FEmusic_history`.

This creates a direct conflict with the task file, which specifies building a **new** pipeline
(`data/youtube/home/HomeModels.kt`, `YouTubeHomeApi.kt`, `YouTubeHomeParser.kt`, `YouTubeHomeRepository.kt`,
`ui/home/YouTubeHomeContent.kt`) and one hook per theme's Home. Following it literally would create a
**second** InnerTube home-fetch pipeline and a **second** Home UI, violating task Quality Rules 1 & 3
("reuse the existing helper; do not create a second one"; "smallest possible change").

Not yet determinable without the user: whether they want (A) the existing implementation extended to
satisfy the task's behaviour, or (B) the task's named architecture built (and the old screen retired).

---

## 5. STEP 0 PLAN (draft — pending the §4 decision)

Reused as-is (no new duplicates): `InnerTubeClient` (browse/context/sign/`sapisidHash`/`reportPlayback`),
`YouTubeAuthStore`, `YouTubeRepository`, `HomeShelfLayout`, `MusicTrackFilter`, `ArtworkImage`,
`LocalThemeStyle`/`GlassTokens`, `LocalDockBottomInset`, `PlaybackController.state`, history DAOs.

### Path A — extend the existing implementation (recommended; honours Quality Rules 1 & 3)
- **New:** `data/youtube/home/HomeModels.kt` only if the task's model names are wanted as a thin,
  unused-elsewhere layer; otherwise skip.
- **Edit (minimal):**
  - `AppViewModel.kt` — add `personalizedHome`/`syncListening` gates; fetch `FEmusic_history`; upsert into
    `youtube_recents`; expose the fetched history to `mergedHistory`; app-open/resume/5-min refresh.
  - `AppSettings.kt` + `SettingsRepository.kt` — the two toggles.
  - `SettingsSubPages.kt` — two switch rows in `YouTubeMusicSettingsPage`.
  - `HomeScreen.kt` — gate `YouTubeHomeScreen` on logged-in AND `personalizedHome`.
  - `YouTubeRepository.kt` — add `historyFeed()` (`FEmusic_history`) if not present.
- Retire nothing.

### Path B — build the task's named architecture
- **New (task-named):** `data/youtube/home/HomeModels.kt`, `YouTubeHomeApi.kt`, `YouTubeHomeParser.kt`,
  `YouTubeHomeRepository.kt`, `ui/home/YouTubeHomeContent.kt`, `data/youtube/YouTubePlaybackReporter.kt`.
- **Edit:** `AppSettings.kt`, `SettingsRepository.kt`, `SettingsSubPages.kt`, `HomeScreen.kt` (+ maybe the
  glass Home), `AppViewModel.kt` (wire the new repo), `AuralisRoot.kt` (pass new state/actions).
- Risk: parallel duplicate pipeline + two Home UIs; must decide the fate of `YouTubeHomeScreen`.

---

## 6. ASSUMPTIONS / OPEN QUESTIONS

1. Task file is at repo root, not `docs/`; left in place (read-only). `docs/` created for this notebook.
2. The exact prior "CONTEXT GUARD" rule set and the exact `AGENT_STATE.md` format were not on disk;
   this notebook adopts the format described in the task's QUALITY RULES + the earlier session notes
   (task id, rules, step status, files, decisions, open questions, last update).
3. ~~**OPEN:** §4 decision (Path A vs Path B)~~ — **RESOLVED:** Path A.

---

## 7. CHANGE LOG

- STEP 0: read task file in full; mapped InnerTube, Home UI (both themes), settings/DataStore, auth gate,
  history DB + merge, playback listener, theme tokens, dock inset. Discovered the pre-existing YouTube Home.
- DECISION: Path A chosen (extend the existing Home); PRIORITY FIX (1–4) planned; written above.
- FIX 3 (data): new `data/youtube/HomeCurator.kt` — `curateHomeShelves()` drops non-music shelves,
  <3-item shelves, cards with no title/art/id; dedupes songs across shelves; caps at 20.
- FIX 2 (cache): `YouTubeRepository` — `homeCacheKey(signedIn, accountKey)` now account-scoped;
  `accountCacheKey()` = SHA-1(SAPISID)[:4]; `historySongs()` key account-scoped; new
  `lastHomeFromCache` flag for the Logs "feed source" line.
- FIX 1–3 (Home): `AppViewModel` — `isYouTubeAuthenticated()` is the one login truth; `loadHome`
  routes For You to `loadSignedInHome`/`loadLocalHome` (never charts); `loadSignedInHome` curates
  shelves, uses YouTube's "Listen again" as Recents, adds "Your most played" (>=5 songs),
  surfaces 401/403 through `offerYouTubeReconnect`; `loadQuickPicks(allowPopular=false)`.
  `HomeUiState.needsYouTubeConnect/connectExpired`; `HomeScreen` gates on `personalizedHome`;
  `YouTubeHomeScreen` renders `YouTubeConnectCard` when signed out and hides category chips.
- FIX 4 (diagnostics): Home load writes authenticated/SAPISID/source/sections/kept/skipped/titles
  to `AppLog` (visible in the existing Logs screen) + a "feed may be generic" warning.
- STEP 6 (settings): `AppSettings.personalizedHome = true`; DataStore key
  `youtube_personalized_home` in decode/encode; two switches in `YouTubeMusicSettingsPage`
  ("Personalized Home", "Sync my listening to YouTube Music").
- STEP 7 (reporter): new `YouTubePlaybackReporter` — once per play at `min(30 s, half the title)`,
  de-duped by `videoId@playStart`, 3 attempts/30 s backoff; wired in the single playback collector.
  `recordYouTubePlay` now only writes the local mirror; reporter reset on sign-in/disconnect.
- STEP 8 (history): new `YouTubeHistorySync` + pure `mergeHistory`; `YouTubeDao.allRecents()` /
  `upsertRecents()` (no schema change, no migration); refresh when the account becomes usable,
  on `onAppResumed`, and every `HISTORY_SYNC_INTERVAL_MS` (5 min, matches cache TTL).
- TESTS: new `HomeCuratorTest` + `YouTubeHistorySyncTest`; cache-key assertions for two accounts.
- STEP 9: read-new-files pass; no `!!` on network data; history merge is stable across refreshes
  (existing rows keep their timestamp/play count); Home Recents fallback prefers the merged mirror;
  diagnostics log `safeShelfLabel` (never a personal "Because you listened to <artist>" heading).
  `:app:testDebugUnitTest :app:assembleDebug` green.
- REPORT: `docs/AGENT_REPORT.md` written (files, edits, assumptions, unverified items, test list).
