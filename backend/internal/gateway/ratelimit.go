package gateway

import (
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

// ============ 登录暴力限速 ============
//
// PRD §2.7 安全剩余项：/api/auth/login 按 IP+username 限速，防暴力撞库。
// 采用轻量内存滑动窗口（不引外部依赖）：每个 (ip,username) 维护一个时间戳切片，
// 仅保留窗口内的命中次数，超限返回 429。后台 goroutine 每 5 分钟清理过期条目，
// 避免 sync.Map 无限增长。

// loginRateWindow 是登录限速的滑动窗口时长：1 分钟。
const loginRateWindow = time.Minute

// loginRateMax 是单个 (ip,username) 在窗口内允许的最大尝试次数。
const loginRateMax = 10

// loginRateCleanInterval 是清理过期条目的周期（5 分钟）。
// 取一个大于窗口的值，保证仍在窗口内的条目不会被误清。
const loginRateCleanInterval = 5 * time.Minute

// loginLimiterEntry 是单个 (ip,username) 的滑动窗口状态。
// hits 存窗口内命中时间戳（毫秒）；mu 保护并发裁剪/追加。
type loginLimiterEntry struct {
	mu   sync.Mutex
	hits []int64 // 窗口内命中时间戳（毫秒）
}

// loginRateLimiter 以 (ip+":"+username) 为键做滑动窗口限速。
// 零值即可用（NewLoginRateLimiter 启动后台清理 goroutine）。不引外部依赖，
// sync.Map + 切片时间戳实现，近似计数的精度足够防暴力场景。
type loginRateLimiter struct {
	entries sync.Map // key string -> *loginLimiterEntry
	stop    chan struct{}
}

// NewLoginRateLimiter 构造限速器并启动后台定期清理 goroutine。
// 清理逻辑用 time.Ticker 周期触发，遍历 map 删除全过期条目，防止长期运行
// 后被攻击者用大量随机用户名撑大内存（每个条目本身很小，但仍需兜底）。
func NewLoginRateLimiter() *loginRateLimiter {
	l := &loginRateLimiter{stop: make(chan struct{})}
	go l.startClean()
	return l
}

// Allow 判断 (ip,username) 是否仍在限速窗口内允许第 max 次以内的请求。
// 返回 true 表示放行（已计数），false 表示超限（调用方据此回 429）。
// hits 切片按需裁剪过期项，避免无限增长。
func (l *loginRateLimiter) Allow(ip, username string) bool {
	key := ip + "|" + username
	now := time.Now().UnixMilli()
	cutoff := now - loginRateWindow.Milliseconds()

	v, _ := l.entries.LoadOrStore(key, &loginLimiterEntry{})
	e := v.(*loginLimiterEntry)
	e.mu.Lock()
	defer e.mu.Unlock()

	// 裁剪窗口外命中，原地压缩。
	keep := e.hits[:0]
	for _, t := range e.hits {
		if t > cutoff {
			keep = append(keep, t)
		}
	}
	if len(keep) >= loginRateMax {
		e.hits = keep
		return false
	}
	e.hits = append(keep, now)
	return true
}

// startClean 周期性清理过期条目。每 5 分钟遍历一次，把窗口内已无剩余命中的
// 条目从 map 删除。停在 l.stop 上（进程中后台运行，退出自然回收，保持轻量）。
func (l *loginRateLimiter) startClean() {
	ticker := time.NewTicker(loginRateCleanInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			l.cleanExpired()
		case <-l.stop:
			return
		}
	}
}

// cleanExpired 遍历所有条目，删除窗口内无剩余命中的 key。
// 遍历 sync.Map 开销与条目数成正比；5 分钟一次且条目本就被限速约束，可接受。
func (l *loginRateLimiter) cleanExpired() {
	cutoff := time.Now().UnixMilli() - loginRateWindow.Milliseconds()
	l.entries.Range(func(k, v any) bool {
		e := v.(*loginLimiterEntry)
		e.mu.Lock()
		hasLive := false
		for _, t := range e.hits {
			if t > cutoff {
				hasLive = true
				break
			}
		}
		if !hasLive {
			e.hits = nil
		}
		e.mu.Unlock()
		if !hasLive {
			l.entries.Delete(k)
		}
		return true
	})
}

// ===== 上传并发限速 =====

// uploadConcurrentMax 是单用户允许的最大并发上传数。
// PRD §2.7：每用户最多 3 个并发上传，超限返回 429。
const uploadConcurrentMax = 3

// uploadSemaphores 按 user_id 维护每用户一把 buffered-channel 信号量，
// 限制该用户的在途上传数。信号量在用户首次上传时懒创建后复用。
var uploadSemaphores sync.Map // userID string -> chan struct{}

// AcquireUploadSlot 尝试为 user 占一个上传槽。成功返回 true，调用方须在上传
// 完成（无论成败）后调用 ReleaseUploadSlot 归还。超限返回 false（handler 回 429）。
// 用 select default 非阻塞 send："发送一个令牌进 channel"表示占位，
// "从 channel receive"表示释放。
func AcquireUploadSlot(userID string) bool {
	ch := uploadSlotChan(userID)
	select {
	case ch <- struct{}{}:
		return true
	default:
		return false
	}
}

// ReleaseUploadSlot 归还一个上传槽。须与 Acquire 配对调用。
func ReleaseUploadSlot(userID string) {
	v, ok := uploadSemaphores.Load(userID)
	if !ok {
		return
	}
	select {
	case <-v.(chan struct{}):
	default:
		// 防御性：若无令牌可取（不应对称配对调用下出现）静默忽略，避免阻塞。
	}
}

// uploadSlotChan 返回（懒创建）该用户的信号量 channel。
func uploadSlotChan(userID string) chan struct{} {
	v, _ := uploadSemaphores.LoadOrStore(userID, make(chan struct{}, uploadConcurrentMax))
	return v.(chan struct{})
}

// ===== IP 提取 =====

// clientIP 从请求中提取客户端 IP，优先 X-Forwarded-For 首段（经反代/负载均衡时
// RemoteAddr 是代理而非真实客户端），否则取 r.RemoteAddr 的 host 部分。
// X-Forwarded-For 形如 "client, proxy1, proxy2"，取第一个即最左端原始客户端。
func clientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		if i := strings.IndexByte(xff, ','); i >= 0 {
			return strings.TrimSpace(xff[:i])
		}
		return strings.TrimSpace(xff)
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		// 无法分割（如无端口）时直接返回原值——客户端 IP 退化为 RemoteAddr。
		return r.RemoteAddr
	}
	return host
}

// ============ 分享链接公开访问 IP 限速 ============
//
// 防暴力枚举短链：GET /api/share/{token} 与 /api/share/{token}/stream/{mediaId}
// 为公开访问端点（无需认证），攻击者可无凭证遍历 token 空间枚举有效分享链接。
// 对这些端点按客户端 IP 做滑动窗口限速：每个 IP 在 window 内最多 max 次请求，
// 超限返回 429 Too Many Requests。内存级实现，复用本文件 clientIP 提取逻辑。
//
// 与 loginRateLimiter（按 (ip,username) 维度，sync.Map）的区别：本限速器仅按 IP
// 维度（公开端点无 username），用 sync.Mutex+map 保护以满足并发安全要求；
// 后台 goroutine 周期清理过期条目，避免长期运行后被大量随机 IP 撑大内存。
//
// Server 在 NewServer 中始终创建一个实例（公开端点不限部署形态），
// 存于 Server.rateLimiter，在公开分享访问 handler 入口调用 Allow 做前置限速。

// shareRateWindow 是分享公开访问端点限速的滑动窗口时长：60 秒。
const shareRateWindow = 60 * time.Second

// shareRateMax 是单个 IP 在窗口内允许的最大请求次数：30（≈每 2 秒 1 次，
// 足以覆盖单条分享的正常查看/下载/刷新；远低于枚举攻击所需速率）。
const shareRateMax = 30

// rlEntry 是单个 IP 的滑动窗口状态。hits 存窗口内命中时间戳（毫秒）。
// 由父 RateLimiter.mu 串行保护，无需自带锁。
type rlEntry struct {
	hits []int64
}

// RateLimiter 是按 IP 做滑动窗口限速的并发安全限速器（用于公开分享访问端点）。
// 用 sync.Mutex 保护内部 map，后台 goroutine 周期清理过期条目。
// 零值不可用——须用 NewRateLimiter 构造（以启动清理 goroutine）。
type RateLimiter struct {
	mu      sync.Mutex
	entries map[string]*rlEntry
	window  time.Duration
	max     int
	stop    chan struct{}
}

// NewRateLimiter 构造一个滑动窗口限速器：每个 IP 在 window 内最多 maxRequests 次。
// window<=0 或 maxRequests<=0 视为非法，分别回退为 shareRateWindow / shareRateMax
// （构造即始终可用，防御调用方误传零值）。启动后台 goroutine 周期清理过期条目
// （周期 = window，取大于等于窗口可保证仍在窗口内的条目不会被误清）。
func NewRateLimiter(window time.Duration, maxRequests int) *RateLimiter {
	if window <= 0 {
		window = shareRateWindow
	}
	if maxRequests <= 0 {
		maxRequests = shareRateMax
	}
	r := &RateLimiter{
		entries: make(map[string]*rlEntry),
		window:  window,
		max:     maxRequests,
		stop:    make(chan struct{}),
	}
	go r.startClean()
	return r
}

// Allow 判断 ip 是否仍在窗口内允许第 max 次以内的请求。
// 返回 true 表示放行（已计数），false 表示超限（调用方据此回 429）。
// 惰性裁剪：每次检查时原地丢弃窗口外的时间戳，避免切片无限增长。
func (r *RateLimiter) Allow(ip string) bool {
	now := time.Now().UnixMilli()
	cutoff := now - r.window.Milliseconds()
	r.mu.Lock()
	defer r.mu.Unlock()
	e, ok := r.entries[ip]
	if !ok {
		e = &rlEntry{}
		r.entries[ip] = e
	}
	keep := e.hits[:0]
	for _, t := range e.hits {
		if t > cutoff {
			keep = append(keep, t)
		}
	}
	if len(keep) >= r.max {
		e.hits = keep
		return false
	}
	e.hits = append(keep, now)
	return true
}

// startClean 周期性清理过期条目。停在 r.stop 上（进程后台运行，退出自然回收）。
func (r *RateLimiter) startClean() {
	ticker := time.NewTicker(r.window)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			r.cleanExpired()
		case <-r.stop:
			return
		}
	}
}

// cleanExpired 遍历所有条目，删除窗口内无剩余命中的 key。
// 遍历开销与条目数成正比；周期 = window 且条目已被限速约束，可接受。
func (r *RateLimiter) cleanExpired() {
	cutoff := time.Now().UnixMilli() - r.window.Milliseconds()
	r.mu.Lock()
	defer r.mu.Unlock()
	for k, e := range r.entries {
		live := false
		for _, t := range e.hits {
			if t > cutoff {
				live = true
				break
			}
		}
		if !live {
			delete(r.entries, k)
		}
	}
}
