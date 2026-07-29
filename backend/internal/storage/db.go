package storage

import (
	"context"
	"database/sql"
	"fmt"

	// modernc.org/sqlite 注册名为 "sqlite" 的纯 Go driver（免 CGO）。
	// 仅以副作用方式 import，通过 sql.Open("sqlite", ...) 使用。
	_ "modernc.org/sqlite"
)

// schemaSQL 是初始建表 DDL。三张表字段严格对应任务定义：
//   - user:   id, username, password_hash, role, created_at
//   - media:  id, user_id, filename, type, size, mime, width, height,
//             created_at, updated_at, sha256, deleted
//   - device: id, user_id, name, platform, created_at
//
// 设计取舍：
//   - 时间列用 TEXT 存 RFC3339 字符串（time.Time↔string 在 repository 层转换），
//     避开 driver 对 INTEGER→time.Time 的不一致行为，保持跨平台可读。
//   - "type" 是 SQL 倾向关键字，以双引号包裹，SQLite 允许作为列名。
//   - deleted 用 INTEGER(0/1) 表达布尔软删除；CRUD 层映射 bool。
//   - 外键约束显式声明并配 PRAGMA foreign_keys=ON 强制生效。
//   - CREATE TABLE IF NOT EXISTS 保证迁移幂等。
const schemaSQL = `
CREATE TABLE IF NOT EXISTS "user" (
    id            TEXT PRIMARY KEY,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    role          TEXT NOT NULL DEFAULT 'user',
    created_at    TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS "media" (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    filename   TEXT NOT NULL,
    "type"     TEXT NOT NULL,
    size       INTEGER NOT NULL DEFAULT 0,
    mime       TEXT NOT NULL DEFAULT '',
    width      INTEGER NOT NULL DEFAULT 0,
    height     INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    sha256     TEXT NOT NULL DEFAULT '',
    deleted    INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (user_id) REFERENCES "user"(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS "device" (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    name       TEXT NOT NULL DEFAULT '',
    platform   TEXT NOT NULL DEFAULT '',
    created_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES "user"(id) ON DELETE CASCADE
);
`

// Store 封装一个 SQLite 连接与其上的 CRUD 能力。
// 当前直接持有 *sql.DB；若后续需要读写分离或连接池调优再演进。
type Store struct {
	db *sql.DB
}

// Open 打开位于 dbPath 的 SQLite 数据库并执行初始化：
//   - 设置 PRAGMA（WAL 提升并发读、外键约束开启、busy_timeout 避免短时锁冲突）。
//   - 运行建表迁移。
//
// dbPath 为空时返回错误，避免误创建匿名内存库导致数据丢失。
func Open(dbPath string) (*Store, error) {
	if dbPath == "" {
		return nil, fmt.Errorf("db_path is empty")
	}
	// modernc.org/sqlite 的 DSN：文件路径即可，`:memory:` 也支持。
	// 配以 busy_timeout 与 foreign_keys 参数，作为 PRAGMA 的替代稳妥路径。
	dsn := fmt.Sprintf("file:%s?_pragma=busy_timeout(5000)&_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)", dbPath)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open sqlite %s: %w", dbPath, err)
	}
	// 单写连接足以支撑当前元数据写入量；max open conns=1 可规避 SQLite
	// "database is locked"（即便有 WAL，写仍串行）。读连接放开以利列表查询。
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

// Migrate 执行建表 DDL（幂等）。后续若需 schema 演进，在此扩展版本化迁移逻辑。
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

// DB 暴露底层 *sql.DB，供需要直接执行 SQL 的调用方使用（如批量导入）。
// 注意：通过此句柄的写操作仍受 MaxOpenConns=1 串行化约束。
func (s *Store) DB() *sql.DB {
	return s.db
}
