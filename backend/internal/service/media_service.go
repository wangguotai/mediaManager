package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	_ "image/gif" // register decoder
	"image/jpeg"
	"image/png"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
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

// CloudImagesDir 返回注入的网盘图片源根目录；未配置 LocalCloudSource 时返回空串。
// 供 REST gateway 的 /api/media/stream 在 uploads 目录找不到时回退查找网盘原图。
func (s *MediaService) CloudImagesDir() string {
	if src, ok := s.cloudSource.(*LocalCloudSource); ok {
		return src.Root()
	}
	return ""
}

// resolveMediaPath 按 mediaID 查找源文件的磁盘路径：先在 uploads 目录按
// "mediaID.*" 匹配，未命中再回退到网盘图片源根目录（data/cloud-images）。
// 网盘图片的 id 是去扩展名的文件名（如 test-cloud-image），与 uploads 的 uuid id
// 同样适用 "id+.*" glob。返回空串表示未找到。
func (s *MediaService) resolveMediaPath(mediaID string) string {
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		return ""
	}
	if files, err := filepath.Glob(filepath.Join(s.uploadsDir, mediaID+".*")); err == nil && len(files) > 0 {
		return files[0]
	}
	if root := s.CloudImagesDir(); root != "" {
		if files, err := filepath.Glob(filepath.Join(root, mediaID+".*")); err == nil && len(files) > 0 {
			return files[0]
		}
	}
	return ""
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
		// 网盘源同时返回图片与视频；下方过滤沿用与 uploads 一致的 IMAGE 哨兵语义
		// （FilterType 未设或为 IMAGE 时放行全部类型；显式 VIDEO 时只返回视频）。
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

	// resolveMediaPath 同时覆盖 uploads 与网盘图片目录，使网盘原图也能通过 gRPC 流式获取。
	path := s.resolveMediaPath(req.MediaId)
	if path == "" {
		return fmt.Errorf("media not found: %s", req.MediaId)
	}

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
	// resolveMediaPath 同时覆盖 uploads 与网盘图片目录，使网盘图片也能生成缩略图。
	path := s.resolveMediaPath(req.MediaId)
	if path == "" {
		return nil, fmt.Errorf("media not found: %s", req.MediaId)
	}

	mediaType := s.detectMediaType(path)

	// 视频缩略图：用 ffmpeg 抽取第 1s 的第一帧并缩放，缓存为 jpg。
	if mediaType == gen.MediaType_VIDEO {
		return s.getVideoThumbnail(ctx, req.MediaId, path, req.Size)
	}

	if mediaType != gen.MediaType_IMAGE {
		return nil, fmt.Errorf("thumbnails are only supported for images and videos")
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

// getVideoThumbnail 用 ffmpeg 从视频第 1s 抽取一帧，按 longEdge 缩放后缓存为 jpg。
// 缩略图文件名固定为 {mediaID}_{longEdge}.jpg，命中缓存则直接读取返回。
func (s *MediaService) getVideoThumbnail(ctx context.Context, mediaID, srcPath string, size gen.ThumbnailSize) (*gen.GetThumbnailResponse, error) {
	longEdge, ok := thumbnailLongEdge[size]
	if !ok {
		return nil, fmt.Errorf("unknown thumbnail size: %v", size)
	}

	if err := os.MkdirAll(s.thumbsDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to ensure thumbs dir: %v", err)
	}

	thumbPath := filepath.Join(s.thumbsDir, fmt.Sprintf("%s_%d.jpg", mediaID, longEdge))

	// Cache hit: 直接返回已渲染的视频缩略图。
	if info, err := os.Stat(thumbPath); err == nil && info.Size() > 0 {
		data, err := os.ReadFile(thumbPath)
		if err != nil {
			return nil, fmt.Errorf("failed to read cached video thumbnail: %v", err)
		}
		w, h := imageDimensions(thumbPath)
		return &gen.GetThumbnailResponse{
			Data:     data,
			MimeType: "image/jpeg",
			Width:    int32(w),
			Height:   int32(h),
		}, nil
	}

	// ffmpeg 抽帧并缩放：-ss 00:00:01 取第 1s 画面，scale 限长边后按比例缩放，输出单帧 jpg。
	// scale='if(gt(iw,ih),min(longEdge,iw),-2)':'if(gt(iw,ih),-2,min(longEdge,ih))' 在保持宽高比
	// 的前提下让长边等于 longEdge，短边自动取偶数（视频编码要求）。
	scale := fmt.Sprintf("scale='if(gt(iw,ih),min(%d,iw),-2)':'if(gt(iw,ih),-2,min(%d,ih))'", longEdge, longEdge)
	cmd := exec.CommandContext(ctx, "ffmpeg",
		"-y",
		"-ss", "00:00:01",
		"-i", srcPath,
		"-frames:v", "1",
		"-vf", scale,
		"-f", "image2",
		"-vframes", "1",
		thumbPath,
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		return nil, fmt.Errorf("ffmpeg thumbnail failed: %v: %s", err, strings.TrimSpace(string(out)))
	}

	// 读取 ffmpeg 产物并返回；imageDimensions 仅对图片生效，这里给视频缩略图用同样路径。
	data, err := os.ReadFile(thumbPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read generated video thumbnail: %v", err)
	}
	w, h := imageDimensions(thumbPath)
	return &gen.GetThumbnailResponse{
		Data:     data,
		MimeType: "image/jpeg",
		Width:    int32(w),
		Height:   int32(h),
	}, nil
}

// VideoInfoRequest 是 GetVideoInfo 的请求（媒体 ID）。
// 注：GetVideoInfo 未进 proto，仅作为 *MediaService 的普通 Go 方法供 REST gateway 调用。
type VideoInfoRequest struct {
	MediaId string
}

// VideoInfoResponse 返回视频时长（秒）与分辨率。
type VideoInfoResponse struct {
	DurationSeconds float64 `json:"duration_seconds"`
	Width           int32   `json:"width"`
	Height          int32   `json:"height"`
	// Codec 与容器信息，便于前端展示与调试；缺失时为空字符串。
	Codec     string `json:"codec,omitempty"`
	Container string `json:"container,omitempty"`
}

// GetVideoInfo 用 ffprobe 解析视频时长与分辨率。支持 uploads 与网盘图片源目录。
// 非 VIDEO 类型文件返回错误，避免误用 ffprobe 解析图片。
func (s *MediaService) GetVideoInfo(ctx context.Context, req *VideoInfoRequest) (*VideoInfoResponse, error) {
	if req == nil || req.MediaId == "" {
		return nil, fmt.Errorf("media_id is required")
	}
	path := s.resolveMediaPath(req.MediaId)
	if path == "" {
		return nil, fmt.Errorf("media not found: %s", req.MediaId)
	}
	if s.detectMediaType(path) != gen.MediaType_VIDEO {
		return nil, fmt.Errorf("media %s is not a video", req.MediaId)
	}

	// ffprobe 以 JSON 输出 streams/format，便于结构化解析且无需逐字段流式处理。
	cmd := exec.CommandContext(ctx, "ffprobe",
		"-v", "error",
		"-print_format", "json",
		"-show_format",
		"-show_streams",
		path,
	)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return nil, fmt.Errorf("ffprobe failed: %v: %s", err, strings.TrimSpace(string(out)))
	}
	return parseFFProbeJSON(out)
}

// ffProbeOutput 是 ffprobe -show_format -show_streams JSON 输出的最小结构。
type ffProbeOutput struct {
	Streams []struct {
		CodecType string `json:"codec_type"`
		CodecName string `json:"codec_name"`
		Width     int    `json:"width"`
		Height    int    `json:"height"`
	} `json:"streams"`
	Format struct {
		Duration   string `json:"duration"`
		FormatName string `json:"format_name"`
	} `json:"format"`
}

// parseFFProbeJSON 解析 ffprobe JSON 并构造 VideoInfoResponse；缺字段以零值兜底。
func parseFFProbeJSON(raw []byte) (*VideoInfoResponse, error) {
	var probe ffProbeOutput
	if err := json.Unmarshal(raw, &probe); err != nil {
		return nil, fmt.Errorf("failed to parse ffprobe output: %v", err)
	}

	resp := &VideoInfoResponse{
		Container: probe.Format.FormatName,
	}
	// 取第一个视频流作为分辨率/编码来源。
	for _, st := range probe.Streams {
		if strings.EqualFold(st.CodecType, "video") {
			resp.Width = int32(st.Width)
			resp.Height = int32(st.Height)
			resp.Codec = st.CodecName
			break
		}
	}
	// duration 以字符串秒数给出（如 "12.345000"），解析失败则保留 0。
	if probe.Format.Duration != "" {
		if d, err := strconv.ParseFloat(strings.TrimSpace(probe.Format.Duration), 64); err == nil {
			resp.DurationSeconds = d
		}
	}
	return resp, nil
}

// VideoInfoProvider 是 REST gateway 用于探测具体 service 是否实现 GetVideoInfo 的能力接口。
// gen.MediaServiceServer 不包含该方法（未进 proto），gateway 通过类型断言按需调用。
type VideoInfoProvider interface {
	GetVideoInfo(ctx context.Context, req *VideoInfoRequest) (*VideoInfoResponse, error)
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
