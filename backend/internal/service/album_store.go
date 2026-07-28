package service

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"github.com/google/uuid"
)

// Album 描述一个相册：名称、包含的媒体 ID 列表及创建时间。
type Album struct {
	ID        string    `json:"id"`
	Name      string    `json:"name"`
	MediaIDs  []string  `json:"media_ids"`
	CreatedAt int64     `json:"created_at"`
}

// AlbumStore 持久化相册列表到 JSON 文件，读写线程安全。
type AlbumStore struct {
	mu       sync.RWMutex
	filePath string
	albums   map[string]*Album
}

// NewAlbumStore 创建一个以 filePath 为持久化路径的 AlbumStore。
// 文件不存在时从空集开始；存在时加载已有数据。目录会自动创建。
func NewAlbumStore(filePath string) (*AlbumStore, error) {
	as := &AlbumStore{
		filePath: filePath,
		albums:   make(map[string]*Album),
	}
	if err := as.load(); err != nil {
		return nil, err
	}
	return as, nil
}

// load 从磁盘加载相册列表；文件不存在时静默返回空集。
func (as *AlbumStore) load() error {
	data, err := os.ReadFile(as.filePath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if len(data) == 0 {
		return nil
	}
	var list []*Album
	if err := json.Unmarshal(data, &list); err != nil {
		return err
	}
	as.albums = make(map[string]*Album, len(list))
	for _, a := range list {
		as.albums[a.ID] = a
	}
	return nil
}

// save 将当前相册列表写入磁盘。
func (as *AlbumStore) save() error {
	list := make([]*Album, 0, len(as.albums))
	for _, a := range as.albums {
		list = append(list, a)
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].CreatedAt > list[j].CreatedAt
	})
	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(as.filePath), 0755); err != nil {
		return err
	}
	return os.WriteFile(as.filePath, data, 0644)
}

// maxAlbums limits the number of albums to prevent unbounded creation.
const maxAlbums = 100

// CreateAlbum 创建一个新相册，返回创建的 Album。
func (as *AlbumStore) CreateAlbum(name string) (*Album, error) {
	if name == "" {
		return nil, fmt.Errorf("album name must not be empty")
	}
	as.mu.Lock()
	defer as.mu.Unlock()
	if len(as.albums) >= maxAlbums {
		return nil, fmt.Errorf("album limit reached (%d); delete an album before creating a new one", maxAlbums)
	}
	album := &Album{
		ID:        uuid.New().String(),
		Name:      name,
		MediaIDs:  []string{},
		CreatedAt: time.Now().Unix(),
	}
	as.albums[album.ID] = album
	if err := as.save(); err != nil {
		delete(as.albums, album.ID)
		return nil, err
	}
	return album, nil
}

// AddToAlbum 将 mediaId 加入指定相册；已存在则幂等返回。
func (as *AlbumStore) AddToAlbum(albumID, mediaID string) error {
	as.mu.Lock()
	defer as.mu.Unlock()
	album, ok := as.albums[albumID]
	if !ok {
		return fmt.Errorf("album not found: %s", albumID)
	}
	for _, id := range album.MediaIDs {
		if id == mediaID {
			return nil // 已存在，幂等
		}
	}
	album.MediaIDs = append(album.MediaIDs, mediaID)
	return as.save()
}

// RemoveFromAlbum 将 mediaId 从指定相册中移除。
func (as *AlbumStore) RemoveFromAlbum(albumID, mediaID string) error {
	as.mu.Lock()
	defer as.mu.Unlock()
	album, ok := as.albums[albumID]
	if !ok {
		return fmt.Errorf("album not found: %s", albumID)
	}
	for i, id := range album.MediaIDs {
		if id == mediaID {
			album.MediaIDs = append(album.MediaIDs[:i], album.MediaIDs[i+1:]...)
			return as.save()
		}
	}
	return nil // 不存在，幂等
}

// ListAlbums 返回所有相册，按创建时间倒序。
func (as *AlbumStore) ListAlbums() []*Album {
	as.mu.RLock()
	defer as.mu.RUnlock()
	list := make([]*Album, 0, len(as.albums))
	for _, a := range as.albums {
		// 返回副本，避免调用方修改内部状态
		copy := *a
		copy.MediaIDs = append([]string(nil), a.MediaIDs...)
		list = append(list, &copy)
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].CreatedAt > list[j].CreatedAt
	})
	return list
}

// GetAlbum 返回指定相册的副本；不存在返回 nil。
func (as *AlbumStore) GetAlbum(albumID string) *Album {
	as.mu.RLock()
	defer as.mu.RUnlock()
	a, ok := as.albums[albumID]
	if !ok {
		return nil
	}
	copy := *a
	copy.MediaIDs = append([]string(nil), a.MediaIDs...)
	return &copy
}

// DeleteAlbum 删除指定相册；不存在则幂等返回。
func (as *AlbumStore) DeleteAlbum(albumID string) error {
	as.mu.Lock()
	defer as.mu.Unlock()
	if _, ok := as.albums[albumID]; !ok {
		return nil
	}
	delete(as.albums, albumID)
	return as.save()
}
