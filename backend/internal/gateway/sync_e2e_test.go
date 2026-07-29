package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"

	"media-manager/backend/internal/auth"
	"media-manager/backend/internal/config"
	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"
)

// newSyncGateway 构造一个带真实 storage.Store + authSvc + per-user 目录的 gateway，
// 并预注册一个测试用户 alice，返回 (server, token, userID, dataRoot)。
// 复用 main.go 的接线方式，使 sync/device/upload 端点端到端可用。
func newSyncGateway(t *testing.T) (*Server, string, string, string) {
	t.Helper()
	dataRoot := t.TempDir()
	usersRoot := filepath.Join(dataRoot, "users")
	if err := os.MkdirAll(usersRoot, 0o755); err != nil {
		t.Fatalf("mkdir users root: %v", err)
	}
	userDirs := service.NewUserDirs(usersRoot)

	// 真实 SQLite store（临时目录），迁移含 client_id/taken_at 列。
	store, err := storage.Open(filepath.Join(dataRoot, "test.db"))
	if err != nil {
		t.Fatalf("storage.Open: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	authSvc, err := auth.New(
		auth.NewStoreBridge(store), "sync-test-secret", 3600, config.SignupFirst,
		auth.WithIDGenerator(func() string { return "u-alice" }),
		auth.WithClock(func() time.Time { return time.Now().Add(time.Hour) }),
	)
	if err != nil {
		t.Fatalf("auth.New: %v", err)
	}
	res, err := authSvc.Register(context.Background(), auth.RegisterRequest{Username: "alice", Password: "pw1234"})
	if err != nil {
		t.Fatalf("seed register: %v", err)
	}

	svc := service.NewMediaService(userDirs, "")
	srv := NewServer(":0", OpenClawConfig{}, svc, userDirs, authSvc)
	srv.SetStore(store) // 启用 sync/device/usage/秒传
	return srv, res.Token, res.User.ID, dataRoot
}

// authedReq 构造带 Bearer token 的请求，body 为非空时设置。
func authedReq(method, path, token string, body []byte) *http.Request {
	var r io.Reader
	if body != nil {
		r = bytes.NewReader(body)
	}
	req := httptest.NewRequest(method, path, r)
	req.Header.Set("Authorization", "Bearer "+token)
	return req
}

// authedHandler 返回经 authMiddleware 包裹的 mux，使带 Bearer token 的测试请求
// 被解析并注入 user_id（与真实 ListenAndServe 链一致；mux 本身不含中间件）。
func authedHandler(srv *Server) http.Handler {
	return srv.authMiddleware(srv.mux)
}

// doJSON 发送请求并返回状态码与解码后的 body map。
func doJSON(t *testing.T, srv *Server, req *http.Request) (int, map[string]any) {
	t.Helper()
	rec := httptest.NewRecorder()
	authedHandler(srv).ServeHTTP(rec, req)
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return rec.Code, m
}

// ----- 鉴权：无 token / store 未配置 -----

func TestSyncChangesRequiresAuth(t *testing.T) {
	srv, _, _, _ := newSyncGateway(t)
	// 无 token 直接打 mux：authMiddleware 已挂在 ListenAndServe，mux 本身不含中间件，
	// 故这里直接验证 handleSyncChanges 在 uid 缺失时 401（纵深防御）。
	req := httptest.NewRequest(http.MethodGet, "/api/sync/changes", nil)
	rec := httptest.NewRecorder()
	srv.handleSyncChanges(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("no uid: want 401, got %d", rec.Code)
	}
}

func TestSyncEndpointsStoreNilReturns503(t *testing.T) {
	// store 未注入：sync/usage/device 端点应 503。用一个 authSvc=nil 的 gateway，
	// 并手动通过 context 注入 uid 绕过中间件，聚焦 store 缺失分支。
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, nil)
	cases := []struct {
		path   string
		method string
	}{
		{"/api/sync/changes", http.MethodGet},
		{"/api/sync/usage", http.MethodGet},
		{"/api/device/register", http.MethodPost},
		{"/api/device/list", http.MethodGet},
	}
	for _, c := range cases {
		req := httptest.NewRequest(c.method, c.path, nil)
		req = req.WithContext(service.WithUserID(req.Context(), "u-1"))
		rec := httptest.NewRecorder()
		srv.mux.ServeHTTP(rec, req)
		if rec.Code != http.StatusServiceUnavailable {
			t.Fatalf("%s %s nil store: want 503, got %d", c.method, c.path, rec.Code)
		}
	}
}

// ----- /api/device/register + /api/device/list -----

func TestDeviceRegisterAndList(t *testing.T) {
	srv, token, uid, _ := newSyncGateway(t)
	_ = uid

	// 注册两台设备。
	for _, name := range []string{"iPhone", "iPad"} {
		body := []byte(`{"device_name":"` + name + `","platform":"ios"}`)
		code, m := doJSON(t, srv, authedReq(http.MethodPost, "/api/device/register", token, body))
		if code != http.StatusOK {
			t.Fatalf("register %s: want 200, got %d body=%v", name, code, m)
		}
		if m["device_id"] == nil || m["device_id"] == "" {
			t.Fatalf("register %s missing device_id: %+v", name, m)
		}
	}

	// 列表应含两台。
	code, m := doJSON(t, srv, authedReq(http.MethodGet, "/api/device/list", token, nil))
	if code != http.StatusOK {
		t.Fatalf("list: want 200, got %d", code)
	}
	devices, _ := m["devices"].([]any)
	if len(devices) != 2 {
		t.Fatalf("device list len=%d (want 2): %+v", len(devices), m)
	}
	// 首项含 device_id/device_name/platform/created_at。
	first, _ := devices[0].(map[string]any)
	if first["device_id"] == nil || first["device_name"] == nil || first["platform"] == nil || first["created_at"] == nil {
		t.Fatalf("device item missing fields: %+v", first)
	}
}

// ----- /api/sync/usage -----

func TestSyncUsage(t *testing.T) {
	srv, token, uid, _ := newSyncGateway(t)
	// 通过 upload 落盘一条媒体（同时写 storage media 表）。
	uploadAndCheck(t, srv, token, "pic.jpg", []byte("hello-world"), "success")

	code, m := doJSON(t, srv, authedReq(http.MethodGet, "/api/sync/usage", token, nil))
	_ = uid
	if code != http.StatusOK {
		t.Fatalf("usage: want 200, got %d body=%v", code, m)
	}
	if int(m["file_count"].(float64)) != 1 {
		t.Fatalf("usage file_count=%v (want 1)", m["file_count"])
	}
	if int64(m["total_bytes"].(float64)) != int64(len("hello-world")) {
		t.Fatalf("usage total_bytes=%v (want %d)", m["total_bytes"], len("hello-world"))
	}
}

// ----- /api/sync/changes 含墓碑 + 增量游标 -----

func TestSyncChangesIncrementalWithTombstone(t *testing.T) {
	srv, token, _, _ := newSyncGateway(t)

	// 上传两条（成功，写 media 表）。
	r1 := uploadAndCheck(t, srv, token, "a.jpg", []byte("aaaa"), "success")
	r2 := uploadAndCheck(t, srv, token, "b.jpg", []byte("bbbb"), "success")

	// 软删第一条：经 storage.Store 直接标记（模拟 DeleteMedia 路径尚未接 store，
	// 这里用 store API 触发墓碑 + updated_at 推进，使 changes 能观测到）。
	if err := srv.store.MarkDeleted(context.Background(), r1.mediaID); err != nil {
		t.Fatalf("MarkDeleted: %v", err)
	}

	// 首次全量拉取（since 空）：应含 2 条变更，其一为墓碑。
	code, m := doJSON(t, srv, authedReq(http.MethodGet, "/api/sync/changes", token, nil))
	if code != http.StatusOK {
		t.Fatalf("changes: want 200, got %d body=%v", code, m)
	}
	changes, _ := m["changes"].([]any)
	if len(changes) != 2 {
		t.Fatalf("changes len=%d (want 2): %+v", len(changes), m)
	}
	// 至少一条 Deleted=true（墓碑）。
	hasTomb := false
	for _, c := range changes {
		item, _ := c.(map[string]any)
		if item["deleted"] == true {
			hasTomb = true
		}
		if item["sha256"] == nil || item["sha256"] == "" {
			t.Fatalf("change missing sha256: %+v", item)
		}
	}
	if !hasTomb {
		t.Fatalf("no tombstone in changes: %+v", changes)
	}

	// 用 next_cursor 续拉应无更多（has_more=false）。
	nc, _ := m["next_cursor"].(float64)
	cursor := int64(nc)
	if cursor <= 0 {
		t.Fatalf("next_cursor should be >0 when has_more or present, got %v", m["next_cursor"])
	}
	code2, m2 := doJSON(t, srv, authedReq(http.MethodGet,
		"/api/sync/changes?since="+itoa(cursor), token, nil))
	if code2 != http.StatusOK {
		t.Fatalf("changes page2: want 200, got %d", code2)
	}
	if m2["has_more"] != false {
		t.Fatalf("page2 has_more=%v (want false): %+v", m2["has_more"], m2)
	}
	changes2, _ := m2["changes"].([]any)
	if len(changes2) != 0 {
		t.Fatalf("page2 should be empty, got %d", len(changes2))
	}
	_ = r2
}

// ----- upload 秒传去重 -----

func TestUploadDedup(t *testing.T) {
	srv, token, _, _ := newSyncGateway(t)
	payload := []byte("dedup-content")

	// 首次上传：成功。
	first := uploadAndCheck(t, srv, token, "orig.jpg", payload, "success")
	// 第二次上传同内容：应秒传（deduped），media_id 复用，不新增文件。
	second := uploadAndCheck(t, srv, token, "copy.jpg", payload, "deduped")
	if second.mediaID != first.mediaID {
		t.Fatalf("dedup should reuse media_id: first=%s second=%s", first.mediaID, second.mediaID)
	}
	// usage 仅计 1 个文件。
	_, m := doJSON(t, srv, authedReq(http.MethodGet, "/api/sync/usage", token, nil))
	if int(m["file_count"].(float64)) != 1 {
		t.Fatalf("after dedup file_count=%v (want 1)", m["file_count"])
	}
	// 客户端传错误 sha256 时，服务端以实测为准仍能去重。
	third := uploadAndCheck(t, srv, token, "copy2.jpg?sha256=wrong", payload, "deduped")
	if third.mediaID != first.mediaID {
		t.Fatalf("dedup with wrong client sha256 should still reuse id: %s", third.mediaID)
	}
}

// ----- upload 写 storage 表（client_id/taken_at 落库）→ changes 回读 -----

func TestUploadPersistsClientIDTakenAt(t *testing.T) {
	srv, token, _, _ := newSyncGateway(t)
	// 上传带 client_id/taken_at。
	path := "/api/media/upload?filename=with-meta.jpg&client_id=device-X&taken_at=1700000000000"
	req := authedReq(http.MethodPost, path, token, []byte("meta-content"))
	rec := httptest.NewRecorder()
	authedHandler(srv).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("upload: want 200, got %d body=%s", rec.Code, rec.Body.String())
	}
	var up map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &up)
	mediaID, _ := up["media_id"].(string)

	// changes 回读应含本条且 client_id/taken_at 正确。
	_, m := doJSON(t, srv, authedReq(http.MethodGet, "/api/sync/changes", token, nil))
	changes, _ := m["changes"].([]any)
	var found map[string]any
	for _, c := range changes {
		item, _ := c.(map[string]any)
		if item["id"] == mediaID {
			found = item
		}
	}
	if found == nil {
		t.Fatalf("uploaded media not found in changes: %+v", m)
	}
	if found["client_id"] != "device-X" {
		t.Fatalf("client_id not persisted: %+v", found["client_id"])
	}
	if int64(found["taken_at"].(float64)) != 1700000000000 {
		t.Fatalf("taken_at not persisted: %+v", found["taken_at"])
	}
}

// ===== 测试辅助 =====

type uploadResult struct {
	mediaID string
	sha     string
}

// uploadAndCheck 上传一个文件（filename 可含 query 后缀如 "x.jpg?sha256=..."），
// 断言 status 并返回 media_id 与 sha256。path 经 authedReq 透传。
func uploadAndCheck(t *testing.T, srv *Server, token, filenameQuery string, body []byte, wantStatus string) uploadResult {
	t.Helper()
	path := "/api/media/upload?filename=" + filenameQuery
	req := authedReq(http.MethodPost, path, token, body)
	rec := httptest.NewRecorder()
	authedHandler(srv).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("upload %s: want 200, got %d body=%s", filenameQuery, rec.Code, rec.Body.String())
	}
	var m map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("upload decode: %v body=%s", err, rec.Body.String())
	}
	if m["status"] != wantStatus {
		t.Fatalf("upload %s status=%v want %s body=%+v", filenameQuery, m["status"], wantStatus, m)
	}
	id, _ := m["media_id"].(string)
	sha, _ := m["sha256"].(string)
	if id == "" {
		t.Fatalf("upload missing media_id: %+v", m)
	}
	return uploadResult{mediaID: id, sha: sha}
}

// itoa 把 int64 转字符串（测试辅助）。
func itoa(n int64) string {
	return strconv.FormatInt(n, 10)
}
