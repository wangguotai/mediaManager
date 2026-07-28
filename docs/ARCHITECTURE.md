# 架构文档 — Media Manager

## 总览

```
┌─────────────────────────────────────────────┐
│              前端 (KMP + Compose)              │
│  ┌─────────────┐  ┌─────────────┐            │
│  │  Android App │  │   iOS App   │            │
│  └──────┬───────┘  └──────┬──────┘            │
│         │    commonMain    │                  │
│         │  (ViewModel /    │                  │
│         │   MediaService / │                  │
│         │   Coil / Ktor)   │                  │
│         └────────┬─────────┘                  │
└──────────────────┼──────────────────────────┘
                   │ HTTP / REST
                   ▼
┌──────────────────────────────────────────────┐
│            后端 (Go gRPC + REST)              │
│  ┌────────────────────────────────────────┐  │
│  │          REST Gateway (:8080)          │  │
│  │  /api/media/*   /api/openclaw/*        │  │
│  │  /healthz                              │  │
│  └──────────────────┬─────────────────────┘  │
│                     │ interface assertion     │
│  ┌──────────────────▼─────────────────────┐  │
│  │           MediaService (gRPC)           │  │
│  │  GetMediaList / GetThumbnail /          │  │
│  │  GetMediaMetadata / DeleteMedia         │  │
│  └──┬──────────┬──────────┬───────────────┘  │
│     │          │          │                  │
│  ┌──▼───┐ ┌───▼────┐ ┌──▼──────┐            │
│  │Thumb │ │Favorite│ │ Album   │            │
│  │Cache │ │Store   │ │ Store   │            │
│  └──────┘ └────────┘ └─────────┘            │
│  ┌──────────────────────────────────────┐   │
│  │  LocalCloudSource (data/cloud-images)│   │
│  │  Uploads Dir (data/uploads)          │   │
│  └──────────────────────────────────────┘   │
│  依赖: ffmpeg / ffprobe                     │
└──────────────────────────────────────────────┘
```

## 前端架构

### Kotlin Multiplatform + Compose Multiplatform

```
composeApp/src/
├── commonMain/          # 跨平台共享
│   ├── kotlin/com/wgt/
│   │   ├── App.kt           # 入口, NavHost
│   │   ├── media/           # 核心功能
│   │   │   ├── MediaViewModel.kt     # 状态管理
│   │   │   ├── MediaService.kt       # 后端 HTTP 客户端
│   │   │   ├── ThumbnailLoader.kt    # 缩略图加载 + LRU
│   │   │   ├── BackendImageLoader.kt # 后端图片加载
│   │   │   ├── DateGroupedGrid.kt    # 日期分组网格
│   │   │   ├── ImageProcessing.kt    # 图片编辑 (裁剪/旋转/滤镜)
│   │   │   ├── SettingsStorage.kt    # 设置持久化
│   │   │   ├── OpenClawBridge.kt     # OpenClaw 桥梁
│   │   │   └── ...
│   │   └── architecture/    # DI / 生命周期
│   └── resources/
├── androidMain/         # Android expect/actual
│   └── kotlin/com/wgt/
│       ├── media/       # VideoView, HapticFeedback, ShareUtils
│       └── architecture/  # AndroidAppLifecycle
└── iosMain/             # iOS expect/actual
    └── kotlin/com/wgt/
        └── media/       # AVPlayer, HapticFeedback, ShareUtils
```

**关键设计：**

- **expect/actual 模式**：平台差异（视频播放、触觉反馈、分享）通过 expect/actual 抽象，commonMain 中定义接口，各平台实现。
- **ViewModel + Compose State**：`MediaViewModel` 持有 UI 状态，通过 `StateFlow` 驱动 Compose 重组。
- **Coil3 + Ktor**：图片加载用 Coil3 管道，网络层用 Ktor Client（Android 用 OkHttp engine，iOS 用 Darwin engine）。
- **LRU 缓存**：缩略图和全屏图片各维护 LRU 缓存，线程安全。

## 后端架构

### Go gRPC + REST Gateway

```
backend/
├── cmd/server/
│   └── main.go              # 入点: 启动 gRPC + REST
├── internal/
│   ├── gateway/
│   │   └── server.go        # REST 路由 + OpenClaw 桥梁
│   └── service/
│       ├── media_service.go  # 核心 gRPC service
│       ├── thumb_cache.go    # 缩略图 LRU 缓存
│       ├── favorite_store.go # 收藏持久化
│       ├── album_store.go    # 相册持久化
│       └── cloud_source.go   # 网盘图片源
└── gen/                     # proto 生成代码
```

**关键设计：**

- **REST + gRPC 双协议**：gRPC 定义服务接口（proto），REST gateway 在 HTTP 上暴露等价端点，前端通过 REST 交互。
- **能力接口断言**：gateway 通过 interface 断言（`favoriteProvider`、`albumStoreProvider`、`videoInfoProvider`）判断 service 是否支持某能力，不支持的端点返回 501。这样新功能可渐进式加入，不影响已有端点。
- **缩略图缓存**：图片按 longEdge 缩放保持比例；视频用 ffmpeg 抽第一帧再缩放。缓存到 `data/thumbnails/{media_id}_{longEdge}.jpg`。
- **收藏/相册持久化**：JSON 文件 + RWMutex，`data/favorites.json` 和 `data/albums.json`。线程安全，启动时加载，修改即落盘。
- **OpenClaw 桥梁**：`/api/openclaw/command` 将前端请求转发到本地 OpenClaw gateway，前端只需与 media-manager 通信。
- **Range 流式**：`http.ServeFile` 天然支持 HTTP Range，视频可拖拽播放。

## 数据流

### 图片浏览

```
App 启动
  → MediaViewModel.loadMedia()
  → MediaService.getMediaList(page, type, q)
  → GET /api/media/list
  → gateway → MediaService.GetMediaList (gRPC)
  → 扫描 data/uploads/ + data/cloud-images/
  → 返回 MediaMetadata[] (enriched with favorite)
  → ViewModel 更新 StateFlow
  → Compose 重组 LazyVerticalStaggeredGrid
  → 每个网格项请求缩略图
  → ThumbnailLoader → GET /api/media/thumbnail/{id}
  → Coil3 管道缓存 + LRU
```

### 视频播放

```
用户点击视频
  → 全屏预览页
  → GET /api/media/video-info/{id} (时长/分辨率)
  → VideoPlayer (Android VideoView / iOS AVPlayer)
  → GET /api/media/stream/{id} (Range 请求)
  → http.ServeFile 分片传输
```

## 外部依赖

| 依赖 | 用途 | 必要性 |
|---|---|---|
| ffmpeg | 视频缩略图抽帧 | 缺失时视频缩略图返回 500 |
| ffprobe | 视频信息解析 (时长/分辨率/编码) | 缺失时 video-info 返回 500 |
| Go 1.21+ | 后端编译 | 必需 |
| JDK 17+ | 前端编译 | 必需 |
| Android SDK 36 | Android 构建 | 必需 |
| Xcode | iOS 构建 | 必需 |
