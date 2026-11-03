# Current verification: Music 0.3.11 / integrated DSP OTA

The original compiled renderer is bundled inside the existing Music APK. No
separate DSP application or library installation is required. The reusable library
remains available; its implementation source and coefficients stay private. The UI
is unchanged. The normal OTA feed is `RTensorLabs/music-app`.

- **90 app checks and 32 DSP-library checks passed**, with no failures, errors or
  skips. Seven checks execute the actual host JNI library. The app checks include
  OTA selection and upgrade identity for both 0.3.9 and the private 0.3.10 preview.
- `:app:verifyOtaDspBundle` verifies the ARM64 and x86-64 renderer bytes inside the
  Music APK against the supplied private artifacts. Official OTA builds must run
  this task. Contributor builds can still omit the private core.
- Package `labs.rtensor.music`, version `0.3.11`, versionCode `14`; same signing
  certificate SHA-256
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
  Release assets use the installed updater's `music-debug.apk` and `SHA256SUMS`
  names. VersionCode 14 upgrades the previous public and private preview APKs.
- The DSP adapter and native processing implementation are unchanged from the
  preview validation below. Publication removes unneeded native symbols without
  changing processing code. The 630-second reference comparison and sanitizer
  results below apply to that same native implementation.

Supported multichannel PCM is rendered automatically; stereo bypasses DSP. No
Android device is attached, so phone playback, routing and ARM execution still need
device validation. This does not add Apple Music protected-audio access or an Atmos
object decoder. See the release's `ota-validation.json` for package checks.

# Previous verification: private DSP preview, app 0.3.10 / library 0.1.0

Based on Claude's `a33d6bf30d6fe1b9785d781e824a02d85639c22b`. No UI implementation
was changed for the DSP work. The native processing implementation is a separate
private artifact; the public repository contains interfaces and the Android adapter.

## Current validation

- **89 app Android/JVM checks and 32 DSP-library checks passed**, with no failures,
  errors or skips. Seven library checks load and execute the actual host-built JNI
  library. The remaining checks cover format/position contracts, PCM conversion,
  partial writes, seek/reset, EOF/backpressure, format changes and spatializer opt-out.
- In the private source tree, **18 portable-core checks**, **56 existing Python DSP
  checks** and **15 existing native-reference checks** passed. The portable core
  matches the original renderer, including independently reordered speaker positions.
- Native processing/reset performed **zero allocations and frees** in the instrumented
  test. AddressSanitizer/UndefinedBehaviorSanitizer checks passed for processing,
  concurrent reset/process, repeated lifetimes, invalid buffers and overlap handling.
- Three complete generated multichannel recordings passed through the real portable
  core and a host build of the original native desktop core. All output samples and
  SHA-256 digests matched: 180 s / 6 channels, 210 s / 8 channels, 240 s / 16 channels.
  Total source audio: **630 seconds**. These are synthetic PCM fixtures, not streamed
  songs, phone decoding or listening tests.
- **16 JavaScript unit, 43 Chromium engine and 6 isolated proof checks** passed.
  Browser provider/account/attestation responses are fixtures.
- Android ARM64 and x86-64 libraries compiled against API 26; ELF load segments and
  APK ZIP placement meet 16 KiB page alignment. APK/AAR DSP bytes match the separate
  private native build. Bundled engine assets match source.
- Package `labs.rtensor.music`, version `0.3.10`, versionCode `13`; the signing
  certificate remains SHA-256
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.

## Boundaries of this preview

No Android device is attached. Actual Android codec layouts, AudioTrack routing,
ARM runtime output, Bluetooth, OEM effects, startup/seek latency and complete-song
listening need phone validation. JNI execution above is on the host JVM; Android
native binaries were cross-compiled. The test build includes a generated 24-bit,
48 kHz 5.1 FLAC separately for Settings → Import audio → Library playback checks.

The existing native core accepts 48/96/192 kHz. Mono/stereo bypass the DSP; unsupported
multichannel rates/layouts fail. Known decoder positions are required, with FLAC's
standard assignment as a documented fallback. Generic multichannel WAV without a
known layout is not guessed. An unavailable private core in a DSP-enabled build
causes a render error rather than a claimed successful downmix.

The adapter drains latency plus a bounded 50 ms filter tail; gapless/crossfade
certification is outside this preview. Processed output opts out of Android's
spatializer, but device/OEM effects still require validation. DSP rendering is not
bit-perfect hardware output. No Apple Music authentication, protected PCM access,
new streaming source or Atmos object decoder is implemented. PCM channel counts
alone do not establish full Atmos rendering.

The 0.3.10 preview was distributed privately. Music 0.3.11 embeds the compiled core
in its normal public OTA APK, as described above. Public source builds without the
private artifact retain the normal playback path. See [DSP build instructions](dsp/README.md).

# Previous verification: v0.3.9

Reviewed Claude's `73e4cb6cea4ebba3ee240212adb69566eec9a870` update, including
the recommendation changes in `7faea75` and the previous media transport fixes.

## Current results

- **88 Android/JVM checks passed**, with zero failures, errors or skips. This includes
  the existing 18 Compose checks, 12 new motion/gesture checks and 8 recommendation checks.
- **15 JavaScript unit tests**, **43 Chromium engine checks** and **6 isolated proof
  checks** passed against the exact packaged engine. Provider/account responses are fixtures.
- `:app:assembleDebug :app:testDebugUnitTest` completed successfully with JDK 17,
  Gradle 8.11.1 and Android SDK 35. Package `labs.rtensor.music`, version `0.3.9`,
  versionCode `12`. All four engine assets in the APK match the committed source.
- APK signature verification passed with the previous certificate, SHA-256
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
  The APK contains no test audio, Python verification helper or keystore.

## Player finding and fix

A long cover swipe could move backward on release: its fixed one-third-width exit
target was closer to the centre than the user's drag position. Motion-enabled tests
reproduced this before the fix. The cover now continues outward from the actual
release position. Its outgoing position is retained through the crossfade, while
the new cover starts centred. Both next and previous gestures advance exactly once;
short drags and blocked queue-end drags return without skipping.

Two other initial failures were test-harness issues. Android measure/layout must run
between virtual animation frames when checking return navigation. A large-text queue
test must scroll its lazy container before looking for an off-screen row. Neither
required a navigation or layout change in the app.

## Motion coverage

The new `PlayerMotionTest` runs real Compose UI and pointer handlers with Robolectric
native graphics on API 35. Motion cases use an explicitly controlled frame clock.

- Long left/right cover swipes: no direction reversal, one skip, outgoing pose held
  through the fade, incoming cover centred.
- Short and unavailable-next cover swipes: settle back without advancing.
- Track title/control crossfades: outgoing content disappears after settling;
  buffering does not move the play control.
- Timeline: advances with frames while playing, holds when paused, disables during
  an unresolved track change, and a real drag issues one bounded seek.
- Reduced motion: outgoing track/cover content is removed promptly.
- Inline queue: selecting a row passes its original queue index without rebuilding
  playback; a vertical cover swipe scrolls to the queue.
- Mini player: swipe up opens the player; Back restores it; horizontal swipe skips
  once; Next is disabled at the queue end.
- Home/Explore pull-to-refresh: each dispatches once; an in-progress refresh ignores
  another pull.
- A 320 dp screen at 1.5× font scale: transport controls and queued tracks remain
  reachable by scrolling.

Eighteen existing screen captures and motion frames are included in the preview ZIP.
`player-swipe.gif` and `player-crossfade.gif` contain frames drawn from the actual
Android view at controlled clock positions, converted to 25 fps with a final hold.
The tracks/accounts are fixtures; covers are neutral placeholders. These previews
are not a measurement of device frame rate, GPU jank, touch latency or network loading.
The JSON report records test counts, case names and capture details.

Reproduce the new checks with:

```sh
./gradlew :app:testDebugUnitTest --tests labs.rtensor.music.PlayerMotionTest
```

Captures are written to `app/build/reports/animation-checks/`.

## Current limits

No physical Android device is attached and no emulator/system image is installed.
Live Google account selection, YouTube acceptance, real startup/seek latency,
screen-off playback, Bluetooth, hardware decoding, GPU smoothness and haptic feel
remain unverified. The workspace proxy blocks YouTube; this UI task does not
establish that any live YouTube song plays. No proxy bypass or relay was used.

The full-length synthetic HTTP/cache/Media3 extraction regressions passed again in
the current Android suite. Independent FFmpeg decoding below was performed for
v0.3.8 and was not repeated for this UI-only fix. The earlier metadata SharedFlow
replay limitation remains in unchanged code. SABR-only streams remain unsupported;
DSP remains off.

# Previous verification: v0.3.8

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
