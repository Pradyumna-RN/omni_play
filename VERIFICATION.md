# First-build verification

- `:app:assembleDebug`: passed.
- `:app:testDebugUnitTest`: 11 passed, no failures or skips.
  - 6 Compose/Robolectric UI checks, including navigation, one Save action,
    play/pause callback, repeated playlist items, light/dark player and a 320dp viewport.
  - 3 YouTube link normalization/validation checks.
  - 2 on-device library serialization checks.
- Python adapter contract suite: 5 passed.
- APK signature verification: passed (debug certificate, APK signature scheme v2).
- Native screenshots inspected at 390dp and 320dp.

The tests exercise real Compose views but use fixture song metadata and no live
stream. They do not verify Android audio output or the embedded Python interpreter
on a physical device.

A live YouTube search was attempted. The workspace's outbound proxy rejected the
request with `Tunnel connection failed: 403 Forbidden`. No proxy bypass was used.
YouTube streaming, advertising behaviour, media controls with the screen off,
Bluetooth, and service lifetime still need a real-device/emulator test.

The bundled yt-dlp extractor is 2026.10.26. Public search, links and playback are
implemented, but compatibility with the current YouTube service is unverified
from this environment. No personalised recommendation feed or DSP effect is enabled.
