package service

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
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
	"sync"
	"sync/atomic"
	"time"

	"media-manager/backend/gen"

	"github.com/google/uuid"
)

// exifEntryCap 限制单张图片解析出的 EXIF 条目上限，避免异常文件拖垮列表接口。
const exifEntryCap = 64

// ListCacheStats reports cache hit/miss statistics for observability.
type ListCacheStats struct {
	Hits   int64 `json:"hits"`
	Misses int64 `json:"misses"`
}

// listCacheStats tracks hit/miss counters for GetMediaList cache.
var (
	listCacheHits   int64
	listCacheMisses int64
)

// GetListCacheStats returns the current list cache hit/miss counters.
// Thread-safe via atomic reads.
func GetListCacheStats() (hits, misses int64) {
	return listCacheHits, listCacheMisses
}

const streamChunkSize = 64 * 1024

// imageProbeExts 是 probeMediaDimensions 走"图片头解析"路径的扩展名集合（小写含点）。
// 与 detectMediaType 的图片集合保持一致；视频扩展名交给 ffprobe。
var imageProbeExts = map[string]bool{
	".jpg": true, ".jpeg": true, ".png": true, ".gif": true,
	".bmp": true, ".webp": true,
}

// probeMediaDimensions 读取磁盘文件 width/height/EXIF，按媒体类型选择策略：
//   - 图片：image.DecodeConfig 读宽高（仅读文件头，开销小），并尝试纯标准库
//     解析 JPEG 的 EXIF APP1 段抽取常用字段。
//   - 视频：ffprobe 解析首个视频流的宽高；视频无 EXIF，返回空 map。
//
// 任何解析失败都不报错，仅置零/留空，保证列表接口不被单个坏文件拖垮。
func probeMediaDimensions(path string) (width, height int32, exif map[string]string) {
	ext := strings.ToLower(filepath.Ext(path))
	if imageProbeExts[ext] {
		w, h, ex := imageProbe(path)
		width, height, exif = int32(w), int32(h), ex
		return
	}
	// 视频走 ffprobe（含 15s 超时，与 GetVideoInfo 一致）。
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if w, h, ok := probeVideoDimensions(ctx, path); ok {
		return int32(w), int32(h), nil
	}
	return 0, 0, nil
}

// imageProbe 用 image.DecodeConfig 读宽高，并尝试抽取 JPEG EXIF。
// DecodeConfig 对 JPEG/PNG/GIF/WebP 等格式均可（前提是注册了对应 decoder；
// stdlib 已注册 gif/jpeg/png/bmp，webp 在当前构建下若未注册则宽高为 0，但不报错）。
func imageProbe(path string) (width, height int, exif map[string]string) {
	f, err := os.Open(path)
	if err != nil {
		return 0, 0, nil
	}
	defer f.Close()
	cfg, _, err := image.DecodeConfig(f)
	if err == nil {
		width, height = cfg.Width, cfg.Height
	}
	// EXIF 仅 JPEG 有；其余格式无此结构，直接返回空 map（仍非 nil，便于上层无条件赋值）。
	if ex := extractJPEGExif(path); len(ex) > 0 {
		exif = ex
	}
	return
}

// probeVideoDimensions 用 ffprobe 取首个视频流的宽高，成功返回 (w,h,true)。
// 复用与 GetVideoInfo 一致的 ffprobe 调用约定；失败返回零值 false。
func probeVideoDimensions(ctx context.Context, path string) (width, height int, ok bool) {
	cmd := exec.CommandContext(ctx, "ffprobe",
		"-v", "error",
		"-print_format", "json",
		"-show_streams",
		"-select_streams", "v:0",
		path,
	)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return 0, 0, false
	}
	var probe ffProbeOutput
	if err := json.Unmarshal(out, &probe); err != nil {
		return 0, 0, false
	}
	for _, st := range probe.Streams {
		if strings.EqualFold(st.CodecType, "video") && st.Width > 0 && st.Height > 0 {
			return st.Width, st.Height, true
		}
	}
	return 0, 0, false
}

// metadataSidecar represents the JSON structure written by handleMediaUpload
// to data/metadata/{id}.json. Only the fields we need for enrichment are decoded.
type metadataSidecar struct {
	Filename  string `json:"filename"`
	Size      int64  `json:"size"`
	CreatedAt int64  `json:"created_at"`
	MimeType  string `json:"mime_type"`
}

// readMetadataSidecar reads <user metadata dir>/{id}.json for the given user.
// Returns nil if the file doesn't exist, can't be parsed, or the user_id is
// invalid — callers fall back to file mtime.
func (s *MediaService) readMetadataSidecar(uid, mediaID string) *metadataSidecar {
	metaDir, err := s.userDirs.MetadataDir(uid)
	if err != nil {
		return nil
	}
	metaPath := filepath.Join(metaDir, mediaID+".json")
	data, err := os.ReadFile(metaPath)
	if err != nil {
		return nil
	}
	var ms metadataSidecar
	if err := json.Unmarshal(data, &ms); err != nil {
		return nil
	}
	return &ms
}

// fillDimensions 用 probeMediaDimensions 探查文件并在 metadata 上回填
// Width/Height/ExifData。探查失败时保持零值/nil，不影响其余字段。
// 所有构造 MediaMetadata 的入口（列表/单条/流首消息/网盘扫描）统一经此填充，
// 确保宽度与 EXIF 不再缺省。
func fillDimensions(meta *gen.MediaMetadata, path string) {
	if meta == nil || path == "" {
		return
	}
	w, h, exif := probeMediaDimensions(path)
	meta.Width = w
	meta.Height = h
	if len(exif) > 0 {
		meta.ExifData = exif
	}
}

// extractJPEGExif 用纯标准库解析 JPEG 中 APP1 EXIF 段，抽取最常用的几个字段：
// Make / Model / DateTimeOriginal / ExifImageWidth / ExifImageHeight / Orientation。
// 解析失败或非 JPEG 返回空 map（失败细粒度：仅丢弃该条目，不影响其余）。
// 故意不引入第三方 EXIF 库，规避 go.mod/sum 变更与外部依赖；GPS 等有理数字段暂不解析。
func extractJPEGExif(path string) map[string]string {
	data, err := os.ReadFile(path)
	if err != nil || len(data) < 4 {
		return nil
	}
	// 仅处理 JPEG（SOI FFD8）。
	if data[0] != 0xFF || data[1] != 0xD8 {
		return nil
	}
	exifBytes, err := findExifSegment(data)
	if err != nil || len(exifBytes) < 8 {
		return nil
	}
	return parseTIFFExif(exifBytes)
}

// findExifSegment 在 JPEG 各段中定位 APP1 "Exif\0\0" 段，返回其后的原始 TIFF 字节。
func findExifSegment(data []byte) ([]byte, error) {
	// 从 SOI 之后开始扫描段标记。
	idx := 2
	for idx+3 < len(data) {
		if data[idx] != 0xFF {
			// 不是段标记；JPEG 流式容错：步进到下一个 0xFF。
			idx++
			continue
		}
		marker := data[idx+1]
		// SOS 之后是压缩图像数据，不再有元数据段，提前结束。
		if marker == 0xDA {
			break
		}
		// 标记可含连续 0xFF 填充，跳过填充字节。
		if marker == 0xFF || marker == 0x00 {
			idx++
			continue
		}
		// 段长度为 2 字节大端，含长度自身不含标记。
		if idx+3 >= len(data) {
			break
		}
		segLen := int(binary.BigEndian.Uint16(data[idx+2 : idx+4]))
		if segLen < 2 || idx+2+segLen > len(data) {
			break
		}
		segStart := idx + 4
		segEnd := idx + 2 + segLen
		// APP1 = 0xFFE1，且紧随 "Exif\0\0"。
		if marker == 0xE1 && segEnd-segStart >= 6 && bytes.Equal(data[segStart:segStart+6], []byte("Exif\x00\x00")) {
			return data[segStart+6 : segEnd], nil
		}
		idx = segEnd
	}
	return nil, fmt.Errorf("no exif segment")
}

// parseTIFFExif 解析 TIFF 头与 IFD0/ExifIFD，抽取常用 EXIF 条目。
func parseTIFFExif(tiff []byte) map[string]string {
	if len(tiff) < 8 {
		return nil
	}
	var order binary.ByteOrder
	switch tiff[0] {
	case 'I':
		order = binary.LittleEndian
	case 'M':
		order = binary.BigEndian
	default:
		return nil
	}
	if order.Uint16(tiff[2:4]) != 0x002A {
		return nil
	}
	ifd0Offset := int(order.Uint32(tiff[4:8]))
	out := make(map[string]string)

	// 解析 IFD0：含 Make/Model/Orientation/DateTime 等。
	exifIFDOffset := parseIFD(tiff, ifd0Offset, order, ifd0TagTable, out)
	// 解析 ExifIFD：含 DateTimeOriginal/ExifImageWidth/Height 等。
	if exifIFDOffset > 0 {
		parseIFD(tiff, exifIFDOffset, order, exifTagTable, out)
	}
	// 裁剪到上限，避免异常值爆炸。
	if len(out) > exifEntryCap {
		for k := range out {
			if len(out) <= exifEntryCap {
				break
			}
			delete(out, k)
		}
	}
	if len(out) == 0 {
		return nil
	}
	return out
}

// tagSpec 描述一个待抽取的 EXIF 条目：tag 编号、值类型处理方式、输出键名。
type tagSpec struct {
	tag  uint16
	kind tagKind
	key  string
}

type tagKind int

const (
	tagASCII tagKind = iota // ASCII 字符串（含可能以空字节结尾）
	tagShort                // 16 位无符号数值（如 Orientation）
	tagLong                 // 32 位无符号数值（如 ExifImageWidth）
)

// ifd0TagTable 列出从 IFD0 抽取的字段。
var ifd0TagTable = []tagSpec{
	{0x010F, tagASCII, "Make"},
	{0x0110, tagASCII, "Model"},
	{0x0112, tagShort, "Orientation"},
	{0x0132, tagASCII, "DateTime"},
}

// exifTagTable 列出从 ExifIFD 抽取的字段。
var exifTagTable = []tagSpec{
	{0x9003, tagASCII, "DateTimeOriginal"},
	{0xA002, tagLong, "ExifImageWidth"},
	{0xA003, tagLong, "ExifImageHeight"},
}

// parseIFD 解析指定偏移处的 IFD，按 table 抽取已知字段写入 out。
// 返回 ExifIFD 的偏移（tag 0x8769），若无则返回 0。
func parseIFD(tiff []byte, ifdOffset int, order binary.ByteOrder, table []tagSpec, out map[string]string) int {
	if ifdOffset < 0 || ifdOffset+2 > len(tiff) {
		return 0
	}
	count := int(order.Uint16(tiff[ifdOffset : ifdOffset+2]))
	// 每个 entry 12 字节，guard 防越界。
	if ifdOffset+2+count*12 > len(tiff) {
		return 0
	}
	exifIFDOffset := 0
	// 预置 tag->spec 查找表。
	specs := make(map[uint16]tagSpec, len(table))
	for _, s := range table {
		specs[s.tag] = s
	}
	for i := 0; i < count; i++ {
		entry := ifdOffset + 2 + i*12
		tag := order.Uint16(tiff[entry : entry+2])
		typ := order.Uint16(tiff[entry+2 : entry+4])
		valueCount := int(order.Uint32(tiff[entry+4 : entry+8]))
		valueOffset := int(order.Uint32(tiff[entry+8 : entry+12]))

		// tag 0x8769（ExifIFD 指针）记录偏移供后续解析。
		if tag == 0x8769 {
			if valueOffset > 0 && valueOffset < len(tiff) {
				exifIFDOffset = valueOffset
			}
			continue
		}
		spec, known := specs[tag]
		if !known {
			continue
		}
		if v, ok := readTagValue(tiff, order, spec.kind, typ, valueCount, valueOffset, entry+8); ok && v != "" {
			out[spec.key] = v
		}
	}
	return exifIFDOffset
}

// readTagValue 解析单个 EXIF 条目的值。
// dataOffset 指向 entry 内的"值或偏移"4 字节区（TIFF 规范：值<=4字节内联，否则为偏移）。
func readTagValue(tiff []byte, order binary.ByteOrder, kind tagKind, typ uint16, count, value int, dataOffset int) (string, bool) {
	switch kind {
	case tagASCII:
		// ASCII 类型在 EXIF 中为 typ=2。
		if typ != 2 || count <= 0 {
			return "", false
		}
		raw := inlineOrOffsetBytes(tiff, order, typ, count, value, dataOffset)
		if raw == nil {
			return "", false
		}
		// 去除尾部 NUL，截断首个 NUL 之后内容（EXIF 字符串可含多段）。
		if n := bytes.IndexByte(raw, 0); n >= 0 {
			raw = raw[:n]
		}
		s := strings.TrimSpace(string(raw))
		if s == "" {
			return "", false
		}
		return s, true
	case tagShort:
		if count < 1 {
			return "", false
		}
		// short(type=3) 单值内联在偏移字段前两字节。
		v := order.Uint16(tiff[dataOffset : dataOffset+2])
		return strconv.Itoa(int(v)), true
	case tagLong:
		if count < 1 {
			return "", false
		}
		// long(type=4) 内联在偏移字段本身。
		if value > 0 {
			return strconv.Itoa(value), true
		}
		// 兼容 short 存 width 的情况（部分厂商以 short 记录）。
		if typ == 3 {
			v := order.Uint16(tiff[dataOffset : dataOffset+2])
			return strconv.Itoa(int(v)), true
		}
		return "", false
	}
	return "", false
}

// inlineOrOffsetBytes 取 ASCII 条目的原始字节：值总长<=4 内联于 dataOffset，
// 否则 value 是相对 TIFF 起点的偏移。
func inlineOrOffsetBytes(tiff []byte, order binary.ByteOrder, typ uint16, count, value, dataOffset int) []byte {
	_ = order // order 仅在偏移路径下不直接用（偏移已在调用方按 order 解出）
	unit := tagUnitSize(typ)
	if unit <= 0 {
		return nil
	}
	totalLen := count * unit
	if totalLen <= 4 {
		if dataOffset+totalLen > len(tiff) {
			return nil
		}
		return tiff[dataOffset : dataOffset+totalLen]
	}
	// value 是 TIFF 偏移。
	if value <= 0 || value+totalLen > len(tiff) {
		return nil
	}
	return tiff[value : value+totalLen]
}

// tagUnitSize 返回 EXIF 数据类型对应的单值字节数。
func tagUnitSize(typ uint16) int {
	switch typ {
	case 1, 2, 7: // BYTE / ASCII / UNDEFINED
		return 1
	case 3: // SHORT
		return 2
	case 4, 9: // LONG / SLONG
		return 4
	case 5, 10: // RATIONAL / SRATIONAL
		return 8
	default:
		return 0
	}
}

var thumbnailLongEdge = map[gen.ThumbnailSize]int{
	gen.ThumbnailSize_THUMBNAIL_SMALL:  128,
	gen.ThumbnailSize_THUMBNAIL_MEDIUM: 256,
	gen.ThumbnailSize_THUMBNAIL_LARGE:  512,
}

// maxHugeImageLongEdge 是超大图长边阈值（像素）。超过此值的图片在生成缩略图时
// 直接降级为 placeholder，避免极端大图（如全景拼接图）在纯 Go 单线程像素循环中
// 长时间占满 CPU 导致接口卡死。
const maxHugeImageLongEdge = 8000

// thumbGenSem 限制同时进行的缩略图生成（nearestNeighbor 像素循环）数量。
// 每个 nearestNeighbor 调用会占满一个 CPU 核较长时间，多个大图并发缩略图会
// 耗尽所有 CPU；此处用 cap=2 的 buffered channel 信号量将并发降至 2，
// 既保留一定并发度又不至于压垮机器。acquire/release 在 resizeLongEdge 调用点完成。
var thumbGenSem = make(chan struct{}, 2)

type MediaService struct {
	gen.UnimplementedMediaServiceServer
	// per-user 目录解析：uploads/thumbnails/metadata/video-meta 均挂在
	// data/users/{uid}/ 下，由 userDirs 按 user_id 解析。cloudImagesRoot 为
	// 全局共享的网盘图片源（语义上是公共源，不按用户隔离）。
	userDirs       *UserDirs
	cloudImagesRoot string
	cloudSource    CloudImageSource
	favStore       *FavoriteStore
	albumStore     *AlbumStore
	thumbCache     *ThumbCache

	// listCache 按 user_id 分桶缓存 GetMediaList 结果，避免跨用户串读。
	// 改造前是单条缓存，多用户会互相污染命中，故改为 map[uid]*entry。
	listCacheMu sync.Mutex
	listCache   map[string]*listCacheEntry

	// cloudCache caches the sorted result of GetCloudImages() to avoid
	// re-scanning the cloud directory and re-sorting on every request.
	// 网盘源是全局共享的公共源，缓存不按用户分桶。
	cloudCacheMu    sync.Mutex
	cloudCache      []*gen.MediaMetadata
	cloudCacheAt    time.Time
	cloudCacheMtime time.Time
}

// listCacheEntry holds a cached GetMediaList response and its expiry.
type listCacheEntry struct {
	response  *gen.GetMediaListResponse
	expiresAt time.Time
	// dirMtime is the uploads directory mtime captured at cache time;
	// if the directory mtime changes (file added/removed), the cache is invalidated.
	dirMtime time.Time
	// cacheKey captures the request parameters that produced this cached response.
	page       int32
	pageSize   int32
	filterType gen.MediaType
	searchQuery string
	favOnly    bool
}

// listCacheTTL is the duration for which GetMediaList results are cached.
const listCacheTTL = 30 * time.Second

// NewMediaService 构造按 user_id 隔离的 MediaService。
//   - userDirs: per-user 目录解析器，决定 data/users/{uid}/ 下的子目录布局。为 nil
//     时服务无法定位任何用户数据（仅可用于无数据的占位场景）。
//   - cloudImagesRoot: 全局共享的网盘图片源根目录；为空表示未配置网盘源。
//
// 缩略图内存 LRU 的 cache key 会带 user_id 前缀（见 GetThumbnail），故全局共享
// 单个 ThumbCache 也不会跨用户串读，同时节省多用户下的重复缓存。
func NewMediaService(userDirs *UserDirs, cloudImagesRoot string) *MediaService {
	return &MediaService{
		userDirs:        userDirs,
		cloudImagesRoot: cloudImagesRoot,
		thumbCache:      NewThumbCache(100, 16*1024*1024, 512*1024), // 100 items / 16 MiB total / 512 KiB per item
		listCache:       make(map[string]*listCacheEntry),
	}
}

// SetCloudSource 注入网盘图片源；为 nil 表示禁用 source=cloud 查询能力。
func (s *MediaService) SetCloudSource(src CloudImageSource) {
	s.cloudSource = src
}

// SetFavoriteStore 注入收藏存储；为 nil 表示收藏功能禁用。
func (s *MediaService) SetFavoriteStore(fs *FavoriteStore) {
	s.favStore = fs
}

// SetAlbumStore 注入相册存储；为 nil 表示相册功能禁用。
func (s *MediaService) SetAlbumStore(as *AlbumStore) {
	s.albumStore = as
}

// IsFavorite 返回 mediaId 是否被 user_id 收藏。favStore 未配置或 uid 非法时返回 false。
func (s *MediaService) IsFavorite(uid, mediaId string) bool {
	if s.favStore == nil {
		return false
	}
	return s.favStore.IsFavorite(uid, mediaId)
}

// ListFavorites 返回 user_id 的所有收藏 mediaId 列表。favStore 未配置时返回空切片。
func (s *MediaService) ListFavorites(uid string) []string {
	if s.favStore == nil {
		return []string{}
	}
	return s.favStore.ListFavorites(uid)
}

// TotalFavorites 返回所有已加载用户的收藏总数聚合，供 /healthz 这类无单一用户上下文
// 的端点展示全局收藏规模。favStore 未配置时返回 0。注意这是跨用户聚合指标，
// 只暴露总数而非任何具体用户的收藏内容。
func (s *MediaService) TotalFavorites() int {
	if s.favStore == nil {
		return 0
	}
	return s.favStore.TotalCount()
}

// AddFavorite 收藏 mediaId（属于 user_id）。
func (s *MediaService) AddFavorite(uid, mediaId string) error {
	if s.favStore == nil {
		return fmt.Errorf("favorite store is not configured")
	}
	return s.favStore.AddFavorite(uid, mediaId)
}

// RemoveFavorite 取消收藏 mediaId（属于 user_id）。
func (s *MediaService) RemoveFavorite(uid, mediaId string) error {
	if s.favStore == nil {
		return fmt.Errorf("favorite store is not configured")
	}
	return s.favStore.RemoveFavorite(uid, mediaId)
}

// CreateAlbum 在 user_id 名下创建新相册。
func (s *MediaService) CreateAlbum(uid, name string) (*Album, error) {
	if s.albumStore == nil {
		return nil, fmt.Errorf("album store is not configured")
	}
	return s.albumStore.CreateAlbum(uid, name)
}

// AddToAlbum 将媒体加入 user_id 名下相册。
func (s *MediaService) AddToAlbum(uid, albumID, mediaID string) error {
	if s.albumStore == nil {
		return fmt.Errorf("album store is not configured")
	}
	return s.albumStore.AddToAlbum(uid, albumID, mediaID)
}

// RemoveFromAlbum 将媒体从 user_id 名下相册中移除。
func (s *MediaService) RemoveFromAlbum(uid, albumID, mediaID string) error {
	if s.albumStore == nil {
		return fmt.Errorf("album store is not configured")
	}
	return s.albumStore.RemoveFromAlbum(uid, albumID, mediaID)
}

// SetAlbumCover V7：设置相册封面 media_id。
func (s *MediaService) SetAlbumCover(uid, albumID, mediaID string) error {
	if s.albumStore == nil {
		return fmt.Errorf("album store is not configured")
	}
	return s.albumStore.SetAlbumCover(uid, albumID, mediaID)
}

// BatchAddToAlbum V7：批量添加多个媒体到相册。
func (s *MediaService) BatchAddToAlbum(uid, albumID string, mediaIDs []string) (int, error) {
	if s.albumStore == nil {
		return 0, fmt.Errorf("album store is not configured")
	}
	return s.albumStore.BatchAddToAlbum(uid, albumID, mediaIDs)
}

// ListAlbums 返回 user_id 名下所有相册列表。
func (s *MediaService) ListAlbums(uid string) []*Album {
	if s.albumStore == nil {
		return []*Album{}
	}
	return s.albumStore.ListAlbums(uid)
}

// GetAlbum 返回 user_id 名下指定相册详情。
func (s *MediaService) GetAlbum(uid, albumID string) *Album {
	if s.albumStore == nil {
		return nil
	}
	return s.albumStore.GetAlbum(uid, albumID)
}

// DeleteAlbum 删除 user_id 名下指定相册。
func (s *MediaService) DeleteAlbum(uid, albumID string) error {
	if s.albumStore == nil {
		return fmt.Errorf("album store is not configured")
	}
	return s.albumStore.DeleteAlbum(uid, albumID)
}

// favoriteFilterKey 是 searchQuery 中用于触发收藏过滤的关键字。
const favoriteFilterKey = "favorite=true"

const cloudSearchPrefix = "source=cloud"

// CloudImagesDir 返回注入的网盘图片源根目录；未配置 LocalCloudSource 时返回空串。
// 供 REST gateway 的 /api/media/stream 在 uploads 目录找不到时回退查找网盘原图。
func (s *MediaService) CloudImagesDir() string {
	if src, ok := s.cloudSource.(*LocalCloudSource); ok {
		return src.Root()
	}
	return ""
}

// ThumbCacheStats 返回缩略图缓存的统计数据，供 /api/stats 端点调用。
func (s *MediaService) ThumbCacheStats() ThumbCacheStats {
	return s.thumbCache.Stats()
}

// resolveMediaPath 按 user_id + mediaID 查找源文件的磁盘路径：先在该用户的 uploads
// 目录按 "mediaID.*" 匹配，未命中再回退到全局共享的网盘图片源根目录（data/cloud-images）。
// 网盘图片的 id 是去扩展名的文件名（如 test-cloud-image），与 uploads 的 uuid id
// 同样适用 "id+.*" glob。uid 非法或 mediaID 非法时返回空串（视为未找到，不回退全局，
// 以免跨用户串读）。返回空串表示未找到。
func (s *MediaService) resolveMediaPath(uid, mediaID string) string {
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		return ""
	}
	if uploadsDir, err := s.userDirs.UploadsDir(uid); err == nil {
		if files, err := filepath.Glob(filepath.Join(uploadsDir, mediaID+".*")); err == nil && len(files) > 0 {
			return files[0]
		}
	}
	if root := s.CloudImagesDir(); root != "" {
		if files, err := filepath.Glob(filepath.Join(root, mediaID+".*")); err == nil && len(files) > 0 {
			return files[0]
		}
	}
	return ""
}

func (s *MediaService) UploadMedia(stream gen.MediaService_UploadMediaServer) error {
	// 认证用户由 gRPC 拦截器或 REST 上传侧注入 context；取出 user_id 决定落盘到谁的
	// uploads 目录。未带 user_id 时拒绝（与 REST /api/* 强制 401 的策略一致）。
	uid := UserIDFromContext(stream.Context())
	uploadsDir, err := s.userDirs.UploadsDir(uid)
	if err != nil {
		return fmt.Errorf("upload rejected: %v", err)
	}

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
			filename := filepath.Join(uploadsDir, currentMediaID+getFileExtension(metadata.Filename))

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

	// Invalidate list cache so the newly uploaded file appears immediately.
	s.invalidateListCache(uid)

	return stream.SendAndClose(&gen.UploadMediaResponse{
		MediaId: currentMediaID,
		Status:  "success",
		Message: fmt.Sprintf("Uploaded %d bytes", totalSize),
	})
}

func (s *MediaService) GetMediaList(ctx context.Context, req *gen.GetMediaListRequest) (*gen.GetMediaListResponse, error) {
	// user_id 决定遍历哪个用户的 uploads 目录；未认证（uid 为空）视为无权访问，
	// 返回空列表而非回退到某个全局目录，避免跨用户串读。
	uid := UserIDFromContext(ctx)
	uploadsDir, err := s.userDirs.UploadsDir(uid)
	if err != nil {
		// uid 非法：返回空列表（与目录不存在一致），不报错以保持接口幂等。
		return s.emptyListResp(req), nil
	}

	// 解析 searchQuery 中的 favorite=true 过滤标记，剩余部分作为关键字。
	query, favOnly := parseFavoriteQuery(req.SearchQuery)

	// 当 searchQuery 以 "source=cloud" 开头时，改用网盘图片源返回图片。
	if strings.HasPrefix(query, cloudSearchPrefix) {
		req.SearchQuery = query
		return s.getCloudMediaList(uid, req, favOnly)
	}

	// Check cache: if we have a valid cached entry for the same user + request params
	// and the user's uploads directory mtime hasn't changed, return cached result.
	if cached, hit := s.tryGetListCache(uid, uploadsDir, req, query, favOnly); hit {
		return cached, nil
	}

	// Scan uploads directory for media files
	files, err := os.ReadDir(uploadsDir)
	if err != nil {
		return nil, fmt.Errorf("failed to read uploads directory: %v", err)
	}

	// Capture directory mtime for caching.
	var dirMtime time.Time
	if info, err := os.Stat(uploadsDir); err == nil {
		dirMtime = info.ModTime()
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
		mediaId := strings.TrimSuffix(file.Name(), filepath.Ext(file.Name()))
		metadata := &gen.MediaMetadata{
			Id:        mediaId,
			Filename:  file.Name(),
			Type:      mediaType,
			Size:      fileInfo.Size(),
			CreatedAt: fileInfo.ModTime().Unix(),
			UpdatedAt: fileInfo.ModTime().Unix(),
			MimeType:  s.getMimeType(file.Name()),
		}

		// Enrich from metadata sidecar: prefer created_at from <user metadata>/{id}.json,
		// fall back to file mtime (already set above).
		if sidecar := s.readMetadataSidecar(uid, mediaId); sidecar != nil {
			if sidecar.CreatedAt > 0 {
				metadata.CreatedAt = sidecar.CreatedAt
			}
			if sidecar.MimeType != "" {
				metadata.MimeType = sidecar.MimeType
			}
			if sidecar.Filename != "" {
				metadata.Filename = sidecar.Filename
			}
		}

		// P1-1: 填充 width/height/exif。读文件头或 ffprobe；失败置零不阻断列表。
		fillDimensions(metadata, filepath.Join(uploadsDir, file.Name()))

		// Apply filters
		if req.FilterType != gen.MediaType_IMAGE && req.FilterType != mediaType {
			continue
		}

		if query != "" && !strings.Contains(strings.ToLower(metadata.Filename), strings.ToLower(query)) {
			continue
		}

		// favorite=true 过滤：仅保留该用户收藏的媒体。
		if favOnly && !s.IsFavorite(uid, mediaId) {
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
		resp := s.emptyListResp(req)
		resp.TotalCount = int32(len(mediaList))
		s.storeListCache(uid, resp, req, query, favOnly, dirMtime)
		return resp, nil
	}

	if endIndex > len(mediaList) {
		endIndex = len(mediaList)
	}

	resp := &gen.GetMediaListResponse{
		MediaList:  mediaList[startIndex:endIndex],
		TotalCount: int32(len(mediaList)),
		Page:       req.Page,
		PageSize:   req.PageSize,
	}
	s.storeListCache(uid, resp, req, query, favOnly, dirMtime)
	return resp, nil
}

// emptyListResp 返回一个空媒体列表响应，供无权访问/空目录场景统一构造。
func (s *MediaService) emptyListResp(req *gen.GetMediaListRequest) *gen.GetMediaListResponse {
	return &gen.GetMediaListResponse{
		MediaList:  []*gen.MediaMetadata{},
		TotalCount: 0,
		Page:       req.Page,
		PageSize:   req.PageSize,
	}
}

// tryGetListCache 按 user_id 分桶检查是否有有效缓存。命中返回 (cached, true)。
// 缓存键含 uid，故不同用户的同参数请求互不串读。
func (s *MediaService) tryGetListCache(uid, uploadsDir string, req *gen.GetMediaListRequest, query string, favOnly bool) (*gen.GetMediaListResponse, bool) {
	s.listCacheMu.Lock()
	entry := s.listCache[uid]
	s.listCacheMu.Unlock()

	if entry == nil {
		atomic.AddInt64(&listCacheMisses, 1)
		return nil, false
	}
	// Check TTL expiry.
	if time.Now().After(entry.expiresAt) {
		atomic.AddInt64(&listCacheMisses, 1)
		return nil, false
	}
	// Check request parameters match.
	if entry.page != req.Page || entry.pageSize != req.PageSize ||
		entry.filterType != req.FilterType || entry.searchQuery != query ||
		entry.favOnly != favOnly {
		atomic.AddInt64(&listCacheMisses, 1)
		return nil, false
	}
	// Check directory mtime: if files were added/removed, mtime changes → invalidate.
	if info, err := os.Stat(uploadsDir); err == nil {
		if !info.ModTime().Equal(entry.dirMtime) {
			atomic.AddInt64(&listCacheMisses, 1)
			return nil, false
		}
	}
	atomic.AddInt64(&listCacheHits, 1)
	return entry.response, true
}

// storeListCache 按 user_id 分桶缓存 GetMediaList 响应。
func (s *MediaService) storeListCache(uid string, resp *gen.GetMediaListResponse, req *gen.GetMediaListRequest, query string, favOnly bool, dirMtime time.Time) {
	s.listCacheMu.Lock()
	defer s.listCacheMu.Unlock()
	s.listCache[uid] = &listCacheEntry{
		response:    resp,
		expiresAt:   time.Now().Add(listCacheTTL),
		dirMtime:    dirMtime,
		page:        req.Page,
		pageSize:    req.PageSize,
		filterType:  req.FilterType,
		searchQuery: query,
		favOnly:     favOnly,
	}
}

// invalidateListCache clears the cached GetMediaList result for a single user.
// Called after file additions or deletions to ensure fresh results.
func (s *MediaService) invalidateListCache(uid string) {
	s.listCacheMu.Lock()
	defer s.listCacheMu.Unlock()
	delete(s.listCache, uid)
}
// cloudCacheTTL is the duration for which the sorted cloud image list is cached.
const cloudCacheTTL = 30 * time.Second

// getSortedCloudImages returns the cloud image list sorted by CreatedAt (newest
// first), utilising an in-memory cache to avoid re-scanning and re-sorting on
// every call. The cache is invalidated by directory mtime changes and TTL expiry.
func (s *MediaService) getSortedCloudImages() ([]*gen.MediaMetadata, error) {
	if s.cloudSource == nil {
		return nil, fmt.Errorf("cloud source is not configured")
	}

	// Check cache validity: TTL + directory mtime.
	root := s.cloudSource.(*LocalCloudSource).Root()
	var dirMtime time.Time
	if root != "" {
		if info, err := os.Stat(root); err == nil {
			dirMtime = info.ModTime()
		}
	}

	s.cloudCacheMu.Lock()
	cached := s.cloudCache
	cachedAt := s.cloudCacheAt
	cachedMtime := s.cloudCacheMtime
	s.cloudCacheMu.Unlock()

	if cached != nil && time.Since(cachedAt) < cloudCacheTTL && dirMtime.Equal(cachedMtime) {
		return cached, nil
	}

	// Cache miss: fetch and sort.
	images, err := s.cloudSource.GetCloudImages()
	if err != nil {
		return nil, err
	}
	sort.Slice(images, func(i, j int) bool {
		return images[i].CreatedAt > images[j].CreatedAt
	})

	s.cloudCacheMu.Lock()
	s.cloudCache = images
	s.cloudCacheAt = time.Now()
	s.cloudCacheMtime = dirMtime
	s.cloudCacheMu.Unlock()

	return images, nil
}

// getCloudMediaList 从全局共享的网盘图片源返回媒体列表。网盘源本身按用户共享，
// 但 favorite=true 过滤仍按 uid 判定该用户的收藏。uid 由调用方传入。
func (s *MediaService) getCloudMediaList(uid string, req *gen.GetMediaListRequest, favOnly bool) (*gen.GetMediaListResponse, error) {
	if s.cloudSource == nil {
		return nil, fmt.Errorf("cloud source is not configured")
	}

	allImages, err := s.getSortedCloudImages()
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

		// favorite=true 过滤：仅保留该用户收藏的媒体。
		if favOnly && !s.IsFavorite(uid, meta.Id) {
			continue
		}

		mediaList = append(mediaList, meta)
	}

	// allImages is already sorted by CreatedAt (newest first) via getSortedCloudImages.

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
	uid := UserIDFromContext(ctx)
	uploadsDir, upErr := s.userDirs.UploadsDir(uid)
	thumbsDir, thErr := s.userDirs.ThumbnailsDir(uid)

	deletedCount := 0
	notFoundCount := 0

	for _, mediaID := range req.MediaIds {
		// 安全检查：防止路径穿越。
		if strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
			notFoundCount++
			continue
		}

		found := false

		// 先在该用户的 uploads 目录查找并删除。
		if upErr == nil {
			files, err := filepath.Glob(filepath.Join(uploadsDir, mediaID+".*"))
			if err == nil {
				for _, file := range files {
					if err := os.Remove(file); err == nil {
						deletedCount++
						found = true
					}
				}
			}
		}

		// 回退到网盘图片源目录（data/cloud-images，全局共享），支持删除网盘文件。
		if root := s.CloudImagesDir(); root != "" {
			cloudFiles, err := filepath.Glob(filepath.Join(root, mediaID+".*"))
			if err == nil {
				for _, file := range cloudFiles {
					if err := os.Remove(file); err == nil {
						deletedCount++
						found = true
					}
				}
			}
		}

		if !found {
			notFoundCount++
		}

		// 删除关联的缩略图（best-effort，不计数）。仅清理该用户目录下的缩略图。
		if thErr == nil {
			thumbs, _ := filepath.Glob(filepath.Join(thumbsDir, mediaID+"_*"))
			for _, t := range thumbs {
				_ = os.Remove(t)
			}
		}

		// 取消该用户的收藏（best-effort）。
		if s.favStore != nil {
			_ = s.favStore.RemoveFavorite(uid, mediaID)
		}
	}
	// File deletions change directory contents — invalidate caches.
	if deletedCount > 0 {
		// Best-effort: remove cached video metadata files for deleted media (per-user).
		if videoMetaDir, err := s.userDirs.VideoMetaDir(uid); err == nil {
			for _, mediaID := range req.MediaIds {
				metaPath := filepath.Join(videoMetaDir, mediaID+".json")
				_ = os.Remove(metaPath)
			}
		}
		// Invalidate list cache so the remaining list is fresh.
		defer s.invalidateListCache(uid)
	}

	// 如实反映删除结果：全部命中并删除 → success；部分未找到 → partial；
	// 全部未找到 → not_found。status 字段让调用方能区分“真的删了”还是“ID 不存在”。
	status := "success"
	if notFoundCount > 0 && deletedCount > 0 {
		status = "partial"
	} else if notFoundCount > 0 && deletedCount == 0 {
		status = "not_found"
	}

	return &gen.DeleteMediaResponse{
		Status:       status,
		Message:      fmt.Sprintf("Deleted %d files, %d not found", deletedCount, notFoundCount),
		DeletedCount: int32(deletedCount),
	}, nil
}

func (s *MediaService) GetMediaMetadata(ctx context.Context, req *gen.GetMediaMetadataRequest) (*gen.GetMediaMetadataResponse, error) {
	uid := UserIDFromContext(ctx)
	uploadsDir, err := s.userDirs.UploadsDir(uid)
	if err != nil {
		return nil, fmt.Errorf("media not found: %s", req.MediaId)
	}
	// Find the file with the given media ID in this user's uploads directory.
	files, err := filepath.Glob(filepath.Join(uploadsDir, req.MediaId+".*"))
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

	// P1-1: 填充 width/height/exif。
	fillDimensions(metadata, files[0])

	return &gen.GetMediaMetadataResponse{
		Metadata: metadata,
	}, nil
}

func (s *MediaService) GetMediaStream(req *gen.GetMediaStreamRequest, stream gen.MediaService_GetMediaStreamServer) error {
	ctx := stream.Context()
	uid := UserIDFromContext(ctx)

	// resolveMediaPath 覆盖该用户的 uploads 与全局网盘图片目录，使网盘原图也能通过 gRPC 流式获取。
	path := s.resolveMediaPath(uid, req.MediaId)
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
	// P1-1: 填充 width/height/exif。
	fillDimensions(metadata, path)
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
	uid := UserIDFromContext(ctx)
	// resolveMediaPath 覆盖该用户的 uploads 与全局网盘图片目录，使网盘图片也能生成缩略图。
	path := s.resolveMediaPath(uid, req.MediaId)
	if path == "" {
		return nil, fmt.Errorf("media not found: %s", req.MediaId)
	}

	// 缩略图落盘到该用户专属目录；内存 LRU 的 key 带 uid 前缀，避免不同用户同名媒体串读。
	thumbsDir, err := s.userDirs.ThumbnailsDir(uid)
	if err != nil {
		return nil, fmt.Errorf("media not found: %s", req.MediaId)
	}

	mediaType := s.detectMediaType(path)

	// 视频缩略图：用 ffmpeg 抽取第 1s 的第一帧并缩放，缓存为 jpg。
	if mediaType == gen.MediaType_VIDEO {
		return s.getVideoThumbnail(ctx, uid, thumbsDir, req.MediaId, path, req.Size)
	}

	if mediaType != gen.MediaType_IMAGE {
		return nil, fmt.Errorf("thumbnails are only supported for images and videos")
	}

	longEdge, ok := thumbnailLongEdge[req.Size]
	if !ok {
		return nil, fmt.Errorf("unknown thumbnail size: %v", req.Size)
	}

	ext := strings.ToLower(filepath.Ext(path))
	thumbName := fmt.Sprintf("%s_%d%s", req.MediaId, longEdge, ext)
	thumbPath := filepath.Join(thumbsDir, thumbName)
	cacheKey := uid + "/" + thumbName

	// In-memory LRU cache hit: skip disk entirely.
	if data, mime, w, h, ok := s.thumbCache.Get(cacheKey); ok {
		return &gen.GetThumbnailResponse{
			Data:     data,
			MimeType: mime,
			Width:    w,
			Height:   h,
		}, nil
	}

	// Disk cache hit: serve the pre-rendered thumbnail.
	if info, err := os.Stat(thumbPath); err == nil && info.Size() > 0 {
		data, err := os.ReadFile(thumbPath)
		if err != nil {
			return nil, fmt.Errorf("failed to read cached thumbnail: %v", err)
		}
		w, h := imageDimensions(thumbPath)
		s.thumbCache.Put(cacheKey, s.getMimeType(thumbPath), int32(w), int32(h), data)
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

	// 超大图降级：长边超过 maxHugeImageLongEdge 的图片直接用 placeholder，避免
	// 极端大图在 nearestNeighbor 像素循环中长时间占满 CPU 导致接口卡死。
	imgBounds := img.Bounds()
	imgLong := imgBounds.Dx()
	if imgBounds.Dy() > imgLong {
		imgLong = imgBounds.Dy()
	}
	if imgLong > maxHugeImageLongEdge {
		placeholder := generatePlaceholderThumbnail(longEdge)
		_ = os.WriteFile(thumbPath, placeholder, 0644)
		w := int32(longEdge)
		h := int32(longEdge)
		s.thumbCache.Put(cacheKey, "image/jpeg", w, h, placeholder)
		return &gen.GetThumbnailResponse{
			Data:     placeholder,
			MimeType: "image/jpeg",
			Width:    w,
			Height:   h,
		}, nil
	}

	// 信号量限并发：acquire 后 defer release，确保即使 resize panic 也会释放。
	// 限制同时只有 2 个 nearestNeighbor 像素循环在跑，防止多个大图并发缩略图占满全部 CPU。
	thumbGenSem <- struct{}{}
	defer func() { <-thumbGenSem }()

	thumb := resizeLongEdge(img, longEdge)
	encoded, mimeType, err := encodeImage(thumb, ext, s.getMimeType(path))
	if err != nil {
		return nil, fmt.Errorf("failed to encode thumbnail: %v", err)
	}
	// Best-effort persist; failure here shouldn't block serving the bytes.
	_ = os.WriteFile(thumbPath, encoded, 0644)

	// Store in memory LRU for subsequent requests.
	w := int32(thumb.Bounds().Dx())
	h := int32(thumb.Bounds().Dy())
	s.thumbCache.Put(cacheKey, mimeType, w, h, encoded)

	return &gen.GetThumbnailResponse{
		Data:     encoded,
		MimeType: mimeType,
		Width:    w,
		Height:   h,
	}, nil
}

// getVideoThumbnail 用 ffmpeg 从视频第 1s 抽取一帧，按 longEdge 缩放后缓存为 jpg。
// 缩略图落在 thumbsDir（该用户专属目录），文件名固定为 {mediaID}_{longEdge}.jpg；
// 内存 LRU key 带 uid 前缀以防跨用户串读。
func (s *MediaService) getVideoThumbnail(ctx context.Context, uid, thumbsDir, mediaID, srcPath string, size gen.ThumbnailSize) (*gen.GetThumbnailResponse, error) {
	longEdge, ok := thumbnailLongEdge[size]
	if !ok {
		return nil, fmt.Errorf("unknown thumbnail size: %v", size)
	}

	thumbPath := filepath.Join(thumbsDir, fmt.Sprintf("%s_%d.jpg", mediaID, longEdge))
	cacheKey := uid + "/" + fmt.Sprintf("%s_%d.jpg", mediaID, longEdge)

	// In-memory LRU cache hit.
	if data, mime, w, h, ok := s.thumbCache.Get(cacheKey); ok {
		return &gen.GetThumbnailResponse{
			Data:     data,
			MimeType: mime,
			Width:    w,
			Height:   h,
		}, nil
	}

	// Disk cache hit: 直接返回已渲染的视频缩略图。
	if info, err := os.Stat(thumbPath); err == nil && info.Size() > 0 {
		data, err := os.ReadFile(thumbPath)
		if err != nil {
			return nil, fmt.Errorf("failed to read cached video thumbnail: %v", err)
		}
		w, h := imageDimensions(thumbPath)
		s.thumbCache.Put(cacheKey, "image/jpeg", int32(w), int32(h), data)
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
		// Fallback: ffmpeg failed (e.g. video too short, corrupted file).
		// Generate a solid-color placeholder thumbnail instead of returning an error.
		placeholder := generatePlaceholderThumbnail(longEdge)
		if werr := os.WriteFile(thumbPath, placeholder, 0644); werr != nil {
			return nil, fmt.Errorf("ffmpeg thumbnail failed: %v: %s", err, strings.TrimSpace(string(out)))
		}
		w, h := longEdge, longEdge
		s.thumbCache.Put(cacheKey, "image/jpeg", int32(w), int32(h), placeholder)
		return &gen.GetThumbnailResponse{
			Data:     placeholder,
			MimeType: "image/jpeg",
			Width:    int32(w),
			Height:   int32(h),
		}, nil
	}

	// 读取 ffmpeg 产物并返回；imageDimensions 仅对图片生效，这里给视频缩略图用同样路径。
	data, err := os.ReadFile(thumbPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read generated video thumbnail: %v", err)
	}
	w, h := imageDimensions(thumbPath)

	// Store in memory LRU.
	s.thumbCache.Put(cacheKey, "image/jpeg", int32(w), int32(h), data)

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

// GetVideoInfo 用 ffprobe 解析视频时长与分辨率。支持该用户 uploads 与全局网盘图片源目录。
// 非 VIDEO 类型文件返回错误，避免误用 ffprobe 解析图片。
func (s *MediaService) GetVideoInfo(ctx context.Context, req *VideoInfoRequest) (*VideoInfoResponse, error) {
	if req == nil || req.MediaId == "" {
		return nil, fmt.Errorf("media_id is required")
	}
	uid := UserIDFromContext(ctx)
	path := s.resolveMediaPath(uid, req.MediaId)
	if path == "" {
		return nil, fmt.Errorf("media not found: %s", req.MediaId)
	}
	if s.detectMediaType(path) != gen.MediaType_VIDEO {
		return nil, fmt.Errorf("media %s is not a video", req.MediaId)
	}

	// Try cached metadata first (per-user video-meta dir).
	if cached, err := s.loadVideoMeta(uid, req.MediaId); err == nil {
		return cached, nil
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
	resp, err := parseFFProbeJSON(out)
	if err != nil {
		return nil, err
	}
	// Persist to metadata file for future requests.
	_ = s.saveVideoMeta(uid, req.MediaId, resp)
	return resp, nil
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

// loadVideoMeta reads cached ffprobe results from <user video-meta>/{id}.json.
// Returns an error if the file doesn't exist, can't be parsed, or uid 非法。
func (s *MediaService) loadVideoMeta(uid, mediaID string) (*VideoInfoResponse, error) {
	if strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		return nil, fmt.Errorf("invalid mediaID")
	}
	videoMetaDir, err := s.userDirs.VideoMetaDir(uid)
	if err != nil {
		return nil, err
	}
	metaPath := filepath.Join(videoMetaDir, mediaID+".json")
	data, err := os.ReadFile(metaPath)
	if err != nil {
		return nil, err
	}
	var resp VideoInfoResponse
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, err
	}
	return &resp, nil
}

// saveVideoMeta persists ffprobe results to <user video-meta>/{id}.json.
// Best-effort: directory creation failures are ignored. uid 非法返回错误。
func (s *MediaService) saveVideoMeta(uid, mediaID string, resp *VideoInfoResponse) error {
	if strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		return fmt.Errorf("invalid mediaID")
	}
	videoMetaDir, err := s.userDirs.VideoMetaDir(uid)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(videoMetaDir, 0755); err != nil {
		return err
	}
	metaPath := filepath.Join(videoMetaDir, mediaID+".json")
	data, err := json.Marshal(resp)
	if err != nil {
		return err
	}
	return os.WriteFile(metaPath, data, 0644)
}

// VideoInfoProvider 是 REST gateway 用于探测具体 service 是否实现 GetVideoInfo 的能力接口。
// gen.MediaServiceServer 不包含该方法（未进 proto），gateway 通过类型断言按需调用。
type VideoInfoProvider interface {
	GetVideoInfo(ctx context.Context, req *VideoInfoRequest) (*VideoInfoResponse, error)
}

// generatePlaceholderThumbnail creates a solid-color JPEG of the given size.
// Used as a fallback when ffmpeg cannot extract a frame (e.g. very short or corrupted video).
func generatePlaceholderThumbnail(size int) []byte {
	img := image.NewRGBA(image.Rect(0, 0, size, size))
	// Fill with a muted dark-gray (#2D2D2D) to visually distinguish from real thumbnails.
	gray := color.RGBA{R: 0x2D, G: 0x2D, B: 0x2D, A: 0xFF}
	for y := 0; y < size; y++ {
		for x := 0; x < size; x++ {
			img.SetRGBA(x, y, gray)
		}
	}
	var buf bytes.Buffer
	_ = jpeg.Encode(&buf, img, &jpeg.Options{Quality: 70})
	return buf.Bytes()
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

// parseFavoriteQuery 从 searchQuery 中提取 "favorite=true" 标记。
// 返回去除该标记后的剩余查询字符串，以及是否启用了收藏过滤。
func parseFavoriteQuery(query string) (remaining string, favOnly bool) {
	if !strings.Contains(strings.ToLower(query), "favorite=true") {
		return query, false
	}
	// 移除 "favorite=true"（大小写不敏感），剩余部分做 trim。
	lowered := strings.ToLower(query)
	idx := strings.Index(lowered, "favorite=true")
	removed := query[:idx] + query[idx+len("favorite=true"):]
	return strings.TrimSpace(removed), true
}

func getFileExtension(filename string) string {
	ext := filepath.Ext(filename)
	if ext == "" {
		return ".dat"
	}
	return ext
}
