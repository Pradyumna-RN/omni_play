# Apple runtime compatibility investigation

The inspected wrapper-v2 native runtime is pinned to Apple Music for Android
3.6.0-beta, build 1109. The current app does not yet contain a working Apple
native-runtime adapter. An Apple Music subscription alone is not a verified raw
multichannel PCM interface.

`LIBS_VERSION.json` is the unmodified native compatibility/hash manifest from
https://github.com/glomatico/wrapper-v2 at commit `100e0a864e883e03a3ac450a780dd9563fff5271` (Unlicense).
It contains hashes and version metadata, not Apple libraries or account data.

To inspect a locally supplied APK/APKM without executing or extracting its code:

```
python3 tools/apple/inspect_runtime.py /path/to/apple-music.apkm --arch arm64-v8a
```

A matching report means only that the expected library bytes are present. It does
not prove JNI loading on a modern phone, successful sign-in, playback entitlement,
FairPlay operation, codec quality or DMR access. Those need separate tests. Do not
commit an Apple APK, native libraries, accounts, cookies, passwords or tokens.

The Apple Music TV project was also inspected. Its standalone path feeds clear
AAC to ExoPlayer; it is not evidence for an ALAC/Atmos multichannel integration.
Its GPL application/CDM implementation has not been copied into Music.

## Phone-only experiment

The requested integration must run inside Music on the phone, without a desktop,
remote server or separate DSP app. Android 11+ (API 30) across brands is the
baseline, with the existing ARM64 and x86-64 native builds. A Motorola Edge 50 Neo
is one test device; it does not define a device restriction. Its Android version
and system memory-page size have not been reported. An Apple
Music subscriber is available for private on-device sign-in testing. No account
information is needed in chat.

wrapper-v2 has an ARM64 NDK worker, but its existing launcher uses Linux mounts
and chroot. Its ARM64 container is not a verified unprivileged Android-app build.
A native in-app port, library dependency closure and account/audio tests are still
missing. The separately documented QEMU backend is not being added to Music.

For an actual user-supplied APK/APKM, the optional NDK metadata check reads ELF
headers, load segments and DT_NEEDED dependencies from stdin, without extracting
or executing native files:

```sh
python3 tools/apple/inspect_runtime.py /path/to/apple-music.apkm \
  --arch arm64-v8a \
  --elf-tool /path/to/android-ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readobj
```

`nativeMetadata` covers the profile's libraries in the requested ABI. A dependency
name present elsewhere in the package is only an inventory observation; it is not
proof that the dependency can load. Dependencies absent from both the package and
the public NDK API-30 library set are listed for investigation. Newer Android-only
public libraries can also appear in that conservative list. An image with 4 KiB
alignment may still run on a 4 KiB phone; the 16 KiB field is a separate check, not
a claim about this tester's page size. Package signing/origin is not checked here.
Missing files, conflicting split libraries, wrong ELF architecture and malformed
ELF are reported separately from hash matches. Runtime, account and playback flags
remain false even when every static check passes.

Run the checker tests, including generated ARM64/x86-64 ELF fixtures, with:

```sh
ANDROID_NDK_HOME=/path/to/android-ndk \
  python3 -m unittest discover -s tools/apple -p 'test_*.py' -v
```

The fixtures contain only generated test functions. No Apple libraries or audio
are used in the tests. The checker accepts a locally supplied package; the pinned
profile requires Apple Music 3.6.0-beta/build 1109, and newer packages must not be
silently treated as compatible with that old private ABI.

The [current-distribution research record](research/current-distribution-2026-11-04.json)
also contains metadata from three ARM64 libraries read in memory from a current
installer supplied by a store linked from Apple's Android distribution guide.
They have 16 KiB load alignment, but the archive lacks the old worker's required
libstoreservicescore.so. This does not provide a working adapter or verify the
package signer, account sign-in, decoder output or DMR. No Apple binary was saved,
loaded, installed or included in the app.
