# YouTube videos recommendation source — porting handoff

Reference implementation: this working tree (`ui-changes`, based on upstream `fa85af3` "Hum 0.4.0 beta 1").
Target: upstream `origin/main` **`bfdd0de` ("Hum 0.4.0 beta 6")**, which has **no `YOUTUBE_VIDEO` source at all**.
Port the whole feature; do not try to apply a diff blindly — upstream moved code between beta 1 and beta 6.

Everything below is **only for `RecommendationEngine.YOUTUBE_VIDEO`**. YouTube Music (`YOUTUBE`), Spotify,
library, playback, downloads and Android Auto must behave exactly as they do upstream.

---

## 0. Ground rules — read before editing

1. **Music tab = YouTube's official Music category**, i.e. `homeVideos(filter = "Music")` — the same
   filter chip the real YouTube home shows. It is **not** a channel feed.
2. **Do NOT reintroduce** the old channel implementation:
   - no `https://www.youtube.com/channel/UC-9-kyTW8ZkZNDHQJ6FgpwQ`
   - no engine op `channel-videos`
   - no `channelVideoSections`, `channelPlaylists`, `channelLockupEntry`
   - no `YouTubeSource.channelVideos`
   Those were explicitly rejected by the user and removed. Verify with grep before finishing:
   `Select-String -Path youtube-engine/src/*.mjs,app/src/main/java/labs/rtensor/music/data/YouTubeSource.kt -Pattern "channel-videos|channelVideos|channelLockup|UC-9-kyTW8ZkZNDHQJ6FgpwQ"` → 0 matches.
3. **Music is the default tab** of the video source. Chip order is `Music · Feed · Gaming · Live · …`.
   "Feed" is the plain YouTube home (it replaces the old "All" chip) and is selected when
   `homeFilter == null`. The chip row always starts at position 0 so Music is visible without scrolling right.
4. The video source's **non-Music** chips render `YouTubeVideoFeed` (YouTube-style 16:9 video cards).
   The **Music chip renders `ProviderHome`** (same shelf UI as the YouTube Music source).
5. **Music tab shelf layout** (like Explore's music tab), sections top to bottom:
   1. **From YouTube** — provider shelves (`providerShelves`) showing videos + mixes from the feed
   2. **Play it again** — listening-history tiles (`playItAgain`)
   3. **New to your ears** — `unheard` tracks (`TrackRow`)
   4. **Your mixes** — mix cards (`homeMixes`)
   5. **Recently played** — history rows (`LastPlayedRow`) at the bottom
   Consistent 16dp vertical spacing between sections.
6. Each tapped video plays as a **one-item queue** (`onPlay(track, listOf(track))`) so `RadioQueue`
   seeds that video's own next recommendations.
7. Change only the video-source branches. Every UI edit must be behind
   `state.recommendationEngine == RecommendationEngine.YOUTUBE_VIDEO`.

---

## 1. Change inventory

| File | Change |
| --- | --- |
| `youtube-engine/src/mapping.mjs` | add `timeToSeconds`, `lockupArtwork`, `videoEntry`, `playlistEntry`, `videoSections`, `videoTrack`; merge same-title sections in `sections()` |
| `youtube-engine/src/engine.mjs` | add `home-videos` op; WEB search branch; `radio` videos branch; `collection` playlist branch; `videoFeed`/`videoFeedRequest`/`videoCursors` |
| `youtube-engine/test/mapping.test.mjs` | add mapping tests |
| `app/src/main/assets/engine/{engine.js,manifest.json}` | **generated** — rebuild, never hand-edit |
| `…/recommendations/RecommendationEngine.kt` | add `YOUTUBE_VIDEO` enum entry |
| `…/data/YouTubeSource.kt` | add `homeVideos()`, `search(..., videos)`, `radio(..., videos)` |
| `…/data/Feed.kt` | add `views`, `published`, `channelArtwork` |
| `…/HomeFeedMore.kt` | merge/dedupe same-title sections across pages |
| `…/MusicViewModel.kt` | feed routing, Music default, `homeFilter`, explore, search, radio |
| `…/ui/ProviderShelves.kt` | video-source chip order (`Music · Feed · …`) + scroll-to-start |
| `…/ui/MusicApp.kt` | YouTube header + `AppWordmark`, Home route branching, `YouTubeVideoFeed`, `YouTubeMusicHome`, `YouTubeVideoCard`, Settings `FlowRow` |

Upstream beta 6 anchors (line numbers will drift; grep the names):
`RecommendationEngine.kt` enum; `YouTubeSource.kt`; `MusicViewModel.refreshFeed` (~424), `homeFilter` (~449),
`refreshExplore` (~478), `providerRadio` (~596), `reloadRecommendations` (~586), `search` (~769);
`MusicApp.AppHeader` (~617), `PersonalScreen` (~899), `ProviderHome` (~1417), `SettingsScreen` (~2612);
`ProviderShelves.ProviderFilters`.

---

## 2. Engine

### 2.1 `youtube-engine/src/mapping.mjs`

Insert before `sections()` (after `audioVersion`). `videoPattern` and `largestArtwork` already exist.

```js
const timeToSeconds = text => String(text || '').trim().split(':').reduce((total, part) => total * 60 + (parseInt(part.replace(/\D/g, ''), 10) || 0), 0);
const lockupArtwork = image => {
  if (!image) return '';
  const target = image.primary_thumbnail ?? image;
  return largestArtwork(target.image ?? target.thumbnails ?? []);
};
/** A regular YouTube home-feed item: full videos and movies only; no Shorts, playlists or channels. */
export function videoEntry(node) {
  if (!node) return null;
  const type = node.type || '';
  let id = '', title = '', channel = '', artwork = '', channelArtwork = '', views = '', published = '', durationSec = 0;
  if (type === 'LockupView') {
    if (node.content_type !== 'VIDEO' && node.content_type !== 'MOVIE' && node.content_type !== 'PODCAST') return null;
    id = node.content_id || '';
    title = text(node.metadata?.title?.toString?.());
    const rows = node.metadata?.metadata?.metadata_rows || [];
    const parts = (rows[0]?.metadata_parts || []).map(part => text(part.text?.toString?.())).filter(Boolean);
    channel = parts[0] || '';
    views = parts.find(part => /\bviews?\b/i.test(part)) || '';
    published = parts.find(part => /(ago|streamed|premiered|watching)/i.test(part)) || '';
    artwork = lockupArtwork(node.content_image);
    channelArtwork = lockupArtwork(node.avatar || node.channel_thumbnail || node.metadata?.image);
    durationSec = node.duration?.seconds || 0;
  } else if (type === 'Video' || type === 'GridVideo' || type === 'CompactVideo' || type === 'PlaylistVideo') {
    id = node.video_id || '';
    title = text(node.title?.toString?.());
    channel = text(node.author?.name || node.byline_text?.toString?.());
    artwork = largestArtwork(node.thumbnails || []);
    channelArtwork = largestArtwork(node.author?.thumbnails || []);
    views = text(node.view_count_text?.toString?.());
    published = text(node.published_time_text?.toString?.());
    durationSec = node.duration?.seconds ?? (typeof node.duration === 'object' && node.duration?.text ? timeToSeconds(node.duration.text) : 0);
  } else return null;
  if (!videoPattern.test(id) || !title) return null;
  if (!views) views = (node.metadata?.metadata?.metadata_rows?.[0]?.metadata_parts || [])
    .map(part => text(part.text?.toString?.())).find(part => /\bviews?\b/i.test(part)) || '';
  if (!published) published = (node.metadata?.metadata?.metadata_rows?.[0]?.metadata_parts || [])
    .map(part => text(part.text?.toString?.())).find(part => /(ago|streamed|premiered|watching)/i.test(part)) || '';
  return { id: `yt:${id}`, title, subtitle: channel, artist: channel, artwork, channelArtwork, views, published,
    kind: 'song', browseId: '', videoId: id, durationMs: Math.max(0, durationSec) * 1000 };
}
/** A playlist from a YouTube shelf: kind 'playlist', browseId set, no videoId — opened as a collection. */
export function playlistEntry(node) {
  if (!node) return null;
  const type = node.type || '';
  let id = '', title = '', channel = '', artwork = '', browseId = '';
  if (type === 'LockupView') {
    if (node.content_type !== 'PLAYLIST') return null;
    id = node.content_id || '';
    title = text(node.metadata?.title?.toString?.());
    browseId = id;
    const rows = node.metadata?.metadata?.metadata_rows || [];
    const parts = (rows[0]?.metadata_parts || []).map(part => text(part.text?.toString?.())).filter(Boolean);
    channel = parts[0] || '';
    artwork = lockupArtwork(node.content_image);
  } else if (type === 'GridPlaylist' || type === 'Playlist') {
    id = node.playlist_id || node.id || '';
    title = text(node.title?.toString?.());
    browseId = id;
    channel = text(node.author?.name || node.byline_text?.toString?.());
    artwork = largestArtwork(node.thumbnails || []);
  } else return null;
  // YouTube playlist ids (PL...) and mix/radio ids (RD...) are longer than an 11-char video id.
  if (!id || id.length < 10) return null;
  if (!title) return null;
  return { id: `yt:${id}`, title, subtitle: channel, artist: channel, artwork, kind: 'playlist', browseId, videoId: '', durationMs: 0 };
}
/** The YouTube (videos) home feed, kept in the provider's order; videos and playlists. */
export function videoSections(feed) {
  const shelves = feed.shelves || [];
  const inShelves = new Set();
  const sections = [];
  for (const shelf of shelves) {
    const contents = shelf.contents || [];
    const videos = contents.map(node => { inShelves.add(node); return videoEntry(node); }).filter(Boolean);
    const playlists = contents.map(playlistEntry).filter(Boolean);
    const title = text(shelf.title?.toString?.()) || 'More on YouTube';
    if (videos.length) sections.push({ title, items: videos });
    if (playlists.length) sections.push({ title, items: playlists });
  }
  const loose = (feed.videos || []).filter(node => !inShelves.has(node)).map(videoEntry).filter(Boolean);
  if (loose.length) sections.unshift({ title: 'From YouTube', items: loose });
  return sections;
}
/** Track-shaped mapping for WEB search results (same shape as [track]). */
export function videoTrack(node) {
  const item = videoEntry(node);
  return item ? { id: item.id, title: item.title, artist: item.artist, artwork: item.artwork,
    durationMs: item.durationMs, source: 'YOUTUBE', url: `https://www.youtube.com/watch?v=${item.videoId}` } : null;
}
```

Then replace `sections()` with the same-title merge (prevents duplicate "From YouTube" headers):

```js
export function sections(feed) {
  // Keep the provider's section, item and duplicate order. No app ranking; music only.
  const mapped = (feed.sections || []).map(section => ({ title: text(section.header?.title || section.title) || 'From YouTube Music',
    items: (section.contents || []).filter(isMusic).map(entry).filter(Boolean) })).filter(s => s.items.length);
  // The initial page can repeat a shelf name (YouTube Videos often returns several "From YouTube"
  // shelves). Merge same-title sections at the first one's position so the header is drawn once.
  const seen = new Map();
  const merged = [];
  for (const section of mapped) {
    const existing = seen.get(section.title);
    if (existing) existing.items.push(...section.items);
    else { seen.set(section.title, section); merged.push(section); }
  }
  return merged;
}
```

Short explanation: `videoSections` keeps provider order; a `LockupView`'s first metadata row is split into
channel / views / published; Shorts, channels and albums are dropped; videos keep `kind: 'song'` so the
existing audio pipeline plays them unchanged. Playlists/mixes are mapped separately via `playlistEntry`
and emitted as their own shelf section below videos.

### 2.2 `youtube-engine/src/engine.mjs`

**Import line** — replace the mapping import with:

```js
import { track, sections, safeError, largestArtwork, isMusic, audioVersion, videoSections, videoTrack } from './mapping.mjs';
```

**Module state** — add `videoFeed`, `videoFeedRequest` to the top-level `let` (alongside `homeFeed`):

```js
let tubePromise, homeFeed, feedRequest = 0, videoFeed, videoFeedRequest = 0, playerPromise, playerScript = '', webConfigPromise, challengePromise, challengeExpires = 0, dataSyncId = '', accountProfilePromise;
```

and next to `const homeCursors = new Set();`:

```js
const videoCursors = new Set();
```

**Search** — change the helper signature `async function search(api, query)` → `async function search(api, query, videos = false)`.
Inside it:

```js
// direct links
const info = await api.getBasicInfo(id, { client: videos ? 'WEB' : 'YTMUSIC' });
// playlists
if (list && /^[A-Za-z0-9_-]{10,100}$/.test(list)) return videos
  ? (await api.getPlaylist(list)).items.map(videoTrack).filter(Boolean)
  : (await api.music.getPlaylist(list)).contents?.map(track).filter(Boolean) || [];
```

Replace the `case 'search'` one-liner with:

```js
case 'search': {
  if (args.videos === true) {
    // Regular YouTube search (WEB client); videos only, playable audio.
    const feed = await api.search(String(args.query || '').trim().slice(0, 200));
    result = { tracks: (feed.results || []).map(videoTrack).filter(Boolean) }; break;
  }
  result = { tracks: await search(api, args.query, args.videos === true) }; break;
}
```

**New `home-videos` case** — insert right before `case 'home':`:

```js
case 'home-videos': {
  // Regular YouTube (WEB client) Home: the listener's own recommended videos.
  // Audio of a tapped video plays through the existing pipeline; no video surface yet.
  // `standalone` fetches a category page (Music / Podcasts) without touching the main feed.
  const standalone = args.standalone === true;
  if (args.more && (standalone || !videoFeed?.has_continuation)) {
    result = { sections: [], hasMore: false, filters: [], selectedFilter: null }; break;
  }
  const request = ++videoFeedRequest;
  let page, filters = [], selected = null;
  if (args.more && videoFeed?.has_continuation) page = await videoFeed.getContinuation();
  else {
    page = await api.getHomeFeed(); filters = page.filters || []; selected = args.filter || null;
    if (selected) {
      // A signed-out session has no category chips; fall back to the unfiltered home instead
      // of failing the whole feed when the Music tab asks for its category.
      if (!filters.length) selected = null;
      else if (!filters.includes(selected)) throw failure('FEED_FILTER', 'This YouTube category is no longer available. Choose Feed to load the current categories.');
      else page = await page.applyFilter(selected);
    }
  }
  if (!standalone && request === videoFeedRequest) { advanceHomeCursor(videoCursors, page, !!args.more); videoFeed = page; }
  const signedIn = !!api.session.logged_in;
  result = { sections: videoSections(page), hasMore: !standalone && !!page.has_continuation, signedIn, filters, selectedFilter: selected,
    personalized: signedIn && lastMusicLoggedOut !== true }; break;
}
```

Notes:
- `api.getHomeFeed()` here must stay the **regular YouTube** feed (`FEwhat_to_watch`, WEB client). Do **not**
  change it to `api.music.getHomeFeed()`.
- `standalone` is for side fetches and must not overwrite `videoFeed` or cursors.
- The "no chips → unfiltered home" fallback is required: signed-out sessions return `filters: []`, and the
  Music tab requests `"Music"`.
- Error copy says **Feed** (the renamed chip), not "All".

**Playlist collection** — tapping a playlist shelf item opens it as a collection. Handle `kind === 'playlist'`
in the engine's `collection` case via the WEB playlist endpoint (regular YouTube `PL…` playlists don't resolve
through the Music client):

```js
if (args.kind === 'playlist') {
  const page = await api.getPlaylist(args.id);
  result = { tracks: (page.items || []).map(videoTrack).filter(Boolean) }; break;
}
```

**Radio (next recommendations)** — videos use YouTube's autoplay "next" feed, not Music up-next:

```js
case 'radio': {
  if (!/^[A-Za-z0-9_-]{11}$/.test(args.videoId)) throw failure('LINK', 'Choose a valid YouTube recording.');
  const videos = args.videos === true;
  let panel;
  if (videos) {
    const info = await api.getInfo(args.videoId, { client: 'WEB' });
    panel = { contents: info.watch_next_feed || [] };
  } else {
    panel = await api.music.getUpNext(args.videoId, true);
  }
  const seen = new Set([args.videoId]);
  result = { tracks: (panel?.contents || []).map(node => videos ? videoTrack(node) : track(audioVersion(node))).filter(t => t && !seen.has(t.id.slice(3)) && seen.add(t.id.slice(3))).slice(0, 50) }; break;
}
```

### 2.3 Tests — `youtube-engine/test/mapping.test.mjs`

Add:

```js
test('same-title sections from one page merge into a single header', () => {
  const a = { id: 'aaaaaaaaaaa', title: 'Zebra', artists: [{ name: 'One' }], item_type: 'song' };
  const b = { id: 'bbbbbbbbbbb', title: 'Apple', artists: [{ name: 'Two' }], item_type: 'song' };
  const feed = sections({ sections: [ { header: { title: 'From YouTube' }, contents: [a] }, { header: { title: 'From YouTube' }, contents: [b, a] } ] });
  assert.deepEqual(feed.map(s => s.title), ['From YouTube']);
  assert.deepEqual(feed[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:bbbbbbbbbbb', 'yt:aaaaaaaaaaa']);
});

test('WEB search mapping keeps video identity and drops non-videos', async () => {
  const { videoTrack } = await import('../src/mapping.mjs');
  const trackLike = videoTrack({ type: 'Video', video_id: 'jNQXAC9IVRw', title: { toString: () => 'Video' },
    author: { name: 'Channel' }, thumbnails: [{ url: 'https://i/x', width: 640, height: 360 }], duration: { seconds: 19 } });
  assert.equal(trackLike.id, 'yt:jNQXAC9IVRw');
  assert.equal(trackLike.url, 'https://www.youtube.com/watch?v=jNQXAC9IVRw');
  assert.equal(trackLike.durationMs, 19000);
  assert.equal(videoTrack({ type: 'ShortsLockupView' }), null);
});

test('youtube videos feed keeps provider order and drops shorts and lockups', async () => {
  const { videoSections } = await import('../src/mapping.mjs');
  const video = (id, title, extra = {}) => ({ type: 'Video', video_id: id, title: { toString: () => title },
    author: { name: 'Channel' }, thumbnails: [{ url: 'https://i/' + id, width: 640, height: 360 }], duration: { seconds: 120 }, ...extra });
  const shelf = { type: 'Shelf', title: { toString: () => 'Shelf title' }, contents: [video('bbbbbbbbbbb', 'In shelf')] };
  const fakeFeed = {
    shelves: [shelf],
    videos: [
      video('aaaaaaaaaaa', 'First'),
      ...shelf.contents,
      { type: 'LockupView', content_type: 'SHORT', content_id: 'ccccccccccc', metadata: { title: { toString: () => 'Short' } } },
      { type: 'LockupView', content_type: 'VIDEO', content_id: 'ddddddddddd', content_image: { image: [{ url: 'https://i/ddddddddddd', width: 640, height: 360 }] },
        metadata: { title: { toString: () => 'Lockup' }, metadata: { metadata_rows: [{ metadata_parts: [{ text: { toString: () => 'Lockup channel' } }] }] } }, duration: { seconds: 200 } },
    ],
  };
  const out = videoSections(fakeFeed);
  assert.deepEqual(out.map(s => s.title), ['From YouTube', 'Shelf title']);
  assert.deepEqual(out[0].items.map(i => i.id), ['yt:aaaaaaaaaaa', 'yt:ddddddddddd']);
  assert.equal(out[0].items[1].artist, 'Lockup channel');
  assert.equal(out[0].items[1].durationMs, 200000);
  assert.equal(out[1].items[0].id, 'yt:bbbbbbbbbbb');
});
```

(The reference branch also carries an unrelated MPR-album test/fix; port it only if upstream lacks it.)

### 2.4 Rebuild the bundle — mandatory

```powershell
npm run build --prefix youtube-engine
```

This regenerates `app/src/main/assets/engine/{engine.js,manifest.json}`. Never hand-edit those.
`:app:verifyYouTubeEngine` fails the build if `manifest.json.sourceDigest` is stale.

---

## 3. Android data layer

### 3.1 `recommendations/RecommendationEngine.kt`

```kotlin
enum class RecommendationEngine(val label: String, val wire: String) {
    YOUTUBE("YouTube Music", "youtube"), YOUTUBE_VIDEO("YouTube videos", "youtube_video"), SPOTIFY("Spotify", "spotify");
    companion object {
        fun parse(value: String?) = entries.firstOrNull { it.wire == value } ?: YOUTUBE
    }
}
```

### 3.2 `data/YouTubeSource.kt`

```kotlin
suspend fun search(query: String, videos: Boolean = false): List<Track> = tracks(runtime.call("search", JSONObject().put("query", query).put("videos", videos)))
/** Regular YouTube's personalised Home feed; audio of a tapped video plays, no video surface. */
suspend fun homeVideos(more: Boolean = false, filter: String? = null, standalone: Boolean = false): HomeFeed {
    return feed(runtime.call("home-videos", JSONObject().put("more", more).put("filter", filter).put("standalone", standalone)))
}
/** YouTube Music's radio for a song: the same up-next mix its own player continues with. */
suspend fun radio(track: Track, exclude: Collection<String> = emptyList(), videos: Boolean = false): List<Track> {
    if (track.source != Source.YOUTUBE) return emptyList()
    val videoId = track.id.removePrefix("yt:").takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return emptyList()
    val skip = exclude.toSet() + track.id
    return tracks(runtime.call("radio", JSONObject().put("videoId", videoId).put("videos", videos))).filter { it.id !in skip }
}
```

Do **not** add `channelVideos`.

### 3.3 `data/Feed.kt`

```kotlin
data class FeedEntry(val id: String, val title: String, val subtitle: String,
    val artwork: String, val kind: String, val browseId: String, val videoId: String, val durationMs: Long = 0, val artist: String = "", val params: String = "",
    val views: String = "", val published: String = "", val channelArtwork: String = "") {
    fun asTrack() = if (kind == "spotify_track") labs.rtensor.music.spotify.SpotifyItem.from(this).track()
        else Track(id, title, artist.ifBlank { subtitle }, "https://www.youtube.com/watch?v=$videoId", artwork, durationMs)
    companion object {
        fun parse(o: JSONObject) = FeedEntry(o.optString("id"), o.optString("title"), o.optString("subtitle"),
            o.optString("artwork"), o.optString("kind"), o.optString("browseId"), o.optString("videoId").takeUnless { it == "null" }.orEmpty(), o.optLong("durationMs"), o.optString("artist"), o.optString("params"),
            o.optString("views"), o.optString("published"), o.optString("channelArtwork"))
    }
}
data class FeedSection(val title: String, val items: List<FeedEntry>)
data class HomeFeed(val sections: List<FeedSection>, val hasMore: Boolean, val signedIn: Boolean = false, val personalized: Boolean = false, val filters: List<String> = emptyList(), val selectedFilter: String? = null)
```

---

## 4. `MusicViewModel.kt`

### 4.1 Feed routing + Music default (`refreshFeed`)

```kotlin
fun refreshFeed(more: Boolean = false) {
    if (more && (mutable.value.feedBusy || !mutable.value.feedHasMore)) return
    feedJob?.cancel()
    val revision = recommendationRevision
    val engine = mutable.value.recommendationEngine
    val filter = mutable.value.homeFilter
    // The YouTube videos source's Music chip is YouTube's own Music category, the same tab the
    // official YouTube home shows. The video source's other chips keep their own category feeds.
    // Music is the tab the video source opens on; "Feed" is the YouTube home itself.
    val engineFilters = mutable.value.homeFilters
    val defaultMusic = engine == RecommendationEngine.YOUTUBE_VIDEO && filter == null && engineFilters.isEmpty() && mutable.value.feed.isEmpty()
    val musicTab = engine == RecommendationEngine.YOUTUBE_VIDEO && (defaultMusic || filter?.equals("music", ignoreCase = true) == true)
    if (defaultMusic) mutable.update { it.copy(homeFilter = "Music") }
    val request = ++feedRevision
    feedJob = viewModelScope.launch {
        mutable.update { it.copy(feedBusy = true, feedAppending = more, feedError = null) }
        try {
            val fetch: suspend (Boolean) -> HomeFeed = { next -> when (engine) {
                RecommendationEngine.SPOTIFY -> spotify.home()
                RecommendationEngine.YOUTUBE_VIDEO -> youtube.homeVideos(next, if (musicTab) filter ?: "Music" else filter)
                else -> youtube.home(next, filter)
            } }
            if (more) {
                val added = loadMoreHomeFeed(mutable.value) { fetch(true) }
                if (revision == recommendationRevision && request == feedRevision) mutable.update { it.withMoreHomeFeed(added).copy(feedBusy = false, feedAppending = false) }
                return@launch
            }
            val feed = fetch(false)
            if (revision != recommendationRevision || request == feedRevision) return@launch
            // YouTube's Music category page is videos-only. Append the mixes/playlists shelves
            // from the general home feed as a section below the videos, so the Music tab shows
            // videos first and mixes underneath (like the official YouTube Music tab).
            val sections = if (musicTab) {
                val mixes = try { youtube.homeVideos(false, null, standalone = true) } catch (_: Exception) { null }
                val mixSections = mixes?.sections?.filter { it.items.any { it.kind == "playlist" } } ?: emptyList()
                feed.sections + mixSections
            } else feed.sections
            mutable.update {
                val base = it.withHomeFeed(feed.copy(sections = sections), engine == RecommendationEngine.SPOTIFY || !it.account.connected || feed.personalized)
                    .copy(feedBusy = false, feedAppending = false)
                if (musicTab) base.copy(homeFilters = engineFilters.ifEmpty { feed.filters }, homeFilter = filter ?: "Music") else base
            }
            if (mutable.value.forYou.isEmpty()) refreshForYou()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (revision == recommendationRevision && request == feedRevision) mutable.update { it.copy(feedBusy = false, feedAppending = false, feedError = catalogueErrorMessage(e, CatalogueMessages.FEED)) } }
    }
}
```

Why it matters:
- `defaultMusic` selects Music before the first fetch, so there is no flash of the Feed tab.
- `engineFilters.ifEmpty { feed.filters }` keeps the video source's chip row even when the first
  (Music-filtered) page reports no chips.
- `musicTab` forces the filter to `"Music"` (category), never a channel.
- Mixes are appended as shelf sections below videos on the Music tab only.

### 4.2 `homeFilter`

```kotlin
fun homeFilter(value: String?) {
    if (mutable.value.recommendationEngine != RecommendationEngine.YOUTUBE && mutable.value.recommendationEngine != RecommendationEngine.YOUTUBE_VIDEO) return
    // The video source's Music tab is its home default even when YouTube returned no chip list.
    val musicDefault = mutable.value.recommendationEngine == RecommendationEngine.YOUTUBE_VIDEO && value?.equals("music", ignoreCase = true) == true
    if (value != null && value !in mutable.value.homeFilters && !musicDefault) return
    mutable.update { it.copy(homeFilter = value) }
    refreshFeed()
}
```

### 4.3 Explore

```kotlin
if (engine == RecommendationEngine.SPOTIFY || engine == RecommendationEngine.YOUTUBE_VIDEO) {
    val feed = if (engine == RecommendationEngine.SPOTIFY) spotify.home() else youtube.homeVideos()
    if (revision == recommendationRevision) mutable.update { it.copy(explore = feed.sections, exploreBusy = false) }
    return@launch
}
```

### 4.4 Search

```kotlin
val results = youtube.search(query, videos = mutable.value.recommendationEngine == RecommendationEngine.YOUTUBE_VIDEO)
```

### 4.5 Next recommendations / radio

```kotlin
private suspend fun providerRadio(seed: Track, exclude: Collection<String> = emptyList()): List<Track> =
    if (mutable.value.recommendationEngine == RecommendationEngine.SPOTIFY) spotify.radio(seed, exclude)
    else youtube.radio(seed, exclude, videos = mutable.value.recommendationEngine == RecommendationEngine.YOUTUBE_VIDEO)
```

### 4.6 Account change reload

```kotlin
val wantsYouTube = changed && mutable.value.recommendationEngine != RecommendationEngine.SPOTIFY
// reset homeFilters/homeFilter/feed/feedContinuations/feedMore/feedEnded/explore when wantsYouTube
if (wantsYouTube) { clearCarHome(); refreshFeed(); refreshExplore() }
```

### 4.7 Music tab shelf layout (`YouTubeMusicHome` in `MusicApp.kt`)

The Music tab uses a custom shelf layout (like Explore's music tab) instead of the default
PersonalScreen order. Sections render in this order, top to bottom:

1. **From YouTube** — provider shelves (`providerShelves`) showing videos + mixes from the feed
2. **Play it again** — listening-history tiles (`playItAgain`, `LazyHorizontalGrid`)
3. **New to your ears** — `unheard` tracks (`TrackRow`)
4. **Your mixes** — mix cards (`homeMixes`, `LazyRow`)
5. **Recently played** — history rows (`LastPlayedRow`) at the bottom

No per-section padding — sections flow directly one after another. `providerShelves` handles its own
24dp gap between shelves; each section's title + subtitle + content rows provide natural separation.

**Root-content routing** (in `MusicApp.kt`'s Home `when` block):
- `YOUTUBE_VIDEO` + Music filter → `PersonalScreen` → `YouTubeMusicHome` (shelf layout)
- `YOUTUBE_VIDEO` + non-Music chips → `YouTubeVideoFeed` (16:9 video cards)
- All other sources → `ProviderHome` (unchanged)

---

## 5. UI

### 5.1 `ui/ProviderShelves.kt` — `ProviderFilters`

```kotlin
@Composable
internal fun ProviderFilters(state: MusicState) {
    // Hum is music only: YouTube Music's Podcasts chip is left out.
    val videoSource = state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO
    val filters = state.homeFilters
        .filterNot { it.contains("podcast", ignoreCase = true) }
        .filter { it.lowercase() != "all" }
        .sortedByDescending { it.equals("music", ignoreCase = true) }
    if (filters.isEmpty() && !videoSource) return
    val select = LocalHomeFilterSelect.current
    val row = rememberLazyListState()
    // The source opens on Music and the row always begins there: when the selected tab changes,
    // bring the row back to its start so Music is visible without scrolling right.
    LaunchedEffect(videoSource, state.homeFilter) { if (videoSource) row.scrollToItem(0) }
    LazyRow(Modifier.fillMaxWidth().padding(top = 12.dp), state = row, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (videoSource) {
            // Music is always the first chip, even before YouTube returns its category list; Feed is
            // YouTube's own home feed, next to it.
            item(key = "filter:music") { FilterChip(state.homeFilter?.equals("Music", ignoreCase: true) == true, { select("Music") }, label = { Text("Music") }) }
            item(key = "filter:feed") { FilterChip(state.homeFilter == null, { select(null) }, label = { Text("Feed") }) }
            itemsIndexed(filters.filterNot { it.equals("music", ignoreCase = true) }, key = { i, title -> "filter:$i:$title" }) { _, title ->
                FilterChip(state.homeFilter == title, { select(title) }, label = { Text(title) })
            }
        } else {
            item(key = "filter:all") { FilterChip(state.homeFilter == null, { select(null) }, label = { Text("All") }) }
            itemsIndexed(filters, key = { i, title -> "$i:$title" }) { _, title ->
                FilterChip(state.homeFilter == title, { select(title) }, label = { Text(title) })
            }
        }
    }
}
```

Important: **no unkeyed `item {}` before a keyed list** — duplicate keys crash LazyRow in some Compose versions.

### 5.2 `ui/MusicApp.kt` — `AppHeader` + `AppWordmark`

Change the signature:

```kotlin
private fun AppHeader(page: String, searchOpen: Boolean, onCloseSearch: () -> Unit, onHome: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit,
    videoHome: Boolean = false, onSearchRequest: () -> Unit = {}, search: @Composable RowScope.() -> Unit) {
```

Add this branch after the back-button branch (`if (page in listOf("now", …))`) and before `else`:

```kotlin
} else if (videoHome) {
    // YouTube videos Home mirrors YouTube's app bar: the wordmark on the left, then search
    // and the account target on the right. The search field expands in place when opened.
    AppWordmark(page, onHome)
    if (searchOpen) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            search()
            IconButton(onClick = onCloseSearch, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, "Close search", Modifier.size(22.dp)) }
        }
    } else {
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onSearchRequest, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Search, "Search YouTube", Modifier.size(22.dp)) }
        IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.AccountCircle, "Open settings", Modifier.size(26.dp)) }
    }
}
```

Extract the existing inline `Wordmark()` composable into a reusable `AppWordmark` (same body, renamed) and
replace both `Wordmark()` call sites with `AppWordmark(page, onHome)`:

```kotlin
/**
 * The app's small wordmark used across looks so code outside [AppHeader] can reuse it.
 */
@Composable
private fun AppWordmark(page: String, onHome: () -> Unit) {
    Column(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clip(RectangleShape).clickable(onClick = onHome).padding(horizontal = 6.dp, vertical = 8.dp)
        .semantics { contentDescription = "Music, go home"; selected = page == "home" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (LocalLook.current.hum) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("hum", fontFamily = HumDisplay, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 22.sp, letterSpacing = (-.03).em)
                Box(Modifier.padding(start = 1.dp, bottom = 3.dp).size(6.dp).background(LocalAccent.current.accent, CircleShape))
            }
        } else if (LocalLook.current.soft) {
            Text("M", fontFamily = SoftFont, fontSize = 18.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(12.dp).height(2.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)))
        } else {
            Text("M", style = DisplayStyle, fontSize = 24.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(3.dp))
            Box(Modifier.width(14.dp).height(4.dp).clip(if (LocalLook.current.classic) RectangleShape else RoundedCornerShape(2.dp)).background(LocalAccent.current.accent))
        }
    }
}
```

Header call site:

```kotlin
AppHeader(page, searchOpen, onHome = { navigate("home") }, onCloseSearch = { searchOpen = false; if (state.query.isNotEmpty()) onQuery(""); keyboard?.hide() }, onSettings = { navigate("settings") }, onBack = ::goBack,
    videoHome = state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO && page == "home" &&
        state.homeFilter?.equals("music", ignoreCase = true) != true,
    onSearchRequest = { searchOpen = true }) { SearchBar(state, onQuery, onSearch, searchFocus, Modifier.weight(1f)) }
```

The Music tab is excluded so it uses the normal app header, exactly like the YouTube Music source.

### 5.3 Home route

In the root content `when` (the `else ->` that renders `ProviderHome`):

```kotlin
else -> {
    if (state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO &&
        state.homeFilter?.equals("music", ignoreCase = true) != true) {
        YouTubeVideoFeed(state, ::play, onRefreshFeed, onMoreFeed, onConnect, homeScroll, page == "home" && !showResults)
    } else if (state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO &&
        state.homeFilter?.equals("music", ignoreCase: true) == true) {
        PersonalScreen(state, ::focusSearch, ::play, onToggle, ::openEntry,
            { onRefreshFeed(); onRefreshForYou() }, onMoreFeed, onConnect,
            listState = homeScroll, active = page == "home" && !showResults,
            recommendations = homeRecommendationGroups(state))
    } else {
        ProviderHome(state.copy(feed = state.feed + state.feedContinuations), ::openEntry,
            onRefreshFeed, onMoreFeed, listState = homeScroll, active = page == "home" && !showResults)
    }
}
```

### 5.4 `PersonalScreen` (Explore → For you)

At the top of `PersonalScreen`:

```kotlin
// The YouTube videos source's non-Music chips get a YouTube-style video feed; the Music chip
// keeps the YouTube Music shelves below, like the YouTube Music source.
if (state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO &&
    state.homeFilter?.equals("music", ignoreCase: true) != true) {
    YouTubeVideoFeed(state, onPlay, onRefresh, onMore, onConnect, listState, active)
    return
}
// YouTube videos Music tab: From YouTube first, then Play it again, New to your ears,
// Your mixes, and Recently played at the bottom — shelf layout like Explore's music tab.
if (state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO &&
    state.homeFilter?.equals("music", ignoreCase: true) == true) {
    YouTubeMusicHome(state, recommendations, onPlay, onEntry, onRefresh, onMore, onConnect, listState, active)
    return
}
```

### 5.5 `YouTubeMusicHome`

Add this composable near `YouTubeVideoFeed` (before it in the file):

```kotlin
/**
 * YouTube videos Music tab: shelf layout like Explore's music tab.
 * Order: From YouTube first, then Play it again, New to your ears,
 * Your mixes, and Recently played at the bottom.
 */
@Composable
private fun YouTubeMusicHome(
    state: MusicState, recommendations: HomeRecommendationGroups,
    onPlay: (Track, List<Track>) -> Unit, onEntry: (FeedEntry) -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onConnect: () -> Unit,
    listState: LazyListState, active: Boolean
) {
    val scheme = MaterialTheme.colorScheme
    val look = LocalLook.current
    val accent = LocalAccent.current
    val recent = state.history.firstOrNull()
    val unheard = recommendations.newToYou
    val hasFeed = state.forYou.isNotEmpty() || feedSongs(state.feed).isNotEmpty()
    val now = remember(state.playEvents, state.history) { System.currentTimeMillis() }
    PullToRefresh(state.feedBusy && !state.feedAppending, onRefresh) {
        LazyColumn(Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
            // Banners (same as PersonalScreen)
            val google = state.googleProfile
            if ((state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE || state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO) && google != null && !state.account.connected && !state.account.connecting) {
                item(key = "sync") {
                    SyncBanner(google.email, onConnect, motionModifier().padding(top = 16.dp))
                }
            }
            if ((state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE || state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO) && state.account.connected && !state.feedPersonalized && hasFeed) {
                item(key = "generic-feed") {
                    NoticeBanner("Your YouTube feed isn’t coming through",
                        "YouTube answered as signed out, so this is the general feed. Reconnecting your account usually fixes it.", "Reconnect", onConnect, motionModifier().padding(top = 16.dp))
                }
            }
            if (state.feedError != null && (state.feed.isEmpty() || !state.feedHasMore)) {
                item(key = "feed-error") {
                    Row(motionModifier().fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.feedError, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRefresh, shape = ButtonShape) { Text(look.label("Retry"), style = LabelText) }
                    }
                }
            }
            // Title + chips row
            item(key = "title") {
                Column(Modifier.padding(bottom = 4.dp)) {
                    PageTitle("For your ears")
                    Text("Recommendations", fontSize = 12.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = if (look.classic) 6.dp else 0.dp))
                    ProviderFilters(state)
                    Spacer(Modifier.height(8.dp))
                    LoadingLine(state.feedBusy && !state.feedAppending, state.reducedMotion)
                }
            }
            // 1. From YouTube — provider shelves with videos and mixes
            val shown = (recommendations.fromListening + recommendations.newToYou + state.history).map { it.libraryKey() }.toSet()
            val providerSections = (state.feed + state.feedContinuations).map { section ->
                section.copy(items = section.items.filter { entry ->
                    if (entry.videoId.isNotBlank() || entry.kind == "spotify_track") entry.asTrack().libraryKey() !in shown else true
                })
            }.filter { it.items.isNotEmpty() }
            if (providerSections.isNotEmpty()) {
                providerShelves(providerSections, onEntry, 24.dp)
            }
            // 2. Play it again — songs from listening history as tiles
            val again = playItAgain(state, if (look.soft) 12 else 5)
            if (again.isNotEmpty()) {
                item(key = "play-it-again") {
                    Column(Modifier.fillMaxWidth()) {
                        SectionTitle("Play it again")
                        Text("From your listening history", fontSize = 12.sp, lineHeight = 16.sp,
                            color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                        // Use LazyHorizontalGrid (not LazyRow) so multi-row tiles don't create blank space below
                        val rows = if (again.size >= 4) 2 else 1
                        LazyHorizontalGrid(
                            GridCells.Fixed(rows),
                            Modifier.fillMaxWidth().height(
                                (112.dp + 46.dp) * rows + 12.dp * (rows - 1)
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(again, key = { _, t -> "again:${t.libraryKey()}" }, contentType = { _, _ -> "again" }) { _, track ->
                                Column(Modifier.width(112.dp).clip(look.card)
                                    .songClickable(track) { onPlay(track, listOf(track)) }) {
                                    Artwork(track, Modifier.size(112.dp).clip(look.card), size = 112.dp)
                                    Text(track.title, Modifier.padding(top = 6.dp), fontSize = 13.sp,
                                        lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = scheme.onBackground)
                                    Text(track.artist, Modifier.padding(top = 3.dp), fontSize = 12.sp,
                                        lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        color = scheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            // 3. New to your ears
            if (unheard.isNotEmpty()) {
                item(key = "new-to-your-ears") {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        SectionTitle(if (state.trackHistory) "New to your ears" else "Discover more")
                        Text(if (state.trackHistory) "Songs outside your recorded listening history."
                            else "Individual recommendations.",
                            fontSize = 12.sp, lineHeight = 18.sp, color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                        unheard.forEach { track ->
                            TrackRow(track, false, false, { onPlay(track, listOf(track)) }, null)
                        }
                    }
                }
            }
            // 4. Your mixes — big cards that play a whole mix
            val mixes = homeMixes(state)
            if (mixes.isNotEmpty()) {
                item(key = "your-mixes") {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        SectionTitle("Your mixes")
                        Text("Each plays as one queue", fontSize = 12.sp, lineHeight = 16.sp,
                            color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(mixes, key = { _, m -> m.key }) { _, mix ->
                                val covers = mix.tracks.distinctBy { it.artwork }.take(4)
                                Box(Modifier.width(248.dp).height(200.dp).clip(look.card)
                                    .background(if (look.soft) look.surface else look.panel)
                                    .clickable(onClickLabel = "Play mix", role = Role.Button,
                                        onClick = { onPlay(mix.tracks.first(), mix.tracks) })) {
                                    if (covers.size >= 4) {
                                        Column(Modifier.fillMaxSize()) {
                                            for (r in 0..1) Row(Modifier.weight(1f)) {
                                                for (c in 0..1) Artwork(covers[r * 2 + c], Modifier.weight(1f).fillMaxSize(), size = 124.dp)
                                            }
                                        }
                                    } else {
                                        Artwork(mix.tracks.first(), Modifier.fillMaxSize(), size = 248.dp)
                                    }
                                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                                        0f to Color.Transparent, .35f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = .86f))))
                                    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Bottom) {
                                        Column(Modifier.weight(1f)) {
                                            Text(if (look.room) mix.title.uppercase(Locale.getDefault()) else mix.title,
                                                style = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 26.sp, lineHeight = 27.sp, letterSpacing = .01.em),
                                                color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(mix.detail, fontFamily = ArchivoFont, fontSize = 12.sp,
                                                lineHeight = 16.sp, color = Color.White.copy(alpha = .82f),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                                        }
                                        Spacer(Modifier.width(10.dp))
                                        Box(Modifier.size(48.dp).background(accent.accent, CircleShape),
                                            contentAlignment = Alignment.Center) {
                                            Icon(Icons.Outlined.PlayArrow, null, tint = accent.onAccent, modifier = Modifier.size(26.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // 5. Recently played at last
            if (recent != null) {
                item(key = "recently-played") {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        SectionTitle("Recently played")
                        state.history.drop(1).take(4).forEach { track ->
                            val playing = track.libraryKey() == state.current?.libraryKey()
                            LastPlayedRow(track, if (playing) "Now playing" else "", { onPlay(track, listOf(track)) })
                        }
                    }
                }
            }
            item(key = "feed-tail") { FeedContinuation(state, active, onMore) }
        }
    }
}
```

### 5.6 `YouTubeVideoFeed` — unchanged (non-Music chips only)

Keep the existing `YouTubeVideoFeed` and `YouTubeVideoCard` composables for non-Music chips
(Feed / Gaming / Live / …).

### 5.7 Settings — recommendation-source chips

Fix the crushed Spotify chip: replace the fixed `Row` with `FlowRow` and cap labels at one line.

```kotlin
FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    labs.rtensor.music.recommendations.RecommendationEngine.entries.forEach { engine ->
        FilterChip(selected = state.recommendationEngine == engine, onClick = {
            if (engine == labs.rtensor.music.recommendations.RecommendationEngine.SPOTIFY && !state.spotifyAccount.connected) onConnectSpotify()
            else onRecommendationEngine(engine)
        }, label = { Text(engine.label, maxLines = 1) }, modifier = Modifier.semantics { contentDescription = "${engine.label} recommendations" })
    }
}
```

Keep the experimental note for this source only:

```kotlin
if (state.recommendationEngine == labs.rtensor.music.recommendations.RecommendationEngine.YOUTUBE_VIDEO)
    Text("YouTube videos discovery is experimental; playback stays audio-only. Video can be enabled in a later update.", fontSize = 12.sp, color = scheme.onSurfaceVariant)
```

### 5.8 Spotify account row (optional)

`Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically)` — prevents
the long Spotify name from pushing the Disconnect button off-screen.

---

## 6. `HomeFeedMore.kt` — no duplicate shelves on "load more"

```kotlin
val seenTitles = (state.feed + state.feedContinuations).map { it.title }.toMutableSet()
// …
val added = page.sections.map { section -> section.copy(items = section.items.filter { /* unchanged */ }) }.filter { it.items.isNotEmpty() }
// Merge same-title sections so duplicates like "From YouTube" don't repeat across continuation pages
val merged = added.groupBy { it.title }.map { (_, group) ->
    group.first().copy(items = group.flatMap { it.items })
}
// Only keep sections whose title hasn't already been shown
val fresh = merged.filter { it.title !in seenTitles }
seenTitles += fresh.map { it.title }
sections += fresh
```

---

## 7. Build and verify (Windows, PowerShell)

```powershell
# 1. engine (only after src/*.mjs edits)
npm run build --prefix youtube-engine

# 2. engine tests (one pre-existing unrelated failure is acceptable upstream: MPR album via home card)
Push-Location youtube-engine; node --test test/*.test.mjs; Pop-Location

# 3. app (JDK 17 required)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
./gradlew :app:assembleRelease --console=plain
```

Checklist:
- [ ] `:app:verifyYouTubeEngine` passes (digest fresh)
- [ ] `:app:assembleRelease` **BUILD SUCCESSFUL**
- [ ] grep: no `channel-videos`, `channelVideos`, `channelLockup`, `UC-9-kyTW8ZkZNDHQJ6FgpwQ`
- [ ] APK path: `app/build/outputs/apk/release/app-release.apk`

Manual check on a phone (signed in):
1. Settings → Playback → Recommendation source → **YouTube videos** → Home opens on **Music** (chips: Music · Feed · Gaming · Live · …).
2. Music tab shows shelves: From YouTube → Play it again → New to your ears → Your mixes → Recently played.
3. Tap **Feed** → plain YouTube home video feed (16:9 cards).
4. Tap a video → audio plays; Next continues with that video's own recommendations.
5. Scroll → next pages append, no repeated "From YouTube" header.
6. Pull to refresh works; YouTube Music and Spotify screens are unchanged.

---

## 8. Porting checklist onto upstream beta 6

1. Add the `YOUTUBE_VIDEO` enum entry first (everything else depends on it).
2. Add `Feed.kt` fields, then `YouTubeSource` methods.
3. Port `mapping.mjs` helpers and `sections()` merge; add the three tests; then `engine.mjs`
   (`home-videos`, search, radio, playlist collection). Rebuild the bundle.
4. Port `refreshFeed` / `homeFilter` / explore / search / `providerRadio` / account gate into beta 6's
   `MusicViewModel` (its line numbers are close to the reference; use the code blocks above verbatim,
   not a patch hunk).
5. Port `ProviderFilters`, then `AppHeader`/`AppWordmark`, the Home route branch, the `PersonalScreen`
   early return, and `YouTubeMusicHome`/`YouTubeVideoFeed`/`YouTubeVideoCard` into beta 6's `MusicApp.kt`.
6. Settings: `FlowRow` + one-line labels + the experimental note.
7. `HomeFeedMore` title merge.
8. Rebuild engine → build app → run the manual checklist.
9. Skip the obsolete `videoMusicRow`/`videoPodcastRow`/`refreshVideoRows` rail leftovers unless you
   intentionally re-add the old rail UI (the user rejected it; do not).

### Known behaviour / limits

- The Music tab depends on YouTube returning a `Music` category chip. If a session returns no chips
  (typically signed out), `home-videos` falls back to the unfiltered home; the UI still labels the tab
  Music with `homeFilter = "Music"` but shows the general video feed.
- Video playback is **audio-only** (existing pipeline), by design.
- Android Auto keeps the non-YouTube placeholder for this source (unchanged upstream) — see `CarLibrary`.
- Shorts, channels, playlists and albums never appear in the video feed (mapping rejects them),
  except playlists/mixes are now mapped as shelf sections on the Music tab via `playlistEntry`.
- ToS posture is unchanged: **undocumented InnerTube API, personal use, user's own session** — same
  accepted risk documented for YouTube Music/Spotify.
