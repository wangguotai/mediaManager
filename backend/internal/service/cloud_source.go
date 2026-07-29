package service

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"media-manager/backend/gen"
)

// cloudImageExts 定义网盘图片源支持的图片扩展名（小写，含点）。
var cloudImageExts = map[string]bool{
	".jpg":  true,
	".jpeg": true,
	".png":  true,
	".gif":  true,
	".webp": true,
	".bmp":  true,
}

// cloudVideoExts 列出网盘源支持的视频扩展名（小写，含点），单独成表便于按扩展名判定类型。
var cloudVideoExts = map[string]bool{
	".mp4": true,
	".mov": true,
	".avi": true,
	".mkv": true,
}

// CloudImageSource 抽象网盘图片源，可由本地目录扫描、远端 API 等多种实现提供图片元数据。
type CloudImageSource interface {
	// GetCloudImages 返回网盘图片源中的图片元数据列表。
	GetCloudImages() ([]*gen.MediaMetadata, error)

	// Root 返回网盘图片源的数据根目录（本地目录扫描实现可用其作文件内容回退查找）。
	// 对于无本地目录的远端实现，返回空串即可，调用方据此决定是否启用本地回退。
	Root() string
}

// LocalCloudSource 基于本地目录扫描的网盘图片源实现。
// 它遍历 root 目录下的图片文件，构造 MediaMetadata 返回。
type LocalCloudSource struct {
	root string
}

// NewLocalCloudSource 创建一个以 root 为扫描根目录的 LocalCloudSource。
func NewLocalCloudSource(root string) *LocalCloudSource {
	return &LocalCloudSource{root: root}
}

// Root 返回扫描根目录，便于上层在初始化或诊断时确认路径。
func (s *LocalCloudSource) Root() string {
	return s.root
}

// GetCloudImages 扫描 root 目录下的图片文件并返回 MediaMetadata 列表。
// 目录读取失败（例如不存在）会返回错误；扫描过程忽略子目录与无法获取信息的条目。
//
// Live Photo 检测：如果一张图片与一个视频共享相同的基础文件名（去扩展名），
// 例如 color-blue.png + color-blue.mp4，则将该图片标记为 LIVE_PHOTO，
// 设置 is_live_photo=true 和 live_photo_video_id=基础名，
// 关联的视频文件将从列表中移除（不单独展示，仅作为 Live Photo 的视频部分）。
func (s *LocalCloudSource) GetCloudImages() ([]*gen.MediaMetadata, error) {
	entries, err := os.ReadDir(s.root)
	if err != nil {
		return nil, fmt.Errorf("failed to read cloud images directory %q: %v", s.root, err)
	}

	// 先收集所有文件，按基础名索引图片和视频，用于后续 Live Photo 配对。
	type fileEntry struct {
		name     string // 完整文件名
		baseName string // 去扩展名的基础名
		ext      string // 小写扩展名（含点）
		info     os.FileInfo
	}
	var allEntries []fileEntry
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		ext := strings.ToLower(filepath.Ext(entry.Name()))
		if !cloudSupportedExts[ext] {
			continue
		}
		info, err := entry.Info()
		if err != nil {
			continue
		}
		baseName := strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name()))
		allEntries = append(allEntries, fileEntry{entry.Name(), baseName, ext, info})
	}

	// 构建 video base name → exists 映射，用于检测 Live Photo 配对。
	videoBaseNames := make(map[string]bool)
	for _, fe := range allEntries {
		if cloudVideoExts[fe.ext] {
			videoBaseNames[fe.baseName] = true
		}
	}

	// 标记哪些基础名的视频是 Live Photo 的视频部分（不应单独展示）。
	livePhotoVideoBaseNames := make(map[string]bool)
	for _, fe := range allEntries {
		if cloudImageExts[fe.ext] && videoBaseNames[fe.baseName] {
			livePhotoVideoBaseNames[fe.baseName] = true
		}
	}

	var list []*gen.MediaMetadata
	for _, fe := range allEntries {
		isImage := cloudImageExts[fe.ext]
		isVideo := cloudVideoExts[fe.ext]

		// Live Photo 配对：图片与同名视频共存 → 图片变 LIVE_PHOTO，视频从列表移除。
		if isImage && videoBaseNames[fe.baseName] {
			meta := &gen.MediaMetadata{
				Id:                 fe.baseName,
				Filename:           fe.name,
				Type:               gen.MediaType_LIVE_PHOTO,
				Size:               fe.info.Size(),
				CreatedAt:          fe.info.ModTime().Unix(),
				UpdatedAt:          fe.info.ModTime().Unix(),
				MimeType:           cloudImageMimeType(fe.ext),
				IsLivePhoto:        true,
				LivePhotoVideoId:   fe.baseName,
			}
			fillDimensions(meta, filepath.Join(s.root, fe.name))
			list = append(list, meta)
			continue
		}

		// 跳过已被 Live Photo 配对占用的视频文件。
		if isVideo && livePhotoVideoBaseNames[fe.baseName] {
			continue
		}

		meta := &gen.MediaMetadata{
			Id:        fe.baseName,
			Filename:  fe.name,
			Type:      cloudMediaType(fe.ext),
			Size:      fe.info.Size(),
			CreatedAt: fe.info.ModTime().Unix(),
			UpdatedAt: fe.info.ModTime().Unix(),
			MimeType:  cloudImageMimeType(fe.ext),
		}
		fillDimensions(meta, filepath.Join(s.root, fe.name))
		list = append(list, meta)
	}

	if list == nil {
		list = []*gen.MediaMetadata{}
	}
	return list, nil
}

// cloudImageMimeType 根据小写扩展名返回 MIME 类型。
func cloudImageMimeType(ext string) string {
	switch ext {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".bmp":
		return "image/bmp"
	case ".mp4":
		return "video/mp4"
	case ".mov":
		return "video/quicktime"
	case ".avi":
		return "video/x-msvideo"
	case ".mkv":
		return "video/x-matroska"
	default:
		return "application/octet-stream"
	}
}

// cloudMediaType 根据小写扩展名返回 MediaType；视频扩展名返回 VIDEO，其余回退 IMAGE。
func cloudMediaType(ext string) gen.MediaType {
	if cloudVideoExts[ext] {
		return gen.MediaType_VIDEO
	}
	return gen.MediaType_IMAGE
}

// cloudSupportedExts 汇总图片与视频扩展名，作为网盘源扫描时的按扩展名收录判定集合。
var cloudSupportedExts = mergeExts(cloudImageExts, cloudVideoExts)

// mergeExts 合并多个扩展名集合；返回的 map 不与入参共享底层状态。
func mergeExts(maps ...map[string]bool) map[string]bool {
	out := make(map[string]bool)
	for _, m := range maps {
		for k, v := range m {
			out[k] = v
		}
	}
	return out
}
