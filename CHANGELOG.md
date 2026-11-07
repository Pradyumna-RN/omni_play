# Changelog

All notable changes to Hum. Newest first. Every release adds an entry here and uses the same text as its GitHub release notes.

## 0.4.0-beta.9 (versionCode 53)

### Fixed
- Sign in with Google should work again. Beta.8 changed Hum's signing key and Google no longer recognised the app; Hum's release key is now registered with Google and accepted by the app.

## 0.4.0-beta.8 (versionCode 52)

### New
- A new loading animation. Four small bars rise and fall in a wave in place of the old ring, on Home, lyrics, sources and everywhere Hum waits. With reduced motion on, the bars hold still.
- Home shows song-shaped placeholders while the first picks load, instead of an empty page.
- The Home update card has What’s new: tap it to read the release notes before choosing Update or Later.

### Fixed
- Pull to refresh no longer draws a circle over the Home title when Hum loads on its own. The circle now appears only when you pull.

### Changed
- Hum is now signed with its own release key instead of the Android debug key. Android will not update an app across different keys, so installs of beta.1 to beta.7 cannot update to this one: uninstall Hum once, then install beta.8. Uninstalling removes Hum's settings and library, so export anything you want to keep first. From beta.8 on, updates install over each other as before.
- README now calls the app Hum throughout.

## 0.4.0-beta.7 (versionCode 51)

### New
- Updates now show on Home. When a new version is out, a card at the top of Home offers Update or Later. Update downloads and checks the file, then opens the installer; Later hides the card until you next open the app. Settings → About still shows the release notes.

### Fixed
- Settings → Sources → Playback sources: the Apple Music and TIDAL buttons, Add a source, Done and Check and save are now the same width and line up with the panel edges.
- The install hint now says to allow updates from Hum (it said Music).

## 0.4.0-beta.6 (versionCode 50)

### New
- Import playlists from a folder: Import playlist → Choose a folder of playlists. Every M3U/M3U8, PLS, XSPF, CSV or JSON file in it, subfolders included, becomes a playlist, and local songs are read from the same folder. A folder with songs but no playlist files becomes one playlist per folder of songs, named after the folder.
- Settings → About shows what’s new in an update (its release notes) before you download or install it.

### Fixed
- Importing songs from your device showed its progress under Export downloads. Import progress now appears under Import audio, and export progress stays under Export downloads.

## 0.4.0-beta.5 (versionCode 49)

### Fixed
- Lyrics no longer leave a big blank gap under Original / English. The lyric window now fills the space under the player on any phone size, opening the lyrics scrolls them into view, and the current line stays on screen and follows the song.
- The update buttons (Check updates, Install update, Download) are the same height, share the row evenly and line up with the rest of the About card.

### Changed
- While lyrics are open the cover shrinks (and folds away on short phones) so the words get the room; it returns when you close them.
- Lyrics no longer show a source or credit line under them.
- Open-source licences is removed from Settings → About for now; it comes back with the expanded list.

## 0.4.0-beta.4 (versionCode 48)

### New
- English lyrics now read in English letters, like the song's own language: Kannada “ನಾನು” shows as “nanu”, Hindi, Tamil, Telugu and the rest the same way.
- The Original / English choice sits with the lyrics, applies to every song, and can be changed any time from the player.
- Export downloads to a folder on your phone (Settings → Sources → Export downloads).
- Import music from your device: one file, many files, a folder with its subfolders, or several folders one after another.
- Spotify home now shows your liked songs and playlists, not only albums.
- Songs you play are added to your YouTube Music history, so they appear in Listen again. Turn it off with Settings → Privacy → Keep listening history.
- Releases are now named Hum.apk, and the README and About page use the Hum name.

### Fixed
- Synced lyrics keep the current line centred in a window sized for your screen, and return to it a moment after you scroll away.

### Changed
- Browse is removed from Explore; Discover covers it.

### Known
- Spotify sign-in through the browser (Chrome) is not built yet.
- Spotify playlist create/add is not built yet (YouTube Music only).
- Listen again can take a few minutes to refresh on YouTube Music's side.

## 0.4.0-beta.3 (versionCode 47)

### New
- What’s new: Settings → About now shows this changelog inside the app, offline.
- Multichannel decoding: the build includes an LGPL FFmpeg decoder for E-AC-3 (Dolby Digital Plus), AC-3 and ALAC, so 5.1 files decode and the Original / Processed toggle can apply the DSP renderer to them. The decoder source is attached to the release.

### Known
- Spotify sign-in through the browser (Chrome) is not built yet.
- Spotify playlist create/add is not built yet (YouTube Music only).
- Stereo sources still play unprocessed by design; processing applies to multichannel sources.

## 0.4.0-beta.2 (versionCode 46)

### New
- Lyrics move with the music: the current line is large and bright, the rest sit back, and the list glides along. Tap a line to jump there. Time-synced lines come from LRCLIB when the song is there, otherwise plain lyrics.
- Lyrics language: switch between Original and English (translated with Google Translate, remembered per song).
- Berry is the default accent, and presses use a rounded highlight instead of a rectangle.

### Fixed
- Home: the end of the feed and Library → Playlists no longer stall or show an error.
- Now playing: the queue no longer overlaps the player.
- Spotify sign-in: the embedded page hides the Google / Facebook / Apple buttons that it blocks.
- Processed audio: the build includes the DSP renderer (ABI 3), so it loads instead of reporting "unavailable". Stereo sources still play unprocessed by design; processing applies to multichannel sources.

### Known
- Spotify sign-in through the browser (Chrome) is not built yet.
- The FFmpeg decoder (E-AC-3 / ALAC) is not in the release build, so multichannel files are not decoded yet.

## 0.4.0-beta.1 (versionCode 45)

### New
- Hum look: Berry colours, floating pill nav, Stage player, pinned player, top-tab Settings.
- Motion: tab fade-through, pill slide, content cascade, player expand, play/pause morph, like burst.
- Lyrics from YouTube Music, downloads, playlist imports.

### Fixed
- Spotify sign-in page showed blank.
- Podcasts chip removed from Home.
- Listening history failed to save on Windows.

### Known
- Spotify playlist create/add is not built yet (YouTube Music only).
- Android Auto and the notification restyle are unverified.
