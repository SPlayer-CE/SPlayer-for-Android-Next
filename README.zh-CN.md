<div align="center">

<img alt="SPlayer for Android Next logo" width="120" height="120" src="public/icons/logo-next.png" />

<h2>SPlayer for Android Next</h2>

<p>🎵 现代化的 Android 移动端音乐播放器，基于 Capacitor + Vue 3 构建</p>

<p>融合 Android 原生音频插件、Kotlin 前台播放与音频回采服务，支持卓越的歌词展现形式与广泛的音频格式</p>

[![Stars](https://img.shields.io/github/stars/SPlayer-CE/SPlayer-for-Android-Next?style=flat)](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/stargazers)
[![Release](https://img.shields.io/github/v/release/SPlayer-CE/SPlayer-for-Android-Next)](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/releases)
[![License](https://img.shields.io/github/license/SPlayer-CE/SPlayer-for-Android-Next)](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/blob/Android/LICENSE)
[![Issues](https://img.shields.io/github/issues/SPlayer-CE/SPlayer-for-Android-Next)](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/issues)

[English](./README.md) | **简体中文**

</div>

---

## 官方文档与分发声明

📖 **官方文档地址**：[https://next.sfa.l.cd/](https://next.sfa.l.cd/)

> ### 官方分发域名与防骗声明
>
> 本项目的首要分发渠道为 GitHub 仓库；下列域名为开发组授权的二级分发域名，仅用于文档访问与下载分发：
> - **Next Android 版文档**：`next.sfa.l.cd`
> - **旧版 Android 文档**：`legacy.sfa.l.cd`
>
> 除上述域名与 GitHub 仓库外，以 `l.cd` 为根域的任何其他子域（包括但不限于 `sfa.l.cd` 下的其他子域）均与本项目及其开发组无关，亦从未被授权分发安装包、发布项目公告或提供任何形式的付费、代理与「客服」服务。
>
> 请谨防仿冒站点、二次打包或篡改的安装包，以及任何以项目名义进行的收费募集。因访问或信任非官方渠道而产生的一切纠纷、损失或法律风险，均由当事人自行承担，本项目及其开发组概不负责。

---

## 项目简介

**SPlayer for Android Next** 是专为 Android 移动平台打造的新一代现代化音乐播放器。

项目基于 **Capacitor + Vue 3 + TypeScript** 架构构建，通过深度定制的 Android 原生插件桥接操作系统底层能力：
- **音频架构**：基于 Android 原生 **Media3 / ExoPlayer**，在专属 `SPlayerPlayback` 音频线程驱动播放、预加载优化与系统 `MediaSession` 集成；
- **前台保活服务**：采用原生 Kotlin 实现的 `PlaybackService` 前台服务，无缝支持锁屏通知、耳机线控与稳定后台播放；
- **双模歌词渲染**：结合 Android 原生 Canvas 硬件加速渲染层与 Web 物理动效引擎，支持逐行动态模糊与灵动岛悬浮歌词；
- **内嵌移动运行时**：内置 Node.js Mobile 与轻量 NanoHTTPD 代理，在本地无缝处理在线音频解析、歌词检索与扩展插件。

---

## 功能特性

- 🎵 **广泛的音频格式支持** —— MP3、FLAC、WAV、AAC、OGG 等格式，基于 Media3 与 Android 原生音频解码
- 📝 **丰富的歌词展现** —— 支持 TTML / QRC / KRC / YRC / LRC / LYS / ASS 等格式，逐字逐行动态高亮、自研逐行模糊过渡、灵动岛悬浮歌词
- 📱 **现代移动端交互** —— 针对触屏精细优化的 FullPlayer 全屏播放器，流畅的 Hero 元素共享过渡动效与手势操控
- 🛡️ **前台保活与播放服务** —— Kotlin 原生 `PlaybackService` 前台服务，锁屏控件与系统 MediaSession 通知，保障后台播放稳定不被系统清理
- 🎙️ **音频回采与识曲** —— 集成 Android 原生音频采集（AudioRecord）与听歌识曲服务
- 📁 **本地音乐管理** —— 基于 Android Storage Access Framework (SAF) + jaudiotagger 与原生 SQLite 高性能扫描管理本地歌曲
- 🌐 **流媒体服务连接** —— Subsonic / Navidrome / Jellyfin / Emby 无缝连接与流式播放
- 🎨 **自适应动态取色** —— 提取封面色彩实时生成沉浸式自适应背景，支持深色/浅色模式与全屏状态栏沉浸
- 🎚️ **实时音乐频谱** —— 硬件低开销 FFT 实时音频可视化
- ⚡ **嵌入式移动服务** —— 内置 Node.js Mobile 本地运行环境，驱动在线解析与插件生态

---

## 架构概览

```
Vue 3 渲染层 (UI / FullPlayer / Web 歌词)
       │
       ▼ (Capacitor 插件通信 / bridge.ts)
Android 原生层 (Kotlin Plugins)
  ├── PlaybackManager (独立 SPlayerPlayback 线程 + Media3/ExoPlayer)
  ├── PlaybackService (前台常驻通知 + 系统 MediaSession)
  ├── MainPlayerLyricOverlayView (原生 Canvas 硬件加速歌词渲染 + 动态模糊)
  ├── LibraryScanner & Database (SAF 目录权限 + jaudiotagger + SQLite)
  └── KotlinApiServer (NanoHTTPD 本地代理服务 :13962)
       │
       ▼ (本地端口代理)
Node.js Mobile 运行时 (:13233)
  └── 在线 API 解析、歌词检索与移动端插件运行时
```

---

## 开发指南

### 环境要求

- **Node.js** >= 22
- **pnpm** >= 10
- **JDK** 21（Android 编译必须，低版本如 JDK 17 会报兼容性错误）
- **Android SDK**（支持 API 34/35）与 **Android NDK**

### 快速开始

```bash
# 1. 安装依赖（自动执行 nodejs-mobile 补丁）
pnpm install

# 2. Web UI 开发预览（在浏览器中启动 Android 界面与开发版 API）
pnpm exec vite --config vite.config.android.ts --host 0.0.0.0
```

### Android 打包与构建

```bash
# 完整安卓前端与嵌入式资源构建流水线
# (build:web -> cap:sync -> build:android:node -> prepare:android:embedded)
pnpm build:android

# 构建原生 APK（需进入 android 目录）
cd android
./gradlew assembleDebug    # 构建 Debug 版 APK
./gradlew assembleRelease  # 构建 Release 版 APK
```

### 代码检查与规范

```bash
pnpm typecheck        # TypeScript 类型检查 (tsc + vue-tsc)
pnpm lint             # ESLint 静态检查
pnpm format           # Prettier 代码格式化
pnpm android:check    # Kotlin 静态检查与编译 (ktlintCheck + compileKotlin + detekt，需 JDK 21)
pnpm android:format   # Kotlin 代码格式化 (ktlintFormat)
```

---

## 致谢

特别感谢以下让 SPlayer for Android Next 成为可能的开源项目与技术：

- [Capacitor](https://capacitorjs.com/) —— 跨平台移动混合开发框架
- [Media3 / ExoPlayer](https://github.com/androidx/media) —— 强大的 Android 媒体播放引擎
- [applemusic-like-lyrics](https://github.com/Steve-xmh/applemusic-like-lyrics) —— 类 Apple Music 歌词渲染库
- [NeteaseCloudMusicApiEnhanced](https://github.com/neteasecloudmusicapienhanced/api-enhanced) —— 网易云音乐 API 增强
- [nodejs-mobile](https://github.com/nodejs-mobile/nodejs-mobile) —— Android 嵌入式 Node.js 运行时

---

## 开源许可

本项目基于 [GNU Affero General Public License v3.0 (AGPL-3.0)](https://www.gnu.org/licenses/agpl-3.0.html) 许可开源。

- **修改与分发：** 任何修改或分发都必须同样基于 **AGPL-3.0**，并一并提供完整源代码。
- **派生作品：** 必须同样采用 **AGPL-3.0**，并在适当位置保留本项目的许可与版权信息。
- **署名：** 必须保留原作者及版权信息。可为二次开发添加你自己的署名，但不得移除或篡改原始信息。
- **商业用途：** 如用于售卖或其他盈利用途，必须提供源代码及原项目链接。由于本项目涉及第三方服务，商业使用可能存在法律风险。
- **免责：** 本软件按「现状」提供，不附带任何形式的担保，详见 AGPL-3.0。

---

## 免责声明

本项目仅供个人学习与研究使用，禁止用于商业及非法用途。部分功能依赖第三方 API，使用者须自行确保其使用符合相关法律法规及服务协议。对于因使用本项目而产生的任何直接或间接后果，作者不承担任何责任。
