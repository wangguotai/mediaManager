// Package auth 实现 JWT 用户认证：注册、登录、密码哈希与 token 签发/校验。
//
// 设计取舍：
//   - 通过 UserStore 接口解耦存储层（当前实现为 storage.Store），便于测试注入
//     内存假实现，也避免 auth 直接 import storage 形成 gateway→auth→storage 之外的
//     不必要耦合。
//   - 密码用 bcrypt（golang.org/x/crypto/bcrypt）单向哈希，cost 用 bcrypt.DefaultCost(10)，
//     兼顾安全与注册/Login 延迟（约 ~50ms 量级，可接受）。
//   - JWT 采用 HS256 对称签名（golang-jwt/jwt/v5），claims 仅含 sub(user id)、exp、iat，
//     满足"解析出 user_id 注入请求"的最小需求，不引入额外状态/DB 查询于中间件路径。
//   - SignupMode 三态语义见 config 包：off / first / open。first 模式下首位注册者获 admin。
package auth

import (
	"context"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"

	"media-manager/backend/internal/config"
)

// 用户角色常量。first 模式下的首位注册者授予 Admin，其余为普通 User。
const (
	RoleAdmin = "admin"
	RoleUser  = "user"
)

// 哨兵错误。gateway 据此映射 HTTP 状态码。
var (
	// ErrInvalidCredentials 登录用户名不存在或密码不匹配。
	ErrInvalidCredentials = errors.New("invalid credentials")
	// ErrSignupDisabled 当前 signup 模式不允许注册（off，或 first 模式且已有用户）。
	ErrSignupDisabled = errors.New("signup is disabled")
	// ErrUsernameTaken 注册时用户名已被占用。
	ErrUsernameTaken = errors.New("username already taken")
	// ErrInvalidToken JWT 缺失/格式错误/签名不符/已过期。
	ErrInvalidToken = errors.New("invalid or expired token")
	// ErrPasswordTooWeak 密码不满足复杂度策略（长度不足或缺少字母/数字）。
	ErrPasswordTooWeak = errors.New("password too weak: must contain letters and digits")
)

// User 是认证层使用的用户视图，仅含 JWT 签发与响应所需字段，
// 与 storage.User 解耦（后者还含 PasswordHash 等存储字段）。
type User struct {
	ID        string    `json:"id"`
	Username  string    `json:"username"`
	Role      string    `json:"role"`
	CreatedAt time.Time `json:"created_at"`
}

// UserStore 抽象认证所需的最小用户持久化能力。storage.Store天然满足此接口；
// 测试可用内存实现替换。方法签名与 storage 包一一对应（见 internal/storage/repository.go）。
type UserStore interface {
	// CreateUser 落库一个新用户；username 唯一约束冲突时返回非 nil error。
	CreateUser(ctx context.Context, u StoredUser) error
	// GetUserByUsername 按用户名查询；未命中返回 ErrUserNotFound 包装错误。
	GetUserByUsername(ctx context.Context, username string) (*StoredUser, error)
	// GetUserByID 按用户主键 id 查询；未命中返回 ErrUserNotFound 包装错误。
	// 供 ChangePassword 在已知 token 解析出的 user.id 时取记录校验旧密码。
	GetUserByID(ctx context.Context, userID string) (*StoredUser, error)
	// ListUsers 返回全部用户，用于 first 模式判断"是否已有用户"。
	ListUsers(ctx context.Context) ([]*StoredUser, error)
	// UpdatePassword 把指定 user 的密码哈希改写为新哈希（调用方负责 bcrypt 哈希）。
	// 供 ChangePassword 调用；未命中返回 ErrUserNotFound 包装错误（由具体实现翻译）。
	UpdatePassword(ctx context.Context, userID, newPasswordHash string) error
}

// StoredUser 是 UserStore 传递给 auth 的持久化用户记录，含密码哈希。
// 它与 storage.User 字段对齐（ID/Username/PasswordHash/Role/CreatedAt），
// 由 storage 适配器在两层间转换。
type StoredUser struct {
	ID           string
	Username     string
	PasswordHash string
	Role         string
	CreatedAt    time.Time
}

// ErrUserNotFound 是 UserStore 未命中的标志错误。auth 用 errors.Is 识别，
// 把"用户不存在"与"系统错误"区分开（前者归入 ErrInvalidCredentials）。
var ErrUserNotFound = errors.New("user not found")

// LoginRequest 是登录入参。
type LoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// RegisterRequest 是注册入参。Role 由 auth 根据 signup 模式决定，调用方无需传。
type RegisterRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// LoginResult 是登录成功响应，与接口契约 {token, expires_at, user} 对齐。
type LoginResult struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
	User      User      `json:"user"`
}

// defaultTTL 是 jwt_ttl_seconds <=0 时的默认有效期：7 天。
const defaultTTL = 7 * 24 * time.Hour

// AuthService 封装认证逻辑。零值不可用，必须经 New 构造。
// secret 为空时 New 会生成进程级随机密钥（重启失效），仅适合开发。
type AuthService struct {
	store   UserStore
	secret  []byte
	ttl     time.Duration
	signup  string // config.Signup* 之一
	idGen   IDGenerator
	nowFunc func() time.Time // 可注入，便于测试
}

// IDGenerator 生成用户主键。默认用 crypto/rand UUID；测试可注入固定值。
type IDGenerator func() string

// Option 在 New 上叠加可选配置（如注入 ID 生成器、时钟），便于测试。
type Option func(*AuthService)

// WithIDGenerator 注入自定义用户 ID 生成器。
func WithIDGenerator(g IDGenerator) Option {
	return func(a *AuthService) { a.idGen = g }
}

// WithClock 注入自定义当前时间函数（测试用）。
func WithClock(f func() time.Time) Option {
	return func(a *AuthService) { a.nowFunc = f }
}

// New 构造 AuthService。
//   - store: 用户持久化实现。
//   - secret: JWT HS256 密钥；为空时生成 32 字节随机密钥（进程级，重启失效）。
//   - ttlSeconds: token 有效期秒数；<=0 用默认 7 天。
//   - signupMode: config.SignupOff / SignupFirst / SignupOpen 之一；空串按 off 处理。
func New(store UserStore, secret string, ttlSeconds int, signupMode string, opts ...Option) (*AuthService, error) {
	if store == nil {
		return nil, fmt.Errorf("auth: nil user store")
	}
	sec := []byte(secret)
	if len(sec) == 0 {
		// 进程级随机密钥：重启后旧 token 全部作废。仅用于无配置的开发场景。
		sec = randomBytes(32)
	}
	mode := signupMode
	switch mode {
	case config.SignupOff, config.SignupFirst, config.SignupOpen:
		// 合法三态。
	default:
		mode = config.SignupOff // 非法/空值退化为最安全的 off。
	}
	ttl := time.Duration(ttlSeconds) * time.Second
	if ttl <= 0 {
		ttl = defaultTTL
	}
	a := &AuthService{
		store:   store,
		secret:  sec,
		ttl:     ttl,
		signup:  mode,
		idGen:   newUUID,
		nowFunc: time.Now,
	}
	for _, o := range opts {
		o(a)
	}
	return a, nil
}

// Login 校验用户名+密码，签发 JWT。用户不存在或密码不匹配均返回 ErrInvalidCredentials
// （不区分二者，避免用户名枚举）。
func (a *AuthService) Login(ctx context.Context, req LoginRequest) (*LoginResult, error) {
	if req.Username == "" || req.Password == "" {
		return nil, ErrInvalidCredentials
	}
	u, err := a.store.GetUserByUsername(ctx, req.Username)
	if err != nil {
		if errors.Is(err, ErrUserNotFound) {
			return nil, ErrInvalidCredentials
		}
		return nil, fmt.Errorf("auth: lookup user: %w", err)
	}
	// bcrypt.CompareHashAndPassword 对不匹配返回错误；统一映射为 ErrInvalidCredentials。
	if err := bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(req.Password)); err != nil {
		return nil, ErrInvalidCredentials
	}
	token, exp, err := a.issueToken(u.ID, a.nowFunc())
	if err != nil {
		return nil, err
	}
	return &LoginResult{
		Token:     token,
		ExpiresAt: exp,
		User: User{
			ID:        u.ID,
			Username:  u.Username,
			Role:      u.Role,
			CreatedAt: u.CreatedAt,
		},
	}, nil
}

// Register 按 signup 模式创建用户并签发 token。
//   - off: 任何注册一律 ErrSignupDisabled。
//   - first: 仅当 store 中尚无任何用户时允许；首位注册者授予 admin 角色。否则 ErrSignupDisabled。
//   - open: 任意人可注册，角色固定 user。
//
// 用户名已存在（唯一约束冲突）返回 ErrUsernameTaken。
func (a *AuthService) Register(ctx context.Context, req RegisterRequest) (*LoginResult, error) {
	if req.Username == "" || req.Password == "" {
		return nil, ErrInvalidCredentials
	}
	role, err := a.resolveSignupRole(ctx)
	if err != nil {
		return nil, err
	}
	if err := a.validatePassword(req.Password); err != nil {
		return nil, err
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("auth: hash password: %w", err)
	}
	now := a.nowFunc()
	u := StoredUser{
		ID:           a.idGen(),
		Username:     req.Username,
		PasswordHash: string(hash),
		Role:         role,
		CreatedAt:    now,
	}
	if err := a.store.CreateUser(ctx, u); err != nil {
		if isUniqueViolation(err) {
			return nil, ErrUsernameTaken
		}
		return nil, fmt.Errorf("auth: create user: %w", err)
	}
	token, exp, err := a.issueToken(u.ID, now)
	if err != nil {
		return nil, err
	}
	return &LoginResult{
		Token:     token,
		ExpiresAt: exp,
		User: User{
			ID:        u.ID,
			Username:  u.Username,
			Role:      u.Role,
			CreatedAt: u.CreatedAt,
		},
	}, nil
}

// resolveSignupRole 根据 signup 模式返回应授予的角色；不允许注册时返回 ErrSignupDisabled。
func (a *AuthService) resolveSignupRole(ctx context.Context) (string, error) {
	switch a.signup {
	case config.SignupOpen:
		return RoleUser, nil
	case config.SignupFirst:
		users, err := a.store.ListUsers(ctx)
		if err != nil {
			return "", fmt.Errorf("auth: list users for first-signup check: %w", err)
		}
		if len(users) > 0 {
			// 已有用户，首位引导已完成 → 关闭注册。
			return "", ErrSignupDisabled
		}
		return RoleAdmin, nil
	default: // SignupOff
		return "", ErrSignupDisabled
	}
}

// minPasswordLength 是允许的最小密码长度。V5 安全基线要求至少 8 位，避免弱口令。
const minPasswordLength = 8

// validatePassword 施加密码策略：最小长度 + 复杂度（至少含字母和数字）。
// V6 §2.7（QA P1-4）：在 V5 长度 8 基础上加复杂度要求，拒绝纯字母或纯数字弱口令。
// 校验失败返回 ErrPasswordTooWeak（含长度不足或缺字母/数字两类情形），
// gateway 据此映射为 400 而非 500。
func (a *AuthService) validatePassword(pw string) error {
	if len(pw) < minPasswordLength {
		return ErrPasswordTooWeak
	}
	// 复杂度：至少一个字母 [a-zA-Z] + 至少一个数字 [0-9]。
	// 用标准库 strings 检查，不引 regexp，依赖最小。
	hasLetter := false
	hasDigit := false
	for _, r := range pw {
		switch {
		case (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z'):
			hasLetter = true
		case r >= '0' && r <= '9':
			hasDigit = true
		}
	}
	if !hasLetter || !hasDigit {
		return ErrPasswordTooWeak
	}
	return nil
}

// issueToken 签发 HS256 JWT，sub=用户ID，含 iat/exp。
func (a *AuthService) issueToken(userID string, now time.Time) (string, time.Time, error) {
	exp := now.Add(a.ttl)
	claims := jwt.RegisteredClaims{
		Subject:   userID,
		IssuedAt:  jwt.NewNumericDate(now),
		ExpiresAt: jwt.NewNumericDate(exp),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	signed, err := token.SignedString(a.secret)
	if err != nil {
		return "", time.Time{}, fmt.Errorf("auth: sign token: %w", err)
	}
	return signed, exp, nil
}

// ParseToken 解析并校验 Bearer token，返回 sub（user_id）。任何失败（格式/签名/过期）返回 ErrInvalidToken。
func (a *AuthService) ParseToken(tokenStr string) (string, error) {
	claims := &jwt.RegisteredClaims{}
	_, err := jwt.ParseWithClaims(tokenStr, claims, func(t *jwt.Token) (any, error) {
		// 强制 HMAC 算法族，防止 alg=none / RS→HS 等降级攻击。
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, fmt.Errorf("unexpected signing method: %v", t.Header["alg"])
		}
		return a.secret, nil
	})
	if err != nil {
		return "", ErrInvalidToken
	}
	if claims.Subject == "" {
		return "", ErrInvalidToken
	}
	return claims.Subject, nil
}

// AllowSignup 返回当前注册模式，供 /healthz 或调试端点观测。
func (a *AuthService) AllowSignup() string { return a.signup }

// ChangePassword 校验旧密码后更新为新密码。供 POST /api/auth/change-password 调用，
// userID 由 JWT 中间件解析注入（即只能改自己的密码，防横向越权）。
//
// 语义：
//   - userID 为空、用户不存在、旧密码不匹配 → ErrInvalidCredentials（不区分，防枚举）。
//   - newPassword 不满足复杂度策略(长度<8或缺字母/数字) → ErrPasswordTooWeak。
//   - 底层 UpdatePassword 未命中 → ErrInvalidCredentials（理论上不会，因前面已查到；防御）。
//   - 成功后不签发新 token——旧 token 仍有效至过期；如需强制重登可后续引入 jti 版本号。
func (a *AuthService) ChangePassword(ctx context.Context, userID, oldPassword, newPassword string) error {
	if userID == "" {
		return ErrInvalidCredentials
	}
	// 先校验新密码长度，避免即便旧密码正确也写入弱口令。
	if err := a.validatePassword(newPassword); err != nil {
		return err
	}
	u, err := a.store.GetUserByID(ctx, userID)
	if err != nil {
		// 用户不存在 → 归入 ErrInvalidCredentials，与"旧密码错"不可区分，防枚举。
		return ErrInvalidCredentials
	}
	// 校验旧密码哈希。
	if err := bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(oldPassword)); err != nil {
		return ErrInvalidCredentials
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(newPassword), bcrypt.DefaultCost)
	if err != nil {
		return fmt.Errorf("auth: hash password on change: %w", err)
	}
	if err := a.store.UpdatePassword(ctx, userID, string(hash)); err != nil {
		return ErrInvalidCredentials
	}
	return nil
}

// BootstrapResult 是 BootstrapAdmin 成功创建首个超管账号后返回的引导信息。
// main.go 据此在启动日志中打印一次性凭据与 token，使运维首次即可登录。
type BootstrapResult struct {
	Username  string    // 实际落库的用户名（等于入参或默认 "admin"）。
	Password  string    // 实际使用的密码（入参为空时为生成的一次性随机密码）。
	Token     string    // 已签发的 admin JWT，可直接用于后续 API。
	ExpiresAt time.Time // token 过期时间，供日志展示。
	UserID    string    // 新建账号的 ID。
}

// defaultBootstrapUsername 是未指定用户名时的默认超管账号名。
const defaultBootstrapUsername = "admin"

// BootstrapAdmin 在 user 表为空时创建首个 admin 账号并签发 token，返回引导信息。
//
// 用途：allow_signup=off（默认）下首次启动无人能注册，存在"鸡生蛋"死锁——
// 此方法在启动期旁路 signup 模式直接建超管，并把一次性凭据打印到日志。
//
// 语义：
//   - 库非空（已存在任意用户）→ 返回 (nil, nil)，视为已引导过，不重复创建。
//   - 用户名为空 → 用默认 "admin"。
//   - 密码为空 → 生成 16 字节随机 hex 作为一次性密码，调用方应提示尽快修改。
//   - 创建唯一冲突（理论上库空不应发生）→ 返回 (nil, ErrUsernameTaken)，交调用方决定。
//
// 该方法不受 allow_signup 约束——引导本身是特权操作，仅在库空时可用一次。
func (a *AuthService) BootstrapAdmin(ctx context.Context, username, password string) (*BootstrapResult, error) {
	users, err := a.store.ListUsers(ctx)
	if err != nil {
		return nil, fmt.Errorf("auth: bootstrap list users: %w", err)
	}
	if len(users) > 0 {
		// 已有用户，无需引导。
		return nil, nil
	}

	if username = strings.TrimSpace(username); username == "" {
		username = defaultBootstrapUsername
	}
	generated := false
	if password == "" {
		// 16 字节随机 = 32 hex 字符，足够一次性强度；调用方须提示尽快修改。
		password = hex.EncodeToString(randomBytes(16))
		generated = true
	}
	if err := a.validatePassword(password); err != nil {
		// 一次性随机密码不会触发；仅防御调用方传入过弱（过短或缺字母/数字）的显式密码。
		return nil, err
	}

	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("auth: bootstrap hash password: %w", err)
	}
	now := a.nowFunc()
	uid := a.idGen()
	u := StoredUser{
		ID:           uid,
		Username:     username,
		PasswordHash: string(hash),
		Role:         RoleAdmin,
		CreatedAt:    now,
	}
	if err := a.store.CreateUser(ctx, u); err != nil {
		if isUniqueViolation(err) {
			return nil, ErrUsernameTaken
		}
		return nil, fmt.Errorf("auth: bootstrap create user: %w", err)
	}
	token, exp, err := a.issueToken(uid, now)
	if err != nil {
		return nil, err
	}
	_ = generated // 保留语义供调用方区分（当前 main.go 据打印文案固定提示修改）。
	return &BootstrapResult{
		Username:  username,
		Password:  password,
		Token:     token,
		ExpiresAt: exp,
		UserID:    uid,
	}, nil
}
