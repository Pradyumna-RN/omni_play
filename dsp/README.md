# Android downmix adapter

Standalone Android AAR, minimum API 26. Interfaces and Media3 integration are open
in this module; the author's processing implementation stays in its private build.
No private DSP source, matrices, coefficients or compiled library is committed here.
The portable build reuses the supplied renderer's native core.
The interpolation filter in `PcmRateAdapter` is a public sample-rate adapter,
separate from the private renderer and its coefficients.

## Build

Ordinary source builds contain the adapter only and retain the normal player.
To package the separately built private ARM64/x86-64 libraries:

```sh
./gradlew :dsp:assembleRelease :app:verifyOtaDspBundle \
  -PrtensorDspJniDir=/absolute/path/to/private/android-output -PrtensorOta=true
```

The directory must contain ABI **2** native libraries. Ordinary AAR builds accept
`arm64-v8a/librtensor_downmix.so` and/or `x86_64/librtensor_downmix.so`; official
OTA validation requires both. ABI 1 belongs to the earlier private 0.1.0 preview.
AAR output is `dsp/build/outputs/aar/dsp-release.aar`.
`rtensorOta=true` makes the distributed Music APK non-debuggable while retaining
the existing variant/filename and signing identity. Omit it for local debugging.
Do not commit private artifacts from this module's build directory. Music OTA
publishes the compiled renderer inside its APK; the implementation source and
coefficients remain private. No separate DSP app/install is required.
A build that declares the private artifact but cannot load it fails an attempted
multichannel render explicitly instead of silently claiming DSP processing.

## Processing contract

- `NativeDownmixEngine`: direct, native-order, interleaved float32 buffers; one
  bounded processing owner per stream; explicit speaker-position IDs. Each stream
  has independent history. Configure allocates; processing does not grow buffers.
- Supported core rates: 48, 96 and 192 kHz. For known multichannel layouts, the
  adapter converts **44.1→48, 88.2→96 and 176.4→192 kHz** using a streaming
  128-tap normalized windowed-sinc interpolator, retaining channel identity and
  phase/history across blocks. Source and render rates are reported separately.
  Unprocessable PCM uses the Android delegate if it accepts the format, with
  comparison disabled and a reason. Music bypasses stereo unchanged at every rate.
- `DownmixAudioSink` wraps the decoded PCM sink, including float output. It avoids
  Media3's float-branch `AudioProcessor` bypass and preserves PCM24 low bits during
  conversion to float. PCM32 conversion follows the core's float32 precision.
- Supported multichannel PCM16/24/32/float maps to the original core and outputs
  stereo float. Mono and stereo bypass by default. Other library consumers may
  opt into native stereo enhancement using `processStereo`; Music does not do so.
  Generated spatial channels are not original Atmos metadata.
- Live comparison calls the original native compare control: FL/FR only, with
  common delay/gain. A 10 ms fade out followed by a 10 ms fade in changes the mix
  at silence. It keeps native history warm and does not reconfigure the sink or
  reload the source. Rate conversion remains active in both comparison modes;
  neither mode establishes bit-perfect hardware output.
- Speaker positions come from the decoder's Android mask, or FLAC's specified
  channel assignment when that mask is absent. Unknown masks and unsupported
  positions (including back-center) disable DSP and pass the original PCM to the
  Android delegate, with an explanation. Decoder remapping arrays still require
  explicit reconciliation before processing and otherwise produce an error.
  Generic multichannel WAV without exposed positions is not guessed from count.
- Partial writes retain pending output without processing/resampling a block twice.
  Seek and discontinuity clear pending output and reset both histories. EOF drains
  the converter/core delay plus a bounded 50 ms core filter tail; the clock excludes
  this padding and uses the actual render rate.
- Processed output requests `SPATIALIZATION_BEHAVIOR_NEVER` to avoid a second Android
  spatializer pass. OEM/device effects and actual output routing require phone tests.
- A `DownmixReport` records known input positions, rate and stereo output. It does
  not infer Atmos object rendering from PCM channel counts and never calls the
  processed result bit-perfect. Stereo bypass alone also does not prove bit-perfect
  hardware output.

For player integration, use `DownmixRenderersFactory` only when the native artifact
is supplied. It decodes compressed audio before the sink stage, disables compressed
offload, rejects an exposed multichannel/JOC source being folded to stereo before
DSP, and requires an output capable of receiving float PCM.

This is an app-owned audio path. It does not access protected Apple Music PCM,
decode Atmos objects itself, capture another app or provide a streaming catalogue.

## Checks

```sh
./gradlew :dsp:testDebugUnitTest \
  -PrtensorDspHostLibraryDir=/absolute/path/to/private/host-output/host
```

Supplying the host directory makes native JNI loading mandatory for the tests.
Without it, native-only tests are explicitly skipped, while adapter/format tests
still run. Buffer/backpressure tests use a recording output sink; they do not
establish phone AudioTrack behaviour. The private build separately compares actual
kernel output against the existing desktop implementation.

Tryptify was consulted for integration concepts and float/multichannel lifecycle
considerations; no Tryptify implementation or coefficients are imported.
