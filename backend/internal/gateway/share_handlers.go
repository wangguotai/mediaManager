package gateway

// 分享链接端点实现（PRD-v7 §1.2）。独立文件以避免与 server.go 的并发编辑冲突。
//
// 路由注册与 authMiddleware 豁免在 server.go 中：
//   - s.mux.HandleFunc("/api/share/create", s.handleShareCreate)  // 需认证
//   - s.mux.HandleFunc("/api/share/", s.handleShareAccess)        // 公开分流
//   - authMiddleware 豁免 /api/share/ 前缀（GET 公开；POST create / DELETE 在 handler 内手动鉴权）

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"path/filepath"
	"strings"
	"time"

	"github.com/google/uuid"
	"golang.org/x/crypto/bcrypt"

	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"
)

// shareTokenLen 是分享短链的字符长度。uuid v4 去掉连字符后为 32 位 hex，取前 12
// 即可获得足够熵的短链（48bit 随机性，碰撞概率可忽略，且入库主键唯一约束兜底）。
const shareTokenLen = 12

// shareMaxMediaIDs 限制单次分享的媒体数量，防止滥用超大分享拖慢公开访问端点
// （逐个 GetMedia 线性扫描）。128 足够覆盖相册级分享场景。
const shareMaxMediaIDs = 128

// requireShareAuth 在 authMiddleware 豁免的 /api/share/ 区内，为需认证的操作
// （POST create、DELETE 撤销）手动解析 Authorization Bearer 并校验 JWT。
// 成功返回 userID；失败已写入 401 响应并返回空串，调用方应直接 return。
// authSvc 为 nil（未配置认证）时放行并返回空串——此场景仅用于无认证的开发/测试，
// 生产部署必配置 authSvc，空 uid 后续因 userDirs/GetMedia 校验自然失败。
func (s *Server) requireShareAuth(w http.ResponseWriter, r *http.Request) string {
	if s.authSvc == nil {
		return ""
	}
	authHeader := r.Header.Get("Authorization")
	if authHeader == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "missing authorization header"})
		return ""
	}
	const bearerPrefix = "Bearer "
	if !strings.HasPrefix(authHeader, bearerPrefix) {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid authorization scheme"})
		return ""
	}
	tokenStr := strings.TrimSpace(strings.TrimPrefix(authHeader, bearerPrefix))
	if tokenStr == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "missing token"})
		return ""
	}
	userID, err := s.authSvc.ParseToken(tokenStr)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid or expired token"})
		return ""
	}
	return userID
}

// generateShareToken 生成 12 字符随机短链：uuid 去连字符后取前 12 位 hex。
// 碰撞概率极低且 share_tokens.token 是主键；如需更强随机性可换 crypto/rand，
// 此处复用既有 uuid 依赖以保持依赖最小化。
func generateShareToken() string {
	return strings.ReplaceAll(uuid.New().String(), "-", "")[:shareTokenLen]
}

// handleShareCreate 处理 POST /api/share/create，创建分享链接（需认证）。
//
// 请求体: {"media_ids":["id1","id2"], "expires_days":7, "password":"可选"}
//   - media_ids：必填，非空且每项格式安全（无 ../ 与 /），上限 shareMaxMediaIDs。
//   - expires_days：0 = 永不过期；>0 = now+N 天 RFC3339；负数视为 0。
//   - password：非空时 bcrypt 哈希落库，公开访问需 ?password=xxx 校验。
//
// 校验 media_ids 中的 media 都属于当前 user_id（用 store.GetMedia 查 user_id 匹配），
// 任一不属该用户即拒绝（防越权分享他人 media）。
func (s *Server) handleShareCreate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	// authMiddleware 已豁免 /api/share/，故在此手动鉴权。
	uid := s.requireShareAuth(w, r)
	if uid == "" && s.authSvc != nil {
		return // 401 已写入
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "share requires storage backend"})
		return
	}
	var req struct {
		MediaIDs    []string `json:"media_ids"`
		ExpiresDays int      `json:"expires_days"`
		Password    string   `json:"password"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids must not be empty"})
		return
	}
	if len(req.MediaIDs) > shareMaxMediaIDs {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": fmt.Sprintf("too many media_ids (max %d)", shareMaxMediaIDs)})
		return
	}
	// 校验每个 media_id 格式安全 + 归属当前用户。GetMedia 含已软删行，故额外
	// 检查 deleted=false，避免分享已被删除的媒体（分享时即应拒绝软删项）。
	for _, mid := range req.MediaIDs {
		if mid == "" || strings.Contains(mid, "..") || strings.Contains(mid, "/") {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id in list"})
			return
		}
		m, err := s.store.GetMedia(r.Context(), mid)
		if err != nil {
			// 未命中或不属于该用户均拒绝，不区分以避免泄露存在性。
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "media not accessible"})
			return
		}
		if m.UserID != uid || m.Deleted {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "media not accessible"})
			return
		}
	}

	// media_ids 序列化为 JSON 数组字符串落库。
	mediaIDsJSON, err := json.Marshal(req.MediaIDs)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "marshal media_ids: " + err.Error()})
		return
	}

	// expires_at：0 或负数 → 永不过期（零值）；正数 → now+N 天。
	var expiresAt time.Time
	if req.ExpiresDays > 0 {
		expiresAt = time.Now().Add(time.Duration(req.ExpiresDays) * 24 * time.Hour)
	}

	// password：非空时 bcrypt 哈希。
	passwordHash := ""
	if req.Password != "" {
		hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "hash password: " + err.Error()})
			return
		}
		passwordHash = string(hash)
	}

	st := &storage.ShareToken{
		Token:        generateShareToken(),
		UserID:       uid,
		MediaIDs:     string(mediaIDsJSON),
		ExpiresAt:    expiresAt,
		PasswordHash: passwordHash,
	}
	if err := s.store.CreateShareToken(r.Context(), st); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "create share token: " + err.Error()})
		return
	}

	// 响应 expires_at：永不过期返回空串，否则 RFC3339。
	expiresStr := ""
	if !expiresAt.IsZero() {
		expiresStr = expiresAt.UTC().Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"token":      st.Token,
		"url":        "/share/" + st.Token,
		"expires_at": expiresStr,
	})
}

// handleShareAccess 是 /api/share/ 前缀的统一入口，按 method + path 分流：
//   - GET /api/share/{token}              → handleShareView（公开查看元数据）
//   - GET /api/share/{token}/stream/{id}  → handleShareStream（公开下载字节流）
//   - DELETE /api/share/{token}           → handleShareDelete（需认证撤销）
//
// /api/share/create 由独立 handleShareCreate 处理，不进入此分流。
func (s *Server) handleShareAccess(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/api/share/" || r.URL.Path == "/api/share" {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "token required"})
		return
	}
	// 后缀 = path 去掉 /api/share/ 前缀。例：abc123 / abc123/stream/mid。
	rest := strings.TrimPrefix(r.URL.Path, "/api/share/")
	if rest == "" || strings.Contains(rest, "..") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid token"})
		return
	}
	// 按 / 切分后缀判断形态：
	//   ["abc123"]                   → view（GET）/ delete（DELETE）
	//   ["abc123","stream","mid"]    → stream（GET）
	parts := strings.Split(rest, "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid token"})
		return
	}
	token := parts[0]

	switch {
	case len(parts) == 3 && parts[1] == "stream" && r.Method == http.MethodGet:
		s.handleShareStream(w, r, token, parts[2])
	case len(parts) == 1 && r.Method == http.MethodGet:
		s.handleShareView(w, r, token)
	case len(parts) == 1 && r.Method == http.MethodDelete:
		s.handleShareDelete(w, r, token)
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed for this share path"})
	}
}

// handleShareView 处理 GET /api/share/{token}（公开访问，无需认证）。
// 返回分享的 media 元数据列表（逐个 GetMedia，过滤 deleted=false）。
// 若 token 设有 password_hash，需 ?password=xxx 校验 bcrypt，错误返回 403。
func (s *Server) handleShareView(w http.ResponseWriter, r *http.Request, token string) {
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "share requires storage backend"})
		return
	}
	st, err := s.store.GetShareToken(r.Context(), token)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "share not found"})
		return
	}
	// 过期校验：expires_at 非零且已过当前时间 → 404（不区分不存在与已过期，避免泄露）。
	if !st.ExpiresAt.IsZero() && time.Now().After(st.ExpiresAt) {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "share not found"})
		return
	}
	// 密码校验：有 password_hash 时要求 ?password=xxx，bcrypt 比对失败 → 403。
	if st.PasswordHash != "" {
		pw := r.URL.Query().Get("password")
		if pw == "" {
			// 不带密码时仍告知需要密码（has_password=true），前端据此弹密码框；
			// 但不返回 media_list，需带正确密码才可见内容。
			writeJSON(w, http.StatusForbidden, map[string]any{
				"error":        "password required",
				"has_password": true,
			})
			return
		}
		if err := bcrypt.CompareHashAndPassword([]byte(st.PasswordHash), []byte(pw)); err != nil {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "invalid password"})
			return
		}
	}

	// 还原 media_ids 并逐个取元数据，过滤 deleted=false（软删的不展示）。
	var mediaIDs []string
	if err := json.Unmarshal([]byte(st.MediaIDs), &mediaIDs); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "invalid share media_ids"})
		return
	}
	type mediaItem struct {
		ID        string `json:"id"`
		Filename  string `json:"filename"`
		Type      string `json:"type"`
		Size      int64  `json:"size"`
		Mime      string `json:"mime"`
		Width     int32  `json:"width"`
		Height    int32  `json:"height"`
		CreatedAt string `json:"created_at"`
	}
	list := make([]mediaItem, 0, len(mediaIDs))
	for _, mid := range mediaIDs {
		m, err := s.store.GetMedia(r.Context(), mid)
		if err != nil || m.Deleted {
			continue // 单个缺失/已删不阻断整体，跳过
		}
		list = append(list, mediaItem{
			ID:        m.ID,
			Filename:  m.Filename,
			Type:      m.Type,
			Size:      m.Size,
			Mime:      m.Mime,
			Width:     m.Width,
			Height:    m.Height,
			CreatedAt: m.CreatedAt.UTC().Format(time.RFC3339),
		})
	}

	expiresStr := ""
	if !st.ExpiresAt.IsZero() {
		expiresStr = st.ExpiresAt.UTC().Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"token":        st.Token,
		"media_list":   list,
		"expires_at":   expiresStr,
		"has_password": st.PasswordHash != "",
	})
}

// handleShareStream 处理 GET /api/share/{token}/stream/{mediaId}（公开下载字节流）。
// 验证 token 有效 + mediaId 在 token.media_ids 中 → 用 userDirs 按 token.UserID
// 定位文件并 ServeFile。复用 handleMediaStream 的文件定位与 Content-Type 逻辑，
// 但 uid 来自 token 创建者（share token 替代认证授权该 media 访问）。
func (s *Server) handleShareStream(w http.ResponseWriter, r *http.Request, token, mediaID string) {
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "share requires storage backend"})
		return
	}
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}
	st, err := s.store.GetShareToken(r.Context(), token)
	if err != nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "share not found"})
		return
	}
	if !st.ExpiresAt.IsZero() && time.Now().After(st.ExpiresAt) {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "share not found"})
		return
	}
	// 密码校验：stream 端点同样要求 ?password=xxx（与 view 一致，否则可绕过密码直接拿文件）。
	if st.PasswordHash != "" {
		pw := r.URL.Query().Get("password")
		if pw == "" {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "password required", "has_password": true})
			return
		}
		if err := bcrypt.CompareHashAndPassword([]byte(st.PasswordHash), []byte(pw)); err != nil {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "invalid password"})
			return
		}
	}
	// 校验 mediaId 在 token.media_ids 白名单内（防越权访问创建者的其它 media）。
	var mediaIDs []string
	if err := json.Unmarshal([]byte(st.MediaIDs), &mediaIDs); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "invalid share media_ids"})
		return
	}
	allowed := false
	for _, id := range mediaIDs {
		if id == mediaID {
			allowed = true
			break
		}
	}
	if !allowed {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not in share"})
		return
	}

	// 用 token.UserID 定位创建者的 uploads 目录（分享的 media 属于创建者）。
	uploadsDir := s.userUploadsDir(st.UserID)
	if uploadsDir == "" {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not found"})
		return
	}
	files, err := filepath.Glob(filepath.Join(uploadsDir, mediaID+".*"))
	if err != nil || len(files) == 0 {
		// 回退到网盘图片源（全局共享公共源，与 handleMediaStream 一致）。
		if s.cloudDir != "" {
			files, err = filepath.Glob(filepath.Join(s.cloudDir, mediaID+".*"))
		}
		if err != nil || len(files) == 0 {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not found"})
			return
		}
	}
	// 复用 handleMediaStream 的 Content-Type 设置逻辑，确保视频可内联播放。
	ct := videoMimeType(files[0])
	if ct == "" {
		ct = "application/octet-stream"
	}
	w.Header().Set("Content-Type", ct)
	http.ServeFile(w, r, files[0])
}

// handleShareDelete 处理 DELETE /api/share/{token}（需认证，仅创建者可撤销）。
// authMiddleware 已豁免 /api/share/，故在此手动鉴权；DeleteShareToken 按
// (token, user_id) 双键过滤，归属不符返回 ErrNotFound（404，不区分不存在与无权）。
func (s *Server) handleShareDelete(w http.ResponseWriter, r *http.Request, token string) {
	uid := s.requireShareAuth(w, r)
	if uid == "" && s.authSvc != nil {
		return // 401 已写入
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "share requires storage backend"})
		return
	}
	if err := s.store.DeleteShareToken(r.Context(), token, uid); err != nil {
		// ErrNotFound（不存在或不属于该用户）→ 404；其余 → 500。
		if errors.Is(err, storage.ErrNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "share not found or not owned"})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "delete share token: " + err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "token": token})
}

// 确保 service 包被引用（userUploadsDir / videoMimeType 等间接依赖，且 share_handlers.go
// 与 server.go 同包共享这些方法；此 import 在显式使用 service 类型时才必要，目前
// handler 仅通过 s.userDirs (*service.UserDirs) 间接使用，保留以备扩展）。
var _ = service.UserIDFromContext
