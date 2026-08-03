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

	// PRD §2.7 登录暴力限速：按 (IP, username) 滑动窗口限速，防暴力撞库。
	// 解析出 username 后再检查（限速维度含用户名）；超限直接 429 不进认证逻辑，
	// 避免暴露"用户名是否存在"的侧信道。loginLimiter 为 nil 时（纯测试）跳过。
	if s.loginLimiter != nil {
		ip := clientIP(r)
		if !s.loginLimiter.Allow(ip, req.Username) {
			writeJSON(w, http.StatusTooManyRequests, map[string]any{
				"error": "too many login attempts, please try again later",
			})
			return
		}
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
	// 弱口令泄漏检测：新密码命中常见弱口令黑名单则拒绝。
	// 与 auth.ChangePassword 内部的长度+复杂度校验互补（防 Passw0rd/Qwerty123 等）。
	if isPasswordCompromised(req.NewPassword) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "password is too common, please choose a stronger one"})
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
	// 弱口令泄漏检测：拒绝常见弱密码（即便满足复杂度策略）。
	// 在 auth.Register 内部的长度+复杂度校验之外叠加一层黑名单拦截。
	if isPasswordCompromised(req.Password) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "password is too common, please choose a stronger one"})
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
//   - ErrInvalidCredentials / ErrPasswordTooWeak → 400（凭据或口令强度不合规）
//   - ErrUsernameTaken → 409（用户名已占用）
//   - ErrSignupDisabled → 403（注册被策略关闭，语义清晰）
//   - 其余 → 500
func writeAuthError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, auth.ErrInvalidCredentials), errors.Is(err, auth.ErrPasswordTooWeak):
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": err.Error()})
	case errors.Is(err, auth.ErrUsernameTaken):
		writeJSON(w, http.StatusConflict, map[string]any{"error": err.Error()})
	case errors.Is(err, auth.ErrSignupDisabled):
		writeJSON(w, http.StatusForbidden, map[string]any{"error": err.Error()})
	default:
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
	}
}

// handleAuthRefresh POST /api/auth/refresh — 用 refresh token 换发新 access token。
//
// 请求体: {"refresh_token": "..."}。验证 refresh token 有效（签名+过期+type=refresh）
// 后签发新 access token（不签发新 refresh，无滑动窗口——refresh 有bounded 30天寿命）。
// 此端点豁免 Bearer access token 中间件（走 refresh token 鉴权）。
func (s *Server) handleAuthRefresh(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.authSvc == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "auth is not configured"})
		return
	}
	var req struct {
		RefreshToken string `json:"refresh_token"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.RefreshToken == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "refresh_token is required"})
		return
	}
	userID, err := s.authSvc.ParseRefreshToken(req.RefreshToken)
	if err != nil {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid or expired refresh token"})
		return
	}
	// 签发新 access token（不签发新 refresh）。
	token, exp, err := s.authSvc.IssueAccessToken(userID)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "failed to issue token"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"token":       token,
		"expires_at":  exp,
		"token_type":  "Bearer",
		"user_id":     userID,
	})
}
