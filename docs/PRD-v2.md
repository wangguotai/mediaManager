# Media Manager — PRD v2.0

> 目标：好用、无 bug 的 Android + iOS 双端媒体管理应用
> 参考：Google Photos、Apple Photos、小米相册

## 产品定位

一款跨端媒体管理器，支持本地/云端图片视频浏览、搜索、编辑、分享。Material3 设计语言，流畅动画，双端一致体验。

## 已有功能（Sprint 0-2 产出）

- ✅ 三 Tab：本地图片 / 已上传 / 网盘图片
- ✅ 网格浏览 + 缩略图加载 + shimmer 占位
- ✅ 全屏预览：左右滑动 + 双击缩放 + 捏合缩放
- ✅ 搜索栏 + 类型筛选 FilterChip
- ✅ 按日期分组 + sticky header
- ✅ 图片详情面板（EXIF / 上滑展开）
- ✅ 设置页（后端地址 / 主题切换 / 关于）
- ✅ 视频播放器（Android VideoView / iOS AVPlayer）
- ✅ 后端：Go gRPC + REST，ffmpeg 视频缩略图，Range 流式
- ✅ 图片编辑基础（裁剪/旋转）
- ✅ 分享功能（系统分享）
- ✅ Splash Screen + Material3 动态色调
- ✅ 时区正确的日期分组
- ✅ LRU 缓存 + 线程安全

## 已知问题（QA 报告遗留）

- P0-1: 后端地址配置是否真实生效需验证
- P1-2: deleteMedia/uploadMedia 异常时假成功
- P1-4: 内存缓存仍需优化
- P2: 搜索结果高亮未实现
- P2: OpenClaw 入口占主 TopAppBar 空间
- P2: rn-module expect/actual Beta 警告

## Night Sprint 功能计划

### Phase 1: 稳定性补丁（2 任务）

**NS-01: 后端地址真实生效 + 错误处理修复**
- 验证 SettingsStorage 的 backendUrl 被 MediaService 实际读取
- VideoPlayer 的 VIDEO_BACKEND_BASE_URL 同步读取
- deleteMedia/uploadMedia 异常返回 false 而非 true
- 连通性测试真实请求后端

**NS-02: 搜索高亮 + OpenClaw 入口归位**
- 搜索匹配的文件名高亮（SpanStyle background color）
- OpenClaw 入口从主 TopAppBar 移到设置页
- TopAppBar actions 只保留：搜索、刷新、设置（齿轮）

### Phase 2: 核心体验（3 任务）

**NS-03: 瀑布流网格 + 预览操作栏**
- LazyVerticalGrid → LazyVerticalStaggeredGrid（Google Photos 风格）
- 网格项圆角 16dp + 柔和阴影
- 预览底部操作栏：编辑 / 分享 / 删除 / 详情
- 预览进入退出 scale+fade 过渡动画
- 长按震动反馈（HapticFeedback expect/actual）

**NS-04: 图片编辑完善 + 滤镜**
- 完善 ImageEditor：裁剪框可拖拽调整大小
- 旋转：90° 步进 + 自由角度滑块
- 基础滤镜：原图 / 黑白 / 暖色 / 冷色（ColorMatrix/ColorFilter 实现）
- 保存到相册 + 可选上传后端

**NS-05: 批量操作 + 收藏**
- 选择模式底栏：全选/反选/批量分享/批量删除/批量上传
- 收藏功能：长按网格项加星标，收藏 Tab 或筛选
- 收藏状态持久化（SettingsStorage 或后端）

### Phase 3: 后端增强（2 任务）

**NS-06: 后端收藏 API + 元数据完善**
- proto 扩展：Favorite 字段 + SetFavorite RPC
- REST: POST /api/media/favorite {media_id, favorite: true}
- GetMediaList 支持 favorite 过滤
- 上传接口 REST 转 gRPC（修复元数据不一致）
- DeleteMedia 支持 cloud 目录

**NS-07: 后端性能 + 缓存**
- 缩略图缓存命中率统计
- 视频时长预取缓存到 metadata
- GetMediaList 支持按 created_at 索引（避免全扫描）
- 健康检查端点 /healthz 返回更多信息

### Phase 4: QA 验收 + 修复（2 任务）

**NS-08: 全量 QA 验收**
- 双端编译（assembleDebug + compileKotlinIosArm64）
- 逐功能验证对照 PRD
- 线程安全/内存/缓存检查
- 跨端兼容性扫描
- 输出报告到 docs/NS-QA-REPORT.md

**NS-09: Bug 修复 + 文档**
- 修复 QA 报告中的 P0/P1
- 更新 README.md
- 更新 interface-contract.md
- 最终双端构建验证

## 验收标准

1. `cd backend && go build ./...` 通过
2. `cd frontend && bash gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosArm64` 通过
3. 所有功能在代码层面完整实现，无已知 P0/P1 bug
4. 每个任务的 git commit 清晰可追溯
