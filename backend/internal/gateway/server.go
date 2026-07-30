// Package gateway exposes a small HTTP surface alongside the gRPC server.
// Its first responsibility is the OpenClaw bridge: forwarding REST calls to
// the local OpenClaw gateway so KMP/web clients only talk to media-manager.
// It also exposes media REST endpoints that proxy to the internal gRPC service.
package gateway

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/google/uuid"

	"media-manager/backend/gen"
	"media-manager/backend/internal/auth"
	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"
)

// OpenClawConfig describes how to reach the local OpenClaw gateway.
type OpenClawConfig struct {
	BaseURL string        // e.g. http://127.0.0.1:18789
	Timeout time.Duration // per-request timeout; defaults to 10s
}

// Server is the REST gateway.
type Server struct {
	addr       string
	openClaw   OpenClawConfig
	httpClient *http.Client
	mux        *http.ServeMux
	mediaSvc   gen.MediaServiceServer
	userDirs   *service.UserDirs // per-user 数据目录解析；直读文件的端点据此按 user_id 定位
	cloudDir   string            // 网盘图片源根目录；为空表示未配置，stream 端点不回退查找
	startTime  time.Time
	authSvc    *auth.AuthService // 为 nil 时认证中间件放行所有请求（仅开发/测试用）
	store      *storage.Store    // 元数据库；为 nil 时 sync/device/usage/dedup 端点返回 503
}

// NewServer wires routes for the given addr. It does not start listening.
//
// userDirs 为 per-user 数据目录解析器；直读文件的上传/流式/健康检查等端点据此按
// 请求中的 user_id 定位该用户的 uploads 目录。为 nil 时这些端点无法定位任何用户
// 数据（仅适用于不使用文件直读路径的纯代理场景）。
// authSvc 为认证服务，非空时启用 JWT 中间件（/api/auth/* 与 /healthz 豁免，其余
// /api/* 未带有效 token 返回 401）。
func NewServer(addr string, cfg OpenClawConfig, mediaSvc gen.MediaServiceServer, userDirs *service.UserDirs, authSvc *auth.AuthService) *Server {
	if cfg.Timeout <= 0 {
		cfg.Timeout = 10 * time.Second
	}
	s := &Server{
		addr:       addr,
		openClaw:   cfg,
		httpClient: &http.Client{Timeout: cfg.Timeout},
		mux:        http.NewServeMux(),
		mediaSvc:   mediaSvc,
		userDirs:   userDirs,
		startTime:  time.Now(),
		authSvc:    authSvc,
	}
	s.registerRoutes()
	return s
}

// SetCloudDir 注入网盘图片源根目录，启用 /api/media/stream 对网盘原图的回退查找。
func (s *Server) SetCloudDir(dir string) { s.cloudDir = dir }

// SetStore 注入元数据库，启用多设备同步相关端点：/api/sync/changes、
// /api/sync/usage、/api/device/register、/api/device/list，以及 upload 的
// (user_id,sha256) 秒传去重。未注入时这些端点返回 503。
func (s *Server) SetStore(store *storage.Store) { s.store = store }

func (s *Server) registerRoutes() {
	// Auth: 登录/注册。这两个端点本身无需认证（中间件按路径前缀豁免）。
	s.mux.HandleFunc("/api/auth/login", s.handleAuthLogin)
	s.mux.HandleFunc("/api/auth/register", s.handleAuthRegister)

	// OpenClaw bridge
	s.mux.HandleFunc("/api/openclaw/command", s.handleOpenClawCommand)

	// Media REST endpoints (proxy to gRPC service)
	s.mux.HandleFunc("/api/media/list", s.handleMediaList)
	s.mux.HandleFunc("/api/media/stream/", s.handleMediaStream)
	s.mux.HandleFunc("/api/media/thumbnail/", s.handleMediaThumbnail)
	s.mux.HandleFunc("/api/media/delete", s.handleMediaDelete)
	s.mux.HandleFunc("/api/media/upload", s.handleMediaUpload)
	s.mux.HandleFunc("/api/media/metadata/", s.handleMediaMetadata)

	// 媒体收藏：POST 设置/取消收藏，DELETE 取消收藏，GET 返回收藏列表。
	s.mux.HandleFunc("/api/media/favorite", s.handleMediaFavorite)
	s.mux.HandleFunc("/api/media/favorites", s.handleMediaFavorites)
	s.mux.HandleFunc("/api/media/favorite-batch", s.handleMediaFavoriteBatch)

	// 相册：创建、列表、加入/移除媒体、删除。
	s.mux.HandleFunc("/api/media/album", s.handleAlbumCreate)
	s.mux.HandleFunc("/api/media/albums", s.handleAlbumList)
	s.mux.HandleFunc("/api/media/album/add", s.handleAlbumAdd)
	s.mux.HandleFunc("/api/media/album/remove", s.handleAlbumRemove)
	s.mux.HandleFunc("/api/media/album/", s.handleAlbumResource)

	// 视频信息：用 ffprobe 返回时长/分辨率，供前端展示与播放器初始化。
	s.mux.HandleFunc("/api/media/video-info/", s.handleMediaVideoInfo)

	// 多设备同步：增量 changes（含墓碑）、用户存储用量。
	s.mux.HandleFunc("/api/sync/changes", s.handleSyncChanges)
	s.mux.HandleFunc("/api/sync/usage", s.handleSyncUsage)

	// 设备注册与列表：记录接入的客户端，供多端同步审计。
	s.mux.HandleFunc("/api/device/register", s.handleDeviceRegister)
	s.mux.HandleFunc("/api/device/list", s.handleDeviceList)

	// Stats: 缩略图缓存命中率等可观测性指标。
	s.mux.HandleFunc("/api/stats", s.handleStats)

	// Health
	s.mux.HandleFunc("/healthz", s.handleHealthz)
}

// OpenClawBaseURL exposes the configured upstream URL for log/startup lines.
func (s *Server) OpenClawBaseURL() string { return s.openClaw.BaseURL }

// ListenAndServe blocks.
func (s *Server) ListenAndServe() error {
	// 中间件链：CORS → JWT 认证 → mux。authMiddleware 内部对 /api/auth/* 与 /healthz 放行。
	return http.ListenAndServe(s.addr, s.corsMiddleware(s.authMiddleware(s.mux)))
}

// userIDFromContext 取出中间件注入的 user_id；未认证请求返回空串。
// 与 service.UserIDFromContext 同源（中间件经 service.WithUserID 注入），这里仅作
// gateway 包内的便捷别名，避免每个 handler 直接跨包调用。
func userIDFromContext(ctx context.Context) string {
	return service.UserIDFromContext(ctx)
}

// authMiddleware 解析 Bearer token，校验后将 user_id 注入请求 context。
// 豁免路径：/api/auth/*（登录/注册本身）与 /healthz（健康检查）。其余路径无有效 token 返回 401。
// s.authSvc 为 nil 时（未配置认证）直接放行，便于无认证的开发/测试场景。
//
// user_id 通过 service.WithUserID 注入，使用 service 包的 context key —— 这样
// MediaService 与 gateway handler 用同一把 key 取回 user_id（service.UserIDFromContext），
// 避免两层各自定义 key 导致互不可见。gateway 本地的 userIDFromContext 仅用于
// handler 显式取值（转发给 service 方法），底层与此同源。
func (s *Server) authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.authSvc == nil {
			next.ServeHTTP(w, r)
			return
		}
		// 豁免登录/注册与健康检查：这些端点本身就是获取 token 或探活用的。
		if strings.HasPrefix(r.URL.Path, "/api/auth/") || r.URL.Path == "/healthz" {
			next.ServeHTTP(w, r)
			return
		}
		// 取 Authorization: Bearer <token>。
		authHeader := r.Header.Get("Authorization")
		if authHeader == "" {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "missing authorization header"})
			return
		}
		const bearerPrefix = "Bearer "
		if !strings.HasPrefix(authHeader, bearerPrefix) {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid authorization scheme"})
			return
		}
		tokenStr := strings.TrimSpace(strings.TrimPrefix(authHeader, bearerPrefix))
		if tokenStr == "" {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "missing token"})
			return
		}
		userID, err := s.authSvc.ParseToken(tokenStr)
		if err != nil {
			writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid or expired token"})
			return
		}
		// 注入 user_id 供下游 handler 与 MediaService 取用（共用 service 包的 key）。
		ctx := service.WithUserID(r.Context(), userID)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// corsMiddleware adds permissive CORS headers for future web frontend support.
// Preflight OPTIONS requests are short-circuited with 204.
func (s *Server) corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ============ Media REST endpoints ============

func (s *Server) handleMediaList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	page := int32(1)
	pageSize := int32(20)
	filterType := gen.MediaType_IMAGE
	searchQuery := ""
	if v := r.URL.Query().Get("page"); v != "" {
		pi, _ := parseIntSafe(v)
		if pi > 0 {
			page = int32(pi)
		}
	}
	if v := r.URL.Query().Get("page_size"); v != "" {
		ps, _ := parseIntSafe(v)
		if ps > 0 {
			pageSize = int32(ps)
		}
	}
	if v := r.URL.Query().Get("type"); v != "" {
		filterType = parseMediaType(v)
	}
	if v := r.URL.Query().Get("q"); v != "" {
		searchQuery = v
	}

	resp, err := s.mediaSvc.GetMediaList(r.Context(), &gen.GetMediaListRequest{
		Page:        page,
		PageSize:    pageSize,
		FilterType:  filterType,
		SearchQuery: searchQuery,
	})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 如果 media service 支持收藏查询，给每条媒体补充 favorite 字段（按当前用户判定）。
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		uid := userIDFromContext(r.Context())
		writeJSON(w, http.StatusOK, enrichMediaList(resp, fav, uid))
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleHealthz(w http.ResponseWriter, r *http.Request) {
	// /healthz 不要求认证（中间件豁免），故无单用户 user_id；media_count 与
	// disk 在此跨所有已创建的用户 uploads 目录聚合统计，favorite_count 走
	// favoriteProvider.TotalFavorites 聚合所有已加载用户。空 userDirs（纯代理
	// 场景）时相应指标为 0。
	mediaCount := s.countAllUserMedia()

	uptime := time.Since(s.startTime).Truncate(time.Second)

	cacheStatus := "unknown"
	cacheHitRate := 0.0
	if _, ok := s.mediaSvc.(*service.MediaService); ok {
		hits, misses := service.GetListCacheStats()
		total := hits + misses
		if total == 0 {
			cacheStatus = "idle"
		} else if hits > 0 {
			cacheStatus = "hit"
		} else {
			cacheStatus = "miss"
		}
		if total > 0 {
			cacheHitRate = float64(hits) / float64(total) * 100
		}
	}

	favoriteCount := 0
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favoriteCount = fav.TotalFavorites()
	}

	// Disk space: statfs on the users root device.
	diskInfo := map[string]any{}
	if root := s.usersRoot(); root != "" {
		if stat, err := diskUsage(root); err == nil {
			diskInfo = map[string]any{
				"total_bytes":     stat.TotalBytes,
				"available_bytes": stat.AvailableBytes,
				"used_bytes":      stat.UsedBytes,
				"total_gb":        fmt.Sprintf("%.2f", float64(stat.TotalBytes)/1e9),
				"available_gb":    fmt.Sprintf("%.2f", float64(stat.AvailableBytes)/1e9),
				"used_gb":         fmt.Sprintf("%.2f", float64(stat.UsedBytes)/1e9),
				"usage_percent":   fmt.Sprintf("%.1f%%", stat.UsagePercent),
			}
		}
	}

	// Memory info: Go runtime MemStats for process-level memory.
	var memStats runtime.MemStats
	runtime.ReadMemStats(&memStats)
	memoryInfo := map[string]any{
		"alloc_bytes":      memStats.Alloc,
		"alloc_mb":         fmt.Sprintf("%.2f", float64(memStats.Alloc)/1e6),
		"sys_bytes":        memStats.Sys,
		"sys_mb":           fmt.Sprintf("%.2f", float64(memStats.Sys)/1e6),
		"heap_alloc_bytes": memStats.HeapAlloc,
		"heap_inuse_bytes": memStats.HeapInuse,
		"num_goroutine":    runtime.NumGoroutine(),
		"num_gc":           memStats.NumGC,
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"status":         "ok",
		"version":        "v0.3.0",
		"media_count":    mediaCount,
		"uptime":         fmt.Sprintf("%ds", int(uptime.Seconds())),
		"cache":          cacheStatus,
		"cache_hit_rate": fmt.Sprintf("%.1f%%", cacheHitRate),
		"favorite_count": favoriteCount,
		"disk":           diskInfo,
		"memory":         memoryInfo,
	})
}

func (s *Server) handleMediaStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/stream/")
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}

	// Direct file read (bypasses gRPC streaming for REST). 文件定位到当前用户的
	// uploads 目录；未命中再回退到全局共享的网盘图片源（其 id 为去扩展名的文件名）。
	uid := userIDFromContext(r.Context())
	// 纵深防御：uid 缺失时不得以空串拼路径——filepath.Join("", ...) 会退化为相对
	// 路径，Glob 可能命中进程 cwd 下任意文件并被 ServeFile 直接返回（信息泄露）。
	// 正常路径 auth 中间件已保证 /api/* 带 token，此处兜底拦截未注入 uid 的异常情形。
	uploadsDir := s.userUploadsDir(uid)
	if uploadsDir == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}
	files, err := filepath.Glob(filepath.Join(uploadsDir, mediaID+".*"))
	if err != nil || len(files) == 0 {
		// 该用户 uploads 未命中 → 回退到网盘图片源目录（全局共享公共源，安全）。
		if s.cloudDir != "" {
			files, err = filepath.Glob(filepath.Join(s.cloudDir, mediaID+".*"))
		}
		if err != nil || len(files) == 0 {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not found"})
			return
		}
	}
	// 显式设置 Content-Type：http.ServeFile 默认靠字节嗅探，对多数视频容器会得到
	// application/octet-stream，导致浏览器无法内联播放。这里按扩展名前置正确的 video/* 或 image/*。
	// ServeFile 不会覆盖已设置的 Content-Type，故 Range 分片播放不受影响。
	// 未知扩展名回退 application/octet-stream 以保证所有响应都有显式 Content-Type。
	ct := videoMimeType(files[0])
	if ct == "" {
		ct = "application/octet-stream"
	}
	w.Header().Set("Content-Type", ct)
	http.ServeFile(w, r, files[0])
}

// handleMediaVideoInfo 用 ffprobe 返回视频时长与分辨率。
// 仅 *service.MediaService 实现 GetVideoInfo（未进 proto），gateway 通过 service.VideoInfoProvider
// 能力接口断言调用；不实现的 service 返回 501。
func (s *Server) handleMediaVideoInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/video-info/")
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}

	provider, ok := s.mediaSvc.(videoInfoProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "video info is not supported by the configured media service"})
		return
	}
	// ctx 超时限制 ffprobe，避免大文件/损坏文件挂起连接。
	ctx, cancel := context.WithTimeout(r.Context(), 15*time.Second)
	defer cancel()
	resp, err := provider.GetVideoInfo(ctx, &service.VideoInfoRequest{MediaId: mediaID})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

// videoInfoProvider 是 service.VideoInfoProvider 的本地别名，gateway 借此对 mediaSvc 做能力
// 断言并按需调用 GetVideoInfo（该方法未进 proto/未在 gen.MediaServiceServer 中声明）。
type videoInfoProvider interface {
	GetVideoInfo(ctx context.Context, req *service.VideoInfoRequest) (*service.VideoInfoResponse, error)
}

// videoMimeType 按小写扩展名返回视频/图片 MIME；未知扩展名回退 octet-stream。
// 用于 handleMediaStream 在 ServeFile 前显式设置 Content-Type，确保浏览器以正确类型播放视频
// （http.ServeFile 默认靠字节嗅探，对多数视频容器会得到 octet-stream）。
func videoMimeType(filename string) string {
	switch strings.ToLower(filepath.Ext(filename)) {
	case ".mp4":
		return "video/mp4"
	case ".mov":
		return "video/quicktime"
	case ".avi":
		return "video/x-msvideo"
	case ".mkv":
		return "video/x-matroska"
	case ".webm":
		return "video/webm"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".bmp":
		return "image/bmp"
	default:
		return ""
	}
}

func (s *Server) handleMediaThumbnail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/thumbnail/")
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}
	sizeStr := r.URL.Query().Get("size")
	size := gen.ThumbnailSize_THUMBNAIL_MEDIUM
	switch strings.ToLower(sizeStr) {
	case "small":
		size = gen.ThumbnailSize_THUMBNAIL_SMALL
	case "large":
		size = gen.ThumbnailSize_THUMBNAIL_LARGE
	}

	resp, err := s.mediaSvc.GetThumbnail(r.Context(), &gen.GetThumbnailRequest{MediaId: mediaID, Size: size})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", resp.MimeType)
	_, _ = w.Write(resp.Data)
}

// maxRequestBodyBytes limits JSON body reads to 10 MB to prevent malicious
// oversized requests from exhausting server memory.
const maxRequestBodyBytes = 10 << 20

func (s *Server) handleMediaDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	var req struct {
		MediaIds []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	resp, err := s.mediaSvc.DeleteMedia(r.Context(), &gen.DeleteMediaRequest{MediaIds: req.MediaIds})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 软删除墓碑：在 SQLite 标记 deleted=1，使 /api/sync/changes 能返回墓碑
	if s.store != nil {
		for _, mid := range req.MediaIds {
			_ = s.store.MarkDeleted(r.Context(), mid)
		}
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleMediaUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	uploadsDir := s.userUploadsDir(uid)
	if uploadsDir == "" {
		// userDirs 未配置或 uid 非法：拒绝落盘（未认证不应能上传）。
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}
	// Direct file write (bypasses gRPC streaming for REST)
	// Upload cap is 100 MB (larger than the 10 MB JSON body limit, since uploads are raw file bytes).
	body, err := io.ReadAll(io.LimitReader(r.Body, 100<<20)) // 100MB cap
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "failed to read body"})
		return
	}
	filename := r.URL.Query().Get("filename")
	if filename == "" {
		filename = "upload.dat"
	}

	// 同步扩展参数（query 传递，因 body 是 raw binary）：
	//   - sha256：内容指纹；提供时按 (user_id,sha256) 去重，命中则秒传不落盘。
	//   - client_id：客户端幂等键，原样入库供多端冲突排查（可空）。
	//   - taken_at：内容拍摄时间（ms）；0 表未知。
	sha256Param := strings.TrimSpace(r.URL.Query().Get("sha256"))
	clientID := strings.TrimSpace(r.URL.Query().Get("client_id"))
	takenAt := parseInt64Query(r.URL.Query().Get("taken_at"))

	// 服务端实测 sha256：以实际落盘字节为准，避免客户端误报导致去重错乱。
	// 若客户端传了 sha256 且与服务端实测不符，以服务端实测值为准（信任本地计算）。
	actualSHA := sha256Hex(body)
	dedupSHA := sha256Param
	if dedupSHA == "" {
		dedupSHA = actualSHA
	}

	// 秒传去重：store 已配置且该用户已存在同 sha256 的内容 → 直接复用既有 media_id，
	// 不重复落盘。即便既有行已被软删，也视为"该内容已存在过"，秒传返回其 id
	// 并复活（写回 deleted=0 + 刷新 updated_at），使多端删除-重传语义一致。
	if s.store != nil && dedupSHA != "" {
		if existing, derr := s.store.GetMediaByUserAndSHA256(r.Context(), uid, dedupSHA); derr == nil && existing != nil {
			resp := map[string]any{
				"media_id": existing.ID,
				"status":   "deduped",
				"size":     existing.Size,
				"sha256":   existing.SHA256,
			}
			// 若既有记录处于软删状态，复活它（updated_at 刷新 → 扩散到其它设备）。
			if existing.Deleted {
				_ = s.reviveDeletedMedia(r.Context(), existing.ID)
				resp["status"] = "deduped_restored"
			}
			writeJSON(w, http.StatusOK, resp)
			return
		}
	}

	// Use the original filename for on-disk storage so the filename is preserved
	// even without a metadata sidecar. Resolve collisions by appending a counter.
	ext := filepath.Ext(filename)
	if ext == "" {
		ext = ".dat"
		filename = filename + ext
	}
	// id 用 uuid，避免多人/多端同名文件冲突，并为 storage 表提供稳定主键。
	id := uuid.New().String()
	uploadPath := filepath.Join(uploadsDir, id+ext)
	// 即便 uuid 撞名概率极低，仍兜底处理文件已存在的情形（按序追加后缀）。
	baseName := strings.TrimSuffix(filename, ext)
	collision := 0
	for {
		if _, err := os.Stat(uploadPath); os.IsNotExist(err) {
			break
		}
		collision++
		uploadPath = filepath.Join(uploadsDir, fmt.Sprintf("%s_%d%s", baseName, collision, ext))
		id = strings.TrimSuffix(filepath.Base(uploadPath), ext)
	}
	if err := os.WriteFile(uploadPath, body, 0644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// Write metadata sidecar to 该用户的 metadata 目录 {id}.json。
	mimeType := detectMimeType(filename)
	metaWarn := ""
	if err := s.writeUploadMetadata(uid, id, filename, int64(len(body)), mimeType); err != nil {
		// Metadata write failure is non-fatal; include warning but still return success.
		metaWarn = err.Error()
	}

	// 写 storage.Store media 表，使该上传可被 /api/sync/changes 增量推送、
	// 被 (user_id,sha256) 去重命中。store 未配置时跳过（向后兼容旧部署）。
	// 失败仅记录 warning，不阻断上传成功响应（文件已落盘）。
	storeWarn := ""
	if s.store != nil {
		mediaType := detectMediaTypeForStorage(filename)
		if serr := s.store.CreateMedia(r.Context(), &storage.Media{
			ID:       id,
			UserID:   uid,
			Filename: filename,
			Type:     mediaType,
			Size:     int64(len(body)),
			Mime:     mimeType,
			SHA256:   actualSHA,
			ClientID: clientID,
			TakenAt:  takenAt,
		}); serr != nil {
			storeWarn = serr.Error()
		}
	}

	resp := map[string]any{
		"media_id": id,
		"status":   "success",
		"size":     len(body),
		"sha256":   actualSHA,
	}
	if metaWarn != "" {
		resp["metadata_warning"] = metaWarn
	}
	if storeWarn != "" {
		resp["store_warning"] = storeWarn
	}
	writeJSON(w, http.StatusOK, resp)
}

// reviveDeletedMedia 把已软删的 media 行复活（deleted=0 并刷新 updated_at），
// 供秒传命中软删记录时恢复内容可见性，使多端"删除-重传"语义一致。
func (s *Server) reviveDeletedMedia(ctx context.Context, mediaID string) error {
	return s.store.UndeleteMedia(ctx, mediaID)
}

// sha256Hex 返回字节的 SHA-256 十六进制摘要（小写）。
func sha256Hex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

// parseInt64Query 解析查询参数为 int64；空串或非数字返回 0。
func parseInt64Query(v string) int64 {
	if v == "" {
		return 0
	}
	var x int64
	if _, err := fmt.Sscanf(v, "%d", &x); err != nil {
		return 0
	}
	return x
}

// detectMediaTypeForStorage 按扩展名返回 storage 层使用的媒体类型字符串
// （"IMAGE"/"VIDEO"/"LIVE_PHOTO"），与 gen.MediaType 语义对齐。
func detectMediaTypeForStorage(filename string) string {
	switch strings.ToLower(filepath.Ext(filename)) {
	case ".mp4", ".mov", ".avi", ".mkv", ".webm":
		return "VIDEO"
	default:
		return "IMAGE"
	}
}

// userUploadsDir 返回当前用户 uid 的 uploads 目录路径（确保已创建）。
// userDirs 未注入或 uid 非法时返回空串，调用方据此拒绝文件直读操作。
func (s *Server) userUploadsDir(uid string) string {
	if s.userDirs == nil {
		return ""
	}
	dir, err := s.userDirs.UploadsDir(uid)
	if err != nil {
		return ""
	}
	return dir
}

// usersRoot 返回 per-user 目录的父根（data/users），供 diskUsage 等聚合指标使用。
// userDirs 未注入时返回空串。
func (s *Server) usersRoot() string {
	if s.userDirs == nil {
		return ""
	}
	return s.userDirs.UsersRoot()
}

// countAllUserMedia 跨所有已存在的用户 uploads 目录聚合统计媒体文件数，
// 供 /healthz 在无单一 user_id 的情形下给出全局 media_count。仅扫已落盘的
// <usersRoot>/<uid>/uploads 目录（不依赖运行期"哪些 uid 访问过"的记忆）。
// userDirs 未注入时返回 0。
func (s *Server) countAllUserMedia() int {
	if s.userDirs == nil {
		return 0
	}
	root := s.userDirs.UsersRoot()
	entries, err := os.ReadDir(root)
	if err != nil {
		return 0
	}
	total := 0
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		uploadsPath := filepath.Join(root, e.Name(), "uploads")
		userEntries, err := os.ReadDir(uploadsPath)
		if err != nil {
			continue
		}
		for _, f := range userEntries {
			if !f.IsDir() && strings.Contains(f.Name(), ".") {
				total++
			}
		}
	}
	return total
}

func (s *Server) handleMediaMetadata(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/metadata/")
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}
	resp, err := s.mediaSvc.GetMediaMetadata(r.Context(), &gen.GetMediaMetadataRequest{MediaId: mediaID})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

// ============ OpenClaw bridge ============

type openclawCommandRequest struct {
	Path   string          `json:"path"`
	Method string          `json:"method,omitempty"`
	Body   json.RawMessage `json:"body,omitempty"`
}

type openclawCommandResponse struct {
	Status      int             `json:"status"`
	ContentType string          `json:"content_type,omitempty"`
	Body        json.RawMessage `json:"body,omitempty"`
	RawBody     string          `json:"raw_body,omitempty"`
	Upstream    string          `json:"upstream"`
}

func (s *Server) handleOpenClawCommand(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if !strings.HasPrefix(s.openClaw.BaseURL, "http://") && !strings.HasPrefix(s.openClaw.BaseURL, "https://") {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "openclaw base url not configured"})
		return
	}

	var req openclawCommandRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid request body: " + err.Error()})
		return
	}
	if !strings.HasPrefix(req.Path, "/") || strings.Contains(req.Path, "..") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "path must start with '/' and must not contain '..'"})
		return
	}
	method := strings.ToUpper(req.Method)
	if method == "" {
		method = http.MethodPost
	}
	if !isAllowedMethod(method) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "method not allowed: " + method})
		return
	}

	upstreamURL := strings.TrimRight(s.openClaw.BaseURL, "/") + req.Path
	ctx, cancel := context.WithTimeout(r.Context(), s.openClaw.Timeout)
	defer cancel()

	upReq, err := http.NewRequestWithContext(ctx, method, upstreamURL, bytes.NewReader(req.Body))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "failed to build upstream request: " + err.Error()})
		return
	}
	if len(req.Body) > 0 {
		upReq.Header.Set("Content-Type", "application/json")
	}
	upReq.Header.Set("Accept", "application/json")

	resp, err := s.httpClient.Do(upReq)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error":    "failed to reach openclaw gateway",
			"upstream": upstreamURL,
			"detail":   err.Error(),
		})
		return
	}
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error":    "failed to read upstream response",
			"upstream": upstreamURL,
			"detail":   err.Error(),
		})
		return
	}

	out := openclawCommandResponse{
		Status:      resp.StatusCode,
		ContentType: resp.Header.Get("Content-Type"),
		Upstream:    upstreamURL,
	}
	if isJSONContentType(out.ContentType) && len(bodyBytes) > 0 {
		out.Body = json.RawMessage(bodyBytes)
	} else {
		out.RawBody = string(bodyBytes)
	}
	writeJSON(w, http.StatusOK, out)
}

// ============ Favorite endpoints ============

// favoriteProvider 是 service.MediaService 的收藏能力接口，
// gateway 借此对 mediaSvc 做能力断言并按需调用收藏方法。所有方法按 user_id 隔离。
// TotalFavorites 返回所有已加载用户的收藏总数聚合（供 /healthz 全局观测，
// 不按单用户隔离——它是个跨用户的健康指标，并非某用户的数据泄露面）。
type favoriteProvider interface {
	IsFavorite(uid, mediaId string) bool
	ListFavorites(uid string) []string
	AddFavorite(uid, mediaId string) error
	RemoveFavorite(uid, mediaId string) error
	TotalFavorites() int
}

// handleMediaFavorite 处理 POST 和 DELETE /api/media/favorite。
// POST 请求体: {"media_id":"xxx","favorite":true/false}
// DELETE 请求体: {"media_id":"xxx"}  — 等价于 favorite:false。
func (s *Server) handleMediaFavorite(w http.ResponseWriter, r *http.Request) {
	var req struct {
		MediaId  string `json:"media_id"`
		Favorite bool   `json:"favorite"`
	}

	switch r.Method {
	case http.MethodPost:
		if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
			return
		}
	case http.MethodDelete:
		if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
			return
		}
		req.Favorite = false // DELETE 永远是取消收藏
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}

	if req.MediaId == "" || strings.Contains(req.MediaId, "..") || strings.Contains(req.MediaId, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}

	fav, ok := s.mediaSvc.(favoriteProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "favorite is not supported by the configured media service"})
		return
	}
	uid := userIDFromContext(r.Context())

	var err error
	if req.Favorite {
		err = fav.AddFavorite(uid, req.MediaId)
	} else {
		err = fav.RemoveFavorite(uid, req.MediaId)
	}
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "media_id": req.MediaId, "favorite": req.Favorite})
}

// handleMediaFavorites 处理 GET /api/media/favorites，返回当前用户的收藏 mediaId 列表。
func (s *Server) handleMediaFavorites(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	fav, ok := s.mediaSvc.(favoriteProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "favorite is not supported by the configured media service"})
		return
	}
	uid := userIDFromContext(r.Context())
	writeJSON(w, http.StatusOK, map[string]any{"favorites": fav.ListFavorites(uid)})
}

// handleMediaFavoriteBatch 处理 POST /api/media/favorite-batch，批量设置/取消收藏。
// 请求体: {"media_ids":["a","b"],"favorite":true}
func (s *Server) handleMediaFavoriteBatch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	var req struct {
		MediaIds []string `json:"media_ids"`
		Favorite bool     `json:"favorite"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.MediaIds) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids must not be empty"})
		return
	}
	for _, id := range req.MediaIds {
		if id == "" || strings.Contains(id, "..") || strings.Contains(id, "/") {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id in list"})
			return
		}
	}

	fav, ok := s.mediaSvc.(favoriteProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "favorite is not supported by the configured media service"})
		return
	}
	uid := userIDFromContext(r.Context())

	succeeded := 0
	failed := 0
	for _, id := range req.MediaIds {
		var err error
		if req.Favorite {
			err = fav.AddFavorite(uid, id)
		} else {
			err = fav.RemoveFavorite(uid, id)
		}
		if err != nil {
			failed++
		} else {
			succeeded++
		}
	}

	statusMsg := "success"
	if failed > 0 {
		statusMsg = fmt.Sprintf("partial: %d succeeded, %d failed", succeeded, failed)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"status":    statusMsg,
		"succeeded": succeeded,
		"failed":    failed,
		"favorite":  req.Favorite,
	})
}

// albumStoreProvider 是 service.MediaService 的相册能力接口，
// gateway 借此对 mediaSvc 做能力断言并按需调用相册方法。所有方法按 user_id 隔离。
type albumStoreProvider interface {
	CreateAlbum(uid, name string) (*service.Album, error)
	AddToAlbum(uid, albumID, mediaID string) error
	RemoveFromAlbum(uid, albumID, mediaID string) error
	ListAlbums(uid string) []*service.Album
	GetAlbum(uid, albumID string) *service.Album
	DeleteAlbum(uid, albumID string) error
}

// handleAlbumCreate 处理 POST /api/media/album，创建新相册。
// 请求体: {"name":"xxx"}
func (s *Server) handleAlbumCreate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	var req struct {
		Name string `json:"name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.Name == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "name must not be empty"})
		return
	}

	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported by the configured media service"})
		return
	}
	uid := userIDFromContext(r.Context())
	album, err := provider.CreateAlbum(uid, req.Name)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, album)
}

// handleAlbumList 处理 GET /api/media/albums，返回当前用户的所有相册列表。
func (s *Server) handleAlbumList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported by the configured media service"})
		return
	}
	uid := userIDFromContext(r.Context())
	writeJSON(w, http.StatusOK, map[string]any{"albums": provider.ListAlbums(uid)})
}

// handleAlbumAdd 处理 POST /api/media/album/add，将媒体加入相册。
// 请求体: {"album_id":"x","media_id":"y"}
func (s *Server) handleAlbumAdd(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	var req struct {
		AlbumID string `json:"album_id"`
		MediaID string `json:"media_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.AlbumID == "" || req.MediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id and media_id are required"})
		return
	}
	if strings.Contains(req.MediaID, "..") || strings.Contains(req.MediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}

	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported by the configured media service"})
		return
	}
	uid := userIDFromContext(r.Context())
	if err := provider.AddToAlbum(uid, req.AlbumID, req.MediaID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "album_id": req.AlbumID, "media_id": req.MediaID})
}

// handleAlbumRemove 处理 POST /api/media/album/remove，将媒体从相册中移除。
// 请求体: {"album_id":"x","media_id":"y"}
func (s *Server) handleAlbumRemove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	var req struct {
		AlbumID string `json:"album_id"`
		MediaID string `json:"media_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.AlbumID == "" || req.MediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id and media_id are required"})
		return
	}

	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported by the configured media service"})
		return
	}
	uid := userIDFromContext(r.Context())
	if err := provider.RemoveFromAlbum(uid, req.AlbumID, req.MediaID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "album_id": req.AlbumID, "media_id": req.MediaID})
}

// handleAlbumResource 处理 /api/media/album/{id} 路径下的请求。
// GET → 获取相册详情；DELETE → 删除相册。
func (s *Server) handleAlbumResource(w http.ResponseWriter, r *http.Request) {
	albumID := strings.TrimPrefix(r.URL.Path, "/api/media/album/")
	if albumID == "" || strings.Contains(albumID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid album id"})
		return
	}

	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported by the configured media service"})
		return
	}
	uid := userIDFromContext(r.Context())

	switch r.Method {
	case http.MethodGet:
		album := provider.GetAlbum(uid, albumID)
		if album == nil {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
			return
		}
		writeJSON(w, http.StatusOK, album)
	case http.MethodDelete:
		if err := provider.DeleteAlbum(uid, albumID); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"status": "success", "album_id": albumID})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
	}
}

// enrichMediaList 将 GetMediaListResponse 中每条媒体补充 favorite 字段，
// 返回一个兼容原 JSON 结构但多了 favorite 键的 map。favorite 按 uid 判定该用户收藏。
func enrichMediaList(resp *gen.GetMediaListResponse, fav favoriteProvider, uid string) map[string]any {
	list := make([]map[string]any, 0, len(resp.MediaList))
	for _, m := range resp.MediaList {
		raw, err := json.Marshal(m)
		if err != nil {
			continue
		}
		var item map[string]any
		if err := json.Unmarshal(raw, &item); err != nil {
			continue
		}
		item["favorite"] = fav.IsFavorite(uid, m.Id)
		list = append(list, item)
	}
	return map[string]any{
		"media_list":  list,
		"total_count": resp.TotalCount,
		"page":        resp.Page,
		"page_size":   resp.PageSize,
	}
}

// writeUploadMetadata writes a metadata sidecar JSON to the user's metadata
// directory {id}.json after a successful upload. Contains filename, size,
// created_at, and mime_type.
func (s *Server) writeUploadMetadata(uid, id, filename string, size int64, mimeType string) error {
	if s.userDirs == nil {
		return fmt.Errorf("user dirs not configured")
	}
	metaDir, err := s.userDirs.MetadataDir(uid)
	if err != nil {
		return fmt.Errorf("failed to resolve metadata dir: %w", err)
	}
	meta := map[string]any{
		"filename":   filename,
		"size":       size,
		"created_at": time.Now().Unix(),
		"mime_type":  mimeType,
	}
	data, err := json.Marshal(meta)
	if err != nil {
		return fmt.Errorf("failed to marshal metadata: %w", err)
	}
	metaPath := filepath.Join(metaDir, id+".json")
	return os.WriteFile(metaPath, data, 0644)
}

// detectMimeType returns the MIME type for a filename based on its extension.
func detectMimeType(filename string) string {
	switch strings.ToLower(filepath.Ext(filename)) {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".bmp":
		return "image/bmp"
	case ".webp":
		return "image/webp"
	case ".mp4":
		return "video/mp4"
	case ".mov":
		return "video/quicktime"
	case ".avi":
		return "video/x-msvideo"
	case ".mkv":
		return "video/x-matroska"
	case ".webm":
		return "video/webm"
	default:
		return "application/octet-stream"
	}
}

// ============ Stats endpoint ============

// thumbCacheProvider 是 service.MediaService 的缩略图缓存能力接口，
// 供 /api/stats 端点获取 ThumbCache 统计数据。
type thumbCacheProvider interface {
	ThumbCacheStats() service.ThumbCacheStats
}

// handleStats 处理 GET /api/stats，返回缩略图缓存命中率等可观测性指标。
func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}

	// Thumbnail cache stats from the media service.
	thumbStats := map[string]any{}
	if provider, ok := s.mediaSvc.(thumbCacheProvider); ok {
		ts := provider.ThumbCacheStats()
		thumbStats = map[string]any{
			"hits":             ts.Hits,
			"misses":           ts.Misses,
			"hit_rate_percent": ts.HitRate,
			"items":            ts.Items,
			"max_items":        ts.MaxItems,
			"total_bytes":      ts.TotalBytes,
			"max_bytes":        ts.MaxBytes,
		}
	}

	// List cache stats (GetMediaList cache).
	listHits, listMisses := service.GetListCacheStats()
	listTotal := listHits + listMisses
	var listHitRate float64
	if listTotal > 0 {
		listHitRate = float64(listHits) / float64(listTotal) * 100
	}
	listStats := map[string]any{
		"hits":             listHits,
		"misses":           listMisses,
		"hit_rate_percent": listHitRate,
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"thumbnail_cache": thumbStats,
		"list_cache":      listStats,
	})
}

// ============ Helpers ============

func isAllowedMethod(m string) bool {
	switch m {
	case http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete:
		return true
	}
	return false
}

func isJSONContentType(ct string) bool {
	ct = strings.ToLower(strings.TrimSpace(strings.Split(ct, ";")[0]))
	return ct == "application/json" || strings.HasSuffix(ct, "+json")
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		_, _ = fmt.Fprintf(w, `{"error":"encode failed: %s"}`, err.Error())
	}
}

func parseIntSafe(s string) (int, error) {
	var n int
	_, err := fmt.Sscanf(s, "%d", &n)
	return n, err
}

func parseMediaType(s string) gen.MediaType {
	switch strings.ToLower(s) {
	case "video":
		return gen.MediaType_VIDEO
	case "live_photo", "livephoto":
		return gen.MediaType_LIVE_PHOTO
	default:
		return gen.MediaType_IMAGE
	}
}

var ErrUpstreamUnavailable = errors.New("openclaw upstream unavailable")

// ============ Disk & memory helpers ============

// diskInfo holds disk usage statistics for a mounted filesystem.
type diskInfo struct {
	TotalBytes     int64
	AvailableBytes int64
	UsedBytes      int64
	UsagePercent   float64
}

// diskUsage returns disk usage for the filesystem containing the given path.
// Uses syscall.Statfs which works on macOS and Linux.
func diskUsage(path string) (*diskInfo, error) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(path, &stat); err != nil {
		return nil, err
	}
	total := int64(stat.Blocks) * int64(stat.Bsize)
	avail := int64(stat.Bavail) * int64(stat.Bsize)
	used := total - avail
	var usagePct float64
	if total > 0 {
		usagePct = float64(used) / float64(total) * 100
	}
	return &diskInfo{
		TotalBytes:     total,
		AvailableBytes: avail,
		UsedBytes:      used,
		UsagePercent:   usagePct,
	}, nil
}
