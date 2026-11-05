# Music for Android

Music is a native Android player from RTensorLabs, built around a simple goal:

> **Find what you enjoy, then play the best available matching recording in one app.**

The app keeps the current warm-paper interface, DM Sans typography, original
provider artwork and raspberry controls. Android 11+ is the supported baseline.

## Our goals

1. **Help you discover music.** Let you choose YouTube Music or Spotify for
   recommendations, albums, playlists and the songs that come next.
2. **Choose a suitable audio source.** Find the same recording among your files
   and connected playback sources, checking the artist, title, duration and edition.
3. **Aim for the best available quality.** Prefer a matching surround recording
   when available, then suitable lossless audio. Actual availability depends on
   the recording, provider, account, device and working integration.
4. **Use the original Downmix Renderer inside the app.** Keep stereo unchanged
   and process supported multichannel input through DMR, with Original/Processed
   comparison. DMR’s rendered output is stereo; multichannel describes its input.
5. **Keep everyday listening simple and fast.** Prioritise reliable Play/Pause,
   seeking, skipping, queue behaviour, imports and responsive scrolling.

Recommendation choice and audio source are independent. A Spotify recommendation
can play from a matching local file, connected provider or YouTube. Its catalogue
entry alone does not establish lossless or Dolby Atmos playback.

## How our goals compare with other apps

These are simple descriptions of each app’s focus. Many goals overlap; the table
does not claim that a feature belongs exclusively to one app or that Music has
already completed all of its goals.

| App | Main goal in simple words |
| --- | --- |
| [Crates](https://crates.app/features/) | Bring your music collections together and organise them with tags, ratings, smart collections and device sync. |
| [Spotify](https://www.spotify.com/) | Help you discover and listen to its catalogue through personalised recommendations and playlists. |
| [YouTube Music](https://music.youtube.com/) | Help you discover and listen to music from YouTube’s catalogue, including official songs, live performances and covers. |
| [Apple Music](https://www.apple.com/apple-music/) | Offer a subscription music catalogue, including lossless and Dolby Atmos listening in supported setups. |
| [Spotube](https://github.com/team-spotube/spotube) | Let users combine music metadata, playlists and audio sources through plugins on desktop and mobile. |
| [Tryptify](https://github.com/tryptz/Tryptify) | Offer Android hi-fi listening with multiple sources, detailed DSP/EQ controls and visual feedback. |
| **Music — this app** | **Combine a choice of discovery services with the best available matching audio and the original DMR, inside a simple Android listening app.** |

For Music, the practical priorities are correct recording matches, clear audio
quality information, reliable surround processing and smooth everyday playback.
A combined library is part of that experience. The current UI can stay while
those features improve.

## Quick guide

1. **Install or update.** Get the [current preview release](https://github.com/RTensorLabs/music-app/releases/tag/v0.3.33).
   Existing installations can use **Settings → About → Check updates**.
2. **Choose recommendations.** Open **Settings → Playback → Recommendation source**.
   YouTube Music is the default. Selecting Spotify starts its sign-in; the choice
   is saved after verification. See the [Spotify guide](SPOTIFY_RECOMMENDATIONS.md).
3. **Search or browse.** Home, Explore, Mixed for you and automatic Up next follow
   the selected recommendation service. Search currently uses YouTube.
4. **Add your files or playback sources.** Use **Settings → Sources → Import audio**
   for a file or folder. **Playback sources** contains provider and personal-server
   connections. Supported files can be stereo, lossless or multichannel.
5. **Compare multichannel playback.** For supported input, the player offers
   **Original** and **Processed**. Stereo plays unchanged. The channel monitor
   appears only after enabling **Settings → About → Developer options**.
6. **Keep control of your queue.** Individual songs can start provider radio;
   albums, playlists and Play all preserve their explicit order. Changing the
   recommendation source preserves the current song, position and manual queue,
   replacing only future automatically generated tracks.

## What is ready and what is still a goal?

| Area | Current position |
| --- | --- |
| Android player, Saved, history and local imports | Implemented, with automated regression coverage. |
| YouTube Music recommendations | Implemented, with provider radio rather than history copied into Up next. |
| Spotify recommendations and collections | Experimental metadata integration. Public catalogue requests and browser tests pass; signed-in personalisation and phone performance still need verification. |
| Local multichannel audio and DMR | Integrated. Host tests cover the real decoder/renderer path, stereo bypass and comparison switching. |
| Apple Music and TIDAL account playback | Experimental. Live account access and delivered audio quality still need device validation. |
| Qobuz and personal music libraries | Available through configured playback-source connections. |
| Android Auto | Home, Saved and Recent (hidden while history is off), search and playback controls in the car, with automated regression coverage. Not yet checked on a car or the Desktop Head Unit. Songs chosen in the car are added to history and continue with radio only while Music is open on the phone. |
| Consistent lossless/multichannel playback across providers | A goal being validated, dependent on available matching recordings and working provider access. |

The player separates the **audio provider** from its format: Apple Music AAC,
ALAC/FLAC lossless, supported surround audio, or a YouTube fallback. A Spotify
recommendation can therefore play from Apple Music without changing its discovery
identity. The provider name updates if playback falls back.

The player describes the format actually received. Stereo lossless remains
stereo. Supported E-AC-3/JOC decoding currently supplies its standard 5.1/7.1
surround bed to DMR; this does not recover the full Atmos object scene. Discovery
metadata, a successful sign-in or an album badge does not prove the audio format.
See [verification and current limits](VERIFICATION.md).

The current UI builds on **Signal**, with compact Home recommendations, rounded
player artwork and a centred round Play button. The default accent follows the
cover; fixed accent choices are available in Settings. Home separates the last
song, recommendations shaped by listening, and new-to-history songs. Collections
stay in Explore. Android Auto shares the phone's playback engine, library and
provider routing. See [verification and current limits](VERIFICATION.md).

[Shareable layout review](https://music-layout-review.chalkybongo1.chatgpt.site):
smaller Home covers and three player options. This is a design prototype; it
does not play audio or change the Android app’s current player layout.

## Architecture

Music has three main paths: **discover a recording**, **choose its audio source**,
and **decode/render it inside the Android app**. The diagrams describe the current
implementation; experimental adapters and unfinished integrations are labelled.

### App and playback control

```mermaid
flowchart TB
    UI["Compose interface<br/>Home / Explore / Library<br/>Player / Settings"]
    VM["MusicViewModel<br/>Screen state and queue actions"]
    UI <-->|"Actions / state"| VM
    VM <--> STORE["Local library and settings<br/>Saved songs / history / files"]
    VM <--> YT["YouTube data layer<br/>Catalogue and stream URLs"]
    VM --> RADIO["RadioQueue<br/>Provider recommendations / cancellation"]
    VM <--> RECS["Recommendation source<br/>YouTube Music / Spotify"]
    RADIO -->|"Song radio"| RECS
    RECS --> YT
    RECS <--> SPOTIFY["Spotify discovery<br/>Home / radio / albums / playlists"]
    SPOTIFY --> MATCH["Lazy recording match<br/>Title / artist / duration / edition"]
    MATCH --> SELECT
    RADIO -->|"Append recommendations"| CONTROL
    YT <--> ENGINE["YouTube client<br/>Catalogue and stream requests"]
    ENGINE <--> CLOUD["YouTube / YouTube Music"]
    VM <-->|"Commands / player events"| CONTROL["MediaController"]
    CONTROL <--> SERVICE["Playback service<br/>MediaLibrarySession and notification"]
    CAR["Android Auto / voice search"] <-->|"Browse / search / controls"| SERVICE
    SERVICE <--> CARLIB["CarLibrary<br/>Resolve current library IDs"]
    CARLIB <--> STORE
    SERVICE --> SELECT["Source routing<br/>Match / rank / pin a recording"]
    STORE -->|"Imported files"| SELECT
    YT -->|"YouTube fallback"| SELECT
    SOURCES["Connected playback sources<br/>TIDAL account: experimental<br/>Provider servers / addons"] --> SELECT
    SELECT --> PLAYER["Media3 ExoPlayer<br/>Buffer / seek / stream cache"]
    PLAYER --> AUDIO["Decode and PCM rendering<br/>See audio flow below"]
    AUDIO --> OUTPUT["Android audio output<br/>Phone / wired / Bluetooth / USB"]
```

The user chooses **YouTube Music or Spotify** under Settings → Playback →
Recommendation source. Spotify supplies discovery metadata and playlist entries;
audio continues through Music’s existing playback sources. Its account connection
and personalised results still need device validation.

The ViewModel uses local listening history only to choose seeds for Mixed for you.
YouTube candidates retain the existing on-device ranking; Spotify pools retain
provider order. Up next comes from the selected provider through `RadioQueue`, preserving provider order
and excluding already-queued songs. Individual selections seed radio; explicit
albums/playlists and Play all keep their lists. History is not an autoplay pool.
The catalogue item keeps its YouTube or Spotify discovery identity, artwork
and history when another source supplies the audio. Matching checks title, artist, duration and edition.
Available candidates rank **JOC first → discrete lossless multichannel → lossless
stereo by sample rate/bit depth**, with local files competing against connected
sources. An explicitly imported local track plays its own file directly.

Source selection happens before the physical MediaSource is created. Seeks retain
that source and encoding. A failed or contradictory provider format triggers a
bounded fallback through a new source at the current position. Rendition rejection
is independent of rotating URLs; byte-cache reuse across changing paths requires
an explicit immutable-file identity. Adaptive streams keep their representation
pinned and bypass the progressive-file cache.

**ExoPlayer plays the audio.** Provider requests and source selection run off the UI/audio
threads. Software decoding uses a worker; PCM rendering runs in Media3's audio
path. Player events and `AudioPipeline` reports update the UI, while a separate
position StateFlow updates the timelines without rebuilding the whole screen.
The expandable channel monitor has its own level StateFlow; metering is disabled
when collapsed, paused or outside the foreground.

### Audio signal flow

```mermaid
flowchart TB
    INPUT["Selected file or stream"] --> DEMUX["Media3 demux / manifest parsing<br/>Actual encoded audio format"]
    DEMUX -->|"ALAC / AC-3 / E-AC-3 / JOC<br/>Standard decoder"| FFMPEG["FFmpeg decoder<br/>24-bit ALAC or original 5.1/7.1 bed"]
    DEMUX -->|"Other supported codecs<br/>Platform fallback"| PLATFORM["Android / Media3 decoder"]
    PCM["Decoded PCM<br/>Original channel-layout information"]
    FFMPEG --> PCM
    PLATFORM --> PCM
    PCM --> CHANNELS{"PCM route?"}

    CHANNELS -->|"Mono or stereo"| BYPASS["Unchanged PCM<br/>No app DSP or resampling"]
    CHANNELS -->|"Known multichannel layout<br/>DMR available"| ADAPT["DownmixAudioSink<br/>Float + rate adapter"]
    CHANNELS -->|"Unsupported processing<br/>or unknown layout"| ORIGINAL["Android delegate<br/>Original PCM if supported"]
    ADAPT --> MODE{"Mode?"}
    MODE -->|"Original"| FRONT["DMR comparison<br/>Front L/R only<br/>Shared gain and delay"]
    MODE -->|"Processed"| DMR["Private DMR<br/>Speaker downmix"]
    FRONT --> STEREO["Two-channel rendered PCM"]
    DMR --> STEREO
    STEREO --> SINK["DefaultAudioSink<br/>Android AudioTrack"]
    BYPASS --> SINK
    ORIGINAL -->|"If supported"| SINK
    PCM -.->|"Input levels"| METER["Read-only PCM meters<br/>Absolute RMS / peak dBFS"]
    STEREO -.->|"Rendered levels"| METER
    BYPASS -.->|"Unchanged levels"| METER
    METER -->|"Approx. 20 Hz / panel open"| PANEL["Player channel monitor<br/>Actual source positions + output"]
    SINK --> DEVICE["Active Android output device"]
```

The adapter converts known multichannel **44.1/88.2/176.4 kHz → 48/96/192 kHz**
for the core. Stereo bypasses that conversion. Music uses unity preamp for the surround path, retaining the original core's peak
limiter instead of the previous fixed −14 dB cut. Stereo bytes remain unchanged.
Original and Processed share the
stream lifecycle and switch with a fade, without loading another recording.
The private adapter explicitly disables synthetic upmixing. The standard decoder
feeds its original 5.1/7.1 bed to DMR; no invented side, rear or height speakers,
HRIR convolution or app binaural stage is added.

The private DMR is bundled into integrated APKs through JNI; there is no separate
DSP app to install. Its implementation and coefficients stay outside this public
repository. **Multichannel here describes the decoded input:** processed output is two-channel PCM. Physical output routing remains Android's
responsibility. Android's mixer/hardware may convert
audio, so stereo bypass does not establish system-wide bit-perfect output.

Unsupported layouts use the Android delegate only when it accepts the format.
Other failures are classified by decoder, layout, DSP or output stage and reported
back to the player.

Music uses FFmpeg's standard E-AC-3/JOC surround-bed decode. The experimental
scene decoder is not registered or packaged, and its setting is removed.
[PCM/DSP integration](dsp/README.md) and [provider status](PROVIDER_PLAYBACK_STATUS.md)
describe the current route. Decoder research remains in its standalone module.

See [recommendation setup and test boundaries](SPOTIFY_RECOMMENDATIONS.md).

### Accounts, storage and updates

Accounts connect in the app. Session credentials are encrypted using Android
KeyStore, while preferences, Saved and listening history remain on the device.
Account authorization is kept separate from requests to audio/CDN hosts.

Apple Music and TIDAL account playback remain experimental. Their availability,
account access and delivered quality require testing on supported devices. Qobuz
and personal-library playback use configured source connections. These options
do not establish that every recording is available in lossless or multichannel.

### Provider playback at a glance

```mermaid
flowchart TB
    SETTINGS["Settings / Playback sources"] --> ACCOUNT["Connect an account or library"]
    ACCOUNT --> MATCH["Find the same recording<br/>Artist / title / duration / edition"]
    MATCH --> AVAILABLE{"Matching audio available?"}
    AVAILABLE -->|"Yes"| FORMAT["Select a rendition<br/>Check its actual audio format"]
    AVAILABLE -->|"No"| FALLBACK["Try another configured source<br/>or report no verified match"]
    FORMAT --> DECODE["Decode inside Music"]
    DECODE --> ROUTE{"Decoded channels?"}
    ROUTE -->|"Mono / stereo"| STEREO["Unchanged audio"]
    ROUTE -->|"Supported multichannel"| DMR["Original DMR<br/>Original / Processed comparison"]
    STEREO --> OUT["Android audio output"]
    DMR --> OUT
```

Source matching and format checks protect against playing a different edition
or presenting a stereo stream as multichannel. Provider availability and real
account playback remain subject to the validation limits above.

```mermaid
flowchart LR
    RELEASE["GitHub release<br/>APK + checksums"] --> UPDATE["GitHubUpdater<br/>Size + SHA-256"]
    UPDATE --> IDENTITY["Verify app ID<br/>Version + signature"]
    IDENTITY --> INSTALL["Android installer<br/>User confirms"]
    INSTALL --> APP["Music updated<br/>App data retained"]
```

Updates require the same package and signing certificate and a newer version
code. The Android installer asks the user to confirm installation.

### Code map

| Component | Responsibility |
| --- | --- |
| [MainActivity](app/src/main/java/labs/rtensor/music/MainActivity.kt), [UI](app/src/main/java/labs/rtensor/music/ui/) | Compose screens, system account/file pickers and playback controls. |
| [MusicViewModel](app/src/main/java/labs/rtensor/music/MusicViewModel.kt) | Observable state, catalogue requests, queue commands, history, radio and the independent progress clock. |
| [YouTubeRuntime](app/src/main/java/labs/rtensor/music/data/YouTubeRuntime.kt), [youtube-engine](youtube-engine/) | YouTube catalogue, account state and audio-stream resolution. |
| [Auth](app/src/main/java/labs/rtensor/music/auth/), [YouTubeLoginActivity](app/src/main/java/labs/rtensor/music/YouTubeLoginActivity.kt) | Google/YouTube account connection and sign-in screens. |
| [Playback](app/src/main/java/labs/rtensor/music/playback/) | MediaSession/ExoPlayer, source factories, cache/range handling, fallback, background playback and notification. |
| [Playback sources](app/src/main/java/labs/rtensor/music/sources/) | TIDAL account adapter, server/addon connectors, manifest validation, matching, ranking and rendition/file identities. |
| [Spotify catalogue](app/src/main/java/labs/rtensor/music/spotify/), [browser bridge](app/src/main/assets/spotify/catalogue.js) | Spotify discovery, recommendation feed/radio, paged albums/playlists and lazy recording matching. |
| [RadioQueue](app/src/main/java/labs/rtensor/music/playback/RadioQueue.kt) | Provider-supplied Up next, cancellation of stale requests, duplicate exclusion, and retry status. |
| [Local data](app/src/main/java/labs/rtensor/music/data/) | Library/history, imported master matching, account storage, metadata and on-device recommendation helpers. |
| [decoder-ffmpeg](decoder-ffmpeg/) | Software ALAC and standard AC-3/E-AC-3 surround-bed decoding before the PCM sink. |
| [decoder-openjoc](decoder-openjoc/) | Standalone experimental reconstruction research; excluded from Music. |
| [dsp](dsp/) | PCM/rate/layout adapter, original private DMR JNI boundary, Original/Processed comparison and diagnostics. |
| [Updates](app/src/main/java/labs/rtensor/music/updates/) | GitHub release discovery and verified in-place APK updates. |

## Current release

[Music 0.3.33](https://github.com/RTensorLabs/music-app/releases/tag/v0.3.33)
adds Android Auto and voice search, cover-derived or fixed accent colours, a
refined player and playback/reconnection fixes. The optimized release targets
Android 11+, ARM64 and x86-64, and stays below 100 MB.

Automated app checks cover the player, navigation, account recovery, car browsing
and queue selection. Existing DSP and browser results remain baseline evidence;
they are not new phone performance or subscriber-playback measurements. See
[verification](VERIFICATION.md) and the release assets for completed counts,
source commit, APK checksums and packaging evidence.

## Build

Use JDK 17+, Android SDK 35/build tools 35.0.0. Android 11+ (API 30) and a recent
Android System WebView are required. Support is device-independent; integrated
native builds currently include ARM64 and x86-64. Decoder and output capabilities
are checked at runtime rather than assumed from a phone brand. The app builds in
Android Studio using the committed client bundles:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The APK is `app/build/outputs/apk/debug/app-debug.apk`. Gradle checks the source and
bundle digests. After editing the JavaScript client, rebuild its bundles first:

```sh
npm ci --prefix youtube-engine
npm run build --prefix youtube-engine
npm test --prefix youtube-engine
BROWSER_EXECUTABLE=/usr/bin/chromium npm run test:browser --prefix youtube-engine
```

Native screenshots are generated in `app/build/reports/screenshots/`. Screenshot
recordings and browser responses are test fixtures, excluded from the product feed.

## Releases and OTA signing

**Settings → About → Check updates** checks published GitHub releases, including
previews. It verifies the download size, SHA-256, package name, higher versionCode and installed
signing certificate before opening Android's installer. Android permission and
installation confirmation remain required. Updates never install silently.

Releases need `music-debug.apk` (or `music-release.apk`) and a `SHA256SUMS` asset
naming that exact APK. Tags use semantic versions and Android versionCode must
increase. Drafts and older builds are ignored. v0.1 has no updater; install a newer
APK manually once. v0.2 and later can use the in-app update flow.

This APK retains the certificate used for v0.1/v0.2 to preserve saved data on upgrade.
Future builds must use the same private signing key. Back it up privately; no key,
password, account cookie, token or local SDK configuration belongs in this public
repository. Checksums supplement Android's certificate validation.

## DSP

DSP is optional. `playback/AudioPipeline.kt` selects the Android sink wrapper when
the separate private native artifact is supplied. The `dsp` module contains its
public interfaces, JNI owner, speaker mapping and Media3 adapter. The private
Downmix Renderer source and coefficients remain outside this repository. Ordinary
source builds retain the normal audio path; stereo bypasses the renderer even in
DSP-enabled builds. See [the DSP module](dsp/README.md).

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
