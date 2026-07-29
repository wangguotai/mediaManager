// Command ops-server 是运营服务端的可执行入口。
//
// 它把 internal/ 下各组件（auth/storage/discovery/relay）组装为一个进程：
//   - HTTP(:18789)：挂载 /admin/* 前端 + JSON API；未来可扩展发现 query/信令 WS。
//   - TCP(:18790)：TURN 式中继，接受配对连接并记账。
//
// 启动时若无运营账号且提供了 MM_OPS_BOOTSTRAP_ADMIN=user:pass，则创建首位 admin，
// 使登录可用（signup=first 模式下首位即 admin）。这是"独立管理员账号"的最小接入路径。
//
// 设计取舍：
//   - 不导入 ws/signaling 的 WS 升级实现（需引第三方 WS 库），故本命令暂不暴露 WS 端点；
//     但 admin 数据看板与中继记账已完整可用。WS 实时信令留待后续工作项接入。
//   - 信号处理：收到 SIGINT/SIGTERM 优雅停 relay 与 HTTP。
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"media-manager/ops-server/internal/admin"
	"media-manager/ops-server/internal/auth"
	"media-manager/ops-server/internal/config"
	"media-manager/ops-server/internal/relay"
	"media-manager/ops-server/internal/storage"
)

func main() {
	if err := run(); err != nil {
		log.Fatalf("ops-server: %v", err)
	}
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return fmt.Errorf("load config: %w", err)
	}

	dataDir, err := cfg.ResolveDataDir()
	if err != nil {
		return err
	}
	dbPath, err := cfg.ResolveDBPath(dataDir)
	if err != nil {
		return err
	}

	store, err := storage.Open(dbPath)
	if err != nil {
		return fmt.Errorf("open storage: %w", err)
	}
	defer store.Close()

	// signup 模式：默认 first（首位注册者即 admin），可由环境变量覆盖。
	signupMode := envOr("MM_OPS_SIGNUP_MODE", auth.SignupFirst)
	auther, err := auth.New(store, cfg.JWTSecret, cfg.JWTTTLSeconds, signupMode)
	if err != nil {
		return fmt.Errorf("init auth: %w", err)
	}

	// 首位 admin bootstrap：MM_OPS_BOOTSTRAP_ADMIN=user:pass。
	if err := bootstrapAdmin(auther); err != nil {
		// bootstrap 失败不应阻止启动（仅影响首次登录），降级为日志。
		log.Printf("ops-server: bootstrap admin skipped: %v", err)
	}

	// 中继服务（独立 TCP 端口）。
	relaySvc, err := relay.New(cfg.RelayAddr, auther, store, nil, nil)
	if err != nil {
		return fmt.Errorf("init relay: %w", err)
	}

	// admin 前端 + API。
	adminH, err := admin.New(admin.Deps{Auther: auther, Store: store})
	if err != nil {
		return fmt.Errorf("init admin: %w", err)
	}

	mux := http.NewServeMux()
	mux.Handle("/admin/", adminH.Handler())
	// 健康检查，供编排系统探测（与 backend /healthz 命名对齐）。
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
	})

	httpSrv := &http.Server{
		Addr:              cfg.HTTPAddr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}

	// 优雅停机。
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	errCh := make(chan error, 2)
	go func() { errCh <- httpSrv.ListenAndServe() }()
	go func() { errCh <- relaySvc.ListenAndServe() }()
	log.Printf("ops-server: http=%s relay=%s", cfg.HTTPAddr, cfg.RelayAddr)

	select {
	case err := <-errCh:
		// HTTP 或 relay 提前退出（http.ErrServerClosed 视为正常关闭）。
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			shutdown(relaySvc, httpSrv)
			return fmt.Errorf("serve: %w", err)
		}
	case <-ctx.Done():
		log.Printf("ops-server: shutdown signal received")
		shutdown(relaySvc, httpSrv)
	}
	return nil
}

// shutdown 顺序关闭 relay 与 HTTP，超时各自兜底。
func shutdown(relaySvc *relay.Service, httpSrv *http.Server) {
	rctx, rcancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer rcancel()
	if err := relaySvc.Shutdown(rctx); err != nil {
		log.Printf("ops-server: relay shutdown: %v", err)
	}
	hctx, hcancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer hcancel()
	if err := httpSrv.Shutdown(hctx); err != nil {
		log.Printf("ops-server: http shutdown: %v", err)
	}
}

// bootstrapAdmin 在无运营账号时用 MM_OPS_BOOTSTRAP_ADMIN=user:pass 创建首位 admin。
// 已有账号则跳过。格式非法或未设置则跳过（返回 nil，不视为错误）。
func bootstrapAdmin(a *auth.AuthService) error {
	cred := os.Getenv("MM_OPS_BOOTSTRAP_ADMIN")
	cred = strings.TrimSpace(cred)
	if cred == "" {
		return nil
	}
	idx := strings.IndexByte(cred, ':')
	if idx <= 0 || idx >= len(cred)-1 {
		return errors.New("MM_OPS_BOOTSTRAP_ADMIN must be 'user:pass'")
	}
	user := strings.TrimSpace(cred[:idx])
	pass := cred[idx+1:]
	// first 模式下首位即 admin；若改为 off 则 Register 拒绝（返回 ErrSignupDisabled），此处降级跳过。
	_, err := a.Register(context.Background(), auth.RegisterRequest{
		Username: user,
		Password: pass,
	})
	if err != nil {
		if errors.Is(err, auth.ErrSignupDisabled) {
			return nil // 已有账号，无需 bootstrap
		}
		if errors.Is(err, auth.ErrUsernameTaken) {
			return nil // 账号已存在，幂等
		}
		return fmt.Errorf("register bootstrap admin: %w", err)
	}
	log.Printf("ops-server: bootstrap admin created (%s)", user)
	return nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// writeJSON 极简 JSON 响应（仅 healthz 用，避免单独引工具包）。
func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}
