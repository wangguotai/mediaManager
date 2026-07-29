package service

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

// 本文件实现 per-user 数据隔离的共享基础设施：
//   - UserDirs：按 user_id 解析该用户的 uploads / thumbnails / metadata /
//     video-meta / favorites.json / albums.json 路径，并按需懒创建。
//   - context key 与 helper：把中间件注入的 user_id 在 service 层取回，
//     供 MediaService 在每个请求中定位到对应用户的数据目录。
//
// 设计取舍：user_id 作为目录名的一部分直接拼路径，因此 validUserID 先做严格校验
// （非空、无路径穿越字符），从源头杜绝 "../" 之类的目录逃逸；UserDirs 本身不再重复校验。

// ctxUserIDKey 是请求 context 中携带已认证 user_id 的键。
// 与 gateway 包的私有键同名但类型不同（不同包的私有类型互不冲突），故 service 层
// 自带一份独立取值入口；实际注入由 gateway.authMiddleware 通过 server 的
// ctxUserIDKey 完成，MediaService 也接受调用方直接用 WithUserID 注入。
type ctxUserIDKey struct{}

// WithUserID 把 user_id 注入 context，返回新 context。供测试与无中间件场景手动注入。
func WithUserID(ctx context.Context, userID string) context.Context {
	return context.WithValue(ctx, ctxUserIDKey{}, userID)
}

// UserIDFromContext 取出 context 中的 user_id；未注入返回空串。
// MediaService 各方法据此定位 per-user 目录。空串表示"未认证/匿名"，
// 调用方应将其视为无权访问任何用户数据（返回未找到而非回退到全局目录）。
func UserIDFromContext(ctx context.Context) string {
	if v, ok := ctx.Value(ctxUserIDKey{}).(string); ok {
		return v
	}
	return ""
}

// maxUserDirSwept 记录已创建的用户目录数，仅用于观测，不影响逻辑。
var (
	userDirMu      sync.Mutex
	userDirCreated = map[string]bool{}
)

// validUserID 校验 user_id 是否可作为目录名安全使用。
// 拒绝空串、含路径分隔符、含 ".." 等可在目录树中逃逸的字符。
// 允许的字符集：字母、数字、以及 "-_："（UUID、自定义 id 常见字符）。
func validUserID(uid string) bool {
	if uid == "" || strings.ContainsAny(uid, "/\\") || strings.Contains(uid, "..") {
		return false
	}
	for _, r := range uid {
		switch {
		case r >= 'a' && r <= 'z',
			r >= 'A' && r <= 'Z',
			r >= '0' && r <= '9':
		case r == '-' || r == '_' || r == ':':
		default:
			return false
		}
	}
	return true
}

// UserDirs 按 user_id 解析该用户的全部数据子路径，并负责懒创建目录。
// 零值不可用；必须经 NewUserDirs 构造。
//
// usersRoot 是所有用户目录的父目录（如 data/users）。某用户 uid 的根为
// <usersRoot>/<uid>，其下固定子目录：uploads / thumbnails / metadata / video-meta。
type UserDirs struct {
	usersRoot string
}

// NewUserDirs 构造一个以 usersRoot 为所有用户目录父根的 UserDirs。
// usersRoot 由调用方（main.go）负责创建；此处不再 MkdirAll 以保持职责单一。
func NewUserDirs(usersRoot string) *UserDirs {
	return &UserDirs{usersRoot: usersRoot}
}

// UsersRoot 返回所有用户目录的父根，便于诊断/日志。
func (u *UserDirs) UsersRoot() string { return u.usersRoot }

// userRoot 返回某用户的根目录 <usersRoot>/<uid>；uid 非法时返回空串。
func (u *UserDirs) userRoot(uid string) string {
	if !validUserID(uid) {
		return ""
	}
	return filepath.Join(u.usersRoot, uid)
}

// ensureUserDir 确保 uid 根下指定子目录存在。子目录名固定且不含用户输入，
// 故无需再校验穿越。返回目录绝对路径；uid 非法返回空串与错误。
func (u *UserDirs) ensureUserDir(uid, sub string) (string, error) {
	root := u.userRoot(uid)
	if root == "" {
		return "", fmt.Errorf("invalid user_id")
	}
	dir := filepath.Join(root, sub)
	// 记录首次创建以便观测；创建本身幂等，重复调用无副作用。
	userDirMu.Lock()
	if !userDirCreated[uid] {
		userDirCreated[uid] = true
	}
	userDirMu.Unlock()
	if err := os.MkdirAll(dir, 0755); err != nil {
		return "", fmt.Errorf("create user dir %s: %w", dir, err)
	}
	return dir, nil
}

// UploadsDir 返回某用户的 uploads 目录并确保存在。uid 非法返回空串与错误。
func (u *UserDirs) UploadsDir(uid string) (string, error) {
	return u.ensureUserDir(uid, "uploads")
}

// ThumbnailsDir 返回某用户的缩略图目录并确保存在。
func (u *UserDirs) ThumbnailsDir(uid string) (string, error) {
	return u.ensureUserDir(uid, "thumbnails")
}

// MetadataDir 返回某用户上传元数据 sidecar 目录并确保存在。
func (u *UserDirs) MetadataDir(uid string) (string, error) {
	return u.ensureUserDir(uid, "metadata")
}

// VideoMetaDir 返回某用户视频 ffprobe 元数据缓存目录并确保存在。
func (u *UserDirs) VideoMetaDir(uid string) (string, error) {
	return u.ensureUserDir(uid, "video-meta")
}

// FavoritesPath 返回某用户的 favorites.json 路径，并确保其父目录（uid 根）存在。
func (u *UserDirs) FavoritesPath(uid string) (string, error) {
	if _, err := u.ensureUserRoot(uid); err != nil {
		return "", err
	}
	return filepath.Join(u.userRoot(uid), "favorites.json"), nil
}

// AlbumsPath 返回某用户的 albums.json 路径，并确保其父目录（uid 根）存在。
func (u *UserDirs) AlbumsPath(uid string) (string, error) {
	if _, err := u.ensureUserRoot(uid); err != nil {
		return "", err
	}
	return filepath.Join(u.userRoot(uid), "albums.json"), nil
}

// ensureUserRoot 在用 "." 子目录调用 MkdirAll 时实际确保 uid 根存在；
// 下列 helper 把它收敛为对 userRoot 的直接 MkdirAll，避免 "nodepth" 歧义。
func (u *UserDirs) ensureUserRoot(uid string) (string, error) {
	root := u.userRoot(uid)
	if root == "" {
		return "", fmt.Errorf("invalid user_id")
	}
	userDirMu.Lock()
	if !userDirCreated[uid] {
		userDirCreated[uid] = true
	}
	userDirMu.Unlock()
	if err := os.MkdirAll(root, 0755); err != nil {
		return "", fmt.Errorf("create user root %s: %w", root, err)
	}
	return root, nil
}
