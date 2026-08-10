// Package gateway: sync_ws.go 实现 PRD-v10 §4.1 WebSocket 实时同步推送通道。
//
// 设计要点：
//   - SyncHub 维护 user_id → []*websocket.Conn 的在线注册表（同用户多设备各自一条连接）。
//   - handleSyncWS 做 JWT 认证（query ?token= 或 Authorization 头）、websocket.Accept、
//     注册到 hub，随后进入读循环（仅消费/忽略客户端帧，主要靠服务端心跳 ping 保活）。
//   - NotifyMediaChange(uid, event) 向该 uid 的全部在线连接下发
//     {type:"media_changed", event, cursor: <now_ms>} 文本帧，客户端收到后调
//     /api/sync/changes 增量续拉。
//   - 路由 /api/sync/ws 在 authMiddleware 豁免名单内（WS 握手无法在浏览器侧带自定义
//     Authorization 头，故改用 query ?token= 并在此 handler 内手动校验 JWT）。
//
// 复用 coder/websocket v1.8.15（ops-server 同款）；与 ops-server/internal/ws/hub.go
// 的 Hub 抽象不同：此处需求更简单（单向服务端→客户端推送，无需信令路由/peer 配对），
// 故直接用 *websocket.Conn 而非 ws.Client 包装。
package gateway

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// syncWsReadLimit 单帧最大字节数。同步推送帧很小，限 32KiB 防滥用。
const syncWsReadLimit = 32 * 1024

// syncWsPingInterval 服务端→客户端 ping 保活间隔（< coder/websocket 默认 30s 关闭超时）。
const syncWsPingInterval = 25 * time.Second

// syncWsWriteTimeout 单帧写入超时，避免慢客户端拖住 hub 写循环。
const syncWsWriteTimeout = 5 * time.Second

// syncWsEvent 描述触发推送的媒体变更类型，下发到客户端供其决定是否立即续拉。
type syncWsEvent string

const (
	syncEventUpload  syncWsEvent = "upload"  // 新增媒体（upload/秒传/编辑保存）
	syncEventDelete  syncWsEvent = "delete"  // 软删除（移入回收站）
	syncEventRestore syncWsEvent = "restore" // 从回收站恢复
)

// syncWsMessage 是服务端→客户端的推送帧。cursor 为服务端当前毫秒时间戳，
// 客户端可选用作兜底 since（真正续拉仍以其本地持久化 cursor 为准，确保不重不漏）。
type syncWsMessage struct {
	Type   string      `json:"type"`            // 固定 "media_changed"
	Event  syncWsEvent `json:"event"`           // upload / delete / restore
	Cursor int64       `json:"cursor"`          // 服务端推送时刻毫秒
	At     int64       `json:"at,omitempty"`    // 推送时刻毫秒（兼容字段，与 cursor 同义）
}

// SyncHub 维护 user_id → 在线 WebSocket 连接集合，支持按 user_id 群发推送。
//
// 与 ops-server 的 ws.Hub 区别：此处只做单向服务端→客户端通知，无 peer 路由，
// 故无需 Envelope/From/To 等信令字段。每条连接直接持有 *websocket.Conn，写操作
// 经 writeMu 串行化（coder/websocket.Conn 并发写需调用方加锁）。
type SyncHub struct {
	mu      sync.Mutex
	clients map[string][]*syncClient // user_id → 该用户的全部在线连接（多设备）
}

// syncClient 是一条已注册的同步 WS 连接。
type syncClient struct {
	conn     *websocket.Conn
	writeMu  sync.Mutex // 串行化向该 conn 的写入（coder/websocket 并发写需加锁）
	closed   chan struct{}
	closeOnce sync.Once
}

func newSyncClient(c *websocket.Conn) *syncClient {
	return &syncClient{
		conn:   c,
		closed: make(chan struct{}),
	}
}

// send 向该连接写一帧文本消息。连接已关闭或写超时返回 false（调用方应将其从 hub 移除）。
func (sc *syncClient) send(ctx context.Context, payload []byte) bool {
	select {
	case <-sc.closed:
		return false
	default:
	}
	sc.writeMu.Lock()
	defer sc.writeMu.Unlock()
	ctx2, cancel := context.WithTimeout(ctx, syncWsWriteTimeout)
	defer cancel()
	if err := sc.conn.Write(ctx2, websocket.MessageText, payload); err != nil {
		return false
	}
	return true
}

// close 标记连接断开并关闭底层 conn（幂等）。
func (sc *syncClient) close() {
	sc.closeOnce.Do(func() {
		close(sc.closed)
		_ = sc.conn.CloseNow()
	})
}

// NewSyncHub 构造空 hub。由 NewServer 调用。
func NewSyncHub() *SyncHub {
	return &SyncHub{clients: make(map[string][]*syncClient)}
}

// register 把一条连接加入 user_id 的在线集合（允许同 user 多条，对应多设备）。
func (h *SyncHub) register(uid string, sc *syncClient) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.clients[uid] = append(h.clients[uid], sc)
}

// unregister 移除指定连接（幂等）。按指针比对，仅移除该实例。
func (h *SyncHub) unregister(uid string, sc *syncClient) {
	h.mu.Lock()
	defer h.mu.Unlock()
	list := h.clients[uid]
	for i, c := range list {
		if c == sc {
			h.clients[uid] = append(list[:i], list[i+1:]...)
			break
		}
	}
	if len(h.clients[uid]) == 0 {
		delete(h.clients, uid)
	}
}

// NotifyMediaChange 向 uid 的全部在线连接推送 media_changed 帧。
// 慢/已断连接静默丢弃并从 hub 移除，不阻塞其他连接的推送。
// uid 为空或无在线连接时为 no-op（best-effort，调用方不感知结果）。
func (h *SyncHub) NotifyMediaChange(uid string, event syncWsEvent) {
	if h == nil || uid == "" {
		return
	}
	now := time.Now().UnixMilli()
	payload, _ := json.Marshal(syncWsMessage{
		Type:   "media_changed",
		Event:  event,
		Cursor: now,
		At:     now,
	})
	h.mu.Lock()
	list := append([]*syncClient(nil), h.clients[uid]...)
	h.mu.Unlock()
	if len(list) == 0 {
		return
	}
	ctx := context.Background()
	var dead []*syncClient
	for _, sc := range list {
		if !sc.send(ctx, payload) {
			dead = append(dead, sc)
		}
	}
	if len(dead) > 0 {
		h.mu.Lock()
		cur := h.clients[uid]
		for _, d := range dead {
			for i := len(cur) - 1; i >= 0; i-- {
				if cur[i] == d {
					cur = append(cur[:i], cur[i+1:]...)
					break
				}
			}
			d.close()
		}
		h.clients[uid] = cur
		if len(h.clients[uid]) == 0 {
			delete(h.clients, uid)
		}
		h.mu.Unlock()
	}
}

// Count 返回当前在线连接总数（调试/可观测用）。
func (h *SyncHub) Count() int {
	if h == nil {
		return 0
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	n := 0
	for _, list := range h.clients {
		n += len(list)
	}
	return n
}

// notifyMediaChange 是 handleMediaUpload/Delete/Restore 成功后的便捷推送封装。
// uid 为空或 hub 为 nil 时 no-op；推送为 best-effort，不阻断调用方响应。
func (s *Server) notifyMediaChange(uid string, event syncWsEvent) {
	if s.syncHub == nil || uid == "" {
		return
	}
	s.syncHub.NotifyMediaChange(uid, event)
}

// handleSyncWS 处理 GET /api/sync/ws —— JWT 认证 + WS 升级 + 注册到 hub + 读循环。
//
// 认证取 token 顺序：
//  1. query ?token= （浏览器原生 WebSocket 无法带自定义 Authorization 头，故支持 query）
//  2. Authorization: Bearer *** 头（非浏览器客户端/调试用）
//
// 路由在 authMiddleware 豁免名单内（见 server.go），故此处手动校验 JWT；
// authSvc 为 nil（未配置认证的开发/测试场景）时放行，uid 取 query ?uid= 或 "anon"。
func (s *Server) handleSyncWS(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	// 取 token：query 优先，其次 Authorization 头。
	tokenStr := strings.TrimSpace(r.URL.Query().Get("token"))
	if tokenStr == "" {
		if ah := r.Header.Get("Authorization"); strings.HasPrefix(ah, "Bearer ") {
			tokenStr = strings.TrimSpace(strings.TrimPrefix(ah, "Bearer "))
		}
	}
	var uid string
	if s.authSvc != nil {
		if tokenStr == "" {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "missing token"})
			return
		}
		uid2, err := s.authSvc.ParseToken(tokenStr)
		if err != nil {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid or expired token"})
			return
		}
		uid = uid2
	} else {
		// 未配置认证（开发/测试）：用 query ?uid= 作占位，缺省 "anon"。
		uid = strings.TrimSpace(r.URL.Query().Get("uid"))
		if uid == "" {
			uid = "anon"
		}
	}

	c, err := websocket.Accept(w, r, &websocket.AcceptOptions{
		// 与 ops-server 一致：本机/内网联调放宽 origin 校验。
		InsecureSkipVerify: true,
	})
	if err != nil {
		// Accept 失败时连接已被库处理，仅日志。
		slog.Info("sync_ws: accept failed", "uid", uid, "err", err)
		return
	}
	c.SetReadLimit(syncWsReadLimit)
	defer c.CloseNow()

	sc := newSyncClient(c)
	s.syncHub.register(uid, sc)
	defer s.syncHub.unregister(uid, sc)

	slog.Info("sync_ws: connected", "uid", uid, "online", s.syncHub.Count())

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	// 写循环：服务端→客户端 ping 保活（无下行推送时也维持连接，防中间设备超时断开）。
	go func() {
		defer cancel()
		ticker := time.NewTicker(syncWsPingInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if !sc.send(ctx, []byte(`{"type":"ping"}`)) {
					return
				}
			case <-sc.closed:
				return
			case <-ctx.Done():
				return
			}
		}
	}()

	// 读循环：消费客户端帧（客户端可发 ping；此处忽略内容，仅维持连接 & 感知断开）。
	// coder/websocket 的 Read 在连接断开时返回 error，据此退出。
	for {
		_, _, rerr := c.Read(ctx)
		if rerr != nil {
			break
		}
	}
}
