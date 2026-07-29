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
//
// 这两个端点经 authMiddleware 按路径前缀豁免，本身无需 token。
// handler 通过 s.authSvc 调用认证逻辑，并把 auth 包的哨兵错误映射为合适的 HTTP 状态码。

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
