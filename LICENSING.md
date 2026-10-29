# Licensing

Telefyna is **dual-licensed**. You can use it under either:

1. **GNU Affero General Public License v3.0 (AGPL-3.0)**: free, open source. See [LICENSE](LICENSE).
2. **Telefyna Commercial License**: a paid licence from Avvento Media for uses the AGPL doesn't allow.

Copyright © 2024–2026 Avvento Media. All rights not expressly granted are reserved.

## Which licence do I need?

| You want to… | Licence |
|---|---|
| Run Telefyna unmodified on your own station or signage | AGPL-3.0 (free) |
| Modify Telefyna and share your changes under the AGPL | AGPL-3.0 (free) |
| Run a **modified** Telefyna that viewers or customers use over a network (e.g. hosted playout, a managed signage service) **without** publishing your source changes | **Commercial** |
| Ship Telefyna inside a closed-source product, device or firmware (OEM / white-label) | **Commercial** |
| Use the Telefyna cloud dashboard, fleet management or subscription features (when released) | **Commercial** (subscription) |

The AGPL's key condition is in section 13: if you modify Telefyna and let users interact with it over a network, you must offer them the corresponding source of your modified version. The commercial licence removes that obligation and adds support terms.

**Commercial licensing:** apps@avventomedia.org

## Contributions

To keep dual licensing possible, Avvento Media must be able to license every contribution under both licences. External contributors are asked to agree to a Contributor Licence Agreement (CLA) before a pull request is merged. Contributions are otherwise welcome under the AGPL-3.0.

## Third-party components

Telefyna depends on the following open-source libraries, each under its own licence. All of them are compatible with distribution under both the AGPL-3.0 and the commercial licence.

| Component | Version | Licence |
|---|---|---|
| AndroidX Media3 / ExoPlayer (exoplayer, hls, dash, rtsp, smoothstreaming, ui) | 1.5.1 | Apache-2.0 |
| Media3 RTMP data source (uses LibRtmp-Client-for-Android / librtmp) | 1.5.1 | Apache-2.0 (librtmp: LGPL-2.1) |
| srtdroid-core (bundles libsrt) | 1.8.3 | Apache-2.0 (libsrt: MPL-2.0) |
| AndroidX Core, AppCompat, Activity, ConstraintLayout, WorkManager | various | Apache-2.0 |
| Material Components for Android | 1.12.0 | Apache-2.0 |
| Glide | 4.16.0 | BSD-2-Clause, with parts under MIT and Apache-2.0 |
| Gson | 2.10.1 | Apache-2.0 |
| Apache Commons IO / Commons Lang 3 | 2.16.1 / 3.12.0 | Apache-2.0 |
| JavaMail (`com.sun.mail:javax.mail`) | 1.6.2 | CDDL-1.1 or GPL-2.0 with Classpath Exception |
| JUnit (tests only) | 4.13.2 | EPL-1.0 |
| MockK (tests only) | 1.13.5 | Apache-2.0 |

The LGPL-2.1 (librtmp) and MPL-2.0 (libsrt) components are used as unmodified, separately linked native libraries. If you modify them, their own licence terms apply to those modifications.

## Test media

Test videos used in pull-request demonstrations (*Big Buck Bunny*, *Sintel*) are © Blender Foundation, licensed CC-BY 3.0, and are not part of this repository.
