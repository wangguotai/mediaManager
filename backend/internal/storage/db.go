package storage

import (
	"context"
	"database/sql"
	"fmt"
	"strings"

	// modernc.org/sqlite 注册名为 "sqlite" 的纯 Go driver（免 CGO）。
	// 仅以副作用方式 import，通过 sql.Open("sqlite", ...) 使用。
	_ "modernc.org/sqlite"
)

// schemaSQL 是初始建表 DDL。三张表字段严格对应任务定义：
//   - user:   id, username, password_hash, role, created_at
//   - media:  id, user_id, filename, type, size, mime, width, height,
//     created_at, updated_at, sha256, deleted, client_id, taken_at
//   - device: id, user_id, name, platform, created_at
//
// 设计取舍：
//   - 时间列用 TEXT 存 RFC3339 字符串（time.Time↔string 在 repository 层转换），
//     避开 driver 对 INTEGER→time.Time 的不一致行为，保持跨平台可读。
//     taken_at 是内容拍摄时间（ms epoch），语义与进程时间不同，故存 INTEGER。
//   - "type" 是 SQL 倾向关键字，以双引号包裹，SQLite 允许作为列名。
//   - deleted 用 INTEGER(0/1) 表达布尔软删除；CRUD 层映射 bool。
//   - 外键约束显式声明并配 PRAGMA foreign_keys=ON 强制生效。
//   - CREATE TABLE IF NOT EXISTS 保证迁移幂等。
//   - client_id/taken_at 在旧库迁移路径（migrateColumns）补齐，schemaSQL 内联便于全新建库。
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
    client_id  TEXT NOT NULL DEFAULT '',
    taken_at   INTEGER NOT NULL DEFAULT 0,
    orientation INTEGER NOT NULL DEFAULT 0,
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

-- 分享链接（PRD-v7 §1.2）：把一组 media 以短链形式公开访问，可选过期与密码保护。
--   - token         : 12 字符随机短链，作为主键也是 URL 标识。
--   - user_id       : 创建者，用于撤销时鉴权（仅创建者可 DELETE）。
--   - media_ids     : JSON 数组字符串，如 ["id1","id2"]，存 TEXT 避免多对多表。
--   - expires_at    : RFC3339 过期时间；空串表示永不过期。
--   - password_hash : bcrypt 哈希；空串表示无密码保护。
--   - created_at    : 创建时间，供审计/列表排序。
-- 不设 user 外键：分享链接在用户删除后仍可独立访问（语义为\"已分享的快照\"），
-- 故与 user 的级联删除解耦；撤销仅由创建者显式 DELETE。
CREATE TABLE IF NOT EXISTS share_tokens (
    token         TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL,
    media_ids     TEXT NOT NULL,
    expires_at    TEXT,
    password_hash TEXT,
    created_at    TEXT NOT NULL
);

-- 性能索引（IF NOT EXISTS 保证幂等，旧库迁移时自动补建）。
--   idx_media_user_sha   : 上传秒传按 (user_id, sha256) 查询（repository GetMediaByUserAndSHA256）。
--   idx_media_user_sync  : 增量同步 ListMediaChanges 按 (user_id, deleted, updated_at) 过滤+排序游标。
--   idx_media_user_list  : 列表分页按 (user_id, updated_at) 排序（与 sync 游标复用，覆盖用户维度多数扫描）。
--   idx_device_user      : 设备列表按 user_id 聚合（repository ListDevices）。
CREATE INDEX IF NOT EXISTS idx_media_user_sha  ON "media"(user_id, sha256);
CREATE INDEX IF NOT EXISTS idx_media_user_sync ON "media"(user_id, deleted, updated_at);
CREATE INDEX IF NOT EXISTS idx_media_user_list ON "media"(user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_device_user     ON "device"(user_id);

-- 共享相册关联（PRD-v7 §2.3）：把相册邀请共享给其它用户。
--   - album_id            : 被共享的相册 ID（相册元数据存于各用户名下的 JSON 文件，
--                           非本库表，故此处不做外键；删除相册时由 gateway 层级联清理此表）。
--   - owner_user_id       : 相册所有者（发起共享的人），与相册 JSON 文件归属用户一致。
--                           冗余存储以便 ListAlbumsShared 不必回查相册文件即可判定归属。
--   - shared_with_user_id : 被共享的目标用户。
--   - shared_at           : 共享发起时间（RFC3339），供列表排序与审计。
-- 联合唯一约束 (album_id, shared_with_user_id) 防止同一用户被重复邀请；二次邀请
-- 由 CreateAlbumShare 用 ON CONFLICT DO NOTHING 幂等处理。
CREATE TABLE IF NOT EXISTS album_shares (
    id                  TEXT PRIMARY KEY,
    album_id            TEXT NOT NULL,
    owner_user_id       TEXT NOT NULL,
    shared_with_user_id TEXT NOT NULL,
    shared_at           TEXT NOT NULL,
    UNIQUE (album_id, shared_with_user_id)
);

--   idx_album_shares_target : 按被共享用户列出其可见相册（ListAlbumsShared）。
--   idx_album_shares_album  : 按相册列出所有被共享者（判定某用户是否对某相册有访问权；
--                             撤销共享、DeleteAlbum 级联清理均走此索引）。
CREATE INDEX IF NOT EXISTS idx_album_shares_target ON album_shares(shared_with_user_id);
CREATE INDEX IF NOT EXISTS idx_album_shares_album  ON album_shares(album_id);

-- V8：媒体标签系统（多对多）
CREATE TABLE IF NOT EXISTS media_tags (
    id         TEXT PRIMARY KEY,
    media_id   TEXT NOT NULL,
    user_id    TEXT NOT NULL,
    tag_name   TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (media_id, user_id, tag_name)
);
CREATE INDEX IF NOT EXISTS idx_media_tags_user   ON media_tags(user_id);
CREATE INDEX IF NOT EXISTS idx_media_tags_media  ON media_tags(media_id);
CREATE INDEX IF NOT EXISTS idx_media_tags_tag    ON media_tags(user_id, tag_name);

-- V8：审计日志系统。记录用户对媒体资源的操作行为，供审计查询与统计。
--   - id         : UUID 主键，由 Store 层生成。
--   - user_id    : 操作者，按用户隔离查询。
--   - action     : 操作类型（upload/delete/share/rename/favorite/tag 等）。
--   - media_id   : 关联媒体 ID，可空（如未来用户级操作），以 NULL 表示无关联。
--   - detail     : 操作细节（JSON 字符串或自由文本），便于审计回溯。
--   - created_at : 操作时间（RFC3339），按时间倒序展示最近行为。
CREATE TABLE IF NOT EXISTS audit_logs (
    id         TEXT PRIMARY KEY,
    user_id    TEXT NOT NULL,
    action     TEXT NOT NULL,
    media_id   TEXT,
    detail     TEXT,
    created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audit_user    ON audit_logs(user_id);
CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_logs(created_at);
`

// columnAdditions 列出在初始 schema 之外、为支持增量同步而追加的 media 列。
// SQLite 的 ALTER TABLE ADD COLUMN 不带 IF NOT EXISTS，故 migrateColumns 用
// "duplicate column" 错误信息判定重复添加并静默忽略，实现幂等迁移。
var columnAdditions = []struct {
	name string
	ddl  string
}{
	{"client_id", `ALTER TABLE "media" ADD COLUMN client_id TEXT NOT NULL DEFAULT ''`},
	{"taken_at", `ALTER TABLE "media" ADD COLUMN taken_at INTEGER NOT NULL DEFAULT 0`},
	{"orientation", `ALTER TABLE "media" ADD COLUMN orientation INTEGER NOT NULL DEFAULT 0`},
}

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
	// 并发连接池：DSN 已启用 WAL（journal_mode(WAL)）与 busy_timeout(5000)。
	// WAL 模式下读连接可并发，写仍由 SQLite 单写者串行；busy_timeout 让短时写锁
	// 冲突排队等待而非立即返回 "database is locked"。放开 MaxOpenConns 提升列表/
	// 同步等多读场景吞吐，元数据写入量受单写瓶颈约束但对该负载足够。
	db.SetMaxOpenConns(10)
	db.SetMaxIdleConns(5)

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

// Migrate 执行建表 DDL（幂等）并为旧库补齐后加列。后续若需 schema 演进，
// 在此扩展版本化迁移逻辑。
func (s *Store) Migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, schemaSQL); err != nil {
		return fmt.Errorf("migrate schema: %w", err)
	}
	if err := s.migrateColumns(ctx); err != nil {
		return fmt.Errorf("migrate columns: %w", err)
	}
	return nil
}

// migrateColumns 为已存在的旧 media 表补齐 client_id/taken_at 列。
// ALTER ADD COLUMN 无 IF NOT EXISTS：首次添加生效，重复添加会报
// "duplicate column name"，此处据错误信息识别并跳过，保持幂等。
func (s *Store) migrateColumns(ctx context.Context) error {
	for _, c := range columnAdditions {
		if _, err := s.db.ExecContext(ctx, c.ddl); err != nil {
			if strings.Contains(err.Error(), "duplicate column") {
				continue
			}
			return fmt.Errorf("%s: %w", c.name, err)
		}
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
// 注意：通过此句柄的并发写受 SQLite 单写者约束，依赖 WAL + busy_timeout 排队。
func (s *Store) DB() *sql.DB {
	return s.db
}
