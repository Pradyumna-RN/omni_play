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

## Verified here

- Native decoder suite (`decoder-ffmpeg/tools/native_test.py`) on host FFmpeg
  6.1 with ASan/UBSan: passes; the old JNI fails the multi-frame and growth cases.

## Not verified here

- Kotlin/Java compilation and the Robolectric tests: the Android SDK and Google
  Maven are not reachable from this environment. Run
  `./gradlew :dsp:testDebugUnitTest :app:testDebugUnitTest` (with the host FFmpeg
  and DSP library properties for the native-backed tests).
- The phone failure itself has not been reproduced. A stereo AAC `.m4a` bypasses
  the DMR by design, so it would not exercise this path at all. To confirm, use
  `ffprobe -show_streams` output of the failing file, the exact symptom, the
  device model, and the `AudioPipeline` log lines.
- JOC content decodes to its channel bed only; this is not the full Atmos
  object scene.
- A distributed build needs versionCode > 17.
