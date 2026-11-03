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
