// Package ws 实现 WebSocket 长连 Hub：客户端与受管服务端各自连到运营服务端，
// 维持在线通道并实时交换信令消息。
//
// 连接模型：
//   - 每条 WS 连接绑定一个 peerID（通常 "<server_id>:<device_id>" 或 server 自身 id）。
//   - 服务端连 (role=server) 与客户端连 (role=client) 都接入同一 Hub。
//   - 上行信令消息（候选地址、SDP offer/answer、relay 请求等）按"目标 peerID"路由到对端连接。
//   - 设备在线态随 WS 连接的开启/关闭自动维护（连接=open→discovery 上线；关闭→下线）。
//
// 消息协议（JSON 文本帧）：
//   { "type": "candidates", "to": "<peerID>", "pair_key": "...", "candidates": [...] }
//   { "type": "sdp",         "to": "<peerID>", "pair_key": "...", "sdp": {...} }
//   { "type": "relay_request","to": "<peerID>", "pair_key": "...", "reason": "p2p_failed" }
//   { "type": "ping" } / { "type": "pong" }
//   服务端收到目标为对端的消息即转发；目标缺失时缓冲短时或回 error。
//
// 设计取舍：
//   - Hub 用 map[peerID]*Client + 互斥锁；写并发量低（信令），锁足够。
//   - 用 coder/websocket（纯 Go，免 CGO）作为底层帧实现，降低自研负担。
package ws

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"sync"
	"time"
)

// Role 标识 WS 连接方角色。
type Role string

const (
	RoleServer Role = "server" // 受管服务端连入
	RoleClient Role = "client" // 客户端连入
)

// Envelope 是 WS 文本帧统一信封。
type Envelope struct {
	Type    string          `json:"type"`              // candidates / sdp / relay_request / ping / pong / error / introduced
	To      string          `json:"to,omitempty"`      // 目标 peerID（转发型消息必填）
	From    string          `json:"from,omitempty"`    // 由 Hub 注入发送方 peerID
	PairKey string          `json:"pair_key,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"` // 任意子结构（candidates/sdp 等）
	Reason  string          `json:"reason,omitempty"`  // relay_request / error 的说明
	Msg     string          `json:"msg,omitempty"`     // error 文本
}

// Client 是一条已注册的 WS 连接在 Hub 中的抽象。
// 实际帧读写由 gateway 层的 handler 持有的 *websocket.Conn 完成；
// Client 仅暴露 Send 通道供 Hub 投递下行消息。
type Client struct {
	PeerID   string
	Role     Role
	ServerID string
	send     chan Envelope
	closeOnce sync.Once
	closed   chan struct{}
}

// NewClient 构造一个 Client。sendBuf 控制下行缓冲深度，<=0 用默认 16。
func NewClient(peerID string, role Role, serverID string, sendBuf int) *Client {
	if sendBuf <= 0 {
		sendBuf = 16
	}
	return &Client{
		PeerID:   peerID,
		Role:     role,
		ServerID: serverID,
		send:     make(chan Envelope, sendBuf),
		closed:   make(chan struct{}),
	}
}

// Send 向该连接投递一条下行消息。若客户端已断开或缓冲满，返回 false（调用方应放弃投递）。
func (c *Client) Send(msg Envelope) bool {
	select {
	case <-c.closed:
		return false
	default:
	}
	select {
	case c.send <- msg:
		return true
	case <-c.closed:
		return false
	default:
		// 缓冲满：为保护 Hub 不阻塞，丢弃并返回 false。信令丢失由上层重试/超时兜底。
		return false
	}
}

// Outbox 返回下行消息的读取通道，供 gateway handler 的写循环消费。
func (c *Client) Outbox() <-chan Envelope { return c.send }

// Close 标记客户端断开，关闭 send 通道（幂等）。
func (c *Client) Close() {
	c.closeOnce.Do(func() {
		close(c.closed)
		// 不直接 close(c.send)：写循环可能在 select 中等待；改由读端感知 closed 后退出。
		// 为避免写循环阻塞在满缓冲，已用 closed 兜底，故此处仅标记。
	})
}

// Done 返回断开信号通道。
func (c *Client) Done() <-chan struct{} { return c.closed }

// Hub 维护 peerID → Client 的在线注册表，并承担信令转发。
type Hub struct {
	mu      sync.RWMutex
	clients map[string]*Client

	// 依赖注入：连接上线/下线时回调发现服务维护在线态。
	onOnline  func(peerID, serverID, deviceID string, role Role)
	onOffline func(peerID, serverID, deviceID string, role Role)

	// 信令事件回调：把候选/中继请求等交给上层（signaler/relay）处理。
	onSignal func(from *Client, env Envelope)
}

// NewHub 构造 Hub。回调均可为 nil（裸转发模式）。
func NewHub(
	onOnline, onOffline func(peerID, serverID, deviceID string, role Role),
	onSignal func(from *Client, env Envelope),
) *Hub {
	return &Hub{
		clients:   make(map[string]*Client),
		onOnline:  onOnline,
		onOffline: onOffline,
		onSignal:  onSignal,
	}
}

// Register 注册一条新连接，返回其 Client。peerID 冲突时拒绝（同一 peer 不允许重复连入）。
func (h *Hub) Register(c *Client) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, exists := h.clients[c.PeerID]; exists {
		return fmt.Errorf("peer %s already connected", c.PeerID)
	}
	h.clients[c.PeerID] = c
	if h.onOnline != nil {
		sid, did := splitPeer(c.PeerID, c.Role)
		h.onOnline(c.PeerID, sid, did, c.Role)
	}
	return nil
}

// Unregister 注销连接并触发下线回调（幂等）。
func (h *Hub) Unregister(c *Client) {
	h.mu.Lock()
	cur, ok := h.clients[c.PeerID]
	if ok && cur == c {
		delete(h.clients, c.PeerID)
	}
	h.mu.Unlock()
	c.Close()
	if h.onOffline != nil {
		sid, did := splitPeer(c.PeerID, c.Role)
		h.onOffline(c.PeerID, sid, did, c.Role)
	}
}

// Lookup 查询 peerID 是否在线。
func (h *Hub) Lookup(peerID string) (*Client, bool) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	c, ok := h.clients[peerID]
	return c, ok
}

// Count 返回当前在线连接数。
func (h *Hub) Count() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.clients)
}

// Route 路由一条上行消息：注入 From 后，按 To 转发到目标客户端；同时触发 onSignal 回调。
//   - To 为空或 type 为 ping/pong/error：不转发，仅交给 onSignal（控制帧由上层处理）。
//   - 目标不在线：回一条 error 帧给发送方，便于其回退 relay。
func (h *Hub) Route(from *Client, env Envelope) {
	env.From = from.PeerID

	// 先交给信令回调（候选地址登记、relay 请求等业务逻辑）。
	if h.onSignal != nil {
		// 注意：onSignal 内可能触发新的下行投递（如介绍完成后给双方下发 introduced）。
		// 为避免持锁调用业务，先在锁外执行。
		h.onSignal(from, env)
	}

	// 控制帧不参与对端转发。
	switch env.Type {
	case "ping":
		// 直接给发送方回 pong（心跳保活）。
		_ = from.Send(Envelope{Type: "pong", To: from.PeerID})
		return
	case "pong", "error", "introduced":
		return
	}

	if env.To == "" {
		// 广播型（暂不支持）或无目标：回错。
		_ = from.Send(Envelope{Type: "error", Msg: "missing target 'to' peerID"})
		return
	}
	target, ok := h.Lookup(env.To)
	if !ok {
		_ = from.Send(Envelope{Type: "error", To: env.To, Msg: fmt.Sprintf("peer %s not online", env.To)})
		return
	}
	if !target.Send(env) {
		_ = from.Send(Envelope{Type: "error", To: env.To, Msg: "peer inbox full, try relay"})
	}
}

// BroadcastServer 向所有受管服务端连接广播（如运维侧全局通知）。当前用于健康/调试。
func (h *Hub) BroadcastServer(env Envelope) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, c := range h.clients {
		if c.Role == RoleServer {
			_ = c.Send(env)
		}
	}
}

// splitPeer 由 peerID 拆出 server_id/device_id。
//   - RoleServer 的 peerID 约定为 "<server_id>:_self"（服务端自身连入，无具体 device）。
//   - RoleClient / 一般 peerID 形如 "<server_id>:<device_id>"。
func splitPeer(peerID string, role Role) (serverID, deviceID string) {
	if role == RoleServer {
		// server 自身连入：peerID 即 "<server_id>:_self" 或纯 server_id。
		sid, did := parseColon(peerID)
		if did == "_self" || did == "" {
			return sid, ""
		}
		return sid, did
	}
	return parseColon(peerID)
}

func parseColon(s string) (string, string) {
	for i := 0; i < len(s); i++ {
		if s[i] == ':' {
			return s[:i], s[i+1:]
		}
	}
	return s, ""
}

// ErrAlreadyClosed 连接已断开时投递失败的标志。
var ErrAlreadyClosed = errors.New("ws: client already closed")

// reapLoop 周期清理逻辑（当前 Hub 无过期项，预留 hooks）。
func (h *Hub) reapLoop(ctx context.Context, interval time.Duration) {
	if interval <= 0 {
		return
	}
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			// 预留：未来可在此清理僵尸 send 缓冲。
			if h.Count() == 0 {
				continue
			}
		}
	}
}

// noteUnused 抑制未用途警告（reapLoop 暂为预留）。
var _ = log.Printf
