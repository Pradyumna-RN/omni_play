# Music — Android first build

Temporary app label: **Music**. The final product name is undecided. The visual
direction follows the approved Margin preview: DM Sans, warm paper, monochrome
artwork, raspberry controls, generous type, and a compact transport.

## What is implemented

- Native Kotlin / Jetpack Compose Home, Search, Saved, Now Playing, Queue and Settings.
- YouTube keyword search, video links, playlist links, and Android Share → Music.
- An independent, on-device yt-dlp source adapter. No ReVanced/Morphe installation,
  account, API key, server, or browser player is required.
- Media3 / ExoPlayer playback through a foreground media-session service: play/pause,
  seeking, previous/next, automatic queue advancement, audio focus, headphone
  disconnect handling, background playback, and notification/lock-screen controls.
- Audio-file playback through Android's document picker, without uploading files.
- On-device saved catalogue items and recent history; device/light/dark appearance,
  listening volume, reduced motion, one Save action, and an animated seek line.
- A native-audio-processor extension point for the future DSP library. DSP is **off**.

No sample songs or made-up recommendations ship in the app. Screenshot fixtures
are confined to tests. Home contains your listening history, not a generated feed.
Provider result and playlist order is retained. Source information appears in
Settings; there is no provider picker on the primary screens.

## YouTube scope and verification

The first adapter supports **public search and accessible recordings**, not a
signed-in YouTube Music account, personalised recommendations, account playlists,
premium-only content, live streams, downloads, or offline streaming. yt-dlp resolves a media URL;
Media3 decodes and plays the audio. It does not embed or patch the official client.
No ad-removal patch is implemented or claimed. Live streaming and advertising
behaviour need verification on the target device and network.

This workspace's egress proxy returns `403 Forbidden` for YouTube requests. A live
search was attempted and stopped at that network boundary. Adapter contract tests
use fixtures; they do not establish live provider compatibility. Do not describe
YouTube playback or ad-free listening as verified until a real-device test passes.
Provider changes, verification challenges and regional restrictions can break
extraction. Errors are shown in the app; there is no fabricated success state.

The library stores stable catalogue identifiers and metadata. Artwork may be
cached by the image loader. Temporary stream URLs and their headers remain in
service memory and expire after three
minutes. Retry resolves a new URL. Your searches and playback requests still go
to YouTube. No cookies or account passwords are collected.

## Build

Requirements: JDK 17+, Android SDK platform 35 / build tools 35.0.0, Python 3.12
on the build machine, and network access to the Android/Maven/PyPI repositories.
Android Studio can import this directory directly. Configure the SDK through
Android Studio, `ANDROID_HOME`, or an ignored `local.properties`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. It supports Android
8.0+ on ARM64 phones and x86-64 emulators. The debug APK is for local testing.
No release signing key is stored here.

```sh
python3.12 -m pip install yt-dlp==2026.10.26
python3.12 -m unittest discover -s tests -v
```

Native screenshots are written by Robolectric/Compose tests to
`app/build/reports/screenshots/`. Their song metadata is an explicit UI fixture,
not the result of a successful live stream. Native library loading, actual audio
output, Android foreground-service behaviour, network transitions, screen-off
playback and Bluetooth controls still require a device/emulator test.

## On a phone

1. Install the debug APK and open Music.
2. In Search, enter an artist/title, or paste a YouTube video/playlist link.
3. Tap a result to resolve and play it in the native player. Allow notification
   permission when requested if you want visible background media controls.
4. Verify pause/resume, seeking, next/previous, screen-off playback and one Save
   action. Then try airplane mode and an unavailable/private video.
5. Use **Already yours** on Home to test local audio independently of YouTube.

## DSP boundary

See `app/src/main/java/labs/rtensor/music/playback/AudioPipeline.kt`.
The future native processor attaches after decoding and before the audio sink.
No private repository code, coefficients, Windows DLL, or Python DSP fallback has
been copied into this app. The Windows binary cannot run on Android: the author’s
core must be ported and built for Android/ARM64, then called through JNI.

Before connecting it, settle the author’s separate distribution terms and review
its dependencies. Agree PCM encodings, sample rates, channel layouts, latency,
clipping/headroom policy and reset behaviour on track changes/seeks. Typical
YouTube music is stereo, so the multichannel downmix alone would not add an effect
to most recordings. EQ/upmix/enhancement are separate, optional DSP features.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for upstream licences.
