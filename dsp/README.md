# Android downmix adapter

Standalone Android AAR, minimum API 26. Interfaces and Media3 integration are open
in this module; the author's processing implementation stays in its private build.
No DSP source, matrices, coefficients or compiled library is committed here.
The portable build reuses the supplied renderer's native core.

## Build

Ordinary source builds contain the adapter only and retain the normal player.
To package the separately built private ARM64/x86-64 libraries:

```sh
./gradlew :dsp:assembleRelease :app:assembleDebug \
  -PrtensorDspJniDir=/absolute/path/to/private/android-output
```

The directory must contain `arm64-v8a/librtensor_downmix.so` and/or
`x86_64/librtensor_downmix.so`. AAR output is `dsp/build/outputs/aar/dsp-release.aar`.
Do not commit or publish private artifacts from this module's build directory.
A build that declares the private artifact but cannot load it fails an attempted
multichannel render explicitly instead of silently claiming DSP processing.

## Processing contract

- `NativeDownmixEngine`: direct, native-order, interleaved float32 buffers; one
  bounded processing owner per stream; explicit speaker-position IDs. Each stream
  has independent history. Configure allocates; processing does not grow buffers.
- Supported core rates: 48, 96 and 192 kHz. Unsupported rates are rejected. No
  hidden resampling or relabelling is performed by the adapter.
- `DownmixAudioSink` wraps the decoded PCM sink, including float output. It avoids
  Media3's float-branch `AudioProcessor` bypass and preserves PCM24 low bits during
  conversion to float. PCM32 conversion follows the core's float32 precision.
- Multichannel PCM16/24/32/float is mapped to the original core and outputs stereo
  float. Mono/stereo bypass the core; no stereo upmix is fabricated.
- Speaker positions come from the decoder's Android mask, or FLAC's specified
  channel assignment when that mask is absent. Unknown masks, remapping arrays
  and unsupported positions (including back-center) produce explicit errors.
  Generic multichannel WAV without exposed positions is not guessed from count.
- Partial writes retain pending output without processing a block twice. Seek and
  discontinuity clear pending output and reset histories. EOF drains the declared
  delay plus a bounded 50 ms filter tail; the clock excludes this padding.
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
