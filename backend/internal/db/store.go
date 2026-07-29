// Package db 提供 SQLite 元数据存储，基于纯 Go 的 modernc.org/sqlite 驱动（免 CGO）。
//
// 三张表：
//   - user    (id, username, password_hash, role, created_at)
//   - media   (id, user_id, filename, type, size, mime, width, height, created_at, updated_at, sha256, deleted)
//   - device  (id, user_id, name, platform, created_at)
//
// Store 封装 *sql.DB 并提供用户、媒体、设备三大类的 CRUD。
// 所有查询都按 user_id 维度隔离，调用方传入 uid 后只操作该用户的数据。
package db

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	_ "modernc.org/sqlite" // 纯 Go SQLite 驱动，注册 "sqlite" 到 database/sql
)

// ErrNotFound 表示按主键/唯一键查询未命中。
var ErrNotFound = errors.New("record not found")

// ErrDuplicate 表示插入违反唯一约束（如用户名重复）。
var ErrDuplicate = errors.New("duplicate record")

// User 对应 user 表一行。
type User struct {
	ID           string
	Username     string
	PasswordHash string
	Role         string
	CreatedAt    int64
}

// MediaRecord 对应 media 表一行（媒体元数据）。
type MediaRecord struct {
	ID        string
	UserID    string
	Filename  string
	Type      string
	Size      int64
	Mime      string
	Width     int32
	Height    int32
	CreatedAt int64
	UpdatedAt int64
	Sha256    string
	Deleted   bool
}

// Device 对应 device 表一行。
type Device struct {
	ID        string
	UserID    string
	Name      string
	Platform  string
	CreatedAt int64
}

// Store 是所有持久化操作的入口，持有 *sql.DB。
type Store struct {
	db *sql.DB
}

// Open 打开（必要时创建）SQLite 库文件并完成建表与索引。
// dsn 形如 "file:./data/media-manager.db?_pragma=busy_timeout(5000)"。
func Open(dsn string) (*Store, error) {
	sdb, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}
	// SQLite 单写多读：连接池保留 1 个写连接即可，读连接可并发。
	// 避免并发写触发 "database is locked"。
	sdb.SetMaxOpenConns(1)

	s := &Store{db: sdb}
	if err := s.migrate(context.Background()); err != nil {
		_ = sdb.Close()
		return nil, err
	}
	return s, nil
}

// Close 关闭底层连接。
func (s *Store) Close() error { return s.db.Close() }

// migrate 建表与索引（幂等），使用 IF NOT EXISTS。
func (s *Store) migrate(ctx context.Context) error {
	stmts := []string{
		`CREATE TABLE IF NOT EXISTS user (
			id            TEXT PRIMARY KEY,
			username      TEXT NOT NULL UNIQUE,
			password_hash TEXT NOT NULL,
			role          TEXT NOT NULL DEFAULT 'user',
			created_at    INTEGER NOT NULL
		)`,
		`CREATE TABLE IF NOT EXISTS media (
			id         TEXT PRIMARY KEY,
			user_id    TEXT NOT NULL,
			filename   TEXT NOT NULL,
			type       TEXT NOT NULL,
			size       INTEGER NOT NULL DEFAULT 0,
			mime       TEXT NOT NULL DEFAULT '',
			width      INTEGER NOT NULL DEFAULT 0,
			height     INTEGER NOT NULL DEFAULT 0,
			created_at INTEGER NOT NULL,
			updated_at INTEGER NOT NULL,
			sha256     TEXT NOT NULL DEFAULT '',
			deleted    INTEGER NOT NULL DEFAULT 0,
			FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX IF NOT EXISTS idx_media_user ON media(user_id, deleted)`,
		`CREATE TABLE IF NOT EXISTS device (
			id         TEXT PRIMARY KEY,
			user_id    TEXT NOT NULL,
			name       TEXT NOT NULL DEFAULT '',
			platform   TEXT NOT NULL DEFAULT '',
			created_at INTEGER NOT NULL,
			FOREIGN KEY (user_id) REFERENCES user(id) ON DELETE CASCADE
		)`,
		`CREATE INDEX IF NOT EXISTS idx_device_user ON device(user_id)`,
		`PRAGMA journal_mode=WAL`,
		`PRAGMA foreign_keys=ON`,
	}
	for _, q := range stmts {
		if _, err := s.db.ExecContext(ctx, q); err != nil {
			return fmt.Errorf("migrate: %w (stmt=%s)", err, firstLine(q))
		}
	}
	return nil
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return strings.TrimSpace(s[:i])
	}
	return strings.TrimSpace(s)
}

// ============ User ============

// CreateUser 插入新用户。用户名唯一冲突时返回 ErrDuplicate。
func (s *Store) CreateUser(ctx context.Context, u *User) error {
	if u.ID == "" {
		u.ID = uuid.New().String()
	}
	if u.CreatedAt == 0 {
		u.CreatedAt = time.Now().Unix()
	}
	if u.Role == "" {
		u.Role = "user"
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO user (id, username, password_hash, role, created_at) VALUES (?, ?, ?, ?, ?)`,
		u.ID, u.Username, u.PasswordHash, u.Role, u.CreatedAt,
	)
	if err != nil {
		if isUniqueViolation(err) {
			return ErrDuplicate
		}
		return err
	}
	return nil
}

// GetUserByUsername 按用户名查询；未命中返回 ErrNotFound。
func (s *Store) GetUserByUsername(ctx context.Context, username string) (*User, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT id, username, password_hash, role, created_at FROM user WHERE username = ?`, username)
	return scanUser(row)
}

// GetUserByID 按主键查询；未命中返回 ErrNotFound。
func (s *Store) GetUserByID(ctx context.Context, id string) (*User, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT id, username, password_hash, role, created_at FROM user WHERE id = ?`, id)
	return scanUser(row)
}

// UserCount 返回用户总数，用于 allow_signup=first 判定。
func (s *Store) UserCount(ctx context.Context) (int, error) {
	var n int
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM user`).Scan(&n); err != nil {
		return 0, err
	}
	return n, nil
}

type rowScanner interface {
	Scan(dest ...any) error
}

func scanUser(r rowScanner) (*User, error) {
	u := &User{}
	if err := r.Scan(&u.ID, &u.Username, &u.PasswordHash, &u.Role, &u.CreatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return u, nil
}

// ============ Media ============

// UpsertMedia 插入或更新媒体元数据（按 id 主键）。
func (s *Store) UpsertMedia(ctx context.Context, m *MediaRecord) error {
	if m.CreatedAt == 0 {
		m.CreatedAt = time.Now().Unix()
	}
	m.UpdatedAt = time.Now().Unix()
	deleted := 0
	if m.Deleted {
		deleted = 1
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO media (id, user_id, filename, type, size, mime, width, height, created_at, updated_at, sha256, deleted)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(id) DO UPDATE SET
		   filename=excluded.filename, type=excluded.type, size=excluded.size,
		   mime=excluded.mime, width=excluded.width, height=excluded.height,
		   updated_at=excluded.updated_at, sha256=excluded.sha256, deleted=excluded.deleted`,
		m.ID, m.UserID, m.Filename, m.Type, m.Size, m.Mime, m.Width, m.Height,
		m.CreatedAt, m.UpdatedAt, m.Sha256, deleted,
	)
	return err
}

// MarkMediaDeleted 软删除指定用户名下的媒体（deleted=1）。
func (s *Store) MarkMediaDeleted(ctx context.Context, userID, mediaID string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE media SET deleted=1, updated_at=? WHERE id=? AND user_id=?`,
		time.Now().Unix(), mediaID, userID)
	return err
}

// ListMediaByUser 返回某用户名下未删除的媒体记录。
func (s *Store) ListMediaByUser(ctx context.Context, userID string) ([]*MediaRecord, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, user_id, filename, type, size, mime, width, height, created_at, updated_at, sha256, deleted
		 FROM media WHERE user_id=? AND deleted=0 ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*MediaRecord
	for rows.Next() {
		m, err := scanMedia(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

func scanMedia(r rowScanner) (*MediaRecord, error) {
	m := &MediaRecord{}
	var deleted int
	if err := r.Scan(&m.ID, &m.UserID, &m.Filename, &m.Type, &m.Size, &m.Mime,
		&m.Width, &m.Height, &m.CreatedAt, &m.UpdatedAt, &m.Sha256, &deleted); err != nil {
		return nil, err
	}
	m.Deleted = deleted != 0
	return m, nil
}

// ============ Device ============

// RegisterDevice 记录一个设备（登录时调用，便于审计与未来推送）。
func (s *Store) RegisterDevice(ctx context.Context, d *Device) error {
	if d.ID == "" {
		d.ID = uuid.New().String()
	}
	if d.CreatedAt == 0 {
		d.CreatedAt = time.Now().Unix()
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO device (id, user_id, name, platform, created_at) VALUES (?, ?, ?, ?, ?)`,
		d.ID, d.UserID, d.Name, d.Platform, d.CreatedAt)
	return err
}

// ListDevicesByUser 返回某用户名下的设备列表。
func (s *Store) ListDevicesByUser(ctx context.Context, userID string) ([]*Device, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, user_id, name, platform, created_at FROM device WHERE user_id=? ORDER BY created_at DESC`,
		userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Device
	for rows.Next() {
		d := &Device{}
		if err := rows.Scan(&d.ID, &d.UserID, &d.Name, &d.Platform, &d.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

// isUniqueViolation 判断是否唯一约束冲突（modernc.org/sqlite 错误信息含 "UNIQUE"）。
func isUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	msg := err.Error()
	return strings.Contains(msg, "UNIQUE") || strings.Contains(msg, "constraint failed: UNIQUE")
}
