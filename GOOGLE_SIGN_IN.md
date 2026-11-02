# Device account sign-in and YouTube sessions

A Google account chooser is optional for Music. The current YouTube web sign-in
does not require an OAuth project. Android isolates the YouTube app's private
session; another app cannot import its cookies or account tokens.

Google OAuth asks the user to select an account and approve specific scopes.
It returns permission tokens, never the user's password. Sign in with Google
identity tokens alone do not authorize YouTube APIs. YouTube Data API access also
does not expose YouTube Music Home recommendations or decoded audio/stream URLs.
Native account selection is a separate integration, not a fix for
this adapter's stream HTTP 403 or a replacement for its web session.

## Device-to-YouTube handoff (v0.3.5)

After Credential Manager verifies the chosen identity, Music asks Android's Google
account provider for a `weblogin:service=youtube` session URL with YouTube Music as
the fixed destination. Android/Google handles any account-access consent. Only a
validated HTTPS accounts.google.com MergeSession URL targeting music.youtube.com
is accepted. It is opened inside Music's bridge-free sign-in view, consumed once
and never logged or persisted. The resulting YouTube account is still verified
through WEB accounts_list before it is marked connected.

Google restricts this handoff on some devices/accounts; its availability is not
guaranteed by Sign in with Google's public identity API. On refusal, Music opens
normal Google browser sign-in with the selected email. The separate browser action
is available in Settings. No OAuth identity token is converted into a YouTube cookie,
and no other app's private session or password is read. Cancellation stops the
account-provider request. This native handoff needs real-device validation.

## Registered clients and native selection (v0.3.3)

1. Create/select a project in https://console.cloud.google.com/.
2. Configure Google Auth Platform branding and audience. While testing, add your
   Google account as a test user where the console requests it.
3. Under Clients, create an Android OAuth client for package `labs.rtensor.music`.
   The certificate SHA-1 for the currently published APK signing key is:
   `42:EC:9F:74:4C:EC:8A:B4:40:0B:B6:3A:9A:DA:32:6D:05:73:09:E3`.
4. For Credential Manager's Sign in with Google integration, create a Web client
   as its server/audience client ID too. Client IDs are public configuration;
   client secrets do not belong in an Android APK or this repository.
5. If adding official YouTube Data API features, enable that API and request only
   the permissions those features need, separately from basic Google identity.

The registered public clients are in `app/src/main/res/values/google_oauth.xml`:
- Android: `526624069759-obhnitrtm5scip7j2eutbm5aufrq916e.apps.googleusercontent.com`
- Web/audience: `526624069759-l806dmep3tq1mkmbo0d08ga6l188ir69.apps.googleusercontent.com`

v0.3.3 implements native device account selection using AndroidX Credential Manager
1.6.0 and Google's ID library 1.1.1. The latter is pinned for Kotlin2.1 compatibility.
The SDK uses the Web client as its token audience; the Android registration binds
this package and signing certificate. No Google Services JSON or client secret is
required in the APK.

Settings → Choose Google account opens the Google provider on the device. The
returned token is verified against Google's fixed HTTPS JWKS endpoint: RS256
signature, issuer, Web audience, authorized presenter, expiration, issue time,
one-use random nonce and verified email. Tokens are not logged or stored. Only
subject/email/display name are encrypted in a separate local preference vault.
Cancellation stops the credential request/key fetch. App sign-out clears the local
profile and YouTube session and asks Credential Manager to clear its selection.
It does not remove the phone's Google account.

A selected Google account and a connected YouTube session are separate UI states.
After successful selection, the chosen email is safely encoded as a Google-page
login hint. The normal YouTube connection still requires the actual signed-in web
session and WEB account verification. Changing Google accounts clears the previous
YouTube connection to prevent another account's cached feed from remaining visible.
A cancelled/unverified Google response does not replace the selected profile.

The native chooser and real OAuth consent have not been tested on a phone here.
An emulator/Robolectric fixture cannot establish Google Play services or Cloud
registration compatibility. If Google reports registration error10, check the
Android package/SHA-1 and that both clients belong to the same project. In Testing
mode, add the intended Google account to the Google Auth Platform test users.

Official documentation:
- https://developer.android.com/identity/sign-in/credential-manager-siwg
- https://developers.google.com/identity/authorization/android
- https://developers.google.com/youtube/v3/guides/authentication
