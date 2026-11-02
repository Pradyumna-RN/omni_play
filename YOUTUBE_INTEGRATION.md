# YouTube integration research and implementation

Research for the v0.3 Android update, 2 November 2026.

## What maintained apps actually do

| Project | Relevant approach | Implication for this independent player |
| --- | --- | --- |
| [Metrolist](https://github.com/MetrolistGroup/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/ui/screens/LoginScreen.kt) | Normal Google/YouTube Music sign-in in an app-owned WebView; captures its YouTube cookies, visitor and selected-account metadata. | Use a normal web session, then verify the account. No TV approval code. |
| [Metrolist playback](https://github.com/MetrolistGroup/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/utils/InnerTubeXPlayer.kt) / [InnerTubeX](https://github.com/MetrolistGroup/innertubex) | Separates extraction, signatures, runtime proofs, session state, transport support and stream probes. | A successful search or player response alone does not establish playable audio. These GPL projects were researched, not copied or linked. |
| [Morphe patches](https://github.com/MorpheApp/morphe-patches) | Patches the official app, using its authentication and playback machinery, with GmsCore support where applicable. | This is not a drop-in authentication/playback SDK for an independent Media3 app. |
| [YouTube.js](https://github.com/LuanRT/YouTube.js) | MIT web API client, provider parsers and current player-script extraction. | Keep the independent adapter and pinned, reproducible bundles. |
| [BgUtils](https://github.com/LuanRT/BgUtils) | MIT implementation of Google's challenge/snapshot/integrity/proof protocol. | Run actual verification in an isolated runtime; use distinct bindings and handle rejection explicitly. |

The ReVanced patches repository returned HTTP 451 during this research. No attempt
was made to bypass that restriction. The maintained Morphe fork provided the
relevant patched-app comparison.

## Specific problems corrected

1. The previous device-code flow identified the connection as YouTube on TV. It is
   removed. Old OAuth blobs cannot be loaded as new web sessions.
2. YouTube.js `account.getInfo()` defaults to TV. The app explicitly calls
   `getInfo(true)` for the WEB accounts-list request, and checks a selected account.
3. In pinned YouTube.js, an explicit WEB option does not reset a MUSIC base context.
   The session now has a WEB base; music requests explicitly select YTMUSIC. Tests
   inspect the actual serialized client and version, not just the requested option.
4. Player signatures must use the current script and signature timestamp. The app
   reads current bootstrap versions/script URLs, extracts the script with YouTube.js,
   and evaluates extracted expressions in a bounded Worker without account access.
5. A signed URL can still be refused by the media CDN. Resolution now checks a
   bounded `Range: bytes=0-1` response, status and media content type before playback.
6. Where verification is needed, a separate WebView runs BgUtils against Google's
   attestation service. It has no encrypted-session bridge, cookie jar transport or
   unrestricted network proxy. The main engine obtains the current WEB `/att/get` challenge and passes only
   that challenge to the sandbox. WEB player proof binds to video ID; WEB_REMIX
   player proof uses the session binding. Media uses the full authenticated
   `DATASYNC_ID`, guest visitor data, or video ID where the provider enables
   content-bound GVS. The raw account binding is never split for minting. Verification rejection never becomes a synthetic success.
7. The previous generic error mapped "unavailable" or "not playable" to a region or
   account restriction without evidence. The app now retains sanitized provider
   reasons and separates client, media HTTP, verification and SABR-only failures.
8. Thumbnail arrays are sorted by area rather than assuming the final entry is
   largest. Real artist metadata is used; missing artists are labeled honestly.

## Local account and network boundaries

The sign-in WebView displays only allowed first-party Google/YouTube navigation and
has no JavaScript interface attached to remote pages. Confirmation requires the
YouTube Music origin. A fixed page-config expression and the app's own CookieManager
capture the selected web session; no browser password storage or external browser
profile is read. AES-GCM/Android KeyStore protects the saved session. A failed new
connection restores the previous session. Closing sign-in clears its browser data.

The bundled API engine has an offline app origin, no page navigation and no direct
network access. Its native transport rejects cleartext, user-info URLs and unrelated
hosts. Up to four first-party HTTPS GET/HEAD page redirects are allowed within one
24-second deadline. Crossing an origin removes account/authentication headers and
cookies. POST, media/proof and Google login/consent redirects are not followed. YouTube cookies are injected only for first-party YouTube.
Googlevideo probes receive no account cookies or authorization. Only a two-byte
media range can use that transport; Media3 handles the actual recording.

The proof runtime has a separate bridge with no account-vault methods and a narrow
Google attestation/interpreter allowlist. It cannot fetch YouTube account endpoints.
Its in-memory minter expires within the provider TTL and is reset on account changes.
The app logs neither account cookies nor proof/signed-media credentials.
Sign-in survives orientation/screen-size changes. Account feed invalidation uses
a session revision rather than a display name, so same-name accounts refresh too.

## Practical scope

Home and Explore preserve provider order. Search uses the shared search bar. No
independent recommendation ranking is introduced. There is no silent replacement
of a rejected recording with a different video or provider.

Only validated non-DRM direct audio is playable in this version. SABR-only responses
are diagnosed; a native SABR transport remains future work. Rights, age, private
video and subscription restrictions remain provider decisions. Google can refuse
an embedded login or device verification; those flows have no fabricated fallback.

The workspace blocks live YouTube traffic. Fixture tests cover the exact bundles,
account protocol, decoder, probes and proof wiring. Real sign-in, accepted live
attestation and an actual stream still require a phone check; they are not claimed
as verified here.

## Maintained verification protocol references

The current binding rules were checked against
[yt-dlp proof binding utilities](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/pot/utils.py)
and its account-session parsing; this is protocol research, not a yt-dlp runtime
dependency. The
[bgutil provider session manager](https://github.com/Brainicism/bgutil-ytdlp-pot-provider/blob/master/server/src/session_manager.ts)
uses the WEB attestation challenge path. Its
[server implementation](https://github.com/Brainicism/bgutil-ytdlp-pot-provider/blob/master/server/src/main.ts)
notes the old `/Create` discovery endpoint is no longer working. Music does not
call that endpoint or depend on a third-party token server.

## v0.3.1 phone-reported failures

The sign-in screenshot showed an authenticated YouTube Music page but HTTP 302
during account confirmation. The former implementation refetched the homepage
before verifying its account. Confirmation and restored sessions now use the
captured visitor/client/account metadata directly, with the real WEB accounts-list
API still required to accept the account. Playback configuration refresh happens
separately and cannot invalidate a confirmed sign-in. The login capture also keeps
the validated player-script location and parsed Music content-bound GVS flag.

The reported `ERROR_CODE_IO_BAD_HTTP_STATUS` exposed a separate playback bug.
Media3 1.6.1 sets the factory User-Agent after applying DataSpec headers, overwriting
the browser UA that passed the provider probe. The fixed factory value is removed.
An actual loopback HTTP test now checks headers and signed-query preservation.
Another test covers a rewritten CDN request returning HTTP 403 while retaining
the original catalogue ID and cache key for diagnostics/invalidation. Actual media
HTTP status is recorded before MediaController serialization, without signed URLs,
headers or response bodies in the copyable diagnostic.
