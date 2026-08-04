package gateway

import (
	"fmt"
	"io"
	"net/http"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"media-manager/backend/internal/service"
)

// ============ /metrics 端点（Prometheus 文本格式）============
//
// 设计目标：在不引入 prometheus/client_golang 的前提下，暴露与 Prometheus
// 抓取兼容的文本格式指标（text exposition format, 0.0.4）。
//
// 指标族：
//   - http_requests_total{method,path,status}    counter    请求计数
//   - http_request_duration_seconds{method,path} histogram   延迟直方图（手写分桶）
//   - http_request_duration_seconds_sum{...}      counter    延迟累计（histogram 配套）
//   - http_request_duration_seconds_count{...}    counter    延迟样本数（histogram 配套）
//   - media_upload_bytes_total                    counter    上传字节累计
//   - sync_changes_served_total                   counter    sync/changes 拉取条目累计
//   - cache_hits_total{cache="list|thumb"}        counter    缓存命中
//   - cache_misses_total{cache="list|thumb"}      counter    缓存未命中
//   - cache_hit_ratio{cache="list|thumb"}         gauge      缓存命中率（0..1）
//   - db_pool_open_connections                    gauge      DB 连接池当前打开数
//   - db_pool_in_use_connections                  gauge      DB 连接池当前使用数
//   - db_pool_max_open_connections                gauge      DB 连接池上限
//   - go_goroutines                               gauge      goroutine 数
//   - go_memstats_alloc_bytes                     gauge      堆已分配
//   - go_memstats_sys_bytes                       gauge      进程向 OS 索取内存
//   - go_memstats_heap_inuse_bytes                gauge      堆 in-use
//   - go_gc_count                                 gauge      GC 次数
//
// 所有指标由 metricsRegistry 集中存储，并发安全（内部自带 mutex）。
// /metrics handler 读取一次快照后渲染为 Prometheus text format。

// ── metricsRegistry：进程级指标存储 ──

// histogramBuckets 是 http_request_duration_seconds 的延迟分桶边界（秒）。
// 覆盖 5ms ~ 10s 的常见 HTTP 延迟区间，与 prometheus默认勒让德分桶接近。
var histogramBuckets = []float64{
	0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10,
}

// reqKey 是按 method/path/status 聚合的请求指标键。
type reqKey struct {
	method string
	path   string
	status string
}

// reqKeyNoStatus 用于延迟直方图（不按 status 分桶，避免桶数爆炸）。
type reqKeyNoStatus struct {
	method string
	path   string
}

// errorEntry 记录一条 5xx 错误的元数据，供 observability-dashboard 与
// recent_errors 展示。不落完整请求体（隐私 + 体积），只记定位排障所需字段。
type errorEntry struct {
	Time     time.Time // 错误发生时刻
	Method   string    // HTTP 方法
	Path     string    // 归一化后的路径（normalizePath）
	Status   int       // 原始状态码（如 500/502/503）
	ReqID    string    // 关联 X-Request-ID，便于回溯链路
	UserTag  string    // 脱敏用户标记（与访问日志同口径：anon / authed:<前8位>）
}

// metricsRegistry 收集本进程的全部可观测指标。
// 访问日志中间件与业务 handler 通过 Record* 方法写入；/metrics handler 通过 Snapshot 读取。
type metricsRegistry struct {
	mu sync.Mutex

	// 请求计数：按 method/path/status 维度。
	reqCounters map[reqKey]int64

	// 延迟直方图：每个 (method,path) 维度一组。
	histSum   map[reqKeyNoStatus]float64 // 累计延迟（秒）
	histCount map[reqKeyNoStatus]int64   // 样本数
	histBuckets map[reqKeyNoStatus][]int64 // 各桶累计计数（与 histogramBuckets 对齐）

	// 上传字节累计。
	uploadBytes int64
	// sync/changes 拉取条目累计。
	syncChangesServed int64

	// PRD-v8 §3.1 可观测性扩展：上传成功率与按端点错误率。
	// uploadAttempts/successes 由 accessLogMiddleware 在请求结束时按 path+status 派生
	// （/api/media/upload 的 2xx 计成功，非 2xx 计失败）。totalAPIErrors 用于
	// /metrics 的全局 api_error_rate；recentErrors 缓存最近若干条 5xx 以供 dashboard。
	uploadAttempts int64
	uploadSuccesses int64
	recentErrors   []errorEntry
	recentErrorsMax int // 缓存上限，默认 50；0 表示不缓存
}

// newMetricsRegistry 返回一个空 registry。
func newMetricsRegistry() *metricsRegistry {
	return &metricsRegistry{
		reqCounters:     make(map[reqKey]int64),
		histSum:         make(map[reqKeyNoStatus]float64),
		histCount:       make(map[reqKeyNoStatus]int64),
		histBuckets:     make(map[reqKeyNoStatus][]int64),
		recentErrorsMax: 50,
	}
}

// RecordRequest 记录一次请求的计数与延迟。
// latency 为该请求处理耗时；render 时按分桶累计。
func (m *metricsRegistry) RecordRequest(method, path, status string, latency time.Duration) {
	m.mu.Lock()
	defer m.mu.Unlock()
	k := reqKey{method: method, path: path, status: status}
	m.reqCounters[k]++

	sec := latency.Seconds()
	nk := reqKeyNoStatus{method: method, path: path}
	m.histSum[nk] += sec
	m.histCount[nk]++

	bk := m.histBuckets[nk]
	if bk == nil {
		bk = make([]int64, len(histogramBuckets))
		m.histBuckets[nk] = bk
	}
	for i, le := range histogramBuckets {
		if sec <= le {
			bk[i]++
		}
	}
}

// RecordUploadBytes 累加上传字节数。
func (m *metricsRegistry) RecordUploadBytes(n int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.uploadBytes += n
}

// RecordSyncChanges 累加本次 /api/sync/changes 返回的变更条目数。
func (m *metricsRegistry) RecordSyncChanges(n int) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.syncChangesServed += int64(n)
}

// RecordUploadOutcome 记录一次上传尝试的结果（PRD-v8 §3.1 upload_success_rate）。
// success=true 计入成功与总尝试；false 仅计入总尝试。由 accessLogMiddleware 在
// /api/media/upload 请求结束时按状态码派生调用（2xx→success）。
func (m *metricsRegistry) RecordUploadOutcome(success bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.uploadAttempts++
	if success {
		m.uploadSuccesses++
	}
}

// RecordError 追加一条 5xx 错误记录到 recentErrors 环形缓存（PRD-v8 §3.1 recent_errors）。
// 超过 recentErrorsMax 时丢弃最旧条目（FIFO），保持缓存固定大小。entry.Time 由调用方
// 填入（通常为 time.Now）；其余字段从请求上下文与响应状态码取。
func (m *metricsRegistry) RecordError(entry errorEntry) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.recentErrorsMax <= 0 {
		return
	}
	m.recentErrors = append(m.recentErrors, entry)
	if len(m.recentErrors) > m.recentErrorsMax {
		// 丢弃最旧的一条，保持切片不无限增长。
		m.recentErrors = m.recentErrors[len(m.recentErrors)-m.recentErrorsMax:]
	}
}

// snapshot 是 registry 在某一时刻的不可变快照，供渲染时免锁读取。
type snapshot struct {
	reqCounters   map[reqKey]int64
	histSum       map[reqKeyNoStatus]float64
	histCount     map[reqKeyNoStatus]int64
	histBuckets   map[reqKeyNoStatus][]int64
	uploadBytes   int64
	syncChanges   int64
	// PRD-v8 §3.1 扩展字段
	uploadAttempts  int64
	uploadSuccesses int64
	recentErrors    []errorEntry // 深拷贝，避免调用方与 registry 共享底层切片
}

// Snapshot 取一份当前 registry 的深拷贝快照（含 maps 的浅拷贝）。
func (m *metricsRegistry) Snapshot() snapshot {
	m.mu.Lock()
	defer m.mu.Unlock()
	s := snapshot{
		uploadBytes:     m.uploadBytes,
		syncChanges:     m.syncChangesServed,
		uploadAttempts:  m.uploadAttempts,
		uploadSuccesses: m.uploadSuccesses,
	}
	// recentErrors 深拷贝：快照应与 registry 后续写入隔离。
	if len(m.recentErrors) > 0 {
		s.recentErrors = make([]errorEntry, len(m.recentErrors))
		copy(s.recentErrors, m.recentErrors)
	}
	if len(m.reqCounters) > 0 {
		s.reqCounters = make(map[reqKey]int64, len(m.reqCounters))
		for k, v := range m.reqCounters {
			s.reqCounters[k] = v
		}
	}
	if len(m.histSum) > 0 {
		s.histSum = make(map[reqKeyNoStatus]float64, len(m.histSum))
		s.histCount = make(map[reqKeyNoStatus]int64, len(m.histCount))
		s.histBuckets = make(map[reqKeyNoStatus][]int64, len(m.histBuckets))
		for k, v := range m.histSum {
			s.histSum[k] = v
		}
		for k, v := range m.histCount {
			s.histCount[k] = v
		}
		for k, v := range m.histBuckets {
			cp := make([]int64, len(v))
			copy(cp, v)
			s.histBuckets[k] = cp
		}
	}
	return s
}

// ── normalizePath：把带 id 的路径归一化为指标标签 ──
//
// 直接用原始 path 做标签会因 /api/media/stream/<uuid> 造成高基数（每个 media_id
// 一个序列），拖垮 Prometheus 内存。这里把含 ID 段的路径折叠为占位符：
//   /api/media/stream/abc-123   -> /api/media/stream/:id
//   /api/media/metadata/abc     -> /api/media/metadata/:id
//   /api/media/thumbnail/abc    -> /api/media/thumbnail/:id
//   /api/media/video-info/abc   -> /api/media/video-info/:id
//   /api/media/album/abc        -> /api/media/album/:id
// 无 ID 的固定路径原样返回（/api/media/list 等）。
func normalizePath(path string) string {
	// 前缀式带 id 的端点：去前缀后剩一段即为 id。
	idPrefixes := []string{
		"/api/media/stream/",
		"/api/media/thumbnail/",
		"/api/media/metadata/",
		"/api/media/video-info/",
		"/api/media/album/",
	}
	for _, p := range idPrefixes {
		if strings.HasPrefix(path, p) {
			return p + ":id"
		}
	}
	return path
}

// ── /metrics handler ──

// handleMetrics 渲染全部指标为 Prometheus text exposition format。
// 不要求认证（authMiddleware 豁免 /metrics，同 /healthz）。
func (s *Server) handleMetrics(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	s.renderMetrics(w)
}

// renderMetrics 把 registry 快照 + runtime/cache/db 指标写入 w。
func (s *Server) renderMetrics(w io.Writer) {
	var b strings.Builder

	// ---- http_requests_total ----
	b.WriteString("# HELP http_requests_total Total HTTP requests processed by method/path/status.\n")
	b.WriteString("# TYPE http_requests_total counter\n")
	if s.metrics != nil {
		sn := s.metrics.Snapshot()
		// 计数器按 key 稳定顺序输出（避免抓取间无谓抖动）。
		keys := make([]reqKey, 0, len(sn.reqCounters))
		for k := range sn.reqCounters {
			keys = append(keys, k)
		}
		sortReqKeys(keys)
		for _, k := range keys {
			b.WriteString(fmt.Sprintf("http_requests_total{method=%q,path=%q,status=%q} %d\n",
				k.method, k.path, k.status, sn.reqCounters[k]))
		}

		// ---- http_request_duration_seconds histogram ----
		b.WriteString("# HELP http_request_duration_seconds HTTP request latency distribution.\n")
		b.WriteString("# TYPE http_request_duration_seconds histogram\n")
		nk := make([]reqKeyNoStatus, 0, len(sn.histCount))
		for k := range sn.histCount {
			nk = append(nk, k)
		}
		sortReqKeyNoStatus(nk)
		for _, k := range nk {
			buckets := sn.histBuckets[k]
			for i, le := range histogramBuckets {
				b.WriteString(fmt.Sprintf("http_request_duration_seconds_bucket{method=%q,path=%q,le=%q} %d\n",
					k.method, k.path, trimFloat(le), buckets[i]))
			}
			b.WriteString(fmt.Sprintf("http_request_duration_seconds_bucket{method=%q,path=%q,le=\"+Inf\"} %d\n",
				k.method, k.path, sn.histCount[k]))
			b.WriteString(fmt.Sprintf("http_request_duration_seconds_sum{method=%q,path=%q} %s\n",
				k.method, k.path, trimFloat(sn.histSum[k])))
			b.WriteString(fmt.Sprintf("http_request_duration_seconds_count{method=%q,path=%q} %d\n",
				k.method, k.path, sn.histCount[k]))
		}

		// ---- media_upload_bytes_total ----
		b.WriteString("# HELP media_upload_bytes_total Total bytes received via media uploads.\n")
		b.WriteString("# TYPE media_upload_bytes_total counter\n")
		b.WriteString(fmt.Sprintf("media_upload_bytes_total %d\n", sn.uploadBytes))

		// ---- sync_changes_served_total ----
		b.WriteString("# HELP sync_changes_served_total Total sync change items served by /api/sync/changes.\n")
		b.WriteString("# TYPE sync_changes_served_total counter\n")
		b.WriteString(fmt.Sprintf("sync_changes_served_total %d\n", sn.syncChanges))

		// ---- PRD-v8 §3.1 可观测性扩展指标 ----
		// upload_success_rate：成功上传数 / 总尝试数（0..1）。无尝试时输出 0。
		// 由 accessLogMiddleware 在 /api/media/upload 请求结束时按 2xx 判定调用 RecordUploadOutcome。
		b.WriteString("# HELP media_upload_attempts_total Total upload attempts (any status code) to /api/media/upload.\n")
		b.WriteString("# TYPE media_upload_attempts_total counter\n")
		b.WriteString(fmt.Sprintf("media_upload_attempts_total %d\n", sn.uploadAttempts))
		b.WriteString("# HELP media_upload_successes_total Successful (2xx) upload count to /api/media/upload.\n")
		b.WriteString("# TYPE media_upload_successes_total counter\n")
		b.WriteString(fmt.Sprintf("media_upload_successes_total %d\n", sn.uploadSuccesses))
		b.WriteString("# HELP media_upload_success_rate Upload success rate (successes/attempts, 0..1).\n")
		b.WriteString("# TYPE media_upload_success_rate gauge\n")
		var uploadRate float64
		if sn.uploadAttempts > 0 {
			uploadRate = float64(sn.uploadSuccesses) / float64(sn.uploadAttempts)
		}
		b.WriteString(fmt.Sprintf("media_upload_success_rate %s\n", trimFloat(uploadRate)))

		// api_error_rate_by_endpoint：按归一化 path 聚合的 5xx 占比（5xx/(2xx+4xx+5xx+...)）。
		// 从 reqCounters 按 path 聚合 total 与 5xx，输出每端点一条 gauge 行。
		// 仅输出至少有一条 5xx 记录的端点，避免无错误端点刷屏（错误率 0 由 absence 表达，
		// Prometheus 语义中缺失即 0）。
		b.WriteString("# HELP api_error_rate_by_endpoint Fraction of 5xx responses per normalized endpoint path.\n")
		b.WriteString("# TYPE api_error_rate_by_endpoint gauge\n")
		type pathAgg struct{ total, errCount int64 }
		agg := make(map[string]*pathAgg)
		for k, cnt := range sn.reqCounters {
			a, ok := agg[k.path]
			if !ok {
				a = &pathAgg{}
				agg[k.path] = a
			}
			a.total += cnt
			// status 形如 "Internal Server Error"（http.StatusText）；按 5xx 词头判定不可靠，
			// 这里用 key 字符串前缀匹配 5xx——但 reqKey.status 存的是 StatusText 而非码。
			// 为稳健判定，改为解析码：见下方 endpointErrRateFromStatusText。
			if isStatusText5xx(k.status) {
				a.errCount += cnt
			}
		}
		// 稳定排序输出（按 path 字典序）。
		paths := make([]string, 0, len(agg))
		for p := range agg {
			paths = append(paths, p)
		}
		sortStrings(paths)
		for _, p := range paths {
			a := agg[p]
			if a.errCount == 0 {
				continue // 仅输出有 5xx 的端点。
			}
			var rate float64
			if a.total > 0 {
				rate = float64(a.errCount) / float64(a.total)
			}
			b.WriteString(fmt.Sprintf("api_error_rate_by_endpoint{path=%q} %s\n", p, trimFloat(rate)))
		}
	} else {
		// registry 未初始化（应不发生，NewServer 保证注入）；输出零值保证格式完整。
		b.WriteString("media_upload_bytes_total 0\n")
		b.WriteString("sync_changes_served_total 0\n")
		b.WriteString("media_upload_attempts_total 0\n")
		b.WriteString("media_upload_successes_total 0\n")
		b.WriteString("media_upload_success_rate 0\n")
	}

	// ---- cache 指标（list + thumb）----
	s.renderCacheMetrics(&b)

	// ---- DB 连接池状态 ----
	s.renderDBPoolMetrics(&b)

	// ---- Go runtime 指标 ----
	renderRuntimeMetrics(&b)

	_, _ = io.WriteString(w, b.String())
}

// renderCacheMetrics 渲染 list 缓存与缩略图缓存的命中/未命中/命中率。
// list 缓存来自 service.GetListCacheStats（进程级原子计数）；
// thumb 缓存来自 mediaSvc 的 ThumbCacheStats()（仅 *service.MediaService 实现）。
func (s *Server) renderCacheMetrics(b *strings.Builder) {
	b.WriteString("# HELP cache_hits_total Cache hit count by cache name.\n")
	b.WriteString("# TYPE cache_hits_total counter\n")
	b.WriteString("# HELP cache_misses_total Cache miss count by cache name.\n")
	b.WriteString("# TYPE cache_misses_total counter\n")
	b.WriteString("# HELP cache_hit_ratio Cache hit ratio (0..1) by cache name.\n")
	b.WriteString("# TYPE cache_hit_ratio gauge\n")

	// List cache（进程级计数，始终可得）。
	listHits, listMisses := service.GetListCacheStats()
	listTotal := listHits + listMisses
	var listRatio float64
	if listTotal > 0 {
		listRatio = float64(listHits) / float64(listTotal)
	}
	b.WriteString(fmt.Sprintf("cache_hits_total{cache=\"list\"} %d\n", listHits))
	b.WriteString(fmt.Sprintf("cache_misses_total{cache=\"list\"} %d\n", listMisses))
	b.WriteString(fmt.Sprintf("cache_hit_ratio{cache=\"list\"} %s\n", trimFloat(listRatio)))

	// Thumb cache（仅 *service.MediaService 实现 ThumbCacheStats）。
	if provider, ok := s.mediaSvc.(thumbCacheProvider); ok {
		ts := provider.ThumbCacheStats()
		tTotal := ts.Hits + ts.Misses
		var tRatio float64
		if tTotal > 0 {
			tRatio = float64(ts.Hits) / float64(tTotal)
		}
		b.WriteString(fmt.Sprintf("cache_hits_total{cache=\"thumb\"} %d\n", ts.Hits))
		b.WriteString(fmt.Sprintf("cache_misses_total{cache=\"thumb\"} %d\n", ts.Misses))
		b.WriteString(fmt.Sprintf("cache_hit_ratio{cache=\"thumb\"} %s\n", trimFloat(tRatio)))
	}
}

// renderDBPoolMetrics 渲染 SQLite 连接池状态（store 未注入时跳过本组指标）。
// modernc.org/sqlite 经 database/sql 暴露 *sql.DB.Stats()，可取 OpenConnections/
// InUse/MaxOpenConnections。
func (s *Server) renderDBPoolMetrics(b *strings.Builder) {
	if s.store == nil {
		return
	}
	db := s.store.DB()
	if db == nil {
		return
	}
	st := db.Stats()
	b.WriteString("# HELP db_pool_open_connections Current number of open DB connections.\n")
	b.WriteString("# TYPE db_pool_open_connections gauge\n")
	b.WriteString(fmt.Sprintf("db_pool_open_connections %d\n", st.OpenConnections))
	b.WriteString("# HELP db_pool_in_use_connections Number of DB connections currently in use.\n")
	b.WriteString("# TYPE db_pool_in_use_connections gauge\n")
	b.WriteString(fmt.Sprintf("db_pool_in_use_connections %d\n", st.InUse))
	b.WriteString("# HELP db_pool_max_open_connections Maximum number of open DB connections allowed.\n")
	b.WriteString("# TYPE db_pool_max_open_connections gauge\n")
	b.WriteString(fmt.Sprintf("db_pool_max_open_connections %d\n", st.MaxOpenConnections))
}

// renderRuntimeMetrics 渲染 Go runtime 进程级指标：goroutine 数与 memstats。
func renderRuntimeMetrics(b *strings.Builder) {
	b.WriteString("# HELP go_goroutines Number of running goroutines.\n")
	b.WriteString("# TYPE go_goroutines gauge\n")
	b.WriteString(fmt.Sprintf("go_goroutines %d\n", runtime.NumGoroutine()))

	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	b.WriteString("# HELP go_memstats_alloc_bytes Number of bytes allocated and still in use.\n")
	b.WriteString("# TYPE go_memstats_alloc_bytes gauge\n")
	b.WriteString(fmt.Sprintf("go_memstats_alloc_bytes %d\n", ms.Alloc))
	b.WriteString("# HELP go_memstats_sys_bytes Number of bytes obtained from system.\n")
	b.WriteString("# TYPE go_memstats_sys_bytes gauge\n")
	b.WriteString(fmt.Sprintf("go_memstats_sys_bytes %d\n", ms.Sys))
	b.WriteString("# HELP go_memstats_heap_inuse_bytes Bytes in in-use heap spans.\n")
	b.WriteString("# TYPE go_memstats_heap_inuse_bytes gauge\n")
	b.WriteString(fmt.Sprintf("go_memstats_heap_inuse_bytes %d\n", ms.HeapInuse))
	b.WriteString("# HELP go_gc_count Total number of completed GC cycles.\n")
	b.WriteString("# TYPE go_gc_count gauge\n")
	b.WriteString(fmt.Sprintf("go_gc_count %d\n", ms.NumGC))
}

// ── 工具函数 ──

// trimFloat 把浮点数格式化为 Prometheus 最紧凑字符串（去掉末尾多余零）。
// 例：1.0 -> "1", 0.005 -> "0.005", 2.5 -> "2.5"。
func trimFloat(f float64) string {
	return strconv.FormatFloat(f, 'g', -1, 64)
}

// sortReqKeys / sortReqKeyNoStatus 对指标键稳定排序，使输出序列在多次抓取间不抖动。
func sortReqKeys(keys []reqKey) {
	// 简单选择排序（键数量通常 < 百级，无需引入 sort + 闭包开销）。
	for i := 0; i < len(keys); i++ {
		min := i
		for j := i + 1; j < len(keys); j++ {
			if lessReqKey(keys[j], keys[min]) {
				min = j
			}
		}
		keys[i], keys[min] = keys[min], keys[i]
	}
}

func lessReqKey(a, b reqKey) bool {
	if a.method != b.method {
		return a.method < b.method
	}
	if a.path != b.path {
		return a.path < b.path
	}
	return a.status < b.status
}

func sortReqKeyNoStatus(keys []reqKeyNoStatus) {
	for i := 0; i < len(keys); i++ {
		min := i
		for j := i + 1; j < len(keys); j++ {
			if lessReqKeyNoStatus(keys[j], keys[min]) {
				min = j
			}
		}
		keys[i], keys[min] = keys[min], keys[i]
	}
}

func lessReqKeyNoStatus(a, b reqKeyNoStatus) bool {
	if a.method != b.method {
		return a.method < b.method
	}
	return a.path < b.path
}

// ── PRD-v8 §3.1 可观测性扩展辅助 ──

// statusText5xxSet 是 http.StatusText(5xx) 的集合，用于从 reqKey.status（存的是
// StatusText 而非数字码）反推是否属于 5xx。覆盖标准 500-511 与常见自定义 5xx。
// 5xx StatusText 多数以 "Internal Server Error" 等形式存在，纯字符串前缀判定不可靠
// （如 500 与 501 均含 "Error"），故用精确集合匹配。
var statusText5xxSet = func() map[string]struct{} {
	m := make(map[string]struct{})
	for code := 500; code <= 511; code++ {
		if t := http.StatusText(code); t != "" {
			m[t] = struct{}{}
		}
	}
	return m
}()

// isStatusText5xx 判断给定 StatusText 字符串是否对应一个 5xx 状态码。
// 空串（理论上不应出现——RecordRequest 总会传 http.StatusText）返回 false。
func isStatusText5xx(statusText string) bool {
	_, ok := statusText5xxSet[statusText]
	return ok
}

// sortStrings 对字符串切片做原地字典序排序（小规模，避免引入 sort 包开销）。
// 与 sortReqKeys 同策略：选择排序，键数量通常 < 百级。
func sortStrings(a []string) {
	for i := 0; i < len(a); i++ {
		min := i
		for j := i + 1; j < len(a); j++ {
			if a[j] < a[min] {
				min = j
			}
		}
		a[i], a[min] = a[min], a[i]
	}
}
