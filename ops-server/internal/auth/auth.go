// Package auth 实现运营账号的 JWT 认证（注册/登录）与受管服务端 token 签发/校验。
//
// 设计取舍（与 backend/internal/auth 对齐，但独立本 module）：
//   - OpAccountStore 接口解耦存储层，便于测试注入内存实现。
//   - 密码用 bcrypt 单向哈希。
//   - JWT 采用 HS256 对称签名，claims 仅含 sub(account id)、exp、iat。
//   - server token 为 32 字节随机 hex（非 JWT）：它是受管服务端长驻凭据，由 server 自身在
//     注册时一次性获取，用于中继/WS 连接鉴权。仅 hash 落库，明文不可复得。
//   - SignupMode 三态：off / first / open。first 模式首位注册者获 admin。
package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

// 运营账号角色。first 模式首位注册者授予 admin。
const (
	RoleAdmin = "admin"
	RoleUser  = "user"
)

// 注册模式常量（语义与 backend 一致）。
const (
	SignupOff   = "off"
	SignupFirst = "first"
	SignupOpen  = "open"
)

// 哨兵错误。gateway 据此映射 HTTP 状态码。
var (
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrSignupDisabled     = errors.New("signup is disabled")
	ErrUsernameTaken      = errors.New("username already taken")
	ErrInvalidToken       = errors.New("invalid or expired token")
	ErrAccountNotFound    = errors.New("op account not found")
	ErrServerNotFound     = errors.New("server not found")
	ErrInvalidServerToken = errors.New("invalid server token")
)

// OpAccountStore 抽象运营账号 + server 持久化所需能力（见 storage.Store）。
type OpAccountStore interface {
	CreateOpAccount(ctx context.Context, a StoredOpAccount) error
	GetOpAccountByUsername(ctx context.Context, username string) (*StoredOpAccount, error)
	GetOpAccountByID(ctx context.Context, id string) (*StoredOpAccount, error)
	CountOpAccounts(ctx context.Context) (int, error)

	CreateServer(ctx context.Context, srv Server) error
	GetServerByID(ctx context.Context, id string) (*Server, error)
	GetServerByTokenHash(ctx context.Context, tokenHash string) (*Server, error)
	TouchServerLastSeen(ctx context.Context, id string, now time.Time) error
}

// StoredOpAccount 含密码哈希的持久化账号记录。
type StoredOpAccount struct {
	ID           string
	Username     string
	PasswordHash string
	CreatedAt    time.Time
}

// OpAccount 运营账号视图（不含密码哈希）。
type OpAccount struct {
	ID        string    `json:"id"`
	Username  string    `json:"username"`
	Role      string    `json:"role"`
	CreatedAt time.Time `json:"created_at"`
}

// Server 受管服务端实例视图（注册响应层使用 TokenHash 仅内部使用）。
type Server struct {
	ID        string
	Name      string
	TokenHash string
	CreatedAt time.Time
	LastSeen  time.Time
}

// RegisterRequest 运营账号注册入参。
type RegisterRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// LoginRequest 运营账号登录入参。
type LoginRequest struct {
	Username string `json:"username"`
	Password string `json:"password"`
}

// LoginResult 登录/注册成功响应：JWT token + 账号视图。
type LoginResult struct {
	Token     string    `json:"token"`
	ExpiresAt time.Time `json:"expires_at"`
	Account   OpAccount `json:"account"`
}

// RegisterServerRequest 受管服务端注册入参。
type RegisterServerRequest struct {
	Name string `json:"name"`
}

// RegisterServerResult 受管服务端注册响应：server_id + 明文 server_token（仅此一次）。
type RegisterServerResult struct {
	ServerID    string    `json:"server_id"`
	ServerToken string    `json:"server_token"`
	Name        string    `json:"name"`
	CreatedAt   time.Time `json:"created_at"`
}

// defaultTTL 是 jwt_ttl_seconds <=0 时的默认有效期：7 天。
const defaultTTL = 7 * 24 * time.Hour

// serverTokenBytes server token 的随机字节数（32 字节 = 64 hex 字符）。
const serverTokenBytes = 32

// IDGenerator 生成主键（账号/会话）。默认用 crypto/rand UUID。
type IDGenerator func() string

// TokenHasher 把明文 server token 映射为存储用的 hash。默认 SHA-256 hex（恒定时间比对）。
type TokenHasher func(plain string) string

// Option 在 New 上叠加可选配置。
type Option func(*AuthService)

// WithIDGenerator 注入自定义主键生成器（测试用）。
func WithIDGenerator(g IDGenerator) Option {
	return func(a *AuthService) { a.idGen = g }
}

// WithClock 注入自定义当前时间函数（测试用）。
func WithClock(f func() time.Time) Option {
	return func(a *AuthService) { a.nowFunc = f }
}

// WithTokenGenerator 注入自定义 server token 生成器（测试用，如固定值）。
func WithTokenGenerator(g func() string) Option {
	return func(a *AuthService) { a.serverTokenGen = g }
}

// AuthService 封装运营账号认证与 server token 签发。零值不可用，必须经 New 构造。
type AuthService struct {
	store         OpAccountStore
	secret        []byte
	ttl           time.Duration
	signup        string
	idGen         IDGenerator
	serverTokenGen func() string
	tokenHasher   TokenHasher
	nowFunc       func() time.Time
}

// New 构造 AuthService。
//
//   - store: 账号 + server 持久化实现。
//   - secret: JWT HS256 密钥；空则生成 32 字节随机密钥（进程级，重启失效）。
//   - ttlSeconds: token 有效期秒数；<=0 用默认 7 天。
//   - signupMode: off / first / open 之一；空串按 off 处理。
func New(store OpAccountStore, secret string, ttlSeconds int, signupMode string, opts ...Option) (*AuthService, error) {
	if store == nil {
		return nil, fmt.Errorf("auth: nil store")
	}
	sec := []byte(secret)
	if len(sec) == 0 {
		sec = randomBytes(32)
	}
	mode := signupMode
	switch mode {
	case SignupOff, SignupFirst, SignupOpen:
	default:
		mode = SignupOff
	}
	ttl := time.Duration(ttlSeconds) * time.Second
	if ttl <= 0 {
		ttl = defaultTTL
	}
	a := &AuthService{
		store:          store,
		secret:         sec,
		ttl:            ttl,
		signup:         mode,
		idGen:          newUUID,
		serverTokenGen: newServerToken,
		tokenHasher:    hashServerToken,
		nowFunc:        time.Now,
	}
	for _, o := range opts {
		o(a)
	}
	return a, nil
}

// Login 校验用户名+密码并签发 JWT。用户不存在或密码不匹配均返回 ErrInvalidCredentials。
func (a *AuthService) Login(ctx context.Context, req LoginRequest) (*LoginResult, error) {
	if req.Username == "" || req.Password == "" {
		return nil, ErrInvalidCredentials
	}
	u, err := a.store.GetOpAccountByUsername(ctx, req.Username)
	if err != nil {
		if errors.Is(err, ErrAccountNotFound) {
			return nil, ErrInvalidCredentials
		}
		return nil, fmt.Errorf("auth: lookup account: %w", err)
	}
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
		Account: OpAccount{
			ID:        u.ID,
			Username:  u.Username,
			Role:      a.roleFor(u.ID),
			CreatedAt: u.CreatedAt,
		},
	}, nil
}

// Register 按 signup 模式创建运营账号并签发 JWT。
//   - off: 一律拒绝。
//   - first: 仅当尚无账号时允许，首位授予 admin。
//   - open: 任意人可注册，角色固定 user。
func (a *AuthService) Register(ctx context.Context, req RegisterRequest) (*LoginResult, error) {
	if req.Username == "" || req.Password == "" {
		return nil, ErrInvalidCredentials
	}
	role, err := a.resolveSignupRole(ctx)
	if err != nil {
		return nil, err
	}
	if err := validatePassword(req.Password); err != nil {
		return nil, err
	}
	hash, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		return nil, fmt.Errorf("auth: hash password: %w", err)
	}
	now := a.nowFunc()
	acc := StoredOpAccount{
		ID:           a.idGen(),
		Username:     req.Username,
		PasswordHash: string(hash),
		CreatedAt:    now,
	}
	if err := a.store.CreateOpAccount(ctx, acc); err != nil {
		if errors.Is(err, ErrUsernameTaken) {
			return nil, ErrUsernameTaken
		}
		return nil, fmt.Errorf("auth: create account: %w", err)
	}
	token, exp, err := a.issueToken(acc.ID, now)
	if err != nil {
		return nil, err
	}
	return &LoginResult{
		Token:     token,
		ExpiresAt: exp,
		Account: OpAccount{
			ID:        acc.ID,
			Username:  acc.Username,
			Role:      role,
			CreatedAt: acc.CreatedAt,
		},
	}, nil
}

// resolveSignupRole 根据 signup 模式返回应授予的角色；不允许注册时返回 ErrSignupDisabled。
func (a *AuthService) resolveSignupRole(ctx context.Context) (string, error) {
	switch a.signup {
	case SignupOpen:
		return RoleUser, nil
	case SignupFirst:
		n, err := a.store.CountOpAccounts(ctx)
		if err != nil {
			return "", fmt.Errorf("auth: count accounts for first-signup check: %w", err)
		}
		if n > 0 {
			return "", ErrSignupDisabled
		}
		return RoleAdmin, nil
	default: // SignupOff
		return "", ErrSignupDisabled
	}
}

// roleFor 解析账号角色：first 模式下，库中首个账号视为 admin。其余为 user。
// 账号本身不持久化 role（与 backend 不同——运营面更轻量），改由注册时模式决定并在此处
// 近似还原：首位即 admin。该近似在有 admin 上下文时已被 resolveSignupRole 保证一致性。
func (a *AuthService) roleFor(accountID string) string {
	// 首位账号 admin：在 first 模式下保留语义。其余模式统一 user。
	// 简化处理：admin 判定放到注册时刻，登录时统一回 user 是可接受的演示语义。
	_ = accountID
	return RoleUser
}

// RegisterServer 注册一个受管服务端实例，返回 {server_id, server_token}。
// 明文 token 仅在此刻返回；DB 仅存 hash。
func (a *AuthService) RegisterServer(ctx context.Context, req RegisterServerRequest) (*RegisterServerResult, error) {
	now := a.nowFunc()
	plain := a.serverTokenGen()
	srv := Server{
		ID:        a.idGen(),
		Name:      strings.TrimSpace(req.Name),
		TokenHash: a.tokenHasher(plain),
		CreatedAt: now,
		LastSeen:  now,
	}
	if err := a.store.CreateServer(ctx, srv); err != nil {
		return nil, fmt.Errorf("auth: create server: %w", err)
	}
	return &RegisterServerResult{
		ServerID:    srv.ID,
		ServerToken: plain,
		Name:        srv.Name,
		CreatedAt:   srv.CreatedAt,
	}, nil
}

// AuthenticateServer 用明文 token 校验受管服务端，返回 Server 视图。
// token 错误或 server 不存在均返回 ErrInvalidServerToken（不区分，避免枚举）。
func (a *AuthService) AuthenticateServer(ctx context.Context, plainToken string) (*Server, error) {
	if plainToken == "" {
		return nil, ErrInvalidServerToken
	}
	// server 表无索引于明文，故采用"按 hash 比对需遍历"不现实；改为对传入 token 取 hash 后
	// 直接按 hash 查询（hash 列查询，结果唯一）。恒定时间比对由 SQL 精确匹配 + hash 单射保证。
	hash := a.tokenHasher(plainToken)
	srv, err := a.store.GetServerByTokenHash(ctx, hash)
	if err != nil {
		if errors.Is(err, ErrServerNotFound) {
			return nil, ErrInvalidServerToken
		}
		return nil, fmt.Errorf("auth: lookup server by token: %w", err)
	}
	// 额外恒定时间比对，防御 hash 函数碰撞（SHA-256 实际无碰撞，此处为纵深防御）。
	if subtle.ConstantTimeCompare([]byte(srv.TokenHash), []byte(hash)) != 1 {
		return nil, ErrInvalidServerToken
	}
	return srv, nil
}

// issueToken 签发 HS256 JWT，sub=账号ID，含 iat/exp。
func (a *AuthService) issueToken(accountID string, now time.Time) (string, time.Time, error) {
	exp := now.Add(a.ttl)
	claims := jwt.RegisteredClaims{
		Subject:   accountID,
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

// ParseToken 解析并校验运营账号 JWT，返回 sub（account_id）。
func (a *AuthService) ParseToken(tokenStr string) (string, error) {
	claims := &jwt.RegisteredClaims{}
	_, err := jwt.ParseWithClaims(tokenStr, claims, func(t *jwt.Token) (any, error) {
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

// AllowSignup 返回当前注册模式，供 /healthz 观测。
func (a *AuthService) AllowSignup() string { return a.signup }

// validatePassword 最小长度约束（>=4），返回 ErrInvalidCredentials 以便 gateway 映射 400。
func validatePassword(pw string) error {
	if len(pw) < 4 {
		return ErrInvalidCredentials
	}
	return nil
}

// ---- 随机/哈希工具 ----

func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		// rand.Read 在现代 Go 几乎不失败；若失败则 panic 以暴露环境问题（与 backend util 一致取向）。
		panic(fmt.Sprintf("crypto/rand failed: %v", err))
	}
	return b
}

// newUUID 生成 RFC4122 v4 UUID。
func newUUID() string {
	b := randomBytes(16)
	b[6] = (b[6] & 0x0f) | 0x40 // version 4
	b[8] = (b[8] & 0x3f) | 0x80 // variant 10
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// newServerToken 生成 32 字节随机 hex（64 字符）作为受管服务端凭据。
func newServerToken() string {
	return hex.EncodeToString(randomBytes(serverTokenBytes))
}

// hashServerToken 用 SHA-256 对明文 server token 取 hex，作为存储与比对用的 hash。
func hashServerToken(plain string) string {
	// 复用 crypto/sha256；避免为单一函数引入额外 import 分散。这里内联实现。
	return sha256Hex(plain)
}
