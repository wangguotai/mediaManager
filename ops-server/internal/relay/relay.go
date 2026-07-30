// Package relay 实现 TURN 式 TCP 中继：当两端直连失败时，由运营服务端中转字节流。
//
// 中继模型（简化 TURN）：
//   - 中继监听独立 TCP 端口 (RelayAddr)。任一端 (A) 主动连入并声明 pair_key，服务端为其分配
//     逻辑中继端点；对端 (B) 随后连入同一 pair_key，二者配对后开始双向字节转发。
//   - 任一端关闭或出错即结束会话，落账 relay_session (bytes_in/bytes_out/started/ended/reason)。
//   - 流量记账按"配对"粒度：一对连接合为一条 session，记录两端合计入站与转发出站字节数。
//
// 鉴权约定（骨架层）：
//   - 中继连接首帧为鉴权帧（明文 server_token + pair_key + role），服务端用 auth.AuthenticateServer
//     校验 server 身份，再据 pair_key 完成配对。生产应升级为 TLS + 更强握手，此处保留可扩展点。
//
// 设计取舍：
//   - 用 io.Copy 双向轉发，配 goroutine + WaitGroup 收尾；读取计数经 atomic 累加。
//   - pair 状态用 map + mutex 管理；同一 pair_key 同时只允许一对活跃会话。
package relay

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"media-manager/ops-server/internal/auth"
)

// RelayStore 中继流量记账所需的存储能力（见 storage.Store）。
type RelayStore interface {
	CreateRelaySession(ctx context.Context, rs RelaySession) error
	FinalizeRelaySession(ctx context.Context, id string, bytesIn, bytesOut int64, endedAt time.Time, reason string) error
	GetRelaySession(ctx context.Context, id string) (*RelaySession, error)
}

// RelaySession 中继会话记录（与 storage.RelaySession 对齐，避免上层反向依赖 storage）。
type RelaySession struct {
	ID          string    `json:"id"`
	ServerID    string    `json:"server_id"`
	PairKey     string    `json:"pair_key"`
	BytesIn     int64     `json:"bytes_in"`
	BytesOut    int64     `json:"bytes_out"`
	StartedAt   time.Time `json:"started_at"`
	EndedAt     time.Time `json:"ended_at"`
	CloseReason string    `json:"close_reason"`
}

// Server 提供中继/身份相关能力（auth 包别名，避免循环 import 由上层注入）。
type Server = auth.Server

// Authenticator 校验中继连接携带的 server_token。
type Authenticator interface {
	AuthenticateServer(ctx context.Context, plainToken string) (*Server, error)
}

// IDGen 生成会话 ID（默认由 Service 用 uuid）。
type IDGen func() string

// Service TCP 中继服务。
type Service struct {
	addr     string
	auther   Authenticator
	store    RelayStore
	idGen    IDGen
	nowFunc  func() time.Time

	mu       sync.Mutex
	pairs    map[string]*pairSlots // pairKey -> 待配对/已配对的两端槽位
	listener net.Listener
	closed   chan struct{}
	wg       sync.WaitGroup
}

// pairSlots 一对中继连接的配对槽。两端先后到达后配对转发。
type pairSlots struct {
	pairKey   string
	serverID  string // 以首先连入且鉴权通过的一方记录归属 server
	sessionID string // 会话 ID（首端进入时分配）
	startedAt time.Time

	mu        sync.Mutex
	a         *relayConn
	b         *relayConn
	paired    bool
	closed    atomic.Bool // 是否已关闭；跨 s.mu / slot.mu 读写，故用原子操作避免 data race
	finishErr error       // relay 期间首个方向的错误（io.Copy 非 EOF），用于区分结束原因
}

// relayConn 一端中继连接的运行态。
type relayConn struct {
	conn   net.Conn
	server *Server
	role   string // "a" / "b"
	bytes  int64  // 本端从对端读入并转发的字节数（io.Copy 内累加）
}

// New 构造中继服务。addr 为 TCP 监听地址；auther/store/idGen 须非 nil（idGen 可空走默认）。
func New(addr string, auther Authenticator, store RelayStore, idGen IDGen, nowFunc func() time.Time) (*Service, error) {
	if addr == "" {
		return nil, fmt.Errorf("relay: empty addr")
	}
	if auther == nil {
		return nil, fmt.Errorf("relay: nil authenticator")
	}
	if store == nil {
		return nil, fmt.Errorf("relay: nil store")
	}
	if idGen == nil {
		idGen = newUUID
	}
	if nowFunc == nil {
		nowFunc = time.Now
	}
	return &Service{
		addr:    addr,
		auther:  auther,
		store:   store,
		idGen:   idGen,
		nowFunc: nowFunc,
		pairs:   make(map[string]*pairSlots),
		closed:  make(chan struct{}),
	}, nil
}

// errSessionNotFound 按 sessionID 未找到活跃会话。
var errSessionNotFound = errors.New("relay: session not found or already ended")

// CloseSession 主动结束一个进行中的中继会话，供运营管理写操作（POST /admin/api/session/close）调用。
//
// 查找策略：遍历 s.pairs 按 sessionID 匹配（活跃会话数量有限，遍历开销可接受）。
// 找到后置 closed=true 并关闭两端连接，促使双向 io.Copy 退出并经 endSession 落账。
// 关闭原因由 relay 正常收尾路径写入（finishReason 把"被对端关闭"归为 peer_error）；
// 若需在 DB 中显式标注 "admin_close"，可在 CloseSession 后另调 store.FinalizeRelaySession，
// 此处保持轻量，不直接写 DB 以避免与 handleConn 的 endSession 双写竞争。
func (s *Service) CloseSession(sessionID string) error {
	s.mu.Lock()
	var found *pairSlots
	for _, slot := range s.pairs {
		slot.mu.Lock()
		if slot.sessionID == sessionID && !slot.closed.Load() {
			found = slot
			slot.mu.Unlock()
			break
		}
		slot.mu.Unlock()
	}
	s.mu.Unlock()
	if found == nil {
		return errSessionNotFound
	}

	found.mu.Lock()
	found.closed.Store(true)
	if found.a != nil && found.a.conn != nil {
		_ = found.a.conn.Close()
	}
	if found.b != nil && found.b.conn != nil {
		_ = found.b.conn.Close()
	}
	found.mu.Unlock()
	return nil
}

// ListenAndServe 开始接受中继连接，阻塞直到 Shutdown。
func (s *Service) ListenAndServe() error {
	ln, err := net.Listen("tcp", s.addr)
	if err != nil {
		return fmt.Errorf("relay: listen %s: %w", s.addr, err)
	}
	s.listener = ln
	log.Printf("relay: listening on %s", s.addr)
	for {
		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-s.closed:
				s.wg.Wait()
				return nil
			default:
				return fmt.Errorf("relay: accept: %w", err)
			}
		}
		s.wg.Add(1)
		go func() {
			defer s.wg.Done()
			s.handleConn(conn)
		}()
	}
}

// Shutdown 停止接受新连接并等待在途会话结束。
func (s *Service) Shutdown(ctx context.Context) error {
	select {
	case <-s.closed:
		return nil
	default:
	}
	close(s.closed)
	if s.listener != nil {
		_ = s.listener.Close()
	}
	done := make(chan struct{})
	go func() { s.wg.Wait(); close(done) }()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// authFrame 中继连接首帧：携带 server_token + pair_key + role 标识。
type authFrame struct {
	ServerToken string `json:"server_token"`
	PairKey     string `json:"pair_key"`
	Role        string `json:"role"` // "a" 或 "b"；同一 pair_key 下先到者为 a
}

// handleConn 处理单条中继连接：读鉴权帧 → 校验 → 配对 → 双向转发 → 落账。
func (s *Service) handleConn(conn net.Conn) {
	defer conn.Close()

	// 鉴权帧读取设较短超时，避免空连占资源。
	_ = conn.SetReadDeadline(s.nowFunc().Add(10 * time.Second))
	reader := bufio.NewReader(conn)
	var frame authFrame
	dec := json.NewDecoder(reader)
	if err := dec.Decode(&frame); err != nil {
		log.Printf("relay: read auth frame from %s: %v", conn.RemoteAddr(), err)
		writeJSON(conn, map[string]any{"status": "error", "msg": "invalid auth frame"})
		return
	}
	_ = conn.SetReadDeadline(time.Time{})

	srv, err := s.auther.AuthenticateServer(context.Background(), frame.ServerToken)
	if err != nil {
		writeJSON(conn, map[string]any{"status": "error", "msg": "auth failed"})
		return
	}
	if frame.PairKey == "" {
		writeJSON(conn, map[string]any{"status": "error", "msg": "missing pair_key"})
		return
	}

	// 进入配对流程。
	rc := &relayConn{conn: conn, server: srv, role: frame.Role}
	slot, isFirst, err := s.enterPair(frame.PairKey, srv, rc)
	if err != nil {
		writeJSON(conn, map[string]any{"status": "error", "msg": err.Error()})
		return
	}

	if isFirst {
		// 第一端：先落一条进行中的 session，回 ack 等对端。
		writeJSON(conn, map[string]any{"status": "waiting", "pair_key": frame.PairKey, "session_id": slot.sessionID})
		// 阻塞等待配对或关闭。超时/对端缺席/服务关闭分别映射不同结束原因（PRD §3.3）。
		if err := slot.waitPaired(s.closed); err != nil {
			reason := classifyWaitError(err)
			s.endSession(slot, rc, reason)
			return
		}
	} else {
		writeJSON(conn, map[string]any{"status": "paired", "pair_key": frame.PairKey, "session_id": slot.sessionID})
		slot.markPaired()
	}

	// 配对完成，双向转发。
	slot.relay(rc)
	reason := slot.finishReason(rc)
	s.endSession(slot, rc, reason)
}

// classifyWaitError 把 waitPaired 返回的错误映射为结束原因字符串（PRD §3.3）。
//   - errWaitTimeout → "timeout"（首端等待对端超时，对端未到达）
//   - errPeerAbsent  → "peer_absent"（对端提前关闭）
//   - service closed  → "service_closed"
func classifyWaitError(err error) string {
	if errors.Is(err, errWaitTimeout) {
		return "timeout"
	}
	if errors.Is(err, errPeerAbsent) {
		return "peer_absent"
	}
	return "service_closed"
}

// enterPair 把当前连接挂入对应 pairKey 的槽位。返回槽位、是否为第一端、错误。
// 同一 pairKey 同时只允许一对活跃：若已有配对完成的槽位且未关闭，新连入按"新的一端"叠加，
// 但为简化骨架，第二端到达即配对；第三端到达视为新一轮（旧 pair 已关闭则重建）。
func (s *Service) enterPair(pairKey string, srv *Server, rc *relayConn) (*pairSlots, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	slot, ok := s.pairs[pairKey]
	now := s.nowFunc()
	if !ok || slot.closed.Load() {
		// 首端：新建槽位 + 落账进行中 session。
		slot = &pairSlots{pairKey: pairKey, serverID: srv.ID, startedAt: now, sessionID: s.idGen()}
		s.pairs[pairKey] = slot
		rc.role = "a"
		slot.mu.Lock()
		slot.a = rc
		slot.mu.Unlock()
		// 落账（进行中）。
		_ = s.store.CreateRelaySession(context.Background(), RelaySession{
			ID:        slot.sessionID,
			ServerID:  srv.ID,
			PairKey:   pairKey,
			StartedAt: now,
		})
		return slot, true, nil
	}
	// 已存在槽位：若已配对/关闭则拒绝（骨架不允许多端复用）。
	// closed 字段为原子量，此处可直接读，无需再加 slot.mu（避免旧代码双重 Unlock 的隐患）。
	if slot.closed.Load() {
		// 旧槽已关，删除后重建（重试一次）。
		delete(s.pairs, pairKey)
		slot2 := &pairSlots{pairKey: pairKey, serverID: srv.ID, startedAt: now, sessionID: s.idGen()}
		s.pairs[pairKey] = slot2
		rc.role = "a"
		slot2.mu.Lock()
		slot2.a = rc
		slot2.mu.Unlock()
		_ = s.store.CreateRelaySession(context.Background(), RelaySession{
			ID: slot2.sessionID, ServerID: srv.ID, PairKey: pairKey, StartedAt: now,
		})
		return slot2, true, nil
	}
	slot.mu.Lock()
	if slot.paired {
		slot.mu.Unlock()
		return nil, false, fmt.Errorf("pair %s already active", pairKey)
	}
	if slot.b != nil {
		slot.mu.Unlock()
		return nil, false, fmt.Errorf("pair %s already has second peer", pairKey)
	}
	slot.b = rc
	rc.role = "b"
	slot.mu.Unlock()
	return slot, false, nil
}

// waitPaired 阻塞等待对端到达或服务关闭。默认 30s 超时（对端未到即 peer_absent）。
// 超时由 waitPairTimeout 控制（PRD §3.3：首端等待加超时默认 30s）。
func (p *pairSlots) waitPaired(svcClosed <-chan struct{}) error {
	// 轮询配对态；配对由第二端 markPaired 触发。
	t := time.NewTicker(50 * time.Millisecond)
	defer t.Stop()
	deadline := time.NewTimer(waitPairTimeout)
	defer deadline.Stop()
	for {
		p.mu.Lock()
		paired := p.paired
		closed := p.closed.Load()
		p.mu.Unlock()
		if paired {
			return nil
		}
		if closed {
			return errPeerAbsent
		}
		select {
		case <-svcClosed:
			return errors.New("service closed")
		case <-deadline.C:
			// 首端等待超时：对端未到达，按 peer_absent 结束。
			return errWaitTimeout
		case <-t.C:
		}
	}
}

// waitPairTimeout 是首端等待对端配对的默认超时（PRD §3.3：30s）。
const waitPairTimeout = 30 * time.Second

// errWaitTimeout 首端等待对端超时。
var errWaitTimeout = errors.New("pair wait timeout: peer absent")

// errPeerAbsent 配对槽已被对端关闭标记（对端提前退出）。
var errPeerAbsent = errors.New("peer absent")

// markPaired 由第二端调用，置 paired=true 唤醒第一端。
func (p *pairSlots) markPaired() {
	p.mu.Lock()
	p.paired = true
	p.mu.Unlock()
}

// relay 在本端与对端之间双向 copy 字节，直到任一方向结束。
// 首个非 EOF 的 copy 错误记入 finishErr，供 finishReason 区分正常结束与异常断开。
func (p *pairSlots) relay(rc *relayConn) {
	p.mu.Lock()
	other := p.peer(rc)
	p.mu.Unlock()
	if other == nil {
		// 配对后对端已不在（异常），记为 peer_absent。
		p.mu.Lock()
		if p.finishErr == nil {
			p.finishErr = errPeerAbsent
		}
		p.mu.Unlock()
		return
	}
	// 双向 copy：本端读→对端写，对端读→本端写。
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		n, err := io.Copy(other.conn, rc.conn)
		atomic.AddInt64(&rc.bytes, n)
		p.recordFinishErr(err)
		// 任一方向完成即半关闭对端写，促使对端 io.Copy 退出。
		_ = other.conn.(closeWriter).CloseWrite()
	}()
	go func() {
		defer wg.Done()
		n, err := io.Copy(rc.conn, other.conn)
		atomic.AddInt64(&other.bytes, n)
		p.recordFinishErr(err)
		_ = rc.conn.(closeWriter).CloseWrite()
	}()
	wg.Wait()
}

// recordFinishErr 记录首个非 nil、非 EOF 的 io.Copy 错误（仅第一个生效）。
func (p *pairSlots) recordFinishErr(err error) {
	if err == nil || errors.Is(err, io.EOF) {
		return
	}
	p.mu.Lock()
	if p.finishErr == nil {
		p.finishErr = err
	}
	p.mu.Unlock()
}

// peer 返回对端连接（需持 p.mu）。
func (p *pairSlots) peer(rc *relayConn) *relayConn {
	if rc.role == "a" {
		return p.b
	}
	return p.a
}

// finishReason 汇总关闭原因，区分 normal / peer_error / peer_absent。
//   - finishErr 为 nil：两端均正常 EOF → "normal"。
//   - finishErr 为 errPeerAbsent：对端在转发前已离开 → "peer_absent"。
//   - 其余（网络中断/连接重置等）：→ "peer_error"。
func (p *pairSlots) finishReason(rc *relayConn) string {
	p.mu.Lock()
	err := p.finishErr
	p.mu.Unlock()
	if err == nil {
		return "normal"
	}
	if errors.Is(err, errPeerAbsent) {
		return "peer_absent"
	}
	return "peer_error"
}

// endSession 结束会话：累加两端字节，落账 finalize。
func (s *Service) endSession(slot *pairSlots, rc *relayConn, reason string) {
	s.mu.Lock()
	slot.mu.Lock()
	alreadyClosed := slot.closed.Load()
	slot.closed.Store(true)
	aBytes := int64(0)
	bBytes := int64(0)
	if slot.a != nil {
		aBytes = atomic.LoadInt64(&slot.a.bytes)
	}
	if slot.b != nil {
		bBytes = atomic.LoadInt64(&slot.b.bytes)
	}
	_ = rc
	slot.mu.Unlock()
	// 仅由第一端(a)或首先到达 finalize 的一方落账一次。
	if !alreadyClosed {
		_ = s.store.FinalizeRelaySession(context.Background(),
			slot.sessionID, aBytes, bBytes, s.nowFunc(), reason)
	}
	delete(s.pairs, slot.pairKey)
	s.mu.Unlock()
}

// closeWriter 抽象 TCP 连接的半关闭写能力（*net.TCPConn 满足）。
type closeWriter interface {
	CloseWrite() error
}

// writeJSON 向中继连接写一行 JSON（鉴权 ack/error）。
func writeJSON(conn net.Conn, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		return
	}
	b = append(b, '\n')
	_, _ = conn.Write(b)
}

// newUUID 生成 v4 UUID（与 auth 包独立实现，避免循环依赖）。
func newUUID() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		// rand.Read 极少失败；失败时以时间兜底，保证不 panic。
		t := time.Now().UnixNano()
		for i := range b {
			b[i] = byte(t >> uint(i*8))
		}
	}
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}
