// Package opapi 实现运营服务端面向受管服务端 / 客户端的 open API（/op/* 路由）。
//
// 与 admin 包（运营前端 + 运营账号 JWT）互补：
//   - /op/server/register：受管存储服务端注册，返回 server_id + server_token（明文仅此一次）。
//   - /op/device/register | /op/device/heartbeat：受管服务端代其名下设备上线上报与心跳。
//   - /op/device/list：发现查询，列出某 server 名下设备。
//   - WS /op/ws（客户端）/ WS /op/server/ws（受管服务端）：长连通道，承载信令与在线态。
//
// 鉴权约定：
//   - 受管服务端相关端点（device/*、server/ws）以 server_token 经 Authorization: Bearer <token>
//     携带，由 auth.AuthenticateServer 校验，成功得到 *auth.Server 视图。
//   - server/register 不鉴权（注册即获取凭据）。
//   - /op/ws（客户端）鉴权：可选 op 账号 JWT 或 server_token；V5 本机联调下宽松放行，
//     连接时携带目标 server_id/device_id 作为 peerID，信令按 peerID 路由。
package opapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/coder/websocket"

	"media-manager/ops-server/internal/auth"
	"media-manager/ops-server/internal/discovery"
	"media-manager/ops-server/internal/signaling"
	"media-manager/ops-server/internal/ws"
)

// Deps 注入 opapi Handler 所需依赖。零值不可用，必须经 New 构造。
type Deps struct {
	Auther    *auth.AuthService
	Discovery *discovery.Service
	Signaler  *signaling.Signaler
	Hub       *ws.Hub
}

// Handler 挂载 /op/* 路由。由 main 调用 Routes() 取得 http.Handler 注册到根 mux。
type Handler struct {
	auther    *auth.AuthService
	discovery *discovery.Service
	signaler  *signaling.Signaler
	hub       *ws.Hub
}

// New 构造 Handler。Auther/Discovery/Hub 须非 nil；Signaler 可空（仅候选不记录）。
func New(d Deps) (*Handler, error) {
	if d.Auther == nil {
		return nil, errors.New("opapi: nil auther")
	}
	if d.Discovery == nil {
		return nil, errors.New("opapi: nil discovery")
	}
	if d.Hub == nil {
		return nil, errors.New("opapi: nil hub")
	}
	return &Handler{
		auther:    d.Auther,
		discovery: d.Discovery,
		signaler:  d.Signaler,
		hub:       d.Hub,
	}, nil
}

// Routes 返回挂在 /op/ 前缀下的 http.Handler（自含子路由）。
// 调用方用 http.StripPrefix("/op", h.Routes()) 或直接挂到 ServeMux 的 "/op/" 上。
func (h *Handler) Routes() http.Handler {
	mux := http.NewServeMux()

	// 受管服务端注册（无鉴权，注册即得凭据）。
	mux.HandleFunc("POST /server/register", h.postServerRegister)

	// 设备注册/心跳（需 server_token 鉴权）。
	mux.HandleFunc("POST /device/register", h.requireServerToken(h.postDeviceRegister))
	mux.HandleFunc("POST /device/heartbeat", h.requireServerToken(h.postDeviceHeartbeat))

	// 设备发现列表（需 server_token 鉴权；仅返回该 server 名下设备）。
	mux.HandleFunc("GET /device/list", h.requireServerToken(h.getDeviceList))

	// WebSocket：受管服务端长连（server_token 鉴权）。
	mux.HandleFunc("GET /server/ws", h.requireServerTokenWS(h.handleServerWS))
	// WebSocket：客户端长连（宽松鉴权，V5 本机联调）。
	mux.HandleFunc("GET /ws", h.handleClientWS)

	return mux
}

// ---- 受管服务端注册 ----

// registerServerRequest 受管服务端注册入参。
type registerServerRequest struct {
	Name string `json:"name"`
}

// postServerRegister 暴露 auth.RegisterServer。
func (h *Handler) postServerRegister(w http.ResponseWriter, r *http.Request) {
	var req registerServerRequest
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

// ---- 设备注册/心跳/列表 ----

// deviceRequest 设备注册/心跳通用入参。
type deviceRequest struct {
	DeviceID string `json:"device_id"`
	Meta     string `json:"meta"` // 透传元信息（平台/名称等原始 JSON 字符串），可空
}

// postDeviceRegister 受管服务端代设备上线（注册/心跳二合一，含 meta）。
func (h *Handler) postDeviceRegister(w http.ResponseWriter, r *http.Request) {
	srv := serverFromContext(r.Context())
	var req deviceRequest
	if err := decodeJSON(r, &req); err != nil || req.DeviceID == "" {
		writeJSONError(w, http.StatusBadRequest, "invalid request body or device_id")
		return
	}
	d, err := h.discovery.RegisterDevice(r.Context(), srv, req.DeviceID, req.Meta)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "register device failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"server_id": d.ServerID,
		"device_id": d.DeviceID,
		"online":    d.Online,
		"last_seen": d.LastSeen,
	})
}

// postDeviceHeartbeat 设备心跳：仅更新 online + last_seen。
func (h *Handler) postDeviceHeartbeat(w http.ResponseWriter, r *http.Request) {
	srv := serverFromContext(r.Context())
	vid := strings.TrimSpace(r.URL.Query().Get("device_id"))
	if vid == "" {
		var req deviceRequest
		if err := decodeJSON(r, &req); err == nil {
			vid = req.DeviceID
		}
	}
	if vid == "" {
		writeJSONError(w, http.StatusBadRequest, "device_id required")
		return
	}
	if err := h.discovery.Heartbeat(r.Context(), srv.ID, vid); err != nil {
		writeJSONError(w, http.StatusInternalServerError, "heartbeat failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok"})
}

// getDeviceList 列出该 server 名下设备（发现查询主路径）。
func (h *Handler) getDeviceList(w http.ResponseWriter, r *http.Request) {
	srv := serverFromContext(r.Context())
	ds, err := h.discovery.ListDevices(r.Context(), srv.ID)
	if err != nil {
		writeJSONError(w, http.StatusInternalServerError, "list devices failed")
		return
	}
	if ds == nil {
		ds = []discovery.Device{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"server_id": srv.ID, "devices": ds})
}

// ---- WebSocket 端点 ----

// wsReadLimit 单帧最大字节数（信令帧偏小，限 64KiB 防滥用）。
const wsReadLimit = 64 * 1024

// wsPingInterval 心跳保活间隔（< coder/websocket 默认 30s 关闭超时）。
const wsPingInterval = 25 * time.Second

// serverWsQuery 给受管服务端 WS 连接的 query 参数名（可选，默认用 server 自己 id 作 peerID）。
const serverWsQuery = "device_id"

// handleServerWS 处理受管服务端 WS 长连。peerID 约定 "<server_id>:_self"。
func (h *Handler) handleServerWS(w http.ResponseWriter, r *http.Request) {
	srv := serverFromContext(r.Context())
	peerID := srv.ID + ":_self"
	// 受管服务端连入：role=server，serverID 为自身。
	h.serveWS(w, r, peerID, ws.RoleServer, srv.ID)
}

// handleClientWS 处理客户端 WS 长连。peerID 取 query: server_id:device_id。
// V5 本机联调：宽松鉴权（可带 server_token 或无），仅按 query 派生 peerID。
func (h *Handler) handleClientWS(w http.ResponseWriter, r *http.Request) {
	serverID := strings.TrimSpace(r.URL.Query().Get("server_id"))
	deviceID := strings.TrimSpace(r.URL.Query().Get("device_id"))
	if serverID == "" || deviceID == "" {
		writeJSONError(w, http.StatusBadRequest, "server_id and device_id query params required")
		return
	}
	peerID := serverID + ":" + deviceID
	h.serveWS(w, r, peerID, ws.RoleClient, serverID)
}

// serveWS 升级 WS、注册到 Hub、双向 pump。
//   - 读循环：解 Envelope → Hub.Route（触发转发与 onSignal 信令处理）。
//   - 写循环：消费 Client.Outbox() → 写 WS 帧。
//   - 心跳 ticker：定期向客户端发 ping 帧保活；收到客户端 ping 由 Hub.Route 回 pong。
func (h *Handler) serveWS(w http.ResponseWriter, r *http.Request, peerID string, role ws.Role, serverID string) {
	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// V5 本机联调：放宽 origin 校验，便于两端口跨 origin 调试。
		InsecureSkipVerify: true,
	})
	if err != nil {
		// Accept 失败时连接已被库处理，仅日志。
		log.Printf("opapi: ws accept %s: %v", peerID, err)
		return
	}
	c.SetReadLimit(wsReadLimit)
	defer c.CloseNow()

	client := ws.NewClient(peerID, role, serverID, 16)
	if err := h.hub.Register(client); err != nil {
		_ = c.Write(r.Context(), websocket.MessageText, mustEncode(ws.Envelope{Type: "error", Msg: err.Error()}))
		return
	}
	defer h.hub.Unregister(client)

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	// 写循环：Outbox → WS。
	go func() {
		defer cancel()
		ticker := time.NewTicker(wsPingInterval)
		defer ticker.Stop()
		for {
			select {
			case msg, ok := <-client.Outbox():
				if !ok {
					return
				}
				if err := c.Write(ctx, websocket.MessageText, mustEncode(msg)); err != nil {
					return
				}
			case <-ticker.C:
				// 主动发 ping 帧保活（与客户端的 pong 响应互补）。
				ping := ws.Envelope{Type: "ping"}
				if err := c.Write(ctx, websocket.MessageText, mustEncode(ping)); err != nil {
					return
				}
			case <-client.Done():
				return
			case <-ctx.Done():
				return
			}
		}
	}()

	// 读循环：WS → Envelope → Hub.Route。
	for {
		_, data, err := c.Read(ctx)
		if err != nil {
			break
		}
		var env ws.Envelope
		if err := json.Unmarshal(data, &env); err != nil {
			_ = client.Send(ws.Envelope{Type: "error", Msg: "invalid json frame"})
			continue
		}
		// 候选地址登记：收到 candidates 帧时，经 Signaler 登记并尝试 Introduce。
		if env.Type == "candidates" && h.signaler != nil {
			h.handleCandidates(client, env)
		}
		h.hub.Route(client, env)
	}
}

// handleCandidates 登记候选地址并尝试完成配对介绍。
//   - 候选 payload 形如 {"candidates":[{"type":"host","addr":"1.2.3.4:5678"}]}。
//   - 登记 A 端后尝试 Introduce(A→B)；若 B 尚未登记返回 ErrIncompletePair（静默，等 B 到达）。
//   - 介绍成功后向双方下发 introduced 帧（含对端候选）。
func (h *Handler) handleCandidates(from *ws.Client, env ws.Envelope) {
	var payload struct {
		Candidates []signaling.Candidate `json:"candidates"`
	}
	if len(env.Payload) > 0 {
		_ = json.Unmarshal(env.Payload, &payload)
	}
	pairKey := env.PairKey
	if pairKey == "" {
		// 未显式指定 pairKey 时，由双方 peerID 字典序派生稳定键，保证双方登记一致。
		pairKey = stablePairKey(from.PeerID, env.To)
	}
	pc := h.signaler.RegisterCandidates(from.PeerID, pairKey, payload.Candidates)
	_ = pc
	// 若有明确目标 To，尝试与对端完成介绍。
	if env.To != "" {
		intro, err := h.signaler.Introduce(from.PeerID, env.To, pairKey)
		if err == nil {
			// 向双方下发 introduced（各自视角的 Local/Remote）。
			_ = from.Send(ws.Envelope{
				Type: "introduced", To: from.PeerID, From: env.To, PairKey: pairKey,
				Payload: mustRaw(intro.Remote),
			})
			if target, ok := h.hub.Lookup(env.To); ok {
				_ = target.Send(ws.Envelope{
					Type: "introduced", To: env.To, From: from.PeerID, PairKey: pairKey,
					Payload: mustRaw(intro.Local),
				})
			}
		}
	}
}

// ---- 鉴权中间件 ----

// ctxKeyServer 是 request context 中 *auth.Server 的键类型。
type ctxKeyServer struct{}

func serverFromContext(ctx context.Context) *auth.Server {
	if v, ok := ctx.Value(ctxKeyServer{}).(*auth.Server); ok {
		return v
	}
	return nil
}

// requireServerToken 校验 Authorization: Bearer <server_token>，注入 *auth.Server。
func (h *Handler) requireServerToken(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		srv, err := h.authenticateServer(r)
		if err != nil {
			writeJSONError(w, http.StatusUnauthorized, "invalid or missing server token")
			return
		}
		ctx := context.WithValue(r.Context(), ctxKeyServer{}, srv)
		next(w, r.WithContext(ctx))
	}
}

// requireServerTokenWS 同 requireServerToken，但 WS 升级失败回 JSON 而非阻塞。
// 用于 GET /op/server/ws：先校验 token 再进入 serveWS。
func (h *Handler) requireServerTokenWS(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		srv, err := h.authenticateServer(r)
		if err != nil {
			writeJSONError(w, http.StatusUnauthorized, "invalid or missing server token")
			return
		}
		ctx := context.WithValue(r.Context(), ctxKeyServer{}, srv)
		next(w, r.WithContext(ctx))
	}
}

// authenticateServer 从 Authorization 头解析 server_token 并校验。
func (h *Handler) authenticateServer(r *http.Request) (*auth.Server, error) {
	tok := bearerToken(r)
	if tok == "" {
		return nil, auth.ErrInvalidServerToken
	}
	return h.auther.AuthenticateServer(r.Context(), tok)
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

// ---- JSON 工具 ----

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeJSONError(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"error": msg})
}

// decodeJSON 限制 body 体积并解码 JSON。
func decodeJSON(r *http.Request, v any) error {
	const maxBody = 1 << 20
	body := http.MaxBytesReader(nil, r.Body, maxBody)
	defer body.Close()
	dec := json.NewDecoder(body)
	if err := dec.Decode(v); err != nil {
		return err
	}
	if _, err := dec.Token(); err != io.EOF {
		return errors.New("unexpected trailing data")
	}
	return nil
}

// mustEncode 序列化 Envelope，失败返回空帧（不应发生）。
func mustEncode(env ws.Envelope) []byte {
	b, err := json.Marshal(env)
	if err != nil {
		return []byte(`{"type":"error","msg":"encode failed"}`)
	}
	return b
}

// mustRaw 序列化任意值为 json.RawMessage，失败返回 nil。
func mustRaw(v any) json.RawMessage {
	b, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	return b
}

// stablePairKey 由两个 peerID 按字典序拼接派生稳定配对键，保证双方调用结果一致。
// 与 signaling 内部 pairKeyFor 同语义；此副本避免依赖未导出函数。
func stablePairKey(a, b string) string {
	parts := []string{a, b}
	sort.Strings(parts)
	return parts[0] + "__" + parts[1]
}
