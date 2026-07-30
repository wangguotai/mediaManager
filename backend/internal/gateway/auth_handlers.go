package gateway

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"media-manager/backend/internal/auth"
)

// ============ Auth endpoints ============
//
// /api/auth/login   POST  {username,password} -> {token,expires_at,user}
// /api/auth/register POST {username,password} -> {token,expires_at,user} （受 allow_signup 控制）
// /api/auth/change-password POST {old_password,new_password} -> {status:"success"}（需 Bearer token，只能改自己）
//
// login/register 经 authMiddleware 明确豁免（无需 token）；change-password 必须带 token，
// user_id 由中间件从 JWT 解析注入。handler 通过 s.authSvc 调用认证逻辑，并把 auth 包的
// 哨兵错误映射为合适的 HTTP 状态码。

// loginRequest 是登录/注册请求体。
type loginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// handleAuthLogin 处理 POST /api/auth/login。
func (s *Server) handleAuthLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.authSvc == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "auth is not configured"})
		return
	}
	var req loginRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	req.Username = strings.TrimSpace(req.Username)
	if req.Username == "" || req.Password == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "username and password are required"})
		return
	}

	result, err := s.authSvc.Login(r.Context(), auth.LoginRequest{
		Username: req.Username,
		Password: req.Password,
	})
	if err != nil {
		writeAuthError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

// changePasswordRequest 是改密请求体。old_password 用于复核身份，new_password 须满足长度策略。
type changePasswordRequest struct {
	OldPassword string `json:"old_password"`
	NewPassword string `json:"new_password"`
}

// handleAuthChangePassword 处理 POST /api/auth/change-password。
// 必须带有效 token（中间件已校验并注入 user_id），用户只能改自己的密码。
// 成功返回 200 {status:"success"}；旧密码错/新密码弱/用户不存在统一 400（防枚举，不区分）。
func (s *Server) handleAuthChangePassword(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.authSvc == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "auth is not configured"})
		return
	}
	// user_id 由 authMiddleware 从 JWT 注入；未带 token 的请求已被中间件 401 拦截，
	// 到这里 uid 必非空。为稳妥仍兜底判空（兼容 authSvc=nil 的纯测试 server）。
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}
	var req changePasswordRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.OldPassword == "" || req.NewPassword == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "old_password and new_password are required"})
		return
	}

	if err := s.authSvc.ChangePassword(r.Context(), uid, req.OldPassword, req.NewPassword); err != nil {
		writeAuthError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success"})
}

// handleAuthRegister 处理 POST /api/auth/register。
func (s *Server) handleAuthRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.authSvc == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "auth is not configured"})
		return
	}
	var req loginRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	req.Username = strings.TrimSpace(req.Username)
	if req.Username == "" || req.Password == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "username and password are required"})
		return
	}

	result, err := s.authSvc.Register(r.Context(), auth.RegisterRequest{
		Username: req.Username,
		Password: req.Password,
	})
	if err != nil {
		writeAuthError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, result)
}

// writeAuthError 把 auth 包的哨兵错误映射为 HTTP 状态码：
//   - ErrInvalidCredentials / ErrUsernameTaken（弱口令）→ 400
//   - ErrSignupDisabled → 403（注册被策略关闭，语义清晰）
//   - 其余 → 500
func writeAuthError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, auth.ErrInvalidCredentials):
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": err.Error()})
	case errors.Is(err, auth.ErrUsernameTaken):
		writeJSON(w, http.StatusConflict, map[string]any{"error": err.Error()})
	case errors.Is(err, auth.ErrSignupDisabled):
		writeJSON(w, http.StatusForbidden, map[string]any{"error": err.Error()})
	default:
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
	}
}
