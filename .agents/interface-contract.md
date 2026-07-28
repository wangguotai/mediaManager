# 接口契约 — media-manager

> 协调者维护，agent 读取此文件了解 API 格式

## REST API (后端 :8080)

### 媒体列表
```
GET /api/media/list?page=1&page_size=20&type=image&q=keyword
Response: { "media_list": [MediaMetadata], "total_count": N, "page": 1, "page_size": 20 }
```

> `type` 取值：`image`（默认，放行图片**与视频**）、`video`（仅视频）、`live_photo`。
> 即不传或传 `image` 时同时返回 IMAGE 与 VIDEO；显式传 `video` 仅返回 VIDEO。

### 媒体原图流
```
GET /api/media/stream/{media_id}
Response: binary stream (Content-Type: application/octet-stream)
```

> Content-Type 按扩展名显式设置：图片为 `image/*`，视频为 `video/mp4` / `video/quicktime` /
> `video/x-msvideo` / `video/x-matroska` / `video/webm`，未知类型回退 `application/octet-stream`。
> 基于 `http.ServeFile` 实现，天然支持 HTTP `Range` 请求，前端可用 `<video>` 分片拖拽播放。

### 缩略图
```
GET /api/media/thumbnail/{media_id}?size=small|medium|large
Response: binary image bytes
```

> 对图片按 longEdge 缩放（保持比例）；对视频用 ffmpeg 抽取第 1s 第一帧再缩放，输出恒为 JPEG
> （`image/jpeg`），缓存到 `data/thumbnails/{media_id}_{longEdge}.jpg`。size → longEdge：
> small=128, medium=256, large=512。

### 视频信息（新增）
```
GET /api/media/video-info/{media_id}
Response: {
  "duration_seconds": 3.0,   // 秒，浮点；ffprobe format.duration
  "width": 320,
  "height": 240,
  "codec": "h264",           // 视频流 codec_name，可选
  "container": "mov,mp4,m4a,3gp,3g2,mj2"  // ffprobe format.format_name，可选
}
```

> 错误：非视频文件返回 500 `media <id> is not a video`；service 未实现该能力返回 501；
> 媒体不存在返回 500 `media not found: <id>`。内部用 ffprobe，含 15s 超时。

### 上传
```
POST /api/media/upload?filename=xxx
Body: raw binary
Response: { "media_id": "xxx", "status": "success", "size": N }
```

### 删除
```
POST /api/media/delete
Body: { "media_ids": ["id1", "id2"] }
Response: { "status": "success", "deleted_count": N }
```

### 元数据
```
GET /api/media/metadata/{media_id}
Response: { "metadata": MediaMetadata }
```

### OpenClaw 桥梁
```
POST /api/openclaw/command
Body: { "path": "/xxx", "method": "POST", "body": {...} }
Response: { "status": 200, "content_type": "...", "body": {...} }
```

## MediaMetadata 格式
```json
{
  "id": "string",
  "filename": "string",
  "type": "IMAGE|LIVE_PHOTO|VIDEO",
  "size": 0,
  "mime_type": "string",
  "created_at": 0,
  "updated_at": 0,
  "is_live_photo": false,
  "live_photo_video_id": "",
  "width": 0,
  "height": 0
}
```

## 网盘图片源约定
- SearchQuery 以 `source=cloud` 开头时，后端从 LocalCloudSource 返回图片
- LocalCloudSource 扫描 `./data/cloud-images/` 目录
- LocalCloudSource 现同时收录图片与视频扩展名（`.mp4`/`.mov`/`.avi`/`.mkv`），
  视频条目 `type=VIDEO`、`mime_type` 按扩展名设置为对应 `video/*`。

## 系统依赖
- `ffmpeg` / `ffprobe`：视频缩略图抽帧与视频信息解析依赖 FFmpeg（已在宿主路径中提供）。
- 安装缺失时，`GET /api/media/thumbnail` 对视频、`GET /api/media/video-info/*` 将返回 500。
