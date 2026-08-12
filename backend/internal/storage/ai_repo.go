// Package storage - PRD-v12 AI 视觉检索数据访问层。
//
// 本文件提供 v12 新增三类能力的 CRUD：媒体注解、图像向量、人物聚类。
// 设计要点：
//   - 向量以 []float32 序列化为 BLOB 落库（binary.LittleEndian），检索时在 Go
//     内存做暴力余弦（单用户量级 <10w，2KB/条 × 10w ≈ 200MB，可承受；超量再上 ANN）。
//   - 所有方法按 user_id 隔离，与现有 repository 范式一致。
//   - 时间列沿用 timeToVal/timeFromVal（RFC3339Nano）。
package storage

import (
	"context"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"math"
	"strings"
	"time"

	"github.com/google/uuid"
)

// ---- 模型 ----

// Annotation 对应 media_annotations 一行。Objects/Colors 在 DB 存 JSON 字符串，
// Go 侧暴露为 []string 便于直接使用。
type Annotation struct {
	MediaID    string
	UserID     string
	Caption    string
	Scene      string
	Objects    []string
	Colors     []string
	Mood       string
	ManualNote string
	ModelVer   string
	CreatedAt  time.Time
	UpdatedAt  time.Time
}

// Embedding 对应 media_embeddings 一行。Vector 为原始 []float32。
type Embedding struct {
	MediaID   string
	UserID    string
	Vector    []float32
	Dim       int
	ModelVer  string
	CreatedAt time.Time
}

// PersonCluster 对应 person_clusters 一行。
type PersonCluster struct {
	ID            string
	UserID        string
	Name          string
	AvatarMediaID string
	FaceCount     int
	CreatedAt     time.Time
}

// MediaPerson 对应 media_persons 一行。
type MediaPerson struct {
	ID         string
	MediaID    string
	UserID     string
	ClusterID  string
	Bbox       string // JSON [x,y,w,h] 归一化
	FaceVector []float32
	CreatedAt  time.Time
}

// AIIndexProgress 表示某用户的索引覆盖率。
type AIIndexProgress struct {
	Total      int
	Indexed    int
	Pending    int
	Annotated  int
	Persons    int
}

// ---- 向量序列化 ----

// encodeVector 把 []float32 序列化为 BLOB（小端 4 字节/元素）。
func encodeVector(v []float32) []byte {
	if len(v) == 0 {
		return nil
	}
	buf := make([]byte, len(v)*4)
	for i, f := range v {
		binary.LittleEndian.PutUint32(buf[i*4:], math.Float32bits(f))
	}
	return buf
}

// decodeVector 把 BLOB 还原为 []float32。dim 为 0 时返回 nil。
func decodeVector(b []byte, dim int) []float32 {
	if dim <= 0 || len(b) < dim*4 {
		return nil
	}
	out := make([]float32, dim)
	for i := 0; i < dim; i++ {
		out[i] = math.Float32frombits(binary.LittleEndian.Uint32(b[i*4:]))
	}
	return out
}

// Cosine 计算两向量余弦相似度。长度不等或零向量返回 0。
func Cosine(a, b []float32) float32 {
	n := len(a)
	if n == 0 || n != len(b) {
		return 0
	}
	var dot, na, nb float64
	for i := 0; i < n; i++ {
		dot += float64(a[i]) * float64(b[i])
		na += float64(a[i]) * float64(a[i])
		nb += float64(b[i]) * float64(b[i])
	}
	if na == 0 || nb == 0 {
		return 0
	}
	return float32(dot / (math.Sqrt(na) * math.Sqrt(nb)))
}

// ---- 注解 CRUD ----

// UpsertAnnotation 插入或更新一条注解（按 media_id 主键 upsert）。
func (s *Store) UpsertAnnotation(ctx context.Context, a *Annotation) error {
	if a.MediaID == "" {
		return fmt.Errorf("media_id required")
	}
	if a.CreatedAt.IsZero() {
		a.CreatedAt = time.Now()
	}
	a.UpdatedAt = time.Now()
	objectsJSON, _ := json.Marshal(a.Objects)
	colorsJSON, _ := json.Marshal(a.Colors)
	_, err := s.db.ExecContext(ctx, `
INSERT INTO media_annotations
  (media_id, user_id, caption, scene, objects, colors, mood, manual_note, model_ver, created_at, updated_at)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
ON CONFLICT(media_id) DO UPDATE SET
  caption=excluded.caption, scene=excluded.scene, objects=excluded.objects,
  colors=excluded.colors, mood=excluded.mood, model_ver=excluded.model_ver,
  updated_at=excluded.updated_at`,
		a.MediaID, a.UserID, a.Caption, a.Scene, string(objectsJSON), string(colorsJSON),
		a.Mood, a.ManualNote, a.ModelVer, timeToVal(a.CreatedAt), timeToVal(a.UpdatedAt))
	return err
}

// UpdateAnnotationManualNote 仅更新用户手动备注（编辑注解场景）。
func (s *Store) UpdateAnnotationManualNote(ctx context.Context, userID, mediaID, note string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE media_annotations SET manual_note=?, updated_at=? WHERE media_id=? AND user_id=?`,
		note, timeToVal(time.Now()), mediaID, userID)
	return err
}

// GetAnnotation 取单张注解。
func (s *Store) GetAnnotation(ctx context.Context, userID, mediaID string) (*Annotation, error) {
	row := s.db.QueryRowContext(ctx,
		`SELECT media_id, user_id, caption, scene, objects, colors, mood, manual_note, model_ver, created_at, updated_at
		 FROM media_annotations WHERE media_id=? AND user_id=?`, mediaID, userID)
	a := &Annotation{}
	var objs, cols string
	var c, u string
	err := row.Scan(&a.MediaID, &a.UserID, &a.Caption, &a.Scene, &objs, &cols, &a.Mood,
		&a.ManualNote, &a.ModelVer, &c, &u)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	_ = json.Unmarshal([]byte(objs), &a.Objects)
	_ = json.Unmarshal([]byte(cols), &a.Colors)
	a.CreatedAt = timeFromVal(c)
	a.UpdatedAt = timeFromVal(u)
	return a, nil
}

// ListAnnotationsByScene 按场景聚合（自动相册用）。返回 scene -> count + 代表 media。
type SceneGroup struct {
	Scene    string
	Count    int
	SampleID string
}

func (s *Store) ListAnnotationsByScene(ctx context.Context, userID string) ([]SceneGroup, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT scene, COUNT(*) AS c, media_id FROM media_annotations
		 WHERE user_id=? AND scene!='' GROUP BY scene ORDER BY c DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []SceneGroup
	for rows.Next() {
		var g SceneGroup
		if err := rows.Scan(&g.Scene, &g.Count, &g.SampleID); err != nil {
			return nil, err
		}
		out = append(out, g)
	}
	return out, rows.Err()
}

// ---- 向量 CRUD ----

// UpsertEmbedding 插入或覆盖一条图像向量。
func (s *Store) UpsertEmbedding(ctx context.Context, e *Embedding) error {
	if e.MediaID == "" || len(e.Vector) == 0 {
		return fmt.Errorf("media_id and vector required")
	}
	if e.CreatedAt.IsZero() {
		e.CreatedAt = time.Now()
	}
	e.Dim = len(e.Vector)
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO media_embeddings (media_id, user_id, vector, dim, model_ver, created_at)
		 VALUES (?, ?, ?, ?, ?, ?)
		 ON CONFLICT(media_id) DO UPDATE SET vector=excluded.vector, dim=excluded.dim,
		   model_ver=excluded.model_ver, created_at=excluded.created_at`,
		e.MediaID, e.UserID, encodeVector(e.Vector), e.Dim, e.ModelVer, timeToVal(e.CreatedAt))
	return err
}

// LoadEmbeddings 加载某用户全部向量到内存（用于暴力检索）。
// 返回 media_id -> vector 映射。单用户量级可控时不上 ANN。
func (s *Store) LoadEmbeddings(ctx context.Context, userID string) (map[string][]float32, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT media_id, vector, dim FROM media_embeddings WHERE user_id=?`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make(map[string][]float32)
	for rows.Next() {
		var id string
		var buf []byte
		var dim int
		if err := rows.Scan(&id, &buf, &dim); err != nil {
			return nil, err
		}
		out[id] = decodeVector(buf, dim)
	}
	return out, rows.Err()
}

// ListUnindexedMedia 返回有原图但尚无向量的 media_id 列表（索引管线消费）。
func (s *Store) ListUnindexedMedia(ctx context.Context, userID string, limit int) ([]string, error) {
	if limit <= 0 {
		limit = 20
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT m.id FROM media m
		 LEFT JOIN media_embeddings e ON e.media_id=m.id
		 WHERE m.user_id=? AND m.deleted=0 AND e.media_id IS NULL
		 ORDER BY m.created_at DESC LIMIT ?`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// AIProgress 返回索引进度统计。
func (s *Store) AIProgress(ctx context.Context, userID string) (*AIIndexProgress, error) {
	p := &AIIndexProgress{}
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM media WHERE user_id=? AND deleted=0`, userID).Scan(&p.Total); err != nil {
		return nil, err
	}
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM media_embeddings WHERE user_id=?`, userID).Scan(&p.Indexed); err != nil {
		return nil, err
	}
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM media_annotations WHERE user_id=? AND caption!=''`, userID).Scan(&p.Annotated); err != nil {
		return nil, err
	}
	if err := s.db.QueryRowContext(ctx,
		`SELECT COUNT(DISTINCT cluster_id) FROM media_persons WHERE user_id=?`, userID).Scan(&p.Persons); err != nil {
		return nil, err
	}
	p.Pending = p.Total - p.Indexed
	if p.Pending < 0 {
		p.Pending = 0
	}
	return p, nil
}

// ---- 人物聚类 ----

// CreatePersonCluster 建空 cluster。name 可空（用户未命名）。
func (s *Store) CreatePersonCluster(ctx context.Context, userID, name, avatarMediaID string) (*PersonCluster, error) {
	c := &PersonCluster{
		ID:            uuid.NewString(),
		UserID:        userID,
		Name:          name,
		AvatarMediaID: avatarMediaID,
		CreatedAt:     time.Now(),
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO person_clusters (id, user_id, name, avatar_media_id, face_count, created_at)
		 VALUES (?, ?, ?, ?, 0, ?)`,
		c.ID, c.UserID, c.Name, nullableStr(c.AvatarMediaID), timeToVal(c.CreatedAt))
	if err != nil {
		return nil, err
	}
	return c, nil
}

// nullableStr 空串→nil（供可空列）。
func nullableStr(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// RenamePersonCluster 用户给聚类命名（"我"/"妈妈"）。
func (s *Store) RenamePersonCluster(ctx context.Context, userID, clusterID, name string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE person_clusters SET name=? WHERE id=? AND user_id=?`, name, clusterID, userID)
	return err
}

// ListPersonClusters 列出某用户全部人物聚类。
func (s *Store) ListPersonClusters(ctx context.Context, userID string) ([]*PersonCluster, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT id, user_id, COALESCE(name,''), COALESCE(avatar_media_id,''), face_count, created_at
		 FROM person_clusters WHERE user_id=? ORDER BY face_count DESC`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*PersonCluster
	for rows.Next() {
		c := &PersonCluster{}
		var cAt string
		if err := rows.Scan(&c.ID, &c.UserID, &c.Name, &c.AvatarMediaID, &c.FaceCount, &cAt); err != nil {
			return nil, err
		}
		c.CreatedAt = timeFromVal(cAt)
		out = append(out, c)
	}
	return out, rows.Err()
}

// AddMediaPerson 记录一张图中的一张脸，归属某 cluster，并更新该 cluster 计数。
func (s *Store) AddMediaPerson(ctx context.Context, mp *MediaPerson) error {
	if mp.ID == "" {
		mp.ID = uuid.NewString()
	}
	if mp.CreatedAt.IsZero() {
		mp.CreatedAt = time.Now()
	}
	var faceBlob any
	if len(mp.FaceVector) > 0 {
		faceBlob = encodeVector(mp.FaceVector)
	}
	_, err := s.db.ExecContext(ctx,
		`INSERT INTO media_persons (id, media_id, user_id, cluster_id, bbox, face_vector, created_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?)`,
		mp.ID, mp.MediaID, mp.UserID, mp.ClusterID, mp.Bbox, faceBlob, timeToVal(mp.CreatedAt))
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx,
		`UPDATE person_clusters SET face_count=(SELECT COUNT(*) FROM media_persons WHERE cluster_id=?)
		 WHERE id=?`, mp.ClusterID, mp.ClusterID)
	return err
}

// ListMediaByCluster 列出某人物 cluster 的全部 media_id。
func (s *Store) ListMediaByCluster(ctx context.Context, userID, clusterID string) ([]string, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT DISTINCT media_id FROM media_persons WHERE user_id=? AND cluster_id=?`,
		userID, clusterID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	return out, rows.Err()
}

// ListAllFaceVectors 取全部人脸向量（聚类重算用）。
type FaceRec struct {
	MediaID    string
	ClusterID  string
	FaceVector []float32
}

func (s *Store) ListAllFaceVectors(ctx context.Context, userID string) ([]FaceRec, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT media_id, cluster_id, face_vector FROM media_persons WHERE user_id=?`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []FaceRec
	for rows.Next() {
		var r FaceRec
		var buf []byte
		if err := rows.Scan(&r.MediaID, &r.ClusterID, &buf); err != nil {
			return nil, err
		}
		if len(buf) > 0 {
			// dim 由 blob 长度推得
			r.FaceVector = decodeVector(buf, len(buf)/4)
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

// AssignMediaPersonCluster 重聚类：把某 face 记录改派到新 cluster。
func (s *Store) AssignMediaPersonCluster(ctx context.Context, userID, personID, newClusterID string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE media_persons SET cluster_id=? WHERE id=? AND user_id=?`,
		newClusterID, personID, userID)
	if err != nil {
		return err
	}
	// 刷新两个相关 cluster 计数
	for _, c := range []string{newClusterID} {
		_, _ = s.db.ExecContext(ctx,
			`UPDATE person_clusters SET face_count=(SELECT COUNT(*) FROM media_persons WHERE cluster_id=?)
			 WHERE id=?`, c, c)
	}
	return nil
}

// sanitize 关闭未使用 import 警告兜底（strings 当前未用，预留分割工具）。
var _ = strings.Split
