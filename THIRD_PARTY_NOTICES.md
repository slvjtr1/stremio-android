# Third-party components

Stremio Enhanced is built on the following projects. License files of vendored and submodule code are kept in their directories.

## Project base

| Component | Use | License |
|---|---|---|
| [stremio-native/stremio-android](https://github.com/stremio-native/stremio-android) | Upstream Android client this project is based on | No license file published upstream |
| [stremio-core-kotlin](https://github.com/perpetus/stremio-core-kotlin) (submodule) | Kotlin/JNI bindings for Stremio core | MIT (`streamio-core-kotlin/stremio-core-kotlin/LICENSE.md`) |
| [stremio-core](https://github.com/Stremio/stremio-core) | Accounts, add-ons, catalogs, library, player state | MIT |
| [stream-server](https://github.com/perpetus/stream-server) (submodule) | Local torrent/HTTP streaming server (Rust) | MIT (`stream-server/LICENSE`) |
| [mpv-android](https://github.com/mpv-android/mpv-android) (vendored) | MPV playback backend | MIT (`third_party/mpv-android-lib/LICENSE`) |
| libmpv, FFmpeg (prebuilt in mpv-android) | MPV engine and decoders | LGPL-2.1-or-later / GPL depending on build configuration — see mpv-android's build scripts |

## Runtime libraries

| Library | License |
|---|---|
| AndroidX: Activity, Core, Lifecycle, Navigation, Fragment, Compose (UI, Foundation, Material 3, icons), Media3 (ExoPlayer, UI, Session) | Apache-2.0 |
| Kotlin standard library, kotlin-reflect, kotlinx-coroutines | Apache-2.0 |
| Coil 3 and OkHttp | Apache-2.0 |
| [Kyant0 backdrop](https://github.com/Kyant0/AndroidLiquidGlass) | Apache-2.0 |
| pbandk runtime | MIT |
| Timber | Apache-2.0 |
| rustls-platform-verifier (AAR) | MIT / Apache-2.0 |
| PostHog Android (inactive without a build-time key) | MIT |
| Facebook Login SDK | Facebook Platform License |

## Development and test only

| Library | License |
|---|---|
| LeakCanary (debug builds) | Apache-2.0 |
| JUnit 4 | EPL-1.0 |
| Robolectric | MIT |
| Compose UI test | Apache-2.0 |

No component was added for subtitle synchronisation: speech detection and alignment are implemented in this repository on top of Media3.
