package gateway

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"media-manager/backend/internal/service"
)

// newIsolationServer 构造一个带真实 per-user UserDirs 的 gateway（authSvc=nil，
// 故中间件放行，便于直接验证 handler 层对 uid 缺失的纵深防御），并返回数据根与
// 一个可直读文件的 media service（与 main.go 接线一致）。
func newIsolationServer(t *testing.T) (*Server, string) {
	t.Helper()
	root := filepath.Join(t.TempDir(), "users")
	if err := os.MkdirAll(root, 0o755); err != nil {
		t.Fatalf("mkdir users root: %v", err)
	}
	dirs := service.NewUserDirs(root)
	svc := service.NewMediaService(dirs, "")
	svc.SetFavoriteStore(service.NewFavoriteStoreWithDirs(dirs))
	svc.SetAlbumStore(service.NewAlbumStoreWithDirs(dirs))
	srv := NewServer(":0", OpenClawConfig{}, svc, dirs, nil) // authSvc=nil：放行，聚焦 handler 层防御
	return srv, root
}

// TestStreamEmptyUIDRejected 验证 /api/media/stream 在 uid 未注入时直接 401，
// 不退化为相对路径 Glob（否则可能 ServeFile 返回进程 cwd 下任意文件 → 信息泄露）。
// 这是 per-user 隔离的纵深防御：即便上游中间件被绕过，handler 也不应串读。
func TestStreamEmptyUIDRejected(t *testing.T) {
	srv, root := newIsolationServer(t)
	// 在 alice 名下放一个文件，确保 mediaID 客观存在——uid 空时应仍被拒绝。
	aliceUploads := filepath.Join(root, "alice", "uploads")
	if err := os.MkdirAll(aliceUploads, 0o755); err != nil {
		t.Fatalf("mkdir alice uploads: %v", err)
	}
	if err := os.WriteFile(filepath.Join(aliceUploads, "alice-pic-1.jpg"), []byte("x"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}

	// 不经 auth 中间件注入 uid（context 无 user_id），直接打 handler。
	req := httptest.NewRequest(http.MethodGet, "/api/media/stream/alice-pic-1", nil)
	rec := httptest.NewRecorder()
	srv.handleMediaStream(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("uid 缺失时 stream 应 401, got %d body=%s", rec.Code, rec.Body.String())
	}
}

// TestStreamUserScoped 验证带 uid 的请求只能取到该用户名下的文件：alice 的文件
// 对经 alice uid 的请求可见，而经 bob uid 的请求取同一 mediaId 应 404。
func TestStreamUserScoped(t *testing.T) {
	srv, root := newIsolationServer(t)
	aliceUploads := filepath.Join(root, "alice", "uploads")
	if err := os.MkdirAll(aliceUploads, 0o755); err != nil {
		t.Fatalf("mkdir alice uploads: %v", err)
	}
	if err := os.WriteFile(filepath.Join(aliceUploads, "alice-pic-1.jpg"), []byte("alice-data"), 0o644); err != nil {
		t.Fatalf("seed: %v", err)
	}

	// alice 视角：能取到自己的文件。
	reqAlice := httptest.NewRequest(http.MethodGet, "/api/media/stream/alice-pic-1", nil)
	reqAlice = reqAlice.WithContext(service.WithUserID(reqAlice.Context(), "alice"))
	recAlice := httptest.NewRecorder()
	srv.handleMediaStream(recAlice, reqAlice)
	if recAlice.Code != http.StatusOK {
		t.Fatalf("alice 取自己的文件应 200, got %d", recAlice.Code)
	}

	// bob 视角：取 alice 的 mediaId 应 404（bob 名下无此文件）。
	reqBob := httptest.NewRequest(http.MethodGet, "/api/media/stream/alice-pic-1", nil)
	reqBob = reqBob.WithContext(service.WithUserID(reqBob.Context(), "bob"))
	recBob := httptest.NewRecorder()
	srv.handleMediaStream(recBob, reqBob)
	if recBob.Code != http.StatusNotFound {
		t.Fatalf("bob 取 alice 的文件应 404, got %d", recBob.Code)
	}
}
