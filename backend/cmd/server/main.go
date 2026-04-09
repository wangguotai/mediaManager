package main

import (
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"

	"media-manager/backend/gen"
	"media-manager/backend/internal/service"

	"google.golang.org/grpc"
)

const (
	port = ":50051"
)

func main() {
	// Create data directory if it doesn't exist
	dataDir := "./data"
	if err := os.MkdirAll(dataDir, 0755); err != nil {
		log.Fatalf("Failed to create data directory: %v", err)
	}

	// Create uploads directory
	uploadsDir := filepath.Join(dataDir, "uploads")
	if err := os.MkdirAll(uploadsDir, 0755); err != nil {
		log.Fatalf("Failed to create uploads directory: %v", err)
	}

	// Initialize media service
	mediaService := service.NewMediaService(uploadsDir)

	// Start gRPC server
	lis, err := net.Listen("tcp", port)
	if err != nil {
		log.Fatalf("Failed to listen: %v", err)
	}

	grpcServer := grpc.NewServer()
	gen.RegisterMediaServiceServer(grpcServer, mediaService)

	fmt.Printf("Media Manager gRPC server listening on %s\n", port)
	if err := grpcServer.Serve(lis); err != nil {
		log.Fatalf("Failed to serve: %v", err)
	}
}
