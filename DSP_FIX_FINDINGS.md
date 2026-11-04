# Android multichannel → DMR fix: findings

Follows `CLAUDE_DSP_FIX_HANDOFF.md`.

## Current validation: Music 0.3.16 / original DMR

Reviewed Claude's `2370a14` and merge `e1351cc`, preserving the 0.3.15 decoder
fix. The complete Android Gradle run passes **128 app tests and 72 DSP tests**,
with no failures, errors or skips, plus fatal lint and both native packaging
guards. The app module and player UI are compiled, including the previously
excluded FLAC-layout test in the DSP suite.

The new native-backed sink test uses the **actual original DMR JNI**, not the
stand-in engine, at all three conversions (44.1→48, 88.2→96 and 176.4→192 kHz),
with both six and eight input channels. A continuous centre-only signal is
rendered in Processed, is suppressed by Original's FL/FR-only comparison, and
returns when Processed is selected again. Assertions check the last 10 ms RMS
level of each segment, exact output duration, finite/non-silent PCM, mode reports,
one engine/sink configuration and the EOF clock excluding conversion/core tails.
These tests feed decoded synthetic PCM; they do not establish phone AAC/ALAC
decoding or an actual Android AudioTrack output.

Added Compose checks for disabled comparison plus its unknown-layout reason and
the missing-library explanation. The existing queue interaction test now scrolls
the lazy list to its target before clicking; a newly visible status section can
place that row outside the initial composed viewport. Product navigation was
not changed to satisfy the test.

Corrected bypass reporting: it now describes the PCM channel count configured
at the Android sink (or the explicit output-channel map), instead of always
claiming a two-channel render. This does not prove the hardware output layout;
Android can mix the passed-through PCM for its actual device. Unknown layouts
remain unprocessed, with no fabricated DMR or Atmos claim.

The native DMR and FFmpeg binaries are unchanged from 0.3.15. No private algorithm
or coefficients were modified or published. **0.3.16 / code 19** uses normal OTA
naming; the updater test covers upgrading installed 0.3.15/code18 as well.
Phone codec support, audible quality, UI frame rate and the user's actual Starboy
file remain unverified because no phone/file was supplied.

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

## Follow-up: 9.1.6 upmix and Spatial (binaural) mode on Android

### What changed
- **9.1.6 upmix for multichannel.** The private DSP gains ABI 3 (a patch for the
  private repo, not in this repository). It adds `setUpmix916` and `processField`.
  When the packaged DSP reports ABI 3 and the stream has more than two channels,
  the app turns the upmix on. The renderer then fills a 16-speaker 9.1.6 field
  (sides, rear centres and six heights) from the decoded bed. ABI 2 builds still
  load; they just have no upmix and no field.
- **Spatial mode (headphones).** A third chip next to Original/Processed. It renders
  the speaker layout to two ears:
  - The speakers are encoded into third-order ambisonics (ACN/SN3D).
  - The result is convolved with the Omnitone/SADIE HRIRs (Apache-2.0), using
    zero-latency FFT overlap-add.
  - LFE is low-passed at 120 Hz and sent to both ears.
  - A limiter holds the output under −0.2 dBFS.
  - With ABI 3, the input is the 9.1.6 upmixed field. Otherwise it is the decoded
    speakers.
  - HRIRs are interpolated for 96 and 192 kHz.
- Fixed an initialisation-order bug: the loaded ABI number was overwritten with 0
  after the library loaded.

### Honest limits
- The 9.1.6 heights are a **synthetic upmix** (ambience derived from the bed), not
  decoded Atmos objects. E-AC-3 JOC is still decoded to its 5.1/7.1 bed only. The earlier claim that no open JOC decoder exists was incorrect: the later
  OpenJOC experiment below provides independent reconstruction. It has fidelity
  limits and is not a licensed Dolby renderer.
- Spatial is a generic (non-personalised) HRTF. Externalisation varies by listener.
  It is for headphones only.
- 192 kHz Spatial uses a 2048-point FFT per block and costs noticeably more CPU.
- Spatial needs the private DSP to be packaged. Without it, the chip is not shown.

### Verification
- Host JVM tests: 47 pass without the private library. 57 pass with an ABI-3 JNI
  host build of the private DSP. These include the real 9.1.6 field having energy
  in the height channels.
- Renderer tests check:
  - FFT path = direct convolution
  - block-size independence
  - left/right mirroring with ITD/ILD
  - LFE handling
  - limiter and NaN safety
  - diffuse level within ±4 dB
- Private DSP tests: 28/28, including under ASan/UBSan.
- **Not verified on a phone yet.** CPU load, audible quality and the chip switching
  live need checking on the device.

## Integrated ABI-3 Android build (0.3.17)

Applied the supplied ABI-3 patch only to the private renderer's Android branch.
Its DSP coefficient/matrix implementation is unchanged; two read-only accessors
expose the already rendered speaker field. Rebuilt the real JNI library for
arm64-v8a, x86_64 and the host, rather than shipping the previous ABI-2 binaries.
The private portable suite passes all 28 cases normally and under ASan/UBSan
(leak checking disabled for the Python host process).

Verified the bundled HRIR bytes against Omnitone upstream commit
`7839c134641260bef5456f8661e14a03426e81ad`: all 16 filters exactly match the
PCM16-to-float conversion. Upstream `src/resources/NOTICE` explicitly licenses
the Google/SADIE filters under Apache-2.0 and credits University of York (2017).
Retained that notice in the resource, source notices and existing licences page.
No copyright text was added to the player or other main app screens.

Added a real ABI-3 JNI sink regression that switches Processed → Original →
Spatial → Processed on a centre-only 5.1 signal. It checks audible centre energy
in both render paths, silence for Original's FL/FR-only comparison, finite bounded
output, acknowledged mode reports, duration, and no engine/output recreation.
Additional app tests cover pending mode requests and unavailable-Spatial fallback.

Spatial is intended for headphones; the current implementation does not detect
or enforce the physical output route. It always emits two-channel binaural PCM.
It is neither a 16-channel hardware output nor an Atmos object decoder. Phone
performance, route behaviour and listening quality remain to be tested on-device.

Full Android verification completed: 132 app tests and 85 DSP tests pass (no
skips), including the new Compose Spatial selection/acknowledgement case and the
real native mode-switching case. The first app run found two outdated two-mode
fixtures; they now acknowledge both `processingEnabled` and `listeningMode`, as
the real sink does. The older ABI-2 JNI suite also passes with its ABI-3-only
field test skipped. APK guards verify the exact supplied native libraries for
both architectures. Version is 0.3.17/code 20 with the existing signing identity.

## Experimental JOC reconstruction (0.3.18)

OpenJOC 0.20.1, pinned to `e3e330a2000f8d0bced3460756473c8774eb16f0`,
adds an opt-in clear 48 kHz JOC → 7.1.4 float PCM route before DMR. Its Apache-2.0
C API and our JNI bridge are separate from the private renderer. The speaker-only
Rust build excludes unused embedded HRTF data. Required linked-crate notices are
retained in source and the existing licences page.

OpenJOC's standard 9.1.6 layout uses front-wide speakers; the private renderer's
custom 9.1.6 field uses rear centres. They are not interchangeable. The chosen
7.1.4 mapping preserves FL/FR/FC/LFE/BL/BR/SL/SR/TFL/TFR/TBL/TBR exactly.
When input already has height speakers, synthetic height upmix is disabled.

The full 189.856-second Dolby reference passes through real JNI decoding and the
actual DMR sink on the host, switching Original/Processed/Spatial. All four heights
have energy; source LFE is silent and remains silent. Output is finite and bounded.
The native decoder emits 32 drain samples for this fixture and reports 609 samples
of internal render latency; those are different measurements. Tests also cover
packet grouping, EOS, flush and seek preroll. No reference audio is committed.

This reconstructs carrier-local JOC signals, not original authored stems. It is
not Dolby-equivalent, not a promise of original scene fidelity, and not verified
on phone hardware. The opt-in applies on loading a track; stereo remains bypassed.

Final Android/JVM run: 156 app + 88 DSP + 7 scene-decoder tests pass, without skips.
The real MP4/M4A container is also parsed completely by Media3 and identified as
JOC. Lint and exact-library packaging checks pass for ARM64 and x86-64, including
the JNI bridge source hash. An outdated bed-renderer ordering fixture and a
44.1 kHz rejection fixture were updated to the new behaviour. Cooperative test
polling resolved a host-load timeout without increasing the decoder deadline.
