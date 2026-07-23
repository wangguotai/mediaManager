# 接口契约 — media-manager

> 协调者维护，agent 读取此文件了解 API 格式

## REST API (后端 :8080)

### 媒体列表
```
GET /api/media/list?page=1&page_size=20&type=image&q=keyword
Response: { "media_list": [MediaMetadata], "total_count": N, "page": 1, "page_size": 20 }
```

### 媒体原图流
```
GET /api/media/stream/{media_id}
Response: binary stream (Content-Type: application/octet-stream)
```

### 缩略图
```
GET /api/media/thumbnail/{media_id}?size=small|medium|large
Response: binary image bytes
```

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
