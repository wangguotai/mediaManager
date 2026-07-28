# Media Manager

跨端媒体管理器 — 支持 Android 与 iOS 的本地/云端图片视频浏览、搜索、编辑、分享应用。
Material3 设计语言，流畅动画，双端一致体验。

参考：Google Photos、Apple Photos、小米相册。

## 技术栈

| 层 | 技术 |
|---|---|
| 前端 | Kotlin Multiplatform (KMP) + Compose Multiplatform |
| UI | Material3 动态色调, Compose Animation |
| 网络 | Ktor Client (Android/Darwin engine) |
| 图片加载 | Coil3 (coil-compose + coil-network-ktor) |
| 序列化 | kotlinx.serialization JSON |
| 协议 | Wire (gRPC proto 兼容) |
| 后端 | Go 1.21+ gRPC + REST gateway |
| 视频 | ffmpeg / ffprobe (缩略图抽帧、视频信息解析) |
| 元数据 | EXIF (go-exif) |
| Android | minSdk 24, targetSdk 36, compileSdk 36 |
| iOS | compileKotlinIosArm64 |

## 功能清单

### 浏览
- 三 Tab：本地图片 / 已上传 / 网盘图片
- 瀑布流网格浏览 + shimmer 占位加载
- 全屏预览：左右滑动 + 双击缩放 + 捏合缩放
- 按日期分组 + sticky header
- 时区正确的日期分组

### 搜索与筛选
- 搜索栏 + 类型筛选 FilterChip (图片/视频/Live Photo)
- 搜索关键词高亮

### 媒体详情
- 图片详情面板（EXIF 信息，上滑展开）
- 视频信息（时长/分辨率/编码，via ffprobe）

### 编辑
- 图片裁剪（可拖拽调整裁剪框）
- 旋转：90° 步进 + 自由角度滑块
- 基础滤镜：原图 / 黑白 / 暖色 / 冷色
- 保存到相册 + 可选上传后端

### 收藏与相册
- 单条/批量收藏（持久化到后端）
- 收藏列表查看
- 创建/删除相册
- 媒体加入/移出相册

### 视频播放
- Android VideoView / iOS AVPlayer
- Range 流式播放（HTTP 分片拖拽）

### 分享与批量操作
- 系统分享（单条/批量）
- 选择模式底栏：全选/反选/批量删除/批量上传
- 长按震动反馈（HapticFeedback）

### 设置
- 后端地址配置
- 主题切换
- OpenClaw 入口
- 关于页

### 后端服务
- Go gRPC + REST gateway (端口 :8080)
- 媒体上传/删除/元数据
- ffmpeg 视频缩略图生成 + LRU 缓存
- 收藏 + 相册持久化 (JSON file)
- 健康检查（media_count / uptime / cache stats / favorite_count）
- OpenClaw 桥梁（REST 代理到本地 OpenClaw gateway）

## 构建说明

### 后端

```bash
cd backend && go build ./...
```

> 依赖 `ffmpeg` / `ffprobe` 在 PATH 中可用。

### 前端

```bash
cd frontend && bash gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosArm64
```

- `assembleDebug` — Android APK
- `compileKotlinIosArm64` — iOS Kotlin 编译（需 Xcode 工具链）

## 运行说明

1. 确保后端已构建且 ffmpeg/ffprobe 可用
2. 启动后端：
   ```bash
   cd backend && go run ./cmd/server
   ```
   后端默认监听 `:8080`
3. 启动前端 App，在设置页配置后端地址（默认 `http://10.0.2.2:8080` for Android Emulator）

## 项目结构

```
media-manager/
├── backend/           # Go 后端 (gRPC + REST gateway)
│   ├── cmd/server/    # 入点
│   ├── internal/
│   │   ├── gateway/   # REST 路由 + OpenClaw 桥梁
│   │   └── service/   # 媒体/收藏/相册/缩略图/云源
│   └── gen/           # proto 生成代码
├── frontend/          # KMP + Compose Multiplatform
│   └── composeApp/
│       └── src/
│           ├── commonMain/  # 跨平台共享代码
│           ├── androidMain/  # Android 实现
│           └── iosMain/      # iOS 实现
├── shared/proto/      # proto 定义
├── docs/              # 文档 (PRD, QA 报告, 架构)
├── .agents/           # 接口契约 + 协调者笔记
└── plan/              # Sprint 规划
```

## 文档

- [PRD v2](docs/PRD-v2.md) — 产品需求文档
- [架构文档](docs/ARCHITECTURE.md) — 前后端架构简述
- [接口契约](.agents/interface-contract.md) — REST API 定义
- [QA 报告](docs/FINAL-QA-REPORT.md) — 最终 QA 验收报告
