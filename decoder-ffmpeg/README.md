# Local AC-3/E-AC-3 bed decoder prototype

Uses the **Apache-2.0 Media3 1.6.1** FFmpeg adapter, with separately linked
**LGPL-2.1-or-later FFmpeg n6.1.6** shared libraries. NextLib, Tryptify, Cavern and
their GPL/non-commercial implementations are not dependencies.

Only `ac3` and `eac3` decoders are enabled. GPL, GPLv3, nonfree and version-3
components, networking, demuxers, encoders and programs are disabled. The build
checks the generated configuration, not just the requested configure arguments.
`libavcodec.so`, `libavutil.so` and `libswresample.so` remain separate replaceable
libraries. The public JNI adapter links them dynamically. DMR is unchanged.

## Scope

- The software renderer is preferred only for clear AC-3/E-AC-3/JOC inputs.
  DRM-protected formats are rejected by the adapter. Other codecs retain their
  existing platform path.
- Output is the **decoded channel bed**, not reconstructed Atmos objects.
  An E-AC-3 JOC input is not evidence of a full Atmos object render.
- FFmpeg's actual native channel mask is reconciled with DMR speaker roles.
  Unknown/custom layouts, unsupported positions and count/mask mismatches are
  rejected. A multichannel/JOC input folded to stereo is rejected.
- The float conversion preserves the decoded channel layout and sample rate.
  Codec DRC is disabled. DMR processes supported multichannel PCM at 48/96/192 kHz;
  stereo bypasses DMR without app upmixing/resampling. Android's output device or
  mixer can still convert audio; this is not a bit-perfect hardware guarantee.
- This adds no provider login, scraped credentials or decryption wrapper.

## Build and replace the decoder

Download the pinned FFmpeg source archive:

```sh
curl -fL https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/refs/tags/n6.1.6 -o ffmpeg-n6.1.6.tar.gz
# SHA-256: 7481306bddb81f4d4b42bfde4b39ea4fa097d7fa5b8cdba377142c88172b4af9
python3 decoder-ffmpeg/tools/build.py --archive ffmpeg-n6.1.6.tar.gz --output /tmp/music-decoder --target arm64-v8a --ndk /path/to/android-ndk
python3 decoder-ffmpeg/tools/build.py --archive ffmpeg-n6.1.6.tar.gz --output /tmp/music-decoder --target x86_64 --ndk /path/to/android-ndk
```

JDK 17, Python 3.12+, `make`, and Android NDK 27 were used. Host builds additionally
need a C/C++ compiler. No NASM, CMake, provider credentials or private renderer
source is required. The source archive SHA-256 is checked before extraction.
Build directories are separate for each target; extract/build the first target
before starting other targets against the same output directory.

Supply the built shared libraries with
`-PrtensorFfmpegJniDir=/tmp/music-decoder/jni` and run
`:app:verifyEac3DecoderBundle`. Integrated builds also use the existing
`rtensorDspJniDir` property. Builds without the FFmpeg property retain the device
decoder. No native binaries are committed to this repository.

To use a modified LGPL decoder, rebuild these libraries and the public Music
application with your own signing key. Extract only `lib/<ABI>/librtensor_downmix.so`
from the installed Music APK into `<directory>/<ABI>/librtensor_downmix.so`, then
supply that directory unchanged through `rtensorDspJniDir`;
replacing FFmpeg does not require DMR source. Android requires uninstalling the
original before installing an APK signed by a different key.

## Corresponding source and notices

Any distributed decoder-enabled APK must be accompanied by the complete FFmpeg
source archive and this adapter/build script. Create the source bundle:

```sh
python3 decoder-ffmpeg/tools/package_source.py --archive ffmpeg-n6.1.6.tar.gz --output decoder-source.tar.gz
```

Attach that bundle alongside the APK. Component licences are in `licenses/`,
and the app's third-party notices identify the decoder and source. The complete
public Music code is also available from the build's Git commit. LGPL replacement
and reverse engineering for debugging modifications to the LGPL component are
permitted; this does not grant rights to the proprietary DMR implementation.
These checks concern software licences, not Dolby certification or patent grants.

Upstream: `androidx/media`, tag `1.6.1`, `libraries/decoder_ffmpeg/src/main`.
Changes: extensible renderer/public decoder, native channel-mask getter,
full-range decode, corrected PCM output capacity/offset, and a dormant upstream
TrueHD reset lifetime fix. Direct-buffer bounds and reused-packet zero padding
are checked. FFmpeg itself is unmodified. If redistributing a modified FFmpeg,
include those modifications and the matching build instructions in its source bundle.

## Tests

The generated fixtures contain original synthetic tones, not provider music.
Six channels carry 311/521/733/77/947/1163 Hz at 48 kHz, encoded as E-AC-3 at
640 kbit/s. FFprobe independently identifies `5.1(side)`. The stereo fixture
contains 311/521 Hz at 192 kbit/s. These establish bed decode/channel identity,
not JOC object rendering.

Build a host JNI decoder using `--target host --jdk /path/to/jdk17`, then run
`:dsp:testDebugUnitTest` with `rtensorFfmpegHostLibraryDir=<output>/host` and the
existing `rtensorDspHostLibraryDir`. The tests use the actual codec and original
DMR JNI libraries. Device playback still needs validation with a user's local file.

### Native decoder checks (no Android SDK needed)

`tools/native_test.py` compiles `src/main/jni/ffmpeg_jni.cc` together with
`src/test/native/decode_packet_test.cc` against a host FFmpeg 6.x/7.x prefix
(`include/` and `lib/` with libavcodec, libavutil, libswresample), with
AddressSanitizer and UndefinedBehaviorSanitizer, and runs it on the fixtures above:

    python3 decoder-ffmpeg/tools/native_test.py --ffmpeg-prefix /path/to/ffmpeg --jdk "$JAVA_HOME"

It checks that one packet holding several E-AC-3 syncframes decodes to the
same bytes as separate packets (float and s16), that growing the output buffer
keeps earlier frames, that an exactly sized buffer is never overrun, that a
5.1 → stereo change rebuilds the converter, and that invalid data produces no
output. Before the fix the multi-frame cases differed at byte 36864 (float) or
18432 (s16), and the growth case crashed.

To check a complete clear 48 kHz 5.1 recording without bundling it in the tests,
stream-copy its audio to raw EC3 and pass `--recording`:

```sh
ffmpeg -i own-recording.m4a -map 0:a:0 -c:a copy -f eac3 /tmp/own-recording.eac3
python3 decoder-ffmpeg/tools/native_test.py --ffmpeg-prefix /path/to/ffmpeg --jdk "$JAVA_HOME" --recording /tmp/own-recording.eac3
```

This streams the full recording through the real native decoder with 1/2/3/6
syncframes per packet and compares decoded byte counts and SHA-256 hashes. It
also rejects non-finite PCM. Output is not retained as a full PCM recording.
The tests exercise the host decoder loop, not Android output or Atmos objects.
