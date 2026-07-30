# Media Manager 使用说明

> 个人云盘 + 小米相册风格的媒体管理应用
> 版本: V4 (188 commits)
> 更新: 2026-07-30

---

## 一、架构概览

```
客户端 App (Android/iOS)
    │
    ├── 直连存储服务端（局域网/自配公网IP）
    │         │
    │         ▼
    │   存储服务端 (Go, Docker一键部署)
    │   - 用户认证 + 多设备同步
    │   - 文件存储/缩略图/视频流
    │
    └── 经运营服务端中继（跨网时）
              │
              ▼
        运营服务端 (Go, 公网部署)
        - 设备发现 + TCP中继
        - 运营前端管理台
```

### 三组件

| 组件 | 目录 | 技术栈 | 端口 |
|------|------|--------|------|
| 存储服务端 | `backend/` | Go + SQLite + ffmpeg | :8080 (REST) |
| 运营服务端 | `ops-server/` | Go + SQLite + WebSocket | :8090 |
| 客户端 App | `frontend/` | Kotlin Multiplatform + Compose | Android/iOS |

---

## 二、存储服务端部署

### 方式1: Docker一键部署（推荐）

```bash
cd backend
# 修改 docker-compose.yml 中的 MM_JWT_SECRET 为随机长字符串
docker compose up -d --build

# 查看首次启动的超管账号和密码
docker compose logs media-server | grep -A8 "INITIAL ADMIN"
```

启动后:
- REST API: `http://localhost:8080`
- 健康检查: `GET http://localhost:8080/healthz`
- 数据持久化: Docker volume `media-data`

### 方式2: 裸机运行

```bash
cd backend
cp config.example.yaml config.yaml
# 编辑 config.yaml: 设置 jwt_secret, data_dir, allow_signup
go run ./cmd/server
```

### 配置说明

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| port | MM_PORT | 8080 | REST 监听端口 |
| data_dir | MM_DATA_DIR | ./data | 数据根目录 |
| db_path | MM_DB_PATH | {data_dir}/media.db | SQLite 路径 |
| jwt_secret | MM_JWT_SECRET | 随机 | JWT 签名密钥 |
| jwt_ttl_seconds | MM_JWT_TTL_SECONDS | 604800 | Token 有效期(秒, 默认7天) |
| allow_signup | MM_ALLOW_SIGNUP | off | 注册策略: off/first/open |
| ops_server_url | MM_OPS_SERVER_URL | 空 | 运营服务端地址(可选) |

优先级: 代码默认 < config.yaml < MM_* 环境变量

---

## 三、运营服务端部署

```bash
cd ops-server
go run ./cmd/ops-server
# 默认监听 :8090
# 运营前端管理台: http://localhost:8090/admin/
```

---

## 四、客户端 App 构建

### Android

```bash
cd frontend
sh gradlew :composeApp:assembleDebug
# APK: composeApp/build/outputs/apk/debug/app-debug.apk
adb install -r composeApp/build/outputs/apk/debug/app-debug.apk
```

### iOS

```bash
cd frontend
sh gradlew :composeApp:compileKotlinIosArm64
# 用 Xcode 打开 composeApp/src/iosMain 生成 Xcode 项目
```

---

## 五、客户端使用

### 首次使用

1. 打开 App → 登录页
2. 输入存储服务端地址（如 `http://192.168.31.251:8080`）
3. 输入用户名和密码（首次用服务端启动时打印的超管账号）
4. 登录成功 → 进入主页

### 主页三 Tab + 底部导航

| Tab | 功能 |
|-----|------|
| 本地图片 | 浏览手机相册，单击预览，长按选择 |
| 已上传 | 云端媒体视图（自动同步），支持搜索/筛选/收藏 |
| 网盘图片 | 后端 cloud-images 目录的共享图片 |
| 我的 | 应用设置 / 相册管理 / 文件管理 |

### 核心操作

- **单击**: 预览图片/播放视频
- **长按**: 进入选择模式（底部栏: 全选/分享/删除/上传）
- **搜索**: 点击搜索图标 → 输入关键词 → debounce 300ms 自动过滤
- **Live Photo**: 预览界面点击"动态照片"按钮播放嵌入视频
- **图片编辑**: 预览 → 编辑 → 裁剪/旋转/滤镜/保存
- **文件管理**: 我的 Tab → 文件管理 → 查看云端全部文件/用量

### 设置页

- 后端地址配置
- 主题切换 (System/Light/Dark/AMOLED)
- 退出登录

---

## 六、API 速查

### 认证

| 端点 | 方法 | 说明 |
|------|------|------|
| /api/auth/login | POST | 登录，返回 JWT token |
| /api/auth/register | POST | 注册（受 allow_signup 控制） |

### 媒体

| 端点 | 方法 | 说明 |
|------|------|------|
| /api/media/list | GET | 媒体列表（按用户隔离） |
| /api/media/upload | POST | 上传文件（支持 sha256 去重） |
| /api/media/stream/{id} | GET | 原图/视频流（支持 Range） |
| /api/media/thumbnail/{id} | GET | 缩略图 |
| /api/media/delete | POST | 删除媒体 |
| /api/media/metadata/{id} | GET | 媒体元数据 |
| /api/media/favorite | POST/DELETE | 收藏/取消收藏 |
| /api/media/favorites | GET | 收藏列表 |
| /api/media/albums | GET | 相册列表 |
| /api/media/album | POST | 创建相册 |

### 同步

| 端点 | 方法 | 说明 |
|------|------|------|
| /api/sync/changes | GET | 增量变更（?since=cursor_ms） |
| /api/sync/usage | GET | 存储用量统计 |
| /api/device/register | POST | 注册设备 |
| /api/device/list | GET | 设备列表 |

### 运维

| 端点 | 方法 | 说明 |
|------|------|------|
| /healthz | GET | 健康检查（含磁盘/内存） |
| /api/stats | GET | 缓存命中率统计 |

### 认证方式

除 /api/auth/* 和 /healthz 外，所有端点需 Header:
```
Authorization: Bearer <token>
```

---

## 七、快速验证流程

```bash
# 1. 启动后端
cd backend && go run ./cmd/server
# 记下启动日志中的超管账号密码

# 2. 登录获取 token
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"打印的密码"}'
# → {"token":"eyJ...","user":{...}}

# 3. 上传图片
curl -X POST http://localhost:8080/api/media/upload \
  -H "Authorization: Bearer <token>" \
  -F "file=@test.jpg"

# 4. 查看媒体列表
curl http://localhost:8080/api/media/list \
  -H "Authorization: Bearer <token>"

# 5. 增量同步
curl "http://localhost:8080/api/sync/changes?since=0" \
  -H "Authorization: Bearer <token>"

# 6. 健康检查
curl http://localhost:8080/healthz
```

---

## 八、已知限制

1. **iOS Keychain**: token 暂存 NSUserDefaults（待 K/N interop 修复后迁移 Keychain）
2. **ops-server**: 未 Docker 化、未真机验证（骨架已就绪）
3. **中继连接**: TCP 中继代码已实现，端到端未验证
4. **上传去重**: SyncManager 简化版未将 sha256 传到后端（后端 API 已支持）
5. **本地图片删除**: Android 10+ 需 recoverable deletion API，代码已实现待真机验证
6. **运营前端**: 纯 HTML/JS 最小版，功能可用但不美观

---

## 九、项目结构

```
media-manager/
├── backend/              # 存储服务端 (Go)
│   ├── cmd/server/       # 入口
│   ├── internal/
│   │   ├── auth/        # JWT 认证
│   │   ├── config/      # 配置 + 环境变量
│   │   ├── db/          # SQLite 存储
│   │   ├── gateway/     # REST API + 中间件
│   │   └── service/     # 业务逻辑
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── config.example.yaml
├── ops-server/          # 运营服务端 (Go)
│   ├── cmd/ops-server/
│   └── internal/
│       ├── auth/        # 运营账号
│       ├── admin/       # 管理前端(embed)
│       ├── discovery/   # 设备发现
│       └── storage/     # SQLite
├── frontend/            # 客户端 (KMP)
│   ├── composeApp/      # Android + iOS App
│   │   └── src/
│   │       ├── commonMain/  # 共享代码
│   │       ├── androidMain/ # Android 实现
│   │       └── iosMain/     # iOS 实现
│   ├── feature-media/   # 媒体服务模块
│   └── feature-common/  # 公共功能模块
└── docs/                # 文档
    ├── PRD-v4.md        # 产品需求文档
    ├── V4-QA-REPORT.md  # QA 验收报告
    └── live-photo-research.md  # Live Photo 研究报告
```
