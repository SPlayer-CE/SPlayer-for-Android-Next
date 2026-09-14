# AGENTS.md

本文档为在本项目中工作的 AI 编程助手（如 Codex、Claude Code、Antigravity 等）提供代码规范、架构指引与开发实践。

## Karpathy 编程原则

**Tradeoff:** 这些原则偏向谨慎而非速度。对于简单任务，自行判断。

### 1. 先思考再编码

**不要假设。不要隐藏困惑。展示权衡。**

实现之前：

- 明确说明你的假设。如果不确定，提问。
- 如果存在多种解释，展示它们 - 不要默默选择。
- 如果存在更简单的方法，说出来。必要时反驳。
- 如果某些事情不清楚，停下来。说明什么让你困惑。提问。

### 2. 简单优先

**解决问题的最小代码。不要投机。**

- 不添加超出要求的功能。
- 不为一次性使用的代码创建抽象。
- 不添加未请求的"灵活性"或"可配置性"。
- 不为不可能的场景添加错误处理。
- 如果 200 行可以是 50 行，重写它。

问自己："高级工程师会说这过度复杂了吗？" 如果是，简化。

### 3. 精确修改

**只修改必须修改的。只清理自己的遗留。**

编辑现有代码时：

- 不要"改进"相邻的代码、注释或格式。
- 不要重构没有问题的东西。
- 匹配现有风格，即使你会以不同方式做。
- 如果注意到不相关的死代码，提及它 - 不要删除它。

当你的更改产生孤立代码时：

- 删除你的更改使其未使用的导入/变量/函数。
- 除非被要求，不要删除预先存在的死代码。

测试：每一行更改都应该直接追溯到用户的请求。

### 4. 目标驱动执行

**定义成功标准。循环直到验证。**

将任务转化为可验证的目标：

- "添加验证" → "为无效输入编写测试，然后使它们通过"
- "修复 bug" → "编写重现它的测试，然后使它通过"
- "重构 X" → "确保测试在之前和之后都通过"

对于多步骤任务，说明简要计划：

```
1. [步骤] → 验证: [检查]
2. [步骤] → 验证: [检查]
3. [步骤] → 验证: [检查]
```

强大的成功标准让你能够独立循环。弱标准（"让它工作"）需要持续澄清。

---

**这些原则有效的标志：** diff 中更少不必要的更改，更少因过度复杂而重写，澄清问题在实现之前而不是错误之后提出。

---

## 项目概览

SPlayer-Next 是一款现代跨平台音乐播放器：

- **桌面端**：基于 **Electron + Vue 3 + TypeScript**，通过 Rust 原生模块（NAPI-RS）实现底层音频解码、系统媒体集成与 Windows 任务栏歌词。
- **Android 端**：基于 **Capacitor + Android Kotlin**，使用 Media3/ExoPlayer 负责音频播放、Capacitor 原生插件打通底层接口，并运行内嵌 Node.js Mobile 提供在线 API、歌词解析与插件运行时。

## 常用命令

```bash
pnpm install              # 安装依赖
pnpm dev                  # 编译原生模块(Debug)并启动 Electron 开发环境
pnpm build                # 完整构建 (rimraf → native → typecheck → electron-vite)
pnpm build:{win,mac,linux}# 打包对应桌面平台安装包
pnpm typecheck            # 类型检查 (tsc + vue-tsc，涵盖 Node 与 Web 目标)
pnpm lint / format        # ESLint 代码检查 / Prettier 自动格式化
pnpm test:node / test:web # 单元测试 (node:test / vitest)
pnpm test:native          # Rust 原生模块测试 (cargo test --workspace)
pnpm build:native         # 仅构建 Rust 原生模块 (加 `--dev` 为 Debug 构建)
pnpm build:web            # 构建 Android WebView 资源包至 dist/capacitor
pnpm cap:sync             # 同步 dist/capacitor 资源至 android/ 目录
pnpm build:android:node   # 打包嵌入式 API (API/mobile-entry.ts) 供 nodejs-mobile 使用
pnpm prepare:android:embedded # 复制嵌入式 Node 资源至 android/ 资源目录
pnpm build:android        # 完整安卓构建流水线 (build:web -> cap:sync -> build:android:node -> prepare)
pnpm android:check        # Kotlin 静态检查与编译 (ktlintCheck + compileKotlin + detekt，需 JDK 21)
pnpm android:format       # Kotlin 代码格式化 (ktlintFormat)
pnpm android:check:log    # 运行 Kotlin 检查并将输出写入 android-check.log
```

- **开发说明**：开发时可设置环境变量 `SKIP_NATIVE_BUILD=true` 跳过 Rust 编译。桌面端 `audio-engine` 通过 `ffmpeg_audio` crate 静态链接 FFmpeg，无需系统安装 FFmpeg。
- **Android 本地开发流程**：
  - **Web 预览**：`pnpm exec vite --config vite.config.android.ts --host 0.0.0.0` 启动 Android UI 及开发版嵌入式 API（`SP_API_PORT` 默认 13962）。浏览器预览非原生容器，原生插件将降级为无操作或 HTML 音频播放。
  - **原生构建**：执行 `pnpm build:android`，然后在 `android/` 目录下执行 `gradlew assembleDebug` 或 `gradlew assembleRelease`。
  - **Kotlin 校验**：使用 `pnpm android:check`，环境必须为 JDK 21（JDK 17 会报 `invalid source release: 21`）。
  - **真机安装脚本**：运行 `SPlayer-for-Android-build-and-install-android-release.cmd`，自动检测 ADB 连接设备、构建打包、用调试证书签名（若未签名）并安装启动。
  - **注意**：除非需要桌面端 Electron 生产包，否则开发 Android 时切勿运行 `pnpm build`。

## 终端与环境

开发终端统一使用 Windows 下的 **Git Bash**。所有终端指令必须符合 Bash 语法（支持 `&&`、`cd` 等），禁止使用 PowerShell 特有语法；文件路径仍采用 Windows 格式（反斜杠或斜杠）。

## 架构体系

### 进程模型

- **主进程（Main）**（`electron/main/`）：管理窗口、IPC 通信、原生模块。
- **预加载脚本（Preload）**（`electron/preload/`）：通过 `contextBridge` 向渲染进程暴露 `window.api`（player/config/system/library/streaming/lyrics 等）。
- **渲染进程（Renderer）**（`src/`）：Vue 3 单页应用。
- **独立歌词窗口**（`windows/desktop-lyric`, `dynamic-island`, `taskbar-lyric`）：独立的 Vue 入口，共享 `windows/shared/`。
- **Android WebView**（`dist/capacitor`）：由 `vite.config.android.ts` 构建的相同 Vue SPA，内置 `__SPLAYER_TARGET__ = "android"`。
- **Android 原生层**（`android/app/src/main/java/top/imsyy/splayer_next/android/`）：Capacitor 插件、Media3 播放管理、本地数据库/缓存/歌词、局域网服务与媒体通知。
- **嵌入式移动端 API**（`API/`）：Node.js Mobile 运行环境，负责在线 API、歌词获取、插件系统以及 Kotlin 代理的接口。

### 原生模块 (Rust + NAPI-RS)

共 6 个 `.node` 模块位于 `native/`，通过 `scripts/build-native.ts` 构建，由主进程懒加载。通过路径别名 `@splayer/*` 导入：

- `audio-engine`：基于 `ffmpeg_audio`（静态 FFmpeg）+ `rodio` 实现解码播放与封面提取；URL 通过 `reqwest + rustls` 实现流式读取并支持随时取消；通过 ThreadsafeFunction 推送播放状态。
- `audio-capture`：系统音频回放与麦克风采集（用于听歌识曲），Windows 下基于 WASAPI Loopback，Linux 下基于 PulseAudio。
- `media-ctrl`：跨平台系统媒体控制（Windows SMTC / Linux MPRIS / macOS MPNowPlaying）与 Discord RPC。
- `taskbar-lyric`：Windows 任务栏歌词文本渲染（基于 Registry / Uia / Tray 监听器）。
- `taskbar-thumbnail`：Windows 缩略图工具栏。
- `opencc`：简繁中文转换。

_(注：Rust 原生模块不打包进 Android 端，Android 的播放与系统集成由 Kotlin 原生实现。)_

### Android 运行时

Android 是基于 Capacitor 的构建目标，通过 `src/services/bridge.ts` 抽象抹平平台差异：

```
Vue App -> bridge.ts -> Capacitor 插件 / KotlinApiServer (:13962)
  -> AndroidNativePlaybackPlugin -> PlaybackManager -> Media3 ExoPlayer
  -> KotlinApiServer (:13962) -> Node.js Mobile API (:13233) 处理在线请求
```

- `capacitor.config.ts`：配置 `webDir = dist/capacitor`、透明背景、允许混合内容、状态栏全屏沉浸（`SystemBars.insetsHandling: "disable"`，安全区由 CSS 变量控制）。
- **API 桥接原则**：`main.ts` 在 Android 端会将 `bridge` 挂载为 `window.api`（`(window as any).api = bridge`）。任何向 `window.api` 新增的方法，必须在 `bridge.ts` 中同步提供 Android 端的对应实现或降级空实现（no-op），否则在设备运行时会因找不到方法而崩溃。
- `MainActivity.kt` 注册插件：`AndroidNativePlaybackPlugin`、`AndroidClipboardPlugin`、`AndroidAppIconPlugin`、`AndroidLocalLyricPlugin`、`AndroidMainLyricPlugin`、`AndroidDownloadPlugin`、`AndroidCachePlugin`、`AndroidSongCachePlugin`、`AndroidLanSharePlugin`、`AndroidLibraryPlugin`、`ApiServerPlugin` 与 `ExternalApiPlugin`。
  - 在 `attachBaseContext` 中强制锁定 `config.fontScale = 1.0f`，防止系统辅助字体放大破坏 WebView 排版。
  - 在版本升级时（`versionCode` 变动）通过 `clearWebViewCacheOnUpgrade` 清除 WebView 资源缓存。
  - 横屏沉浸模式下同时隐藏状态栏与手势导航条（`PREF_IMMERSIVE_LANDSCAPE`）。
- `KotlinApiServer`：原生 NanoHTTPD 服务（端口 13962），处理健康检查、静态资源、局域网同步及缓存查询，并代理 `/api/*` 到 Node 移动服务（端口 13233）。

### Android 功能模块划分

- **播放与音频引擎**：
  - `AndroidNativePlaybackPlugin.kt`：向前端暴露起播、暂停、跳转、音量、均衡器、动态岛悬浮歌词及前台服务控制。
  - `PlaybackManager.kt`：拥有专用的 `HandlerThread("SPlayerPlayback", Process.THREAD_PRIORITY_AUDIO)`，统一驱动 ExoPlayer、MediaSession 与 Media3 `DefaultPreloadManager`（预载下一曲开头 10s）。跨线程交互严格通过 `runOnPlaybackThread`（异步）与 `onPlaybackThread`（同步）收口，状态读取使用 volatile 线程安全快照。
  - `PlaybackService.kt`：在 `onCreate` 中立即于主线程通过轻量占位通知升级为前台服务，彻底解耦播放线程拥塞，规避 Android 14+ 前台服务启动超时（FGS Timeout）崩溃。
  - `PlaybackUrlResolver.kt` + `android-router.ts`：插件解析音源候选并行竞速，极速缩短切歌延迟。
  - `webViewVisible` 机制：当 App 切到后台时，静默高频频谱/进度推送以节省功耗。
- **本地音乐库**：`AndroidLibraryPlugin.kt`、`LibraryScanner.kt`、`LibraryDatabase.kt` 结合 SAF 目录权限与 `jaudiotagger`，使用原生 SQLite 扫描和管理本地音频。
- **缓存体系**：`AndroidCachePlugin.kt`、`AndroidSongCachePlugin.kt`、`AudioCacheProvider.kt`、`AudioPrefetchTtlIndex.kt`，管理音频流缓存、封面、歌词 DB 缓存与启动清理。
- **歌词渲染系统**：
  - `AndroidLocalLyricPlugin.kt` 管理 SAF 歌词目录、字体导入与外挂歌词检索。
  - `AndroidMainLyricPlugin.kt` + `MainPlayerLyricOverlayView.kt`：全屏歌词 Canvas 原生渲染层。
  - `LyricBlurController.kt`：逐行模糊控制器，使用 0.5x 降采样位图 LRU 缓存（上限为最大堆内存的 1/8，介于 16MB~48MB 之间）、模糊档位双位图叠化切换；API 31+ 上对退场直绘行启用 `RenderNode` + `RenderEffect` GPU 硬件模糊。
  - `AndroidMainLyricHost.vue`：宿主适配层，使用模块级所有权令牌（`activeKotlinHostToken`）解决横竖屏旋转重建时的竞态清空问题，并逐帧上报弹出层触摸避让矩形（`touchExclusionSelector`）。
- **移动端全屏播放器 (FullPlayerMobile)**：
  - `FullPlayerMobile.vue` + `PlayerData.vue`：通过单层 CSS Grid 重叠布局消除位移回流，运用 FLIP 原则实现封面与文字的 60fps 平滑过渡。
  - 缩放计算基于较大矩形高度以避免文字模糊；麦克风等子图标通过透明度与位移过渡而非改变宽度（避免 Layout Reflow）；组件销毁时在 `onBeforeUnmount` 中严格清理动画定时器与 RAF；非 Hero 的副信息元素在起飞前淡出、返回时淡入。
- **局域网与外部 API**：`AndroidLanSharePlugin.kt`、`KotlinApiServer.kt` 负责跨端同步、网页端播放控制与受限外部 API 访问。

### 播放数据流

**桌面端：**

```
用户操作 → status store → IPC (player:load/play/pause/seek)
  → 主进程 player.ts → audio-engine (Rust)
  → Rust 事件广播 (stateChanged/position/ended)
  → 主进程同步 media-ctrl 并广播渲染进程
  → status store 更新响应式状态
  → playback.ts 更新高频非响应式时间源
```

**Android 端：**

```
用户操作 → status/media store → bridge.ts
  → AndroidNativePlayback Capacitor 插件 (派发至 SPlayerPlayback 线程)
  → PlaybackManager (runOnPlaybackThread / onPlaybackThread)
  → ExoPlayer / MediaSession / DefaultPreloadManager
  → Capacitor 原生事件 (status/progress/ended/fft) 回传渲染进程
  → playback.ts 维持非响应式时间源同步
```

_注意：Android 端音频源必须是 WebView 或 ExoPlayer 安全的有效 URL（如 `/api/cache/song/play`、`/api/lanShare/audio`），禁止传入裸文件绝对路径或 `file://` 协议。_

### 状态管理

采用高低频分离的双层架构：

- `src/stores/status.ts`：Pinia 响应式状态（`position / duration / state / volume`），约 5Hz 低频更新，驱动进度条、播放按钮与基础 UI。
- `src/services/playback.ts`：非响应式普通变量。`getCurrentTime()` 在推送间隙进行线性插值；`usePlaybackTime()` 在 RAF 循环中读取，为歌词滚动与频谱提供 60fps 无额外 Vue 响应式开销的高性能驱动。
- `src/stores/media.ts`：Pinia + `shallowRef`，维护当前轻量 `Track` 及按需加载的 `TrackDetail`（只将当前曲目基本信息持久化到 sessionStorage，切勿持久化庞大的 TrackDetail 歌词对象）。

### 流媒体子系统 (Streaming)

客户端适配层位于主进程（`electron/main/services/streaming/`）：

- 支持 Subsonic 协议族（Navidrome、OpenSubsonic、Airsonic、LMS 等统一适配器）以及 Jellyfin / Emby。
- 凭证加密保存在 `{userData}/app-data/config/streaming.json`（基于 Electron safeStorage）。
- 封面代理：注册 `streaming-cover://` 协议处理带鉴权的流媒体封面。
- `stores/streaming.ts`：仅保存服务器列表与浅响应式数据，主进程数据变更触发 SQLite 增量快照，不直连流媒体服务器。

### 歌词系统

渲染管线（`src/services/lyric/`，桌面与 Android WebView 共享）：

- `loader.ts`：加载核心。按优先级调度：预载歌词 → 本地 TTML 库 → 在线首选平台 → 插件兜底 → 内嵌歌词。通过 `currentToken` 守卫并发竞态；已渲染有效歌词时若后续尝试失败绝不清空现有歌词。
- **TTML 自动升级机制**：仅在 `lyricSourcePreference === "auto"` 且网络 TTML 优于普通格式时触发尝试。用户显式指定的音源不会被静默篡改。单个平台失败仅丢弃该候选，不中断跨平台兜底。
- **解析与格式**：`ttml / qrc / krc / yrc / lrc / lys / ass / srt`，TTML 优先级最高。统一定义为 `LyricData`（`LyricLine/LyricWord/LyricSpan`）。
- **歌词渲染表面选择**：

| 条件（自上而下匹配）               | 渲染组件                              | 渲染机制                                                |
| ---------------------------------- | ------------------------------------- | ------------------------------------------------------- |
| `settings.lyric.engine === "amll"` | `Lyrics/AMLLLyrics.vue`               | `@applemusic-like-lyrics/core` LyricPlayer              |
| `isAndroid` (安卓环境)             | `FullPlayer/AndroidMainLyricHost.vue` | 双模适配器（Kotlin 原生 / Legacy Web）                  |
| 其它情况（桌面默认）               | `Lyrics/index.vue`                    | 自研物理动效引擎（逐词/逐行构建器、弹簧系统、间奏动画） |

`AndroidMainLyricHost` 细节：

- **kotlin 模式**：将解析好的歌词推送至原生 `MainPlayerLyricOverlayView.kt` 进行 Canvas 绘制。前端停止自身 RAF 歌词时钟（由原生主导），宿主负责视口同步、字体测量、弹窗触摸避让及所有权令牌保护。
- **legacy 模式**：在 WebView 内部渲染 Web 版 `Lyrics/index.vue`。

---

## Android 关键踩坑经验与核心禁忌

1. **接口冷启动超时拒绝**：`apiFetch` 在 Node.js Mobile 未就绪时会**直接 reject** 抛出 `[bridge] embedded API is not ready`，绝不会 resolve `{ok: false}`。所有带 loading 的异步调用必须包裹在 `try ... finally` 中结束 loading 态。
2. **前台服务启动超时崩溃（Android 14+ FGS Timeout）**：Android 14+ 要求调用 `startForegroundService()` 后约 10 秒内必须调用 `Service.startForeground()`。若将该调用排队到专属音频线程，慢速设备在初始化 ExoPlayer/PreloadManager 时会直接触发 `ForegroundServiceDidNotStartInTimeException` 导致 App 闪退。**解决方案**：`PlaybackService.onCreate` 必须在主线程立即使用轻量级占位通知提升为前台服务，之后再异步连接音频线程并在就绪后更新为 MediaStyle 完整通知。
3. **Media3 播放线程安全铁律**：ExoPlayer、MediaSession 与 `DefaultPreloadManager` 必须且只能在专属 `SPlayerPlayback` HandlerThread 上操作，绝对禁止从 Android 主线程或 Capacitor 桥接线程直接触碰！所有公开 API 必须通过 `runOnPlaybackThread`（异步）或 `onPlaybackThread`（同步锁）派发；跨线程状态读取走 volatile 缓存快照。
4. **屏幕旋转导致原生歌词空白（所有权令牌机制）**：在移动端横竖屏切换时，竖屏与横屏两个 `AndroidMainLyricHost.vue` 实例会在同一次 Vue Patch 中相继卸载与挂载。如果旧实例卸载时的 `clear()` 比新实例的 `setLyrics()` 晚一步到达原生层，刚装载的歌词就会被清空且不再恢复。**解决方案**：使用模块作用域所有权令牌 `activeKotlinHostToken`，旧实例在 `onBeforeUnmount` 发现令牌已被新实例接管时，主动跳过清理。
5. **原生歌词 Canvas 模糊与功耗控制**：在 Canvas 上逐帧生成大半径模糊位图会引发剧烈的 GC 抖动和掉帧。`LyricBlurController.kt` 规范：
   - 位图 LRU 缓存预算上限严格限制为堆内存的 1/8（16MB~48MB）。
   - 采用 0.5x 降采样渲染模糊位图（计算开销降至 1/8，视觉近无损）。
   - 档位变化过渡期采用「起点档 + 目标档」双位图叠化，禁止逐帧新建位图。
   - 处于退场浮动衰减态的动态行，在 API 31+ 上录入 `RenderNode` 并通过 GPU `RenderEffect` 进行硬件模糊（限制最多 3 个实例），低版本则直绘无模糊。
6. **移动端 Hero FLIP 动效防抖与无泄漏**：
   - 严禁对 `width`、`font-size` 等引发 Layout Reflow（重排）的属性做动画，一律使用 CSS `transform: translate3d(...) scale(...)` 与 `opacity`。
   - 文本缩放的基准矩形（layoutRect）必须选择字号较大的那一侧，从大到小 scale 保持清晰，从小放大必定失真。
   - 麦克风等伴随图标通过透明度渐隐并让相邻文字平移覆盖其空间，不要压缩宽度。
   - 组件卸载时（`onBeforeUnmount`）必须清理 `heroTransitionTimer` 和 `cancelAnimationFrame`。
   - 不参与飞行的副信息（标签、专辑、来源等）在动画起飞前先行淡出，返回时淡入。
7. **原生歌词层全局置顶误触避让**：原生歌词 View 叠加在 WebView 之上，默认优先吞噬所有触摸事件。当展示竖屏快捷操作弹窗时，必须由前端通过 `touchExclusionSelector` 获取 DOM 物理像素矩形，逐帧推送给原生层注册避让区，避免弹窗点击被穿透识别为歌词点击定位。
8. **内嵌 Node API 禁止劫持全局 DNS**：切勿在 `API/mobile-entry.ts` 中猴子补丁篡改全局 `dns.lookup`，否则会导致 `listen(13233, "127.0.0.1")` 被 Fake-IP 代理篡改造成端口绑定失败。DNS 覆盖已收敛于 `API/public-dns.ts` 中的 Agent 级控制。内嵌环境为 **Node 12.19.0**，不支持全局 fetch、`node:` 协议前缀（靠 esbuild 插件剥离）和现代 API。
9. **依赖安装后补丁链条**：`postinstall` 必须执行 `tsx scripts/patch-nodejs-mobile-cordova.ts`，否则 Android 构建必定失败。安装依赖须在常规终端进行，避免沙盒环境报 EPERM 错误。
10. **WebView 字体与缓存规范**：`MainActivity` 必须锁定 `config.fontScale = 1.0f`，版本升级时必须清理 WebView 缓存。

---

## 编码规范与最佳实践

### 注释规范

- 项目内所有业务代码注释**统一使用中文**。
- 导出方法使用规范 JSDoc 标注参数与返回值说明。
- 严禁出现无意义的分隔线（如 `// ────`）、冗长叙述性废话、显而易见的废话注释。仅在**设计原因不直观**时撰写注释。

### 代码组织与精简

- 提倡通过合理拆分文件组织代码，禁止使用大块注释分隔单个巨型文件。
- 拒绝为仅有 1~2 处调用的场景过度封装通用 Helper 函数。
- 严禁添加“以防万一”的臆想防御性代码，不预设不可达分支的兜底。
- 无需配置化的数值直接写死为具名常量，不要增加配置复杂度。

### 内存与性能纪律

- **图片按显示尺寸取用**：高斯模糊、取色及列表场景一律使用 300px 的 `cover` 缩略图；原图仅用于全屏大封面与海报导出。大图增加 `decoding="async"`。
- **严控合成层**：严禁在未定长列表中滥用 CSS `will-change`；全屏 `filter: blur` 和 `backdrop-filter` 必须克制使用。
- **后台与隐藏静默**：高频数据推送（`position` / `fftData`）在窗口隐藏或 App 切后台时必须静默停止；Canvas 渲染与 RAF 循环在不可见时必须暂停。
- **内存缓存必须有界**：所有模块级 Map/Array 缓存必须配备淘汰机制（LRU / 计数驱逐），严禁常驻保存单曲以外的庞大对象。

### 数据类型与存储规范

- 统一使用 **毫秒（ms）** 作为前端时间基准；Rust 底层使用的秒在进入 IPC 边界时统一通过 `toMs()` 转换。
- 禁止手动为原生模块手写 TS 类型，一律自 `@splayer/*` 导入。
- 存储轻量曲目集合使用 `shallowRef`，避免 Deep Proxy 深度劫持引发的卡顿。
- 保存至 IndexedDB 前必须使用 `toRaw` 脱除 Vue 响应式代理，规避 `DataCloneError`。

### 弹窗体系 (Popup Layers)

- `SDialog`、`SDrawer`、`SPopover` 内部同时承载着上游的统一层级管理（`usePopupZIndex`）与 Android 平台特化（返回键关闭 `useBackClosable`、防自动聚焦 `preventOpenAutoFocus`、悬停转点击降级等），修改弹窗组件时切勿漏掉任意一方的逻辑。

### 验证流程 (Verification Loop)

在完成代码修改后，必须按需运行对应检查：

- 涉及 TS/Vue 改动：`pnpm typecheck`。
- 涉及代码风格改动：`pnpm lint`。
- 涉及测试覆盖模块：`pnpm test:node` / `pnpm test:web`。
- 涉及 Rust 模块：`pnpm test:native`。
- 涉及 Android Kotlin：`pnpm android:check`（需要 JDK 21）。
- 提交前确保 Prettier 格式化（双引号、分号、100 字符宽、尾随逗号）。

### Git 提交规范

遵循 Conventional Commits 规范，使用**中文单行摘要**：`<type>: <summary>`（例如：`feat: ...`、`fix: ...`、`perf: ...`、`refactor: ...`）。
