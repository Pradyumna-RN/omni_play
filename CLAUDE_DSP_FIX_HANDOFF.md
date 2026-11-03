# Fix Android multichannel playback and DMR integration

## User request

The user reports that the original desktop Downmix Renderer works, while the
music files tried in our Android Music app do not. They want the Android DSP
integration fixed so they can compare Original and Processed with real music.
Treat this as an unresolved Android playback/DSP integration defect. Passing
host tests is not evidence that the user's phone works.

The recent test files are The Weeknd / Starboy `.m4a` tracks. Screenshots show
filenames, sizes and durations, but not their actual codecs or channel layouts.
No failing audio file, MediaInfo report, exact playback error, device model or
Android version has been provided in this session. The user has not yet clarified
whether failure means import failure, no playback, silence, or no audible change.
The precise failing stage is therefore not established. Preserve the user's
desktop success as the comparison to reproduce; do not assert a proven bug in
the original native algorithm without tracing the Android path.

## First check: which APK is installed?

The normal OTA release is still **0.3.13 / versionCode 16**. The new software
E-AC-3 decoder is in a separate manual **0.3.14 / versionCode 17** prototype:

- Source branch: `feature/local-eac3-decoder`.
- Implementation commit: `4d8c16257f7097d527a90dafc91d7ece584f6272`.
- [Prototype release](https://github.com/RTensorLabs/music-app/releases/tag/local-eac3-prototype-20261103).
- APK asset: `music-eac3-prototype.apk`.
- APK SHA-256: `7c5509cd4d8027e1a8b04e5712926a1213f74426882a0e8faea3b79a8d8069dd`.
- Package: `labs.rtensor.music`.
- Signing certificate SHA-256:
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.

The prototype does not appear in the existing OTA selector: its release tag is
not a semantic version and its APK uses a different asset name. Do not assume
the user installed it. Confirm the installed version/build before diagnosing
the decoder. A subsequent distributed fix must have **versionCode > 17** and
a newer version name so both Android and the updater accept it.

Check the latest branch state and preserve any newer Claude work before editing.
Do not start from main and accidentally omit the decoder/provider-source work.

## Required behaviour

- Keep the current approved UI and the original private DMR implementation.
  Do not replace it with Cavern, a generic matrix, or a new DSP application.
- Mono/stereo must use the ordinary playback path. Do not enable stereo
  enhancement, invent extra channels, or resample stereo to make comparison work.
- Decode supported clear multichannel AC-3/E-AC-3 to PCM with a verified speaker
  layout, then feed that PCM into the original DMR inside Music.
- Supported native DMR rates are currently 48/96/192 kHz. Diagnose unsupported
  multichannel rates/layouts explicitly; do not silently substitute stereo.
- Original uses the original renderer's FL/FR-only comparison with its common
  gain/delay. Processed includes the supported multichannel contributions.
  Switching should preserve playback position and native filter history.
- UI processing status must acknowledge actual applied processing, not merely
  the selected button. A paused pending change can wait for new PCM.
- FFmpeg E-AC-3 JOC decoding provides a multichannel bed; it does not reconstruct
  the full Atmos object scene. Keep that distinction truthful.

## Trace the actual failure

1. **Reproduce and identify the source.** Obtain one failing file privately and
   the exact symptom/error. Compare the identical file on desktop and Android.
   Inspect codec/profile, encryption, duration, sample rate, channel count and
   speaker layout with MediaInfo/ffprobe. `.m4a`, `[M]`, artwork and file size do
   not establish Atmos. Keep commercial music and account data out of GitHub.
2. **Confirm import and URI access.** Verify persistent read access and playback
   of the original content URI after app restart. Metadata support is optional
   at explicit import. Check whether the file picker hides an audio-only MP4
   because its provider labels it `video/mp4`; the current picker accepts
   `audio/*` and `application/octet-stream`.
3. **Confirm native loading on the phone.** Log the installed ABI, packaged
   libraries, `FfmpegLibrary.isAvailable()`, `NativeDownmixEngine.isAvailable`
   and private DSP ABI version. Retain the actual native load/ABI exception;
   `NativeBindings.available` currently collapses load failures into a Boolean.
   Check dependencies and 16 KiB alignment on the affected device. Both native
   modules must be present in the Music APK.
4. **Confirm extraction and renderer selection.** Record Media3's input Format,
   decoder initialization/name, selected track and original PlaybackException
   cause chain. The preferred renderer should be `MusicFfmpegBedAudioRenderer`
   for clear AC-3/E-AC-3/JOC. Distinguish failure to load the software decoder
   from a device-codec fallback or genuine decoder error. Check MP4 `ec-3`
   extraction and JOC access units/dependent substreams with real content.
5. **Confirm decoded PCM before DMR.** Record sample rate, PCM encoding, channels,
   FFmpeg native channel mask and resolved speaker order. Verify no folding to
   stereo before the DSP. Check the decoder's initial output-format caching and
   later format changes; do not assume the first packet describes every packet.
   Reconcile FFmpeg masks with DMR positions; side and back surrounds are distinct.
   Reject unknown layouts rather than guessing from the channel count.
6. **Confirm DMR processing and Android output.** Trace sink configuration, JNI
   create/process/compare/reset return status, input/output frame counts, buffer
   consumption, end draining and AudioTrack writes/backpressure. Establish that
   non-silent PCM reaches DMR and its output reaches the sink without truncation,
   duplicated blocks, invalid float data or unintended gain changes. Add bounded
   diagnostics, not per-sample logging or heavy work on the main/audio thread.
7. **Compare with desktop on equal inputs.** Feed the same decoded PCM, speaker
   positions, rate, gain and comparison mode to both wrappers. If desktop receives
   a full object-rendered 7.1/9.1.6 signal while Android receives a 5.1 bed, document
   that input difference; do not claim identical Atmos rendering. Change private
   processing code only if a controlled comparison actually identifies a core bug.

Keep these as investigation steps, not claims that each issue is already present.
Preserve useful error causes in diagnostics so the user can report what failed.
Do not dismiss a confirmed multichannel failure as expected stereo bypass.

## Relevant code

| Stage | Files |
| --- | --- |
| File import | `app/src/main/java/labs/rtensor/music/MainActivity.kt`, `MusicViewModel.kt`, `data/LocalAudioImporter.kt` |
| Player/renderer wiring | `app/src/main/java/labs/rtensor/music/playback/MusicPlaybackService.kt`, `AudioPipeline.kt` |
| Renderer selection and PCM layout | `dsp/src/main/java/labs/rtensor/dsp/DownmixRenderersFactory.kt`, `FfmpegSpeakerLayout.kt`, `AndroidSpeakerLayout.kt` |
| PCM processing/output | `dsp/src/main/java/labs/rtensor/dsp/DownmixAudioSink.kt`, `NativeDownmixEngine.kt`, `DownmixFormat.kt`, `PcmFloatConverter.kt` |
| Software decoder | `decoder-ffmpeg/src/main/java/androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.java`, `FfmpegAudioDecoder.java`, `FfmpegLibrary.java`, `decoder-ffmpeg/src/main/jni/ffmpeg_jni.cc` |
| Compare UI/status | `app/src/main/java/labs/rtensor/music/ui/MusicApp.kt`, `playback/AudioPipeline.kt` |
| Tests/build | `dsp/src/test/java/labs/rtensor/dsp/Eac3DecoderTest.kt`, `DownmixAudioSinkTest.kt`, `NativeDownmixEngineTest.kt`, `app/src/test/java/labs/rtensor/music/LocalAudioImportTest.kt`, `app/build.gradle.kts`, `dsp/build.gradle.kts` |

Some table entries share their preceding package directory; resolve those names
within that directory. Native build/replacement instructions are in
[decoder-ffmpeg/README.md](decoder-ffmpeg/README.md) and [dsp/README.md](dsp/README.md).
The original renderer source is in a separate private repository. Never copy its
implementation, coefficient definitions, secrets or keys into this public repo.

## Reproduction content and existing evidence

The previous complete run passed **123 app checks and 52 DSP checks**, fatal lint
and both APK packaging guards. Three new DSP checks exercise actual host FFmpeg
JNI, six distinct decoded channels, the real original DMR JNI, stereo bypass,
flush and conservative format/layout selection. Those fixtures are synthetic
ordinary E-AC-3, not the user's music or a JOC object test. No physical Android
device was attached. The user's real playback failure remains unresolved.

A real music test vector is available directly from Dolby:

- [UltraViolet — Untie, DD+ JOC 768 kbit/s](https://ott.dolby.com/media/music/mp4s/UltraViolet_Untie_ddpjoc_768kbps.mp4).
- ffprobe identified E-AC-3, Dolby Digital Plus + Dolby Atmos, **6 channels,
  `5.1(side)`, 48 kHz**, 768 kbit/s, about 189.856 seconds.
- Dolby supplies this content for testing. Link to its original source; do not
  bundle or republish it as app music. It has not been played through our Android
  app in this session. Codec identification alone is not a successful DMR test.

Use this known source to progress while arranging the failing file. Continue
independent investigation without making the user's file the only next step.

## Completion criteria

1. Explain the reproduced root cause with logs or an actual PCM comparison, and
   fix it at the responsible stage. Add a regression test for the real failure.
2. Demonstrate a complete multichannel music playback through decoder → original
   DMR → Android sink, including Original/Processed, seek forward/back, pause/
   resume, end of track and the next track. Record evidence from the affected
   phone when available; clearly label host-only checks otherwise.
3. Verify stereo remains unprocessed and byte-identical through the DSP adapter;
   comparison must not be fabricated for stereo. Preserve existing player motion
   and UI responsiveness.
4. Run appropriate app/DSP tests, fatal lint, `:app:verifyOtaDspBundle` and
   `:app:verifyEac3DecoderBundle` using both sets of supplied native libraries.
   Decoder-enabled releases need the corresponding LGPL source bundle and notices.
5. Build an integrated APK with the existing package/signing identity and a new
   version/code. The user expects OTA: use a versioned release tag, a supported
   APK filename (`music-debug.apk` or `music-release.apk`), and matching
   `SHA256SUMS` when publishing the validated fix. Verify the real updater can
   select it. Do not quietly publish another manual-only prototype as the fix.
6. Report what was reproduced, changed and tested, plus any remaining device or
   full-Atmos limitations. Do not claim a phone fix from synthetic tests alone.

For any new commits, use **Tejascodex001** as requested by the user. Preserve
concurrent work and keep the original renderer private.
