# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Project Overview

SPlayer-Next — music player on **Electron + Vue 3 + TypeScript** for desktop and **Capacitor + Android Kotlin** for Android. Desktop uses Rust native modules (NAPI-RS) for audio decoding, system media integration, and Windows taskbar lyric; Android uses Media3/ExoPlayer, Capacitor plugins, and an embedded Node.js Mobile API service.

## Commands

```bash
pnpm install              # Install deps
pnpm dev                  # Build native (debug) + start Electron dev
pnpm build                # Full build (rimraf → native → typecheck → electron-vite)
pnpm build:{win,mac,linux}# Platform packages
pnpm typecheck            # tsc + vue-tsc (node + web targets)
pnpm lint / format        # ESLint / Prettier
pnpm build:native         # Rust only; add `--dev` for debug
pnpm build:web            # Android WebView bundle to dist/capacitor
pnpm cap:sync             # Sync dist/capacitor into android/ via Capacitor
pnpm build:android:node   # Bundle API/mobile-entry.ts for nodejs-mobile-cordova
pnpm prepare:android:embedded # Copy embedded Node assets/libs into android/app assets
pnpm build:android        # build:web -> cap sync -> build:android:node -> prepare embedded
```

`SKIP_NATIVE_BUILD=true` skips Rust during dev.

Android local flow:

- Web preview: `pnpm exec vite --config vite.config.android.ts --host 0.0.0.0` starts Android UI and a dev embedded API (`API/mobile-entry.ts`) on `SP_API_PORT` (default 13962 for Vite dev). Browser preview is not a native Capacitor container, so native plugins fall back to no-op or HTML audio behavior.
- Native sync/build: run `pnpm build:android`, then build an APK from `android/` with `gradlew assembleDebug` or `gradlew assembleRelease`.
- Release install helper: `SPlayer-for-Android-build-and-install-android-release.cmd` selects connected ADB devices, runs `pnpm build:android`, runs `gradlew assembleRelease`, signs an unsigned arm64-v8a APK with the debug keystore if needed, installs it, and launches `top.imsyy.splayer_next`.
- Do not run `pnpm build` for Android unless you explicitly need the desktop Electron production build; Android does not depend on desktop Rust native output.

`audio-engine` static-links FFmpeg via the `ffmpeg_audio` crate (vendor zip + cc-built at compile time). Zero environment dependency — no `FFMPEG_DIR` / `PKG_CONFIG_PATH`, no system FFmpeg required.

## Shell

The development shell is Git Bash on Windows. Write all terminal commands in bash syntax (`&&`, `cd`, etc.) — no PowerShell-only constructs. File paths remain in Windows format (backslashes).

## Architecture

### Process Model

- **Main** (`electron/main/`) — windows, IPC, native modules
- **Preload** (`electron/preload/`) — `contextBridge` exposing `window.api` (player/config/system/library/streaming/lyrics)
- **Renderer** (`src/`) — Vue 3 SPA
- **Lyric windows** (`windows/desktop-lyric`, `dynamic-island`, `taskbar-lyric`) — independent Vue entries sharing `windows/shared/`
- **Android WebView** (`dist/capacitor` generated from `vite.config.android.ts`) — same Vue SPA with `__SPLAYER_TARGET__ = "android"`
- **Android native layer** (`android/app/src/main/java/top/imsyy/splayer_next/android/`) — Capacitor plugins, Media3 playback, local cache/library/lyrics, LAN server, notification/media session
- **Embedded mobile API** (`API/`) — Node.js Mobile service for online APIs, lyrics, plugin runtime, config/stats compatibility, and routes proxied by Kotlin

### Native Modules (Rust + NAPI-RS)

Six `.node` modules in `native/`, built via `scripts/build-native.ts`, lazy-loaded by `electron/main/utils/nativeLoader.ts`. NAPI-RS auto-generates `index.d.ts`, imported via path aliases `@splayer/audio-engine`, `@splayer/audio-capture`, `@splayer/media-ctrl`, `@splayer/taskbar-lyric`, `@splayer/taskbar-thumbnail`, `@splayer/opencc`.

- `audio-engine` — `ffmpeg_audio` decode (static FFmpeg) + rodio playback + FFT + cover extraction. URLs wrapped as `Read + Seek` via `ffmpeg_audio::HttpAudioSource` (using `HttpCancelHandle` for cancellation/reset) — TLS handled in Rust (`reqwest` + `rustls`), cross-platform with no system deps. Pushes events (state/position/ended/outputStalled) via ThreadsafeFunction. Has load_token race protection and an `HttpCancelHandle` handle injected into `HttpAudioSource` for instant stop and reset.
- `audio-capture` — System sound / microphone capture for song recognition. Windows via WASAPI Loopback; Linux via PulseAudio (`libpulse-binding`, needs `libpulse-dev` at build time — CI `dev.yml`/`release.yml` install it). Collects 8 kHz mono f32 PCM.
- `media-ctrl` — Cross-platform system media controls (Windows SMTC / Linux MPRIS / macOS MPNowPlaying) + Discord RPC.
- `taskbar-lyric` — Windows taskbar lyric text rendering with RegistryWatcher / UiaWatcher / TrayWatcher.

Desktop Rust native modules are not packaged into Android. Android playback and system integration are implemented in Kotlin under `android/app/src/main/java/.../playback`.

### Android Runtime

Android is a Capacitor target that reuses the renderer where possible and swaps platform services through `src/services/bridge.ts`:

```
Vue app -> bridge.ts -> Capacitor plugin / KotlinApiServer
  -> AndroidNativePlaybackPlugin -> PlaybackManager -> Media3 ExoPlayer
  -> KotlinApiServer :13962 -> Node.js Mobile API :13233 for online APIs
```

- `capacitor.config.ts` sets `webDir = dist/capacitor`, transparent background, mixed content, status bar overlay, and Android WebView debugging.
- `vite.config.android.ts` builds a single-page WebView bundle with `base: "./"`, injects `cordova.js` for production builds, defines `__SPLAYER_TARGET__ = "android"`, and starts the embedded API dev server during Vite serve unless `SPLAYER_SKIP_EMBEDDED_API_DEV=true`.
- **`main.ts` installs `bridge` as `window.api` on Android** (`(window as ...).api = bridge`). Existing code calling `window.api.*` therefore routes through the bridge at runtime — but typecheck validates against the preload `index.d.ts` shape, not the bridge. Adding/upstreaming a new `window.api` method requires a matching `bridge.ts` entry (no-op/fallback on Android) or it crashes on device with `is not a function` while typecheck stays green.
- `MainActivity.kt` registers Android plugins: `AndroidNativePlayback`, `AndroidLocalLyric`, `AndroidMainLyric`, `AndroidDownload`, `AndroidCache`, `AndroidSongCache`, `AndroidLanShare`, `AndroidLibrary`, `ApiServer`, and `ExternalApi`.
- `KotlinApiServer` listens on port 13962 in the native app. It serves health checks/static Web assets/LAN sync/cache DB/external API routes and proxies most `/api/*` routes to Node.js Mobile on 127.0.0.1:13233.
- `API/mobile-entry.ts` is the Node.js Mobile entry. In packaged Android it defaults to `SP_API_HOST=127.0.0.1`, `SP_API_PORT=13233`, and `SP_EMBEDDED=1`; Vite dev overrides host/port for LAN preview.
- `API/mobile-server.ts` hosts the compatibility API surface: online music/lyrics, config, streaming placeholders, stats, plugin management, lyric matching, and Kotlin cache DB access.

### Android Feature Areas

- Playback: `AndroidNativePlaybackPlugin.kt` exposes load/play/pause/seek/volume/speed/status, notification permission, MediaSession metadata, FFT/spectrum, equalizer, dynamic-island floating lyric controls, and app shutdown/background behavior.
- Audio engine: `PlaybackManager.kt` owns a singleton Media3 `ExoPlayer`, `MediaSession`, foreground `PlaybackService`, notification actions, queue context, URL resolution, prefetch promotion, FFT (`FftAudioProcessor`) and EQ (`EqualizerAudioProcessor`).
- Local library: `AndroidLibraryPlugin.kt`, `LibraryScanner.kt`, and `LibraryDatabase.kt` use SAF directory permissions and a native SQLite database to scan/query/delete local tracks.
- Cache: `AndroidCachePlugin.kt`, `AndroidSongCachePlugin.kt`, `AudioCacheProvider.kt`, `DbCacheHelper.kt`, and `CacheStorage.kt` manage file caches, ExoPlayer audio cache, lyric/TTML/match DB caches, and bounded cleanup.
- Lyrics: `AndroidLocalLyricPlugin.kt` handles SAF lyric directories, sidecar matching, font import, and lyric indexing; `AndroidMainLyricPlugin.kt` renders the native main-player lyric overlay; dynamic-island lyric lives under `playback/DynamicIslandService.kt`.
- Downloads: `AndroidDownloadPlugin.kt` writes audio/lyric files through SAF and reports progress through Capacitor events.
- LAN/external API: `AndroidLanSharePlugin.kt`, `KotlinApiServer.kt`, and `ExternalApi*` implement LAN playback sync, browser client access, restricted external API routes, WebSocket heartbeat, and token checks.

### Android Folders

- `android/` — Gradle Android project generated by Capacitor and customized for Kotlin plugins, Media3 playback, nodejs-mobile-cordova assets, ABI splits, signing, and Android resources.
- `android/app/src/main/java/top/imsyy/splayer_next/android/` — native Android source grouped by `playback/`, `cache/`, `library/`, `lyric/`, `download/`, and `server/`.
- `android/app/src/test/java/.../lyric/` — JVM tests for Android lyric parsing, timeline, word segmentation, and local lyric path mapping.
- `API/` — embedded Node.js API source; `mobile-entry.ts` boots the service, `mobile-server.ts` owns HTTP routes, `plugins/` contains Android plugin runtime/registry/router/storage/network compatibility.
- `src/plugins/android*.ts` — typed Capacitor plugin wrappers for renderer code.
- `src/services/bridge.ts` — cross-platform boundary that chooses Electron APIs, Android native plugins, Android Web preview fallbacks, or embedded HTTP API calls.
- `dist/capacitor/` — generated Android WebView output; contains `nodejs-project/` after `pnpm build:android:node` and is synchronized into Android assets by `cap sync` / `prepare:android:embedded`.

### Playback Data Flow

```
User action → status store → IPC (player:load/play/pause/seek)
  → main process player.ts → audio-engine
  → Rust events (stateChanged/position/ended/outputStalled)
  → main broadcasts to renderer + syncs to media-ctrl
  → status store updates reactive state
  → playback.ts updates non-reactive time source
```

Android playback data flow:

```
User action -> status/media store -> bridge.ts
  -> AndroidNativePlayback Capacitor plugin
  -> PlaybackManager / ExoPlayer / MediaSession / PlaybackService
  -> Capacitor events (status/progress/ended/fft) back to renderer
  -> playback.ts keeps the non-reactive millisecond time source in sync
```

Android audio sources must be WebView/ExoPlayer-safe URLs. Do not feed cached absolute paths or raw `file://` URLs to preview/native playback; cached songs are exposed through embedded/Kotlin HTTP routes such as `/api/cache/song/play`, and LAN follower devices should use the host `/api/lanShare/audio` route instead of resolving the track locally.

### State Management

Two-tier position tracking — high-frequency animation vs. low-frequency UI:

- `src/stores/status.ts` — Pinia reactive. `position / duration / state / volume`, pushed ~5Hz from main. Drives progress bar, time display, play button.
- `src/services/playback.ts` — Non-reactive plain vars. `getCurrentTime()` interpolates between pushes; `usePlaybackTime()` reads in RAF loop for 60fps lyrics/spectrum without Vue reactivity.
- `src/stores/media.ts` — Pinia + shallowRef. Current `Track` (lightweight) + `TrackDetail` (lyrics, quality). Only `track + activeLyric` persisted to sessionStorage; never persist `TrackDetail` (large lyric strings cause memory issues).

### Streaming Subsystem

Server protocol clients live in the main process (`electron/main/services/streaming/`): Subsonic / Jellyfin / Emby adapters, safeStorage-backed config, Jellyfin/Emby session management, SQLite synchronization, and the authenticated cover protocol. Subsonic family (Navidrome / OpenSubsonic / Airsonic / Gonic / LMS) shares one adapter; types differ only as UI labels.

- `electron/main/services/streaming/config.ts` — encrypted config and secret-free renderer views.
- `electron/main/services/streaming/connection.ts` — connection tests, connect, and authenticated adapter requests.
- `electron/main/services/streaming/coverProtocol.ts` — `streaming-cover://` proxy registered for the default and `persist:main` sessions.
- `electron/main/services/streaming/adapters/` — Server response → unified `Track / Album / Artist / Playlist`. Trusts server's artist field; no client-side splitting.
- `services/streaming/session.ts` — Jellyfin/Emby `/Sessions/Playing` heartbeat + PlaySessionId state machine; called from `core/player.ts`.
- `stores/streaming.ts` — Server list, active state, and complete shallowRef arrays; main-process update events trigger SQLite snapshot reloads, with no polling or direct media-server access.
- Credentials — `electron/main/services/streaming/config.ts` encrypts via Electron `safeStorage` to `{userData}/app-data/config/streaming.json`. `accessToken / userId` remain in the bounded main-process session cache and are re-acquired on connect.

### Lyric System

Renderer pipeline (`src/services/lyric/`, shared by desktop and the Android WebView):

- `loader.ts` — the orchestration core. `loadForTrack` / `beginLoad` token guards races. Load order: preloaded lyric → local TTML repo → online by preference → plugin fallback → embedded. Platform loads wrap every online path in `withPluginPrefer` (upstream: when `preferPluginLyric` is on, the plugin result wins). The online resolver is loader-local `tryOnlineByPreference`, NOT the `resolve.ts` one — it adds: renderer `CacheManager "lyrics"` read/write cache (keyed `${platform}_${track.id}.json`), `platformCanUpgrade` prescreening (skip network when the platform's best format can't outrank the local format), smart mode parallel racing with progressive commit (first result commits, a later higher-ranked one replaces), `isLanWebClient()` guards (LAN followers receive lyrics pushed by the host — `beginLoad` skips local lyric-state reset to avoid flashing empty on track switch). `applyOnline` commits, then fires the TTML overlay attempt.
- `resolve.ts` — `resolveOnlineByPreference` here is still consumed by `src/services/download/lyric.ts` (download-side lyric resolution). Also hosts `resolveTTMLOverlay` (TTML only when `ttml` outranks the online format and `system.lyric.enableOnlineTTMLLyric` is on) and `resolvePluginLyric`.
- `request.ts` — thin bridge calls. `preload.ts` + `nextTrackPreloader.ts` warm the next track (desktop only; Android prefetch is native `prefetchUpcomingUrls`, so `initPlayer` skips installing the JS preload watchers when `isAndroidNative`).

Backends: desktop main (`electron/main/apis/common/lyric/{netease,qqmusic,kugou}.ts` byId/byQuery + `ttml.ts` → AMLL TTML DB); Android (`KotlinApiServer :13962` → Node `API/mobile-server.ts :13233`, same route shapes).

Formats parse in `src/utils/lyric/parse*.ts` (`ttml/qrc/krc/yrc/lrc/lys/ass/srt`); `DEFAULT_LYRIC_FORMAT_ORDER` ranks `ttml` first. Parsed shape is `LyricData` (`LyricLine/LyricWord/LyricSpan` in `shared/types/lyrics.ts`).

Caches: `lyricMatchCache` (fingerprint = title + artists + 5s duration bucket, 30d TTL) maps fuzzy hits to platform ids — TTML overlay for cross-source tracks depends on it; `lyricTtmlCache` (positive forever, negative 72h); renderer `CacheManager "lyrics"` namespace; local TTML repo (`matchLocalTTML`, desktop only) and sidecar files (desktop `player.readLyricFile`, Android SAF via `AndroidLocalLyricPlugin`).

Render surfaces — pick by target, never mix. The main-player lyric has a three-way component chain (same chain in `FullPlayer/index.vue` and `FullPlayerMobile.vue`, which mounts it twice for portrait/landscape layouts):

| Condition (first match wins) | Component | Renderer |
|---|---|---|
| `settings.lyric.engine === "amll"` | `Lyrics/AMLLLyrics.vue` | `@applemusic-like-lyrics/core` `LyricPlayer` (upstream library) |
| `isAndroid` | `FullPlayer/AndroidMainLyricHost.vue` | dual-mode adapter — see below |
| everything else (desktop default) | `Lyrics/index.vue` | self-built engine: `Lyrics/engine/` (line/word builders, springs, interlude, `renderer.css`), translation/romaji, `bg.ts`/`poster.ts` |

`AndroidMainLyricHost` resolves its own `renderMode` prop (`settings.lyric.engine === "kotlin" ? "kotlin" : "legacy"`):

- **kotlin** (`isAndroidNative` only) — pushes parsed `LyricLine[]` (JSON) + time/config through the `AndroidMainLyric` plugin to native `MainPlayerLyricOverlayView.kt` Canvas rendering (models in `LyricModels.kt`; JVM-tested timeline/segmentation in `android/app/src/test/.../lyric/`). JS stops its RAF tick (`usesNativeKotlinLyricClock`) — the native layer owns the clock; the host handles seek events, viewport sync, font-size sentinel (`ResizeObserver`), font-weight ×2 scaling (cap 1000), and pauses the overlay while dialogs/queue sheets cover it.
- **legacy** — renders `Lyrics/index.vue` inside the host (same engine as desktop, Android-tuned props like `applyScrollPreroll`).

Engine-setting semantics (`settings.lyric.engine`: `physics` | `amll` | `kotlin`): desktop `kotlin` migrates back to `physics` on load (`stores/settings.ts`); Android `physics` + `system.androidLyric.renderMode === "kotlin"` auto-upgrades to `kotlin`. The FullPlayer bottom bar shows plain text (`bottomBarLyricText`) in kotlin mode (native Canvas can't embed into the WebView DOM), otherwise the normal lyric component.

Other surfaces:

- Desktop lyric window (`windows/desktop-lyric`) — transparent always-on-top Electron window, full lines + word highlight, `pickPrimaryIndex` (overlap-aware, stays on the still-sounding line).
- Dynamic island (`windows/dynamic-island`; Android `playback/DynamicIslandService.kt`) — compact pill, `pickLatestStartedIndex` (switch the instant the next line starts), transport controls; Android adds floating-lyric controls through the playback plugin.
- Taskbar lyric (`windows/taskbar-lyric` + Rust `native/taskbar-lyric`, Windows only) — text embedded into the taskbar via Registry/Uia/Tray watchers.

Shared rules: `windows/*` must use `useNowPlayingSync` / `getNowPlayingCurrentMs()` from `windows/shared/composables/` — never reimplement sync, index, or interpolation. Time is ms everywhere. The global TTML switch is `system.lyric.enableOnlineTTMLLyric` (per-song toggle in `QuickActionsMenu.vue`).

### Android Port Traps (learned the hard way)

- `apiFetch` **rejects** with `[bridge] embedded API is not ready` during Node cold start — it never resolves `{ok:false}`. Every `await` behind a loading spinner needs try/finally (see `PluginMarket.refresh`).
- Fuzzy lyric IDs on Android persist only behind `shouldPersistLyricMatch`; same-playback TTML relies on `stashMatchedLyricId` (L1-only, 5min). Do not remove the stash, and do not "simplify" to unconditional persist (wrong songs would stick for 30 days).
- `NativeLogConsoleBridge` forwards logcat `*:E` into `console.error("[native:error] ...")`; `FATAL_LOG` bypasses all filters — keep it first, and keep the Kotlin list in sync with `src/utils/bridgeLogFilter.ts`.
- Ports: Kotlin `13962`, Node `13233`, Vite dev overrides host for LAN. SAF `content://` URIs are not file paths. Feed ExoPlayer only WebView-safe URLs (`/api/cache/song/play`, `/api/lanShare/audio`).
- `scripts/build-android-node.ts` (embedded API bundle): alias resolution must verify candidates with `stat().isFile()` — `access()` succeeds on directories (Windows) and esbuild then fails with `Incorrect function`. Electron main-process imports pulled into `API/` (e.g. kugou `config.ts` → `@main/store`) need an `embedded-*-stub` plugin registered BEFORE the generic `@main/` alias plugin — the store can't run under Node.js Mobile (top-level `electron` import).
- `postinstall` is a three-step chain: `node node_modules/electron/install.js && electron-rebuild -f -w better-sqlite3 && tsx scripts/patch-nodejs-mobile-cordova.ts`. The patch step is mandatory for Android builds — losing it breaks `build:android`. Run `pnpm install` in a regular terminal (not sandboxed), or the patch fails with EPERM.

### Type System

- `shared/types/player.ts` — `Track`, `TrackDetail`, `Artist`, `Album`, `AudioQuality`, `PlayerState`, `PlayerStatus`, `PlayerEvent`, `LoadOptions`, `LoadResult`, `IpcResponse`
- `shared/types/lyrics.ts` — `LyricFormat`, `LyricSource (external | embedded | online)`, `LyricData`, `LyricLine`, `LyricWord`, `LyricSpan`
- `shared/types/platform.ts` — `Platform (netease | qqmusic | kugou)`
- `shared/types/streaming.ts` — `StreamingServerType`, `StreamingServerConfig`, `StreamingPingResult`, `StreamingAuthResult`, etc.

`Track` is for queue storage (no heavy data); `TrackDetail` loads on demand.

### Settings Schema

Declarative — defined in `src/settings/schema.ts`, types in `src/types/settings-schema.ts` (`SettingCategory → SettingSection → SettingItem`). Items bind via `{ store: "settings"|"theme", path: "nested.path" }`; `system.*` paths route through IPC to main config. Tag support on section/item via `SettingTag = { text; type? }` for Beta/experimental badges. i18n keys: `settings.section.{id}` / `settings.{itemKey}.{label,description}`.

### Data Storage

```
{userData}/app-data/        # Unified data directory, separate from Chromium cache data
├── config/
│   ├── settings.json       # Main config (electron/main/store/)
│   ├── streaming.json      # Streaming credentials (safeStorage encrypted)
│   └── lastfm.json         # Last.fm credentials (safeStorage encrypted)
├── database/library.db     # Music library (better-sqlite3, WAL)
├── cache/                  # covers/ (cache:// protocol) + artists/ backgrounds/ songs/
├── logs/                   # App logs + native/
└── plugins/                # scripts/ data/ logs/

# All paths are defined centrally in electron/main/utils/paths.ts
```

Renderer IndexedDB (localforage): `splayer/library`, `splayer/queue`. Local playlists are stored in
SQLite through the main-process playlist service; the old `splayer/playlists` store is migration-only.

Android storage:

- Web assets live under Android app assets after Capacitor sync; embedded Node project is copied to `android/app/src/main/assets/www/nodejs-project`.
- Kotlin cache/library/lyric DB data lives in app-private storage and is accessed through Android plugins or `KotlinApiServer` routes; SAF-selected music/lyric/download directories are represented by persisted `content://` URI permissions.
- Node.js Mobile config/stats compatibility defaults under the embedded API config directory; Android routes proxy sensitive config/session operations only for local requests.

### Cover Image

Rust extracts 300x300 JPEG thumbnail to `{userData}/app-data/cache/covers/` during decode; renderer reads via `cover://{filename}` protocol. Original via `getCoverRaw()` for SMTC, never cached. Authenticated streaming covers use the main-process `streaming-cover://` proxy.

### Config Store (Main)

`electron/main/store/` is custom (not electron-store). Reads/writes `{userData}/app-data/config/settings.json` (path via `electron/main/utils/paths.ts`), merges with defaults from `shared/defaults/settings.ts`. Supports dot-path access (`store.get("system.taskbarProgress")`), atomic writes, schema migrations.

### i18n

Renderer uses `vue-i18n` with `src/i18n/locales/{zh-CN,en-US}.json`. Main process has a lightweight translation table (`electron/main/utils/i18n.ts`) for tray/thumbar; locale synced via `system:setLocale` IPC.

### Path Aliases

```
@/                     → src/                   (renderer, tsconfig.web.json)
@shared/               → shared/                (both processes)
@main/                 → electron/main/         (main, tsconfig.node.json)
@windows/              → windows/               (lyric windows)
@splayer/audio-engine  → native/audio-engine    (main)
@splayer/audio-capture → native/audio-capture   (main)
@splayer/media-ctrl    → native/media-ctrl      (main)
@splayer/taskbar-lyric → native/taskbar-lyric   (main)
@splayer/taskbar-thumbnail → native/taskbar-thumbnail (main, thumbnail toolbar)
@splayer/opencc        → native/opencc          (CJK conversion)
```

Android Vite also defines `@root` -> repository root for Android-only Web builds.

### Android Audio Dependencies

- Desktop audio decoding: Rust `audio-engine` uses `ffmpeg_audio` + statically built FFmpeg + `rodio`; this is desktop-only.
- Android native playback: Kotlin uses `androidx.media3:media3-exoplayer:1.8.0`, `androidx.media3:media3-session:1.8.0`, `androidx.media:media:1.7.0`, custom `AudioProcessor`s for FFT/EQ, and a foreground `PlaybackService`.
- Embedded API runtime: `nodejs-mobile-cordova` provides Node.js in the Android app; `@neteasecloudmusicapienhanced/api` is copied and patched into the packaged vendor tree by `scripts/build-android-node.ts`.
- Local HTTP/WebSocket: `org.nanohttpd:nanohttpd` and `nanohttpd-websocket` power `KotlinApiServer`, LAN sync, cache DB proxy, and external API access.

## Conventions

### Comments — Chinese, with JSDoc

All comments in Chinese. Methods use standard JSDoc with `@param name - description` and
`@returns` when meaningful:

```ts
/**
 * <Chinese method description>
 * @param trackId - <Chinese parameter description>
 * @returns <Chinese return description>
 */
```

Forbidden: `// ───` separator lines (including ones with section titles), prose-style multi-paragraph comments, restating-the-obvious comments, numbered enumerations (`1. 2. 3.`) inside comments. Write comments only when the **why** is non-obvious.

### Code Organization

Split logic into files rather than separator comments. Don't extract a helper for one-place callers (3+ uses justify it). No "just in case" defensive code or fallbacks for impossible scenarios. No configurable knobs (timeouts / retries / buffer sizes) unless required — write constants. Don't break errors into per-case enums; `anyhow` or plain `Error` is usually enough.

### Memory Discipline

Memory is a hard requirement. The main process logs memory usage through `app.getAppMetrics()`
60 seconds after launch and then every 10 minutes. When a change touches rendering, caching, or
IPC, verify before and after with these samples.

- **Images by display size** — anything blurred, sampled, or rendered small uses the 300px `cover` thumbnail (player blur background, color extraction, lists). `coverOriginal` only for the visible large cover and poster export. Large `<img>`: add `decoding="async"`; preload with `img.decode()` before fading in.
- **Compositing layers are budgeted** — never put `will-change` in CSS on unbounded element collections; promote dynamically and only near the viewport (lyric engine `lineWillChange` pattern). New full-screen `filter: blur` / `backdrop-filter` layers need justification.
- **Hidden = silent** — high-frequency pushes (`position` / `fftData` / `position-sync`) must not reach hidden windows: `broadcast(channel, data, true)` or an `isVisible()` gate; consumers recover from the next push (≤200ms), no resync needed. Low-frequency state events (`stateChanged` / `ended` / track-change) always go through. RAF loops and canvases must stop when their surface is hidden (engine `freeze()` / `visibilitychange` pattern).
- **In-memory caches must be bounded** — every module-level Map/array cache needs an eviction rule (subsonic `viewAuthCache` evicts per-server). Never retain `TrackDetail`-sized data beyond the current track.

### Units

Frontend time is **milliseconds** everywhere. Rust engine uses seconds internally; `toMs()` in `electron/main/ipc/player.ts` converts.

### Types & Persistence

Never hand-write native module types — import from `@splayer/*`. Use `shallowRef` for `Track` arrays/collections (avoid deep proxy). Vue proxied objects can't be cloned by IDB (`DataCloneError`); use `toRaw` before persisting.

### Auto-imports

In Vue components, `vue / pinia / vue-router / @vueuse/core / vue-i18n` are auto-imported, and UI components in `src/components/` are auto-registered.
Icon components used only in Vue templates are auto-imported. Do not manually import them in
`<script setup>`; import an icon explicitly only when it is referenced by script code.

### Logging (Main Process)

Use scoped loggers from `@main/utils/logger` (`coreLog / playerLog / mediaLog / trayLog / taskbarLog / nativeLog`, etc.). Don't import `electron-log` directly.

### IPC Listeners

In preload's `onEvent`, always `ipcRenderer.removeAllListeners()` before adding a new listener (HMR accumulates otherwise). Renderer composables call the returned `unsubscribe` in `onBeforeUnmount`.

### Popup Layers (SDialog / SDrawer / SPopover)

Every popup combines two systems that must stay co-present: upstream's unified stacking via `usePopupZIndex` (from `@/composables/useZIndex`; components pass `zIndex`/report `onOpenChange`, fixing #227) and the Android adaptations — `useBackClosable` (back button closes the popup, `@/composables/useAndroidBack`), `preventOpenAutoFocus` (SDialog), and hover→click trigger downgrade on Android (`effectiveTrigger`, SPopover). When editing these components, keep both sides; don't "simplify" either away.

### Android Boundaries

- Keep Android-specific native APIs behind Capacitor plugins or `src/services/bridge.ts`; do not import Android plugin wrappers directly into unrelated shared modules.
- Keep desktop Electron IPC and Android HTTP/Capacitor behavior aligned at the `Window["api"]` shape where practical, but document unsupported Android methods with explicit no-op/fallback behavior.
- Preserve the port split: Kotlin app server `13962`, packaged Node.js Mobile `13233`, Vite dev embedded API host `0.0.0.0` with `SP_API_PORT` defaulting to `13962`.
- Browser Android preview (`isAndroidPreview`) is not a native container: native plugin calls must degrade to HTML audio/no-op behavior and must not assume SAF, MediaSession, ExoPlayer, or app-private storage.
- LAN requests must stay gated: sensitive Node routes (`/api/apis/call`, cookie/session/login routes) remain local-only unless a dedicated external API route performs its own token/allowLan checks.

### Prettier

Double quotes, semicolons, 100-char width, trailing commas.

Before committing, run Prettier on every file included in the commit and verify the formatted
working tree before creating the commit. Do not leave formatting-only changes from the current task
outside the commit.

### Shared Types

Put cross-process types (`LocaleCode / SystemConfig / StreamingServerType`, etc.) in `shared/types/`.

### Commit Messages

Use Conventional Commits with a Chinese summary: `<type>: <summary>`. Keep the title on one line;
do not add a body or bullets unless explicitly requested. Use the type that matches the change,
such as `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `build`, `ci`, `style`, or `chore`.
