# V7+V8 Sprint 验收报告

> 更新时间：2026-07-31 03:30 GMT+8
> HEAD: da9e579
> 总 commit 数：85

## 本轮新增功能（c37b3e8 → da9e579）

### 后端
1. 回收站 auto-purge goroutine（30天 + 6小时扫描）
2. 过期分享链接 auto-purge
3. /api/media/duplicates（SHA256 + keep_id/delete_ids）
4. /api/media/summary（综合摘要）
5. /api/media/rename（重命名）
6. /api/media/batch-download（zip 流式传输）
7. /api/media/album/cover（设置相册封面）
8. /api/media/list has_more 字段
9. RN manifest size + sha256
10. healthz v0.4.0
11. media_ids null 修复
12. PurgeExpiredShareTokens + PurgeExpiredTrash

### 前端
1. 图片预览：重命名对话框
2. 图片预览：左右导航箭头（ic_arrow_back/forward）
3. 图片预览：设为封面按钮
4. 我的Tab：媒体时间线卡片
5. 存储清理：SHA256 重复检测 + 一键删除
6. 设置页：检查 RN 更新
7. 设置页：后端版本号显示（从 /healthz 获取）
8. 相册：排序切换
9. 相册：长按移除照片
10. 相册：空状态"添加照片"引导
11. 相册：添加照片 fallback 修复
12. 回收站：恢复全部 + 清空按钮
13. RN 热更新：SHA256 完整性校验
14. drawable 颜色 crash 修复（@android:color/white → #FFFFFFFF）

## 编译状态

- 后端: go build + go test 全 PASS
- 前端: assembleDebug + compileKotlinIosArm64 BUILD SUCCESSFUL
- git 工作树干净

## 真机验证通过项

1. RN活动中心 + 热更新（v1.1.0 → SHA256校验 → Running）
2. 我的Tab：用户卡片 + 存储概览 + 媒体时间线
3. 存储清理：SHA256重复检测 + 一键删除
4. 设置页：v0.4.0 + 检查RN更新 + 后端版本 v0.4.0
5. 图片预览：重命名 + 左右导航箭头
6. 相册列表显示（media_ids null 修复）
7. 相册添加照片对话框（fallback 显示4张可选）
