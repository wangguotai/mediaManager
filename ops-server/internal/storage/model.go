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

import (
	"time"

	"media-manager/ops-server/internal/auth"
	"media-manager/ops-server/internal/relay"
)

// 与领域层类型对齐的桥接策略：本包暴露的 StoredOpAccount / Server / RelaySession
// 定义为指向 auth / relay 对应类型的 type alias，使 *Store 的方法签名直接满足
// auth.OpAccountStore 与 relay.RelayStore 接口，避免在 main 层手写 adapter。
// 依赖方向为 storage → auth、storage → relay（relay → auth），单向无环。
//
// OpAccount / Device 仍为本包独立视图类型（auth/relay 未提供等价物或字段集不同）。

// OpAccount 运营账号视图（不含密码哈希，供响应层使用）。
type OpAccount struct {
	ID        string    `json:"id"`
	Username  string    `json:"username"`
	CreatedAt time.Time `json:"created_at"`
}

// StoredOpAccount 含密码哈希的持久化账号记录。alias 到 auth.StoredOpAccount，
// 使 CreateOpAccount/GetOpAccountByUsername/GetOpAccountByID 满足 auth.OpAccountStore。
type StoredOpAccount = auth.StoredOpAccount

// Server 受管服务端实例。ServerToken 仅在注册时以明文返回；DB 仅存 TokenHash。
// alias 到 auth.Server，使 CreateServer/GetServerBy* 满足 auth.OpAccountStore。
type Server = auth.Server

// Device 设备在线登记记录。Online/LastSeen 由设备心跳或 WS 连接态驱动。
type Device struct {
	ServerID string    `json:"server_id"`
	DeviceID string    `json:"device_id"`
	Online   bool      `json:"online"`
	LastSeen time.Time `json:"last_seen"`
	Meta     string    `json:"meta"` // 透传的设备元信息（平台/名称等原始 JSON 字符串），可空
}

// RelaySession TCP 中继流量记账记录。alias 到 relay.RelaySession，
// 使 CreateRelaySession/FinalizeRelaySession 满足 relay.RelayStore。
type RelaySession = relay.RelaySession
