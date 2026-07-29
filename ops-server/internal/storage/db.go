package storage

import (
	"context"
	"database/sql"
	"fmt"

	// modernc.org/sqlite 注册名为 "sqlite" 的纯 Go driver（免 CGO）。
	// 仅以副作用方式 import，通过 sql.Open("sqlite", ...) 使用——与 backend 保持一致。
	_ "modernc.org/sqlite"
)

// schemaSQL 是初始建表 DDL。四张表字段严格对应任务定义（见 model.go 包注释）。
//
// 设计取舍：
//   - 时间列用 TEXT 存 RFC3339 字符串。device/relay_session 的时间在 repository 层转换。
//   - device 主键为复合 (server_id, device_id)：同一 server 下 device 唯一；
//     不同 server 的同名 device 各自独立，符合"设备发现按 server 隔离"语义。
//   - relay_session.server_id 允许空串：跨 server 的中继对未必隶属单一 server；
//     此处保留列以便在归属明确时记账，但不强制外键（避免收紧导致无法记账）。
//   - server.token_hash 唯一性不强制（仅作认证比对），username 不约束。
//   - CREATE TABLE IF NOT EXISTS 保证迁移幂等。
const schemaSQL = `
CREATE TABLE IF NOT EXISTS op_account (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS server (
    id         TEXT PRIMARY KEY,
    name       TEXT NOT NULL DEFAULT '',
    token_hash TEXT NOT NULL,
    created_at TEXT NOT NULL,
    last_seen  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS device (
    server_id   TEXT NOT NULL,
    device_id   TEXT NOT NULL,
    online      INTEGER NOT NULL DEFAULT 0,
    last_seen   TEXT NOT NULL,
    meta        TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (server_id, device_id),
    FOREIGN KEY (server_id) REFERENCES server(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS relay_session (
    id           TEXT PRIMARY KEY,
    server_id    TEXT NOT NULL DEFAULT '',
    pair_key     TEXT NOT NULL DEFAULT '',
    bytes_in     INTEGER NOT NULL DEFAULT 0,
    bytes_out    INTEGER NOT NULL DEFAULT 0,
    started_at   TEXT NOT NULL,
    ended_at     TEXT NOT NULL DEFAULT '',
    close_reason TEXT NOT NULL DEFAULT ''
);
`

// Store 封装一个 SQLite 连接与其上的 CRUD 能力。
type Store struct {
	db *sql.DB
}

// Open 打开位于 dbPath 的 SQLite 数据库并执行初始化（PRAGMA + 建表）。
func Open(dbPath string) (*Store, error) {
	if dbPath == "" {
		return nil, fmt.Errorf("db_path is empty")
	}
	// busy_timeout 规避短时写锁；foreign_keys 强制级联；WAL 提升并发读。
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)", dbPath)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite %s: %w", dbPath, err)
	}
	// SQLite 写串行：MaxOpenConns=1 规避 "database is locked"。
	db.SetMaxOpenConns(1)

	if err := db.PingContext(context.Background()); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping sqlite %s: %w", dbPath, err)
	}

	s := &Store{db: db}
	if err := s.Migrate(context.Background()); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

// Migrate 执行建表 DDL（幂等）。
func (s *Store) Migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, schemaSQL); err != nil {
		return fmt.Errorf("migrate schema: %w", err)
	}
	return nil
}

// Close 关闭底层连接，应在服务停机时调用。
func (s *Store) Close() error {
	if s.db == nil {
		return nil
	}
	return s.db.Close()
}

// DB 暴露底层 *sql.DB，供需要直接执行 SQL 的调用方使用（如批量统计）。
func (s *Store) DB() *sql.DB { return s.db }
