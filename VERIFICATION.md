# v0.3.4 verification

- Native Android build and bundled-engine/source/proof digest checks: passed.
- Android/JVM suite: **53 tests**, no failures or skips.
  - 17 Compose checks: Home/Explore/Saved, persistent search submission and clearing,
    provider cards, albums, original queue identity, one Save action, compact/dark
    player, ordinary sign-in action, copyable errors and verified-update installer,
    incomplete YouTube connection banner, combined account-card sign-out, floating
    mini-player controls and Settings jump-bar navigation.
  - 10 account/update checks: encrypted session reload/tamper rejection, old TV-blob
    rejection, login-origin constraints, account-cookie isolation from media/proofs,
    network boundaries, version ordering, package/signature/downgrade checks and
    trusted update selection/checksum filenames, and backwards-compatible captured
    player metadata with strict URL/flag validation.
  - 6 synthetic redirect tests: allowed statuses/methods, relative paths, origin
    isolation, unsafe destinations, bounded loops, no-follow request classes and
    cancellation across a chain.
  - 2 actual loopback Media3 HTTP tests: negotiated headers/signed query retention
    and rewritten-media HTTP failure reporting with the original catalogue key.
  - 4 GVS/artwork checks: full multi-range reads, absolute seek ranges, truncated
    audio rejection and provider rendition URLs.
  - 3 retry checks: one forced reopen, repeated rejection remains an error, and
    later range failures preserve catalogue identity.
  - 6 Google identity checks: signed response verification, tamper rejection,
    expiry/audience/issuer/nonce/presenter checks, supported algorithms and keys,
    separate encrypted profile storage, and safe Google login-hint encoding.
  - 3 catalogue-link and 2 saved-library checks.

## Unchanged engine checks

The engine, proof bundle, auth implementation and media transport are unchanged from
v0.3.3. These JavaScript/Chromium results were recorded for v0.3.3 and were not rerun
for this UI update; the current Gradle build rechecks engine/source/proof digests.

- JavaScript mapping/configuration suite: **12 tests passed**.
- Real Chromium using the exact engine bundle: **25 checks passed** with synthetic
  provider HTTP: actual upstream search parsing/artist data, largest artwork, current
  bootstrap versions, playback-sized query-range validation, signature timestamp, upstream signature/n
  extraction and Worker evaluation, provider order, Explore, WEB accounts, SAPISIDHASH
  requests, actual rejection messages, SABR-only errors, failed CDN probes, client/account-specific
  proof bindings and current WEB attestation challenge, no TV/OAuth requests, and isolation from the account bridge.
  Additional regressions confirm a captured account despite homepage HTTP 302,
  reject invalid accounts, report guest browser redirects without fake success, and
  execute the exact Android login-capture expression against representative page metadata.
  A forced retry must send runtime proofs on its first player attempt.
- Loopback HTTP tests enable the JDK-only restricted-header flag so Robolectric
  models Android HttpURLConnection accepting Origin; this test setting is not in the APK.
- Exact proof bundle in a separate Chromium page: **6 checks passed** using a fixture
  VM/integrity response: BgUtils protocol, minter caching, client/account binding outputs,
  isolation from account access and rejection without fabricated success.

## APK and UI review

- Native Home, Explore, search, sign-in, Settings, mini-player and error/player screenshots inspected;
  Settings text is larger with stronger contrast; its icon is 8 dp from the right.
  Larger provider artwork URLs have an original-image fallback. No grayscale filter.
- Reduce motion disables the new list, card, artwork, size and screen transitions,
  as well as frame-driven progress interpolation. Account copy distinguishes choosing
  a Google identity from connecting the YouTube Music session.
- APK package `labs.rtensor.music`, version `0.3.4`, versionCode `7`.
- APK signature must match prior releases; verified SHA-256:
  `43dffb7aa7b922220c9063c0f2ee9126a30c7d663e1971c8f214bdd2ac7b571f`.
- Packaged engine/proof assets checked against the source assets. No Python runtime,
  private DSP source, keystore or real account data belongs in the release archive.

## Not verified live

**The phone-reported live playback HTTP 403 is not claimed resolved.**

A fresh YouTube probe was rejected by this workspace's outbound proxy:
`Tunnel connection failed: 403 Forbidden`. No network-policy bypass was used. The
browser tests use synthetic responses and a fixture VM; they do not establish live
provider compatibility, successful Google sign-in or accepted live attestation.

Native Google account selection, provider consent and production JWKS retrieval
have not been tested on a device. The identity tests use generated RSA fixtures;
no real user token or credentials are used.

Real-device checks remain: sign in on Google's page and select the intended channel;
load its personalised Home and Explore; search and play a recording, including the
original failing one; confirm screen-off/Bluetooth playback; and exercise Android's
update permission/package installer with the same signing identity. If a stream
fails, Copy details preserves its stage/code without exposing credentials.

SABR-only media is explicitly unsupported. DSP is off. No universal playback,
non-subscription access, live attestation or advertising guarantee is claimed.
