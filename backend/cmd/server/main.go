package main

import (
	"context"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
	"time"

	"media-manager/backend/gen"
	"media-manager/backend/internal/auth"
	"media-manager/backend/internal/config"
	"media-manager/backend/internal/gateway"
	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"

	"google.golang.org/grpc"
)

const (
	grpcPort = ":50051"
	// configPath 是后端配置文件路径，相对 cwd（通常为 backend/）。缺失时回退默认值。
	configPath = "config.yaml"
	// defaultOpenClawURL 是未配置运维面时的回退 OpenClaw 网关地址（本地默认端口）。
	defaultOpenClawURL = "http://127.0.0.1:18789"
)

func main() {
	// 加载配置：文件缺失视为正常（沿用默认），其余错误直接 fatal。
	cfg, err := config.Load(configPath)
	if err != nil {
		log.Fatalf("Failed to load config %s: %v", configPath, err)
	}
	// 环境变量 MM_* 在文件之后覆盖，支持 compose/k8s 注入与本地临时覆盖。
	cfg.ApplyEnv()

	// 解析并创建数据目录（uploads / thumbnails / cloud-images 等子目录的父目录）。
	dataDir, err := cfg.ResolveDataDir()
	if err != nil {
		log.Fatalf("Failed to resolve data_dir: %v", err)
	}

	uploadsDir := filepath.Join(dataDir, "uploads")
	if err := os.MkdirAll(uploadsDir, 0755); err != nil {
		log.Fatalf("Failed to create uploads directory: %v", err)
	}

	thumbsDir := filepath.Join(dataDir, "thumbnails")
	if err := os.MkdirAll(thumbsDir, 0755); err != nil {
		log.Fatalf("Failed to create thumbnails directory: %v", err)
	}

	cloudImagesDir := filepath.Join(dataDir, "cloud-images")
	if err := os.MkdirAll(cloudImagesDir, 0755); err != nil {
		log.Fatalf("Failed to create cloud-images directory: %v", err)
	}

	// 打开 SQLite 元数据库（user/media/device 表 + 外键级联）。
	dbPath, err := cfg.ResolveDBPath()
	if err != nil {
		log.Fatalf("Failed to resolve db_path: %v", err)
	}
	store, err := storage.Open(dbPath)
	if err != nil {
		log.Fatalf("Failed to open storage: %v", err)
	}
	defer store.Close()

	// 构造认证服务：JWT HS256 + bcrypt，用户持久化经适配器接入 storage.Store。
	// jwt_secret 为空时 auth.New 生成进程级随机密钥（重启失效），此处给出醒目警告。
	if cfg.JWTSecret == "" {
		log.Printf("WARNING: jwt_secret is empty; using an ephemeral random key (tokens invalidate on restart). Set jwt_secret in config.yaml for production.")
	}
	authSvc, err := auth.New(
		auth.NewStoreBridge(store),
		cfg.JWTSecret,
		cfg.JWTTTLSeconds,
		cfg.AllowSignup,
	)
	if err != nil {
		log.Fatalf("Failed to initialize auth service: %v", err)
	}
	log.Printf("Auth ready: allow_signup=%s", authSvc.AllowSignup())

	// 首次启动引导：库为空时自动创建首个超管账号，打印一次性凭据 + token，
	// 避免 allow_signup=off 下无人能登录的死锁。库非空则静默跳过。
	bootstrapAdmin(cfg, authSvc)

	favoritesPath := filepath.Join(dataDir, "favorites.json")
	favStore, err := service.NewFavoriteStore(favoritesPath)
	if err != nil {
		log.Fatalf("Failed to initialize favorite store: %v", err)
	}

	albumsPath := filepath.Join(dataDir, "albums.json")
	albumStore, err := service.NewAlbumStore(albumsPath)
	if err != nil {
		log.Fatalf("Failed to initialize album store: %v", err)
	}

	mediaService := service.NewMediaService(uploadsDir, thumbsDir)
	mediaService.SetCloudSource(service.NewLocalCloudSource(cloudImagesDir))
	mediaService.SetFavoriteStore(favStore)
	mediaService.SetAlbumStore(albumStore)

	// Start gRPC server
	lis, err := net.Listen("tcp", grpcPort)
	if err != nil {
		log.Fatalf("Failed to listen: %v", err)
	}

	grpcServer := grpc.NewServer()
	gen.RegisterMediaServiceServer(grpcServer, mediaService)

	go func() {
		fmt.Printf("Media Manager gRPC server listening on %s\n", grpcPort)
		if err := grpcServer.Serve(lis); err != nil {
			log.Fatalf("Failed to serve gRPC: %v", err)
		}
	}()

	// Start REST gateway (OpenClaw bridge + media REST + auth)
	// 端口优先取 cfg.Port（经文件 + MM_PORT 覆盖链），回退 8080；
	// 同时保留旧环境变量 REST_PORT 的向后兼容（仅当 MM_* 均未设置时）。
	restAddr := ":" + resolvePort(cfg.Port)
	openClawURL := resolveOpenClawURL(cfg, defaultOpenClawURL)
	restSrv := gateway.NewServer(restAddr, gateway.OpenClawConfig{
		BaseURL: openClawURL,
		Timeout: 10 * time.Second,
	}, mediaService, uploadsDir, authSvc)
	// 注入网盘图片源目录，使 /api/media/stream 能回退查找到网盘原图（data/cloud-images）。
	restSrv.SetCloudDir(cloudImagesDir)
	fmt.Printf("Media Manager REST gateway listening on %s (OpenClaw -> %s)\n", restAddr, restSrv.OpenClawBaseURL())
	if err := restSrv.ListenAndServe(); err != nil {
		log.Fatalf("Failed to serve REST: %v", err)
	}
}

// bootstrapAdmin 在 user 表为空时创建首个超管账号，并在日志中打印一次性凭据与 token。
//
// 凭据来源（均可选，均可经同名环境变量覆盖）：
//   - MM_BOOTSTRAP_ADMIN_USERNAME：超管用户名，缺省 "admin"。
//   - MM_BOOTSTRAP_ADMIN_PASSWORD：超管密码，缺省则生成一次性随机密码（强烈建议登录后修改）。
//
// 库非空时 authSvc.BootstrapAdmin 返回 nil，本函数静默跳过——这是幂等的正常路径，
// 每次重启不会重复建号、也不会重复打印凭据。
func bootstrapAdmin(cfg *config.Config, authSvc *auth.AuthService) {
	username := os.Getenv("MM_BOOTSTRAP_ADMIN_USERNAME")
	password := os.Getenv("MM_BOOTSTRAP_ADMIN_PASSWORD")
	res, err := authSvc.BootstrapAdmin(context.Background(), username, password)
	if err != nil {
		// 引导失败不阻断启动——主功能仍可用，仅记录便于排查。
		log.Printf("WARNING: admin bootstrap failed: %v", err)
		return
	}
	if res == nil {
		// 已有用户，无需引导。
		return
	}
	// 多行醒目输出，便于 `docker logs` 抓取。token 仅此一次明文出现。
	log.Printf("========================================================")
	log.Printf(" INITIAL ADMIN ACCOUNT CREATED (first run, empty user DB)")
	log.Printf("--------------------------------------------------------")
	log.Printf("  username: %s", res.Username)
	log.Printf("  password: %s", res.Password)
	log.Printf("  token   : %s", res.Token)
	log.Printf("  expires : %s", res.ExpiresAt.Format(time.RFC3339))
	log.Printf("--------------------------------------------------------")
	log.Printf(" Login via POST /api/auth/login with these credentials,")
	log.Printf(" then CHANGE the password immediately. allow_signup=%s", cfg.AllowSignup)
	log.Printf("========================================================")
}

// resolvePort 把端口归一为纯端口号字符串。cfg.Port 经 config 已去前导冒号；
// 这里再兜底空值回退 8080，并去掉调用方可能传入的 ":8080" 形态。
func resolvePort(p string) string {
	if p = strings.TrimSpace(p); p == "" {
		return "8080"
	}
	return strings.TrimPrefix(p, ":")
}

// resolveOpenClawURL 决定 OpenClaw 网关地址，优先级：
// cfg.OpsServerURL（config.yaml + MM_OPS_SERVER_URL）> 旧环境变量 OPENCLAW_GATEWAY_URL > def。
// 保留 OPENCLAW_GATEWAY_URL 回退以兼容现有部署文档。
func resolveOpenClawURL(cfg *config.Config, def string) string {
	if cfg.OpsServerURL != "" {
		return cfg.OpsServerURL
	}
	if v := os.Getenv("OPENCLAW_GATEWAY_URL"); v != "" {
		return v
	}
	return def
}
