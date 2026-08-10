package gateway

import (
	"context"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// TestSyncWS_PushOnUpload 验证 PRD-v10 §4.1 推送链路：
// 客户端用 JWT 通过 /api/sync/ws 握手连入 → 触发一次 NotifyMediaChange(upload) →
// 客户端收到 {type:"media_changed", event:"upload"} 帧。
//
// 用 newSyncGateway 构造带真实 authSvc 的 server，token 经 query ?token= 传递
// （与 handleSyncWS 的浏览器兼容路径一致），用 coder/websocket.Dial 直连 httptest server。
func TestSyncWS_PushOnUpload(t *testing.T) {
	srv, token, uid, _ := newSyncGateway(t)
	if uid == "" {
		t.Fatalf("newSyncGateway returned empty uid")
	}

	hs := httptest.NewServer(srv.mux)
	defer hs.Close()

	wsURL := "ws" + strings.TrimPrefix(hs.URL, "http") + "/api/sync/ws?token=" + token
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	c, _, err := websocket.Dial(ctx, wsURL, nil)
	if err != nil {
		t.Fatalf("ws dial: %v", err)
	}
	defer c.CloseNow()
	c.SetReadLimit(syncWsReadLimit)

	// 给读循环一点时间完成 hub 注册（handleSyncWS 在 Accept 后 register）。
	time.Sleep(100 * time.Millisecond)

	// 触发一次推送（模拟 upload 成功后调 notifyMediaChange）。
	srv.notifyMediaChange(uid, syncEventUpload)

	// 读一帧，应在 2s 内收到 media_changed。ping 帧需跳过。
	// coder/websocket 用 ctx 控制读超时，每次读给 1.5s 窗口。
	deadline := time.Now().Add(2 * time.Second)
	var got syncWsMessage
	for time.Now().Before(deadline) {
		readCtx, readCancel := context.WithTimeout(ctx, 1500*time.Millisecond)
		_, data, rerr := c.Read(readCtx)
		readCancel()
		if rerr != nil {
			t.Fatalf("ws read: %v", rerr)
		}
		if err := json.Unmarshal(data, &got); err != nil {
			// 可能是 ping 帧，跳过继续读。
			continue
		}
		if got.Type == "ping" {
			continue
		}
		break
	}

	if got.Type != "media_changed" {
		t.Fatalf("expected type=media_changed, got %q (raw frame %+v)", got.Type, got)
	}
	if got.Event != syncEventUpload {
		t.Fatalf("expected event=upload, got %q", got.Event)
	}
	if got.Cursor <= 0 {
		t.Fatalf("expected positive cursor ms, got %d", got.Cursor)
	}
}

// TestSyncWS_RequiresToken 验证未带 token 的握手被 401 拒绝（不升级 WS）。
func TestSyncWS_RequiresToken(t *testing.T) {
	srv, _, _, _ := newSyncGateway(t)
	hs := httptest.NewServer(srv.mux)
	defer hs.Close()

	// 直接 HTTP GET（不带 token）应返回 401，而非升级 WS。
	req := httptest.NewRequest("GET", "/api/sync/ws", nil)
	rr := httptest.NewRecorder()
	srv.handleSyncWS(rr, req)
	if rr.Code != 401 {
		t.Fatalf("expected 401 for missing token, got %d", rr.Code)
	}
}

// TestSyncWS_HubRegisterUnregister 验证 hub 的注册/注销计数与多设备共存。
func TestSyncWS_HubRegisterUnregister(t *testing.T) {
	h := NewSyncHub()
	if h.Count() != 0 {
		t.Fatalf("expected 0, got %d", h.Count())
	}
	// 模拟两条同 uid 连接（多设备）。
	sc1 := newSyncClient(nil)
	sc2 := newSyncClient(nil)
	h.register("u1", sc1)
	h.register("u1", sc2)
	if h.Count() != 2 {
		t.Fatalf("expected 2, got %d", h.Count())
	}
	// 向不在线的 uid 推送应 no-op。
	h.NotifyMediaChange("nobody", syncEventUpload)
	if h.Count() != 2 {
		t.Fatalf("count changed after nobody push: %d", h.Count())
	}
	// 注销其中一条。
	h.unregister("u1", sc1)
	if h.Count() != 1 {
		t.Fatalf("expected 1 after unregister, got %d", h.Count())
	}
	// 注销最后一条后 user key 应被删除。
	h.unregister("u1", sc2)
	if h.Count() != 0 {
		t.Fatalf("expected 0 after all unregister, got %d", h.Count())
	}
}
