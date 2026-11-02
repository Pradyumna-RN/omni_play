# Third-party components

The private Downmix Renderer source is **not included**. This project does not
grant any rights to that separate repository or its algorithm.

Main dependencies:

| Component | Version | Upstream licence |
| --- | --- | --- |
| yt-dlp (Python source package, without optional binary bundles) | 2026.10.26 | Unlicense; consult its bundled third-party notices |
| Chaquopy | 17.2.0 | MIT; bundled CPython/runtime libraries have their own notices |
| CPython | 3.12 runtime supplied by Chaquopy | Python Software Foundation licence and bundled notices |
| AndroidX Compose, Lifecycle, Activity, Core, Media3 | pinned in Gradle | Apache-2.0 and upstream notices |
| Kotlin / coroutines | 2.1.20 / 1.10.2 | Apache-2.0 |
| Coil / OkHttp / Okio | 2.7.0 / resolved Gradle versions | Apache-2.0 |
| DM Sans | bundled font | SIL Open Font License 1.1; included in app resources |

Dependency licence review is separate from the integration agreement for the DSP.
Do not substitute a binary bundle or new extractor without checking its actual
licence and transitive components. Preserve upstream notices when distributing.

Architecture photographs were created for the existing approved Margin concept.
No third-party album artwork is bundled; actual search-result thumbnails come
from the provider at runtime. The additional photographs bundled from the design
are used only as generic artwork fallbacks.

Upstream references:

- https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE
- https://github.com/yt-dlp/yt-dlp#license
- https://github.com/chaquo/chaquopy/blob/master/LICENSE.txt
- https://docs.python.org/3/license.html
- https://developer.android.com/jetpack/androidx
- https://github.com/coil-kt/coil/blob/main/LICENSE.txt
