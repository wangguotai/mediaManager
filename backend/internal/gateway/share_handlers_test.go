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
	"testing"
	"time"

	"media-manager/backend/internal/auth"
	"media-manager/backend/internal/config"
	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"
)

// newShareGateway 构造带真实 storage.Store + authSvc + per-user 目录的 gateway，
// 预注册用户 alice，并创建 2 个 media 记录（含一个真实落盘文件供 stream 验证）。
// 返回 (server, aliceToken, aliceUID, mediaIDs, dataRoot)。
func newShareGateway(t *testing.T) (*Server, string, string, []string, string) {
	t.Helper()
	dataRoot := t.TempDir()
	usersRoot := filepath.Join(dataRoot, "users")
	if err := os.MkdirAll(usersRoot, 0o755); err != nil {
		t.Fatalf("mkdir users root: %v", err)
	}
	userDirs := service.NewUserDirs(usersRoot)

	store, err := storage.Open(filepath.Join(dataRoot, "test.db"))
	if err != nil {
		t.Fatalf("storage.Open: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	authSvc, err := auth.New(
		auth.NewStoreBridge(store), "share-test-secret", 3600, config.SignupFirst,
		auth.WithIDGenerator(func() string { return "u-alice" }),
		auth.WithClock(func() time.Time { return time.Now().Add(time.Hour) }),
	)
	if err != nil {
		t.Fatalf("auth.New: %v", err)
	}
	res, err := authSvc.Register(context.Background(), auth.RegisterRequest{Username: "alice", Password: "pw123456"})
	if err != nil {
		t.Fatalf("seed register: %v", err)
	}
	uid := res.User.ID

	// 在 alice 的 uploads 目录落盘一个真实图片文件（id=mid1），供 stream 端点验证。
	uploadsDir, err := userDirs.UploadsDir(uid)
	if err != nil {
		t.Fatalf("uploads dir: %v", err)
	}
	mid1 := "mid-share-1"
	imgPath := filepath.Join(uploadsDir, mid1+".png")
	if err := os.WriteFile(imgPath, []byte("\x89PNG\r\n\x1a\n fake png"), 0o644); err != nil {
		t.Fatalf("write img: %v", err)
	}
	// 创建 2 个 media 记录：mid1 有真实文件，mid2 仅元数据（验证 stream 404）。
	for _, m := range []*storage.Media{
		{ID: mid1, UserID: uid, Filename: "a.png", Type: "IMAGE", Mime: "image/png", Size: 13},
		{ID: "mid-share-2", UserID: uid, Filename: "b.jpg", Type: "IMAGE", Mime: "image/jpeg", Size: 10},
	} {
		if err := store.CreateMedia(context.Background(), m); err != nil {
			t.Fatalf("create media %s: %v", m.ID, err)
		}
	}

	svc := service.NewMediaService(userDirs, "")
	srv := NewServer(":0", OpenClawConfig{}, svc, userDirs, authSvc)
	srv.SetStore(store)
	return srv, res.Token, uid, []string{mid1, "mid-share-2"}, dataRoot
}

// shareReq 构造请求；token 非空时带 Bearer。用 mux（不经 authMiddleware，因 /api/share/
// 已豁免；create/delete 在 handler 内手动鉴权）。
func shareReq(method, path, token string, body []byte) *http.Request {
	var r io.Reader
	if body != nil {
		r = bytes.NewReader(body)
	}
	req := httptest.NewRequest(method, path, r)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return req
}

// doShare 发请求经 mux，返回状态码与解析后的 body。
func doShare(t *testing.T, srv *Server, req *http.Request) (int, map[string]any) {
	t.Helper()
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return rec.Code, m
}

// ----- POST /api/share/create -----

func TestShareCreateSuccess(t *testing.T) {
	srv, token, uid, mids, _ := newShareGateway(t)

	body, _ := json.Marshal(map[string]any{"media_ids": mids, "expires_days": 7})
	code, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	if code != http.StatusOK {
		t.Fatalf("create: want 200, got %d body=%v", code, resp)
	}
	st, ok := resp["token"].(string)
	if !ok || len(st) != 12 {
		t.Fatalf("create: want 12-char token, got %v", resp["token"])
	}
	if resp["url"] != "/share/"+st {
		t.Fatalf("create: url mismatch: %v", resp["url"])
	}
	if ea, _ := resp["expires_at"].(string); ea == "" {
		t.Fatalf("create: expires_at should be non-empty for 7 days")
	}
	_ = uid
}

func TestShareCreateNoAuth(t *testing.T) {
	srv, _, _, mids, _ := newShareGateway(t)
	body, _ := json.Marshal(map[string]any{"media_ids": mids})
	code, _ := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", "", body))
	if code != http.StatusUnauthorized {
		t.Fatalf("create without token: want 401, got %d", code)
	}
}

func TestShareCreateRejectsOthersMedia(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	// alice 试图分享一个不存在的 media_id → 403（防越权）。
	body, _ := json.Marshal(map[string]any{"media_ids": []string{"not-mine-or-missing"}})
	code, _ := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	if code != http.StatusForbidden {
		t.Fatalf("create with foreign/missing media: want 403, got %d", code)
	}
	_ = mids
}

func TestShareCreateNeverExpires(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	body, _ := json.Marshal(map[string]any{"media_ids": mids, "expires_days": 0})
	code, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	if code != http.StatusOK {
		t.Fatalf("create no-expire: want 200, got %d", code)
	}
	if ea, _ := resp["expires_at"].(string); ea != "" {
		t.Fatalf("create no-expire: expires_at should be empty, got %q", ea)
	}
}

func TestShareCreateWithPassword(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	body, _ := json.Marshal(map[string]any{"media_ids": mids, "password": "secret123"})
	code, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	if code != http.StatusOK {
		t.Fatalf("create with password: want 200, got %d body=%v", code, resp)
	}
	st := resp["token"].(string)
	// 公开查看不带密码 → 403 + has_password=true。
	code2, resp2 := doShare(t, srv, shareReq(http.MethodGet, "/api/share/"+st, "", nil))
	if code2 != http.StatusForbidden || resp2["has_password"] != true {
		t.Fatalf("view without password: want 403 has_password=true, got %d %v", code2, resp2)
	}
	// 错误密码 → 403。
	code3, _ := doShare(t, srv, shareReq(http.MethodGet, "/api/share/"+st+"?password=wrong", "", nil))
	if code3 != http.StatusForbidden {
		t.Fatalf("view wrong password: want 403, got %d", code3)
	}
	// 正确密码 → 200 + media_list。
	code4, resp4 := doShare(t, srv, shareReq(http.MethodGet, "/api/share/"+st+"?password=secret123", "", nil))
	if code4 != http.StatusOK {
		t.Fatalf("view correct password: want 200, got %d", code4)
	}
	if list, _ := resp4["media_list"].([]any); len(list) != 2 {
		t.Fatalf("view: want 2 media, got %v", resp4["media_list"])
	}
	if resp4["has_password"] != true {
		t.Fatalf("view: has_password should be true")
	}
}

// ----- GET /api/share/{token} -----

func TestShareViewPublic(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	body, _ := json.Marshal(map[string]any{"media_ids": mids})
	_, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	st := resp["token"].(string)

	// 无 Authorization 头也能访问（公开）。
	code, resp2 := doShare(t, srv, shareReq(http.MethodGet, "/api/share/"+st, "", nil))
	if code != http.StatusOK {
		t.Fatalf("public view: want 200, got %d", code)
	}
	if resp2["token"] != st {
		t.Fatalf("view: token mismatch")
	}
	if list, _ := resp2["media_list"].([]any); len(list) != 2 {
		t.Fatalf("view: want 2 media, got %d", len(list))
	}
	if resp2["has_password"] != false {
		t.Fatalf("view: has_password should be false")
	}
}

func TestShareViewNotFound(t *testing.T) {
	srv, _, _, _, _ := newShareGateway(t)
	code, _ := doShare(t, srv, shareReq(http.MethodGet, "/api/share/nonexistent-token", "", nil))
	if code != http.StatusNotFound {
		t.Fatalf("view missing token: want 404, got %d", code)
	}
}

func TestShareViewExpired(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	// expires_days 不支持小数，故直接建一个已过期的 token 入库验证。
	// 用 1 天创建后无法立刻过期；改为直接插一个过期记录。
	st := &storage.ShareToken{
		Token:     "expiredtok123",
		UserID:    "u-alice",
		MediaIDs:  `["` + mids[0] + `"]`,
		ExpiresAt: time.Now().Add(-1 * time.Hour), // 已过期
	}
	if err := srv.store.CreateShareToken(context.Background(), st); err != nil {
		t.Fatalf("seed expired token: %v", err)
	}
	code, _ := doShare(t, srv, shareReq(http.MethodGet, "/api/share/expiredtok123", "", nil))
	if code != http.StatusNotFound {
		t.Fatalf("view expired: want 404, got %d", code)
	}
	_ = token
}

func TestShareViewFiltersDeletedMedia(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	// 先创建分享（含 mid1 + mid2），再软删 mid2 → view 应只返回 1 个 media。
	body, _ := json.Marshal(map[string]any{"media_ids": mids})
	_, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	st := resp["token"].(string)

	if err := srv.store.MarkDeleted(context.Background(), mids[1]); err != nil {
		t.Fatalf("mark deleted: %v", err)
	}

	_, resp2 := doShare(t, srv, shareReq(http.MethodGet, "/api/share/"+st, "", nil))
	if list, _ := resp2["media_list"].([]any); len(list) != 1 {
		t.Fatalf("view after soft-delete: want 1 media, got %d", len(list))
	}
}

// ----- GET /api/share/{token}/stream/{mediaId} -----

func TestShareStreamPublic(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	body, _ := json.Marshal(map[string]any{"media_ids": mids})
	_, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	st := resp["token"].(string)

	// 公开下载 mid1（有真实文件）。
	req := shareReq(http.MethodGet, "/api/share/"+st+"/stream/"+mids[0], "", nil)
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("stream: want 200, got %d", rec.Code)
	}
	if rec.Body.Len() == 0 {
		t.Fatalf("stream: empty body")
	}
	if ct := rec.Header().Get("Content-Type"); ct != "image/png" {
		t.Fatalf("stream: Content-Type want image/png, got %q", ct)
	}
}

func TestShareStreamRejectsMediaNotInShare(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	// 只分享 mids[0]。
	body, _ := json.Marshal(map[string]any{"media_ids": []string{mids[0]}})
	_, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	st := resp["token"].(string)
	// mids[1] 不在该分享内 → 404。
	req := shareReq(http.MethodGet, "/api/share/"+st+"/stream/"+mids[1], "", nil)
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("stream not-in-share: want 404, got %d", rec.Code)
	}
}

// ----- DELETE /api/share/{token} -----

func TestShareDeleteByOwner(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	body, _ := json.Marshal(map[string]any{"media_ids": mids})
	_, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	st := resp["token"].(string)

	// 所有者撤销。
	code, _ := doShare(t, srv, shareReq(http.MethodDelete, "/api/share/"+st, token, nil))
	if code != http.StatusOK {
		t.Fatalf("delete by owner: want 200, got %d", code)
	}
	// 撤销后 view → 404。
	code2, _ := doShare(t, srv, shareReq(http.MethodGet, "/api/share/"+st, "", nil))
	if code2 != http.StatusNotFound {
		t.Fatalf("view after delete: want 404, got %d", code2)
	}
}

func TestShareDeleteNoAuth(t *testing.T) {
	srv, token, _, mids, _ := newShareGateway(t)
	body, _ := json.Marshal(map[string]any{"media_ids": mids})
	_, resp := doShare(t, srv, shareReq(http.MethodPost, "/api/share/create", token, body))
	st := resp["token"].(string)

	// 无 token 删除 → 401。
	code, _ := doShare(t, srv, shareReq(http.MethodDelete, "/api/share/"+st, "", nil))
	if code != http.StatusUnauthorized {
		t.Fatalf("delete without auth: want 401, got %d", code)
	}
}

// ----- 路由分流 -----

func TestShareAccessMethodDispatch(t *testing.T) {
	srv, _, _, _, _ := newShareGateway(t)
	// /api/share/ 自身 → 404 token required。
	code, _ := doShare(t, srv, shareReq(http.MethodGet, "/api/share/", "", nil))
	if code != http.StatusNotFound {
		t.Fatalf("bare /api/share/: want 404, got %d", code)
	}
	// PUT 不支持 → 405。
	code, _ = doShare(t, srv, shareReq(http.MethodPut, "/api/share/sometoken", "", nil))
	if code != http.StatusMethodNotAllowed {
		t.Fatalf("PUT: want 405, got %d", code)
	}
}
