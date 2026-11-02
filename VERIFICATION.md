# v0.2.0 verification

- Android debug APK build and bundled-client/source digest check: passed.
- Android/JVM suite: **20 passed**, no failures or skips.
  - 9 native Compose UI checks: navigation, compact/dark player, one Save action,
    repeated playlist entries, Google sign-in action, verified-update install action,
    and provider-feed → album → original track-list playback wiring.
  - 6 account/update checks: encrypted credential reload/tamper rejection, native
    HTTP origin boundaries, version ordering, package/signature/downgrade rejection,
    exact checksum filenames and trusted release selection.
  - 3 catalogue-link checks and 2 saved-library checks.
- JavaScript mapping suite: **4 passed**.
- Real Chromium, exact bundled client: **11 checks passed** with synthetic provider
  responses: startup, upstream search parser, audio URL resolution, provider and
  duplicate order, device-code events, credential storage callback, authenticated
  feed headers, HTTP diagnostics, isolated player worker, expired-token refresh
  after restoring a session, and sign-out clearing credentials.
- Native screenshots inspected for sign-in and update controls.
- APK signature verified. Certificate SHA-256 matches v0.1.0:
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
- APK package `labs.rtensor.music`, version `0.2.0`, versionCode `2`.
- Packaged JS assets match source assets. No Chaquopy/Python runtime remains.

## Not verified live

A fresh YouTube network probe was rejected by this workspace's outbound proxy:
`Tunnel connection failed: 403 Forbidden`. No network-policy bypass was used.
The browser tests use fixtures, not a live Google account or successful live stream.
Actual Google approval, personalised YouTube Music feed, current stream availability,
advertising behaviour, Android KeyStore hardware behaviour, screen-off audio,
Bluetooth and an actual Android package installation still need a phone test.

The updater is implemented and its selection/validation/identity rules are tested.
The OS install-permission and package-installer flow is wired to the UI but is not
claimed as end-to-end tested on a device. v0.1 has no updater; install v0.2 manually
once. Future updates require a higher Android versionCode and the same signing key.
DSP is off. No private DSP implementation is included.
