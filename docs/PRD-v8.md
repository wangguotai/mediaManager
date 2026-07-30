# PRD-v8：下一阶段产品演进

> 创建时间：2026-07-31
> 基于 V7 sprint 经验 + 用户反馈
> 对标：百度网盘 + 小米相册 + Google Photos

## 1. 核心功能

### 1.1 视频编辑（V7 §2.2 延续）
- 视频裁剪（选择起止点 → 导出片段）
- Android: MediaExtractor + MediaMuxer（无需 ffmpeg）
- iOS: AVAssetExportSession
- 导出后上传到后端

### 1.2 共享相册前端（V7 §2.3 延续）
- 相册页加「共享相册」Tab
- 创建相册时可选「共享」+ 邀请用户
- 共享相册详情页（查看/添加图片）

### 1.3 RN 热更新
- App 启动时调 /api/rn/manifest 检查版本
- 有新版自动下载 /api/rn/bundle/{name} 到缓存
- 下次启动从缓存加载新 bundle
- 版本号比对 + 回滚机制

### 1.4 AI 清理建议
- 模糊照片检测（Laplacian variance 或边缘检测）
- 低分辨率照片检测
- 截图分类检测
- 暗光/过曝检测
- 智能推荐"可删除"列表

### 1.5 离线模式
- 本地缓存最近 N 天的缩略图
- 离线浏览已缓存媒体
- 网络恢复后自动同步
- 离线标记 + 冲突解决

## 2. 体验优化

### 2.1 图片加载性能
- 分页加载云端媒体（当前一次性加载全部 cloudMedia）
- 缩略图内存缓存 LRU（当前每次重新解码）
- 大图降采样加载（已实现 decodeImageBitmapDownsampled）

### 2.2 上传体验
- 后台上传服务（WorkManager）
- 上传重试 + 断点续传
- 上传队列管理（暂停/恢复/取消）
- 上传进度通知（已有 BackupStatusNotifier，扩展为上传通知）

### 2.3 搜索增强
- 全文搜索（文件名 + EXIF 描述 + 标签）
- 按日期范围筛选
- 按位置筛选（如有 GPS EXIF）
- 搜索历史 + 智能建议

## 3. 运维与安全

### 3.1 后端可观测性增强
- Prometheus metrics 导出（已有 /metrics，扩展指标）
- 健康检查深化（DB / 磁盘 / 网络 分项检查）
- 日志结构化改进（已有 slog，加 request tracing）

### 3.2 安全加固
- 分享链接访问限速
- 上传文件类型白名单校验（MIME + magic bytes）
- JWT token 刷新机制
- 密码强度检查已有，加密码泄漏检测

## 4. 技术债

### 4.1 iOS RN 容器
- 用 UIKitView 嵌入 RCTRootView
- Podfile 配置 React Native 依赖
- bridging header + RCTBridge 初始化

### 4.2 照片编辑持久化
- 当前涂鸦/马赛克/文字为预览不持久化
- 保存时烘焙叠加层到 Bitmap（需要 commonMain 位图操作 API）

### 4.3 分页加载
- 后端 /api/media/list 加 cursor 分页
- 前端 cloudMedia 分页拉取 + hasMore 状态
- 已有 sync/changes 增量同步，list 分页补齐

## 5. 非目标

- 不做 AI 人脸识别/场景分类（需 ML 模型，超出范围）
- 不做 Web 端管理界面（ops-server admin 前端已够）
- 不做实时同步（push 通道），仍用 cursor 增量拉取
- 不做视频转码/压缩（仅裁剪）

## 6. 优先级排序

P0（必须做）:
- §1.2 共享相册前端（V7 后端已就绪）
- §2.1 图片加载性能（分页+LRU）
- §4.3 分页加载

P1（重要）:
- §1.1 视频编辑
- §1.3 RN 热更新
- §2.2 上传体验（WorkManager）

P2（锦上添花）:
- §1.4 AI 清理建议
- §1.5 离线模式
- §2.3 搜索增强
- §3.1 可观测性增强
- §4.1 iOS RN 容器
- §4.2 照片编辑持久化
