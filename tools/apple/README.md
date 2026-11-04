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
