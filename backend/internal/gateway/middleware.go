package gateway

import (
	"context"
	"log/slog"
	"net"
	"net/http"
	"os"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ============ 结构化日志 + request id 中间件 ============
//
// 本文件实现 PRD §2.6 可观测性的"结构化日志 + request id"部分：
//   1. initLogger 用 log/slog 建立包级 logger（JSON handler，便于采集解析）；
//      gateway 包内的访问日志与关键事件经 slog 输出，main.go 的 log.Printf 保留兼容。
//   2. requestIDMiddleware 为每个请求生成 UUID，注入 context 与 X-Request-ID 响应头，
//      供链路追踪与日志关联。
//   3. accessLogMiddleware 记录每个请求的 method/path/status/latency/user_id，
//      user_id 从 context 取（经 service.UserIDFromContext），脱敏为是否存在而非裸值。
//
// 设计决策：
//   - 用 stdlib log/slog 而非第三方库，避免引入依赖、与 Go 1.21+ 运行时对齐。
//   - slog handler 选 JSON 格式：行式文本虽对人友好，但 JSON 便于 ELK/Loki 等采集后
//     按字段检索；与 main.go 保留的 log.Printf（行式）并存不冲突——前者面向采集，后者
//     面向人眼排查。
//   - user_id 脱敏：访问日志只记 anon/authed + 前 8 字符，不落完整 UUID，
//     降低日志泄露面（日志可能经采集流转多跳）。

// logger 是 gateway 包共享的结构化日志器。initLogger 赋值；未初始化时回退 slog.Default。
var logger *slog.Logger = slog.Default()

// initLogger 用 JSON handler 构造包级 slog logger。
// level 透传调用方指定（支持 debug 诊断）。由 NewServer 调用一次；
// 重复调用仅最后一次生效（测试场景覆盖）。
func initLogger(level slog.Level) {
	opts := &slog.HandlerOptions{Level: level}
	logger = slog.New(slog.NewJSONHandler(logWriter, opts))
}

// logWriter 是 slog handler 的输出目的地。
// os.Stderr——与标准 log 包（默认 stderr）一致，避免 stdout 被 ServeFile 等
// 响应混入。main.go 的 log.Printf 仍走 log 包默认，二者各走各的，互不干扰。
var logWriter = os.Stderr

// requestIDCtxKey 是 context 中携带 request id 的键。
// 独立于 service.UserIDFromContext 的 user_id 键（不同类型、不同语义），
// 避免 handler 误读。
type requestIDCtxKey struct{}

// requestIDFromContext 取出中间件注入的 request id；未注入返回空串。
func requestIDFromContext(ctx context.Context) string {
	if v, ok := ctx.Value(requestIDCtxKey{}).(string); ok {
		return v
	}
	return ""
}

// ── requestIDMiddleware ──

// requestIDMiddleware 为每个请求生成 UUID v4，注入：
//   - context（供下游 handler/日志关联）
//   - X-Request-ID 响应头（供客户端/代理回传关联）
// 若请求已带 X-Request-ID 头（如经上游代理注入），则透传复用而非覆盖，
// 以保持跨服务链路一致。
func (s *Server) requestIDMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rid := r.Header.Get("X-Request-ID")
		if rid == "" {
			rid = uuid.New().String()
		}
		// 响应头注入，使客户端/日志采集侧能据 rid 关联同一请求的多条日志。
		w.Header().Set("X-Request-ID", rid)
		ctx := context.WithValue(r.Context(), requestIDCtxKey{}, rid)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

// ── statusRecorder：捕获下游 WriteHeader 的状态码 ──
//
// accessLogMiddleware 需要知道最终响应状态码，但 http.ResponseWriter 不暴露它。
// 这里用薄包装拦截 WriteHeader 记录 status，其余 Write/Hijack/Flush 透传原 writer。
// 注意：未显式调用 WriteHeader 时 net/http 默认 200，故 status 默认值取 200。
type statusRecorder struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func newStatusRecorder(w http.ResponseWriter) *statusRecorder {
	return &statusRecorder{ResponseWriter: w, status: http.StatusOK}
}

func (r *statusRecorder) WriteHeader(code int) {
	if r.wrote {
		// 重复 WriteHeader 由 net/http 自身忽略；这里同样只记首次，避免日志误判。
		return
	}
	r.status = code
	r.wrote = true
	r.ResponseWriter.WriteHeader(code)
}

func (r *statusRecorder) Write(b []byte) (int, error) {
	// 记录 200 的隐式 Write：net/http 在首次 Write 前若未 WriteHeader 会隐式 200，
	// 这里同步标记以反映真实状态。
	if !r.wrote {
		r.wrote = true
	}
	return r.ResponseWriter.Write(b)
}

// ── accessLogMiddleware ──

// accessLogMiddleware 记录每个请求的结构化访问日志并更新请求计数/延迟指标。
// 日志字段：method、path、status、latency_ms、request_id、user（脱敏）、
//           remote（客户端 IP，脱敏为 /24）。
//
// 链路位置：CORS → requestID → auth → [accessLog] → mux。
// authMiddleware 在本中间件之外：校验通过后用 r.WithContext(ctx) 造带 user_id 的
// 新 Request 传给本中间件，故本中间件持有的 r 已是带 user_id 的新 r（而非原始 r）。
// next.ServeHTTP(rec, r) 传给 mux 的也是同一个 r，next 返回后读 r.Context() 即可
// 拿到 auth 注入的 user_id。豁免路径（login/register/healthz/metrics）与未认证请求
// auth 不注入 user_id，此处读到空串记为 anon，符合预期。
//
// 注意：此前链路为 CORS → requestID → accessLog → auth → mux（accessLog 在 auth 之外），
// auth 用 r.WithContext 的新 r 只传给 mux，accessLog 持有的仍是原始 r，其 context
// 永远不含 user_id，导致全部记为 anon（QA P0-2）。调换为 auth → accessLog 后修复。
//
// 指标：method/path/status 计数与延迟经 s.metrics.RecordRequest 记录；
// path 用 normalizePath 折叠 ID 段，避免高基数拖垮 Prometheus。
func (s *Server) accessLogMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := newStatusRecorder(w)
		next.ServeHTTP(rec, r)
		latency := time.Since(start)

		// user_id 脱敏：仅记 anon/authed + 前 8 字符，不落完整 UUID。
		uid := userIDFromContext(r.Context())
		userTag := "anon"
		if uid != "" {
			if len(uid) > 8 {
				userTag = "authed:" + uid[:8]
			} else {
				userTag = "authed:" + uid
			}
		}

		rid := requestIDFromContext(r.Context())

		logger.LogAttrs(r.Context(), slog.LevelInfo, "http_request",
			slog.String("method", r.Method),
			slog.String("path", r.URL.Path),
			slog.Int("status", rec.status),
			slog.Int64("latency_ms", latency.Milliseconds()),
			slog.String("request_id", rid),
			slog.String("user", userTag),
			slog.String("remote", remoteMasked(r)),
		)

		// 指标记录：path 归一化避免高基数（如 /api/media/stream/<uuid> 折叠为 /:id）。
		if s.metrics != nil {
			s.metrics.RecordRequest(r.Method, normalizePath(r.URL.Path), http.StatusText(rec.status), latency)
		}
	})
}

// remoteMasked 返回脱敏后的客户端 IP（保留前 3 段，末段置 0，/24）。
// 复用 ratelimit.go 的 clientIP() 取原始客户端 IP（优先 X-Forwarded-For 首段，
// 否则 RemoteAddr 的 host），再对 IPv4 做 /24 掩码。IPv6 原样返回（内网多为 v4，
// v6 脱敏收益低）。脱敏目的：日志可定位到网段用于排障，又不泄露具体主机。
func remoteMasked(r *http.Request) string {
	ip := clientIP(r)
	parsed := net.ParseIP(ip)
	if parsed == nil || parsed.To4() == nil {
		return ip // 非 IPv4 原样返回。
	}
	// IPv4 点分四段：末段置 0，实现 /24 掩码。
	parts := strings.Split(ip, ".")
	if len(parts) == 4 {
		return parts[0] + "." + parts[1] + "." + parts[2] + ".0"
	}
	return ip
}
