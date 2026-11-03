# Local multichannel decoder prototype

This build adds a software AC-3/E-AC-3 decoder inside Music, before the existing
original DMR. The approved UI and private renderer implementation are unchanged.
The software decoder is used only for clear AC-3/E-AC-3 inputs; other codecs keep
their existing path. It does not add provider accounts, a public music server,
protected Apple Music playback or a decryption wrapper.

## Try it on Android

1. Install `music-eac3-prototype.apk` from the separate prototype release. It uses
   Music's existing package/signature, and includes both the decoder and DMR.
   It is a manual test build, excluded from the normal OTA feed.
2. Download `owned-51-channel-check.m4a` from the same release. In Music, open
   **Settings → Import audio**, select it, then play it from **Saved**.
3. Open Now Playing. Once audio starts, the report should identify six input
   channels and DMR's stereo render. Compare **Original** and **Processed**.
   Original is DMR's FL/FR-only comparison with its common delay/gain, not a
   second full multichannel output. Processed incorporates the other channels.
4. Seek forward/back, pause/resume and switch modes while playing. Then try a
   regular stereo track: comparison controls should disappear and stereo should
   use its ordinary playback path without DMR processing.

The generated sample has sequential FL/FR/FC/SL/SR tones lasting four seconds
each, with a quiet LFE tone throughout, at 48 kHz. It contains no provider music
or Atmos objects. Use a comfortable listening level. Your own clear multichannel
E-AC-3 recordings in M4A/MKV containers can also be imported. Metadata decoding
is optional at import, so files unsupported by Android's metadata reader can
still reach Media3's software decoder.

Raw EC3 files may have no seek table/duration. Use a suitable container to test
seeking. Automatic folder matching still depends on platform metadata support;
the explicit import path is the prototype's entry point.

## What this establishes

The real host-built FFmpeg JNI decoder decodes the synthetic E-AC-3 fixture to
six distinct PCM channels at 48 kHz. Its reported native speaker mask is mapped
explicitly to the original DMR. The tests then run that PCM through the real
original DMR JNI library. Stereo PCM remains byte-identical and creates no DMR
engine. Unknown layouts and protected formats are rejected by this adapter.

E-AC-3 JOC can provide a multichannel **bed** through this decoder. It does not
reconstruct the full Atmos object scene and must not be advertised as a full
Atmos renderer. The sample verifies ordinary E-AC-3, not JOC. Android's mixer,
device output format and OEM processing remain outside the bit-identical stereo
adapter check.

No physical Android device was attached during development. Host JNI tests and
cross-compilation do not establish phone playback, audible quality, seek latency,
UI frame rate or hardware bit-perfect output. Report the input file format,
phone/Android version and displayed error if the device test fails.

## Sources and licences

The public decoder adapter derives from Apache-2.0 Media3 1.6.1. FFmpeg n6.1.6 is
an unmodified minimal shared LGPL-2.1-or-later build with AC-3/E-AC-3 enabled and
GPL/nonfree components disabled. The release includes the complete corresponding
decoder source archive and the public Music source. No Tryptify, Cavern or NextLib
implementation is copied. No proprietary DMR source or coefficients are included.

See [decoder build/replacement instructions](decoder-ffmpeg/README.md),
[corrected provider research](PROVIDER_ACCESS_RESEARCH.md), and
[verification results](VERIFICATION.md).
