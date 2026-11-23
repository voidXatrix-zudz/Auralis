# AURALIS Home Redesign — Working Notes

Branch: `home-redesign` (created from `now-playing-blend`).
Rules: Home only; no ADB/emulator; no new libraries; reuse existing data/navigation/playback.

## DO
- Pure black canvas, left-aligned plain section titles, big Top Picks cards, paged song columns with a peeking next page.
- Reuse existing data, navigation, play and menu functions.
- Keep one shared component per card type, used by both themes.
- Keep AURALIS's own mark, palette and wording.
- Commit after each step on branch `home-redesign`.

## DON'T
- Don't touch dock, mini-player, Now Playing, lyrics, Settings, Library, Explore, login, sync, downloads, equalizer.
- Don't copy any logo, name, artwork, font, icon, text or exact colour from the reference.
- Don't add libraries, permissions or network calls.
- Don't use ADB or SDK commands.
- Don't leave dead code, duplicate composables or commented-out code.
- Don't change package name, version codes or signing.

## NEXT
- Nothing required. Optional follow-ups if the look needs tuning: card-corner radii, the
  status-bar strip on the Glass theme, or adding "Show all" targets to the local-library rows.
- Pre-existing uncommitted work outside Home (glass-overlay screens, lyrics providers, Clerk auth,
  YouTube history sync) is intentionally left untouched in the working tree.

---

## STEP 1 — MAP (which composable draws which part today)

### Entry point
`HomeScreen.kt` decides:
1. `youtubeEnabled && personalizedHome` → `YouTubeHomeScreen` (BOTH themes; the Glass dock is not used here).
2. else Glassmorphic → `GlassHomeScreen` (local library).
3. else → inline Normal local library Column in `HomeScreen`.

### Signed-in / integration-on Home (both themes) — `YouTubeHomeScreen.kt`
| Part | Composable | Data |
|---|---|---|
| Header / avatar / refresh / account menu | `HomeHeader` | `accountName`, `avatarUrl`, `signedIn`; menu actions `onSignIn/onSignOut/onSwitchAccount` |
| Category chips | `CategoryChips` | `HomeCategory.entries`, `state.category` |
| One section | `HomeSection` | `HomeUiState.rows` (ordered `Map<String, HomeRow>`) |
| Section title / Show all | `SectionHeader` | `HomeRow.title`, `HomeRow.subtitle`, `HomeRow.showAll` |
| Track cards | `TrackCard` | `HomeRow.tracks` (style TRACKS/AUTO) |
| Big Top Picks | `LargePickCard` | `HomeRow.cards`, style LARGE_CARDS, `cardDisplay()` |
| Square collection | `CollectionCard` | `HomeRow.cards`, style SQUARE_TILES/AUTO |
| 16:9 video | `WideVideoCard` | `HomeRow.cards`, style WIDE_CARDS |
| Artist circle | `ArtistCard` | `HomeRow.cards`, style CIRCLE_CARDS |
| Load more | `LoadMoreCard` | `HomeRow.hasMore`, `onLoadMoreHomeRow` |
| Row loading | `RowPlaceholder` | `HomeRow.loading` |
| Whole-page loading | `HomeSkeleton` | no rows + refreshing/loading |
| Empty | `HomeEmpty` | signed in, no rows, no error |
| Error | `HomeErrorBanner` | `state.homeError` |
| Connect prompt | `YouTubeConnectCard` | `signedIn == false`, `expired` |
| Collection bottom sheet | `CollectionSheet` | `state.collection*` |
| Pull to refresh | `PullToRefreshBox` | `state.refreshing`, `onRefreshHome` |

Row order today is the insertion order of `HomeUiState.rows` built in `AppViewModel.loadSignedInHome` / `loadLocalHome`:
TOP_PICKS, RECENTS, MOODS, MOST_PLAYED, YouTube shelves…, QUICK_PICKS (failure path last).

### Local library Home — Normal (`HomeScreen.kt`)
| Part | Composable | Data |
|---|---|---|
| Header + Scan button | inline `Row` + `SectionTitle` | `sources`, `tracks`, `scanning` |
| Folder list | `FolderRow` | `sources` |
| Empty state | `EmptyLibraryHero` | `tracks.isEmpty()`, `sources` |
| Song list | `TrackRows` | `tracks`, `favorites`, `playlists` |

### Local library Home — Glass (`GlassScreens.kt: GlassHomeScreen`)
| Part | Composable | Data |
|---|---|---|
| Greeting + scan/settings | inline `Row`, `GlassIconButton` | `tracks`, `sources`, time of day |
| Folder chips | `GlassFolderChip` | `sources` |
| Hero carousel | `GlassHeroCarousel` | `tracks` sorted by `addedAt` |
| Recents/favourites rows | `GlassArtworkRow` | `tracks`, `favorites` |
| Albums row | `GlassAlbumRow` | `tracks.groupBy(album)` |
| All songs | `GlassTrackRow` | `tracks` |

### Sources of data feeding rows
- `AppViewModel.HomeUiState` / `HomeRow` (already curated by `HomeCurator` + classified by `HomeShelfLayout`).
- `HomeShelfBlock → HomeRow` happens in `AppViewModel.toHomeRow`.
- Local library data passed straight into `HomeScreen` from `AuralisRoot`
  (`tracks`, `sources`, `favorites`, `playlists`, `playlistEntries`, `playlistYtEntries`,
  `youtubePlaylists`, `spotifyImports`, `playbackTrackId`).

### Theme/colour tokens used by Home today
- Normal: `MaterialTheme.colorScheme.*` (surfaceContainerLow/High, primary, onBackground, onSurfaceVariant).
- Glass: `GlassTokens` (`Cream`, `Silver`, `Cream65`, `Surface`, `Indigo`) + `glassWindowPanelColor()`.
- Both: `LocalThemeStyle.current == ThemeStyle.GLASSMORPHIC` branch.

---

## STEP 1 — PLAN (decisions)
- New shared row models live in `ui/home/HomeRows.kt` as `HomeRowUi` (not `HomeRow`, because
  `com.auralis.app.ui.HomeRow` already exists and the two names would collide).
- One presentation per row type built once in `ui/home/HomeComponents.kt` and composed by both themes
  through a `HomeStyle` parameter (glass vs normal text/surface tokens).
- `YouTubeHomeScreen` becomes thin: it collects `HomeUiState`, maps to `HomeRowUi`, and draws.
- Local Home (integration off) becomes `LocalHomeFeed` used by both the Normal branch in
  `HomeScreen.kt` and the thin `GlassHomeScreen` wrapper.
- Category chips are removed from Home; `HomeCategory` data/state stays in the view model.
- Avatar in the Home top bar opens Settings (as specified). Home owns its top bar, so the root
  hides the shared app top bar on the Home tab and lets Home draw behind the status bar.
- Local "From your library" needs playlist covers/counts already computed by `buildLibraryPlaylists`;
  `AuralisRoot` passes that list into `HomeScreen` (no new network, same data as Library).

---

## STEP 2 — IMPLEMENTATION (done)

### New files (all under 500 lines)
- `ui/components/AuralisMark.kt` — AURALIS' own sound-wave mark, drawn in code (no bundled artwork).
- `ui/home/HomeRows.kt` — `HomeTopPick`, `LibraryTileData`, `HomeRowUi` (TopPicks / PagedSongs /
  LibraryTiles / Videos / SquareCards / Artists), the `HomeRow.toHomeRowUi()` mapper, section-order
  priority, and `buildLocalHomeRows()`.
- `ui/home/MoodShapes.kt` — deterministic geometric artwork for artwork-less cards.
- `ui/home/HomeComponents.kt` — `HomeDimens`, `HomeText`, `HomeCardPalette`, `HomeStyle`
  (`rememberHomeStyle()`), `HomeTopBar`, `HomeSectionTitle`, `TopPickCard`, `PagedSongColumns`
  (+ `SongRow` / `CompactVideoRow` / `SongOptionsMenu`), `LibraryTile`, `VideoCard`,
  `SquareArtCard`, `HomeArtistCard`, `HomeMoreCard`, `HomeSkeleton`, `HomeErrorBanner`,
  `HomeEmpty`, `HomeConnectCard`, `HomeEmptyLibrary`.
- `ui/home/HomeFeed.kt` — `HomeRowBlock` (one mapped section) and `LocalHomeFeed` (the whole
  integration-off Home, shared by Normal and Glass).

### Rewritten
- `YouTubeHomeScreen.kt` (895 → ~270 lines): maps `HomeUiState` → `HomeRowUi`, orders the rows,
  draws the black canvas + own top bar, keeps pull-to-refresh, skeleton/empty/error/Connect states
  and the collection `GlassSheet`.
- `HomeScreen.kt`: routes (YouTube feed / Glass / Normal) and delegates both local paths to
  `LocalHomeFeed`; keeps `EmptyLibraryHero` (Library still calls it) and `FolderRow`.
- `GlassScreens.kt`: `GlassHomeScreen` is now a thin wrapper around `LocalHomeFeed`; the old private
  hero/artwork/album rows and `greetingFor` were deleted.
- `AuralisRoot.kt`: hides the shared pinned `TopBar` on Home (Home draws its own), drops the
  Glass-Home-only avatar trailing, and passes `libraryPlaylists` + open-playlist/Library callbacks.

### Behaviour kept
- Refresh / Connect / error / skeleton states unchanged. Rows with fewer than 3 items are hidden
  (except the small-library "Quick picks" fallback so a 1–2 song library is never blank).
- Play, like, play-next, add-to-playlist and downloads all call the existing view-model functions.

### Wiring
- Avatar → Settings; section "Show all" → Explore tab; song three-dot → `SongOptionsMenu`
  (play next / like / download / add to playlist), the same actions as `TrackList`.
- Library tiles → `Destination.PlaylistDetail` / YouTube playlist / Library tab.

### Compile
- `:app:compileDebugKotlin` succeeds with JDK 21 and Gradle 8.14.3 (only pre-existing warnings).
- Note: `gradle/wrapper/gradle-wrapper.properties` had been bumped to Gradle 9.7.1, which AGP
  8.13.2 rejects; it was restored to the committed 8.14.3 to build.

### Polish
- Every literal size now lives in `HomeDimens`; the files are ~118–360 lines each (all < 500).
- Committed on branch `home-redesign` in three steps (shared system, size tokens, notes).