package storage

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
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

// UpdatePassword 把指定 user 的 password_hash 改为新哈希。供 /api/auth/change-password
// 调用。按 id 精确匹配；未命中返回 ErrNotFound。newHash 应为调用方已 bcrypt 过的哈希，
// 此处不再重复哈希（保留哈希策略于 auth 层统一管理）。
func (s *Store) UpdatePassword(ctx context.Context, id, newHash string) error {
	if id == "" {
		return fmt.Errorf("user id is required")
	}
	res, err := s.db.ExecContext(ctx, `UPDATE "user" SET password_hash = ? WHERE id = ?`, newHash, id)
	if err != nil {
		return fmt.Errorf("update password: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
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
INSERT INTO "media" (id, user_id, filename, "type", size, mime, width, height, created_at, updated_at, sha256, deleted, client_id, taken_at, orientation)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		m.ID, m.UserID, m.Filename, m.Type, m.Size, m.Mime, m.Width, m.Height,
		timeToVal(m.CreatedAt), timeToVal(m.UpdatedAt), m.SHA256, boolToInt(m.Deleted),
		m.ClientID, m.TakenAt, m.Orientation)
	if err != nil {
		return fmt.Errorf("insert media: %w", err)
	}
	return nil
}

// mediaColumns 是 media 表的完整列清单（含同步扩展列 client_id/taken_at），
// 供各 SELECT 复用，避免增删列时多处漂移。
const mediaColumns = `id, user_id, filename, "type", size, mime, width, height, created_at, updated_at, sha256, deleted, client_id, taken_at, orientation`

// GetMedia 按 id 取单行 media（含已软删除行，便于审计/恢复）。未命中返回 ErrNotFound。
func (s *Store) GetMedia(ctx context.Context, id string) (*Media, error) {
	row := s.db.QueryRowContext(ctx, `SELECT `+mediaColumns+` FROM "media" WHERE id = ?`, id)
	return scanMedia(row.Scan)
}

// ListMediaByUser 返回某用户的所有未软删除媒体，按 created_at 降序（最新在前），
// 与现有 MediaService 列表排序一致。
func (s *Store) ListMediaByUser(ctx context.Context, userID string) ([]*Media, error) {
	rows, err := s.db.QueryContext(ctx, `
SELECT `+mediaColumns+` FROM "media" WHERE user_id = ? AND deleted = 0 ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, fmt.Errorf("list media by user: %w", err)
	}
	return scanMediaRows(rows)
}

// AdvancedSearchOpts 是 AdvancedSearchMedia 的多条件搜索参数。各字段为零值时
// 表示不施加该条件（与 ListMediaByUser 的"全列出"行为对齐）。Limit<=0 默认 100。
//
// 字段对应的 query 参数（server.go handler 解析）：
//
//	Type     ← type       (IMAGE / VIDEO / LIVE_PHOTO)
//	MIMEType ← mime       (如 image/jpeg)
//	MinSize  ← min_size   (字节)
//	MaxSize  ← max_size   (字节)
//	DateFrom ← date_from  (RFC3339，按 created_at >= ? 过滤)
//	DateTo   ← date_to    (RFC3339，按 created_at <= ? 过滤)
//	Tag      ← tag        (精确匹配 media_tags.tag_name，用 EXISTS 子查询)
type AdvancedSearchOpts struct {
	Type     string
	MIMEType string
	MinSize  int64
	MaxSize  int64
	DateFrom string // RFC3339
	DateTo   string // RFC3339
	Tag      string
	Limit    int
}

// AdvancedSearchMedia 按多条件组合搜索当前用户未软删的媒体，按 created_at 降序，
// 与 ListMediaByUser 排序一致。所有条件均可选（零值跳过），至少按 user_id + deleted=0
// 过滤。
//
// SQL 构建用 strings.Builder 动态拼 WHERE 子句，参数逐个以 ? 绑定（args []any），
// 不拼接用户输入进 SQL 文本——type/mime/date/tag 均作为参数传入，防注入。
//
// tag 条件用 EXISTS 子查询而非 JOIN：保持 media 行不因多标签而重复（DISTINCT 可省），
// 且未打标签的 media 仍可被其他条件命中（JOIN 会过滤掉无标签行）。子查询引用外表
// 用 "media".id 限定，user_id 双键绑定为当前用户，防跨用户标签串扰。
func (s *Store) AdvancedSearchMedia(ctx context.Context, userID string, opts AdvancedSearchOpts) ([]*Media, error) {
	if userID == "" {
		return nil, fmt.Errorf("user id is required")
	}
	if opts.Limit <= 0 {
		opts.Limit = 100
	}
	var sb strings.Builder
	args := make([]any, 0, 8)
	args = append(args, userID) // user_id = ?
	sb.WriteString(`SELECT ` + mediaColumns + ` FROM "media" WHERE user_id = ? AND deleted = 0`)
	if opts.Type != "" {
		sb.WriteString(` AND "type" = ?`)
		args = append(args, opts.Type)
	}
	if opts.MIMEType != "" {
		sb.WriteString(` AND mime = ?`)
		args = append(args, opts.MIMEType)
	}
	if opts.MinSize > 0 {
		sb.WriteString(` AND size >= ?`)
		args = append(args, opts.MinSize)
	}
	if opts.MaxSize > 0 {
		sb.WriteString(` AND size <= ?`)
		args = append(args, opts.MaxSize)
	}
	if opts.DateFrom != "" {
		sb.WriteString(` AND created_at >= ?`)
		args = append(args, opts.DateFrom)
	}
	if opts.DateTo != "" {
		sb.WriteString(` AND created_at <= ?`)
		args = append(args, opts.DateTo)
	}
	if opts.Tag != "" {
		sb.WriteString(` AND EXISTS (SELECT 1 FROM media_tags WHERE media_id = "media".id AND user_id = ? AND tag_name = ?)`)
		args = append(args, userID, opts.Tag)
	}
	sb.WriteString(` ORDER BY created_at DESC LIMIT ?`)
	args = append(args, opts.Limit)

	rows, err := s.db.QueryContext(ctx, sb.String(), args...)
	if err != nil {
		return nil, fmt.Errorf("advanced search media: %w", err)
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

// SetMediaRotation 设置单行 media 的旋转角度（orientation 列），且仅当其 user_id
// 等于 userID 时才生效。供 POST /api/media/rotate 使用。
//
// rotation 应为 0/90/180/270 之一（EXIF orientation 语义）；本方法不做值域校验，
// 由调用方（handler）负责，保持 storage 层只管持久化。
//
// 防横向越权：WHERE 含 user_id 双键，与 MarkDeletedForUser/UndeleteMediaForUser 同策略，
// 非己有或不存在均返回 ErrNotFound（不区分，避免泄露 media_id 是否存在）。
// 同时刷新 updated_at，使旋转变更进入增量同步流。userID 为空直接 ErrNotFound。
func (s *Store) SetMediaRotation(ctx context.Context, userID, mediaID string, rotation int) error {
	if userID == "" || mediaID == "" {
		return ErrNotFound
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE "media" SET orientation = ?, updated_at = ? WHERE id = ? AND user_id = ?`,
		rotation, timeToVal(time.Now()), mediaID, userID)
	if err != nil {
		return fmt.Errorf("set media rotation: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// BatchSetMediaRotation 批量旋转媒体（单条 UPDATE，区别于逐条 SetMediaRotation）。
// 把指定 mediaIDs 中属于 userID 的记录 orientation 置为 rotation，刷新 updated_at 使
// 变更进入增量同步流。返回实际更新的行数（未命中 / 不属于当前用户均不计入，与
// BatchRestoreMedia 同样的越权防护语义——不泄露 media_id 是否存在）。mediaIDs 为空
// 或 userID 为空直接返回 (0, nil)。
//
// rotation 应为 0/90/180/270 之一（EXIF orientation 语义）；本方法不做值域校验，由调用方
// （handler）负责，保持 storage 层只管持久化（与 SetMediaRotation 一致）。
//
// 防横向越权：WHERE 含 user_id 双键，非己有或不存在均不计入计数（不区分，避免泄露）。
// SQL 注入防护：IN 列表用 strings.Repeat("?,", n) 生成等量占位符，mediaIDs 作为参数
// 逐个绑定（不拼接进 SQL 文本），参考 BatchRestoreMedia 的参数化模式。
func (s *Store) BatchSetMediaRotation(ctx context.Context, userID string, mediaIDs []string, rotation int) (int, error) {
	if userID == "" || len(mediaIDs) == 0 {
		return 0, nil
	}
	// 构造 IN (?, ?, ...) 占位符：n 个 "?" 用 "," 连接，外层包 "IN (" ")"。
	placeholders := strings.Repeat("?,", len(mediaIDs))
	placeholders = placeholders[:len(placeholders)-1] // 去掉末尾多余的 ","
	args := make([]any, 0, len(mediaIDs)+3)
	args = append(args, rotation)               // orientation = ?
	args = append(args, timeToVal(time.Now()))  // updated_at = ?
	for _, id := range mediaIDs {               // id IN (...) — 逐个追加，避免 []string→[]any 的 ... 展开类型不符
		args = append(args, id)
	}
	args = append(args, userID) // AND user_id = ?
	res, err := s.db.ExecContext(ctx,
		`UPDATE "media" SET orientation = ?, updated_at = ? WHERE id IN (`+placeholders+`) AND user_id = ?`,
		args...)
	if err != nil {
		return 0, fmt.Errorf("batch set media rotation: %w", err)
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// MarkDeleted 软删除一行 media（deleted=1），刷新 updated_at。未命中返回 ErrNotFound。
// 软删除保留数据，与现有 DeleteMedia（物理删文件）解耦：SQL 软删 + 磁盘文件清理
// 可由后续任务分别处理。
//
// 注意：本方法不校验归属——任何调用方只要持 id 即可软删。V5 安全基线要求防
// 横向越权，删除端点路径应改用 MarkDeletedForUser（按 user_id 校验）。本方法
// 保留供后端内部/测试/清理路径使用。
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

// MarkDeletedForUser 软删除一行 media，且仅当其 user_id 等于 userID 时才生效。
// 用途：删除端点防横向越权——同一 media_id 即使被攻击者猜到，因 user_id 不匹配
// 会被 WHERE 过滤，RowsAffected=0，返回 ErrNotFound，使调用方能据此判定为"非己有
// 或不存在"并拒绝。未命中、ID 不存在、归属不符均返回 ErrNotFound（不区分，避免泄露）。
// userID 为空时直接 ErrNotFound，防止误以空串匹配。
func (s *Store) MarkDeletedForUser(ctx context.Context, userID, id string) error {
	if userID == "" || id == "" {
		return ErrNotFound
	}
	res, err := s.db.ExecContext(ctx, `UPDATE "media" SET deleted = 1, updated_at = ? WHERE id = ? AND user_id = ?`, timeToVal(time.Now()), id, userID)
	if err != nil {
		return fmt.Errorf("mark media deleted for user: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// UndeleteMedia 复活一行被软删的 media（deleted=0），刷新 updated_at 使其重新
// 出现在列表与增量同步流中。未命中返回 ErrNotFound。供秒传命中软删记录时
// 恢复内容可见性使用。
func (s *Store) UndeleteMedia(ctx context.Context, id string) error {
	res, err := s.db.ExecContext(ctx, `UPDATE "media" SET deleted = 0, updated_at = ? WHERE id = ?`, timeToVal(time.Now()), id)
	if err != nil {
		return fmt.Errorf("undelete media: %w", err)
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

// ===== 回收站（PRD-v7 §1.1） =====

// ListTrashByUser 返回某用户已软删除（deleted=1）的媒体列表，按 updated_at 降序，
// 分页。供回收站列表端点 GET /api/media/trash 使用。limit<=0 时默认 100（与
// ListMediaChanges 一致）；offset<0 视为 0。空 userID 直接报错（回收站必须按用户隔离）。
func (s *Store) ListTrashByUser(ctx context.Context, userID string, limit, offset int) ([]*Media, error) {
	if userID == "" {
		return nil, fmt.Errorf("user id is required")
	}
	if limit <= 0 {
		limit = 100
	}
	if offset < 0 {
		offset = 0
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+mediaColumns+` FROM "media" WHERE user_id = ? AND deleted = 1 ORDER BY updated_at DESC LIMIT ? OFFSET ?`,
		userID, limit, offset)
	if err != nil {
		return nil, fmt.Errorf("list trash by user: %w", err)
	}
	return scanMediaRows(rows)
}

// CountTrashByUser 返回某用户回收站（deleted=1）的总条数，供 /api/media/trash
// 填充 total 与前端翻页判定。与 ListTrashByUser 同条件（user_id + deleted=1）。
func (s *Store) CountTrashByUser(ctx context.Context, userID string) (int, error) {
	if userID == "" {
		return 0, fmt.Errorf("user id is required")
	}
	var n int
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM "media" WHERE user_id = ? AND deleted = 1`, userID).Scan(&n); err != nil {
		return 0, fmt.Errorf("count trash by user: %w", err)
	}
	return n, nil
}

// UndeleteMediaForUser 复活一行被软删的 media（deleted=0），且仅当其 user_id 等于
// userID 且 deleted=1 时才生效。供 POST /api/media/restore 使用。
//
// 防横向越权：恢复端点按 (id, user_id) 校验归属，非己有或不存在均返回 ErrNotFound
// （不区分两者，避免泄露 media_id 是否存在）。刷新 updated_at 使恢复后的记录重新
// 出现在常规列表与增量同步流中。userID 为空直接 ErrNotFound，避免空串误匹配。
func (s *Store) UndeleteMediaForUser(ctx context.Context, userID, id string) error {
	if userID == "" || id == "" {
		return ErrNotFound
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE "media" SET deleted = 0, updated_at = ? WHERE id = ? AND user_id = ? AND deleted = 1`,
		timeToVal(time.Now()), id, userID)
	if err != nil {
		return fmt.Errorf("undelete media for user: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// PurgeMediaForUser 物理删除一行 media（DELETE row），且仅当其 user_id 等于 userID
// 且 deleted=1 时才生效。供 POST /api/media/purge 使用——仅能从回收站彻底清空。
//
// 实现：先 GetMedia 确认归属与软删状态，再 DELETE（WHERE 含 user_id 双键，即便在
// GetMedia 与 DELETE 之间被并发修改也不会误删他人记录）。未命中、归属不符、未软删
// 均返回 ErrNotFound（不区分，避免泄露）。
//
// 注意：本方法仅删除数据库行；磁盘文件清理由 gateway 层负责（需 userDirs 定位该
// 用户的 uploads 目录，filepath.Glob 查找 id.* 后 os.Remove）。storage 层是纯 SQL
// 层，不持有任何文件路径信息，与现有 Store 设计一致（CreateMedia/DeleteMedia 均不
// 触碰文件）。
func (s *Store) PurgeMediaForUser(ctx context.Context, userID, id string) error {
	if userID == "" || id == "" {
		return ErrNotFound
	}
	// 先确认归属 + 已软删：仅能从回收站彻底删除，不能误删活跃媒体或他人记录。
	m, err := s.GetMedia(ctx, id)
	if err != nil {
		return err // ErrNotFound（不存在）或包装后的扫描错误
	}
	if m.UserID != userID || !m.Deleted {
		return ErrNotFound
	}
	// 物理删除该行；WHERE 含 user_id 双键。未命中不报错（幂等），前置校验已保证存在。
	if _, err := s.db.ExecContext(ctx, `DELETE FROM "media" WHERE id = ? AND user_id = ?`, id, userID); err != nil {
		return fmt.Errorf("purge media for user: %w", err)
	}
	return nil
}

// PurgeExpiredTrash V7：自动清理回收站中超过 maxAge 的记录。
// 返回清理的条数。物理删除 DB 记录（文件由调用方决定是否删）。
func (s *Store) PurgeExpiredTrash(ctx context.Context, maxAge time.Duration) (int, error) {
	cutoff := time.Now().Add(-maxAge)
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM "media" WHERE deleted = 1 AND updated_at < ?`, cutoff.Format(time.RFC3339))
	if err != nil {
		return 0, fmt.Errorf("purge expired trash: %w", err)
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// PurgeAllTrashForUser V8：物理删除当前用户的所有已软删媒体。
func (s *Store) PurgeAllTrashForUser(ctx context.Context, userID string) (int, error) {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM "media" WHERE deleted = 1 AND user_id = ?`, userID)
	if err != nil {
		return 0, fmt.Errorf("purge all trash for user: %w", err)
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// BatchRestoreMedia V8：批量恢复回收站媒体（单条 UPDATE，区别于逐条 UndeleteMediaForUser）。
// 把指定 mediaIDs 中属于 userID 且 deleted=1 的记录置为 deleted=0，刷新 updated_at。
// 返回实际复活的行数（未命中 / 已恢复 / 不属于当前用户均不计入，符合回收站"不泄露
// media_id 是否存在"的越权防护语义）。mediaIDs 为空或 userID 为空直接返回 (0, nil)。
//
// SQL 注入防护：IN 列表用 strings.Repeat("?,", n) 生成等量占位符，mediaIDs 作为参数
// 逐个绑定（不拼接进 SQL 文本），参考 BatchRemoveFromAlbum/批量查询的参数化模式。
func (s *Store) BatchRestoreMedia(ctx context.Context, userID string, mediaIDs []string) (int, error) {
	if userID == "" || len(mediaIDs) == 0 {
		return 0, nil
	}
	// 构造 IN (?, ?, ...) 占位符：n 个 "?" 用 "," 连接，外层包 "IN (" ")"。
	placeholders := strings.Repeat("?,", len(mediaIDs))
	placeholders = placeholders[:len(placeholders)-1] // 去掉末尾多余的 ","
	args := make([]any, 0, len(mediaIDs)+2)
	args = append(args, timeToVal(time.Now())) // updated_at = ?
	for _, id := range mediaIDs {              // id IN (...) — 逐个追加，避免 []string→[]any 的 ... 展开类型不符
		args = append(args, id)
	}
	args = append(args, userID) // AND user_id = ?
	res, err := s.db.ExecContext(ctx,
		`UPDATE "media" SET deleted = 0, updated_at = ? WHERE id IN (`+placeholders+`) AND user_id = ? AND deleted = 1`,
		args...)
	if err != nil {
		return 0, fmt.Errorf("batch restore media: %w", err)
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// ===== MediaTag ===== V8：媒体标签系统

// MediaTag 表示一条媒体标签关联。
type MediaTag struct {
	ID        string    `json:"id"`
	MediaID   string    `json:"media_id"`
	UserID    string    `json:"user_id"`
	TagName   string    `json:"tag_name"`
	CreatedAt time.Time `json:"created_at"`
}

// AddMediaTag 给媒体打标签（幂等，UNIQUE 约束保证不重复）。
func (s *Store) AddMediaTag(ctx context.Context, userID, mediaID, tagName string) error {
	tagID := "tag-" + uuid.NewString()
	_, err := s.db.ExecContext(ctx,
		`INSERT OR IGNORE INTO media_tags (id, media_id, user_id, tag_name, created_at) VALUES (?, ?, ?, ?, ?)`,
		tagID, mediaID, userID, tagName, time.Now().Format(time.RFC3339))
	if err != nil {
		return fmt.Errorf("add media tag: %w", err)
	}
	return nil
}

// RemoveMediaTag 移除媒体的某个标签。
func (s *Store) RemoveMediaTag(ctx context.Context, userID, mediaID, tagName string) error {
	_, err := s.db.ExecContext(ctx,
		`DELETE FROM media_tags WHERE user_id = ? AND media_id = ? AND tag_name = ?`,
		userID, mediaID, tagName)
	if err != nil {
		return fmt.Errorf("remove media tag: %w", err)
	}
	return nil
}

// ListMediaTags 列出某个媒体的所有标签。
func (s *Store) ListMediaTags(ctx context.Context, userID, mediaID string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT tag_name FROM media_tags WHERE user_id = ? AND media_id = ? ORDER BY tag_name`,
		userID, mediaID)
	if err != nil {
		return nil, fmt.Errorf("list media tags: %w", err)
	}
	defer rows.Close()
	var tags []string
	for rows.Next() {
		var t string
		if err := rows.Scan(&t); err != nil {
			return nil, fmt.Errorf("scan media tag: %w", err)
		}
		tags = append(tags, t)
	}
	return tags, nil
}

// ListAllTags 列出当前用户用过的所有标签（去重）。
func (s *Store) ListAllTags(ctx context.Context, userID string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT DISTINCT tag_name FROM media_tags WHERE user_id = ? ORDER BY tag_name`,
		userID)
	if err != nil {
		return nil, fmt.Errorf("list all tags: %w", err)
	}
	defer rows.Close()
	var tags []string
	for rows.Next() {
		var t string
		if err := rows.Scan(&t); err != nil {
			return nil, fmt.Errorf("scan tag: %w", err)
		}
		tags = append(tags, t)
	}
	return tags, nil
}

// SearchMediaByTag V8：返回带有指定标签的 media_id 列表。
func (s *Store) SearchMediaByTag(ctx context.Context, userID, tagName string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT media_id FROM media_tags WHERE user_id = ? AND tag_name = ? ORDER BY media_id`,
		userID, tagName)
	if err != nil {
		return nil, fmt.Errorf("search media by tag: %w", err)
	}
	defer rows.Close()
	var ids []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, fmt.Errorf("scan media_id: %w", err)
		}
		ids = append(ids, id)
	}
	return ids, nil
}

// TagStats V8：返回每个标签的媒体数量，按数量倒序。
func (s *Store) TagStats(ctx context.Context, userID string) ([]map[string]any, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT tag_name, COUNT(*) as cnt FROM media_tags WHERE user_id = ? GROUP BY tag_name ORDER BY cnt DESC`,
		userID)
	if err != nil {
		return nil, fmt.Errorf("tag stats: %w", err)
	}
	defer rows.Close()
	var out []map[string]any
	for rows.Next() {
		var name string
		var cnt int
		if err := rows.Scan(&name, &cnt); err != nil {
			return nil, fmt.Errorf("scan tag stats: %w", err)
		}
		out = append(out, map[string]any{"tag": name, "count": cnt})
	}
	return out, nil
}

// ===== AuditLog ===== V8：审计日志系统

// AuditLog 表示一条审计日志记录。
type AuditLog struct {
	ID        string    `json:"id"`
	UserID    string    `json:"user_id"`
	Action    string    `json:"action"`
	MediaID   string    `json:"media_id,omitempty"`
	Detail    string    `json:"detail,omitempty"`
	CreatedAt time.Time `json:"created_at"`
}

// AddAuditLog 写入一条审计日志。ID 由 uuid.NewString() 生成，
// CreatedAt 置为当前时间。action 不应为空（空串虽不报错但语义无效）。
// mediaID 为空串时落 NULL（表示非媒体级操作），detail 同理。
func (s *Store) AddAuditLog(ctx context.Context, userID, action, mediaID, detail string) error {
	if userID == "" {
		return fmt.Errorf("user id is required")
	}
	id := uuid.NewString()
	created := time.Now().Format(time.RFC3339)
	// mediaID 空串 → nil（落 NULL），非空直接绑定字符串。
	var mediaArg any
	if mediaID == "" {
		mediaArg = nil
	} else {
		mediaArg = mediaID
	}
	if _, err := s.db.ExecContext(ctx,
		`INSERT INTO audit_logs (id, user_id, action, media_id, detail, created_at) VALUES (?, ?, ?, ?, ?, ?)`,
		id, userID, action, mediaArg, detail, created); err != nil {
		return fmt.Errorf("add audit log: %w", err)
	}
	return nil
}

// ListAuditLogs 返回某用户的审计日志，按 created_at 降序（最近在前）。
// limit<=0 时默认 50（与默认审计页一致）。空 userID 直接返回空切片（不查库）。
func (s *Store) ListAuditLogs(ctx context.Context, userID string, limit int) ([]*AuditLog, error) {
	if userID == "" {
		return nil, nil
	}
	if limit <= 0 {
		limit = 50
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, user_id, action, media_id, detail, created_at FROM audit_logs WHERE user_id = ? ORDER BY created_at DESC LIMIT ?`,
		userID, limit)
	if err != nil {
		return nil, fmt.Errorf("list audit logs: %w", err)
	}
	defer rows.Close()
	var out []*AuditLog
	for rows.Next() {
		var a AuditLog
		var createdAt string
		var mediaID sql.NullString
		if err := rows.Scan(&a.ID, &a.UserID, &a.Action, &mediaID, &a.Detail, &createdAt); err != nil {
			return nil, fmt.Errorf("scan audit log: %w", err)
		}
		if mediaID.Valid {
			a.MediaID = mediaID.String
		}
		a.CreatedAt = timeFromVal(createdAt)
		out = append(out, &a)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows audit logs: %w", err)
	}
	return out, nil
}

// ListAuditLogsByMedia 返回某用户指定媒体的操作历史，按 created_at 降序（最近在前）。
// userID 或 mediaID 为空时直接返回空切片（不查库），避免无意义全表/空值扫描。
// 与 ListAuditLogs 行扫描逻辑一致：media_id 为 NULL 列用 sql.NullString 接住。
func (s *Store) ListAuditLogsByMedia(ctx context.Context, userID, mediaID string) ([]*AuditLog, error) {
	if userID == "" || mediaID == "" {
		return nil, nil
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, user_id, action, media_id, detail, created_at FROM audit_logs WHERE user_id = ? AND media_id = ? ORDER BY created_at DESC`,
		userID, mediaID)
	if err != nil {
		return nil, fmt.Errorf("list audit logs by media: %w", err)
	}
	defer rows.Close()
	var out []*AuditLog
	for rows.Next() {
		var a AuditLog
		var createdAt string
		var mid sql.NullString
		if err := rows.Scan(&a.ID, &a.UserID, &a.Action, &mid, &a.Detail, &createdAt); err != nil {
			return nil, fmt.Errorf("scan audit log by media: %w", err)
		}
		if mid.Valid {
			a.MediaID = mid.String
		}
		a.CreatedAt = timeFromVal(createdAt)
		out = append(out, &a)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows audit logs by media: %w", err)
	}
	return out, nil
}

// AuditLogStats 按操作类型聚合某用户的审计日志数量，返回 [{action, count}]，
// 按 count 降序（与 TagStats 模式一致）。空 userID 直接返回空切片。
func (s *Store) AuditLogStats(ctx context.Context, userID string) ([]map[string]any, error) {
	if userID == "" {
		return nil, nil
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT action, COUNT(*) AS cnt FROM audit_logs WHERE user_id = ? GROUP BY action ORDER BY cnt DESC`,
		userID)
	if err != nil {
		return nil, fmt.Errorf("audit log stats: %w", err)
	}
	defer rows.Close()
	var out []map[string]any
	for rows.Next() {
		var action string
		var cnt int
		if err := rows.Scan(&action, &cnt); err != nil {
			return nil, fmt.Errorf("scan audit log stats: %w", err)
		}
		out = append(out, map[string]any{"action": action, "count": cnt})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows audit log stats: %w", err)
	}
	return out, nil
}

// RenameTag V8：重命名标签（用户维度，所有包含 old_name 的标签改为 new_name）。
// 用 INSERT OR IGNORE + DELETE 处理可能的 UNIQUE 冲突。
func (s *Store) RenameTag(ctx context.Context, userID, oldName, newName string) (int, error) {
	// 先尝试把 old_name 改为 new_name（UPDATE 可能因 UNIQUE 约束失败）
	// 用两步：1) INSERT OR IGNORE new_name 行 2) DELETE old_name 行
	res, err := s.db.ExecContext(ctx,
		`INSERT OR IGNORE INTO media_tags (id, media_id, user_id, tag_name, created_at)
		 SELECT 'tag-' || replace(hex(randomblob(8)), '-', '') AS id, media_id, user_id, ?, created_at
		 FROM media_tags WHERE user_id = ? AND tag_name = ?`,
		newName, userID, oldName)
	if err != nil {
		return 0, fmt.Errorf("rename tag insert: %w", err)
	}
	added, _ := res.RowsAffected()

	del, err := s.db.ExecContext(ctx,
		`DELETE FROM media_tags WHERE user_id = ? AND tag_name = ?`,
		userID, oldName)
	if err != nil {
		return int(added), fmt.Errorf("rename tag delete: %w", err)
	}
	deleted, _ := del.RowsAffected()
	return int(deleted), nil
}

// DeleteTag V8：删除用户的所有带指定标签的记录。
func (s *Store) DeleteTag(ctx context.Context, userID, tagName string) (int, error) {
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM media_tags WHERE user_id = ? AND tag_name = ?`,
		userID, tagName)
	if err != nil {
		return 0, fmt.Errorf("delete tag: %w", err)
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// BatchTagByType V8：按媒体类型批量打标签。
// IMAGE → 照片, VIDEO → 视频, LIVE_PHOTO → 动态照片
func (s *Store) BatchTagByType(ctx context.Context, userID string) (int, error) {
	mediaList, err := s.ListMediaByUser(ctx, userID)
	if err != nil {
		return 0, err
	}
	typeTagMap := map[string]string{"IMAGE": "照片", "VIDEO": "视频", "LIVE_PHOTO": "动态照片"}
	count := 0
	for _, m := range mediaList {
		if m.Deleted {
			continue
		}
		tag, ok := typeTagMap[m.Type]
		if !ok {
			continue
		}
		if err := s.AddMediaTag(ctx, userID, m.ID, tag); err == nil {
			count++
		}
	}
	return count, nil
}

// ===== ShareToken =====（PRD-v7 §1.2 分享链接）

// CreateShareToken 插入一行 share_tokens。Token/UserID/MediaIDs 必填；
// ExpiresAt 零值落空串表示永不过期；PasswordHash 空串表示无密码保护。
// CreatedAt 零值时置当前时间。
func (s *Store) CreateShareToken(ctx context.Context, st *ShareToken) error {
	if st == nil {
		return fmt.Errorf("share token is nil")
	}
	if st.Token == "" || st.UserID == "" || st.MediaIDs == "" {
		return fmt.Errorf("share token, user_id and media_ids are required")
	}
	created := st.CreatedAt
	if created.IsZero() {
		created = time.Now()
	}
	// expires_at：零值 → 空串（永不过期）；非零 → RFC3339。
	expiresVal := ""
	if !st.ExpiresAt.IsZero() {
		expiresVal = st.ExpiresAt.UTC().Format(time.RFC3339Nano)
	}
	if _, err := s.db.ExecContext(ctx, `
INSERT INTO share_tokens (token, user_id, media_ids, expires_at, password_hash, created_at)
VALUES (?, ?, ?, ?, ?, ?)`,
		st.Token, st.UserID, st.MediaIDs, expiresVal, st.PasswordHash, timeToVal(created)); err != nil {
		return fmt.Errorf("insert share token: %w", err)
	}
	return nil
}

// GetShareToken 按 token 取单行 share_tokens。未命中返回 ErrNotFound。
// 公开访问端点（无需认证）据此获取分享元数据。
func (s *Store) GetShareToken(ctx context.Context, token string) (*ShareToken, error) {
	row := s.db.QueryRowContext(ctx, `
SELECT token, user_id, media_ids, expires_at, password_hash, created_at
FROM share_tokens WHERE token = ?`, token)
	var st ShareToken
	var expiresAt, createdAt string
	// password_hash 可能为空串（无密码保护）；expires_at 可能为空串（永不过期）。
	if err := row.Scan(&st.Token, &st.UserID, &st.MediaIDs, &expiresAt, &st.PasswordHash, &createdAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrNotFound
		}
		return nil, fmt.Errorf("get share token: %w", err)
	}
	st.ExpiresAt = timeFromVal(expiresAt) // 空串 → 零值（永不过期）
	st.HasPassword = st.PasswordHash != ""
	st.CreatedAt = timeFromVal(createdAt)
	return &st, nil
}

// DeleteShareToken 撤销分享：仅当 token 与 user_id 同时匹配才删除（防越权撤销他人分享）。
// 未命中或不属于该用户均返回 ErrNotFound（不区分，避免泄露存在性）。
// userID 为空时直接 ErrNotFound，防止误以空串匹配。
func (s *Store) DeleteShareToken(ctx context.Context, token, userID string) error {
	if token == "" || userID == "" {
		return ErrNotFound
	}
	res, err := s.db.ExecContext(ctx, `DELETE FROM share_tokens WHERE token = ? AND user_id = ?`, token, userID)
	if err != nil {
		return fmt.Errorf("delete share token: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// ExtendShareToken 延长分享链接的有效期（防越权：仅 owner 可操作）。
//
// 新过期时间计算规则（与任务约定一致）：
//   - token 且 expires_at=空串（永不过期）：直接返回 nil，无需延长。
//   - 已过期（expires_at <= now）：新过期 = now + extendDuration。
//   - 未过期（expires_at > now）：新过期 = 原 expires_at + extendDuration。
//
// 按 (token, user_id) 双键过滤，防越权延长他人分享；未命中或不属于该用户
// 均返回 ErrNotFound（不区分，避免泄露存在性）。userID 为空直接 ErrNotFound。
// 新过期时间以 RFC3339Nano 落库，与 CreateShareToken/GetShareToken 约定一致。
func (s *Store) ExtendShareToken(ctx context.Context, token, userID string, extendDuration time.Duration) error {
	if token == "" || userID == "" {
		return ErrNotFound
	}
	st, err := s.GetShareToken(ctx, token)
	if err != nil {
		return err // ErrNotFound 或扫描错误
	}
	if st.UserID != userID {
		return ErrNotFound // 防越权：非 owner 拒绝，不区分以避免泄露
	}
	// 永不过期（expires_at 为空串/零值）：无需延长，直接返回。
	if st.ExpiresAt.IsZero() {
		return nil
	}
	now := time.Now()
	var newExpires time.Time
	if !now.After(st.ExpiresAt) {
		// 未过期：从原 expires_at 累加。
		newExpires = st.ExpiresAt.Add(extendDuration)
	} else {
		// 已过期：从 now 重新计算。
		newExpires = now.Add(extendDuration)
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE share_tokens SET expires_at = ? WHERE token = ? AND user_id = ?`,
		newExpires.UTC().Format(time.RFC3339Nano), token, userID)
	if err != nil {
		return fmt.Errorf("extend share token: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
	}
	return nil
}

// DeleteExpiredShareTokens V7：物理删除所有已过期的分享链接。
// expires_at 非空且 < now 的行被删除。返回清理条数。
func (s *Store) DeleteExpiredShareTokens(ctx context.Context) (int, error) {
	now := time.Now().Format(time.RFC3339)
	res, err := s.db.ExecContext(ctx,
		`DELETE FROM share_tokens WHERE expires_at != '' AND expires_at < ?`, now)
	if err != nil {
		return 0, fmt.Errorf("delete expired share tokens: %w", err)
	}
	n, _ := res.RowsAffected()
	return int(n), nil
}

// ListShareTokensByUser V7：返回当前用户创建的所有分享链接，按创建时间倒序。
func (s *Store) ListShareTokensByUser(ctx context.Context, userID string) ([]*ShareToken, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT token, user_id, media_ids, expires_at, password_hash, created_at
		 FROM share_tokens WHERE user_id = ? ORDER BY created_at DESC`, userID)
	if err != nil {
		return nil, fmt.Errorf("list share tokens: %w", err)
	}
	defer rows.Close()
	var result []*ShareToken
	for rows.Next() {
		var st ShareToken
		var expiresAt, createdAt string
		if err := rows.Scan(&st.Token, &st.UserID, &st.MediaIDs, &expiresAt, &st.PasswordHash, &createdAt); err != nil {
			return nil, fmt.Errorf("scan share token: %w", err)
		}
		st.ExpiresAt = timeFromVal(expiresAt)
		st.HasPassword = st.PasswordHash != ""
		result = append(result, &st)
	}
	return result, nil
}

// CreateAlbumShare 把一个相册共享给 sharedWithUserID。ownerUserID 为相册所有者
// （发起共享的人）。若该 (album_id, shared_with_user_id) 已存在则幂等返回，
// 不报错也不更新 shared_at（语义：重复邀请是 no-op）。
//
// ownerUserID 与 sharedWithUserID 不可相同（禁止把相册共享给自己），否则视作
// 参数错误——调用方应在上游校验，此处兜底返回 ErrSelfShare。
//
// SharedAt 零值时置当前时间。返回完整记录（含生成的 ID 与落库时间）。
func (s *Store) CreateAlbumShare(ctx context.Context, as *AlbumShare) error {
	if as == nil {
		return fmt.Errorf("album share is nil")
	}
	if as.AlbumID == "" || as.OwnerUserID == "" || as.SharedWithUserID == "" {
		return fmt.Errorf("album_id, owner_user_id and shared_with_user_id are required")
	}
	if as.SharedWithUserID == as.OwnerUserID {
		return ErrSelfShare
	}
	if as.ID == "" {
		as.ID = uuid.NewString()
	}
	sharedAt := as.SharedAt
	if sharedAt.IsZero() {
		sharedAt = time.Now()
	}
	// ON CONFLICT DO NOTHING：联合唯一约束 (album_id, shared_with_user_id) 命中时
	// 跳过插入，幂等。注意：modernc.org/sqlite 支持 UPSERT 语法。
	res, err := s.db.ExecContext(ctx, `
INSERT INTO album_shares (id, album_id, owner_user_id, shared_with_user_id, shared_at)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (album_id, shared_with_user_id) DO NOTHING`,
		as.ID, as.AlbumID, as.OwnerUserID, as.SharedWithUserID, timeToVal(sharedAt))
	if err != nil {
		return fmt.Errorf("insert album share: %w", err)
	}
	// 回填记录的真实时间，供调用方返回。
	as.SharedAt = sharedAt
	// 若因冲突跳过，回查已有记录的 shared_at 以保证返回一致（可选；此处简化为
	// 不再回查，调用方据 rowsAffected==0 判定为已是共享状态）。
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrAlreadyShared
	}
	return nil
}

// ErrSelfShare 表示试图把相册共享给所有者自己。
var ErrSelfShare = errors.New("cannot share album with self")

// ErrAlreadyShared 表示该 (album_id, user) 共享关系已存在（CreateAlbumShare 幂等跳过）。
var ErrAlreadyShared = errors.New("album already shared with this user")

// ListAlbumSharesSharedWith 返回 sharedWithUserID 被共享的全部相册关联记录，
// 按 shared_at 降序（最近共享在前）。供 GET /api/media/albums/shared 列出被共享
// 给当前用户的相册。空 sharedWithUserID 直接返回空切片（不查库）。
func (s *Store) ListAlbumSharesSharedWith(ctx context.Context, sharedWithUserID string) ([]*AlbumShare, error) {
	if sharedWithUserID == "" {
		return nil, nil
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT id, album_id, owner_user_id, shared_with_user_id, shared_at
FROM album_shares WHERE shared_with_user_id = ?
ORDER BY shared_at DESC`, sharedWithUserID)
	if err != nil {
		return nil, fmt.Errorf("list album shares shared with: %w", err)
	}
	defer rows.Close()
	var out []*AlbumShare
	for rows.Next() {
		var a AlbumShare
		var sharedAt string
		if err := rows.Scan(&a.ID, &a.AlbumID, &a.OwnerUserID, &a.SharedWithUserID, &sharedAt); err != nil {
			return nil, fmt.Errorf("scan album share: %w", err)
		}
		a.SharedAt = timeFromVal(sharedAt)
		out = append(out, &a)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows album shares: %w", err)
	}
	return out, nil
}

// ListAlbumSharesByAlbum V8：返回某相册共享给了哪些用户。
func (s *Store) ListAlbumSharesByAlbum(ctx context.Context, albumID, ownerUserID string) ([]*AlbumShare, error) {
	if albumID == "" {
		return nil, nil
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT id, album_id, owner_user_id, shared_with_user_id, shared_at
FROM album_shares WHERE album_id = ? AND owner_user_id = ?
ORDER BY shared_at DESC`, albumID, ownerUserID)
	if err != nil {
		return nil, fmt.Errorf("list album shares by album: %w", err)
	}
	defer rows.Close()
	var out []*AlbumShare
	for rows.Next() {
		var a AlbumShare
		var sharedAt string
		if err := rows.Scan(&a.ID, &a.AlbumID, &a.OwnerUserID, &a.SharedWithUserID, &sharedAt); err != nil {
			return nil, fmt.Errorf("scan album share: %w", err)
		}
		a.SharedAt = timeFromVal(sharedAt)
		out = append(out, &a)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows album shares: %w", err)
	}
	return out, nil
}

// IsAlbumSharedWith 判断 albumID 是否已共享给 sharedWithUserID（即该用户对该相册
// 有访问权）。
//
// 仅校验"被共享"关系，不含所有者自身权限（owner 访问自己的相册不走此方法）。
// 供 handleAlbumResource/handleAlbumAdd 等端点判定 sharee 是否可访问相册。
// 任一参数为空返回 false（不报错），保持调用方逻辑简洁。
func (s *Store) IsAlbumSharedWith(ctx context.Context, albumID, sharedWithUserID string) bool {
	if albumID == "" || sharedWithUserID == "" {
		return false
	}
	var n int
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM album_shares WHERE album_id = ? AND shared_with_user_id = ?`,
		albumID, sharedWithUserID).Scan(&n); err != nil {
		return false
	}
	return n > 0
}

// DeleteAlbumShare 撤销共享：仅当 album_id 与 owner_user_id 同时匹配才删除——
// 只有所有者能撤销自己发起的共享。sharedWithUserID 非空时进一步按目标用户过滤
// （撤销指定 sharee）；为空时撤销该相册的所有共享（用于 DeleteAlbum 级联清理）。
//
// 未命中或不属于该用户均返回 ErrNotFound（不区分不存在与无权，避免泄露）。
// ownerUserID 为空时直接 ErrNotFound，防止误以空串匹配。
func (s *Store) DeleteAlbumShare(ctx context.Context, albumID, ownerUserID, sharedWithUserID string) error {
	if albumID == "" || ownerUserID == "" {
		return ErrNotFound
	}
	q := `DELETE FROM album_shares WHERE album_id = ? AND owner_user_id = ?`
	args := []any{albumID, ownerUserID}
	if sharedWithUserID != "" {
		q += ` AND shared_with_user_id = ?`
		args = append(args, sharedWithUserID)
	}
	res, err := s.db.ExecContext(ctx, q, args...)
	if err != nil {
		return fmt.Errorf("delete album share: %w", err)
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return ErrNotFound
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

// ===== 同步：去重 / 增量 changes / usage =====

// GetMediaByUserAndSHA256 按 (user_id, sha256) 查询媒体记录（含已软删行）。
// 用于上传秒传：同一用户已存在同 sha256 的内容时直接复用，避免重复落盘。
// sha256 为空时直接返回 ErrNotFound（不去重空指纹）。
func (s *Store) GetMediaByUserAndSHA256(ctx context.Context, userID, sha256 string) (*Media, error) {
	if userID == "" || sha256 == "" {
		return nil, ErrNotFound
	}
	row := s.db.QueryRowContext(ctx, `SELECT `+mediaColumns+` FROM "media" WHERE user_id = ? AND sha256 = ? LIMIT 1`, userID, sha256)
	return scanMedia(row.Scan)
}

// ListMediaChanges 返回某用户 updated_at 严格大于 sinceCursor 的全部 media 行，
// 含软删除墓碑（deleted=1）。按 (updated_at, id) 升序排序，配合 limit/offset 实现
// "拉取增量 → 用最后一条的 (updated_at, id) 作为下一次复合 cursor" 的增量同步。
//
// sinceCursor 为空串时从头拉取。sinceID 非空时启用 (updated_at, id) 复合严格大于：
// `(updated_at > ? OR (updated_at = ? AND id > ?))`，消除同时间戳边界的重/漏风险
// （批量导入或并发写入可能产生相同 updated_at）。sinceID 为空时退化为仅时间戳
// 严格大于，保持向下兼容（旧客户端仅传 since=ms，无 id）。
// 返回值用于构造 {changes, next_cursor, next_cursor_id, has_more}。
func (s *Store) ListMediaChanges(ctx context.Context, userID, sinceCursor, sinceID string, limit, offset int) ([]*Media, error) {
	if limit <= 0 {
		limit = 100
	}
	q := `SELECT ` + mediaColumns + ` FROM "media" WHERE user_id = ?`
	args := []any{userID}
	if sinceCursor != "" {
		if sinceID != "" {
			// 复合游标：(updated_at, id) 严格大于，同时间戳按 id 续拉，无重无漏。
			q += ` AND (updated_at > ? OR (updated_at = ? AND id > ?))`
			args = append(args, sinceCursor, sinceCursor, sinceID)
		} else {
			// 向下兼容：纯时间戳严格大于（旧客户端或首次拉取无 id 续点）。
			q += ` AND updated_at > ?`
			args = append(args, sinceCursor)
		}
	}
	q += ` ORDER BY updated_at ASC, id ASC LIMIT ? OFFSET ?`
	args = append(args, limit, offset)
	rows, err := s.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, fmt.Errorf("list media changes: %w", err)
	}
	return scanMediaRows(rows)
}

// CountMediaChanges 返回满足与 ListMediaChanges 相同游标条件的行数（含墓碑），
// 供 changes 端点计算 has_more（总数 vs 已取）。游标判定逻辑与 ListMediaChanges
// 保持一致（含 sinceID 的复合判定），确保 has_more 计算与实际剩余行数对齐。
func (s *Store) CountMediaChanges(ctx context.Context, userID, sinceCursor, sinceID string) (int, error) {
	q := `SELECT COUNT(*) FROM "media" WHERE user_id = ?`
	args := []any{userID}
	if sinceCursor != "" {
		if sinceID != "" {
			q += ` AND (updated_at > ? OR (updated_at = ? AND id > ?))`
			args = append(args, sinceCursor, sinceCursor, sinceID)
		} else {
			q += ` AND updated_at > ?`
			args = append(args, sinceCursor)
		}
	}
	var n int
	if err := s.db.QueryRowContext(ctx, q, args...).Scan(&n); err != nil {
		return 0, fmt.Errorf("count media changes: %w", err)
	}
	return n, nil
}

// UserUsage 统计某用户名下未软删媒体的存储总量与文件数，供 /api/sync/usage。
// 软删墓碑不计入（其文件可能仍在磁盘但语义上已不属于用户活跃集）。
func (s *Store) UserUsage(ctx context.Context, userID string) (totalBytes int64, fileCount int, err error) {
	q := `SELECT COALESCE(SUM(size), 0), COUNT(*) FROM "media" WHERE user_id = ? AND deleted = 0`
	if err = s.db.QueryRowContext(ctx, q, userID).Scan(&totalBytes, &fileCount); err != nil {
		return 0, 0, fmt.Errorf("user usage: %w", err)
	}
	return totalBytes, fileCount, nil
}

// ===== scan 辅助 =====

// scanFuncRow 是 QueryRow.Scan 的签名；用于让单行与多行复用同一列扫描逻辑。
type scanFunc func(dest ...any) error

// scanMedia 把一行 media 列扫描进 *Media。传入 row.Scan 或 rows.Scan。
// 列顺序须与 mediaColumns 一致（含 client_id/taken_at）。
func scanMedia(scan scanFunc) (*Media, error) {
	var m Media
	var createdAt, updatedAt string
	var deleted int
	if err := scan(&m.ID, &m.UserID, &m.Filename, &m.Type, &m.Size, &m.Mime,
		&m.Width, &m.Height, &createdAt, &updatedAt, &m.SHA256, &deleted,
		&m.ClientID, &m.TakenAt, &m.Orientation); err != nil {
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

// ===== 时间轴日历（按拍摄日期分组） =====

// TimelineCalendar 按拍摄日期（taken_at）分组统计每天的媒体数量，按日期倒序。
// 与 upload-calendar 不同：前者基于 taken_at（EXIF/客户端声明的毫秒时间戳）且不限时间范围，
// 后者基于 created_at（上传时间）且只看最近 30 天。
//
// taken_at 列为 INTEGER 毫秒时间戳，0 表未知；WHERE taken_at > 0 排除无拍摄时间的记录。
// TimeDistribution 按拍摄时间（taken_at）的 UTC 小时分段统计媒体分布。分段与中文标签：
//
//	"深夜" 00:00–05:59   （小时 0–5）
//	"早晨" 06:00–11:59   （小时 6–11）
//	"下午" 12:00–17:59   （小时 12–17）
//	"晚上" 18:00–23:59   （小时 18–23）
//
// taken_at 列为 INTEGER 毫秒时间戳（0 表未知），与 TimelineCalendar 同源；WHERE taken_at > 0
// 排除无拍摄时间的记录，仅统计当前用户未软删（deleted=0）的媒体。
//
// 小时提取用 strftime('%H', taken_at/1000, 'unixepoch')：除 1000 转秒后按 Unix 时间戳解读，
// strftime('%H') 返回 UTC 两位小时（00–23）。与 TimelineCalendar 一致使用 UTC，避免依赖
// 服务器本地时区；前端如需本地时段可自行换算（此处保持与库内其他时间聚合端点同策略）。
//
// 返回 map 固定含四个中文键，值为对应段计数（无命中的段为 0）。调用方据此累加 total。
func (s *Store) TimeDistribution(ctx context.Context, userID string) (map[string]int, error) {
	if userID == "" {
		return nil, fmt.Errorf("user id is required")
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT strftime('%H', taken_at/1000, 'unixepoch') AS hour, COUNT(*) AS count
FROM "media"
WHERE user_id = ? AND deleted = 0 AND taken_at > 0
GROUP BY hour`, userID)
	if err != nil {
		return nil, fmt.Errorf("time distribution: %w", err)
	}
	defer rows.Close()

	dist := map[string]int{"深夜": 0, "早晨": 0, "下午": 0, "晚上": 0}
	for rows.Next() {
		var hour string
		var count int
		if err := rows.Scan(&hour, &count); err != nil {
			return nil, fmt.Errorf("scan time distribution: %w", err)
		}
		// hour 形如 "00".."23"（_STRFtime 保证两位）；atoi 后按 0–5/6–11/12–17/18–23 分段。
		h, err := strconv.Atoi(hour)
		if err != nil || h < 0 || h > 23 {
			// 理论不会出现；防御性跳过异常行，不阻断整体统计。
			continue
		}
		switch {
		case h < 6:
			dist["深夜"] += count
		case h < 12:
			dist["早晨"] += count
		case h < 18:
			dist["下午"] += count
		default:
			dist["晚上"] += count
		}
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows time distribution: %w", err)
	}
	return dist, nil
}

// 时间提取用 strftime('%Y-%m-%d', taken_at/1000, 'unixepoch')：除 1000 转秒，
// 'unixepoch' 修饰符把整列秒数当 Unix 时间戳解读，strftime 取 UTC 日期（与 DB 一致用 UTC，
// 前端按本地时区渲染由调用方决定）。按 (date, type) 双维度分组，type 为 IMAGE/VIDEO/LIVE_PHOTO 等，
// 供前端日历视图区分当天照片/视频数量。每行含 date、type、count；total 为当天全部类型合计
// （供前端"那天共 N 张"的 tooltip，避免再 SUM 聚合）。
//
// 返回 []map[string]any 而非结构体切片，保持与任务约定的灵活 schema 一致，便于前端直接消费。
func (s *Store) TimelineCalendar(ctx context.Context, userID string) ([]map[string]any, error) {
	if userID == "" {
		return nil, fmt.Errorf("user id is required")
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT strftime('%Y-%m-%d', taken_at/1000, 'unixepoch') AS date,
       "type" AS type,
       COUNT(*) AS count
FROM "media"
WHERE user_id = ? AND deleted = 0 AND taken_at > 0
GROUP BY date, "type"
ORDER BY date DESC`, userID)
	if err != nil {
		return nil, fmt.Errorf("timeline calendar: %w", err)
	}
	defer rows.Close()

	// 先按 (date,type) 收集，同时累计每天的 total，保持行顺序按日期倒序。
	// SQLite 已 ORDER BY date DESC，但同一天内多 type 行的相对顺序未定——无妨，
	// 输出每行带 total，前端不需依赖 type 顺序。
	var out []map[string]any
	dayTotal := make(map[string]int) // date → 当天全部 type 合计
	for rows.Next() {
		var date, typ string
		var count int
		if err := rows.Scan(&date, &typ, &count); err != nil {
			return nil, fmt.Errorf("scan timeline calendar: %w", err)
		}
		out = append(out, map[string]any{
			"date":  date,
			"type":  typ,
			"count": count,
		})
		dayTotal[date] += count
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows timeline calendar: %w", err)
	}
	// 回填每行的 total（当天所有类型合计）。
	for _, row := range out {
		row["total"] = dayTotal[row["date"].(string)]
	}
	return out, nil
}

// ===== 媒体热力图（按天统计，一年 GitHub 风格贡献图） =====

// MediaHeatmap 按天统计当前用户未软删媒体的发布数量，供前端渲染一年（12 个月 ×
// 每月天数）的 GitHub 贡献图风格热力图。
//
// 日期取值优先 taken_at（EXIF/客户端声明的拍摄时间，INTEGER 毫秒时间戳）：taken_at>0
// 时用 strftime('%Y-%m-%d', taken_at/1000, 'unixepoch') 取 UTC 日期；taken_at=0（未知）
// 时回退到 created_at（上传时间，TEXT RFC3339），用 substr(created_at,1,10) 取前 10 字符
// 即 YYYY-MM-DD。这样无 EXIF 的媒体也能按上传日归入热力图，避免大片空白。
//
// COALESCE 实现：taken_at=0 时 strftime 返回空串（SQLite 对 0/1000=0 epoch → 1970-01-01
// 实际非空，故显式用 CASE 判定 taken_at>0 选择来源），保证回退语义清晰。
//
// SQL：
//
//	SELECT CASE WHEN taken_at > 0
//	  THEN strftime('%Y-%m-%d', taken_at/1000, 'unixepoch')
//	  ELSE substr(created_at,1,10) END AS date,
//	  COUNT(*) AS count
//	FROM "media"
//	WHERE user_id = ? AND deleted = 0
//	GROUP BY date
//	ORDER BY date
//
// 返回 [{date:"2026-07-30", count:3}, ...]，按 date 升序。仅返回有媒体的日期，
// 调用方/前端据此填充一年网格的空白格为 0。空 userID 直接报错（按用户隔离）。
func (s *Store) MediaHeatmap(ctx context.Context, userID string) ([]map[string]any, error) {
	if userID == "" {
		return nil, fmt.Errorf("user id is required")
	}
	rows, err := s.db.QueryContext(ctx, `
SELECT CASE WHEN taken_at > 0
  THEN strftime('%Y-%m-%d', taken_at/1000, 'unixepoch')
  ELSE substr(created_at,1,10) END AS date,
       COUNT(*) AS count
FROM "media"
WHERE user_id = ? AND deleted = 0
GROUP BY date
ORDER BY date`, userID)
	if err != nil {
		return nil, fmt.Errorf("media heatmap: %w", err)
	}
	defer rows.Close()

	out := make([]map[string]any, 0)
	for rows.Next() {
		var date string
		var count int
		if err := rows.Scan(&date, &count); err != nil {
			return nil, fmt.Errorf("scan media heatmap: %w", err)
		}
		out = append(out, map[string]any{
			"date":  date,
			"count": count,
		})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows media heatmap: %w", err)
	}
	return out, nil
}
