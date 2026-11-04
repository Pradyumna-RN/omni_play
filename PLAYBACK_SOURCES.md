# Playback sources

Music keeps YouTube discovery, artwork and queue identity. A playback source can
supply the matching recording without replacing recommendations or opening a
second player. No public download service, provider cookie, app secret or content
key is shipped in the APK.

## Options in Settings → Playback → Playback sources

| Option | What it connects to | What you need |
| --- | --- | --- |
| TIDAL account (experimental) | Native browser sign-in and account playback requests | Your TIDAL subscription; live account playback remains unverified |
| Provider account server | An implementation of the account-gateway contract below, for Apple Music, TIDAL, Qobuz or a personal library | A compatible server and whatever account access that server requires |
| Audio addon | The HTTP search/stream addon protocol | Your addon URL and optional server bearer token |
| TIDAL HiFi server | A user-hosted [HiFi API](https://github.com/binimum/hifi-api) | Your server, configured with your TIDAL account |
| Atmos & lossless folder | Files you import through Android's folder picker | Read access to your own files; no subscription needed |

**Native Apple Music and Qobuz account playback are not implemented.**
The new direct TIDAL account path is experimental and has not been tested against
a subscriber account. It uses browser PKCE and a public native client identifier,
not an RTensorLabs developer registration. No client secret is shipped.
 The provider-account option is a gateway client, not MusicKit
or a bundled provider backend. Entering a provider's normal website URL will not
connect it. The addon client supports clear full-track streams; addons requiring
their original app's private signing credentials or encrypted hybrid plans are
unsupported.

Account requirements belong to the source. Installing an addon does not turn a
subscription catalogue into a free catalogue. Imported files and servers serving
music you own are independent options. Server URLs and bearer tokens are encrypted
with Android KeyStore in app-private storage, excluded from Android backups.
Authorization is sent only to the configured server, never the media CDN.

## Selection and playback

Candidates must match normalized title and artist, preserving edition tags such
as live/remaster/clean, and agree within four seconds when duration is known.
Artist matching is deliberately conservative: uncertain matches keep the original
YouTube recording. Filenames containing “Atmos” are not proof of Atmos.

The player ranks an explicit E-AC-3 JOC candidate first, then the available
discrete lossless multichannel recordings, then lossless stereo by sample rate
and bit depth, then other multichannel formats.
Local masters compete with connected sources. The
actual encoded format and decoded speaker count are shown after loading: a server's claimed FLAC label never
overrides a decoder reporting AAC. Codec, known sample-rate and channel-count
contradictions reject the representation rather than silently accepting a downgrade. A failed remote source is excluded for that
queue item; playback recreates a separate source at the same position and tries
the next candidate, then local/YouTube fallback. Signed links and different
representations never share cached byte ranges. Recently resolved next-track
candidates are retained briefly to reduce repeated source requests.

Stereo bypasses DMR without app-side resampling, upmix, gain or format conversion.
Android's output mixer, Bluetooth and hardware may still convert it: this is not
a system-wide bit-perfect promise. Original/Processed controls appear only for
supported decoded multichannel PCM. DMR accepts up to 16 channels at 48/96/192 kHz
(the adapter converts multichannel 44.1/88.2/176.4 kHz to those rates) and renders stereo; Original uses the original renderer's front-L/R comparison.

An Atmos codec badge describes the source codec, not a verified object render.
Android must actually decode multichannel PCM for DMR to process it. A stereo
decode cannot be used as an Atmos-to-DMR route: the private renderer rejects it
and a remote source falls back to the next candidate. Protected provider players do not
automatically expose raw PCM to this app. Device decoding and commercial account
playback require validation with the user's actual source and phone.

## Direct TIDAL and experimental JOC

TIDAL sign-in opens the system browser. Tokens and pending PKCE state are sealed
with Android KeyStore; API bearer tokens stay on TIDAL's account/API hosts. Media
URLs are fetched separately without those tokens. A failed refresh asks for
reconnection instead of obtaining an app secret from another service.

Search matches use the account's actual country. Lossless playback requests the
full recording. Advertised Atmos tracks also request an EAC3_JOC manifest through
`/v2/trackManifests/{id}`; protected or downgraded manifests are excluded. Native
selection has a six-second budget and retains candidates found before a later
edition fails or times out. A valid sign-in alone is not proof of quality availability.

Settings → Playback → Scene decoding (experimental) enables OpenJOC for the next
track load. Reload a clear 48 kHz JOC file to compare its 7.1.4 reconstruction via
Original/Processed/Spatial. With it disabled, the existing FFmpeg bed decoder is
used. Actual height inputs disable the DMR adapter's synthetic height upmix.
OpenJOC is an independent experimental reconstruction with upstream fidelity
limits; it does not recover original authored stems or claim Dolby equivalence.

## Account gateway contract, version 1

The base URL must be HTTPS, without userinfo, query or fragment. A personal path
is allowed. Optional `Authorization: Bearer <server token>` applies to API requests
only. Redirects are disabled for API calls.

`GET {base}/v1/capabilities`:

```json
{"version":1,"name":"Personal library","fullTracks":true,"providers":["apple_music","tidal","qobuz","library"]}
```

`POST {base}/v1/resolve` receives a `track` object (YouTube catalogue ID, title,
artist, duration in milliseconds), `provider`, and `priority:["atmos","lossless"]`.
Respond with available full-track recordings:

```json
{"streams":[{"id":"recording-and-edition-id","provider":"qobuz","title":"Example","artist":"Artist","durationMs":200000,"url":"https://your-cdn.example/full.flac","mime":"audio/flac","sampleRate":192000,"bitDepth":24,"channels":2,"atmos":false}]}
```

`mime` describes the encoded audio; optional `transportMime` describes a DASH/HLS
container. A stream URL must work without forwarding account cookies, arbitrary
headers or content keys. Preview/shortened, encrypted and DRM-key responses are
rejected. Responses are limited to 1 MB and 12 stream candidates per gateway.
Selection requests run off the UI/audio threads and have a 4.5-second budget per
connection. There are at most four saved connections.

## Addon and HiFi protocols

Addons expose `GET /manifest.json`, `GET /search?q=<artist title>&quality=hi_res&atmos=auto`
returning `tracks`, and `GET /stream/{escaped-id}?quality=hi_res&atmos=auto` returning
an ordinary URL plus format metadata. Search metadata is retained when a stream
response contains only transport details. `format: "dash"` or `"hls"` selects the
matching Media3 source. No borrowed native-app HMAC/signing key is used.

HiFi uses `/search/?s=...` and `/track/?id=...&quality=HI_RES_LOSSLESS`. Clear
`encryptionType: "NONE"` BTS manifests and unprotected FLAC DASH manifests are
supported. Encrypted manifests and DRM license/key extraction are unsupported.

## Research and limits

- [MusicKit](https://developer.apple.com/musickit/) supplies official Apple
  subscription playback, but no verified raw multichannel PCM hook for this DMR
  integration has been established.
- [gamdl](https://github.com/glomatico/gamdl) documents active Apple Music account
  and backend requirements. Its public source is not a free Apple catalogue.
- [streamrip](https://github.com/nathom/streamrip) documents premium TIDAL/Qobuz
  account requirements. Its downloader is not embedded here.
- [LastWave](https://lastwave.app/addon/) uses a separately installed backend for
  additional formats; YouTube itself does not supply FLAC/Atmos. Only public
  protocol fields were consulted; no GPL implementation or private secrets copied.
- [Monochrome](https://github.com/monochrome-music/monochrome) is an open frontend;
  its backend availability and origin restrictions are separate. Music does not
  impersonate that frontend or bypass its origin checks.
- [TIDAL also offers Atmos](https://support.tidal.com/hc/en-us/articles/360004255778-Dolby-Atmos).
  It is not exclusive to Apple Music. Both TIDAL paths reject protected media;
  the native account path additionally requests eligible clear JOC manifests.
