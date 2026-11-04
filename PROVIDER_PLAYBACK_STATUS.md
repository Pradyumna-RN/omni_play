# Provider playback checkpoint — 0.3.26

## Current audio route

Music uses standard E-AC-3/JOC surround-bed decoding into the original DMR.
Spatial/binaural processing, synthetic 9.1.6 upmix and the scene-decoder setting
are removed from the app. Old saved selections are cleared. Stereo bypass and
Original/Processed comparison remain. Standalone decoder research is separate
and is not packaged into Music. The historical checkpoints below retain their
original test results and do not describe the current audio route.

## Apple account integration added in 0.3.24

Music now includes first-party web sign-in for Widevine AAC, and an isolated
native subscriber worker for compatible user-imported Apple runtimes. Native
renditions are inspected for actual ALAC/JOC format and routed to the in-app
decoder/DMR path, with a separate AAC fallback. FFmpeg includes 24-bit ALAC.
This is an unofficial, experimental integration; live account sign-in, runtime
loading and commercial playback on a phone remain unverified. No Apple binary,
account, developer secret or music download is bundled.

See [current setup and validation boundaries](APPLE_ACCOUNT_PLAYBACK.md). The
0.3.18 checkpoint below is historical; its Apple implementation status is superseded.

## Implemented in this branch

- Optional native TIDAL account adapter: browser PKCE, encrypted account storage,
  public-client refresh and matching full-track lossless requests. It needs the
  listener's subscription. It has no bundled account, client secret or music server.
  It uses an unofficial native public client identity; RTensorLabs has not registered
  or obtained provider approval for that identity.
- TIDAL clear Atmos requests use the account API and an unprotected DASH manifest.
  Protected or unavailable responses keep the lossless alternative. Live account
  sign-in, refresh, entitlement and Atmos availability have not been tested.
- Experimental OpenJOC 0.20.1 reconstructs supported, clear 48 kHz JOC recordings
  into explicitly mapped 7.1.4 PCM before the original DMR. It is off by default.
  No synthetic height upmix is added to inputs that already have height channels.
  This is not a Dolby-certified or equivalent object renderer.
- YouTube discovery, metadata and logical queue identity remain. Matching playback
  ranks JOC first, discrete lossless multichannel next, then lossless stereo by
  available rate/depth. Local files and connected gateways remain supported.
- DASH representations are pinned individually. Manifest channel/rate metadata is
  preserved, including 44.1/88.2/176.4 kHz multichannel conversion. Unknown values
  remain unknown. Actual format contradictions cause a different source to load;
  the app does not silently label AAC/stereo as a requested FLAC/surround source.
- Stereo bypasses DMR unchanged. Original, Processed and Spatial still emit stereo
  output; Spatial is headphone binaural, not physical 12-channel hardware output.
- Account authorization is not forwarded to media/CDN hosts. Logout invalidates
  resolved native candidates. HTTP calls cancel with their coroutine.

## Historical 0.3.18 Apple and Qobuz checkpoint

The user has access to a friend's Apple Music subscription, not a configured
provider backend. The friend must authenticate privately. Native Apple playback
inside Music is not implemented. An account-gateway contract is not MusicKit.

The inspected Apple TV client supplies AAC and does not meet the requested
lossless/multichannel route. The wrapper-v2 native runtime requires compatible
Apple Music 3.6.0-beta/build 1109 libraries. Download access failed here; a
user-supplied APK/APKM is needed for the next compatibility experiment.
`tools/apple/inspect_runtime.py` checks that package read-only without executing
its code. Matching hashes would still not establish phone playback or DMR access.

No direct Qobuz account adapter or free commercial catalogue is bundled. Imported
files and personal library sources work independently of provider subscriptions.

## Evidence and limits

The complete 189.856-second public Dolby music reference has passed through the
real OpenJOC Java/JNI decoder, actual private DMR JNI and app sink on the host.
All four height outputs have signal, PCM is finite, switching among all three
modes preserves duration, and synthetic upmix stays off. EOS draining, packet
grouping, flush and seek-preroll tests use the same native decoder. The recording
is not redistributed.

This is host validation, not Android AudioTrack, physical-device performance,
listener quality assessment or live commercial-provider playback. OpenJOC's
carrier-local reconstruction has known upstream fidelity limits. Its latency
report and filter tail are retained; no zero-latency or bit-perfect claim is made.

See [PLAYBACK_SOURCES.md](PLAYBACK_SOURCES.md) and
[decoder-openjoc/README.md](decoder-openjoc/README.md) for setup and limits.

Final integrated validation passes 156 app tests, 88 DSP tests and 7 decoder tests
(251 total, no failures/errors/skips), Android lint and all three exact-library APK
guards. Media3 parses the real M4A reference to EOF and reports E-AC-3 JOC before
renderer selection. Native packet/seek/flush tests pass with cooperative polling;
the initial busy-poll harness timeout under combined build load was corrected
without changing the decoder or extending its deadline.

## 0.3.19 review follow-up

Rendition rejection now has its own stable identity, independent of signed URL
paths, manifest signatures and representation ordering when IDs are available.
Exact-file cache identities are optional and source-bound; unknown-file byte
ranges stay isolated. A stale JNI sample position after flush → empty EOS is
fixed. Decoder latency remains uncompensated and is now documented explicitly.
Apple tooling, the TIDAL account path and existing provider features are retained.
See [the review record](PROVIDER_REVIEW_FINDINGS.md).
