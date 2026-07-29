// Package discovery 管理设备在线表与受管服务端存活心跳。
//
// 设备发现模型：
//   - 受管服务端 (server) 通过 server_token 鉴权后，代表其名下设备上报在线状态。
//   - 设备记录按 (server_id, device_id) 唯一；online/last_seen 由心跳或 WS 连接态驱动。
//   - 发现查询：给定 server_id，列出其下在线设备，供对端直连或经信令交换候选地址。
//
// 该包不直接持有存储，而通过 DeviceStore 接口解耦，便于测试注入内存实现。
package discovery

import (
	"context"
	"errors"
	"fmt"
	"time"

	"media-manager/ops-server/internal/auth"
)

// DeviceStore 抽象设备 + server 存活所需能力（见 storage.Store）。
type DeviceStore interface {
	UpsertDevice(ctx context.Context, d Device) error
	SetDeviceOnline(ctx context.Context, serverID, deviceID string, online bool, now time.Time) error
	MarkServerDevicesOffline(ctx context.Context, serverID string, now time.Time) error
	ListDevicesByServer(ctx context.Context, serverID string) ([]Device, error)
	ListOnlineDevicesByServer(ctx context.Context, serverID string) ([]Device, error)
	GetDevice(ctx context.Context, serverID, deviceID string) (*Device, error)
	DeleteDevice(ctx context.Context, serverID, deviceID string) error
	TouchServerLastSeen(ctx context.Context, id string, now time.Time) error
}

// Device 设备在线记录视图（与 storage.Device 对齐，避免上层反向依赖 storage）。
type Device struct {
	ServerID string    `json:"server_id"`
	DeviceID string    `json:"device_id"`
	Online   bool      `json:"online"`
	LastSeen time.Time `json:"last_seen"`
	Meta     string    `json:"meta"`
}

// ErrDeviceNotFound 设备未命中。
var ErrDeviceNotFound = errors.New("device not found")

// Service 设备发现服务。零值不可用，必须经 New 构造。
type Service struct {
	store   DeviceStore
	nowFunc func() time.Time
}

// Option 可选配置（注入时钟，测试用）。
type Option func(*Service)

// WithClock 注入当前时间函数。
func WithClock(f func() time.Time) Option {
	return func(s *Service) { s.nowFunc = f }
}

// New 构造发现服务。store 须非 nil。
func New(store DeviceStore, opts ...Option) (*Service, error) {
	if store == nil {
		return nil, fmt.Errorf("discovery: nil store")
	}
	s := &Service{store: store, nowFunc: time.Now}
	for _, o := range opts {
		o(s)
	}
	return s, nil
}

// RegisterDevice 上报设备上线/心跳。server 为已鉴权的受管服务端；deviceID 名下设备标识。
// meta 为透传元信息（平台/名称等原始 JSON 字符串），可空。
func (s *Service) RegisterDevice(ctx context.Context, server *auth.Server, deviceID, meta string) (Device, error) {
	if server == nil || deviceID == "" {
		return Device{}, fmt.Errorf("discovery: server and device_id required")
	}
	now := s.nowFunc()
	d := Device{
		ServerID: server.ID,
		DeviceID: deviceID,
		Online:   true,
		LastSeen: now,
		Meta:     meta,
	}
	if err := s.store.UpsertDevice(ctx, d); err != nil {
		return Device{}, fmt.Errorf("discovery: upsert device: %w", err)
	}
	// 顺手刷新 server 存活心跳。
	_ = s.store.TouchServerLastSeen(ctx, server.ID, now)
	return d, nil
}

// Heartbeat 设备心跳：仅更新 online=true + last_seen，不覆盖 meta。
func (s *Service) Heartbeat(ctx context.Context, serverID, deviceID string) error {
	if serverID == "" || deviceID == "" {
		return fmt.Errorf("discovery: server_id and device_id required")
	}
	if err := s.store.SetDeviceOnline(ctx, serverID, deviceID, true, s.nowFunc()); err != nil {
		return fmt.Errorf("discovery: heartbeat: %w", err)
	}
	return nil
}

// SetOffline 将单个设备置为离线（WS 断开或显式下线）。
func (s *Service) SetOffline(ctx context.Context, serverID, deviceID string) error {
	if err := s.store.SetDeviceOnline(ctx, serverID, deviceID, false, s.nowFunc()); err != nil {
		return fmt.Errorf("discovery: set offline: %w", err)
	}
	return nil
}

// MarkServerOffline 某 server 整体离线时，将其名下所有设备置离线。
func (s *Service) MarkServerOffline(ctx context.Context, serverID string) error {
	if err := s.store.MarkServerDevicesOffline(ctx, serverID, s.nowFunc()); err != nil {
		return fmt.Errorf("discovery: mark server offline: %w", err)
	}
	return nil
}

// ListDevices 列出某 server 名下所有设备。
func (s *Service) ListDevices(ctx context.Context, serverID string) ([]Device, error) {
	ds, err := s.store.ListDevicesByServer(ctx, serverID)
	if err != nil {
		return nil, fmt.Errorf("discovery: list devices: %w", err)
	}
	return ds, nil
}

// ListOnlineDevices 列出某 server 名下在线设备（发现查询主路径）。
func (s *Service) ListOnlineDevices(ctx context.Context, serverID string) ([]Device, error) {
	ds, err := s.store.ListOnlineDevicesByServer(ctx, serverID)
	if err != nil {
		return nil, fmt.Errorf("discovery: list online devices: %w", err)
	}
	return ds, nil
}

// GetDevice 查询单设备。
func (s *Service) GetDevice(ctx context.Context, serverID, deviceID string) (*Device, error) {
	d, err := s.store.GetDevice(ctx, serverID, deviceID)
	if err != nil {
		if errors.Is(err, ErrDeviceNotFound) || isErrorDeviceNotFound(err) {
			return nil, ErrDeviceNotFound
		}
		return nil, fmt.Errorf("discovery: get device: %w", err)
	}
	return d, nil
}

// UnregisterDevice 删除设备记录（设备解绑）。
func (s *Service) UnregisterDevice(ctx context.Context, serverID, deviceID string) error {
	if err := s.store.DeleteDevice(ctx, serverID, deviceID); err != nil {
		return fmt.Errorf("discovery: unregister device: %w", err)
	}
	return nil
}

// isErrorDeviceNotFound 宽松识别 storage 层 ErrDeviceNotFound（字符串/包装）。
func isErrorDeviceNotFound(err error) bool {
	return err != nil && (err.Error() == "device not found")
}
