<!--
感谢贡献！提交前请确认：
1. 目标分支：请向 Android 分支（移动端主线开发）或 dev 分支提交，严禁向 main 分支直接提交未稳定的改动。
2. 职责聚焦：单个 PR 尽量只聚焦单一功能或修复，避免无关修改；多个不相关的改动请拆分成多个 PR。
3. 质量把关：若改动中包含 AI 辅助生成的代码，请务必人工逐行审阅并完成 Android 设备/模拟器上的真实测试，杜绝未经实测的提交。
-->

## 目标分支

- [ ] `Android`（移动端主线特性 / 修复）
- [ ] `dev`（开发分支 / 多端共享逻辑）

## 改动类型

- [ ] 🚀 新功能（feat）
- [ ] 🐛 缺陷修复（fix）
- [ ] ⚡ 性能优化（perf）
- [ ] 🤖 Android 原生层变动（Kotlin / Media3 / Capacitor 插件 / 前台服务）
- [ ] 🎨 Web 渲染层变动（Vue 3 / FullPlayer / 样式动效）
- [ ] ♻️ 重构（refactor，不改变对外行为）
- [ ] 📦 依赖与构建配置（build / CI / Gradle / Capacitor）
- [ ] 📝 文档更新（docs）
- [ ] 🔧 其他（请在「改动说明」中注明）

## 涉及模块与影响范围

- [ ] **Android 原生层**（`android/` 目录：`PlaybackManager`、`PlaybackService`、Capacitor 插件等）
- [ ] **Web 前端渲染层**（`src/` 目录：UI 交互、Pinia Store、播放控制）
- [ ] **歌词子系统**（Canvas 原生歌词、Web 物理歌词、逐行模糊）
- [ ] **本地媒体与存储**（SAF 目录授权、SQLite 数据库、jaudiotagger 扫描）
- [ ] **嵌入式 API 与网络**（`API/` 目录：Node.js Mobile、NanoHTTPD 代理）
- [ ] **权限与系统特性**（通知权限、前台服务、音频录制/回采、电池优化白名单等）
- [ ] **ABI 架构兼容性**（armeabi-v7a / arm64-v8a / x86_64）

## 改动说明

<!--
详细描述本 PR 的改动内容：
1. 背景与动机：解决了什么问题或引入了什么功能？
2. 核心技术实现：修改了哪些关键逻辑或调用链？
3. 是否涉及 Breaking Change 或破坏性行为变动？
-->

## 关联 Issue

<!-- 如有关联，填写 #编号；使用 "Closes #编号" 或 "Fixes #编号" 可以在 PR 合并时自动关闭对应 Issue -->

## 权限与兼容性说明

- **Android 系统版本要求**：最低支持 Android 7.0 (API 24)，目标 Android 14/15 (API 34/35)
- **新增/调整权限**：<!-- 如新增 RECORD_AUDIO、POST_NOTIFICATIONS、FOREGROUND_SERVICE_MEDIA_PLAYBACK 等，请在此说明；若无填“无” -->
- **ABI 兼容性影响**：<!-- 是否影响 32/64 位架构（armeabi-v7a / arm64-v8a 等），若无填“无” -->

## 测试情况

<!--
请列出你的测试验证环境与结果（真机实测优先）：
- 测试设备：例如 小米 13 / Pixel 7 / 雷电模拟器
- 系统版本 / ROM：例如 Android 14 (HyperOS 1.0) / 原生 Android 14
- CPU 架构：arm64-v8a / x86_64
- 验证场景覆盖：
  - [ ] 前台正常播放 / 暂停 / 切歌 / 拖拽进度
  - [ ] 锁屏与通知栏 MediaSession 控制
  - [ ] 后台播放稳定性（长时间播放未被系统杀除）
  - [ ] 屏幕旋转（横竖屏切换歌词与界面正常）
  - [ ] 音频焦点切换（来电/其他应用抢占焦点）
  - [ ] 无闪退与 ANR（已检查 logcat 无严重异常）
-->

## 截图 / 录屏

<!-- 涉及 UI、歌词动效、交互改动请附上测试截图或录屏；如无则可删除本节 -->

## 自查清单

- [ ] 目标分支正确选择为 `Android` 或 `dev`
- [ ] 本 PR 只包含**一个主要功能 / 修复**，没有夹带无关改动
- [ ] 严格遵守项目代码规范，已在 Android 真机或模拟器上**完整实测**；**AI 辅助生成的代码已逐行审阅并验证**
- [ ] 已运行 `pnpm format`，并确认 `pnpm typecheck`、`pnpm lint` 检查全部通过
- [ ] 涉及 Kotlin 改动已运行 `pnpm android:check`（JDK 21）验证通过，并已运行 `pnpm android:format` 格式化
- [ ] 涉及 Capacitor 桥接方法变动已在 `src/services/bridge.ts` 中完成双向同步或提供空实现（no-op）降级，未遗留未实现接口
- [ ] 未引入任何硬编码密钥、敏感信息或无意义的临时构建产物
