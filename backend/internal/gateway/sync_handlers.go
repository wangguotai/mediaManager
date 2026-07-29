package gateway

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"media-manager/backend/internal/storage"
)

// ============ 增量同步端点 ============
//
// GET /api/sync/changes?since=ms&page=1&page_size=100
//   返回当前用户 updated_at 严格晚于 since 的全部 media 行（含软删墓碑），
//   按 updated_at 升序，分页。客户端用本页最后一条的 updated_at 毫秒作为下次
//   since，循环直至 has_more=false 即完成一次全量增量同步。
//
// GET /api/sync/usage
//   返回当前用户未软删媒体的存储总量与文件数。

// defaultSyncPageSize 是 /api/sync/changes 未指定 page_size 时的默认值。
const defaultSyncPageSize = 100

// maxSyncPageSize 是 /api/sync/changes 单页上限，防止单次拉取过载。
const maxSyncPageSize = 500

// syncChangeItem 是 changes 响应中的单条变更。它基于接口契约的 MediaMetadata
// 字段，并额外携带同步语义所需字段：
//   - deleted：墓碑标记，true 表示该媒体已被软删，客户端应本地删除。
//   - sha256 / client_id / taken_at：去重与排序辅助字段。
//
// type 取 "IMAGE"|"VIDEO"|"LIVE_PHOTO"，与 storage.Media.Type 一致。
type syncChangeItem struct {
	ID        string `json:"id"`
	Filename  string `json:"filename"`
	Type      string `json:"type"`
	Size      int64  `json:"size"`
	MimeType  string `json:"mime_type"`
	CreatedAt int64  `json:"created_at"`
	UpdatedAt int64  `json:"updated_at"`
	Width     int32  `json:"width"`
	Height    int32  `json:"height"`
	Deleted   bool   `json:"deleted"`
	SHA256    string `json:"sha256"`
	ClientID  string `json:"client_id,omitempty"`
	TakenAt   int64  `json:"taken_at,omitempty"`
}

// changesResponse 是 /api/sync/changes 的响应体。
type changesResponse struct {
	Changes    []syncChangeItem `json:"changes"`
	NextCursor int64            `json:"next_cursor"` // 下一页 since（毫秒）；0 表示无更多
	HasMore    bool             `json:"has_more"`
}

// usageResponse 是 /api/sync/usage 的响应体。
type usageResponse struct {
	TotalBytes int64 `json:"total_bytes"`
	FileCount  int   `json:"file_count"`
}

// handleSyncChanges 处理 GET /api/sync/changes。
//
// since 以毫秒时间戳传入（客户端易于生成）；在服务端转换为 RFC3339Nano 字符串
// 与 media.updated_at 列比较。空 since 表示从头拉取（首次同步）。page/page_size
// 从 1 开始；next_cursor 为本页最后一条的 updated_at 毫秒，has_more 表示是否还有
// 未取的增量。
func (s *Server) handleSyncChanges(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "sync is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	page, pageSize := parseSyncPaging(r)
	sinceCursor, sinceMS, ok := parseSinceCursor(r.URL.Query().Get("since"))
	if !ok {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid since cursor"})
		return
	}

	limit := pageSize
	offset := (page - 1) * pageSize
	changes, err := s.store.ListMediaChanges(r.Context(), uid, sinceCursor, limit, offset)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 计算剩余总量以判定 has_more：用 CountMediaChanges 取 since 之后全部行数，
	// 与已取 offset+len(changes) 比较。这比"恰好取 pageSize+1 条"更稳健——
	// 即便同 cursor 有恰好 pageSize 条也能正确报告 has_more。
	total, err := s.store.CountMediaChanges(r.Context(), uid, sinceCursor)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	items := make([]syncChangeItem, 0, len(changes))
	var lastMS int64
	for _, m := range changes {
		items = append(items, mediaToChangeItem(m))
		if ms := m.UpdatedAt.UnixNano() / int64(time.Millisecond); ms > lastMS {
			lastMS = ms
		}
	}

	hasMore := (offset + len(changes)) < total
	// next_cursor 始终指向"已同步到的位置"：本页非空时取末条 updated_at 毫秒，
	// 客户端下次以此续拉（has_more=false 时续拉得空页，表示同步完成）。
	// 本页为空且无更多时回显原 since，游标不回退。
	nextCursor := int64(0)
	if len(items) > 0 && lastMS > 0 {
		nextCursor = lastMS
	} else if sinceMS > 0 {
		nextCursor = sinceMS
	}

	writeJSON(w, http.StatusOK, changesResponse{
		Changes:    items,
		NextCursor: nextCursor,
		HasMore:    hasMore,
	})
}

// handleSyncUsage 处理 GET /api/sync/usage，返回当前用户活跃媒体的总量与计数。
func (s *Server) handleSyncUsage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "sync is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}
	totalBytes, fileCount, err := s.store.UserUsage(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, usageResponse{
		TotalBytes: totalBytes,
		FileCount:  fileCount,
	})
}

// mediaToChangeItem 把 storage.Media 行转成同步变更项。
// 时间戳统一转毫秒，与契约 cursor 单位一致。
func mediaToChangeItem(m *storage.Media) syncChangeItem {
	return syncChangeItem{
		ID:        m.ID,
		Filename:  m.Filename,
		Type:      m.Type,
		Size:      m.Size,
		MimeType:  m.Mime,
		CreatedAt: m.CreatedAt.UnixNano() / int64(time.Millisecond),
		UpdatedAt: m.UpdatedAt.UnixNano() / int64(time.Millisecond),
		Width:     m.Width,
		Height:    m.Height,
		Deleted:   m.Deleted,
		SHA256:    m.SHA256,
		ClientID:  m.ClientID,
		TakenAt:   m.TakenAt,
	}
}

// parseSyncPaging 解析 page/page_size 查询参数，缺省 page=1、page_size=100，
// 越界值夹到 [1, maxSyncPageSize]。page<1 视为 1。
func parseSyncPaging(r *http.Request) (page, pageSize int) {
	page = 1
	pageSize = defaultSyncPageSize
	if v := r.URL.Query().Get("page"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			page = n
		}
	}
	if v := r.URL.Query().Get("page_size"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			pageSize = n
		}
	}
	if pageSize > maxSyncPageSize {
		pageSize = maxSyncPageSize
	}
	return page, pageSize
}

// parseSinceCursor 把 since 毫秒时间戳解析为 (RFC3339Nano 字符串, 毫秒值, ok)。
// 空串返回 ("",0,true) 表示从头拉取；非数字返回 ok=false。
// 用毫秒构造 UTC 时间再格式化为纳秒精度 RFC3339，确保与 updated_at 列可比。
func parseSinceCursor(s string) (cursor string, ms int64, ok bool) {
	if strings.TrimSpace(s) == "" {
		return "", 0, true
	}
	n, err := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
	if err != nil || n < 0 {
		return "", 0, false
	}
	t := time.UnixMilli(n).UTC()
	return t.Format(time.RFC3339Nano), n, true
}
