# Android multichannel → DMR fix: findings

Follows `CLAUDE_DSP_FIX_HANDOFF.md`.

## Root cause found

`ffmpeg_jni.cc` `decodePacket` advanced the output pointer *and* added the
running byte count when writing each further AVFrame of a packet. MP4 E-AC-3
samples commonly carry several syncframes, so FFmpeg returns several frames
per packet. Frame 2 was written at twice its offset: the PCM handed to the DMR
had stale bytes in place of audio, the write could run past the buffer, and the
buffer-growth path crashed. The converter was also cached from the first frame
and never rebuilt if the decoded layout, rate or format changed.

## Changes

- JNI: each frame is written at `base + bytes so far`; growth returns the new
  base; the converter is rebuilt when the frame's layout/rate/format differs; a
  channel-count change inside one packet is an error.
- `FfmpegAudioDecoder`: the layout mask is captured with the count and rate on
  the decoder thread; a later change in count, rate or mask is a
  `FfmpegDecoderException` that names both layouts.
- `FfmpegSpeakerLayout`: maps the height positions TFL/TFR/TBL/TBR/TSL/TSR;
  rejections name the mask in hex and the unmapped bits.
- `DownmixException` + `DownmixStage` mark where the chain stopped;
  `PlaybackDiagnostics` classifies the cause chain in the service process
  (the controller only receives the top-level cause). The app shows that reason
  instead of "check your connection"; decoding/output errors without a staged
  cause get their own wording.
- `NativeDownmixEngine.unavailableReason` keeps the DSP load error; the service
  logs one environment line (ABIs, FFmpeg version, DSP state).
- The file picker also offers `video/mp4`, where many owned E-AC-3 files land.

## Follow-up validation and Music 0.3.15

- Pulled Claude's `584beff` and rebuilt host, ARM64 and x86-64 JNI against the
  pinned minimal shared FFmpeg n6.1.6 build. The original private DMR source and
  native binaries are unchanged.
- **126 app tests and 66 DSP tests pass**, with no failures, errors or skips.
  This includes real Java/JNI multiframe buffer growth and layout-change tests,
  original DMR JNI processing, diagnostics, imports and existing player UI tests.
- Corrected the diagnostics test's call to a package-private FFmpeg exception
  constructor by using the public DecoderException base type; actual FFmpeg
  exceptions are exercised by the decoder tests. Android-dependent error tests
  now use Robolectric. The deep-cause test expects no classification when the
  recognised stage lies outside the documented bounded traversal.
- Native decoder tests pass under ASan/UBSan. Added optional full-recording
  testing without retaining a complete decoded PCM recording in memory.
- The complete Dolby UltraViolet / Untie reference (clear 5.1 E-AC-3 JOC,
  48 kHz, 189.856 seconds) was stream-copied to raw EC3 and decoded using
  **1, 2, 3 and 6 syncframes per packet**. All four runs produced 5,933 decoded
  syncframes and 218,714,112 PCM bytes with SHA-256:
  `94ca0908f12b072d8ed312fdf2b46170e4e44878ba10d20e3fba8e2b078d3eef`.
  This exercises the actual host native decoder loop, not Android AudioTrack or
  a full recording through DMR. The reference music is not redistributed.
- Fatal Android lint and both `verifyOtaDspBundle` / `verifyEac3DecoderBundle`
  checks pass. Release validation checks the existing signing identity and 16 KiB
  APK/ELF alignment. Corresponding decoder source accompanies the release.
- **0.3.15 / code 18** uses normal OTA naming and supersedes both normal 0.3.13
  and manual 0.3.14 installations. Updated the OTA test to cover version 0.3.14
  and code 17 explicitly.

## Still not verified

- The phone failure itself has not been reproduced. A stereo AAC `.m4a` bypasses
  the DMR by design, so it would not exercise this path at all. To confirm, use
  `ffprobe -show_streams` output of the failing file, the exact symptom, the
  device model, and the `AudioPipeline` log lines.
- JOC content decodes to its channel bed only; this is not the full Atmos
  object scene.
- No physical phone was attached. Audible comparison, Android AudioTrack routing,
  UI frame rate and the user's actual recording still need device validation.
