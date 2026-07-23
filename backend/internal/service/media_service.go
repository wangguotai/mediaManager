package service

import (
	"bytes"
	"context"
	"fmt"
	"image"
	_ "image/gif" // register decoder
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"media-manager/backend/gen"

	"github.com/google/uuid"
)

const streamChunkSize = 64 * 1024

var thumbnailLongEdge = map[gen.ThumbnailSize]int{
	gen.ThumbnailSize_THUMBNAIL_SMALL:  128,
	gen.ThumbnailSize_THUMBNAIL_MEDIUM: 256,
	gen.ThumbnailSize_THUMBNAIL_LARGE:  512,
}

type MediaService struct {
	gen.UnimplementedMediaServiceServer
	uploadsDir  string
	thumbsDir   string
	cloudSource CloudImageSource
}

func NewMediaService(uploadsDir, thumbsDir string) *MediaService {
	return &MediaService{
		uploadsDir: uploadsDir,
		thumbsDir:  thumbsDir,
	}
}

// SetCloudSource 注入网盘图片源；为 nil 表示禁用 source=cloud 查询能力。
func (s *MediaService) SetCloudSource(src CloudImageSource) {
	s.cloudSource = src
}

const cloudSearchPrefix = "source=cloud"

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
	// 当 searchQuery 以 "source=cloud" 开头时，改用网盘图片源返回图片。
	if strings.HasPrefix(req.SearchQuery, cloudSearchPrefix) {
		return s.getCloudMediaList(req)
	}

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

// getCloudMediaList 通过注入的 CloudImageSource 获取网盘图片，支持附加的关键字过滤与分页。
func (s *MediaService) getCloudMediaList(req *gen.GetMediaListRequest) (*gen.GetMediaListResponse, error) {
	if s.cloudSource == nil {
		return nil, fmt.Errorf("cloud source is not configured")
	}

	allImages, err := s.cloudSource.GetCloudImages()
	if err != nil {
		return nil, fmt.Errorf("failed to fetch cloud images: %v", err)
	}

	// 去掉 "source=cloud" 前缀，剩余部分作为文件名关键字过滤条件。
	remainingQuery := strings.TrimSpace(strings.TrimPrefix(req.SearchQuery, cloudSearchPrefix))

	var mediaList []*gen.MediaMetadata
	for _, meta := range allImages {
		// 网盘源仅返回图片，已隐含 image 过滤；这里仍尊重非图片过滤的拒绝语义。
		if req.FilterType != gen.MediaType_IMAGE && req.FilterType != meta.Type {
			continue
		}

		if remainingQuery != "" && !strings.Contains(strings.ToLower(meta.Filename), strings.ToLower(remainingQuery)) {
			continue
		}

		mediaList = append(mediaList, meta)
	}

	// Sort by creation time (newest first)
	sort.Slice(mediaList, func(i, j int) bool {
		return mediaList[i].CreatedAt > mediaList[j].CreatedAt
	})

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

func (s *MediaService) GetMediaStream(req *gen.GetMediaStreamRequest, stream gen.MediaService_GetMediaStreamServer) error {
	ctx := stream.Context()

	files, err := filepath.Glob(filepath.Join(s.uploadsDir, req.MediaId+".*"))
	if err != nil || len(files) == 0 {
		return fmt.Errorf("media not found: %s", req.MediaId)
	}
	path := files[0]

	fileInfo, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("failed to stat file: %v", err)
	}

	// Resolve read range
	offset := req.Offset
	if offset < 0 {
		offset = 0
	}
	if offset > fileInfo.Size() {
		offset = fileInfo.Size()
	}
	remaining := fileInfo.Size() - offset
	if req.Length > 0 && req.Length < remaining {
		remaining = req.Length
	}

	// First message: metadata so the client knows file info / mime / size ahead of the bytes.
	metadata := &gen.MediaMetadata{
		Id:        req.MediaId,
		Filename:  filepath.Base(path),
		Type:      s.detectMediaType(path),
		Size:      fileInfo.Size(),
		CreatedAt: fileInfo.ModTime().Unix(),
		UpdatedAt: fileInfo.ModTime().Unix(),
		MimeType:  s.getMimeType(path),
	}
	if err := stream.Send(&gen.GetMediaStreamResponse{
		Data: &gen.GetMediaStreamResponse_Metadata{Metadata: metadata},
	}); err != nil {
		return err
	}

	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("failed to open file: %v", err)
	}
	defer f.Close()

	if offset > 0 {
		if _, err := f.Seek(offset, io.SeekStart); err != nil {
			return fmt.Errorf("failed to seek: %v", err)
		}
	}

	buf := make([]byte, streamChunkSize)
	sent := int64(0)
	for sent < remaining {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}

		want := int64(len(buf))
		if remaining-sent < want {
			want = remaining - sent
		}
		n, err := f.Read(buf[:want])
		if n > 0 {
			if err := stream.Send(&gen.GetMediaStreamResponse{
				Data: &gen.GetMediaStreamResponse_ChunkData{ChunkData: buf[:n]},
			}); err != nil {
				return err
			}
			sent += int64(n)
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("read error: %v", err)
		}
	}

	return nil
}

func (s *MediaService) GetThumbnail(ctx context.Context, req *gen.GetThumbnailRequest) (*gen.GetThumbnailResponse, error) {
	files, err := filepath.Glob(filepath.Join(s.uploadsDir, req.MediaId+".*"))
	if err != nil || len(files) == 0 {
		return nil, fmt.Errorf("media not found: %s", req.MediaId)
	}
	path := files[0]

	if s.detectMediaType(path) != gen.MediaType_IMAGE {
		return nil, fmt.Errorf("thumbnails are only supported for images")
	}

	longEdge, ok := thumbnailLongEdge[req.Size]
	if !ok {
		return nil, fmt.Errorf("unknown thumbnail size: %v", req.Size)
	}

	if err := os.MkdirAll(s.thumbsDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to ensure thumbs dir: %v", err)
	}

	ext := strings.ToLower(filepath.Ext(path))
	thumbName := fmt.Sprintf("%s_%d%s", req.MediaId, longEdge, ext)
	thumbPath := filepath.Join(s.thumbsDir, thumbName)

	// Cache hit: serve the pre-rendered thumbnail.
	if info, err := os.Stat(thumbPath); err == nil && info.Size() > 0 {
		data, err := os.ReadFile(thumbPath)
		if err != nil {
			return nil, fmt.Errorf("failed to read cached thumbnail: %v", err)
		}
		w, h := imageDimensions(thumbPath)
		return &gen.GetThumbnailResponse{
			Data:     data,
			MimeType: s.getMimeType(thumbPath),
			Width:    int32(w),
			Height:   int32(h),
		}, nil
	}

	// Cache miss: decode -> resize -> encode -> persist.
	srcData, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read source: %v", err)
	}
	img, _, err := image.Decode(bytes.NewReader(srcData))
	if err != nil {
		return nil, fmt.Errorf("failed to decode image: %v", err)
	}

	thumb := resizeLongEdge(img, longEdge)
	encoded, mimeType, err := encodeImage(thumb, ext, s.getMimeType(path))
	if err != nil {
		return nil, fmt.Errorf("failed to encode thumbnail: %v", err)
	}
	// Best-effort persist; failure here shouldn't block serving the bytes.
	_ = os.WriteFile(thumbPath, encoded, 0644)

	return &gen.GetThumbnailResponse{
		Data:     encoded,
		MimeType: mimeType,
		Width:    int32(thumb.Bounds().Dx()),
		Height:   int32(thumb.Bounds().Dy()),
	}, nil
}

// resizeLongEdge scales img so its longest side is <= longEdge, preserving aspect ratio.
// Upscaling is not performed; images smaller than longEdge are returned as-is.
func resizeLongEdge(img image.Image, longEdge int) image.Image {
	bounds := img.Bounds()
	w := bounds.Dx()
	h := bounds.Dy()
	if w <= 0 || h <= 0 {
		return img
	}
	currentLong := w
	if h > w {
		currentLong = h
	}
	if currentLong <= longEdge {
		return img
	}
	scale := float64(longEdge) / float64(currentLong)
	newW := int(float64(w) * scale)
	newH := int(float64(h) * scale)
	if newW < 1 {
		newW = 1
	}
	if newH < 1 {
		newH = 1
	}
	return nearestNeighbor(img, newW, newH)
}

// nearestNeighbor is a dependency-free downscaler. Good enough for thumbnails;
// swap for golang.org/x/image/draw if higher quality is needed later.
func nearestNeighbor(src image.Image, newW, newH int) image.Image {
	sb := src.Bounds()
	srcW := sb.Dx()
	srcH := sb.Dy()
	dst := image.NewRGBA(image.Rect(0, 0, newW, newH))
	xRatio := float64(srcW) / float64(newW)
	yRatio := float64(srcH) / float64(newH)
	for y := 0; y < newH; y++ {
		sy := int(float64(y)*yRatio) + sb.Min.Y
		if sy >= sb.Max.Y {
			sy = sb.Max.Y - 1
		}
		for x := 0; x < newW; x++ {
			sx := int(float64(x)*xRatio) + sb.Min.X
			if sx >= sb.Max.X {
				sx = sb.Max.X - 1
			}
			dst.Set(x, y, src.At(sx, sy))
		}
	}
	return dst
}

// encodeImage encodes img according to ext. It returns the bytes and the mime type
// actually used (PNG fallback rewrites the mime for formats stdlib can't encode).
func encodeImage(img image.Image, ext, srcMimeType string) ([]byte, string, error) {
	var buf bytes.Buffer
	switch ext {
	case ".jpg", ".jpeg":
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 80}); err != nil {
			return nil, "", err
		}
		return buf.Bytes(), "image/jpeg", nil
	case ".png":
		if err := png.Encode(&buf, img); err != nil {
			return nil, "", err
		}
		return buf.Bytes(), "image/png", nil
	default:
		// stdlib can't encode gif/webp/bmp — fall back to PNG and rewrite the mime.
		if err := png.Encode(&buf, img); err != nil {
			return nil, "", err
		}
		return buf.Bytes(), "image/png", nil
	}
}
func imageDimensions(path string) (int, int) {
	f, err := os.Open(path)
	if err != nil {
		return 0, 0
	}
	defer f.Close()
	cfg, _, err := image.DecodeConfig(f)
	if err != nil {
		return 0, 0
	}
	return cfg.Width, cfg.Height
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
