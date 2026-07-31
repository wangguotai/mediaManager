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
	"archive/zip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
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
	dataDir    string            // 数据根目录（绝对路径）；RN bundle / promotions 端点据此定位文件
	startTime  time.Time
	authSvc    *auth.AuthService // 为 nil 时认证中间件放行所有请求（仅开发/测试用）
	store      *storage.Store    // 元数据库；为 nil 时 sync/device/usage/dedup 端点返回 503

	// metrics 是进程级可观测指标收集器（请求计数/延迟/上传字节/sync 拉取量等），
	// 供 /metrics 端点与 accessLog 中间件写入。NewServer 中初始化。
	metrics *metricsRegistry
	// slogLevel 控制结构化日志级别（默认 Info）。NewServer 初始化 initLogger 用。
	slogLevel slog.Level

	// healthz 降频缓存：/healthz 每次请求都跑 countAllUserMedia 全量目录扫描会造成
	// IO 放大，且该端点无认证可被外部刷。缓存统计结果（30s TTL），过期才重扫。
	mediaCountMu        sync.Mutex
	mediaCountCache     int
	mediaCountCachedAt  time.Time

	// loginLimiter 是 /api/auth/login 的 IP+username 滑动窗口限速器（PRD §2.7）。
	// 防暴力撞库：每 (ip,username) 每分钟最多 loginRateMax 次，超限返回 429。
	// 为 nil 时（未配置认证的纯测试 server）跳过限速，保持既有测试兼容。
	loginLimiter *loginRateLimiter
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
		// 可观测性：初始化结构化日志（slog，JSON handler）与进程级指标收集器。
		// slogLevel 默认 Info；测试/NewServer 可覆盖。metrics 始终非空，
		// 保证 accessLog 与 /metrics 在任何部署形态下都能正常工作。
		slogLevel: slog.LevelInfo,
		metrics:   newMetricsRegistry(),
	}
	initLogger(s.slogLevel)
	// 登录暴力限速器：authSvc 配置时启用，后台 goroutine 周期清理过期条目。
	// authSvc 为 nil（纯测试/无认证开发场景）时不创建，handleAuthLogin 据此跳过。
	if authSvc != nil {
		s.loginLimiter = NewLoginRateLimiter()
	}
	s.registerRoutes()
	return s
}

// SetCloudDir 注入网盘图片源根目录，启用 /api/media/stream 对网盘原图的回退查找。
func (s *Server) SetCloudDir(dir string) { s.cloudDir = dir }

// SetDataDir 注入数据根目录（绝对路径），启用 RN bundle 端点（/api/rn/*）与
// 运营活动端点（/api/promotions）。应传入 main.go 中 cfg.ResolveDataDir() 解析后的绝对路径。
func (s *Server) SetDataDir(dir string) { s.dataDir = dir }

// SetStore 注入元数据库，启用多设备同步相关端点：/api/sync/changes、
// /api/sync/usage、/api/device/register、/api/device/list，以及 upload 的
// (user_id,sha256) 秒传去重。未注入时这些端点返回 503。
func (s *Server) SetStore(store *storage.Store) { s.store = store }

func (s *Server) registerRoutes() {
	// Auth: 登录/注册本身无需认证（中间件按路径前缀豁免）；改密端点需认证（带 token）。
	s.mux.HandleFunc("/api/auth/login", s.handleAuthLogin)
	s.mux.HandleFunc("/api/auth/register", s.handleAuthRegister)
	s.mux.HandleFunc("/api/auth/change-password", s.handleAuthChangePassword)

	// OpenClaw bridge
	s.mux.HandleFunc("/api/openclaw/command", s.handleOpenClawCommand)

	// Media REST endpoints (proxy to gRPC service)
	s.mux.HandleFunc("/api/media/list", s.handleMediaList)
	// V7：按类型分组的存储统计端点
	s.mux.HandleFunc("/api/media/storage-stats", s.handleMediaStorageStats)
	// V7：重复文件检测端点（基于 SHA256 分组）
	s.mux.HandleFunc("/api/media/duplicates", s.handleMediaDuplicates)
	// V7：媒体库综合摘要端点
	s.mux.HandleFunc("/api/media/summary", s.handleMediaSummary)
	// V7：按月份分组的时间轴端点
	s.mux.HandleFunc("/api/media/timeline", s.handleMediaTimeline)
	// V7：按类型+月份分组的存储统计
	s.mux.HandleFunc("/api/media/storage-breakdown", s.handleMediaStorageBreakdown)
	// V7：搜索建议（基于文件名前缀）
	s.mux.HandleFunc("/api/media/search-suggestions", s.handleMediaSearchSuggestions)
	// V7：最近活动（合并最近上传/收藏/分享）
	s.mux.HandleFunc("/api/media/recent-activity", s.handleMediaRecentActivity)
	// V7：存储增长趋势（按月份累计）
	s.mux.HandleFunc("/api/media/storage-trend", s.handleMediaStorageTrend)
	// V7：重命名媒体文件
	s.mux.HandleFunc("/api/media/rename", s.handleMediaRename)
	// V8：批量重命名
	s.mux.HandleFunc("/api/media/batch-rename", s.handleMediaBatchRename)
	// V8：单个媒体详情
	s.mux.HandleFunc("/api/media/info/", s.handleMediaInfo)
	// V7：批量下载（zip）
	s.mux.HandleFunc("/api/media/batch-download", s.handleMediaBatchDownload)
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
	// V7：批量添加媒体到相册
	s.mux.HandleFunc("/api/media/album/batch-add", s.handleAlbumBatchAdd)
	// V7：批量从相册移除媒体
	s.mux.HandleFunc("/api/media/album/batch-remove", s.handleAlbumBatchRemove)
	s.mux.HandleFunc("/api/media/album/remove", s.handleAlbumRemove)
	// V7：设置相册封面
	s.mux.HandleFunc("/api/media/album/cover", s.handleAlbumCover)
	// V8：取消相册共享
	s.mux.HandleFunc("/api/media/album/unshare", s.handleAlbumUnshare)
	// V8：列出相册共享给了哪些用户
	s.mux.HandleFunc("/api/media/album/shared-with", s.handleAlbumSharedWith)
	// V8：相册内媒体完整 metadata
	s.mux.HandleFunc("/api/media/album/media-list", s.handleAlbumMediaList)
	// V8：重命名相册
	s.mux.HandleFunc("/api/media/album/rename", s.handleAlbumRename)
	s.mux.HandleFunc("/api/media/album/", s.handleAlbumResource)

	// 共享相册（PRD-v7 §2.3）：邀请 / 撤销 / 列出被共享的相册。
	//   - POST   /api/media/album/share  ：邀请用户共享相册（body: album_id + username/user_id）。
	//   - DELETE /api/media/album/share  ：撤销共享（body: album_id + username/user_id）。
	//   - GET    /api/media/albums/shared：列出被共享给当前用户的相册。
	// 路由优先级：ServeMux 按"最长匹配优先"，/api/media/album/share 精确匹配优先于
	// /api/media/album/ 前缀匹配，故 POST share 不会被 handleAlbumResource 误捕获。
	// 同理 /api/media/albums/shared 精确匹配优先于（未注册的）/api/media/albums/ 前缀。
	// 这些端点位于 authMiddleware 保护下，需 Bearer token。
	s.mux.HandleFunc("/api/media/album/share", s.handleAlbumShare)
	s.mux.HandleFunc("/api/media/albums/shared", s.handleAlbumsShared)

	// 视频信息：用 ffprobe 返回时长/分辨率，供前端展示与播放器初始化。
	s.mux.HandleFunc("/api/media/video-info/", s.handleMediaVideoInfo)

	// 回收站（PRD-v7 §1.1）：列表 / 恢复 / 彻底删除。全部需认证，user_id 由
	// authMiddleware 注入 context，handler 用 userIDFromContext 取回并按用户隔离数据。
	//   - GET  /api/media/trash   ：列出当前用户已软删（deleted=1）的媒体，分页。
	//   - POST /api/media/restore ：批量复活（deleted=0），按 user_id 校验防越权。
	//   - POST /api/media/purge   ：批量物理删除（DELETE row + 删磁盘文件），仅对
	//     deleted=1 的记录操作（只能从回收站彻底清空）。
	s.mux.HandleFunc("/api/media/trash", s.handleTrashList)
	s.mux.HandleFunc("/api/media/restore", s.handleMediaRestore)
	s.mux.HandleFunc("/api/media/purge", s.handleMediaPurge)
	// V8：清空回收站（物理删除当前用户的所有已软删媒体）
	s.mux.HandleFunc("/api/media/empty-trash", s.handleMediaEmptyTrash)
	// V8：媒体标签系统
	s.mux.HandleFunc("/api/media/tag/add", s.handleMediaTagAdd)
	s.mux.HandleFunc("/api/media/tag/remove", s.handleMediaTagRemove)
	s.mux.HandleFunc("/api/media/tag/list", s.handleMediaTagList)
	s.mux.HandleFunc("/api/media/tag/all", s.handleMediaTagAll)
	// V8：按标签搜索媒体
	s.mux.HandleFunc("/api/media/tag/search", s.handleMediaTagSearch)
	// V8：批量打标签
	s.mux.HandleFunc("/api/media/tag/batch-add", s.handleMediaTagBatchAdd)
	// V8：批量移除标签
	s.mux.HandleFunc("/api/media/tag/batch-remove", s.handleMediaTagBatchRemove)
	// V8：标签统计（每个标签关联的媒体数量）
	s.mux.HandleFunc("/api/media/tag/stats", s.handleMediaTagStats)
	// V8：重命名标签
	s.mux.HandleFunc("/api/media/tag/rename", s.handleMediaTagRename)
	// V8：删除标签
	s.mux.HandleFunc("/api/media/tag/delete", s.handleMediaTagDelete)
	// V8：标签自动补全
	s.mux.HandleFunc("/api/media/tag/autocomplete", s.handleMediaTagAutocomplete)
	// V8：合并标签
	s.mux.HandleFunc("/api/media/tag/merge", s.handleMediaTagMerge)
	// V8：用户存储配额
	s.mux.HandleFunc("/api/media/user-quota", s.handleUserQuota)
	// V8：最近上传的媒体
	s.mux.HandleFunc("/api/media/recent-uploads", s.handleMediaRecentUploads)
	// V8：极端媒体（最老+最大）
	s.mux.HandleFunc("/api/media/extreme-media", s.handleMediaExtremeMedia)
	// V8：按 MIME 类型统计
	s.mux.HandleFunc("/api/media/file-types", s.handleMediaFileTypes)
	// V8：孤立文件检查（DB 有记录但磁盘文件缺失）
	s.mux.HandleFunc("/api/media/orphan-check", s.handleMediaOrphanCheck)
	// V8：按天统计上传量（日历热力图）
	s.mux.HandleFunc("/api/media/upload-calendar", s.handleMediaUploadCalendar)
	// V8：磁盘使用情况
	s.mux.HandleFunc("/api/media/disk-usage", s.handleDiskUsage)
	// V8：按分辨率统计
	s.mux.HandleFunc("/api/media/by-resolution", s.handleMediaByResolution)

	// 多设备同步：增量 changes（含墓碑）、用户存储用量。
	s.mux.HandleFunc("/api/sync/changes", s.handleSyncChanges)
	s.mux.HandleFunc("/api/sync/usage", s.handleSyncUsage)

	// 设备注册与列表：记录接入的客户端，供多端同步审计。
	s.mux.HandleFunc("/api/device/register", s.handleDeviceRegister)
	s.mux.HandleFunc("/api/device/list", s.handleDeviceList)

	// 分享链接（PRD-v7 §1.2）：
	//   - POST /api/share/create ：需认证（在 handleShareCreate 内手动校验 JWT，
	//     因 authMiddleware 按 /api/share/ 前缀整体豁免以放行公开的 GET）。
	//   - /api/share/{token}         ：GET 公开查看、DELETE 需认证撤销（handler 内分流+校验）。
	//   - /api/share/{token}/stream/{mediaId} ：GET 公开下载字节流。
	// 路由用 /api/share/ 前缀匹配后缀段，在 handleShareAccess 内按 method+path 分流。
	s.mux.HandleFunc("/api/share/create", s.handleShareCreate)
	// V7：列出当前用户的分享链接
	s.mux.HandleFunc("/api/share/list", s.handleShareList)
	s.mux.HandleFunc("/api/share/", s.handleShareAccess)

	// Stats: 缩略图缓存命中率等可观测性指标（JSON，兼容旧前端，保留）。
	s.mux.HandleFunc("/api/stats", s.handleStats)

	// React Native 动态 bundle 下发（PRD §3.2）：列出可用 bundle 与版本、下载 JS 文件。
	// bundle 名取路径尾段，/api/rn/bundle/ 前缀匹配使 {name} 可任意。
	s.mux.HandleFunc("/api/rn/manifest", s.handleRNManifest)
	s.mux.HandleFunc("/api/rn/bundle/", s.handleRNBundle)

	// 运营活动（PRD §3.3）：返回 promotions 列表，供客户端首页 banner/弹窗展示。
	s.mux.HandleFunc("/api/promotions", s.handlePromotions)

	// Metrics: Prometheus 文本格式指标暴露（PRD §2.6）。无认证（authMiddleware 豁免，
	// 与 /healthz 一致），供 Prometheus 抓取。与 /api/stats 并存——前者面向采集系统，
	// 后者面向人眼/前端。Status 类指标、计数器、延迟桶见 metrics.go。
	s.mux.HandleFunc("/metrics", s.handleMetrics)

	// Health
	s.mux.HandleFunc("/healthz", s.handleHealthz)
}

// OpenClawBaseURL exposes the configured upstream URL for log/startup lines.
func (s *Server) OpenClawBaseURL() string { return s.openClaw.BaseURL }

// ListenAndServe blocks.
func (s *Server) ListenAndServe() error {
	// V7：启动回收站自动清理 goroutine（每 6 小时清理 >30 天的回收站项目）
	if s.store != nil {
		go s.startTrashPurger(30*24*time.Hour, 6*time.Hour)
	}
	// 中间件链：CORS → requestID → auth → accessLog → mux（PRD §2.6 可观测性）。
	//   - corsMiddleware：跨域头 + OPTIONS 短路（最外层，确保预检不被内层 401 拦截）。
	//   - requestIDMiddleware：每请求生成/透传 X-Request-ID，注入 context 供日志关联。
	//   - authMiddleware：JWT 校验 + user_id 注入（/api/auth/login、/register、/healthz、
	//     /metrics 豁免）。校验通过后用 r.WithContext(ctx) 造新 Request 传给内层。
	//   - accessLogMiddleware：slog 结构化访问日志（method/path/status/latency/脱敏 user）+
	//     更新 metrics 计数/延迟。置于 auth 之内（auth 是其外层），auth 注入 user_id 后
	//     传给本中间件的 r 已带新 context，next 返回后读 r.Context() 即可拿到 user_id。
	//     豁免/未认证请求 auth 不注入 user_id，此处读到空串记为 anon，符合预期。
	return http.ListenAndServe(s.addr,
		s.corsMiddleware(
			s.requestIDMiddleware(
				s.authMiddleware(
					s.accessLogMiddleware(s.mux)))))
}

// startTrashPurger V7：后台定期清理回收站 + 过期分享链接。
// maxAge: 回收站最大保留时长；interval: 清理周期。
func (s *Server) startTrashPurger(maxAge, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	// 启动时先清理一次
	s.runPurge(maxAge)
	for range ticker.C {
		s.runPurge(maxAge)
	}
}

// runPurge 执行一轮清理（回收站 + 过期分享链接）。
func (s *Server) runPurge(maxAge time.Duration) {
	if n, err := s.store.PurgeExpiredTrash(context.Background(), maxAge); err != nil {
		slog.Error("trash purge failed", "error", err)
	} else if n > 0 {
		slog.Info("trash purged", "count", n)
	}
	if n, err := s.store.DeleteExpiredShareTokens(context.Background()); err != nil {
		slog.Error("share token purge failed", "error", err)
	} else if n > 0 {
		slog.Info("expired share tokens purged", "count", n)
	}
}

// userIDFromContext 取出中间件注入的 user_id；未认证请求返回空串。
// 与 service.UserIDFromContext 同源（中间件经 service.WithUserID 注入），这里仅作
// gateway 包内的便捷别名，避免每个 handler 直接跨包调用。
func userIDFromContext(ctx context.Context) string {
	return service.UserIDFromContext(ctx)
}

// authMiddleware 解析 Bearer token，校验后将 user_id 注入请求 context。
// 豁免路径：/api/auth/login、/api/auth/register（获取 token 本身）、/healthz（健康检查）、
// /metrics（Prometheus 抓取，PRD §2.6）。其余路径（含 /api/auth/change-password）无有效
// token 返回 401。s.authSvc 为 nil 时（未配置认证）直接放行，便于无认证的开发/测试场景。
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
		// 豁免登录/注册、健康检查与 metrics 抓取：这些端点本身不涉用户数据或用于探活/采集。
		// 注意：仅豁免 login 与 register 两条；change-password 必须带 token（走认证），
		// 故不再用宽泛的 /api/auth/ 前缀豁免，避免新增的改密端点被误放行。
		// /metrics 与 /healthz 同为无认证端点，供 Prometheus 与健康探针抓取。
		switch r.URL.Path {
		case "/api/auth/login", "/api/auth/register", "/healthz", "/metrics":
			next.ServeHTTP(w, r)
			return
		}
		// 分享链接（PRD-v7 §1.2）：/api/share/ 前缀整体豁免——GET 查看/流式为公开访问，
		// 不要求认证。POST /api/share/create 与 DELETE /api/share/{token} 虽需认证，
		// 但因 ServeMux 按 /api/share/ 前缀统一分流到 handleShareAccess/handleShareCreate，
		// 中间件无法按方法区分，故整体豁免后由各 handler 内部手动校验 JWT
		// （requireShareAuth 辅助解析 Authorization → user_id）。这样公开端点无需 token，
		// 需认证端点在 handler 入口显式鉴权，二者解耦。
		if strings.HasPrefix(r.URL.Path, "/api/share/") {
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

// corsMiddleware 收紧 CORS：只对 localhost（任意端口）与内网私网网段的 Origin 放行，
// 回显该具体 Origin（不再用 "*")。不匹配的来源不写 Access-Control-Allow-Origin，
// 使浏览器拒绝跨域读响应——避免此前 "*" 暴露 API 给任意站点。
//
// 判定依据 Origin 而非 Referer：Origin 在所有跨域请求（含预检）中由浏览器设定，
// 是 CORS 放行的权威来源。私网网段覆盖 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16。
// Preflight OPTIONS 短路 204，但同样只对受信 Origin 写入允许头。
func (s *Server) corsMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if origin != "" && isAllowedOrigin(origin) {
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
			// 受信 Origin 才允许携带凭据（如未来需要 cookie Authorization）。
			w.Header().Set("Access-Control-Allow-Credentials", "true")
		}
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// isAllowedOrigin 判断 Origin 是否为允许的本地/内网来源。
// 仅接受 http(s) + localhost（任意端口）+ 私网 IP 网段；其余一律拒绝。
func isAllowedOrigin(origin string) bool {
	u, err := url.Parse(origin)
	if err != nil || u.Host == "" {
		return false
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return false
	}
	host := u.Hostname()
	// localhost / 127.0.0.1 / ::1 全部放行（端口已在 u.Host 中，这里只看主机名）。
	if host == "localhost" || host == "127.0.0.1" || host == "::1" {
		return true
	}
	return isPrivateIPv4(host)
}

// isPrivateIPv4 判断 host（可能含端口）是否为 RFC1918 私网地址。
// 解析失败（含域名、IPv6）一律返回 false——本服务仅对私网/本机放行跨域。
func isPrivateIPv4(host string) bool {
	// 去掉端口（IPv4 形态 host 中若有冒号必为端口分隔）。
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	ip := net.ParseIP(host)
	if ip == nil || ip.To4() == nil {
		return false
	}
	// 10.0.0.0/8、172.16.0.0/12、192.168.0.0/16 三段私网。
	for _, cidr := range []string{"10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"} {
		if _, network, err := net.ParseCIDR(cidr); err == nil && network.Contains(ip) {
			return true
		}
	}
	return false
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
	// V7 §1.3：排序参数（date=按时间降序默认，size=按大小降序，name=按文件名升序）。
	sortBy := r.URL.Query().Get("sort")

	// V7：文件大小范围筛选（min_size / max_size，单位字节）。
	// 在 gateway 层后处理过滤，避免改 proto + service 层。
	var minSize, maxSize int64
	if v := r.URL.Query().Get("min_size"); v != "" {
		if n, _ := parseIntSafe(v); n > 0 {
			minSize = int64(n)
		}
	}
	if v := r.URL.Query().Get("max_size"); v != "" {
		if n, _ := parseIntSafe(v); n > 0 {
			maxSize = int64(n)
		}
	}

	// V7：日期范围筛选（date_from / date_to，Unix 时间戳秒）。
	// 在 gateway 层后处理过滤，避免改 proto + service 层。
	var dateFrom, dateTo int64
	if v := r.URL.Query().Get("date_from"); v != "" {
		if n, _ := parseIntSafe(v); n > 0 {
			dateFrom = int64(n)
		}
	}
	if v := r.URL.Query().Get("date_to"); v != "" {
		if n, _ := parseIntSafe(v); n > 0 {
			dateTo = int64(n)
		}
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

	// V7：文件大小+日期范围筛选（gateway 层后处理，避免改 proto）。
	if minSize > 0 || maxSize > 0 || dateFrom > 0 || dateTo > 0 {
		filtered := make([]*gen.MediaMetadata, 0, len(resp.MediaList))
		for _, m := range resp.MediaList {
			if minSize > 0 && m.Size < minSize {
				continue
			}
			if maxSize > 0 && m.Size > maxSize {
				continue
			}
			if dateFrom > 0 && m.CreatedAt < dateFrom {
				continue
			}
			if dateTo > 0 && m.CreatedAt > dateTo {
				continue
			}
			filtered = append(filtered, m)
		}
		resp.MediaList = filtered
		resp.TotalCount = int32(len(filtered))
	}

	// V7 §1.3：在 gateway 层按 sort 参数排序，避免改 proto + 重生成。
	// GetMediaList 默认按 created_at 降序（service 层），其他排序在此后处理。
	if sortBy != "" && sortBy != "date" && len(resp.MediaList) > 1 {
		sorted := make([]*gen.MediaMetadata, len(resp.MediaList))
		copy(sorted, resp.MediaList)
		sortMediaList(sorted, sortBy)
		// 重建 resp（protobuf 不可变 slice 需替换）。
		resp.MediaList = sorted
	}

	// 如果 media service 支持收藏查询，给每条媒体补充 favorite 字段（按当前用户判定）。
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		uid := userIDFromContext(r.Context())
		writeJSON(w, http.StatusOK, enrichMediaList(resp, fav, uid))
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

// sortMediaList 按 sortBy 对 media 列表排序（原地）。
// sortBy: "date"（created_at 降序，默认）、"size"（size 降序）、"name"（filename 升序）。
func sortMediaList(list []*gen.MediaMetadata, sortBy string) {
	switch sortBy {
	case "size":
		sort.Slice(list, func(i, j int) bool {
			return list[i].Size > list[j].Size // 大文件在前
		})
	case "name":
		sort.Slice(list, func(i, j int) bool {
			return list[i].Filename < list[j].Filename // 文件名 A-Z
		})
	// "date" 或其他：保持默认 created_at 降序，不额外排序。
	}
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
		"version":        "v0.4.0",
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
	// 软删除墓碑：在 SQLite 标记 deleted=1，使 /api/sync/changes 能返回墓碑。
	// V5 安全基线——防横向越权：墓碑标记必须按 user_id 校验归属，仅当该 media 属于
	// 当前请求用户才置 deleted=1。此前 MarkDeleted(mid) 仅按 id 匹配，攻击者只要猜中
	// media_id 即可把他人媒体软删。MarkDeletedForUser 用 (id, user_id) 双键过滤，归属
	// 不符则 RowsAffected=0（视为未命中，不写墓碑）。best-effort：未命中不阻断文件删除结果。
	uid := userIDFromContext(r.Context())
	if s.store != nil && uid != "" {
		for _, mid := range req.MediaIds {
			_ = s.store.MarkDeletedForUser(r.Context(), uid, mid)
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
	// PRD §2.7 上传滥用限速：每用户最多 uploadConcurrentMax 个并发上传。用
	// buffered-channel 信号量（非阻塞 acquire）限制在途数；超限直接 429。
	// 必须在确认 uid 有效后再占槽（否则匿名请求也会消耗配额），且用 defer
	// 保证所有返回路径都释放槽，避免泄漏导致用户被永久拒上传。
	if !AcquireUploadSlot(uid) {
		writeJSON(w, http.StatusTooManyRequests, map[string]any{
			"error": "too many concurrent uploads, please retry shortly",
		})
		return
	}
	defer ReleaseUploadSlot(uid)
	filename := r.URL.Query().Get("filename")
	if filename == "" {
		filename = "upload.dat"
	}
	ext := filepath.Ext(filename)
	if ext == "" {
		ext = ".dat"
		filename = filename + ext
	}

	// 流式落盘：避免把整个上传体读入堆（旧实现用 io.ReadAll 把 100MB 整文件
	// 载入内存）。这里在 uploads 目录下建临时文件，用 io.Copy 边收边写并同步计算
	// sha256，写完即 rename 到最终 uuid 文件名。大文件只占固定大小缓冲，不再占等量堆。
	tmpFile, err := os.CreateTemp(uploadsDir, "upload-*"+ext)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "failed to create temp file"})
		return
	}
	tmpPath := tmpFile.Name()
	// 失败/早退时清理临时文件；成功路径会在 rename 后令 remove 失效（静默忽略）。
	cleanupTmp := func() { _ = os.Remove(tmpPath) }

	// 上限仍保留 100MB（raw binary body，大于 JSON 体限制）。
	limited := io.LimitReader(r.Body, 100<<20)
	hasher := sha256.New()
	written, err := io.Copy(io.MultiWriter(tmpFile, hasher), limited)
	tmpFile.Close()
	if err != nil {
		cleanupTmp()
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "failed to read body"})
		return
	}
	actualSHA := hex.EncodeToString(hasher.Sum(nil))

	// 同步扩展参数（query 传递，因 body 是 raw binary）：
	//   - sha256：内容指纹；提供时按 (user_id,sha256) 去重，命中则秒传不落盘。
	//   - client_id：客户端幂等键，原样入库供多端冲突排查（可空）。
	//   - taken_at：内容拍摄时间（ms）；0 表未知。
	sha256Param := strings.TrimSpace(r.URL.Query().Get("sha256"))
	clientID := strings.TrimSpace(r.URL.Query().Get("client_id"))
	takenAt := parseInt64Query(r.URL.Query().Get("taken_at"))

	// 服务端实测 sha256 已在流式落盘时同步算出（actualSHA）；以实际落盘字节为准，
	// 避免客户端误报导致去重错乱。若客户端未传 sha256，以实测值作为去重指纹。
	dedupSHA := sha256Param
	if dedupSHA == "" {
		dedupSHA = actualSHA
	}

	// 秒传去重：store 已配置且该用户已存在同 sha256 的内容 → 直接复用既有 media_id，
	// 不重复落盘。即便既有行已被软删，也视为"该内容已存在过"，秒传返回其 id
	// 并复活（写回 deleted=0 + 刷新 updated_at），使多端删除-重传语义一致。
	if s.store != nil && dedupSHA != "" {
		if existing, derr := s.store.GetMediaByUserAndSHA256(r.Context(), uid, dedupSHA); derr == nil && existing != nil {
			// 命中去重：丢弃刚刚落盘的临时文件，不保留重复内容。
			cleanupTmp()
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
	// 流式落盘已完成：将临时文件原子 rename 到最终 uuid 路径（同目录 rename 即原子）。
	// 同一 uploads 目录下 rename 不跨设备，O(1) 且不重写数据。
	if err := os.Rename(tmpPath, uploadPath); err != nil {
		cleanupTmp()
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// Write metadata sidecar to 该用户的 metadata 目录 {id}.json。
	mimeType := detectMimeType(filename)
	metaWarn := ""
	if err := s.writeUploadMetadata(uid, id, filename, written, mimeType); err != nil {
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
			Size:     written,
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
		"size":     written,
		"sha256":   actualSHA,
	}
	if metaWarn != "" {
		resp["metadata_warning"] = metaWarn
	}
	if storeWarn != "" {
		resp["store_warning"] = storeWarn
	}
	// 可观测性：累计本次实际上传字节数（不含秒传/去重命中，那些路径未真正落盘新内容）。
	if s.metrics != nil {
		s.metrics.RecordUploadBytes(written)
	}
	writeJSON(w, http.StatusOK, resp)
}

// reviveDeletedMedia 把已软删的 media 行复活（deleted=0 并刷新 updated_at），
// 供秒传命中软删记录时恢复内容可见性，使多端"删除-重传"语义一致。
func (s *Server) reviveDeletedMedia(ctx context.Context, mediaID string) error {
	return s.store.UndeleteMedia(ctx, mediaID)
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

// healthzMediaCountTTL 是 /healthz 的 media_count 缓存有效期。该端点无认证、
// 每次 countAllUserMedia 全量扫描所有用户 uploads 目录，IO 开销随用户数线性放大；
// 30s 内复用上次结果，把刷 healthz 的 IO 放大降为常数级。
const healthzMediaCountTTL = 30 * time.Second

// countAllUserMedia 跨所有已存在的用户 uploads 目录聚合统计媒体文件数，
// 供 /healthz 在无单一 user_id 的情形下给出全局 media_count。仅扫已落盘的
// <usersRoot>/<uid>/uploads 目录（不依赖运行期"哪些 uid 访问过"的记忆）。
// 结果按 healthzMediaCountTTL 缓存以避免每次请求全量扫描；userDirs 未注入时返回 0。
func (s *Server) countAllUserMedia() int {
	if s.userDirs == nil {
		return 0
	}
	// 缓存命中：30s 内直接返回上次扫描值，跳过全量目录 IO。
	s.mediaCountMu.Lock()
	if !s.mediaCountCachedAt.IsZero() && time.Since(s.mediaCountCachedAt) < healthzMediaCountTTL {
		cached := s.mediaCountCache
		s.mediaCountMu.Unlock()
		return cached
	}
	s.mediaCountMu.Unlock()

	total := s.scanAllUserMedia()

	// 写回缓存（即便 total==0 也缓存，避免空库被刷时反复扫盘）。
	s.mediaCountMu.Lock()
	s.mediaCountCache = total
	s.mediaCountCachedAt = time.Now()
	s.mediaCountMu.Unlock()
	return total
}

// scanAllUserMedia 执行一次真实的全量目录扫描，返回媒体文件总数。
func (s *Server) scanAllUserMedia() int {
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
	SetAlbumCover(uid, albumID, mediaID string) error
	BatchAddToAlbum(uid, albumID string, mediaIDs []string) (int, error)
	BatchRemoveFromAlbum(uid, albumID string, mediaIDs []string) (int, error)
	ListAlbums(uid string) []*service.Album
	GetAlbum(uid, albumID string) *service.Album
	DeleteAlbum(uid, albumID string) error
	RenameAlbum(uid, albumID, newName string) error
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
	// 相册归属判定（PRD-v7 §2.3）：所有者或被共享者均可向相册添加媒体。
	// resolveAlbumOwnerForUser 返回应操作的归属 uid（owner → uid；sharee → owner_uid）；
	// 无权访问返回空串 → 404（不区分不存在与无权，避免泄露）。
	opUID := uid
	if provider.GetAlbum(uid, req.AlbumID) == nil {
		opUID = s.resolveAlbumOwnerForUser(r, provider, uid, req.AlbumID)
		if opUID == "" {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
			return
		}
	}
	if err := provider.AddToAlbum(opUID, req.AlbumID, req.MediaID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "album_id": req.AlbumID, "media_id": req.MediaID})
}

// handleAlbumBatchAdd V7：POST /api/media/album/batch-add — 批量添加媒体到相册。
// 请求体: {"album_id":"x","media_ids":["a","b","c"]}
func (s *Server) handleAlbumBatchAdd(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported"})
		return
	}
	var req struct {
		AlbumID  string   `json:"album_id"`
		MediaIDs []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" || len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id and media_ids are required"})
		return
	}
	if len(req.MediaIDs) > 200 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "max 200 media per batch"})
		return
	}
	uid := userIDFromContext(r.Context())
	opUID := uid
	if provider.GetAlbum(uid, req.AlbumID) == nil {
		opUID = s.resolveAlbumOwnerForUser(r, provider, uid, req.AlbumID)
		if opUID == "" {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
			return
		}
	}
	added, err := provider.BatchAddToAlbum(opUID, req.AlbumID, req.MediaIDs)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":      "success",
		"album_id":    req.AlbumID,
		"added_count": added,
		"total":       len(req.MediaIDs),
	})
}

// handleAlbumBatchRemove V7：POST /api/media/album/batch-remove — 批量从相册移除媒体。
// 请求体: {"album_id":"x","media_ids":["a","b","c"]}
func (s *Server) handleAlbumBatchRemove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported"})
		return
	}
	var req struct {
		AlbumID  string   `json:"album_id"`
		MediaIDs []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" || len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id and media_ids are required"})
		return
	}
	uid := userIDFromContext(r.Context())
	opUID := uid
	if provider.GetAlbum(uid, req.AlbumID) == nil {
		opUID = s.resolveAlbumOwnerForUser(r, provider, uid, req.AlbumID)
		if opUID == "" {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
			return
		}
	}
	removed, err := provider.BatchRemoveFromAlbum(opUID, req.AlbumID, req.MediaIDs)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"album_id":      req.AlbumID,
		"removed_count": removed,
	})
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

// handleAlbumCover V7：POST /api/media/album/cover — 设置相册封面。
func (s *Server) handleAlbumCover(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album is not supported"})
		return
	}
	var req struct {
		AlbumID string `json:"album_id"`
		MediaID string `json:"media_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" || req.MediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id and media_id required"})
		return
	}
	uid := userIDFromContext(r.Context())
	if err := provider.SetAlbumCover(uid, req.AlbumID, req.MediaID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "album_id": req.AlbumID, "cover_media_id": req.MediaID})
}

// handleAlbumUnshare V8：POST /api/media/album/unshare — 取消相册共享。
// 请求体: { album_id, shared_with_user_id }
// 仅相册 owner 可撤销共享。
func (s *Server) handleAlbumUnshare(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	var req struct {
		AlbumID          string `json:"album_id"`
		SharedWithUserID string `json:"shared_with_user_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" || req.SharedWithUserID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id and shared_with_user_id required"})
		return
	}

	// 校验：仅 owner 可撤销共享
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album not supported"})
		return
	}
	album := provider.GetAlbum(uid, req.AlbumID)
	if album == nil {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "not album owner"})
		return
	}

	if err := s.store.DeleteAlbumShare(r.Context(), req.AlbumID, uid, req.SharedWithUserID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":             "success",
		"album_id":           req.AlbumID,
		"shared_with_user":   req.SharedWithUserID,
	})
}

// handleAlbumSharedWith V8：GET /api/media/album/shared-with?album_id=xxx
// 返回某相册共享给了哪些用户。
func (s *Server) handleAlbumSharedWith(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	albumID := r.URL.Query().Get("album_id")
	if albumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id required"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	// 校验 owner
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album not supported"})
		return
	}
	if provider.GetAlbum(uid, albumID) == nil {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "not album owner"})
		return
	}
	shares, err := s.store.ListAlbumSharesByAlbum(r.Context(), albumID, uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	items := make([]map[string]any, 0, len(shares))
	for _, s2 := range shares {
		items = append(items, map[string]any{
			"shared_with_user_id": s2.SharedWithUserID,
			"shared_at":           s2.SharedAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"album_id":    albumID,
		"shared_with": items,
		"count":       len(items),
	})
}

// handleAlbumMediaList V8：GET /api/media/album/media-list?album_id=xxx — 返回相册内媒体的完整 metadata。
func (s *Server) handleAlbumMediaList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	albumID := r.URL.Query().Get("album_id")
	if albumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id required"})
		return
	}
	// 校验 owner
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album not supported"})
		return
	}
	album := provider.GetAlbum(uid, albumID)
	if album == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
		return
	}
	// 获取每个 media 的 metadata
	items := make([]map[string]any, 0, len(album.MediaIDs))
	for _, mediaID := range album.MediaIDs {
		m, err := s.store.GetMedia(r.Context(), mediaID)
		if err != nil || m == nil || m.UserID != uid {
			continue
		}
		if m.Deleted {
			continue
		}
		items = append(items, map[string]any{
			"id":         m.ID,
			"filename":   m.Filename,
			"type":       m.Type,
			"size":       m.Size,
			"mime":       m.Mime,
			"width":      m.Width,
			"height":     m.Height,
			"created_at": m.CreatedAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"album_id": albumID,
		"items":    items,
		"count":    len(items),
	})
}

// handleAlbumRename V8：POST /api/media/album/rename — 重命名相册。
// 请求体: { album_id, name }
func (s *Server) handleAlbumRename(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album not supported"})
		return
	}
	var req struct {
		AlbumID string `json:"album_id"`
		Name    string `json:"name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" || req.Name == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id and name required"})
		return
	}
	if err := provider.RenameAlbum(uid, req.AlbumID, req.Name); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":   "success",
		"album_id": req.AlbumID,
		"name":     req.Name,
	})
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
		// 相册详情：所有者或被共享者均可查看（PRD-v7 §2.3）。
		// resolveAlbumOwnerForUser 返回应使用的归属 uid（owner 自己 → uid；
		// sharee → owner_uid）；无权访问返回空串 → 404。
		viewUID := uid
		if provider.GetAlbum(uid, albumID) == nil {
			viewUID = s.resolveAlbumOwnerForUser(r, provider, uid, albumID)
			if viewUID == "" {
				writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
				return
			}
		}
		album := provider.GetAlbum(viewUID, albumID)
		if album == nil {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
			return
		}
		writeJSON(w, http.StatusOK, album)
	case http.MethodDelete:
		// 删除相册：仅所有者可删。删除成功后级联清理该相册的所有共享关系，
		// 避免悬空的 album_shares 记录（被共享者再查列表时虽会跳过，但清理更干净）。
		if provider.GetAlbum(uid, albumID) == nil {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
			return
		}
		if err := provider.DeleteAlbum(uid, albumID); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		// 级联清理共享关系（store 为 nil 时跳过，纯文件相册无共享记录）。
		if s.store != nil {
			_ = s.store.DeleteAlbumShare(r.Context(), albumID, uid, "")
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
		"has_more":    resp.Page*resp.PageSize < resp.TotalCount,
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

// handleMediaStorageStats 处理 GET /api/media/storage-stats，
// 返回按媒体类型分组的存储统计（数量 + 总字节数）。
// gateway 层聚合，避免改 proto/service 层。
func (s *Server) handleMediaStorageStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}

	// 分页拉取当前用户全部媒体（page_size=100，循环至无更多）。
	uid := userIDFromContext(r.Context())
	typeStats := map[string]map[string]any{
		"image":      {"count": 0, "total_bytes": int64(0)},
		"video":      {"count": 0, "total_bytes": int64(0)},
		"live_photo": {"count": 0, "total_bytes": int64(0)},
	}
	var totalCount int
	var totalBytes int64

	page := int32(1)
	for {
		resp, err := s.mediaSvc.GetMediaList(r.Context(), &gen.GetMediaListRequest{
			Page:       page,
			PageSize:   100,
			FilterType: gen.MediaType_IMAGE, // 不按类型过滤，取全部
		})
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		for _, m := range resp.MediaList {
			var key string
			switch m.Type {
			case gen.MediaType_VIDEO:
				key = "video"
			case gen.MediaType_LIVE_PHOTO:
				key = "live_photo"
			default:
				key = "image"
			}
			ts := typeStats[key]
			ts["count"] = ts["count"].(int) + 1
			ts["total_bytes"] = ts["total_bytes"].(int64) + m.Size
			totalCount++
			totalBytes += m.Size
		}
		// 判断是否还有更多页
		if int32(len(resp.MediaList)) < 100 {
			break
		}
		page++
		// 安全上限：最多 100 页（10000 条）
		if page > 100 {
			break
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"by_type":     typeStats,
		"total_count":  totalCount,
		"total_bytes":  totalBytes,
		"total_mb":     float64(totalBytes) / (1024 * 1024),
		"user_id":      uid,
	})
}

// handleMediaDuplicates 处理 GET /api/media/duplicates，
// 返回按 SHA256 分组的重复媒体列表（每组包含所有重复文件）。
// 直接从 storage 层查询（proto MediaMetadata 无 SHA256 字段）。
func (s *Server) handleMediaDuplicates(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}

	// 直接从 DB 获取全部媒体（含 SHA256），避免 proto 层无 SHA256 的问题
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 按 SHA256 分组
	type metaItem struct {
		ID       string `json:"id"`
		Filename string `json:"filename"`
		Size     int64  `json:"size"`
		Sha256   string `json:"sha256"`
		Type     string `json:"type"`
		CreateAt int64  `json:"created_at"`
	}
	groups := make(map[string][]metaItem)
	for _, m := range mediaList {
		if m.SHA256 == "" || m.Deleted {
			continue
		}
		groups[m.SHA256] = append(groups[m.SHA256], metaItem{
			ID:       m.ID,
			Filename: m.Filename,
			Size:     m.Size,
			Sha256:   m.SHA256,
			Type:     m.Type,
			CreateAt: m.CreatedAt.Unix(),
		})
	}

	// 只保留 count > 1 的组
	dupes := make([]map[string]any, 0, len(groups))
	totalDupes := 0
	totalWasted := int64(0)
	for sha, items := range groups {
		if len(items) > 1 {
			totalDupes += len(items)
			totalWasted += int64(len(items)-1) * items[0].Size
			// V7：建议保留最新的一份（created_at 最大），其余建议删除
			sorted := make([]metaItem, len(items))
			copy(sorted, items)
			sort.Slice(sorted, func(i, j int) bool {
				return sorted[i].CreateAt > sorted[j].CreateAt
			})
			keepID := sorted[0].ID
			deleteIDs := make([]string, 0, len(sorted)-1)
			for _, m := range sorted[1:] {
				deleteIDs = append(deleteIDs, m.ID)
			}
			dupes = append(dupes, map[string]any{
				"sha256":     sha,
				"count":      len(items),
				"size":       items[0].Size,
				"media":      items,
				"keep_id":    keepID,
				"delete_ids": deleteIDs,
			})
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"groups":       dupes,
		"group_count":  len(dupes),
		"total_dupes":  totalDupes,
		"wasted_bytes": totalWasted,
		"wasted_mb":    float64(totalWasted) / (1024 * 1024),
		"user_id":      uid,
	})
}

// handleMediaSummary 处理 GET /api/media/summary，
// 返回用户媒体库综合摘要（总数/大小/时间范围/收藏数/相册数）。
func (s *Server) handleMediaSummary(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}

	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	var totalCount int
	var totalBytes int64
	var earliest, latest int64
	imageCount, videoCount, liveCount := 0, 0, 0
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		totalCount++
		totalBytes += m.Size
		ts := m.CreatedAt.Unix()
		if earliest == 0 || ts < earliest {
			earliest = ts
		}
		if ts > latest {
			latest = ts
		}
		switch m.Type {
		case "VIDEO":
			videoCount++
		case "LIVE_PHOTO":
			liveCount++
		default:
			imageCount++
		}
	}

	// 收藏数和相册数通过 service 层获取（store 层无对应方法）
	// 这里只返回媒体统计，收藏/相册数前端 separately 获取
	// V7：扩展统计——收藏数/相册数/分享数
	favCount := 0
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favCount = len(fav.ListFavorites(uid))
	}
	albumCount := 0
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		albumCount = len(provider.ListAlbums(uid))
	}
	shareCount := 0
	if tokens, err := s.store.ListShareTokensByUser(r.Context(), uid); err == nil {
		shareCount = len(tokens)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"total_count":  totalCount,
		"total_bytes":  totalBytes,
		"total_mb":     float64(totalBytes) / (1024 * 1024),
		"image_count":  imageCount,
		"video_count":  videoCount,
		"live_count":   liveCount,
		"favorite_count": favCount,
		"album_count":    albumCount,
		"share_count":    shareCount,
		"earliest_ts":  earliest,
		"latest_ts":    latest,
		"user_id":      uid,
	})
}

// handleMediaTimeline V7：GET /api/media/timeline — 按月份分组返回媒体列表。
// 返回格式: {"groups": [{"month": "2026-07", "count": 3, "items": [...media_view...]}]}
func (s *Server) handleMediaTimeline(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 按月份分组
	type monthGroup struct {
		Month string        `json:"month"`
		Count int           `json:"count"`
		Items []map[string]any `json:"items"`
	}
	groups := make(map[string]*monthGroup)
	var monthOrder []string

	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		month := m.CreatedAt.Format("2006-01")
		g, ok := groups[month]
		if !ok {
			g = &monthGroup{Month: month}
			groups[month] = g
			monthOrder = append(monthOrder, month)
		}
		g.Count++
		g.Items = append(g.Items, map[string]any{
			"id":         m.ID,
			"filename":   m.Filename,
			"type":       m.Type,
			"size":       m.Size,
			"created_at": m.CreatedAt.Unix(),
		})
	}

	// 按月份倒序排列
	sort.Slice(monthOrder, func(i, j int) bool {
		return monthOrder[i] > monthOrder[j]
	})
	result := make([]*monthGroup, 0, len(monthOrder))
	for _, mo := range monthOrder {
		result = append(result, groups[mo])
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"groups":      result,
		"total_months": len(result),
	})
}

// handleMediaStorageBreakdown V7：GET /api/media/storage-breakdown — 按类型+月份分组的存储统计。
// 返回格式: {"by_type": {"IMAGE":{count,bytes}, ...}, "by_month": {"2026-07":{count,bytes}, ...}, "total":{count,bytes}}
func (s *Server) handleMediaStorageBreakdown(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	type stat struct {
		Count int   `json:"count"`
		Bytes int64 `json:"bytes"`
	}
	byType := map[string]*stat{"IMAGE": {}, "VIDEO": {}, "LIVE_PHOTO": {}}
	byMonth := make(map[string]*stat)
	var totalCount int
	var totalBytes int64

	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		totalCount++
		totalBytes += m.Size

		// 按类型
		t := m.Type
		if t == "" {
			t = "IMAGE"
		}
		if _, ok := byType[t]; !ok {
			byType[t] = &stat{}
		}
		byType[t].Count++
		byType[t].Bytes += m.Size

		// 按月份
		month := m.CreatedAt.Format("2006-01")
		if _, ok := byMonth[month]; !ok {
			byMonth[month] = &stat{}
		}
		byMonth[month].Count++
		byMonth[month].Bytes += m.Size
	}

	// byMonth 转为有序列表（月份倒序）
	type monthStat struct {
		Month string `json:"month"`
		Count int    `json:"count"`
		Bytes int64  `json:"bytes"`
	}
	var months []monthStat
	for mo, st := range byMonth {
		months = append(months, monthStat{Month: mo, Count: st.Count, Bytes: st.Bytes})
	}
	sort.Slice(months, func(i, j int) bool { return months[i].Month > months[j].Month })

	writeJSON(w, http.StatusOK, map[string]any{
		"by_type":  byType,
		"by_month": months,
		"total": map[string]any{
			"count": totalCount,
			"bytes": totalBytes,
			"mb":    float64(totalBytes) / (1024 * 1024),
		},
	})
}

// handleMediaSearchSuggestions V7：GET /api/media/search-suggestions?q=xxx
// 基于当前用户的媒体文件名做前缀匹配，返回去重的建议列表（最多 10 条）。
func (s *Server) handleMediaSearchSuggestions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	q := strings.ToLower(r.URL.Query().Get("q"))
	if len(q) < 1 {
		writeJSON(w, http.StatusOK, map[string]any{"suggestions": []string{}})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	seen := make(map[string]bool)
	var suggestions []string
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		name := strings.ToLower(m.Filename)
		if strings.Contains(name, q) {
			// 去掉扩展名作为建议
			base := m.Filename
			if idx := strings.LastIndex(base, "."); idx > 0 {
				base = base[:idx]
			}
			if !seen[base] {
				seen[base] = true
				suggestions = append(suggestions, base)
				if len(suggestions) >= 10 {
					break
				}
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"suggestions": suggestions,
		"q":           q,
	})
}

// handleMediaRecentActivity V7：GET /api/media/recent-activity
// 合并最近上传/收藏/分享活动，按时间倒序返回（最多 20 条）。
func (s *Server) handleMediaRecentActivity(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}

	type activity struct {
		Type      string `json:"type"`       // upload / favorite / share
		MediaID   string `json:"media_id"`
		Filename  string `json:"filename"`
		Timestamp int64  `json:"timestamp"`
		Detail    string `json:"detail"`
	}

	var activities []activity

	// 1. 最近上传（取最近 10 个媒体）
	mediaList, _ := s.store.ListMediaByUser(r.Context(), uid)
	for i, m := range mediaList {
		if m.Deleted {
			continue
		}
		activities = append(activities, activity{
			Type:      "upload",
			MediaID:   m.ID,
			Filename:  m.Filename,
			Timestamp: m.CreatedAt.Unix(),
			Detail:    "上传了 " + m.Filename,
		})
		if i >= 9 {
			break
		}
	}

	// 2. 最近分享
	if shares, err := s.store.ListShareTokensByUser(r.Context(), uid); err == nil {
		for i, st := range shares {
			activities = append(activities, activity{
				Type:      "share",
				MediaID:   st.Token,
				Filename:  st.Token,
				Timestamp: st.CreatedAt.Unix(),
				Detail:    "创建了分享链接",
			})
			if i >= 4 {
				break
			}
		}
	}

	// 按时间倒序排序
	sort.Slice(activities, func(i, j int) bool {
		return activities[i].Timestamp > activities[j].Timestamp
	})

	// 最多 20 条
	if len(activities) > 20 {
		activities = activities[:20]
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"activities": activities,
		"total":      len(activities),
	})
}

// handleMediaStorageTrend V7：GET /api/media/storage-trend
// 按月份返回存储增长趋势（每月新增媒体数+新增字节数），月份正序。
func (s *Server) handleMediaStorageTrend(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	type monthTrend struct {
		Month     string `json:"month"`
		AddedCount int   `json:"added_count"`
		AddedBytes int64 `json:"added_bytes"`
		CumBytes   int64 `json:"cum_bytes"`
	}
	byMonth := make(map[string]*monthTrend)
	var order []string
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		month := m.CreatedAt.Format("2006-01")
		if _, ok := byMonth[month]; !ok {
			byMonth[month] = &monthTrend{Month: month}
			order = append(order, month)
		}
		byMonth[month].AddedCount++
		byMonth[month].AddedBytes += m.Size
	}
	// 月份正序 + 累计
	sort.Strings(order)
	var cum int64
	var trends []monthTrend
	for _, mo := range order {
		cum += byMonth[mo].AddedBytes
		t := *byMonth[mo]
		t.CumBytes = cum
		trends = append(trends, t)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"trends": trends,
	})
}

// handleMediaRename 处理 POST /api/media/rename，
// 修改媒体文件的 filename 字段（不改变实际存储路径）。
func (s *Server) handleMediaRename(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}

	var req struct {
		MediaID  string `json:"media_id"`
		Filename string `json:"filename"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.MediaID == "" || req.Filename == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_id and filename required"})
		return
	}

	// 获取媒体验证归属
	media, err := s.store.GetMedia(r.Context(), req.MediaID)
	if err != nil || media == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not found"})
		return
	}
	if media.UserID != uid {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "not owner"})
		return
	}

	// 更新 filename
	media.Filename = req.Filename
	if err := s.store.UpdateMedia(r.Context(), media); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"media_id": req.MediaID,
		"filename": req.Filename,
	})
}

// handleMediaBatchRename V8：POST /api/media/batch-rename
// 批量重命名，支持模板模式：{prefix}_{seq} 格式。
// 请求体: { media_ids: ["id1","id2"], pattern: "vacation_{seq}", start_seq: 1 }
func (s *Server) handleMediaBatchRename(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}

	var req struct {
		MediaIDs []string `json:"media_ids"`
		Pattern  string   `json:"pattern"`
		StartSeq int      `json:"start_seq"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.MediaIDs) == 0 || req.Pattern == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids and pattern required"})
		return
	}
	if len(req.MediaIDs) > 100 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "max 100 files per batch"})
		return
	}
	if !strings.Contains(req.Pattern, "{seq}") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "pattern must contain {seq}"})
		return
	}

	seq := req.StartSeq
	if seq <= 0 {
		seq = 1
	}
	type renameResult struct {
		ID       string `json:"media_id"`
		Filename string `json:"filename"`
	}
	succeeded := make([]renameResult, 0, len(req.MediaIDs))
	failed := make([]batchOpFailure, 0)
	for _, mediaID := range req.MediaIDs {
		media, err := s.store.GetMedia(r.Context(), mediaID)
		if err != nil || media == nil {
			failed = append(failed, batchOpFailure{ID: mediaID, Reason: "not_found"})
			continue
		}
		if media.UserID != uid {
			failed = append(failed, batchOpFailure{ID: mediaID, Reason: "not_owner"})
			continue
		}
		// 保留文件扩展名
		ext := ""
		if i := strings.LastIndex(media.Filename, "."); i >= 0 {
			ext = media.Filename[i:]
		}
		newName := strings.ReplaceAll(req.Pattern, "{seq}", fmt.Sprintf("%d", seq)) + ext
		seq++

		media.Filename = newName
		if err := s.store.UpdateMedia(r.Context(), media); err != nil {
			failed = append(failed, batchOpFailure{ID: mediaID, Reason: "error"})
			continue
		}
		succeeded = append(succeeded, renameResult{ID: mediaID, Filename: newName})
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"renamed_count": len(succeeded),
		"renamed":       succeeded,
		"failed":        failed,
	})
}

// handleMediaInfo V8：GET /api/media/info/{id} — 返回单个媒体详情。
func (s *Server) handleMediaInfo(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/info/")
	if mediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media id required"})
		return
	}
	media, err := s.store.GetMedia(r.Context(), mediaID)
	if err != nil || media == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not found"})
		return
	}
	if media.UserID != uid {
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "not owner"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"id":         media.ID,
		"filename":   media.Filename,
		"type":       media.Type,
		"size":       media.Size,
		"mime":       media.Mime,
		"width":      media.Width,
		"height":     media.Height,
		"sha256":     media.SHA256,
		"created_at": media.CreatedAt.Format(time.RFC3339),
		"updated_at": media.UpdatedAt.Format(time.RFC3339),
		"taken_at":   media.TakenAt,
		"deleted":    media.Deleted,
	})
}

// handleMediaTagAdd V8：POST /api/media/tag/add — 给媒体打标签。
func (s *Server) handleMediaTagAdd(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	var req struct {
		MediaID string `json:"media_id"`
		TagName string `json:"tag_name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.MediaID == "" || req.TagName == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_id and tag_name required"})
		return
	}
	if err := s.store.AddMediaTag(r.Context(), uid, req.MediaID, req.TagName); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "media_id": req.MediaID, "tag": req.TagName})
}

// handleMediaTagRemove V8：POST /api/media/tag/remove — 移除标签。
func (s *Server) handleMediaTagRemove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	var req struct {
		MediaID string `json:"media_id"`
		TagName string `json:"tag_name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.MediaID == "" || req.TagName == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_id and tag_name required"})
		return
	}
	if err := s.store.RemoveMediaTag(r.Context(), uid, req.MediaID, req.TagName); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success"})
}

// handleMediaTagList V8：GET /api/media/tag/list?media_id=xxx — 列出媒体的标签。
func (s *Server) handleMediaTagList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	mediaID := r.URL.Query().Get("media_id")
	if mediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_id required"})
		return
	}
	tags, err := s.store.ListMediaTags(r.Context(), uid, mediaID)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"media_id": mediaID, "tags": tags})
}

// handleMediaTagAll V8：GET /api/media/tag/all — 列出当前用户的所有标签。
func (s *Server) handleMediaTagAll(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	tags, err := s.store.ListAllTags(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"tags": tags, "count": len(tags)})
}

// handleMediaTagSearch V8：GET /api/media/tag/search?tag=xxx — 按标签搜索媒体 ID。
func (s *Server) handleMediaTagSearch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	tagName := r.URL.Query().Get("tag")
	if tagName == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "tag required"})
		return
	}
	mediaIDs, err := s.store.SearchMediaByTag(r.Context(), uid, tagName)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tag":       tagName,
		"media_ids": mediaIDs,
		"count":     len(mediaIDs),
	})
}

// handleMediaTagBatchAdd V8：POST /api/media/tag/batch-add — 批量打标签。
// 请求体: { media_ids: [...], tag_name: "xxx" }
func (s *Server) handleMediaTagBatchAdd(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	var req struct {
		MediaIDs []string `json:"media_ids"`
		TagName  string   `json:"tag_name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.MediaIDs) == 0 || req.TagName == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids and tag_name required"})
		return
	}
	if len(req.MediaIDs) > 100 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "max 100 media per batch"})
		return
	}
	count := 0
	for _, mediaID := range req.MediaIDs {
		if err := s.store.AddMediaTag(r.Context(), uid, mediaID, req.TagName); err == nil {
			count++
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tag":          req.TagName,
		"tagged_count": count,
		"total":        len(req.MediaIDs),
	})
}

// handleMediaTagBatchRemove V8：POST /api/media/tag/batch-remove — 批量移除标签。
// 请求体: { media_ids: [...], tag_name: "xxx" }
func (s *Server) handleMediaTagBatchRemove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	var req struct {
		MediaIDs []string `json:"media_ids"`
		TagName  string   `json:"tag_name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.MediaIDs) == 0 || req.TagName == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids and tag_name required"})
		return
	}
	count := 0
	for _, mediaID := range req.MediaIDs {
		if err := s.store.RemoveMediaTag(r.Context(), uid, mediaID, req.TagName); err == nil {
			count++
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tag":           req.TagName,
		"removed_count": count,
		"total":         len(req.MediaIDs),
	})
}

// handleMediaTagStats V8：GET /api/media/tag/stats — 标签统计（每个标签的媒体数）。
func (s *Server) handleMediaTagStats(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	stats, err := s.store.TagStats(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tags":  stats,
		"total": len(stats),
	})
}

// handleMediaTagRename V8：POST /api/media/tag/rename — 重命名标签。
// 请求体: { old_name, new_name }
func (s *Server) handleMediaTagRename(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	var req struct {
		OldName string `json:"old_name"`
		NewName string `json:"new_name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.OldName == "" || req.NewName == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "old_name and new_name required"})
		return
	}
	count, err := s.store.RenameTag(r.Context(), uid, req.OldName, req.NewName)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"old_name":      req.OldName,
		"new_name":      req.NewName,
		"renamed_count": count,
	})
}

// handleMediaTagDelete V8：POST /api/media/tag/delete — 删除标签。
// 请求体: { tag_name }
func (s *Server) handleMediaTagDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	var req struct {
		TagName string `json:"tag_name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.TagName == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "tag_name required"})
		return
	}
	count, err := s.store.DeleteTag(r.Context(), uid, req.TagName)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tag":           req.TagName,
		"deleted_count": count,
	})
}

// handleUserQuota V8：GET /api/media/user-quota — 返回用户存储配额信息。
// 默认配额 10GB（10 * 1024^3 字节）。
func (s *Server) handleUserQuota(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	const defaultQuotaBytes int64 = 10 * 1024 * 1024 * 1024 // 10 GB

	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	var usedBytes int64
	for _, m := range mediaList {
		if !m.Deleted {
			usedBytes += m.Size
		}
	}
	percent := 0.0
	if defaultQuotaBytes > 0 {
		percent = float64(usedBytes) / float64(defaultQuotaBytes) * 100
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"quota_bytes":   defaultQuotaBytes,
		"quota_gb":      float64(defaultQuotaBytes) / (1024 * 1024 * 1024),
		"used_bytes":    usedBytes,
		"used_mb":       float64(usedBytes) / (1024 * 1024),
		"free_bytes":    defaultQuotaBytes - usedBytes,
		"usage_percent": percent,
	})
}

// handleMediaRecentUploads V8：GET /api/media/recent-uploads — 最近上传的媒体。
// 返回最新 5 个媒体（完整 metadata），按 created_at 倒序。
func (s *Server) handleMediaRecentUploads(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	limit := 5
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 过滤已删除 + 按 CreatedAt 倒序
	var active []*storage.Media
	for _, m := range mediaList {
		if !m.Deleted {
			active = append(active, m)
		}
	}
	// 按 CreatedAt 倒序排序
	for i := 0; i < len(active)-1; i++ {
		for j := i + 1; j < len(active); j++ {
			if active[j].CreatedAt.After(active[i].CreatedAt) {
				active[i], active[j] = active[j], active[i]
			}
		}
	}
	if len(active) > limit {
		active = active[:limit]
	}
	items := make([]map[string]any, 0, len(active))
	for _, m := range active {
		items = append(items, map[string]any{
			"id":         m.ID,
			"filename":   m.Filename,
			"type":       m.Type,
			"size":       m.Size,
			"created_at": m.CreatedAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"items": items,
		"count": len(items),
	})
}

// handleMediaExtremeMedia V8：GET /api/media/extreme-media — 返回最老和最大的媒体。
func (s *Server) handleMediaExtremeMedia(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	var oldest, largest *storage.Media
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		if oldest == nil || m.CreatedAt.Before(oldest.CreatedAt) {
			oldest = m
		}
		if largest == nil || m.Size > largest.Size {
			largest = m
		}
	}
	mediaToMap := func(m *storage.Media) map[string]any {
		if m == nil {
			return nil
		}
		return map[string]any{
			"id":         m.ID,
			"filename":   m.Filename,
			"type":       m.Type,
			"size":       m.Size,
			"created_at": m.CreatedAt.Format(time.RFC3339),
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"oldest":  mediaToMap(oldest),
		"largest": mediaToMap(largest),
	})
}

// handleMediaFileTypes V8：GET /api/media/file-types — 按 MIME 类型统计。
func (s *Server) handleMediaFileTypes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	type mimeStat struct {
		Mime  string `json:"mime"`
		Count int    `json:"count"`
		Bytes int64  `json:"bytes"`
	}
	byMime := make(map[string]*mimeStat)
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		key := m.Mime
		if key == "" {
			key = "unknown"
		}
		if _, ok := byMime[key]; !ok {
			byMime[key] = &mimeStat{Mime: key}
		}
		byMime[key].Count++
		byMime[key].Bytes += m.Size
	}
	stats := make([]mimeStat, 0, len(byMime))
	for _, v := range byMime {
		stats = append(stats, *v)
	}
	// 按数量倒序
	for i := 0; i < len(stats)-1; i++ {
		for j := i + 1; j < len(stats); j++ {
			if stats[j].Count > stats[i].Count {
				stats[i], stats[j] = stats[j], stats[i]
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"types":  stats,
		"total":  len(stats),
	})
}

// handleMediaOrphanCheck V8：GET /api/media/orphan-check — 检查孤立文件（DB 有记录但磁盘文件缺失）。
// 最多扫描 500 个文件，返回缺失文件的 media_id 列表。
func (s *Server) handleMediaOrphanCheck(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage not configured"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	uploadsDir := s.userUploadsDir(uid)
	if uploadsDir == "" {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "uploads dir not configured"})
		return
	}
	checked := 0
	var orphans []map[string]any
	for _, m := range mediaList {
		if m.Deleted || checked >= 500 {
			continue
		}
		checked++
		pattern := filepath.Join(uploadsDir, m.ID+".*")
		files, _ := filepath.Glob(pattern)
		if len(files) == 0 {
			orphans = append(orphans, map[string]any{
				"media_id": m.ID,
				"filename": m.Filename,
			})
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"checked":      checked,
		"orphan_count": len(orphans),
		"orphans":      orphans,
	})
}

// handleMediaUploadCalendar V8：GET /api/media/upload-calendar — 按天统计上传量。
// 返回最近 30 天每天的上传文件数+总大小，按日期正序。
func (s *Server) handleMediaUploadCalendar(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	type dayStat struct {
		Date  string `json:"date"`
		Count int    `json:"count"`
		Bytes int64  `json:"bytes"`
	}
	byDay := make(map[string]*dayStat)
	cutoff := time.Now().AddDate(0, 0, -30)
	for _, m := range mediaList {
		if m.Deleted || m.CreatedAt.Before(cutoff) {
			continue
		}
		day := m.CreatedAt.Format("2006-01-02")
		if _, ok := byDay[day]; !ok {
			byDay[day] = &dayStat{Date: day}
		}
		byDay[day].Count++
		byDay[day].Bytes += m.Size
	}
	// 按日期正序
	days := make([]string, 0, len(byDay))
	for d := range byDay {
		days = append(days, d)
	}
	sort.Strings(days)
	stats := make([]dayStat, 0, len(days))
	for _, d := range days {
		stats = append(stats, *byDay[d])
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"days":  stats,
		"total": len(stats),
	})
}

// handleMediaTagAutocomplete V8：GET /api/media/tag/autocomplete?q=xxx — 标签自动补全。
func (s *Server) handleMediaTagAutocomplete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	q := r.URL.Query().Get("q")
	if q == "" {
		writeJSON(w, http.StatusOK, map[string]any{"suggestions": []string{}, "q": q})
		return
	}
	allTags, err := s.store.ListAllTags(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	qLower := strings.ToLower(q)
	var suggestions []string
	for _, t := range allTags {
		if strings.Contains(strings.ToLower(t), qLower) {
			suggestions = append(suggestions, t)
			if len(suggestions) >= 10 {
				break
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"suggestions": suggestions,
		"q":           q,
	})
}

// handleMediaTagMerge V8：POST /api/media/tag/merge — 合并标签。
// 请求体: { source_tag, target_tag }
// 把所有 source_tag 的记录改为 target_tag（INSERT OR IGNORE 处理冲突），然后删除 source_tag。
func (s *Server) handleMediaTagMerge(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	var req struct {
		SourceTag string `json:"source_tag"`
		TargetTag string `json:"target_tag"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.SourceTag == "" || req.TargetTag == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source_tag and target_tag required"})
		return
	}
	if req.SourceTag == req.TargetTag {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source and target must be different"})
		return
	}
	// 复用 RenameTag 逻辑
	count, err := s.store.RenameTag(r.Context(), uid, req.SourceTag, req.TargetTag)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"source_tag":    req.SourceTag,
		"target_tag":    req.TargetTag,
		"merged_count":  count,
	})
}

// handleDiskUsage V8：GET /api/media/disk-usage — 返回服务器磁盘使用情况。
func (s *Server) handleDiskUsage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	uploadsDir := s.userUploadsDir(uid)
	if uploadsDir == "" {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "uploads dir not configured"})
		return
	}
	var stat syscall.Statfs_t
	if err := syscall.Statfs(uploadsDir, &stat); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": fmt.Sprintf("statfs: %v", err)})
		return
	}
	totalBytes := stat.Blocks * uint64(stat.Bsize)
	freeBytes := stat.Bavail * uint64(stat.Bsize)
	usedBytes := totalBytes - freeBytes
	writeJSON(w, http.StatusOK, map[string]any{
		"total_bytes":   totalBytes,
		"total_gb":      float64(totalBytes) / (1024 * 1024 * 1024),
		"used_bytes":    usedBytes,
		"used_gb":       float64(usedBytes) / (1024 * 1024 * 1024),
		"free_bytes":    freeBytes,
		"free_gb":       float64(freeBytes) / (1024 * 1024 * 1024),
		"usage_percent": float64(usedBytes) / float64(totalBytes) * 100,
	})
}

// handleMediaByResolution V8：GET /api/media/by-resolution — 按分辨率统计。
// 分档：4K(≥3840px) / 2K(≥1920px) / 1080p(≥1280px) / 720p(≥960px) / 其他
func (s *Server) handleMediaByResolution(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	resolutions := map[string]int{
		"4K":     0,
		"2K":     0,
		"1080p":  0,
		"720p":   0,
		"其他":    0,
	}
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		maxDim := int(m.Width)
		if int(m.Height) > maxDim {
			maxDim = int(m.Height)
		}
		switch {
		case maxDim >= 3840:
			resolutions["4K"]++
		case maxDim >= 1920:
			resolutions["2K"]++
		case maxDim >= 1280:
			resolutions["1080p"]++
		case maxDim >= 960:
			resolutions["720p"]++
		default:
			resolutions["其他"]++
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"resolutions": resolutions,
		"total":       len(mediaList),
	})
}

// handleMediaBatchDownload 处理 POST /api/media/batch-download，
// 将多个媒体文件打包成 zip 流式返回。
func (s *Server) handleMediaBatchDownload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}

	var req struct {
		MediaIDs []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids required"})
		return
	}
	if len(req.MediaIDs) > 100 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "max 100 files per batch"})
		return
	}

	uploadsDir := s.userUploadsDir(uid)
	if uploadsDir == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"media-batch-%d.zip\"", time.Now().Unix()))
	zipWriter := zip.NewWriter(w)
	defer zipWriter.Close()

	for _, mediaID := range req.MediaIDs {
		if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
			continue
		}
		// 验证归属
		media, err := s.store.GetMedia(r.Context(), mediaID)
		if err != nil || media == nil || media.UserID != uid || media.Deleted {
			continue
		}
		// 定位文件
		files, err := filepath.Glob(filepath.Join(uploadsDir, mediaID+".*"))
		if err != nil || len(files) == 0 {
			if s.cloudDir != "" {
				files, err = filepath.Glob(filepath.Join(s.cloudDir, mediaID+".*"))
			}
			if err != nil || len(files) == 0 {
				continue
			}
		}
		// 读文件写入 zip
		data, err := os.ReadFile(files[0])
		if err != nil {
			continue
		}
		fw, err := zipWriter.Create(media.Filename)
		if err != nil {
			continue
		}
		fw.Write(data)
	}
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
