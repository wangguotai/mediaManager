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
	PasswordHash string    `json:"-"`    // 不序列化，避免泄露
	Role         string    `json:"role"` // 如 "admin"、"user"
	CreatedAt    time.Time `json:"created_at"`
}

// Media 对应 media 表的一行。type/mime/deleted 等字段名与建表 SQL 一致。
// Deleted 为软删除标记：true 表示已标记删除，不出现在常规列表中。
//   - SHA256：内容指纹，配合 UserID 做 (user_id,sha256) 秒传去重。
//   - ClientID：客户端为本次上传分配的幂等键（可空），用于多端冲突排查。
//   - TakenAt：内容实际拍摄时间（EXIF/客户端声明的毫秒时间戳）；0 表未知，
//     同步端点在 changes 中原样回传，列表排序不依赖它（沿用 updated_at）。
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
	ClientID  string    `json:"client_id"`
	TakenAt   int64     `json:"taken_at"`
}

// Device 对应 device 表的一行。记录已接入的客户端设备信息。
type Device struct {
	ID        string    `json:"id"`
	UserID    string    `json:"user_id"`
	Name      string    `json:"name"`
	Platform  string    `json:"platform"` // 如 "ios"、"android"、"web"
	CreatedAt time.Time `json:"created_at"`
}

// ShareToken 对应 share_tokens 表的一行（PRD-v7 §1.2 分享链接）。
// 把一组 media 以 12 字符随机短链形式公开访问，可选过期与密码保护。
//   - Token        : 12 字符随机短链，作为主键也是公开 URL 标识。
//   - UserID       : 创建者，撤销时鉴权（仅创建者可 DELETE）。
//   - MediaIDs     : JSON 数组字符串，如 ["id1","id2"]；存 TEXT 避免多对多表。
//     调用方需自行 json.Unmarshal 还原为 []string。
//   - ExpiresAt    : 过期时间；零值表示永不过期（落库为空串）。
//   - PasswordHash : bcrypt 哈希；空串表示无密码保护。
type ShareToken struct {
	Token        string    `json:"token"`
	UserID       string    `json:"user_id"`
	MediaIDs     string    `json:"media_ids"`      // JSON 数组字符串
	ExpiresAt    time.Time `json:"expires_at"`     // 零值 = 永不过期
	PasswordHash string    `json:"-"`              // 不序列化，避免泄露
	HasPassword  bool      `json:"has_password"`   // 派生字段：PasswordHash != ""（供公开响应）
	CreatedAt    time.Time `json:"created_at"`
}
