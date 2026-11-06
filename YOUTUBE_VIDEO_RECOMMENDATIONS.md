# Can YouTube (regular) be a recommendation engine with video support, as an alternative to YouTube Music?

Research report, 7 Nov 2026. Scope: Music's in-app "Recommendation source" toggle — add **YouTube (videos)** next to
**YouTube Music** and **Spotify**, with a layout that matches regular YouTube and optionally supports video content.

## Short answer

**Technically feasible, partially reused machinery, materially higher risk than the current YouTube Music path.**
The app could ship a "YouTube (videos)" source that shows a signed-in, personalised video feed and plays it as
audio. **Actual in-app video playback is a much bigger, separate feature** and is not recommended as part of a
recommendation switch.

Two hard constraints shape everything:

1. **The official YouTube Data API v3 has no personalised recommendation/home-feed endpoint.** It can search and
   read metadata, but it cannot reproduce "your Home feed". Any recommendation engine has to use the undocumented
   InnerTube API (what the app already does for YouTube Music) or nothing at all.
2. **YouTube's Terms prohibit automated access without permission** and the API Services terms prohibit undocumented
   APIs and scraping. The app already accepts this risk for YouTube Music and Spotify (documented in
   `YOUTUBE_INTEGRATION.md` and `SPOTIFY_RECOMMENDATIONS.md`); a full YouTube video mode widens the same exposure,
   and *audible/visual* video playback makes the app look more like a YouTube client replacement.

## What the research shows

### 1. InnerTube does serve a personalised YouTube home feed

`POST https://www.youtube.com/youtubei/v1/browse` with `browseId: "FEwhat_to_watch"` and a WEB client context returns
the signed-in user's Home feed (richGrid/richItem/videoRenderer items: thumbnails, duration, channel, view counts).
Continuation tokens page through it the same way the Music feed does. `FEsubscriptions`, `FEexplore`, search and
`/next` (up-next/related) work the same way. Confirmed across multiple independent InnerTube clients
(tomat NSIS / tombulled/innertube, lighttube-org, superness/youtube-data-api, MeeTube research notes). No API key is
required; the client version must track YouTube's current WEB version (read from `www.youtube.com` page config),
exactly as the Music engine already reads `INNERTUBE_CLIENT_VERSION` from the YouTube page.

Anonymous requests work too, but return the generic/trending feed — useless as "your recommendations". A signed-in
WEB session is required for a personalised feed.

### 2. The session the app already captures probably works for www.youtube.com

The Music sign-in WebView captures cookies for `.youtube.com` (SAPISID etc.). Those cookies are shared across
`www.youtube.com` and `music.youtube.com`. The existing engine already special-cases origin handling
(`nativeFetch` rewrites `www.youtube.com` → `music.youtube.com` and re-signs SAPISIDHASH for the music origin). A
YouTube mode would do the mirror-image job:

- bootstrap `https://www.youtube.com/` instead of `music.youtube.com/`, expect `clientName: "WEB"`;
- keep Cookies for the www origin, sign SAPISIDHASH with `https://www.youtube.com`;
- allowlist `www.youtube.com` + read its `INNERTUBE_CONTEXT` (the page ships it, like Music's);
- note the code comment's warning: *a www-signed feed request sent to the music origin comes back signed-out* — the
  same is true in reverse; the two modes must not mix origins.

What is **not** reusable as-is:

- **Proof/attestation bindings.** `contentBoundGvs = { YTMUSIC, WEB }` exists, and proof code is keyed off the WEB_REMIX
  session; the WEB client path needs its own challenge fetch and `/att/get` handling (same BgUtils sandbox can run
  it, but the wiring, allowlist and tests are Music-specific today).
- **Parsers.** `mapping.mjs` extracts `musicResponsiveListItemRenderer`/music shelves. YouTube returns
  `richItemRenderer`/`videoRenderer`/`shortsLockupViewModel`/`compactVideoRenderer` etc. A parallel parser, or an
  added branch, is required. Videos are not songs: no artist field, no album/duration semantics, 16:9 thumbnails,
  view counts, channel names, live badges, Shorts.
- **Filtering.** The feed contains music videos, Shorts, podcasts, "mixes", premieres and regular videos. Any
  sensible "audio player seeded from YouTube" needs a content-type filter (e.g. drop Shorts <60 s, or make it
  optional), or the queue will fill with non-musical content. This is a product decision, not just parsing.
- **Duration/format gating.** Music assumes short, matchable recordings. YouTube videos can be 10 minutes or 8 hours;
  the matcher/queue logic (radio, up-next) is built around track semantics.

### 3. Playback: audio-of-video works; video playback does not come free

- **Audio path:** once an item is chosen, the existing `player` pipeline is identical (WEB player, signature/proof
  machinery, SABR-only handling). Playing the audio of a YouTube video through Media3 is the same shape of work the
  Spotify fallback match already does, except the video ID *is* the identity — no cross-service matching needed.
  Rights/age/region restrictions remain provider decisions; YouTube is stricter here (embeds disabled ≠ audio
  playable; SABR-only responses).
- **Actual video rendering:** the app has no video surface today (audio-first Media3 UI). Real video playback means
  an ExoPlayer video renderer, a `PlayerView`/surface in the player screen, Picture-in-Picture decisions,
  orientation handling, captions, and a changed "queue" model. That is a substantial feature (weeks, not days),
  unrelated to recommendations, and it makes the "YouTube client replacement" argument against the app much stronger.

### 4. Terms and risk (unchanged in kind, larger in degree)

- YouTube ToS: no automated access without prior written permission; no scraping; API Services policies forbid
  undocumented APIs and require the documented means only. Contract breach is the realistic exposure
  (blocking, account termination), not usually criminal liability (hiQ v. LinkedIn), but account suspension of the
  user's own Google account is a real, user-facing risk.
- The app already documents this accepted risk for Music and Spotify. Adding regular YouTube video recommendations is
  the same legal category; adding **video playback** approaches a replacement client and clearly outstrips the
  "personalised recommendations for personal listening" framing.
- The safe alternative everyone points to — the official Data API — cannot provide personalised recommendations at
  all (no "home feed" endpoint; search-only; 10,000 units/day quota). There is no compliant path to a personalised
  feed. Any YouTube-recommendation feature is necessarily a gray-area, user-consented, personal-use feature, like the
  existing Spotify mode.

### 5. What similar projects do

- **NewPipe** parses InnerTube/web pages for regular YouTube without any login (no personalised feed — feeds are
  built from local subscriptions; personalised Home is inaccessible without account auth, and they deliberately do
  not do account sign-in).
- **Metrolist** (the app's documented comparison for YTM) signs in via WebView and uses InnerTube WEB_REMIX — same
  pattern, music origin. Nobody mainstream ships a *personalised YouTube* feed for audio via the official API
  because none exists.
- **YouTube.js** supports WEB context and browse; InnerTube clients for regular YouTube are common in the
  open-source ecosystem, so the parsing/knowledge base exists — but those projects are unaffected by YouTube's ToS
  only in practice, not in permission.

## What "YouTube layout" would mean in the app

A switch in Settings → Playback → Recommendation source, e.g. **YouTube (videos)** alongside YouTube Music:

- **Feed UI:** video-card rows (16:9 thumbnail, title, channel, views/age) or a mixed grid; Shorts and live badges
  shown honestly; a "songs only"/"all videos" filter. HomeShelves/carousels would need a video variant — current
  cards assume square art and music metadata.
- **Tap behaviour:** tap a card → play its audio (existing engine) — this is the natural v1 and matches how Music
  treats all sources. "Watch" (video surface) is v2 and optional.
- **Identity/matching:** video ID is canonical; no artist/title fuzzy matching, but *saved/history* semantics shift —
  saves become "saved videos", which is honest if labelled.
- **Account:** same captured Google session; switching source drops queued recommendations, keeps manual queues
  (same semantics as the existing Spotify switch).
- **Rate limits/failure modes:** feed needs sign-in; Google may refuse in-app login (already handled for Music);
  feed may come back signed-out (detect `responseContext...loggedOut`, show reconnect) — the code already does this
  check for Music and it must be carried over.

## Effort estimate (v1: personalised video feed, audio playback, no video surface)

| Area | Estimate |
| --- | --- |
| Engine: WEB bootstrap, origin allowlist, www SAPISIDHASH, `loggedOut` detection | 1–2 days |
| Parsers for `richItemRenderer`/`videoRenderer`/shorts/lockup + continuation | 2–4 days |
| Content-type filter + settings UI toggle + source plumbing (same shape as Spotify plumbing) | 1–2 days |
| UI video-card variant (16:9, channel, views) | 2–3 days |
| WEB proof/attestation path and binding (if playback proofs are demanded for WEB too) | 2–5 days, highly variable |
| Tests (fixtures for feed parse, origin rules, loggedOut, source switching) | 1–2 days |
| Live-phone verification (feed loads, playback, switching) | required, unverifiable in workspace |

**v1 total: roughly 1–2 weeks + phone testing.** Actual video playback adds a separate 1–2+ weeks and materially
increases policy risk.

## Implementation status (7 Nov 2026)

Implemented as the third recommendation source **YouTube videos** ("youtube_video"):
- Engine: new `home-videos` operation in `youtube-engine` — `api.getHomeFeed()` (WEB client, same
  signed-in YouTube session as Music), `LockupView`/`Video`/`GridVideo` parsing, Shorts and
  non-VIDEO lockups dropped, RichShelf sections preserved in provider order. Fixture tests in
  `mapping.test.mjs` cover it. Native transport needs no CORS/cookie changes: WEB calls to
  www.youtube.com carry the session's own SAPISIDHASH, exactly like the web client.
- App: `RecommendationEngine.YOUTUBE_VIDEO`, `YouTubeSource.homeVideos()`, Home/Explore/feed/filters/
  signed-out banners all route like the YouTube Music source. Player layout unchanged — tapping a
  video plays its audio; no video surface exists yet. Settings shows "YouTube videos" with an
  "audio-only" note. Android Auto keeps no video feed (car shows the same guidance as Spotify).
- Video player is deliberately absent (on hold): it will attach an ExoPlayer video surface behind a
  Settings toggle, defaulting Off, without touching the feed/queue code paths.
- Android Auto will also not offer it; feed is phone-only, like Spotify's.
- Videos Home redesign (phone Home only, this source): one infinite sideways **For you** rail with
  auto-continuation paging, plus **Music** and **Podcasts** horizontal rails fed by YouTube's own
  chip-filtered pages (fetched standalone, without touching the FYP feed's paging state). Filter
  chips now act as tabs: pressing one hides the Music/Podcasts rails and shows only the filtered FYP
  rail. "Recently played" UI is gone from this Home (hero and Apple-hero rows included). Podcast
  lockups are playable like videos. Music/Podcasts rails hide themselves if your feed has no
  matching chip. Android Auto and Explore behave as before.
- Not verified on a phone: live sign-in, feed contents, filter chips and playback of arbitrary videos.

## Recommendation

1. **Feasible and consistent with the app's existing architecture** — do it as a third recommendation source, not as a
   replacement, exactly mirroring the Spotify integration pattern (isolated session, signed-out detection,
   explicit user choice, no silent fallbacks).
2. **Ship v1 as audio-of-YouTube-videos** with a visible content filter (music videos / all videos / hide Shorts).
   Treat real video playback as a separate project with its own decision.
3. **Be explicit in UI copy and docs** that it uses the user's own YouTube session, is unofficial, is personal-use,
   can break when YouTube changes its web app, and may return a signed-out/generic feed.
4. **Do not weaken the existing Music path to make this work**; keep the two strictly separate at the origin/proof
   layer as the code already requires.
5. **Gate on a phone test** before calling it supported — a feed rendering generic content or Google login refusal
   would both look like "it doesn't work" without host logs/fixtures.
6. **Non-goals:** offline caching/download of videos, ad-blocking claims, or "full YouTube experience" — each is a
   different, worse risk category.
