# Music for Android

Temporary name: **Music**. Native Android player from RTensorLabs with the approved
Margin visual direction: warm paper, DM Sans, original provider artwork and raspberry controls.

## Version 0.3.13 — truthful switch status and lighter player updates

The comparison now shows a pending state while the requested mode differs from
the renderer's acknowledged mode. A paused selection says “ready when you play”;
active playback says “Switching…” until new audio applies it. Loading preserves
your selection without claiming the effect is already active.

Playback progress updates only the full-player and mini-player timelines. It no
longer rebuilds the screen state, reparses current-song metadata or remaps the queue
five times per second. Moving progress lines draw in their own layers. Track,
pause, seek and metadata events still update the screen normally.

Published builds use `-PrtensorOta=true` to disable debugging overhead while keeping
the same package, signing certificate and updater-compatible APK filename. Normal
contributor builds remain debuggable. Phone scrolling performance needs device
validation; host checks are recorded in [VERIFICATION.md](VERIFICATION.md).

## Previous release: 0.3.12 — compare Original and Processed while listening

Now Playing has an **Original / Processed** control. It switches the existing
renderer during playback with a short fade, without seeking, loading another
stream or resetting its filter history. Your choice is saved.

YouTube's decoded stereo can use the original renderer's optional stereo monitor
upmix. This creates spatial ambience from stereo; it does not recover an Atmos
mix or lossless source. Common 44.1 kHz stereo is explicitly converted to 48 kHz
before the fixed-rate core; 48/96/192 kHz uses its own rate. Original bypasses
the native upmix/matrix while retaining the same rate conversion, delay and gain
for comparison. For multichannel recordings, Original keeps only FL/FR, matching
the desktop renderer's comparison. The player identifies that front-pair mode.

Playback settings now contain the Autoplay control and Import audio action. The
four descriptive feature rows and the hard-coded processing status were removed.
DSP remains inside the normal Music APK and is distributed through OTA.
The integrated APK targets 64-bit ARM and x86-64, matching the supplied native core.

## Previous release: 0.3.11 — integrated DSP and OTA

The normal Music APK now bundles the original native DSP and its player adapter.
Install it through Settings → Updates; no separate DSP application or library
installation is needed. The standalone library remains available for reuse.

The existing player routes supported decoded multichannel audio through the
renderer automatically. Stereo bypasses DSP. The UI and account/library storage
stay in the same `labs.rtensor.music` application, with the same signing identity.
The private source and coefficients stay outside this repository; compiled Android
libraries are embedded in the APK.

Official OTA builds must run `:app:verifyOtaDspBundle` with the private artifact
directory. That task rejects an APK missing either native ABI or containing a
different library. Source-only contributor builds remain supported.

## Previous preview: private DSP build (0.3.10)

Adds a standalone Android `dsp` library and Media3 sink adapter. The optional private
native build reuses the original Downmix Renderer processing core, with explicit
speaker-position mapping and float32 stereo output. Stereo sources bypass the DSP.
The app's UI is unchanged from Claude's `a33d6bf` update.

Ordinary source builds contain only the public adapter. The private processing
implementation, coefficients and native binaries are not committed to this repo.
Supply `-PrtensorDspJniDir=/absolute/path/to/private/android-output` to build the
DSP-enabled APK and AAR. See [adapter build and format contract](dsp/README.md).

The initial core supports 48/96/192 kHz and known multichannel speaker positions.
Unsupported configurations fail explicitly. The adapter handles float output,
high-bit-depth conversion, partial writes, seek resets and bounded end draining.
The processed render is not bit-perfect. This is a decoded-PCM integration, not
an Apple Music integration or an Atmos object decoder. Phone playback is unverified.

## Version 0.3.9 — player motion and gestures

Includes Claude's `73e4cb6` update: a queue below the player, calmer transitions,
Home/Explore pull-to-refresh, and the recommendation refinements in `7faea75`.

- Fixes long cover swipes briefly reversing direction on release. The outgoing
  cover keeps its release position during the fade and the incoming cover starts centred.
- Adds 12 Compose checks with animations enabled and actual pointer gestures:
  covers, title/control fades, timeline progress and seeking, mini-player navigation,
  queue selection, refresh, reduced motion and compact screens with large text.
- All **88 Android/JVM checks**, **15 JavaScript unit tests**, **43 Chromium engine
  checks** and **6 proof checks** passed. The signed APK keeps the previous certificate.

The release includes frame captures and GIF previews from the native-graphics UI
tests. They use fixture tracks with placeholder covers and a controlled animation
clock. They do not measure phone frame rate or establish live YouTube playback.
See [verification details](VERIFICATION.md).

## Version 0.3.8 — media fallback and seek fixes

Includes Claude's visionOS 1.04 / visionOS Music / embedded-client update, plus:

- Preserve the catalogue URI through the audio cache, so an uncached seek still
  resolves with the right headers and byte-range metadata.
- Track rejected playback paths per queue item and try remaining paths within a
  finite budget, including when more than one media URL returns HTTP 403.
- Resume at the exact next unread byte; keep the original encoded format and length
  so a fallback cannot splice incompatible audio into the current recording.
- Remember a preferred client only after native playback starts. Prefetched URLs
  do not establish playback success. Guest profiles no longer share mutable state.
- Direct visionOS audio can start without waiting for the web player script; guest
  deciphering receives the shared player once it is ready. Reject wrong-video responses.

Three generated AAC tracks (180/210/240 seconds) were transferred completely over
local HTTP, parsed to EOF by Media3 and decoded to EOF by FFmpeg. These are synthetic
test tracks, not live YouTube songs. YouTube access is blocked by the workspace proxy
and no Android device is attached. See [verification details](VERIFICATION.md).

## Version 0.3.7 — Library, radio and player update

Packages Claude's `9a863d9` update:

- Library combines Saved and listening History, with day groups and weekly stats.
- YouTube Music radio can extend the queue. “Mixed for you” ranks radio candidates
  on this device using listening history and saved songs.
- Music browse/search/radio requests use the music origin and matching request
  signatures; Home reports when YouTube explicitly answers as signed out.
- Adds floating navigation, player/mini-player swipe gestures, a blurred cover
  backdrop, and artwork renditions/caches suited to each display slot.

Live account personalisation, radio, gestures and playback still need phone
verification. See [verification details](VERIFICATION.md).

## Version 0.3.6 — Claude update build

Packages Claude's `76a9d09` update: regional Google sign-in redirects, a Continue
button during sign-in, a shorter device-account handoff timeout, preferred playback
paths, attestation-challenge warmup and buffered artist metadata events.

This build preserves that implementation. Previously identified playback-fallback
and metadata-lifecycle concerns remain; live login and playback need device checks.
See [verification details](VERIFICATION.md).

## Version 0.3.5 — sign-in handoff, seeking and faster starts

- Device Google-account selection now attempts the Google account provider's web-session handoff before opening YouTube. A verified YouTube session is still required; providers that decline this handoff fall back to browser sign-in. No password or one-use handoff URL is saved.
- Removes the separate audio probe before playback. Media3 opens and validates the real stream, with one proof-backed refresh on HTTP 403, including a later range read. Recovery preserves the requested seek position.
- Prefers AAC/MP4, warms the player script, shares concurrent resolutions, retains fresh resolved streams longer, and resolves the next queue item after playback starts. Starts with a 500 ms audio buffer; adds a 128 MiB local LRU byte cache for rereads/seeks.
- Home no longer contains the device-file row. Settings → Import audio adds a document to Saved using a persistent Android document permission; it does not immediately play it. The original file remains on the device.
- Fixes YouTube artist mapping when an empty artist array hid author or linked subtitle data. Missing recent/saved/playing artist data is refreshed from YouTube's exact video metadata and persisted without guessing names from titles.

Device-account handoff, real YouTube latency and live seeking still need phone verification. See [verification details](VERIFICATION.md).

## Version 0.3.4 — refined UI, synced sign-in and smoother motion

- **One account card.** Settings → Account shows Google and YouTube Music together
  with a status line for each. *Sign in with Google* picks a device account and goes
  straight on to YouTube Music with that email preselected; *Sign out* clears both.
  If the YouTube step is skipped, Home shows a *Finish syncing YouTube Music* banner.
- **Home keeps "Back to your music".** The last-played hero, local-file row and
  recently played stay at the top; the YouTube Music feed loads underneath instead of
  replacing them. The loading line reserves its space, so nothing jumps.
- **Settings is easier to scan.** Options are grouped into cards (Account, Playback,
  Appearance, Updates, Privacy) with icon headers and a jump bar at the top.
- **Mini player.** Rebuilt as a floating card with even spacing, a live progress
  edge, a slide-in/out entrance, track-change and play/pause morph animations and a
  loading state.
- **Motion.** Emphasized-easing screen transitions, frame-synced progress (no more
  200 ms steps), a springy navigation indicator, press feedback on cards, artwork
  cross-fades and animated list changes. *Reduce motion* disables these transitions.
  The Settings icon stays 8 dp from the right edge with a full touch target.
- **Ad-free by design.** Music requests only the audio-only stream and never asks
  for ads (no ad placements, no ad video), and playback continues in the background.

This release changes the UI; native sign-in and live YouTube playback still need
real-device verification. It does not establish a fix for the reported HTTP 403.
See [verification details](VERIFICATION.md).

## Version 0.3.3 — native Google account selection

- Adds Settings → Choose Google account using Android Credential Manager and the
  registered Android/Web OAuth clients. Google's signed, nonce-bound response is
  verified before accepting a profile.
- Saves only the selected profile in encrypted local storage; ID/access tokens and
  passwords are not saved. Native cancellation and sign-out are wired.
- Keeps Google identity and YouTube feed connection as separate states. The chosen
  email preselects Google's YouTube sign-in page. Account switches clear the previous
  YouTube connection; existing web-only sign-in remains available.
- Includes the v0.3.2 UI/artwork/transport changes below. Live Google sign-in and
  YouTube playback remain unverified here. See [Google sign-in details](GOOGLE_SIGN_IN.md).

## Version 0.3.2 — playback transport candidate

- Uses bounded one-MiB GVS query ranges across playback and seeks. The resolver's
  validation requests the same initial range and reads a bounded prefix, replacing
  the misleading two-byte header probe.
- A playback-open 403 gets at most one fresh resolution requiring valid runtime
  proofs. A repeated rejection remains an error; another recording is never substituted.
- Requests larger provider artwork renditions, including old saved/history URLs,
  with original-image fallback when a larger rendition is unavailable.
- Increases Settings text size/contrast, wraps actions, and places its icon 8 dp
  from the right edge with a full touch target.
- Automatically confirms an authenticated YouTube Music page, with cancellation
  available during verification. Native device-account login needs separate Google
  OAuth registration and cannot import the YouTube app's private session. See
  [GOOGLE_SIGN_IN.md](GOOGLE_SIGN_IN.md).

Live YouTube playback remains unverified from this workspace. This is a transport
candidate, not a claim that the reported live 403 has been resolved.

## Version 0.3.1

- Restores original artwork colours across the app.
- Fixes the HTTP 302 account-confirmation failure: verify the session captured from
  the signed-in YouTube page directly, without requiring another homepage fetch.
- Handles bounded HTTPS first-party page redirects while keeping account credentials
  confined to their origin. Google sign-in/consent steps stay in the sign-in browser.
- Fixes a Media3 header mismatch: the player's fixed User-Agent used to overwrite the
  browser header that passed the stream probe. Playback now retains resolved headers.
- Preserves the actual media HTTP status in Copy details and drops rejected cached
  streams so retries can resolve a fresh URL.

Includes the previous app features:


- **Home / Explore / Saved**, with a shared search bar and inline results. Explore
  reads YouTube Music's discoveries; provider ordering and duplicates are preserved.
- **Normal YouTube sign-in**, replacing the TV device-code connection. Settings →
  Sign in to YouTube opens Google's actual pages. When YouTube Music opens, select
  the desired channel and tap **Use this account**. The app verifies the account
  through the WEB accounts endpoint before marking it connected.
- Uses the current web-session configuration, ordinary WEB_REMIX/WEB player requests,
  signature timestamps and isolated player-script decoding. No TV or guessed Android
  client fallback. A small audio range is checked before returning a stream to Media3.
- Includes Google's runtime-attestation flow using **BgUtils 4.0.3 (MIT)** in a separate
  WebView without the account bridge. Proofs use client-specific recording, visitor and
  selected-account bindings, including the full authenticated session ID. Declined verification does not produce a fabricated token.
- Preserves actual provider rejection reasons. Unsupported clients, CDN rejections,
  verification failures and unsupported transports have separate diagnostics. The
  player can **Copy details** without copying cookies, proofs or signed media URLs.
- Selects the largest available artwork instead of the smallest thumbnail, and
  retains real artist metadata.
- Removes the redundant volume slider from Settings; device volume controls playback.
- Keeps native Media3/background audio, album/playlist browsing, local files, saved
  items/history, compact transport, one Save action, animated timeline, dark mode,
  reduced motion, and verified APK updates from GitHub Releases.

Old TV credentials are discarded; sign in again after upgrading. The selected
YouTube web session is encrypted with AES-GCM using Android KeyStore. Only Music's
own sign-in view is read; no system-browser cookies or password form are collected.
Google's page handles authentication. The view's cookies/cache/storage are cleared
when it closes. Account cookies are sent only to first-party YouTube hosts, never
media CDNs or the isolated attestation transport. Sign out removes the local session.
No Music account server exists.

## Provider verification and limitations

**Live YouTube playback and Google sign-in are not verified from this workspace.**
Its outbound proxy blocks YouTube. Real Chromium tests run the exact bundles against
synthetic provider responses, including the actual upstream parser and signature
extractor. Those tests establish the implementation's behaviour, not compatibility
with today's live provider. See [VERIFICATION.md](VERIFICATION.md).

YouTube can change its web API, refuse embedded sign-in or require verification.
This build plays validated, non-DRM direct audio streams. **SABR-only streams are
not yet supported** and receive an explicit transport error. Premium-only content,
live streams, downloads/offline streaming and listening-history submission are
outside this build. No universal playback or ad-free guarantee is made. Artist
cards open inline search; albums and playlists open native track lists.

Implementation research: [YouTube integration](YOUTUBE_INTEGRATION.md) and
[Tryptify DSP integration review](DSP_INTEGRATION_RESEARCH.md).

## Build

Use JDK 17+, Android SDK 35/build tools 35.0.0. Android 8.0+ and a recent Android
System WebView are required. The committed bundles build in Android Studio without
Python, Node or an extraction server:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle checks the source and
bundle digests. After editing the JavaScript client, rebuild its bundles first:

```sh
npm ci --prefix youtube-engine
npm run build --prefix youtube-engine
npm test --prefix youtube-engine
BROWSER_EXECUTABLE=/usr/bin/chromium npm run test:browser --prefix youtube-engine
```

Native screenshots are generated in `app/build/reports/screenshots/`. Screenshot
recordings and browser responses are test fixtures, excluded from the product feed.

## Releases and OTA signing

**Settings → App updates** checks published GitHub releases, including previews. It
verifies the download size, SHA-256, package name, higher versionCode and installed
signing certificate before opening Android's installer. Android permission and
installation confirmation remain required. Updates never install silently.

Releases need `music-debug.apk` (or `music-release.apk`) and a `SHA256SUMS` asset
naming that exact APK. Tags use semantic versions and Android versionCode must
increase. Drafts and older builds are ignored. v0.1 has no updater; install a newer
APK manually once. v0.2 and later can use the in-app update flow.

This APK retains the certificate used for v0.1/v0.2 to preserve saved data on upgrade.
Future builds must use the same private signing key. Back it up privately; no key,
password, account cookie, token or local SDK configuration belongs in this public
repository. Checksums supplement Android's certificate validation.

## DSP

DSP is optional. `playback/AudioPipeline.kt` selects the Android sink wrapper when
the separate private native artifact is supplied. The `dsp` module contains its
public interfaces, JNI owner, speaker mapping and Media3 adapter. The private
Downmix Renderer source and coefficients remain outside this repository. Ordinary
source builds retain the normal audio path; stereo bypasses the renderer even in
DSP-enabled builds. See [the DSP module](dsp/README.md).

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
