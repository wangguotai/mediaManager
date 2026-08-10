package gateway

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"

	"media-manager/backend/internal/storage"
)

// ============ 回收站端点（PRD-v7 §1.1） ============
//
// 三个端点均在 authMiddleware 之后（除非 store 未注入，向下兼容 503），user_id 由
// 中间件经 service.WithUserID 注入 context，handler 用 userIDFromContext 取回。
// 所有数据访问均按 (id, user_id) 双键校验归属，防横向越权：非己有或不存在一律
// 视为未命中，不向调用方区分（避免泄露 media_id 是否存在）。

// trashListResponse 是 GET /api/media/trash 的响应体。Items 复用 syncChangeItem
// （与 /api/sync/changes 同结构），前端可复用同一渲染模型。分页信息 page/page_size/
// total 供前端展示"共 N 条"与翻页。
type trashListResponse struct {
	Items    []syncChangeItem `json:"items"`
	Page     int              `json:"page"`
	PageSize int              `json:"page_size"`
	Total    int              `json:"total"` // 当前用户回收站总条数（不受分页影响）
}

// batchIDsRequest 是 restore / purge 的请求体：一批 media_id。
// 空数组返回 400，避免无意义空操作；单次上限由 maxBatchIDs 限制防滥用。
type batchIDsRequest struct {
	MediaIDs []string `json:"media_ids"`
}

// maxBatchIDs 限制单次 restore/purge 的 media 数量，防一次性传入超大数组打满 DB。
const maxBatchIDs = 500

// batchOpResult 是 restore / purge 的响应体：逐条结果汇总。
// Failed 中每项含 id 与原因（如 not_found / 该用户不存在 / 未软删），供前端提示。
type batchOpResult struct {
	Succeeded []string          `json:"succeeded"`          // 成功操作的 media_id
	Failed    []batchOpFailure  `json:"failed,omitempty"`   // 失败项及原因
}

// batchOpFailure 描述单条失败：id + 原因（小写短语，对前端友好且不泄露内部细节）。
type batchOpFailure struct {
	ID     string `json:"id"`
	Reason string `json:"reason"`
}

// handleTrashList 处理 GET /api/media/trash，返回当前用户已软删的媒体列表，分页。
// 分页参数复用 parseSyncPaging（page/page_size，与 /api/sync/changes 一致）。
// 排序按 updated_at DESC（最近删除的在前），与回收站"最近操作优先"语义一致。
func (s *Server) handleTrashList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "trash is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	page, pageSize := parseSyncPaging(r)
	limit := pageSize
	offset := (page - 1) * pageSize

	items, err := s.store.ListTrashByUser(r.Context(), uid, limit, offset)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 总数：用 CountTrashByUser（与 ListTrashByUser 同条件 user_id + deleted=1），
	// 供前端展示"共 N 条"与翻页判定 has_more。
	total, err := s.store.CountTrashByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	out := make([]syncChangeItem, 0, len(items))
	for _, m := range items {
		out = append(out, mediaToChangeItem(m))
	}
	writeJSON(w, http.StatusOK, trashListResponse{
		Items:    out,
		Page:     page,
		PageSize: pageSize,
		Total:    total,
	})
}

// handleMediaRestore 处理 POST /api/media/restore，批量复活已软删的媒体。
// 请求体 {"media_ids": ["id1","id2"]}；逐条调 UndeleteMediaForUser（按 user_id
// 校验），返回成功/失败计数与失败明细。已恢复（deleted=0）或不属于当前用户的
// 记录返回 ErrNotFound，记入 failed（reason: not_found），不区分二者防越权探测。
func (s *Server) handleMediaRestore(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "trash is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	req, ok := decodeBatchIDs(w, r)
	if !ok {
		return // decodeBatchIDs 已写错误响应
	}

	result := batchOpResult{Succeeded: []string{}}
	for _, id := range req.MediaIDs {
		if err := s.store.UndeleteMediaForUser(r.Context(), uid, id); err != nil {
			reason := "not_found"
			if !errors.Is(err, storage.ErrNotFound) {
				reason = "error"
			}
			result.Failed = append(result.Failed, batchOpFailure{ID: id, Reason: reason})
			continue
		}
		result.Succeeded = append(result.Succeeded, id)
	}
	// PRD-v10 §4.1：恢复成功后向该用户在线设备推送 media_changed(restore)，
	// 其他端收到后调 /api/sync/changes 续拉复活记录。best-effort，不阻断响应。
	if len(result.Succeeded) > 0 {
		s.notifyMediaChange(uid, syncEventRestore)
	}
	writeJSON(w, http.StatusOK, result)
}

// handleMediaPurge 处理 POST /api/media/purge，批量物理删除已软删的媒体（DELETE row +
// 删磁盘文件）。仅对 deleted=1 的记录操作（只能从回收站彻底清空）。
//
// 实现：逐条调 PurgeMediaForUser（先 GetMedia 确认归属 + 软删状态，再 DELETE row），
// 成功后清理该 media 在用户 uploads 目录下的磁盘文件（filepath.Glob 查找 id.* 后
// os.Remove）。文件清理为 best-effort：DB 行已删，文件删失败不回滚（记录日志），
// 避免磁盘故障阻断回收站清空语义。未命中/不属于当前用户记入 failed（not_found）。
func (s *Server) handleMediaPurge(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "trash is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	req, ok := decodeBatchIDs(w, r)
	if !ok {
		return
	}

	// 定位该用户的 uploads 目录：purge 需删文件。userDirs 未配置时仅删 DB 行
	//（文件遗留由运维/清理任务处理，不阻断回收站清空）。
	uploadsDir := s.userUploadsDir(uid)

	result := batchOpResult{Succeeded: []string{}}
	for _, id := range req.MediaIDs {
		if err := s.store.PurgeMediaForUser(r.Context(), uid, id); err != nil {
			reason := "not_found"
			if !errors.Is(err, storage.ErrNotFound) {
				reason = "error"
			}
			result.Failed = append(result.Failed, batchOpFailure{ID: id, Reason: reason})
			continue
		}
		// DB 行已删；best-effort 清理磁盘文件。不回滚 DB：文件删失败不阻断回收站语义。
		if uploadsDir != "" {
			s.purgeMediaFiles(uploadsDir, id)
		}
		result.Succeeded = append(result.Succeeded, id)
	}
	writeJSON(w, http.StatusOK, result)
}

// purgeMediaFiles 删除 uploadsDir 下所有以 id 为前缀的文件（id.*）。
// best-effort：删除失败仅记录日志，不返回错误（DB 行已删，文件遗留可由清理任务兜底）。
// 与 handleMediaStream 的文件定位逻辑一致：filepath.Glob(id+".*") 匹配任意扩展名。
func (s *Server) purgeMediaFiles(uploadsDir, id string) {
	pattern := filepath.Join(uploadsDir, id+".*")
	files, err := filepath.Glob(pattern)
	if err != nil {
		slog.Warn("trash: glob media files failed", "pattern", pattern, "err", err)
		return
	}
	for _, f := range files {
		if err := os.Remove(f); err != nil {
			// 文件已被删/不存在视作成功（幂等）；其他错误记录但继续。
			if !os.IsNotExist(err) {
				slog.Warn("trash: remove media file failed", "file", f, "err", err)
			}
		}
	}
}

// decodeBatchIDs 解析 restore/purge 的请求体，校验非空且不超 maxBatchIDs。
// 失败时已写入 HTTP 错误响应，调用方据 ok=false 直接 return。
func decodeBatchIDs(w http.ResponseWriter, r *http.Request) (*batchIDsRequest, bool) {
	var req batchIDsRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return nil, false
	}
	if len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids must not be empty"})
		return nil, false
	}
	if len(req.MediaIDs) > maxBatchIDs {
		writeJSON(w, http.StatusBadRequest, map[string]any{
			"error": "too many media_ids in one request",
		})
		return nil, false
	}
	return &req, true
}

// handleMediaEmptyTrash V8：POST /api/media/empty-trash — 清空当前用户回收站。
// 物理删除所有 deleted=1 的媒体记录（磁盘文件遗留由清理任务处理）。
func (s *Server) handleMediaEmptyTrash(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "trash is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	count, err := s.store.PurgeAllTrashForUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":       "success",
		"purged_count": count,
	})
}

// handleMediaBatchRestore V8：POST /api/media/batch-restore — 批量恢复回收站媒体。
//
// 与 POST /api/media/restore 的区别：restore 逐条调 UndeleteMediaForUser（按 (id,user_id)
// 双键校验，返回逐条成功/失败明细）；本端点用单条 UPDATE ... WHERE id IN (...) AND user_id=?
// 一次性恢复，仅返回总恢复计数（不区分逐条结果），适合前端"全选恢复"等不关心明细的场景。
//
// 请求体复用 batchIDsRequest（{"media_ids": [...]}），校验复用 decodeBatchIDs（非空、≤maxBatchIDs）。
// 审计日志记一条 "restore"（mediaID 留空，detail 注明批量恢复数量），与 share/empty-trash 等批量操作一致。
// 响应：{"status":"success","restored_count":N}，N 为实际复活行数（未命中/已恢复/非己有均不计入）。
func (s *Server) handleMediaBatchRestore(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "trash is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	req, ok := decodeBatchIDs(w, r)
	if !ok {
		return // decodeBatchIDs 已写错误响应
	}

	count, err := s.store.BatchRestoreMedia(r.Context(), uid, req.MediaIDs)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 审计日志：best-effort，失败不影响恢复结果（与 handleShareCreate 等一致）。
	_ = s.store.AddAuditLog(r.Context(), uid, "restore", "", fmt.Sprintf("batch restore %d items", count))

	writeJSON(w, http.StatusOK, map[string]any{
		"status":         "success",
		"restored_count": count,
	})
}
