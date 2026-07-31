package service

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
)

// FavoriteStore 现在按 user_id 维度隔离持久化收藏列表。
//
// 改造前：单一全局 filePath + 全局 favorites map。
// 改造后：以 UserDirs 为根，按需为每个 user_id 懒创建一个独立的 perUserFav（对应
// data/users/{uid}/favorites.json），各自持锁、各自落盘。不同用户的收藏互不可见，
// 也互不锁竞争。仅经 NewFavoriteStoreWithDirs 构造；dirs 为 nil 时返回 nil，
// 调用方据此禁用收藏功能。
type FavoriteStore struct {
	dirs *UserDirs

	mu    sync.Mutex
	users map[string]*perUserFav
}

// perUserFav 单个用户的收藏集合：对应一个 favorites.json 文件与一把读写锁。
type perUserFav struct {
	mu        sync.RWMutex
	filePath  string
	favorites map[string]bool
}

// NewFavoriteStoreWithDirs 以 userDirs 为根构造按用户隔离的 FavoriteStore。
// dirs 为 nil 时返回 nil（调用方据此禁用收藏功能）。
func NewFavoriteStoreWithDirs(dirs *UserDirs) *FavoriteStore {
	if dirs == nil {
		return nil
	}
	return &FavoriteStore{
		dirs:  dirs,
		users: make(map[string]*perUserFav),
	}
}

// forUser 取出（或懒创建）某用户的 perUserFav。uid 非法时返回 nil。
func (fs *FavoriteStore) forUser(uid string) *perUserFav {
	if fs == nil || fs.dirs == nil || !validUserID(uid) {
		return nil
	}
	fs.mu.Lock()
	defer fs.mu.Unlock()
	if pf, ok := fs.users[uid]; ok {
		return pf
	}
	path, err := fs.dirs.FavoritesPath(uid)
	if err != nil {
		return nil
	}
	pf := &perUserFav{filePath: path, favorites: make(map[string]bool)}
	if err := pf.load(); err != nil {
		// 加载失败不致命：以空集起步，首个 save 会重写文件；保持与改造前"文件缺失=空集"语义。
		_ = err
	}
	fs.users[uid] = pf
	return pf
}

// load 从磁盘加载某用户收藏列表；文件不存在时静默返回空集。
func (pf *perUserFav) load() error {
	data, err := os.ReadFile(pf.filePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if len(data) == 0 {
		return nil
	}
	var list []string
	if err := json.Unmarshal(data, &list); err != nil {
		return err
	}
	pf.favorites = make(map[string]bool, len(list))
	for _, id := range list {
		pf.favorites[id] = true
	}
	return nil
}

// save 将某用户收藏列表写入其专属文件。
func (pf *perUserFav) save() error {
	list := make([]string, 0, len(pf.favorites))
	for id := range pf.favorites {
		list = append(list, id)
	}
	sort.Strings(list)
	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(pf.filePath), 0755); err != nil {
		return err
	}
	return os.WriteFile(pf.filePath, data, 0644)
}

// maxFavorites limits the number of favorites per user to prevent unbounded growth.
const maxFavorites = 10000

// errNoUser 在 user_id 缺失/非法时统一返回的哨兵错误。
const errNoUserMsg = "user_id is required for favorite operation"

// AddFavorite 标记 uid 名下 mediaId 为收藏并持久化。
func (fs *FavoriteStore) AddFavorite(uid, mediaId string) error {
	pf := fs.forUser(uid)
	if pf == nil {
		return fmt.Errorf(errNoUserMsg)
	}
	pf.mu.Lock()
	defer pf.mu.Unlock()
	if !pf.favorites[mediaId] && len(pf.favorites) >= maxFavorites {
		return fmt.Errorf("favorite limit reached (%d); remove a favorite before adding a new one", maxFavorites)
	}
	pf.favorites[mediaId] = true
	return pf.save()
}

// RemoveFavorite 取消 uid 名下 mediaId 的收藏并持久化。
func (fs *FavoriteStore) RemoveFavorite(uid, mediaId string) error {
	pf := fs.forUser(uid)
	if pf == nil {
		return fmt.Errorf(errNoUserMsg)
	}
	pf.mu.Lock()
	defer pf.mu.Unlock()
	delete(pf.favorites, mediaId)
	return pf.save()
}

// IsFavorite 返回 mediaId 是否在 uid 的收藏集中。
func (fs *FavoriteStore) IsFavorite(uid, mediaId string) bool {
	pf := fs.forUser(uid)
	if pf == nil {
		return false
	}
	pf.mu.RLock()
	defer pf.mu.RUnlock()
	return pf.favorites[mediaId]
}

// BatchRemoveFavorites 批量取消 uid 名下 mediaIDs 的收藏，单次加锁 + 单次落盘
// （区别于循环调 RemoveFavorite 每条都 save 一次，避免大批量时 IO 放大）。
// 返回实际被移除的条数——即 mediaIDs 中原本就在收藏集里的数量；不在收藏集里的
// id 静默跳过（幂等，不报错）。uid 非法或 FavoriteStore 未配置返回 (0, nil)，
// 与 RemoveFavorite 的 forUser==nil 语义对齐（后者会报错，但批量场景为便于前端
// 统计 removed_count，这里降级为 0 而非报错——调用方 handler 仍会在 favStore
// 不支持时整体 501，不会走到这里）。
//
// 安全：仅操作 uid 自己的 perUserFav（forUser 按 uid 隔离），不触及他人收藏。
func (fs *FavoriteStore) BatchRemoveFavorites(uid string, mediaIDs []string) int {
	pf := fs.forUser(uid)
	if pf == nil || len(mediaIDs) == 0 {
		return 0
	}
	pf.mu.Lock()
	defer pf.mu.Unlock()
	removed := 0
	for _, id := range mediaIDs {
		if pf.favorites[id] {
			delete(pf.favorites, id)
			removed++
		}
	}
	if removed == 0 {
		// 没有任何变化则不触发落盘，避免无谓 IO。
		return 0
	}
	_ = pf.save()
	return removed
}

// ListFavorites 返回 uid 的全部收藏 mediaId 列表（无序）。
func (fs *FavoriteStore) ListFavorites(uid string) []string {
	pf := fs.forUser(uid)
	if pf == nil {
		return []string{}
	}
	pf.mu.RLock()
	defer pf.mu.RUnlock()
	list := make([]string, 0, len(pf.favorites))
	for id := range pf.favorites {
		list = append(list, id)
	}
	return list
}

// TotalCount 返回所有用户收藏总数，供 /healthz 聚合展示。
// 遍历已懒加载的用户集合（未访问过的用户视为 0）。
func (fs *FavoriteStore) TotalCount() int {
	if fs == nil {
		return 0
	}
	fs.mu.Lock()
	defer fs.mu.Unlock()
	total := 0
	for _, pf := range fs.users {
		pf.mu.RLock()
		total += len(pf.favorites)
		pf.mu.RUnlock()
	}
	return total
}
