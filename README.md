# Music for Android

Temporary name: **Music**. Native Android player from RTensorLabs, using the
approved Margin visual direction: warm paper, DM Sans, monochrome artwork and
raspberry controls.

## Version 0.2.0

- Replaces the Python / yt-dlp adapter with **YouTube.js 18.3.0 (InnerTube)**.
- Bundles the JavaScript client in the APK. Chromium runs the client; a Worker
  isolates player-signature expressions from the Android bridge and account tokens.
- Supports public YouTube Music search, video/playlist links, native Media3 audio,
  provider Home sections, album/playlist browsing and background queue playback.
- Adds Google device-code sign-in through the external browser. In Settings, tap
  **Sign in to YouTube**, then **Open Google sign-in** and enter the displayed code.
  This provider connection may be shown as **YouTube on TV** on Google's page.
- Reads the connected account's YouTube Music feed. Provider section/item order,
  original IDs and duplicates are preserved; Music does not re-rank the feed.
- Encrypts OAuth credentials using AES-GCM with a key in Android KeyStore. Tokens
  stay on the device, are not logged, and are cleared on sign-out. The bundled
  client sends provider requests directly to YouTube. No Music account server exists.
- Adds **Settings → App updates**: check GitHub Releases, download an APK, verify
  byte count/SHA-256/package/version/signing certificate, and open Android's installer.
- Keeps local audio, saved items/history, one Save action, compact transport,
  animated timeline, light/dark themes and reduced motion.

The old v0.1 APK has no updater. Install v0.2 manually once; later builds can be
installed from within Music. Android requires permission to install updates from
Music and asks for confirmation. Updates never install silently.

## Provider verification

The workspace's network proxy blocks YouTube. No live YouTube playback, Google
account sign-in or personalised feed has been verified from this environment.
The bundled engine is tested in real Chromium with synthetic HTTP responses,
including the upstream parser, audio resolution, device-code events, token refresh
storage, authenticated feed headers, diagnostics and isolated player evaluation.
These tests do **not** establish compatibility with today's live provider.

YouTube may reject a client, require verification, restrict a recording by region,
or change its API. The app shows a diagnostic code instead of pretending playback
succeeded. No ad-free guarantee is made. Premium-only content, downloads/offline
streaming, and live streams are outside this build. Artist cards open provider
search; albums and playlists open native track lists. Existing provider-account
history informs the feed; this build does not submit listening-history events.

Source/account information and update controls live in Settings. No account
password is entered into Music. Google approval happens on the provider's browser
page; the app does not embed a Google password form or collect browser cookies.

## Build

Use JDK 17+, Android SDK 35 / build tools 35.0.0. Android 8.0+ and a recent Android
System WebView are required. The committed JS bundle can be built by Android
Studio without Python, Node, or external extraction services.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle verifies that the
committed JS bundle matches its source inputs. To edit the YouTube client, use
Node 20+ and rebuild it first:

```sh
npm ci --prefix youtube-engine
npm run build --prefix youtube-engine
npm test --prefix youtube-engine
BROWSER_EXECUTABLE=/usr/bin/chromium npm run test:browser --prefix youtube-engine
```

The Chromium checks use fixtures and do not sign into a real account. Native UI
screenshots are generated in `app/build/reports/screenshots/`; their recording
metadata is a test fixture. See [VERIFICATION.md](VERIFICATION.md) for results and
remaining real-device checks.

## Releases and OTA signing

The updater reads published releases, including previews, from this repository.
A compatible release must have `music-debug.apk` (or `music-release.apk`) plus a
`SHA256SUMS` asset naming that exact APK. Tags use semantic versions such as
`v0.2.1`, and Android `versionCode` must increase. Drafts and older versions are ignored.

The v0.2 build uses the same signing certificate as the original v0.1 download,
allowing an upgrade that preserves saved data. Future APKs **must** use that same
private signing key; a new developer/debug key is not interchangeable. Keep a
private backup of the key outside the public repository. No keystore, passwords,
OAuth tokens or local SDK configuration belong in Git or release source archives.
GitHub checksums do not replace certificate verification. Android also verifies
and confirms package installation.

## DSP

DSP is off. `playback/AudioPipeline.kt` remains the after-decode integration point.
The private downmix code has not been copied into this project. Its Windows DLL
needs an Android/ARM port and JNI integration under the author's distribution
terms. Stereo music will not benefit from multichannel downmix alone; optional
EQ/enhancement is a separate feature.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
