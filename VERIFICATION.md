# v0.3.6 verification

Built from Claude's commit `76a9d09b32695a75bc8ccc17efeafef9e938e956`.
Only release version metadata and documentation were added for this build; the
reviewed sign-in/playback implementation is unchanged.

## Current checks

- Android build, packaged engine/source/proof digests: passed.
- Android/JVM: **60 tests**, no failures or skips, including 18 Compose checks.
  - A generated AAC/MP4 fixture is served over real loopback HTTP. Media3's actual
    MP4 extractor reads its seek map, seeks to 8 seconds and back to 2 seconds,
    and produces samples at the requested timestamps through query-range transport.
    This exercises the extractor and transport, not an audio device/decoder.
  - Range recovery verifies a later HTTP 403 refreshes the URL and reopens at the
    exact next unread byte after a nonzero seek, preserving the catalogue key.
    Repeated rejection is still reported rather than presented as success.
  - Existing range, truncation, negotiated-header, HTTP error, redirect, encrypted
    account, updater, library, Google identity and UI regression checks pass.
  - Checks cover constrained device handoff URLs, regional Google sign-in
    navigation, exact-video artist repair,
    preserving known artists/local metadata, Home without the local-file row,
    and Settings import action placement/callback.
- JavaScript mapping/configuration: **14 tests passed**. Empty artist arrays no
  longer hide YouTube authors/linked subtitle artists. Album/year/view text is
  never inferred to be an artist.
- Exact bundled engine in Chromium: **29 checks passed** against synthetic provider
  HTTP, including no duplicate media probe, AAC preference, signed content-length
  fallback, metadata events, shared concurrent resolution, parsing, account capture,
  real signature/n extraction, proof bindings and failure reporting.
- Exact proof bundle in separate Chromium: **6 checks passed** with fixture VM and
  integrity responses. These checks do not establish live attestation acceptance.
- APK: `labs.rtensor.music`, version `0.3.6`, code `9`. Signature matches previous
  releases, certificate SHA-256:
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
- Native UI screenshots inspected. Settings icon remains 8 dp from the right;
  Home no longer offers device-file playback. Import belongs in Settings and
  imported documents are listed in Saved. Original provider artwork colours remain.
- Engine assets in the APK match tracked source assets. Source archives contain
  no keystore, private DSP implementation or real account data. The test audio is
  a generated sine wave.

## Known review findings

These findings from the source review are not fixed by this build. The existing
passing tests do not establish that these cases work:

- A forced verified retry can stop after the preferred client fails, before trying
  another client, because the verification gate examines the preferred failure.
- A preferred path is recorded after URL resolution, before Media3 confirms actual
  audio playback. It is not a record of a successfully played stream.
- Metadata SharedFlow has no replay; events emitted without an active subscriber
  are dropped, including metadata fetched during background-only playback.

## Implementation limits and device checks

The live probe recorded during v0.3.5 verification was rejected by this workspace's proxy with
`CONNECT tunnel failed, response 403`. No network-policy bypass was used. The
reported live playback/seek issue is not claimed universally resolved by fixtures.

Google's account provider may decline `weblogin:` handoff for third-party apps.
Music first attempts the chosen device account, then falls back to normal Google
browser sign-in if no usable session URL is returned. It never fabricates a web
session from an identity token. Native consent, ticket acceptance, the registered
OAuth clients and actual personalised Home still require phone verification.

The configured 500 ms startup buffer is a threshold, not a measured live startup
latency. Verify initial play, repeated forward/backward seeks, cold-network starts,
queue changes, and screen-off/Bluetooth playback on a phone. A bounded 128 MiB
local byte cache supports rereads during a queue item's lifetime. Source URLs are
not promised to remain valid, and a repeated provider denial remains visible.

Also verify document access survives restart and Saved plays the imported file
while its original remains available, plus Android's update installer with the
same signing identity. SABR-only streams remain unsupported. DSP is off.
