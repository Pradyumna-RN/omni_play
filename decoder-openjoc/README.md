# Optional OpenJOC speaker decoder

Independent, experimental JOC reconstruction before DMR. This module uses OpenJOC
0.20.1 at commit `e3e330a2000f8d0bced3460756473c8774eb16f0`, ABI 1.7. It renders
supported, unprotected 48 kHz JOC to explicitly ordered 7.1.4 float PCM. It does
not recover original authored stems or claim Dolby renderer equivalence.

The app's Scene decoding setting is off by default and applies when loading a
track. Turn it off and reload a track to use the existing base-bed decoder.
Stereo sources bypass this decoder and DMR. For scene output with explicit height
speakers, the DMR adapter leaves its synthetic height upmix disabled.

The JNI adapter owns native decoding on one worker thread. Its bounded input and
output pools support backpressure, flush, seek preroll and filter draining before
EOS. The reference JOC fixture emits 32 extra drain samples; the library also
reports internal render latency. Neither is a bit-perfect or zero-latency claim.

## Source and build

The native probe workflow is reproduced in `tools/native-build-workflow.yml`.
It checks out the exact source commit, runs upstream C API tests, builds host and
Android ARM64/x86-64 artifacts with 16 KiB ELF alignment, and records SHA256SUMS.
A two-line Cargo feature patch disables unused built-in HRTF data. Speaker
rendering uses Music's existing DMR and Omnitone stage instead of OpenJOC's HRTFs.

Build the app's adapter against those verified artifacts:

```
python3 decoder-openjoc/tools/build_bridge.py --artifacts /path/to/artifacts \
  --output /path/to/compiled --java-home /path/to/jdk17 --ndk /path/to/ndk
```

Then pass `-PrtensorOpenJocJniDir=/path/to/compiled/jni` for APKs and
`-PrtensorOpenJocHostLibraryDir=/path/to/compiled/host` for native JVM tests.
The existing private-DSP and FFmpeg properties remain required for integrated
builds. Do not commit compiled native libraries or private renderer files.

For real-audio tests, set `-Drtensor.openjoc.recording=/path/to/UltraViolet_Untie.eac3`
to a privately held copy of the public Dolby reference. No recording is bundled.
Tests requiring that fixture skip in contributor builds without it; integrated
validation must supply it and report no skips.

## Licences

OpenJOC and its public C API header use Apache-2.0; see `OPENJOC_LICENSE`. Linked
crate records and required notices are retained in `third-party/`. The native
build excludes the upstream embedded SADIE datasets. The app's existing Omnitone
asset and notices remain unchanged. No Cavern or GPL application code is linked.
