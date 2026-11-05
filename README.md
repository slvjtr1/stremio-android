# Stremio Enhanced for Android

An unofficial, community-built Android client for the Stremio ecosystem — Kotlin, Jetpack Compose, ExoPlayer/Media3 and MPV — focused on a polished interface and smarter playback.

[![Android CI](https://github.com/slvjtr1/stremio-android/actions/workflows/android-ci.yml/badge.svg?branch=feature/stremio-enhanced)](https://github.com/slvjtr1/stremio-android/actions/workflows/android-ci.yml)

> [!WARNING]
> Stremio Enhanced is **not** the official Stremio app and is not affiliated with or endorsed by Stremio. It uses the open-source Stremio core, so your Stremio account, library, add-ons and Continue Watching work as usual. It is based on [stremio-native/stremio-android](https://github.com/stremio-native/stremio-android).

## Highlights

### Automatic subtitle synchronisation
Pick an add-on or imported subtitle and Stremio Enhanced lines it up with the dialogue by itself.

- Listens to the audio being played (on-device, no AI model, no upload) and matches detected speech against the subtitle timing.
- Fixes **constant offsets** (subtitles always early or late) after about a minute of audio.
- Fixes **progressive drift** — subtitles that slowly fall behind, typically from a 23.976 vs 25 fps release — by correcting the time scale, not just the delay.
- Only applies a correction when the match is statistically clear; otherwise it says so and leaves the timing alone.
- The subtitle menu shows the status, the current correction, and lets you reset it or switch off drift correction. Your manual delay always stays in effect on top.
- Works with SubRip, WebVTT, SSA/ASS and TTML files on the ExoPlayer engine. Manual delay now also works with ExoPlayer.

### Home Hero Banner
A large cinematic banner with backdrop crossfades, title logo, rating, year, genres and synopsis, plus **Watch now** and **More info**. It mixes your first catalogs, skips titles you have already watched or are watching, preloads the next image, supports swiping, and pauses when the app is in the background. With *Reduce motion* (in the app or Android's accessibility settings) it stays still.

### Spoiler protection
Turn on **Hide Spoilers** (synced with your Stremio account) and thumbnails of episodes you have not watched yet are blurred — in the episode list and in the next-episode popup. Titles and navigation stay readable. Choose light, medium or strong blur.

### Smooth on high-refresh displays
Choose *Highest available* (90/120/144 Hz where the screen supports it), *System default*, or *Standard 60 Hz* to save battery. During playback the player's frame-rate matching takes over, and your choice is restored when you leave the player.

### Picture-in-picture
Leave the app while a video plays and it continues in a floating window (automatically on Android 12+).

### Everything from the original client
Stremio account and sync, add-on catalog and management, Discover, Library, Continue Watching with stream memory, ExoPlayer and MPV engines, audio/subtitle language preferences, subtitle styling, gestures, local torrent/HTTP streaming through the bundled Rust stream server, and Classic or Liquid Glass styles with a performance mode.

## Playback engines

| | ExoPlayer (default) | MPV |
|---|---|---|
| Decoding | Hardware (MediaCodec) with software fallback | FFmpeg software and hardware |
| Typical codecs | H.264, H.265/HEVC, VP9, AV1\*, AAC, Opus, FLAC, AC-3/E-AC-3\* | Nearly everything FFmpeg decodes |
| Subtitle files | SRT, VTT, SSA/ASS, TTML | All, with full libass rendering |
| Automatic subtitle sync | Yes | No (manual delay only) |

\* Depends on the device's decoders. HDR and passthrough formats (AC-3, E-AC-3, DTS) depend on hardware and the connected audio output. No player can promise universal codec support.

## Build

Requirements: JDK 21 (also on `PATH`), Android SDK 37, NDK `29.0.13846066`, Git with submodules.

```powershell
git clone --recurse-submodules https://github.com/slvjtr1/stremio-android.git
cd stremio-android
.\gradlew :app:assembleDebug            # debug APKs
.\gradlew :app:testDebugUnitTest        # unit + Robolectric Compose tests
.\gradlew :app:lintDebug                # Android Lint
.\gradlew :mpv-android-lib:assembleRelease :app:assembleRelease
```

The Rust stream server (`libstream_server.so`) is built by the release workflow. Local builds without it still run; torrent streaming is then unavailable and direct HTTP streams keep working. To build it locally see `.\gradlew compileNativeLibs` (Rust, `cargo-ndk` and vcpkg required).

Release builds are unsigned unless these Gradle properties or environment variables are set: `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. The `release-apk.yml` workflow expects the same values as repository secrets (`ANDROID_RELEASE_KEYSTORE_BASE64` for the keystore) and publishes split APKs plus SHA-256 checksums to GitHub Releases.

Official releases are signed with the Stremio Enhanced key, SHA-256 certificate fingerprint `92:A1:87:88:DB:C8:91:5E:5B:94:7A:AE:21:D6:92:31:71:D6:56:EA:87:98:C4:9A:15:22:2F:9C:1E:CF:91:56`. Check it with `apksigner verify --print-certs`.

The application id is `io.github.slvjtr1.stremioenhanced`, so it installs next to other Stremio clients.

## Privacy

Builds from this repository send no analytics or crash reports: Firebase has been removed, and the bundled PostHog SDK stays inactive unless a `POSTHOG_API_KEY` is supplied at build time (it can then be switched off in *Settings → Android Settings*). Subtitle synchronisation runs entirely on the device. Stremio's own services and the add-ons you install have their own privacy practices.

## Architecture

```text
app/                       Android app (Compose UI, view model, repositories)
  player/                  Backend-neutral Player API, ExoPlayer and MPV backends
  player/subsync/          Speech detector, offset/drift engine, ExoPlayer subtitle renderer
  core/display/            Refresh-rate and picture-in-picture controllers
  presentation/            Screens and components (Hero Banner, spoiler artwork, player UI)
third_party/mpv-android-lib/  Vendored mpv-android with prebuilt libmpv/FFmpeg
streamio-core-kotlin/      Stremio core (Rust) Kotlin bindings — submodule
stream-server/             Rust streaming server — submodule
```

Third-party components and their licenses are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License

Changes made in Stremio Enhanced are released under the [MIT License](LICENSE). The upstream project this fork is based on does not publish a license, so its original code is not covered by that grant. Bundled components keep their own licenses.

## Disclaimer

Stremio is a trademark of its respective owner. This project is an independent client for the open Stremio add-on ecosystem; it does not host or provide content.
