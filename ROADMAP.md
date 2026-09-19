# SPlayer-for-Android-Next 开发路线图（Roadmap）

> 本文档维护 Android 端的后续开发方案：安全加固、工程优化、桌面能力迁移与协作治理约定。
> 状态随 PR / issue 更新；变更请通过 PR 修改本文件。

## 一、当前基线

- `Android` 为主分支，自 PR #4 起为**纯移动端工程**（已移除 `electron/`、`native/`、`windows/` 及桌面构建配置）。
- 与桌面端 `dev` / upstream **彻底分叉**：不再整体 merge dev，改为按需 cherry-pick `src/`、`shared/`。
- 桌面能力迁移的取材源为 `dev` / upstream / 历史提交（≤ `828eb98`），不在本分支内。

## 二、安全加固（issue #11）

审计共 3 高危 + 3 中危 + 若干低危，修复收敛在两个 PR：

| #   | 级别 | 问题                                          | 修复 PR   | 状态                                                |
| --- | ---- | --------------------------------------------- | --------- | --------------------------------------------------- |
| 1   | 高   | node:vm 沙箱原型链逃逸（宿主 Buffer 注入）    | #13       | 已批准（待合并）                                    |
| 2   | 高   | LAN 配置读写未鉴权                            | #12       | 已批准（ktlint 已修，待合并）                       |
| 3   | 高   | WebSocket 不校验 Origin（CSWSH）              | #12       | 已批准（ktlint 已修，待合并）                       |
| 4   | 中   | 凭据存外部存储 getExternalFilesDir            | #12       | 已批准（ktlint 已修，待合并）                       |
| 5   | 中   | Release 常开 WebView 远程调试                 | #12 / #13 | 已批准（待合并）                                    |
| 6   | 中   | 全局允许明文 HTTP                             | 已决策    | 保留明文（自建流媒体核心场景），接受风险 + 文档缓解 |
| 7   | 低   | FileProvider 根路径过宽 / Release 转发 Logcat | #13 / #12 | 已批准（待合并）                                    |

- 另含 SSRF 重定向防护（#13）。
- allowBackup（凭据随系统备份外泄）已由 PR #10 落实方案 A（`allowBackup="false"`），见 issue #6。
- **重复治理**：曾出现 #14/#15 与 #12/#13 重复，已关闭 #14/#15 收敛；后续同一 issue 的修复应集中在单一 PR 或明确分工，避免多 PR 改同一文件冲突。

## 三、工程优化

| 项  | 内容                                                                                      | 状态                                     |
| --- | ----------------------------------------------------------------------------------------- | ---------------------------------------- |
| O1  | 备份安全 allowBackup                                                                      | ✅ PR #10                                |
| O2  | 听歌识曲 AFP 指纹移入 Web Worker                                                          | ✅ PR #5                                 |
| O5  | CI：内嵌 Node 打包守护 + Android 纳入 ci.yml push                                         | ✅ PR #4 / #9                            |
| O3  | God 模块渐进拆分（mobile-server.ts / MainPlayerLyricOverlayView.kt / PlaybackManager.kt） | 待排期                                   |
| O6  | bridge.ts 收敛 Android-only（分叉后死代码清理）                                           | 待评估（影响 upstream src/ cherry-pick） |
| O7  | dev→Android 同步策略文档化（cherry-pick src/shared）                                      | 本文档即起点                             |
| O4  | 测试补齐（recognize / match / AudioCaptureManager；streaming 迁移同步补）                 | 进行中（recognize.spec.ts，PR #17）      |

## 四、桌面能力迁移（M1–M5）

取材源：`dev` / upstream / 历史（≤ `828eb98`）。落地于内嵌 Node API（`API/`）或 Kotlin 原生。

| 项  | 内容                                    | 优先级 | 备注                                                                                            |
| --- | --------------------------------------- | ------ | ----------------------------------------------------------------------------------------------- |
| M1  | 流媒体 Subsonic / Jellyfin / Emby       | P1     | 内嵌 Node 复刻适配器；Keystore 替 safeStorage；HTTP 路由替 streaming-cover://；先 Subsonic 打样 |
| M2  | 插件歌词/封面匹配 matchLyric/matchCover | P1     | PR #4 已迁入插件 loader；补匹配路由 + 接通 withPluginPrefer                                     |
| M3  | 播放器淡入淡出 / 音量归一化             | P2     | Media3 音量斜坡；ReplayGain 经 setPreampGain                                                    |
| M4  | 插件更新/菜单/控制 API                  | P2     | 关联 upstream #285 / #284 / #258                                                                |
| M5  | 变调 setPitch                           | P3     | 不确定则判"不支持"并隐藏 UI                                                                     |

平台差异（隐藏而非迁移）：输出设备、桌面歌词窗 / 任务栏歌词 / 灵动岛窗口 / 全局快捷键 / MCP / 自动更新。

## 五、upstream issue 分诊（Android 相关）

- 插件生态：#285 playTrack、#284 market @grant ui、#258 源插件 id → 归 M4。
- 歌词：#278 副行字号、#201 单曲循环进度 / 歌词重叠、#63 AMLL 滚动、#176 编码识别。
- 移动端 UI：#149 / #263 小屏适配（P1，需真机核验）、#254 缩放。
- 音源 / 播放：#280 跨平台音源、#259 播放中断、#199 切换暂停、#249 收藏歌单。
- 内嵌 API：#247 酷狗歌单 >300 截断（**N/A：酷狗歌单非 Android 既有功能**）、#207 代理 / 证书。
- i18n：#171 韩文显示。

## 六、执行路线

1. **安全加固优先**：#12 / #13 评审合并、#6 产品决策。
2. **路线 A（快赢）**：O4 测试补齐（PR #17 进行中）、#149 / #263 小屏适配（需真机核验）；#247 经核实为桌面专属（N/A）。
3. **路线 B（里程碑）**：M1 流媒体（Subsonic 打样）。
4. **路线 C（架构）**：O7 同步文档化、O6 bridge 收敛、O3 God 模块拆分。

## 七、协作治理约定（防重复）

- 开 issue / PR 前先检索现有 issue / PR，避免同题多开。
- 一个 issue 对应一个主题；修复类 PR 在正文 `refs #N` / `closes #N` 关联。
- 同一 issue 的多项修复尽量集中一个 PR，或先在 issue 下分工认领，避免多 PR 改同一文件产生冲突。
- 安全类批量修复以审计 issue 为总纲（如 #11），逐项勾选销账。

## 八、执行原则

- 全部在 `Android` 分支开发；每项独立 PR + CI 绿 + 评审。
- 遵守 CLAUDE.md：中文注释 + JSDoc、前端毫秒、Track 轻量 / TrackDetail 不持久化、内存纪律、有界缓存、持久化前 toRaw、Prettier。
- 迁移优先复用内嵌 API / 现有原生通路；不引入 Electron 专属依赖。
- 平台不支持项：UI 按 isAndroid 隐藏 + bridge 统一契约，不静默 no-op。
- 原生→JS 事件桥接：可取消异步任务的终止事件派发前必须校验会话 token。

## 九、路线 B 预研（M1 流媒体迁移）

取材：历史提交 `828eb988` 的 `electron/main/services/streaming/`（9 文件）。可移植性结论：

- **可直接移植（纯 HTTP）**：`adapters/subsonic.ts`、`adapters/jellyfin.ts`、`adapters/resolve.ts`、`adapters/types.ts`——仅依赖 `node:crypto` 与 `@shared/types`，无 Electron；落地为内嵌 Node API 的 streaming 路由。
- **需 Android 替换**：
  - `config.ts`：`electron.safeStorage` → Android Keystore（Kotlin 插件暴露加解密）或内嵌侧 AES + Keystore 密钥；`@main/utils/paths` → 内嵌配置目录；logger → 内嵌日志。
  - `sync.ts`：`@main/database`（better-sqlite3，Android Node 不可用）→ 复用 Kotlin 缓存 DB（`/api/cache/db` 路由）；`@main/utils/broadcast`（Electron IPC）→ Capacitor / 内嵌事件。
  - `coverProtocol.ts`：Electron `streaming-cover://` → 内嵌 HTTP 路由 `/api/streaming/cover`，渲染层以 HTTP URL 加载。
  - `connection.ts`：基本可移植（依赖 config）。
- 渲染层：`stores/streaming.ts` 复用；bridge 的 streaming 桩（现 reject/空）改路由到内嵌 API。
- 打样顺序：Subsonic 家族（adapter 最成熟）→ Jellyfin / Emby。

## 十、路线 C 预研（架构清理）

- **O6 bridge 收敛**：`bridge.ts` 3521 行、245 处 `electronApi()` 死调用（Android 恒 isAndroid）。收敛可大幅瘦身，但与 upstream `src/` cherry-pick 冲突面大；**若继续吸收 upstream 修复则暂缓 O6**，否则可收敛。
- **O7 同步策略**：分叉后改按需 cherry-pick `src/`、`shared/`；流程（挑拣范围、冲突处理、回归验证）写入 CONTRIBUTING / 本文档。
- **O3 God 模块渐进拆分**：`mobile-server.ts` 3095 行（lyric/plugins/config/cache/stats 路由混杂）→ 按域拆路由模块；`MainPlayerLyricOverlayView.kt` 4402 行（渲染+时间轴+分段）→ 拆 renderer/timeline/segment；`PlaybackManager.kt` 2730 行（播放+会话+通知+FFT/EQ）→ 拆 session/notification/processor。均小步拆分 + 测试护航。
