// Package gateway exposes a small HTTP surface alongside the gRPC server.
// Its first responsibility is the OpenClaw bridge: forwarding REST calls to
// the local OpenClaw gateway so KMP/web clients only talk to media-manager.
// It also exposes media REST endpoints that proxy to the internal gRPC service.
package gateway

import (
	"archive/zip"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
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
	"strconv"
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
	mediaCountMu       sync.Mutex
	mediaCountCache    int
	mediaCountCachedAt time.Time

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
	// V24：多源增强搜索建议（文件名+标签+相册名合并去重）
	s.mux.HandleFunc("/api/media/search-suggestions-enhanced", s.handleSearchSuggestionsEnhanced)
	// V8：多条件高级搜索（type+mime+size+date+tag 组合）
	s.mux.HandleFunc("/api/media/advanced-search", s.handleMediaAdvancedSearch)
	// V9：媒体旋转（更新 EXIF orientation / 旋转标记）
	s.mux.HandleFunc("/api/media/rotate", s.handleMediaRotate)
	// V9：批量旋转多个媒体（单条 UPDATE，返回实际旋转计数）
	s.mux.HandleFunc("/api/media/batch-rotate", s.handleMediaBatchRotate)
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
	// V9：完整 EXIF/metadata（合并 SQLite 持久化字段 + 文件实时解析的 EXIF 标签）
	s.mux.HandleFunc("/api/media/exif/", s.handleMediaExif)
	// V9：按拍摄日期（taken_at）分组的日历视图，不限时间范围。区别于 upload-calendar
	// （基于 created_at 的最近 30 天上传热力图）。供前端日历视图渲染每天照片/视频数量。
	s.mux.HandleFunc("/api/media/timeline-calendar", s.handleMediaTimelineCalendar)
	// V9：按拍摄时间段（早晨/下午/晚上/深夜）统计分布，基于 taken_at 的 UTC 小时。
	s.mux.HandleFunc("/api/media/time-distribution", s.handleMediaTimeDistribution)
	// V9：按月统计媒体数量（所有媒体 created_at 的 YYYY-MM 分布，不限时间范围）。
	s.mux.HandleFunc("/api/media/media-count-by-month", s.handleMediaCountByMonth)
	// V9：存储预测端点（基于最近 6 个月上传趋势预测 1/3/6 个月后的用量，并估算配额耗尽时间）。
	s.mux.HandleFunc("/api/media/storage-forecast", s.handleStorageForecast)
	// V9：媒体增长报告（本周/本月/本年上传统计对比+环比增长率）。
	s.mux.HandleFunc("/api/media/growth-report", s.handleMediaGrowthReport)
	// 周报摘要（最近7天上传统计+最活跃的一天+新增标签数+新增相册数）。
	s.mux.HandleFunc("/api/media/weekly-summary", s.handleWeeklySummary)
	// V8：媒体生命周期分析
	s.mux.HandleFunc("/api/media/media-lifecycle", s.handleMediaLifecycle)
	// V13：年度回顾报告（某年媒体统计摘要：总览/月分布/类型分布/收藏数/上传最忙日）。
	s.mux.HandleFunc("/api/media/yearly-review", s.handleMediaYearlyReview)
	// V21：综合报告（合并 quick-stats/yearly-review/storage/tag/pattern/duplicate 为一次请求），
	// 供前端"年度报告"风格页面一次加载全部数据，避免并发拉 6 个端点。
	s.mux.HandleFunc("/api/media/full-report", s.handleMediaFullReport)
	// V8：按天统计媒体数量热力图（一年 GitHub 风格贡献图，按 taken_at 优先、created_at 回退）。
	s.mux.HandleFunc("/api/media/media-heatmap", s.handleMediaHeatmap)
	// 按 24 小时分布统计上传习惯（created_at 的 UTC 小时，0-23 全槽位返回）。
	s.mux.HandleFunc("/api/media/media-by-hour", s.handleMediaByHour)
	// V9：一站式统计汇总（聚合多个统计端点的最常用数据，供前端"我的"Tab 一次加载）
	s.mux.HandleFunc("/api/media/stat-summary", s.handleMediaStatSummary)
	// V12：极简统计端点（首页快速加载，只返 6 个数字，区别于 stat-summary 的全量汇总）。
	s.mux.HandleFunc("/api/media/quick-stats", s.handleMediaQuickStats)
	// 媒体覆盖率报告：已标记标签/已收藏/已分享/在相册中的媒体占比，供前端展示媒体整理完成度。
	s.mux.HandleFunc("/api/media/media-coverage", s.handleMediaCoverage)
	// 用户活跃度评分（基于 upload/favorite/share/tag/rename/rotate 各维度加权打分+等级+明细）。
	// 数据来源：audit_log 操作统计（AuditLogStats）+ ListMediaByUser 取上传数 +
	// favoriteProvider.ListFavorites 取当前收藏数（audit_log 仅记录 unfavorite）。
	s.mux.HandleFunc("/api/media/user-activity-score", s.handleUserActivityScore)
	// V9：批量获取下载 URL 列表（返回每个媒体的直接下载链接，前端可"复制链接"或批量下载，
	// 区别于 batch-download 的 zip 流式打包，不创建分享链接）
	s.mux.HandleFunc("/api/media/batch-download-urls", s.handleMediaBatchDownloadUrls)
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
	// V9：批量取消收藏（单次落盘，返回实际移除条数）
	s.mux.HandleFunc("/api/media/batch-favorite-remove", s.handleMediaBatchFavoriteRemove)
	// 收藏时间线：按收藏时间（media.updated_at 倒序）展示已收藏媒体，供前端"收藏"Tab 渲染。
	s.mux.HandleFunc("/api/media/favorite-timeline", s.handleFavoriteTimeline)

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
	// 智能选封面：优先图片类型 + 最大尺寸 + 最近上传，自动挑出最佳封面并落库。
	// 区别于 auto-cover（仅取第一个 media 且只在封面为空时执行），本端点无论已有
	// 封面与否都按尺寸/新鲜度重选并覆盖。精确匹配优先于 /api/media/album/ 前缀。
	s.mux.HandleFunc("/api/media/album/cover-auto-pick", s.handleAlbumCoverAutoPick)
	// V8：取消相册共享
	s.mux.HandleFunc("/api/media/album/unshare", s.handleAlbumUnshare)
	// V8：列出相册共享给了哪些用户
	s.mux.HandleFunc("/api/media/album/shared-with", s.handleAlbumSharedWith)
	// V9：一键切换相册共享状态（已共享则取消，未共享则创建公开分享链接）。
	// 精确匹配优先于 /api/media/album/ 前缀，不会被 handleAlbumResource 误捕获。
	s.mux.HandleFunc("/api/media/album/share-toggle", s.handleAlbumShareToggle)
	// V12：批量强制设置相册封面（覆盖已有封面，区别于 auto-cover-all 仅处理空封面）。
	s.mux.HandleFunc("/api/media/album/batch-set-cover", s.handleAlbumBatchSetCover)
	// V12：列出相册带封面缩略图 URL（在 all-summary 基础上额外返回 cover_thumbnail_url，
	// 方便前端一次渲染相册网格，无需为每个相册再发一次请求取封面）。
	s.mux.HandleFunc("/api/media/album/list-with-cover", s.handleAlbumListWithCover)
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
	// V8：批量恢复回收站媒体（单条 UPDATE，区别于逐条 /api/media/restore）
	s.mux.HandleFunc("/api/media/batch-restore", s.handleMediaBatchRestore)
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
	// V21：标签详细统计（每个标签的 count + 关联文件总大小 + 最近 media 创建时间）。
	// 区别于 tag/stats 仅返 count，本端点聚合 size/时间维度，供前端标签管理页展示
	// 每个标签占用的存储量与活跃度。实现：TagStats 取标签计数，ListMediaByUser 一次拉
	// 全量 media 建索引，SearchMediaByTag 取每个标签关联的 media_id，汇总落 sum/max。
	s.mux.HandleFunc("/api/media/tag-stat-detailed", s.handleMediaTagStatDetailed)
	// 按媒体类型统计标签使用：每个标签在 IMAGE/VIDEO/LIVE_PHOTO 中的分布。
	// 区别于 tag/stats（仅 count）与 tag-stat-detailed（count+size+时间），本端点聚焦
	// 类型维度，供前端展示"图片标签 vs 视频标签"分布。
	s.mux.HandleFunc("/api/media/tag/stat-by-type", s.handleTagStatByType)
	// 标签共现分析（哪些标签经常一起出现在同一 media 上）。
	// 对每对标签统计同时拥有两者的 media 数量，只返回 co-occurrence >= 2 的标签对。
	s.mux.HandleFunc("/api/media/tag-co-occurrence", s.handleTagCoOccurrence)
	// 标签网络图数据（节点+边）：标签作为节点（count=关联媒体数），共现关系作为边
	// （weight=同时拥有两标签的 media 数）。供前端可视化标签关联图（力导向/弦图等）。
	// 与 tag-co-occurrence 互补：后者只返 co>=2 的标签对（列表视角），本端点返完整
	// 图结构（nodes+edges），所有共现 >=1 均纳入以便前端按权重过滤渲染。
	s.mux.HandleFunc("/api/media/tag-network", s.handleTagNetwork)
	// 标签层级分析：自动分析标签名中的分隔符（- / : 等）推断父子关系，
	// 如 "旅行-国内"、"旅行-国外" 的父节点为 "旅行"。无分隔符的标签作为顶层。
	// 返回 {hierarchy: [{tag, count, children: [...]}], total_roots}，供前端树形展示。
	s.mux.HandleFunc("/api/media/tag-hierarchy", s.handleTagHierarchy)
	// V8：标签云数据（标签 + count + 关联的最近缩略图 URL）
	s.mux.HandleFunc("/api/media/tag/cloud-data", s.handleMediaTagCloudData)
	// V8：重命名标签
	s.mux.HandleFunc("/api/media/tag/rename", s.handleMediaTagRename)
	// V8：批量重命名标签
	s.mux.HandleFunc("/api/media/tag/batch-rename", s.handleMediaTagBatchRename)
	// V8：批量导入标签（从外部系统迁移标签数据，INSERT OR IGNORE 幂等）
	s.mux.HandleFunc("/api/media/tag/import", s.handleMediaTagImport)
	// V8：导出用户所有标签数据（标签名 + 关联 media_id 列表）为 JSON。
	s.mux.HandleFunc("/api/media/tag/export", s.handleMediaTagExport)
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
	// 按 MIME 类型详细统计（比 file-types 更细粒度：每种 MIME 的 count/total_bytes/avg_bytes/最早+最晚上传时间）。
	s.mux.HandleFunc("/api/media/mime-type-stats", s.handleMimeTypeStats)
	// V8：孤立文件检查（DB 有记录但磁盘文件缺失）
	s.mux.HandleFunc("/api/media/orphan-check", s.handleMediaOrphanCheck)
	// V8：按天统计上传量（日历热力图）
	s.mux.HandleFunc("/api/media/upload-calendar", s.handleMediaUploadCalendar)
	// 连续上传天数统计（current/longest streak，类似 GitHub 连续贡献天数）。
	s.mux.HandleFunc("/api/media/upload-streak", s.handleUploadStreak)
	// V8：磁盘使用情况
	s.mux.HandleFunc("/api/media/disk-usage", s.handleDiskUsage)
	// V8：按分辨率统计
	s.mux.HandleFunc("/api/media/by-resolution", s.handleMediaByResolution)
	// V8：按文件大小范围统计
	s.mux.HandleFunc("/api/media/by-size-range", s.handleMediaBySizeRange)
	// 媒体年龄分布（按 created_at 到 now 的时间差分组：<1天/1-7天/7-30天/30-90天/90-365天/>365天）
	s.mux.HandleFunc("/api/media/media-age-distribution", s.handleMediaAgeDistribution)
	// 媒体归档状态（按上传年龄热/温/冷分类：热≤30天 / 温30-180天 / 冷>180天）
	s.mux.HandleFunc("/api/media/media-archive-status", s.handleMediaArchiveStatus)
	// V8：同步状态摘要
	s.mux.HandleFunc("/api/media/sync-status", s.handleMediaSyncStatus)
	// V8：所有相册摘要
	s.mux.HandleFunc("/api/media/album/all-summary", s.handleAlbumAllSummary)
	// V8：批量删除相册
	s.mux.HandleFunc("/api/media/album/delete-batch", s.handleAlbumDeleteBatch)
	// V8：复制相册
	s.mux.HandleFunc("/api/media/album/clone", s.handleAlbumClone)
	// V8：调整相册内照片顺序
	s.mux.HandleFunc("/api/media/album/reorder", s.handleAlbumReorder)
	// V8：移动照片到另一相册
	s.mux.HandleFunc("/api/media/album/move-media", s.handleAlbumMoveMedia)
	// V8：跨相册复制照片（source 不删）
	s.mux.HandleFunc("/api/media/album/copy-media", s.handleAlbumCopyMedia)
	// V8：导出相册元数据为 JSON（相册信息 + 内含媒体列表 metadata）
	s.mux.HandleFunc("/api/media/album/export", s.handleAlbumExport)
	// V9：打包整个相册为 zip 下载（GET /api/media/album/download?album_id=xxx）
	s.mux.HandleFunc("/api/media/album/download", s.handleAlbumDownload)
	// V9：批量创建分享链接——一次为多个 media 各生成一个独立分享 token。
	// 走 /api/media/ 前缀（authMiddleware 自动鉴权），handler 用 userIDFromContext 取 uid。
	s.mux.HandleFunc("/api/media/batch-share", s.handleMediaBatchShare)
	// 分享分析统计（分享总数/活跃/过期/密码保护/即将过期的占比），只读端点。
	s.mux.HandleFunc("/api/media/share-analytics", s.handleShareAnalytics)
	// V8：按文件名自动打标签
	s.mux.HandleFunc("/api/media/auto-tag", s.handleMediaAutoTag)
	// V8：审计日志——列表/统计/记录
	s.mux.HandleFunc("/api/media/audit-log/list", s.handleAuditLogList)
	s.mux.HandleFunc("/api/media/audit-log/stats", s.handleAuditLogStats)
	s.mux.HandleFunc("/api/media/audit-log/by-media", s.handleAuditLogByMedia)
	s.mux.HandleFunc("/api/media/audit-log/record", s.handleAuditLogRecord)
	// 统一活动流：合并审计日志 + 最近上传，按时间倒序返回统一时间线。
	s.mux.HandleFunc("/api/media/activity-feed", s.handleMediaActivityFeed)
	// V17：搜索历史端点——从 audit_log 中提取 action="search" 的记录。
	// 当前无 search 类埋点时，回退返回全部 audit log 作为"最近操作历史"。
	s.mux.HandleFunc("/api/media/search-history", s.handleMediaSearchHistory)
	// V19：搜索查询统计——热词频率 + 最近7天搜索趋势。
	// 从 audit_log 中提取 action="search" 记录统计；无 search 埋点时分析
	// detail 字段作为回退。无任何搜索记录时返回空列表。
	s.mux.HandleFunc("/api/media/media-query-stats", s.handleMediaQueryStats)
	// V8：合并两个相册
	s.mux.HandleFunc("/api/media/album/merge", s.handleAlbumMerge)
	// V18：批量合并多个相册到第一个（album_ids[0] 为目标，其余为源）
	s.mux.HandleFunc("/api/media/album/batch-merge", s.handleAlbumBatchMerge)
	// V8：自动设置相册封面（用第一个 media）
	s.mux.HandleFunc("/api/media/album/auto-cover", s.handleAlbumAutoCover)
	// V8：按日期排序相册内媒体
	s.mux.HandleFunc("/api/media/album/sort-by-date", s.handleAlbumSortByDate)
	// V9：相册置顶 — 置顶 / 取消置顶 / 列出置顶相册
	s.mux.HandleFunc("/api/media/album/pin", s.handleAlbumPin)
	s.mux.HandleFunc("/api/media/album/unpin", s.handleAlbumUnpin)
	s.mux.HandleFunc("/api/media/album/pinned", s.handleAlbumPinned)
	// V9：批量置顶多个相册（遍历 PinAlbum，返回实际置顶计数）。
	s.mux.HandleFunc("/api/media/album/batch-pin", s.handleAlbumBatchPin)
	// V9：批量取消置顶多个相册（遍历 UnpinAlbum，返回实际取消计数）。
	s.mux.HandleFunc("/api/media/album/batch-unpin", s.handleAlbumBatchUnpin)
	// V9：批量克隆多个相册（逐个 GetAlbum + CreateAlbum + BatchAddToAlbum，返回新相册 id 列表）。
	s.mux.HandleFunc("/api/media/album/batch-clone", s.handleAlbumBatchClone)
	// V14：相册媒体数量排行（按相册内媒体数倒序，返回哪些相册照片最多）。
	s.mux.HandleFunc("/api/media/album/count-ranking", s.handleAlbumCountRanking)
	// V19：相册统计摘要（总相册数+总照片数+平均每相册照片数+最大/最小相册）。
	s.mux.HandleFunc("/api/media/album/stats-summary", s.handleAlbumStatsSummary)
	// V15：相册活动时间线（每个相册最近一次有媒体加入/其成员最新上传时间，
	// 按该时间倒序）。与 count-ranking 互补：后者看相册"大小"，本端点看相册"活跃度"。
	s.mux.HandleFunc("/api/media/album/activity", s.handleAlbumActivity)
	// V15：存储清理建议（重复+大文件+旧文件+孤立文件分析，估算可回收空间）。
	s.mux.HandleFunc("/api/media/storage-recommendations", s.handleStorageRecommendations)
	// V25：存储健康度评分（综合重复率/孤立率/配额使用率/冷数据占比给出 0-100 分+等级+建议）。
	s.mux.HandleFunc("/api/media/storage-health", s.handleStorageHealth)
	// 仪表盘（合并存储健康度+quick_stats+upload_streak+recent_activity+tag_top3+coverage 为一次请求），
	// 供前端首页/我的Tab 一次拉取渲染全部关键卡片，区别于 stat-summary（偏数据概览）与 full-report（年度报告）。
	s.mux.HandleFunc("/api/media/dashboard", s.handleMediaDashboard)
	// V20：上传模式分析（最常上传的类型/大小范围/时段/星期，基于 created_at）。
	s.mux.HandleFunc("/api/media/upload-pattern-analysis", s.handleUploadPatternAnalysis)
	// V8：批量给所有无封面相册自动设封面（用第一个 media）
	s.mux.HandleFunc("/api/media/album/auto-cover-all", s.handleAlbumAutoCoverAll)
	// V16：批量按日期排序多个相册内含照片
	s.mux.HandleFunc("/api/media/album/batch-sort", s.handleAlbumBatchSort)
	// V8：按媒体类型批量打标签（IMAGE→照片/VIDEO→视频/LIVE_PHOTO→动态照片）
	s.mux.HandleFunc("/api/media/tag/batch-by-type", s.handleMediaTagBatchByType)
	// V22：智能标签推荐——基于现有标签和文件名模式（IMG_/VID_/Screenshot/WeChat/camera），
	// 推荐用户尚未使用的标签及命中的媒体数量，供前端"建议标签"功能展示。只读端点。
	s.mux.HandleFunc("/api/media/tag-recommendations", s.handleMediaTagRecommendations)
	// V23：一键应用所有标签推荐——复用 tag-recommendations 的推荐逻辑，对每条推荐的
	// 匹配媒体调 AddMediaTag 落库，返回已应用关联总数与每个标签的计数。
	s.mux.HandleFunc("/api/media/apply-tag-recommendations", s.handleApplyTagRecommendations)
	// V8：自动清理重复媒体
	s.mux.HandleFunc("/api/media/duplicate-cleanup", s.handleMediaDuplicateCleanup)
	// 重复文件详细报告（仅报告不删除）
	s.mux.HandleFunc("/api/media/duplicate-report", s.handleMediaDuplicateReport)
	// 重复文件组摘要（比 duplicate-report 更轻量，只返回组数+总可回收+最大组信息，
	// 不展开每组明细，适合前端卡片 / 仪表盘首屏快速展示）
	s.mux.HandleFunc("/api/media/duplicate-groups-summary", s.handleDuplicateGroupsSummary)
	// 智能洞察报告——自动分析用户媒体库并给出可操作建议（重复/存储分布/上传习惯/
	// 未标签/最大相册/存储健康度），一次请求合并多个分析维度，供前端"洞察"卡片展示。
	s.mux.HandleFunc("/api/media/insights", s.handleMediaInsights)
	// 相册智能建议——基于未分类媒体（不在任何相册中的 media）按日期/类型/标签分组，
	// 推荐可创建的相册（如"2026年7月的照片"、"视频合集"、"旅行标签"等），供前端
	// "推荐相册"功能展示并让用户一键创建。只读端点。
	s.mux.HandleFunc("/api/media/album-suggestions", s.handleAlbumSuggestions)
	// V8：清理孤立记录（磁盘文件缺失的媒体软删除）
	s.mux.HandleFunc("/api/media/cleanup-orphan", s.handleMediaCleanupOrphan)

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
	// 延长分享链接有效期（POST /api/share/extend?token=xxx，需认证）。
	// 精确匹配优先于 /api/share/ 前缀，故不会被 handleShareAccess 误捕获。
	s.mux.HandleFunc("/api/share/extend", s.handleShareExtend)
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
			_ = s.store.AddAuditLog(r.Context(), uid, "delete", mid, "soft delete")
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
	BatchRemoveFavorites(uid string, mediaIDs []string) int
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

// handleFavoriteTimeline 处理 GET /api/media/favorite-timeline，按收藏时间倒序
// 返回当前用户已收藏的媒体列表。
//
// 收藏集本身（favoriteProvider）只存 media_id，不记录收藏发生的时间戳。这里以
// media.updated_at 作为 favorited_at 的近似——收藏操作会触发该 media 的
// updated_at 刷新（详见 service 层 AddFavorite 实现），故 updated_at 倒序等价于
// "最近发生收藏的在前"，符合"收藏时间线"语义。updated_at 缺失或为 0 的条目
// 退到列表尾部。
//
// 与 /api/media/favorites 的差异：后者只返 media_id 字符串数组；本端点返完整
// 摘要（media_id + filename + type + favorited_at）并按时间倒序、带 limit 分页，
// 供前端"收藏"Tab 直接渲染列表，无需再逐条拉 /api/media/info。
//
// 查询参数:?limit=20（上限 200，<=0 回退默认 20）。
// 返回:{"favorites":[{"media_id","filename","type","favorited_at"}],"total":N}
func (s *Server) handleFavoriteTimeline(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	// 收藏能力由 mediaSvc 提供（文件系统态，不依赖 SQL store）；未配置时 501。
	fav, ok := s.mediaSvc.(favoriteProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "favorite is not supported by the configured media service"})
		return
	}
	// filename/type/updated_at 来自元数据库；store 未注入时无法渲染摘要，503。
	//（与 handleMediaRecentActivity / handleMediaRecentUploads 口径一致。）
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}

	limit := 20
	if v := strings.TrimSpace(r.URL.Query().Get("limit")); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = n
		}
	}
	if limit > 200 {
		limit = 200
	}

	favIDs := fav.ListFavorites(uid)
	if len(favIDs) == 0 {
		writeJSON(w, http.StatusOK, map[string]any{"favorites": []map[string]any{}, "total": 0})
		return
	}
	favSet := make(map[string]struct{}, len(favIDs))
	for _, id := range favIDs {
		favSet[id] = struct{}{}
	}

	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	type favItem struct {
		MediaID     string    `json:"media_id"`
		Filename    string    `json:"filename"`
		Type        string    `json:"type"`
		FavoritedAt time.Time `json:"favorited_at"`
	}
	items := make([]favItem, 0, len(favIDs))
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		if _, isFav := favSet[m.ID]; !isFav {
			continue
		}
		items = append(items, favItem{
			MediaID:     m.ID,
			Filename:    m.Filename,
			Type:        m.Type,
			FavoritedAt: m.UpdatedAt,
		})
	}
	// 按 favorited_at（=media.updated_at）倒序；零值回退到列表尾部。
	sort.SliceStable(items, func(i, j int) bool {
		return items[i].FavoritedAt.After(items[j].FavoritedAt)
	})
	total := len(items)
	if len(items) > limit {
		items = items[:limit]
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"favorites": items,
		"total":     total,
	})
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

// handleMediaBatchFavoriteRemove 处理 POST /api/media/batch-favorite-remove，
// 批量取消收藏。请求体: {"media_ids":["a","b",...]}。
//
// 与 /api/media/favorite-batch（favorite=false）的差异：
//   - favorite-batch 逐条调 RemoveFavorite，每条都加锁+落盘 IO（N 次 save）；
//   - 本端点走 favoriteProvider.BatchRemoveFavorites，单次加锁 + 单次落盘，
//     大批量取消时 IO 放大显著降低。
//
// 返回 {"status":"success","removed_count":N}。removed_count 为实际被移除的
// 条数（请求中本就在收藏集里的 id 数）；不在收藏集里的 id 幂等跳过，不计入。
// 收藏功能未配置（mediaSvc 未实现 favoriteProvider）时返回 501；store 未注入时
// 仅跳过审计日志，不影响收藏功能本身（收藏走文件系统，不依赖 SQL store）。
func (s *Server) handleMediaBatchFavoriteRemove(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	var req struct {
		MediaIds []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.MediaIds) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids must not be empty"})
		return
	}
	// 媒体 id 安全校验：与 handleMediaFavoriteBatch 一致，禁空/禁路径穿越字符。
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

	removed := fav.BatchRemoveFavorites(uid, req.MediaIds)

	// 审计日志：store 未注入时跳过（收藏本身不依赖 store）。detail 标注批量移除条数。
	if s.store != nil {
		_ = s.store.AddAuditLog(r.Context(), uid, "unfavorite", "", fmt.Sprintf("batch remove %d favorites", removed))
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"removed_count": removed,
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
	ReorderAlbumMedia(uid, albumID string, newOrder []string) error
	PinAlbum(uid, albumID string) error
	UnpinAlbum(uid, albumID string) error
	ListPinnedAlbums(uid string) ([]*service.Album, error)
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

// handleAlbumCoverAutoPick 智能选封面：POST /api/media/album/cover-auto-pick。
// 请求体: { album_id }
// 从相册所有 media 中按优先级「图片类型 > 最大尺寸(width*height) > 最近上传(created_at)」
// 挑选最佳封面并调 SetAlbumCover 落库。区别于 auto-cover（仅取第一个且只在封面为空时执行），
// 本端点无论已有封面与否都重选并覆盖。返回 {status, cover_media_id, reason}。
func (s *Server) handleAlbumCoverAutoPick(w http.ResponseWriter, r *http.Request) {
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
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album not supported"})
		return
	}
	var req struct {
		AlbumID string `json:"album_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id required"})
		return
	}
	album := provider.GetAlbum(uid, req.AlbumID)
	if album == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
		return
	}
	if len(album.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album is empty"})
		return
	}

	// 拉取每个 media 的元数据，筛选 IMAGE 类型，按 width*height DESC + created_at DESC 排序。
	type candidate struct {
		ID       string
		Pixels   int64
		CreatedA time.Time
	}
	var cands []candidate
	type fallback struct {
		ID       string
		CreatedA time.Time
	}
	var fallbacks []fallback
	for _, mid := range album.MediaIDs {
		m, err := s.store.GetMedia(r.Context(), mid)
		if err != nil || m == nil || m.Deleted {
			continue
		}
		fallbacks = append(fallbacks, fallback{ID: mid, CreatedA: m.CreatedAt})
		if m.Type == "IMAGE" {
			cands = append(cands, candidate{
				ID:       mid,
				Pixels:   int64(m.Width) * int64(m.Height),
				CreatedA: m.CreatedAt,
			})
		}
	}

	reason := ""
	coverID := ""
	if len(cands) > 0 {
		sort.Slice(cands, func(i, j int) bool {
			if cands[i].Pixels != cands[j].Pixels {
				return cands[i].Pixels > cands[j].Pixels
			}
			return cands[i].CreatedA.After(cands[j].CreatedA)
		})
		coverID = cands[0].ID
		reason = "best_image_by_size_and_recency"
	} else {
		// 相册中无图片：回退取上传时间最新的 media 作为封面。
		sort.Slice(fallbacks, func(i, j int) bool {
			return fallbacks[i].CreatedA.After(fallbacks[j].CreatedA)
		})
		if len(fallbacks) == 0 {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "no media available"})
			return
		}
		coverID = fallbacks[0].ID
		reason = "no_image_fallback_to_most_recent_media"
	}

	if err := provider.SetAlbumCover(uid, req.AlbumID, coverID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":         "success",
		"album_id":       req.AlbumID,
		"cover_media_id": coverID,
		"reason":         reason,
	})
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
		"status":           "success",
		"album_id":         req.AlbumID,
		"shared_with_user": req.SharedWithUserID,
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

// handleAlbumShareToggle V8：POST /api/media/album/share-toggle — 一键切换相册共享状态。
// 请求体: { album_id }
func (s *Server) handleAlbumShareToggle(w http.ResponseWriter, r *http.Request) {
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
		AlbumID string `json:"album_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id required"})
		return
	}
	// 检查是否已共享
	shares, err := s.store.ListAlbumSharesByAlbum(r.Context(), req.AlbumID, uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	if len(shares) > 0 {
		// 取消共享
		_ = s.store.DeleteAlbumShare(r.Context(), req.AlbumID, uid, "")
		writeJSON(w, http.StatusOK, map[string]any{"status": "success", "shared": false})
	} else {
		// 创建共享（生成 token + 7 天过期）
		token := generateShareToken()
		expiresAt := time.Now().Add(7 * 24 * time.Hour)
		st := &storage.ShareToken{
			Token:     token,
			UserID:    uid,
			ExpiresAt: expiresAt,
			CreatedAt: time.Now(),
		}
		if err := s.store.CreateShareToken(r.Context(), st); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"status":     "success",
			"shared":     true,
			"share_url":  "/api/share/" + token,
			"token":      token,
			"expires_at": expiresAt.Format(time.RFC3339),
		})
	}
	_ = s.store.AddAuditLog(r.Context(), uid, "share_toggle", "", "album "+req.AlbumID)
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

// handleAlbumExport V8：GET /api/media/album/export?album_id=xxx — 导出相册元数据为 JSON。
// 返回相册基本信息（id/name/cover_media_id/created_at）及内含媒体的完整 metadata 列表。
// 校验相册归属当前用户，仅导出未软删的媒体。
func (s *Server) handleAlbumExport(w http.ResponseWriter, r *http.Request) {
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
	// 校验相册归属当前用户
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
	// 遍历相册内媒体，取每个 media 的 metadata
	media := make([]map[string]any, 0, len(album.MediaIDs))
	for _, mediaID := range album.MediaIDs {
		m, err := s.store.GetMedia(r.Context(), mediaID)
		if err != nil || m == nil || m.UserID != uid {
			continue
		}
		if m.Deleted {
			continue
		}
		media = append(media, map[string]any{
			"id":         m.ID,
			"filename":   m.Filename,
			"type":       m.Type,
			"size":       m.Size,
			"mime":       m.Mime,
			"width":      m.Width,
			"height":     m.Height,
			"sha256":     m.SHA256,
			"created_at": m.CreatedAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"album": map[string]any{
			"id":             album.ID,
			"name":           album.Name,
			"cover_media_id": album.CoverMediaID,
			"created_at":     album.CreatedAt,
		},
		"media_count": len(media),
		"media":       media,
	})
}

// handleAlbumDownload 处理 GET /api/media/album/download?album_id=xxx，
// 将整个相册内的媒体文件打包成 zip 流式返回。
// 校验相册归属当前用户，仅打包未软删的媒体。文件定位逻辑与
// handleMediaBatchDownload 一致：优先 userUploadsDir，缺失时回退 cloudDir。
func (s *Server) handleAlbumDownload(w http.ResponseWriter, r *http.Request) {
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
	// 校验相册归属当前用户
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

	uploadsDir := s.userUploadsDir(uid)
	if uploadsDir == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	// 相册名用于 zip 文件名，去掉文件名不安全字符（路径分隔符等）。
	albumName := strings.Map(func(r rune) rune {
		if r == '/' || r == '\\' || r == ':' || r == '*' || r == '?' || r == '"' || r == '<' || r == '>' || r == '|' {
			return '_'
		}
		return r
	}, album.Name)
	if albumName == "" {
		albumName = "album"
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=\"album_%s.zip\"", albumName))
	zipWriter := zip.NewWriter(w)
	defer zipWriter.Close()

	for _, mediaID := range album.MediaIDs {
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
		"total_count": totalCount,
		"total_bytes": totalBytes,
		"total_mb":    float64(totalBytes) / (1024 * 1024),
		"user_id":     uid,
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

// handleMediaDuplicateReport 处理 GET /api/media/duplicate-report，
// 返回按 SHA256 分组的重复媒体详细报告（仅报告，不删除）。
// 相比 /api/media/duplicates：返回每组 reclaimable_bytes 与全局汇总，
// 且不给出 keep_id/delete_ids 建议（纯报告用途），便于前端展示与审计。
func (s *Server) handleMediaDuplicateReport(w http.ResponseWriter, r *http.Request) {
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

	// 按 SHA256 分组（跳过空 SHA256 与已软删的记录）
	type mediaItem struct {
		ID        string `json:"id"`
		Filename  string `json:"filename"`
		Size      int64  `json:"size"`
		CreatedAt int64  `json:"created_at"`
	}
	type dupGroup struct {
		SHA256           string      `json:"sha256"`
		Count            int         `json:"count"`
		Media            []mediaItem `json:"media"`
		ReclaimableBytes int64       `json:"reclaimable_bytes"`
	}
	groups := make(map[string][]mediaItem)
	for _, m := range mediaList {
		if m.SHA256 == "" || m.Deleted {
			continue
		}
		groups[m.SHA256] = append(groups[m.SHA256], mediaItem{
			ID:        m.ID,
			Filename:  m.Filename,
			Size:      m.Size,
			CreatedAt: m.CreatedAt.Unix(),
		})
	}

	// 只保留 count > 1 的组；同一 SHA256 内容相同，文件 size 应一致，
	// 取首份 size 作为每文件字节数：reclaimable_bytes = (count-1) * bytes_per_file
	dups := make([]dupGroup, 0, len(groups))
	var totalReclaimable int64
	totalDupCount := 0
	for sha, items := range groups {
		if len(items) < 2 {
			continue
		}
		// 组内按 created_at 降序，输出稳定（最新的在前）
		sort.Slice(items, func(i, j int) bool {
			return items[i].CreatedAt > items[j].CreatedAt
		})
		bytesPerFile := items[0].Size
		reclaimable := int64(len(items)-1) * bytesPerFile
		totalReclaimable += reclaimable
		totalDupCount += len(items)
		dups = append(dups, dupGroup{
			SHA256:           sha,
			Count:            len(items),
			Media:            items,
			ReclaimableBytes: reclaimable,
		})
	}
	// 按 SHA256 排序保证输出稳定
	sort.Slice(dups, func(i, j int) bool {
		return dups[i].SHA256 < dups[j].SHA256
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"duplicates":              dups,
		"total_groups":            len(dups),
		"total_reclaimable_bytes": totalReclaimable,
		"total_duplicate_count":   totalDupCount,
		"user_id":                 uid,
	})
}

// handleDuplicateGroupsSummary 处理 GET /api/media/duplicate-groups-summary，
// 返回重复文件组摘要（比 duplicate-report 更轻量：只返回组数、总可回收字节数、
// 最大组信息与平均组大小，不展开每组明细），适合前端卡片 / 仪表盘首屏快速展示。
//
// 响应结构：
//
//	{
//	  "total_groups": N,                      // count>1 的重复组数量
//	  "total_duplicates": N,                  // 所有重复组中的文件总数
//	  "total_reclaimable_bytes": N,           // 所有组可回收字节数之和（(count-1)*bytes_per_file）
//	  "largest_group": {                      // 按 count 最大的组（并列取 reclaimable 最大）
//	    "sha256_prefix": "abc123...",         // SHA256 前 12 字符（脱敏，避免泄露完整哈希）
//	    "count": N,
//	    "reclaimable_bytes": N
//	  },
//	  "avg_group_size": N,                    // 平均每组文件数：total_duplicates/total_groups（无组时 0）
//	  "user_id": "..."
//	}
//
// 无重复组时 largest_group 返回 null；其余数值字段为 0。
func (s *Server) handleDuplicateGroupsSummary(w http.ResponseWriter, r *http.Request) {
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

	// 直接从 DB 获取全部媒体（含 SHA256），与 duplicate-report 同源。
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 按 SHA256 分组（跳过空 SHA256 与已软删的记录），仅记录每组的 count 与 bytes_per_file。
	type groupInfo struct {
		Count        int
		BytesPerFile int64
		Reclaimable  int64
	}
	const sha256PrefixLen = 12
	groups := make(map[string]*groupInfo)
	for _, m := range mediaList {
		if m.SHA256 == "" || m.Deleted {
			continue
		}
		g, ok := groups[m.SHA256]
		if !ok {
			g = &groupInfo{BytesPerFile: m.Size}
			groups[m.SHA256] = g
		}
		g.Count++
	}

	var totalGroups int
	var totalDupCount int
	var totalReclaimable int64
	var largestSha string
	var largestCount int
	var largestReclaimable int64

	for sha, g := range groups {
		if g.Count < 2 {
			continue
		}
		// 同一 SHA256 内容相同，文件 size 应一致，取首份 size 作为每文件字节数。
		// 注意：组内首份 size 是分组时第一条记录的 size，与 duplicate-report 一致。
		g.Reclaimable = int64(g.Count-1) * g.BytesPerFile

		totalGroups++
		totalDupCount += g.Count
		totalReclaimable += g.Reclaimable

		// 最大组判定：count 优先，并列时 reclaimable 更大者胜（保证输出稳定可解释）。
		if g.Count > largestCount || (g.Count == largestCount && g.Reclaimable > largestReclaimable) {
			largestSha = sha
			largestCount = g.Count
			largestReclaimable = g.Reclaimable
		}
	}

	var largestGroup any
	if totalGroups > 0 {
		prefix := largestSha
		if len(prefix) > sha256PrefixLen {
			prefix = prefix[:sha256PrefixLen]
		}
		largestGroup = map[string]any{
			"sha256_prefix":     prefix,
			"count":             largestCount,
			"reclaimable_bytes": largestReclaimable,
		}
	} else {
		largestGroup = nil
	}

	// 平均每组文件数：无组时为 0（避免除零）。
	var avgGroupSize float64
	if totalGroups > 0 {
		avgGroupSize = float64(totalDupCount) / float64(totalGroups)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"total_groups":            totalGroups,
		"total_duplicates":        totalDupCount,
		"total_reclaimable_bytes": totalReclaimable,
		"largest_group":           largestGroup,
		"avg_group_size":          avgGroupSize,
		"user_id":                 uid,
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
		"total_count":    totalCount,
		"total_bytes":    totalBytes,
		"total_mb":       float64(totalBytes) / (1024 * 1024),
		"image_count":    imageCount,
		"video_count":    videoCount,
		"live_count":     liveCount,
		"favorite_count": favCount,
		"album_count":    albumCount,
		"share_count":    shareCount,
		"earliest_ts":    earliest,
		"latest_ts":      latest,
		"user_id":        uid,
	})
}

// handleMediaCoverage GET /api/media/media-coverage — 媒体覆盖率报告。
// 返回当前用户媒体库中四项整理维度的覆盖占比：
//   - tagged    ：至少打了一个标签的媒体
//   - favorited ：已收藏的媒体
//   - shared    ：出现在任一分享链接中的媒体
//   - in_album  ：加入了至少一个相册的媒体
//   - untagged  ：完全没有标签的媒体（tagged 的补集，便于前端展示待整理量）
//
// 覆盖率 = count/total*100，round2 保留两位小数。total=0 时各覆盖率与计数均为 0。
// 数据来源：media 来自 store.ListMediaByUser；标签来自 store.ListAllTags +
// SearchMediaByTag 汇总成带标签 media_id 集合；收藏来自 mediaSvc.favoriteProvider；
// 分享来自 store.ListShareTokensByUser（MediaIDs JSON 数组）；相册来自
// mediaSvc.albumStoreProvider.ListAlbums（Album.MediaIDs）。各项能力未配置时对应计数为 0。
func (s *Server) handleMediaCoverage(w http.ResponseWriter, r *http.Request) {
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

	// 全部未软删媒体的 id 集合（ListMediaByUser 已过滤 deleted，这里再次跳过以防御式）。
	liveIDs := make(map[string]struct{}, len(mediaList))
	total := 0
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		total++
		liveIDs[m.ID] = struct{}{}
	}

	// tagged：遍历用户所有标签，汇总关联的 media_id。
	taggedSet := make(map[string]struct{})
	if total > 0 {
		if tags, terr := s.store.ListAllTags(r.Context(), uid); terr == nil {
			for _, tag := range tags {
				if ids, serr := s.store.SearchMediaByTag(r.Context(), uid, tag); serr == nil {
					for _, id := range ids {
						taggedSet[id] = struct{}{}
					}
				}
			}
		}
	}

	// favorited：收藏 media_id 列表。
	favSet := make(map[string]struct{})
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		for _, id := range fav.ListFavorites(uid) {
			favSet[id] = struct{}{}
		}
	}

	// shared：出现在任一分享链接 MediaIDs 中的媒体。
	sharedSet := make(map[string]struct{})
	if tokens, serr := s.store.ListShareTokensByUser(r.Context(), uid); serr == nil {
		for _, t := range tokens {
			var ids []string
			if jerr := json.Unmarshal([]byte(t.MediaIDs), &ids); jerr == nil {
				for _, id := range ids {
					sharedSet[id] = struct{}{}
				}
			}
		}
	}

	// in_album：加入至少一个相册的媒体。
	albumSet := make(map[string]struct{})
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		for _, a := range provider.ListAlbums(uid) {
			for _, id := range a.MediaIDs {
				albumSet[id] = struct{}{}
			}
		}
	}

	countInLive := func(set map[string]struct{}) int {
		n := 0
		for id := range set {
			if _, ok := liveIDs[id]; ok {
				n++
			}
		}
		return n
	}

	taggedCount := countInLive(taggedSet)
	favoritedCount := countInLive(favSet)
	sharedCount := countInLive(sharedSet)
	inAlbumCount := countInLive(albumSet)
	untaggedCount := total - taggedCount

	type coverage struct {
		Count   int     `json:"count"`
		Percent float64 `json:"percent"`
	}
	pct := func(c int) float64 {
		if total == 0 {
			return 0
		}
		return round2(float64(c) / float64(total) * 100)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"total":     total,
		"tagged":    coverage{Count: taggedCount, Percent: pct(taggedCount)},
		"favorited": coverage{Count: favoritedCount, Percent: pct(favoritedCount)},
		"shared":    coverage{Count: sharedCount, Percent: pct(sharedCount)},
		"in_album":  coverage{Count: inAlbumCount, Percent: pct(inAlbumCount)},
		"untagged":  coverage{Count: untaggedCount, Percent: pct(untaggedCount)},
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
		Month string           `json:"month"`
		Count int              `json:"count"`
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
		"groups":       result,
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

// handleSearchSuggestionsEnhanced V24：GET /api/media/search-suggestions-enhanced?q=xxx
// 多源增强搜索建议：从文件名、标签、相册名三个来源各自做子串匹配，
// 合并去重后返回带来源标记的建议列表。
//
// 返回: { suggestions: [{text, source}], total }
//
//	source ∈ {"filename","tag","album"}
//	文件名建议最多 5 条、标签最多 3 条、相册名最多 3 条。
func (s *Server) handleSearchSuggestionsEnhanced(w http.ResponseWriter, r *http.Request) {
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
	q := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("q")))
	if q == "" {
		writeJSON(w, http.StatusOK, map[string]any{"suggestions": []any{}, "total": 0})
		return
	}

	type suggestion struct {
		Text   string `json:"text"`
		Source string `json:"source"`
	}
	seen := make(map[string]bool)
	var suggestions []suggestion

	// a) 文件名子串匹配（最多 5 条）
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err == nil {
		for _, m := range mediaList {
			if m.Deleted {
				continue
			}
			// 去掉扩展名作为建议，与 handleMediaSearchSuggestions 一致
			base := m.Filename
			if idx := strings.LastIndex(base, "."); idx > 0 {
				base = base[:idx]
			}
			key := strings.ToLower(base)
			if !strings.Contains(key, q) {
				continue
			}
			if seen[key] {
				continue
			}
			seen[key] = true
			suggestions = append(suggestions, suggestion{Text: base, Source: "filename"})
			if len(suggestions) >= 5 {
				break
			}
		}
	}

	// b) 标签名子串匹配（最多 3 条）
	tags, err := s.store.ListAllTags(r.Context(), uid)
	if err == nil {
		tagCount := 0
		for _, t := range tags {
			if !strings.Contains(strings.ToLower(t), q) {
				continue
			}
			key := strings.ToLower(t)
			if seen[key] {
				continue
			}
			seen[key] = true
			suggestions = append(suggestions, suggestion{Text: t, Source: "tag"})
			tagCount++
			if tagCount >= 3 {
				break
			}
		}
	}

	// c) 相册名子串匹配（最多 3 条）
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		albums := provider.ListAlbums(uid)
		albumCount := 0
		for _, a := range albums {
			if a == nil {
				continue
			}
			if !strings.Contains(strings.ToLower(a.Name), q) {
				continue
			}
			key := strings.ToLower(a.Name)
			if seen[key] {
				continue
			}
			seen[key] = true
			suggestions = append(suggestions, suggestion{Text: a.Name, Source: "album"})
			albumCount++
			if albumCount >= 3 {
				break
			}
		}
	}

	if suggestions == nil {
		suggestions = []suggestion{}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"suggestions": suggestions,
		"total":       len(suggestions),
	})
}

// handleMediaAdvancedSearch V8：GET /api/media/advanced-search
// 多条件组合搜索当前用户的媒体。所有参数可选，未给则该条件不施加。
//
//	Query 参数:
//	  type       — IMAGE / VIDEO / LIVE_PHOTO
//	  mime       — 如 image/jpeg
//	  min_size   — 字节数（整数）
//	  max_size   — 字节数（整数）
//	  date_from  — RFC3339，created_at >=
//	  date_to    — RFC3339，created_at <=
//	  tag        — 精确标签名
//	  limit      — 返回上限，默认 100，最大 500
//
//	返回: { "media": [...], "total": N }
func (s *Server) handleMediaAdvancedSearch(w http.ResponseWriter, r *http.Request) {
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
	q := r.URL.Query()
	opts := storage.AdvancedSearchOpts{
		Type:     strings.TrimSpace(q.Get("type")),
		MIMEType: strings.TrimSpace(q.Get("mime")),
		Tag:      strings.TrimSpace(q.Get("tag")),
		DateFrom: strings.TrimSpace(q.Get("date_from")),
		DateTo:   strings.TrimSpace(q.Get("date_to")),
	}
	if v := strings.TrimSpace(q.Get("min_size")); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err == nil && n > 0 {
			opts.MinSize = n
		}
	}
	if v := strings.TrimSpace(q.Get("max_size")); v != "" {
		n, err := strconv.ParseInt(v, 10, 64)
		if err == nil && n > 0 {
			opts.MaxSize = n
		}
	}
	if v := strings.TrimSpace(q.Get("limit")); v != "" {
		n, err := strconv.Atoi(v)
		if err == nil && n > 0 {
			opts.Limit = n
		}
	}
	if opts.Limit <= 0 || opts.Limit > 500 {
		opts.Limit = 100
	}

	mediaList, err := s.store.AdvancedSearchMedia(r.Context(), uid, opts)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"media": mediaList,
		"total": len(mediaList),
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
		Type      string `json:"type"` // upload / favorite / share
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
		Month      string `json:"month"`
		AddedCount int    `json:"added_count"`
		AddedBytes int64  `json:"added_bytes"`
		CumBytes   int64  `json:"cum_bytes"`
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
	_ = s.store.AddAuditLog(r.Context(), uid, "rename", req.MediaID, req.Filename)

	writeJSON(w, http.StatusOK, map[string]any{
		"media_id": req.MediaID,
		"filename": req.Filename,
	})
}

// handleMediaRotate 处理 POST /api/media/rotate，
// 更新媒体旋转标记（media.orientation 列，EXIF orientation 语义：0/90/180/270）。
// 仅持久化旋转角度，不改动底层图像文件——前端按 orientation 渲染显示旋转。
func (s *Server) handleMediaRotate(w http.ResponseWriter, r *http.Request) {
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
		Rotation int    `json:"rotation"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.MediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_id required"})
		return
	}
	// 校验旋转角度为合法值（0/90/180/270），非法直接 400。
	switch req.Rotation {
	case 0, 90, 180, 270:
	default:
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "rotation must be one of 0/90/180/270"})
		return
	}

	// SetMediaRotation 内部按 (id, user_id) 双键校验归属，防横向越权；
	// 非己有或不存在均返回 ErrNotFound（不区分，避免泄露）。
	if err := s.store.SetMediaRotation(r.Context(), uid, req.MediaID, req.Rotation); err != nil {
		if errors.Is(err, storage.ErrNotFound) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not found"})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	_ = s.store.AddAuditLog(r.Context(), uid, "rotate", req.MediaID, fmt.Sprintf("%d degrees", req.Rotation))

	writeJSON(w, http.StatusOK, map[string]any{
		"status":   "ok",
		"rotation": req.Rotation,
	})
}

// handleMediaBatchRotate 处理 POST /api/media/batch-rotate，
// 批量旋转多个媒体（更新 media.orientation 列，EXIF orientation 语义：0/90/180/270）。
//
// 与 POST /api/media/rotate 的区别：rotate 逐条更新单条媒体；本端点用单条
// UPDATE ... WHERE id IN (...) AND user_id=? 一次性旋转，仅返回总旋转计数（不区分逐条
// 结果），适合前端"全选旋转"等不关心明细的场景。
//
// 请求体: { media_ids: ["id1","id2",...], rotation: 90 }
// 校验：rotation 必须为 0/90/180/270 之一；media_ids 非空且不超过 maxBatchIDs（500）。
// 防横向越权：BatchSetMediaRotation 内部按 (id, user_id) 双键校验，非己有或不存在均
// 不计入计数（不区分，避免泄露 media_id 是否存在）。
// 审计日志记一条 "rotate"（mediaID 留空，detail 注明批量旋转角度与数量），与
// batch-restore 等批量操作一致。响应：{"status":"success","rotated_count":N}。
func (s *Server) handleMediaBatchRotate(w http.ResponseWriter, r *http.Request) {
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
		Rotation int      `json:"rotation"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids required"})
		return
	}
	if len(req.MediaIDs) > maxBatchIDs {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "too many media_ids in one request"})
		return
	}
	// 校验旋转角度为合法值（0/90/180/270），非法直接 400。
	switch req.Rotation {
	case 0, 90, 180, 270:
	default:
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "rotation must be one of 0/90/180/270"})
		return
	}

	count, err := s.store.BatchSetMediaRotation(r.Context(), uid, req.MediaIDs, req.Rotation)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 审计日志：best-effort，失败不影响旋转结果（与 handleMediaRotate / batch-restore 等一致）。
	_ = s.store.AddAuditLog(r.Context(), uid, "rotate", "", fmt.Sprintf("batch rotate %d media to %d degrees", count, req.Rotation))

	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"rotated_count": count,
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

// handleMediaExif V9：GET /api/media/exif/{id} — 返回单个媒体的完整 EXIF/metadata。
//
// 合并两个数据源：
//  1. s.store.GetMedia：SQLite 持久化字段（含 taken_at 拍摄时间、orientation 旋转角度、
//     sha256 指纹、width/height 等入库时记录的元数据）。
//  2. s.mediaSvc.GetMediaMetadata：实时从磁盘文件解析出的 EXIF 标签 map（相机型号、
//     ISO、光圈、快门速度、GPS 等原始 EXIF 条目，由 service 层 parseTIFFExif 提取）。
//
// 任一数据源失败时降级：store 命中即返回 basic info（exif 为空 map），store 未命中
// 则回退单独调 mediaSvc 取文件级 metadata。保证端点始终可用。鉴权与 handleMediaInfo
// 一致：需有效 token，且 media.UserID 必须匹配当前 user_id。
func (s *Server) handleMediaExif(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/exif/")
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}

	// 基础信息：从 SQLite 取持久化字段（含 taken_at / orientation / sha256）。
	// store 未注入或未命中时不阻断——降级为仅返回文件级 metadata（见下方回退）。
	var media *storage.Media
	if s.store != nil {
		m, err := s.store.GetMedia(r.Context(), mediaID)
		if err == nil && m != nil {
			media = m
		}
	}
	if media != nil && media.UserID != uid {
		// 越权防护：store 命中且归属不匹配直接 403，避免泄露他人媒体元数据。
		writeJSON(w, http.StatusForbidden, map[string]any{"error": "not owner"})
		return
	}

	// EXIF 标签：实时从磁盘文件解析（service 层 parseTIFFExif 提取 IFD0/ExifIFD 条目）。
	// 该调用按 user_id 隔离（service.UserIDFromContext 从 context 取 uid），故即便 store
	// 未命中也能保证只读本人 uploads 目录下的文件。
	exifData := map[string]string{}
	var fileMeta *gen.MediaMetadata
	if s.mediaSvc != nil {
		resp, err := s.mediaSvc.GetMediaMetadata(r.Context(), &gen.GetMediaMetadataRequest{MediaId: mediaID})
		if err == nil && resp != nil && resp.Metadata != nil {
			fileMeta = resp.Metadata
			if ed := resp.Metadata.GetExifData(); ed != nil {
				exifData = ed
			}
		}
	}

	// store 未命中且 mediaSvc 也未取到文件 → 404。
	if media == nil && fileMeta == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not found"})
		return
	}

	// 组装响应：优先用 store 持久化字段（更准确，含 orientation/taken_at/sha256），
	// 缺失时用文件级 metadata 补齐。
	resp := map[string]any{
		"media_id": mediaID,
		"exif":     exifData,
		"source":   "basic_info",
	}
	if len(exifData) > 0 {
		resp["source"] = "basic_info+exif"
	}
	if media != nil {
		resp["filename"] = media.Filename
		resp["type"] = media.Type
		resp["size"] = media.Size
		resp["mime"] = media.Mime
		resp["width"] = media.Width
		resp["height"] = media.Height
		resp["sha256"] = media.SHA256
		resp["taken_at"] = media.TakenAt
		resp["orientation"] = media.Orientation
		resp["deleted"] = media.Deleted
		resp["client_id"] = media.ClientID
		resp["created_at"] = media.CreatedAt.Format(time.RFC3339)
		resp["updated_at"] = media.UpdatedAt.Format(time.RFC3339)
	} else if fileMeta != nil {
		// 回退：store 未注入/未命中，仅用文件级 metadata。
		resp["filename"] = fileMeta.GetFilename()
		resp["type"] = gen.MediaType_name[int32(fileMeta.GetType())]
		resp["size"] = fileMeta.GetSize()
		resp["mime"] = fileMeta.GetMimeType()
		resp["width"] = fileMeta.GetWidth()
		resp["height"] = fileMeta.GetHeight()
		resp["sha256"] = ""
		resp["taken_at"] = int64(0)
		resp["orientation"] = 0
		resp["deleted"] = false
		resp["client_id"] = ""
		resp["created_at"] = time.Unix(fileMeta.GetCreatedAt(), 0).Format(time.RFC3339)
		resp["updated_at"] = time.Unix(fileMeta.GetUpdatedAt(), 0).Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, resp)
}

// handleMediaTimelineCalendar V9：GET /api/media/timeline-calendar — 按拍摄日期（taken_at）
// 分组统计每天的媒体数量，供前端日历视图渲染。
//
// 与 upload-calendar 区别：本端点基于 taken_at（EXIF/客户端声明的拍摄时间）而非 created_at
// （上传时间），且不限制时间范围（返回全部有拍摄时间的媒体）。taken_at 缺失（=0）的记录被排除。
//
// 返回 {days: [{date, count, type, total}], total_days: N, total_media: M}：
//   - days 按 (date,type) 分组，date 为 YYYY-MM-DD（UTC），type 为 IMAGE/VIDEO/LIVE_PHOTO 等，
//     count 为该类型当天数量，total 为当天所有类型合计。
//   - total_days：有媒体的不同日期数；total_media：有拍摄时间的媒体总数。
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaTimelineCalendar(w http.ResponseWriter, r *http.Request) {
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
	rows, err := s.store.TimelineCalendar(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 统计独立日期数与媒体总数。rows 按 (date,type) 展开，需去重计天数、求和计媒体数。
	seen := make(map[string]struct{}, len(rows))
	var totalDays, totalMedia int
	for _, row := range rows {
		d, _ := row["date"].(string)
		if _, ok := seen[d]; !ok && d != "" {
			seen[d] = struct{}{}
			totalDays++
		}
		if c, ok := row["count"].(int); ok {
			totalMedia += c
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"days":        rows,
		"total_days":  totalDays,
		"total_media": totalMedia,
	})
}

// handleMediaTimeDistribution V9：GET /api/media/time-distribution — 按拍摄时间段
// （早晨/下午/晚上/深夜）统计媒体分布，基于 taken_at 的 UTC 小时。
//
// 返回 {distribution: {"早晨":N,"下午":N,"晚上":N,"深夜":N}, total: N}。
// total 为四段合计（即有拍摄时间的未软删媒体总数）。taken_at 缺失（=0）的记录不计入任何段，
// 故 total 可能小于用户媒体总数——这是预期行为（无拍摄时间的媒体无法归类时段）。
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaTimeDistribution(w http.ResponseWriter, r *http.Request) {
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
	dist, err := s.store.TimeDistribution(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	total := dist["早晨"] + dist["下午"] + dist["晚上"] + dist["深夜"]
	writeJSON(w, http.StatusOK, map[string]any{
		"distribution": dist,
		"total":        total,
	})
}

// handleMediaCountByMonth GET /api/media/media-count-by-month — 按月统计媒体数量。
//
// 按 created_at 的 YYYY-MM 分组所有未软删媒体，返回每月 count + bytes，
// 不限制时间范围（区别于 upload-calendar 只覆盖最近 30 天上传量）。
//
// 响应结构：
//
//	{
//	  "months": [{"month":"2024-01","count":12,"bytes":3456789}, ...], // 按月份升序
//	  "total_months": N, // 命中的独立月份数
//	  "total_media":  N  // 所有月份 count 合计
//	}
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaCountByMonth(w http.ResponseWriter, r *http.Request) {
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

	// 月份桶：key=YYYY-MM → [2]int64{count, bytes}
	buckets := make(map[string][2]int64)
	var totalMedia int
	for _, m := range mediaList {
		month := m.CreatedAt.UTC().Format("2006-01")
		b := buckets[month]
		b[0]++
		b[1] += m.Size
		buckets[month] = b
		totalMedia++
	}

	months := make([]map[string]any, 0, len(buckets))
	for month, b := range buckets {
		months = append(months, map[string]any{
			"month": month,
			"count": b[0],
			"bytes": b[1],
		})
	}
	// 按月份升序排列
	sort.Slice(months, func(i, j int) bool {
		mi, _ := months[i]["month"].(string)
		mj, _ := months[j]["month"].(string)
		return mi < mj
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"months":       months,
		"total_months": len(months),
		"total_media":  totalMedia,
	})
}

// handleMediaByHour GET /api/media/media-by-hour — 按 24 小时分布统计上传习惯。
//
// 基于 created_at（上传时间）的 UTC 小时数将所有未软删媒体分入 24 个槽位（0-23），
// 返回每个小时的上传数量，供前端渲染"一天中哪些时段最爱传图"的柱状图/雷达图。
//
// 响应结构：
//
//	{
//	  "hours": [{"hour":0,"count":N},{"hour":1,"count":N}, ... {"hour":23,"count":N}],
//	  "total":  N  // 24 个槽位 count 合计（= 参与分桶的未软删媒体总数）
//	}
//
// 24 个小时槽全部返回（count=0 的也要返回），保证前端无需补齐缺失槽位。
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaByHour(w http.ResponseWriter, r *http.Request) {
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

	// 24 个小时槽位，count 全部初始化为 0。
	hours := make([]map[string]any, 24)
	for i := 0; i < 24; i++ {
		hours[i] = map[string]any{"hour": i, "count": int64(0)}
	}
	var total int64
	for _, m := range mediaList {
		h := m.CreatedAt.UTC().Hour()
		if h < 0 || h >= 24 { // 防御性：零值 time.Time 的 Hour()=0，不会越界
			continue
		}
		c, _ := hours[h]["count"].(int64)
		hours[h]["count"] = c + 1
		total++
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"hours": hours,
		"total": total,
	})
}

// handleStorageForecast V9：GET /api/media/storage-forecast — 存储用量预测。
//
// 基于最近 6 个月的上传趋势（created_at 的 YYYY-MM 分组累计 bytes），计算月均
// 增长率并预测 1/3/6 个月后的预期存储用量，同时估算当前配额何时耗尽。
//
// 响应结构：
//
//	{
//	  "current_bytes":         N,     // 当前未软删媒体总字节数
//	  "monthly_average_bytes": N,     // 最近 6 个月月均新增字节数（样本月数<2 时为 0）
//	  "growth_rate_percent":   12.34, // 月均增长率相对 current 的百分比，current=0 时为 0
//	  "forecast": [
//	    {"months_ahead": 1, "predicted_bytes": N},
//	    {"months_ahead": 3, "predicted_bytes": N},
//	    {"months_ahead": 6, "predicted_bytes": N}
//	  ],
//	  "quota_bytes":        N,           // 用户配额（默认 10GB，与 handleUserQuota 一致）
//	  "months_until_full":  N 或 null    // 按 monthly_average_bytes 估算配额耗尽月数；
//	                                    // monthly_average_bytes<=0 时为 null（无法预测），
//	                                    // current_bytes>=quota 时为 0（已满）
//	}
//
// 算法说明：
//   - 取最近 6 个有媒体的月份的 monthly bytes 增量；月均增长率 = 这 6 个月增量和 / 样本月数
//     （样本月数 >=2 才有意义，否则置 0 不预测）。
//   - 预测 = current_bytes + monthly_average_bytes * months_ahead（线性外推）。
//   - months_until_full = ceil((quota - current) / monthly_average_bytes)；当月均 <=0
//     时返回 null（无法预测），current >= quota 时返回 0（已满）。
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleStorageForecast(w http.ResponseWriter, r *http.Request) {
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

	// 1) 按 created_at 的 YYYY-MM 分桶累计 bytes（仅未软删），同时对齐计算 current_bytes。
	buckets := make(map[string]int64) // month(YYYY-MM) → bytes
	var currentBytes int64
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		currentBytes += m.Size
		month := m.CreatedAt.UTC().Format("2006-01")
		buckets[month] += m.Size
	}

	// 2) 收集月份键并升序排序（YYYY-MM 字典序 = 时间序）。
	months := make([]string, 0, len(buckets))
	for month := range buckets {
		months = append(months, month)
	}
	sort.Strings(months)

	// 3) 取最近最多 6 个月作为样本。
	const sampleMonths = 6
	recent := months
	if len(recent) > sampleMonths {
		recent = recent[len(recent)-sampleMonths:]
	}

	// 4) 月均增长率（bytes/month）：样本月 bytes 之和 / 样本月数。
	//    样本月数 < 2 时无法拟合趋势，置 0 且不预测增长。
	var sampleBytesSum int64
	for _, month := range recent {
		sampleBytesSum += buckets[month]
	}
	monthlyAverageBytes := int64(0)
	if len(recent) >= 2 {
		monthlyAverageBytes = sampleBytesSum / int64(len(recent))
	}

	// 5) 增长率百分比（相对 current_bytes），current=0 时为 0。
	growthRatePercent := 0.0
	if currentBytes > 0 {
		growthRatePercent = float64(monthlyAverageBytes) / float64(currentBytes) * 100
	}

	// 6) 预测 1/3/6 个月后的用量（线性外推）。
	forecastHops := []int{1, 3, 6}
	forecast := make([]map[string]any, 0, len(forecastHops))
	for _, n := range forecastHops {
		predicted := currentBytes + monthlyAverageBytes*int64(n)
		forecast = append(forecast, map[string]any{
			"months_ahead":    n,
			"predicted_bytes": predicted,
		})
	}

	// 7) 配额耗尽估算。默认 10GB，与 handleUserQuota 的 defaultQuotaBytes 保持一致。
	const quotaBytes int64 = 10 * 1024 * 1024 * 1024 // 10 GB
	var monthsUntilFull any                          // 默认 nil → JSON null
	if monthlyAverageBytes > 0 && currentBytes < quotaBytes {
		remaining := quotaBytes - currentBytes
		// 向上取整：剩余空间除不尽时算多一个月。
		n := remaining / monthlyAverageBytes
		if remaining%monthlyAverageBytes != 0 {
			n++
		}
		monthsUntilFull = n
	} else if currentBytes >= quotaBytes {
		// 已达或超配额 → 0 个月（已满），区别于“无限/无法预测”的 null。
		monthsUntilFull = int64(0)
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"current_bytes":         currentBytes,
		"monthly_average_bytes": monthlyAverageBytes,
		"growth_rate_percent":   growthRatePercent,
		"forecast":              forecast,
		"quota_bytes":           quotaBytes,
		"months_until_full":     monthsUntilFull,
	})
}

// handleMediaGrowthReport GET /api/media/growth-report — 媒体增长报告。
//
// 返回本周/本月/本年的上传统计（count+bytes）对比，以及周环比/月环比增长率。
// 基于 created_at（上传时间，go time.Time）在 Go 侧分桶，复用 ListMediaByUser
// （仅未软删行，created_at DESC），不新增 Store 方法。
//
// 周边界按 ISO-8601：本周 = 从本周一 00:00 UTC 到当前时刻；上周 = 上周一到本周一。
// 月边界：本月 = 本月 1 日 00:00 UTC；上月 = 上月 1 日到本月 1 日。
// 年边界：本年 = 本年 1 月 1 日 00:00 UTC。
// 环比增长率 = (本期-上期)/上期*100，上期为 0 时返回 null（避免除零）。
//
// 响应结构：
//
//	{
//	  "this_week":  {"count":N,"bytes":N},
//	  "last_week":  {"count":N,"bytes":N},
//	  "week_change_percent": 12.34 或 null,
//	  "this_month": {"count":N,"bytes":N},
//	  "last_month": {"count":N,"bytes":N},
//	  "month_change_percent": 12.34 或 null,
//	  "this_year":  {"count":N,"bytes":N}
//	}
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaGrowthReport(w http.ResponseWriter, r *http.Request) {
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

	// 用 UTC 计算时间边界，与 timeline/time-distribution 等统计端点一致。
	now := time.Now().UTC()
	year, month, day := now.Date()
	// 本周一 00:00 UTC（ISO-8601周：周一为周首）。
	weekday := int(now.Weekday())
	if weekday == 0 {
		weekday = 7 // Go 的 Sunday=0，ISO 需转为 7
	}
	thisWeekStart := time.Date(year, month, day-weekday+1, 0, 0, 0, 0, time.UTC)
	lastWeekStart := thisWeekStart.AddDate(0, 0, -7)
	thisMonthStart := time.Date(year, month, 1, 0, 0, 0, 0, time.UTC)
	lastMonthStart := thisMonthStart.AddDate(0, -1, 0)
	thisYearStart := time.Date(year, 1, 1, 0, 0, 0, 0, time.UTC)

	var twCount, twBytes, lwCount, lwBytes, tmCount, tmBytes, lmCount, lmBytes, tyCount, tyBytes int64
	for _, m := range mediaList {
		ca := m.CreatedAt.UTC()
		size := m.Size
		if !ca.Before(thisWeekStart) && ca.Before(now) {
			twCount++
			twBytes += size
		} else if !ca.Before(lastWeekStart) && ca.Before(thisWeekStart) {
			lwCount++
			lwBytes += size
		}
		if !ca.Before(thisMonthStart) {
			tmCount++
			tmBytes += size
		} else if !ca.Before(lastMonthStart) {
			lmCount++
			lmBytes += size
		}
		if !ca.Before(thisYearStart) {
			tyCount++
			tyBytes += size
		}
	}

	// 环比增长率：上期为 0 时返回 null（避免除零）。
	var weekChange, monthChange any
	if lwCount > 0 {
		weekChange = float64(twCount-lwCount) / float64(lwCount) * 100
	}
	if lmCount > 0 {
		monthChange = float64(tmCount-lmCount) / float64(lmCount) * 100
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"this_week":            map[string]any{"count": twCount, "bytes": twBytes},
		"last_week":            map[string]any{"count": lwCount, "bytes": lwBytes},
		"week_change_percent":  weekChange,
		"this_month":           map[string]any{"count": tmCount, "bytes": tmBytes},
		"last_month":           map[string]any{"count": lmCount, "bytes": lmBytes},
		"month_change_percent": monthChange,
		"this_year":            map[string]any{"count": tyCount, "bytes": tyBytes},
	})
}

// handleWeeklySummary GET /api/media/weekly-summary — 周报摘要。
//
// 返回最近 7 天（滚动窗口，非自然周）的媒体活动摘要，供前端周报卡片一次加载：
//   - week_start / week_end：窗口起止时间（UTC，RFC3339）
//   - uploaded_count / uploaded_bytes：本周上传的媒体数与总字节数（不含软删行）
//   - by_day：按天分布 [{day,count}]，共 7 项，day 为英文星期缩写（Mon/Tue/...）
//     从 week_start 当天起按 UTC 日期分桶，每桶为当日 00:00-24:00 UTC 的上传量。
//   - most_active_day：本周上传最多的那一天 {day,count}；全部为 0 则 {day:"",count:0}
//   - new_tags_count：本周新增的标签操作数（audit_log 中 action="tag" 且 created_at
//     落在窗口内的记录数）。注意"新增标签数"口径为标签关联操作次数而非去重标签名，
//     与现有 audit_log 埋点一致（每次打标签写一条 "tag" 审计）。
//   - new_albums_count：本周创建的相册数（album.CreatedAt Unix 秒 >= week_start）
//
// 数据来源：media 用 s.store.ListMediaByUser（仅未软删行，created_at DESC，复用既有
// 单次全量拉取）；audit log 用 s.store.ListAuditLogs（取较大 limit 拉足够记录再在内存
// 过滤，避免新增 Store 方法）；相册用 mediaSvc 的 albumStoreProvider.ListAlbums。
// 需认证，按 user_id 隔离；store 未注入返回 503；mediaSvc 不支持相册时 new_albums_count 为 0。
//
// 响应结构：
//
//	{
//	  "week_start": "2026-07-26T10:00:00Z",
//	  "week_end":   "2026-08-02T10:00:00Z",
//	  "uploaded_count": N,
//	  "uploaded_bytes": N,
//	  "by_day": [{"day":"Mon","count":N}, ...7],
//	  "most_active_day": {"day":"Mon","count":N} 或 {"day":"","count":0},
//	  "new_tags_count": N,
//	  "new_albums_count": N
//	}
func (s *Server) handleWeeklySummary(w http.ResponseWriter, r *http.Request) {
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

	// 滚动 7 天窗口，UTC 与 growth-report/timeline 等统计端点口径一致。
	now := time.Now().UTC()
	weekStart := now.AddDate(0, 0, -7)

	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 按天分桶：生成 weekStart 当天起共 7 个 UTC 日期桶，标签为星期缩写。
	weekdayShort := []string{"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"}
	type dayBucket struct {
		Day   string `json:"day"`
		Count int    `json:"count"`
	}
	buckets := make([]dayBucket, 7)
	dayStarts := make([]time.Time, 7)
	for i := 0; i < 7; i++ {
		d := weekStart.AddDate(0, 0, i)
		dayStarts[i] = time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, time.UTC)
		buckets[i].Day = weekdayShort[int(dayStarts[i].Weekday())]
	}

	var uploadedCount, uploadedBytes int64
	for _, m := range mediaList {
		ca := m.CreatedAt.UTC()
		if ca.Before(weekStart) || !ca.Before(now) {
			continue
		}
		uploadedCount++
		uploadedBytes += m.Size
		// 落入对应 UTC 日期桶（ca 在 [weekStart, now) 内，故必落在 7 桶之一，
		// 用线性扫描定位当天 00:00 <= ca < 次日 00:00 的桶）。
		for i := 0; i < 7; i++ {
			nextStart := dayStarts[i].AddDate(0, 0, 1)
			if !ca.Before(dayStarts[i]) && ca.Before(nextStart) {
				buckets[i].Count++
				break
			}
		}
	}

	// 最活跃的一天：count 最大者；并列取靠前的桶；全 0 返回 {day:"",count:0}。
	mostActive := dayBucket{}
	for i := 0; i < 7; i++ {
		if buckets[i].Count > mostActive.Count {
			mostActive = buckets[i]
		}
	}
	if mostActive.Count == 0 {
		mostActive = dayBucket{Day: "", Count: 0}
	}

	// 本周新增标签数：audit_log 中 action="tag" 且 created_at 落在窗口内。
	// 取较大 limit 拉足够记录在内存过滤（ListAuditLogs 无法按时间范围查询）。
	newTagsCount := 0
	if logs, err := s.store.ListAuditLogs(r.Context(), uid, 5000); err == nil {
		for _, a := range logs {
			if a.Action != "tag" {
				continue
			}
			if ca := a.CreatedAt.UTC(); !ca.Before(weekStart) && ca.Before(now) {
				newTagsCount++
			}
		}
	} // 查询失败不致命，按 0 计并继续返回其余字段。

	// 本周新增相册数：album.CreatedAt 为 Unix 秒（int64），>= weekStart.Unix() 即本周创建。
	newAlbumsCount := 0
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		for _, a := range provider.ListAlbums(uid) {
			if a.CreatedAt >= weekStart.Unix() {
				newAlbumsCount++
			}
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"week_start":       weekStart.Format(time.RFC3339),
		"week_end":         now.Format(time.RFC3339),
		"uploaded_count":   uploadedCount,
		"uploaded_bytes":   uploadedBytes,
		"by_day":           buckets,
		"most_active_day":  mostActive,
		"new_tags_count":   newTagsCount,
		"new_albums_count": newAlbumsCount,
	})
}

// handleMediaLifecycle V8：GET /api/media/media-lifecycle — 媒体生命周期分析。
// 从 audit_log 获取各 action 的统计，展示上传→收藏→分享→删除各阶段分布。
func (s *Server) handleMediaLifecycle(w http.ResponseWriter, r *http.Request) {
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
	stats, err := s.store.AuditLogStats(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 阶段映射：action → 生命周期阶段
	stageMap := map[string]string{
		"upload":     "上传",
		"favorite":   "收藏",
		"unfavorite": "取消收藏",
		"share":      "分享",
		"rename":     "重命名",
		"tag":        "打标签",
		"rotate":     "旋转",
		"delete":     "删除",
		"restore":    "恢复",
	}
	total := 0
	for _, s2 := range stats {
		cnt, _ := s2["count"].(int)
		total += cnt
	}
	lifecycle := make([]map[string]any, 0, len(stats))
	for _, s2 := range stats {
		action, _ := s2["action"].(string)
		cnt, _ := s2["count"].(int)
		stage := stageMap[action]
		if stage == "" {
			stage = action
		}
		pct := 0.0
		if total > 0 {
			pct = float64(cnt) * 100.0 / float64(total)
		}
		lifecycle = append(lifecycle, map[string]any{
			"stage":      stage,
			"action":     action,
			"count":      cnt,
			"percentage": pct,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"lifecycle":      lifecycle,
		"total_actions":  total,
		"total_stages":   len(lifecycle),
	})
}

// handleMediaYearlyReview GET /api/media/yearly-review — 年度回顾报告。
//
// 返回某年的媒体统计摘要（类似各 App 的年度报告）：
//   - summary：total_count / total_bytes（该年媒体总量）
//   - by_month：1..12 各月上传统数 [{month,count}, ...]（固定 12 项，含 0）
//   - by_type：按媒体类型分桶 {image,video,live}（小写键名，便于前端展示）
//   - first_upload / last_upload：该年最早与最晚上传时间（ISO 字符串）
//   - top_day：该年单日上传最多的日子 {date,count}；无数据时 date="" count=0
//   - favorites：该年被标记为收藏的媒体数（need mediaSvc 实现 favoriteProvider）
//
// 年筛选以 created_at（上传时间）的 UTC 年份为准；?year=2026 默认当前 UTC 年。
// 需认证，按 user_id 隔离；store 未注入返回 503。
//
// 响应结构：
//
//	{
//	  "year": 2026,
//	  "summary": {"total_count":N,"total_bytes":N},
//	  "by_month": [{"month":1,"count":N}, ... 12],
//	  "by_type":  {"image":N,"video":N,"live":N},
//	  "first_upload": "2026-04-13T10:11:12Z" 或 null,
//	  "last_upload":  "2026-12-..Z" 或 null,
//	  "top_day":      {"date":"2026-07-04","count":N} 或 {"date":"","count":0},
//	  "favorites":    N
//	}
func (s *Server) handleMediaYearlyReview(w http.ResponseWriter, r *http.Request) {
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

	// year 默认当年（UTC），?year=2026 指定；非法回退到当年。
	now := time.Now().UTC()
	year := now.Year()
	if q := r.URL.Query().Get("year"); q != "" {
		if y, err := strconv.Atoi(q); err == nil && y >= 1970 && y <= 9999 {
			year = y
		}
	}

	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 聚合：按月/类型分桶 + 总量 + 首末上传 + 单日最高。
	var totalCount, totalBytes int64
	var imageCount, videoCount, liveCount int64
	monthCounts := make([]int64, 12) // index 0=1月
	dayCounts := make(map[string]int64)
	var firstUpload, lastUpload time.Time
	yearIDs := make(map[string]struct{}, len(mediaList))

	for _, m := range mediaList {
		ca := m.CreatedAt.UTC()
		if ca.Year() != year {
			continue
		}
		totalCount++
		totalBytes += m.Size
		yearIDs[m.ID] = struct{}{}

		monthCounts[ca.Month()-1]++
		dayKey := ca.Format("2006-01-02")
		dayCounts[dayKey]++

		switch strings.ToUpper(m.Type) {
		case "IMAGE", "PHOTO":
			imageCount++
		case "VIDEO":
			videoCount++
		case "LIVE_PHOTO", "LIVE":
			liveCount++
		}

		if firstUpload.IsZero() || ca.Before(firstUpload) {
			firstUpload = ca
		}
		if lastUpload.IsZero() || ca.After(lastUpload) {
			lastUpload = ca
		}
	}

	// by_month 固定 12 项，便于前端无脑渲染。
	byMonth := make([]map[string]any, 0, 12)
	for i := 0; i < 12; i++ {
		byMonth = append(byMonth, map[string]any{
			"month": i + 1,
			"count": monthCounts[i],
		})
	}

	// top_day：上传最多的日子；多个并列取日期最早者（稳定输出）。
	var topDay string
	var topCount int64
	for d, c := range dayCounts {
		if c > topCount || (c == topCount && (topDay == "" || d < topDay)) {
			topDay = d
			topCount = c
		}
	}

	// 首末上传时间格式化为 RFC3339（UTC "Z"）。
	var firstISO, lastISO any
	if totalCount > 0 {
		firstISO = firstUpload.UTC().Format(time.RFC3339)
		lastISO = lastUpload.UTC().Format(time.RFC3339)
	} else {
		firstISO = nil
		lastISO = nil
	}

	// favorites：该年被收藏的媒体数。favoriteProvider 可能未实现（返回 0）。
	favoriteCount := 0
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favIDs := fav.ListFavorites(uid)
		if len(favIDs) > 0 && len(yearIDs) > 0 {
			favSet := make(map[string]struct{}, len(favIDs))
			for _, id := range favIDs {
				favSet[id] = struct{}{}
			}
			for id := range yearIDs {
				if _, ok := favSet[id]; ok {
					favoriteCount++
				}
			}
		}
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"year": year,
		"summary": map[string]any{
			"total_count": totalCount,
			"total_bytes": totalBytes,
		},
		"by_month":     byMonth,
		"by_type":      map[string]any{"image": imageCount, "video": videoCount, "live": liveCount},
		"first_upload": firstISO,
		"last_upload":  lastISO,
		"top_day":      map[string]any{"date": topDay, "count": topCount},
		"favorites":    favoriteCount,
	})
}

// handleMediaFullReport V21：GET /api/media/full-report?year=2026 — 综合报告。
//
// 一次请求合并多个统计端点的数据，供前端"年度报告"风格页面单次加载：
//   - quick_stats：全库极简统计（total_media/image_count/video_count/album_count/favorite_count）
//   - yearly：指定年度回顾（summary/by_month/by_type/first_upload/last_upload/top_day/favorites）
//   - storage：存储摘要（total_bytes + used_quota_percent，来自磁盘 statfs）
//   - tags：tag_top5（按 count DESC 取前 5）
//   - pattern：上传模式（dominant_type/dominant_time_period/dominant_weekday，口径同 upload-pattern-analysis）
//   - duplicates：重复文件摘要（groups 数 + reclaimable_bytes）
//
// 实现策略：Store.ListMediaByUser 单次拉全量后内存派生各块，best-effort ——
// 磁盘 statfs 失败时 storage 置零而非整体失败；TagStats 失败时 tags 置空数组。
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaFullReport(w http.ResponseWriter, r *http.Request) {
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

	// year 默认当年（UTC），?year=2026 指定；非法回退到当年。
	now := time.Now().UTC()
	year := now.Year()
	if q := r.URL.Query().Get("year"); q != "" {
		if y, err := strconv.Atoi(q); err == nil && y >= 1970 && y <= 9999 {
			year = y
		}
	}

	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// ---- 一次性遍历派生 quick_stats + yearly + pattern ----
	var totalMedia, imgCount, vidCount int
	var totalBytes int64 // 全库未删字节数（quick_stats 口径）
	typeCounts := map[string]int{}
	periodCounts := map[string]int{}
	weekdayCounts := map[string]int{}

	// yearly 维度
	var yearCount, yearBytes int64
	var yearImg, yearVid, yearLive int64
	monthCounts := make([]int64, 12)
	dayCounts := make(map[string]int64)
	var firstUpload, lastUpload time.Time
	yearIDs := make(map[string]struct{})

	// 重复文件分组（同 duplicate-report 口径：SHA256 分组，跳过空 SHA256 与软删）
	dupGroups := make(map[string][]struct {
		size int64
		ca   int64
	})

	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		totalMedia++
		totalBytes += m.Size
		switch strings.ToUpper(m.Type) {
		case "IMAGE", "PHOTO":
			imgCount++
		case "VIDEO":
			vidCount++
		}
		// pattern 维度
		typeCounts[m.Type]++
		hour := m.CreatedAt.Hour()
		var period string
		switch {
		case hour >= 6 && hour < 12:
			period = "早晨"
		case hour >= 12 && hour < 18:
			period = "下午"
		case hour >= 18 && hour < 24:
			period = "晚上"
		default:
			period = "深夜"
		}
		periodCounts[period]++
		weekdayCounts[m.CreatedAt.Weekday().String()]++

		// yearly 维度
		ca := m.CreatedAt.UTC()
		if ca.Year() == year {
			yearCount++
			yearBytes += m.Size
			yearIDs[m.ID] = struct{}{}
			monthCounts[ca.Month()-1]++
			dayCounts[ca.Format("2006-01-02")]++
			switch strings.ToUpper(m.Type) {
			case "IMAGE", "PHOTO":
				yearImg++
			case "VIDEO":
				yearVid++
			case "LIVE_PHOTO", "LIVE":
				yearLive++
			}
			if firstUpload.IsZero() || ca.Before(firstUpload) {
				firstUpload = ca
			}
			if lastUpload.IsZero() || ca.After(lastUpload) {
				lastUpload = ca
			}
		}

		// duplicate 维度
		if m.SHA256 != "" {
			dupGroups[m.SHA256] = append(dupGroups[m.SHA256], struct {
				size int64
				ca   int64
			}{size: m.Size, ca: m.CreatedAt.Unix()})
		}
	}

	// ---- quick_stats: album/favorite counts ----
	favoriteCount := 0
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favoriteCount = len(fav.ListFavorites(uid))
	}
	albumCount := 0
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		albumCount = len(provider.ListAlbums(uid))
	}
	quickStats := map[string]any{
		"total_media":    totalMedia,
		"image_count":    imgCount,
		"video_count":    vidCount,
		"album_count":    albumCount,
		"favorite_count": favoriteCount,
	}

	// ---- yearly by_month / top_day ----
	byMonth := make([]map[string]any, 0, 12)
	for i := 0; i < 12; i++ {
		byMonth = append(byMonth, map[string]any{"month": i + 1, "count": monthCounts[i]})
	}
	var topDay string
	var topCount int64
	for d, c := range dayCounts {
		if c > topCount || (c == topCount && (topDay == "" || d < topDay)) {
			topDay = d
			topCount = c
		}
	}
	var firstISO, lastISO any
	if yearCount > 0 {
		firstISO = firstUpload.UTC().Format(time.RFC3339)
		lastISO = lastUpload.UTC().Format(time.RFC3339)
	}
	// yearly favorites：与 yearIDs 求交。
	yFavorite := 0
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favIDs := fav.ListFavorites(uid)
		if len(favIDs) > 0 && len(yearIDs) > 0 {
			favSet := make(map[string]struct{}, len(favIDs))
			for _, id := range favIDs {
				favSet[id] = struct{}{}
			}
			for id := range yearIDs {
				if _, ok := favSet[id]; ok {
					yFavorite++
				}
			}
		}
	}
	yearly := map[string]any{
		"year": year,
		"summary": map[string]any{
			"total_count": yearCount,
			"total_bytes": yearBytes,
		},
		"by_month":     byMonth,
		"by_type":      map[string]any{"image": yearImg, "video": yearVid, "live": yearLive},
		"first_upload": firstISO,
		"last_upload":  lastISO,
		"top_day":      map[string]any{"date": topDay, "count": topCount},
		"favorites":    yFavorite,
	}

	// ---- storage summary（best-effort：statfs 失败置零）----
	storageSummary := map[string]any{
		"total_bytes":        totalBytes,
		"used_quota_percent": 0.0,
	}
	if uploadsDir := s.userUploadsDir(uid); uploadsDir != "" {
		var stat syscall.Statfs_t
		if err := syscall.Statfs(uploadsDir, &stat); err == nil {
			diskTotal := stat.Blocks * uint64(stat.Bsize)
			diskFree := stat.Bavail * uint64(stat.Bsize)
			diskUsed := diskTotal - diskFree
			storageSummary["disk_total_bytes"] = diskTotal
			storageSummary["disk_used_bytes"] = diskUsed
			if diskTotal > 0 {
				storageSummary["used_quota_percent"] = float64(diskUsed) / float64(diskTotal) * 100
			}
		}
	}

	// ---- tag_top5（best-effort：TagStats 失败置空数组）----
	tagTop5 := []map[string]any{}
	if stats, err := s.store.TagStats(r.Context(), uid); err == nil {
		limit := 5
		if len(stats) < limit {
			limit = len(stats)
		}
		for i := 0; i < limit; i++ {
			tagTop5 = append(tagTop5, stats[i])
		}
	} else {
		slog.Warn("full-report: tag stats failed", "error", err)
	}

	// ---- pattern：众数选取（同 upload-pattern-analysis 口径）----
	pickDominant := func(order []string, counts map[string]int) map[string]any {
		bestKey := ""
		bestCount := 0
		for _, k := range order {
			if c := counts[k]; c > bestCount {
				bestKey = k
				bestCount = c
			}
		}
		for k, c := range counts {
			if c > bestCount {
				bestKey = k
				bestCount = c
			}
		}
		return map[string]any{"key": bestKey, "count": bestCount}
	}
	periodOrder := []string{"早晨", "下午", "晚上", "深夜"}
	weekdayOrder := []string{"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"}
	pattern := map[string]any{
		"dominant_type":        pickDominant([]string{"IMAGE", "VIDEO", "LIVE_PHOTO"}, typeCounts),
		"dominant_time_period": pickDominant(periodOrder, periodCounts),
		"dominant_weekday":     pickDominant(weekdayOrder, weekdayCounts),
		"total":                totalMedia,
	}

	// ---- duplicate summary（同 duplicate-report 口径的汇总数字）----
	dupGroupCount := 0
	var reclaimableBytes int64
	for _, items := range dupGroups {
		if len(items) < 2 {
			continue
		}
		dupGroupCount++
		reclaimableBytes += int64(len(items)-1) * items[0].size
	}
	duplicates := map[string]any{
		"groups":            dupGroupCount,
		"reclaimable_bytes": reclaimableBytes,
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"year":        year,
		"quick_stats": quickStats,
		"yearly":      yearly,
		"storage":     storageSummary,
		"tags":        tagTop5,
		"pattern":     pattern,
		"duplicates":  duplicates,
		"user_id":     uid,
	})
}

// handleMediaHeatmap GET /api/media/media-heatmap — 按天统计媒体数量热力图。
//
// 返回一年（12 个月 × 每月天数）的 GitHub 贡献图风格数据。日期优先取 taken_at
// （拍摄时间），缺失（=0）回退到 created_at（上传时间），保证无 EXIF 的媒体也计入。
// 仅返回有媒体的日期，前端将空白日补 0。
//
// 响应结构：
//
//	{
//	  "days": [{"date":"2026-07-30","count":3}, ...], // 按 date 升序
//	  "total_days":  N,  // 有媒体的不同日期数
//	  "total_media": M   // 所有日期 count 合计（= 未软删媒体总数）
//	}
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaHeatmap(w http.ResponseWriter, r *http.Request) {
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
	days, err := s.store.MediaHeatmap(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	var totalMedia int
	for _, d := range days {
		if c, ok := d["count"].(int); ok {
			totalMedia += c
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"days":        days,
		"total_days":  len(days),
		"total_media": totalMedia,
	})
}

// handleMediaStatSummary V9：GET /api/media/stat-summary — 一站式统计汇总。
//
// 合并前端"我的"Tab 多个卡片所需的最常用统计，一次请求返回，避免逐卡片多次调用
// （storage-breakdown / file-types / tag.stats / audit-log/stats / user-quota /
// favorites / albums / share/list / trash 等）。
//
// 各子统计 best-effort：单条 Store 调用失败仅令对应字段为空/null，不影响整体 200。
// 需认证，按 user_id 隔离；store 未注入返回 503。
//
// 响应结构：
//
//	{
//	  "summary": {total_count, total_bytes, image_count, video_count, live_count},
//	  "tags":     [{tag, count}],      // top 5
//	  "audit":    [{action, count}],
//	  "quota":    {quota_bytes, used_bytes, usage_percent},
//	  "favorites": N,
//	  "shares":    N,
//	  "albums":    N,
//	  "trash":     N,
//	  "recent_uploads": [{id, filename, type, created_at}]  // top 3
//	}
func (s *Server) handleMediaStatSummary(w http.ResponseWriter, r *http.Request) {
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

	// summary + recent_uploads 由 ListMediaByUser 一次拉取派生（该方法返回未软删
	// 媒体且已按 created_at DESC 排序，故前 3 条即最近上传）。
	const defaultQuotaBytes int64 = 10 * 1024 * 1024 * 1024 // 10 GB，与 handleUserQuota 一致
	const recentUploadLimit = 3

	var summary map[string]any
	var recentUploads []map[string]any
	var usedBytes int64
	if mediaList, err := s.store.ListMediaByUser(r.Context(), uid); err != nil {
		slog.Warn("stat-summary: list media failed", "error", err)
	} else {
		var total int
		var imgCount, vidCount, liveCount int
		for _, m := range mediaList {
			total++
			usedBytes += m.Size
			switch m.Type {
			case "IMAGE":
				imgCount++
			case "VIDEO":
				vidCount++
			case "LIVE_PHOTO":
				liveCount++
			}
		}
		summary = map[string]any{
			"total_count": total,
			"total_bytes": usedBytes,
			"image_count": imgCount,
			"video_count": vidCount,
			"live_count":  liveCount,
		}
		n := len(mediaList)
		if n > recentUploadLimit {
			n = recentUploadLimit
		}
		recentUploads = make([]map[string]any, 0, n)
		for i := 0; i < n; i++ {
			m := mediaList[i]
			recentUploads = append(recentUploads, map[string]any{
				"id":         m.ID,
				"filename":   m.Filename,
				"type":       m.Type,
				"created_at": m.CreatedAt.Format(time.RFC3339),
			})
		}
	}

	// tags：取 top 5（TagStats 已按 count DESC 返回）。
	var topTags []map[string]any
	if tagStats, err := s.store.TagStats(r.Context(), uid); err != nil {
		slog.Warn("stat-summary: tag stats failed", "error", err)
	} else {
		n := len(tagStats)
		if n > 5 {
			n = 5
		}
		topTags = make([]map[string]any, 0, n)
		for i := 0; i < n; i++ {
			topTags = append(topTags, tagStats[i])
		}
	}

	// audit：按操作类型聚合。
	var auditStats []map[string]any
	if stats, err := s.store.AuditLogStats(r.Context(), uid); err != nil {
		slog.Warn("stat-summary: audit stats failed", "error", err)
	} else {
		auditStats = stats
	}

	// quota：复用 summary 阶段算出的 usedBytes（ListMediaByUser 失败时 usedBytes=0）。
	usagePercent := 0.0
	if defaultQuotaBytes > 0 {
		usagePercent = float64(usedBytes) / float64(defaultQuotaBytes) * 100
	}
	quota := map[string]any{
		"quota_bytes":   defaultQuotaBytes,
		"used_bytes":    usedBytes,
		"usage_percent": usagePercent,
	}

	// shares：当前用户创建的分享链接数。
	var shareCount int
	if tokens, err := s.store.ListShareTokensByUser(r.Context(), uid); err != nil {
		slog.Warn("stat-summary: list share tokens failed", "error", err)
	} else {
		shareCount = len(tokens)
	}

	// trash：回收站（deleted=1）总条数。
	var trashCount int
	if n, err := s.store.CountTrashByUser(r.Context(), uid); err != nil {
		slog.Warn("stat-summary: count trash failed", "error", err)
	} else {
		trashCount = n
	}

	// favorites / albums：来自 mediaSvc 的 provider 接口（与 handleMediaFavorites /
	// handleAlbumList 一致），provider 不支持时记 0 且不阻断。
	favoriteCount := 0
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favoriteCount = len(fav.ListFavorites(uid))
	}
	albumCount := 0
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		albumCount = len(provider.ListAlbums(uid))
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"summary":        summary,
		"tags":           topTags,
		"audit":          auditStats,
		"quota":          quota,
		"favorites":      favoriteCount,
		"shares":         shareCount,
		"albums":         albumCount,
		"trash":          trashCount,
		"recent_uploads": recentUploads,
	})
}

// handleMediaQuickStats V12：GET /api/media/quick-stats — 极简统计端点。
//
// 仅返回首页快速加载所需的 6 个数字，区别于 stat-summary 的全量汇总（含
// tags/audit/quota/shares/recent_uploads 等多块数据）。前端首页只展示总量级概览，
// 无需 stat-summary 的额外开销（tags/audit 都需要独立的 Store 查询）。
//
// 响应结构：
//
//	{
//	  "total_media":    N,   // 未软删媒体总数（含 IMAGE/VIDEO/LIVE_PHOTO 等）
//	  "total_bytes":    N,   // 未软删媒体字节数累计
//	  "image_count":    N,
//	  "video_count":    N,
//	  "album_count":    N,
//	  "favorite_count": N
//	}
//
// summary 与 image/video/total_bytes 由 store.ListMediaByUser 一次拉取派生；
// album_count 与 favorite_count 来自 mediaSvc 的 provider 接口（provider 未实现
// 时记为 0，不阻断），与 handleMediaStatSummary 的同名字段口径一致。
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaQuickStats(w http.ResponseWriter, r *http.Request) {
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

	var totalMedia, imgCount, vidCount int
	var totalBytes int64
	if mediaList, err := s.store.ListMediaByUser(r.Context(), uid); err != nil {
		slog.Warn("quick-stats: list media failed", "error", err)
	} else {
		for _, m := range mediaList {
			totalMedia++
			totalBytes += m.Size
			switch m.Type {
			case "IMAGE":
				imgCount++
			case "VIDEO":
				vidCount++
			}
		}
	}

	favoriteCount := 0
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favoriteCount = len(fav.ListFavorites(uid))
	}
	albumCount := 0
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		albumCount = len(provider.ListAlbums(uid))
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"total_media":    totalMedia,
		"total_bytes":    totalBytes,
		"image_count":    imgCount,
		"video_count":    vidCount,
		"album_count":    albumCount,
		"favorite_count": favoriteCount,
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

// handleMediaTagStatDetailed V21：GET /api/media/tag-stat-detailed — 标签详细统计。
// 每个标签返回：tag_name + count（关联媒体数） + total_bytes（关联文件 size 总和）
// + last_created_at（关联 media 中最近的 created_at）。
//
// 实现策略（避免 N+1 查询 media 行）：
//  1. TagStats 取标签计数（已按 count DESC 排序）。
//  2. ListMediaByUser 一次拉该用户全量 media，构造 id→*Media 索引。
//  3. 对每个标签调 SearchMediaByTag 取关联 media_id 列表，用索引汇总 size 求和与
//     created_at 取最大值。
//
// 注意：ListMediaByUser 只返未软删 media，已被软删的 media 不计入 size/时间统计，
// 但 media_tags 关系行不随软删联动清理，故 count 仍按标签关系表口径。若某 media_id
// 在索引中缺失（理论上不应发生——软删会从 ListMediaByUser 排除但关系仍在），跳过该 id
// 不累加 size/时间，best-effort 处理。
func (s *Server) handleMediaTagStatDetailed(w http.ResponseWriter, r *http.Request) {
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
	// 一次拉全量 media 建索引，避免对每个 media_id 单独 GetMedia。
	medias, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	idx := make(map[string]*storage.Media, len(medias))
	for _, m := range medias {
		idx[m.ID] = m
	}
	out := make([]map[string]any, 0, len(stats))
	for _, st := range stats {
		tagName, _ := st["tag"].(string)
		count, _ := st["count"].(int)
		ids, err := s.store.SearchMediaByTag(r.Context(), uid, tagName)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		var totalBytes int64
		var lastCreated time.Time
		for _, id := range ids {
			m, ok := idx[id]
			if !ok {
				// 软删或缺失，跳过 size/时间统计（best-effort）。
				continue
			}
			totalBytes += m.Size
			if m.CreatedAt.After(lastCreated) {
				lastCreated = m.CreatedAt
			}
		}
		row := map[string]any{
			"tag_name":    tagName,
			"count":       count,
			"total_bytes": totalBytes,
		}
		// last_created_at 仅在该标签至少有一个未软删 media 时返回；否则置 null，
		// 明确区分"无关联媒体"与"1970-01-01"语义。
		if !lastCreated.IsZero() {
			row["last_created_at"] = lastCreated.UTC().Format(time.RFC3339)
		} else {
			row["last_created_at"] = nil
		}
		out = append(out, row)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tags":       out,
		"total_tags": len(out),
	})
}

// handleTagStatByType GET /api/media/tag/stat-by-type — 按媒体类型统计标签使用。
// 对每个标签返回：tag_name + total（关联媒体总数）+ image_count/video_count/live_count
// （该标签在 IMAGE/VIDEO/LIVE_PHOTO 三类媒体中的分布数量）。
// 响应: { tags: [{tag_name, total, image_count, video_count, live_count}], total_tags }。
//
// 实现策略（避免 N+1 查询 media 行，与 handleMediaTagStatDetailed 一致）：
//  1. ListAllTags 取该用户全量标签名（按名字升序）。
//  2. ListMediaByUser 一次拉该用户全量 media，构造 id→*Media 索引。
//  3. 对每个标签调 SearchMediaByTag 取关联 media_id 列表，用索引查 m.Type 统计分布。
//     LIVE_PHOTO 计入 live_count；未在索引中命中（软删/缺失）的 id 跳过，best-effort。
func (s *Server) handleTagStatByType(w http.ResponseWriter, r *http.Request) {
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
	tags, err := s.store.ListAllTags(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 一次拉全量 media 建索引，避免对每个 media_id 单独 GetMedia。
	medias, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	idx := make(map[string]*storage.Media, len(medias))
	for _, m := range medias {
		idx[m.ID] = m
	}
	type tagTypeStat struct {
		TagName     string `json:"tag_name"`
		Total       int    `json:"total"`
		ImageCount  int    `json:"image_count"`
		VideoCount  int    `json:"video_count"`
		LiveCount   int    `json:"live_count"`
	}
	out := make([]tagTypeStat, 0, len(tags))
	for _, tagName := range tags {
		ids, err := s.store.SearchMediaByTag(r.Context(), uid, tagName)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		st := tagTypeStat{TagName: tagName}
		for _, id := range ids {
			m, ok := idx[id]
			if !ok {
				// 软删或缺失，跳过类型统计（best-effort），不计入 total。
				continue
			}
			switch m.Type {
			case "IMAGE":
				st.ImageCount++
			case "VIDEO":
				st.VideoCount++
			case "LIVE_PHOTO":
				st.LiveCount++
			}
			st.Total++
		}
		out = append(out, st)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tags":       out,
		"total_tags": len(out),
	})
}

// handleTagCoOccurrence GET /api/media/tag-co-occurrence — 标签共现分析。
// 对每对标签 (A, B) 统计同时拥有这两个标签的 media 数量，只返回 co-occurrence >= 2
// 的标签对。响应: { pairs: [{tag_a, tag_b, count}], total_pairs }。
//
// 实现：ListAllTags 取全量标签，对每个标签调 SearchMediaByTag 取关联 media_id 集合，
// 再对每对 (i<j) 求交集大小。标签数 N 时共 N*(N-1)/2 对，N 通常较小（几十～几百）。
func (s *Server) handleTagCoOccurrence(w http.ResponseWriter, r *http.Request) {
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
	tags, err := s.store.ListAllTags(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 为每个标签构造 media_id 集合，供后续求交集。
	tagMedia := make(map[string]map[string]struct{}, len(tags))
	for _, t := range tags {
		ids, err := s.store.SearchMediaByTag(r.Context(), uid, t)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		set := make(map[string]struct{}, len(ids))
		for _, id := range ids {
			set[id] = struct{}{}
		}
		tagMedia[t] = set
	}
	type coPair struct {
		TagA  string `json:"tag_a"`
		TagB  string `json:"tag_b"`
		Count int    `json:"count"`
	}
	pairs := make([]coPair, 0)
	for i := 0; i < len(tags); i++ {
		a := tags[i]
		setA := tagMedia[a]
		for j := i + 1; j < len(tags); j++ {
			b := tags[j]
			setB := tagMedia[b]
			// 求交集大小：遍历较小的集合查较大的集合。
			small, large := setA, setB
			if len(small) > len(large) {
				small, large = large, small
			}
			count := 0
			for id := range small {
				if _, ok := large[id]; ok {
					count++
				}
			}
			if count >= 2 {
				pairs = append(pairs, coPair{TagA: a, TagB: b, Count: count})
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"pairs":       pairs,
		"total_pairs": len(pairs),
	})
}

// handleTagNetwork GET /api/media/tag-network — 标签网络图数据（节点+边）。
// 标签作为节点（count=关联媒体数），共现关系作为边（weight=同时拥有两标签的 media 数）。
// 所有共现 >=1 的标签对均纳入边集，供前端按权重过滤渲染力导向/弦图等可视化。
// 响应: { nodes: [{id, count}], edges: [{source, target, weight}], total_nodes, total_edges }。
//
// 实现：ListAllTags 取全量标签，对每个标签调 SearchMediaByTag 取关联 media_id 集合
// （同时得到节点 count = 集合大小），再对每对 (i<j) 求交集大小作为边权重。与
// handleTagCoOccurrence 同构，但输出图结构而非标签对列表，且纳管全部共现 >=1 的边。
func (s *Server) handleTagNetwork(w http.ResponseWriter, r *http.Request) {
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
	tags, err := s.store.ListAllTags(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 为每个标签构造 media_id 集合，供后续求交集；集合大小即节点 count。
	type netNode struct {
		ID    string `json:"id"`
		Count int    `json:"count"`
	}
	type netEdge struct {
		Source string `json:"source"`
		Target string `json:"target"`
		Weight int    `json:"weight"`
	}
	tagMedia := make(map[string]map[string]struct{}, len(tags))
	nodes := make([]netNode, 0, len(tags))
	for _, t := range tags {
		ids, err := s.store.SearchMediaByTag(r.Context(), uid, t)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		set := make(map[string]struct{}, len(ids))
		for _, id := range ids {
			set[id] = struct{}{}
		}
		tagMedia[t] = set
		nodes = append(nodes, netNode{ID: t, Count: len(set)})
	}
	edges := make([]netEdge, 0)
	for i := 0; i < len(tags); i++ {
		a := tags[i]
		setA := tagMedia[a]
		for j := i + 1; j < len(tags); j++ {
			b := tags[j]
			setB := tagMedia[b]
			// 求交集大小：遍历较小的集合查较大的集合。
			small, large := setA, setB
			if len(small) > len(large) {
				small, large = large, small
			}
			weight := 0
			for id := range small {
				if _, ok := large[id]; ok {
					weight++
				}
			}
			if weight >= 1 {
				edges = append(edges, netEdge{Source: a, Target: b, Weight: weight})
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"nodes":       nodes,
		"edges":       edges,
		"total_nodes": len(nodes),
		"total_edges": len(edges),
	})
}

// handleMediaTagCloudData V8：GET /api/media/tag/cloud-data — 标签云数据。
// 返回每个标签的 count 及关联的最近一个 media_id 对应的缩略图 URL，
// 供前端渲染带封面的标签云。响应: { tags: [{tag_name, count, thumbnail_url}], total }。
// 实现：先 TagStats 取全量标签计数（已按 count DESC 排序），再对每个标签调
// SearchMediaByTag 取关联的第一个 media_id（按 media_id 升序，即字典序最小者），
// 拼出 thumbnail_url = /api/media/thumbnail/{media_id}。
func (s *Server) handleMediaTagCloudData(w http.ResponseWriter, r *http.Request) {
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
	out := make([]map[string]any, 0, len(stats))
	for _, st := range stats {
		tagName, _ := st["tag"].(string)
		count, _ := st["count"].(int)
		// 取该标签关联的第一个 media_id 作为封面。
		ids, err := s.store.SearchMediaByTag(r.Context(), uid, tagName)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		thumbURL := ""
		if len(ids) > 0 {
			thumbURL = "/api/media/thumbnail/" + ids[0]
		}
		out = append(out, map[string]any{
			"tag_name":      tagName,
			"count":         count,
			"thumbnail_url": thumbURL,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tags":  out,
		"total": len(out),
	})
}

// handleTagHierarchy GET /api/media/tag-hierarchy — 标签层级分析。
// 自动分析标签名中的分隔符（- / : 等）推断父子关系：如 "旅行-国内"、"旅行-国外"
// 的父节点为 "旅行"；没有分隔符的标签作为顶层根节点。
// 响应: { hierarchy: [{tag, count, children: [{tag, count}]}], total_roots }。
//
// 推断算法：
//  1. ListAllTags 取当前用户的全部标签集合 tagSet（含标签名）。
//  2. TagStats 取各标签关联媒体数，构建 tag→count 映射。
//  3. 对每个标签，按 - / : 三种分隔符切分出前缀 parent + 后缀 suffix。
//     - 若分隔符存在且 parent ∈ tagSet（父标签确实由用户独立使用），则视为
//       父子关系；否则该标签作为顶层根。
//     - 优先级：- > / :（按出现顺序依次尝试，命中即停）。
//     - 仅按第一个分隔符切分：a-b-c 的父为 a（若 a 独立存在），不做多层嵌套，
//       保持单层父子、简单可预测。
//     count 取该标签自身关联媒体数（不含子标签的，避免重复统计口径混乱）。
//
// 注意：本端点只读，不落任何数据；推断纯基于标签名命名约定。
func (s *Server) handleTagHierarchy(w http.ResponseWriter, r *http.Request) {
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
	tags, err := s.store.ListAllTags(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// tag→count 映射。TagStats 返回 []map{tag,count}（按 count DESC）。
	stats, err := s.store.TagStats(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	countOf := make(map[string]int, len(stats))
	for _, st := range stats {
		name, _ := st["tag"].(string)
		cnt, _ := st["count"].(int)
		countOf[name] = cnt
	}
	// tagSet 用于 O(1) 判断父标签是否独立存在。
	tagSet := make(map[string]struct{}, len(tags))
	for _, t := range tags {
		tagSet[t] = struct{}{}
	}
	// splitParent 按 - / : 分隔符切分出 (parent, ok)。
	// 命中即返回；分隔符优先级 - > / :。仅按第一个分隔符切分
	// （a-b-c 的父为 a，是非多层嵌套，保持单层简单可预测）。
	splitParent := func(name string) (parent string, ok bool) {
		for _, sep := range []string{"-", "/", ":"} {
			if idx := strings.Index(name, sep); idx > 0 && idx < len(name)-len(sep) {
				p := name[:idx]
				if _, exists := tagSet[p]; exists {
					return p, true
				}
			}
		}
		return "", false
	}
	type childNode struct {
		Tag   string `json:"tag"`
		Count int    `json:"count"`
	}
	type rootNode struct {
		Tag      string      `json:"tag"`
		Count    int         `json:"count"`
		Children []childNode `json:"children"`
	}
	// 按 parent 分组子标签。
	childrenOf := make(map[string][]string)
	for _, t := range tags {
		if p, ok := splitParent(t); ok {
			childrenOf[p] = append(childrenOf[p], t)
		}
	}
	hierarchy := make([]rootNode, 0, len(tags))
	for _, t := range tags {
		// 根节点 = 自身不是任何现存标签的子标签。
		if _, ok := splitParent(t); ok {
			continue
		}
		node := rootNode{Tag: t, Count: countOf[t]}
		kids := childrenOf[t]
		// ListAllTags 已按 tag_name 升序，故 kids 亦升序，无需再排序。
		for _, c := range kids {
			node.Children = append(node.Children, childNode{Tag: c, Count: countOf[c]})
		}
		if node.Children == nil {
			node.Children = []childNode{}
		}
		hierarchy = append(hierarchy, node)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"hierarchy":    hierarchy,
		"total_roots":  len(hierarchy),
		"total_tags":   len(tags),
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

// handleMediaTagBatchRename V8：POST /api/media/tag/batch-rename — 批量重命名标签。
// 请求体: { renames: [{old_name, new_name}, ...] }
// 遍历调 Store.RenameTag(ctx, uid, old, new)；逐条记录成功与失败，互不中断。
// 响应: { status, renamed_count, failed: [{old_name, reason}] }
func (s *Server) handleMediaTagBatchRename(w http.ResponseWriter, r *http.Request) {
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
		Renames []struct {
			OldName string `json:"old_name"`
			NewName string `json:"new_name"`
		} `json:"renames"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.Renames) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "renames required"})
		return
	}
	renamedCount := 0
	failed := make([]map[string]string, 0, len(req.Renames))
	for _, item := range req.Renames {
		oldName := strings.TrimSpace(item.OldName)
		newName := strings.TrimSpace(item.NewName)
		if oldName == "" || newName == "" {
			failed = append(failed, map[string]string{"old_name": item.OldName, "reason": "old_name and new_name required"})
			continue
		}
		if _, err := s.store.RenameTag(r.Context(), uid, oldName, newName); err != nil {
			failed = append(failed, map[string]string{"old_name": oldName, "reason": err.Error()})
			continue
		}
		renamedCount++
	}
	status := "success"
	if renamedCount == 0 {
		status = "failed"
	} else if len(failed) > 0 {
		status = "partial"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        status,
		"renamed_count": renamedCount,
		"failed":        failed,
	})
}

// handleMediaTagImport V8：POST /api/media/tag/import — 批量导入标签。
// 用于从外部系统迁移标签数据。请求体: { tags: [{media_id, tag_name}, ...] }
// 遍历调 s.store.AddMediaTag(ctx, uid, media_id, tag_name)；底层 INSERT OR IGNORE 幂等。
// 单批最多 500 条。AddMediaTag 返回 nil 不区分"新增"与"忽略已存在"，故导入前先
// 查各媒体的已存标签集合用于判重：命中已存→skipped；否则 AddMediaTag 后计 imported。
// 响应: { status, imported_count, skipped_count, total }
func (s *Server) handleMediaTagImport(w http.ResponseWriter, r *http.Request) {
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
		Tags []struct {
			MediaID string `json:"media_id"`
			TagName string `json:"tag_name"`
		} `json:"tags"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.Tags) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "tags required"})
		return
	}
	if len(req.Tags) > 500 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "max 500 tags per batch"})
		return
	}
	ctx := r.Context()
	imported, skipped := 0, 0
	// 按 media_id 批量预查已存标签集合，避免逐条查库；同一媒体多个标签一次取回。
	mediaIDs := make(map[string]struct{}, len(req.Tags))
	for _, t := range req.Tags {
		mediaIDs[t.MediaID] = struct{}{}
	}
	existing := make(map[string]map[string]struct{}, len(mediaIDs))
	for mid := range mediaIDs {
		tags, err := s.store.ListMediaTags(ctx, uid, mid)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		set := make(map[string]struct{}, len(tags))
		for _, t := range tags {
			set[t] = struct{}{}
		}
		existing[mid] = set
	}
	for _, item := range req.Tags {
		mediaID := strings.TrimSpace(item.MediaID)
		tagName := strings.TrimSpace(item.TagName)
		if mediaID == "" || tagName == "" {
			skipped++
			continue
		}
		// 幂等判重：该媒体已挂此标签则跳过，AddMediaTag 本身 INSERT OR IGNORE 也兜底。
		if set, ok := existing[mediaID]; ok {
			if _, hit := set[tagName]; hit {
				skipped++
				continue
			}
		}
		if err := s.store.AddMediaTag(ctx, uid, mediaID, tagName); err != nil {
			skipped++
			continue
		}
		// 成功插入后登记到本地集合，同批内重复 (media_id,tag_name) 也会被正确计入 skipped。
		if existing[mediaID] == nil {
			existing[mediaID] = make(map[string]struct{})
		}
		existing[mediaID][tagName] = struct{}{}
		imported++
	}
	status := "success"
	if imported == 0 {
		status = "skipped"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":         status,
		"imported_count": imported,
		"skipped_count":  skipped,
		"total":          len(req.Tags),
	})
}

// handleMediaTagExport V8：GET /api/media/tag/export — 导出用户所有标签数据。
// 遍历用户所有标签名，对每个标签查关联的 media_id 列表，汇总为 JSON 导出。
// 响应: { tags: [{tag_name, media_ids: [...], count}], total_tags, total_relations }
// 用于备份/迁移：导出数据可直接喂给 /api/media/tag/import 还原。
func (s *Server) handleMediaTagExport(w http.ResponseWriter, r *http.Request) {
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
	ctx := r.Context()
	tagNames, err := s.store.ListAllTags(ctx, uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	type tagExport struct {
		TagName  string   `json:"tag_name"`
		MediaIDs []string `json:"media_ids"`
		Count    int      `json:"count"`
	}
	tags := make([]tagExport, 0, len(tagNames))
	totalRelations := 0
	for _, name := range tagNames {
		mediaIDs, err := s.store.SearchMediaByTag(ctx, uid, name)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		if mediaIDs == nil {
			mediaIDs = []string{}
		}
		tags = append(tags, tagExport{
			TagName:  name,
			MediaIDs: mediaIDs,
			Count:    len(mediaIDs),
		})
		totalRelations += len(mediaIDs)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"tags":            tags,
		"total_tags":      len(tags),
		"total_relations": totalRelations,
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
		"types": stats,
		"total": len(stats),
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

// handleUploadStreak GET /api/media/upload-streak — 连续上传天数统计。
//
// 类似 GitHub 连续贡献天数：按 created_at 日期分组后计算：
//   - current_streak: 从今天往前连续有上传的天数（今天有上传则含今天）
//   - longest_streak: 历史最长连续天数
//   - total_active_days: 有上传的总天数
//   - last_upload_date: 最近一次上传日期（YYYY-MM-DD，无上传则为空串）
//   - today_count: 今日上传数
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleUploadStreak(w http.ResponseWriter, r *http.Request) {
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

	// 按日期（YYYY-MM-DD）分组，记录每个有上传的日期集合。
	// storage 层 created_at 以 UTC RFC3339 落库（timeToVal → t.UTC()），
	// 行扫描返回 UTC 定位的时间，故此处 day/today 均按 UTC 取，避免与本地时区
	// 错位导致 today_count 与 current_streak 偏一天。
	nowUTC := time.Now().UTC()
	days := make(map[string]bool)
	today := nowUTC.Format("2006-01-02")
	todayCount := 0
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		day := m.CreatedAt.Format("2006-01-02")
		days[day] = true
		if day == today {
			todayCount++
		}
	}

	// 排序日期，便于计算 longest_streak 与 last_upload_date。
	sorted := make([]string, 0, len(days))
	for d := range days {
		sorted = append(sorted, d)
	}
	sort.Strings(sorted)

	// longest_streak：遍历有序日期，统计最长连续段。
	longest := 0
	curRun := 0
	var prev time.Time
	for _, d := range sorted {
		t, err := time.Parse("2006-01-02", d)
		if err != nil {
			continue
		}
		if curRun == 0 || t.Sub(prev) == 24*time.Hour {
			curRun++
			if curRun > longest {
				longest = curRun
			}
		} else {
			curRun = 1
		}
		prev = t
	}

	// current_streak：从今天往前连续有上传的天数。
	// 若今天无上传，则从昨天起算（允许“今天还没传但仍算连续”）。
	current := 0
	cursor, _ := time.Parse("2006-01-02", today)
	if !days[today] {
		cursor = cursor.AddDate(0, 0, -1)
	}
	for days[cursor.Format("2006-01-02")] {
		current++
		cursor = cursor.AddDate(0, 0, -1)
	}

	lastUpload := ""
	if len(sorted) > 0 {
		lastUpload = sorted[len(sorted)-1]
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"current_streak":    current,
		"longest_streak":    longest,
		"total_active_days": len(days),
		"last_upload_date":  lastUpload,
		"today_count":       todayCount,
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
		"source_tag":   req.SourceTag,
		"target_tag":   req.TargetTag,
		"merged_count": count,
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
		"4K":    0,
		"2K":    0,
		"1080p": 0,
		"720p":  0,
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

// handleMediaBySizeRange V8：GET /api/media/by-size-range — 按文件大小范围统计。
// 分档：<1MB / 1-10MB / 10-100MB / >100MB
func (s *Server) handleMediaBySizeRange(w http.ResponseWriter, r *http.Request) {
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
	const mb = 1024 * 1024
	ranges := map[string]int{
		"<1MB":     0,
		"1-10MB":   0,
		"10-100MB": 0,
		">100MB":   0,
	}
	var rangeBytes = map[string]int64{
		"<1MB":     0,
		"1-10MB":   0,
		"10-100MB": 0,
		">100MB":   0,
	}
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		sizeMB := m.Size / mb
		var key string
		switch {
		case sizeMB < 1:
			key = "<1MB"
		case sizeMB < 10:
			key = "1-10MB"
		case sizeMB < 100:
			key = "10-100MB"
		default:
			key = ">100MB"
		}
		ranges[key]++
		rangeBytes[key] += m.Size
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ranges":      ranges,
		"range_bytes": rangeBytes,
		"total":       len(mediaList),
	})
}

// handleMediaAgeDistribution GET /api/media/media-age-distribution — 媒体年龄分布。
//
// 按 created_at（上传时间）到 now 的时间差将所有未软删媒体分入 6 个年龄档：
//
//	<1天 / 1-7天 / 7-30天 / 30-90天 / 90-365天 / >365天
//
// 每档统计 count 与 bytes（累计该档媒体的 Size），并返回总未软删媒体数 total。
// 需认证，按 user_id 隔离；store 未注入返回 503。
//
// 响应结构：
//
//	{
//	  "ranges": [
//	    {"range":"<1天","count":N,"bytes":N},
//	    {"range":"1-7天","count":N,"bytes":N},
//	    {"range":"7-30天","count":N,"bytes":N},
//	    {"range":"30-90天","count":N,"bytes":N},
//	    {"range":"90-365天","count":N,"bytes":N},
//	    {"range":">365天","count":N,"bytes":N}
//	  ],
//	  "total": N  // 参与分桶的未软删媒体总数
//	}
func (s *Server) handleMediaAgeDistribution(w http.ResponseWriter, r *http.Request) {
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

	// 6 个年龄档，顺序固定，count/bytes 预置 0。用切片而非 map 以保证返回顺序。
	now := time.Now()
	type ageBucket struct {
		Range string `json:"range"`
		Count int64  `json:"count"`
		Bytes int64  `json:"bytes"`
	}
	buckets := []ageBucket{
		{Range: "<1天"},
		{Range: "1-7天"},
		{Range: "7-30天"},
		{Range: "30-90天"},
		{Range: "90-365天"},
		{Range: ">365天"},
	}
	var total int64
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		age := now.Sub(m.CreatedAt) // 正值=过去上传；零值 time.Time 会落入 >365天
		var idx int
		switch {
		case age < 24*time.Hour:
			idx = 0
		case age < 7*24*time.Hour:
			idx = 1
		case age < 30*24*time.Hour:
			idx = 2
		case age < 90*24*time.Hour:
			idx = 3
		case age < 365*24*time.Hour:
			idx = 4
		default:
			idx = 5
		}
		buckets[idx].Count++
		buckets[idx].Bytes += m.Size
		total++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ranges": buckets,
		"total":  total,
	})
}

// handleMediaArchiveStatus GET /api/media/media-archive-status — 媒体归档状态（热/温/冷分类）。
//
// 按 created_at（上传时间）到 now 的时间差将所有未软删媒体分入 3 个归档温度档：
//
//	hot  热数据（最近 30 天内上传）
//	warm 温数据（30-180 天内上传）
//	cold 冷数据（上传超过 180 天）
//
// 每档统计 count 与 bytes（累计该档媒体的 Size），并返回总未软删媒体数 total。
// 需认证，按 user_id 隔离；store 未注入返回 503。
//
// 响应结构：
//
//	{
//	  "hot":  {"count":N,"bytes":N},
//	  "warm":{"count":N,"bytes":N},
//	  "cold":{"count":N,"bytes":N},
//	  "total": N  // 参与分类的未软删媒体总数
//	}
func (s *Server) handleMediaArchiveStatus(w http.ResponseWriter, r *http.Request) {
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

	// 3 个归档温度档，count/bytes 预置 0。
	now := time.Now()
	type tier struct {
		Count int64 `json:"count"`
		Bytes int64 `json:"bytes"`
	}
	hot := tier{}
	warm := tier{}
	cold := tier{}
	var total int64
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		age := now.Sub(m.CreatedAt) // 正值=过去上传
		switch {
		case age < 30*24*time.Hour:
			hot.Count++
			hot.Bytes += m.Size
		case age < 180*24*time.Hour:
			warm.Count++
			warm.Bytes += m.Size
		default:
			cold.Count++
			cold.Bytes += m.Size
		}
		total++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"hot":   hot,
		"warm":  warm,
		"cold":  cold,
		"total": total,
	})
}

// handleMediaSyncStatus V8：GET /api/media/sync-status — 返回用户同步状态摘要。
func (s *Server) handleMediaSyncStatus(w http.ResponseWriter, r *http.Request) {
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
	totalCount := 0
	deletedCount := 0
	var totalBytes int64
	var lastUpdate time.Time
	for _, m := range mediaList {
		if m.Deleted {
			deletedCount++
			continue
		}
		totalCount++
		totalBytes += m.Size
		if m.UpdatedAt.After(lastUpdate) {
			lastUpdate = m.UpdatedAt
		}
	}
	var lastUpdateStr string
	if !lastUpdate.IsZero() {
		lastUpdateStr = lastUpdate.Format(time.RFC3339)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"total_media":   totalCount,
		"deleted_media": deletedCount,
		"total_bytes":   totalBytes,
		"last_update":   lastUpdateStr,
		"server_time":   time.Now().Format(time.RFC3339),
	})
}

// handleAlbumAllSummary V8：GET /api/media/album/all-summary — 所有相册的摘要列表。
func (s *Server) handleAlbumAllSummary(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
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
	albums := provider.ListAlbums(uid)
	items := make([]map[string]any, 0, len(albums))
	for _, a := range albums {
		items = append(items, map[string]any{
			"id":             a.ID,
			"name":           a.Name,
			"media_count":    len(a.MediaIDs),
			"cover_media_id": a.CoverMediaID,
			"created_at":     a.CreatedAt,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"albums": items,
		"total":  len(items),
	})
}

// handleMediaCleanupOrphan V8：POST /api/media/cleanup-orphan — 清理孤立记录。
// 扫描 DB 中的媒体记录，将磁盘文件缺失的记录标记为已删除。
func (s *Server) handleMediaCleanupOrphan(w http.ResponseWriter, r *http.Request) {
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
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage not configured"})
		return
	}
	uploadsDir := s.userUploadsDir(uid)
	if uploadsDir == "" {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "uploads dir not configured"})
		return
	}
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	checked := 0
	cleaned := 0
	var cleanedIDs []string
	for _, m := range mediaList {
		if m.Deleted || checked >= 500 {
			continue
		}
		checked++
		pattern := filepath.Join(uploadsDir, m.ID+".*")
		files, _ := filepath.Glob(pattern)
		if len(files) == 0 {
			// 软删除孤立记录
			m.Deleted = true
			if err := s.store.UpdateMedia(r.Context(), m); err == nil {
				cleaned++
				cleanedIDs = append(cleanedIDs, m.ID)
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"checked":       checked,
		"cleaned_count": cleaned,
		"cleaned_ids":   cleanedIDs,
	})
}

// handleAlbumDeleteBatch V8：POST /api/media/album/delete-batch — 批量删除相册。
// 请求体: { album_ids: ["id1","id2"] }
// 仅 owner 可删，返回成功/失败计数。
func (s *Server) handleAlbumDeleteBatch(w http.ResponseWriter, r *http.Request) {
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
		AlbumIDs []string `json:"album_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.AlbumIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_ids required"})
		return
	}
	if len(req.AlbumIDs) > 50 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "max 50 albums per batch"})
		return
	}
	type delResult struct {
		ID    string `json:"album_id"`
		Name  string `json:"name"`
		Error string `json:"error,omitempty"`
	}
	succeeded := make([]delResult, 0)
	failed := make([]batchOpFailure, 0)
	for _, albumID := range req.AlbumIDs {
		album := provider.GetAlbum(uid, albumID)
		if album == nil {
			failed = append(failed, batchOpFailure{ID: albumID, Reason: "not_found"})
			continue
		}
		name := album.Name
		if err := provider.DeleteAlbum(uid, albumID); err != nil {
			failed = append(failed, batchOpFailure{ID: albumID, Reason: "error"})
			continue
		}
		// 级联清理共享记录
		if s.store != nil {
			_ = s.store.DeleteAlbumShare(r.Context(), albumID, uid, "")
		}
		succeeded = append(succeeded, delResult{ID: albumID, Name: name})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"deleted_count": len(succeeded),
		"deleted":       succeeded,
		"failed":        failed,
	})
}

// handleAlbumClone V8：POST /api/media/album/clone — 复制相册。
// 请求体: { source_album_id, new_name }
func (s *Server) handleAlbumClone(w http.ResponseWriter, r *http.Request) {
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
		SourceAlbumID string `json:"source_album_id"`
		NewName       string `json:"new_name"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.SourceAlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source_album_id required"})
		return
	}
	// 获取源相册
	source := provider.GetAlbum(uid, req.SourceAlbumID)
	if source == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "source album not found"})
		return
	}
	// 生成名称
	name := req.NewName
	if name == "" {
		name = source.Name + " (副本)"
	}
	// 创建新相册
	newAlbum, err := provider.CreateAlbum(uid, name)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 批量复制 media_ids
	added := 0
	if len(source.MediaIDs) > 0 {
		added, _ = provider.BatchAddToAlbum(uid, newAlbum.ID, source.MediaIDs)
	}
	// 复制封面
	if source.CoverMediaID != "" {
		_ = provider.SetAlbumCover(uid, newAlbum.ID, source.CoverMediaID)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":         "success",
		"new_album_id":   newAlbum.ID,
		"new_album_name": name,
		"copied_count":   added,
	})
}

// handleAlbumReorder V8：POST /api/media/album/reorder — 调整相册内照片顺序。
// 请求体: { album_id, media_ids: ["id1","id2",...] }
func (s *Server) handleAlbumReorder(w http.ResponseWriter, r *http.Request) {
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
		AlbumID  string   `json:"album_id"`
		MediaIDs []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" || len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id and media_ids required"})
		return
	}
	if err := provider.ReorderAlbumMedia(uid, req.AlbumID, req.MediaIDs); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":    "success",
		"album_id":  req.AlbumID,
		"reordered": len(req.MediaIDs),
	})
}

// handleAlbumMoveMedia V8：POST /api/media/album/move-media — 移动照片到另一相册。
// 请求体: { source_album_id, target_album_id, media_ids: [...] }
// 原子操作：BatchAddToAlbum(target) + BatchRemoveFromAlbum(source)
func (s *Server) handleAlbumMoveMedia(w http.ResponseWriter, r *http.Request) {
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
		SourceAlbumID string   `json:"source_album_id"`
		TargetAlbumID string   `json:"target_album_id"`
		MediaIDs      []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.SourceAlbumID == "" || req.TargetAlbumID == "" || len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source_album_id, target_album_id and media_ids required"})
		return
	}
	if req.SourceAlbumID == req.TargetAlbumID {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source and target must be different"})
		return
	}
	// 校验两个相册都属于当前用户
	if provider.GetAlbum(uid, req.SourceAlbumID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "source album not found"})
		return
	}
	if provider.GetAlbum(uid, req.TargetAlbumID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "target album not found"})
		return
	}
	// 先添加到目标，再从源移除
	added, addErr := provider.BatchAddToAlbum(uid, req.TargetAlbumID, req.MediaIDs)
	if addErr != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": addErr.Error()})
		return
	}
	removed, _ := provider.BatchRemoveFromAlbum(uid, req.SourceAlbumID, req.MediaIDs)
	writeJSON(w, http.StatusOK, map[string]any{
		"status":          "success",
		"moved_count":     removed,
		"added_to_target": added,
	})
}

// handleAlbumCopyMedia V8：POST /api/media/album/copy-media — 跨相册复制照片（source 不删）。
// 请求体: { source_album_id, target_album_id, media_ids: [...] }
// 与 move-media 不同：只 BatchAddToAlbum(target)，不调用 BatchRemoveFromAlbum(source)。
func (s *Server) handleAlbumCopyMedia(w http.ResponseWriter, r *http.Request) {
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
		SourceAlbumID string   `json:"source_album_id"`
		TargetAlbumID string   `json:"target_album_id"`
		MediaIDs      []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.SourceAlbumID == "" || req.TargetAlbumID == "" || len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source_album_id, target_album_id and media_ids required"})
		return
	}
	if req.SourceAlbumID == req.TargetAlbumID {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source and target must be different"})
		return
	}
	// 校验两个相册都属于当前用户
	if provider.GetAlbum(uid, req.SourceAlbumID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "source album not found"})
		return
	}
	if provider.GetAlbum(uid, req.TargetAlbumID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "target album not found"})
		return
	}
	// 只加到 target，source 不删
	copied, err := provider.BatchAddToAlbum(uid, req.TargetAlbumID, req.MediaIDs)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":       "success",
		"copied_count": copied,
	})
}

// handleMediaBatchShare V9：POST /api/media/batch-share — 批量创建分享链接。
//
// 请求体: {"media_ids": ["id1","id2",...]}
// 对每个 media_id 各创建一个独立分享 token（1 个 media → 1 个 share link），
// 简化版：默认不设密码，7 天过期。
//
// 鉴权：走 /api/media/ 前缀（authMiddleware 自动校验），handler 用 userIDFromContext 取 uid。
// 单批上限 50 个 media_ids，防滥用。每个 media_id 均校验格式安全 + 归属当前用户
// （含 deleted=false 检查，复用 handleShareCreate 的越权防护策略）。
//
// 响应: {"links": [{"media_id":..., "token":..., "url":...}], "created_count": N}
// 其中任一 media 创建失败即整体 400/403/500 返回（事务性语义，避免半成品）。
func (s *Server) handleMediaBatchShare(w http.ResponseWriter, r *http.Request) {
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
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "share requires storage backend"})
		return
	}
	var req struct {
		MediaIDs []string `json:"media_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids must not be empty"})
		return
	}
	// 单批最多 50 个（batchShareMaxIDs），比单次分享上限 shareMaxMediaIDs(128) 更保守，
	// 因为批量端点每个 media 各落一条 ShareToken，写放大更显著。
	const batchShareMaxIDs = 50
	if len(req.MediaIDs) > batchShareMaxIDs {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": fmt.Sprintf("too many media_ids (max %d)", batchShareMaxIDs)})
		return
	}
	// 校验每个 media_id 格式安全 + 归属当前用户 + 未软删（与 handleShareCreate 一致）。
	// 任一不合法即整体拒绝——不部分创建，避免向客户端返回混杂的成败结果。
	for _, mid := range req.MediaIDs {
		if mid == "" || strings.Contains(mid, "..") || strings.Contains(mid, "/") {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id in list"})
			return
		}
		m, err := s.store.GetMedia(r.Context(), mid)
		if err != nil {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "media not accessible"})
			return
		}
		if m.UserID != uid || m.Deleted {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "media not accessible"})
			return
		}
	}

	// 默认 7 天过期（简化版：不设密码）。
	const batchShareExpiresDays = 7
	expiresAt := time.Now().Add(time.Duration(batchShareExpiresDays) * 24 * time.Hour)

	type shareLink struct {
		MediaID string `json:"media_id"`
		Token   string `json:"token"`
		URL     string `json:"url"`
	}
	links := make([]shareLink, 0, len(req.MediaIDs))
	for _, mid := range req.MediaIDs {
		// 每个 media 各序列化成单元素 JSON 数组落库，保持与 ShareToken.MediaIDs 字段
		// （JSON 数组字符串）的格式一致，公开访问端点解析逻辑无需改动。
		mediaIDsJSON, err := json.Marshal([]string{mid})
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "marshal media_ids: " + err.Error()})
			return
		}
		st := &storage.ShareToken{
			Token:     generateShareToken(),
			UserID:    uid,
			MediaIDs:  string(mediaIDsJSON),
			ExpiresAt: expiresAt,
		}
		if err := s.store.CreateShareToken(r.Context(), st); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "create share token: " + err.Error()})
			return
		}
		links = append(links, shareLink{
			MediaID: mid,
			Token:   st.Token,
			URL:     "/share/" + st.Token,
		})
	}
	_ = s.store.AddAuditLog(r.Context(), uid, "share", "", fmt.Sprintf("batch created %d share links", len(links)))

	writeJSON(w, http.StatusOK, map[string]any{
		"links":         links,
		"created_count": len(links),
	})
}

// handleShareAnalytics GET /api/media/share-analytics — 分享分析统计。
//
// 聚合当前用户所有分享链接的使用状况，供前端"分享分析"卡片展示：
//   - total             : 分享链接总数
//   - active            : 未过期的分享数（含永不过期 + 过期时间晚于 now）
//   - expired           : 已过期的分享数（过期时间早于 now，永不过期不计入）
//   - password_protected: 设置了密码的分享数
//   - expiring_soon     : 7 天内将过期的分享数（仅算未过期且非永久）
//   - active_percentage: 活跃率 = active/total*100，保留两位小数；total=0 时为 0
//
// 数据来源：store.ListShareTokensByUser。ExpiresAt 零值表示永不过期（计为 active，
// 不计为 expired/expiring_soon）。PasswordHash != "" 即密码保护。查询/扫描失败不致命——
// 总数已确定的情况下返回已有统计，store 错误本身回 500。
func (s *Server) handleShareAnalytics(w http.ResponseWriter, r *http.Request) {
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

	tokens, err := s.store.ListShareTokensByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	total := len(tokens)
	active := 0
	expired := 0
	passwordProtected := 0
	expiringSoon := 0

	now := time.Now().UTC()
	soonCutoff := now.AddDate(0, 0, 7)

	for _, st := range tokens {
		if st.PasswordHash != "" {
			passwordProtected++
		}
		if st.ExpiresAt.IsZero() {
			// 永不过期：算 active，不计 expired 也不计 expiring_soon。
			active++
			continue
		}
		expiresUTC := st.ExpiresAt.UTC()
		if !expiresUTC.Before(now) {
			// 未过期。
			active++
			if !expiresUTC.After(soonCutoff) {
				// 7 天内（含 now~cutoff）将过期。
				expiringSoon++
			}
		} else {
			expired++
		}
	}

	// 保留两位小数。
	var activePct float64
	if total > 0 {
		activePct = float64(active) / float64(total) * 100
		activePct = float64(int(activePct*100+0.5)) / 100 // round2
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"total":              total,
		"active":             active,
		"expired":            expired,
		"password_protected": passwordProtected,
		"expiring_soon":      expiringSoon,
		"active_percentage":  activePct,
		"user_id":            uid,

		// 旧字段别名（与任务规格 total_shares 等对应，兼容前端旧命名口径）：
		"total_shares":   total,
		"active_shares":  active,
		"expired_shares": expired,
	})
}

// handleMediaAutoTag V8：POST /api/media/auto-tag — 按文件名自动打标签。
// 规则：IMG_ → 照片, VID_ → 视频, Screenshot → 截图, color-* → 色卡
// 扫描用户所有未删除媒体，为每个匹配的文件添加对应标签（幂等）。
func (s *Server) handleMediaAutoTag(w http.ResponseWriter, r *http.Request) {
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
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 标签规则：文件名前缀 → 标签名
	rules := []struct {
		prefix  string
		tagName string
	}{
		{"IMG_", "照片"},
		{"VID_", "视频"},
		{"Screenshot", "截图"},
		{"color-", "色卡"},
		{"batch_", "批量重命名"},
	}

	tagged := 0
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		nameUpper := strings.ToUpper(m.Filename)
		for _, rule := range rules {
			if strings.HasPrefix(nameUpper, strings.ToUpper(rule.prefix)) {
				if err := s.store.AddMediaTag(r.Context(), uid, m.ID, rule.tagName); err == nil {
					tagged++
				}
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":       "success",
		"scanned":      len(mediaList),
		"tagged_count": tagged,
		"rules":        len(rules),
	})
}

// rule 为一条标签推荐规则：pattern 匹配文件名（isSub 决定 Contains/HasPrefix），
// 命中则映射到 tag（中文标签名），reason 是推荐理由文案。
type rule struct {
	pattern string
	isSub   bool // true→Contains, false→HasPrefix
	tag     string
	reason  string
}

// tagRecommendation 为单条推荐结果，含标签名、理由、命中媒体数与命中 media_id 列表。
// mediaIds 供 apply 端点遍历 AddMediaTag 用；只读端点不序列化它。
type tagRecommendation struct {
	TagName             string   `json:"tag_name"`
	Reason              string   `json:"reason"`
	SuggestedMediaCount int      `json:"suggested_media_count"`
	mediaIds            []string // 未导出，避免计入 read-only 响应 JSON
}

// computeTagRecommendations 复用的推荐核心逻辑（V22/V23 共用）：
// 扫描用户未删除媒体的 Filename，按常见命名模式（IMG_/VID_/Screenshot/WeChat/camera）
// 映射到中文标签名；若用户已有该标签则跳过。返回推荐列表，每条携带命中的 media_id
// 列表供 apply 端点落库使用。调用方需保证 s.store 非 nil。
func (s *Server) computeTagRecommendations(ctx context.Context, uid string) ([]tagRecommendation, error) {
	// 用户已有标签集合（用于跳过已存在的推荐）。
	existingTags, err := s.store.ListAllTags(ctx, uid)
	if err != nil {
		return nil, fmt.Errorf("list existing tags: %w", err)
	}
	haveTag := make(map[string]bool, len(existingTags))
	for _, t := range existingTags {
		haveTag[t] = true
	}
	// 拉取所有媒体文件名（ListMediaByUser 仅返回 deleted=0 行，已按 created_at DESC 排序）。
	mediaList, err := s.store.ListMediaByUser(ctx, uid)
	if err != nil {
		return nil, fmt.Errorf("list media: %w", err)
	}
	// 推荐规则：匹配模式 → (标签名, 推荐理由)。匹配对文件名与模式都做 ToUpper，
	// 大小写不敏感，与 handleMediaAutoTag 的 prefix 匹配约定一致。
	rules := []rule{
		{"IMG_", false, "照片", "文件名以 IMG_ 开头"},
		{"VID_", false, "视频", "文件名以 VID_ 开头"},
		{"Screenshot", true, "截图", "文件名含 Screenshot"},
		{"WeChat", true, "微信", "文件名含 WeChat"},
		{"camera", true, "相机", "文件名含 camera"},
	}
	// 统计每条规则命中的媒体 id 列表（模式与文件名都 ToUpper 后比较，大小写不敏感）。
	hits := make([][]string, len(rules))
	for _, m := range mediaList {
		nameUpper := strings.ToUpper(m.Filename)
		for i, rl := range rules {
			patUpper := strings.ToUpper(rl.pattern)
			hitted := false
			if rl.isSub {
				hitted = strings.Contains(nameUpper, patUpper)
			} else {
				hitted = strings.HasPrefix(nameUpper, patUpper)
			}
			if hitted {
				hits[i] = append(hits[i], m.ID)
			}
		}
	}
	// 组装推荐列表：跳过用户已有标签或零命中的规则。
	recommendations := make([]tagRecommendation, 0, len(rules))
	for i, rl := range rules {
		if len(hits[i]) == 0 {
			continue
		}
		if haveTag[rl.tag] {
			continue
		}
		recommendations = append(recommendations, tagRecommendation{
			TagName:             rl.tag,
			Reason:              rl.reason,
			SuggestedMediaCount: len(hits[i]),
			mediaIds:            hits[i],
		})
	}
	return recommendations, nil
}

// handleMediaTagRecommendations V22：GET /api/media/tag-recommendations —
// 基于现有标签和文件名模式推荐新标签。只读端点，不修改任何媒体。
//
// 策略：扫描用户所有未删除媒体的 Filename，按常见命名模式（IMG_/VID_/Screenshot/
// WeChat/camera）映射到中文标签名；若用户已有该标签则跳过。返回命中该模式的媒体数量，
// 供前端"建议标签"功能展示并让用户一键采纳。
//
// 响应:
//
//	{
//	  "recommendations": [
//	    {"tag_name":"照片","reason":"文件名以 IMG_ 开头","suggested_media_count":12},
//	    ...
//	  ],
//	  "total": 3
//	}
func (s *Server) handleMediaTagRecommendations(w http.ResponseWriter, r *http.Request) {
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
	recommendations, err := s.computeTagRecommendations(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"recommendations": recommendations,
		"total":           len(recommendations),
	})
}

// handleApplyTagRecommendations V23：POST /api/media/apply-tag-recommendations —
// 一键应用所有标签推荐。复用 computeTagRecommendations 获取推荐，对每条推荐的匹配
// 媒体逐个调 AddMediaTag 落库（INSERT OR IGNORE 幂等，已存在关联不报错），
// 返回已应用的标签-Media 关联总数与每个标签的计数。
//
// 响应:
//
//	{
//	  "status": "success",
//	  "applied_count": 18,
//	  "tags_applied": [
//	    {"tag_name":"照片","count":12},
//	    {"tag_name":"截图","count":3},
//	    ...
//	  ]
//	}
func (s *Server) handleApplyTagRecommendations(w http.ResponseWriter, r *http.Request) {
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
	recommendations, err := s.computeTagRecommendations(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 对每条推荐，遍历其命中的 media_id 逐个 AddMediaTag（幂等，INSERT OR IGNORE），
	// 统计每个标签实际新增的关联计数与累计总数。
	type tagApplied struct {
		TagName string `json:"tag_name"`
		Count   int    `json:"count"`
	}
	tagsApplied := make([]tagApplied, 0, len(recommendations))
	appliedCount := 0
	for _, rec := range recommendations {
		count := 0
		for _, mediaID := range rec.mediaIds {
			if err := s.store.AddMediaTag(r.Context(), uid, mediaID, rec.TagName); err == nil {
				count++
			}
		}
		// 即使某标签全部命中已存在关联（count==0），也保留该条目以告知前端"该标签
		// 已处理"——但零命中通常意味着推荐逻辑已跳过（haveTag 拦截），故此处 count>0。
		tagsApplied = append(tagsApplied, tagApplied{
			TagName: rec.TagName,
			Count:   count,
		})
		appliedCount += count
	}
	if s.store != nil {
		_ = s.store.AddAuditLog(r.Context(), uid, "tag", "",
			fmt.Sprintf("apply-tag-recommendations: applied %d associations across %d tags", appliedCount, len(tagsApplied)))
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"applied_count": appliedCount,
		"tags_applied":  tagsApplied,
	})
}

// handleAuditLogList V8：GET /api/media/audit-log/list?limit=50
// 返回当前用户最近的审计日志（最近在前），limit<=0 默认 50。
func (s *Server) handleAuditLogList(w http.ResponseWriter, r *http.Request) {
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
	limit := 50
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = n
		}
	}
	logs, err := s.store.ListAuditLogs(r.Context(), uid, limit)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	out := make([]map[string]any, 0, len(logs))
	for _, a := range logs {
		out = append(out, map[string]any{
			"id":         a.ID,
			"action":     a.Action,
			"media_id":   a.MediaID,
			"detail":     a.Detail,
			"created_at": a.CreatedAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"logs":  out,
		"total": len(out),
	})
}

// handleAuditLogStats V8：GET /api/media/audit-log/stats
// 按操作类型聚合当前用户的审计日志数量：{stats: [{action,count}], total: N}
func (s *Server) handleAuditLogStats(w http.ResponseWriter, r *http.Request) {
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
	stats, err := s.store.AuditLogStats(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"stats": stats,
		"total": len(stats),
	})
}

// handleAuditLogRecord V8：POST /api/media/audit-log/record
// 请求体: {action, media_id, detail}。调 AddAuditLog 写入一条审计日志。
func (s *Server) handleAuditLogRecord(w http.ResponseWriter, r *http.Request) {
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
		Action  string `json:"action"`
		MediaID string `json:"media_id"`
		Detail  string `json:"detail"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.Action == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "action required"})
		return
	}
	if err := s.store.AddAuditLog(r.Context(), uid, req.Action, req.MediaID, req.Detail); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success"})
}

// handleAuditLogByMedia GET /api/media/audit-log/by-media?media_id=xxx
// 返回指定媒体的操作历史（最近在前）。响应: {logs: [...], total: N}。
// media_id 缺失返回 400；未登录返回 401；store 不可用返回 503。
func (s *Server) handleAuditLogByMedia(w http.ResponseWriter, r *http.Request) {
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
	mediaID := r.URL.Query().Get("media_id")
	if mediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_id required"})
		return
	}
	logs, err := s.store.ListAuditLogsByMedia(r.Context(), uid, mediaID)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	out := make([]map[string]any, 0, len(logs))
	for _, a := range logs {
		out = append(out, map[string]any{
			"id":         a.ID,
			"action":     a.Action,
			"media_id":   a.MediaID,
			"detail":     a.Detail,
			"created_at": a.CreatedAt.Format(time.RFC3339),
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"logs":  out,
		"total": len(out),
	})
}

// handleMediaActivityFeed GET /api/media/activity-feed?limit=20
// 统一活动流：合并审计日志（最近所有操作）与最近上传（前 5 个 media），
// 按时间倒序返回统一时间线。响应: {feed: [{type,action,detail,media_id,timestamp}], total}。
//
// 数据来源：
//   - 审计日志来自 s.store.ListAuditLogs（取 ?limit 条，最近在前，已按 created_at DESC）。
//   - 最近上传来自 s.store.ListMediaByUser（按 created_at DESC，取前 5 个未软删 media）。
//
// 合并后按 timestamp（unix 秒）倒序排序，截断到 limit。两条时间戳相同的记录顺序不特定
// （sort.Slice 非稳定排序，但活动流场景对同秒事件的相对顺序无语义要求）。
//
// 未登录返回 401；store 不可用返回 503；limit<=0 默认 20。
func (s *Server) handleMediaActivityFeed(w http.ResponseWriter, r *http.Request) {
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
	limit := 20
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := strconv.Atoi(v); err == nil && n > 0 {
			limit = n
		}
	}

	type feedItem struct {
		Type      string `json:"type"`      // "audit" | "upload"
		Action    string `json:"action"`    // upload / share / album / favorite / delete / ...
		Detail    string `json:"detail"`    // 人类可读描述
		MediaID   string `json:"media_id"`  // 可空（非媒体级操作为空）
		Timestamp int64  `json:"timestamp"` // unix 秒
	}

	feed := make([]feedItem, 0, limit+5)

	// 1. 审计日志（最近操作，已按 created_at DESC）
	logs, err := s.store.ListAuditLogs(r.Context(), uid, limit)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	for _, a := range logs {
		detail := a.Detail
		if detail == "" {
			detail = a.Action
		}
		feed = append(feed, feedItem{
			Type:      "audit",
			Action:    a.Action,
			Detail:    detail,
			MediaID:   a.MediaID,
			Timestamp: a.CreatedAt.Unix(),
		})
	}

	// 2. 最近上传（前 5 个未软删 media，作为 upload 类型活动）
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err == nil {
		count := 0
		for _, m := range mediaList {
			if m.Deleted {
				continue
			}
			feed = append(feed, feedItem{
				Type:      "upload",
				Action:    "upload",
				Detail:    "上传了 " + m.Filename,
				MediaID:   m.ID,
				Timestamp: m.CreatedAt.Unix(),
			})
			count++
			if count >= 5 {
				break
			}
		}
	}

	// 合并后按时间倒序排序
	sort.Slice(feed, func(i, j int) bool {
		return feed[i].Timestamp > feed[j].Timestamp
	})

	// 截断到 limit
	if len(feed) > limit {
		feed = feed[:limit]
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"feed":  feed,
		"total": len(feed),
	})
}

// handleMediaSearchHistory V17：GET /api/media/search-history
// 返回用户搜索历史。设计为从 audit_log 中过滤 action="search" 的记录；
// 但当前搜索端点尚未埋点（无 search 类 audit log 写入），若过滤结果为空，
// 则回退返回全部 audit log 作为"最近操作历史"，保证端点可用。
//
// 响应:
//
//	有 search 记录时: {history: [{id, detail, created_at}], total}
//	无 search 记录时: {history: [{id, action, detail, created_at}], total}
//
// 永远取最近 20 条（ListAuditLogs limit=20）。未登录 401；store 不可用 503。
func (s *Server) handleMediaSearchHistory(w http.ResponseWriter, r *http.Request) {
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
	logs, err := s.store.ListAuditLogs(r.Context(), uid, 20)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 先尝试过滤 action="search" 的记录。
	var searchLogs []*storage.AuditLog
	for _, a := range logs {
		if a.Action == "search" {
			searchLogs = append(searchLogs, a)
		}
	}

	out := make([]map[string]any, 0, len(logs))
	if len(searchLogs) > 0 {
		// 有 search 类埋点：只返回搜索记录（id/detail/created_at）。
		for _, a := range searchLogs {
			out = append(out, map[string]any{
				"id":         a.ID,
				"detail":     a.Detail,
				"created_at": a.CreatedAt.Format(time.RFC3339),
			})
		}
	} else {
		// 无 search 埋点：回退返回全部 audit log 作为"最近操作历史"，
		// 额外带 action 字段以便前端区分操作类型。
		for _, a := range logs {
			out = append(out, map[string]any{
				"id":         a.ID,
				"action":     a.Action,
				"detail":     a.Detail,
				"created_at": a.CreatedAt.Format(time.RFC3339),
			})
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"history": out,
		"total":   len(out),
	})
}

// handleMediaQueryStats V19：GET /api/media/media-query-stats — 搜索查询统计。
// 返回搜索关键词频率（热词 top10）+ 最近7天每天的搜索次数趋势。
//
// 数据来源口径（与 handleMediaSearchHistory 一致）：
//   - 首选：audit_log 中 action="search" 的记录，detail 字段视为搜索关键词。
//   - 回退：当前搜索端点尚未埋点（无 search 类 audit log）。此时分析全部
//     audit log 的 detail 字段，按空格切分取非空 token 作为"关键词"近似，
//     以保证端点可用（返回基于操作详情的词频，而非空）。
//   - 全无记录：返回 total_searches=0 + 空列表。
//
// 响应:
//
//	{
//	  total_searches: N,
//	  top_keywords:   [{keyword, count}, ...],   // top 10，count 降序
//	  search_trend:   [{date, count}, ...]        // 最近7天（含今天），日期升序
//	}
//
// 未登录 401；store 不可用 503。取最近 5000 条 audit log（ListAuditLogs limit）。
func (s *Server) handleMediaQueryStats(w http.ResponseWriter, r *http.Request) {
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
	logs, err := s.store.ListAuditLogs(r.Context(), uid, 5000)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 收集搜索记录：优先 action="search"，回退分析全部 detail。
	type searchEntry struct {
		detail string
		when   time.Time
	}
	var entries []searchEntry
	for _, a := range logs {
		if a.Action == "search" {
			entries = append(entries, searchEntry{detail: a.Detail, when: a.CreatedAt})
		}
	}
	fallback := len(entries) == 0
	if fallback {
		// 无 search 埋点：用全部 audit log 的 detail 字段近似。
		for _, a := range logs {
			if strings.TrimSpace(a.Detail) == "" {
				continue
			}
			entries = append(entries, searchEntry{detail: a.Detail, when: a.CreatedAt})
		}
	}

	// 关键词计数。search 类记录：detail 整体作为一个关键词。
	// 回退模式：detail 按空格切分，每个非空 token 作为一个关键词。
	keywordCount := make(map[string]int)
	for _, e := range entries {
		kw := strings.TrimSpace(e.detail)
		if kw == "" {
			continue
		}
		if fallback {
			for _, tok := range strings.Fields(kw) {
				keywordCount[tok]++
			}
		} else {
			keywordCount[kw]++
		}
	}

	// top 10 关键词（count 降序，同 count 按关键词字母序稳定排序）。
	type kwEntry struct {
		Keyword string `json:"keyword"`
		Count   int    `json:"count"`
	}
	topKeywords := make([]kwEntry, 0, len(keywordCount))
	for kw, c := range keywordCount {
		topKeywords = append(topKeywords, kwEntry{Keyword: kw, Count: c})
	}
	sort.Slice(topKeywords, func(i, j int) bool {
		if topKeywords[i].Count != topKeywords[j].Count {
			return topKeywords[i].Count > topKeywords[j].Count
		}
		return topKeywords[i].Keyword < topKeywords[j].Keyword
	})
	if len(topKeywords) > 10 {
		topKeywords = topKeywords[:10]
	}

	// 最近7天趋势（含今天，UTC 日期，升序）。无记录的日期 count=0。
	now := time.Now().UTC()
	todayStart := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC)
	dayStart := todayStart.AddDate(0, 0, -6) // 7 天窗口：[today-6, today]
	type trendEntry struct {
		Date  string `json:"date"`
		Count int    `json:"count"`
	}
	trend := make([]trendEntry, 0, 7)
	for i := 0; i < 7; i++ {
		ds := dayStart.AddDate(0, 0, i)
		trend = append(trend, trendEntry{Date: ds.Format("2006-01-02"), Count: 0})
	}
	// trend[i] 对应 [dayStart+i, dayStart+i+1)。
	for _, e := range entries {
		d := e.when.UTC()
		ds := time.Date(d.Year(), d.Month(), d.Day(), 0, 0, 0, 0, time.UTC)
		if ds.Before(dayStart) {
			continue // 超出7天窗口
		}
		idx := int(ds.Sub(dayStart).Hours() / 24)
		if idx < 0 || idx >= len(trend) {
			continue
		}
		trend[idx].Count++
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"total_searches": len(entries),
		"top_keywords":   topKeywords,
		"search_trend":   trend,
	})
}

// handleAlbumMerge V8：POST /api/media/album/merge — 合并两个相册。
// 请求体: { source_album_id, target_album_id }
// 把 source 的所有 media 移到 target，然后删除 source 相册。
func (s *Server) handleAlbumMerge(w http.ResponseWriter, r *http.Request) {
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
		SourceAlbumID string `json:"source_album_id"`
		TargetAlbumID string `json:"target_album_id"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.SourceAlbumID == "" || req.TargetAlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source_album_id and target_album_id required"})
		return
	}
	if req.SourceAlbumID == req.TargetAlbumID {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "source and target must be different"})
		return
	}
	source := provider.GetAlbum(uid, req.SourceAlbumID)
	if source == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "source album not found"})
		return
	}
	if provider.GetAlbum(uid, req.TargetAlbumID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "target album not found"})
		return
	}
	// 把 source 的 media 全部加到 target（幂等）
	added, addErr := provider.BatchAddToAlbum(uid, req.TargetAlbumID, source.MediaIDs)
	if addErr != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": addErr.Error()})
		return
	}
	// 删除 source 相册
	_ = provider.DeleteAlbum(uid, req.SourceAlbumID)
	// 级联清理 source 的共享记录
	if s.store != nil {
		_ = s.store.DeleteAlbumShare(r.Context(), req.SourceAlbumID, uid, "")
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":          "success",
		"merged_count":    added,
		"target_album_id": req.TargetAlbumID,
		"deleted_source":  req.SourceAlbumID,
	})
}

// handleAlbumBatchMerge V18：POST /api/media/album/batch-merge — 批量合并多个相册到第一个。
// 请求体: { album_ids: ["id1","id2","id3"] }
// album_ids[0] 是目标相册，album_ids[1:] 是源相册。
// 逐个把源相册的 MediaIDs BatchAddToAlbum 到目标，然后删除源相册。
// 返回 { status, merged_count, target_album_id, deleted_sources: [...] }
func (s *Server) handleAlbumBatchMerge(w http.ResponseWriter, r *http.Request) {
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
		AlbumIDs []string `json:"album_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.AlbumIDs) < 2 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_ids must contain at least 2 ids"})
		return
	}
	targetID := req.AlbumIDs[0]
	if provider.GetAlbum(uid, targetID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "target album not found"})
		return
	}
	// 去重 & 校验源相册 id 不能等于目标
	seen := make(map[string]bool, len(req.AlbumIDs))
	seen[targetID] = true
	var sourceIDs []string
	for _, id := range req.AlbumIDs[1:] {
		if id == "" || id == targetID || seen[id] {
			continue
		}
		seen[id] = true
		sourceIDs = append(sourceIDs, id)
	}
	if len(sourceIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "no valid source albums to merge"})
		return
	}

	mergedCount := 0
	deletedSources := make([]string, 0, len(sourceIDs))
	failedSources := make([]string, 0)
	for _, srcID := range sourceIDs {
		source := provider.GetAlbum(uid, srcID)
		if source == nil {
			// 源相册不存在，跳过（不中断整体流程）
			failedSources = append(failedSources, srcID)
			continue
		}
		if len(source.MediaIDs) > 0 {
			added, addErr := provider.BatchAddToAlbum(uid, targetID, source.MediaIDs)
			if addErr != nil {
				writeJSON(w, http.StatusInternalServerError, map[string]any{
					"error":           addErr.Error(),
					"failed_source":   srcID,
					"target_album_id": targetID,
				})
				return
			}
			mergedCount += added
		}
		// 删除源相册
		_ = provider.DeleteAlbum(uid, srcID)
		// 级联清理源相册共享记录
		if s.store != nil {
			_ = s.store.DeleteAlbumShare(r.Context(), srcID, uid, "")
		}
		deletedSources = append(deletedSources, srcID)
	}

	resp := map[string]any{
		"status":          "success",
		"merged_count":    mergedCount,
		"target_album_id": targetID,
		"deleted_sources": deletedSources,
	}
	if len(failedSources) > 0 {
		resp["failed_sources"] = failedSources // 源相册不存在被跳过时列出
	}
	writeJSON(w, http.StatusOK, resp)
}

// handleAlbumAutoCover V8：POST /api/media/album/auto-cover — 自动设置相册封面。
// 请求体: { album_id }
// 如果相册没有封面，用第一个 media 作为封面。
func (s *Server) handleAlbumAutoCover(w http.ResponseWriter, r *http.Request) {
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
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id required"})
		return
	}
	album := provider.GetAlbum(uid, req.AlbumID)
	if album == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
		return
	}
	// 已有封面则不覆盖
	if album.CoverMediaID != "" {
		writeJSON(w, http.StatusOK, map[string]any{
			"status":         "already_set",
			"cover_media_id": album.CoverMediaID,
		})
		return
	}
	// 用第一个 media 作为封面
	if len(album.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album is empty"})
		return
	}
	coverID := album.MediaIDs[0]
	if err := provider.SetAlbumCover(uid, req.AlbumID, coverID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":         "success",
		"cover_media_id": coverID,
	})
}

// handleAlbumSortByDate V8：POST /api/media/album/sort-by-date — 按日期排序相册内媒体。
// 请求体: { album_id, order: "asc"|"desc" }（默认 desc）
func (s *Server) handleAlbumSortByDate(w http.ResponseWriter, r *http.Request) {
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
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album not supported"})
		return
	}
	var req struct {
		AlbumID string `json:"album_id"`
		Order   string `json:"order"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if req.AlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id required"})
		return
	}
	album := provider.GetAlbum(uid, req.AlbumID)
	if album == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
		return
	}
	if len(album.MediaIDs) == 0 {
		writeJSON(w, http.StatusOK, map[string]any{"status": "success", "reordered": 0})
		return
	}

	// 获取每个 media 的 created_at，排序
	type mediaTime struct {
		ID    string
		CTime time.Time
	}
	var items []mediaTime
	for _, mid := range album.MediaIDs {
		m, err := s.store.GetMedia(r.Context(), mid)
		if err != nil || m == nil || m.Deleted {
			continue
		}
		items = append(items, mediaTime{ID: mid, CTime: m.CreatedAt})
	}
	// 排序
	descending := req.Order != "asc"
	sort.Slice(items, func(i, j int) bool {
		if descending {
			return items[i].CTime.After(items[j].CTime)
		}
		return items[i].CTime.Before(items[j].CTime)
	})
	// 构建新顺序
	newOrder := make([]string, len(items))
	for i, it := range items {
		newOrder[i] = it.ID
	}
	if err := provider.ReorderAlbumMedia(uid, req.AlbumID, newOrder); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":    "success",
		"order":     req.Order,
		"reordered": len(newOrder),
	})
}

// handleAlbumBatchSort V16：POST /api/media/album/batch-sort — 批量按日期排序
// 多个相册内含照片。请求体: { "album_ids": ["id1","id2",...], "order": "asc"|"desc" }（默认 desc）。
// 对每个相册复用 sort-by-date 逻辑（GetAlbum → 获取 media created_at → 排序 → ReorderAlbumMedia）。
// 跳过不存在/非当前用户所有/无媒体的相册；store 不可用时返回 503（排序依赖 created_at 时间戳）。
// 返回 { "status": "success", "sorted_count": N, "skipped_count": M }。
func (s *Server) handleAlbumBatchSort(w http.ResponseWriter, r *http.Request) {
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
	provider, ok := s.mediaSvc.(albumStoreProvider)
	if !ok {
		writeJSON(w, http.StatusNotImplemented, map[string]any{"error": "album not supported"})
		return
	}
	var req struct {
		AlbumIDs []string `json:"album_ids"`
		Order    string   `json:"order"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.AlbumIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_ids is required"})
		return
	}
	descending := req.Order != "asc"
	var sorted, skipped int
	for _, albumID := range req.AlbumIDs {
		// 校验归属并跳过无媒体相册，不中断整体流程。
		album := provider.GetAlbum(uid, albumID)
		if album == nil || len(album.MediaIDs) == 0 {
			skipped++
			continue
		}
		// 获取每个 media 的 created_at，跳过已删除/缺失元数据的项。
		type mediaTime struct {
			ID    string
			CTime time.Time
		}
		var items []mediaTime
		for _, mid := range album.MediaIDs {
			m, err := s.store.GetMedia(r.Context(), mid)
			if err != nil || m == nil || m.Deleted {
				continue
			}
			items = append(items, mediaTime{ID: mid, CTime: m.CreatedAt})
		}
		if len(items) == 0 {
			skipped++
			continue
		}
		// 按日期排序
		sort.Slice(items, func(i, j int) bool {
			if descending {
				return items[i].CTime.After(items[j].CTime)
			}
			return items[i].CTime.Before(items[j].CTime)
		})
		newOrder := make([]string, len(items))
		for i, it := range items {
			newOrder[i] = it.ID
		}
		if err := provider.ReorderAlbumMedia(uid, albumID, newOrder); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"error":    err.Error(),
				"album_id": albumID,
			})
			return
		}
		sorted++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"sorted_count":  sorted,
		"skipped_count": skipped,
	})
}

// handleAlbumPin V9：POST /api/media/album/pin — 置顶相册。
// 请求体: { "album_id": "x" } → PinAlbum → { "status": "success", "album_id": "x" }
func (s *Server) handleAlbumPin(w http.ResponseWriter, r *http.Request) {
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
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.AlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id is required"})
		return
	}
	// 先校验相册归属，避免对不存在/非己有的相册操作
	if provider.GetAlbum(uid, req.AlbumID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
		return
	}
	if err := provider.PinAlbum(uid, req.AlbumID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "album_id": req.AlbumID})
}

// handleAlbumUnpin V9：POST /api/media/album/unpin — 取消相册置顶。
// 请求体: { "album_id": "x" } → UnpinAlbum → { "status": "success", "album_id": "x" }
func (s *Server) handleAlbumUnpin(w http.ResponseWriter, r *http.Request) {
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
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if req.AlbumID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_id is required"})
		return
	}
	if provider.GetAlbum(uid, req.AlbumID) == nil {
		writeJSON(w, http.StatusNotFound, map[string]any{"error": "album not found"})
		return
	}
	if err := provider.UnpinAlbum(uid, req.AlbumID); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "success", "album_id": req.AlbumID})
}

// handleAlbumPinned V9：GET /api/media/album/pinned — 列出当前用户置顶的相册。
// 返回 { "albums": [...], "count": N }
func (s *Server) handleAlbumPinned(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
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
	albums, err := provider.ListPinnedAlbums(uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	if albums == nil {
		albums = []*service.Album{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"albums": albums, "count": len(albums)})
}

// handleAlbumBatchPin V9：POST /api/media/album/batch-pin — 批量置顶多个相册。
// 请求体: { "album_ids": ["id1","id2",...] } → 逐个 PinAlbum →
// { "status": "success", "pinned_count": N, "skipped_count": M }
// 对不存在/非当前用户所属的相册跳过（GetAlbum 校验归属），不中断整体流程。
func (s *Server) handleAlbumBatchPin(w http.ResponseWriter, r *http.Request) {
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
		AlbumIDs []string `json:"album_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.AlbumIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_ids is required"})
		return
	}
	pinned, skipped := 0, 0
	for _, albumID := range req.AlbumIDs {
		// 先校验相册归属，跳过不存在/非当前用户所有的相册，不中断整体流程。
		if provider.GetAlbum(uid, albumID) == nil {
			skipped++
			continue
		}
		if err := provider.PinAlbum(uid, albumID); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"error":    err.Error(),
				"album_id": albumID,
			})
			return
		}
		pinned++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"pinned_count":  pinned,
		"skipped_count": skipped,
	})
}

// handleAlbumBatchUnpin V9：POST /api/media/album/batch-unpin — 批量取消置顶多个相册。
// 请求体: { "album_ids": ["id1","id2",...] } → 逐个 UnpinAlbum →
// { "status": "success", "unpinned_count": N, "skipped_count": M }
// 对不存在/非当前用户所属的相册跳过（GetAlbum 校验归属），不中断整体流程。
func (s *Server) handleAlbumBatchUnpin(w http.ResponseWriter, r *http.Request) {
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
		AlbumIDs []string `json:"album_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.AlbumIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_ids is required"})
		return
	}
	unpinned, skipped := 0, 0
	for _, albumID := range req.AlbumIDs {
		// 先校验相册归属，跳过不存在/非当前用户所有的相册，不中断整体流程。
		if provider.GetAlbum(uid, albumID) == nil {
			skipped++
			continue
		}
		if err := provider.UnpinAlbum(uid, albumID); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"error":    err.Error(),
				"album_id": albumID,
			})
			return
		}
		unpinned++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":         "success",
		"unpinned_count": unpinned,
		"skipped_count":  skipped,
	})
}

// handleAlbumBatchClone V9：POST /api/media/album/batch-clone — 批量克隆多个相册。
// 请求体: { "album_ids": ["id1","id2",...], "suffix": "(副本)"（可选，默认 "(副本)"） }
// 逐个 GetAlbum（校验归属，跳过不存在的）+ CreateAlbum + BatchAddToAlbum 复制 media +
// SetAlbumCover 复制封面。返回 { "status","cloned_count","skipped_count","new_album_ids" }。
// 单个相册克隆出错不中断整体流程，记录到 failed_album_ids 并继续。
func (s *Server) handleAlbumBatchClone(w http.ResponseWriter, r *http.Request) {
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
		AlbumIDs []string `json:"album_ids"`
		Suffix   string   `json:"suffix"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.AlbumIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_ids is required"})
		return
	}
	suffix := req.Suffix
	if suffix == "" {
		suffix = "(副本)"
	}
	cloned, skipped := 0, 0
	newIDs := make([]string, 0, len(req.AlbumIDs))
	failed := make([]string, 0)
	for _, albumID := range req.AlbumIDs {
		// 获取源相册并校验归属；不存在/非当前用户所有则跳过，不中断整体流程。
		source := provider.GetAlbum(uid, albumID)
		if source == nil {
			skipped++
			continue
		}
		name := source.Name + " " + suffix
		newAlbum, err := provider.CreateAlbum(uid, name)
		if err != nil {
			failed = append(failed, albumID)
			continue
		}
		// 批量复制 media_ids（错误不计入失败，仅影响 copied 计数；新相册已创建成功）。
		if len(source.MediaIDs) > 0 {
			_, _ = provider.BatchAddToAlbum(uid, newAlbum.ID, source.MediaIDs)
		}
		// 复制封面（可选，忽略错误）。
		if source.CoverMediaID != "" {
			_ = provider.SetAlbumCover(uid, newAlbum.ID, source.CoverMediaID)
		}
		newIDs = append(newIDs, newAlbum.ID)
		cloned++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":           "success",
		"cloned_count":     cloned,
		"skipped_count":    skipped,
		"new_album_ids":    newIDs,
		"failed_album_ids": failed,
	})
}

// handleAlbumCountRanking V14：GET /api/media/album/count-ranking — 按相册内媒体数量
// 倒序排列，返回哪些相册照片/视频最多。无请求体。
// 响应: { "ranking": [{ "album_id","name","count","cover_media_id" }], "total_albums": N }。
func (s *Server) handleAlbumCountRanking(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
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
	albums := provider.ListAlbums(uid)
	type albumCount struct {
		AlbumID      string `json:"album_id"`
		Name         string `json:"name"`
		Count        int    `json:"count"`
		CoverMediaID string `json:"cover_media_id"`
	}
	ranking := make([]albumCount, 0, len(albums))
	for _, a := range albums {
		ranking = append(ranking, albumCount{
			AlbumID:      a.ID,
			Name:         a.Name,
			Count:        len(a.MediaIDs),
			CoverMediaID: a.CoverMediaID,
		})
	}
	sort.Slice(ranking, func(i, j int) bool {
		return ranking[i].Count > ranking[j].Count
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"ranking":      ranking,
		"total_albums": len(albums),
	})
}

// handleAlbumStatsSummary V19：GET /api/media/album/stats-summary — 相册统计摘要。
// 汇总当前用户全部相册：总相册数、总照片数（所有相册 media_ids 之和）、平均每相册
// 照片数、媒体数最多的相册与最少的相册（仅 id+name+count）。
//
// 设计取舍：
//   - 与 count-ranking 的区别：count-ranking 返回全部相册的完整排行列表，本端点只
//     返回聚合数字 + 极值相册，面向首页/概览卡片，载荷小、前端无需再排序取极值。
//   - total_media 为各相册 MediaIDs 长度之和；同一 media 被多相册收录时会重复计数，
//     这与 count-ranking、“all-summary 的 media_count 之和”口径一致，均为相册视图下的
//     “相册内照片数”而非去重后的“媒体库照片数”，避免本端点为去重额外拉全量媒体。
//   - avg_per_album 计算到小数（float64）；无相册时为 0，max/min_album 为 nil。
func (s *Server) handleAlbumStatsSummary(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
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
	albums := provider.ListAlbums(uid)

	type albumExt struct {
		ID    string `json:"id"`
		Name  string `json:"name"`
		Count int    `json:"count"`
	}

	totalAlbums := len(albums)
	totalMedia := 0
	var maxAlbum, minAlbum *albumExt
	for _, a := range albums {
		count := len(a.MediaIDs)
		totalMedia += count
		cur := &albumExt{ID: a.ID, Name: a.Name, Count: count}
		if maxAlbum == nil || count > maxAlbum.Count {
			maxAlbum = cur
		}
		if minAlbum == nil || count < minAlbum.Count {
			minAlbum = cur
		}
	}

	var avgPerAlbum float64
	if totalAlbums > 0 {
		avgPerAlbum = float64(totalMedia) / float64(totalAlbums)
	}

	// max/minAlbum 为指针，无相册时为 nil；直接放入 map 让 JSON 编码为 null。
	writeJSON(w, http.StatusOK, map[string]any{
		"total_albums":  totalAlbums,
		"total_media":   totalMedia,
		"avg_per_album": avgPerAlbum,
		"max_album":     maxAlbum,
		"min_album":     minAlbum,
	})
}

// handleAlbumActivity V15：GET /api/media/album/activity — 相册活动时间线。
// 遍历当前用户所有相册，对每个相册取其成员媒体中最新的 created_at（上传时间）
// 作为该相册的"最近活动时间"，按该时间倒序返回。
//
// 设计取舍：
//   - 相册本身无"最后修改时间"字段（Album 仅记录 CreatedAt），故用其成员媒体的
//     created_at 上界近似"最近一次有内容流入相册的时间"。media 的 created_at 是
//     上传落库时间，由 store 统一维护，比 audit_log（需显式 record 才有数据、且
//     album 增删操作当前并未自动写审计）更可靠且无需额外存储。
//   - 只做一次 ListMediaByUser（按 created_at 降序），在内存里建 mediaID→createdAt
//     映射后逐相册扫描取最大值，避免对每个相册逐条 GetMedia 打 N 次 DB。
//
// 响应:
//
//	{
//	  "activities": [
//	    {"album_id","name","count","last_activity"}
//	  ],
//	  "total": N
//	}
//
// 其中 last_activity 为 RFC3339 字符串；相册无成员媒体时为空串（排序时视作最旧）。
// store 不可用时回退到相册 CreatedAt（Unix 秒），保证端点在无元数据库场景仍可用。
func (s *Server) handleAlbumActivity(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
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
	albums := provider.ListAlbums(uid)

	// 一次性拉取用户全部媒体，建立 mediaID → created_at 映射。store 不可用或拉取
	// 失败时 mediaTS 为空 map，后续回退到相册自身 CreatedAt，端点不会因此报错。
	mediaTS := make(map[string]time.Time, 0)
	if s.store != nil {
		if mediaList, err := s.store.ListMediaByUser(r.Context(), uid); err == nil {
			for _, m := range mediaList {
				mediaTS[m.ID] = m.CreatedAt
			}
		}
	}

	type albumActivity struct {
		AlbumID      string `json:"album_id"`
		Name         string `json:"name"`
		Count        int    `json:"count"`
		LastActivity string `json:"last_activity"`
	}

	activities := make([]albumActivity, 0, len(albums))
	for _, a := range albums {
		// 取该相册所有成员媒体中最新的 created_at；无成员或媒体元数据缺失时
		// 回退到相册自身创建时间（Unix 秒 → time.Time）。
		var latest time.Time
		for _, mid := range a.MediaIDs {
			if t, ok := mediaTS[mid]; ok && !t.IsZero() {
				if t.After(latest) {
					latest = t
				}
			}
		}
		lastStr := ""
		if !latest.IsZero() {
			lastStr = latest.Format(time.RFC3339)
		} else if a.CreatedAt > 0 {
			// 无成员媒体时间可参照时，用相册创建时间作回退（至少反映相册何时建立）。
			lastStr = time.Unix(a.CreatedAt, 0).Format(time.RFC3339)
		}
		activities = append(activities, albumActivity{
			AlbumID:      a.ID,
			Name:         a.Name,
			Count:        len(a.MediaIDs),
			LastActivity: lastStr,
		})
	}

	// 按 last_activity 倒序（空串视作最旧，排在最后）。
	sort.SliceStable(activities, func(i, j int) bool {
		return activities[i].LastActivity > activities[j].LastActivity
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"activities": activities,
		"total":      len(activities),
	})
}

// handleAlbumAutoCoverAll V8：POST /api/media/album/auto-cover-all — 批量给
// 当前用户所有无封面相册自动设封面（用每个相册的第一个 media）。
// 无请求体。跳过已有封面或空相册。返回 { status, updated_count, total_albums }。
func (s *Server) handleAlbumAutoCoverAll(w http.ResponseWriter, r *http.Request) {
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
	albums := provider.ListAlbums(uid)
	total := len(albums)
	updated := 0
	for _, album := range albums {
		// 已有封面则跳过
		if album.CoverMediaID != "" {
			continue
		}
		// 空相册无法设封面
		if len(album.MediaIDs) == 0 {
			continue
		}
		if err := provider.SetAlbumCover(uid, album.ID, album.MediaIDs[0]); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"error":    err.Error(),
				"album_id": album.ID,
			})
			return
		}
		updated++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"updated_count": updated,
		"total_albums":  total,
	})
}

// handleAlbumBatchSetCover V12：POST /api/media/album/batch-set-cover — 批量强制
// 设置多个相册的封面。请求体: { album_ids: ["id1","id2"] }。与 auto-cover-all 不同：
// batch-set-cover 接受显式 album_ids 列表，且即使已有封面也覆盖；空相册跳过。
// 返回 { status, updated_count, skipped_count }。
func (s *Server) handleAlbumBatchSetCover(w http.ResponseWriter, r *http.Request) {
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
		AlbumIDs []string `json:"album_ids"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	if len(req.AlbumIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "album_ids required"})
		return
	}
	updated, skipped := 0, 0
	for _, albumID := range req.AlbumIDs {
		album := provider.GetAlbum(uid, albumID)
		if album == nil {
			// 相册不存在或不属于当前用户 — 跳过，不中断整体流程。
			skipped++
			continue
		}
		// 空相册无法设封面 — 跳过。
		if len(album.MediaIDs) == 0 {
			skipped++
			continue
		}
		// 强制设封面：用相册第一个 media，覆盖已有封面。
		if err := provider.SetAlbumCover(uid, albumID, album.MediaIDs[0]); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{
				"error":    err.Error(),
				"album_id": albumID,
			})
			return
		}
		updated++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"updated_count": updated,
		"skipped_count": skipped,
	})
}

// handleAlbumListWithCover V12：GET /api/media/album/list-with-cover — 列出当前用户所有相册，
// 在 all-summary 基础上额外返回 cover_thumbnail_url，方便前端一次渲染相册网格。
// 对每个有 cover_media_id 的相册，生成缩略图 URL：/api/media/thumbnail/{cover_media_id}。
// 返回 { albums: [{id,name,media_count,cover_media_id,cover_thumbnail_url,pinned,created_at}], total }。
func (s *Server) handleAlbumListWithCover(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
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
	albums := provider.ListAlbums(uid)
	items := make([]map[string]any, 0, len(albums))
	for _, a := range albums {
		coverThumb := ""
		if a.CoverMediaID != "" {
			coverThumb = "/api/media/thumbnail/" + a.CoverMediaID
		}
		items = append(items, map[string]any{
			"id":                  a.ID,
			"name":                a.Name,
			"media_count":         len(a.MediaIDs),
			"cover_media_id":      a.CoverMediaID,
			"cover_thumbnail_url": coverThumb,
			"pinned":              a.Pinned,
			"created_at":          a.CreatedAt,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"albums": items,
		"total":  len(items),
	})
}

// handleMediaTagBatchByType V8：POST /api/media/tag/batch-by-type — 按媒体类型
// 批量打标签。IMAGE → 照片, VIDEO → 视频, LIVE_PHOTO → 动态照片。
// 无请求体。跳过已删除或类型未知的媒体。返回 { status, tagged_count }。
func (s *Server) handleMediaTagBatchByType(w http.ResponseWriter, r *http.Request) {
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
	count, err := s.store.BatchTagByType(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":       "success",
		"tagged_count": count,
	})
}

// handleMediaDuplicateCleanup V8：POST /api/media/duplicate-cleanup — 自动清理重复媒体。
// 按 SHA256 找重复（非空），每组保留最早的，其余软删。
func (s *Server) handleMediaDuplicateCleanup(w http.ResponseWriter, r *http.Request) {
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
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 按 SHA256 分组（跳过空 SHA256 和已删除）
	bySHA := make(map[string][]*storage.Media)
	for _, m := range mediaList {
		if m.Deleted || m.SHA256 == "" {
			continue
		}
		bySHA[m.SHA256] = append(bySHA[m.SHA256], m)
	}

	// 对每组（>1个），保留最早的，其余软删
	type deletedItem struct {
		ID       string `json:"media_id"`
		Filename string `json:"filename"`
		SHA256   string `json:"sha256"`
	}
	var deleted []deletedItem
	for sha, group := range bySHA {
		if len(group) < 2 {
			continue
		}
		// 找最早的（保留）
		oldest := group[0]
		for _, m := range group[1:] {
			if m.CreatedAt.Before(oldest.CreatedAt) {
				oldest = m
			}
		}
		// 软删其余
		for _, m := range group {
			if m.ID == oldest.ID {
				continue
			}
			m.Deleted = true
			if err := s.store.UpdateMedia(r.Context(), m); err == nil {
				deleted = append(deleted, deletedItem{ID: m.ID, Filename: m.Filename, SHA256: sha[:16]})
			}
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":        "success",
		"groups_found":  len(bySHA),
		"deleted_count": len(deleted),
		"deleted":       deleted,
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

// handleMediaBatchDownloadUrls 处理 POST /api/media/batch-download-urls ——
// 返回多个媒体的直接下载 URL 列表。
//
// 与 batch-download（直接 zip 流式打包）和 batch-share（批量创建分享链接）不同：
// 本端点既不打包文件流，也不创建分享 token，仅返回临时的直接下载 URL
// （/api/media/download/{id}），前端可据此渲染"复制链接"或并发批量下载。
//
// 请求体: {"media_ids": ["id1","id2",...]}
// 单批最多 100 个 media_ids（与 batch-download 上限一致）。
// 每个 media_id 均校验格式安全 + 归属当前用户 + 未软删；任一不合法即整体拒绝，
// 不部分返回，避免向客户端暴露混杂的成败结果。
//
// 响应: {"urls": [{"media_id":..., "filename":..., "url":..., "size":...}], "count": N}
func (s *Server) handleMediaBatchDownloadUrls(w http.ResponseWriter, r *http.Request) {
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
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	if len(req.MediaIDs) == 0 {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_ids must not be empty"})
		return
	}
	// 单批上限与 batch-download 一致（100），防止请求体/遍历放大。
	const batchDownloadURLsMax = 100
	if len(req.MediaIDs) > batchDownloadURLsMax {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": fmt.Sprintf("too many media_ids (max %d)", batchDownloadURLsMax)})
		return
	}

	// 单次遍历：校验每个 media_id 格式安全 + 归属当前用户 + 未软删，同时收集 media
	// 用于生成 URL。任一不合法即整体拒绝——不部分返回，避免半成品响应（与 batch-share 一致）。
	medias := make([]*storage.Media, 0, len(req.MediaIDs))
	for _, mid := range req.MediaIDs {
		if mid == "" || strings.Contains(mid, "..") || strings.Contains(mid, "/") {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id in list"})
			return
		}
		m, err := s.store.GetMedia(r.Context(), mid)
		if err != nil || m == nil {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "media not accessible"})
			return
		}
		if m.UserID != uid || m.Deleted {
			writeJSON(w, http.StatusForbidden, map[string]any{"error": "media not accessible"})
			return
		}
		medias = append(medias, m)
	}

	// 生成直接下载 URL：/api/media/download/{id}。该路径由 authMiddleware 鉴权，
	// 仅当前用户可访问；URL 临时有效（随会话/token 失效），不落库、不创建分享 token。
	type downloadURL struct {
		MediaID  string `json:"media_id"`
		Filename string `json:"filename"`
		URL      string `json:"url"`
		Size     int64  `json:"size"`
	}
	urls := make([]downloadURL, 0, len(medias))
	for _, m := range medias {
		urls = append(urls, downloadURL{
			MediaID:  m.ID,
			Filename: m.Filename,
			URL:      "/api/media/download/" + m.ID,
			Size:     m.Size,
		})
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"urls":  urls,
		"count": len(urls),
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

// handleUserActivityScore GET /api/media/user-activity-score — 用户活跃度评分。
//
// 基于各操作维度的加权累计分数，并映射到等级（新手/活跃/达人/专家），
// 附带各维度明细（action + count + points）与总操作数：
//
//	score = upload_count*3 + favorite_count*2 + share_count*4 +
//	        tag_count*1 + rename_count*1 + rotate_count*1
//
// 等级映射：新手(0-10) / 活跃(11-50) / 达人(51-100) / 专家(101+)。
//
// 数据来源：
//   - audit_log 操作统计（store.AuditLogStats 返回 [{action,count}]），用于
//     share/share_toggle/tag/rename/rotate 等已埋点的操作。upload 操作未在
//     上传路径埋点，故 upload_count 取自 ListMediaByUser 的未软删媒体数。
//   - favorite 维度：audit_log 仅记录 unfavorite（取消收藏），无法表达累计收藏
//     活跃度，故优先用 favoriteProvider.ListFavorites 的当前收藏数；该能力未
//     配置（mediaSvc 未实现 favoriteProvider）时回退到 audit_log 中 "favorite"
//     动作计数（兼容用户自行 record 的情况），仍不可得则记 0。
//
// 需认证，按 user_id 隔离；store 未注入返回 503。响应：
//
//	{score, level, breakdown:[{action,count,points}], total_actions, user_id}
func (s *Server) handleUserActivityScore(w http.ResponseWriter, r *http.Request) {
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

	// 1. audit_log 操作统计 → action→count 映射。
	stats, err := s.store.AuditLogStats(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	actionCount := make(map[string]int, len(stats))
	for _, st := range stats {
		action, _ := st["action"].(string)
		cnt, _ := st["count"].(int)
		if action == "" {
			continue
		}
		actionCount[action] += cnt
	}

	// 2. upload_count：取未软删媒体总数（upload 未埋点，以存量代表上传活跃度）。
	uploadCount := 0
	if mediaList, merr := s.store.ListMediaByUser(r.Context(), uid); merr == nil {
		for _, m := range mediaList {
			if !m.Deleted {
				uploadCount++
			}
		}
	}

	// 3. favorite_count：优先 favoriteProvider.ListFavorites；否则回退 audit_log
	//    的 "favorite" 动作计数。
	favoriteCount := 0
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favoriteCount = len(fav.ListFavorites(uid))
	}
	if favoriteCount == 0 {
		favoriteCount = actionCount["favorite"]
	}

	// 4. 各维度数值。share 合并 share + share_toggle 两个埋点动作。
	shareCount := actionCount["share"] + actionCount["share_toggle"]
	tagCount := actionCount["tag"]
	renameCount := actionCount["rename"]
	rotateCount := actionCount["rotate"]

	// 5. 加权打分（按任务指定权重）。
	weights := map[string]int{
		"upload":   3,
		"favorite": 2,
		"share":    4,
		"tag":      1,
		"rename":   1,
		"rotate":   1,
	}
	counts := map[string]int{
		"upload":   uploadCount,
		"favorite": favoriteCount,
		"share":    shareCount,
		"tag":      tagCount,
		"rename":   renameCount,
		"rotate":   rotateCount,
	}
	// 保持展示顺序稳定（upload → favorite → share → tag → rename → rotate）。
	order := []string{"upload", "favorite", "share", "tag", "rename", "rotate"}

	score := 0
	totalActions := 0
	breakdown := make([]map[string]any, 0, len(order))
	for _, act := range order {
		c := counts[act]
		w := weights[act]
		points := c * w
		score += points
		totalActions += c
		breakdown = append(breakdown, map[string]any{
			"action": act,
			"count":  c,
			"weight": w,
			"points": points,
		})
	}

	// 6. 等级映射：新手(0-10) / 活跃(11-50) / 达人(51-100) / 专家(101+)。
	level := "新手"
	switch {
	case score >= 101:
		level = "专家"
	case score >= 51:
		level = "达人"
	case score >= 11:
		level = "活跃"
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"score":         score,
		"level":         level,
		"breakdown":     breakdown,
		"total_actions": totalActions,
		"user_id":       uid,
	})
}

// handleStorageHealth V25：GET /api/media/storage-health — 存储健康度评分。
// 综合重复率（权重 30）、孤立率（权重 20，可选磁盘扫描，跳过用 0）、
// 配额使用率（权重 30，默认 10GB）、冷数据占比（权重 20，>180 天视为冷数据）
// 给出 0-100 分，并映射 A/B/C/D 等级，附带针对性建议。
//
// 评分模型：score = 100 - (duplicate_rate*30 + orphan_rate*20 + quota_usage*30 + (1-age_score)*20)
//   - duplicate_rate = duplicate_count / total_count（同一 SHA256 出现 >1 份视为重复）
//   - orphan_rate    = orphan_count / total_count（跳过磁盘扫描时为 0）
//   - quota_usage    = used_bytes / quota_bytes（默认 10GB）
//   - age_score      = 1 - cold_count / total_count（热+温数据占比，>180 天视为冷）
func (s *Server) handleStorageHealth(w http.ResponseWriter, r *http.Request) {
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

	const defaultQuotaBytes int64 = 10 * 1024 * 1024 * 1024 // 10 GB
	now := time.Now()

	// 一次遍历：统计总量、已用字节、冷数据（>180 天）、按 SHA256 重复。
	totalCount := 0
	coldCount := 0
	var usedBytes int64
	shaCounts := make(map[string]int)
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		totalCount++
		usedBytes += m.Size
		if now.Sub(m.CreatedAt) >= 180*24*time.Hour {
			coldCount++
		}
		if m.SHA256 != "" {
			shaCounts[m.SHA256]++
		}
	}

	// 重复文件份：每个 SHA256 出现 >1，超出 1 份的部分计入重复。
	duplicateCount := 0
	for _, c := range shaCounts {
		if c > 1 {
			duplicateCount += c - 1
		}
	}

	// 孤立文件需磁盘扫描，本端点默认跳过（避免 IO 放大），按 0 计。
	// 如需精确孤立率，调用方可用 /api/media/orphan-check 单独扫描。
	orphanCount := 0

	// 各项比率 [0,1]，totalCount 为 0 时全部归 0（空库视为满分健康）。
	duplicateRate := 0.0
	orphanRate := 0.0
	quotaUsage := 0.0
	ageScore := 1.0
	if totalCount > 0 {
		duplicateRate = float64(duplicateCount) / float64(totalCount)
		orphanRate = float64(orphanCount) / float64(totalCount)
		ageScore = 1.0 - float64(coldCount)/float64(totalCount)
	}
	if defaultQuotaBytes > 0 {
		quotaUsage = float64(usedBytes) / float64(defaultQuotaBytes)
	}

	// 健康度评分：100 - 加权扣分（各权重分别 30/20/30/20，合计 100）。
	// 各扣分项上限为其权重，避免单项异常拉爆到负分。
	dupPenalty := min01(duplicateRate) * 30
	orphanPenalty := min01(orphanRate) * 20
	quotaPenalty := min01(quotaUsage) * 30
	agePenalty := (1.0 - min01(ageScore)) * 20
	score := 100.0 - (dupPenalty + orphanPenalty + quotaPenalty + agePenalty)
	if score < 0 {
		score = 0
	}
	if score > 100 {
		score = 100
	}

	// 等级映射：A(>=85) / B(70-84) / C(50-69) / D(<50)。
	grade := "D"
	switch {
	case score >= 85:
		grade = "A"
	case score >= 70:
		grade = "B"
	case score >= 50:
		grade = "C"
	}

	// 建议：按各项扣分严重程度给出针对性提示，最多保留最相关的几条。
	suggestions := make([]string, 0, 5)
	if duplicateCount > 0 {
		suggestions = append(suggestions, fmt.Sprintf("检测到 %d 份重复文件（重复率 %.1f%%），建议用 /api/media/duplicate-cleanup 清理", duplicateCount, duplicateRate*100))
	}
	if quotaUsage >= 0.8 {
		suggestions = append(suggestions, fmt.Sprintf("配额使用率 %.1f%% 接近上限，建议清理回收站或升级配额", quotaUsage*100))
	} else if quotaUsage >= 0.5 {
		suggestions = append(suggestions, fmt.Sprintf("配额使用率 %.1f%%，建议关注存储增长趋势", quotaUsage*100))
	}
	if coldCount > 0 && totalCount > 0 && float64(coldCount)/float64(totalCount) > 0.3 {
		suggestions = append(suggestions, fmt.Sprintf("冷数据占比 %.1f%%（%d 个文件超过 180 天未访问），建议归档或清理", float64(coldCount)/float64(totalCount)*100, coldCount))
	}
	if orphanCount > 0 {
		suggestions = append(suggestions, fmt.Sprintf("检测到 %d 个孤立文件（DB 有记录但磁盘缺失），建议用 /api/media/cleanup-orphan 处理", orphanCount))
	}
	if len(suggestions) == 0 {
		suggestions = append(suggestions, "存储状态良好，建议保持当前使用习惯")
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"score":           int(score),
		"grade":           grade,
		"duplicate_rate":  round2(duplicateRate),
		"quota_usage":     round2(quotaUsage),
		"age_score":       round2(ageScore),
		"total_count":     totalCount,
		"duplicate_count": duplicateCount,
		"orphan_count":    orphanCount,
		"cold_count":      coldCount,
		"used_bytes":      usedBytes,
		"quota_bytes":     defaultQuotaBytes,
		"suggestions":     suggestions,
		"user_id":         uid,
	})
}

// handleMediaDashboard GET /api/media/dashboard — 仪表盘聚合端点。
//
// 一次请求合并首页/我的Tab 渲染所需的全部关键数据，避免前端并发拉 6 个端点：
//
//	storage_health_score + grade   ← 存储健康度评分（0-100）与等级（A/B/C/D）
//	quick_stats                     ← 6 个核心计数（total_media/total_bytes/image/video/album/favorite）
//	upload_streak                   ← 当前连续上传天数 + 最长连续 + 今日上传数
//	recent_activity                 ← 最近活动 top 3（按时间倒序，合并 upload/share）
//	tag_top3                        ← 标签使用数 top 3（TagStats 已按 count DESC 返回）
//	coverage                        ← 覆盖率（已打标签% + 已收藏%，基于未软删媒体总数）
//
// 不同于 stat-summary（偏数据概览，含 quota/audit/trash 等多块）、quick-stats（仅 6 个数字）、
// full-report（年度报告维度），dashboard 聚焦 UI 仪表盘首屏卡片：健康度+计数+streak+活动+标签+覆盖率。
//
// 实现策略：ListMediaByUser 一次拉全量未软删媒体，单次遍历派生 quick_stats /
// streak / health / recent_activity（前 3）；TagStats 一次查询派生 tag_top3；
// favoriteProvider.ListFavorites 一次派生 favorited 计数；ListAllTags + SearchMediaByTag
// 计算已打标签集合。各派生步骤独立容错（单步失败记 warn 不阻断）。
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
func (s *Server) handleMediaDashboard(w http.ResponseWriter, r *http.Request) {
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

	const defaultQuotaBytes int64 = 10 * 1024 * 1024 * 1024 // 10 GB，与 handleStorageHealth / handleUserQuota 一致
	now := time.Now()
	nowUTC := now.UTC()
	today := nowUTC.Format("2006-01-02")

	// 1. 一次拉取全量未软删媒体（ListMediaByUser 已过滤 deleted 且按 created_at DESC 排序）。
	mediaList, err := s.store.ListMediaByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}

	// 2. 单次遍历派生 quick_stats + streak + health + recent_activity。
	var (
		totalMedia        int
		imgCount          int
		vidCount          int
		usedBytes         int64
		coldCount         int
		todayCount        int
		duplicateCount    int
		days              = make(map[string]bool)
		shaCounts         = make(map[string]int)
		recentActivities  []map[string]any
		recentLimit       = 3
		recentCollected   = 0
		recentUploadLimit = 3 // 最近上传用于 activity（前 3 个）
	)
	for i, m := range mediaList {
		if m.Deleted {
			continue
		}
		totalMedia++
		usedBytes += m.Size
		switch m.Type {
		case "IMAGE":
			imgCount++
		case "VIDEO":
			vidCount++
		}
		// streak 维度（UTC 日期，与 handleUploadStreak 一致避免时区错位）
		day := m.CreatedAt.Format("2006-01-02")
		days[day] = true
		if day == today {
			todayCount++
		}
		// health 维度
		if now.Sub(m.CreatedAt) >= 180*24*time.Hour {
			coldCount++
		}
		if m.SHA256 != "" {
			shaCounts[m.SHA256]++
		}
		// recent_activity 维度：mediaList 已按 created_at DESC，前 N 个即最近上传。
		if recentCollected < recentUploadLimit {
			recentActivities = append(recentActivities, map[string]any{
				"type":      "upload",
				"media_id":  m.ID,
				"filename":  m.Filename,
				"timestamp": m.CreatedAt.Unix(),
				"detail":    "上传了 " + m.Filename,
			})
			recentCollected++
		}
		_ = i
	}

	// 3. quick_stats: album/favorite count 来自 mediaSvc provider 接口。
	favoriteIDs := []string{}
	if fav, ok := s.mediaSvc.(favoriteProvider); ok {
		favoriteIDs = fav.ListFavorites(uid)
	}
	albumCount := 0
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		albumCount = len(provider.ListAlbums(uid))
	}
	quickStats := map[string]any{
		"total_media":    totalMedia,
		"total_bytes":    usedBytes,
		"image_count":    imgCount,
		"video_count":    vidCount,
		"album_count":    albumCount,
		"favorite_count": len(favoriteIDs),
	}

	// 4. upload_streak：复用 handleUploadStreak 算法。
	// longest_streak：遍历有序日期统计最长连续段。
	sortedDays := make([]string, 0, len(days))
	for d := range days {
		sortedDays = append(sortedDays, d)
	}
	sort.Strings(sortedDays)
	longestStreak := 0
	curRun := 0
	var prev time.Time
	for _, d := range sortedDays {
		t, perr := time.Parse("2006-01-02", d)
		if perr != nil {
			continue
		}
		if curRun == 0 || t.Sub(prev) == 24*time.Hour {
			curRun++
			if curRun > longestStreak {
				longestStreak = curRun
			}
		} else {
			curRun = 1
		}
		prev = t
	}
	// current_streak：从今天（或昨天）往前连续有上传的天数。
	currentStreak := 0
	cursor, _ := time.Parse("2006-01-02", today)
	if !days[today] {
		cursor = cursor.AddDate(0, 0, -1)
	}
	for days[cursor.Format("2006-01-02")] {
		currentStreak++
		cursor = cursor.AddDate(0, 0, -1)
	}
	lastUpload := ""
	if len(sortedDays) > 0 {
		lastUpload = sortedDays[len(sortedDays)-1]
	}
	uploadStreak := map[string]any{
		"current_streak":    currentStreak,
		"longest_streak":    longestStreak,
		"total_active_days": len(days),
		"last_upload_date":  lastUpload,
		"today_count":       todayCount,
	}

	// 5. storage_health_score + grade：复用 handleStorageHealth 评分模型。
	for _, c := range shaCounts {
		if c > 1 {
			duplicateCount += c - 1
		}
	}
	orphanCount := 0 // 孤立文件需磁盘扫描，本端点跳过避免 IO 放大（与 handleStorageHealth 一致）
	duplicateRate := 0.0
	ageScore := 1.0
	quotaUsage := 0.0
	if totalMedia > 0 {
		duplicateRate = float64(duplicateCount) / float64(totalMedia)
		ageScore = 1.0 - float64(coldCount)/float64(totalMedia)
	}
	if defaultQuotaBytes > 0 {
		quotaUsage = float64(usedBytes) / float64(defaultQuotaBytes)
	}
	dupPenalty := min01(duplicateRate) * 30
	orphanPenalty := min01(float64(orphanCount)/float64(max1(totalMedia))) * 20
	quotaPenalty := min01(quotaUsage) * 30
	agePenalty := (1.0 - min01(ageScore)) * 20
	score := 100.0 - (dupPenalty + orphanPenalty + quotaPenalty + agePenalty)
	if score < 0 {
		score = 0
	}
	if score > 100 {
		score = 100
	}
	grade := "D"
	switch {
	case score >= 85:
		grade = "A"
	case score >= 70:
		grade = "B"
	case score >= 50:
		grade = "C"
	}
	storageHealth := map[string]any{
		"score":           int(score),
		"grade":           grade,
		"duplicate_rate":  round2(duplicateRate),
		"quota_usage":     round2(quotaUsage),
		"age_score":       round2(ageScore),
		"duplicate_count": duplicateCount,
		"cold_count":      coldCount,
		"used_bytes":      usedBytes,
		"quota_bytes":     defaultQuotaBytes,
	}

	// 6. recent_activity（top 3）：补充最近分享（按时间倒序合并），与 handleMediaRecentActivity 口径一致。
	// mediaList 已按 created_at DESC，前 recentUploadLimit 条 upload 已收录；
	// 再补最近分享，合并后按 timestamp 倒序取前 recentLimit。
	if shares, serr := s.store.ListShareTokensByUser(r.Context(), uid); serr == nil {
		for i, st := range shares {
			if i >= 2 { // 仅取前 2 条分享，避免压过 upload
				break
			}
			recentActivities = append(recentActivities, map[string]any{
				"type":      "share",
				"media_id":  st.Token,
				"filename":  st.Token,
				"timestamp": st.CreatedAt.Unix(),
				"detail":    "创建了分享链接",
			})
		}
	}
	sort.Slice(recentActivities, func(i, j int) bool {
		ti, _ := recentActivities[i]["timestamp"].(int64)
		tj, _ := recentActivities[j]["timestamp"].(int64)
		return ti > tj
	})
	if len(recentActivities) > recentLimit {
		recentActivities = recentActivities[:recentLimit]
	}

	// 7. tag_top3：复用 TagStats（已按 count DESC 返回），取前 3。
	var tagTop3 []map[string]any
	if tagStats, terr := s.store.TagStats(r.Context(), uid); terr == nil {
		n := len(tagStats)
		if n > 3 {
			n = 3
		}
		tagTop3 = make([]map[string]any, 0, n)
		for i := 0; i < n; i++ {
			tagTop3 = append(tagTop3, tagStats[i])
		}
	} else {
		slog.Warn("dashboard: tag stats failed", "error", terr)
		tagTop3 = []map[string]any{}
	}

	// 8. coverage：tagged% + favorited%（基于未软删媒体总数）。
	// tagged：遍历用户所有标签，汇总关联的 media_id 集合（与 handleMediaCoverage 同口径）。
	liveIDs := make(map[string]struct{}, totalMedia)
	for _, m := range mediaList {
		if !m.Deleted {
			liveIDs[m.ID] = struct{}{}
		}
	}
	taggedSet := make(map[string]struct{})
	if totalMedia > 0 {
		if tags, terr := s.store.ListAllTags(r.Context(), uid); terr == nil {
			for _, tag := range tags {
				if ids, serr := s.store.SearchMediaByTag(r.Context(), uid, tag); serr == nil {
					for _, id := range ids {
						taggedSet[id] = struct{}{}
					}
				}
			}
		} else {
			slog.Warn("dashboard: list tags failed", "error", terr)
		}
	}
	favSet := make(map[string]struct{})
	for _, id := range favoriteIDs {
		favSet[id] = struct{}{}
	}
	countInLive := func(set map[string]struct{}) int {
		n := 0
		for id := range set {
			if _, ok := liveIDs[id]; ok {
				n++
			}
		}
		return n
	}
	taggedCount := countInLive(taggedSet)
	favoritedCount := countInLive(favSet)
	pct := func(c int) float64 {
		if totalMedia == 0 {
			return 0
		}
		return round2(float64(c) / float64(totalMedia) * 100)
	}
	coverage := map[string]any{
		"total":           totalMedia,
		"tagged_count":    taggedCount,
		"tagged_percent":  pct(taggedCount),
		"fav_count":       favoritedCount,
		"favorited_percent": pct(favoritedCount),
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"storage_health":  storageHealth,
		"quick_stats":     quickStats,
		"upload_streak":   uploadStreak,
		"recent_activity": recentActivities,
		"tag_top3":        tagTop3,
		"coverage":        coverage,
		"user_id":         uid,
	})
}

// max1 返回 n 与 1 的较大者，用于避免除零（与 min01 配套）。
func max1(n int) int {
	if n < 1 {
		return 1
	}
	return n
}

// min01 把浮点截断到 [0,1] 区间，用于评分项上限钳制。
func min01(v float64) float64 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

// round2 保留两位小数，用于 JSON 响应中的比率字段。
func round2(v float64) float64 {
	return float64(int(v*100+0.5)) / 100
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

// handleStorageRecommendations V15：GET /api/media/storage-recommendations —
// 存储清理建议。一次拉取用户全部媒体，从四个维度分析可回收空间：
//
//	a) 重复文件（SHA256 相同）：每组保留 1 份，其余可删，累加可回收字节数。
//	b) 大文件（>100MB）：按大小倒序取 top 5。
//	c) 旧文件（CreatedAt 距今 >365 天）：统计 count + bytes（Media 无独立访问时间，
//	   以 CreatedAt 上传时间作为代理；旧上传且长期未更新的文件通常无保留价值）。
//	d) 孤立文件（DB 有记录但磁盘文件缺失，复用 orphan-check 的 Glob 逻辑）。
//
// 响应:
//
//	{
//	  "duplicates": {"count","reclaimable_bytes"},
//	  "large_files": [{"media_id","filename","size"}],
//	  "old_files": {"count","bytes"},
//	  "orphans": {"count","bytes"},
//	  "total_reclaimable_bytes": N,
//	  "recommendation_count": N
//	}
func (s *Server) handleStorageRecommendations(w http.ResponseWriter, r *http.Request) {
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

	// uploadsDir 可能为空（userDirs 未注入）；非空时才做孤立文件磁盘检查。
	uploadsDir := s.userUploadsDir(uid)

	const largeFileThreshold = 100 * 1024 * 1024 // 100MB
	const oldFileThreshold = 365 * 24 * time.Hour

	// (a) 重复文件：按 SHA256 分组，组内保留 1 份，其余计入可回收。
	dupGroups := make(map[string][]*storage.Media)
	for _, m := range mediaList {
		if m.SHA256 == "" {
			continue
		}
		dupGroups[m.SHA256] = append(dupGroups[m.SHA256], m)
	}
	dupCount := 0
	var dupReclaimable int64
	for _, items := range dupGroups {
		if len(items) > 1 {
			// 保留 1 份，其余可删；可回收 = (count-1) * size。
			// 同一 SHA256 的文件 size 应一致，取第 0 个的 size 即可。
			dupCount += len(items) - 1
			dupReclaimable += int64(len(items)-1) * items[0].Size
		}
	}

	// (b) 大文件：>100MB，按 size 倒序取 top 5。
	type largeFile struct {
		MediaID  string `json:"media_id"`
		Filename string `json:"filename"`
		Size     int64  `json:"size"`
	}
	var larges = make([]largeFile, 0)
	for _, m := range mediaList {
		if m.Size > largeFileThreshold {
			larges = append(larges, largeFile{
				MediaID:  m.ID,
				Filename: m.Filename,
				Size:     m.Size,
			})
		}
	}
	sort.Slice(larges, func(i, j int) bool { return larges[i].Size > larges[j].Size })
	if len(larges) > 5 {
		larges = larges[:5]
	}

	// (c) 旧文件：CreatedAt 距今 >365 天。统计 count + bytes。
	cutoff := time.Now().Add(-oldFileThreshold)
	oldCount := 0
	var oldBytes int64
	for _, m := range mediaList {
		if m.CreatedAt.Before(cutoff) {
			oldCount++
			oldBytes += m.Size
		}
	}

	// (d) 孤立文件：DB 有记录但磁盘缺失（复用 orphan-check 逻辑）。
	// uploadsDir 为空时跳过磁盘检查，orphans 计为 0。
	orphanCount := 0
	var orphanBytes int64
	if uploadsDir != "" {
		for _, m := range mediaList {
			pattern := filepath.Join(uploadsDir, m.ID+".*")
			files, _ := filepath.Glob(pattern)
			if len(files) == 0 {
				orphanCount++
				orphanBytes += m.Size
			}
		}
	}

	totalReclaimable := dupReclaimable + oldBytes + orphanBytes

	// recommendation_count：去重的建议条目数（重复组数 + 大文件数 + 旧文件类 + 孤立类）。
	dupGroupCount := 0
	for _, items := range dupGroups {
		if len(items) > 1 {
			dupGroupCount++
		}
	}
	recommendationCount := dupGroupCount + len(larges)
	if oldCount > 0 {
		recommendationCount++
	}
	if orphanCount > 0 {
		recommendationCount++
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"duplicates": map[string]any{
			"count":             dupCount,
			"reclaimable_bytes": dupReclaimable,
		},
		"large_files": larges,
		"old_files": map[string]any{
			"count": oldCount,
			"bytes": oldBytes,
		},
		"orphans": map[string]any{
			"count": orphanCount,
			"bytes": orphanBytes,
		},
		"total_reclaimable_bytes": totalReclaimable,
		"recommendation_count":    recommendationCount,
		"user_id":                 uid,
	})
}

// handleUploadPatternAnalysis V20：GET /api/media/upload-pattern-analysis —
// 上传模式分析。基于当前用户全部未删除媒体的 created_at，统计最常上传的：
//   - 类型（IMAGE / VIDEO / LIVE_PHOTO）
//   - 大小范围（<1MB / 1-10MB / 10-50MB / 50-100MB / >100MB）
//   - 时段（早晨 6-11 / 下午 12-17 / 晚上 18-23 / 深夜 0-5）
//   - 星期（Sunday..Saturday）
//
// 返回 {dominant_type, dominant_size_range, dominant_time_period,
//
//	dominant_weekday, total}，每个 dominant_* 形如 {key, count}；
//
// 无数据时各 dominant_* 的 count 为 0、key 为空串，total 为 0。
//
// 说明：时段划分按任务约定小时位 [6,12)=早晨、[12,18)=下午、[18,24)=晚上、
// [0,6)=深夜；星期使用 Go time.Weekday 的英文全称（time.January 时
// Weekday().String() 返回 "Monday" 等），便于前端固定 i18n 映射。
func (s *Server) handleUploadPatternAnalysis(w http.ResponseWriter, r *http.Request) {
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

	// 计数器
	typeCounts := map[string]int{}
	sizeRangeOrder := []string{"<1MB", "1-10MB", "10-50MB", "50-100MB", ">100MB"}
	sizeCounts := map[string]int{}
	periodOrder := []string{"早晨", "下午", "晚上", "深夜"}
	periodCounts := map[string]int{}
	weekdayCounts := map[string]int{}

	total := 0
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		total++

		// (a) 类型：原样使用 DB 中 type 字段（IMAGE/VIDEO/LIVE_PHOTO）。
		typeCounts[m.Type]++

		// (b) 大小范围：基于字节阈值分桶。
		var bucket string
		switch {
		case m.Size < 1024*1024:
			bucket = "<1MB"
		case m.Size < 10*1024*1024:
			bucket = "1-10MB"
		case m.Size < 50*1024*1024:
			bucket = "10-50MB"
		case m.Size < 100*1024*1024:
			bucket = "50-100MB"
		default:
			bucket = ">100MB"
		}
		sizeCounts[bucket]++

		// (c) 时段：按 created_at 本地小时划分（task spec：6-11 早晨、12-17 下午、
		// 18-23 晚上、0-5 深夜）。区间为半开 [lo,hi)。
		hour := m.CreatedAt.Hour()
		var period string
		switch {
		case hour >= 6 && hour < 12:
			period = "早晨"
		case hour >= 12 && hour < 18:
			period = "下午"
		case hour >= 18 && hour < 24:
			period = "晚上"
		default: // 0..5
			period = "深夜"
		}
		periodCounts[period]++

		// (d) 星期：使用 Go 的 Weekday().String()（英文全称）。
		weekdayCounts[m.CreatedAt.Weekday().String()]++
	}

	// 选出每个维度的众数（count 最大者；并列时按预定义顺序取第一个；
	// 全 0 则返回 key="" count=0）。
	type dominant struct {
		Key   string `json:"key"`
		Count int    `json:"count"`
	}

	pickDominant := func(order []string, counts map[string]int) dominant {
		var best dominant
		for _, k := range order {
			if c := counts[k]; c > best.Count {
				best = dominant{Key: k, Count: c}
			}
		}
		// order 之外的 key（如 type 维度可能有未知值、weekday 维度天然全在 order 内）
		// 也遍历一次，保证未被 order 列入的取值仍可竞争众数。
		for k, c := range counts {
			if c > best.Count {
				best = dominant{Key: k, Count: c}
			}
		}
		return best
	}

	weekdayOrder := []string{
		"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"dominant_type":        pickDominant([]string{"IMAGE", "VIDEO", "LIVE_PHOTO"}, typeCounts),
		"dominant_size_range":  pickDominant(sizeRangeOrder, sizeCounts),
		"dominant_time_period": pickDominant(periodOrder, periodCounts),
		"dominant_weekday":     pickDominant(weekdayOrder, weekdayCounts),
		"total":                total,
		// 详情明细一并返回，便于前端可视化（不增加额外查询成本）。
		"by_type":        typeCounts,
		"by_size_range":  sizeCounts,
		"by_time_period": periodCounts,
		"by_weekday":     weekdayCounts,
		"user_id":        uid,
	})
}

// handleMimeTypeStats GET /api/media/mime-type-stats — 按 MIME 类型详细统计。
// 与 /api/media/file-types 的区别：file-types 只返回 count + bytes，本端点额外提供
// avg_bytes（平均大小）与 earliest/latest（该 MIME 最早/最晚上传时间），供前端存储
// 分析页按 MIME 粒度展示完整大小与时间维度。实现复用 ListMediaByUser 全量拉取 +
// 内存按 Mime 分组聚合，按数量倒序排序后返回。
func (s *Server) handleMimeTypeStats(w http.ResponseWriter, r *http.Request) {
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
		Mime       string    `json:"mime"`
		Count      int       `json:"count"`
		TotalBytes int64     `json:"total_bytes"`
		AvgBytes   int64     `json:"avg_bytes"`
		Earliest   time.Time `json:"earliest"`
		Latest     time.Time `json:"latest"`
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
		st, ok := byMime[key]
		if !ok {
			st = &mimeStat{Mime: key, Earliest: m.CreatedAt, Latest: m.CreatedAt}
			byMime[key] = st
		}
		st.Count++
		st.TotalBytes += m.Size
		if m.CreatedAt.Before(st.Earliest) {
			st.Earliest = m.CreatedAt
		}
		if m.CreatedAt.After(st.Latest) {
			st.Latest = m.CreatedAt
		}
	}

	stats := make([]mimeStat, 0, len(byMime))
	for _, v := range byMime {
		if v.Count > 0 {
			v.AvgBytes = v.TotalBytes / int64(v.Count)
		}
		stats = append(stats, *v)
	}
	// 按数量倒序，并列时按 MIME 字典序保证稳定输出。
	sort.Slice(stats, func(i, j int) bool {
		if stats[i].Count != stats[j].Count {
			return stats[i].Count > stats[j].Count
		}
		return stats[i].Mime < stats[j].Mime
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"mimes": stats,
		"total": len(stats),
	})
}

// handleAlbumSuggestions GET /api/media/album-suggestions — 相册智能建议。
//
// 基于当前用户**未分类**的媒体（不在任何相册中的 media），按日期/类型/标签分组，
// 生成可一键创建的相册建议，供前端"推荐相册"功能展示。只读端点，不修改任何数据。
//
// 建议生成规则（每条建议携带 name/media_count/type/preview_ids）：
//
//	a) by_month  — 按"年-月"分组（优先 taken_at 拍摄时间，回退 created_at 上传时间，
//	   UTC 归一化）；count >= 3 的月份产出一条建议，形如"2026年7月的照片"。
//	b) by_type   — 所有 VIDEO 类型未分类媒体合并为一条"视频合集"（count >= 1 即产出）。
//	c) by_tag    — 遍历用户所有标签（ListAllTags + SearchMediaByTag），对每个标签统计
//	   其关联媒体中"未分类"的数量；count >= 2 的标签产出一条"旅行标签"形建议。
//
// 数据来源：media 来自 store.ListMediaByUser（已过滤 deleted）；相册归属来自
// mediaSvc.albumStoreProvider.ListAlbums（Album.MediaIDs 汇总成 in-album 集合）。
// albumStoreProvider 未配置时无法判定归属，此时把所有未删除媒体视为未分类
// （保守建议——宁可多建议也不漏），而非 501：相册归属是过滤维度，非本端点核心能力。
//
// 响应:
//
//	{
//	  "suggestions": [
//	    {"name":"2026年7月的照片","media_count":12,"type":"by_month","preview_ids":["a","b","c","d"]},
//	    {"name":"视频合集","media_count":5,"type":"by_type","preview_ids":[...]},
//	    {"name":"旅行","media_count":8,"type":"by_tag","preview_ids":[...]}
//	  ],
//	  "total": 3
//	}
//
// preview_ids 最多 4 个（取该组内最新的 media，按 created_at 降序），供前端渲染封面缩略图。
func (s *Server) handleAlbumSuggestions(w http.ResponseWriter, r *http.Request) {
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

	// in-album 集合：出现在任一相册 MediaIDs 中的 media_id。
	// albumStoreProvider 未配置时为空集合 → 所有未删除媒体均视为未分类（保守建议）。
	inAlbum := make(map[string]struct{})
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		for _, a := range provider.ListAlbums(uid) {
			for _, id := range a.MediaIDs {
				inAlbum[id] = struct{}{}
			}
		}
	}

	// 筛选未分类媒体（不在任何相册中且未软删；ListMediaByUser 已过滤 deleted，此处防御式再跳过）。
	uncategorized := make([]*storage.Media, 0, len(mediaList))
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		if _, ok := inAlbum[m.ID]; ok {
			continue
		}
		uncategorized = append(uncategorized, m)
	}

	// albumSuggestion 是单条建议的响应结构。
	type albumSuggestion struct {
		Name       string   `json:"name"`
		MediaCount int      `json:"media_count"`
		Type       string   `json:"type"`
		PreviewIDs []string `json:"preview_ids"`
	}
	suggestions := make([]albumSuggestion, 0)

	// previewIDs 从一组 media（按 created_at 降序）取前 maxPreview 个 id。
	const maxPreview = 4
	previewIDs := func(group []*storage.Media) []string {
		if len(group) == 0 {
			return []string{}
		}
		// 复制后排序，避免改乱原切片（分组内仍需原顺序做后续聚合）。
		sorted := make([]*storage.Media, len(group))
		copy(sorted, group)
		sort.Slice(sorted, func(i, j int) bool {
			return sorted[i].CreatedAt.After(sorted[j].CreatedAt)
		})
		n := len(sorted)
		if n > maxPreview {
			n = maxPreview
		}
		ids := make([]string, 0, n)
		for i := 0; i < n; i++ {
			ids = append(ids, sorted[i].ID)
		}
		return ids
	}

	// 中文月份名（1..12 → "1月".."12月"），用于"YYYY年M月的照片"命名。
	monthName := func(m int) string {
		// int → 中文数字月份；1-9 用单字，10-12 直接拼接。
		cnNums := []string{"", "一", "二", "三", "四", "五", "六", "七", "八", "九", "十", "十一", "十二"}
		if m >= 1 && m <= 12 {
			return cnNums[m] + "月"
		}
		return fmt.Sprintf("%d月", m)
	}

	// mediaTime 取一条 media 的代表性时间（优先拍摄时间 taken_at，回退上传时间 created_at），
	// UTC 归一化（codebase 时间聚合约定）。taken_at 为 int64 毫秒时间戳，0 表未知。
	mediaTime := func(m *storage.Media) time.Time {
		if m.TakenAt > 0 {
			return time.UnixMilli(m.TakenAt).UTC()
		}
		return m.CreatedAt.UTC()
	}

	// a) by_month：按"年-月"分组，count >= 3 产出建议。
	type monthBucket struct {
		year  int
		month int
		media []*storage.Media
	}
	byMonth := make(map[string]*monthBucket)
	for _, m := range uncategorized {
		t := mediaTime(m)
		key := fmt.Sprintf("%d-%02d", t.Year(), t.Month())
		b, ok := byMonth[key]
		if !ok {
			b = &monthBucket{year: t.Year(), month: int(t.Month())}
			byMonth[key] = b
		}
		b.media = append(b.media, m)
	}
	// 月份键按时间倒序（最近月份优先），便于前端展示"最近的未分类照片"在前。
	monthKeys := make([]string, 0, len(byMonth))
	for k := range byMonth {
		monthKeys = append(monthKeys, k)
	}
	sort.Sort(sort.Reverse(sort.StringSlice(monthKeys)))
	for _, k := range monthKeys {
		b := byMonth[k]
		if len(b.media) < 3 {
			continue
		}
		suggestions = append(suggestions, albumSuggestion{
			Name:       fmt.Sprintf("%d年%s的照片", b.year, monthName(b.month)),
			MediaCount: len(b.media),
			Type:       "by_month",
			PreviewIDs: previewIDs(b.media),
		})
	}

	// b) by_type：所有 VIDEO 类型未分类媒体合并为"视频合集"（count >= 1）。
	var videos []*storage.Media
	for _, m := range uncategorized {
		if strings.ToUpper(m.Type) == "VIDEO" {
			videos = append(videos, m)
		}
	}
	if len(videos) >= 1 {
		suggestions = append(suggestions, albumSuggestion{
			Name:       "视频合集",
			MediaCount: len(videos),
			Type:       "by_type",
			PreviewIDs: previewIDs(videos),
		})
	}

	// c) by_tag：遍历用户所有标签，对每个标签统计其关联媒体中"未分类"的数量（count >= 2）。
	// 用 uncategorized 的 id 集合做 O(1) 成员判定，避免对每个标签重新过滤。
	uncatIDs := make(map[string]struct{}, len(uncategorized))
	for _, m := range uncategorized {
		uncatIDs[m.ID] = struct{}{}
	}
	type tagBucket struct {
		tag   string
		ids   []string
		count int
	}
	var tagBuckets []tagBucket
	if tags, terr := s.store.ListAllTags(r.Context(), uid); terr == nil {
		for _, tag := range tags {
			if ids, serr := s.store.SearchMediaByTag(r.Context(), uid, tag); serr == nil {
				b := tagBucket{tag: tag}
				for _, id := range ids {
					if _, ok := uncatIDs[id]; ok {
						b.ids = append(b.ids, id)
						b.count++
					}
				}
				if b.count >= 2 {
					tagBuckets = append(tagBuckets, b)
				}
			}
		}
	}
	// 标签建议按命中未分类媒体数倒序（最有"成册价值"的标签在前），count 相同按标签名升序。
	sort.Slice(tagBuckets, func(i, j int) bool {
		if tagBuckets[i].count != tagBuckets[j].count {
			return tagBuckets[i].count > tagBuckets[j].count
		}
		return tagBuckets[i].tag < tagBuckets[j].tag
	})
	for _, b := range tagBuckets {
		// preview_ids 从该标签命中的未分类 media 中取（需映射回 *storage.Media 以按 created_at 排序）。
		tagMedia := make([]*storage.Media, 0, len(b.ids))
		for _, id := range b.ids {
			// 从 uncategorized 线性查找；标签命中数通常较小，O(n) 可接受，
			// 且避免再建一张 id→*Media 索引（uncategorized 已在内存中）。
			for _, m := range uncategorized {
				if m.ID == id {
					tagMedia = append(tagMedia, m)
					break
				}
			}
		}
		suggestions = append(suggestions, albumSuggestion{
			Name:       b.tag,
			MediaCount: b.count,
			Type:       "by_tag",
			PreviewIDs: previewIDs(tagMedia),
		})
	}

	writeJSON(w, http.StatusOK, map[string]any{
		"suggestions": suggestions,
		"total":       len(suggestions),
	})
}

// handleMediaInsights GET /api/media/insights — 智能洞察报告。
//
// 自动分析当前用户媒体库并给出可操作的智能建议，一次请求合并多个分析维度，
// 供前端"洞察"卡片展示。与 stat-summary（数据概览）/ storage-health（仅健康度）/
// storage-recommendations（仅清理建议）互补：本端点把各维度的结论凝练成"洞察条目"，
// 每条带 type/title/detail/action_url，前端可直接渲染为建议列表，无需自行再聚合。
//
// 六类洞察（仅当对应条件成立才产出，避免空建议噪音）：
//
//	a) duplicates    — 有重复文件时给出可回收字节数（SHA256 分组，组内保留 1 份）
//	b) distribution  — 存储类型分布，最大占比类型所占百分比
//	c) upload_habit  — 最常上传的时段（按 created_at 小时划分 早晨/下午/晚上/深夜）
//	d) untagged      — 未标签媒体数 > 0 时提示待整理量
//	e) top_album     — 媒体数最多的相册及其项数（albumStoreProvider 未配置时不产）
//	f) health        — 存储健康度等级（复用 storage-health 的评分模型：重复率/配额/冷数据）
//
// 数据来源：store.ListMediaByUser 一次拉全量未软删媒体 → 单遍历派生重复/类型分布/
// 时段/健康度；store.ListAllTags + SearchMediaByTag 派生已标签集合算 untagged；
// albumStoreProvider.ListAlbums 派生最大相册。各维度独立容错（单步失败记 warn 不阻断）。
//
// 需认证，按 user_id 隔离；store 未注入返回 503。
// 响应：{ insights: [{type, title, detail, action_url?}], total }
func (s *Server) handleMediaInsights(w http.ResponseWriter, r *http.Request) {
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

	const defaultQuotaBytes int64 = 10 * 1024 * 1024 * 1024 // 10 GB，与 storage-health/stat-summary 一致
	now := time.Now()

	// 单遍历：总量/字节/类型分布/SHA256 重复/冷数据/上传时段。
	totalCount := 0
	var usedBytes int64
	typeBytes := map[string]int64{"IMAGE": 0, "VIDEO": 0, "LIVE_PHOTO": 0}
	typeCounts := map[string]int{"IMAGE": 0, "VIDEO": 0, "LIVE_PHOTO": 0}
	shaCounts := make(map[string]int)
	coldCount := 0 // >180 天视为冷数据（同 storage-health 口径）
	// 上传时段：[6,12)=早晨、[12,18)=下午、[18,24)=晚上、[0,6)=深夜（同 upload-pattern-analysis）。
	periodCounts := map[string]int{"早晨": 0, "下午": 0, "晚上": 0, "深夜": 0}
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		totalCount++
		usedBytes += m.Size
		if _, ok := typeBytes[m.Type]; ok {
			typeBytes[m.Type] += m.Size
			typeCounts[m.Type]++
		} else {
			// 未知类型也计入，便于 distribution 占比兜底。
			typeBytes[m.Type] += m.Size
			typeCounts[m.Type]++
		}
		if now.Sub(m.CreatedAt) >= 180*24*time.Hour {
			coldCount++
		}
		if m.SHA256 != "" {
			shaCounts[m.SHA256]++
		}
		hour := m.CreatedAt.Hour()
		switch {
		case hour >= 6 && hour < 12:
			periodCounts["早晨"]++
		case hour >= 12 && hour < 18:
			periodCounts["下午"]++
		case hour >= 18 && hour < 24:
			periodCounts["晚上"]++
		default:
			periodCounts["深夜"]++
		}
	}

	// (a) 重复文件份：每个 SHA256 出现 >1，超出 1 份的部分计入重复；同 SHA256 size 一致，
	// 取首份 size 作每文件字节数：dupReclaimable = (count-1) * size_per_file。
	dupCount := 0
	var dupReclaimable int64
	for sha, c := range shaCounts {
		if c <= 1 {
			continue
		}
		dupCount += c - 1
		// 找到该 SHA256 的一个样本来取 size（同组 size 应一致）。
		var sizePerFile int64
		for _, m := range mediaList {
			if m.SHA256 == sha && !m.Deleted {
				sizePerFile = m.Size
				break
			}
		}
		dupReclaimable += int64(c-1) * sizePerFile
	}

	// (b) 存储类型分布：按字节占比找出最大类型。totalBytes>0 才有意义。
	var dominantType string
	var dominantTypeBytes int64
	for t, b := range typeBytes {
		if b > dominantTypeBytes {
			dominantTypeBytes = b
			dominantType = t
		}
	}
	typePercent := 0.0
	if usedBytes > 0 && dominantTypeBytes > 0 {
		typePercent = float64(dominantTypeBytes) / float64(usedBytes) * 100
	}

	// (c) 最常上传的时段：count 最大者，并列时按预定义顺序取第一个。
	periodOrder := []string{"早晨", "下午", "晚上", "深夜"}
	var dominantPeriod string
	maxPeriod := -1
	for _, p := range periodOrder {
		if c := periodCounts[p]; c > maxPeriod {
			maxPeriod = c
			dominantPeriod = p
		}
	}

	// (d) 未标签媒体：total - taggedCount。tagged 来自 ListAllTags + SearchMediaByTag
	// 汇总成带标签 media_id 集合（同 handleMediaCoverage 口径）。
	taggedSet := make(map[string]struct{})
	if totalCount > 0 {
		if tags, terr := s.store.ListAllTags(r.Context(), uid); terr == nil {
			for _, tag := range tags {
				if ids, serr := s.store.SearchMediaByTag(r.Context(), uid, tag); serr == nil {
					for _, id := range ids {
						taggedSet[id] = struct{}{}
					}
				}
			}
		} else {
			slog.Warn("insights: list all tags failed", "error", terr)
		}
	}
	// 只统计未软删媒体的已标签数（taggedSet 可能含已删媒体的残留 id）。
	liveTagged := 0
	liveIDs := make(map[string]struct{}, len(mediaList))
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		liveIDs[m.ID] = struct{}{}
	}
	for id := range taggedSet {
		if _, ok := liveIDs[id]; ok {
			liveTagged++
		}
	}
	untaggedCount := totalCount - liveTagged

	// (e) 最大相册：albumStoreProvider 未配置时跳过该洞察（不产 out，不报 501，
	// 与 stat-summary 互为参考——本端点聚焦建议，相册只是其中一条，不该因 provider
	// 缺位而整端点 501）。
	var topAlbumName string
	var topAlbumCount int
	hasTopAlbum := false
	if provider, ok := s.mediaSvc.(albumStoreProvider); ok {
		for _, a := range provider.ListAlbums(uid) {
			n := len(a.MediaIDs)
			if n > topAlbumCount {
				topAlbumCount = n
				topAlbumName = a.Name
				hasTopAlbum = true
			}
		}
	}

	// (f) 存储健康度等级：复用 storage-health 评分模型（重复率/配额/冷数据，权重 30/30/20；
	// 孤立率需磁盘扫描，本端点默认跳过按 0 计，避免 IO 放大）。
	duplicateRate := 0.0
	quotaUsage := 0.0
	ageScore := 1.0
	if totalCount > 0 {
		duplicateRate = float64(dupCount) / float64(totalCount)
		ageScore = 1.0 - float64(coldCount)/float64(totalCount)
	}
	if defaultQuotaBytes > 0 {
		quotaUsage = float64(usedBytes) / float64(defaultQuotaBytes)
	}
	dupPenalty := min01(duplicateRate) * 30
	quotaPenalty := min01(quotaUsage) * 30
	agePenalty := (1.0 - min01(ageScore)) * 20
	score := 100.0 - (dupPenalty + quotaPenalty + agePenalty)
	if score < 0 {
		score = 0
	}
	if score > 100 {
		score = 100
	}
	healthGrade := "D"
	switch {
	case score >= 85:
		healthGrade = "A"
	case score >= 70:
		healthGrade = "B"
	case score >= 50:
		healthGrade = "C"
	}

	// 汇聚洞察条目：仅当条件成立才产出，避免空建议噪音。
	type insight struct {
		Type      string `json:"type"`
		Title     string `json:"title"`
		Detail    string `json:"detail"`
		ActionURL string `json:"action_url,omitempty"`
	}
	insights := make([]insight, 0, 6)

	if dupCount > 0 {
		insights = append(insights, insight{
			Type:      "duplicates",
			Title:     fmt.Sprintf("你有 %d 个重复文件，可回收 %s", dupCount, formatBytes(dupReclaimable)),
			Detail:    fmt.Sprintf("重复文件占用 %s 存储空间，清理后可释放。重复率 %.1f%%。", formatBytes(dupReclaimable), duplicateRate*100),
			ActionURL: "/api/media/duplicate-cleanup",
		})
	}

	if totalCount > 0 && dominantType != "" {
		typeLabel := map[string]string{
			"IMAGE": "图片", "VIDEO": "视频", "LIVE_PHOTO": "动态照片",
		}[dominantType]
		if typeLabel == "" {
			typeLabel = dominantType
		}
		insights = append(insights, insight{
			Type:   "distribution",
			Title:  fmt.Sprintf("你的%s占 %.1f%% 存储空间", typeLabel, typePercent),
			Detail: fmt.Sprintf("媒体库共 %d 项、占用 %s，其中%s %d 项占 %s（%s）。", totalCount, formatBytes(usedBytes), typeLabel, typeCounts[dominantType], formatBytes(dominantTypeBytes), fmtPercent(typePercent)),
		})
	}

	if totalCount > 0 && dominantPeriod != "" {
		insights = append(insights, insight{
			Type:   "upload_habit",
			Title:  fmt.Sprintf("最常上传的时段是%s", dominantPeriod),
			Detail: fmt.Sprintf("在%s时段上传了 %d 项媒体，占上传总数的 %s。", dominantPeriod, periodCounts[dominantPeriod], fmtPercent(float64(periodCounts[dominantPeriod])/float64(totalCount)*100)),
		})
	}

	if untaggedCount > 0 {
		insights = append(insights, insight{
			Type:      "untagged",
			Title:     fmt.Sprintf("你有 %d 个未标签的媒体", untaggedCount),
			Detail:    fmt.Sprintf("未标签媒体占总数的 %s，建议打标签便于检索与整理。", fmtPercent(float64(untaggedCount)/float64(totalCount)*100)),
			ActionURL: "/api/media/tag-recommendations",
		})
	}

	if hasTopAlbum && topAlbumCount > 0 {
		insights = append(insights, insight{
			Type:      "top_album",
			Title:     fmt.Sprintf("相册'%s'照片最多，有 %d 项", topAlbumName, topAlbumCount),
			Detail:    fmt.Sprintf("相册'%s'内含 %d 项媒体，是你收录最多的相册。", topAlbumName, topAlbumCount),
			ActionURL: "/api/media/album/count-ranking",
		})
	}

	insights = append(insights, insight{
		Type:   "health",
		Title:  fmt.Sprintf("你的存储健康度为%s级", healthGrade),
		Detail: fmt.Sprintf("综合重复率（%s）、配额使用率（%s）、冷数据占比，存储健康度评分 %d/100，等级 %s。", fmtPercent(duplicateRate*100), fmtPercent(quotaUsage*100), int(score), healthGrade),
		ActionURL: "/api/media/storage-health",
	})

	writeJSON(w, http.StatusOK, map[string]any{
		"insights": insights,
		"total":    len(insights),
		"user_id":  uid,
	})
}

// formatBytes 把字节数格式化为人类可读的容量字符串（如 "12.3 MB"），用于洞察详情。
func formatBytes(b int64) string {
	const unit = 1024
	if b < unit {
		return fmt.Sprintf("%d B", b)
	}
	div, exp := int64(unit), 0
	for n := b / unit; n >= unit; n /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(b)/float64(div), "KMGTPE"[exp])
}

// fmtPercent 把百分比数值格式化为保留一位小数的字符串（如 "45.2%"），用于洞察详情。
func fmtPercent(v float64) string {
	return fmt.Sprintf("%.1f%%", v)
}
