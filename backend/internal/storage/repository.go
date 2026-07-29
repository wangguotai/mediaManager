package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"time"
)

// ErrNotFound 表示按主键查询未命中行。调用方可用 errors.Is 判别。
var ErrNotFound = errors.New("record not found")

// ---- 时间与软删除的列值转换辅助 ----
//
// SQLite 列约定：时间 TEXT(RFC3339Nano)，deleted INTEGER(0/1)。
// 这组辅助统一处理 Go time.Time/bool 与列值之间的转换，避免每个方法重复模板。

// timeToVal 把 time.Time 格式化为 RFC3339Nano 字符串；零值返回空串便于 NOT NULL 列兜底。
func timeToVal(t time.Time) string {
	if t.IsZero() {
		return time.Now().UTC().Format(time.RFC3339Nano)
	}
	return t.UTC().Format(time.RFC3339Nano)
}

// timeFromVal 把 RFC3339 字符串解析回 time.Time；解析失败或空串返回零值（不报错，
// 与列表接口“单坏行不阻断整体”的策略一致）。
func timeFromVal(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	t, err := time.Parse(time.RFC3339Nano, s)
	if err != nil {
		// 兼容可能被 SQLite datetime() 写入的旧格式（仅 RFC3339 兜底失败时）。
		if t2, err2 := time.Parse(time.RFC3339, s); err2 == nil {
			return t2
		}
		return time.Time{}
	}
	return t
}

// boolFromVal 把 SQLite 整数列转 bool（非零为真）。
func boolFromVal(v int) bool { return v != 0 }

// ===== User =====

// CreateUser 插入一行 user。ID/Username/PasswordHash/Role 不应留空；
// CreatedAt 为零值时由代码置为当前时间（数据库列未设默认，故在此兜底）。
func (s *Store) CreateUser(ctx context.Context, u *User) error {
	if u == nil {
		return fmt.Errorf("user is nil")
	}
	if u.ID == "" || u.Username == "" {
		return fmt.Errorf("user id and username are required")
	}
	role := u.Role
	if role == "" {
		role = "user"
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO "user" (id, username, password_hash, role, created_at)
VALUES (?, ?, ?, ?, ?)`,
		u.ID, u.Username, u.PasswordHash, role, timeToVal(u.CreatedAt))
	if err != nil {
		return fmt.Errorf("insert user: %w", err)
	}
	return nil
}

// GetUser 按 id 取单行 user。未命中返回 ErrNotFound。
func (s *Store) GetUser(ctx context.Context, id string) (*User, error) {
	row := s.db.QueryRowContext(ctx, `SELECT id, username, password_hash, role, created_at FROM "user" WHERE id = ?`, id)
	var u User
	var createdAt string
	if err := row.Scan(&u.ID, &u.Username, &u.PasswordHash, &u.Role, &createdAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("get user: %w", err)
	}
	u.CreatedAt = timeFromVal(createdAt)
	return &u, nil
}

// GetUserByUsername 按唯一 username 取单行 user。用于登录校验。未命中返回 ErrNotFound。
func (s *Store) GetUserByUsername(ctx context.Context, username string) (*User, error) {
	row := s.db.QueryRowContext(ctx, `SELECT id, username, password_hash, role, created_at FROM "user" WHERE username = ?`, username)
	var u User
	var createdAt string
	if err := row.Scan(&u.ID, &u.Username, &u.PasswordHash, &u.Role, &createdAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("get user by username: %w", err)
	}
	u.CreatedAt = timeFromVal(createdAt)
	return &u, nil
}

// ListUsers 返回全部用户，按 created_at 升序。当前数据量小，不分页；
// 需要分页时在调用方做截断或在此加 limit/offset 参数。
func (s *Store) ListUsers(ctx context.Context) ([]*User, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, username, password_hash, role, created_at FROM "user" ORDER BY created_at ASC`)
	if err != nil {
		return nil, fmt.Errorf("list users: %w", err)
	}
	defer rows.Close()
	var out []*User
	for rows.Next() {
		var u User
		var createdAt string
		if err := rows.Scan(&u.ID, &u.Username, &u.PasswordHash, &u.Role, &createdAt); err != nil {
			return nil, fmt.Errorf("scan user: %w", err)
		}
		u.CreatedAt = timeFromVal(createdAt)
		out = append(out, &u)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows users: %w", err)
	}
	return out, nil
}

// DeleteUser 按 id 物理删除 user。关联的 media/device 因 ON DELETE CASCADE 一并删除。
// 未命中不报错（幂等）。
func (s *Store) DeleteUser(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM "user" WHERE id = ?`, id)
	if err != nil {
		return fmt.Errorf("delete user: %w", err)
	}
	return nil
}

// ===== Media =====

// CreateMedia 插入一行 media。ID/UserID/Filename/Type 必填。
func (s *Store) CreateMedia(ctx context.Context, m *Media) error {
	if m == nil {
		return fmt.Errorf("media is nil")
	}
	if m.ID == "" || m.UserID == "" {
		return fmt.Errorf("media id and user_id are required")
	}
	now := time.Now()
	if m.CreatedAt.IsZero() {
		m.CreatedAt = now
	}
	if m.UpdatedAt.IsZero() {
		m.UpdatedAt = now
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO "media" (id, user_id, filename, "type", size, mime, width, height, created_at, updated_at, sha256, deleted)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		m.ID, m.UserID, m.Filename, m.Type, m.Size, m.Mime, m.Width, m.Height,
		timeToVal(m.CreatedAt), timeToVal(m.UpdatedAt), m.SHA256, boolToInt(m.Deleted))
	if err != nil {
		return fmt.Errorf("insert media: %w", err)
	}
	return nil
}

// GetMedia 按 id 取单行 media（含已软删除行，便于审计/恢复）。未命中返回 ErrNotFound。
func (s *Store) GetMedia(ctx context.Context, id string) (*Media, error) {
	row := s.db.QueryRowContext(ctx, `
SELECT id, user_id, filename, "type", size, mime, width, height, created_at, updated_at, sha256, deleted
FROM "media" WHERE id = ?`, id)
	return scanMedia(row.Scan)
}

// ListMediaByUser 返回某用户的所有未软删除媒体，按 created_at 降序（最新在前），
// 与现有 MediaService 列表排序一致。
func (s *Store) ListMediaByUser(ctx context.Context, userID string) ([]*Media, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT id, user_id, filename, "type", size, mime, width, height, created_at, updated_at, sha256, deleted
FROM "media" WHERE user_id = ? AND deleted = 0 ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, fmt.Errorf("list media by user: %w", err)
	}
	return scanMediaRows(rows)
}

// UpdateMedia 更新一行 media 的可变元数据字段（filename/type/size/mime/width/height/sha256）。
//   - UpdatedAt 强制刷新为当前时间。
//   - 不触动 deleted 列：软删除状态由 MarkDeleted 专属管理，元数据更新不得复活
//     已软删的记录。这样一次部分字段的更新不会把 deleted 清零。
//   - 这是"部分覆盖"语义：调用方传入的字段会写回，未传入字段的零值也会被写入
//     （例如重命名时仍把 size=0 写回）。如需"只改非零字段"的合并语义，在调用方
//     先 GetMedia 再修改后回写。
func (s *Store) UpdateMedia(ctx context.Context, m *Media) error {
	if m == nil || m.ID == "" {
		return fmt.Errorf("media id is required")
	}
	m.UpdatedAt = time.Now()
	res, err := s.db.ExecContext(ctx, `
UPDATE "media" SET filename=?, "type"=?, size=?, mime=?, width=?, height=?, updated_at=?, sha256=?
WHERE id = ?`,
		m.Filename, m.Type, m.Size, m.Mime, m.Width, m.Height,
		timeToVal(m.UpdatedAt), m.SHA256, m.ID)
	if err != nil {
		return fmt.Errorf("update media: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// MarkDeleted 软删除一行 media（deleted=1），刷新 updated_at。未命中返回 ErrNotFound。
// 软删除保留数据，与现有 DeleteMedia（物理删文件）解耦：SQL 软删 + 磁盘文件清理
// 可由后续任务分别处理。
func (s *Store) MarkDeleted(ctx context.Context, id string) error {
	res, err := s.db.ExecContext(ctx, `UPDATE "media" SET deleted = 1, updated_at = ? WHERE id = ?`, timeToVal(time.Now()), id)
	if err != nil {
		return fmt.Errorf("mark media deleted: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteMedia 按 id 物理删除一行 media。未命中不报错（幂等）。
// 多数场景应优先用 MarkDeleted；此方法供清理/重置使用。
func (s *Store) DeleteMedia(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM "media" WHERE id = ?`, id)
	if err != nil {
		return fmt.Errorf("delete media: %w", err)
	}
	return nil
}

// ===== Device =====

// CreateDevice 插入一行 device。ID/UserID 必填。CreatedAt 零值时置当前时间。
func (s *Store) CreateDevice(ctx context.Context, d *Device) error {
	if d == nil {
		return fmt.Errorf("device is nil")
	}
	if d.ID == "" || d.UserID == "" {
		return fmt.Errorf("device id and user_id are required")
	}
	_, err := s.db.ExecContext(ctx, `
INSERT INTO "device" (id, user_id, name, platform, created_at)
VALUES (?, ?, ?, ?, ?)`,
		d.ID, d.UserID, d.Name, d.Platform, timeToVal(d.CreatedAt))
	if err != nil {
		return fmt.Errorf("insert device: %w", err)
	}
	return nil
}

// GetDevice 按 id 取单行 device。未命中返回 ErrNotFound。
func (s *Store) GetDevice(ctx context.Context, id string) (*Device, error) {
	row := s.db.QueryRowContext(ctx, `SELECT id, user_id, name, platform, created_at FROM "device" WHERE id = ?`, id)
	var d Device
	var createdAt string
	if err := row.Scan(&d.ID, &d.UserID, &d.Name, &d.Platform, &createdAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("get device: %w", err)
	}
	d.CreatedAt = timeFromVal(createdAt)
	return &d, nil
}

// ListDevicesByUser 返回某用户的全部设备，按 created_at 升序。
func (s *Store) ListDevicesByUser(ctx context.Context, userID string) ([]*Device, error) {
	rows, err := s.db.QueryContext(ctx, `SELECT id, user_id, name, platform, created_at FROM "device" WHERE user_id = ? ORDER BY created_at ASC`, userID)
	if err != nil {
		return nil, fmt.Errorf("list devices by user: %w", err)
	}
	defer rows.Close()
	var out []*Device
	for rows.Next() {
		var d Device
		var createdAt string
		if err := rows.Scan(&d.ID, &d.UserID, &d.Name, &d.Platform, &createdAt); err != nil {
			return nil, fmt.Errorf("scan device: %w", err)
		}
		d.CreatedAt = timeFromVal(createdAt)
		out = append(out, &d)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows devices: %w", err)
	}
	return out, nil
}

// DeleteDevice 按 id 物理删除 device。未命中不报错（幂等）。
func (s *Store) DeleteDevice(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, `DELETE FROM "device" WHERE id = ?`, id)
	if err != nil {
		return fmt.Errorf("delete device: %w", err)
	}
	return nil
}

// ===== scan 辅助 =====

// scanFuncRow 是 QueryRow.Scan 的签名；用于让单行与多行复用同一列扫描逻辑。
type scanFunc func(dest ...any) error

// scanMedia 把一行 media 列扫描进 *Media。传入 row.Scan 或 rows.Scan。
func scanMedia(scan scanFunc) (*Media, error) {
	var m Media
	var createdAt, updatedAt string
	var deleted int
	if err := scan(&m.ID, &m.UserID, &m.Filename, &m.Type, &m.Size, &m.Mime,
		&m.Width, &m.Height, &createdAt, &updatedAt, &m.SHA256, &deleted); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("scan media: %w", err)
	}
	m.CreatedAt = timeFromVal(createdAt)
	m.UpdatedAt = timeFromVal(updatedAt)
	m.Deleted = boolFromVal(deleted)
	return &m, nil
}

// scanMediaRows 遍历多行 media，关闭 rows 后返回集合。
func scanMediaRows(rows *sql.Rows) ([]*Media, error) {
	defer rows.Close()
	var out []*Media
	for rows.Next() {
		m, err := scanMedia(rows.Scan)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows media: %w", err)
	}
	return out, nil
}

// boolToInt 把 bool 映射为 SQLite 整数列值。
func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
