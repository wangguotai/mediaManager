package main

import (
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"time"

	"media-manager/backend/gen"
	"media-manager/backend/internal/gateway"
	"media-manager/backend/internal/service"

	"google.golang.org/grpc"
)

const (
	grpcPort = ":50051"
	restPort = ":8080"
)

func main() {
	// Create data directory if it doesn't exist
	dataDir := "./data"
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		log.Fatalf("Failed to create data directory: %v", err)
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

	// Start REST gateway (OpenClaw bridge + future HTTP endpoints)
	restAddr := envOr("REST_PORT", restPort)
	restSrv := gateway.NewServer(restAddr, gateway.OpenClawConfig{
		BaseURL: envOr("OPENCLAW_GATEWAY_URL", "http://127.0.0.1:18789"),
		Timeout: 10 * time.Second,
	}, mediaService, uploadsDir)
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
