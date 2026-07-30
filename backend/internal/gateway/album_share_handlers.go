package gateway

// 共享相册端点实现（PRD-v7 §2.3）。独立文件以避免与 server.go 的并发编辑冲突。
//
// 路由注册在 server.go 的 registerRoutes 中：
//   - POST   /api/media/album/share   → handleAlbumShare        （邀请用户共享相册）
//   - DELETE /api/media/album/share   → handleAlbumShare        （撤销共享，按 method 分流）
//   - GET    /api/media/albums/shared → handleAlbumsShared      （列出被共享给当前用户的相册）
//
// 这些路径位于 authMiddleware 保护之下（非 /api/share/ 前缀），需 Bearer token，
// user_id 由中间件注入 context，handler 用 userIDFromContext 取回。
//
// 相册元数据本身存于各用户名下的 JSON 文件（service.AlbumStore 按 owner uid 分桶），
// 故共享关系落 SQLite（album_shares 表），跨用户可查；访问相册内容时以 owner uid
// 作为 AlbumStore 的查询键。被共享者（sharee）据此也能 GET 相册详情 / POST 添加图片
// （见 server.go 的 handleAlbumResource / handleAlbumAdd 中对共享权限的判定）。

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"media-manager/backend/internal/storage"
)

// albumShareRequest 是 POST/DELETE /api/media/album/share 的请求体。
// 调用方提供 album_id 与目标用户标识（username 或 user_id，二选一）。
//   - Username 非空时优先按 username 解析 user_id（经 s.store.GetUserByUsername）；
//   - UserID 非空时直接使用。
// 两者都为空 → 400；都非空以 Username 优先。
type albumShareRequest struct {
	AlbumID  string `json:"album_id"`
	Username string `json:"username"`
	UserID   string `json:"user_id"`
}

// resolveTargetUserID 把请求里的 username/user_id 统一解析成 user_id，并回查用户名
// 供响应展示。未命中返回 ("" , "", ErrUserNotFound)。
// 优先 username；username 为空时用 user_id 直接回查。
func (s *Server) resolveTargetUserID(r *http.Request, req albumShareRequest) (targetUID, targetUsername string, err error) {
	ctx := r.Context()
	if req.Username != "" {
		u, err := s.store.GetUserByUsername(ctx, req.Username)
		if err != nil {
			if errors.Is(err, storage.ErrNotFound) {
				return "", "", errUserNotFound
			}
			return "", "", err
		}
		return u.ID, u.Username, nil
	}
	if req.UserID != "" {
		u, err := s.store.GetUser(ctx, req.UserID)
		if err != nil {
			if errors.Is(err, storage.ErrNotFound) {
				return "", "", errUserNotFound
			}
			return "", "", err
		}
		return u.ID, u.Username, nil
	}
	return "", "", errUserNotFound
}

// errUserNotFound 表示按 username/user_id 解析目标用户未命中。handler 层据此返回 404，
// 其余 store 错误返回 500。
var errUserNotFound = errors.New("target user not found")

// handleAlbumShare 处理 POST /api/media/album/share（邀请共享）与
// DELETE /api/media/album/share（撤销共享）。按 method 分流到 share/unshare 子流程。
//
// 鉴权：authMiddleware 已校验 token 并注入 user_id（发起者 = 相册所有者）。
// store 为 nil 时返回 503（共享关系必须落库）。mediaSvc 不支持 albumStoreProvider
// 时返回 501（无法校验相册归属）。
func (s *Server) handleAlbumShare(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPost:
		s.handleAlbumShareCreate(w, r)
	case http.MethodDelete:
		s.handleAlbumShareDelete(w, r)
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
	}
}

// handleAlbumShareCreate 处理 POST /api/media/album/share：邀请用户共享相册。
//
// 流程：
//  1. 解析 body（album_id + username/user_id）。
//  2. 校验发起者是该相册的所有者（用 albumStoreProvider.GetAlbum(uid, albumID) 非空判定）。
//  3. 解析目标用户（username/user_id → user_id）。
//  4. 禁止共享给自己（owner == target → 400）。
//  5. 落 album_shares（CreateAlbumShare，幂等；已存在返回 200 但标记 already_shared）。
//
// 响应：200 + {album_id, shared_with: {user_id, username}, shared_at, already_shared}。
func (s *Server) handleAlbumShareCreate(w http.ResponseWriter, r *http.Request) {
	uid := userIDFromContext(r.Context())
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "album share requires storage backend"})
		return
	}
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported by the configured media service"})
		return
	}
	var req albumShareRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.AlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id is required"})
		return
	}
	if req.Username == "" && req.UserID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "username or user_id is required"})
		return
	}
	// 校验发起者拥有该相册：GetAlbum(uid, albumID) 非空才视为所有者。
	// 不可共享不存在的相册或他人相册（防越权）。
	if provider.GetAlbum(uid, req.AlbumID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found or not owned"})
		return
	}
	// 解析目标用户。
	targetUID, targetUsername, err := s.resolveTargetUserID(r, req)
	if err != nil {
		if errors.Is(err, errUserNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "target user not found"})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "resolve target user: " + err.Error()})
		return
	}
	// 禁止共享给自己。
	if targetUID == uid {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "cannot share album with yourself"})
		return
	}
	// 落库：CreateAlbumShare 幂等；已存在返回 ErrAlreadyShared（仍视为成功，标记 already_shared）。
	as := &storage.AlbumShare{
		AlbumID:          req.AlbumID,
		OwnerUserID:      uid,
		SharedWithUserID: targetUID,
	}
	alreadyShared := false
	if err := s.store.CreateAlbumShare(r.Context(), as); err != nil {
		if errors.Is(err, storage.ErrAlreadyShared) {
			alreadyShared = true
		} else if errors.Is(err, storage.ErrSelfShare) {
			// 兜底（上游已校验），保持健壮。
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "cannot share album with yourself"})
			return
		} else {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "create album share: " + err.Error()})
			return
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"album_id": req.AlbumID,
		"shared_with": map[string]any{
			"user_id":  targetUID,
			"username": targetUsername,
		},
		"shared_at":      as.SharedAt.UTC().Format(time.RFC3339),
		"already_shared": alreadyShared,
	})
}

// handleAlbumShareDelete 处理 DELETE /api/media/album/share：撤销共享。
//
// 请求体: {"album_id":"x", "username":"y"} 或 {"album_id":"x", "user_id":"y"}。
// 仅相册所有者可撤销。DeleteAlbumShare 按 (album_id, owner_user_id, shared_with_user_id)
// 三键删除；未命中 / 非所有者 / 目标用户不存在均返回 404（不区分，避免泄露）。
//
// 幂等：撤销不存在的共享关系返回 404（与既有 share token 撤销语义一致）。
func (s *Server) handleAlbumShareDelete(w http.ResponseWriter, r *http.Request) {
	uid := userIDFromContext(r.Context())
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "album share requires storage backend"})
		return
	}
	var req albumShareRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.AlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id is required"})
		return
	}
	if req.Username == "" && req.UserID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "username or user_id is required"})
		return
	}
	// 解析目标用户：未命中 → 404（撤销一个不存在的用户无意义）。
	targetUID, _, err := s.resolveTargetUserID(r, req)
	if err != nil {
		if errors.Is(err, errUserNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "target user not found or share not found"})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "resolve target user: " + err.Error()})
		return
	}
	// 撤销：按 (album_id, owner=uid, target) 三键删除。未命中或不属于该用户均 404。
	if err := s.store.DeleteAlbumShare(r.Context(), req.AlbumID, uid, targetUID); err != nil {
		if errors.Is(err, storage.ErrNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "share not found or not owned"})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "delete album share: " + err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":   "success",
		"album_id": req.AlbumID,
		"user_id":  targetUID,
	})
}

// handleAlbumsShared 处理 GET /api/media/albums/shared：列出被共享给当前用户的相册。
//
// 流程：
//  1. ListAlbumSharesSharedWith(uid) 取所有共享给当前用户的关联记录。
//  2. 对每条记录，用 albumStoreProvider.GetAlbum(owner_uid, album_id) 取相册元数据
//     （相册文件属于 owner）。相册已被所有者删除时跳过（共享关系悬空，不阻断列表）。
//  3. 每条响应附带 owner_user_id 与 shared_at，便于前端区分来源。
//
// 响应：200 + {"albums":[{...album fields..., owner_user_id, shared_at}]}。
func (s *Server) handleAlbumsShared(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "album share requires storage backend"})
		return
	}
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported by the configured media service"})
		return
	}
	shares, err := s.store.ListAlbumSharesSharedWith(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "list shared albums: " + err.Error()})
		return
	}
	// 组装结果：对每条共享，取 owner 名下的相册元数据。
	type sharedAlbum struct {
		ID           string   `json:"id"`
		Name         string   `json:"name"`
		MediaIDs     []string `json:"media_ids"`
		CreatedAt    int64    `json:"created_at"`
		OwnerUserID  string   `json:"owner_user_id"`
		SharedAt     string   `json:"shared_at"`
	}
	list := make([]sharedAlbum, 0, len(shares))
	for _, sh := range shares {
		album := provider.GetAlbum(sh.OwnerUserID, sh.AlbumID)
		if album == nil {
			// 相册已被所有者删除（或所有者 uid 非法导致目录解析失败）。
			// 悬空共享不影响其余相册列表；此处跳过，不单独清理（级联清理在
			// 删除相册时发生）。可选：后续可补异步清理任务。
			continue
		}
		list = append(list, sharedAlbum{
			ID:          album.ID,
			Name:        album.Name,
			MediaIDs:    album.MediaIDs,
			CreatedAt:   album.CreatedAt,
			OwnerUserID: sh.OwnerUserID,
			SharedAt:    sh.SharedAt.UTC().Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"albums": list})
}

// resolveAlbumOwnerForUser 判断 uid 是否可访问 albumID，返回应使用的相册归属 uid
// （uid 自身为所有者时返回 uid；否则为被共享者时返回 owner_uid；无权返回空串）。
//
// 供 handleAlbumResource（GET 详情）/ handleAlbumAdd（添加媒体）复用，使被共享者
// 能以 owner uid 访问相册内容。判定顺序：
//  1. GetAlbum(uid, albumID) 非空 → 所有者，返回 uid。
//  2. IsAlbumSharedWith(albumID, uid) 为真 → 被共享者，回查共享记录的 owner_uid。
//  3. 均否 → 无权，返回空串。
//
// store 为 nil 时仅判定所有者路径（共享关系不可查），返回 uid 或空串。
func (s *Server) resolveAlbumOwnerForUser(r *http.Request, provider albumStoreProvider, uid, albumID string) (ownerUID string) {
	// 所有者自身：直接命中自己的相册。
	if provider.GetAlbum(uid, albumID) != nil {
		return uid
	}
	if s.store == nil {
		return ""
	}
	// 被共享者：查共享关系，回查 owner。
	if s.store.IsAlbumSharedWith(r.Context(), albumID, uid) {
		// 取一条共享记录的 owner（理论上所有共享记录的 owner 相同 = 相册所有者）。
		shares, err := s.store.ListAlbumSharesSharedWith(r.Context(), uid)
		if err != nil {
			return ""
		}
		for _, sh := range shares {
			if sh.AlbumID == albumID {
				return sh.OwnerUserID
			}
		}
	}
	return ""
}

// ensure imports used（strings 当前仅用于未来扩展，保留以便后续路径校验复用）。
var _ = strings.Contains
