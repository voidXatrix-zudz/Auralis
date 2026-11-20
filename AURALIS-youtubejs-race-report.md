# AURALIS — YouTube.js third provider + three-way stream race

Build: `AURALIS-youtubejs-race-v1.2.0.apk`
Package: `com.auralis.app` · versionCode 2 · versionName 1.2.0

## What this build adds

YouTube.js (`youtubei.js` 18.3.0, embedded) now runs as a **third stream provider** behind the same
provider contract as NewPipeExtractor and InnerTube. On a YouTube tap, all enabled providers start
together; the first one to return a non-empty, playable URL wins and the others are cancelled.

No UI, theme, or dock changes were made, with one deliberate exception: the requested on/off toggle
for YouTube.js in **Settings → Playback & Quality**.

### Race behaviour

- Candidates: `NEWPIPE`, `INNERTUBE`, `YOUTUBEJS` (YouTube.js is skipped only when the toggle is off
  or the engine has permanently failed).
- All candidates launch at `t≈0` (preferred provider first, others staggered ~25 ms so the race is
  still real and not a sequential await).
- First valid non-empty result wins immediately; losers are cancelled.
- A failed/empty result can never win; one provider failing does not stop the others.
- Overall race budget: **6 s**. If *every* provider fails, the error is surfaced immediately instead
  of waiting out the budget.
- Providers slower than **1.5 s** are marked `(slow)` in the log but may still win up to the 6 s cap.
- The winning provider per network type (metered / unmetered) is remembered and starts first next
  time; the other two still launch.
- `StreamSourceMode` now also has `YOUTUBEJS`, so the existing "Stream source" picker can pin
  YouTube.js alone. `ResolverKind.YOUTUBEJS` was added; `other` maps to `NEWPIPE` for retries.

### Logs you will see (in-app Logs screen, tag `YouTube`)

```
Race: NewPipe 1.1s ✔ | InnerTube 1.6s (slow) | YouTube.js 2.3s
Race: cache hit in 0 ms ✔ (no resolver needed)
Timing: tap→stream URL in 1420 ms (race 1210 ms)
Timing: tap→ready in 1680 ms
Timing: tap→audible in 1940 ms
```

`tap→stream URL`, `tap→ready`, and `tap→audible` are emitted once per tap (a generation counter means
re-tapping the same song still logs). Stale taps (>30 s old) are not attributed to later playback.

### Cache, prefetch, network choice

- Winning URL cached in memory; TTL is capped by the URL's own `expire` parameter minus 60 s (falls
  back to 55 min), so an about-to-expire signed URL is never served.
- In-flight dedupe remains, so a tap and a prefetch for the same song share one resolution.
- Audio format is chosen by the current `StreamQuality` (Wi-Fi / mobile / data-saver), preferring
  Opus then AAC at or below the bitrate target.
- The shared OkHttp client (connection pool 6 × 5 min, HTTP/2) is reused for every provider and for
  playback; a 2-byte ranged request warms the chosen audio host before the first real read.
- Engine warm-up is kicked off at app start on a private worker (`auralis-youtubejs` thread): it
  evaluates the bundle and then creates the Innertube session (config + player), so the first tap
  pays only one player request instead of the full start-up cost.

## Implementation map

| Area | File |
| --- | --- |
| QuickJS host, asset loading, bundle transform, fetch bridge | `data/youtube/YouTubeJsEngine.kt` |
| Provider adapter + URL validation + search fallback | `data/youtube/YouTubeJsProvider.kt` |
| Provider contract | `data/youtube/StreamProvider.kt` |
| N-way race, cache, stats, logs | `data/youtube/StreamResolver.kt` |
| Wiring + warm-up + search fallback | `data/youtube/YouTubeRepository.kt`, `AppContainer.kt` |
| Settings model/persistence/toggle | `domain/model/AppSettings.kt`, `data/settings/SettingsRepository.kt`, `ui/settings/SettingsSubPages.kt` |
| Tap → resolved → audible timing | `playback/PlaybackTiming.kt`, `ui/AppViewModel.kt`, `playback/PlaybackService.kt` |
| Embedded JS runtime | `assets/youtubejs/{polyfills,http,api,browser}.js` |
| QuickJS dependency + ABIs | `gradle/libs.versions.toml`, `app/build.gradle.kts` |

### Embedded JS pipeline

1. `polyfills.js` — console, timers, `TextEncoder/TextDecoder`, `URL`/`URLSearchParams`, `crypto`,
   `structuredClone` (prototype-preserving), `Intl`, `CustomEvent`, `ReadableStream`, `FormData`/
   `Blob`/`File`, plus guarded modern built-ins.
2. `http.js` — `Headers`/`Request`/`Response`/`AbortController` and a buffered `fetch` bridged to
   Kotlin via the `__nativeFetch` async binding (real OkHttp call on an IO thread; JSON envelope).
3. `browser.js` — youtubei.js 18.3.0. Its single trailing `export { … }` block is rewritten at load
   time to `globalThis.__YTJS = { … }`, so the bundle evaluates as a classic script (no module
   loader, no static imports).
4. `api.js` — `__auralis.warmup/resolveAudio/metadata/search` over one warm `Innertube` instance.

QuickJS contexts are not re-entrant, so evaluations are serialized with a mutex and pinned to one
worker thread; the first tap during warm-up waits for the build under the same deadline.

## Verification performed on this machine

The exact asset files were executed in an isolated VM context (no browser globals, so the polyfills
and shims were the only platform surface) against live YouTube:

- Bundle loads; `globalThis.__YTJS.Innertube` present; `__auralis` present.
- `warmup()` → `true` (session/player created).
- `search("believer imagine dragons")` → 20 songs with real titles, artists, albums, durations.
- `metadata(videoId)` → real title/artist/thumbnail/duration.
- `resolveAudio(videoId, 0)` → real URL:
  `https://rr1---sn-…googlevideo.com/videoplayback?…&itag=140…`
- The returned URL was fetched with `Range: bytes=0-1023`:
  **HTTP 206**, `content-type: audio/mp4`, `content-range: bytes 0-1023/3309731`, and a valid
  `ftyp` box — i.e. a genuinely playable AAC stream.

Measured in Node/V8 (not the device):

- Cold session create (`Innertube.create`, includes player base.js): **≈3.3 s**.
- Warm audio resolve: **≈0.2 s**.
- These are V8 numbers; QuickJS on-device will be slower and must be measured on the phone.

### Client findings (important)

Against a live player response, the youtubei.js client ids behave very differently:

| Client | Audio formats | Usable URL |
| --- | --- | --- |
| `IOS` | 2 | **direct `url`** |
| `YTMUSIC` | 4 | `signatureCipher` (Player deciphers) |
| `ANDROID`, `WEB`, `MWEB` | 4–6 | none (URLs stripped; PO-token case) |
| `ANDROID_VR`, `TV`, `VISIONOS` | — | no streaming data |

The provider therefore tries `IOS` → `YTMUSIC` → `ANDROID` → `WEB`, and skips a client that answers
without a usable audio URL rather than accepting it.

## Build / test status

- `:app:compileDebugKotlin` — clean (only pre-existing warnings elsewhere).
- `:app:testDebugUnitTest` — **167 tests, 0 failures, 0 errors, 0 skipped** (33 suites).
- `:app:assembleDebug` — **BUILD SUCCESSFUL**.

APK contents verified:

- `assets/youtubejs/browser.js` = 1,580,239 bytes
- `assets/youtubejs/polyfills.js` = 23,083 bytes
- `assets/youtubejs/http.js` = 7,999 bytes
- `assets/youtubejs/api.js` = 6,941 bytes
- `lib/arm64-v8a/libquickjs.so`, `lib/armeabi-v7a/libquickjs.so`, `lib/x86_64/libquickjs.so`

QuickJS binding: `io.github.dokar3:quickjs-kt:1.0.15` (1.0.16+ require compileSdk 37; this project
targets 36).

## Honest limitations — not verified on a device

This machine has no Android runtime or device attached, so the following are **not** measured yet:

- Actual QuickJS execution of the 1.58 MB bundle on-device (syntax/feature coverage). All JS was
  validated on V8 only; the polyfills are ES5-style and guarded for a narrower engine, but this is
  not proof.
- Real tap→audible latency on Wi-Fi and cellular. The 2–3 s target must be confirmed on the phone
  using the `Timing` lines above.
- Native `libquickjs.so` behaviour on 16 KB-page Android 15 devices (the AAR is prebuilt).
- YouTube may change client requirements (PO tokens) at any time, which could invalidate a client.

If the engine cannot start, the app logs `YouTube.js engine unavailable; continuing without it` and
the existing NewPipe/InnerTube race continues to work.

## How to check on the phone

1. Install `AURALIS-youtubejs-race-v1.2.0.apk`.
2. Settings → Playback & Quality: ensure **YouTube.js source** is on (default).
3. Tap a YouTube song, then open the Logs screen and look for `Race: …` (provider times + winner) and
   `Timing: tap→audible …`.
4. To exercise the engine alone, set **Stream source → YouTube.js only**.

## Artifact

```
C:\Users\Dipanjan\Desktop\AURALIS-youtubejs-race-v1.2.0.apk
size   36,925,991 bytes
sha256 D9909C18372A91B2704E90E2163F00E0430F02052784469E7ED19C78E927FDBD
```