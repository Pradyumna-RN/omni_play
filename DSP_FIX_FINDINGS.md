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

## Follow-up: Original/Processed toggle with real multichannel .m4a files

### How it was checked
I built Media3 1.6.1 and the `dsp` module on a desktop JVM against `android-all` 15, with a fake fold-down engine standing in for the private DSP. No private code or coefficients were used. I made eight test `.m4a` files with ffmpeg, giving each channel its own tone so that every channel could be identified at the output:

- AAC stereo 44.1 kHz
- AAC 5.1 at 44.1 kHz and 48 kHz
- AAC 7.1 48 kHz
- ALAC 5.1 at 44.1 kHz, 48 kHz and 96 kHz/24-bit
- E-AC-3 5.1(side) 48 kHz, in an `M4A`-branded MP4

ffmpeg decode stood in for MediaCodec. Each file was played through `DownmixAudioSink` the way `MediaCodecAudioRenderer` drives it:

1. The renderer asks whether PCM16 is supported at the stream's channel count and rate.
2. It configures float output.
3. It sends 1024-frame buffers.
4. Playback switches to Original at 4 s and back to Processed at 7 s.
5. The stream plays to the end.

The media files are not committed.

### What failed before this change
- **44.1/88.2/176.4 kHz multichannel files would not play.** Most AAC and ALAC music is 44.1 kHz. The sink rejected these rates, so the renderer reported `FORMAT_UNSUPPORTED_SUBTYPE`. The rate converter only handled stereo.
- **A missing or unknown decoder speaker layout stopped playback.** This happens when a decoder doesn't report `KEY_CHANNEL_MASK`, or reports a mask that `FfmpegSpeakerLayout` doesn't map. The result was a hard `LAYOUT` error instead of audio.
- **Stereo files never show the toggle.** This is by design, because stereo bypasses the DSP.

### What changed
- `PcmRateAdapter` (previously `StereoRateAdapter`) converts any channel count: 44.1→48, 88.2→96 and 176.4→192 kHz. Its output for each channel matches a one-channel conversion exactly. On this host it costs about 47 ms of CPU per second of 5.1 audio.
- Unknown layouts and PCM the core can't take (8-bit, 32 kHz, and so on) now play the original through Android. The report has `comparisonAvailable = false` and an `unavailableReason`. Other configuration failures still throw.
- `DownmixReport.sourceChannels` was added. The player now shows the toggle, disabled and with its reason, for every multichannel source. It also shows the toggle when the DSP library is missing or failed to load. Previously the toggle just disappeared.

### Result
All 73 harness checks pass:
- Every channel is heard while Processed is selected.
- Only FL/FR are heard while Original is selected.
- The audio fades through silence at each switch.
- Left stays left and right stays right.
- Duration is correct.
- The no-mask cases pass every byte through unchanged.

33 of 33 `dsp` JVM unit tests pass. The Robolectric runner was swapped for plain JUnit, and `flacFallback…` was excluded because the FFmpeg renderer classes aren't in the host build.

### Not verified here
- An Android build. AGP can't be downloaded in this environment.
- Whether a given phone's AAC decoder reports a channel mask.
- Whether the phone has an ALAC decoder.
- The real private DSP.

On a phone, `adb logcat -s AudioPipeline` now prints the source channels and any reason a track was not processed.
