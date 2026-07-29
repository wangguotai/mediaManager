package main

import (
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
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
	restPort = ":8080"
	// configPath 是后端配置文件路径，相对 cwd（通常为 backend/）。缺失时回退默认值。
	configPath = "config.yaml"
)

func main() {
	// 加载配置：文件缺失视为正常（沿用默认），其余错误直接 fatal。
	cfg, err := config.Load(configPath)
	if err != nil {
		log.Fatalf("Failed to load config %s: %v", configPath, err)
	}

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
	restAddr := envOr("REST_PORT", restPort)
	restSrv := gateway.NewServer(restAddr, gateway.OpenClawConfig{
		BaseURL: envOr("OPENCLAW_GATEWAY_URL", "http://127.0.0.1:18789"),
		Timeout: 10 * time.Second,
	}, mediaService, uploadsDir, authSvc)
	// 注入网盘图片源目录，使 /api/media/stream 能回退查找到网盘原图（data/cloud-images）。
	restSrv.SetCloudDir(cloudImagesDir)
	fmt.Printf("Media Manager REST gateway listening on %s (OpenClaw -> %s)\n", restAddr, restSrv.OpenClawBaseURL())
	if err := restSrv.ListenAndServe(); err != nil {
		log.Fatalf("Failed to serve REST: %v", err)
	}
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
