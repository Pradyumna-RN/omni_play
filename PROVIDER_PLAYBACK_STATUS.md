# Provider playback checkpoint

This branch is work in progress, not a released provider integration. Version
0.3.14 / code 17 is reserved for the next build. No new release has been published.

## Implemented

- Stereo bypasses the DSP adapter unchanged at its original encoding/sample rate.
  Music does not enable the native library's optional stereo enhancement.
- Original/Processed controls are limited to reports with multichannel comparison
  available. Existing independent progress clocks and player animations remain.
- Local matching preserves edition tags, checks artist/duration conservatively
  and does not infer Atmos from filenames. Ranking is Atmos first, then highest
  available lossless sample rate/bit depth, then other multichannel recordings.
- Network source connectors support a user-hosted TIDAL HiFi API, the clear-stream
  HTTP addon protocol and a documented account-gateway contract. The gateway
  protocol has no bundled server implementation.
- Source selection runs before constructing a physical MediaSource. The logical
  YouTube catalogue item remains intact for metadata, recommendations and history.
  Representations have isolated cache keys, failed candidates are excluded, and
  briefly retained alternatives avoid repeating searches on each fallback.
- Server profiles are encrypted with Android KeyStore; API credentials are not
  forwarded to returned audio URLs. A compact settings row opens an inline editor.
- The original private DSP implementation and compiled native libraries are
  unchanged. No private processing source or coefficients were added here.

## Incomplete

- Native Apple Music, TIDAL and Qobuz subscription sign-in/playback is **not**
  implemented. The account-gateway connection is not a MusicKit integration.
- The user has no backend/addon configured and no Apple/TIDAL developer app
  registration yet. Further research is examining existing account-based clients
  that do not require each listener to register an application.
- No free commercial catalogue is bundled. Imported files work independently of
  subscriptions; server/addon access depends on that source's requirements.
- Live commercial playback, device lossless output and Atmos decoding into DMR
  are unverified. Protected provider SDK playback does not establish a raw-PCM hook.

## Validation status

- An earlier full checkpoint passed 117 app tests and 49 DSP tests, fatal lint and
  the non-debuggable APK/native packaging guard.
- After final caching/matching and UI test additions, the full run had 120 app
  tests with two failures. One was an Android JSON assertion in a plain JVM test;
  that assertion was moved to the Robolectric suite. The subsequent focused run
  passed the 17 matching/source tests.
- The new `SourceSettingsUiTest` still fails to find `HTTPS server URL` after
  activating Playback sources. It previously timed out with automatic animation
  advancement; manual-clock and inline-editor variants still need investigation.
  Do not assume this is only a harness issue: verify the interaction on a device.
- The current source compiles. The complete suite and final APK packaging must be
  run again after the settings interaction is fixed. No current APK is promoted.

## UI handoff

Claude can refine the UI on top of this checkpoint. Preserve the ViewModel source
connection callbacks and non-secret summaries, or update both ends together. Keep
stereo unchanged, expose comparison only for decoded multichannel input, preserve
the separate progress clock, and keep YouTube catalogue/artist/artwork identity.
Provider/gateway status should distinguish a saved server from verified account
playback. Do not advertise unverified Atmos, lossless or direct provider login.

Protocol details and source limitations are in [PLAYBACK_SOURCES.md](PLAYBACK_SOURCES.md).
