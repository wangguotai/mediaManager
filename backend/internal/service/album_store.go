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

// Album 描述一个相册：名称、包含的媒体 ID 列表及创建时间。所有字段与改造前一致，
// 隔离由 AlbumStore 按 user_id 分桶实现，Album 本身不变。
type Album struct {
	ID           string   `json:"id"`
	Name         string   `json:"name"`
	MediaIDs     []string `json:"media_ids"`
	CreatedAt    int64    `json:"created_at"`
	CoverMediaID string   `json:"cover_media_id,omitempty"` // V7：相册封面 media_id
}

// AlbumStore 按 user_id 维度隔离持久化相册列表。
//
// 改造前：单文件 albums.json + 全局 map。改造后：以 UserDirs 为根，按 user_id
// 懒创建独立的 perUserAlbums（对应 data/users/{uid}/albums.json），各自持锁、各自落盘。
// 不同用户的相册互不可见。
type AlbumStore struct {
	dirs *UserDirs

	mu    sync.Mutex
	users map[string]*perUserAlbums
}

// perUserAlbums 单个用户的相册集合：一个 albums.json 文件 + 一把读写锁。
type perUserAlbums struct {
	mu      sync.RWMutex
	filePath string
	albums  map[string]*Album
}

// NewAlbumStoreWithDirs 以 userDirs 为根构造按用户隔离的 AlbumStore。
// dirs 为 nil 时返回 nil（调用方据此禁用相册功能）。
func NewAlbumStoreWithDirs(dirs *UserDirs) *AlbumStore {
	if dirs == nil {
		return nil
	}
	return &AlbumStore{
		dirs:  dirs,
		users: make(map[string]*perUserAlbums),
	}
}

// forUser 取出（或懒创建）某用户的 perUserAlbums。uid 非法返回 nil。
func (as *AlbumStore) forUser(uid string) *perUserAlbums {
	if as == nil || as.dirs == nil || !validUserID(uid) {
		return nil
	}
	as.mu.Lock()
	defer as.mu.Unlock()
	if pa, ok := as.users[uid]; ok {
		return pa
	}
	path, err := as.dirs.AlbumsPath(uid)
	if err != nil {
		return nil
	}
	pa := &perUserAlbums{filePath: path, albums: make(map[string]*Album)}
	if err := pa.load(); err != nil {
		_ = err // 加载失败以空集起步，首个 save 重写文件。
	}
	as.users[uid] = pa
	return pa
}

// load 从磁盘加载某用户相册列表；文件不存在时静默返回空集。
func (pa *perUserAlbums) load() error {
	data, err := os.ReadFile(pa.filePath)
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
	pa.albums = make(map[string]*Album, len(list))
	for _, a := range list {
		pa.albums[a.ID] = a
	}
	return nil
}

// save 将某用户相册列表写入其专属文件。
func (pa *perUserAlbums) save() error {
	list := make([]*Album, 0, len(pa.albums))
	for _, a := range pa.albums {
		list = append(list, a)
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].CreatedAt > list[j].CreatedAt
	})
	data, err := json.MarshalIndent(list, "", "  ")
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(pa.filePath), 0755); err != nil {
		return err
	}
	return os.WriteFile(pa.filePath, data, 0644)
}

// maxAlbums limits the number of albums per user to prevent unbounded creation.
const maxAlbums = 100

// errNoUserAlbum 在 user_id 缺失/非法时统一返回的哨兵错误。
const errNoUserAlbumMsg = "user_id is required for album operation"

// CreateAlbum 在 uid 名下创建一个新相册，返回创建的 Album。
func (as *AlbumStore) CreateAlbum(uid, name string) (*Album, error) {
	pa := as.forUser(uid)
	if pa == nil {
		return nil, fmt.Errorf(errNoUserAlbumMsg)
	}
	if name == "" {
		return nil, fmt.Errorf("album name must not be empty")
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	if len(pa.albums) >= maxAlbums {
		return nil, fmt.Errorf("album limit reached (%d); delete an album before creating a new one", maxAlbums)
	}
	album := &Album{
		ID:        uuid.New().String(),
		Name:      name,
		MediaIDs:  []string{},
		CreatedAt: time.Now().Unix(),
	}
	pa.albums[album.ID] = album
	if err := pa.save(); err != nil {
		delete(pa.albums, album.ID)
		return nil, err
	}
	return album, nil
}

// AddToAlbum 将 mediaId 加入 uid 名下指定相册；已存在则幂等返回。
func (as *AlbumStore) AddToAlbum(uid, albumID, mediaID string) error {
	pa := as.forUser(uid)
	if pa == nil {
		return fmt.Errorf(errNoUserAlbumMsg)
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	album, ok := pa.albums[albumID]
	if !ok {
		return fmt.Errorf("album not found: %s", albumID)
	}
	for _, id := range album.MediaIDs {
		if id == mediaID {
			return nil // 已存在，幂等
		}
	}
	album.MediaIDs = append(album.MediaIDs, mediaID)
	return pa.save()
}

// BatchAddToAlbum V7：批量添加多个媒体到相册。已存在的跳过（幂等）。
func (as *AlbumStore) BatchAddToAlbum(uid, albumID string, mediaIDs []string) (int, error) {
	pa := as.forUser(uid)
	if pa == nil {
		return 0, fmt.Errorf(errNoUserAlbumMsg)
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	album, ok := pa.albums[albumID]
	if !ok {
		return 0, fmt.Errorf("album not found: %s", albumID)
	}
	existing := make(map[string]bool, len(album.MediaIDs))
	for _, id := range album.MediaIDs {
		existing[id] = true
	}
	added := 0
	for _, id := range mediaIDs {
		if !existing[id] {
			album.MediaIDs = append(album.MediaIDs, id)
			existing[id] = true
			added++
		}
	}
	if added > 0 {
		return added, pa.save()
	}
	return 0, nil
}

// RemoveFromAlbum 将 mediaId 从 uid 名下指定相册中移除。
func (as *AlbumStore) RemoveFromAlbum(uid, albumID, mediaID string) error {
	pa := as.forUser(uid)
	if pa == nil {
		return fmt.Errorf(errNoUserAlbumMsg)
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	album, ok := pa.albums[albumID]
	if !ok {
		return fmt.Errorf("album not found: %s", albumID)
	}
	for i, id := range album.MediaIDs {
		if id == mediaID {
			album.MediaIDs = append(album.MediaIDs[:i], album.MediaIDs[i+1:]...)
			return pa.save()
		}
	}
	return nil // 不存在，幂等
}

// BatchRemoveFromAlbum V7：批量从相册移除多个媒体。不存在的跳过（幂等）。
func (as *AlbumStore) BatchRemoveFromAlbum(uid, albumID string, mediaIDs []string) (int, error) {
	pa := as.forUser(uid)
	if pa == nil {
		return 0, fmt.Errorf(errNoUserAlbumMsg)
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	album, ok := pa.albums[albumID]
	if !ok {
		return 0, fmt.Errorf("album not found: %s", albumID)
	}
	toRemove := make(map[string]bool, len(mediaIDs))
	for _, id := range mediaIDs {
		toRemove[id] = true
	}
	filtered := album.MediaIDs[:0]
	removed := 0
	for _, id := range album.MediaIDs {
		if toRemove[id] {
			removed++
			continue
		}
		filtered = append(filtered, id)
	}
	if removed > 0 {
		album.MediaIDs = filtered
		return removed, pa.save()
	}
	return 0, nil
}

// SetAlbumCover V7：设置相册封面 media_id。
func (as *AlbumStore) SetAlbumCover(uid, albumID, mediaID string) error {
	pa := as.forUser(uid)
	if pa == nil {
		return fmt.Errorf(errNoUserAlbumMsg)
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	album, ok := pa.albums[albumID]
	if !ok {
		return fmt.Errorf("album not found: %s", albumID)
	}
	album.CoverMediaID = mediaID
	return pa.save()
}

// ListAlbums 返回 uid 名下所有相册，按创建时间倒序。
func (as *AlbumStore) ListAlbums(uid string) []*Album {
	pa := as.forUser(uid)
	if pa == nil {
		return []*Album{}
	}
	pa.mu.RLock()
	defer pa.mu.RUnlock()
	list := make([]*Album, 0, len(pa.albums))
	for _, a := range pa.albums {
		// 返回副本，避免调用方修改内部状态
		copy := *a
		// V7 修复：确保 MediaIDs 不为 nil（前端 JsonArray 解析会 crash）
		copy.MediaIDs = append([]string{}, a.MediaIDs...)
		list = append(list, &copy)
	}
	sort.Slice(list, func(i, j int) bool {
		return list[i].CreatedAt > list[j].CreatedAt
	})
	return list
}

// GetAlbum 返回 uid 名下指定相册的副本；不存在返回 nil。
func (as *AlbumStore) GetAlbum(uid, albumID string) *Album {
	pa := as.forUser(uid)
	if pa == nil {
		return nil
	}
	pa.mu.RLock()
	defer pa.mu.RUnlock()
	a, ok := pa.albums[albumID]
	if !ok {
		return nil
	}
	copy := *a
	copy.MediaIDs = append([]string{}, a.MediaIDs...) // V7 修复：确保非 nil
	return &copy
}

// DeleteAlbum 删除 uid 名下指定相册；不存在则幂等返回。
func (as *AlbumStore) DeleteAlbum(uid, albumID string) error {
	pa := as.forUser(uid)
	if pa == nil {
		return fmt.Errorf(errNoUserAlbumMsg)
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	if _, ok := pa.albums[albumID]; !ok {
		return nil
	}
	delete(pa.albums, albumID)
	return pa.save()
}

// RenameAlbum V8：重命名相册。
func (as *AlbumStore) RenameAlbum(uid, albumID, newName string) error {
	if newName == "" {
		return fmt.Errorf("album name cannot be empty")
	}
	pa := as.forUser(uid)
	if pa == nil {
		return fmt.Errorf(errNoUserAlbumMsg)
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	album, ok := pa.albums[albumID]
	if !ok {
		return fmt.Errorf("album not found")
	}
	album.Name = newName
	return pa.save()
}

// ReorderAlbumMedia V8：调整相册内照片顺序。
func (as *AlbumStore) ReorderAlbumMedia(uid, albumID string, newOrder []string) error {
	pa := as.forUser(uid)
	if pa == nil {
		return fmt.Errorf(errNoUserAlbumMsg)
	}
	pa.mu.Lock()
	defer pa.mu.Unlock()
	album, ok := pa.albums[albumID]
	if !ok {
		return fmt.Errorf("album not found")
	}
	album.MediaIDs = newOrder
	return pa.save()
}
