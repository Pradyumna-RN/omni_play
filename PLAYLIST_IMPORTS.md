# Playlist imports and Spotify sign-in — local handoff

These are source changes for **0.3.41 / versionCode 44**. No APK or OTA for this
version has been published in this session. The latest published release is 0.3.40.

Claude's `9a2baef` Explore viewport-clipping fix is included without additional
changes to that layout.

## Import playlists

Open **Library → Playlists → Import playlist**:

- **YouTube Music:** choose an account playlist after connecting, or paste a
  public YouTube/YouTube Music playlist link. Private links require account access.
- **Spotify:** choose an account playlist or paste its full Spotify playlist link.
  Account access is needed for these live catalogue reads.
- **Other apps:** choose an M3U/M3U8, PLS, XSPF, CSV or JSON export. CSV supports
  track URI/URL, name, artist and duration columns; JSON supports Music-style
  track lists and Spotify's playlist data export.
- **Local playlists:** choose the export and grant access to its audio folder.
  Ambiguous filenames, parent-directory references and inaccessible files are
  skipped. Files outside the selected/read-granted locations are not opened.

Review the playlist names, available songs and skipped entries, then select
**Import to Music**. Imports create device copies; they never edit the originals
or upload local audio. Order and repeated occurrences are retained. Reads finish
before saving, and a multi-playlist import is written atomically. A failed or
cancelled read does not save a partial playlist.

Limits: 2 MB per export, 5,000 songs per import. Metadata-only rows are matched
conservatively; ambiguous matches are skipped. Complete Spotify track metadata
can retain the existing lazy recording-match route. Imported playlists do not
change the audio-provider/quality label shown by the player.

## Listening history

Music keeps its listening history on the device. There is no explicit playback
history sync to YouTube Music or Spotify; Spotify provides discovery metadata
rather than reporting these plays as Spotify streams. YouTube playlist edits
and ratings remain separate, explicit account actions. Do not rely on a play
appearing in either service's history.

## Spotify blank-page changes

- Reload recreates the WebView and clears its page cache without clearing the
  Spotify session cookie.
- An older login activity cannot detach the current activity's browser; callbacks
  from replaced WebViews are ignored.
- Responsive viewport settings and safe blank/Spotify-blob subframes are enabled.
  Local-file/javascript navigation, insecure TLS and third-party native bridges
  remain blocked.
- A blank-page timeout gives a recovery message instead of loading indefinitely.
- Profile verification follows the current React root and waits through transient
  post-login changes. A missing anonymity flag requires a successful canonical
  self-profile check; a cookie or display name alone cannot confirm sign-in.

## Verification completed and remaining

- Spotify bridge: 16 Node checks passed.
- YouTube engine: 39 Node checks and 62 browser/proof checks passed.
- Public Spotify login forms rendered in Chromium with desktop and Android
  WebView user-agent profiles. The signed-out public profile probe correctly
  returned AUTH_REQUIRED. No credentials were used.
- Android main Kotlin compilation passed during development. The latest focused
  Android run had **55 of 56 checks pass**. The remaining PLS test exposed format
  detection treating `[playlist]` as JSON; that detection has now been corrected,
  but this final correction has **not been rerun**.
- No complete final Gradle/lint run, release build, physical-phone test or live
  signed-in Spotify/YouTube playlist import has been completed for this commit.

Suggested local checks (configure private build inputs as described in README):

```sh
./gradlew :app:testDebugUnitTest --tests labs.rtensor.music.PlaylistImportDataTest
./gradlew :app:testDebugUnitTest :dsp:testDebugUnitTest :app:lintDebug
npm test --prefix youtube-engine
npm run test:browser --prefix youtube-engine
node --test spotify-engine/test/catalogue.test.mjs
```

The generated YouTube engine bundle and manifest are committed with the source.
Use the existing release/OTA signing configuration when building locally.
