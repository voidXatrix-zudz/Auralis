# TASK_YT_HOME (READ-ONLY. Never edit or delete this file.)

## ROLE
You are a senior Android engineer (Kotlin, Jetpack Compose, Media3, Room, OkHttp, kotlinx.serialization or the JSON library already in the project). You are editing the existing AURALIS project. Read this whole file first, then do the steps in order. Do not skip steps, do not add extra features, do not redesign anything that is not listed.

## GOAL
Make Home show personalized YouTube Music recommendations (like the BitChord app) in BOTH themes (Normal and Glassmorphic), and make listening in AURALIS count as YouTube Music history when the user is logged in. A reference screen recording is attached: match its structure and content types, NOT its exact design.

## WHAT I (THE USER) WILL DO
I will build the APK and test on my phone myself. YOU MUST NOT run adb, an emulator, an SDK manager, or install/update any SDK or tool. Do not change Gradle versions or dependencies unless a step below says so. Your job is only to write clean, correct code by reading the existing code carefully.

## QUALITY RULES (very important)
1. Zero guessing. If the project already has a helper (InnerTube client, cookie store, SAPISIDHASH function, JSON parser, Coil setup, theme tokens, history DB), REUSE it. Find it by searching the code. Do not create a second one.
2. If something is unclear, choose the option that changes the fewest existing lines, and list it under "Assumptions" in your final report. Do not invent APIs.
3. Smallest possible change to existing files. New logic goes in NEW files. Existing files get only the minimum hook lines.
4. Everything you write must compile: check every import, every name, every nullable type, every suspend/non-suspend call, every Compose parameter against the real code before finishing. Re-read each file you edited once at the end as if you were the compiler.
5. Never crash on bad data: every JSON field is optional; use safe access and skip items that fail to parse. No `!!` operators on network data. Wrap network calls in try/catch and return a Result or null.
6. No network or disk work on the main thread. Use the existing coroutine scopes/dispatchers.
7. Do not touch: dock/navigation, Now Playing, lyrics, stream resolving (NewPipeExtractor / InnerTube / yt-dlp race), downloads, Clerk login, Settings layout, Equalizer, package name, existing Room data.
8. BitChord (reference app) is GPL-3.0. Do not copy its files or code. Write everything yourself.
9. If you add a Room table, add a proper Migration and bump the version. Never use destructive migration.
10. Keep both themes in sync: the same Home screen logic is shared; only colors, surfaces and glass effects differ per theme.

## STEP 0 - READ (no edits)
Read and understand: the current Home screen of the Normal theme and of the Glassmorphic theme, their ViewModel(s), the InnerTube client and how it builds requests (context, headers, cookie, SAPISIDHASH), the "YouTube integration" toggle and login state, the history Room entities/DAO/History screen, the code that starts playback of a YouTube track, and the theme tokens. Then write a short plan listing: files to create, files to edit, and the exact line/place of each hook. Then continue.

## STEP 1 - DATA MODELS (new file: data/youtube/home/HomeModels.kt)
Create these (plain Kotlin data classes/sealed classes, no Android dependencies):
- `data class HomeFeed(val sections: List<HomeSection>, val continuation: String?)`
- `data class HomeSection(val id: String, val title: String, val kind: SectionKind, val items: List<HomeItem>)`
- `enum class SectionKind { TOP_PICKS, RECENTS, MOOD, ALBUMS, QUICK_PICKS, PLAYLISTS, VIDEOS, SONGS, GENERIC }`
- `sealed class HomeItem { abstract val id: String; abstract val title: String; abstract val subtitle: String; abstract val thumbnailUrl: String? }`
  - `Song(videoId, title, subtitle, thumbnailUrl, artists, album?)`
  - `Album(browseId, ..., playlistId?)`
  - `Playlist(playlistId, browseId?, ...)`
  - `Artist(browseId, ...)`
  - `Video(videoId, ..., viewsText?)`
  - `Mood(browseId, params, title, colorArgb?)`

Decide SectionKind from the section title text with lowercase "contains" matching (e.g. "top picks" -> TOP_PICKS, "recent" or "listen again" -> RECENTS, "mood" -> MOOD, "album" -> ALBUMS, "quick picks" -> QUICK_PICKS, "from your library" -> PLAYLISTS, "video" -> VIDEOS, otherwise GENERIC). Unknown sections must still render (as GENERIC), never be dropped.

## STEP 2 - FETCH AND PARSE (new files: YouTubeHomeApi.kt, YouTubeHomeParser.kt)
Fetch:
- Reuse the existing InnerTube client. POST `https://music.youtube.com/youtubei/v1/browse` with body `{ "context": <existing WEB_REMIX context>, "browseId": "FEmusic_home" }`.
- Headers (reuse existing builder): Cookie, `Authorization = "SAPISIDHASH <ts>_<sha1("<ts> <SAPISID> https://music.youtube.com")>"`, `X-Goog-AuthUser: 0`, `Origin` and `X-Origin: https://music.youtube.com`, `Referer: https://music.youtube.com/`, same User-Agent the project already uses.
- Continuation: if the response has `sectionListRenderer.continuations[0].nextContinuationData.continuation`, load the next page with the same endpoint adding query params `ctoken=<token>&continuation=<token>&type=next`, body without browseId. Continuation response path: `continuationContents.sectionListContinuation`.

Parse (every step optional-safe):
- First page path: `contents.singleColumnBrowseResultsRenderer.tabs[0].tabRenderer.content.sectionListRenderer.contents[]`.
- For each entry handle `musicCarouselShelfRenderer` (title: `header.musicCarouselShelfBasicHeaderRenderer.title.runs[0].text`; items: `contents[]`), and `musicImmersiveCarouselShelfRenderer` the same way if present. Ignore other entry types.
- Item types inside a shelf:
  - `musicTwoRowItemRenderer`: title = `title.runs[0].text`; subtitle = all `subtitle.runs` texts joined; thumbnail = `thumbnailRenderer.musicThumbnailRenderer.thumbnail.thumbnails` (take the LAST/largest url); `navigationEndpoint` decides type: `watchEndpoint.videoId` -> Song or Video (Video if the subtitle contains "views" and thumbnail is 16:9 or the section kind is VIDEOS); `browseEndpoint` with `browseEndpointContextSupportedConfigs.browseEndpointContextMusicConfig.pageType` `MUSIC_PAGE_TYPE_ALBUM` -> Album, `MUSIC_PAGE_TYPE_PLAYLIST` -> Playlist, `MUSIC_PAGE_TYPE_ARTIST` -> Artist; `watchPlaylistEndpoint.playlistId` -> Playlist (a mix/radio).
  - `musicResponsiveListItemRenderer` (list rows): title from `flexColumns[0]...runs[0].text`; subtitle from `flexColumns[1]` runs joined; videoId from `playlistItemData.videoId` or the title run's `navigationEndpoint.watchEndpoint.videoId`; thumbnail from `thumbnail.musicThumbnailRenderer.thumbnail.thumbnails`.
  - `musicNavigationButtonRenderer` (mood tiles): title = `buttonText.runs[0].text`; browseId and params = `clickCommand.browseEndpoint.browseId` / `.params`; color = `solid.leftStripeColor` (Long to ARGB int) if present.
- If an item has no id or no title, skip that item. If a section ends with zero items, skip the section.
- DEBUG ONLY: if `BuildConfig.DEBUG`, write the raw JSON of the first Home response to the app's cache as `youtube_home_raw.json` and log a one-line summary "sections=N items=M skipped=K". This lets the user send you the raw file if some section does not show.

## STEP 3 - REPOSITORY + CACHE (new file: YouTubeHomeRepository.kt)
- `fun homeFeed(): Flow<HomeState>` where `HomeState = Loading | Content(feed, isRefreshing) | Error(message, cachedFeed?) | Disabled`.
- Order of emission: cached feed from disk instantly (JSON file in cacheDir, valid for any age but replaced on success), then network result.
- `loadMore()`: uses the stored continuation, appends sections, dedupes by section id, ignores calls while one is running.
- `refresh()`: fetches page 1 again and REPLACES the list (this must return new picks; never reuse the cached list as the result). Keep the old list visible until new data arrives.
- Return `Disabled` when the user is not logged in to YouTube or when the "Personalized Home" setting is off (Step 6). The Home screen then shows the existing old Home exactly as before.
- Network errors never clear the visible content; they only show a small inline error with Retry.

## STEP 4 - HOME UI (new file: ui/home/YouTubeHomeContent.kt, plus one hook in each theme's Home)
Layout: one LazyColumn (stable keys = section id), pull-to-refresh, bottom padding equal to the dock + mini-player height that the existing Home already uses (reuse that constant).
Header: keep the existing top bar (greeting, profile avatar that opens Settings). Do not change it.

Sections, rendered in the order returned by YouTube, by kind:
- TOP_PICKS: LazyRow of large cards, 260dp wide, 4:5 aspect, album art fills the card, title and subtitle at the bottom over a dark gradient scrim. Tap = play the mix/radio (Playlist/Album) or the song.
- RECENTS and QUICK_PICKS: a horizontally paged list where each page shows 4 rows (art 48dp rounded, title 1 line, subtitle 1 line, three-dot menu at the end). Page width = screen width minus 40dp so the next page peeks in. Use LazyRow with snap fling. The three-dot menu opens the existing song menu (play next, add to queue, add to playlist, like) if it exists; otherwise only "Play next" and "Add to queue" using the existing queue API.
- MOOD: LazyRow of tiles 112x112dp, 20dp corner, a subtle vertical gradient from the tile color (or theme accent if missing) to a darker tone, centered label under an icon-sized initial or mapped icon if one exists. Tap opens that mood's page using the existing browse/search-list screen if one exists; if none exists, start a radio from a search of the mood title.
- ALBUMS and PLAYLISTS: LazyRow of 150dp square cards, 16dp corner, title (1 line) and subtitle (1 line) below. Tap opens the existing album/playlist screen if present; otherwise plays it.
- VIDEOS: LazyRow of 16:9 cards, 280dp wide, title and "views" line below. Tap plays the audio of the video (videoId) through the normal player.
- SONGS and GENERIC: same list style as QUICK_PICKS for Song items; card style for everything else.
- Artist items use circular art.

Images: use the project's existing image loader (Coil) with size hints, crossfade 150ms, and a solid placeholder color from the theme. Never decode full-size art into small boxes.
Loading: simple shimmer placeholders (3 section skeletons). Error: one slim row "Couldn't refresh - Retry".
Performance: no heavy work in composition; use remember/derivedStateOf; item lambdas are stable; no nested vertical scrolling; keep recomposition small so scrolling stays smooth at 60/90/120 Hz.

## STEP 5 - DESIGN (so it is not a copy of the reference)
- Normal theme: use the existing Normal colors, text styles and card styles. Glassmorphic theme: Midnight Indigo #212842 background, Vanilla Cream #F0E7D5 text/icons; cards use the existing glass surface component and must respect Live blur and Glass clarity settings. Do not add any new purple tint.
- Section title: 20sp semi-bold with a 3dp-wide accent bar before it (accent = theme accent). "Show all" is optional; if there is no screen for it, do not show it.
- Corners: cards 20dp (Top picks), 16dp (albums), 12dp (list art). 1dp subtle outline on cards. Gaps: 12dp between cards, 24dp between sections.
- No BitChord name, logo or icon anywhere.

## STEP 6 - SETTINGS (two toggles only)
In the existing YouTube Music category in Settings (both themes), add: "Personalized Home" (default ON) and "Sync my listening to YouTube Music" (default ON). Store them with the existing settings/DataStore mechanism. When "Personalized Home" is OFF or the user is not logged in, Home is exactly the old Home.

## STEP 7 - REPORT LISTENING TO YOUTUBE MUSIC (new file: YouTubePlaybackReporter.kt)
Rules:
- Only when logged in, "Sync my listening" is ON, and the track has a YouTube videoId. Never report local, downloaded-only or Spotify-sourced tracks.
- Report each play at most ONCE: when played time reaches min(30 seconds, half of the duration). Keep a set of reported (videoId + playStartTimestamp) to prevent duplicates. Hook into the existing player listener (Media3 Player.Listener / the place where playback position is already tracked), with the minimum hook lines.
- Run entirely on a background coroutine, AFTER playback has started. It must never delay tap-to-play or change stream resolving.

Steps:
1. Get the player response for the videoId: reuse the cached player response from stream resolving if it exists; otherwise call `POST /youtubei/v1/player` with the WEB_REMIX context, the videoId and the same logged-in headers.
2. Read `playbackTracking.videostatsPlaybackUrl.baseUrl`.
3. Generate `cpn` = 16 random chars from A-Z a-z 0-9 - _.
4. GET `baseUrl + "&ver=2&c=WEB_REMIX&cver=<client version used in context>&cpn=<cpn>"` with the same Cookie and Authorization headers.
5. If `playbackTracking.videostatsWatchtimeUrl.baseUrl` exists, GET it once with the same extra params plus `&cmt=<secondsPlayed>&st=0&et=<secondsPlayed>&state=playing`.

On any failure: put the videoId in a small in-memory retry queue and retry up to 3 times with 30s backoff; then drop it silently. No toasts, no crashes.

## STEP 8 - HISTORY MERGE
- Fetch `POST /youtubei/v1/browse` with browseId `FEmusic_history` using the same headers. Sections are `musicShelfRenderer` (title like "Today"); rows are `musicResponsiveListItemRenderer`; parse them with the same row parser as Step 2.
- Merged history list = local Room history (has real timestamps) sorted newest first, then YouTube history items (YouTube gives order only) appended in YouTube's order, skipping any videoId already present. Mark each entry with source = AURALIS or YOUTUBE (do not show the source in UI, only keep it in the model).
- Use this merged list for (a) the Home "Recents" section, and (b) the existing History screen. Do not change the History screen's design.
- Refresh the YouTube history on app open, on app resume, and every 5 minutes while the app is in the foreground. Do not poll when logged out or when the setting is off.

## STEP 9 - SELF-CHECK BEFORE YOU FINISH (code reading only, no tools)
Go through this list and fix anything that fails:
- Every new file has the correct package line and imports.
- No unresolved references; names match the real existing classes.
- No `!!` on network data; every nullable is handled.
- No work on the main thread; no leaked coroutines (use viewModelScope or the existing app scope).
- Both themes call the same YouTubeHomeContent with their own theme tokens.
- Logged-out and setting-OFF paths still show the old Home.
- Player listener hook adds no delay to starting playback.
- Room: migration added if you changed the DB; otherwise no DB changes.
- Nothing in the "do not touch" list was edited.

## FINAL REPORT (short)
1. Files created. 2. Files edited and what exactly changed in each. 3. Assumptions you made. 4. Anything you could not verify without running the app. 5. The list of small things the user should check when testing (Home loads, pull-to-refresh gives new picks, a played song appears in YouTube Music history, history merge, both themes).
