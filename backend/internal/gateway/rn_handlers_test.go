package gateway

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"media-manager/backend/internal/service"
)

// newRNGateway 构造一个带 dataDir 注入的 gateway（无认证），用于 RN bundle 与
// promotions 端点测试。dataDir 下预置 activity-bundle 示例与 promotions.json。
func newRNGateway(t *testing.T) (*Server, string) {
	t.Helper()
	dataRoot := t.TempDir()
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, nil)
	srv.SetDataDir(dataRoot)
	// 预置 activity-bundle 示例。
	if err := EnsureRNBundleSeed(dataRoot); err != nil {
		t.Fatalf("EnsureRNBundleSeed: %v", err)
	}
	// 预置 promotions.json demo 数据。
	if err := EnsurePromotionsSeed(dataRoot); err != nil {
		t.Fatalf("EnsurePromotionsSeed: %v", err)
	}
	return srv, dataRoot
}

// rnReq 构造带 uid 注入的请求（绕过 authMiddleware，直接注入 user_id，聚焦 handler 逻辑）。
func rnReq(method, path string) *http.Request {
	req := httptest.NewRequest(method, path, nil)
	return req.WithContext(service.WithUserID(req.Context(), "u-test"))
}

// doReq 发送请求并返回状态码与响应体字符串。
func doReq(t *testing.T, srv *Server, req *http.Request) (int, string) {
	t.Helper()
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	return rec.Code, rec.Body.String()
}

// ----- /api/rn/manifest -----

func TestRNManifestListsActivityBundle(t *testing.T) {
	srv, _ := newRNGateway(t)
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/rn/manifest"))
	if code != http.StatusOK {
		t.Fatalf("manifest: want 200, got %d body=%s", code, body)
	}
	var resp rnManifestResponse
	if err := json.Unmarshal([]byte(body), &resp); err != nil {
		t.Fatalf("decode: %v body=%s", err, body)
	}
	if len(resp.Bundles) != 1 {
		t.Fatalf("bundles len=%d (want 1): %+v", len(resp.Bundles), resp)
	}
	b := resp.Bundles[0]
	if b.Name != "activity-bundle" || b.Version != "1.0.0" || b.Entry != "index.android.bundle" {
		t.Fatalf("bundle view mismatch: %+v", b)
	}
	if b.Description != "运营活动" {
		t.Fatalf("description=%q want 运营活动", b.Description)
	}
}

func TestRNManifestRequiresAuth(t *testing.T) {
	srv, _ := newRNGateway(t)
	// 无 uid 注入：handler 应 401。
	req := httptest.NewRequest(http.MethodGet, "/api/rn/manifest", nil)
	code, _ := doReq(t, srv, req)
	if code != http.StatusUnauthorized {
		t.Fatalf("no uid: want 401, got %d", code)
	}
}

func TestRNManifestEmptyWhenNoDataDir(t *testing.T) {
	// dataDir 未注入：返回空列表（非 503）。
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, nil)
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/rn/manifest"))
	if code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	if !strings.Contains(body, `"bundles":[]`) && !strings.Contains(body, `"bundles": []`) {
		t.Fatalf("expected empty bundles, got %s", body)
	}
}

func TestRNManifestMethodNotAllowed(t *testing.T) {
	srv, _ := newRNGateway(t)
	code, _ := doReq(t, srv, rnReq(http.MethodPost, "/api/rn/manifest"))
	if code != http.StatusMethodNotAllowed {
		t.Fatalf("want 405, got %d", code)
	}
}

// ----- /api/rn/bundle/{name} -----

func TestRNBundleDownload(t *testing.T) {
	srv, _ := newRNGateway(t)
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/rn/bundle/activity-bundle"))
	if code != http.StatusOK {
		t.Fatalf("bundle: want 200, got %d body=%s", code, body)
	}
	if !strings.Contains(body, "activity-bundle") {
		t.Fatalf("bundle content mismatch: %s", body)
	}
	// Content-Type 应为 application/javascript（handler 显式设置）。
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, rnReq(http.MethodGet, "/api/rn/bundle/activity-bundle"))
	ct := rec.Header().Get("Content-Type")
	if !strings.HasPrefix(ct, "application/javascript") {
		t.Fatalf("Content-Type=%q want application/javascript", ct)
	}
}

func TestRNBundleNotFound(t *testing.T) {
	srv, _ := newRNGateway(t)
	code, _ := doReq(t, srv, rnReq(http.MethodGet, "/api/rn/bundle/nonexistent"))
	if code != http.StatusNotFound {
		t.Fatalf("nonexistent bundle: want 404, got %d", code)
	}
}

func TestRNBundleRejectsPathTraversal(t *testing.T) {
	srv, _ := newRNGateway(t)
	// http.ServeMux 会对含 ".." 的路径做 Clean 并 301 重定向（如 /api/rn/bundle/..
	// → /api/rn/bundle/），这是路由层的安全兜底，非 handler 职责。这里验证：
	//   1) 经 mux 的 /api/rn/bundle/.. 被 ServeMux 重定向（301），不会落到 handler。
	//   2) 直接调 handler（绕过 mux 的 clean）传入含 ".." 的名，handler 应 400 拒绝。
	//   3) 含 "/" 的名（如 foo/bar）handler 应 400。
	// mux 层：.. 触发 301 重定向（安全）。
	code, _ := doReq(t, srv, rnReq(http.MethodGet, "/api/rn/bundle/.."))
	if code != http.StatusMovedPermanently && code != http.StatusBadRequest {
		t.Fatalf("path .. via mux: want 301 (mux clean) or 400, got %d", code)
	}
	// handler 层：直接构造 Request 绕过 mux clean，验证 isSafeBundleName 拦截。
	// 构造 path "/api/rn/bundle/../../etc/passwd" 形态——TrimPrefix 后 name 含 ".."/"/"。
	hazardCases := []string{
		"/api/rn/bundle/../etc/passwd",
		"/api/rn/bundle/foo/bar",
	}
	for _, p := range hazardCases {
		req := rnReq(http.MethodGet, p)
		rec := httptest.NewRecorder()
		srv.handleRNBundle(rec, req) // 绕过 mux 的 clean，直测 handler 校验
		if rec.Code != http.StatusBadRequest {
			t.Fatalf("handler path %q: want 400 (invalid name), got %d body=%s", p, rec.Code, rec.Body.String())
		}
	}
}

func TestRNBundleRequiresAuth(t *testing.T) {
	srv, _ := newRNGateway(t)
	req := httptest.NewRequest(http.MethodGet, "/api/rn/bundle/activity-bundle", nil)
	code, _ := doReq(t, srv, req)
	if code != http.StatusUnauthorized {
		t.Fatalf("no uid: want 401, got %d", code)
	}
}

func TestRNBundleRespectsManifestEntryFile(t *testing.T) {
	// 自定义 entryFile：写一个 custom bundle，manifest 指向 custom.js。
	srv, dataRoot := newRNGateway(t)
	customDir := filepath.Join(dataRoot, "rn-bundles", "custom-bundle")
	if err := os.MkdirAll(customDir, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	manifest := `{"name":"custom-bundle","version":"2.0.0","description":"自定义","entryFile":"custom.js"}`
	if err := os.WriteFile(filepath.Join(customDir, "manifest.json"), []byte(manifest), 0o644); err != nil {
		t.Fatalf("write manifest: %v", err)
	}
	if err := os.WriteFile(filepath.Join(customDir, "custom.js"), []byte("// custom entry"), 0o644); err != nil {
		t.Fatalf("write custom.js: %v", err)
	}
	// manifest 应列出 entry=custom.js。
	_, body := doReq(t, srv, rnReq(http.MethodGet, "/api/rn/manifest"))
	if !strings.Contains(body, `"entry":"custom.js"`) {
		t.Fatalf("manifest should list custom.js entry: %s", body)
	}
	// 下载应返回 custom.js 内容（而非 index.android.bundle）。
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/rn/bundle/custom-bundle"))
	if code != http.StatusOK {
		t.Fatalf("custom bundle: want 200, got %d", code)
	}
	if !strings.Contains(body, "custom entry") {
		t.Fatalf("custom bundle content mismatch: %s", body)
	}
}

// ----- /api/promotions -----

func TestPromotionsReturnsSeed(t *testing.T) {
	srv, _ := newRNGateway(t)
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/promotions"))
	if code != http.StatusOK {
		t.Fatalf("promotions: want 200, got %d body=%s", code, body)
	}
	var promos []promotion
	if err := json.Unmarshal([]byte(body), &promos); err != nil {
		t.Fatalf("decode: %v body=%s", err, body)
	}
	if len(promos) != 1 || promos[0].ID != "p1" || promos[0].Title != "夏季活动" {
		t.Fatalf("promotions mismatch: %+v", promos)
	}
	if promos[0].ExpiresAt != "2026-08-31" {
		t.Fatalf("expiresAt=%q want 2026-08-31", promos[0].ExpiresAt)
	}
}

func TestPromotionsEmptyWhenNoFile(t *testing.T) {
	srv, dataRoot := newRNGateway(t)
	// 删除 promotions.json：应返回空数组。
	_ = os.Remove(filepath.Join(dataRoot, "promotions.json"))
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/promotions"))
	if code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	if !strings.Contains(body, "[]") {
		t.Fatalf("expected empty array, got %s", body)
	}
}

func TestPromotionsEmptyWhenNoDataDir(t *testing.T) {
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, nil)
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/promotions"))
	if code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	if !strings.Contains(body, "[]") {
		t.Fatalf("expected empty array, got %s", body)
	}
}

func TestPromotionsRequiresAuth(t *testing.T) {
	srv, _ := newRNGateway(t)
	req := httptest.NewRequest(http.MethodGet, "/api/promotions", nil)
	code, _ := doReq(t, srv, req)
	if code != http.StatusUnauthorized {
		t.Fatalf("no uid: want 401, got %d", code)
	}
}

func TestPromotionsMalformedReturnsEmpty(t *testing.T) {
	srv, dataRoot := newRNGateway(t)
	// 写一份损坏的 JSON：应降级返回空数组（不报 500）。
	_ = os.WriteFile(filepath.Join(dataRoot, "promotions.json"), []byte("{not json"), 0o644)
	code, body := doReq(t, srv, rnReq(http.MethodGet, "/api/promotions"))
	if code != http.StatusOK {
		t.Fatalf("malformed: want 200 (degraded), got %d", code)
	}
	if !strings.Contains(body, "[]") {
		t.Fatalf("malformed should degrade to empty array, got %s", body)
	}
}
