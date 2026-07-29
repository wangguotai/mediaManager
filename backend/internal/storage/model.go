// Package storage 提供 SQLite 元数据存储层：连接管理、建表迁移与基础 CRUD。
//
// 本包为新增层，不改动现有 service.MediaService（基于文件系统扫描 + JSON
// sidecar）。后续任务将把媒体/用户/设备元数据逐步迁入此处。
package storage

import "time"

// User 对应 user 表的一行。存储账号与认证信息（密码以 hash 形式落库）。
type User struct {
	ID           string    `json:"id"`
	Username     string    `json:"username"`
	PasswordHash string    `json:"-"`           // 不序列化，避免泄露
	Role         string    `json:"role"`        // 如 "admin"、"user"
	CreatedAt    time.Time `json:"created_at"`
}

// Media 对应 media 表的一行。type/mime/deleted 等字段名与建表 SQL 一致。
// Deleted 为软删除标记：true 表示已标记删除，不出现在常规列表中。
type Media struct {
	ID        string    `json:"id"`
	UserID    string    `json:"user_id"`
	Filename  string    `json:"filename"`
	Type      string    `json:"type"` // "IMAGE" | "VIDEO" | "LIVE_PHOTO"
	Size      int64     `json:"size"`
	Mime      string    `json:"mime"`
	Width     int32     `json:"width"`
	Height    int32     `json:"height"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
	SHA256    string    `json:"sha256"`
	Deleted   bool      `json:"deleted"`
}

// Device 对应 device 表的一行。记录已接入的客户端设备信息。
type Device struct {
	ID        string    `json:"id"`
	UserID    string    `json:"user_id"`
	Name      string    `json:"name"`
	Platform  string    `json:"platform"` // 如 "ios"、"android"、"web"
	CreatedAt time.Time `json:"created_at"`
}
