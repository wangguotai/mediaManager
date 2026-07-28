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

### 视频信息
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

### 收藏
```
POST /api/media/favorite
Body: { "media_id": "xxx", "favorite": true }
Response: { "status": "success", "media_id": "xxx", "favorite": true }
```

> 设置或取消单个媒体的收藏状态。`favorite=true` 加星标，`false` 取消。
> 持久化到 `data/favorites.json`，线程安全（RWMutex）。

```
GET /api/media/favorites
Response: { "favorites": ["id1", "id2", ...] }
```

> 返回所有已收藏的 mediaId 列表。

```
POST /api/media/favorite-batch
Body: { "media_ids": ["a", "b"], "favorite": true }
Response: {
  "status": "success",
  "succeeded": 2,
  "failed": 0,
  "favorite": true
}
```

> 批量设置/取消收藏。部分失败时 status 为 `partial: N succeeded, M failed`。

### 相册

```
POST /api/media/album
Body: { "name": "我的相册" }
Response: {
  "id": "uuid-string",
  "name": "我的相册",
  "media_ids": [],
  "created_at": 1706000000
}
```

> 创建新相册，返回完整 Album 对象。name 不可为空。

```
GET /api/media/albums
Response: { "albums": [Album, ...] }
```

> 返回所有相册列表，按创建时间倒序。每个 Album 含 id / name / media_ids / created_at。

```
POST /api/media/album/add
Body: { "album_id": "xxx", "media_id": "yyy" }
Response: { "status": "success", "album_id": "xxx", "media_id": "yyy" }
```

> 将媒体加入相册，已存在则幂等返回。

```
POST /api/media/album/remove
Body: { "album_id": "xxx", "media_id": "yyy" }
Response: { "status": "success", "album_id": "xxx", "media_id": "yyy" }
```

> 将媒体从相册中移除，不存在则幂等返回。

```
GET /api/media/album/{id}
Response: Album
```

> 获取相册详情（含 media_ids 列表）。相册不存在返回 404。

```
DELETE /api/media/album/{id}
Response: { "status": "success", "album_id": "xxx" }
```

> 删除相册，不存在则幂等返回。相册持久化到 `data/albums.json`，线程安全。

### 健康检查（增强）
```
GET /healthz
Response: {
  "status": "ok",
  "media_count": 42,
  "uptime": "3600s",
  "cache": "hit|miss|idle|unknown",
  "favorite_count": 5
}
```

> `media_count`：uploads 目录中的文件数。
> `cache`：媒体列表 LRU 缓存状态（hit=有命中记录 / miss=有未命中记录 / idle=尚未使用 / unknown=service 不支持）。
> `favorite_count`：当前收藏总数。
> `uptime`：后端启动至今的秒数（截断到秒）。

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
  "height": 0,
  "favorite": false
}
```

> `favorite` 字段由 gateway 在 `/api/media/list` 响应中动态附加（基于 FavoriteStore），
> proto 定义中不含此字段。非 list 端点不携带。

## Album 格式
```json
{
  "id": "uuid-string",
  "name": "相册名称",
  "media_ids": ["id1", "id2"],
  "created_at": 1706000000
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
