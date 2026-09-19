# Android 推送与发布指南

本页介绍 Android 分支（`Android`）的 CI/CD 工作流（`.github/workflows/android.yml`）：如何通过正确的推送方式触发自动构建、签名与 Release 发布。

## 触发规则一览

| 推送内容 | 触发任务 | 结果 |
| --- | --- | --- |
| push 到 `Android` 分支 | `build` (release) | 构建签名 APK，上传为 Artifact（不发布 Release） |
| PR 指向 `Android` 分支 | `check` | 仅静态校验（ktlint / Kotlin 编译 / detekt），拦截破坏构建的改动 |
| PR 带 `ci:debug` 或 `ci:dual` 标签 | `build-pr-apk` | 产出测试 APK Artifact（详见 [PR 测试构建](#pr-测试构建标签驱动)） |
| push `v*` 格式的 tag | `build` (release + debug) | 构建双包，分别附到正式版 Release 与 debug 预发布 Release |

## 发布正式版（自动附 Release）

前置条件：仓库 Secrets 中已配置 4 个签名密钥（见下文 [签名配置](#签名配置)）。

**第 1 步：确认本地分支已与远端同步**

```bash
git checkout Android
git pull origin Android
git status          # 确认工作区干净
```

**第 2 步：打 tag 并推送**

```bash
git tag v1.0.0-android.1
git push origin v1.0.0-android.1
```

tag 必须以 `v` 开头，否则不会触发发布流程。

**第 3 步：等待构建完成**

在仓库的 [Actions 页面](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/actions) 查看进度。构建完成后，4 个分 ABI 的签名 APK 会自动附到 [Releases 页面](https://github.com/SPlayer-CE/SPlayer-for-Android-Next/releases) 对应的 Release 上：

- `SPlayer-Next-<tag>-arm64-v8a.apk` — 主流 64 位真机
- `SPlayer-Next-<tag>-armeabi-v7a.apk` — 32 位真机
- `SPlayer-Next-<tag>-x86_64.apk` — x86_64 模拟器
- `SPlayer-Next-<tag>-x86.apk` — x86 模拟器

Release 描述由 GitHub 自动生成（基于两个 tag 之间的提交记录）。

推送 `v*` tag 后，CI 自动产出两个 Release：

| Release | Tag | 类型 | APK 命名 |
| --- | --- | --- | --- |
| 正式版 | `v*`（如 `v1.0.0-android.1`） | Release | `SPlayer-Next-<tag>-<abi>.apk` |
| Debug 版 | `v*-debug`（如 `v1.0.0-android.1-debug`） | Pre-release | `SPlayer-Next-<tag>-debug-<abi>.apk` |

> **不要手动 push `v*-debug` 后缀的 tag**——该 tag 由 CI 通过 GitHub API 自动创建。手动推送会触发 workflow 但被 job 级防递归守卫跳过。

## Debug 可调试包

`v*` tag 发版时同步产出的 debug 包具备以下特征：

- **applicationId**：`top.imsyy.splayer_next.debug`（带 `.debug` 后缀）
- **应用名**："SPlayer Next (Debug)"
- **并存**：可与正式包同设备安装，存储/登录态/缓存相互独立
- **调试能力**：已开启 WebView 远程调试（`chrome://inspect`）与 Logcat → console 透传
- **签名**：使用 CI 构建时的临时 debug keystore（每次构建签名不同）

::: danger 端口共用——测试前必须强制停止正式包
本地服务端口（KotlinApiServer `:13962` / Node `:13233`）为硬编码常量，debug 包与正式包共用。若正式包在后台运行（前台服务常驻），debug 包的 API 请求会连到正式包进程，导致：

- 登录复现失真（请求实际由正式包处理）
- debug 包的 `chrome://inspect` 看不到诊断日志（日志打在正式包进程）

**操作规范**：测试 debug 包前，先在系统设置中强制停止正式包（或滑动清除后台）。

后续开放问题：按变体分端口偏移（`buildConfigField` + Kotlin/TS/Node 联动）根治端口冲突。
:::

::: warning Debug 签名覆盖安装
由于 CI 每次构建生成不同的 debug keystore，新 debug 包**无法覆盖安装**旧 debug 包。安装新版本前需先卸载旧的 debug 包（正式版不受影响）。

后续可通过配置 `ANDROID_DEBUG_KEYSTORE_BASE64` secret 固定 debug 签名以支持覆盖安装（开放问题）。
:::

## PR 测试构建（标签驱动）

在 PR 上打标签可触发测试 APK 构建，产物作为 Artifact 供测试者从 Actions run 页面下载：

| 标签 | 构建内容 | Artifact 命名 |
| --- | --- | --- |
| `ci:debug` | 仅 Debug APK | `SPlayer-Next-PR<编号>-debug` |
| `ci:dual` | Release + Debug APK | `SPlayer-Next-PR<编号>-release` / `-debug` |

使用说明：

1. 标签 `ci:debug` / `ci:dual` 需在仓库 **Settings → Labels** 预先创建。
2. 给 PR 打上标签后，workflow 自动触发（`types: [labeled]`）；也可在已有标签时 push 新提交触发。
3. `ci:dual` 的 Release 变体依赖签名 secrets，仅同仓分支 PR 可用；fork PR 无法获取 secrets，Release APK 将回退为 debug 签名。
4. PR 测试构建**不会发布 Release**，仅产出 Artifact。

## 日常推送（仅出构建产物，不发布）

直接 push 到 `Android` 分支即可：

```bash
git push origin Android
```

构建产物在对应 run 页面的 Artifacts 区域（命名 `SPlayer-Next-release-APKs-<run编号>`），保留 90 天，适合测试验证但不会出现在 Releases 页面。

## 签名配置

构建使用仓库 Secrets 中的正式密钥签名：

| Secret | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 密钥库文件的 Base64 内容（单行） |
| `ANDROID_KEYSTORE_PASSWORD` | 密钥库口令 |
| `ANDROID_KEY_ALIAS` | 密钥别名 |
| `ANDROID_KEY_PASSWORD` | Key 口令 |

若 Secrets 未配置，构建**不会失败**，而是自动回退 debug 签名——此类 APK 可安装测试，但无法覆盖升级正式签名版本，也不要用于对外发布。

::: info Nightly 构建
`nightly.yml` 每夜构建仍仅产出 Release 包（不含 debug 变体），发布到 `nightly-<日期>` tag 的 Pre-release。如需 Nightly debug 包，可在后续迭代中扩展。
:::

::: warning 密钥安全
签名密钥丢失后无法恢复，已安装的正式包将无法通过同 appId 升级覆盖。请将密钥库在多个离线介质各备份一份，且不要提交进任何仓库。
:::

## 常见问题

**推了 tag 但没有触发发布**

tag 必须指向包含 `.github/workflows/android.yml` 的提交。旧提交上打的 tag 不会触发本工作流，删除后重新指向最新提交：

```bash
git tag -d v1.0.0-android.1
git tag v1.0.0-android.1 Android
git push origin :refs/tags/v1.0.0-android.1   # 若已推送过错误 tag，先删远端
git push origin v1.0.0-android.1
```

**tag 打在了落后的本地提交上**

打 tag 前先 `git pull origin Android`，确保指向远端最新提交，否则 Release 里会缺少最新改动。

**APK 内部版本号不随 tag 变化**

`versionCode` / `versionName` 定义在 `android/app/build.gradle` 中（静态值），tag 名只影响 APK 文件名与 Release 标题。发版前如有需要，请在构建提交中手动更新这两项。
