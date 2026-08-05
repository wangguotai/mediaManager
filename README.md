# Media Manager

> 开源自托管云相册 + 网盘 —— 跨端媒体管理器，支持 Android 与 iOS 的本地/云端图片视频浏览、搜索、编辑、分享。
> 前端 KMP Compose Multiplatform，后端 Go（gRPC + REST gateway）。

参考产品：Google Photos、Apple Photos、小米相册。

--- 

## ✨ 功能特性

### 📱 浏览与预览
- 三 Tab：本地图片 / 已上传 / 网盘图片
- 瀑布流网格浏览 + shimmer 占位加载
- 全屏预览：左右滑动 + 双击缩放 + 捏合缩放
- 按日期分组 + sticky header（时区正确）

### 🔍 搜索与筛选
- 搜索栏 + 类型筛选 FilterChip（图片/视频/Live Photo）
- 搜索关键词高亮

### 📋 媒体详情
- 图片详情面板（EXIF 信息，上滑展开）
- 视频信息（时长/分辨率/编码，via ffprobe）

### 🎨 编辑
- 图片裁剪（可拖拽调整裁剪框）
- 旋转：90° 步进 + 自由角度滑块
- 基础滤镜：原图 / 黑白 / 暖色 / 冷色
- 保存到相册 + 可选上传后端

### ⭐ 收藏与相册
- 单条/批量收藏（持久化到后端）
- 收藏列表查看
- 创建/删除相册，媒体加入/移出相册

### 🎬 视频播放
- Android VideoView / iOS AVPlayer
- Range 流式播放（HTTP 分片拖拽）

### 📤 分享与批量操作
- 系统分享（单条/批量）
- 选择模式底栏：全选/反选/批量删除/批量上传
- 长按震动反馈（HapticFeedback）

### ⚙️ 设置
- 后端地址配置
- 主题切换
- OpenClaw 运维入口
- 关于页

### 🖥️ 后端服务
- Go gRPC + REST gateway（端口 :8080）
- 媒体上传/删除/元数据管理
- ffmpeg 视频缩略图生成 + LRU 缓存
- 收藏 + 相册持久化
- 健康检查（media_count / uptime / cache stats / favorite_count）
- OpenClaw 桥接（REST 代理到本地 ops-server gateway）
- JWT 用户认证 + 首次启动自动引导超管账号

---

## 🚀 快速开始

### 方式一：Docker Compose 一键部署（推荐）

> 适用于服务器 / NAS / 任何装有 Docker 的环境，5 分钟完成部署。

```bash
# 1. 进入部署目录
cd deploy

# 2. 拷贝环境变量模板并编辑（至少填写 JWT 密钥，或直接用部署脚本自动生成）
cp .env.example .env

# 3. 一键部署（自动生成随机 JWT 密钥 + 构建镜像 + 启动）
./deploy.sh
```

**首次启动后获取管理员账号：**

```bash
# 后端首次启动会在日志中打印一次性超管账号 + token（密码不落日志）
docker compose logs media-server | grep -A8 "INITIAL ADMIN"

# 输出示例：
#   INITIAL ADMIN ACCOUNT CREATED (first run, empty user DB)
#   username: admin
#   token   : eyJhbGciOi...（用此 token 首次登录）
#   note    : use the token to login, then CHANGE password via POST /api/auth/change-password
```

> 也可在 `.env` 中预设 `MM_BOOTSTRAP_ADMIN_USERNAME` + `MM_BOOTSTRAP_ADMIN_PASSWORD` 指定管理员凭据。

**验证服务健康：**

```bash
curl http://localhost:8080/healthz
# {"status":"ok", ...}
```

**常用部署命令：**

| 命令 | 说明 |
|------|------|
| `./deploy.sh` | 构建并启动 |
| `./deploy.sh --no-build` | 仅启动（镜像已构建） |
| `./deploy.sh status` | 查看容器状态 |
| `./deploy.sh logs` | 查看日志（follow） |
| `./deploy.sh restart` | 重启容器 |
| `./deploy.sh down` | 停止并移除容器（**保留数据卷**） |
| `docker compose down -v` | ⚠️ 连同数据卷一并删除（谨慎！） |

<details>
<summary>手动部署（不用 deploy.sh）</summary>

```bash
cd deploy
cp .env.example .env
# 手动生成 JWT 密钥
echo "MM_JWT_SECRET=$(openssl rand -hex 32)" >> .env
echo "MM_OPS_JWT_SECRET=$(openssl rand -hex 32)" >> .env

# 构建镜像（从源码）
cd ../backend && docker build -t media-manager:latest .
cd ../ops-server && docker build -t media-manager/ops-server:latest .
cd ../deploy

docker compose --env-file .env up -d
```
</details>

---

### 方式二：首次创建管理员账号

部署完成并获取 token 后：

1. 在移动端 App 的 **设置 → 后端地址** 填入服务器地址（如 `http://192.168.1.100:8080`）
2. 使用日志中打印的 **token** 登录（App 支持用户名 + token 登录）
3. 登录后调用 `POST /api/auth/change-password` 修改默认密码
4. 之后如需建号，管理员可通过 API 或改变 `MM_ALLOW_SIGNUP` 模式开放注册

> **注册模式说明：**
> - `off`（默认）：禁止自助注册，最安全
> - `first`：首位注册者自动获 admin 角色，其后关闭 —— 适合冷启动
> - `open`：任意人可注册（角色固定 user）—— 适合受信内部环境

---

### 方式三：Android APK 安装

```bash
# 从源码构建 APK（需 JDK 17 + Android SDK）
cd frontend
./gradlew :composeApp:assembleDebug

# APK 输出位置：
# frontend/composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

安装到设备：

```bash
adb install frontend/composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

> 安装后在 App 设置页配置后端地址。模拟器用 `http://10.0.2.2:8080`，真机用服务器局域网 IP。

---

## 🏗️ 架构

```
┌─────────────────────────────────────────────────────────┐
│                    移动端 App (KMP)                      │
│         Compose Multiplatform (Android / iOS)           │
│   ┌──────────┬──────────┬──────────┬───────────────┐   │
│   │  浏览    │  搜索    │  编辑    │  收藏/相册    │   │
│   └────┬─────┴────┬─────┴────┬─────┴───────┬───────┘   │
│        │          │          │              │           │
│        └──────────┴──── Ktor ┴──────────────┘           │
│                     HTTP / REST                         │
└────────────────────────┬────────────────────────────────┘
                         │ :8080
┌────────────────────────▼────────────────────────────────┐
│              Media Manager Backend (Go)                 │
│  ┌─────────────────────────────────────────────────┐    │
│  │  REST Gateway (:8080)                           │    │
│  │  /api/auth/*  /api/media/*  /api/favorites/*    │    │
│  │  /api/albums/*  /healthz  /api/openclaw/*       │    │
│  └───────────┬─────────────────────┬───────────────┘    │
│              │                     │                    │
│  ┌───────────▼─────────┐  ┌───────▼────────┐           │
│  │  Media Service      │  │  OpenClaw Bridge│           │
│  │  (gRPC :50051 内部) │  │  (REST 代理)    │           │
│  │  · 上传/缩略图      │  └───────┬────────┘           │
│  │  · ffmpeg 抽帧      │          │                    │
│  │  · EXIF 解析        │          │                    │
│  └──────────┬──────────┘          │                    │
│             │                     │                    │
│  ┌──────────▼──────────┐          │                    │
│  │  SQLite (media.db)  │          │                    │
│  │  + 文件存储          │          │                    │
│  └─────────────────────┘          │                    │
└───────────────────────────────────┼────────────────────┘
                                    │ :8090 (HTTP/WS)
┌───────────────────────────────────▼────────────────────┐
│              Ops Server (Go) - 运营服务端               │
│  ┌─────────────┬──────────────┬────────────────────┐   │
│  │ REST/WS :8090│ TCP Relay   │ Admin Web UI       │   │
│  │ 账号/注册/发现│ :18791      │ (内嵌静态页)        │   │
│  └─────────────┴──────────────┴────────────────────┘   │
│  ┌─────────────────────────────────────────────────┐    │
│  │  SQLite (ops.db)  +  会话状态                    │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

**技术栈：**

| 层 | 技术 |
|---|---|
| 前端 | Kotlin Multiplatform (KMP) + Compose Multiplatform |
| UI | Material3 动态色调, Compose Animation |
| 网络 | Ktor Client (Android/Darwin engine) |
| 图片加载 | Coil3 (coil-compose + coil-network-ktor) |
| 序列化 | kotlinx.serialization JSON |
| 协议 | Wire (gRPC proto 兼容) |
| 后端 | Go 1.24, gRPC + REST gateway |
| 运营服务端 | Go 1.24, REST + WebSocket + TCP Relay |
| 媒体处理 | ffmpeg / ffprobe（缩略图抽帧、视频信息解析） |
| 元数据 | EXIF (go-exif) |
| 数据库 | SQLite (modernc.org/sqlite — 纯 Go，免 CGO) |
| Android | minSdk 24, targetSdk 36, compileSdk 36 |
| iOS | compileKotlinIosArm64 |

---

## ⚙️ 环境变量配置

### 后端 (media-server)

| 变量 | 必填 | 默认值 | 说明 |
|------|:----:|--------|------|
| `MM_JWT_SECRET` | ✅ 生产必填 | （空→内存随机） | JWT HS256 签名密钥。生产必须用强随机串，`openssl rand -hex 32` |
| `MM_PORT` | ❌ | `8080` | REST gateway 端口（容器内固定 8080，控制宿主机映射） |
| `MM_DATA_DIR` | ❌ | `./data` | 数据根目录（容器内 `/app/data`） |
| `MM_DB_PATH` | ❌ | `<data_dir>/media.db` | SQLite 数据库路径 |
| `MM_JWT_TTL_SECONDS` | ❌ | `604800` (7天) | JWT token 有效期（秒） |
| `MM_ALLOW_SIGNUP` | ❌ | `off` | 注册模式：`off` / `first` / `open` |
| `MM_OPS_SERVER_URL` | ❌ | （空） | ops-server 地址，如 `http://ops-server:8090` |
| `MM_BOOTSTRAP_ADMIN_USERNAME` | ❌ | `admin` | 首次启动超管用户名 |
| `MM_BOOTSTRAP_ADMIN_PASSWORD` | ❌ | （空→随机生成） | 首次启动超管密码，留空则日志打印 token |

### 运营服务端 (ops-server)

| 变量 | 必填 | 默认值 | 说明 |
|------|:----:|--------|------|
| `MM_OPS_JWT_SECRET` | ✅ 生产必填 | （空→内存随机） | JWT 签名密钥 |
| `MM_OPS_HTTP_ADDR` | ❌ | `:8090` | HTTP/WS 监听地址 |
| `MM_OPS_RELAY_ADDR` | ❌ | `:18791` | TCP 中继监听地址 |
| `MM_OPS_DATA_DIR` | ❌ | `/data` | 数据目录 |
| `MM_OPS_DB_PATH` | ❌ | `<data_dir>/ops.db` | SQLite 数据库路径 |
| `MM_OPS_JWT_TTL_SECONDS` | ❌ | `604800` | JWT 有效期（秒） |
| `MM_OPS_BOOTSTRAP_ADMIN` | ❌ | `admin:admin123` | 首次启动首位 admin（格式 `user:pass`） |
| `MM_OPS_SIGNUP_MODE` | ❌ | `first` | 注册模式：`first` / `off` |

> **配置覆盖链：** 代码默认 < config.yaml < MM_* 环境变量

---

## 🛠️ 开发指南

### 前置依赖

- **Go** 1.24+
- **Node.js**（如需 proto 代码生成）
- **JDK 17** + **Android SDK**（compileSdk 36）
- **Xcode** 工具链（iOS 编译）
- **ffmpeg / ffprobe** 在 PATH 中可用
- **Docker** + **Docker Compose V2**（容器化部署）

### 本地运行后端

```bash
cd backend

# 方式一：直接运行（无配置文件，用代码默认值 + 环境变量）
cp .env.example .env
source .env
go run ./cmd/server

# 方式二：用 config.yaml
cp config.example.yaml config.yaml
# 编辑 config.yaml ...
go run ./cmd/server
```

后端默认监听 `:8080`，健康检查 `GET /healthz`（免认证）。

### 本地运行 ops-server（可选）

```bash
cd ops-server
MM_OPS_JWT_SECRET=dev-secret go run ./cmd/ops-server
# 监听 :8090 (HTTP) + :18791 (TCP relay)
```

### 编译前端

```bash
cd frontend
./gradlew :composeApp:assembleDebug          # Android APK
./gradlew :composeApp:compileKotlinIosArm64  # iOS Kotlin 编译（需 Xcode）
```

### Docker 构建镜像

```bash
# 后端
cd backend && docker build -t media-manager:latest .

# 运营服务端
cd ops-server && docker build -t media-manager/ops-server:latest .

# 或用 compose 从源码直接构建启动
cd backend && docker compose up -d --build
```

---

## 📁 项目结构

```
media-manager/
├── backend/               # Go 后端 (gRPC + REST gateway)
│   ├── cmd/server/        # 入口
│   ├── internal/
│   │   ├── config/        # 配置加载 + 环境变量覆盖
│   │   ├── gateway/       # REST 路由 + OpenClaw 桥接
│   │   ├── service/       # 媒体/收藏/相册/缩略图/云源
│   │   └── auth/          # JWT 认证 + 超管引导
│   ├── gen/               # proto 生成代码
│   ├── Dockerfile         # 两阶段构建（ffmpeg + tini + healthcheck）
│   ├── docker-compose.yml # 单服务编排
│   ├── config.example.yaml
│   └── .env.example
├── ops-server/            # 运营服务端 (REST + WS + TCP relay)
│   ├── cmd/ops-server/
│   ├── internal/
│   ├── Dockerfile
│   └── docker-compose.yml
├── frontend/              # KMP + Compose Multiplatform
│   ├── composeApp/
│   │   └── src/
│   │       ├── commonMain/   # 跨平台共享代码
│   │       ├── androidMain/  # Android 实现
│   │       └── iosMain/      # iOS 实现
│   └── shared/              # KMP 共享模块
├── deploy/                # 生产级一键部署
│   ├── docker-compose.yml # backend + ops-server 联合编排
│   ├── .env.example       # 环境变量模板
│   └── deploy.sh          # 一键部署脚本
├── shared/proto/          # proto 定义
├── docs/                  # 文档 (PRD, QA 报告, 架构)
├── .agents/               # 接口契约 + 协调者笔记
└── plan/                  # Sprint 规划
```

---

## ❓ 常见问题

### Q: 首次启动后怎么登录？找不到密码

后端首次启动（user 表为空）会自动创建超管账号并**在日志中打印一次性 token**（密码不落日志以保安全）：

```bash
docker compose logs media-server | grep -A8 "INITIAL ADMIN"
```

用 token 登录后，调用 `POST /api/auth/change-password` 设置自己的密码。

如需预设凭据，在 `.env` 中设置：
```
MM_BOOTSTRAP_ADMIN_USERNAME=admin
MM_BOOTSTRAP_ADMIN_PASSWORD=your-secure-password
```

### Q: JWT 密钥忘了 / 重启后 token 全部失效

JWT 密钥留空时每次重启都会生成新的内存随机密钥，导致所有已签发 token 作废。**生产环境必须**在 `.env` 中固定 `MM_JWT_SECRET`。`deploy.sh` 会自动生成并写入。

### Q: 如何开放注册让其他人建号？

在 `.env` 中设置 `MM_ALLOW_SIGNUP=open`，重启容器。任意人可通过注册端点建号（角色固定 user）。受信内部环境推荐 `first`（首位 admin 后自动关闭）。

### Q: 不需要 ops-server，能只部署后端吗？

可以。ops-server 是可选的运营面。只部署后端：

```bash
cd backend
cp .env.example .env  # 或直接用 docker-compose.yml 的默认值
docker compose up -d --build
```

不配置 `MM_OPS_SERVER_URL` 时，OpenClaw 桥接端点返回 502，**不影响媒体主功能**。

### Q: 上传的文件存在哪里？怎么备份？

数据落在 Docker 命名卷 `media-data`（映射到容器 `/app/data`）：

```bash
# 查看卷位置
docker volume inspect media-data

# 备份
docker run --rm -v media-data:/data -v $(pwd):/backup alpine tar czf /backup/media-data-backup.tar.gz -C /data .

# 恢复
docker run --rm -v media-data:/data -v $(pwd):/backup alpine tar xzf /backup/media-data-backup.tar.gz -C /data
```

目录布局：`users/<uid>/`（按用户隔离）、`cloud-images/`（共享）、`media.db`（SQLite）。

### Q: Android 真机连不上后端？

模拟器用 `http://10.0.2.2:8080`（特殊别名指向宿主机）。真机需用服务器局域网 IP，如 `http://192.168.1.100:8080`，确保手机与服务器在同一网络、防火墙放行 8080 端口。

### Q: iOS 怎么编译运行？

```bash
cd frontend
./gradlew :composeApp:compileKotlinIosArm64
```

完整 iOS App 需在 Xcode 中打开 `frontend/composeApp/iosApp/` 项目配置签名后构建到真机。

### Q: 如何更新到新版本？

```bash
cd deploy
git pull                          # 拉取最新代码
docker compose up -d --build      # 重新构建并启动（数据卷保留）
```

---

## 📚 文档

- [PRD v2](docs/PRD-v2.md) — 产品需求文档
- [架构文档](docs/ARCHITECTURE.md) — 前后端架构简述
- [接口契约](.agents/interface-contract.md) — REST API 定义
- [QA 报告](docs/FINAL-QA-REPORT.md) — 最终 QA 验收报告
- [部署指南](deploy/) — 生产级 Docker 部署

---

## 📄 License

开源项目，欢迎自托管使用与贡献。
