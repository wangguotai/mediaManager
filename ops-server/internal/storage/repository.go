package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
)

// 哨兵错误。gateway/auth 据此映射 HTTP 状态码。
var (
	// ErrAccountNotFound 按 username/id 查找运营账号未命中。
	ErrAccountNotFound = errors.New("op account not found")
	// ErrUsernameTaken 注册运营账号时用户名已被占用。
	ErrUsernameTaken = errors.New("username already taken")
	// ErrServerNotFound server_id/token 查找受管服务端未命中。
	ErrServerNotFound = errors.New("server not found")
	// ErrDeviceNotFound (server_id, device_id) 查找设备未命中。
	ErrDeviceNotFound = errors.New("device not found")
)

// isUniqueViolation 判断 SQLite 唯一约束冲突（modernc 驱动错误信息含 "UNIQUE constraint failed"）。
func isUniqueViolation(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}

// ---- 运营账号 ----

// CreateOpAccount 落库一个新运营账号；username 唯一冲突返回 ErrUsernameTaken。
func (s *Store) CreateOpAccount(ctx context.Context, a StoredOpAccount) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO op_account (id, username, password_hash, created_at) VALUES (?, ?, ?, ?)`,
		a.ID, a.Username, a.PasswordHash, a.CreatedAt.Format(time.RFC3339Nano),
	)
	if err != nil {
		if isUniqueViolation(err) {
			return ErrUsernameTaken
		}
		return fmt.Errorf("insert op_account: %w", err)
	}
	return nil
}

// GetOpAccountByUsername 按用户名查找运营账号；未命中返回 ErrAccountNotFound。
func (s *Store) GetOpAccountByUsername(ctx context.Context, username string) (*StoredOpAccount, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT id, username, password_hash, created_at FROM op_account WHERE username = ?`, username)
	return scanOpAccount(row)
}

// GetOpAccountByID 按 ID 查找运营账号；未命中返回 ErrAccountNotFound。
func (s *Store) GetOpAccountByID(ctx context.Context, id string) (*StoredOpAccount, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT id, username, password_hash, created_at FROM op_account WHERE id = ?`, id)
	return scanOpAccount(row)
}

// CountOpAccounts 返回运营账号总数，用于"首位注册者即超管"之类判断。
func (s *Store) CountOpAccounts(ctx context.Context) (int, error) {
	var n int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM op_account`).Scan(&n)
	if err != nil {
		return 0, fmt.Errorf("count op_account: %w", err)
	}
	return n, nil
}

func scanOpAccount(row *sql.Row) (*StoredOpAccount, error) {
	var a StoredOpAccount
	var createdStr string
	if err := row.Scan(&a.ID, &a.Username, &a.PasswordHash, &createdStr); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrAccountNotFound
		}
		return nil, fmt.Errorf("scan op_account: %w", err)
	}
	t, err := parseTime(createdStr)
	if err != nil {
		return nil, err
	}
	a.CreatedAt = t
	return &a, nil
}

// ---- 受管服务端 ----

// CreateServer 落库一个受管服务端实例。token 仅以 hash 形式存储。
func (s *Store) CreateServer(ctx context.Context, srv Server) error {
	if srv.LastSeen.IsZero() {
		srv.LastSeen = srv.CreatedAt
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO server (id, name, token_hash, created_at, last_seen) VALUES (?, ?, ?, ?, ?)`,
		srv.ID, srv.Name, srv.TokenHash,
		srv.CreatedAt.Format(time.RFC3339Nano), srv.LastSeen.Format(time.RFC3339Nano),
	)
	if err != nil {
		return fmt.Errorf("insert server: %w", err)
	}
	return nil
}

// GetServerByID 按 ID 查找受管服务端；未命中返回 ErrServerNotFound。
func (s *Store) GetServerByID(ctx context.Context, id string) (*Server, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT id, name, token_hash, created_at, last_seen FROM server WHERE id = ?`, id)
	return scanServer(row)
}

// GetServerByTokenHash 按 token hash 查找受管服务端；用于中继/WS 鉴权时比对。
// 未命中返回 ErrServerNotFound。
func (s *Store) GetServerByTokenHash(ctx context.Context, tokenHash string) (*Server, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT id, name, token_hash, created_at, last_seen FROM server WHERE token_hash = ?`, tokenHash)
	return scanServer(row)
}

// TouchServerLastSeen 更新 server.last_seen 为当前时间，用于存活心跳。
func (s *Store) TouchServerLastSeen(ctx context.Context, id string, now time.Time) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE server SET last_seen = ? WHERE id = ?`, now.Format(time.RFC3339Nano), id)
	if err != nil {
		return fmt.Errorf("touch server last_seen: %w", err)
	}
	return nil
}

func scanServer(row *sql.Row) (*Server, error) {
	var srv Server
	var createdStr, lastSeenStr string
	if err := row.Scan(&srv.ID, &srv.Name, &srv.TokenHash, &createdStr, &lastSeenStr); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrServerNotFound
		}
		return nil, fmt.Errorf("scan server: %w", err)
	}
	ct, err := parseTime(createdStr)
	if err != nil {
		return nil, err
	}
	lt, err := parseTime(lastSeenStr)
	if err != nil {
		return nil, err
	}
	srv.CreatedAt = ct
	srv.LastSeen = lt
	return &srv, nil
}

// ---- 设备在线表 ----

// UpsertDevice 插入或更新设备在线记录（按 server_id+device_id 复合主键）。
// online/last_seen/meta 由调用方传入；通常 upsert 即设备"上线/心跳"。
func (s *Store) UpsertDevice(ctx context.Context, d Device) error {
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO device (server_id, device_id, online, last_seen, meta)
		 VALUES (?, ?, ?, ?, ?)
		 ON CONFLICT(server_id, device_id) DO UPDATE SET
		   online   = excluded.online,
		   last_seen = excluded.last_seen,
		   meta     = excluded.meta`,
		d.ServerID, d.DeviceID, boolToInt(d.Online),
		d.LastSeen.Format(time.RFC3339Nano), d.Meta,
	)
	if err != nil {
		return fmt.Errorf("upsert device: %w", err)
	}
	return nil
}

// SetDeviceOnline 更新设备在线态与 last_seen（轻量心跳路径，仅改两列）。
func (s *Store) SetDeviceOnline(ctx context.Context, serverID, deviceID string, online bool, now time.Time) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE device SET online = ?, last_seen = ? WHERE server_id = ? AND device_id = ?`,
		boolToInt(online), now.Format(time.RFC3339Nano), serverID, deviceID,
	)
	if err != nil {
		return fmt.Errorf("set device online: %w", err)
	}
	return nil
}

// MarkServerDevicesOffline 把指定 server 下所有设备置为离线（WS 整批断开时调用）。
func (s *Store) MarkServerDevicesOffline(ctx context.Context, serverID string, now time.Time) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE device SET online = 0, last_seen = ? WHERE server_id = ?`,
		now.Format(time.RFC3339Nano), serverID,
	)
	if err != nil {
		return fmt.Errorf("mark server devices offline: %w", err)
	}
	return nil
}

// ListDevicesByServer 返回某 server 下所有设备记录。
func (s *Store) ListDevicesByServer(ctx context.Context, serverID string) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT server_id, device_id, online, last_seen, meta FROM device WHERE server_id = ? ORDER BY device_id`,
		serverID)
	if err != nil {
		return nil, fmt.Errorf("list devices: %w", err)
	}
	defer rows.Close()
	return scanDevices(rows)
}

// ListOnlineDevicesByServer 返回某 server 下当前在线的设备。
func (s *Store) ListOnlineDevicesByServer(ctx context.Context, serverID string) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT server_id, device_id, online, last_seen, meta FROM device
		 WHERE server_id = ? AND online = 1 ORDER BY device_id`,
		serverID)
	if err != nil {
		return nil, fmt.Errorf("list online devices: %w", err)
	}
	defer rows.Close()
	return scanDevices(rows)
}

// GetDevice 查找单条设备记录；未命中返回 ErrDeviceNotFound。
func (s *Store) GetDevice(ctx context.Context, serverID, deviceID string) (*Device, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT server_id, device_id, online, last_seen, meta FROM device WHERE server_id = ? AND device_id = ?`,
		serverID, deviceID)
	var d Device
	var onlineInt int
	var lastSeenStr string
	if err := row.Scan(&d.ServerID, &d.DeviceID, &onlineInt, &lastSeenStr, &d.Meta); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrDeviceNotFound
		}
		return nil, fmt.Errorf("scan device: %w", err)
	}
	t, err := parseTime(lastSeenStr)
	if err != nil {
		return nil, err
	}
	d.Online = onlineInt != 0
	d.LastSeen = t
	return &d, nil
}

// DeleteDevice 删除设备记录（设备注销/解绑时调用）。
func (s *Store) DeleteDevice(ctx context.Context, serverID, deviceID string) error {
	_, err := s.db.ExecContext(ctx,
		`DELETE FROM device WHERE server_id = ? AND device_id = ?`, serverID, deviceID)
	if err != nil {
		return fmt.Errorf("delete device: %w", err)
	}
	return nil
}

func scanDevices(rows *sql.Rows) ([]Device, error) {
	var out []Device
	for rows.Next() {
		var d Device
		var onlineInt int
		var lastSeenStr string
		if err := rows.Scan(&d.ServerID, &d.DeviceID, &onlineInt, &lastSeenStr, &d.Meta); err != nil {
			return nil, fmt.Errorf("scan device row: %w", err)
		}
		t, err := parseTime(lastSeenStr)
		if err != nil {
			return nil, err
		}
		d.Online = onlineInt != 0
		d.LastSeen = t
		out = append(out, d)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate devices: %w", err)
	}
	return out, nil
}

// ---- 中继流量记账 ----

// CreateRelaySession 落库一条进行中的中继会话（EndedAt 零值）。
func (s *Store) CreateRelaySession(ctx context.Context, rs RelaySession) error {
	ended := ""
	if !rs.EndedAt.IsZero() {
		ended = rs.EndedAt.Format(time.RFC3339Nano)
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO relay_session (id, server_id, pair_key, bytes_in, bytes_out, started_at, ended_at, close_reason)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		rs.ID, rs.ServerID, rs.PairKey, rs.BytesIn, rs.BytesOut,
		rs.StartedAt.Format(time.RFC3339Nano), ended, rs.CloseReason,
	)
	if err != nil {
		return fmt.Errorf("insert relay_session: %w", err)
	}
	return nil
}

// FinalizeRelaySession 结束一条中继会话：写 EndedAt/流量计数/关闭原因。
func (s *Store) FinalizeRelaySession(ctx context.Context, id string, bytesIn, bytesOut int64, endedAt time.Time, reason string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE relay_session SET bytes_in = ?, bytes_out = ?, ended_at = ?, close_reason = ? WHERE id = ?`,
		bytesIn, bytesOut, endedAt.Format(time.RFC3339Nano), reason, id)
	if err != nil {
		return fmt.Errorf("finalize relay_session: %w", err)
	}
	return nil
}

// GetRelaySession 按 ID 查找中继会话。
func (s *Store) GetRelaySession(ctx context.Context, id string) (*RelaySession, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT id, server_id, pair_key, bytes_in, bytes_out, started_at, ended_at, close_reason FROM relay_session WHERE id = ?`, id)
	var rs RelaySession
	var startedStr, endedStr string
	if err := row.Scan(&rs.ID, &rs.ServerID, &rs.PairKey, &rs.BytesIn, &rs.BytesOut, &startedStr, &endedStr, &rs.CloseReason); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, fmt.Errorf("relay_session not found: %s", id)
		}
		return nil, fmt.Errorf("scan relay_session: %w", err)
	}
	st, err := parseTime(startedStr)
	if err != nil {
		return nil, err
	}
	rs.StartedAt = st
	if endedStr != "" {
		et, err := parseTime(endedStr)
		if err != nil {
			return nil, err
		}
		rs.EndedAt = et
	}
	return &rs, nil
}

// ListRelaySessionsByServer 返回某 server 下中继会话（默认最近在前，limit 上限保护）。
func (s *Store) ListRelaySessionsByServer(ctx context.Context, serverID string, limit int) ([]RelaySession, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, server_id, pair_key, bytes_in, bytes_out, started_at, ended_at, close_reason
		 FROM relay_session WHERE server_id = ? ORDER BY started_at DESC LIMIT ?`,
		serverID, limit)
	if err != nil {
		return nil, fmt.Errorf("list relay_sessions: %w", err)
	}
	defer rows.Close()
	var out []RelaySession
	for rows.Next() {
		var rs RelaySession
		var startedStr, endedStr string
		if err := rows.Scan(&rs.ID, &rs.ServerID, &rs.PairKey, &rs.BytesIn, &rs.BytesOut, &startedStr, &endedStr, &rs.CloseReason); err != nil {
			return nil, fmt.Errorf("scan relay_session row: %w", err)
		}
		st, err := parseTime(startedStr)
		if err != nil {
			return nil, err
		}
		rs.StartedAt = st
		if endedStr != "" {
			et, err := parseTime(endedStr)
			if err != nil {
				return nil, err
			}
			rs.EndedAt = et
		}
		out = append(out, rs)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate relay_sessions: %w", err)
	}
	return out, nil
}

// RelayTrafficSummary 是某 server 的中继流量汇总，供记账查询端点返回。
type RelayTrafficSummary struct {
	ServerID string `json:"server_id"`
	Count    int    `json:"session_count"`
	TotalIn  int64  `json:"total_bytes_in"`
	TotalOut int64  `json:"total_bytes_out"`
	Active   int    `json:"active_sessions"`
}

// RelayTrafficSummaryByServer 聚合某 server 的中继会话计数与流量。
func (s *Store) RelayTrafficSummaryByServer(ctx context.Context, serverID string) (RelayTrafficSummary, error) {
	var sum RelayTrafficSummary
	sum.ServerID = serverID
	row := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*),
		        COALESCE(SUM(bytes_in), 0),
		        COALESCE(SUM(bytes_out), 0),
		        COALESCE(SUM(CASE WHEN ended_at = '' THEN 1 ELSE 0 END), 0)
		 FROM relay_session WHERE server_id = ?`, serverID)
	if err := row.Scan(&sum.Count, &sum.TotalIn, &sum.TotalOut, &sum.Active); err != nil {
		return sum, fmt.Errorf("relay traffic summary: %w", err)
	}
	return sum, nil
}

// ---- 跨 server 聚合（admin 看板用）----
//
// 下列方法面向运营管理前端"全局视图"：跨所有 server 汇总设备、服务端与会话。
// 与上面的按 server 查询互补，避免 handler 逐 server 遍历（单连接串行下 N 次往返）。

// ListServers 返回全部受管服务端，按最近活跃在前排序。
func (s *Store) ListServers(ctx context.Context) ([]Server, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, name, token_hash, created_at, last_seen FROM server ORDER BY last_seen DESC`)
	if err != nil {
		return nil, fmt.Errorf("list servers: %w", err)
	}
	defer rows.Close()
	var out []Server
	for rows.Next() {
		var srv Server
		var createdStr, lastSeenStr string
		if err := rows.Scan(&srv.ID, &srv.Name, &srv.TokenHash, &createdStr, &lastSeenStr); err != nil {
			return nil, fmt.Errorf("scan server row: %w", err)
		}
		ct, err := parseTime(createdStr)
		if err != nil {
			return nil, err
		}
		lt, err := parseTime(lastSeenStr)
		if err != nil {
			return nil, err
		}
		srv.CreatedAt = ct
		srv.LastSeen = lt
		out = append(out, srv)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate servers: %w", err)
	}
	return out, nil
}

// ListAllDevices 返回全量设备记录（跨 server），按 server_id、device_id 排序。
// 供 admin"用户列表"页面展示。结果集可能较大，调用方应在 handler 层不做无限增长假设；
// 当前运营规模有限，全量返回可接受。
func (s *Store) ListAllDevices(ctx context.Context) ([]Device, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT server_id, device_id, online, last_seen, meta FROM device
		 ORDER BY server_id, device_id`)
	if err != nil {
		return nil, fmt.Errorf("list all devices: %w", err)
	}
	defer rows.Close()
	return scanDevices(rows)
}

// ListAllRelaySessions 返回全量中继会话（跨 server），最近在前，limit 上限保护。
func (s *Store) ListAllRelaySessions(ctx context.Context, limit int) ([]RelaySession, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, server_id, pair_key, bytes_in, bytes_out, started_at, ended_at, close_reason
		 FROM relay_session ORDER BY started_at DESC LIMIT ?`, limit)
	if err != nil {
		return nil, fmt.Errorf("list all relay_sessions: %w", err)
	}
	defer rows.Close()
	var out []RelaySession
	for rows.Next() {
		var rs RelaySession
		var startedStr, endedStr string
		if err := rows.Scan(&rs.ID, &rs.ServerID, &rs.PairKey, &rs.BytesIn, &rs.BytesOut, &startedStr, &endedStr, &rs.CloseReason); err != nil {
			return nil, fmt.Errorf("scan relay_session row: %w", err)
		}
		st, err := parseTime(startedStr)
		if err != nil {
			return nil, err
		}
		rs.StartedAt = st
		if endedStr != "" {
			et, err := parseTime(endedStr)
			if err != nil {
				return nil, err
			}
			rs.EndedAt = et
		}
		out = append(out, rs)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate relay_sessions: %w", err)
	}
	return out, nil
}

// ServerDeviceCount 返回各 server 名下设备计数（id → count）。
// 用 GROUP BY 一次聚合，避免按 server 逐次 COUNT。
func (s *Store) ServerDeviceCount(ctx context.Context) (map[string]int, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT server_id, COUNT(*) FROM device GROUP BY server_id`)
	if err != nil {
		return nil, fmt.Errorf("count devices by server: %w", err)
	}
	defer rows.Close()
	out := make(map[string]int)
	for rows.Next() {
		var sid string
		var n int
		if err := rows.Scan(&sid, &n); err != nil {
			return nil, fmt.Errorf("scan device count row: %w", err)
		}
		out[sid] = n
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate device counts: %w", err)
	}
	return out, nil
}

// GlobalRelaySummary 全局中继流量汇总（跨所有 server）。
type GlobalRelaySummary struct {
	SessionCount   int   `json:"session_count"`
	ActiveSessions int   `json:"active_sessions"`
	TotalBytesIn   int64 `json:"total_bytes_in"`
	TotalBytesOut  int64 `json:"total_bytes_out"`
}

// GlobalRelaySummary 返回跨所有 server 的中继会话计数与流量。
func (s *Store) GlobalRelaySummary(ctx context.Context) (GlobalRelaySummary, error) {
	var g GlobalRelaySummary
	row := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*),
		        COALESCE(SUM(bytes_in), 0),
		        COALESCE(SUM(bytes_out), 0),
		        COALESCE(SUM(CASE WHEN ended_at = '' THEN 1 ELSE 0 END), 0)
		 FROM relay_session`)
	if err := row.Scan(&g.SessionCount, &g.TotalBytesIn, &g.TotalBytesOut, &g.ActiveSessions); err != nil {
		return g, fmt.Errorf("global relay summary: %w", err)
	}
	return g, nil
}

// AllRelaySummaries 返回每个 server 的中继流量汇总，外加一条 server_id="*" 的全局合计。
// 单次扫描用 GROUP BY 聚合，比逐 server 调用 RelayTrafficSummaryByServer 更省往返。
func (s *Store) AllRelaySummaries(ctx context.Context) ([]RelayTrafficSummary, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT server_id,
		        COUNT(*),
		        COALESCE(SUM(bytes_in), 0),
		        COALESCE(SUM(bytes_out), 0),
		        COALESCE(SUM(CASE WHEN ended_at = '' THEN 1 ELSE 0 END), 0)
		 FROM relay_session GROUP BY server_id ORDER BY server_id`)
	if err != nil {
		return nil, fmt.Errorf("all relay summaries: %w", err)
	}
	defer rows.Close()
	var out []RelayTrafficSummary
	for rows.Next() {
		var r RelayTrafficSummary
		var serverID string
		if err := rows.Scan(&serverID, &r.Count, &r.TotalIn, &r.TotalOut, &r.Active); err != nil {
			return nil, fmt.Errorf("scan relay summary row: %w", err)
		}
		r.ServerID = serverID
		out = append(out, r)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate relay summaries: %w", err)
	}
	return out, nil
}

// DeviceCountTotals 返回全量设备数与在线设备数。
func (s *Store) DeviceCountTotals(ctx context.Context) (total, online int, err error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*), COALESCE(SUM(CASE WHEN online=1 THEN 1 ELSE 0 END),0) FROM device`)
	if err = row.Scan(&total, &online); err != nil {
		return 0, 0, fmt.Errorf("device count totals: %w", err)
	}
	return total, online, nil
}

// CountServers 返回受管服务端总数。
func (s *Store) CountServers(ctx context.Context) (int, error) {
	var n int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM server`).Scan(&n); err != nil {
		return 0, fmt.Errorf("count servers: %w", err)
	}
	return n, nil
}

// ---- 工具 ----

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// parseTime 解析 RFC3339Nano 字符串；空串返回零值。
func parseTime(s string) (time.Time, error) {
	if s == "" {
		return time.Time{}, nil
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		return time.Time{}, fmt.Errorf("parse time %q: %w", s, err)
	}
	return t, nil
}
