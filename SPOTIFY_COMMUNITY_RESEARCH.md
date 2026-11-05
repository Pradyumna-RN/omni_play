# Spotify: broader public-community and source review

Research date: 5 November 2026. This extends
[the integration investigation](SPOTIFY_INTEGRATION_RESEARCH.md).

## Finding and recommendation

Apps offering the requested experience do exist. They use different mechanisms:
metadata from Spotify with audio elsewhere; Spotify's Web Player inside Android;
modifications to the installed Spotify client; or a privileged companion that
restarts/mutes that client during ads. These mechanisms are not interchangeable.

For Music, the recommended first integration is **Spotify discovery and library
plus the existing playback engine**: Spotify recommendations, radio, playlists,
albums, liked songs and search; verified matching to the chosen playback source.
It preserves the current UI, media controls and native audio path without adding
a browser playback engine. This is a design recommendation, not a shipped feature.

The catalogues overlap substantially, but individual recordings, edits, remasters,
regional availability, podcasts and audiobooks can differ. Never assume every
Spotify item has an identical playable YouTube Music counterpart.

## Telegram and Discord coverage

The review used web searches, public Telegram previews, public Discord discovery
pages/invites, developer websites and linked public repositories. No account was
joined and no message sent. Discord invite pages do not expose private channel
history; that history was not read. Some invite/status URLs failed to load.

| Public community source | What it establishes | What it does not establish |
| --- | --- | --- |
| [Yuma Hub](https://t.me/s/yumaplayer), linked from the project's README | The developer describes Spotify feeds, YouTube Music streams and separate lossless sources, and links the source repository. | Direct Spotify Free audio, or that the lossless files come from Spotify. |
| [Spotube's Discord discovery page](https://discord.com/servers/spotube-1012234096237350943) | Its public description explicitly separates Spotify data from YouTube/Piped/JioSaavn audio. | A native Spotify audio decoder or lossless Spotify entitlement. |
| [BitChord's Discord discovery page](https://discord.com/servers/bitchord-1540054967573020772) | Publicly describes YouTube Music streaming plus Spotify integration. | That Spotify playlist support means Spotify supplies the audio. |
| [Official xManager update channel](https://t.me/s/xmanagerupdates), cross-linked by [its website](https://www.xmanagerapp.com/links) | Manager releases, community links and the project's identity; the GitHub link redirects to Team-xManager. | That any particular Spotify build currently plays successfully on a free account. |
| [SpotiDuck developer README](https://github.com/23fpsz/SpotiDuck-Releases) and its Discord invite | The maintainer links a community and documents a WebView player with free-account compatibility limitations. | Private Discord reports; the invite could not be read with the web tool. |
| [Myspoti developer README](https://github.com/kota-zo/myspoti) and its Discord invite | The native UI/Web Player architecture and a developer-linked support community. | Private chat history; the invite only returned a JavaScript-required page. |
| Public [Apps Android Respaldo](https://t.me/s/app_android_back/3) and [NIGANPRO TV](https://t.me/s/niganpro?before=2140) posts | Leads for names such as Myspoti, Spotui, SpotiDuck, SpotiWeb and other players. | APK authenticity, source equivalence, current operation, or a new independent audio technology. |

Community posts were used to discover candidates, then checked against developer
documentation or source where available. Reposted version labels and download
claims were not treated as successful playback tests. No community APK was run,
and no credential, account cookie or token was requested from another person.

## Apps, extensions and server stacks

Evidence below is code inspection or project documentation, as stated. None of
these entries represents a successful live-account test in this environment.

| Candidate | Mechanism found | Fit for Music / evidence limit |
| --- | --- | --- |
| [Spotube](https://github.com/team-spotube/spotube) + [Spotify metadata plugin](https://github.com/sonic-liberation/spotube-plugin-spotify) | Separate metadata and audio APIs. The Spotify plugin reads library/radio; NewPipe YouTube resolves audio. | Strong architecture match for the recommended integration. Both sides were inspected in source; details and pinned commits are in the companion report. |
| [Meld](https://github.com/nahalewski/Meld) | Spotify account data and catalogue drive the UI; songs are matched to YouTube Music. | Strong matching/library reference. Its README also describes a custom recommendation algorithm using a Spotify taste profile: do not equate that algorithm with Spotify's own live radio results. |
| [YumaPlayer](https://github.com/MuwMx/YumaPlayer) | Documents Spotify feeds/catalogue with YouTube Music or separate lossless playback. Contains lazy Spotify matching and paging tests. | Useful design reference for incremental matching instead of resolving an entire large playlist before playback. Telegram developer description agrees with the hybrid architecture. |
| [BitChord](https://github.com/kushagrasinghx/BitChord) | YouTube Music client with Spotify playlists/liked songs and optional separate lossless modules. | Another hybrid integration, not proof of direct Spotify Free audio. README, source layout and public Discord description were checked. |
| [Myspoti](https://github.com/kota-zo/myspoti) | Native UI controls Spotify's Web Player in a hidden WebView; ad-media filtering, playback-state rewriting and recovery. | Closest independently inspectable direct-Web-Player candidate. Undocumented web interfaces and Android DRM compatibility require real-account/device testing. No raw PCM reaches Music's DMR through this route. |
| [Spotui](https://github.com/Spotui/Spotui) | Contains a hidden Spotify Web Player and separate YouTube/lossless playback. `canPlay` gates the web route. | Important caveat: presence of a Spotify web engine does not prove it is used; unsupported devices fall through to other sources. |
| [SpotiDuck](https://github.com/23fpsz/SpotiDuck-Releases) | Maintainer documents a Spotify WebView wrapper with ad filtering and Android media integration. | Public repository contains release/documentation material rather than the Android implementation. Free-account, WebView and DRM limitations are explicitly documented. Do not claim source-verified playback. |
| [Spotilol public source](https://github.com/tallowmoth/Spotilol) | WebView wrapper with ad-request filtering and injected ad-state handling. Its README distinguishes InnerTube-based offline audio and an optional local-certificate proxy mode. | Another actual source-level lead from the community search. Do not confuse its offline files with Spotify audio, or introduce its certificate/proxy setup into Music as an assumed requirement. The earlier lyssadev source URL returned 404; the inspected public repository is named explicitly. |
| [SpotiGo](https://github.com/Slluxx/SpotiGo) | Developer describes a custom browser engine with a JavaScript/native media bridge and links a Discord community. Its README explicitly says this is not a WebView solution and the implementation is not published. | A distinct, relevant lead, but only documentation was inspectable. The repository contains overview/workflow material, not an embeddable player implementation. Do not infer its engine, ad-free reliability or account compatibility from the description. |
| Spotifuck / Spotify Web Fake Player | Identified as an earlier WebView wrapper; SpotiDuck credits it as an ancestor. | The source URL found in search returned GitHub 404. An indexed description is not current source verification or a separate maintained engine. |
| [Hush](https://github.com/coldMEW/Hush) | Companion to official Spotify: notification metadata identifies ads; a privileged helper restarts Spotify, with mute fallback and optional Spotify-only DNS filtering. | Source confirms the restart mechanism. Requires installed Spotify and Wireless debugging/Shizuku/root-style helper access; not an in-app Music playback provider, and restart gaps are not gapless playback. |
| [xManager](https://github.com/Team-xManager/xManager) | Its README describes fetching/installing selected app variants and managing the installed app. | Manager source is not Spotify's playback source. An available manager release does not establish a redistributable playback library or functioning free-account stream. |
| ReVanced Spotify-related leads | Android patching ecosystem discovered through public search and community links. | The queried upstream patches repository returned GitHub HTTP 451 with a DMCA-block response. It was not inspected through a bypass or represented as a working current integration. |
| [Echo](https://github.com/brahmkshatriya/echo) Spotify extension | Original extension URL returned 404; a public [fork](https://github.com/LuftVerbot/echo-spotify-extension) was inspected. It declares a private build dependency and a separate key-service dependency for one streaming route. | Not a self-contained public playback component. No working backend, free-account playback or build was verified. A commented-out Widevine route must not be reported as implemented support. |
| [SpotifySync / GhostSync](https://github.com/Matteomac81888/SpotifySync) | Mirrors another player's song into a nearly muted Spotify Web Player for social listening status. | Does not provide the audible source for Music. Its documented use is status synchronization, not replacing our playback engine. |
| [LavaSrc](https://github.com/topi314/LavaSrc) | Its README explicitly marks Spotify as a mirrored metadata source: another provider supplies playable audio. | Explains why some Discord music stacks accept Spotify links without streaming Spotify audio. It is also a server component, not a ready Android account engine. |
| [librespot](https://github.com/librespot-org/librespot) | Independent Spotify client/Connect library. | Explicit Premium requirement rules it out for the free-account target. |
| [librespot-java](https://github.com/librespot-org/librespot-java), [Jetispot](https://github.com/iTaysonLab/jetispot), [go-librespot](https://github.com/devgianlu/go-librespot) | Independent-client family; Java development moved to Go, and Jetispot is archived and documents Premium. | Not evidence of a maintained free-account Android route. |
| [SpotX](https://github.com/SpotX-Official/SpotX) / [spotify-adblock](https://github.com/abba23/spotify-adblock) / [Spicetify](https://github.com/spicetify/cli) | Desktop client patching, Linux request interception, or desktop customization. | Useful evidence that desktop solutions exist; none is an embeddable Android Spotify engine by itself. |

An app advertising FLAC alongside Spotify sign-in is not automatically streaming
Spotify FLAC. The checked hybrid projects describe separate audio providers or
download-source extensions. Source, codec and actual channel count need separate
verification in Music.

The name **SpotiWeb** is also ambiguous: search found an archived
[Electron wrapper](https://github.com/tomasmcm/SpotiWeb), an unrelated
[artist-browsing project](https://github.com/nicolasbrailo/SpotiWeb), and Android
repost labels. A Telegram name alone cannot identify which implementation was
distributed. The desktop wrapper's release notes themselves describe ad-system
changes breaking blocking/reload behaviour; this is not Android validation.

## Additional source snapshots

| Project | Commit inspected |
| --- | --- |
| Meld | `637dd5adbddc3ddb0b86373e1977d3d2b4a7e67d` |
| YumaPlayer | `90359f38722534341302f3feb6bff4c4750c720d` |
| BitChord | `b690dc146009d4815825fa4a20815ef2e4ee8898` |
| Spotui | `2502236aa5ed69b10bb70324beb3900bfc0e5b3f` |
| SpotiDuck release repository | `adee8ccc2b26b3f3fe711bb6fab1da424fc1b414` |
| Public Spotilol source | `528b6cb2f892766a71633d1a78473190d7750719` |
| SpotiGo overview repository | `a3e2e5a0ccdf0078dca5236ddbb680d01ede5b97` |
| Hush | `a290e1421e6f7cfc59f959608ff4199b31616a4d` |
| xManager | `7e8476815c840fdabd500aeb9d1f0c2243ffe9f3` |
| Echo app | `f210820680cd5e7bdcb383e3daab3fa2c06b71e0` |
| Public Echo Spotify-extension fork | `a8aaffd3a0e87fb1d74dc0ec7e80086ade7f6795` |
| SpotifySync | `fe6dc488c0142f9b17e70f525563eae0a5813391` |
| LavaSrc | `c62ae61d14c5753a3f78dc84779c7fb9ec183c15` |

Useful code-level references beyond the companion report:

- [Spotui web player](https://github.com/Spotui/Spotui/blob/2502236aa5ed69b10bb70324beb3900bfc0e5b3f/app/src/main/java/com/music/spotui/di/SpotifyWebPlayer.kt)
  and [engine selection](https://github.com/Spotui/Spotui/blob/2502236aa5ed69b10bb70324beb3900bfc0e5b3f/app/src/main/java/com/music/spotui/di/SongPlayer.kt).
- [Hush restart coordinator](https://github.com/coldMEW/Hush/blob/a290e1421e6f7cfc59f959608ff4199b31616a4d/android/app/src/main/java/io/coldmew/hush/Skipper.java)
  and its [privileged helper actions](https://github.com/coldMEW/Hush/blob/a290e1421e6f7cfc59f959608ff4199b31616a4d/android/app/src/main/java/io/coldmew/hush/helper/Actions.java).
- [LavaSrc mirroring explanation](https://github.com/topi314/LavaSrc/blob/c62ae61d14c5753a3f78dc84779c7fb9ec183c15/README.md#what-is-mirroring).
- [Spotilol WebView request handling](https://github.com/tallowmoth/Spotilol/blob/528b6cb2f892766a71633d1a78473190d7750719/app/src/main/java/com/project/lol/webview/SpotifyWebViewClient.kt)
  and [filter helpers](https://github.com/tallowmoth/Spotilol/blob/528b6cb2f892766a71633d1a78473190d7750719/app/src/main/java/com/project/lol/webview/helpers/AdBlocker.kt).
- [YumaPlayer bounded lazy matcher](https://github.com/MuwMx/YumaPlayer/blob/90359f38722534341302f3feb6bff4c4750c720d/app/src/main/kotlin/moe/rukamori/archivetune/spotify/SpotifyLazyTrackMatcher.kt)
  and its [tests](https://github.com/MuwMx/YumaPlayer/blob/90359f38722534341302f3feb6bff4c4750c720d/app/src/test/kotlin/moe/rukamori/archivetune/spotify/SpotifyLazyTrackMatcherTest.kt)
  were read. Upstream tests were not executed.

## Proposed Music behaviour

1. Connect Spotify for its catalogue/library. Preserve Spotify IDs and ordering
   alongside the resolved playback-source identity; do not relabel a YouTube
   stream as Spotify audio.
2. Offer Spotify playlists, albums, liked songs, home suggestions and real
   provider-radio results where available. Distinguish a provider result from
   any app-generated taste mix. Never fill the next queue with history entries
   merely because Spotify is unavailable.
3. Resolve lazily and preload a small next-track window. Cache successful
   recording matches separately from expiring stream URLs.
4. Match ISRC when available and also verify artist, title, edition and duration.
   Reject unverified covers/live/remix substitutions. Expose a manual match fix
   and a clear unavailable state for genuinely missing recordings.
5. Keep the authorised local/Apple/TIDAL/Qobuz source selection and native DSP.
   Validate the delivered format before showing lossless or multichannel labels;
   YouTube fallback remains compressed stereo. Keep account disconnect and stored
   sessions independent between providers.
6. Test pagination, cancellation, unavailable tracks, duplicate identities, cache
   expiry, queue ordering and account isolation before an optimized release.
   Live sign-in and recommendation access still need a tester's own account.

No third-party implementation or binary was copied into Music. No app source,
provider or playback feature was changed, and no APK/OTA was published for this
research. The previous release remains unchanged.
