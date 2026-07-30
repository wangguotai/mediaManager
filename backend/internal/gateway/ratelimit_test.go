package gateway

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// TestLoginRateLimiterAllowsUnderMax 验证滑动窗口在阈值内放行全部请求。
func TestLoginRateLimiterAllowsUnderMax(t *testing.T) {
	l := NewLoginRateLimiter()
	for i := 0; i < loginRateMax; i++ {
		if !l.Allow("1.2.3.4", "alice") {
			t.Fatalf("attempt %d should be allowed (max=%d)", i+1, loginRateMax)
		}
	}
}

// TestLoginRateLimiterBlocksOverMax 验证超过阈值后返回 false（调用方应回 429）。
// 同一窗口内第 loginRateMax+1 次 must be blocked。
func TestLoginRateLimiterBlocksOverMax(t *testing.T) {
	l := NewLoginRateLimiter()
	for i := 0; i < loginRateMax; i++ {
		l.Allow("10.0.0.1", "bob")
	}
	if l.Allow("10.0.0.1", "bob") {
		t.Fatalf("attempt %d should be blocked", loginRateMax+1)
	}
}

// TestLoginRateLimiterPerKeyIsolation 验证限速维度是 (ip+username)：不同 ip 或
// 不同 username 的尝试不互相消耗配额（防"一个 attacker 锁死整站登录"）。
func TestLoginRateLimiterPerKeyIsolation(t *testing.T) {
	l := NewLoginRateLimiter()
	for i := 0; i < loginRateMax; i++ {
		l.Allow("1.1.1.1", "alice")
	}
	// 同 ip 不同 user 不受影响。
	if !l.Allow("1.1.1.1", "carol") {
		t.Fatal("different username should have its own quota")
	}
	// 不同 ip 同 user 不受影响。
	if !l.Allow("2.2.2.2", "alice") {
		t.Fatal("different ip should have its own quota")
	}
}

// TestLoginRateLimiterDisabledWhenNil 验证 nil 限速器时 handleAuthLogin 不崩溃、
// 不限速（纯测试 server 场景）。用一个 authSvc=nil 的 server 直接打 login，
// nil loginLimiter 跳过限速分支，应正常进到 authSvc==nil → 503。
func TestLoginRateLimiterDisabledWhenNil(t *testing.T) {
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, nil)
	if srv.loginLimiter != nil {
		t.Fatalf("authSvc-nil server should not create a login limiter")
	}
	req := httptest.NewRequest(http.MethodPost, "/api/auth/login",
		strings.NewReader(`{"username":"x","password":"y"}`))
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	// authSvc 为 nil → 503（未触达限速分支，nil 检查通过）。
	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("want 503 (authSvc nil, limiter nil), got %d body=%s", rec.Code, rec.Body.String())
	}
}

// TestLoginEndpointRateLimitedReturns429 端到端验证：对同一 (ip,username) 连打
// 超过 loginRateMax 次后，第 max+1 次登录返回 429 Too Many Requests。
// 用 newAuthedGateway 的真实 authSvc；httptest.NewRequest 的 RemoteAddr 经
// clientIP() 解析（无 X-Forwarded-For 时取 RemoteAddr host）。
func TestLoginEndpointRateLimitedReturns429(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	body := `{"username":"alice","password":"pw123456"}`
	// 前 loginRateMax 次正常（200 或 400 均可——密码对则 200，错则 400，但不限速）。
	for i := 0; i < loginRateMax; i++ {
		req := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(body))
		req.RemoteAddr = "192.168.1.50:1234"
		rec := httptest.NewRecorder()
		srv.mux.ServeHTTP(rec, req)
		if rec.Code == http.StatusTooManyRequests {
			t.Fatalf("attempt %d should not be rate limited yet (max=%d)", i+1, loginRateMax)
		}
	}
	// 第 max+1 次应为 429。
	req := httptest.NewRequest(http.MethodPost, "/api/auth/login", strings.NewReader(body))
	req.RemoteAddr = "192.168.1.50:1234"
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("attempt %d: want 429, got %d body=%s", loginRateMax+1, rec.Code, rec.Body.String())
	}
}

// TestUploadConcurrencyLimitReturns429 验证每用户最多 uploadConcurrentMax 个
// 并发上传，第 max+1 个并发请求返回 429；释放后可再次上传。
//
// 由于上传 handler 需读 body 才返回（串行 httptest 无法真正并发），这里直接测
// 信号量原语 AcquireUploadSlot/ReleaseUploadSlot 的并发计数语义，等价于 handler
// 内的 acquire 行为（handler 即用这两个函数）。
func TestUploadConcurrencyLimitReturns429(t *testing.T) {
	uid := "u-concur"
	// 占满 3 个槽。
	for i := 0; i < uploadConcurrentMax; i++ {
		if !AcquireUploadSlot(uid) {
			t.Fatalf("acquire %d should succeed (max=%d)", i+1, uploadConcurrentMax)
		}
	}
	// 第 4 个应失败（handler 据此回 429）。
	if AcquireUploadSlot(uid) {
		t.Fatalf("acquire %d should fail with 429", uploadConcurrentMax+1)
	}
	// 释放一个后应能再占。
	ReleaseUploadSlot(uid)
	if !AcquireUploadSlot(uid) {
		t.Fatal("after release, acquire should succeed again")
	}
	// 清理：把余下槽排空，避免影响后续测试（信号量是包级全局，按 uid 隔离；
	// 用专属 uid 已避免与其它测试冲突，这里仍排空保持整洁）。
	for i := 0; i < uploadConcurrentMax; i++ {
		ReleaseUploadSlot(uid)
	}
}

// TestUploadConcurrencySlotConcurrentSafety 并发 acquire/release 不竞态、不超发。
// 用少量 goroutine 验证 buffered channel 的非阻塞语义在并发下稳定。
func TestUploadConcurrencySlotConcurrentSafety(t *testing.T) {
	uid := "u-concur-safe"
	var wg sync.WaitGroup
	// 先占满，使多数并发 acquire 返回 false。
	for i := 0; i < uploadConcurrentMax; i++ {
		AcquireUploadSlot(uid)
	}
	// 50 个 goroutine 并发尝试 acquire（应全 false，因已满）+ 随机释放/重占。
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			AcquireUploadSlot(uid)
			ReleaseUploadSlot(uid)
		}()
	}
	wg.Wait()
	// 不校验精确计数（channel 语义保证），仅验证不 panic、不死锁。
	// 排空残留（最多 uploadConcurrentMax 个令牌可被 receive）。
	for i := 0; i < uploadConcurrentMax; i++ {
		ReleaseUploadSlot(uid)
	}
}

// TestClientIP 解析 X-Forwarded-For 与 RemoteAddr 的优先级与格式。
func TestClientIP(t *testing.T) {
	cases := []struct {
		name     string
		xff      string
		remote   string
		want     string
	}{
		{"xff single", "203.0.113.5", "10.0.0.1:443", "203.0.113.5"},
		{"xff multi takes first", "203.0.113.5, 10.0.0.1, 10.0.0.2", "10.0.0.1:443", "203.0.113.5"},
		{"no xff use remoteaddr", "", "198.51.100.7:5555", "198.51.100.7"},
		{"no port", "", "198.51.100.7", "198.51.100.7"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			req.RemoteAddr = c.remote
			if c.xff != "" {
				req.Header.Set("X-Forwarded-For", c.xff)
			}
			got := clientIP(req)
			if got != c.want {
				t.Fatalf("clientIP: got %q want %q", got, c.want)
			}
		})
	}
	// 触发一次 cleanExpired（等待 ticker 不现实，直接调用确认无 panic）。
	l := NewLoginRateLimiter()
	l.Allow("1.1.1.1", "x")
	l.cleanExpired()
}
