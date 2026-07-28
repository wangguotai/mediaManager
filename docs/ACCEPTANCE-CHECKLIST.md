# Media Manager — 验收清单

> wgt 早上好！这是昨晚夜间连续工作的成果。140 commits，双端编译绿色。

## 项目状态
- 后端: Go gRPC + REST :8080，运行中
- 前端: KMP + Compose Multiplatform，Android 真机 + iOS 模拟器
- 140 commits，双端编译通过

## 真机验证通过的功能 ✅
1. ✅ App 启动 + Splash Screen
2. ✅ 网盘图片加载（6张测试图 + 1个测试视频）
3. ✅ 本地图片加载（真机照片，权限修复后）
4. ✅ 日期分组 + sticky header
5. ✅ 点击图片进全屏预览
6. ✅ 预览缩略图条 + 操作栏（编辑/分享/删除/详情）
7. ✅ 图片编辑器（裁剪/旋转/滤镜 5种）
8. ✅ 空状态美化（每Tab不同插画+动画）
9. ✅ 文件大小自适应显示（566 B / 34 KB / 2.8 MB）
10. ✅ 视频在列表显示（播放图标+时长 0:05）
11. ✅ FilterChip（全部/图片/视频/收藏）
12. ✅ Material3 动态色调 + 深色/AMOLED 主题

## 真机发现并修复的 bug（8个）
1. 点击图片不进预览 → combinedClickable 替代 detectTapGestures
2. 本地图片空 → READ_MEDIA_IMAGES 权限 (Android 13+)
3. 预览 crash → @android:color/white 改为 #FFFFFFFF
4. 大图 OOM → 降采样 + LRU + 分页
5. 文件大小 0.0 MB → 自适应单位
6. 视频不显示 → parseMediaType 支持整数类型
7. 上传文件名 .dat → 保留原始文件名
8. 设置入口 → 移到 bottomBar（TopAppBar 在 MIUI 不生效）

## 待你手动验证
- ❓ 设置页打开（bottomBar 设置按钮在 MIUI 可能需要调整位置）
- ❓ 视频播放器（VideoPlayer 点击视频应打开播放器）
- ❓ 搜索功能（点击搜索图标输入关键词）
- ❓ 幻灯片播放
- ❓ 相册功能
- ❓ 分享功能

## 功能清单（代码层面已实现）
- 瀑布流网格 + 日期分组 + 搜索高亮
- 全屏预览（缩放/滑动/缩略图条/毛玻璃背景）
- 幻灯片播放（自动3秒/循环/暂停）
- 视频播放器（Android VideoView / iOS AVPlayer）
- 图片编辑（裁剪4比例/旋转90°+自由/滤镜5种）
- 收藏（星标/筛选/后端API持久化）
- 相册（创建/加入/列表/删除后端API）
- 分享（系统分享 expect/actual）
- 批量操作（全选/删除确认/上传进度/批量分享）
- 设置（后端地址/主题4种/关于/OpenClaw入口）
- 后端: gRPC+REST/ffmpeg视频缩略图/LRU缓存/收藏/相册/healthz/CORS

## 构建
```bash
cd backend && go build ./...
cd frontend && bash gradlew :composeApp:assembleDebug :composeApp:compileKotlinIosArm64
```

## 运行
```bash
cd backend && go run ./cmd/server/  # 后端 :8080 + gRPC :50051
# App 默认连接 http://192.168.31.251:8080（局域网IP）
```
