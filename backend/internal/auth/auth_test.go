package auth

import (
	"context"
	"errors"
	"testing"
	"time"
)

// memStore 是 UserStore 的内存实现，用于隔离 SQLite 测试 auth 核心逻辑。
type memStore struct {
	users     []*StoredUser
	byID      map[string]*StoredUser
	createErr error // 注入：下一次 CreateUser 强制返回此错误（模拟唯一冲突）
}

func newMemStore() *memStore {
	return &memStore{byID: map[string]*StoredUser{}}
}

func (m *memStore) CreateUser(ctx context.Context, u StoredUser) error {
	if m.createErr != nil {
		err := m.createErr
		m.createErr = nil
		return err
	}
	for _, ex := range m.users {
		if ex.Username == u.Username {
			return errors.New("UNIQUE constraint failed: user.username")
		}
	}
	stored := &StoredUser{
		ID:           u.ID,
		Username:     u.Username,
		PasswordHash: u.PasswordHash,
		Role:         u.Role,
		CreatedAt:    u.CreatedAt,
	}
	m.users = append(m.users, stored)
	m.byID[u.ID] = stored
	return nil
}

func (m *memStore) GetUserByUsername(ctx context.Context, username string) (*StoredUser, error) {
	for _, u := range m.users {
		if u.Username == username {
			return u, nil
		}
	}
	return nil, ErrUserNotFound
}

func (m *memStore) GetUserByID(ctx context.Context, userID string) (*StoredUser, error) {
	if u, ok := m.byID[userID]; ok {
		return u, nil
	}
	return nil, ErrUserNotFound
}

func (m *memStore) UpdatePassword(ctx context.Context, userID, newHash string) error {
	u, ok := m.byID[userID]
	if !ok {
		return ErrUserNotFound
	}
	u.PasswordHash = newHash
	return nil
}

func (m *memStore) ListUsers(ctx context.Context) ([]*StoredUser, error) {
	out := make([]*StoredUser, len(m.users))
	copy(out, m.users)
	return out, nil
}

const testSecret = "test-secret-key-very-long"

// newTestAuth 构造一个接内存 store、固定 ID/时钟的 AuthService。
// 时钟捕获一次"未来"时间并固定返回，使注册/登录两次签发的 exp 完全一致、可断言相等。
func newTestAuth(t *testing.T, signup string) (*AuthService, *memStore) {
	t.Helper()
	var counter int
	idGen := func() string {
		counter++
		return "u-" + string(rune('0'+counter)) // u-1, u-2 ...
	}
	frozen := time.Now().Add(time.Hour) // 未来时间，确保 token 在真实墙钟下未过期
	a, err := New(newMemStore(), testSecret, 3600, signup,
		WithIDGenerator(idGen), WithClock(func() time.Time { return frozen }))
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return a, a.store.(*memStore)
}

// TestLoginSuccess 验证注册后能用同凭据登录，且返回 token 可被 ParseToken 解析回同一 user_id。
func TestLoginSuccess(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	reg, err := a.Register(context.Background(), RegisterRequest{Username: "alice", Password: "pw123456"})
	if err != nil {
		t.Fatalf("Register: %v", err)
	}
	if reg.Token == "" || reg.User.Username != "alice" || reg.User.Role != "user" {
		t.Fatalf("Register result mismatch: %+v", reg)
	}
	// 注册返回的 token 立即可解析。
	uid, err := a.ParseToken(reg.Token)
	if err != nil || uid != reg.User.ID {
		t.Fatalf("ParseToken after register: uid=%q err=%v", uid, err)
	}

	login, err := a.Login(context.Background(), LoginRequest{Username: "alice", Password: "pw123456"})
	if err != nil {
		t.Fatalf("Login: %v", err)
	}
	if login.Token == "" || login.User.ID != reg.User.ID {
		t.Fatalf("Login result mismatch: %+v", login)
	}
	// ExpiresAt 应在当前时间之后（未过期），且与注册时刻 +ttl 一致量级（+1h ttl）。
	if !login.ExpiresAt.After(time.Now()) {
		t.Fatalf("ExpiresAt should be in the future: %v", login.ExpiresAt)
	}
	// 登录 token 也可解析回同一 user_id（与注册 token 是同号签发）。
	uid2, err := a.ParseToken(login.Token)
	if err != nil || uid2 != reg.User.ID {
		t.Fatalf("ParseToken after login: uid=%q err=%v", uid2, err)
	}
	// 两次签发（注册/登录）的 expires_at 应相等（同一 nowFunc 返回值 + 相同 ttl）。
	if !login.ExpiresAt.Equal(reg.ExpiresAt) {
		t.Fatalf("register/login ExpiresAt should match: reg=%v login=%v", reg.ExpiresAt, login.ExpiresAt)
	}
}

// TestLoginInvalidCredentials 验证错误密码/不存在用户均返回 ErrInvalidCredentials（不泄露用户是否存在）。
func TestLoginInvalidCredentials(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	if _, err := a.Register(context.Background(), RegisterRequest{Username: "bob", Password: "pw123456"}); err != nil {
		t.Fatalf("Register: %v", err)
	}
	cases := []LoginRequest{
		{Username: "bob", Password: "wrong"},
		{Username: "nobody", Password: "pw123456"},
		{Username: "", Password: "pw123456"},
		{Username: "bob", Password: ""},
	}
	for _, c := range cases {
		if _, err := a.Login(context.Background(), c); !errors.Is(err, ErrInvalidCredentials) {
			t.Fatalf("Login %+v: want ErrInvalidCredentials, got %v", c, err)
		}
	}
}

// TestSignupOff 验证 off 模式下注册一律被拒。
func TestSignupOff(t *testing.T) {
	a, _ := newTestAuth(t, "off")
	_, err := a.Register(context.Background(), RegisterRequest{Username: "x", Password: "pw123456"})
	if !errors.Is(err, ErrSignupDisabled) {
		t.Fatalf("off mode: want ErrSignupDisabled, got %v", err)
	}
}

// TestSignupFirst 验证 first 模式：首位注册者获 admin，第二位被拒。
func TestSignupFirst(t *testing.T) {
	a, _ := newTestAuth(t, "first")
	first, err := a.Register(context.Background(), RegisterRequest{Username: "founder", Password: "pw123456"})
	if err != nil {
		t.Fatalf("first Register: %v", err)
	}
	if first.User.Role != "admin" {
		t.Fatalf("first registrant should be admin, got %q", first.User.Role)
	}
	// 已有用户 → 注册关闭。
	_, err = a.Register(context.Background(), RegisterRequest{Username: "second", Password: "pw123456"})
	if !errors.Is(err, ErrSignupDisabled) {
		t.Fatalf("second Register in first mode: want ErrSignupDisabled, got %v", err)
	}
}

// TestSignupOpen 验证 open 模式下注册者均为普通 user。
func TestSignupOpen(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	r, err := a.Register(context.Background(), RegisterRequest{Username: "u1", Password: "pw123456"})
	if err != nil || r.User.Role != "user" {
		t.Fatalf("open mode: role=%q err=%v", r.User.Role, err)
	}
}

// TestUsernameTaken 验证唯一约束冲突映射为 ErrUsernameTaken。
func TestUsernameTaken(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	if _, err := a.Register(context.Background(), RegisterRequest{Username: "dup", Password: "pw123456"}); err != nil {
		t.Fatalf("first Register: %v", err)
	}
	_, err := a.Register(context.Background(), RegisterRequest{Username: "dup", Password: "pw123456"})
	if !errors.Is(err, ErrUsernameTaken) {
		t.Fatalf("duplicate username: want ErrUsernameTaken, got %v", err)
	}
}

// TestShortPassword 验证过短密码被拒（>= minPasswordLength=8）。
func TestShortPassword(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	_, err := a.Register(context.Background(), RegisterRequest{Username: "short", Password: "abc"})
	if err == nil {
		t.Fatalf("short password should be rejected")
	}
}

// TestPasswordMinLength8 验证 V5 安全基线——密码至少 8 位：7 位被拒，8 位通过。
func TestPasswordMinLength8(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	// 7 位应被拒。
	if _, err := a.Register(context.Background(), RegisterRequest{Username: "seven", Password: "1234567"}); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("7-char password should be rejected, got %v", err)
	}
	// 8 位应通过。
	res, err := a.Register(context.Background(), RegisterRequest{Username: "eight", Password: "ab123456"})
	if err != nil {
		t.Fatalf("8-char password should be accepted, got %v", err)
	}
	if res == nil || res.Token == "" {
		t.Fatalf("expected successful registration for 8-char password")
	}
}

// TestChangePassword 验证改密：旧密码正确时成功改密并可用新密码登录；旧密码错时拒绝。
func TestChangePassword(t *testing.T) {
	a, store := newTestAuth(t, "open")
	reg, err := a.Register(context.Background(), RegisterRequest{Username: "carol", Password: "pw123456"})
	if err != nil {
		t.Fatalf("Register: %v", err)
	}
	uid := reg.User.ID

	// 旧密码错 → ErrInvalidCredentials。
	if err := a.ChangePassword(context.Background(), uid, "WRONGOLD", "newpass12"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("wrong old password: want ErrInvalidCredentials, got %v", err)
	}
	// 新密码过短 → ErrInvalidCredentials（即便旧密码正确也应拒绝写入弱口令）。
	if err := a.ChangePassword(context.Background(), uid, "pw123456", "short"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("short new password: want ErrInvalidCredentials, got %v", err)
	}
	// 正常改密。
	if err := a.ChangePassword(context.Background(), uid, "pw123456", "newpass12"); err != nil {
		t.Fatalf("ChangePassword: %v", err)
	}
	// 旧密码应已失效。
	if _, err := a.Login(context.Background(), LoginRequest{Username: "carol", Password: "pw123456"}); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("login with old password after change: want ErrInvalidCredentials, got %v", err)
	}
	// 新密码可登录。
	if _, err := a.Login(context.Background(), LoginRequest{Username: "carol", Password: "newpass12"}); err != nil {
		t.Fatalf("login with new password: %v", err)
	}
	// 底层 hash 确实被改写（防"改密静默失败"）：新密码能登录即证明 hash 已更新。
	_ = store
}

// TestChangePasswordUnknownUser 验证对不存在用户改密返回 ErrInvalidCredentials（不泄露存在性）。
func TestChangePasswordUnknownUser(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	if err := a.ChangePassword(context.Background(), "no-such-user", "whatever1", "newpass12"); !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("unknown user change: want ErrInvalidCredentials, got %v", err)
	}
}

// TestParseTokenInvalid 验证伪造/篡改/错误密钥签发的 token 均被拒。
func TestParseTokenInvalid(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	reg, err := a.Register(context.Background(), RegisterRequest{Username: "p", Password: "pw123456"})
	if err != nil {
		t.Fatalf("Register: %v", err)
	}
	// 缺失 sub/空串、乱码、错误密钥签发的 token 都应失败。
	bad := []string{"", "not-a-jwt", reg.Token + "tampered"}
	for _, b := range bad {
		if _, err := a.ParseToken(b); !errors.Is(err, ErrInvalidToken) {
			t.Fatalf("ParseToken(%q): want ErrInvalidToken, got %v", b, err)
		}
	}
	// 不同密钥签发的 token 也应被拒（防 alg 降级/密钥混淆）。
	other, err := New(newMemStore(), "different-secret", 3600, "off")
	if err != nil {
		t.Fatalf("New other: %v", err)
	}
	if _, err := other.ParseToken(reg.Token); !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("token signed by different secret should be rejected")
	}
}

// TestExpiredToken 验证过期 token 被拒。
// jwt 库用真实墙钟校验 exp；令签发时刻为"过去"且 ttl 极短，使 exp 落在真实当前时间之前。
func TestExpiredToken(t *testing.T) {
	past := time.Now().Add(-2 * time.Hour) // 签发于 2 小时前
	a, err := New(newMemStore(), testSecret, 1, "open",
		WithIDGenerator(func() string { return "u-x" }),
		WithClock(func() time.Time { return past }),
	)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	reg, err := a.Register(context.Background(), RegisterRequest{Username: "e", Password: "pw123456"})
	if err != nil {
		t.Fatalf("Register: %v", err)
	}
	// token 的 exp = past + 1s，远早于真实当前时间 → 应判过期。
	if _, err := a.ParseToken(reg.Token); !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("expired token should be rejected, got %v", err)
	}
}

// TestNewNilStore 验证 nil store 构造失败。
func TestNewNilStore(t *testing.T) {
	if _, err := New(nil, testSecret, 3600, "off"); err == nil {
		t.Fatalf("New with nil store should error")
	}
}

// TestNewInvalidSignupFallsBackToOff 验证非法 signup 模式退化为 off。
func TestNewInvalidSignupFallsBackToOff(t *testing.T) {
	a, err := New(newMemStore(), testSecret, 3600, "garbage")
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	if a.AllowSignup() != "off" {
		t.Fatalf("invalid signup should fall back to off, got %q", a.AllowSignup())
	}
}

// TestIsUniqueViolationHelper 覆盖 util.go 的子串判定分支。
func TestIsUniqueViolationHelper(t *testing.T) {
	cases := []struct {
		err  error
		want bool
	}{
		{nil, false},
		{errors.New("UNIQUE constraint failed: user.username"), true},
		{errors.New("unique constraint failed: x"), true},
		{errors.New("some other db error"), false},
	}
	for _, c := range cases {
		if got := isUniqueViolation(c.err); got != c.want {
			t.Fatalf("isUniqueViolation(%v): got %v want %v", c.err, got, c.want)
		}
	}
	// 确保大小写不敏感路径（错误信息小写）也命中——modernc sqlite 实际为大写，这里双保险。
	if !isUniqueViolation(errors.New("UNIQUE constraint failed: a.b")) {
		t.Fatalf("expected unique violation match")
	}
}

// TestBootstrapEmptyCreatesAdmin 验证库空时 BootstrapAdmin 创建 admin 账号、签发可用 token。
func TestBootstrapEmptyCreatesAdmin(t *testing.T) {
	a, store := newTestAuth(t, "off")
	res, err := a.BootstrapAdmin(context.Background(), "root", "strongpw1")
	if err != nil {
		t.Fatalf("BootstrapAdmin: %v", err)
	}
	if res == nil {
		t.Fatalf("BootstrapAdmin on empty store should return result, got nil")
	}
	if res.Username != "root" || res.Password != "strongpw1" || res.Token == "" {
		t.Fatalf("bootstrap result mismatch: %+v", res)
	}
	// 角色应为 admin。
	if len(store.users) != 1 || store.users[0].Role != "admin" {
		t.Fatalf("expected one admin user, got %+v", store.users)
	}
	// token 可解析回新建 user_id。
	uid, err := a.ParseToken(res.Token)
	if err != nil || uid != res.UserID {
		t.Fatalf("ParseToken: uid=%q err=%v", uid, err)
	}
	// 用生成的凭据能登录。
	if _, err := a.Login(context.Background(), LoginRequest{Username: "root", Password: "strongpw1"}); err != nil {
		t.Fatalf("Login with bootstrap creds: %v", err)
	}
}

// TestBootstrapNonEmptyReturnsNil 验证库非空时 BootstrapAdmin 返回 nil（已引导过，不重复）。
func TestBootstrapNonEmptyReturnsNil(t *testing.T) {
	a, _ := newTestAuth(t, "open")
	if _, err := a.Register(context.Background(), RegisterRequest{Username: "someone", Password: "pw123456"}); err != nil {
		t.Fatalf("Register: %v", err)
	}
	res, err := a.BootstrapAdmin(context.Background(), "admin", "pw123456")
	if err != nil {
		t.Fatalf("BootstrapAdmin on non-empty: %v", err)
	}
	if res != nil {
		t.Fatalf("BootstrapAdmin on non-empty store should return nil, got %+v", res)
	}
}

// TestBootstrapDefaultsUsernamePassword 验证空用户名用默认 "admin"，空密码生成一次性随机密码。
func TestBootstrapDefaultsUsernamePassword(t *testing.T) {
	a, _ := newTestAuth(t, "off")
	res, err := a.BootstrapAdmin(context.Background(), "", "")
	if err != nil {
		t.Fatalf("BootstrapAdmin: %v", err)
	}
	if res == nil {
		t.Fatalf("expected result")
	}
	if res.Username != "admin" {
		t.Fatalf("default username: got %q want admin", res.Username)
	}
	if res.Password == "" || len(res.Password) < 16 {
		t.Fatalf("generated password should be non-empty and reasonably long, got %q", res.Password)
	}
	// 生成的随机密码仍可用于登录（证明落库哈希与原文一致）。
	if _, err := a.Login(context.Background(), LoginRequest{Username: "admin", Password: res.Password}); err != nil {
		t.Fatalf("Login with generated password: %v", err)
	}
}

// TestBootstrapShortExplicitPasswordRejected 验证显式传入过短密码被拒（一次性随机密码不受影响）。
func TestBootstrapShortExplicitPasswordRejected(t *testing.T) {
	a, _ := newTestAuth(t, "off")
	_, err := a.BootstrapAdmin(context.Background(), "admin", "ab")
	if !errors.Is(err, ErrInvalidCredentials) {
		t.Fatalf("short explicit password: want ErrInvalidCredentials, got %v", err)
	}
}
