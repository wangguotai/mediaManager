# PRD-v9：体验打磨——让 APP 好用

> 创建时间：2026-08-10
> 基线：PRD-v8 全部功能完成，v1.0.0 已上架
> 目标：从"功能齐全"到"体验出色"，对标 Google Photos / 小米相册的交互流畅度

## 问题诊断

当前 APP 功能完整但体验粗糙：
- 导航用 `when(screen)` 硬切，无动画过渡，页面切换突兀
- 图片预览无滑动翻页（返回→重新点下一张）
- 视频播放器无全屏、无手势控制亮度/音量
- 无底部 Sheet（ModalBottomSheet）——操作都用全屏 Dialog
- MediaListScreen 5840行巨物导致编译慢、维护难
- 搜索框展开逻辑复杂、无搜索结果高亮
- 相册列表无拖拽排序
- 无"照片详情"侧滑面板（EXIF/位置/标签一键查看）
- 上传进度只在通知栏，App 内不可见
- 空态/错误态体验不统一（各页各写各的）

## 1. 导航与过渡

### 1.1 页面切换动画
- App.kt 的 `when(screen)` 包裹 `AnimatedContent`
- 入场：slideInHorizontally + fadeIn（从右滑入）
- 退场：slideOutHorizontally + fadeOut（向右滑出）
- 语义化方向：前进(右→左)，后退(左→右)
- Tab 切换用 Crossfade（无滑动，仅淡入淡出）

### 1.2 手势返回支持
- 预览/编辑/全屏模式支持左滑手势返回
- Android: BackHandler + 滑动手势
- iOS: interactivePopGestureRecognizer 兼容

## 2. 图片浏览体验

### 2.1 预览滑动翻页
- ImagePreviewDialog 改为 HorizontalPager
- 左右滑动切换上一张/下一张媒体
- 当前位置指示器（如 "3 / 42"）
- 双指缩放 + 双击缩放在 Pager 内正常工作
- 缩放状态下禁用滑动（避免误触翻页）

### 2.2 预览底栏操作增强
- 底部操作栏：收藏/分享/编辑/删除/信息（图标行）
- 收藏按钮带动画（心形跳变）
- 删除带二次确认 BottomSheet（非 AlertDialog）
- 信息按钮 → 照片详情 BottomSheet

### 2.3 照片详情 BottomSheet
- ModalBottomSheet 展示：文件名/大小/尺寸/拍摄时间/设备/位置/标签
- EXIF 摘要（光圈/ISO/焦距/曝光）
- 标签 chips（可点击编辑）
- 相册归属（可点击移除/添加）

## 3. 视频播放体验

### 3.1 全屏播放
- 视频播放器支持全屏切换（旋转横屏）
- 全屏时隐藏状态栏/导航栏
- 退出全屏恢复竖屏

### 3.2 手势控制
- 左半屏上下滑 = 亮度
- 右半屏上下滑 = 音量
- 水平滑动 = seek（显示预览时间气泡）
- 双击暂停/播放

### 3.3 播放控制
- 长按 2x 速播放（显示 "2x" 指示）
- 进度条拖拽预览缩略图（蓝色光标，资金流向化简版）

## 4. 网格与列表体验

### 4.1 网格长按多选优化
- 长按进入多选模式 + 触觉反馈（已有 AlbumScreen，缺 MediaGrid）
- 多选时底部 BottomSheet（而非全屏活动栏）显示操作
- 选中态：缩略图叠半透明蓝色 + 勾选标记 + 缩放动画

### 4.2 网格瀑布流畅度
- staggeredGrid 预加载窗口扩大（prefetch 远端项）
- 缩略图加载用 ShimmerPlaceholder（已有，确认全路径覆盖）
- 滚动时临时降采样（fastScroll 时不加载高清）

### 4.3 日期分组粘性头
- DateGroupedGrid 日期头 sticky（滚动时悬浮）
- 头部显示日期 + 当日数量（如 "8月10日 · 12张"）

## 5. 搜索体验

### 5.1 搜索流程优化
- 搜索框点击展开为全屏搜索页（非 inline）
- 搜索历史 chips（最近 5 个，可删）
- 搜索建议实时下推（已在打字时请求）
- 空搜索显示"最近查看"/"热门标签"

### 5.2 搜索结果高亮
- 文件名匹配部分高亮（SpanStyle color）
- 搜索结果页用专用网格（非复用主网格）
- 无结果时显示建议（"试试搜索 $tag"）

## 6. 上传体验

### 6.1 App 内上传进度
- 底部上传进度条（MediaListScreen 底部悬浮）
- 显示 "正在上传 3/15" + 进度百分比 + 可展开队列
- 展开后 BottomSheet 显示完整队列+暂停/恢复/取消

### 6.2 上传后体验
- 上传成功：媒体列表顶部插入新项 + 淡入动画
- 上传失败：Toast/ Snackbar 提示 + 重试按钮
- 秒传：轻提示 "该照片已存在"，不插入

## 7. 代码健康度

### 7.1 MediaListScreen 拆分
- 5840行 → 按组件分组拆到 medialist/ 子目录
- 预览组: ImagePreviewDialog + ZoomableImage + ThumbnailStrip
- 网格组: MediaGrid + MediaGridItem + DateGroupedGrid
- 对话框组: ShareDialog + RenameDialog + TagDialog + InfoDialog
- 底栏组: SelectionBottomBar + UploadFab + UploadProgress
- 主文件只保留 MediaListScreen 编排

## 8. 视觉一致性

### 8.1 统一空态/错误态组件
- EmptyState(title, subtitle, icon, actionText, onAction)
- ErrorState(message, onRetry)
- 所有页面统一使用（替换各页各写的）

### 8.2 统一 BottomSheet 组件
- MediaBottomSheet(content) 封装 ModalBottomSheet
- 统一圆角/dragHandle/scrimColor
- 替换所有 AlertDialog 为 BottomSheet（删除/分享/标签等）

## 9. 非目标
- 不引入 Navigation Compose（when(screen) 够用，避免 KMP 兼容风险）
- 不做 Material3 动态取色（minSdk 覆盖不足）
- 不做 onboarding 引导页（目标用户是技术背景）

## 10. 优先级

P0（体验核心）:
- §2.1 预览滑动翻页
- §4.1 网格长按多选
- §8.1 统一空态/错误态组件

P1（显著提升）:
- §1.1 页面切换动画
- §2.2 预览底栏操作
- §6.1 App 内上传进度

P2（打磨细节）:
- §3.1-3.3 视频体验增强
- §5.1-5.2 搜索优化
- §7.1 MediaListScreen 拆分
- §8.2 统一 BottomSheet
