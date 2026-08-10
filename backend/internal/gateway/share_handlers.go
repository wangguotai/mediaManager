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
	_ = s.store.AddAuditLog(r.Context(), uid, "share", "", "created share link")

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

// handleShareList V7：GET /api/share/list — 返回当前用户创建的所有分享链接。
func (s *Server) handleShareList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	// /api/share/ 前缀整体豁免 authMiddleware，此处手动鉴权
	uid := s.requireShareAuth(w, r)
	if uid == "" {
		return // requireShareAuth 已写入 401
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	tokens, err := s.store.ListShareTokensByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	items := make([]map[string]any, 0, len(tokens))
	for _, st := range tokens {
		var expiresStr string
		if st.ExpiresAt.IsZero() {
			expiresStr = "永久"
		} else {
			expiresStr = st.ExpiresAt.Format(time.RFC3339)
		}
		hasPassword := st.PasswordHash != ""
		items = append(items, map[string]any{
			"token":        st.Token,
			"url":          "/share/" + st.Token,
			"media_ids":    st.MediaIDs,
			"expires_at":   expiresStr,
			"has_password": hasPassword,
			"created_at":   st.CreatedAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"shares": items,
		"total":  len(items),
	})
}

// handleShareExtend 处理 POST /api/share/extend?token=xxx，延长分享链接有效期（需认证）。
//
// 路由形式：用 query param ?token=xxx（而非路径参数 /api/share/{token}/extend），
// 因为 /api/share/ 前缀会进入 handleShareAccess 分流；用 query 可在 ServeMux 用
// /api/share/extend 精确匹配独立处理，避免与 {token} 段冲突。
//
// 请求体: {"extend_hours": 24}（可选，默认 24 小时；0 或负数视为默认 24）。
// 鉴权：/api/share/ 前缀整体豁免 authMiddleware，故用 requireShareAuth 手动校验
// JWT 获取 userID；仅创建者（owner）可延长自己的分享（repository 层按 token+user_id
// 双键校验，非 owner 返回 ErrNotFound → 404，不区分不存在与无权以避免泄露）。
//
// 返回: {"status":"success","token":...,"new_expires_at":"RFC3339"}。
func (s *Server) handleShareExtend(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	// /api/share/ 前缀整体豁免 authMiddleware，此处手动鉴权。
	uid := s.requireShareAuth(w, r)
	if uid == "" && s.authSvc != nil {
		return // 401 已写入
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "share requires storage backend"})
		return
	}
	// token 必须从 query param 获取（路由设计为 ?token=xxx）。
	token := r.URL.Query().Get("token")
	if token == "" || strings.Contains(token, "..") || strings.Contains(token, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "token query param required"})
		return
	}
	// 解析请求体，读取 extend_hours（可空 body）。
	var req struct {
		ExtendHours int `json:"extend_hours"`
	}
	// body 可选——为空时走默认值。
	if r.ContentLength != 0 {
		if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
			if !errors.Is(err, io.EOF) {
				writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
				return
			}
		}
	}
	// 默认 24 小时；0 或负数也视为默认（避免误传 0 导致无意义延长）。
	hours := req.ExtendHours
	if hours <= 0 {
		hours = 24
	}
	extendDuration := time.Duration(hours) * time.Hour

	if err := s.store.ExtendShareToken(r.Context(), token, uid, extendDuration); err != nil {
		if errors.Is(err, storage.ErrNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "share not found or not owned"})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "extend share token: " + err.Error()})
		return
	}
	// 重新查询以获取最新 expires_at 返回给客户端（UPDATE 后需重读）。
	st, err := s.store.GetShareToken(r.Context(), token)
	if err != nil {
		// 延长成功但读取失败——不回滚，告知已成功但无 new_expires_at。
		writeJSON(w, http.StatusOK, map[string]any{
			"status": "success",
			"token":  token,
		})
		return
	}
	newExpiresStr := ""
	if !st.ExpiresAt.IsZero() {
		newExpiresStr = st.ExpiresAt.UTC().Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":          "success",
		"token":           token,
		"new_expires_at":  newExpiresStr,
	})
}

// handleShareAccess 是 /api/share/ 前缀的统一入口，按 method + path 分流：
//   - GET /api/share/{token}              → handleShareView（公开查看元数据）
//   - GET /api/share/{token}/stream/{id}  → handleShareStream（公开下载字节流）
//   - DELETE /api/share/{token}           → handleShareDelete（需认证撤销）
//
// /api/share/create 由独立 handleShareCreate 处理，不进入此分流。
func (s *Server) handleShareAccess(w http.ResponseWriter, r *http.Request) {
	// 公开分享访问前置 IP 限速（防暴力枚举短链 token）。此 handler 是 /api/share/{token}
	// 的统一入口（GET 查看 / GET stream / DELETE 撤销），按 client IP 滑动窗口计数，
	// 每 IP 60s 内超 shareRateMax（30）次即拒。nil 守卫兼容未初始化的测试 server。
	// 仅影响以 token 为路径段的公开端点；create/list/extend 为独立精确匹配路由，不受影响。
	if s.rateLimiter != nil && !s.rateLimiter.Allow(clientIP(r)) {
		writeJSON(w, http.StatusTooManyRequests, map[string]any{"error": "rate limit exceeded"})
		return
	}
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

// handleSharePreview 处理 GET /s/{token}（公开 HTML 预览页，PRD-v10 §1.1）。
// 收件人无需安装 App 即可在浏览器中查看分享照片。
// 路由注册：server.go 的 s.mux.HandleFunc("/s/", s.handleSharePreview)；
// authMiddleware 豁免 /s/ 前缀，公开访问不要求认证。
//
// 逻辑：
//   - 路径形如 /s/{token}；取 /s/ 后第一段作为 token（忽略后续路径段）。
//   - 复用 GetShareToken + 过期校验（与 handleShareView 一致）。
//   - 有 password_hash 时：若 ?password=xxx 缺失或校验失败，返回含密码输入框的
//     HTML 页面（表单 GET /s/{token}?password=xxx）；密码正确则展示瀑布流。
//   - 无密码：直接展示照片瀑布流。
//   - 过期/不存在/无效：返回错误 HTML 页（不暴露具体原因）。
//   - 照片 <img src="/api/share/{token}/stream/{mediaId}">（与公开 stream 端点一致）。
func (s *Server) handleSharePreview(w http.ResponseWriter, r *http.Request) {
	if s.store == nil {
		writeShareErrorHTML(w, "服务暂不可用，请稍后再试。")
		return
	}
	// path 形如 /s/{token} 或 /s/{token}/…（额外段忽略）。取 /s/ 后第一段。
	rest := strings.TrimPrefix(r.URL.Path, "/s/")
	if rest == "" || rest == r.URL.Path {
		// rest == r.URL.Path 表示未匹配 /s/ 前缀（应不可能，因路由按前缀注册）。
		writeShareErrorHTML(w, "无效的分享链接。")
		return
	}
	if strings.Contains(rest, "..") {
		writeShareErrorHTML(w, "无效的分享链接。")
		return
	}
	token := rest
	if idx := strings.IndexByte(token, '/'); idx >= 0 {
		token = token[:idx]
	}
	if token == "" {
		writeShareErrorHTML(w, "无效的分享链接。")
		return
	}

	st, err := s.store.GetShareToken(r.Context(), token)
	if err != nil {
		writeShareErrorHTML(w, "分享链接不存在或已失效。")
		return
	}
	// 过期校验（不区分不存在与已过期，避免泄露）。
	if !st.ExpiresAt.IsZero() && time.Now().After(st.ExpiresAt) {
		writeShareErrorHTML(w, "分享链接不存在或已失效。")
		return
	}

	// 密码校验：有 password_hash 时要求 ?password=xxx，bcrypt 比对失败 → 密码输入页。
	needPasswordPage := false
	if st.PasswordHash != "" {
		pw := r.URL.Query().Get("password")
		if pw == "" {
			needPasswordPage = true
		} else if err := bcrypt.CompareHashAndPassword([]byte(st.PasswordHash), []byte(pw)); err != nil {
			needPasswordPage = true // 密码错误也回到密码页，不区分“无密码”与“错密码”
		}
		if needPasswordPage {
			writeSharePasswordHTML(w, token)
			return
		}
	}

	// 还原 media_ids 并逐个取元数据，过滤 deleted=false（软删的不展示）。
	var mediaIDs []string
	if err := json.Unmarshal([]byte(st.MediaIDs), &mediaIDs); err != nil {
		writeShareErrorHTML(w, "分享内容解析失败。")
		return
	}

	items := make([]sharePreviewItem, 0, len(mediaIDs))
	for _, mid := range mediaIDs {
		m, err := s.store.GetMedia(r.Context(), mid)
		if err != nil || m.Deleted {
			continue
		}
		items = append(items, sharePreviewItem{
			ID:       m.ID,
			Filename: m.Filename,
			Mime:     m.Mime,
		})
	}

	// 密码正确/无密码时，<img> 需带 ?password=xxx 以通过 stream 端点校验。
	pwQuery := ""
	if st.PasswordHash != "" {
		pwQuery = "?password=" + r.URL.Query().Get("password")
	}

	writeShareGalleryHTML(w, token, st, len(items), items, pwQuery)
}

// writeShareErrorHTML 返回轻量错误页（内联 CSS，中文友好提示）。
func writeShareErrorHTML(w http.ResponseWriter, msg string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusNotFound)
	fmt.Fprintf(w, `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>分享链接</title>
<style>%s</style></head>
<body><main class="wrap">
<div class="card error">
  <div class="icon">⊘</div>
  <p>%s</p>
</div>
</main></body></html>`, sharePreviewCSS(), msg)
}

// writeSharePasswordHTML 返回密码输入页（表单 GET /s/{token}?password=xxx）。
func writeSharePasswordHTML(w http.ResponseWriter, token string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>输入密码</title>
<style>%s</style></head>
<body><main class="wrap">
<div class="card pw">
  <div class="icon">🔐</div>
  <h1>请输入密码</h1>
  <p class="hint">该分享链接受密码保护。</p>
  <form method="get" action="/s/%s" class="pw-form">
    <input type="password" name="password" placeholder="密码" autofocus required>
    <button type="submit">查看</button>
  </form>
</div>
</main></body></html>`, sharePreviewCSS(), token)
}

// sharePreviewItem 是瀑布流预览页单张照片的渲染数据。
type sharePreviewItem struct {
	ID       string
	Filename string
	Mime     string
}

// writeShareGalleryHTML 返回照片瀑布流预览页（内联 CSS，响应式）。
func writeShareGalleryHTML(w http.ResponseWriter, token string, st *storage.ShareToken, count int, items []sharePreviewItem, pwQuery string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)

	createdStr := st.CreatedAt.UTC().Format("2006-01-02 15:04")
	expiresStr := "永久有效"
	if !st.ExpiresAt.IsZero() {
		expiresStr = st.ExpiresAt.UTC().Format("2006-01-02 15:04 MST")
	}

	// 构造缩略图卡片 HTML。
	var cards strings.Builder
	for _, it := range items {
		src := fmt.Sprintf("/api/share/%s/stream/%s%s", token, it.ID, pwQuery)
		fmt.Fprintf(&cards, `    <figure class="card photo" data-src="%s">
      <img loading="lazy" src="%s" alt="%s">
    </figure>`+"\n", src, src, it.Filename)
	}

	fmt.Fprintf(w, `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>分享相册</title>
<style>%s</style></head>
<body><main class="wrap">
<header class="hdr">
  <h1>📷 分享相册</h1>
  <p class="meta">创建于 %s · 有效期 %s · 共 %d 张</p>
</header>
<section class="grid">
%s</section>
<footer class="ftr">
  <button class="dl" onclick="downloadAll()">⬇ 下载全部</button>
</footer>
<div id="lightbox" class="lightbox" onclick="this.classList.remove('show')">
  <img id="lb-img" src="" alt="">
</div>
<script>
function downloadAll(){
  alert('请在 App 中使用「批量下载」获取全部原图。');
}
// 点击照片大图查看。
document.querySelectorAll('.photo').forEach(function(f){
  f.addEventListener('click',function(){
    var s=f.getAttribute('data-src');
    document.getElementById('lb-img').src=s;
    document.getElementById('lightbox').classList.add('show');
  });
});
</script>
</main></body></html>`, sharePreviewCSS(), createdStr, expiresStr, count, cards.String())
}

// sharePreviewCSS 返回预览页内联 CSS（响应式瀑布流：手机1列/平板2列/桌面3列）。
func sharePreviewCSS() string {
	return `*{box-sizing:border-box;margin:0;padding:0}
body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;background:#f5f5f7;color:#1d1d1f;line-height:1.5}
.wrap{max-width:1200px;margin:0 auto;padding:16px}
.hdr{padding:20px 0;text-align:center}
.hdr h1{font-size:24px;font-weight:600}
.meta{color:#86868b;font-size:14px;margin-top:6px}
.grid{display:grid;grid-template-columns:1fr;gap:12px;margin:16px 0}
@media(min-width:640px){.grid{grid-template-columns:repeat(2,1fr)}}
@media(min-width:1024px){.grid{grid-template-columns:repeat(3,1fr)}}
.card{background:#fff;border-radius:12px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.08)}
.photo{cursor:pointer;transition:transform .15s}
.photo:hover{transform:scale(1.02)}
.photo img{display:block;width:100%;height:auto;object-fit:cover}
.ftr{text-align:center;padding:24px 0}
.dl{background:#0071e3;color:#fff;border:none;border-radius:980px;padding:12px 28px;font-size:15px;cursor:pointer}
.dl:hover{background:#0077ed}
.lightbox{display:none;position:fixed;inset:0;background:rgba(0,0,0,.9);z-index:999;justify-content:center;align-items:center;cursor:zoom-out}
.lightbox.show{display:flex}
.lightbox img{max-width:92vw;max-height:92vh;object-fit:contain}
.icon{font-size:48px;text-align:center;margin-bottom:12px}
.error .icon{color:#ff3b30}
.error p{text-align:center;color:#86868b}
.pw h1{font-size:20px;font-weight:600;text-align:center;margin-bottom:8px}
.pw .hint{text-align:center;color:#86868b;font-size:14px;margin-bottom:20px}
.pw-form{display:flex;flex-direction:column;gap:12px;max-width:320px;margin:0 auto}
.pw-form input{padding:12px 14px;border:1px solid #d2d2d7;border-radius:10px;font-size:16px;outline:none}
.pw-form input:focus{border-color:#0071e3}
.pw-form button{background:#0071e3;color:#fff;border:none;border-radius:980px;padding:12px;font-size:15px;cursor:pointer}
.pw-form button:hover{background:#0077ed}`
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
