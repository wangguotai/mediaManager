package auth

import (
	"context"
	"errors"

	"media-manager/backend/internal/storage"
)

// StoreBridge 把 *storage.Store 适配为 auth.UserStore 接口。
//
// 必要性：auth.UserStore 的方法签名（值类型 StoredUser、未命中返回
// auth.ErrUserNotFound）与 storage.Store（指针 *User、未命中返回
// storage.ErrNotFound）并不一致，即 auth.go 注释中“storage.Store 天然满足
// 此接口”在当前签名下并不成立。本桥在此做字段平移与错误码翻译，使
// gateway 只面向 auth.UserStore 抽象，保持两层解耦。
//
// 零值不可用；必须经 NewStoreBridge 构造。
type StoreBridge struct {
	store *storage.Store
}

// NewStoreBridge 用一个已打开的 storage.Store 构造 auth.UserStore 实现。
func NewStoreBridge(s *storage.Store) *StoreBridge {
	return &StoreBridge{store: s}
}

// 编译期断言：*StoreBridge 实现 auth.UserStore。若接口演进导致不满足，
// 会在此立刻编译失败而非推迟到 wiring 处。
var _ UserStore = (*StoreBridge)(nil)

// CreateUser 把 auth.StoredUser 平移成 storage.User 并委托给 storage 层插入。
// storage 层会在 username 唯一约束冲突时返回包裹错误，isUniqueViolation 据
// 此识别（auth.Register 据此映射为 ErrUsernameTaken）。
func (b *StoreBridge) CreateUser(ctx context.Context, u StoredUser) error {
	return b.store.CreateUser(ctx, &storage.User{
		ID:           u.ID,
		Username:     u.Username,
		PasswordHash: u.PasswordHash,
		Role:         u.Role,
		CreatedAt:    u.CreatedAt,
	})
}

// GetUserByUsername 按用户名查询，把 storage.ErrNotFound 翻译为 auth.ErrUserNotFound，
// 使 auth.Login 能用 errors.Is 把“用户不存在”归入 ErrInvalidCredentials，
// 与“系统错误”区分开（避免用户名枚举 + 不污染 5xx 路径）。
func (b *StoreBridge) GetUserByUsername(ctx context.Context, username string) (*StoredUser, error) {
	u, err := b.store.GetUserByUsername(ctx, username)
	if err != nil {
		if errors.Is(err, storage.ErrNotFound) {
			return nil, ErrUserNotFound
		}
		return nil, err
	}
	return toStoredUser(u), nil
}

// ListUsers 返回全部用户，供 first 模式判断“已有用户”。
func (b *StoreBridge) ListUsers(ctx context.Context) ([]*StoredUser, error) {
	users, err := b.store.ListUsers(ctx)
	if err != nil {
		return nil, err
	}
	out := make([]*StoredUser, 0, len(users))
	for _, u := range users {
		out = append(out, toStoredUser(u))
	}
	return out, nil
}

// toStoredUser 在两条 user 视图间做纯字段拷贝。CreatedAt 取 storage.Time
// 的零值语义（timeFromVal 解析失败返回零值），auth 层不额外兜底——
// 零值 CreatedAt 仅影响 JWT 响应里展示的时间，不参与 token 签发。
func toStoredUser(u *storage.User) *StoredUser {
	if u == nil {
		return nil
	}
	return &StoredUser{
		ID:           u.ID,
		Username:     u.Username,
		PasswordHash: u.PasswordHash,
		Role:         u.Role,
		CreatedAt:    u.CreatedAt,
	}
}
