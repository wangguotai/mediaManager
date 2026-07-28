package service

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"sync"
)

// FavoriteStore 持久化收藏的 mediaId 列表到 JSON 文件，读写线程安全。
type FavoriteStore struct {
	mu        sync.RWMutex
	filePath  string
	favorites map[string]bool
}

// NewFavoriteStore 创建一个以 filePath 为持久化路径的 FavoriteStore。
// 文件不存在时从空集开始；存在时加载已有数据。目录会自动创建。
func NewFavoriteStore(filePath string) (*FavoriteStore, error) {
	fs := &FavoriteStore{
		filePath:  filePath,
		favorites: make(map[string]bool),
	}
	if err := fs.load(); err != nil {
		return nil, err
	}
	return fs, nil
}

// load 从磁盘加载收藏列表；文件不存在时静默返回空集。
func (fs *FavoriteStore) load() error {
	data, err := os.ReadFile(fs.filePath)
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
	fs.favorites = make(map[string]bool, len(list))
	for _, id := range list {
		fs.favorites[id] = true
	}
	return nil
}

// save 将当前收藏列表写入磁盘。
func (fs *FavoriteStore) save() error {
	list := make([]string, 0, len(fs.favorites))
	for id := range fs.favorites {
		list = append(list, id)
	}
	sort.Strings(list)
	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(fs.filePath), 0755); err != nil {
		return err
	}
	return os.WriteFile(fs.filePath, data, 0644)
}

// AddFavorite 将 mediaId 标记为收藏并持久化。
func (fs *FavoriteStore) AddFavorite(mediaId string) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	fs.favorites[mediaId] = true
	return fs.save()
}

// RemoveFavorite 取消收藏 mediaId 并持久化。
func (fs *FavoriteStore) RemoveFavorite(mediaId string) error {
	fs.mu.Lock()
	defer fs.mu.Unlock()
	delete(fs.favorites, mediaId)
	return fs.save()
}

// IsFavorite 返回 mediaId 是否在收藏集中。
func (fs *FavoriteStore) IsFavorite(mediaId string) bool {
	fs.mu.RLock()
	defer fs.mu.RUnlock()
	return fs.favorites[mediaId]
}

// ListFavorites 返回所有收藏的 mediaId 列表（无序）。
func (fs *FavoriteStore) ListFavorites() []string {
	fs.mu.RLock()
	defer fs.mu.RUnlock()
	list := make([]string, 0, len(fs.favorites))
	for id := range fs.favorites {
		list = append(list, id)
	}
	return list
}
