# Device account sign-in and YouTube sessions

A Google account chooser is optional for Music. The current YouTube web sign-in
does not require an OAuth project. Android isolates the YouTube app's private
session; another app cannot import its cookies or account tokens.

Google OAuth asks the user to select an account and approve specific scopes.
It returns permission tokens, never the user's password. Sign in with Google
identity tokens alone do not authorize YouTube APIs. YouTube Data API access also
does not expose YouTube Music Home recommendations or decoded audio/stream URLs.
Native account selection would therefore be a separate integration, not a fix for
this adapter's stream HTTP 403 or a replacement for its web session.

## Registration if native Google sign-in is added

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

Registration alone does not add the SDK or provide YouTube Music playback access.
No native-account chooser is enabled in v0.3.2. The existing web flow now confirms
an authenticated Music page automatically and remains cancellable during checking.

Official documentation:
- https://developer.android.com/identity/sign-in/credential-manager-siwg
- https://developers.google.com/identity/authorization/android
- https://developers.google.com/youtube/v3/guides/authentication
