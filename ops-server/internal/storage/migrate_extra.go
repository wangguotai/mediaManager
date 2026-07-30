package storage

import (
	"context"
	"strings"
)

// migrateExtra 对既有库做幂等的列级迁移。
//
// 背景：CREATE TABLE IF NOT EXISTS 不会给已存在的表补列。当 schema 演进新增列时
// （如 PRD §2.8 的 op_account.server_id），需要对旧库执行 ALTER TABLE ADD COLUMN。
// SQLite 的 ADD COLUMN 不支持 IF NOT EXISTS，故先查 pragma 拿到列清单再决定是否加。
//
// 幂等性：每次启动都查一次；列已存在则跳过，故反复执行无副作用。
func (s *Store) migrateExtra(ctx context.Context) error {
	return s.ensureOpAccountServerColumn(ctx)
}

// ensureOpAccountServerColumn 保证 op_account 表含 server_id TEXT NOT NULL DEFAULT ''。
// 旧库（PRD §2.8 之前）无此列，运营前端"绑 server"写操作依赖它。
func (s *Store) ensureOpAccountServerColumn(ctx context.Context) error {
	cols, err := s.tableColumns(ctx, "op_account")
	if err != nil {
		return err
	}
	for _, c := range cols {
		if strings.EqualFold(c, "server_id") {
			return nil // 已存在
		}
	}
	_, err = s.db.ExecContext(ctx,
		`ALTER TABLE op_account ADD COLUMN server_id TEXT NOT NULL DEFAULT ''`)
	return err
}

// tableColumns 返回某表的列名清单（经 PRAGMA table_info）。
func (s *Store) tableColumns(ctx context.Context, table string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx, `PRAGMA table_info(`+table+`)`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		// PRAGMA table_info 列：cid, name, type, notnull, dflt_value, pk
		var cid int
		var name, typ string
		var notnull int
		var dflt sqlNullString
		var pk int
		if err := rows.Scan(&cid, &name, &typ, &notnull, &dflt, &pk); err != nil {
			return nil, err
		}
		out = append(out, name)
	}
	return out, rows.Err()
}

// sqlNullString 兼容 NULL 的默认值列（PRAGMA table_info 对无默认值的列返回 NULL）。
// 用 sql.NullString 亦可；此处本地定义避免 import database/sql 在本小文件散开。
type sqlNullString struct {
	V   string
	OK  bool
}

func (n *sqlNullString) Scan(v any) error {
	if v == nil {
		n.OK = false
		return nil
	}
	switch t := v.(type) {
	case string:
		n.V = t
		n.OK = true
		return nil
	case []byte:
		n.V = string(t)
		n.OK = true
		return nil
	}
	n.OK = false
	return nil
}
