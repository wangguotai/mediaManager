// Package storage 封装运营服务端的 SQLite 持久化。
//
// 三张核心表，严格对应任务定义：
//   - op_account:  运营账号 (id, username, password_hash, created_at)，登录后领取 JWT。
//   - server:      受管服务端实例 (id, name, token_hash, created_at, last_seen)，注册时签发 token。
//   - device:      设备在线登记表 (server_id, device_id, online, last_seen, meta)，发现用。
//   - relay_session: TCP 中继流量记账 (id, server_id, pair_key, bytes_in, bytes_out, started_at, ended_at)。
//
// 设计取舍（与 backend/storage 对齐）：
//   - 时间列用 TEXT 存 RFC3339 字符串，避免 driver 对 INTEGER→time.Time 的不一致行为。
//   - 外键约束显式声明并配 PRAGMA foreign_keys=ON。
//   - device / relay_session 的主键为复合键或逻辑唯一键，按 server_id 隔离。
//   - server.token 存 hash（bcrypt 负担对每个中继连接的 server 鉴权不在热路径，此处存哈希避免明文泄露），
//     注册时一次性返回明文 token 给调用方，之后不再可读。
package storage

import "time"

// OpAccount 运营账号视图（不含密码哈希，供响应层使用）。
type OpAccount struct {
	ID        string    `json:"id"`
	Username  string    `json:"username"`
	CreatedAt time.Time `json:"created_at"`
}

// StoredOpAccount 含密码哈希的持久化账号记录。
type StoredOpAccount struct {
	ID           string
	Username     string
	PasswordHash string
	CreatedAt    time.Time
}

// Server 受管服务端实例。ServerToken 仅在注册时以明文返回；DB 仅存 TokenHash。
type Server struct {
	ID        string    `json:"server_id"`
	Name      string    `json:"name"`
	TokenHash string    `json:"-"`
	CreatedAt time.Time `json:"created_at"`
	LastSeen  time.Time `json:"last_seen"`
}

// Device 设备在线登记记录。Online/LastSeen 由设备心跳或 WS 连接态驱动。
type Device struct {
	ServerID string    `json:"server_id"`
	DeviceID string    `json:"device_id"`
	Online   bool      `json:"online"`
	LastSeen time.Time `json:"last_seen"`
	Meta     string    `json:"meta"` // 透传的设备元信息（平台/名称等原始 JSON 字符串），可空
}

// RelaySession TCP 中继流量记账记录。
type RelaySession struct {
	ID         string    `json:"id"`
	ServerID   string    `json:"server_id"`
	PairKey    string    `json:"pair_key"`    // 会话对标识，便于按对聚合；如 "<a>__<b>"
	BytesIn    int64     `json:"bytes_in"`    // 从客户端 A 入站字节数
	BytesOut   int64     `json:"bytes_out"`   // 转发到对端字节数
	StartedAt  time.Time `json:"started_at"`
	EndedAt    time.Time `json:"ended_at"` // 零值表示进行中
	CloseReason string   `json:"close_reason"`
}
