后端 REST 上传补写元数据 + 收藏 API 完善：
1. handleMediaUpload 上传后写元数据到 data/metadata/{id}.json
2. GetMediaList 读取 metadata 文件补充 created_at（用原始拍摄时间）
3. /healthz 返回 cache_hit_rate
4. 收藏 API 加批量操作: POST /api/media/favorite-batch
