package gateway

import (
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/google/uuid"

	"media-manager/backend/internal/storage"
)

// ============ 设备注册与列表端点 ============
//
// POST /api/device/register   {device_name,platform} -> {device_id}
// GET  /api/device/list        -> {devices:[{device_id,device_name,platform,created_at}]}

// deviceRegisterRequest 是注册请求体。device_name/platform 由客户端声明。
type deviceRegisterRequest struct {
	DeviceName string `json:"device_name"`
	Platform   string `json:"platform"` // 如 "ios" / "android" / "web"
}

// deviceRegisterResponse 返回新分配的 device_id。
type deviceRegisterResponse struct {
	DeviceID string `json:"device_id"`
}

// deviceItem 是 /api/device/list 中的单设备视图。字段命名对齐前端习惯
// （device_id/device_name 而非 storage 层的 id/name），created_at 为毫秒。
type deviceItem struct {
	DeviceID    string `json:"device_id"`
	DeviceName  string `json:"device_name"`
	Platform    string `json:"platform"`
	CreatedAtMs int64  `json:"created_at"`
}

// deviceListResponse 是设备列表响应体。
type deviceListResponse struct {
	Devices []deviceItem `json:"devices"`
}

// handleDeviceRegister 处理 POST /api/device/register。
// 为当前用户登记一台设备：生成 uuid 作为 device_id 并落库，返回该 id。
// 同一用户可注册多台设备；不在此做去重（设备名可重复，以 device_id 区分）。
func (s *Server) handleDeviceRegister(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "device registry is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}

	var req deviceRegisterRequest
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBodyBytes)).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body: " + err.Error()})
		return
	}
	// device_name 可空（旧客户端未必传），但提供则修剪；platform 透传不校验枚举。
	req.DeviceName = strings.TrimSpace(req.DeviceName)

	deviceID := uuid.New().String()
	if err := s.store.CreateDevice(r.Context(), &storage.Device{
		ID:        deviceID,
		UserID:    uid,
		Name:      req.DeviceName,
		Platform:  strings.TrimSpace(req.Platform),
		CreatedAt: time.Now(),
	}); err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, deviceRegisterResponse{DeviceID: deviceID})
}

// handleDeviceList 处理 GET /api/device/list，返回当前用户名下的全部设备。
func (s *Server) handleDeviceList(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "device registry is not configured"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "authentication required"})
		return
	}
	devices, err := s.store.ListDevicesByUser(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	items := make([]deviceItem, 0, len(devices))
	for _, d := range devices {
		items = append(items, deviceItem{
			DeviceID:    d.ID,
			DeviceName:  d.Name,
			Platform:    d.Platform,
			CreatedAtMs: d.CreatedAt.UnixNano() / int64(time.Millisecond),
		})
	}
	writeJSON(w, http.StatusOK, deviceListResponse{Devices: items})
}
