// Package opsclient 实现媒体后端（受管存储服务端）到运营服务端的注册上报与信令客户端。
//
// 路径 B（PRD §3.2）：客户端经运营服务端中继连到自己的存储服务端。为此存储服务端
// 启动时需向 ops-server 注册，拿到 server_id/server_token，随后维持 WS 长连 +
// 定期心跳，使其在运营服务端的设备发现表中保持"在线"。
//
// 本包封装该职责：
//   - Register：POST /op/server/register 拿 {server_id, server_token}（首次注册）。
//   - Run：建立 WS /op/server/ws 长连（Bearer server_token），收发信令帧 + 周期 ping 保活。
//   - 失败重连：WS 断开后退避重连，不阻断主进程。
//   - 优雅停机：Run 在 ctx 取消时关闭连接返回。
//
// 设计取舍：
//   - 不持久化 server_token（与 ops auth 语义一致：明文 token 仅注册时一次可得）。
//     本机联调下进程重启即重新注册（旧 server 记录仍存在但 token hash 不同）；生产应持久化。
//   - 信令候选上报（P2P 优先）留为方法 ReportCandidates，V5 不实测穿透，候选仅记录于 ops。
package opsclient

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/coder/websocket"
)

// Client 是媒体后端到运营服务端的注册+信令客户端。零值不可用，必须经 New 构造。
type Client struct {
	baseURL string // 运营服务端 HTTP 基址，如 http://localhost:8090
	name    string // 受管服务端展示名（可为空）
	http    *http.Client
}

// Credentials 注册返回的凭据。
type Credentials struct {
	ServerID    string    `json:"server_id"`
	ServerToken string    `json:"server_token"`
	Name        string    `json:"name"`
	CreatedAt   time.Time `json:"created_at"`
}

// New 构造客户端。baseURL 为 ops-server HTTP 地址（不含尾斜杠）。
func New(baseURL, name string) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		name:    name,
		http:    &http.Client{Timeout: 15 * time.Second},
	}
}

// Register 调用 POST /op/server/register 拿 {server_id, server_token}。
// 失败返回错误；调用方应决定是否重试或降级（不阻断主进程）。
func (c *Client) Register(ctx context.Context) (Credentials, error) {
	body, _ := json.Marshal(map[string]string{"name": c.name})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/op/server/register", bytes.NewReader(body))
	if err != nil {
		return Credentials{}, fmt.Errorf("opsclient: build register request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.http.Do(req)
	if err != nil {
		return Credentials{}, fmt.Errorf("opsclient: register: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return Credentials{}, fmt.Errorf("opsclient: register status %d", resp.StatusCode)
	}
	var creds Credentials
	if err := json.NewDecoder(resp.Body).Decode(&creds); err != nil {
		return Credentials{}, fmt.Errorf("opsclient: decode register response: %w", err)
	}
	if creds.ServerID == "" || creds.ServerToken == "" {
		return Credentials{}, errors.New("opsclient: register response missing server_id/server_token")
	}
	return creds, nil
}

// Run 维持到 ops-server 的 WS 长连（/op/server/ws，Bearer server_token）。
//   - 成功升级后周期发 ping 保活（默认 25s），收信令帧即时打印（V5 仅记录）。
//   - 断开后退避重连（1s→2s→5s 封顶），不抛错，循环直到 ctx 取消。
//   - ctx 取消时关闭当前连接并返回 nil。
//
// 该方法阻塞，调用方应在独立 goroutine 中运行。
func (c *Client) Run(ctx context.Context, creds Credentials) error {
	backoff := initialBackoff
	for {
		if err := ctx.Err(); err != nil {
			return nil
		}
		err := c.runOnce(ctx, creds)
		if err == nil {
			// runOnce 仅在 ctx 取消时返回 nil。
			return nil
		}
		log.Printf("opsclient: ws disconnected: %v (reconnect in %s)", err, backoff)
		select {
		case <-time.After(backoff):
		case <-ctx.Done():
			return nil
		}
		backoff *= 2
		if backoff > maxBackoff {
			backoff = maxBackoff
		}
	}
}

// runOnce 建立一次 WS 连接并 pump，直到断开或 ctx 取消（返回 nil 表示 ctx 取消）。
func (c *Client) runOnce(ctx context.Context, creds Credentials) error {
	url := c.baseURL + "/op/server/ws"
	dialCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	conn, _, err := websocket.Dial(dialCtx, url, &websocket.DialOptions{
		HTTPHeader: http.Header{
			"Authorization": []string{"Bearer " + creds.ServerToken},
		},
	})
	if err != nil {
		return fmt.Errorf("ws dial: %w", err)
	}
	defer conn.CloseNow()
	conn.SetReadLimit(64 * 1024)
	log.Printf("opsclient: connected to ops-server ws (server_id=%s)", creds.ServerID)

	// 读循环 + ping ticker。任一退出即结束本次连接。
	ping := time.NewTicker(pingInterval)
	defer ping.Stop()
	readErrCh := make(chan error, 1)
	go func() {
		readErrCh <- c.readLoop(ctx, conn, creds)
	}()

	for {
		select {
		case err := <-readErrCh:
			return err
		case <-ping.C:
			if err := c.writePing(ctx, conn); err != nil {
				return fmt.Errorf("ping: %w", err)
			}
		case <-ctx.Done():
			_ = conn.Close(websocket.StatusNormalClosure, "shutdown")
			return nil
		}
	}
}

// readLoop 读 WS 帧，解 Envelope 记录信令（V5 仅日志，不触发本端动作）。
func (c *Client) readLoop(ctx context.Context, conn *websocket.Conn, creds Credentials) error {
	for {
		_, data, err := conn.Read(ctx)
		if err != nil {
			return fmt.Errorf("read: %w", err)
		}
		var env map[string]any
		if err := json.Unmarshal(data, &env); err != nil {
			log.Printf("opsclient: non-json frame: %s", string(data))
			continue
		}
		// V5：信令帧仅记录。候选/介绍/relay_request 等后续可在此驱动本端中继接入。
		log.Printf("opsclient: signal frame: %v", env)
	}
}

// writePing 发一帧 {type:"ping"} 保活，并对端应回 pong。
func (c *Client) writePing(ctx context.Context, conn *websocket.Conn) error {
	frame, _ := json.Marshal(map[string]string{"type": "ping"})
	return conn.Write(ctx, websocket.MessageText, frame)
}

// ReportCandidates 向 ops-server 的 WS 上报本端候选地址（P2P 优先，V5 仅记录）。
// 需先经 Run 建立连接；本机联调下为可选辅助，暂不在此进程内调用。
//
// 该函数保留为占位接口，便于后续在发现本端可达地址后调用。
func (c *Client) ReportCandidates(_ context.Context, _ Credentials, pairKey string, cands []Candidate) error {
	// V5：候选仅记录，不实测穿透。具体上报将在 WS 连接内联实现，此处仅留方法签名。
	_ = pairKey
	_ = cands
	return nil
}

// Candidate 一条候选地址（与 ops signaling.Candidate 对齐的子集）。
type Candidate struct {
	Type string `json:"type"` // host / srflx / prflx / relay
	Addr string `json:"addr"`
}

// InitialBackoff/MaxBackoff 重连退避参数。
const (
	initialBackoff = 1 * time.Second
	maxBackoff     = 5 * time.Second
	pingInterval   = 25 * time.Second
)
