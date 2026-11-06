# Music 0.3.37 / continuous feeds and retained browsing position

- Home loads genuine provider continuations automatically near the bottom, until
  YouTube reports no continuation. Both full-feed/More buttons are removed; all
  provider shelves remain on Home. Pull to refresh remains an explicit reset.
- Continuations append below the first page, without replacing New to your ears,
  rebuilding the opening mixes or dropping previous pages. Duplicate-content
  batches retain the provider's continuation instead of ending after three pages.
- The YouTube engine stops on the actual end without refetching the first page.
  Repeated server cursors raise a recoverable error to prevent automatic loops.
  Failed page requests wait for Retry loading more and retain loaded content.
- Home and Explore retain their vertical position through player navigation;
  nested shelves and Library tabs retain their saved state. Starting a song does
  not remove that song from the visible provider shelf when it enters history.
- Pagination uses a bottom loading indicator rather than the pull-to-refresh
  spinner, and pauses while the browsing screen is inactive. Existing looks,
  playback providers, playlists and audio engine are retained.

Host/fixture tests cover provider end/repeat cursors, append-only pages, automatic
loading/retry, and returning from playback after content is inserted above the
scroll anchor. No phone or live signed-in YouTube account was used. Release checks
and exact counts are in decoder-validation.json attached to the release.

## Previous release baseline

# Music 0.3.36 / song actions, playlists and player motion

- Long-press songs or use their menu for Play next, Add to queue, Start radio,
  Save, playlist actions, sharing, copying a link and artist search. Up next
  supports removing a future item and saving the queue as a playlist.
- Library has device playlists and connected YouTube Music playlists. New synced
  playlists are private. Account mutations await provider acknowledgement; removal
  uses the playlist occurrence ID so repeated songs are not confused. Deleting a
  playlist requires confirmation and never deletes its audio recordings. Local
  files stay in device playlists. Playlist contents load in pages; playback uses
  the loaded portion within the 100-item queue limit.
- Search offers real YouTube query/song suggestions after a short debounce.
  Editing cancels obsolete HTTP requests and discards late results; failed
  suggestions leave ordinary search available.
- Home exposes genuine provider shelves and YouTube mood filters when supplied,
  including mixes, albums, artists and community collections. Provider item order
  is preserved. Collection-only continuation pages remain visible. Expanded
  sections keep their originating Home/Explore feed through screen restoration;
  Home mood filters are not shown for Explore.
- Explicit queue choices take priority over generated radio. Selecting a new
  manual song reseeds the selected provider's radio, with old requests cancelled.
  History is not used as the Up next queue.
- Soft restores the centred Previous / Play / Next row, with Like by the song
  information. Like feedback stays circular; play/pause icons have fixed bounds.
  A reproduced cover-motion bug after requeueing the same song is fixed: the next
  transition uses its current queue position, not the old position.

Validation counts and release-binary checks are recorded in the release's
`decoder-validation.json`. Tests include the actual bundled YouTube JavaScript
against fixture endpoints, Android Compose screenshots and frame assertions,
playlist persistence, acknowledgement/error cases and queue cancellation. No
physical phone or live signed-in YouTube account was used: playlist sync, account
ratings, provider availability and device frame rate still need device validation.
Native decoder, private DSP, Apple runtime and Spotify catalogue bundle are
unchanged from 0.3.35. Android 11+, ARM64/x86-64, existing OTA signing identity.

## Previous release baseline

# Music 0.3.35 / looks, Home shelves, player and sign-in fixes

- **Three looks** (Settings › Appearance › Look): Listening room (new default; dark first,
  poster titles, a room panel on Home with the real speaker layout lit in the cover's
  colour), Soft (the 0.3.31 rounded raspberry design with the blurred cover wash) and
  Classic (the 0.3.32 Signal design). All behaviour is shared; sign-in pages follow the look.
- **Home shelves**: "Next up for you" (paged four-row song grid), "Play it again" (hidden
  when listening history is off) and "Your mixes", then "New to your ears". "More from your
  feed" again swaps in the next set of songs, as in 0.3.32.
- **Player and notification**: Next/Previous from the notification, lock screen, headset or
  car work after a failed song; the notification clock and Play/Pause stay correct during a
  call; Play during a call no longer pauses; radio that arrives after the queue ended starts
  playing; the playing song is marked in lists; the playback clock ticks ten times a second
  instead of every frame.
- **Sign-in**: a temporary Android KeyStore error no longer signs you out of everything;
  Spotify sign-out sticks; YouTube and Apple sign-in pages say why they stopped, survive
  theme/keyboard changes, and a crashed sign-in page no longer ends playback.
- **Accessibility and copy**: 48dp targets, no clipping at large text, readable contrast in
  every look, announced error banners with plain wording.

Recommendation ranking is unchanged: Music shows the chosen provider's (YouTube Music or
Spotify) recommendations. The release target is **0.3.35 / versionCode 38**, Android 11+,
ARM64/x86-64 and the existing signing identity. Native decoder, DSP and Apple adapter/runtime
bytes are unchanged from 0.3.33/0.3.34. No physical phone, car or signed-in account was used.

## Previous release baseline

# Music 0.3.34 / feed, cover colours and Spotify sign-in

This release fixes three problems reported on 0.3.33:

- **More from your feed** reshuffled Home instead of adding songs. Home's
  "New to your ears" group took the last six unheard songs of the whole feed, so
  each new page replaced what was on screen. Added pages now append only songs not
  already loaded or played, keep existing rows in place, and fetch up to three
  pages when a page brings nothing new.
- **From the cover** cached any failed artwork load as "no colour" for the whole
  session, gave up on dark, muted and black-and-white covers, and counted YouTube
  side panels and letterbox bars. Failed loads now retry, bars are cropped, and the
  colour that fills most of the cover is used. Measured locally on 24 real covers:
  colours appeared for 18 before and 24 after (the images are not in the repository;
  tests use generated covers).
- **Spotify sign-in** could cancel silently, trap users with a second "Continue"
  button, and block Google, Facebook and Apple hand-off pages. Every exit now shows
  a reason in Settings, the page's button is "Connect", and the hand-off hosts are
  allowed during interactive sign-in only.

The release target is **0.3.34 / versionCode 37**, Android 11+, ARM64/x86-64 and
the existing signing identity. The full app run passed **481 checks with no
failures or errors (2 skipped)** on the release source; release packaging, the
OTA/DSP, decoder, Apple adapter, surround-bed and optimization guards run on the
final APK. Native decoder, DSP and Apple adapter/runtime bytes are unchanged from
0.3.33 and were taken from the 0.3.33 release APK; their hashes match the 0.3.33
`decoder-validation.json`. No physical phone, car or signed-in Spotify session was
used for this release.

## Previous release baseline

# Music 0.3.33 / player, accents and Android Auto

This release reviews Claude's merged player/accent redesign, playback and account
recovery changes, Android Auto browsing and voice search. The player has rounded
artwork, centred transport controls, cover-derived colours and fixed accent
choices. Palette extraction runs off the UI thread and caches results; tests
check contrast, missing-artwork fallbacks and actual rendered screens.

The review found a car queue-selection defect: filtering a removed or unplayable
entry could shift the requested start index and play a different song. The fix
preserves the original request index, including repeated occurrences, and rejects
a removed selected song. All four regressions failed before the fix and passed afterward;
the complete car callback suite also passed against the corrected code.

The release target is **0.3.33 / versionCode 36**, Android 11+, ARM64/x86-64 and
the existing signing identity. **436 unique app checks pass**, with no failures, errors or skips: 432 in the full run, followed by 35 affected checks containing four new regressions.
The affected checks and lint cover
the actual Android sources on a host. Exact completed counts and release-artifact
checks are recorded in `decoder-validation.json` and `SHA256SUMS`. Packaging runs
R8/resource shrinking, release lint, native/runtime integrity guards and the
under-100-MB size guard.

Decoder and DSP sources and packaged native/runtime binaries are retained from
0.3.32. Their earlier DSP/browser results remain baseline evidence, not newly
executed tests. No physical phone, car, signed-in Spotify session or new Apple
lossless/multichannel account playback was validated by this review. Android Auto
uses the existing engine and provider routes; its automated checks do not replace
car or Desktop Head Unit testing. Car-started history/radio continuation currently
depends on Music being open on the phone.

## Previous release baseline

# Music 0.3.32 / Signal UI

The Signal design passed a full **308-check app run**, including rendered light
and dark player screens, playback gestures, source controls and recommendation
partitioning. All checks pass with no failures, errors or skips. Release Kotlin
compilation and debug lint also pass. The [review](SIGNAL_UI_REVIEW.md) explains
the corrections made before merging.

The release target is **0.3.32 / versionCode 35**, retaining Android 11+,
ARM64/x86-64 and the existing signing identity. Updater regression checks cover
0.3.31 and older integrated installations. Release packaging runs optimization,
native/runtime integrity checks and release lint. Completed artifact checks and
exact hashes are recorded in the release’s `decoder-validation.json` and
`SHA256SUMS`.

Provider/authentication, decoder and DSP implementations are unchanged from
0.3.31. Existing native/runtime payloads must match the previous release by hash.
The previous DSP/browser results remain baseline evidence; this UI release does
not claim new phone performance measurements or newly verified Apple lossless
account access. Full app checks were completed during UI review; the version-bump
updater checks and release-package checks are run separately for publication.

## Previous release baseline

### Music 0.3.31 verification

This update fixes the Spotify sign-in handoff across the private browser process.
The reply now uses the framework Binder receiver, rather than an app-private
anonymous class that the service cannot load. Android 11 and Android 15 tests
exercise both the old failure and the actual repaired client/service reply path.
The sign-in screen provides the same Continue/Connect flow as YouTube, with
bounded verification, progress, retry and cancellation. Allowlisted linked-account
pages are interactive only; the catalogue bridge stays on Spotify’s HTTPS origin.

Apple source labels distinguish the actual provider, AAC compression, verified
ALAC/FLAC input and the decoded surround bed routed to stereo DMR. Unknown speaker
positions no longer cause a known multichannel input to be labelled stereo.
An unverified rendition shows “checking audio” and a YouTube fallback loses the
Apple label immediately. UI and format regressions cover these transitions.

Advanced Apple sign-in now names the failed stage (engine startup, account
authentication, code submission, session verification or subscription check),
retaining only a numeric provider error code. Raw exception text, passwords and
account tokens never enter these messages. Tests cover preservation of the existing
AAC vault on an advanced-login failure. These diagnostics do not establish that
the failing phone can authenticate or deliver lossless/multichannel audio. AAC
and advanced playback remain separate authorizations; merging their UI cannot
grant a missing playback entitlement. No native provider, decoder or DMR binary
was changed in this update.

A separate [public design review](https://music-layout-review.chalkybongo1.chatgpt.site)
compares smaller Home covers and three centred player concepts. Browser checks
cover exact transport alignment, next-song visibility, queue opening/closing,
theme selection and stable metadata height. These mockups are not a native
Android performance test and have not replaced the shipped player layout.

No real Spotify account or physical phone was available for this verification.
Apple AAC works according to the user’s report; the advanced-login failure has
not been reproduced on their phone. Exact completed test counts, artifact hashes,
signing identity and native/runtime retention checks are recorded in this release’s
`decoder-validation.json` and `SHA256SUMS`.

## Previous release baseline

### Music 0.3.30 / recommendation source choice

**269 unique app checks, 86 DSP checks and ten browser-bridge checks pass**, with
no failures, errors or skips. The full app run passes 268 checks; the final
36-check affected run adds one MediaSession diagnostics regression. Debug lint
passes. All four README architecture diagrams and the Spotify data-flow diagram
render successfully.

- Compose checks exercise the actual recommendation chips: YouTube defaults on,
  disconnected Spotify opens sign-in without falsely selecting it, verified
  accounts switch both ways, preferences persist and Disconnect invokes its action.
- Engine checks cover stable discovery identity, strict artist/title/duration/
  edition matching, seven-day match cache expiry and duplicate in-flight lookups,
  ten-minute catalogue caching, provider radio order, local-file exclusion,
  account expiry and stale feed/sign-in rejection.
- Real MediaSource tests prove lazy matching with providers disabled, priority for
  an already selected local lossless file, preserved queue/cache/discovery identity,
  a clear match failure before requesting audio and canonical session-URI gating.
  The final error check uses Media3 PlaybackException bundle serialization to
  confirm why app-specific matching failures need in-process retention.
- Queue checks preserve the current song/position/Play state and manually queued
  tracks while replacing only generated future tracks. Late results from the old
  provider cannot append after switching. Existing player, import, library,
  provider, updater and DSP regressions remain covered by the full run.
- Simulated page tests cover metadata privacy, anonymous-account rejection,
  provider feed/radio and collection paging, origin/subframe and command guards,
  sanitised errors, concurrent operation discovery, temporary asset failure recovery
  and the track-detail firstArtist/
  otherArtists schema found during the public browser probe. Current public
  Spotify desktop/search assets contain all five required operation definitions.
  Seven real public-catalogue checks pass in a browser: Home, search, track details,
  song radio, album, playlist and a second playlist page. A test-only harness
  permits Spotify’s anonymous catalogue token; the app itself still requires
  sign-in. All returned track lists contain artist/title/duration metadata. This
  is public-interface evidence, not subscriber/personalised-account validation.

Spotify is **experimental and metadata-only**. No signed-in Spotify account, Android
phone, personalised response or device smoothness was verified. Search remains
YouTube search. Matching is metadata-based, not an audio fingerprint/ISRC proof.
[Setup, architecture and limitations](SPOTIFY_RECOMMENDATIONS.md).

Release packaging retains Android 11+, ARM64/x86-64 and the existing update signing
identity. The release process runs R8/resource shrinking, release lint, the exact
native/runtime guards and the under-100-MB optimization guard. Actual artifact
size, hashes, source commit and completed checks are recorded in the release's
`decoder-validation.json` and `SHA256SUMS`; source-level unit tests do not replace
physical-device validation. Existing native/vendor binaries and DMR processing
are retained; the new code changes discovery and routing, not their implementation.

Version **0.3.30/code33** targets the existing normal preview OTA feed.

# Previous verification: Music 0.3.29 / developer-only monitor and release build

35 targeted app checks pass with no failures, errors or skips: three developer
options checks, seven channel-monitor checks, ten player-comparison checks, four
source-settings checks and eleven account/updater checks. Android debug and
release lint, all four native/runtime guards and the release-optimization guard
pass. The universal release APK is 89,171,888 bytes (89.2 MB), compared with
117,713,012 bytes for the prior debug APK. Optimized DEX is 4,919,016 bytes.

- Developer options defaults off and is stored on this device. The normal player
  has no channel-monitor entry. Enabling the setting makes it available; turning
  it off removes an open monitor and stops observation immediately. Playback and
  Original/Processed comparison remain independent of the setting.
- Distribution uses the actual release variant, with R8 optimization, resource
  shrinking and a non-debuggable manifest. A build guard requires the universal
  APK to stay below 100,000,000 bytes and preserves the five required JNI class
  names. Exact native/runtime payload checks target the release APK by default.
- The existing updater supports `music-release.apk`; the OTA regression covers
  the release filename and upgrades from 0.3.28/code31. The existing signing
  identity is retained to allow installed copies to update without uninstalling.
- Both ARM64 and x86-64, Android 11+, providers, the previous volume correction
  and original stereo/surround routing are retained. Native and vendor payloads
  are compared with 0.3.28; no private renderer implementation is copied.

The UI checks run through Robolectric against the debug test variant. They do
not constitute running the optimized release on a physical phone. Live Apple
subscriber playback, old vendor-runtime compatibility and phone performance
remain unverified; the prior native evidence below is unchanged-source evidence.

Version **0.3.29/code32** retains the package/signing identity and normal OTA feed.

# Previous verification: Music 0.3.28 / fixed speaker lights

31 targeted app checks pass: seven channel-monitor checks, ten player-comparison
checks, three processing-state checks and eleven account/updater checks. The
monitor checks are repeated after refreshing the animated preview fixture.
Android lint and all four native/runtime packaging guards pass. No DSP, decoder,
provider or Apple account source changes from the validated 0.3.27 baseline.

- Audio activity changes only opacity/brightness of fixed-size input and output
  nodes. Growing bars and sweeping peak arcs are removed. Bounds remain identical
  as measurements change; silence/non-finite measurements do not produce light.
- Preserves Claude's speaker plan: absent positions are unlit reference outlines.
  Native Original comparison dims non-front inputs; Processed displays actual
  renderer inputs. Bypass is explicitly distinguished and never labelled as a
  renderer downmix or front-pair-only comparison. Native Original keeps its
  shared gain/delay description instead of claiming untouched source PCM.
- Tests cover stereo, 5.1, compact-screen 7.1, unknown layouts, pause/collapse,
  unchanged player composition during observations and physical L/R under RTL in
  both the map and output pair. The previews use generated test measurements.
- The prior surround-volume correction, stereo bypass, native libraries and
  bundled Apple runtime are unchanged. Prior native/phone limitations below
  remain; no new physical phone, performance or live Apple playback claim.

Version **0.3.28/code31** retains the package/signing identity and normal OTA feed.

# Previous verification: Music 0.3.27 / volume, channel monitor and bundled setup

**331 unique app/DSP checks pass, with no failures, errors or skips.** The full
244-app/86-DSP run passed first. After the compression-only asset representation
change and an added physical-left/right RTL check, all nine affected player/setup
checks pass on the final representation (245 unique app checks, 86 DSP checks).
Android lint and the four exact-library/asset APK guards pass on the final build.
All four README architecture diagrams render. Light/dark stereo, 5.1 and compact
7.1 native UI previews were inspected.

- Real private DMR comparison on a 32-second reference measures a 14 dB RMS gain
  increase after removing Music's fixed -14 dB preamp cut: -32.276 to -18.276 dBFS,
  peak 0.91553. The complete 189.856-second recording passes the real decoder/DMR
  route at unity preamp, including switching and bounded output. The original
  native peak limiter stays active; private renderer source/defaults are unchanged.
- Meter tests cover absolute per-channel RMS/peak, PCM8/16/24/32/float, silence,
  unknown layouts, buffer-state preservation, backpressure and pause/seek resets.
  Stereo remains byte-exact. UI checks prove meter publications do not recompose
  the surrounding player and that RTL does not swap physical speakers. Metering
  stops outside the open, playing, foreground panel. These are sink PCM levels,
  before output volume/effects, not an acoustic measurement or an exact hardware
  timing claim. Worst-case host metering at 16 channels/192 kHz used 34.4 ms of
  one CPU thread per second of audio; phone performance remains unmeasured.
- The exact user-supplied 3.6.0-beta/build1109 APKM matches all 18 pinned images
  in ARM64 and x86-64. Automatic compressed-asset preparation and strict failure/
  cleanup are tested on Android API 30 and 35. The 17-image playback closure excludes
  the unused ICU test utility. A source preparer reproduces all 37 packaged assets
  exactly; no Apple code was executed by these tests. The test resource package
  explicitly tracks external assets so incremental renames invalidate its cache.
- The supplied runtime is included only through an explicit external build input.
  The user stated redistribution permission and a paid monthly fee; no independent
  agreement verification is claimed. Original acknowledgements remain available.
  Public source archives contain no vendor images or private DMR implementation.
  The compressed APK remains within the existing 200 MB OTA limit.

No physical phone, live subscriber sign-in or commercial Apple playback was tested.
Delivered lossless/multichannel availability and compatibility of the old runtime
with 16 KiB-page phones remain unverified. DMR still renders stereo from the actual
surround bed; no Spatial mode, scene decoder or synthetic upmix is reintroduced.
Native sanitizer/runtime-metadata results retained below are unchanged-source
baseline evidence, not newly executed checks for this release.

Version **0.3.27/code30** retains the package, signing identity and preview OTA feed.

# Previous verification: Music 0.3.26 / standard surround bed

**236 app tests and 79 DSP tests pass** (315 total), with no failures, errors or
skips. Android lint, APK assembly and all four native packaging guards pass.
All four updated README architecture diagrams render successfully.

- The actual FFmpeg Java/JNI decoder plays the complete 189.856-second E-AC-3
  reference through the actual private DMR: six channels, 48 kHz and 9,113,088
  decoded frames. Original/Processed switching, finite output, end-of-stream and
  playback position pass. The terminal audio sink is a host stand-in, not a phone.
- Native tests confirm synthetic upmix is disabled and produces zero generated
  height-channel energy. Genuine extended source positions remain supported.
- Music has only Original and Processed modes. Stereo sources bypass DMR unchanged.
  Multichannel sources use the original renderer and render two-channel output;
  this does not claim physical 5.1/7.1 speaker output or full Atmos object rendering.
- Upgrade tests confirm obsolete Spatial and scene-decoding preferences are cleared
  while the Processed preference is preserved. The APK contains no binaural renderer,
  HRIR assets or OpenJOC runtime. The standalone renderer/research source is retained.
- Existing 24-bit ALAC sample-preservation, account, import, provider and player tests
  pass. Native binaries that remain packaged are unchanged from the prior baseline.

No physical Android phone or live Apple subscriber session was used. Listening
quality, hardware output and commercial Apple lossless/multichannel availability
remain unverified. The older sanitizer and runtime-inspection results below are
baseline evidence, not newly executed checks for this release.

Version **0.3.26/code29** retains the package and signing identity for OTA updates.

# Previous verification: Music 0.3.25 / runtime import fixes

43 targeted app tests pass with no failures, errors or skips: archive/protocol
checks on Android API 30/35, account/update checks and settings callbacks.
The added APKM test uses compressed nested APK splits and confirms that only the
requested architecture is extracted. Dependency tests reproduce an unrelated ICU
test helper requesting libicutu_sv_apple.so; playback loading excludes that helper
while missing required dependencies, missing roots and cycles still fail.

The picker now accepts documents independently of provider MIME classification.
Content and every pinned library hash are still verified. The loader follows the
five entry points used by the native adapter and their transitive dependencies,
rather than preloading every image in the SDK inventory. Required-library errors
identify the requesting image. No missing library was added to Android's public
library allowlist, and no provider or playback feature was removed.

Android lint and the four exact-library APK guards pass. Native decoder, DMR and
Apple adapter binaries and their source are unchanged from the evaluated 0.3.24
baseline below; their 90 DSP and 8 scene-decoder test results are retained as
baseline evidence, not newly rerun tests. Live native loading and Apple subscriber
playback still require phone validation. This change addresses the import blocks;
it does not establish delivered lossless or multichannel availability.

Version 0.3.25/code28 retains the package and signing identity for OTA updates.

# Previous verification: Music 0.3.24 / Apple account playback adapters

**232 app tests, 90 DSP tests and 8 scene-decoder tests pass** (330 total), with
no failures, errors or skips. Android lint, APK assembly and all four integrated
native packaging guards pass. Fifteen additional runtime-inspection tests pass;
the native FFmpeg regression suite passes under ASan/UBSan, including the complete
189.856-second E-AC-3 reference grouped into 1/2/3/6-frame packets. All four README
architecture diagrams parse and render.

- Apple settings expose AAC web sign-in, native lossless/multichannel setup,
  cancellation and disconnect without replacing existing providers or discovery.
- Real host JNI checks platform JSON conversion, Unicode, 64-bit integers,
  attached native threads and rejection of unauthenticated native operations.
- Account tests cover native verification codes, encrypted session persistence,
  stale web callbacks, cancellation and Binder error/session boundaries.
- Apple protocol checks run on Android API 30 and 35 in Robolectric: allowed
  origins, token parsing, no account headers on media requests, redirect/response
  limits, HLS rendition metadata, MP4 ranges, key rotation and stale media access.
- The actual Media3 fragmented-MP4 extractor parses an owned CBCS-protected ALAC
  fixture through the native TrackOutput adapter. Both complete encoded samples,
  including partial-block tails, match the original bytes exactly. The runtime
  decryptor in this test uses an owned synthetic key; it is not Apple's binary.
- Controlled account/CDN responses exercise the real catalogue/rendition resolver,
  actual MP4 codec probing, ALAC-versus-AAC selection and different-recording
  rejection. Server-provided AAC cannot acquire the private account capability.
- The actual FFmpeg Java/JNI decoder preserves every generated 24-bit sample at
  192 kHz stereo and 96 kHz 5.1, including layout and flush/seek. ALAC uses float
  output instead of truncating the source to 16-bit. Hardware output is not tested.
- APK checks match the exact evaluated FFmpeg, OpenJOC, private ABI-3 DMR and public
  Apple adapter for ARM64/x86-64. Apple libraries and private renderer source are
  absent. The app targets all brands on Android 11+, with target API 35.

**No compatible Apple runtime binary, live Apple subscriber or physical phone was
available for execution here.** Native SDK loading, real Apple sign-in, commercial
track playback, delivered ALAC/JOC availability and phone performance remain
unverified. Importing the pinned 3.6.0-beta/build1109 runtime is required for the
native route; a current store installer is not ABI-compatible. AAC is lossy.
DMR receives multichannel input but renders stereo; JOC reconstruction remains
experimental. See [account setup and limits](APPLE_ACCOUNT_PLAYBACK.md).

Version **0.3.24/code27** retains the OTA package, signing identity and asset names.
Release assets include a validation report and matching decoder/application source.

# Previous verification: Android 11 baseline and Apple runtime investigation

**195 app tests, 88 DSP tests and 8 scene-decoder tests pass** (291 total), with
no failures, errors or skipped tests. Android lint, APK assembly and all three
integrated native packaging guards pass. The read-only Apple runtime checker has
15 additional passing tests, including real generated ARM64/x86-64 ELF fixtures.

- The merged manifest and built APK both declare minimum Android API 30 (Android
  11), with target API 35. Native packaging retains ARM64 and x86-64. There is no
  Motorola or other phone-brand restriction; the Edge 50 Neo is one test device.
- The existing real HTTP/MP4 forward/backward seek test now runs under both
  Android API 30 and 35 in Robolectric and passes on each.
- Static native checks distinguish ELF architecture, 4 KiB/16 KiB load alignment,
  dependency-name gaps, malformed images and conflicting APK splits. Matching
  bytes or dependency names never mark runtime/account/playback verification true.
- Read-only inspection of three current-distribution Apple ARM64 images confirms
  their ELF architecture and 16 KiB alignment. The archive lacks the old wrapper's
  required libstoreservicescore.so. These observations do not establish a working
  native adapter or any delivered audio format; see the provider research record.

No Android phone or Apple subscriber session was used. Apple account playback,
lossless streaming and multichannel input to DMR have not been integrated by
this work. No private Apple code or binary was added to the app. The app version
is still 0.3.23/code26 and no new OTA release was published for this working-tree
validation. The previously published release is described below.

# Previous verification: Music 0.3.23 / live Home controls and provider radio

**194 app tests, 88 DSP tests and 8 scene-decoder tests pass** (290 total), with
no failures, errors or skipped tests. Android lint and all three integrated
APK packaging guards also pass. The updated README architecture diagrams render.

- Compose checks Home Pause → Play → Pause and buffering status, including a
  legacy/current ID alias. Toggles preserve position and queue and do not call
  the start-song callback. A different last-played song starts only that song.
- Recently played, Mixed for you, Saved, History and Search selection checks pass
  one seed rather than their displayed lists. Play mix / Play all retain their
  explicit lists; album/playlist playback still retains its collection.
- The production RadioQueue runs against a real Media3 ExoPlayer and controlled
  provider responses. Provider order is preserved; current/duplicate/local entries
  and tracks manually queued while a request is pending are excluded.
- Tests prove stale responses cannot enter a newer selection, disabling Autoplay
  cancels a pending response, local files do not make radio requests, and explicit
  lists remain ordered before recommendations are appended near their end.
- A failed request keeps the current song, position and play intent. Retry adds
  provider results without a history fallback or restarting audio. The UI exposes
  loading/error/retry status separately from audio playback errors.

The bundled YouTube engine's existing radio operation calls YouTube Music getUpNext;
it does not read the app's local history. Spotify recommendations are not integrated.
These are host UI/Media3/provider-fixture checks; no phone or live signed-in YouTube
session was used here. Native decoders, DMR and source selection are unchanged.
Version 0.3.23/code26 retains the package, signature and normal OTA asset names.

# Previous verification: Music 0.3.22 / stable Home layout and scrolling metadata

**182 app tests, 88 DSP tests and 8 scene-decoder tests pass** (278 total), with
no failures, errors or skipped tests. Android lint and all three integrated
APK packaging guards also pass.

- The pre-fix Compose checks reproduce shelves moving 57 dp at the normal test
  width and 83 dp on a narrow screen with larger text, plus a 56 dp shift of the
  Home Play control when a title/artist wraps.
- Song cards, Last played and the compact bar use a single title line and a single
  artist line, including when the artist is initially empty. Text keeps the current
  typography and scales with the configured font size.
- Regression checks compare section/control coordinates across horizontal scrolling,
  metadata refreshes and short/long song names, at normal and enlarged font sizes.
- A frame-clock/pixel check verifies that overflowing compact-bar text visibly scrolls
  while its bounds and playback-control positions remain unchanged. Full metadata
  remains in accessibility semantics. Reduced-motion rendering uses static ellipsis.

These are host Robolectric/Compose rendering checks. No Android phone is connected.
The full player, source resolution, decoder/DSP binaries and playback behaviour are
unchanged. Version 0.3.22/code25 uses the existing package/signature and OTA filenames.

# Previous verification: Music 0.3.21 / simplified Settings

**178 app tests, 88 DSP tests and 8 scene-decoder tests pass** (274 total), with
no failures, errors or skipped tests. Android lint and all three integrated
APK packaging guards pass.

- Settings updates remain reachable by scrolling after removing the pinned
  section selector; the Reduce motion option is absent.
- The shared Import audio entry reaches the existing file picker only after
  choosing a file, and it retains folder selection, rescan and removal callbacks.
- Existing account, source-editor, history, player-motion and library-removal
  checks remain part of the app suite. Audio import/decoding and native artifacts
  are unchanged from 0.3.20.

These are host Robolectric/Compose checks, with screenshots of the laid-out UI.
No Android phone is connected. Version 0.3.21/code24 keeps the existing package,
signing certificate and normal OTA asset names.

# Previous verification: Music 0.3.20 / library removal and consistent history

**177 app tests, 88 DSP tests and 8 scene-decoder tests pass** (273 total), with
no failures, errors or skipped tests. Fatal Android lint and all three integrated
APK packaging guards also pass.

Regression coverage includes:

- Compose Settings counts the same unique local/YouTube identities as History,
  including legacy IDs, and removing the playing import clears the mini player.
- A real Media3 ExoPlayer queue loses every removed local copy. Removing the
  current import stops it; removing another queued import preserves the current
  song and position. A blocked, already-open source read is cancelled and closed.
- Removing an indexed folder replaces both the current and queued pinned local
  sources, preserving the catalogue song, seek position and pause state.
- Controlled concurrent document-provider scans cannot restore a cleared folder
  or overwrite its replacement after the newer selection has committed.
- Local removals survive a reconstructed store, reject stale saved-list writes,
  retain the original document, reject imports started before removal, and permit
  explicit reimport. Upgrade cleanup
  retains registered imports and online history. Saved lists beyond 100 entries
  persist completely.
- Forgetting history clears Home's shortcut and every older play of that song,
  retaining Saved and playback. Controlled out-of-order writes cannot undo clear
  or removal after storage reload.

The app also suppresses the current listen after History deletion so periodic
player callbacks and shutdown cannot immediately recreate it. History-off applies
both to detailed events and Home recents. Recommendations being regenerated are
cancelled before history is forgotten.

Validation uses Robolectric/Compose, the real Media3 queue/loader and deterministic
provider/IO fixtures on this host. No phone is connected; device listening, Android
frame pacing and live subscriber playback are not established by these checks.
The native DMR, FFmpeg and OpenJOC artifacts and provider integrations are unchanged
from 0.3.19. Version 0.3.20/code23 retains the package/signature and OTA asset names.

# Previous verification: Music 0.3.16 / multichannel rates and comparison

- **128 app tests and 72 DSP tests pass**, no failures, errors or skips, plus
  fatal lint and both decoder/private-DMR APK packaging guards.
- Real original DMR JNI processes six/eight-channel PCM through all three new
  rate conversions. A centre-only A/B test measures audible-level output in
  Processed, suppression in Original, and return in Processed, alongside exact
  duration, mode reports, one sink/engine and EOF-clock/tail checks.
- Compose tests verify disabled comparison and a clear reason for unknown layouts
  or missing DSP. The lazy queue test scrolls to its offscreen target. Existing
  gestures, progress and pending-mode checks pass.
- Bypassed PCM reports its configured sink channel count, not a false DMR stereo
  render. Actual hardware routing/mixing is not established by that report.
- Stereo remains unchanged. The private DMR and FFmpeg native artifacts are the
  same as 0.3.15. Version 0.3.16/code19 uses the same signature/package and normal
  OTA filenames; tests cover upgrade from 0.3.15/code18 and the older prototype.

These native tests use decoded synthetic PCM and a recording sink on the host.
Phone AAC/ALAC decoding, AudioTrack routing, listening quality, UI frame rate and
the user's Starboy file remain unverified. See [findings](DSP_FIX_FINDINGS.md).

# Previous verification: Music 0.3.15 / E-AC-3 fix and normal OTA

- **126 app tests and 66 DSP tests pass**, with no failures, errors or skips,
  including Claude's real Java/JNI multiframe packet and layout-change tests.
  Corrected uncompiled diagnostics tests without changing the private DSP core.
- Native decoder regression tests pass under ASan/UBSan. The complete 189.856 s
  Dolby UltraViolet / Untie JOC recording decodes identically with 1/2/3/6
  syncframes per packet: 5,933 frames, 218,714,112 PCM bytes per pass, identical
  SHA-256. This is host codec validation; full-music Android output/DMR listening
  was not performed. Synthetic tests exercise the original DMR JNI separately.
- Fatal lint and both integrated decoder/DMR APK guards pass. The ARM64/x86-64
  FFmpeg JNI adapters are rebuilt; original private DMR bytes are unchanged.
  Release checks verify the existing signature and 16 KiB ZIP/ELF alignment.
- Normal OTA asset `music-debug.apk`, tag `v0.3.15`, versionCode 18. OTA tests
  cover both installed 0.3.13/code16 and manual 0.3.14/code17, alongside older
  releases. Decoder source, public app source and checksums accompany the APK.

No phone is attached and the user's Starboy file has not been provided. A real
decoder defect is fixed; the user's particular phone failure remains unverified.
JOC bed decoding does not establish a full Atmos object render. See
[fix findings](DSP_FIX_FINDINGS.md) and [testing](LOCAL_EAC3_PROTOTYPE.md).

# Previous verification: local E-AC-3 → original DMR prototype

- **123 app checks and 52 DSP checks pass**, with no failures, errors or skips.
  The complete final run includes player motion, compare status, source matching,
  imports and settings interaction. The source editor uses Compose's automatic
  clock; the navigation test targets the current clickable About tab, which now
  contains updates. No product UI was changed for these test corrections.
- Three new checks load the actual FFmpeg JNI decoder. Synthetic E-AC-3 decodes
  into six independently checked channels at 48 kHz, with a `5.1(side)` mask;
  flushing/seeking continues decoding. That PCM runs through the actual original
  DMR JNI. Stereo remains byte-identical and creates no DSP engine. Codec/DRM
  selection and conservative speaker-mask mapping are checked too.
- The private DSP source and supplied ARM64/x86-64 native binaries are unchanged.
  The new decoder uses the Apache-2.0 Media3 adapter and a minimal, shared
  LGPL-2.1-or-later FFmpeg n6.1.6 build. GPL/nonfree components are disabled. The
  release includes corresponding decoder source and public Music source.
- Android fatal lint and both packaging guards pass. The non-debuggable APK
  includes the original DMR and all four decoder libraries for both 64-bit ABIs.
  Release validation checks signing identity and APK/ELF 16 KiB alignment.
- Explicit file import now tolerates missing platform codec metadata, while
  rejecting empty/unreadable files. Three import checks cover both paths.

This is a separate manual test release of 0.3.14 / code 17. It is excluded from
normal OTA selection by its non-version tag and prototype APK asset name. The next
distributed update must use a versionCode greater than 17. No physical Android
device is attached; phone playback, audible output, seek latency and performance
remain unverified. The fixture is ordinary E-AC-3, not an Atmos object test.
JOC decoding supplies a channel bed, not the complete Atmos scene. No provider
subscription integration or protected-audio decoder is added.

See [prototype testing](LOCAL_EAC3_PROTOTYPE.md) and
[provider implementation status](PROVIDER_PLAYBACK_STATUS.md).

# Last published verification: Music 0.3.13 / pending mode and player UI work

- Three new Compose cases reproduced 0.3.12's incorrect paused/loading status
  before the fix. **102 app checks and 48 DSP-library checks now pass**, with no
  failures, errors or skips. Tests cover delayed renderer acknowledgement while
  paused/playing, reversing a pending request, loading state, both timeline clock
  consumers, seeking/navigation, metadata cache invalidation and existing gestures.
- Requests and native acknowledgements remain separate. Selecting a mode never
  fabricates an applied report. Paused requests wait for playback; native compare
  still changes on new PCM without reloading, seeking or resetting the source.
- The 200 ms polling path no longer republishes the complete screen snapshot or
  reparses unchanged catalogue metadata. Lifecycle-aware clock consumers are
  confined to the timeline components; their moving lines use separate draw layers.
  Previous uses the controller's live position rather than the event snapshot.
- The OTA build uses `rtensorOta=true`: the APK is **non-debuggable**, keeping the
  same package/signing identity and legacy asset filename. Android fatal lint checks
  and the embedded ARM64/x86-64 DSP packaging check passed. Local developer builds
  remain debuggable by default. Version `0.3.13`, versionCode `16`.
- No native implementation, rate converter, provider client or audio buffer was
  changed. The native bytes are the same as 0.3.12. A host-only probe processed four
  roughly 32-second stereo cases at 44.1/48 kHz, Original/Processed. With the build
  idle, average processing took 0.21–0.38 ms per roughly 10.7 ms block; the earlier
  concurrent-build probe had higher scheduling outliers. This does not measure
  phone audio deadlines or UI frame rate.

The screenshot's display mismatch is reproduced and covered by regression tests.
Physical-phone scrolling, device frame rate and audible mode switching are still
unverified here. The UI changes remove identified work; they do not establish that
all device lag is resolved. No separate DSP app is introduced.

# Previous verification: Music 0.3.12 / live player comparison

- **94 app checks and 47 DSP-library checks passed**, with no failures, errors or
  skips. These include working player comparison callbacks, preference persistence,
  compact settings, real host JNI stereo processing at 44.1/48 kHz, seeking, partial
  writes, converter timing, and mode changes without source reload or history reset.
- **22 private portable-core checks passed**, including stereo monitor generation,
  live front-pair comparison, channel order and parity with the desktop processor.
  The original processing implementation and coefficients are unchanged. Stereo
  uses the existing optional monitor generation; the adapter adds no replacement
  matrix or recovered-Atmos claim.
- AddressSanitizer/UndefinedBehaviorSanitizer checks passed, including concurrent
  compare/reset/process and repeated native lifetimes. Native process/reset/compare
  performed **zero allocations and frees** in the instrumented run.
- The public 44.1→48 kHz stereo converter passed analytical 1/10/19/20 kHz sine
  checks, DC gain/channel checks, exact duration accounting, irregular-block
  equivalence, malformed-input/reset and sink backpressure checks. Source and render
  rates are reported separately. Converter delay and padding are excluded from the
  playback clock. Other unsupported stereo rates bypass with comparison disabled.
- The UI retains its existing design. Playback settings contain only Autoplay radio
  and Import audio. Now Playing offers Original/Processed with actual render status.
  On multichannel input, Original means the desktop renderer's FL/FR-only comparison.
  Both modes retain common gain, conversion and delay; neither claims bit-perfect
  output. A short fade changes modes at silence while keeping native history warm.
- The signed OTA APK is version `0.3.12`, versionCode `15`, package
  `labs.rtensor.music`, with the same certificate as 0.3.11. The publication task
  verifies that both supplied ARM64/x86-64 libraries are inside Music.

The standalone DSP component remains reusable; no separate DSP app is needed.
YouTube client selection and transport code are unchanged. Tests exercise decoded
PCM and fixture provider responses; no live YouTube song, physical Android route,
ARM runtime, Apple Music protected audio or Atmos object decoder is verified here.
Phone listening and performance remain device checks.

# Previous verification: Music 0.3.11 / integrated DSP OTA

The original compiled renderer is bundled inside the existing Music APK. No
separate DSP application or library installation is required. The reusable library
remains available; its implementation source and coefficients stay private. The UI
is unchanged. The normal OTA feed is `RTensorLabs/music-app`.

- **90 app checks and 32 DSP-library checks passed**, with no failures, errors or
  skips. Seven checks execute the actual host JNI library. The app checks include
  OTA selection and upgrade identity for both 0.3.9 and the private 0.3.10 preview.
- `:app:verifyOtaDspBundle` verifies the ARM64 and x86-64 renderer bytes inside the
  Music APK against the supplied private artifacts. Official OTA builds must run
  this task. Contributor builds can still omit the private core.
- Package `labs.rtensor.music`, version `0.3.11`, versionCode `14`; same signing
  certificate SHA-256
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
  Release assets use the installed updater's `music-debug.apk` and `SHA256SUMS`
  names. VersionCode 14 upgrades the previous public and private preview APKs.
- The DSP adapter and native processing implementation are unchanged from the
  preview validation below. Publication removes unneeded native symbols without
  changing processing code. The 630-second reference comparison and sanitizer
  results below apply to that same native implementation.

Supported multichannel PCM is rendered automatically; stereo bypasses DSP. No
Android device is attached, so phone playback, routing and ARM execution still need
device validation. This does not add Apple Music protected-audio access or an Atmos
object decoder. See the release's `ota-validation.json` for package checks.

# Previous verification: private DSP preview, app 0.3.10 / library 0.1.0

Based on Claude's `a33d6bf30d6fe1b9785d781e824a02d85639c22b`. No UI implementation
was changed for the DSP work. The native processing implementation is a separate
private artifact; the public repository contains interfaces and the Android adapter.

## Current validation

- **89 app Android/JVM checks and 32 DSP-library checks passed**, with no failures,
  errors or skips. Seven library checks load and execute the actual host-built JNI
  library. The remaining checks cover format/position contracts, PCM conversion,
  partial writes, seek/reset, EOF/backpressure, format changes and spatializer opt-out.
- In the private source tree, **18 portable-core checks**, **56 existing Python DSP
  checks** and **15 existing native-reference checks** passed. The portable core
  matches the original renderer, including independently reordered speaker positions.
- Native processing/reset performed **zero allocations and frees** in the instrumented
  test. AddressSanitizer/UndefinedBehaviorSanitizer checks passed for processing,
  concurrent reset/process, repeated lifetimes, invalid buffers and overlap handling.
- Three complete generated multichannel recordings passed through the real portable
  core and a host build of the original native desktop core. All output samples and
  SHA-256 digests matched: 180 s / 6 channels, 210 s / 8 channels, 240 s / 16 channels.
  Total source audio: **630 seconds**. These are synthetic PCM fixtures, not streamed
  songs, phone decoding or listening tests.
- **16 JavaScript unit, 43 Chromium engine and 6 isolated proof checks** passed.
  Browser provider/account/attestation responses are fixtures.
- Android ARM64 and x86-64 libraries compiled against API 26; ELF load segments and
  APK ZIP placement meet 16 KiB page alignment. APK/AAR DSP bytes match the separate
  private native build. Bundled engine assets match source.
- Package `labs.rtensor.music`, version `0.3.10`, versionCode `13`; the signing
  certificate remains SHA-256
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.

## Boundaries of this preview

No Android device is attached. Actual Android codec layouts, AudioTrack routing,
ARM runtime output, Bluetooth, OEM effects, startup/seek latency and complete-song
listening need phone validation. JNI execution above is on the host JVM; Android
native binaries were cross-compiled. The test build includes a generated 24-bit,
48 kHz 5.1 FLAC separately for Settings → Import audio → Library playback checks.

The existing native core accepts 48/96/192 kHz. Mono/stereo bypass the DSP; unsupported
multichannel rates/layouts fail. Known decoder positions are required, with FLAC's
standard assignment as a documented fallback. Generic multichannel WAV without a
known layout is not guessed. An unavailable private core in a DSP-enabled build
causes a render error rather than a claimed successful downmix.

The adapter drains latency plus a bounded 50 ms filter tail; gapless/crossfade
certification is outside this preview. Processed output opts out of Android's
spatializer, but device/OEM effects still require validation. DSP rendering is not
bit-perfect hardware output. No Apple Music authentication, protected PCM access,
new streaming source or Atmos object decoder is implemented. PCM channel counts
alone do not establish full Atmos rendering.

The 0.3.10 preview was distributed privately. Music 0.3.11 embeds the compiled core
in its normal public OTA APK, as described above. Public source builds without the
private artifact retain the normal playback path. See [DSP build instructions](dsp/README.md).

# Previous verification: v0.3.9

Reviewed Claude's `73e4cb6cea4ebba3ee240212adb69566eec9a870` update, including
the recommendation changes in `7faea75` and the previous media transport fixes.

## Current results

- **88 Android/JVM checks passed**, with zero failures, errors or skips. This includes
  the existing 18 Compose checks, 12 new motion/gesture checks and 8 recommendation checks.
- **15 JavaScript unit tests**, **43 Chromium engine checks** and **6 isolated proof
  checks** passed against the exact packaged engine. Provider/account responses are fixtures.
- `:app:assembleDebug :app:testDebugUnitTest` completed successfully with JDK 17,
  Gradle 8.11.1 and Android SDK 35. Package `labs.rtensor.music`, version `0.3.9`,
  versionCode `12`. All four engine assets in the APK match the committed source.
- APK signature verification passed with the previous certificate, SHA-256
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
  The APK contains no test audio, Python verification helper or keystore.

## Player finding and fix

A long cover swipe could move backward on release: its fixed one-third-width exit
target was closer to the centre than the user's drag position. Motion-enabled tests
reproduced this before the fix. The cover now continues outward from the actual
release position. Its outgoing position is retained through the crossfade, while
the new cover starts centred. Both next and previous gestures advance exactly once;
short drags and blocked queue-end drags return without skipping.

Two other initial failures were test-harness issues. Android measure/layout must run
between virtual animation frames when checking return navigation. A large-text queue
test must scroll its lazy container before looking for an off-screen row. Neither
required a navigation or layout change in the app.

## Motion coverage

The new `PlayerMotionTest` runs real Compose UI and pointer handlers with Robolectric
native graphics on API 35. Motion cases use an explicitly controlled frame clock.

- Long left/right cover swipes: no direction reversal, one skip, outgoing pose held
  through the fade, incoming cover centred.
- Short and unavailable-next cover swipes: settle back without advancing.
- Track title/control crossfades: outgoing content disappears after settling;
  buffering does not move the play control.
- Timeline: advances with frames while playing, holds when paused, disables during
  an unresolved track change, and a real drag issues one bounded seek.
- Reduced motion: outgoing track/cover content is removed promptly.
- Inline queue: selecting a row passes its original queue index without rebuilding
  playback; a vertical cover swipe scrolls to the queue.
- Mini player: swipe up opens the player; Back restores it; horizontal swipe skips
  once; Next is disabled at the queue end.
- Home/Explore pull-to-refresh: each dispatches once; an in-progress refresh ignores
  another pull.
- A 320 dp screen at 1.5× font scale: transport controls and queued tracks remain
  reachable by scrolling.

Eighteen existing screen captures and motion frames are included in the preview ZIP.
`player-swipe.gif` and `player-crossfade.gif` contain frames drawn from the actual
Android view at controlled clock positions, converted to 25 fps with a final hold.
The tracks/accounts are fixtures; covers are neutral placeholders. These previews
are not a measurement of device frame rate, GPU jank, touch latency or network loading.
The JSON report records test counts, case names and capture details.

Reproduce the new checks with:

```sh
./gradlew :app:testDebugUnitTest --tests labs.rtensor.music.PlayerMotionTest
```

Captures are written to `app/build/reports/animation-checks/`.

## Current limits

No physical Android device is attached and no emulator/system image is installed.
Live Google account selection, YouTube acceptance, real startup/seek latency,
screen-off playback, Bluetooth, hardware decoding, GPU smoothness and haptic feel
remain unverified. The workspace proxy blocks YouTube; this UI task does not
establish that any live YouTube song plays. No proxy bypass or relay was used.

The full-length synthetic HTTP/cache/Media3 extraction regressions passed again in
the current Android suite. Independent FFmpeg decoding below was performed for
v0.3.8 and was not repeated for this UI-only fix. The earlier metadata SharedFlow
replay limitation remains in unchanged code. SABR-only streams remain unsupported;
DSP remains off.

# Previous verification: v0.3.8

Based on Claude's `94f14f9e034b0206300327bd1b8c27d07cbba648` update. This build adds
native cache/retry fixes and corresponding engine feedback/format constraints.

## What was actually tested

- **73 Android/JVM tests passed**, no failures or skips, including 18 Compose checks.
- **15 JavaScript unit tests**, **42 Chromium engine checks**, and **6 isolated proof
  checks** passed. Browser provider responses, account data and attestation are fixtures.
- Three generated AAC/MP4 tones were transferred fully over real loopback HTTP through
  the production Media3 range/retry/cache classes. Received bytes matched each source
  SHA-256. The real MP4 extractor reached EOF and independently FFmpeg decoded each
  complete received file with `-xerror` and no decoder warnings/errors.

| Synthetic test track | Length | Received bytes | Complete transfer / extraction / decoding |
| --- | --- | --- | --- |
| 440 Hz AAC | 180 s | 2,917,751 | Passed |
| 550 Hz AAC | 210 s | 3,405,530 | Passed |
| 660 Hz AAC | 240 s | 3,891,527 | Passed |

Each track crosses at least two one-MiB boundaries. The server rejects the first
client after one MiB, rejects the second client too, then serves the third. Recovery
preserves the exact byte offset. Cached backward/forward/tail reads use no new HTTP.
A separate queue item reads only its header, seeks outside the cache and recovers
without losing the original catalogue URI. The extractor then verifies timestamped
forward/backward seeks. The total audio decoded is **630 seconds (10.5 minutes)**;
tests run faster than real time and do not represent listening on a phone.

Reproduce after running the Android suite:
`python3 tools/verify-full-track-audio.py --output artifacts/playback-validation.json`

## Fixes covered by the regressions

- CacheDataSource sees the original catalogue URI rather than persisting an expiring
  signed media URL that bypasses resolution on an uncached seek.
- Media retries exclude the actual failed path for that queue item, preserve source
  identity/headers and stop after a finite budget. Other recordings and prefetches
  cannot overwrite its rejection list.
- Engine responses include the playback path and encoded format. Mid-file recovery
  accepts the original itag/MIME/length only; incompatible audio produces an explicit
  error rather than mixed/corrupt media bytes.
- Native `isPlaying` feedback selects the preferred path. Merely obtaining a URL
  does not mark playback successful. All excluded paths fail without more player calls.
- Separate guest sessions keep visionOS variants isolated; concurrent exclusion
  lists do not share an incompatible resolution result. Visitor requests share one
  in-flight fetch. Native guest requests keep account cookies/headers out.
- Direct visionOS resolution completes while player JavaScript is deliberately
  blocked. A ciphered guest request waits for and receives that shared player.
- Wrong-video responses are rejected before returning audio or relabelling metadata.

The visionOS version/model/agent fields match NewPipe Extractor's current public
constants. This comparison is source inspection, not evidence of live compatibility.

## Build and signing

- Package `labs.rtensor.music`, version `0.3.8`, versionCode `11`.
- APK signing certificate matches prior previews, SHA-256:
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
- Gradle engine/source/proof digest checks passed and APK engine assets match source.
- The test tones and Python verification helper are development-only, outside the APK.
  No keystore, private DSP implementation or real account data is in the source archive.

## Not verified live

**Three live YouTube songs have not been tested here.** A fresh YouTube request was
rejected by the workspace proxy: `CONNECT tunnel failed, response 403`. `adb devices`
reported no attached Android device; no emulator/system image is installed. No proxy
bypass or remote YouTube relay was used.

Native Google login/consent, live acceptance of the visionOS profiles/attestation,
full playback of `zAiIgYOH4Ys`, real startup latency, device decoders, screen-off/Wi-Fi
behaviour and Bluetooth remain unverified. The deep buffer is a setting, not a
measured result. SABR-only streams remain unsupported and DSP remains off.

The earlier metadata SharedFlow replay limitation remains in unchanged code:
metadata produced without a subscriber is not retained. This task focuses on
playback transport and client fallback, not history/recommendation behaviour.
