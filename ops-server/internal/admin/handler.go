// Package admin 见 embed.go 包注释。本文件实现 /admin/* 的 HTTP 路由、
// JWT 鉴权中间件与 JSON API 端点。
package admin

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"media-manager/ops-server/internal/auth"
	"media-manager/ops-server/internal/storage"
)

// onlineFreshness 判定 server 在线的心跳新鲜度阈值。last_seen 距 now 在此阈值内视为在线，
// 超出但非零视为心跳超时(stale)，无心跳视为离线。与前端 servers.html 提示保持一致。
const onlineFreshness = 90 * time.Second

// Handler 封装运营管理前端 + /admin/* API。
// 依赖：auther 用于登录签发与 token 校验；store 用于查询服务端/设备/会话数据。
type Handler struct {
	auther *auth.AuthService
	store  *storage.Store
}

// Deps 注入参数；零值不可用，必须经 New 构造。
type Deps struct {
	Auther *auth.AuthService
	Store  *storage.Store
}

// New 构造 admin Handler。auther/store 均须非 nil。
func New(d Deps) (*Handler, error) {
	if d.Auther == nil || d.Store == nil {
		return nil, errors.New("admin: auther and store are required")
	}
	return &Handler{auther: d.Auther, store: d.Store}, nil
}

// Handler 返回挂在 /admin/ 下的 http.Handler（自含子路由）。
// 日志与 UnixSignal 关停由上层 main 负责。
func (h *Handler) Handler() http.Handler {
	mux := http.NewServeMux()

	// 静态页面：GET /admin/ 与 GET /admin/{page}.html。
	// 用精确前缀匹配（ pledges 由 ServeMux 1.22+ 的 path pattern 表达）。
	mux.HandleFunc("GET /{$}", h.serveLogin)  // /admin/ → 登录页
	mux.HandleFunc("GET /", h.servePageOrAPI) // 其余 /admin/... 统一入口

	// 登录无需鉴权。
	mux.HandleFunc("POST /login", h.postLogin)

	// 鉴权后的 JSON API（GET /api/*）。
	mux.HandleFunc("GET /api/overview", h.requireAuth(h.apiOverview))
	mux.HandleFunc("GET /api/users", h.requireAuth(h.apiUsers))
	mux.HandleFunc("GET /api/servers", h.requireAuth(h.apiServers))
	mux.HandleFunc("GET /api/sessions", h.requireAuth(h.apiSessions))
	mux.HandleFunc("GET /api/traffic/summary", h.requireAuth(h.apiTrafficSummary))
	mux.HandleFunc("GET /api/account", h.requireAuth(h.apiAccount))

	return http.StripPrefix("/admin", mux)
}

// ---- 静态页面 ----

// serveLogin 处理 GET /admin/，返回 index.html（登录页）。
// 路由 {$} 保证仅精确匹配根，不吞下 /admin/api/*。
func (h *Handler) serveLogin(w http.ResponseWriter, r *http.Request) {
	h.serveStatic(w, "index.html")
}

// servePageOrAPI 是 /admin/ 下非根路径的统一入口：HTML 页面直接返回，
// /api/* 不应落到这里（已被显式路由捕获）；其余路径 404。
func (h *Handler) servePageOrAPI(w http.ResponseWriter, r *http.Request) {
	p := strings.TrimPrefix(r.URL.Path, "/admin/")
	if p == "" {
		h.serveStatic(w, "index.html")
		return
	}
	// 仅放行白名单页面，避免目录穿越/列举。
	if pageNames[p] {
		h.serveStatic(w, p)
		return
	}
	http.NotFound(w, r)
}

// serveStatic 写出一个嵌入页面，带基础缓存控制与内容类型。
func (h *Handler) serveStatic(w http.ResponseWriter, name string) {
	b, err := staticFS.ReadFile("static/" + name)
	if err != nil {
		http.NotFound(w, nil)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	_, _ = w.Write(b)
}

// ---- 鉴权 ----

// requireAuth 包裹受保护 handler：校验 Bearer JWT，失败返回 401 JSON。
// 校验通过后把 accountID 注入 request context，供下游 handler 取用。
func (h *Handler) requireAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token := bearerToken(r)
		if token == "" {
			writeJSONError(w, http.StatusUnauthorized, "missing token")
			return
		}
		accID, err := h.auther.ParseToken(token)
		if err != nil {
			writeJSONError(w, http.StatusUnauthorized, "invalid or expired token")
			return
		}
		ctx := context.WithValue(r.Context(), ctxKeyAccountID{}, accID)
		next(w, r.WithContext(ctx))
	}
}

// ctxKeyAccountID 是 request context 中 accountID 的键类型（避免字符串键冲突）。
type ctxKeyAccountID struct{}

// accountIDFrom 从 context 取出 accountID（仅 requireAuth 包裹后的 handler 可用）。
func accountIDFrom(ctx context.Context) string {
	if v, ok := ctx.Value(ctxKeyAccountID{}).(string); ok {
		return v
	}
	return ""
}

// bearerToken 从 Authorization 头解析 Bearer token。
func bearerToken(r *http.Request) string {
	v := r.Header.Get("Authorization")
	const pfx = "Bearer "
	if len(v) > len(pfx) && strings.EqualFold(v[:len(pfx)], pfx) {
		return strings.TrimSpace(v[len(pfx):])
	}
	return ""
}

// ---- 登录端点 ----

func (h *Handler) postLogin(w http.ResponseWriter, r *http.Request) {
	var req auth.LoginRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	res, err := h.auther.Login(r.Context(), req)
	if err != nil {
		if errors.Is(err, auth.ErrInvalidCredentials) {
			writeJSONError(w, http.StatusUnauthorized, "用户名或密码错误")
			return
		}
		writeJSONError(w, http.StatusInternalServerError, "login failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"token":   res.Token,
		"expires": res.ExpiresAt,
		"account": res.Account,
	})
}

// ---- 数据 API ----

// apiAccount 返回当前登录账号信息（前端可在导航栏展示用户名）。
func (h *Handler) apiAccount(w http.ResponseWriter, r *http.Request) {
	accID := accountIDFrom(r.Context())
	acc, err := h.store.GetOpAccountByID(r.Context(), accID)
	if err != nil {
		writeJSONError(w, http.StatusUnauthorized, "account not found")
		return
	}
	// 注：本包不持久化 role，登录态下统一回 user；admin 语义由注册时模式决定（见 auth.roleFor 注释）。
	writeJSON(w, http.StatusOK, map[string]any{
		"account": map[string]any{
			"id":         acc.ID,
			"username":   acc.Username,
			"created_at": acc.CreatedAt,
		},
	})
}

// apiUsers 返回全量设备（终端用户），跨所有 server。
func (h *Handler) apiUsers(w http.ResponseWriter, r *http.Request) {
	devs, err := h.store.ListAllDevices(r.Context())
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "load devices failed")
		return
	}
	out := make([]map[string]any, 0, len(devs))
	for _, d := range devs {
		out = append(out, map[string]any{
			"server_id": d.ServerID,
			"device_id": d.DeviceID,
			"online":    d.Online,
			"last_seen": d.LastSeen,
			"meta":      d.Meta,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"users": out})
}

// serverStatusView 是 /api/servers 响应中的单条服务端视图（含在线判定）。
type serverStatusView struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Status      string    `json:"status"` // online / stale / offline
	LastSeen    time.Time `json:"last_seen"`
	CreatedAt   time.Time `json:"created_at"`
	DeviceCount int       `json:"device_count"`
}

// apiServers 返回全部受管服务端 + 在线状态 + 名下设备计数。
func (h *Handler) apiServers(w http.ResponseWriter, r *http.Request) {
	servers, err := h.store.ListServers(r.Context())
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "load servers failed")
		return
	}
	counts, err := h.store.ServerDeviceCount(r.Context())
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "load device counts failed")
		return
	}
	now := time.Now()
	out := make([]serverStatusView, 0, len(servers))
	for _, s := range servers {
		out = append(out, serverStatusView{
			ID:          s.ID,
			Name:        s.Name,
			Status:      classifyServer(s.LastSeen, now),
			LastSeen:    s.LastSeen,
			CreatedAt:   s.CreatedAt,
			DeviceCount: counts[s.ID],
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"servers": out})
}

// classifyServer 按 last_seen 新鲜度判定在线/stale/offline。
// 零值 LastSeen 视为离线（理论上注册即写 last_seen，零值属异常数据）。
func classifyServer(lastSeen, now time.Time) string {
	if lastSeen.IsZero() {
		return "offline"
	}
	if now.Sub(lastSeen) <= onlineFreshness {
		return "online"
	}
	return "stale"
}

// apiSessions 返回最近中继会话（跨 server）。limit 上限 200，默认 50。
func (h *Handler) apiSessions(w http.ResponseWriter, r *http.Request) {
	limit := 50
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = n
		}
	}
	if limit > 200 {
		limit = 200
	}
	list, err := h.store.ListAllRelaySessions(r.Context(), limit)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "load sessions failed")
		return
	}
	out := make([]map[string]any, 0, len(list))
	for _, rs := range list {
		row := map[string]any{
			"id":           rs.ID,
			"server_id":    rs.ServerID,
			"pair_key":     rs.PairKey,
			"bytes_in":     rs.BytesIn,
			"bytes_out":    rs.BytesOut,
			"started_at":   rs.StartedAt,
			"close_reason": rs.CloseReason,
		}
		if !rs.EndedAt.IsZero() {
			row["ended_at"] = rs.EndedAt
		} else {
			row["ended_at"] = nil
		}
		out = append(out, row)
	}
	writeJSON(w, http.StatusOK, map[string]any{"sessions": out})
}

// apiTrafficSummary 返回每 server 中继流量汇总 + 全局合计。
func (h *Handler) apiTrafficSummary(w http.ResponseWriter, r *http.Request) {
	sums, err := h.store.AllRelaySummaries(r.Context())
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "load traffic summary failed")
		return
	}
	out := make([]map[string]any, 0, len(sums))
	for _, s := range sums {
		out = append(out, map[string]any{
			"server_id":       s.ServerID,
			"session_count":   s.Count,
			"active_sessions": s.Active,
			"total_bytes_in":  s.TotalIn,
			"total_bytes_out": s.TotalOut,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{"summaries": out})
}

// apiOverview 返回看板总览数字：服务端/设备/会话计数与全局流量。
// 用单次 SQL 聚合而非逐表遍历，保证看板首屏低延迟。
func (h *Handler) apiOverview(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	serversTotal, err := h.store.CountServers(ctx)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "count servers failed")
		return
	}
	// 在线服务端数：需要按 last_seen 判定，逐表列后统计。
	onlineServers := 0
	if serversTotal > 0 {
		srvs, err := h.store.ListServers(ctx)
		if err == nil {
			now := time.Now()
			for _, s := range srvs {
				if classifyServer(s.LastSeen, now) == "online" {
					onlineServers++
				}
			}
		}
	}
	devTotal, devOnline, err := h.store.DeviceCountTotals(ctx)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "count devices failed")
		return
	}
	g, err := h.store.GlobalRelaySummary(ctx)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "relay summary failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"servers_total":   serversTotal,
		"servers_online":  onlineServers,
		"devices_total":   devTotal,
		"devices_online":  devOnline,
		"sessions_total":  g.SessionCount,
		"sessions_active": g.ActiveSessions,
		"total_bytes_in":  g.TotalBytesIn,
		"total_bytes_out": g.TotalBytesOut,
	})
}

// ---- JSON 工具 ----

// writeJSON 序列化 v 为 JSON 响应。
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// writeJSONError 写出统一错误格式 { "error": msg }，供前端 j.error 读取。
func writeJSONError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

// decodeJSON 限制 body 体积并解码 JSON，防止超大请求体耗内存。
// 解码后若仍有非空白尾随数据视为格式错误（拒收一对象多段 JSON）。
func decodeJSON(r *http.Request, v any) error {
	const maxBody = 1 << 20 // 1 MiB
	body := http.MaxBytesReader(nil, r.Body, maxBody)
	defer body.Close()
	dec := json.NewDecoder(body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return err
	}
	// 尝试解码第二个值；应得 io.EOF（无尾随）。
	if _, err := dec.Token(); err != io.EOF {
		return errors.New("unexpected trailing data")
	}
	return nil
}
