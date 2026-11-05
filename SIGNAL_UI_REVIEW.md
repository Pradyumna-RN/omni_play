# Signal UI review

Reviewed Claude’s `ab54281` design against the current application, including the
0.3.31 account, provider and audio-source fixes. The reviewed UI is included in the Music 0.3.32 release target.

The paper/ink palette, orange accent, square controls, Archivo display type and
DM Mono labels give Music a coherent visual identity. Both light and dark player
screens were rendered through the actual Compose UI and checked during review.

Before merging, the following issues were corrected:

- The source branch predates the Spotify integration and latest account fixes.
  Merge conflicts preserve those changes and the actual provider/codec badge.
- The original four-control grid placed Play/Pause off-centre. Save now sits by
  the song metadata, leaving symmetric Previous / Play/Pause / Next controls.
  Artwork adapts to the available height; the next song is visible on the tested
  390 × 844 dp screen. Existing swipe and seek behaviour remains covered.
- “Unheard” used artist novelty, which rejected new songs by familiar artists.
  It now checks recording identity against recorded plays, history and the
  current song. Saved-but-unplayed songs remain eligible. The two recommendation
  groups do not repeat recordings, and history is never copied in as candidates.
- Home exposes individual recommended songs. Albums and playlists remain in
  Explore. With listening-history tracking disabled, the discovery section says
  “Discover more” rather than claiming that its songs have never been heard.
- Smaller 128 dp recommendation covers and single-line scrolling metadata keep
  Home compact and prevent long titles or artist names from resizing sections.
- The older branch’s monitor changes would restore growing output bars and
  mislabel stereo bypass. The merged monitor retains the current fixed-node
  brightness behaviour, accurate bypass wording and Developer options gate.
- The always-dark dock needs light system-navigation icons in light mode too.
  Navigation-bar appearance now follows the dock rather than the page theme alone.

Validation covers the actual Compose callbacks and layout, metadata updates,
cover/mini-player gestures, Original/Processed switching, queue selection,
recommendation partitioning, provider settings and the existing application
regressions. No provider implementation, audio decoder, DSP implementation,
manifest entry, version code or native binary is changed by this review.

| Before | After | Why |
| --- | --- | --- |
| Four controls put Play/Pause off-centre. | Save beside metadata; symmetric transport row. | Clear, balanced primary action. |
| Artist novelty stood in for unheard music. | Recording-history filtering and separate song groups. | Unheard songs by familiar artists remain discoverable. |
| Old monitor code drew growing bars. | Fixed speaker nodes change brightness only. | Preserves the approved monitor behaviour. |

Validation completed: **308 app checks pass**, with no failures, errors or skips.
The focused 49-check UI/recommendation run also passes; it is a subset, not 49
additional unique checks. `:app:compileReleaseKotlin` and `:app:lintDebug` pass.
The resulting merge commit and detailed evidence are recorded in the local
review audit. Host-rendered UI checks are not a phone frame-rate measurement.
