package service

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"media-manager/backend/gen"

	"github.com/google/uuid"
)

type MediaService struct {
	gen.UnimplementedMediaServiceServer
	uploadsDir string
}

func NewMediaService(uploadsDir string) *MediaService {
	return &MediaService{
		uploadsDir: uploadsDir,
	}
}

func (s *MediaService) UploadMedia(stream gen.MediaService_UploadMediaServer) error {
	var currentMediaID string
	var currentFile *os.File
	var totalSize int64
	var metadata *gen.MediaMetadata

	for {
		req, err := stream.Recv()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}

		switch data := req.Data.(type) {
		case *gen.UploadMediaRequest_Metadata:
			// Start new file upload
			metadata = data.Metadata
			currentMediaID = uuid.New().String()
			filename := filepath.Join(s.uploadsDir, currentMediaID+getFileExtension(metadata.Filename))

			file, err := os.Create(filename)
			if err != nil {
				return fmt.Errorf("failed to create file: %v", err)
			}
			currentFile = file
			totalSize = 0

		case *gen.UploadMediaRequest_ChunkData:
			// Write chunk to file
			if currentFile == nil {
				return fmt.Errorf("no active upload session")
			}
			chunk := data.ChunkData
			n, err := currentFile.Write(chunk)
			if err != nil {
				currentFile.Close()
				return fmt.Errorf("failed to write chunk: %v", err)
			}
			totalSize += int64(n)
		}
	}

	if currentFile != nil {
		currentFile.Close()

		// Update metadata with actual file info
		if metadata != nil {
			metadata.Id = currentMediaID
			metadata.Size = totalSize
			metadata.CreatedAt = time.Now().Unix()
			metadata.UpdatedAt = time.Now().Unix()
		}
	}

	return stream.SendAndClose(&gen.UploadMediaResponse{
		MediaId: currentMediaID,
		Status:  "success",
		Message: fmt.Sprintf("Uploaded %d bytes", totalSize),
	})
}

func (s *MediaService) GetMediaList(ctx context.Context, req *gen.GetMediaListRequest) (*gen.GetMediaListResponse, error) {
	// Scan uploads directory for media files
	files, err := os.ReadDir(s.uploadsDir)
	if err != nil {
		return nil, fmt.Errorf("failed to read uploads directory: %v", err)
	}

	var mediaList []*gen.MediaMetadata
	for _, file := range files {
		if file.IsDir() {
			continue
		}

		// Skip files without extensions (metadata files, etc.)
		if !strings.Contains(file.Name(), ".") {
			continue
		}

		fileInfo, err := file.Info()
		if err != nil {
			continue
		}

		mediaType := s.detectMediaType(file.Name())
		metadata := &gen.MediaMetadata{
			Id:        strings.TrimSuffix(file.Name(), filepath.Ext(file.Name())),
			Filename:  file.Name(),
			Type:      mediaType,
			Size:      fileInfo.Size(),
			CreatedAt: fileInfo.ModTime().Unix(),
			UpdatedAt: fileInfo.ModTime().Unix(),
			MimeType:  s.getMimeType(file.Name()),
		}

		// Apply filters
		if req.FilterType != gen.MediaType_IMAGE && req.FilterType != mediaType {
			continue
		}

		if req.SearchQuery != "" && !strings.Contains(strings.ToLower(metadata.Filename), strings.ToLower(req.SearchQuery)) {
			continue
		}

		mediaList = append(mediaList, metadata)
	}

	// Sort by creation time (newest first)
	sort.Slice(mediaList, func(i, j int) bool {
		return mediaList[i].CreatedAt > mediaList[j].CreatedAt
	})

	// Apply pagination
	startIndex := int(req.Page-1) * int(req.PageSize)
	endIndex := startIndex + int(req.PageSize)
	if startIndex >= len(mediaList) {
		return &gen.GetMediaListResponse{
			MediaList:  []*gen.MediaMetadata{},
			TotalCount: int32(len(mediaList)),
			Page:       req.Page,
			PageSize:   req.PageSize,
		}, nil
	}

	if endIndex > len(mediaList) {
		endIndex = len(mediaList)
	}

	return &gen.GetMediaListResponse{
		MediaList:  mediaList[startIndex:endIndex],
		TotalCount: int32(len(mediaList)),
		Page:       req.Page,
		PageSize:   req.PageSize,
	}, nil
}

func (s *MediaService) DeleteMedia(ctx context.Context, req *gen.DeleteMediaRequest) (*gen.DeleteMediaResponse, error) {
	deletedCount := 0

	for _, mediaID := range req.MediaIds {
		// Find all files with this media ID (including different extensions)
		files, err := filepath.Glob(filepath.Join(s.uploadsDir, mediaID+".*"))
		if err != nil {
			continue
		}

		for _, file := range files {
			err := os.Remove(file)
			if err == nil {
				deletedCount++
			}
		}
	}

	return &gen.DeleteMediaResponse{
		Status:       "success",
		Message:      fmt.Sprintf("Deleted %d files", deletedCount),
		DeletedCount: int32(deletedCount),
	}, nil
}

func (s *MediaService) GetMediaMetadata(ctx context.Context, req *gen.GetMediaMetadataRequest) (*gen.GetMediaMetadataResponse, error) {
	// Find the file with the given media ID
	files, err := filepath.Glob(filepath.Join(s.uploadsDir, req.MediaId+".*"))
	if err != nil || len(files) == 0 {
		return nil, fmt.Errorf("media not found: %s", req.MediaId)
	}

	fileInfo, err := os.Stat(files[0])
	if err != nil {
		return nil, fmt.Errorf("failed to get file info: %v", err)
	}

	mediaType := s.detectMediaType(files[0])
	metadata := &gen.MediaMetadata{
		Id:        req.MediaId,
		Filename:  filepath.Base(files[0]),
		Type:      mediaType,
		Size:      fileInfo.Size(),
		CreatedAt: fileInfo.ModTime().Unix(),
		UpdatedAt: fileInfo.ModTime().Unix(),
		MimeType:  s.getMimeType(files[0]),
	}

	return &gen.GetMediaMetadataResponse{
		Metadata: metadata,
	}, nil
}

func (s *MediaService) detectMediaType(filename string) gen.MediaType {
	ext := strings.ToLower(filepath.Ext(filename))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp":
		return gen.MediaType_IMAGE
	case ".mp4", ".mov", ".avi", ".mkv", ".webm":
		return gen.MediaType_VIDEO
	default:
		return gen.MediaType_IMAGE
	}
}

func (s *MediaService) getMimeType(filename string) string {
	ext := strings.ToLower(filepath.Ext(filename))
	switch ext {
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

func getFileExtension(filename string) string {
	ext := filepath.Ext(filename)
	if ext == "" {
		return ".dat"
	}
	return ext
}
