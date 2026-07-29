package storage

import (
	"context"
	"errors"
	"path/filepath"
	"testing"
	"time"
)

// newTestStore 打开一个位于临时目录的 SQLite 库并迁移建表，返回 Store 与清理函数。
func newTestStore(t *testing.T) (*Store, func()) {
	t.Helper()
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "test-media.db")
	s, err := Open(dbPath)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	return s, func() { _ = s.Close() }
}

// TestOpenMigrate 验证 Open 能成功打开库、建表幂等，且 PRAGMA 生效无报错。
func TestOpenMigrate(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()

	// 再次迁移应幂等，不报错。
	if err := s.Migrate(context.Background()); err != nil {
		t.Fatalf("re-migrate: %v", err)
	}
}

// TestUserCRUD 覆盖 user 增/查/按名查/列表/删除。
func TestUserCRUD(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()

	u := &User{
		ID:           "u-1",
		Username:     "alice",
		PasswordHash: "hash:secret",
		Role:         "admin",
		CreatedAt:    time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC),
	}
	if err := s.CreateUser(ctx, u); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	got, err := s.GetUser(ctx, "u-1")
	if err != nil {
		t.Fatalf("GetUser: %v", err)
	}
	if got.Username != "alice" || got.Role != "admin" || got.PasswordHash != "hash:secret" {
		t.Fatalf("GetUser mismatch: %+v", got)
	}
	if !got.CreatedAt.Equal(u.CreatedAt) {
		t.Fatalf("CreatedAt roundtrip: got %v want %v", got.CreatedAt, u.CreatedAt)
	}

	byName, err := s.GetUserByUsername(ctx, "alice")
	if err != nil || byName.ID != "u-1" {
		t.Fatalf("GetUserByUsername: %v / %+v", err, byName)
	}

	if _, err := s.GetUser(ctx, "nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected ErrNotFound, got %v", err)
	}

	users, err := s.ListUsers(ctx)
	if err != nil || len(users) != 1 {
		t.Fatalf("ListUsers: %v len=%d", err, len(users))
	}

	// 重复 username 触发 UNIQUE 约束 → 错误。
	if err := s.CreateUser(ctx, &User{ID: "u-2", Username: "alice", PasswordHash: "x"}); err == nil {
		t.Fatalf("expected duplicate username error")
	}

	if err := s.DeleteUser(ctx, "u-1"); err != nil {
		t.Fatalf("DeleteUser: %v", err)
	}
	if _, err := s.GetUser(ctx, "u-1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("after delete expected ErrNotFound, got %v", err)
	}
}

// TestMediaCRUDSoftDelete 覆盖 media 增/查/列表（排除软删）/更新/软删。
func TestMediaCRUDSoftDelete(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()

	if err := s.CreateUser(ctx, &User{ID: "u-1", Username: "bob", PasswordHash: "h"}); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}

	m := &Media{
		ID: "m-1", UserID: "u-1", Filename: "a.jpg", Type: "IMAGE",
		Size: 1024, Mime: "image/jpeg", Width: 100, Height: 50,
		SHA256: "abcdef",
	}
	if err := s.CreateMedia(ctx, m); err != nil {
		t.Fatalf("CreateMedia: %v", err)
	}

	got, err := s.GetMedia(ctx, "m-1")
	if err != nil {
		t.Fatalf("GetMedia: %v", err)
	}
	if got.Filename != "a.jpg" || got.Type != "IMAGE" || got.Mime != "image/jpeg" || got.Deleted {
		t.Fatalf("GetMedia mismatch: %+v", got)
	}
	if got.CreatedAt.IsZero() || got.UpdatedAt.IsZero() {
		t.Fatalf("timestamps not filled: %+v", got)
	}

	list, err := s.ListMediaByUser(ctx, "u-1")
	if err != nil || len(list) != 1 {
		t.Fatalf("ListMediaByUser: %v len=%d", err, len(list))
	}

	// 软删除后不再出现在列表，但 GetMedia 仍可取到。
	if err := s.MarkDeleted(ctx, "m-1"); err != nil {
		t.Fatalf("MarkDeleted: %v", err)
	}
	list2, err := s.ListMediaByUser(ctx, "u-1")
	if err != nil || len(list2) != 0 {
		t.Fatalf("after soft delete expected empty list, got %d", len(list2))
	}
	if _, err := s.GetMedia(ctx, "m-1"); err != nil {
		t.Fatalf("GetMedia should still return soft-deleted row: %v", err)
	}

	// 更新字段后取回反映新值；且 deleted 状态不被 UpdateMedia 覆盖（仍为 true）。
	if err := s.UpdateMedia(ctx, &Media{ID: "m-1", Filename: "renamed.jpg", Type: "IMAGE"}); err != nil {
		t.Fatalf("UpdateMedia: %v", err)
	}
	upd, _ := s.GetMedia(ctx, "m-1")
	if upd.Filename != "renamed.jpg" {
		t.Fatalf("UpdateMedia did not reflect new filename: %+v", upd)
	}
	if !upd.Deleted {
		t.Fatalf("UpdateMedia must not clear deleted flag: %+v", upd)
	}
}

// TestDeviceCRUD 覆盖 device 增/查/按用户列表/删除。
func TestDeviceCRUD(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()

	if err := s.CreateUser(ctx, &User{ID: "u-1", Username: "carol", PasswordHash: "h"}); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	d := &Device{ID: "d-1", UserID: "u-1", Name: "iPhone", Platform: "ios"}
	if err := s.CreateDevice(ctx, d); err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	got, err := s.GetDevice(ctx, "d-1")
	if err != nil || got.Name != "iPhone" || got.Platform != "ios" {
		t.Fatalf("GetDevice: %v / %+v", err, got)
	}
	if got.CreatedAt.IsZero() {
		t.Fatalf("CreatedAt not filled")
	}

	list, err := s.ListDevicesByUser(ctx, "u-1")
	if err != nil || len(list) != 1 {
		t.Fatalf("ListDevicesByUser: %v len=%d", err, len(list))
	}

	if err := s.DeleteDevice(ctx, "d-1"); err != nil {
		t.Fatalf("DeleteDevice: %v", err)
	}
	if _, err := s.GetDevice(ctx, "d-1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("after delete expected ErrNotFound, got %v", err)
	}
}

// TestCascadeDelete 验证 user 被删时 media/device 经 ON DELETE CASCADE 一并清理。
func TestCascadeDelete(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()

	if err := s.CreateUser(ctx, &User{ID: "u-1", Username: "dan", PasswordHash: "h"}); err != nil {
		t.Fatalf("CreateUser: %v", err)
	}
	if err := s.CreateMedia(ctx, &Media{ID: "m-1", UserID: "u-1", Filename: "x.jpg", Type: "IMAGE"}); err != nil {
		t.Fatalf("CreateMedia: %v", err)
	}
	if err := s.CreateDevice(ctx, &Device{ID: "d-1", UserID: "u-1", Name: "n", Platform: "web"}); err != nil {
		t.Fatalf("CreateDevice: %v", err)
	}

	if err := s.DeleteUser(ctx, "u-1"); err != nil {
		t.Fatalf("DeleteUser: %v", err)
	}
	// child rows 应被级联删除。
	if _, err := s.GetMedia(ctx, "m-1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("media should be cascaded: %v", err)
	}
	if _, err := s.GetDevice(ctx, "d-1"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("device should be cascaded: %v", err)
	}
}
