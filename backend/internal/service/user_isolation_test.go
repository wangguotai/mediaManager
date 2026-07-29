package service

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"

	"media-manager/backend/gen"
)

// newIsolationFixture 构造一份按 user_id 隔离的 MediaService + stores，数据根落在
// t.TempDir() 下，模拟 main.go 的接线（UserDirs → per-user stores → MediaService）。
// 返回 service 与数据根，供各用例注入不同 user_id 的 context 验证隔离。
func newIsolationFixture(t *testing.T) (*MediaService, string) {
	t.Helper()
	root := filepath.Join(t.TempDir(), "users")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatalf("mkdir users root: %v", err)
	}
	dirs := NewUserDirs(root)
	fav := NewFavoriteStoreWithDirs(dirs)
	albums := NewAlbumStoreWithDirs(dirs)
	svc := NewMediaService(dirs, "") // 无 cloud 源，聚焦 per-user uploads 隔离
	svc.SetFavoriteStore(fav)
	svc.SetAlbumStore(albums)
	return svc, root
}

// seedUpload 直接在 uid 的 uploads 目录写一个 mediaID 文件，绕过 REST 上传，
// 便于精确控制每用户拥有哪些媒体。
func seedUpload(t *testing.T, svc *MediaService, uid, mediaID, ext string) {
	t.Helper()
	dir, err := svc.userDirs.UploadsDir(uid)
	if err != nil {
		t.Fatalf("UploadsDir(%q): %v", uid, err)
	}
	path := filepath.Join(dir, mediaID+ext)
	if err := os.WriteFile(path, []byte("x"), 0o644); err != nil {
		t.Fatalf("write seed: %v", err)
	}
}

// withUID 包一层 context，模拟 auth 中间件注入 user_id。
func withUID(uid string) context.Context { return WithUserID(context.Background(), uid) }

// listIDs 取 GetMediaList 返回的 media id 集合，便于断言可见性。
func listIDs(t *testing.T, svc *MediaService, uid string) []string {
	t.Helper()
	resp, err := svc.GetMediaList(withUID(uid), &gen.GetMediaListRequest{Page: 1, PageSize: 100})
	if err != nil {
		t.Fatalf("GetMediaList(%q): %v", uid, err)
	}
	ids := make([]string, 0, len(resp.MediaList))
	for _, m := range resp.MediaList {
		ids = append(ids, m.Id)
	}
	return ids
}

// contains 报告 slice 是否含 s。
func contains(slice []string, s string) bool {
	for _, v := range slice {
		if v == s {
			return true
		}
	}
	return false
}

// TestPerUserUploadsIsolation 验证 A 的上传对 B 不可见，反之亦然。
func TestPerUserUploadsIsolation(t *testing.T) {
	svc, _ := newIsolationFixture(t)
	seedUpload(t, svc, "alice", "alice-pic-1", ".jpg")
	seedUpload(t, svc, "bob", "bob-pic-1", ".png")

	alice := listIDs(t, svc, "alice")
	if !contains(alice, "alice-pic-1") {
		t.Fatalf("alice 应能看到自己的 alice-pic-1, got %v", alice)
	}
	if contains(alice, "bob-pic-1") {
		t.Fatalf("alice 不应看到 bob 的 bob-pic-1, got %v", alice)
	}

	bob := listIDs(t, svc, "bob")
	if contains(bob, "alice-pic-1") {
		t.Fatalf("bob 不应看到 alice 的 alice-pic-1, got %v", bob)
	}
}

// TestEmptyUIDSeesNothing 验证无 user_id（未认证）时 GetMediaList 返回空，
// 不回退到任何全局目录，杜绝跨用户串读。
func TestEmptyUIDSeesNothing(t *testing.T) {
	svc, _ := newIsolationFixture(t)
	seedUpload(t, svc, "alice", "alice-pic-1", ".jpg")

	// 未注入 uid 的 context（模拟未认证请求）。
	resp, err := svc.GetMediaList(context.Background(), &gen.GetMediaListRequest{Page: 1, PageSize: 100})
	if err != nil {
		t.Fatalf("GetMediaList with empty uid: %v", err)
	}
	if len(resp.MediaList) != 0 {
		t.Fatalf("未认证请求不应看到任何媒体, got %d items: %+v", len(resp.MediaList), resp.MediaList)
	}
}

// TestFavoriteIsolation 验证收藏按 user_id 隔离：A 的收藏对 B 不可见。
func TestFavoriteIsolation(t *testing.T) {
	svc, _ := newIsolationFixture(t)
	seedUpload(t, svc, "alice", "alice-pic-1", ".jpg")

	if err := svc.AddFavorite("alice", "alice-pic-1"); err != nil {
		t.Fatalf("AddFavorite(alice): %v", err)
	}
	if !svc.IsFavorite("alice", "alice-pic-1") {
		t.Fatal("alice 应能查到自己收藏的媒体")
	}
	// bob 查同一 mediaId 应为未收藏，且 bob 的收藏列表为空。
	if svc.IsFavorite("bob", "alice-pic-1") {
		t.Fatal("bob 不应看到 alice 的收藏状态")
	}
	if got := svc.ListFavorites("bob"); len(got) != 0 {
		t.Fatalf("bob 收藏列表应为空, got %v", got)
	}
	// uid 缺失时按未授权处理：不报错但 IsFavorite 为 false、列表为空。
	if svc.IsFavorite("", "alice-pic-1") {
		t.Fatal("空 uid 不应命中任何收藏")
	}
	if got := svc.ListFavorites(""); len(got) != 0 {
		t.Fatalf("空 uid 收藏列表应为空, got %v", got)
	}
}

// TestAlbumIsolation 验证相册按 user_id 隔离：A 创建的相册 B 看不到、也无法操作。
func TestAlbumIsolation(t *testing.T) {
	svc, _ := newIsolationFixture(t)

	album, err := svc.CreateAlbum("alice", "Alice's Album")
	if err != nil {
		t.Fatalf("CreateAlbum(alice): %v", err)
	}
	if err := svc.AddToAlbum("alice", album.ID, "alice-pic-1"); err != nil {
		t.Fatalf("AddToAlbum(alice): %v", err)
	}

	// bob 的相册列表不应包含 alice 的相册。
	for _, a := range svc.ListAlbums("bob") {
		if a.ID == album.ID {
			t.Fatal("bob 不应在相册列表里看到 alice 的相册")
		}
	}
	// bob 直接按 id 取 alice 的相册应返回 nil（跨用户不可见）。
	if got := svc.GetAlbum("bob", album.ID); got != nil {
		t.Fatalf("bob 取 alice 的相册应返回 nil, got %+v", got)
	}
	// bob 向 alice 的相册加媒体应失败（相册对 bob 不存在）。
	if err := svc.AddToAlbum("bob", album.ID, "some-id"); err == nil {
		t.Fatal("bob 不应能向 alice 的相册添加媒体")
	}
}

// TestThumbnailPerUserDir 验证缩略图落盘到该用户专属目录，且缓存 key 带 uid 前缀，
// 不同用户同名媒体不会串读缩略图。用真实 jpeg 字节走 GetThumbnail 图片路径。
func TestThumbnailPerUserDir(t *testing.T) {
	svc, root := newIsolationFixture(t)
	// 两用户各放一张同名媒体 "shared-id.jpg"，内容不同（宽高不同）。
	seedJPEG(t, svc, "alice", "shared-id", 40, 30)
	seedJPEG(t, svc, "bob", "shared-id", 20, 10)

	aliceThumb, err := svc.GetThumbnail(withUID("alice"), &gen.GetThumbnailRequest{
		MediaId: "shared-id", Size: gen.ThumbnailSize_THUMBNAIL_SMALL,
	})
	if err != nil {
		t.Fatalf("GetThumbnail(alice): %v", err)
	}
	bobThumb, err := svc.GetThumbnail(withUID("bob"), &gen.GetThumbnailRequest{
		MediaId: "shared-id", Size: gen.ThumbnailSize_THUMBNAIL_SMALL,
	})
	if err != nil {
		t.Fatalf("GetThumbnail(bob): %v", err)
	}
	// 原图宽高不同（alice 40x30 vs bob 20x10），small(longEdge=128) 不放大，
	// 故缩略图宽高应保留各自原比例——若串读会得到相同尺寸。
	if aliceThumb.Width == bobThumb.Width && aliceThumb.Height == bobThumb.Height {
		t.Fatalf("alice/bob 同名媒体缩略图尺寸不应相同（应各自隔离）: alice=%dx%d bob=%dx%d",
			aliceThumb.Width, aliceThumb.Height, bobThumb.Width, bobThumb.Height)
	}

	// 缩略图文件应分别落在各自 thumbnails 目录，不混在一个全局目录。
	aliceThumbFile := filepath.Join(root, "alice", "thumbnails", "shared-id_128.jpg")
	bobThumbFile := filepath.Join(root, "bob", "thumbnails", "shared-id_128.jpg")
	if _, err := os.Stat(aliceThumbFile); err != nil {
		t.Fatalf("alice 缩略图应落盘到 per-user 目录: %v", err)
	}
	if _, err := os.Stat(bobThumbFile); err != nil {
		t.Fatalf("bob 缩略图应落盘到 per-user 目录: %v", err)
	}
	// 不应存在全局 thumbnails 目录（旧布局）。
	if _, err := os.Stat(filepath.Join(filepath.Dir(root), "thumbnails")); err == nil {
		t.Fatal("不应存在全局 data/thumbnails 目录（应已按 user 隔离）")
	}
}

// seedJPEG 在 uid 的 uploads 目录写一个指定宽高的真实 JPEG，供缩略图测试用。
func seedJPEG(t *testing.T, svc *MediaService, uid, mediaID string, w, h int) {
	t.Helper()
	dir, err := svc.userDirs.UploadsDir(uid)
	if err != nil {
		t.Fatalf("UploadsDir(%q): %v", uid, err)
	}
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	// 填一个非默认色，避免某些解码器对纯黑/空白做特殊处理。
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.SetRGBA(x, y, color.RGBA{R: uint8(x * 5), G: uint8(y * 5), B: 0x80, A: 0xFF})
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 80}); err != nil {
		t.Fatalf("jpeg encode: %v", err)
	}
	path := filepath.Join(dir, mediaID+".jpg")
	if err := os.WriteFile(path, buf.Bytes(), 0o644); err != nil {
		t.Fatalf("write seed jpeg: %v", err)
	}
}

// TestValidUserID 验证 user_id 校验拒绝路径穿越与非法字符，从源头杜绝目录逃逸。
func TestValidUserID(t *testing.T) {
	for _, bad := range []string{"", "../etc", "a/b", `a\b`, "a..b", "a b", "a.b", "用户"} {
		if validUserID(bad) {
			t.Errorf("validUserID(%q) 应为 false", bad)
		}
	}
	for _, good := range []string{"u-1", "alice", "ABC123", "a:b_c-1"} {
		if !validUserID(good) {
			t.Errorf("validUserID(%q) 应为 true", good)
		}
	}
}
