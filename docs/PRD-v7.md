# Media Manager — PRD v7.0（体验打磨 + 功能完善 + RN 动态下发）

> 基线：main 89d2e5e（V6 sprint + QA hotfix 全部完成）
> 更新时间：2026-07-30
> 对标：百度网盘 + 小米相册

---

## 0. 本 sprint 的目标

V6 收尾后，三端编译通过、QA P0/P1 修复、安卓真机端到端验证通过。但产品对标百度网盘/小米相册仍有体验和功能差距。本 sprint 做三件事：

1. **体验打磨**（§1）：补齐百度网盘/小米相册的核心体验差距——回收站、分享链接、文件搜索分类、时光相册、备份进度通知。
2. **功能完善**（§2）：照片编辑增强（涂鸦/马赛克）、视频编辑（裁剪）、共享相册、存储清理建议。
3. **RN 动态下发**（§3）：基于已有 rn-module KMP 框架，实现 RN 页面容器 + 远程 bundle 下载/缓存/加载，用于动态功能下发（如运营活动页、A/B 测试页、热修复页）。

---

## 1. 体验打磨

### 1.1 回收站（对标百度网盘「回收站」）

现状：删除走 MarkDeletedForUser 软删墓碑，UndeleteMedia 已实现（秒传命中软删记录复活），但没有用户可见的回收站 UI。

待办：
1. 后端：`GET /api/media/trash` 返回当前用户 deleted=true 的 media 列表（分页，复用 ListMediaChanges 过滤 deleted=1）。`POST /api/media/restore` 按 id+user_id 复活（复用 UndeleteMedia，加 user_id 校验防越权）。`POST /api/media/purge` 物理删除（DeleteMedia + 文件删除，按 user_id 校验）。
2. 前端：新增「回收站」入口（设置页或文件管理页），展示已删列表，支持「恢复」「彻底删除」「清空回收站」。
3. 30 天自动清理：后端定时任务 purging deleted_at 超过 30 天的记录（或标记 deleted_at 时间戳，查询时过滤）。

验收：删除一图 → 回收站可见 → 恢复后列表重现 → 彻底删除后不可恢复。

### 1.2 分享链接（对标百度网盘「分享」）

现状：只有系统分享面板（ShareUtils），没有后端分享链接。

待办：
1. 后端：`POST /api/share/create` 生成分享 token（随机短链 ID），关联 media_id 列表 + user_id，设过期时间（默认 7 天）+ 可选密码。`GET /api/share/{token}` 公开访问（无认证），返回 media 元数据 + 字节流。`DELETE /api/share/{token}` 撤销分享（仅创建者）。
2. 前端：选中图片 → 「分享链接」按钮 → 生成链接展示/复制。支持设密码、过期时间。
3. 分享页：`/share/{token}` 路由（独立页面，无登录态），展示分享的图片网格 + 下载按钮。

验收：选中 3 张图 → 生成分享链接 → 另一台设备/浏览器打开链接 → 看到 3 张图可下载。

### 1.3 文件搜索与分类（对标百度网盘「文件分类」）

现状：本地搜索只搜 filename，云端无搜索。

待办：
1. 后端：`GET /api/media/list` 已支持 `q` 参数搜索 filename。扩展支持 `type` 过滤（IMAGE/VIDEO/LIVE_PHOTO）、`album_id` 过滤、`favorite=true` 过滤。新增 `sort` 参数（date/size/name）。
2. 前端：文件管理页增加分类筛选（图片/视频/文档/收藏），增加排序选项，搜索框支持后端搜索（当前只搜本地）。
3. 按日期/大小分组展示（已有日期分组 DateGroupedGrid，补大小分组）。

验收：文件管理页输入关键词 → 后端搜索结果实时返回 → 按类型/时间/大小筛选排序。

### 1.4 时光相册（对标小米「回忆」）

现状：无自动生成回忆功能。

待办：
1. 前端：基于已有 cloudMedia + syncCursor 数据，自动生成「这一年」「本月精选」「某地合集」等回忆卡片。用 MediaMetadata.created_at 做年份/月份分组，取每月前 5 张作为封面。
2. 首页时光线：在「已上传」Tab 顶部加「回忆」横滚卡片区域，点击进入回忆详情页（展示该时间段全部图片）。
3. 纯前端实现，无需后端改动（数据已有）。

验收：已上传 Tab 顶部显示「2026 年 7 月」回忆卡 → 点击进入当月图片列表。

### 1.5 备份进度通知（对标小米「备份中 N/M」）

现状：有 SettingsScreen 的 WiFi/充电开关，但无通知栏备份进度。

待办：
1. Android：用 NotificationCompat.foregroundService 风格的进度通知（备份中 N/M · 已暂停非 WiFi）。需要 MediaService foreground notification。
2. 设置页展示「待备份 N 项」「上次备份时间」。
3. commonMain 抽象 BackupStatusNotifier（expect/actual），Android 用 NotificationManager，iOS 暂不实现（后台受限）。

验收：开启自动备份 → 拍照 → 通知栏显示「备份中 1/3」→ 完成后通知消失 + 设置页显示上次备份时间。

---

## 2. 功能完善

### 2.1 照片编辑增强（对标小米「涂鸦/马赛克」）

现状：ImageEditor 已有 Crop + 滤镜（ColorMatrix），无涂鸦/马赛克/文字标注。

待办：
1. ImageEditor 增加「涂鸦」模式：Canvas 上自由绘制路径（DrawPath），支持颜色/粗细选择。
2. 增加「马赛克」模式：手指划过区域像素化处理（取像素 → downscale → upscale 叠加）。
3. 增加「文字」标注：在指定位置添加可拖拽的 Text 叠加层。
4. 保存：将编辑后的 Bitmap 写入 Bitmap → 上传到后端（复用 uploadLocal）。

验收：打开图片 → 涂鸦画线 + 打马赛克 → 保存 → 编辑后图片上传到云端。

### 2.2 视频编辑（对标小米「视频裁剪」）

现状：有视频播放器，无编辑。

待办：
1. 新增 VideoEditor 页面：加载视频 → 展示时间轴 → 选择起止点 → 预览裁剪片段 → 导出。
2. Android：用 MediaCodec/MediaMuxer 裁剪（或 ffmpeg if available）。
3. iOS：用 AVAssetExportSession。
4. 导出后上传到后端。

验收：打开视频 → 拖动起止点 → 导出裁剪片段 → 上传。

### 2.3 共享相册（对标百度网盘「共享」）

现状：有相册（AlbumStore），但只能单用户私有。

待办：
1. 后端：Album 表增加 `shared_with` JSON 列（user_id 列表）或新建 `album_share` 关联表。`POST /api/media/album/share` 邀请用户。`GET /api/media/albums/shared` 返回被共享的相册。
2. 前端：相册页增加「共享相册」Tab，展示他人共享给你的相册。创建相册时可选「共享」并邀请用户。

验收：A 创建相册 → 共享给 B → B 的相册页看到共享相册 → B 可查看/添加图片。

### 2.4 存储清理建议（对标小米「清理」）

现状：有 /api/sync/usage 展示用量，无清理建议。

待办：
1. 前端：分析 cloudMedia，找出「疑似重复」（sha256 不同但 filename + size 相同）、「大文件 Top 10」、「老照片（>1年未看）」等清理建议。
2. 设置页增加「存储清理」入口，展示建议列表，支持一键删除选中项。

验收：存储清理页展示「3 张疑似重复」「5 个大文件」→ 选中删除 → 用量下降。

---

## 3. RN 动态功能下发

### 3.1 RN 页面容器

现状：rn-module 已有 RnManager + RNContainerManager（Android Bridgeless + iOS），但 composeApp 没有实际使用的 RN 页面。

待办：
1. 新增 `RnContainer` Composable（commonMain）：接收 componentName + bundlePath，在 Android 用 `AndroidView` 嵌入 ReactRootView/Surface，iOS 用 `UIKitView` 嵌入 RCTRootView。
2. 新增 `RnPage` Screen：从 App 导航跳转到 RnContainer，展示指定 RN 页面。
3. 导航：设置页增加「动态模块」入口，跳转到一个 RN Demo 页面（验证容器可用）。

### 3.2 远程 Bundle 下载与缓存

现状：RNContainerManager 只支持从 assets 加载 bundle（bundleAssetName）。

待办：
1. 后端：`GET /api/rn/bundle/{name}` 返回 JS bundle 文件（从 data/rn-bundles/ 目录读取，支持版本管理）。
2. 前端：RnBundleDownloader（commonMain + expect/actual），下载远程 bundle 到本地缓存目录。缓存按 `name + version` 管理版本，支持增量更新（比对版本号决定是否下载）。
3. RnManager.loadRemoteBundle(name, version)：下载 → 缓存 → 调 RNContainerManager 从文件路径加载。
4. 版本管理：后端 `GET /api/rn/manifest` 返回可用 bundle 列表 + 版本号，App 启动时拉取，有新版本即下载。

### 3.3 动态功能页（Demo：运营活动页）

待办：
1. 创建一个简单的 RN bundle（`activity-bundle`）：展示一个运营活动列表页（从后端 `GET /api/promotions` 拉活动数据，展示 banner + 标题 + 跳转链接）。
2. App 设置页「活动中心」入口 → 加载 `activity-bundle` → 展示活动列表。
3. 后端 `GET /api/promotions` 返回活动 JSON（标题、图片 URL、链接、过期时间）。
4. 发布流程：RN bundle 打包 → 上传到后端 data/rn-bundles/ → App 启动检测新版本 → 下载缓存 → 下次打开生效。

验收：后端放新版本 activity-bundle → App 重启 → 打开「活动中心」展示新内容（无需 App 发版）。

---

## 4. DAG 任务拆分

按文件边界分层，降低并行冲突：

### Layer 0（并行，无依赖）

- v7-trash（backend + frontend）：§1.1 回收站 API + UI
- v7-share（backend + frontend）：§1.2 分享链接 API + UI
- v7-search-filter（backend + frontend）：§1.3 文件搜索分类增强
- v7-rn-backend（backend）：§3.2 RN bundle 端点 + 版本管理 + promotions API
- v7-rn-bundle（frontend/rn-bundles/）：§3.3 RN Demo bundle 项目脚手架

### Layer 1（依赖 Layer0，并行）

- v7-time-album（frontend）：§1.4 时光相册（依赖 cloudMedia 数据结构稳定）
- v7-backup-notify（frontend）：§1.5 备份进度通知（依赖 BackupPolicy 已就绪）
- v7-photo-edit（frontend）：§2.1 照片编辑增强
- v7-video-edit（frontend）：§2.2 视频编辑
- v7-shared-album（backend + frontend）：§2.3 共享相册
- v7-cleanup（frontend）：§2.4 存储清理建议

### Layer 2（依赖 Layer1）

- v7-rn-container（frontend）：§3.1 RN 页面容器 + §3.2 远程 bundle 加载（依赖 v7-rn-backend 端点 + v7-rn-bundle 脚手架）

### Layer 3（依赖 Layer2）

- v7-qa：全量验收报告

---

## 5. 非目标

- 不做 AI 人脸识别/场景分类（需 ML 模型，超出本 sprint 范围）
- 不做 Web 端管理界面（ops-server admin 前端已够）
- 不做实时同步（push 通道），仍用 cursor 增量拉取
- 不做视频转码/压缩（仅裁剪）
- RN bundle 仅做运营活动页 demo，不做复杂业务功能

---

## 6. 验收标准

1. 编译：backend go build + ops-server go build + frontend assembleDebug + compileKotlinIosArm64 全通过
2. 回收站：删除 → 回收站可见 → 恢复 → 列表重现
3. 分享链接：生成链接 → 浏览器打开 → 下载图片
4. 时光相册：已上传 Tab 顶部回忆卡片 → 点击进入当月列表
5. 备份通知：拍照 → 通知栏显示进度 → 设置页显示上次备份时间
6. 照片编辑：涂鸦 + 马赛克 → 保存上传
7. RN 动态下发：后端放新 bundle → App 检测下载 → 打开活动中心展示新内容
