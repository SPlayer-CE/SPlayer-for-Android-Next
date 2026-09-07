# Android 本地缓存服务实现

## 概述

本项目为 SPlayer-Next Android 版本实现了完整的本地歌曲文件缓存服务，基于 Electron 端的 `songCache` 逻辑进行适配。

## 架构设计

### 核心组件

1. **songCache.ts** - 歌曲文件缓存服务
   - 位置: `API/songCache.ts`
   - 功能: 管理歌曲文件的下载、存储、查询和清理

2. **mobile-server.ts** - 嵌入式 API 服务器
   - 位置: `API/mobile-server.ts`
   - 功能: 提供 HTTP API 接口供前端调用

### 缓存目录结构

```
splayer-data/
└── cache/
    └── songs/           # 歌曲文件缓存目录
        ├── abc123.bin   # 缓存的歌曲文件（SHA1 hash）
        ── def456.bin
```

默认路径: `<CONFIG_DIR>/cache/songs`

可通过环境变量 `SP_CACHE_DIR` 自定义。

## API 接口

### 1. 获取缓存统计信息

```
GET /api/cache/getStats
```

响应:

```json
[
  {
    "id": "songs",
    "kind": "file",
    "path": "/path/to/cache/songs",
    "size": 1073741824
  }
]
```

### 2. 获取缓存目录路径

```
GET /api/cache/getDir
```

响应:

```json
"/path/to/cache"
```

### 3. 重置缓存目录

```
POST /api/cache/resetDir
```

响应:

```json
"/path/to/cache"
```

### 4. 查询缓存命中

```
GET /api/cache/song/lookup?cacheKey=<key>
```

参数:

- `cacheKey`: 缓存键值（通常是歌曲 URL 的哈希）

响应:

- 命中: 返回文件绝对路径字符串
- 未命中: 返回 `null`

### 5. 异步下载并缓存

```
POST /api/cache/song/fetch
```

请求体:

```json
{
  "cacheKey": "abc123",
  "source": "netease",
  "streamUrl": "https://example.com/song.mp3"
}
```

响应:

- 成功: 返回文件绝对路径字符串
- 失败: 返回 `null`

### 6. 取消下载

```
POST /api/cache/song/cancel
```

请求体:

```json
{
  "cacheKey": "abc123"
}
```

### 7. 清空缓存

```
POST /api/cache/clear
```

请求体:

```json
{
  "id": "songs"
}
```

### 8. 按类型清空缓存

```
POST /api/cache/clearAllByKind
```

请求体:

```json
{
  "kind": "file"
}
```

## 特性

### 1. 并发控制

- 最大并发下载数: 2
- 使用队列机制管理等待中的下载任务

### 2. 智能过滤

- **MIME 类型检查**: 拒绝 HTML/JSON/XML 等非音频内容
- **文件大小限制**: 默认上限 10GB
- **文件头检测**: 通过首字节判断是否为真实音频文件

### 3. 原子写入

- 使用 `.part` 临时文件
- 下载完成后重命名为正式文件
- 避免损坏的文件被误认为有效缓存

### 4. 孤儿清理

- 启动时自动清理残留的 `.part` 文件
- 删除数据库中记录但文件不存在的条目
- 删除文件存在但数据库无记录的孤儿文件

### 5. AbortController 支持

- 所有下载操作支持取消
- 应用退出时自动中止所有进行中的下载

## 与 Electron 端的差异

| 特性       | Electron    | Android   |
| ---------- | ----------- | --------- |
| 数据库支持 | ✅ SQLite   | ❌ 待实现 |
| LRU 淘汰   | ✅ 完整实现 | ⚠️ 简化版 |
| 并发下载   | 2           | 2         |
| MIME 检查  | ✅          | ✅        |
| 文件头检测 | ✅          | ✅        |
| 原子写入   | ✅          | ✅        |

### TODO 项

1. **SQLite 数据库集成**
   - 需要添加 `better-sqlite3` 或类似库
   - 实现 `upsert`, `findByKey`, `deleteByKey` 等方法
   - 支持 LRU 淘汰策略

2. **LRU 淘汰优化**
   - 当前仅实现基础清理
   - 需要根据 `lastUsedAt` 字段智能淘汰

3. **占用统计精确化**
   - 当前返回固定值 0
   - 需要遍历目录计算实际大小

## 使用示例

### 前端调用（通过 bridge）

```typescript
// 查询缓存
const cachedPath = await window.api.cache.song.lookup(cacheKey);
if (cachedPath) {
  // 使用本地缓存文件
  player.load(cachedPath);
} else {
  // 下载并缓存
  const path = await window.api.cache.song.fetch(cacheKey, source, streamUrl);
  if (path) {
    player.load(path);
  }
}

// 取消下载
window.api.cache.song.cancel(cacheKey);

// 清空缓存
window.api.cache.clear("songs");
```

## 注意事项

1. **权限要求**: Android 端需要读写外部存储权限
2. **存储空间**: 建议定期检查缓存大小并清理
3. **网络环境**: 下载操作依赖网络连接，需处理超时和失败情况
4. **线程安全**: 所有操作均为异步，支持并发访问

## 未来优化方向

1. 集成 SQLite 数据库实现完整的元数据管理
2. 实现更智能的 LRU 淘汰算法
3. 添加缓存预热和预加载功能
4. 支持多种压缩格式以节省空间
5. 添加缓存命中率统计和监控
