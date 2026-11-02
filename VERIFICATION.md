# v0.3.0 verification

- Native Android build and bundled-engine/source/proof digest checks: passed.
- Android/JVM suite: **25 tests**, no failures or skips.
  - 11 Compose checks: Home/Explore/Saved, persistent search submission and clearing,
    provider cards, albums, original queue identity, one Save action, compact/dark
    player, ordinary sign-in action, copyable errors and verified-update installer.
  - 9 account/update checks: encrypted session reload/tamper rejection, old TV-blob
    rejection, login-origin constraints, account-cookie isolation from media/proofs,
    network boundaries, version ordering, package/signature/downgrade checks and
    trusted update selection/checksum filenames.
  - 3 catalogue-link and 2 saved-library checks.
- JavaScript mapping/configuration suite: **12 tests passed**.
- Real Chromium using the exact engine bundle: **20 checks passed** with synthetic
  provider HTTP: actual upstream search parsing/artist data, largest artwork, current
  bootstrap versions, range-probed streams, signature timestamp, upstream signature/n
  extraction and Worker evaluation, provider order, Explore, WEB accounts, SAPISIDHASH
  requests, actual rejection messages, SABR-only errors, failed CDN probes, client/account-specific
  proof bindings and current WEB attestation challenge, no TV/OAuth requests, and isolation from the account bridge.
- Exact proof bundle in a separate Chromium page: **6 checks passed** using a fixture
  VM/integrity response: BgUtils protocol, minter caching, client/account binding outputs,
  isolation from account access and rejection without fabricated success.
- Native Home, Explore, search, sign-in and error/player screenshots inspected;
  Settings volume slider removed.
- APK package `labs.rtensor.music`, version `0.3.0`, versionCode `3`.
- APK signature must match v0.1/v0.2; verified SHA-256:
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
- Packaged engine/proof assets checked against the source assets. No Python runtime,
  private DSP source, keystore or real account data belongs in the release archive.

## Not verified live

A YouTube probe was rejected by this workspace's outbound proxy:
`Tunnel connection failed: 403 Forbidden`. No network-policy bypass was used. The
browser tests use synthetic responses and a fixture VM; they do not establish live
provider compatibility, successful Google sign-in or accepted live attestation.

Real-device checks remain: sign in on Google's page and select the intended channel;
load its personalised Home and Explore; search and play a recording, including the
original failing one; confirm screen-off/Bluetooth playback; and exercise Android's
update permission/package installer with the same signing identity. If a stream
fails, Copy details preserves its stage/code without exposing credentials.

SABR-only media is explicitly unsupported. DSP is off. No universal playback,
non-subscription access, live attestation or advertising guarantee is claimed.
