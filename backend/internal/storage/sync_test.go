package storage

import (
	"context"
	"errors"
	"testing"
	"time"
)

// seedUser 在临时库中创建一个测试用户并返回其 id。
func seedUser(t *testing.T, s *Store, id, username string) {
	t.Helper()
	if err := s.CreateUser(context.Background(), &User{ID: id, Username: username, PasswordHash: "h"}); err != nil {
		t.Fatalf("CreateUser %s: %v", username, err)
	}
}

// TestMediaClientIDTakenAtRoundtrip 验证 client_id/taken_at 新列正确写入与读回。
func TestMediaClientIDTakenAtRoundtrip(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()
	seedUser(t, s, "u-1", "alice")

	m := &Media{
		ID: "m-1", UserID: "u-1", Filename: "a.jpg", Type: "IMAGE",
		Size: 10, SHA256: "deadbeef", ClientID: "device-A", TakenAt: 1700000000000,
	}
	if err := s.CreateMedia(ctx, m); err != nil {
		t.Fatalf("CreateMedia: %v", err)
	}
	got, err := s.GetMedia(ctx, "m-1")
	if err != nil {
		t.Fatalf("GetMedia: %v", err)
	}
	if got.ClientID != "device-A" || got.TakenAt != 1700000000000 {
		t.Fatalf("client_id/taken_at not roundtripped: %+v", got)
	}
	// 列表也应带新列。
	list, err := s.ListMediaByUser(ctx, "u-1")
	if err != nil || len(list) != 1 {
		t.Fatalf("ListMediaByUser: %v len=%d", err, len(list))
	}
	if list[0].ClientID != "device-A" {
		t.Fatalf("list missing client_id: %+v", list[0])
	}
}

// TestGetMediaByUserAndSHA256 验证按 (user_id,sha256) 去重查询：
// 命中返回记录，未命中 ErrNotFound，跨用户同 sha256 不命中（隔离）。
func TestGetMediaByUserAndSHA256(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()
	seedUser(t, s, "u-1", "alice")
	seedUser(t, s, "u-2", "bob")

	if err := s.CreateMedia(ctx, &Media{ID: "m-1", UserID: "u-1", Filename: "a.jpg", Type: "IMAGE", SHA256: "hash-x"}); err != nil {
		t.Fatalf("CreateMedia: %v", err)
	}
	// bob 也有同 sha256 —— 隔离验证。
	if err := s.CreateMedia(ctx, &Media{ID: "m-2", UserID: "u-2", Filename: "b.jpg", Type: "IMAGE", SHA256: "hash-x"}); err != nil {
		t.Fatalf("CreateMedia bob: %v", err)
	}

	got, err := s.GetMediaByUserAndSHA256(ctx, "u-1", "hash-x")
	if err != nil || got.ID != "m-1" {
		t.Fatalf("alice lookup hash-x: got %+v err %v (want m-1)", got, err)
	}
	// 跨用户同 sha256 不应命中 alice 的查询。
	if _, err := s.GetMediaByUserAndSHA256(ctx, "u-2", "hash-y"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown sha256: want ErrNotFound, got %v", err)
	}
	// 空 sha256 直接未命中（不去重空指纹）。
	if _, err := s.GetMediaByUserAndSHA256(ctx, "u-1", ""); !errors.Is(err, ErrNotFound) {
		t.Fatalf("empty sha256: want ErrNotFound, got %v", err)
	}
}

// TestListMediaChanges 增量同步核心：按 updated_at 严格大于 cursor 返回，
// 含软删墓碑，按 updated_at 升序，分页正确。
func TestListMediaChanges(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()
	seedUser(t, s, "u-1", "alice")

	// 插入 3 条，updated_at 用 setUpdated 钉到确定性时间，保证顺序稳定。
	if err := s.CreateMedia(ctx, &Media{ID: "m-1", UserID: "u-1", Filename: "a.jpg", Type: "IMAGE", SHA256: "h1"}); err != nil {
		t.Fatalf("CreateMedia m1: %v", err)
	}
	setUpdated(t, s, "m-1", time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC))
	if err := s.CreateMedia(ctx, &Media{ID: "m-2", UserID: "u-1", Filename: "b.jpg", Type: "IMAGE", SHA256: "h2"}); err != nil {
		t.Fatalf("CreateMedia m2: %v", err)
	}
	setUpdated(t, s, "m-2", time.Date(2026, 1, 2, 0, 0, 0, 0, time.UTC))
	if err := s.CreateMedia(ctx, &Media{ID: "m-3", UserID: "u-1", Filename: "c.jpg", Type: "IMAGE", SHA256: "h3"}); err != nil {
		t.Fatalf("CreateMedia m3: %v", err)
	}
	setUpdated(t, s, "m-3", time.Date(2026, 1, 3, 0, 0, 0, 0, time.UTC))
	// 软删 m-2：MarkDeleted 会把 updated_at 推到 now，故删后再钉回一个晚于 m-3 的
	// 确定性时间，使墓碑排在最末且顺序可断言。
	if err := s.MarkDeleted(ctx, "m-2"); err != nil {
		t.Fatalf("MarkDeleted: %v", err)
	}
	setUpdated(t, s, "m-2", time.Date(2026, 1, 4, 0, 0, 0, 0, time.UTC))

	// 全量首拉（since 空）：3 条，按 updated_at 升序 m-1,m-3,m-2(墓碑最末)。
	all, err := s.ListMediaChanges(ctx, "u-1", "", 100, 0)
	if err != nil || len(all) != 3 {
		t.Fatalf("first pull: %v len=%d", err, len(all))
	}
	wantOrder := []string{"m-1", "m-3", "m-2"}
	for i, want := range wantOrder {
		if all[i].ID != want {
			t.Fatalf("order[%d]=%s want %s (full: %s %s %s)", i, all[i].ID, want, all[0].ID, all[1].ID, all[2].ID)
		}
	}
	// 墓碑在结果中且 Deleted=true。
	if !all[2].Deleted {
		t.Fatalf("m-2 should be a tombstone: %+v", all[2])
	}

	// 自 m-1 cursor（2026-01-01）之后：应含 m-3、m-2，共 2 条。
	cursor := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC).Format(time.RFC3339Nano)
	part, err := s.ListMediaChanges(ctx, "u-1", cursor, 100, 0)
	if err != nil || len(part) != 2 {
		t.Fatalf("since m-1: %v len=%d", err, len(part))
	}
	if part[0].ID != "m-3" || part[1].ID != "m-2" {
		t.Fatalf("since order wrong: %s %s", part[0].ID, part[1].ID)
	}

	// 分页：limit=1 自该 cursor 取首条应为 m-3。
	page1, err := s.ListMediaChanges(ctx, "u-1", cursor, 1, 0)
	if err != nil || len(page1) != 1 || page1[0].ID != "m-3" {
		t.Fatalf("page1: %v len=%d %+v", err, len(page1), page1)
	}

	// CountMediaChanges 与剩余行数一致；跨用户隔离。
	n, err := s.CountMediaChanges(ctx, "u-1", cursor)
	if err != nil || n != 2 {
		t.Fatalf("count since m-1: got %d err %v (want 2)", n, err)
	}
	if n2, err := s.CountMediaChanges(ctx, "u-99", cursor); err != nil || n2 != 0 {
		t.Fatalf("unknown user count: got %d err %v", n2, err)
	}
}

// TestUndeleteMedia 验证秒传命中软删记录时的复活路径。
func TestUndeleteMedia(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()
	seedUser(t, s, "u-1", "alice")
	if err := s.CreateMedia(ctx, &Media{ID: "m-1", UserID: "u-1", Filename: "a.jpg", Type: "IMAGE", SHA256: "h1"}); err != nil {
		t.Fatalf("CreateMedia: %v", err)
	}
	if err := s.MarkDeleted(ctx, "m-1"); err != nil {
		t.Fatalf("MarkDeleted: %v", err)
	}
	if err := s.UndeleteMedia(ctx, "m-1"); err != nil {
		t.Fatalf("UndeleteMedia: %v", err)
	}
	got, err := s.GetMedia(ctx, "m-1")
	if err != nil {
		t.Fatalf("GetMedia after undelete: %v", err)
	}
	if got.Deleted {
		t.Fatalf("media should be undeleted: %+v", got)
	}
	// 列表恢复可见。
	list, _ := s.ListMediaByUser(ctx, "u-1")
	if len(list) != 1 {
		t.Fatalf("after undelete list len=%d (want 1)", len(list))
	}
	if err := s.UndeleteMedia(ctx, "nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("undelete missing: want ErrNotFound got %v", err)
	}
}

// TestUserUsage 验证用量统计：含未删媒体 size 求和与计数，软删不计入。
func TestUserUsage(t *testing.T) {
	s, cleanup := newTestStore(t)
	defer cleanup()
	ctx := context.Background()
	seedUser(t, s, "u-1", "alice")
	if err := s.CreateMedia(ctx, &Media{ID: "m-1", UserID: "u-1", Filename: "a.jpg", Type: "IMAGE", Size: 100}); err != nil {
		t.Fatalf("CreateMedia: %v", err)
	}
	if err := s.CreateMedia(ctx, &Media{ID: "m-2", UserID: "u-1", Filename: "b.jpg", Type: "IMAGE", Size: 250}); err != nil {
		t.Fatalf("CreateMedia m2: %v", err)
	}
	if err := s.MarkDeleted(ctx, "m-2"); err != nil {
		t.Fatalf("MarkDeleted: %v", err)
	}
	totalBytes, fileCount, err := s.UserUsage(ctx, "u-1")
	if err != nil {
		t.Fatalf("UserUsage: %v", err)
	}
	if totalBytes != 100 || fileCount != 1 {
		t.Fatalf("usage: bytes=%d count=%d (want 100/1)", totalBytes, fileCount)
	}
	// 未知用户：0/0，不报错。
	tb, fc, err := s.UserUsage(ctx, "u-99")
	if err != nil || tb != 0 || fc != 0 {
		t.Fatalf("unknown user usage: %d/%d err %v", tb, fc, err)
	}
}

// setUpdated 直接写 updated_at 列到指定时间，便于在增量测试中制造确定性顺序。
func setUpdated(t *testing.T, s *Store, id string, ts time.Time) {
	t.Helper()
	_, err := s.db.ExecContext(context.Background(),
		`UPDATE "media" SET updated_at = ? WHERE id = ?`, ts.UTC().Format(time.RFC3339Nano), id)
	if err != nil {
		t.Fatalf("setUpdated %s: %v", id, err)
	}
}
