# v0.3.8 verification

Based on Claude's `94f14f9e034b0206300327bd1b8c27d07cbba648` update. This build adds
native cache/retry fixes and corresponding engine feedback/format constraints.

## What was actually tested

- **73 Android/JVM tests passed**, no failures or skips, including 18 Compose checks.
- **15 JavaScript unit tests**, **42 Chromium engine checks**, and **6 isolated proof
  checks** passed. Browser provider responses, account data and attestation are fixtures.
- Three generated AAC/MP4 tones were transferred fully over real loopback HTTP through
  the production Media3 range/retry/cache classes. Received bytes matched each source
  SHA-256. The real MP4 extractor reached EOF and independently FFmpeg decoded each
  complete received file with `-xerror` and no decoder warnings/errors.

| Synthetic test track | Length | Received bytes | Complete transfer / extraction / decoding |
| --- | --- | --- | --- |
| 440 Hz AAC | 180 s | 2,917,751 | Passed |
| 550 Hz AAC | 210 s | 3,405,530 | Passed |
| 660 Hz AAC | 240 s | 3,891,527 | Passed |

Each track crosses at least two one-MiB boundaries. The server rejects the first
client after one MiB, rejects the second client too, then serves the third. Recovery
preserves the exact byte offset. Cached backward/forward/tail reads use no new HTTP.
A separate queue item reads only its header, seeks outside the cache and recovers
without losing the original catalogue URI. The extractor then verifies timestamped
forward/backward seeks. The total audio decoded is **630 seconds (10.5 minutes)**;
tests run faster than real time and do not represent listening on a phone.

Reproduce after running the Android suite:
`python3 tools/verify-full-track-audio.py --output artifacts/playback-validation.json`

## Fixes covered by the regressions

- CacheDataSource sees the original catalogue URI rather than persisting an expiring
  signed media URL that bypasses resolution on an uncached seek.
- Media retries exclude the actual failed path for that queue item, preserve source
  identity/headers and stop after a finite budget. Other recordings and prefetches
  cannot overwrite its rejection list.
- Engine responses include the playback path and encoded format. Mid-file recovery
  accepts the original itag/MIME/length only; incompatible audio produces an explicit
  error rather than mixed/corrupt media bytes.
- Native `isPlaying` feedback selects the preferred path. Merely obtaining a URL
  does not mark playback successful. All excluded paths fail without more player calls.
- Separate guest sessions keep visionOS variants isolated; concurrent exclusion
  lists do not share an incompatible resolution result. Visitor requests share one
  in-flight fetch. Native guest requests keep account cookies/headers out.
- Direct visionOS resolution completes while player JavaScript is deliberately
  blocked. A ciphered guest request waits for and receives that shared player.
- Wrong-video responses are rejected before returning audio or relabelling metadata.

The visionOS version/model/agent fields match NewPipe Extractor's current public
constants. This comparison is source inspection, not evidence of live compatibility.

## Build and signing

- Package `labs.rtensor.music`, version `0.3.8`, versionCode `11`.
- APK signing certificate matches prior previews, SHA-256:
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
- Gradle engine/source/proof digest checks passed and APK engine assets match source.
- The test tones and Python verification helper are development-only, outside the APK.
  No keystore, private DSP implementation or real account data is in the source archive.

## Not verified live

**Three live YouTube songs have not been tested here.** A fresh YouTube request was
rejected by the workspace proxy: `CONNECT tunnel failed, response 403`. `adb devices`
reported no attached Android device; no emulator/system image is installed. No proxy
bypass or remote YouTube relay was used.

Native Google login/consent, live acceptance of the visionOS profiles/attestation,
full playback of `zAiIgYOH4Ys`, real startup latency, device decoders, screen-off/Wi-Fi
behaviour and Bluetooth remain unverified. The deep buffer is a setting, not a
measured result. SABR-only streams remain unsupported and DSP remains off.

The earlier metadata SharedFlow replay limitation remains in unchanged code:
metadata produced without a subscriber is not retained. This task focuses on
playback transport and client fallback, not history/recommendation behaviour.
