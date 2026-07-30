// Package admin — 本文件实现 PRD §2.8 运营前端写操作端点：
//
//   - POST /admin/api/server/register   手动登记/查看 server，生成 server_token
//   - POST /admin/api/session/close     主动断开活跃中继会话
//   - POST /admin/api/user/bind-server  op_user(op_account) 绑定 server_id
//   - GET  /admin/api/users/list        运营账号列表（含 server_id， binds UI 用）
//   - GET  /admin/api/sessions/active   活跃中继会话 + 在线设备列表
//
// 鉴权：全部经 requireAuth 包裹（与既有只读 API 同一套 JWT），输入输出均 JSON。
//
// 设计说明（op_user vs op_account）：PRD 文案用 op_user，本仓持久化表实际为 op_account
// （运营账号），二者语义一致——此处按 op_account 实现，前端文案保留"运营账号/用户"通用称呼。
package admin

import (
	"errors"
	"net/http"
	"strings"
	"time"

	"media-manager/ops-server/internal/auth"
)

// ---- POST /api/server/register ----

// serverRegisterRequest 手动登记受管服务端的入参。仅需 name；空白走默认值。
type serverRegisterRequest struct {
	Name string `json:"name"`
}

// apiServerRegister 复用 auth.AuthService.RegisterServer 落库一个受管服务端，
// 并把一次性明文 server_token 返回给前端。与 /op/server/register 端点逻辑一致，
// 区别仅在此端点要求运营账号 JWT（管理面），后者免鉴权（受管服务端自注册面）。
func (h *Handler) apiServerRegister(w http.ResponseWriter, r *http.Request) {
	var req serverRegisterRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	res, err := h.auther.RegisterServer(r.Context(), auth.RegisterServerRequest{Name: req.Name})
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "register server failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"server_id":    res.ServerID,
		"server_token": res.ServerToken,
		"name":         res.Name,
		"created_at":   res.CreatedAt,
	})
}

// ---- POST /api/session/close ----

// sessionCloseRequest 主动断开会话入参。
type sessionCloseRequest struct {
	SessionID string `json:"session_id"`
}

// apiSessionClose 调 relay.Service.CloseSession 主动断开进行中的中继会话。
// relay 未注入（h.relay==nil）时返回 503，提示该管理面未接线中继服务。
func (h *Handler) apiSessionClose(w http.ResponseWriter, r *http.Request) {
	var req sessionCloseRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	sid := strings.TrimSpace(req.SessionID)
	if sid == "" {
		writeJSONError(w, http.StatusBadRequest, "session_id required")
		return
	}
	if h.relay == nil {
		writeJSONError(w, http.StatusServiceUnavailable, "relay service not attached")
		return
	}
	if err := h.relay.CloseSession(sid); err != nil {
		// 会话不存在或已结束视为幂等成功更友好？这里返回 404 让前端区分"无此活跃会话"。
		writeJSONError(w, http.StatusNotFound, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "closed", "session_id": sid})
}

// ---- POST /api/user/bind-server ----

// userBindServerRequest 把运营账号绑定到 server_id。空 server_id 视为解绑。
type userBindServerRequest struct {
	AccountID string `json:"account_id"`
	ServerID  string `json:"server_id"`
}

// apiUserBindServer 更新 op_account.server_id。account_id 必填；server_id 可空（解绑）。
// 不在此校验 server_id 是否真实存在（绑定到尚未注册的 server 不致命，后续可补校验），
// 保持接口宽容以便前端先选 server 占位。
func (h *Handler) apiUserBindServer(w http.ResponseWriter, r *http.Request) {
	var req userBindServerRequest
	if err := decodeJSON(r, &req); err != nil {
		writeJSONError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	aid := strings.TrimSpace(req.AccountID)
	if aid == "" {
		writeJSONError(w, http.StatusBadRequest, "account_id required")
		return
	}
	sid := strings.TrimSpace(req.ServerID)
	if err := h.store.SetOpAccountServer(r.Context(), aid, sid); err != nil {
		if errors.Is(err, auth.ErrAccountNotFound) {
			writeJSONError(w, http.StatusNotFound, "account not found")
			return
		}
		writeJSONError(w, http.StatusInternalServerError, "bind server failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":     "bound",
		"account_id": aid,
		"server_id":  sid,
	})
}

// ---- GET /api/users/list ----

// apiUsersList 返回全部运营账号（op_account，含 server_id 绑定态）。
// 供"绑定 server"操作页填充账号下拉。与 /api/users（设备列表）区分命名。
func (h *Handler) apiUsersList(w http.ResponseWriter, r *http.Request) {
	list, err := h.store.ListOpAccounts(r.Context())
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "load accounts failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"accounts": list})
}

// ---- GET /api/sessions/active ----

// apiActiveSessions 返回活跃中继会话（ended_at 为空）+ 全量在线设备列表。
// 供流量页"活跃会话列表/在线设备列表"展示与断开操作。活跃会话仍取自 storage，
// 与 /api/sessions 同源但过滤 ended_at 为空，避免前端再次过滤。
func (h *Handler) apiActiveSessions(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	// 活跃会话：列出最近 200 条，再在内存过滤 ended_at 零值。
	// 取 200 而非全量，避免历史会话堆积时返回过大；活跃会话通常远小于此数。
	list, err := h.store.ListAllRelaySessions(ctx, 200)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "load sessions failed")
		return
	}
	sessions := make([]map[string]any, 0, len(list))
	now := time.Now()
	for _, rs := range list {
		if !rs.EndedAt.IsZero() {
			continue
		}
		sessions = append(sessions, map[string]any{
			"id":           rs.ID,
			"server_id":    rs.ServerID,
			"pair_key":     rs.PairKey,
			"bytes_in":     rs.BytesIn,
			"bytes_out":    rs.BytesOut,
			"started_at":   rs.StartedAt,
			"close_reason": rs.CloseReason,
			"age_seconds":  int64(now.Sub(rs.StartedAt).Seconds()),
		})
	}

	// 在线设备：online=1，按 last_seen 倒序。
	devs, err := h.store.ListAllOnlineDevices(ctx)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "load online devices failed")
		return
	}
	devices := make([]map[string]any, 0, len(devs))
	for _, d := range devs {
		devices = append(devices, map[string]any{
			"device_id": d.DeviceID,
			"server_id": d.ServerID,
			"last_seen": d.LastSeen,
			"meta":      d.Meta,
		})
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"sessions": sessions,
		"devices":  devices,
	})
}
