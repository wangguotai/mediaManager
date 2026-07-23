// Package gateway exposes a small HTTP surface alongside the gRPC server.
// Its first responsibility is the OpenClaw bridge: forwarding REST calls to
// the local OpenClaw gateway so KMP/web clients only talk to media-manager.
// It also exposes media REST endpoints that proxy to the internal gRPC service.
package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"media-manager/backend/gen"
)

// OpenClawConfig describes how to reach the local OpenClaw gateway.
type OpenClawConfig struct {
	BaseURL string        // e.g. http://127.0.0.1:18789
	Timeout time.Duration // per-request timeout; defaults to 10s
}

// Server is the REST gateway.
type Server struct {
	addr       string
	openClaw   OpenClawConfig
	httpClient *http.Client
	mux        *http.ServeMux
	mediaSvc   gen.MediaServiceServer
	uploadsDir string
	cloudDir   string // 网盘图片源根目录；为空表示未配置，stream 端点不回退查找
}

// NewServer wires routes for the given addr. It does not start listening.
//
// cloudDir 为网盘图片源根目录（data/cloud-images），供 /api/media/stream 在 uploads
// 目录找不到时回退查找网盘原图；传空串则禁用回退。mediaSvc 若实现了 service.MediaService，
// 会自动取其 CloudImagesDir() 填充，调用方也可直接通过 cloudDir 覆盖。
func NewServer(addr string, cfg OpenClawConfig, mediaSvc gen.MediaServiceServer, uploadsDir string) *Server {
	if cfg.Timeout <= 0 {
		cfg.Timeout = 10 * time.Second
	}
	s := &Server{
		addr:       addr,
		openClaw:   cfg,
		httpClient: &http.Client{Timeout: cfg.Timeout},
		mux:        http.NewServeMux(),
		mediaSvc:   mediaSvc,
		uploadsDir: uploadsDir,
	}
	s.registerRoutes()
	return s
}

// SetCloudDir 注入网盘图片源根目录，启用 /api/media/stream 对网盘原图的回退查找。
func (s *Server) SetCloudDir(dir string) { s.cloudDir = dir }

func (s *Server) registerRoutes() {
	// OpenClaw bridge
	s.mux.HandleFunc("/api/openclaw/command", s.handleOpenClawCommand)

	// Media REST endpoints (proxy to gRPC service)
	s.mux.HandleFunc("/api/media/list", s.handleMediaList)
	s.mux.HandleFunc("/api/media/stream/", s.handleMediaStream)
	s.mux.HandleFunc("/api/media/thumbnail/", s.handleMediaThumbnail)
	s.mux.HandleFunc("/api/media/delete", s.handleMediaDelete)
	s.mux.HandleFunc("/api/media/upload", s.handleMediaUpload)
	s.mux.HandleFunc("/api/media/metadata/", s.handleMediaMetadata)

	// Health
	s.mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
}

// OpenClawBaseURL exposes the configured upstream URL for log/startup lines.
func (s *Server) OpenClawBaseURL() string { return s.openClaw.BaseURL }

// ListenAndServe blocks.
func (s *Server) ListenAndServe() error {
	return http.ListenAndServe(s.addr, s.mux)
}

// ============ Media REST endpoints ============

func (s *Server) handleMediaList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	page := int32(1)
	pageSize := int32(20)
	filterType := gen.MediaType_IMAGE
	searchQuery := ""
	if v := r.URL.Query().Get("page"); v != "" {
		pi, _ := parseIntSafe(v)
		if pi > 0 { page = int32(pi) }
	}
	if v := r.URL.Query().Get("page_size"); v != "" {
		ps, _ := parseIntSafe(v)
		if ps > 0 { pageSize = int32(ps) }
	}
	if v := r.URL.Query().Get("type"); v != "" {
		filterType = parseMediaType(v)
	}
	if v := r.URL.Query().Get("q"); v != "" {
		searchQuery = v
	}

	resp, err := s.mediaSvc.GetMediaList(r.Context(), &gen.GetMediaListRequest{
		Page:        page,
		PageSize:    pageSize,
		FilterType:  filterType,
		SearchQuery: searchQuery,
	})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleMediaStream(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/stream/")
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}

	// Direct file read (bypasses gRPC streaming for REST)
	files, err := filepath.Glob(filepath.Join(s.uploadsDir, mediaID+".*"))
	if err != nil || len(files) == 0 {
		// uploads 目录未命中 → 回退到网盘图片源目录（网盘图片 id 为去扩展名的文件名）。
		if s.cloudDir != "" {
			files, err = filepath.Glob(filepath.Join(s.cloudDir, mediaID+".*"))
		}
		if err != nil || len(files) == 0 {
			writeJSON(w, http.StatusNotFound, map[string]any{"error": "media not found"})
			return
		}
	}
	http.ServeFile(w, r, files[0])
}

func (s *Server) handleMediaThumbnail(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/thumbnail/")
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}
	sizeStr := r.URL.Query().Get("size")
	size := gen.ThumbnailSize_THUMBNAIL_MEDIUM
	switch strings.ToLower(sizeStr) {
	case "small":
		size = gen.ThumbnailSize_THUMBNAIL_SMALL
	case "large":
		size = gen.ThumbnailSize_THUMBNAIL_LARGE
	}

	resp, err := s.mediaSvc.GetThumbnail(r.Context(), &gen.GetThumbnailRequest{MediaId: mediaID, Size: size})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	w.Header().Set("Content-Type", resp.MimeType)
	_, _ = w.Write(resp.Data)
}

func (s *Server) handleMediaDelete(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	var req struct {
		MediaIds []string `json:"media_ids"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
		return
	}
	resp, err := s.mediaSvc.DeleteMedia(r.Context(), &gen.DeleteMediaRequest{MediaIds: req.MediaIds})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleMediaUpload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	// Direct file write (bypasses gRPC streaming for REST)
	body, err := io.ReadAll(io.LimitReader(r.Body, 100<<20)) // 100MB cap
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "failed to read body"})
		return
	}
	filename := r.URL.Query().Get("filename")
	if filename == "" {
		filename = "upload.dat"
	}
	id := fmt.Sprintf("%d", time.Now().UnixNano())
	ext := filepath.Ext(filename)
	if ext == "" {
		ext = ".dat"
	}
	uploadPath := filepath.Join(s.uploadsDir, id+ext)
	if err := os.WriteFile(uploadPath, body, 0644); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"media_id": id, "status": "success", "size": len(body)})
}

func (s *Server) handleMediaMetadata(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	mediaID := strings.TrimPrefix(r.URL.Path, "/api/media/metadata/")
	if mediaID == "" || strings.Contains(mediaID, "..") || strings.Contains(mediaID, "/") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid media_id"})
		return
	}
	resp, err := s.mediaSvc.GetMediaMetadata(r.Context(), &gen.GetMediaMetadataRequest{MediaId: mediaID})
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, resp)
}

// ============ OpenClaw bridge ============

type openclawCommandRequest struct {
	Path   string          `json:"path"`
	Method string          `json:"method,omitempty"`
	Body   json.RawMessage `json:"body,omitempty"`
}

type openclawCommandResponse struct {
	Status      int             `json:"status"`
	ContentType string          `json:"content_type,omitempty"`
	Body        json.RawMessage `json:"body,omitempty"`
	RawBody     string          `json:"raw_body,omitempty"`
	Upstream    string          `json:"upstream"`
}

func (s *Server) handleOpenClawCommand(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if !strings.HasPrefix(s.openClaw.BaseURL, "http://") && !strings.HasPrefix(s.openClaw.BaseURL, "https://") {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "openclaw base url not configured"})
		return
	}

	var req openclawCommandRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid request body: " + err.Error()})
		return
	}
	if !strings.HasPrefix(req.Path, "/") || strings.Contains(req.Path, "..") {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "path must start with '/' and must not contain '..'"})
		return
	}
	method := strings.ToUpper(req.Method)
	if method == "" {
		method = http.MethodPost
	}
	if !isAllowedMethod(method) {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "method not allowed: " + method})
		return
	}

	upstreamURL := strings.TrimRight(s.openClaw.BaseURL, "/") + req.Path
	ctx, cancel := context.WithTimeout(r.Context(), s.openClaw.Timeout)
	defer cancel()

	upReq, err := http.NewRequestWithContext(ctx, method, upstreamURL, bytes.NewReader(req.Body))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "failed to build upstream request: " + err.Error()})
		return
	}
	if len(req.Body) > 0 {
		upReq.Header.Set("Content-Type", "application/json")
	}
	upReq.Header.Set("Accept", "application/json")

	resp, err := s.httpClient.Do(upReq)
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error":    "failed to reach openclaw gateway",
			"upstream": upstreamURL,
			"detail":   err.Error(),
		})
		return
	}
	defer resp.Body.Close()

	bodyBytes, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		writeJSON(w, http.StatusBadGateway, map[string]any{
			"error":    "failed to read upstream response",
			"upstream": upstreamURL,
			"detail":   err.Error(),
		})
		return
	}

	out := openclawCommandResponse{
		Status:      resp.StatusCode,
		ContentType: resp.Header.Get("Content-Type"),
		Upstream:    upstreamURL,
	}
	if isJSONContentType(out.ContentType) && len(bodyBytes) > 0 {
		out.Body = json.RawMessage(bodyBytes)
	} else {
		out.RawBody = string(bodyBytes)
	}
	writeJSON(w, http.StatusOK, out)
}

// ============ Helpers ============

func isAllowedMethod(m string) bool {
	switch m {
	case http.MethodGet, http.MethodPost, http.MethodPut, http.MethodPatch, http.MethodDelete:
		return true
	}
	return false
}

func isJSONContentType(ct string) bool {
	ct = strings.ToLower(strings.TrimSpace(strings.Split(ct, ";")[0]))
	return ct == "application/json" || strings.HasSuffix(ct, "+json")
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		_, _ = fmt.Fprintf(w, `{"error":"encode failed: %s"}`, err.Error())
	}
}

func parseIntSafe(s string) (int, error) {
	var n int
	_, err := fmt.Sscanf(s, "%d", &n)
	return n, err
}

func parseMediaType(s string) gen.MediaType {
	switch strings.ToLower(s) {
	case "video":
		return gen.MediaType_VIDEO
	case "live_photo", "livephoto":
		return gen.MediaType_LIVE_PHOTO
	default:
		return gen.MediaType_IMAGE
	}
}

var ErrUpstreamUnavailable = errors.New("openclaw upstream unavailable")
