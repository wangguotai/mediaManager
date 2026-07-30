package gateway

import (
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"media-manager/backend/internal/service"
)

// ============ React Native bundle 端点（PRD §3.2）============
//
// 提供三组能力，支撑 KMP Android 客户端按需拉取动态下发的 RN JS bundle：
//
//  GET /api/rn/manifest        — 列出可用 bundle 及版本（需认证）
//  GET /api/rn/bundle/{name}   — 下载指定 bundle 的 JS 文件（需认证）
//  GET /api/promotions         — 返回运营活动列表（需认证）
//
// 数据布局（data 目录为 main.go 注入的绝对路径）：
//   <dataDir>/rn-bundles/<bundle-name>/manifest.json   # name/version/description/entryFile
//   <dataDir>/rn-bundles/<bundle-name>/index.android.bundle
//   <dataDir>/promotions.json                          # 运营活动数组（可选）
//
// manifest.json 字段说明（与 RN 热更新约定一致）：
//   - name: bundle 目录名，客户端据此定位下载
//   - version: 语义版本号，客户端比对决定是否拉新版本
//   - description: 中文描述，供前端展示
//   - entryFile: 入口 JS 文件名（通常 index.android.bundle）
//
// 安全线：bundle 名仅允许字母数字-_.，禁止 .. 与 /，避免目录穿越读到任意文件。

// rnManifest 是单个 bundle 的 manifest.json 反序列化结构。
type rnManifest struct {
	Name        string `json:"name"`
	Version     string `json:"version"`
	Description string `json:"description"`
	EntryFile   string `json:"entryFile"`
}

// rnBundleView 是 /api/rn/manifest 响应中的单 bundle 视图。
// entry 字段对齐 PRD §3.2 约定的响应口径（与 manifest.entryFile 同值）。
type rnBundleView struct {
	Name        string `json:"name"`
	Version     string `json:"version"`
	Description string `json:"description"`
	Entry       string `json:"entry"`
}

// rnManifestResponse 是 /api/rn/manifest 的响应体。
type rnManifestResponse struct {
	Bundles []rnBundleView `json:"bundles"`
}

// promotion 是 /api/promotions 中的单条运营活动。
type promotion struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	ImageURL  string `json:"imageUrl"`
	Link      string `json:"link"`
	ExpiresAt string `json:"expiresAt"`
}

// rnBundlesDir 返回 RN bundle 存放目录：优先注入的 dataDir/rn-bundles；
// dataDir 为空时返回空串，调用方据此返回 503。
func (s *Server) rnBundlesDir() string {
	if s.dataDir == "" {
		return ""
	}
	return filepath.Join(s.dataDir, "rn-bundles")
}

// promotionsPath 返回 promotions.json 全路径；dataDir 为空时返回空串。
func (s *Server) promotionsPath() string {
	if s.dataDir == "" {
		return ""
	}
	return filepath.Join(s.dataDir, "promotions.json")
}

// handleRNManifest 列出 data/rn-bundles/ 下所有 bundle 的 manifest 信息。
// 扫描每个子目录的 manifest.json，失败或缺失的子目录静默跳过（容错降级）。
// 响应按 name 排序，保证客户端看到的顺序稳定。
func (s *Server) handleRNManifest(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	// 纵深防御：即便 authMiddleware 已放行，handler 仍校验 uid（数据下发不应匿名可达）。
	if userIDFromContext(r.Context()) == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}
	dir := s.rnBundlesDir()
	if dir == "" {
		// dataDir 未注入：返回空列表而非 503——manifest 端点语义为"列举可用"，
		// 空列表即"暂无可用 bundle"，客户端据此跳过更新流程，不阻断启动。
		writeJSON(w, http.StatusOK, rnManifestResponse{Bundles: []rnBundleView{}})
		return
	}
	entries, err := os.ReadDir(dir)
	if err != nil {
		// 目录不存在视为空列表（首次启动未放任何 bundle 时即此态）。
		if os.IsNotExist(err) {
			writeJSON(w, http.StatusOK, rnManifestResponse{Bundles: []rnBundleView{}})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "failed to read rn-bundles dir: " + err.Error()})
		return
	}
	bundles := make([]rnBundleView, 0, len(entries))
	for _, e := range entries {
		if !e.IsDir() {
			continue
		}
		manifestPath := filepath.Join(dir, e.Name(), "manifest.json")
		data, err := os.ReadFile(manifestPath)
		if err != nil {
			// 该子目录无 manifest.json 或读失败：跳过，不阻断其余 bundle 列举。
			continue
		}
		var m rnManifest
		if err := json.Unmarshal(data, &m); err != nil {
			continue
		}
		// name 缺失时回退用目录名，保证响应非空。
		if m.Name == "" {
			m.Name = e.Name()
		}
		// entry 缺失时回退默认 index.android.bundle（与客户端下载路径约定一致）。
		if m.EntryFile == "" {
			m.EntryFile = "index.android.bundle"
		}
		bundles = append(bundles, rnBundleView{
			Name:        m.Name,
			Version:     m.Version,
			Description: m.Description,
			Entry:       m.EntryFile,
		})
	}
	// 按 name 排序，稳定输出顺序（便于人工 diff 与测试断言）。
	sort.Slice(bundles, func(i, j int) bool { return bundles[i].Name < bundles[j].Name })
	writeJSON(w, http.StatusOK, rnManifestResponse{Bundles: bundles})
}

// handleRNBundle 下载指定 bundle 的入口 JS 文件。
// 路径: data/rn-bundles/{name}/{entryFile}，entryFile 默认 index.android.bundle。
// 用 http.ServeFile 返回文件，显式设置 Content-Type: application/javascript
// （ServeFile 默认按扩展名嗅探 .bundle 会得到 application/octet-stream，导致 RN 客户端无法识别）。
// 未找到返回 404。
func (s *Server) handleRNBundle(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if userIDFromContext(r.Context()) == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}
	// 路径形如 /api/rn/bundle/{name}，取最后一段为 bundle 名。
	name := strings.TrimPrefix(r.URL.Path, "/api/rn/bundle/")
	if name == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "missing bundle name"})
		return
	}
	// 路径穿越防护：仅允许字母数字、-、_、.；禁止 .. 与 /。
	if !isSafeBundleName(name) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid bundle name"})
		return
	}
	dir := s.rnBundlesDir()
	if dir == "" {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "rn-bundles directory is not configured"})
		return
	}
	// 读 manifest 取 entryFile；manifest 缺失时回退默认 index.android.bundle。
	// 这样即便运营只放了 .bundle 文件未写 manifest，下载链路仍可用。
	entryFile := "index.android.bundle"
	if data, err := os.ReadFile(filepath.Join(dir, name, "manifest.json")); err == nil {
		var m rnManifest
		if json.Unmarshal(data, &m) == nil && m.EntryFile != "" {
			entryFile = m.EntryFile
		}
	}
	// entryFile 同样做穿越校验（防 manifest 内嵌恶意路径）。
	if !isSafeBundleName(entryFile) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid entry file in manifest"})
		return
	}
	bundlePath := filepath.Join(dir, name, entryFile)
	if _, err := os.Stat(bundlePath); err != nil {
		if os.IsNotExist(err) {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "bundle not found"})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "stat bundle: " + err.Error()})
		return
	}
	// 显式设 Content-Type：.bundle 扩展名在 mime 表中未登记，ServeFile 会回退
	// application/octet-stream；RN 客户端与浏览器需 application/javascript 才能正确解析。
	// ServeFile 不会覆盖已设置的 Content-Type（仅当未设置时才嗅探）。
	w.Header().Set("Content-Type", "application/javascript")
	http.ServeFile(w, r, bundlePath)
}

// handlePromotions 返回运营活动列表 JSON。
// 从 data/promotions.json 读；文件不存在返回空数组（不报错，便于未配置运营时的降级）。
func (s *Server) handlePromotions(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if userIDFromContext(r.Context()) == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}
	path := s.promotionsPath()
	if path == "" {
		// dataDir 未注入：返回空数组（语义同"无运营活动"）。
		writeJSON(w, http.StatusOK, []promotion{})
		return
	}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			writeJSON(w, http.StatusOK, []promotion{})
			return
		}
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "failed to read promotions: " + err.Error()})
		return
	}
	var promos []promotion
	if err := json.Unmarshal(data, &promos); err != nil {
		// promotions.json 损坏：返回空数组降级，不阻断客户端（运营位为非核心功能）。
		writeJSON(w, http.StatusOK, []promotion{})
		return
	}
	if promos == nil {
		promos = []promotion{}
	}
	writeJSON(w, http.StatusOK, promos)
}

// isSafeBundleName 校验 bundle 名/文件名仅含字母数字、-、_、.，
// 禁止 ..、/、空格等可致目录穿越的字符。空串返回 false。
func isSafeBundleName(name string) bool {
	if name == "" || name == "." || name == ".." {
		return false
	}
	// 禁止包含路径分隔符或 .. 段（filepath.Clean 后比对防 ../ 绕过）。
	cleaned := filepath.Clean(name)
	if cleaned != name || strings.ContainsAny(name, "/\\") {
		return false
	}
	for _, r := range name {
		if !(r >= 'a' && r <= 'z') && !(r >= 'A' && r <= 'Z') &&
			!(r >= '0' && r <= '9') && r != '-' && r != '_' && r != '.' {
			return false
		}
	}
	return true
}

// ============ 启动期数据目录初始化辅助 ============

// EnsureRNBundleSeed 在 data/rn-bundles/ 下放一份 activity-bundle 示例（manifest.json
// + 占位 index.android.bundle），用于验证下载链路。仅在首次创建（不覆盖既有文件）。
// 调用方传入数据目录绝对路径；目录/文件创建失败仅返回 error，由 main.go 记日志不阻断。
func EnsureRNBundleSeed(dataDir string) error {
	bundlesDir := filepath.Join(dataDir, "rn-bundles")
	if err := os.MkdirAll(bundlesDir, 0o755); err != nil {
		return err
	}
	activityDir := filepath.Join(bundlesDir, "activity-bundle")
	if err := os.MkdirAll(activityDir, 0o755); err != nil {
		return err
	}
	manifestPath := filepath.Join(activityDir, "manifest.json")
	if _, err := os.Stat(manifestPath); os.IsNotExist(err) {
		manifest := rnManifest{
			Name:        "activity-bundle",
			Version:     "1.0.0",
			Description: "运营活动",
			EntryFile:   "index.android.bundle",
		}
		data, _ := json.MarshalIndent(manifest, "", "  ")
		if err := os.WriteFile(manifestPath, data, 0o644); err != nil {
			return err
		}
	}
	bundlePath := filepath.Join(activityDir, "index.android.bundle")
	if _, err := os.Stat(bundlePath); os.IsNotExist(err) {
		// 占位 JS：一个 console.log 用于验证下载与执行链路。
		placeholder := "// activity-bundle placeholder (media-manager PRD §3.2)\n" +
			"// 由后端 ensureRNBundleSeed 生成，用于验证 RN bundle 下载链路。\n" +
			"console.log('[activity-bundle] loaded from media-manager backend');\n"
		if err := os.WriteFile(bundlePath, []byte(placeholder), 0o644); err != nil {
			return err
		}
	}
	return nil
}

// EnsurePromotionsSeed 在 data/promotions.json 不存在时写一份 demo 数据，
// 使 /api/promotions 开箱即有内容可返回。既有文件不覆盖。
func EnsurePromotionsSeed(dataDir string) error {
	path := filepath.Join(dataDir, "promotions.json")
	if _, err := os.Stat(path); err == nil {
		return nil // 已存在，不覆盖。
	} else if !os.IsNotExist(err) {
		return err
	}
	promos := []promotion{
		{
			ID:        "p1",
			Title:     "夏季活动",
			ImageURL:  "https://example.com/promotions/summer.png",
			Link:      "https://example.com/promotions/summer",
			ExpiresAt: "2026-08-31",
		},
	}
	data, _ := json.MarshalIndent(promos, "", "  ")
	return os.WriteFile(path, data, 0o644)
}

// 编译期保证 service 包被引用（userIDFromContext 跨包委托）。
var _ = service.UserIDFromContext
