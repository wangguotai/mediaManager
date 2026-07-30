# V7+V8 Sprint 验收报告（最终版）

> 更新时间：2026-07-31 03:00 GMT+8
> HEAD: 139a69b
> 总 commit 数：74

## 完成状态总览

| 功能 | 状态 | 编译 | 真机验证 |
|------|------|------|---------|
| 回收站（trash/restore/purge + auto-purge 30天 + 过期分享链接清理） | ✅ | ✅ | ✅ |
| 分享链接（create/get/delete + auto-expiry purge） | ✅ | ✅ | ✅ |
| 搜索排序（date/size/name）+ has_more 分页 | ✅ | ✅ | ✅ |
| 时光相册 | ✅ | ✅ | ✅ |
| 备份通知 | ✅ | ✅ | ✅ |
| 照片编辑（涂鸦/马赛克/文字） | ✅ | ✅ | ✅ |
| 共享相册（3端点+15测试+前端FAB） | ✅ | ✅ | ✅ |
| 存储清理（SHA256检测+keep_id策略+一键删除） | ✅ | ✅ | ✅ |
| RN活动中心（端到端+热更新+SHA256校验） | ✅ | ✅ | ✅ |
| RN热更新（10KB阈值+版本管理+manifest size+sha256） | ✅ | ✅ | ✅ |
| 媒体重命名（backend+VM+UI对话框） | ✅ | ✅ | ✅ |
| 批量下载 zip | ✅ | ✅ | curl ✅ |
| 媒体库摘要（summary+时间线卡片） | ✅ | ✅ | ✅ |
| 存储统计（storage-stats+卡片） | ✅ | ✅ | ✅ |
| 相册排序切换 | ✅ | ✅ | ✅ |
| 文件管理快捷筛选（大文件+近30天） | ✅ | ✅ | ✅ |
| 检查RN更新（manifest版本号） | ✅ | ✅ | ✅ |
| 图片预览左右导航箭头 | ✅ | ✅ | ✅ |
| 相册封面（设置+显示） | ✅ | ✅ | 真机 ✅ |
| 相册长按移除照片 | ✅ | ✅ | 编译 ✅ |
| 相册预览设为封面按钮 | ✅ | ✅ | 编译 ✅ |
| media_ids null 修复 | ✅ | ✅ | 真机 ✅ |
| 清除缓存 + 版本号 v0.4.0 | ✅ | ✅ | ✅ |

## 本次新增（c37b3e8 → 139a69b）

1. 回收站自动清理 goroutine（30天 + 6小时扫描）
2. 过期分享链接自动清理
3. RN manifest checksum（size + SHA256）
4. RN 热更新 SHA256 完整性校验
5. healthz v0.4.0
6. 图片预览左右导航箭头（ic_arrow_back/forward）
7. drawable 颜色 crash 修复（@android:color/white → #FFFFFFFF）
8. /api/media/list has_more 字段
9. /api/media/album/cover 端点 + 前端 API
10. 相册详情长按移除照片对话框
11. 相册详情预览"设为封面"按钮
12. media_ids null 修复（前端 JsonArray crash）

## 编译状态

- 后端: go build + go test 全 PASS
- ops-server: go build + go vet 全 PASS
- 前端: assembleDebug + compileKotlinIosArm64 BUILD SUCCESSFUL
- git 工作树干净

## 真机验证

- RN活动中心 + 热更新全链路（v1.1.0 → 下载1MB → SHA256校验 → Running）
- 我的Tab：用户卡片 + 存储概览 + 媒体时间线
- 存储清理：SHA256重复检测 + 一键删除
- 设置页：v0.4.0 + 检查RN更新（最新版本 1.1.0）
- 图片预览重命名对话框
- 图片预览左右导航箭头（上一张/下一张）
- 相册列表显示 test-cover-album（media_ids null 修复后）
