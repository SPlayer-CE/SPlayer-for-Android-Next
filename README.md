<div align="center">

<img alt="SPlayer for Android Next logo" width="120" height="120" src="public/icons/logo-next.png" />

<h2>SPlayer for Android Next</h2>

<p>🎵 Modern Android mobile music player built with Capacitor + Vue 3</p>

<p>Integrating Android native audio plugins, Kotlin foreground playback and audio capture services, supporting rich lyrics display and a wide range of audio formats</p>

[![Stars](https://img.shields.io/github/stars/SPlayer-CE/SPlayer-for-Android-Next?style=flat)](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/stargazers)
[![Release](https://img.shields.io/github/v/release/SPlayer-CE/SPlayer-for-Android-Next)](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/releases)
[![License](https://img.shields.io/github/license/SPlayer-CE/SPlayer-for-Android-Next)](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/blob/Android/LICENSE)
[![Issues](https://img.shields.io/github/issues/SPlayer-CE/SPlayer-for-Android-Next)](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/issues)

**English** | [简体中文](./README.zh-CN.md)

</div>

---

## Official Documentation & Distribution Statement

📖 **Official Documentation**: [https://next.sfa.l.cd/](https://next.sfa.l.cd/)

> ### Official Distribution Domains & Anti-Fraud Statement
>
> The primary distribution channel for this project is the GitHub repository. The following domains are authorized secondary distribution domains by the development team, strictly used for documentation access and download distribution:
> - **Next Android Documentation**: `next.sfa.l.cd`
> - **Legacy Android Documentation**: `legacy.sfa.l.cd`
>
> Except for the aforementioned domains and the official GitHub repository, any other subdomains under the root domain `l.cd` (including but not limited to other subdomains under `sfa.l.cd`) have no affiliation with this project or its development team, nor have they ever been authorized to distribute installation packages, publish project announcements, or provide any paid services, proxy services, or "customer support" in any form.
>
> Please stay vigilant against phishing websites, repacked or tampered APKs, and any paid fundraising campaigns conducted in the name of the project. Any disputes, losses, or legal risks arising from visiting or trusting unofficial channels shall be borne solely by the individual, and this project and its development team assume no responsibility.

---

## Overview

**SPlayer for Android Next** is a next-generation modern music player tailored specifically for the Android mobile platform.

The project is built on **Capacitor + Vue 3 + TypeScript**, bridging low-level operating system capabilities through customized Android native plugins:
- **Audio Architecture**: Powered by Android native **Media3 / ExoPlayer**, running on a dedicated `SPlayerPlayback` audio thread to manage playback, preloading, and system `MediaSession` integration;
- **Foreground Playback Service**: Implemented in native Kotlin (`PlaybackService`) to ensure seamless lock screen controls, headset remote commands, and persistent background playback without system kills;
- **Dual-Mode Lyric Engine**: Combining a hardware-accelerated native Android Canvas overlay with a Web physics animation engine, supporting dynamic line-by-line blur and floating dynamic island lyrics;
- **Embedded Mobile Runtime**: Bundling Node.js Mobile and a lightweight NanoHTTPD proxy to handle online API resolution, lyric fetching, and plugins locally on the device.

---

## Features

- 🎵 **Wide Audio Format Support** — MP3, FLAC, WAV, AAC, OGG, and more, decoded via Media3 and native Android audio pipelines
- 📝 **Rich Lyric Experience** — Support for TTML, QRC, KRC, YRC, LRC, LYS, and ASS, with word-by-word dynamic highlighting, line-by-line blur transitions, and floating dynamic island lyrics
- 📱 **Modern Mobile Interaction** — Touch-optimized FullPlayer interface with fluid FLIP-based Hero shared element transitions and gesture controls
- 🛡️ **Foreground Persistence** — Kotlin native `PlaybackService` with lock screen controls and system MediaSession notifications for reliable background playback
- 🎙️ **Audio Capture & Recognition** — Integrated native audio recording (AudioRecord) and music recognition services
- 📁 **Local Music Library** — High-performance local library scanning and tag management via Android Storage Access Framework (SAF), jaudiotagger, and native SQLite
- 🌐 **Streaming Server Support** — Seamless connection and streaming with Subsonic, Navidrome, Jellyfin, and Emby
- 🎨 **Adaptive Color Theming** — Real-time dynamic theme generation extracted from album covers, supporting Dark/Light modes and immersive edge-to-edge system bars
- 🎚️ **Real-Time Spectrum** — Low-overhead hardware FFT visualization
- ⚡ **Embedded Mobile API Service** — Built-in local Node.js Mobile runtime powering online resolution and plugin ecosystems

---

## Architecture

```
Vue 3 Renderer (UI / FullPlayer / Web Lyrics)
       │
       ▼ (Capacitor Plugin Bridge / bridge.ts)
Android Native Layer (Kotlin Plugins)
  ├── PlaybackManager (Dedicated SPlayerPlayback thread + Media3/ExoPlayer)
  ├── PlaybackService (Foreground Notification + System MediaSession)
  ├── MainPlayerLyricOverlayView (Native Canvas GPU-accelerated lyrics + dynamic blur)
  ├── LibraryScanner & Database (SAF permissions + jaudiotagger + SQLite)
  └── KotlinApiServer (NanoHTTPD local proxy server :13962)
       │
       ▼ (Local loopback proxy)
Node.js Mobile Runtime (:13233)
  └── Online API resolution, lyric retrieval, and mobile plugin runtime
```

---

## Development

### Requirements

- **Node.js** >= 22
- **pnpm** >= 10
- **JDK** 21 (Required for Android compilation; JDK 17 or lower will fail)
- **Android SDK** (API 34/35 support) and **Android NDK**

### Getting Started

```bash
# 1. Install dependencies (applies nodejs-mobile patches automatically)
pnpm install

# 2. Start Web UI dev server (launches Android UI and dev API in browser)
pnpm exec vite --config vite.config.android.ts --host 0.0.0.0
```

### Android Build Pipeline

```bash
# Full Android build pipeline (Web bundle -> Cap sync -> Node bundle -> Embedded assets)
pnpm build:android

# Build native APKs (inside android directory)
cd android
./gradlew assembleDebug    # Build Debug APK
./gradlew assembleRelease  # Build Release APK
```

### Linting & Verification

```bash
pnpm typecheck        # TypeScript check (tsc + vue-tsc)
pnpm lint             # ESLint check
pnpm format           # Prettier code formatting
pnpm android:check    # Kotlin static check & compilation (ktlintCheck + compileKotlin + detekt, JDK 21 required)
pnpm android:format   # Kotlin code formatting (ktlintFormat)
```

---

## Acknowledgements

Special thanks to the following open-source projects and technologies:

- [Capacitor](https://capacitorjs.com/) — Cross-platform mobile hybrid framework
- [Media3 / ExoPlayer](https://github.com/androidx/media) — Robust media playback engine for Android
- [applemusic-like-lyrics](https://github.com/Steve-xmh/applemusic-like-lyrics) — Apple Music-like lyrics rendering library
- [NeteaseCloudMusicApiEnhanced](https://github.com/neteasecloudmusicapienhanced/api-enhanced) — NetEase Cloud Music API enhanced
- [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) — Embedded Node.js runtime for Android

---

## License

This project is licensed under the [GNU Affero General Public License v3.0 (AGPL-3.0)](https://www.gnu.org/licenses/agpl-3.0.html).

- **Modification & distribution:** Any modification or distribution must also be released under **AGPL-3.0**, with the complete source code provided.
- **Derivative works:** Must adopt **AGPL-3.0** as well, retaining this project's license and copyright notice.
- **Attribution:** The original author and copyright information must be preserved. You may add your own notice for derivative works, but you must not remove or alter the original.
- **Commercial use:** If used for sale or any other for-profit purpose, the source code and a link to the original project must be provided. Due to third-party services involved, commercial use may carry legal risks.
- **No warranty:** The software is provided "as is", without warranty of any kind, as described in AGPL-3.0.

---

## Disclaimer

This project is intended for personal learning and research purposes only and must not be used for commercial or illegal activities. Certain features rely on third-party APIs; users are solely responsible for ensuring compliance with applicable laws and service agreements. The authors accept no responsibility for any direct or indirect consequences arising from the use of this project.
