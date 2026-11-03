# Provider playback checkpoint

This remains work in progress, not a released native provider integration. The
earlier local E-AC-3 prototype used version 0.3.14 / code 17 and was excluded
from normal OTA. Music **0.3.15 / code 18** includes the multiframe decoder fix
and uses the normal OTA feed. This does not add provider subscription access.

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
- The local decoder prototype adds clear AC-3/E-AC-3 bed PCM decoding into DMR,
  with explicit native speaker masks. It adds no Atmos object renderer or new
  provider credentials. Explicit import can retain files whose platform metadata
  reader lacks codec support.

## Incomplete

- Native Apple Music, TIDAL and Qobuz subscription sign-in/playback is **not**
  implemented. The account-gateway connection is not a MusicKit integration.
- The user has no backend/addon configured and no Apple/TIDAL developer app
  registration yet. Further research is examining existing account-based clients
  that do not require each listener to register an application.
- No free commercial catalogue is bundled. Imported files work independently of
  subscriptions; server/addon access depends on that source's requirements.
- Live commercial playback, device lossless output and phone E-AC-3 playback into
  DMR are unverified. Host JNI bed decoding is tested. Protected provider SDK
  playback does not establish a raw-PCM hook.

## Validation status

- The complete final run passes **126 app tests and 66 DSP tests**, with no failures,
  errors or skips, plus fatal lint and both native APK packaging guards.
- SourceSettingsUiTest passes against Claude's inline editor using the automatic
  animation clock. The navigation test now targets the clickable About tab,
  matching the current settings design. Product UI remains unchanged.
- Real host JNI checks decode six distinct E-AC-3 channels into the original DMR;
  stereo bypass is byte-identical. These are synthetic fixtures, not live provider
  recordings or physical-device playback.
- The integrated OTA APK does not promote these server
  connectors to verified Apple Music/TIDAL/Qobuz subscription integrations.

## UI handoff

Claude can refine the UI on top of this checkpoint. Preserve the ViewModel source
connection callbacks and non-secret summaries, or update both ends together. Keep
stereo unchanged, expose comparison only for decoded multichannel input, preserve
the separate progress clock, and keep YouTube catalogue/artist/artwork identity.
Provider/gateway status should distinguish a saved server from verified account
playback. Do not advertise unverified Atmos, lossless or direct provider login.

Protocol details and source limitations are in [PLAYBACK_SOURCES.md](PLAYBACK_SOURCES.md).
