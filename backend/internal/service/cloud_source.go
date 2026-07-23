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

// CloudImageSource 抽象网盘图片源，可由本地目录扫描、远端 API 等多种实现提供图片元数据。
type CloudImageSource interface {
	// GetCloudImages 返回网盘图片源中的图片元数据列表。
	GetCloudImages() ([]*gen.MediaMetadata, error)
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
func (s *LocalCloudSource) GetCloudImages() ([]*gen.MediaMetadata, error) {
	entries, err := os.ReadDir(s.root)
	if err != nil {
		return nil, fmt.Errorf("failed to read cloud images directory %q: %v", s.root, err)
	}

	var list []*gen.MediaMetadata
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}

		ext := strings.ToLower(filepath.Ext(entry.Name()))
		if !cloudImageExts[ext] {
			continue
		}

		info, err := entry.Info()
		if err != nil {
			continue
		}

		list = append(list, &gen.MediaMetadata{
			Id:        strings.TrimSuffix(entry.Name(), filepath.Ext(entry.Name())),
			Filename:  entry.Name(),
			Type:      gen.MediaType_IMAGE,
			Size:      info.Size(),
			CreatedAt: info.ModTime().Unix(),
			UpdatedAt: info.ModTime().Unix(),
			MimeType:  cloudImageMimeType(ext),
		})
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
	default:
		return "application/octet-stream"
	}
}
