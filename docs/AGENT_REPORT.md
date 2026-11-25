# AGENT_REPORT — TASK_YT_HOME

## 1. Files created

- `app/src/main/java/com/auralis/app/data/youtube/HomeCurator.kt`
  - `curateHomeShelves()`: drops non-music shelves (podcasts/shorts/news/sports/audiobooks/explore/charts),
    shelves with fewer than 3 usable cards, and cards with no title, artwork or id; dedupes a song
    across all shelves (first copy wins); caps each shelf at 20. `looksLikeRecentsShelf()` detects
    "Listen again"/"Recently played". `safeShelfLabel()` keeps diagnostic logs free of personal headings.
- `app/src/main/java/com/auralis/app/data/youtube/YouTubePlaybackReporter.kt`
  - Reports a play once at `min(30 s, half the title)` per `videoId@playStart`, runs on the supplied
    app scope after playback started, retries 3× with 30 s backoff, then drops silently. The network
    call is injected, so the file has no dependency on the player or resolver.
- `app/src/main/java/com/auralis/app/data/youtube/YouTubeHistorySync.kt`
  - `mergeHistory()` is pure: fetched `FEmusic_history` order becomes timestamps for **new** rows only;
    existing rows keep their real timestamp and play count (an import never inflates plays and a
    5-minute refresh never makes old history look "just played"); metadata falls back to the local row.
  - `YouTubeHistorySync.sync()` writes the merge through `YouTubeDao` and never throws.
- Tests: `app/src/test/java/com/auralis/app/data/youtube/HomeCuratorTest.kt`,
  `app/src/test/java/com/auralis/app/data/youtube/YouTubeHistorySyncTest.kt`.
- Notebook: `docs/AGENT_STATE.md` (live, updated each step).

## 2. Files edited and what changed

- `data/db/Daos.kt` — `YouTubeDao.allRecents()` + `upsertRecents(List)` for the merge. No schema
  change, so **no migration/version bump**.
- `data/youtube/YouTubeRepository.kt` — Home and history cache keys are scoped to the account
  (`SHA-1(SAPISID)[:4]`) as well as signed/anon, so one account's feed/history can never be read by
  another; `lastHomeFromCache` exposes the network/cache source for diagnostics.
- `data/youtube/InnerTubeClient.kt` — `trackingUrl()` now sends the web player's full `/videostats`
  shape (`c=WEB_REMIX`, `cver`, `cmt`, `st`, `et`, `state=playing`) for the watchtime hit. Still the
  one existing `reportPlayback` path; failures stay swallowed.
- `domain/model/AppSettings.kt` + `data/settings/SettingsRepository.kt` — new `personalizedHome`
  (default **ON**), key `youtube_personalized_home`, decoded/encoded with the existing mechanism.
- `ui/AppViewModel.kt`
  - `isYouTubeAuthenticated()` is the single login truth; `youTubeSignedIn()` delegates.
  - `loadHome()`: For You → `loadSignedInHome()` when authenticated, else `loadLocalHome()`; no path
    reaches the public charts on a logged-out/rejected session.
  - `loadSignedInHome()`: curates the raw `FEmusic_home` shelves, uses YouTube's own "Listen again"
    as the Recents row, keeps "Find Your Mood", adds "Your most played" (top 10, only with ≥5 songs),
    and surfaces 401/403 through the existing reconnect prompt. Failure keeps rendered rows.
  - `loadLocalHome()`: Connect-card state + the user's own recent/never-played rows and Quick Picks
    seeded from local activity only (`loadQuickPicks(allowPopular = false)`).
  - `HomeUiState.needsYouTubeConnect/connectExpired`; diagnostics lines in the existing `AppLog`
    (authenticated, SAPISID present, network/cache, sections returned/kept/skipped, items, safe titles)
    plus a "feed may be generic" warning.
  - STEP 7 wiring: the existing playback collector feeds `YouTubePlaybackReporter` only for `yt:` ids,
    with "Sync my listening" ON and a real login; `recordYouTubePlay()` now only writes the local mirror.
  - STEP 8 wiring: `syncYouTubeHistory()` on account-ready, `onAppResumed()`, and every 5 minutes
    while foregrounded (idles in background, off when logged out or the setting is off).
- `ui/screens/YouTubeHomeScreen.kt` — `expired` param + `YouTubeConnectCard` ("Connect"/"Reconnect",
  both themes, theme tokens), category chips hidden when signed out, empty state only for signed-in
  accounts. The screen stays shared by both themes.
- `ui/screens/HomeScreen.kt` — renders the YouTube Home only when `youtubeEnabled && personalizedHome`;
  otherwise exactly the old local Home. Passes `youtubeExpired`.
- `ui/AuralisRoot.kt` — passes `personalizedHome` and `youtubeExpired` to `HomeScreen`.
- `ui/settings/SettingsSubPages.kt` — "Personalized Home" switch (new) and the existing history switch
  relabelled "Sync my listening to YouTube Music", both in the existing YouTube Music category.

## 3. Assumptions

- The existing `YouTubeHomeScreen`/`YouTubeRepository` pipeline is the Home; the task's parallel
  `data/youtube/home/*` files were not created (would duplicate a working pipeline and two Home UIs).
- "Personalized Home OFF" means exactly the old local Home (implemented). "Logged out" shows the
  Connect card plus the user's own recent/most-played rows, because the priority fix explicitly asks
  for a Connect card and forbids the generic charts; it does not show the local-library list.
- Section titles are logged only through a generic allowlist ("listen again", "top picks", …; anything
  else becomes "other") so `AppLog` keeps its promise never to store artist names.
- STEP 7 reuses the existing `InnerTubeClient.reportPlayback` (which fetches the player response and
  reads `playbackTracking`) instead of a second player-response cache.
- The YouTube history mirror (`youtube_recents`) is device-wide user data, so Disconnect does not
  delete it; the feed/history response caches are account-scoped and cleared on Disconnect.
- No Room table/column was added (queries only), so no migration was needed.

## 4. Could not verify without running the app

- Whether the account's `FEmusic_home` really returns the expected shelf set for this account, and
  whether the extra `/videostats` params are accepted (all failures remain silent by design).
- Live 401/403 handling against a genuinely revoked cookie, and the WebView sign-in round-trip.
- Actual visual spacing of the new Connect card on small screens in both themes.
- Reinstall behaviour (Room is wiped, so the first signed-out launch is Connect-card-only until sign-in).

## 5. What to check when testing

1. Signed in, Home → only your own shelves ("Top picks", "Listen again", "Find your mood",
   "Your most played", albums/mixes…); no worldwide charts; no podcasts/shorts.
2. Settings → YouTube Music → "Personalized Home" OFF → Home is the old local Home; ON → your feed.
3. Disconnect (or fresh install) → Home shows the Connect card, not charts; Connect → feed replaces
   the page without restarting.
4. Play a YouTube song for ~30 s → it appears in YouTube Music's own history (other device/web).
5. History screen still shows local + YouTube plays in order, after reopening and after a resume
   (imported entries should not keep jumping to "now").
6. Pull-to-refresh loads fresh picks; a failed refresh keeps the current rows.
7. Logs screen → filter ALL → `YouTube Home: status=OK … authenticated=true … source=network|cache
   sections=… skipped=… titles=…`; a generic feed logs a `feed may be generic` warning.
8. Both themes (Normal and Glassmorphic) render the same Home; the Connect card uses theme surfaces.