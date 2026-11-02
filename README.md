# Music for Android

Temporary name: **Music**. Native Android player from RTensorLabs with the approved
Margin visual direction: warm paper, DM Sans, original provider artwork and raspberry controls.

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

DSP is off. `playback/AudioPipeline.kt` is the after-decode integration point. The
private Downmix Renderer implementation has not been copied into this repository.
Its Windows DLL needs an Android/ARM port and JNI integration under the author's
terms. Stereo enhancement is separate from multichannel downmix.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
