# Follow-up review of f98eae4

The user's constraint is to preserve existing providers and features, including
Apple runtime tooling and TIDAL sign-in. Ask the user before removing or disabling
a feature or provider. This follow-up makes correctness fixes without doing so.

## Confirmed and fixed

- **Rejected rendition reappears after URL rotation.** A fixture advances the
  lookup clock beyond 120 seconds and refreshes the failed recording with a new
  signed path. Before the fix, it selects the failed recording again instead of
  the valid alternative. Rejection now uses an independent rendition key scoped
  by source, recording, format and representation/file identity.
- **Flush followed by empty EOS keeps an old sample position.** With the real
  JNI decoder and the authorized reference recording, a new segment anchored at
  37 seconds ended at 46.536 seconds. Reset now clears `firstPts` as well as the
  sample counter, latency and previous native error. The regression exercises
  public decoder calls; it does not inspect a private field.
- **TIDAL indentation.** The `assetPresentation` check is aligned with its block.
- **Native cleanup.** Initialization now owns the native handle with RAII while
  allocating buffers. Drain accepts the API's pending-output status, and a retry
  that cannot release any output fails explicitly instead of spinning.

## Cache scope and limitations

Removing every URL from a byte-cache key would allow unrelated files sharing a
track ID and codec label to share cached ranges. Rendition rejection and exact
encoded-file identity are therefore separate. Gateways/addons can supply an
immutable `fileId`; it must change when any encoded bytes change. Without it,
transport isolation is retained. The native TIDAL response does not currently
establish such an immutable-file ID, so rotating paths can still miss its byte
cache. Ordinary seeks keep the existing URL pinned. Adaptive DASH already bypasses
the progressive byte cache. These fixes do not promise cache hits across plays.

DASH rendition identity uses its actual Representation ID instead of signed XML
or an ordering-dependent index when possible. Tests change signed paths and reorder
same-format representations. Source namespaces come from the adapter rather than
the response, preventing separate servers from sharing an identity accidentally.

## OUTPUT_PENDING was checked and is not a duplication bug

At the pinned upstream commit `e3e330a2000f8d0bced3460756473c8774eb16f0`,
`openjoc_stream_decoder_send_chunk` delegates to `FfmpegDecoder::send_packet`.
Its pending-output and staging-capacity checks occur before bytes are appended.
The unconsumed chunk must be retried after receiving output.

The public `decoder-openjoc/tools/backpressure_probe.cpp` deliberately sends before
draining. With the real pinned library it forces **49 OUTPUT_PENDING refusals**
and produces exactly the same PCM as normal draining: **460,832 samples/channel**
for 300 source syncframes, including the 32-sample drain tail. No input is duplicated
or dropped. The existing multi-syncframe Java/JNI tests remain relevant too.

Upstream source:
https://github.com/chyinan/OpenJOC/blob/e3e330a2000f8d0bced3460756473c8774eb16f0/crates/openjoc-ffmpeg/src/lib.rs

## Latency is explicitly uncompensated

The pinned speaker renderer reports 609 samples (about 12.69 ms at 48 kHz), separately
from PCM presentation times. Music currently neither shifts those times, trims that
amount from audio, nor requests additional latency-specific seek preroll. The
existing seek trimming only uses the requested presentation time. The README and
getter documentation now state this directly. The 32-sample tail is a different
measurement; blindly discarding 609 samples is not a validated compensation scheme.

Upstream contract:
https://github.com/chyinan/OpenJOC/blob/e3e330a2000f8d0bced3460756473c8774eb16f0/docs/integration/FFMPEG.md#drain-flush-seek-latency-and-preroll

## Source/licence verification

The pinned upstream workspace declares version 0.20.1 and Apache-2.0. Its LICENSE
matches the shipped `OPENJOC_LICENSE` byte-for-byte. The speaker-only build remains
pinned, excludes embedded HRTF data, and has 54 linked dependency records. All
external records have retained notices, checked against the CI dependency-licence
artifact. This establishes declared source licences and retained notice provenance;
it is not Dolby certification or a claim of equivalent rendering.

The earlier C-API build/test run and exact pins are recorded in the 0.3.18 release's
validation report. No private renderer source, commercial audio, or Apple binaries
are copied into this follow-up. Live provider playback and phone performance remain
separate unverified items; the review does not upgrade those claims.

## Final validation

The final source passes **159 app + 88 DSP + 8 decoder tests** (255 total, no
failures/errors/skips), Android lint and all three exact-native APK guards. This
includes the final addon guard: only its stream response can supply an immutable
encoded-file ID; a search-result ID is not enough. The full reference recording
still traverses the real JNI decoder and private DMR in all three modes.

Version 0.3.19/code 22 keeps the existing package/signing identity and normal OTA
feed. Source and native hashes are recorded in the release validation report.
Physical-device performance and live provider-account playback remain unverified.
