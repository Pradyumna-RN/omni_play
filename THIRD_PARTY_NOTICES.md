# Third-party notices

The app contains no private Downmix Renderer implementation or coefficients.

Runtime client dependencies (pinned through the lockfile):

| Component | Version | Licence |
| --- | --- | --- |
| youtubei.js | 18.3.0 | MIT |
| bgutils-js | 4.0.3 | MIT |
| AndroidX Credentials / Play services auth bridge | 1.6.0 | Apache-2.0 |
| Google ID library | 1.1.1 | Android SDK Licence |
| @bufbuild/protobuf | 2.16.0 | (Apache-2.0 AND BSD-3-Clause) |
| fflate | 0.8.3 | MIT |
| meriyah | 7.4.1 | ISC |
| Omnitone third-order ambisonic HRIRs (derived from the SADIE database, University of York) | 2.0.0 | Apache-2.0, © 2016 Google Inc. |

AndroidX/Media3, Kotlin/coroutines, Coil, OkHttp and Okio use Apache-2.0 and their
upstream notices. DM Sans uses SIL OFL 1.1. Full licence texts are bundled in
Settings → Open-source licences. The old Python/Chaquopy/yt-dlp runtime is removed.
Architecture images were created for the approved Margin concept; provider
artwork is loaded at runtime. No album artwork or account data is bundled as fixtures.

Google Identity and Google Play services components retain Google’s Android SDK
Licence terms: https://developer.android.com/studio/terms.html.
AndroidX Credentials uses Apache-2.0.

Upstream: https://github.com/LuanRT/YouTube.js and https://github.com/LuanRT/BgUtils (MIT).
Metrolist, InnerTubeX and Morphe were researched but their GPL code is not bundled.

The headphone (Spatial) mode bundles the 16×256-tap third-order HRIR set from
Omnitone 2.0.0 (https://github.com/GoogleChrome/omnitone, Apache-2.0), re-encoded as
raw float32. Those filters are derived from the SADIE HRTF database (University of
York). The binaural renderer code is original; see toa_hrir_48k.NOTICE.txt.

Omnitone HRIR data notice (retained from src/resources/NOTICE):

# SADIE Binaural Measurements

Omnitone's HRIR files for the binaural rendering is based on Google/SADIE
binaural filters.

https://www.york.ac.uk/sadie-project/GoogleVRSADIE.html

Copyright (c) 2017, University of York.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
