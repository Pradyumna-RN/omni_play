# Provider access without registering our own application

Research checkpoint: 3 November 2026. Repository code/docs were inspected; no live
subscription account, commercial stream or phone output was tested.

The earlier claim that every integration requires our own developer registration
was too broad. Official SDK integration requires the appropriate app credentials.
Unofficial clients can use existing web/native client configuration with a
listener's account. That avoids a separate registration for the listener, but does
not establish provider approval, stable access, free catalogue access or DMR support.

| Project | Verified approach | Relevance and limits |
| --- | --- | --- |
| [python-tidal](https://github.com/EbbLabs/python-tidal) | Browser PKCE, refreshable account sessions, FLAC/hi-res manifest handling; supplies existing client configuration | Best direct TIDAL account-client reference. No listener-owned developer app is required by its example. Client identity still affects permitted stream quality. |
| [qobine](https://github.com/SofusA/qobine) | Standalone Qobuz player, current browser OAuth flow, account token, file/segmented streaming | Stronger current Qobuz reference than a downloader alone. Uses web-player app configuration and signing material; recent code also handles encrypted segmented streams. Requires a paid subscription. |
| [gamdl](https://github.com/glomatico/gamdl) | Apple account cookies or wrapper-backed login; ALAC/other encoded downloads | Requires an active Apple Music subscription. Its README recommends wrapper-v2 for ALAC; it is not an Android playback SDK. |
| [apple-music-downloader](https://github.com/zhaarey/apple-music-downloader) | ALAC/AAC/EC3, including Atmos; wrapper-lite backend | README marks the listed formats as subscription-required. An Android/Termux downloader binary does not remove its separate wrapper dependency. |
| [Lumitide](https://github.com/BreakLime/lumitide) | Direct TIDAL account playback with platform-dependent login | Its README reports Windows FLAC versus Linux/macOS AAC. Useful evidence that a successful login does not prove lossless playback. |
| [Cider 2](https://github.com/ciderapp/Cider-2) | MusicKit-based Apple Music client | Public repo is an issue tracker, not its application source. README explicitly lists MusicKit.js lossless as missing; it is not evidence for our Atmos-to-DMR route. |

## TIDAL: investigate PKCE before a device-code implementation

The [PKCE example](https://github.com/EbbLabs/python-tidal/blob/main/examples/pkce_example.py)
loads a per-user session, requests hi-res quality, inspects codec/bit depth/sample
rate and handles MPEG-DASH manifests. The library supplies existing client
configuration rather than asking the listener to register an application.

[Issue 404](https://github.com/EbbLabs/python-tidal/issues/404) documents lossless
requests silently returning AAC with a limited client. Its closing maintainer
comment says PKCE permits lossless/hi-res while the device OAuth route returns
HIGH. This is repository evidence, not a successful test with our own account.
An adapter must check the returned format rather than trust the request or catalogue.

Lumitide demonstrates another constraint: some routes reuse native-client identity
and require encrypted-stream handling. Its Windows solution cannot simply be
assumed to work on Android. No shared client secrets or content keys were added to
Music as part of this research.

## Qobuz: use a current account flow

Qobine's [client](https://github.com/SofusA/qobine/blob/main/qobuz-client/src/client.rs)
implements browser OAuth and an authorization-code exchange, and obtains web-player
configuration. This avoids collecting the account password inside our own form.
Its source also has file-based playback and encrypted segmented FLAC handling.

The older [qobuz-dl](https://github.com/vitiko98/qobuz-dl) is a useful quality/API
reference, but [issue 329](https://github.com/vitiko98/qobuz-dl/issues/329) reports
normal account credentials failing after login changes. Do not adopt its older
password-login path as a verified current integration.

## Apple: account-based tools exist, but the runtime is a separate problem

[wrapper-v2](https://github.com/glomatico/wrapper-v2) describes a Linux supervisor
and Android/NDK worker using Apple Music native libraries supplied by the person
building it. It exposes account/playback and FairPlay processing services; its
README calls it research-grade. This is neither a normal MusicKit SDK nor a
ready-to-redistribute native Android library for this app.

These tools demonstrate an unofficial route without our own MusicKit registration.
They do not prove a supported phone-only login/playback implementation or an
authorized, redistributable raw multichannel PCM interface. Native runtime,
account authorization, codec decoding and DMR routing need separate validation.

## Implementation decision

The next useful experiment is **direct per-user TIDAL PKCE**, followed by a current
Qobuz browser-account adapter. Neither needs a user-hosted gateway if implemented
inside Music. Verify full duration, actual codec/rate/depth, seeking, refresh and
decoder channel layout using an authorized test account before declaring success.
Keep Apple Atmos as a separate runtime/rights/decoding investigation.

The committed Music source connectors are still groundwork: these direct account
logins are **not implemented**. The current HiFi/addon adapter supports clear
streams and rejects encrypted/DRM manifests; it is not a replacement for a complete
authorized protected-playback implementation. No subscription bypass or free
commercial catalogue was verified. Keep stereo unchanged and send only genuine
decoded multichannel PCM into DMR.

Use the public protocols as references. Do not copy GPL application implementations
into the proprietary renderer integration. No private renderer code changed.

## Follow-up: different repositories and the complete audio requirements

“Subscription” above means the listener's provider subscription. These unofficial
routes do not ask the listener to purchase an Apple Developer membership. A music
subscription alone does not establish that an arbitrary client gets every codec.

- [OpenTune](https://github.com/yyan115/tidal-app): native Android TIDAL client,
  browser account authorization, encrypted sessions, no server of its own, and
  advertised lossless up to 24-bit/192 kHz. Its README explicitly excludes protected
  streams. Its `StreamManifest` code rejects encrypted BTS and DASH
  `ContentProtection`. Therefore it is a useful phone-only hi-res reference,
  **not proof of protected Atmos or multichannel delivery to DMR**. Its client
  identity includes a secret; account login is not simply a public client-ID setting.
- [TIDAL Atmos for TidaLuna](https://github.com/Cxsmo-ai/Tidal-Atmos-For-Tidal-Luna-):
  Windows project with account device authorization, Atmos-first matching and a
  hidden custom mpv renderer. Upstream reports two E-AC-3 JOC / six-channel / 48 kHz
  cases, plus FLAC at 192 kHz. Those are upstream results, not our tests. The public
  tree does not contain the complete playback API implementation; API executables
  and native runtime ship in releases. This is a promising architectural reference,
  **not an audited Android-ready component**.
- [Ripster Mobile](https://github.com/Raccoon-Trashpanda/Raccoon-Ripster-Mobile):
  own-account Android Qobuz/TIDAL sources with hi-res downloads. README distinguishes
  downloadable TIDAL DASH from directly streamable files; its playback can use another
  connected service when a source supplies segments. Apple ALAC requires pairing
  with the desktop wrapper. Do not equate its download feature with all codecs
  streaming directly on the phone.
- [VarMusic](https://github.com/Varixxl/omarchy-music): Android implementation and
  per-account TIDAL FLAC matching. Its Apple Music selections play the matched
  TIDAL recording, **not Apple's protected audio**. Its build obtains TIDAL client
  configuration from `tidalapi`; no new account/Atmos guarantee follows from that.
- [Spotufi](https://github.com/fr0stb1rd/Spotufi): public TIDAL/Monochrome backends
  supply the claimed lossless audio. It does not demonstrate direct lossless
  Spotify playback or a provider-approved free catalogue.
- [AppleMusicDecrypt v3](https://github.com/WorldObservationLog/AppleMusicDecrypt/tree/v3):
  the repository's current default branch is `v3`; the old `master` branch's paused
  development notice is not the current implementation. V3 supports ALAC and EC3,
  with local processing via Temari. Its [Android guide](https://github.com/WorldObservationLog/AppleMusicDecrypt/blob/v3/android-deploy.md)
  still requires a local or trusted remote wrapper-lite instance and says login
  occurs on that wrapper. Termux/proot instructions do not establish a standalone,
  unrooted Music APK containing the complete Apple account/runtime path.

The required signal path remains: provider-authorized full recording → actual
encoded format → decoder → verified multichannel PCM → the original DMR. Stereo
lossless goes unchanged to output. Atmos E-AC-3 JOC is commonly a 48 kHz spatial
source and is not synonymous with 24-bit/192 kHz lossless stereo. Decoding its
channel bed is also distinct from rendering every Atmos object.

Conclusion: account-based hi-res stereo has concrete Android references without
the listener registering a developer application. Multichannel/Atmos has concrete
desktop and wrapper-backed references. **An entirely phone-only implementation
meeting all requirements has not been verified.** No direct provider login or
Atmos support was implemented merely on the strength of these README claims.
Next implementation needs an auditable account-access path, an authorized test
account and actual Android decoder/output validation; developer membership is not
the sole issue.

## Tryptify and its actual provider backend

[Tryptify](https://github.com/tryptz/Tryptify) is a particularly relevant Android
reference. Its provider implementation must be considered together with the
separate [TrypT-hifi](https://github.com/tryptz/trypt-hifi) backend.

- `app/src/main/java/tf/monochrome/android/data/api/ApiServer.kt` defines user-added servers and detects TIDAL through
  `/search/`, Qobuz through `/api/get-music`, and Apple through
  `/api/apple/get-music`, plus Deezer through `/api/deezer/get-music`. This is server-backed provider access, not native
  provider subscription login in the Android client.
- `HiFiApiClient.kt` resolves TIDAL stereo through
  `/api/tidal/download-music?track_id=...&quality=...`, with `/track/` as a fallback. Its Atmos path
  uses `/api/tidal/download-music?track_id=...&atmos=true`, checks the returned
  stream extension, and consumes a progressively served, range-capable file.
- TrypT-hifi can act as the TIDAL HiFi server using an account refresh token and
  client configuration, or use another HiFi instance. Qobuz needs an account
  token plus an app ID and secret obtained by extracting Qobuz's app credentials
  (the README links Qobuz-AppID-Secret-Tool). Calling that merely "configuration"
  understated the credential provenance. TIDAL setup refers to hifi-api's auth
  script for its client ID/secret; no provider permission to reuse those identities
  was established in this review. These are account-backed
  unofficial interfaces; the listener does not purchase a developer membership
  as part of those setup instructions.
- The backend README says TIDAL Atmos uses `/trackManifests/` and assembles DASH
  into an E-AC-3 JOC M4A without re-encoding the audio. Protected Atmos streams
  are not supported by that path and can fall back to stereo. Therefore this
  is not a guarantee that every Atmos edition/account will play in Atmos.
- Apple catalogue access in this backend requires a ready developer token or
  an owner-created MusicKit token. ALAC/Atmos additionally needs a wrapper;
  the Apple on-device integration document is explicitly a design document.
  Do not confuse it with a completed Apple native sign-in path.
- Tryptify's `app/src/main/cpp/atmos/README.md` is internally inconsistent: its
  foundation section says "no runtime consumers yet", while a later section is
  headed "Render pipeline — as shipped" and describes `AtmosAudioProcessor`.
  The separate `audio/atmos/AtmosTapMediaSourceFactory.kt` exists in source;
  that class name was from the implementation, not a quote from the README.
  `player/PlaybackService.kt` references both it and `AtmosAudioProcessor`, and
  configures NextLib. Thus source wiring exists; the README alone does not prove
  a working shipped renderer, nor have we independently validated it on a device.

Tryptify is GPL-3.0-or-later and its Cavern subtree has additional non-commercial
terms; TrypT-hifi is MIT. No implementation was copied into Music. The local
decoder prototype uses the Apache-2.0 Media3 adapter and a separately linked,
minimal LGPL-2.1-or-later FFmpeg build instead of NextLib's GPL implementation.

For Music, the most relevant architecture is the real server protocol plus an
Android decoder that exposes multichannel PCM to the original DMR. Tryptify's
object renderer is a separate implementation and need not replace DMR. A plain
FFmpeg E-AC-3 bed decode provides multichannel bed PCM, not the complete Atmos
object scene. Stream-copy/remux can preserve JOC; decoding and re-encoding to
ordinary stereo FLAC cannot preserve that scene.

This review establishes a concrete Android/backend reference, not a completed
Music integration. Source-account authorization, backend deployment and actual
codec/channel validation remain necessary. The app repository does not ship a
free TIDAL/Qobuz account or a public music backend. No Tryptify/Cavern DSP code
or shared provider credentials were copied into Music.

## November 3 implementation follow-up

The preceding checkpoints are historical. `TidalAccount.kt` now independently
implements native browser PKCE/account requests without a client secret. It uses
a public native client ID observed in account-client configuration; that is not
a developer registration or provider approval for RTensorLabs. Public-client
refresh and subscriber playback remain live-test requirements. Invalid synthetic
refresh probing is not proof that a valid token will refresh successfully.

The adapter requests lossless full playback and eligible clear EAC3_JOC manifests
from the official account APIs. API bearer tokens do not go to the CDN. Protected
manifests are rejected, and the implementation neither extracts content keys nor
embeds a public download backend. Fixture tests cannot establish real account
entitlement or catalogue quality availability.

[OpenJOC](https://github.com/chyinan/OpenJOC), Apache-2.0, supplies independent
experimental reconstruction; the earlier blanket claim that open decoding is
unavailable was wrong. Pinned 0.20.1 speaker rendering has been connected to DMR
through 7.1.4 PCM. Upstream explicitly documents unresolved codec-domain fidelity
and distinguishes carrier-local signals from authored stems. It is not evidence
for Dolby-equivalent playback. See the decoder module for reproducible build and
licence records.

[JObersi10/apple-music-tv](https://github.com/JObersi10/apple-music-tv) documents an
AAC-only standalone path and is not the requested lossless multichannel bridge.
[glomatico/wrapper-v2](https://github.com/glomatico/wrapper-v2) (Unlicense, inspected
commit `100e0a864e883e03a3ac450a780dd9563fff5271`) requires compatible Apple Android
3.6.0-beta/build 1109 libraries. Its Linux supervisor/Android native worker is not
a proven phone SDK. The app contains only a read-only compatibility checker and
public library hashes; no Apple libraries, device credentials or GPL CDM code
were copied. A user-supplied APK/APKM and private account testing are still needed
before claiming Apple native playback.


## Official MusicKit sign-in reassessment — 2026-11-04

Apple Music account authorization is implementable on Android. It is separate
from generic Sign in with Apple and does not, by itself, establish lossless or
multichannel playback through Music's DMR pipeline.

Official sources checked:

- [MusicKit overview](https://developer.apple.com/musickit/) explicitly offers
  Android authentication/playback libraries. Its SDK download link currently
  redirects to Apple Developer account sign-in in this environment.
- [Generating developer tokens](https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens)
  requires a trusted developer/member of the Apple Developer Program. Other
  platforms use a developer token signed with the team's MusicKit key. A listener's
  Apple Music subscription does not supply this app registration.
- [User authentication](https://developer.apple.com/documentation/applemusicapi/user-authentication-for-musickit)
  requires a Music User Token for subscriber-specific requests and directs Android
  apps to the MusicKit SDK authentication flow. The app must manage that token.
- [Apple's lossless guide](https://support.apple.com/en-us/118295) and
  [Atmos guide](https://support.apple.com/en-us/109354) document these features in
  Apple's own Android app, with device/output requirements. Those pages do not
  establish equivalent quality or raw PCM access in the third-party Android SDK.

As a recent implementation reference, [OrchardDrive's README](https://github.com/dverba/OrchardDrive)
reports native MusicKit playback as AAC, up to 256 kbps, with no lossless support.
Its [October 2026 compatibility log](https://github.com/dverba/OrchardDrive/blob/main/docs/MUSICKIT_AAOS_COMPATIBILITY.md)
identifies playback SDK 1.1.1/auth SDK 1.1.2 and an SDK-owned AudioTrack. This is
third-party evidence about that implementation, not a guarantee about a newer SDK
behind Apple's authenticated download page. Its architectural findings were read;
no application implementation or SDK binaries were copied.

For this project's quality target, the release gates remain separate:

1. Obtain our own MusicKit registration, official SDK and developer-token setup;
   authorize a subscriber through Apple's UI. Private keys remain outside the APK
   and public repository.
2. Verify the actual delivered codec, rate/depth, channel layout and device output.
   Catalog lossless/Atmos availability attributes are not proof of the played format.
3. Verify a supported authorized decoded-multichannel path into DMR. No public
   MusicKit Android PCM callback or working lossless/Atmos-to-DMR integration was
   verified in this review. SDK-managed playback is not a Media3/DMR buffer source.

The previously reported registration status is unregistered. No developer token,
official Android SDK download or live Apple subscriber session was available for
verification. No Apple sign-in button, quality badge or playback claim was added;
existing Apple server connectors and runtime-inspection tooling remain unchanged.
