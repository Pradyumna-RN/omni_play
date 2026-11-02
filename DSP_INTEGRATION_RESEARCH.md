# Tryptify DSP integration review

Reviewed the public [Tryptify source at commit
6573ae4](https://github.com/tryptz/Tryptify/tree/6573ae466ef0e198f72fe406388956be361144e4),
2 November 2026. This is a source review; Tryptify was not built or measured here.
No private Downmix Renderer source or coefficients were accessed or copied.

## What is actually integrated

Tryptify processes the audio **after decoding and before output**, inside Media3's
audio pipeline. Kotlin implements the `AudioProcessor` contract; JNI connects it
to a C++17 engine compiled for Android as `libmonochrome_dsp.so`. It does not load
a Windows DLL into Android or rely on a system-wide equalizer.

The actual normal-player order is visible in
[PlaybackService.kt, lines 1012–1043](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/java/tf/monochrome/android/player/PlaybackService.kt#L1012):

```text
Decoder PCM
  → channel / BPM / dynamic-range taps
  → ReplayGain
  → optional Atmos render and stereo upmix
  → native mixer and effects
  → multichannel-to-stereo downmix
  → AutoEQ
  → parametric EQ
  → visualizer taps
  → silence / speed / pitch stages
  → Android audio output
```

The USB and high-resolution Android output paths run the equivalent processing
chain in a wrapping sink. This matters because an alternative output path must
not accidentally omit DSP or apply it twice.

## Buffer and native-library boundary

- [MixBusProcessor.kt](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/java/tf/monochrome/android/audio/dsp/MixBusProcessor.kt#L224)
  negotiates PCM16 or float PCM, sample rate and channel count. Stereo/mono samples
  are converted into reusable left/right float arrays; output is interleaved again.
  PCM16 output is quantized with dither. Work is divided into bounded frame blocks.
- The stereo
  [JNI entry point](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/cpp/dsp/dsp_jni.cpp#L49)
  receives float arrays. `GetFloatArrayElements` can copy or pin them, so this path
  should not be described as zero-copy. Multichannel processing instead uses a
  [direct planar float buffer](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/cpp/dsp/dsp_jni.cpp#L399),
  checks its capacity and processes it in place.
- [CMakeLists.txt](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/cpp/CMakeLists.txt#L57)
  builds the C++17 shared library with optimized ARM64 SIMD support. Android ABI
  selection is configured in
  [app/build.gradle.kts](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/build.gradle.kts#L133).
  [DspNativeLoader](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/java/tf/monochrome/android/audio/dsp/DspNativeLoader.kt#L3)
  centralizes library loading so linker work can happen away from UI startup.

## Lifecycle lessons worth retaining

Tryptify creates the native engine when a format is configured, reconfigures it
when the sample rate/layout changes, and preserves user settings across ordinary
Media3 flushes. Its main engine handle remains alive while control/UI code can
reference it. This avoids a documented use-after-free during track changes. A
small wrapper in our app should instead have an explicit, safely synchronized
owner and a clear release boundary; the important lesson is handle lifetime, not
making every native object live forever.

Its
[DSP bypass](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/java/tf/monochrome/android/audio/dsp/MixBusProcessor.kt#L263)
skips the conversion and native processing work. AutoEQ and parametric EQ have
separate enabled states, so bypassing the mixer does not automatically mean every
other processor is off. Our eventual bypass must also preserve the negotiated
channel/format contract, particularly when an active processor changes channel count.

Each simultaneous stream needs its own filter history. Tryptify's
[DspChain](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/java/tf/monochrome/android/audio/dsp/DspChain.kt#L12)
creates a separate engine for a crossfade tail and seeds its settings. Sharing one
stateful engine between two decoders would mix their histories and sample-rate state.

The C++ engine uses atomics for simple controls and mutexes for graph edits.
Its multichannel wrapper has a try-lock fallback, but the implementation is not
universally lock-free. We should design bounded audio-thread work and avoid file
I/O, JSON parsing, graph construction and long control locks in the processing call.

## The downmix attribution is narrower than the complete DSP engine

The public
[DownmixProcessor.kt documentation](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/java/tf/monochrome/android/audio/dsp/DownmixProcessor.kt#L14)
attributes its fixed direct matrix to the **peqdb Downmix Renderer ADC2**. That
file is a multichannel fold-down processor and is inactive for ordinary mono or
stereo input. The app also contains separate effects and spatial-rendering paths.

This attribution does **not** establish that Tryptify embeds the private
`prospeck/downmix-renderer-source` implementation, that the algorithms are identical,
or that its licence grants rights to redistribute that private implementation.
No comparison with private code was performed.

## Recommended boundary for Music

Our existing `playback/AudioPipeline.processors()` hook already sits at the same
useful point: Media3 owns the decoded PCM for both local files and our native
YouTube playback. Keep provider fetching separate from DSP.

For the next DSP phase, define a small independent interface: create/configure an
engine, process bounded PCM blocks, update parameters, reset on seek, and destroy
it safely. Put an Android `AudioProcessor`/JNI adapter around that interface. Keep
the author's private implementation in its private build and use an Android NDK
library under the author's actual distribution terms. A compiled library avoids
publishing source, but cannot guarantee that its algorithm is unrecoverable.

**Resolve the float-output path before enabling DSP.** Tryptify explicitly notes
that Media3's float branch can skip the custom processor chain; it supplies its
own float-processing sink path to address this. Simply adding a processor to our
current `setAudioProcessors(...)` call is not sufficient evidence that every input
format will be processed. Choose and test a deliberate PCM path, including float
and high-bit-depth sources, with no silent bypass.

The useful first scope is one processing engine, working bypass, clear format
negotiation and predictable stereo behaviour. The full console, USB driver, Atmos
renderer and large plugin catalogue are independent features. A multichannel
downmixer alone will not enhance an already stereo YouTube stream.

Tryptify is
[GPL-3.0-or-later](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/README.md#licences).
Copying its Kotlin/C++ implementation or incorporating its engine would require
complying with that licence and may conflict with a private-only DSP distribution.
We can independently implement the integration pattern without importing its code.

Development does not require extra hardware: its
[host DSP test runner](https://github.com/tryptz/Tryptify/blob/6573ae466ef0e198f72fe406388956be361144e4/app/src/main/cpp/dsp/tests/run_host_tests.sh#L1)
demonstrates desktop checks, including sanitizers and control/audio stress tests.
For our implementation, test known signals, clipping/non-finite input, channel
identity, bypass, seeks, rate changes and repeated create/destroy cycles on the
laptop; validate actual latency, Android routes and listening behaviour on a phone.

This review adds no DSP implementation to the APK. DSP remains off in v0.3.
