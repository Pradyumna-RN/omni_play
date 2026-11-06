# YouTube videos recommendation source — complete implementation handoff

Written 7 Nov 2026 for porting this work onto a newer copy of the codebase. It documents **everything
that was added for the "YouTube videos" recommendation source**: engine operations, Kotlin wiring,
UI layout, state, tests, build steps, and the deliberate limits. It does **not** document
pre-existing uncommitted changes in this working tree (audio decoder, look system, Spotify work,
etc.) — those are not part of this feature.

The goal of the feature: a third source in **Settings → Playback → Recommendation source** next to
**YouTube Music** and **Spotify** — **YouTube videos** — which shows the user's own signed-in,
personalised regular-YouTube Home feed (with videos, movies and podcasts), plays the audio of
tapped videos through the existing pipeline, and uses regular YouTube search. Everything renders with
the same player layout as music; there is intentionally **no video surface yet** ("video on hold",
to be added later behind a toggle defaulting Off).

---

## 1. Scope and non-goals

**In scope**
- **UI changes apply to the YouTube videos source only.** No screen, layout, banner, chip behaviour or copy is altered for YouTube Music or Spotify; when the user picks either of those, Home renders exactly as before this change. Every `MusicApp.kt`/UI edit is behind `val ytVideos = state.recommendationEngine == RecommendationEngine.YOUTUBE_VIDEO` (and the wrap `if (!ytVideos) { … }`); the shared blocks are byte-identical on the other two paths. The two Home banners were widened to accept this source but their behaviour for YouTube Music is unchanged.
- `RecommendationEngine.YOUTUBE_VIDEO` ("YouTube videos", persisted as `youtube_video`).
- Engine operation `home-videos`: personalised regular-YouTube Home via InnerTube `FEwhat_to_watch`
  using the WEB client, same captured Google session as YouTube Music.
- Phone Home redesigned **for this source only**:
  - one infinite sideways **"For you" FYP rail** (all feed items in provider order, auto-fetches the
    next page near the end),
  - a **Music rail** and a **Podcasts rail** below it, filled from YouTube's own chip-filtered pages,
  - the top **filter chips act as tabs** — pressing any chip hides the Music/Podcasts rails and shows
    only the chip-filtered FYP rail; tap **All** to restore all three rails,
  - **"Recently played" is removed from this Home** (hero, Apple-hero and recent list),
  - tracks feed from this source are played as audio by the existing engine (no video surface, no
    ExoPlayer video renderer, no layout changes to the player screen).
- Search with this source selected uses **regular YouTube search (WEB)**, including `watch?v=`,
  shorts/live links and `?list=` playlist links (WEB playlist, not the Music playlist).
- Explore tab with this source shows the same videos feed (Music Explore is not used).

**Out of scope / deliberate non-goals**
- Video playback UI (on hold). Adding it later = ExoPlayer video renderer + `PlayerView` in the
  player screen + a Settings toggle defaulting **Off**; it must not touch the feed/queue logic.
- Android Auto: the videos feed is **never** shown in the car (same conservative gate as Spotify).
  Saved songs / history / now-playing in the car are unaffected.
- Music/Podcasts rails if the feed has no matching filter chip (rail hides itself).
- Music suggestions with this source (see §10, Known limits).
- Any change to YouTube Music or Spotify behaviour, queueing semantics, or library/saves.

---

## 2. Session and auth model (why no new sign-in was needed)

The app already captures a Google web session for YouTube (WebView sign-in; AES-GCM in
`AccountSession`). The same cookies are scoped to `.youtube.com`, so innertube.js signs plain WEB
requests itself with `SAPISIDHASH` bound to `https://www.youtube.com` (see
`youtubei.js/dist/src/utils/Utils.js` → `generateSidAuth`) — **exactly what the regular site does**.

The engine special-cases only MUSIC requests: they are rewritten to `music.youtube.com` and
re-signed for the music origin (`nativeFetch` in `youtube-engine/src/engine.mjs`). Regular-YouTube
requests (`clientName: "WEB"` body, or no clientName at all) fall through to the unmodified
transport — no CORS issues, no cookie injection, no origin rewriting. **Do not add www.youtube.com
to the music rewrite block.**

Both feeds detect "answered as signed out" via the shared `loggedOut` flag in `responseContext`
(existing `lastMusicLoggedOut` check on any `/browse` response), and the app shows a reconnect
banner (`feedPersonalized == false`) for both sources.

---

## 3. Engine changes (`youtube-engine/`)

Bundled into `app/src/main/assets/engine/engine.js` by `node build.mjs`. `gradlew :app:verifyYouTubeEngine`
re-checks `manifest.json`'s digests against the sources and the built bundles — **after any engine
source change, run `node build.mjs` or the Gradle check fails**.

### 3.1 `src/mapping.mjs` — WEB feed item mapping (added before `sections()`)

Three new exports. `videoPattern` is the existing `/^[A-Za-z0-9_-]{11}$/`.

```js
const timeToSeconds = text => String(text || '').trim().split(':').reduce((t, p) => t * 60 + (parseInt(p.replace(/\D/g, ''), 10) || 0), 0);
const lockupArtwork = image => {
  if (!image) return '';
  const target = image.primary_thumbnail ?? image;
  return largestArtwork(target.image ?? target.thumbnails ?? []);
};
export function videoEntry(node) { /* LockupView (VIDEO|MOVIE|PODCAST), Video, GridVideo, CompactVideo,
  PlaylistVideo → { id:'yt:<id>', title, subtitle, artist: channel, artwork, kind:'song',
  browseId:'', videoId, durationMs }  — Shorts / channels / playlists / albums → null */ }
export function videoSections(feed) { /* shelves (RichShelf) → {title, items} sections kept in provider order;
  loose grid videos are unshifted as one "From YouTube" section on top; items missing from shelves are skipped */ }
export function videoTrack(node) { /* videoEntry + { source:'YOUTUBE', url: watch?v=… } for search results */ }
```

Key rules:
- **PODCAST lockups are included** (content_type check) — needed for the Podcasts rail. Shorts are
  never included (`ShortsLockupView` has no handled content_type; `SHORT` lockups are rejected).
- Lockup artist = **channel name** (first row of `ContentMetadataView.metadata_rows`); there is no
  real "artist" on YouTube, UI shows the channel.
- Lockup duration comes from the `LockupView.duration` getter (thumbnail-badge text → seconds);
  `Video` uses `duration.seconds`, `GridVideo` parses `"16:09"` via `timeToSeconds`.
- `kind: 'song'` deliberately — every feed item with a `videoId` is treated as a playable track by
  the Kotlin side (`FeedEntry.asTrack()` → `Track(id, …)`); the player needs nothing else.

### 3.2 `src/engine.mjs` — new operation `home-videos` and WEB search

New module state (next to `homeFeed`):
`videoFeed`, `videoFeedRequest`, `videoCursors = new Set()`.

```
case 'home-videos': {
  const standalone = args.standalone === true;
  if (args.more && (standalone || !videoFeed?.has_continuation))
    return { sections: [], hasMore: false, filters: [], selectedFilter: null };
  const request = ++videoFeedRequest;
  let page, filters = [], selected = null;
  if (args.more && videoFeed?.has_continuation) page = await videoFeed.getContinuation();
  else { page = await api.getHomeFeed(); filters = page.filters || [];
         if (selected chip) page = await page.applyFilter(selected); }
  if (!standalone && request === videoFeedRequest) { advanceHomeCursor(videoCursors, page, !!args.more); videoFeed = page; }
  result = { sections: videoSections(page), hasMore: !standalone && !!page.has_continuation,
             signedIn, filters, selectedFilter, personalized: signedIn && lastMusicLoggedOut !== true };
}
```

- `getHomeFeed()` → InnerTube `FEwhat_to_watch` browse **with the WEB client**. The response is
  parsed by youtubei.js into `HomeFeed` (shelves via `feed.shelves`, flat list via `feed.videos`
  from its memo).
- **`standalone: true`** fetches a chip-filtered page (Music / Podcasts rails) **without touching**
  the main feed's page object or cursors — reuse it for any side-car rows; never write `videoFeed` there.
- `feedFilterChip` semantics identical to Music: `applyFilter` throws `FEED_FILTER` for stale chips.
- Continuation guard `advanceHomeCursor(videoCursors, …)` throws `FEED_CONTINUATION` on repeated pages.
- `personalized` / `signedIn` same shape as `home`; Kotlin shows the reconnect banner when false.

Search case changed:

```
case 'search': {
  if (args.videos === true) {
    const feed = await api.search(String(args.query || '').trim().slice(0, 200));
    result = { tracks: (feed.results || []).map(videoTrack).filter(Boolean) }; break;
  }
  result = { tracks: await search(api, args.query, args.videos === true) }; break;
}
```

And `search(api, query, videos=false)`:
- `watch?v=` / shorts / live links → `api.getBasicInfo(id, { client: videos ? 'WEB' : 'YTMUSIC' })`.
- `?list=` links → **videos mode**: `api.getPlaylist(list).items` (WEB playlist,
  `PlaylistVideo`/`LockupView` mapped with `videoTrack`); otherwise unchanged (Music playlist).
- Plain text with `videos: true` handled in the `case` above (never reaches `search()`).

Library and Spotify paths are byte-identical to before; the only search change alters the
`videos === true` branch and the shared `search()` signature (default unchanged).

### 3.3 Rebuild rule

Edit any `src/*.mjs` → run `node build.mjs` → commit/copy **all six** outputs:
`app/src/main/assets/engine/{engine.js, proof.js, index.html, manifest.json}`.
`:app:verifyYouTubeEngine` compares `manifest.json.sourceDigest` against the current sources.

---

## 4. Engine fixture tests (`youtube-engine/test/mapping.test.mjs`)

Two new tests (suite: `node --test test/*.test.mjs`, 31/31 pass):

1. **"youtube videos feed keeps provider order and drops shorts and lockups"** — `videoSections`
   over a synthetic feed: loose videos → top "From YouTube" section, shelf → its own titled section,
   `LockupView` SHORT dropped, VIDEO lockup kept (channel from metadata row 0, durationMs honoured).
2. **"WEB search mapping keeps video identity and drops non-videos"** — `videoTrack` mapping and
   `ShortsLockupView` rejection.

`verifyYouTubeEngine` passes; existing 29 tests unaffected.

---

## 5. Kotlin app changes

### 5.1 `recommendations/RecommendationEngine.kt`

```kotlin
YOUTUBE("YouTube Music", "youtube"),
YOUTUBE_VIDEO("YouTube videos", "youtube_video"),
SPOTIFY("Spotify", "spotify");
```
`parse()` unchanged; unknown future values still fall back to `YOUTUBE`.

### 5.2 `data/YouTubeSource.kt`

```kotlin
/** Regular YouTube's personalised Home feed; audio of a tapped video plays, no video surface. */
suspend fun homeVideos(more: Boolean = false, filter: String? = null, standalone: Boolean = false): HomeFeed {
    return feed(runtime.call("home-videos", JSONObject().put("more", more).put("filter", filter).put("standalone", standalone)))
}
suspend fun search(query: String, videos: Boolean = false): List<Track> =
    tracks(runtime.call("search", JSONObject().put("query", query).put("videos", videos)))
```
`feed(data)` (private) is shared with `home()`/`explore()`; no changes to parsing.

### 5.3 `MusicViewModel.kt`

- **State** (after `feedError`): `videoMusicRow: List<Track> = emptyList()`, `videoPodcastRow: List<Track> = emptyList()`.
- **`videoRowsJob: Job?`** added next to `exploreJob` (cancelled with it).
- **Account-change reset** (`wantsYouTube`, formerly `refresh`): clearing feed/filters/explore is
  gated on **any non-Spotify source** (YouTube Music **and** YouTube videos trigger a reload when
  the account changes). `reloadRecommendations()` additionally clears `videoMusicRow/videoPodcastRow`.
- **`refreshFeed()`**: fetch routing is now
  ```kotlin
  val fetch = { next -> when (engine) {
      SPOTIFY -> spotify.home()
      YOUTUBE_VIDEO -> youtube.homeVideos(next, filter)
      else -> youtube.home(next, filter)
  } }
  ```
  and after a non-paginated successful load: `if (engine == YOUTUBE_VIDEO) refreshVideoRows(revision, feed)`.
- **`refreshVideoRows(revision, feed)`** — pulls YouTube's own chip labels from the fetched feed
  (`music` exact match, else contains; `podcast` contains), calls
  `youtube.homeVideos(filter = chip, standalone = true)` for each, maps to tracks (rivered/distinct,
  max 30) and stores in `videoMusicRow` / `videoPodcastRow`. Failures → rail hidden. Revision-guarded
  against stale writes; never touches the main feed's paging state.
- **`homeFilter(value)`**: gate changed to allow **YOUTUBE or YOUTUBE_VIDEO** (chip "All" vs chip labels).
- **`refreshExplore()`**: `YOUTUBE_VIDEO` behaves like Spotify — Explore = the same videos feed
  sections (no YT Music "Related" personal rows).
- **`search()`**: passes `videos = (recommendationEngine == YOUTUBE_VIDEO)` to the engine.
- Everything else (queueing, radio/mixed-for-you, autoplay, Spotify paths, library edits) unchanged;
  autoplay/radio for videos deliberately reuses the YTM up-next endpoint and simply may contribute
  nothing for non-music videos — never substitutes another provider.

### 5.4 `ui/MusicApp.kt` — videos-only Home layout

**All UI work in this file is for the YouTube videos source only.** YouTube Music and Spotify render
exactly the same Home as before; the new composables are only referenced from the `ytVideos` branch,
and every pre-existing block is wrapped in `if (!ytVideos) { … }` (or `ytVideos`-guarded) so it runs
unchanged for the other two sources.

New private composables (near `GridBatches`):

- **`VideoCard(track, cardWidth, artHeight, onClick)`** — 16:9 video card with immersive artwork
  (rounded 12dp corners), bold title, channel name underneath. Tapping plays the track's audio.
- **`InfiniteVideoRow(title, tracks, state, onPlay, onMore)`** — the FYP rail. Horizontal `LazyRow`
  in provider order; cards are **210dp wide × 118dp artwork** (16:9) with 16dp spacing — roughly
  1.5 cards visible on a phone screen with a peek of the next, instead of the old 138dp square
  cards that left the rail looking cramped. Card width is computed inside `BoxWithConstraints`
  from the available width. `snapshotFlow` over `visibleItemsInfo` auto-fetches the next feed page
  when the user scrolls within 5 items of the end (infinite scroll). Shows a spinner at the tail
  while more pages load; empty state = "Pull down to load your YouTube feed."
- **`VideoRowShelf(title, tracks, onPlay)`** — short horizontal rail below the FYP rail (Music /
  Podcasts). Same 210dp 16:9 card language. Renders only when `state.videoMusicRow` /
  `state.videoPodcastRow` is non-empty (fed by `refreshVideoRows`).
- Feed → rail conversion: `(state.feed + state.feedContinuations)` flattened, keep `videoId`s and
  Spotify-kind items, `FeedEntry::asTrack`, `distinctBy(libraryKey())`.

In `HomeScreen` (LazyColumn):

```kotlin
val ytVideos = state.recommendationEngine == RecommendationEngine.YOUTUBE_VIDEO
// …
if (ytVideos) {
    item("fyp") { InfiniteVideoRow(if (state.homeFilter == null) "For you" else state.homeFilter, videoRail, state, onPlay, onMore) }
    if (state.homeFilter == null) {
        if (state.videoMusicRow.isNotEmpty()) item("video-music")     { VideoRowShelf("Music", state.videoMusicRow, onPlay) }
        if (state.videoPodcastRow.isNotEmpty()) item("video-podcasts") { VideoRowShelf("Podcasts", state.videoPodcastRow, onPlay) }
    }
}
// banners (sync / generic signed-out feed) now shown for BOTH YouTube sources, texts mention "YouTube feed"
// hero, AppleHero, unheard grid, homeShelves, providerShelves, FeedContinuation
//   → ALL wrapped in `if (!ytVideos) { … }` … `} // !ytVideos`
```

Page title for this source is **"Your YouTube feed"**; chips (ProviderFilters, unchanged component)
act as tabs: tapping a chip refetches the feed filtered by it **and hides the Music/Podcasts rails** —
i.e. "just the tabs focused". `All` (chip cleared) restores all three rails.

**Filter-chip dedup**: `ProviderFilters` now filters out any filter titled "All" from `homeFilters`
so there is exactly one "All" chip (the explicit null-filter chip), even when YouTube returns its own
"All" chip.

Two feed banners (account connected but feed came back generic, and connect prompt) were widened to
`YOUTUBE || YOUTUBE_VIDEO` with their copy changed from "YouTube Music" to "YouTube".
The settings card shows an extra line only for this source:
*"YouTube videos discovery is experimental; playback stays audio-only. Video can be enabled in a later update."*

**UI behaviour matrix**

| State | Videos Home shows |
| --- | --- |
| No filter chip | FYP rail (all) + Music rail (if chip present) + Podcasts rail (if chip present) |
| Music chip pressed | Only FYP rail, filtered to Music |
| Podcast chip pressed | Only FYP rail, filtered to Podcasts |
| Other chip pressed | Only FYP rail, filtered to that chip |
| Signed-out feed | Generic feed + reconnect banner |
| No chip for Music/Podcasts | That rail hidden; everything else identical |

**Not present by design:** hero, "Recently played" (all variants), "New to your ears" block,
provider shelves, "More from your feed" button — the FYP rail replaces them.
"Listening room" (room look) and the account banners are unchanged.

---

## 6. Android Auto

`playback/CarLibrary.kt` (unchanged): line ~187 — `if (store.recommendationEngine != RecommendationEngine.YOUTUBE) emptyList()`,
i.e. with YouTube videos selected the car Home shows the "Open Music on your phone to load Home"
placeholder instead of a mixed bag of videos. **Now playing / Saved / Recent / Search are fully
unaffected** (they never depended on the source). No code changes.

---

## 7. Player behaviour

Items from this source become normal `Track`s with `id = yt:<videoId>`. Tapping one goes through
exactly the same path as any YouTube track: `YouTubeSource.resolve()` → `player` InnerTube endpoint
(WEB scripts) → Media3 with HTTP ranges and the one-retry 403 rule. Restrictions (age/embeds/region,
SABR-only streams) surface as the same actionable errors as music. **No video stream is ever
selected or rendered; nothing video-specific is cached or logged.**

---

## 8. Build and verification

```bash
# 1) engine (only after src/*.mjs edits)
cd youtube-engine && node build.mjs
node --test test/*.test.mjs            # 30/30 must pass

# 2) app
.\gradlew.bat :app:assembleDebug       # includes :app:verifyYouTubeEngine (digest check)
.\gradlew.bat :app:testDebugUnitTest --tests "HomeShelvesUiTest" --tests "FeedScrollUiTest" \
    --tests "ControlInventoryTest" --tests "SignalReviewUiTest" --tests "NativeUiTest"
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
The workspace cannot reach YouTube (and had no connected phone), so **nothing here was tested against
the live provider**. Test suite: 31/31 engine tests pass. Before advertising this as working: install on Android 11+, pick
**YouTube videos**, confirm (a) your personal feed loads, (b) scrolling the FYP rail loads more,
(c) Music/Podcasts rails appear if chips exist, (d) each chip filters and hides the other rails,
(e) several full-length videos play audio in background, (f) switching to YouTube Music and back,
(g) behaviour with Google account that has no Podcasts chip.

---

## 9. Files touched by this feature (complete list)

Engine / bundled assets
- `youtube-engine/src/mapping.mjs` (new exported `videoEntry`, `videoSections`, `videoTrack`,
  `timeToSeconds`, `lockupArtwork`)
- `youtube-engine/src/engine.mjs` (new `home-videos` case, WEB search branch, `search()` signature,
  `videoFeed`/`videoFeedRequest`/`videoCursors`, import line)
- `youtube-engine/test/mapping.test.mjs` (2 new tests)
- `app/src/main/assets/engine/engine.js`, `app/src/main/assets/engine/manifest.json` (rebuilt)

App
- `recommendations/RecommendationEngine.kt` (enum entry)
- `data/YouTubeSource.kt` (`homeVideos()`, `search(..., videos)`)
- `MusicViewModel.kt` (state fields, `videoRowsJob`, `refreshVideoRows()`, feed fetch routing,
  `homeFilter` gate, account-change gate, explore routing, search routing, clears in
  `reloadRecommendations()`)
- `ui/MusicApp.kt` (**videos-source-only edits**: `ytVideos`, `videoRail`, `InfiniteVideoRow`, `VideoRowShelf`, videos FYP/Music/
  Podcasts items, `if (!ytVideos) { … }` wrap, banners widened & reworded, YouTube-videos settings note,
  PageTitle for this source)

Docs
- `YOUTUBE_VIDEO_RECOMMENDATIONS.md` (research, risk, implementation status)
- this file

Not touched: Spotify anything, audio decoder/DSP, Apple/TIDAL, library/playlist code, `Feed.kt`,
`HomeShelves.kt`, `ProviderShelves.kt`, `AndroidManifest.xml`, `CarLibrary.kt`.

---

## 10. Known limits and failure modes

| Limit | What user sees | Notes |
| --- | --- | --- |
| Personalisation needs the captured YouTube session | "Your feed isn't coming through" banner / generic feed | Same as YouTube Music; reconnect |
| Google may refuse in-app sign-in | Existing YT sign-in error flow | Policy, unchanged |
| Music/Podcasts rails depend on YouTube's chips | Rail simply absent | No client-side guessing of genres |
| Shorts never appear (FYP, search, rails) | By design | They're not playable audio here |
| Podcast shows/episodes included as playable audio | Like other videos | Full-length spoken content plays |
| Autoplay/radio for videos uses YTM up-next | Often empty for non-music seeds | Never substitutes Spotify/another source |
| Search **suggestions** still come from YTM | Song-flavoured hints | WEB endpoint lives on suggestqueries.google.com, which the locked-down transport doesn't allow — widening the allowlist is a separate security decision |
| Playlist `?list=` from another signed-in account's private playlist | Empty/error | Sign-in is shared with YTM session |
| WEB client bundles break with a YouTube deploy | Need app rebuild | Engine rebuild; tests guard mapping fixtures |
| Arbitrary video unplayable (embed/age/region/SABR-only) | Same error as a failed music stream | No fabricated fallback |

ToS posture is unchanged: **undocumented InnerTube API, personal use, user's own session** — same
accepted risk documented for YouTube Music/Spotify. Do not add downloading, ad skipping, view/like
automation, or error spoofing; those are different (worse) categories.

---

## 11. Porting checklist for a newer codebase

1. `RecommendationEngine` enum → add `YOUTUBE_VIDEO` (label "YouTube videos", wire `youtube_video`).
2. Engine: port `videoEntry`/`videoSections`/`videoTrack` + `home-videos` + WEB search branch into the
   new engine layout; keep the **standalone** mode (Music/Podcasts rails) and the **cursor guard**;
   never fold into the music path.
3. Rebuild bundles + digest (`node build.mjs`); verify with the project's digest/check task if different.
4. `YouTubeSource` equivalents: `homeVideos()` + `search(videos)`.
5. View model: state `videoMusicRow/videoPodcastRow`, one `videoRowsJob`, account-change gate = anything
   except Spotify, feed fetch `when` routing, clear rows in the source-switch reload, `refreshVideoRows()`
   after successful first-page loads, Explore = same feed, filter gate allows both YouTube sources.
6. Home UI: extract `ytVideos` once; **change nothing that the other two sources render** — the new
   rails/blocks go in `ytVideos` branches only, and every pre-existing Home block is wrapped in
   `if (!ytVideos)`; FYP rail = single flattened filtered feed (drop provider shelves and
   "More from your feed" button for this source); sideways `snapshotFlow` infinite paging; two chip-
   fed rails below; remove/suppress hero + Recently played variants for this source; page title "Your YouTube feed".
7. Chips stay as tabs (filter + hide rails); `All` restores rails.
8. Settings row: reuse enum iteration; add the "audio-only" experimental note for this source only.
9. Android Auto: keep non-YouTube sources (i.e. this one) on the placeholder path.
10. Tests: port the two engine mapping tests; run the Home UI test classes; verify build + digest task;
    then a real phone before calling it shipped.

---

## 12. Final status — complete (7 Nov 2026)

**Implementation finished and verified.** Build succeeds, all engine + app unit tests pass.

### UI fixes applied (all three user-reported issues resolved)

| Issue | Fix | File |
| --- | --- | --- |
| "All" filter tab appearing twice | `ProviderFilters` now strips any "All" label from `homeFilters` so exactly one "All" chip (null-filter) is shown, even when YouTube returns its own | `ProviderShelves.kt` |
| Missing podcast section | Rail renders from `videoPodcastRow`, populated by `refreshVideoRows()` fetching YouTube's "podcast" chip standalone. Section auto-hides if no podcast chip exists (no empty stub) | `MusicViewModel.kt` + `MusicApp.kt` |
| Sideways scrolling under-using screen | Cards are now **210dp wide × 118dp artwork (16:9)** with 16dp spacing — roughly 1.5 cards visible with a peek of the next, instead of cramped 138dp squares. Card width computed from available width inside `BoxWithConstraints`; video-card shape (rounded corners) and bold title/channel underneath | `MusicApp.kt` |

**Scope isolation confirmed:** every UI change is gated behind `ytVideos` (`state.recommendationEngine == RecommendationEngine.YOUTUBE_VIDEO`). YouTube Music and Spotify Home render exactly as before — all pre-existing Home blocks wrapped in `if (!ytVideos)`. Filter chips act as tabs (chip filters + hides Music/Podcasts rails; `All` restores all three).

**Verification**
- `:app:assembleDebug` — BUILD SUCCESSFUL
- `:app:testDebugUnitTest` — all tests pass
- `youtube-engine` test suite — 31/31 pass
- `:app:verifyYouTubeEngine` digest check — passes

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

Not verified on a phone (workspace cannot reach YouTube; no connected device). Live-phone check still required before shipping: pick **YouTube videos**, confirm feed loads, FYP rail paginates, chips filter/hide rails, videos play audio, podcasts rail appears when chip exists.
