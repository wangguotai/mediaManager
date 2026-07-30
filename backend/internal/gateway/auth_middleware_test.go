package gateway

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"media-manager/backend/internal/auth"
	"media-manager/backend/internal/config"
)

// fakeStore 是 auth.UserStore 的内存实现，供 gateway 端到端测试构造可用的 authSvc
// （无需引入 SQLite 驱动）。username 重复返回模拟 sqlite 唯一约束冲突的错误信息，
// 由 auth.isUniqueViolation 识别为 ErrUsernameTaken。
type fakeStore struct {
	users []*auth.StoredUser
}

func (f *fakeStore) CreateUser(ctx context.Context, u auth.StoredUser) error {
	for _, ex := range f.users {
		if ex.Username == u.Username {
			return errors.New("UNIQUE constraint failed: user.username")
		}
	}
	f.users = append(f.users, &u)
	return nil
}
func (f *fakeStore) GetUserByUsername(ctx context.Context, username string) (*auth.StoredUser, error) {
	for _, u := range f.users {
		if u.Username == username {
			return u, nil
		}
	}
	return nil, auth.ErrUserNotFound
}

func (f *fakeStore) GetUserByID(ctx context.Context, userID string) (*auth.StoredUser, error) {
	for _, u := range f.users {
		if u.ID == userID {
			return u, nil
		}
	}
	return nil, auth.ErrUserNotFound
}

func (f *fakeStore) UpdatePassword(ctx context.Context, userID, newHash string) error {
	for _, u := range f.users {
		if u.ID == userID {
			u.PasswordHash = newHash
			return nil
		}
	}
	return auth.ErrUserNotFound
}
func (f *fakeStore) ListUsers(ctx context.Context) ([]*auth.StoredUser, error) {
	out := make([]*auth.StoredUser, len(f.users))
	copy(out, f.users)
	return out, nil
}

// newAuthedGateway 构造一个带可用 store 的 authSvc，并预注册一个测试用户。
// 返回 Server、有效 token、用户 id。
func newAuthedGateway(t *testing.T) (*Server, string, string) {
	t.Helper()
	store := &fakeStore{}
	frozen := time.Now().Add(time.Hour)
	authSvc, err := auth.New(store, "gw-test-secret", 3600, config.SignupFirst,
		auth.WithIDGenerator(func() string { return "u-1" }),
		auth.WithClock(func() time.Time { return frozen }),
	)
	if err != nil {
		t.Fatalf("auth.New: %v", err)
	}
	res, err := authSvc.Register(context.Background(), auth.RegisterRequest{Username: "alice", Password: "pw123456"})
	if err != nil {
		t.Fatalf("seed register: %v", err)
	}
	// 这些测试只断言 auth 中间件/登录/注册行为，mediaSvc 为 nil 且 probe handler 不
	// 触达文件路径，故 userDirs 传 nil 即可（NewServer 接受 *service.UserDirs）。
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, authSvc)
	return srv, res.Token, res.User.ID
}

// newReq 构造带可选 Bearer token 的请求。
func newReq(method, path, token string, body string) *http.Request {
	var r io.Reader
	if body != "" {
		r = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, path, r)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return req
}

func recJSON(t *testing.T, rec *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return m
}

// ----- 中间件：豁免路径 -----

func TestMiddlewareAuthExempt(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	// /healthz 与 /api/auth/* 无需 token。即便无 Authorization 头也应穿透到 handler。
	for _, p := range []string{"/healthz", "/api/auth/login"} {
		req := newReq(http.MethodGet, p, "", "")
		rec := httptest.NewRecorder()
		srv.authMiddleware(srv.mux).ServeHTTP(rec, req)
		// 穿透即非 401：/healthz 返回 200，/api/auth/login 的 GET 返回 405（method 校验先于认证逻辑外）。
		if rec.Code == http.StatusUnauthorized {
			t.Fatalf("%s: 豁免路径不应返回 401, got %d", p, rec.Code)
		}
	}
}

// ----- 中间件：无/坏 token → 401 -----

func TestMiddlewareMissingTokenReturns401(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	// 受保护路径在无 token 时必须 401，而非穿透。
	for _, p := range []string{"/api/media/list", "/api/media/upload"} {
		req := newReq(http.MethodGet, p, "", "")
		rec := httptest.NewRecorder()
		srv.authMiddleware(srv.mux).ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("%s without token: want 401, got %d", p, rec.Code)
		}
	}
}

func TestMiddlewareBadSchemeReturns401(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	// 非 Bearer 前缀应被拒。
	req := httptest.NewRequest(http.MethodGet, "/api/media/list", nil)
	req.Header.Set("Authorization", "Basic xyz")
	rec := httptest.NewRecorder()
	srv.authMiddleware(srv.mux).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("non-bearer scheme: want 401, got %d", rec.Code)
	}
}

func TestMiddlewareInvalidTokenReturns401(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	req := newReq(http.MethodGet, "/api/media/list", "garbage.token.here", "")
	rec := httptest.NewRecorder()
	srv.authMiddleware(srv.mux).ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("invalid token: want 401, got %d", rec.Code)
	}
}

// ----- 中间件：有效 token 注入 user_id -----

func TestMiddlewareValidTokenInjectsUserID(t *testing.T) {
	srv, token, userID := newAuthedGateway(t)
	// 用一个探针 handler 验证 context 中的 user_id 是否被注入。
	injected := make(chan string, 1)
	probe := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		injected <- userIDFromContext(r.Context())
	})
	req := newReq(http.MethodGet, "/api/media/list", token, "")
	rec := httptest.NewRecorder()
	srv.authMiddleware(probe).ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("valid token: want 200, got %d", rec.Code)
	}
	got := <-injected
	if got != userID {
		t.Fatalf("injected user_id = %q, want %q", got, userID)
	}
}

// ----- 中间件：authSvc=nil 放行 -----

func TestMiddlewareNilAuthSvcPassThrough(t *testing.T) {
	// authSvc 为 nil 时中间件直接放行（开发/测试场景）。
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, nil)
	called := false
	probe := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true })
	req := httptest.NewRequest(http.MethodGet, "/api/media/list", nil)
	srv.authMiddleware(probe).ServeHTTP(httptest.NewRecorder(), req)
	if !called {
		t.Fatal("nil authSvc should pass through")
	}
}

// ----- 端到端：login/register handler -----

func TestLoginEndpointSuccess(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	body := `{"username":"alice","password":"pw123456"}`
	req := newReq(http.MethodPost, "/api/auth/login", "", body)
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("login: want 200, got %d body=%s", rec.Code, rec.Body.String())
	}
	m := recJSON(t, rec)
	if m["token"] == nil || m["expires_at"] == nil || m["user"] == nil {
		t.Fatalf("login response missing fields: %+v", m)
	}
}

func TestLoginEndpointBadCredentialsReturns400(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	body := `{"username":"alice","password":"WRONG"}`
	req := newReq(http.MethodPost, "/api/auth/login", "", body)
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("bad credentials: want 400, got %d", rec.Code)
	}
}

func TestRegisterEndpointWeakPasswordReturns400(t *testing.T) {
	// 用 open 模式 gateway：弱口令触达 validatePassword → ErrInvalidCredentials → 400（而非 500）。
	store := &fakeStore{}
	authSvc, err := auth.New(store, "k", 3600, config.SignupOpen)
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, authSvc)
	body := `{"username":"weak","password":"ab"}`
	req := newReq(http.MethodPost, "/api/auth/register", "", body)
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("weak password: want 400 (not 500), got %d body=%s", rec.Code, rec.Body.String())
	}
}

func TestRegisterEndpointSignupDisabledReturns403(t *testing.T) {
	// off 模式：注册一律 ErrSignupDisabled → 403。
	store := &fakeStore{}
	authSvcOff, err := auth.New(store, "k", 3600, config.SignupOff)
	if err != nil {
		t.Fatal(err)
	}
	srv := NewServer(":0", OpenClawConfig{}, nil, nil, authSvcOff)
	body := `{"username":"n","password":"pw123456"}`
	req := newReq(http.MethodPost, "/api/auth/register", "", body)
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("signup off: want 403, got %d", rec.Code)
	}
}

func TestRegisterEndpointDuplicateReturns409(t *testing.T) {
	// open 模式下重复用户名 → 唯一约束冲突 → ErrUsernameTaken → 409。
	store := &fakeStore{}
	authSvc, err := auth.New(store, "k", 3600, config.SignupOpen)
	if err != nil {
		t.Fatal(err)
	}
	srv2 := NewServer(":0", OpenClawConfig{}, nil, nil, authSvc)
	// 先注册一次。
	req1 := newReq(http.MethodPost, "/api/auth/register", "", `{"username":"dup","password":"pw123456"}`)
	srv2.mux.ServeHTTP(httptest.NewRecorder(), req1)
	// 同名再注册。
	req2 := newReq(http.MethodPost, "/api/auth/register", "", `{"username":"dup","password":"pw123456"}`)
	rec := httptest.NewRecorder()
	srv2.mux.ServeHTTP(rec, req2)
	if rec.Code != http.StatusConflict {
		t.Fatalf("duplicate register: want 409, got %d", rec.Code)
	}
}

func TestAuthMethodNotAllowed(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	req := newReq(http.MethodGet, "/api/auth/login", "", "")
	rec := httptest.NewRecorder()
	srv.mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET /api/auth/login: want 405, got %d", rec.Code)
	}
}

// ----- /api/auth/change-password -----

// serveAuthed 用 authMiddleware 包裹 mux，使带 token 的请求被解析注入 user_id，
// 与真实 ListenAndServe 中间件链一致（mux 本身不含中间件）。
func serveAuthed(srv *Server, req *http.Request) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	srv.authMiddleware(srv.mux).ServeHTTP(rec, req)
	return rec
}

// TestChangePasswordRequiresToken 验证改密端点不被豁免：无 token → 401。
func TestChangePasswordRequiresToken(t *testing.T) {
	srv, _, _ := newAuthedGateway(t)
	req := newReq(http.MethodPost, "/api/auth/change-password", "",
		`{"old_password":"pw123456","new_password":"newpass12"}`)
	rec := serveAuthed(srv, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("change-password without token: want 401, got %d body=%s", rec.Code, rec.Body.String())
	}
}

// TestChangePasswordSuccess 验证旧密码正确可改密，新密码可登录、旧密码失效。
func TestChangePasswordSuccess(t *testing.T) {
	srv, token, _ := newAuthedGateway(t)
	// alice 初始密码 "pw123456"。
	body := `{"old_password":"pw123456","new_password":"newpass12"}`
	rec := serveAuthed(srv, newReq(http.MethodPost, "/api/auth/change-password", token, body))
	if rec.Code != http.StatusOK {
		t.Fatalf("change-password: want 200, got %d body=%s", rec.Code, rec.Body.String())
	}
	m := recJSON(t, rec)
	if m["status"] != "success" {
		t.Fatalf("change-password status: %+v", m)
	}
	// 新密码可登录。
	loginRec := httptest.NewRecorder()
	srv.mux.ServeHTTP(loginRec, newReq(http.MethodPost, "/api/auth/login", "",
		`{"username":"alice","password":"newpass12"}`))
	if loginRec.Code != http.StatusOK {
		t.Fatalf("login with new password: want 200, got %d body=%s", loginRec.Code, loginRec.Body.String())
	}
	// 旧密码应失效。
	oldRec := httptest.NewRecorder()
	srv.mux.ServeHTTP(oldRec, newReq(http.MethodPost, "/api/auth/login", "",
		`{"username":"alice","password":"pw123456"}`))
	if oldRec.Code != http.StatusBadRequest {
		t.Fatalf("login with old password: want 400, got %d", oldRec.Code)
	}
}

// TestChangePasswordWrongOldReturns400 验证旧密码错 → 400（不区分用户/密码错，防枚举）。
func TestChangePasswordWrongOldReturns400(t *testing.T) {
	srv, token, _ := newAuthedGateway(t)
	body := `{"old_password":"WRONGPASS","new_password":"newpass12"}`
	rec := serveAuthed(srv, newReq(http.MethodPost, "/api/auth/change-password", token, body))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("wrong old password: want 400, got %d body=%s", rec.Code, rec.Body.String())
	}
}

// TestChangePasswordWeakNewReturns400 验证新密码不满足长度策略 → 400。
func TestChangePasswordWeakNewReturns400(t *testing.T) {
	srv, token, _ := newAuthedGateway(t)
	body := `{"old_password":"pw123456","new_password":"short"}`
	rec := serveAuthed(srv, newReq(http.MethodPost, "/api/auth/change-password", token, body))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("weak new password: want 400, got %d body=%s", rec.Code, rec.Body.String())
	}
}

// TestChangePasswordFieldsRequired 验证字段缺失 → 400。
func TestChangePasswordFieldsRequired(t *testing.T) {
	srv, token, _ := newAuthedGateway(t)
	rec := serveAuthed(srv, newReq(http.MethodPost, "/api/auth/change-password", token, `{"old_password":"pw123456"}`))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("missing new_password: want 400, got %d", rec.Code)
	}
}
