package gateway

// 共享相册端点测试（PRD-v7 §2.3）。覆盖：
//   - POST /api/media/album/share  ：邀请共享（成功 / 鉴权 / 所有权校验 / 自共享拒绝 /
//     未知用户 / 幂等二次邀请）。
//   - GET  /api/media/albums/shared：列出被共享相册（成功 / 空列表 / 无 token 401）。
//   - DELETE /api/media/album/share：撤销共享（成功 / 非所有者拒绝 / 幂等）。
//   - 权限贯通：被共享者可 GET 相册详情、POST 添加媒体；非被共享者不可访问。
//
// 测试经 authMiddleware（srv.authMiddleware(srv.mux)）以注入 user_id，与真实链一致。
// 相册元数据走 service.AlbumStore（每用户 JSON 文件），共享关系走 SQLite album_shares。

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
	"time"

	"media-manager/backend/internal/auth"
	"media-manager/backend/internal/config"
	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"
)

// newAlbumShareGateway 构造带 storage + auth + albumStore 的 gateway，预注册两个用户
// alice（owner）与 bob（sharee），返回各 token 供测试驱动共享流程。
//
// 返回 (server, aliceToken, aliceUID, bobToken, bobUID, dataRoot)。
func newAlbumShareGateway(t *testing.T) (*Server, string, string, string, string, string) {
	t.Helper()
	dataRoot := t.TempDir()
	usersRoot := filepath.Join(dataRoot, "users")
	if err := os.MkdirAll(usersRoot, 0o755); err != nil {
		t.Fatalf("mkdir users root: %v", err)
	}
	userDirs := service.NewUserDirs(usersRoot)

	store, err := storage.Open(filepath.Join(dataRoot, "test.db"))
	if err != nil {
		t.Fatalf("storage.Open: %v", err)
	}
	t.Cleanup(func() { _ = store.Close() })

	// 计数式 ID 生成器：每个用户分配确定且唯一的 uid，便于断言。
	idSeq := 0
	authSvc, err := auth.New(
		auth.NewStoreBridge(store), "album-share-test-secret", 3600, config.SignupFirst,
		withCountingIDGen(&idSeq),
		auth.WithClock(func() time.Time { return time.Now().Add(time.Hour) }),
	)
	if err != nil {
		t.Fatalf("auth.New: %v", err)
	}
	// 注册 alice + bob。
	aliceRes, err := authSvc.Register(context.Background(), auth.RegisterRequest{Username: "alice", Password: "pw123456"})
	if err != nil {
		t.Fatalf("register alice: %v", err)
	}
	bobRes, err := authSvc.Register(context.Background(), auth.RegisterRequest{Username: "bob", Password: "pw123456"})
	if err != nil {
		t.Fatalf("register bob: %v", err)
	}

	// 组装 MediaService + AlbumStore（按用户隔离的 JSON 文件相册）。
	svc := service.NewMediaService(userDirs, "")
	albumStore := service.NewAlbumStoreWithDirs(userDirs)
	svc.SetAlbumStore(albumStore)

	srv := NewServer(":0", OpenClawConfig{}, svc, userDirs, authSvc)
	srv.SetStore(store)
	return srv, aliceRes.Token, aliceRes.User.ID, bobRes.Token, bobRes.User.ID, dataRoot
}

// withCountingIDGen 返回一个基于外部计数器的 ID 生成器 Option，使多次注册获得
// 确定性 uid（u-alice / u-bob / ...）。避免闭包捕获问题：计数器在 helper 中定义
// 并以指针共享，使 helper 误写（如上方未使用的第一段）不影响真实生成器。
func withCountingIDGen(seq *int) auth.Option {
	return auth.WithIDGenerator(func() string {
		*seq++
		switch *seq {
		case 1:
			return "u-alice"
		case 2:
			return "u-bob"
		default:
			return "u-extra"
		}
	})
}

// authedAlbumReq 构造带 Bearer token 的请求；经 authMiddleware 注入 user_id。
// 命名避开 share_handlers_test.go 中的 authedReq（同包内已存在）。
func authedAlbumReq(method, path, token string, body []byte) *http.Request {
	var r io.Reader
	if body != nil {
		r = bytes.NewReader(body)
	}
	req := httptest.NewRequest(method, path, r)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	return req
}

// doAlbumShare 经 authMiddleware 包裹的 mux 发请求，返回状态码与解析后的 body。
func doAlbumShare(t *testing.T, srv *Server, req *http.Request) (int, map[string]any) {
	t.Helper()
	rec := httptest.NewRecorder()
	srv.authMiddleware(srv.mux).ServeHTTP(rec, req)
	var m map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &m)
	return rec.Code, m
}

// createAlbumViaAPI 用 token 调 POST /api/media/album 创建相册，返回相册 id。
func createAlbumViaAPI(t *testing.T, srv *Server, token, name string) string {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"name": name})
	code, resp := doAlbumShare(t, srv, authedAlbumReq(http.MethodPost, "/api/media/album", token, body))
	if code != http.StatusOK {
		t.Fatalf("create album: want 200, got %d body=%v", code, resp)
	}
	id, _ := resp["id"].(string)
	if id == "" {
		t.Fatalf("create album: empty id, resp=%v", resp)
	}
	return id
}

// shareAlbumViaAPI 用 token 调 POST /api/media/album/share 共享相册，返回 (code, body)。
func shareAlbumViaAPI(t *testing.T, srv *Server, token, albumID, targetUsername string) (int, map[string]any) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"album_id": albumID, "username": targetUsername})
	return doAlbumShare(t, srv, authedAlbumReq(http.MethodPost, "/api/media/album/share", token, body))
}

// ===== POST /api/media/album/share =====

func TestAlbumShareCreateSuccess(t *testing.T) {
	srv, aliceToken, aliceUID, _, bobUID, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")

	code, resp := shareAlbumViaAPI(t, srv, aliceToken, albumID, "bob")
	if code != http.StatusOK {
		t.Fatalf("share: want 200, got %d body=%v", code, resp)
	}
	if resp["album_id"] != albumID {
		t.Fatalf("share: album_id mismatch: %v", resp["album_id"])
	}
	sw, _ := resp["shared_with"].(map[string]any)
	if sw == nil || sw["user_id"] != bobUID || sw["username"] != "bob" {
		t.Fatalf("share: shared_with mismatch: %v", resp["shared_with"])
	}
	if resp["already_shared"] != false {
		t.Fatalf("share: first share should have already_shared=false, got %v", resp["already_shared"])
	}
	if ea, _ := resp["shared_at"].(string); ea == "" {
		t.Fatalf("share: shared_at should be non-empty")
	}
	_ = aliceUID
}

func TestAlbumShareCreateNoAuth(t *testing.T) {
	srv, aliceToken, _, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// 无 token → authMiddleware 返回 401。
	body, _ := json.Marshal(map[string]any{"album_id": albumID, "username": "bob"})
	code, _ := doAlbumShare(t, srv, authedReq(http.MethodPost, "/api/media/album/share", "", body))
	if code != http.StatusUnauthorized {
		t.Fatalf("share without token: want 401, got %d", code)
	}
}

func TestAlbumShareCreateNonOwnerRejected(t *testing.T) {
	srv, aliceToken, _, bobToken, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// bob 不是所有者，试图共享 alice 的相册 → 404（不区分不存在与无权）。
	code, _ := shareAlbumViaAPI(t, srv, bobToken, albumID, "alice")
	if code != http.StatusNotFound {
		t.Fatalf("share by non-owner: want 404, got %d", code)
	}
}

func TestAlbumShareCreateRejectsSelfShare(t *testing.T) {
	srv, aliceToken, _, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// alice 试图把相册共享给自己 → 400。
	code, _ := shareAlbumViaAPI(t, srv, aliceToken, albumID, "alice")
	if code != http.StatusBadRequest {
		t.Fatalf("self share: want 400, got %d", code)
	}
}

func TestAlbumShareCreateUnknownUser(t *testing.T) {
	srv, aliceToken, _, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// 共享给不存在的用户 → 404。
	code, _ := shareAlbumViaAPI(t, srv, aliceToken, albumID, "nobody")
	if code != http.StatusNotFound {
		t.Fatalf("share to unknown user: want 404, got %d", code)
	}
}

func TestAlbumShareCreateIdempotent(t *testing.T) {
	srv, aliceToken, _, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// 第一次共享 → 200 already_shared=false。
	code, resp := shareAlbumViaAPI(t, srv, aliceToken, albumID, "bob")
	if code != http.StatusOK || resp["already_shared"] != false {
		t.Fatalf("first share: want 200 already_shared=false, got %d %v", code, resp)
	}
	// 第二次共享同一用户 → 200 already_shared=true（幂等）。
	code2, resp2 := shareAlbumViaAPI(t, srv, aliceToken, albumID, "bob")
	if code2 != http.StatusOK || resp2["already_shared"] != true {
		t.Fatalf("second share: want 200 already_shared=true, got %d %v", code2, resp2)
	}
}

func TestAlbumShareCreateByUserID(t *testing.T) {
	srv, aliceToken, _, _, bobUID, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// 用 user_id 而非 username 共享 → 成功。
	body, _ := json.Marshal(map[string]any{"album_id": albumID, "user_id": bobUID})
	code, resp := doAlbumShare(t, srv, authedReq(http.MethodPost, "/api/media/album/share", aliceToken, body))
	if code != http.StatusOK {
		t.Fatalf("share by user_id: want 200, got %d body=%v", code, resp)
	}
	sw, _ := resp["shared_with"].(map[string]any)
	if sw["user_id"] != bobUID {
		t.Fatalf("share by user_id: shared_with.user_id mismatch: %v", sw)
	}
}

func TestAlbumShareCreateMissingFields(t *testing.T) {
	srv, aliceToken, _, _, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// 缺 album_id → 400。
	body, _ := json.Marshal(map[string]any{"username": "bob"})
	code, _ := doAlbumShare(t, srv, authedReq(http.MethodPost, "/api/media/album/share", aliceToken, body))
	if code != http.StatusBadRequest {
		t.Fatalf("share missing album_id: want 400, got %d", code)
	}
	// 缺 username/user_id → 400。
	body2, _ := json.Marshal(map[string]any{"album_id": albumID})
	code2, _ := doAlbumShare(t, srv, authedReq(http.MethodPost, "/api/media/album/share", aliceToken, body2))
	if code2 != http.StatusBadRequest {
		t.Fatalf("share missing target: want 400, got %d", code2)
	}
}

// ===== DELETE /api/media/album/share =====

func TestAlbumShareDeleteByOwner(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	_, _ = shareAlbumViaAPI(t, srv, aliceToken, albumID, "bob")

	// 撤销共享。
	body, _ := json.Marshal(map[string]any{"album_id": albumID, "username": "bob"})
	code, resp := doAlbumShare(t, srv, authedReq(http.MethodDelete, "/api/media/album/share", aliceToken, body))
	if code != http.StatusOK {
		t.Fatalf("unshare: want 200, got %d body=%v", code, resp)
	}
	// 撤销后 bob 不再能 GET 相册详情 → 404。
	code2, _ := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/album/"+albumID, bobToken, nil))
	if code2 != http.StatusNotFound {
		t.Fatalf("bob GET after unshare: want 404, got %d", code2)
	}
}

func TestAlbumShareDeleteNonOwnerRejected(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	_, _ = shareAlbumViaAPI(t, srv, aliceToken, albumID, "bob")
	// bob（被共享者）试图撤销共享 → 404（仅所有者可撤销）。
	body, _ := json.Marshal(map[string]any{"album_id": albumID, "username": "bob"})
	code, _ := doAlbumShare(t, srv, authedReq(http.MethodDelete, "/api/media/album/share", bobToken, body))
	if code != http.StatusNotFound {
		t.Fatalf("unshare by non-owner: want 404, got %d", code)
	}
}

func TestAlbumShareDeleteNotFound(t *testing.T) {
	srv, aliceToken, _, _, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// 撤销一个从未存在的共享关系 → 404。
	body, _ := json.Marshal(map[string]any{"album_id": albumID, "username": "bob"})
	code, _ := doAlbumShare(t, srv, authedReq(http.MethodDelete, "/api/media/album/share", aliceToken, body))
	if code != http.StatusNotFound {
		t.Fatalf("unshare nonexistent: want 404, got %d", code)
	}
}

// ===== GET /api/media/albums/shared =====

func TestAlbumsSharedList(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	album1 := createAlbumViaAPI(t, srv, aliceToken, "Trip1")
	album2 := createAlbumViaAPI(t, srv, aliceToken, "Trip2")
	_, _ = shareAlbumViaAPI(t, srv, aliceToken, album1, "bob")
	_, _ = shareAlbumViaAPI(t, srv, aliceToken, album2, "bob")

	code, resp := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/albums/shared", bobToken, nil))
	if code != http.StatusOK {
		t.Fatalf("list shared: want 200, got %d body=%v", code, resp)
	}
	list, _ := resp["albums"].([]any)
	if len(list) != 2 {
		t.Fatalf("list shared: want 2 albums, got %d", len(list))
	}
	// 每条应含 owner_user_id 与 shared_at。
	first, _ := list[0].(map[string]any)
	if first["owner_user_id"] == "" || first["shared_at"] == "" {
		t.Fatalf("list shared: entry missing owner_user_id/shared_at: %v", first)
	}
	if first["name"] == "" {
		t.Fatalf("list shared: entry missing name: %v", first)
	}
}

func TestAlbumsSharedListEmpty(t *testing.T) {
	srv, _, _, bobToken, _, _ := newAlbumShareGateway(t)
	// bob 无任何被共享相册 → 200 + 空列表。
	code, resp := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/albums/shared", bobToken, nil))
	if code != http.StatusOK {
		t.Fatalf("list shared empty: want 200, got %d", code)
	}
	list, _ := resp["albums"].([]any)
	if len(list) != 0 {
		t.Fatalf("list shared empty: want 0, got %d", len(list))
	}
}

func TestAlbumsSharedListNoAuth(t *testing.T) {
	srv, _, _, _, _, _ := newAlbumShareGateway(t)
	code, _ := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/albums/shared", "", nil))
	if code != http.StatusUnauthorized {
		t.Fatalf("list shared no auth: want 401, got %d", code)
	}
}

func TestAlbumsSharedListExcludesOwnAlbums(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	// alice 创建相册但不共享给 bob。
	createAlbumViaAPI(t, srv, aliceToken, "Private")
	// bob 查共享列表 → 空（alice 的相册未共享给 bob）。
	code, resp := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/albums/shared", bobToken, nil))
	if code != http.StatusOK {
		t.Fatalf("list shared: want 200, got %d", code)
	}
	list, _ := resp["albums"].([]any)
	if len(list) != 0 {
		t.Fatalf("list shared should exclude unshared albums, got %d", len(list))
	}
}

// ===== 权限贯通：被共享者可查看详情 + 添加媒体 =====

func TestShareeCanViewAlbumDetail(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	_, _ = shareAlbumViaAPI(t, srv, aliceToken, albumID, "bob")

	// bob 以被共享者身份 GET 相册详情 → 200。
	code, resp := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/album/"+albumID, bobToken, nil))
	if code != http.StatusOK {
		t.Fatalf("sharee view: want 200, got %d body=%v", code, resp)
	}
	if resp["id"] != albumID {
		t.Fatalf("sharee view: album id mismatch: %v", resp["id"])
	}
	if resp["name"] != "Trip" {
		t.Fatalf("sharee view: name mismatch: %v", resp["name"])
	}
}

func TestShareeCanAddMediaToAlbum(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	_, _ = shareAlbumViaAPI(t, srv, aliceToken, albumID, "bob")

	// bob 向相册添加一个 media_id → 200（被共享者有添加权限）。
	body, _ := json.Marshal(map[string]any{"album_id": albumID, "media_id": "bob-media-1"})
	code, resp := doAlbumShare(t, srv, authedReq(http.MethodPost, "/api/media/album/add", bobToken, body))
	if code != http.StatusOK {
		t.Fatalf("sharee add: want 200, got %d body=%v", code, resp)
	}
	// 验证媒体确实加入了相册：alice（所有者）GET 详情应包含该 media_id。
	code2, resp2 := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/album/"+albumID, aliceToken, nil))
	if code2 != http.StatusOK {
		t.Fatalf("owner view after sharee add: want 200, got %d", code2)
	}
	mediaIDs, _ := resp2["media_ids"].([]any)
	found := false
	for _, m := range mediaIDs {
		if m == "bob-media-1" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("sharee add: media_id not in album after add, media_ids=%v", mediaIDs)
	}
}

func TestNonShareeCannotViewAlbum(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// 不共享给 bob。
	// bob 访问 → 404（无权，不区分不存在与无权）。
	code, _ := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/album/"+albumID, bobToken, nil))
	if code != http.StatusNotFound {
		t.Fatalf("non-sharee view: want 404, got %d", code)
	}
}

func TestNonShareeCannotAddMedia(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	// 不共享给 bob；bob 试图添加 → 404。
	body, _ := json.Marshal(map[string]any{"album_id": albumID, "media_id": "bob-media-1"})
	code, _ := doAlbumShare(t, srv, authedReq(http.MethodPost, "/api/media/album/add", bobToken, body))
	if code != http.StatusNotFound {
		t.Fatalf("non-sharee add: want 404, got %d", code)
	}
}

// ===== 删除相册级联清理共享 =====

func TestDeleteAlbumCascadesShareCleanup(t *testing.T) {
	srv, aliceToken, _, bobToken, _, _ := newAlbumShareGateway(t)
	albumID := createAlbumViaAPI(t, srv, aliceToken, "Trip")
	_, _ = shareAlbumViaAPI(t, srv, aliceToken, albumID, "bob")

	// alice 删除相册 → 200。
	code, _ := doAlbumShare(t, srv, authedReq(http.MethodDelete, "/api/media/album/"+albumID, aliceToken, nil))
	if code != http.StatusOK {
		t.Fatalf("delete album: want 200, got %d", code)
	}
	// bob 查共享列表 → 不再包含已删除相册（悬空共享已被级联清理，或即便未清理也因
	// 相册不存在而被 list 跳过）。两种实现下结果一致：空列表。
	code2, resp2 := doAlbumShare(t, srv, authedReq(http.MethodGet, "/api/media/albums/shared", bobToken, nil))
	if code2 != http.StatusOK {
		t.Fatalf("list shared after album delete: want 200, got %d", code2)
	}
	list, _ := resp2["albums"].([]any)
	if len(list) != 0 {
		t.Fatalf("list shared after album delete: want 0, got %d", len(list))
	}
}

// ===== 路由分流：POST/DELETE /api/media/album/share 不被 /api/media/album/ 误捕 =====

func TestAlbumShareRouteNotCapturedByResource(t *testing.T) {
	srv, aliceToken, _, _, _, _ := newAlbumShareGateway(t)
	// POST /api/media/album/share 缺 body → 经 handleAlbumShare（而非 handleAlbumResource）。
	// handleAlbumShare 解析空 body → 400 invalid body；handleAlbumResource 会因 albumID="share"
	// 走 GET/DELETE 分支并返回不同错误。此处验证走的是 share handler（400 invalid body）。
	code, resp := doAlbumShare(t, srv, authedReq(http.MethodPost, "/api/media/album/share", aliceToken, []byte("{}")))
	// 空 body {} 缺 album_id → 400 album_id is required（证明走 share handler）。
	if code != http.StatusBadRequest {
		t.Fatalf("POST /api/media/album/share routing: want 400, got %d body=%v", code, resp)
	}
	if e, _ := resp["error"].(string); e != "album_id is required" {
		t.Fatalf("POST /api/media/album/share routing: error mismatch: %v", resp["error"])
	}
}
