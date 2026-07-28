修复 QA 报告 P1 问题:
1. loadVideoMeta/saveVideoMeta 加路径穿越校验（拒绝含 .. 或 / 的 mediaID）
2. UploadMedia 调用 invalidateListCache（上传后立即刷新缓存）
3. 移除 server.lastCacheHit 死代码
