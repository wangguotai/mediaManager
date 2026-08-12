// Package gateway - PRD-v12 AI 视觉检索端点与索引管线。
//
// 本文件实现 v12 的 HTTP 层：
//   - AIClient：调本地 Python 特征服务（:8095）做向量化/caption/classify
//   - 索引 worker：周期扫描未索引 media，提取向量+注解并落库
//   - 检索端点：/api/ai/search 语义 top-k + 混合排序
//   - 注解端点：取/编辑单张注解
//   - 自动相册：按 scene 聚合
//
// 设计：worker 单 goroutine 串行调特征服务（避免压垮），失败不阻塞；检索在
// Go 内存做暴力余弦（用户级向量集 <10w 可承受，超量再上 ANN）。路径解析复用
// MediaService.ResolveMediaPath，不重复文件定位逻辑。
package gateway

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"media-manager/backend/internal/service"
	"media-manager/backend/internal/storage"
)

// aiFeatureSvcURL 是 Python 特征服务地址。可由环境变量 AI_SVC_URL 覆盖。
var aiFeatureSvcURL = getenvDefault("AI_SVC_URL", "http://127.0.0.1:8095")

func getenvDefault(k, d string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return d
}

// ---- AI 客户端 ----

// AIClient 封装对 Python 特征服务的调用。零失败：任一端点不可达时返回降级结果。
type AIClient struct {
	baseURL string
	hc      *http.Client
}

func NewAIClient(baseURL string) *AIClient {
	if baseURL == "" {
		baseURL = aiFeatureSvcURL
	}
	return &AIClient{baseURL: baseURL, hc: &http.Client{Timeout: 120 * time.Second}}
}

// embedResp 是特征服务 /embed、/text-embed 的返回。
type embedResp struct {
	Vector    []float32 `json:"vector"`
	Dim       int       `json:"dim"`
	ModelVer  string    `json:"model_ver"`
}

// captionResp 是 /caption 返回。
type captionResp struct {
	Caption   string   `json:"caption"`
	Scene     string   `json:"scene"`
	Objects   []string `json:"objects"`
	Colors    []string `json:"colors"`
	Mood      string   `json:"mood"`
	ModelVer  string   `json:"model_ver"`
}

// EmbedText 文本向量化（检索查询用）。
func (c *AIClient) EmbedText(ctx context.Context, text string) (*embedResp, error) {
	body, _ := json.Marshal(map[string]string{"text": text})
	return c.doJSON(ctx, "/text-embed", body)
}

// EmbedImageFile 对磁盘上的图片文件做视觉向量化。
func (c *AIClient) EmbedImageFile(ctx context.Context, path string) (*embedResp, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return c.doRaw(ctx, "/embed", raw, mimeFromExt(filepath.Ext(path)))
}

// CaptionImageFile 对磁盘图片生成中文描述+结构化标签。
func (c *AIClient) CaptionImageFile(ctx context.Context, path string) (*captionResp, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	req, _ := http.NewRequestWithContext(ctx, "POST", c.baseURL+"/caption", bytes.NewReader(raw))
	req.Header.Set("Content-Type", mimeFromExt(filepath.Ext(path)))
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("caption status %d: %s", resp.StatusCode, string(b))
	}
	var cr captionResp
	if err := json.NewDecoder(resp.Body).Decode(&cr); err != nil {
		return nil, err
	}
	return &cr, nil
}

func (c *AIClient) doJSON(ctx context.Context, path string, body []byte) (*embedResp, error) {
	req, _ := http.NewRequestWithContext(ctx, "POST", c.baseURL+path, bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("%s status %d: %s", path, resp.StatusCode, string(b))
	}
	var er embedResp
	if err := json.NewDecoder(resp.Body).Decode(&er); err != nil {
		return nil, err
	}
	return &er, nil
}

func (c *AIClient) doRaw(ctx context.Context, path string, raw []byte, mime string) (*embedResp, error) {
	req, _ := http.NewRequestWithContext(ctx, "POST", c.baseURL+path, bytes.NewReader(raw))
	req.Header.Set("Content-Type", mime)
	resp, err := c.hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		b, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("%s status %d: %s", path, resp.StatusCode, string(b))
	}
	var er embedResp
	if err := json.NewDecoder(resp.Body).Decode(&er); err != nil {
		return nil, err
	}
	return &er, nil
}

func mimeFromExt(ext string) string {
	switch strings.ToLower(ext) {
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	default:
		return "image/jpeg"
	}
}

// ---- 索引 worker ----

// AIIndexer 是后台索引管线。ScanInterval 控制扫描周期。
type AIIndexer struct {
	server *Server
	ai     *AIClient
	logf   func(format string, args ...any)

	mu      sync.Mutex
	running bool
	stopCh  chan struct{}
}

// NewAIIndexer 创建索引器。logf 为空时用标准日志。
func (s *Server) NewAIIndexer() *AIIndexer {
	return &AIIndexer{
		server: s,
		ai:     NewAIClient(aiFeatureSvcURL),
		logf:   func(format string, args ...any) { slog.Info("ai-indexer: "+format, args...) },
		stopCh: make(chan struct{}),
	}
}

// Start 启动后台 worker。幂等（重复调用安全）。
func (a *AIIndexer) Start(scanInterval time.Duration) {
	if scanInterval <= 0 {
		scanInterval = 30 * time.Second
	}
	a.mu.Lock()
	if a.running {
		a.mu.Unlock()
		return
	}
	a.running = true
	a.mu.Unlock()
	go a.loop(scanInterval)
	a.logf("started, scan interval %v", scanInterval)
}

func (a *AIIndexer) loop(interval time.Duration) {
	t := time.NewTicker(interval)
	defer t.Stop()
	// 启动后立即跑一轮
	a.indexOnce(context.Background(), 20)
	for {
		select {
		case <-a.stopCh:
			a.logf("stopped")
			return
		case <-t.C:
			a.indexOnce(context.Background(), 20)
		}
	}
}

// resolveMediaPath 由 gateway 通过 mediaSvc 接口断言拿到 MediaService。
// 这里用类型断言访问 service.MediaService.ResolveMediaPath。
func (a *AIIndexer) resolvePath(uid, mediaID string) string {
	if ms, ok := a.server.mediaSvc.(*service.MediaService); ok {
		return ms.ResolveMediaPath(uid, mediaID)
	}
	return ""
}

// indexOnce 扫描全部用户（或指定用户）的未索引 media，逐张提取向量+caption 落库。
// limit 控制单轮处理量，避免长时间占用。返回本轮处理数。
func (a *AIIndexer) indexOnce(ctx context.Context, limit int) int {
	if a.server.store == nil {
		return 0
	}
	users, err := a.server.store.ListUsers(ctx)
	if err != nil || len(users) == 0 {
		return 0
	}
	processed := 0
	for _, u := range users {
		ids, err := a.server.store.ListUnindexedMedia(ctx, u.ID, limit)
		if err != nil || len(ids) == 0 {
			continue
		}
		for _, mid := range ids {
			if err := a.indexOne(ctx, u.ID, mid); err != nil {
				a.logf("index %s err: %v", mid, err)
				continue
			}
			processed++
		}
	}
	if processed > 0 {
		a.logf("round processed %d", processed)
	}
	return processed
}

// indexOne 处理单张：取文件→embed→caption→落库。
func (a *AIIndexer) indexOne(ctx context.Context, uid, mediaID string) error {
	path := a.resolvePath(uid, mediaID)
	if path == "" {
		return fmt.Errorf("media file not found: %s", mediaID)
	}
	// 1. 向量
	er, err := a.ai.EmbedImageFile(ctx, path)
	if err != nil {
		return fmt.Errorf("embed: %w", err)
	}
	if err := a.server.store.UpsertEmbedding(ctx, &storage.Embedding{
		MediaID: mediaID, UserID: uid, Vector: er.Vector, ModelVer: er.ModelVer,
	}); err != nil {
		return fmt.Errorf("save embed: %w", err)
	}
	// 2. caption（失败不致命，向量已落库）
	cr, err := a.ai.CaptionImageFile(ctx, path)
	ann := &storage.Annotation{
		MediaID: mediaID, UserID: uid, ModelVer: "none",
	}
	if err == nil && cr != nil {
		ann.Caption = cr.Caption
		ann.Scene = cr.Scene
		ann.Objects = cr.Objects
		ann.Colors = cr.Colors
		ann.Mood = cr.Mood
		ann.ModelVer = cr.ModelVer
	}
	_ = a.server.store.UpsertAnnotation(ctx, ann)
	return nil
}

// ---- 检索 ----

// aiSearchResult 单条检索结果。
type aiSearchResult struct {
	Media    map[string]any `json:"media"`
	Score    float32        `json:"score"`
	Caption  string         `json:"caption,omitempty"`
	Scene    string         `json:"scene,omitempty"`
}

// SearchSemantic 执行语义检索：文本→向量→内存余弦 top-k→混合排序。
// 人物/时间/类型词走硬过滤（复用 parseSmartQuery）。
func (s *Server) SearchSemantic(ctx context.Context, uid, query string, limit int) ([]aiSearchResult, error) {
	if limit <= 0 {
		limit = 50
	}
	ai := NewAIClient(aiFeatureSvcURL)
	er, err := ai.EmbedText(ctx, query)
	if err != nil {
		return nil, fmt.Errorf("embed query: %w", err)
	}
	qv := er.Vector

	// 加载用户全部图像向量
	embeds, err := s.store.LoadEmbeddings(ctx, uid)
	if err != nil {
		return nil, err
	}
	if len(embeds) == 0 {
		return nil, nil
	}

	// 解析硬过滤条件
	parsed := parseSmartQuery(query)

	// 人物词过滤：查询含"我/他/她/妈妈/爸爸/宝宝"等时，按命名 cluster 限定候选集。
	// 例："我穿汉服的照片" → 先取名为"我"的 cluster 全部 media，再语义排序。
	// 注意：nil 表示"不限定"，空 map 会误过滤（空 map 非 nil，所有 mid 都 !hit）。
	// 故无人物词或无匹配 cluster 时必须保持 nil，不能初始化为空 map。
	var personClusterMedia map[string]bool // nil = 不限定
	if personName, ok := detectPersonInQuery(query); ok {
		clusters, _ := s.store.ListPersonClusters(ctx, uid)
		for _, pc := range clusters {
			if pc.Name == personName {
				ids, _ := s.store.ListMediaByCluster(ctx, uid, pc.ID)
				for _, id := range ids {
					if personClusterMedia == nil {
						personClusterMedia = make(map[string]bool)
					}
					personClusterMedia[id] = true
				}
			}
		}
		// 若识别到人物名但没匹配 cluster，不强制过滤（保持 nil 避免零结果）
		if len(personClusterMedia) == 0 {
			personClusterMedia = nil
		}
	}

	// 先收集候选 + 余弦分
	type cand struct {
		mediaID string
		sem     float32
	}
	cands := make([]cand, 0, len(embeds))
	for mid, mv := range embeds {
		cands = append(cands, cand{mid, storage.Cosine(qv, mv)})
	}
	// 混合排序后取 top-k
	sort.Slice(cands, func(i, j int) bool { return cands[i].sem > cands[j].sem })

	// 取 top limit*2 候选（够过滤），补 metadata 做硬过滤与时间近度
	fetchN := limit * 2
	if fetchN > len(cands) {
		fetchN = len(cands)
	}
	out := make([]aiSearchResult, 0, limit)
	for i := 0; i < fetchN && len(out) < limit; i++ {
		mid := cands[i].mediaID
		// 人物词过滤：若启用了 personClusterMedia 限制，候选必须在其中
		if personClusterMedia != nil && !personClusterMedia[mid] {
			continue
		}
		m, err := s.store.GetMedia(ctx, mid)
		if err != nil || m == nil || m.UserID != uid {
			continue
		}
		// 硬过滤
		if parsed.Type != "" && m.Type != parsed.Type {
			continue
		}
		if !inTimeWindow(m, parsed) {
			continue
		}
		ann, _ := s.store.GetAnnotation(ctx, uid, mid)
		// 语义+NLP 关键词加成（caption/manual_note 含查询词）
		boost := float32(0)
		if ann != nil {
			if strings.Contains(ann.Caption, query) || strings.Contains(ann.ManualNote, query) {
				boost += 0.15
			}
			for _, o := range ann.Objects {
				if strings.Contains(query, o) {
					boost += 0.1
				}
			}
		}
		mediaMap := mediaToMap(m)
		mediaMap["thumbnail_url"] = "/api/media/stream/" + mid
		out = append(out, aiSearchResult{
			Media:   mediaMap,
			Score:   cands[i].sem + boost,
			Caption: captionOf(ann),
			Scene:   sceneOf(ann),
		})
	}
	return out, nil
}

// detectPersonInQuery 检查询询是否含人物称谓词，返回匹配的 cluster 命名。
// "我/我的" → "我"；"妈妈/老妈" → "妈妈"；"爸爸" → "爸爸" 等。用户需先给 cluster 这样命名。
func detectPersonInQuery(q string) (string, bool) {
	// 称谓词 → 标准命名
	pairs := []struct{ word, name string }{
		{"妈妈", "妈妈"}, {"老妈", "妈妈"}, {"母亲", "妈妈"},
		{"爸爸", "爸爸"}, {"老爸", "爸爸"}, {"父亲", "爸爸"},
		{"宝宝", "宝宝"}, {"小孩", "宝宝"}, {"孩子", "宝宝"},
		{"爷爷", "爷爷"}, {"奶奶", "奶奶"},
		{"朋友", "朋友"}, {"女朋友", "女朋友"}, {"男朋友", "男朋友"},
		{"老公", "老公"}, {"老婆", "老婆"},
	}
	for _, p := range pairs {
		if strings.Contains(q, p.word) {
			return p.name, true
		}
	}
	// "我/我的" 最后判（最弱匹配）
	if strings.Contains(q, "我的") || strings.Contains(q, "我") {
		return "我", true
	}
	return "", false
}

// inTimeWindow 判断 media 是否落在 smart query 解析的时间范围内。
// DateRange.From/To 是 RFC3339 字符串（与 smartParsedQuery 一致）。
func inTimeWindow(m *storage.Media, pq smartParsedQuery) bool {
	if pq.DateRange == nil {
		return true
	}
	// 用 taken_at（ms epoch）优先，无则 created_at
	var t time.Time
	if m.TakenAt > 0 {
		t = time.UnixMilli(m.TakenAt)
	} else {
		t = m.CreatedAt
	}
	if pq.DateRange.From != "" {
		if from, err := time.Parse(time.RFC3339, pq.DateRange.From); err == nil && t.Before(from) {
			return false
		}
	}
	if pq.DateRange.To != "" {
		if to, err := time.Parse(time.RFC3339, pq.DateRange.To); err == nil && t.After(to) {
			return false
		}
	}
	return true
}

func captionOf(a *storage.Annotation) string {
	if a == nil {
		return ""
	}
	if a.ManualNote != "" {
		return a.ManualNote
	}
	return a.Caption
}

func sceneOf(a *storage.Annotation) string {
	if a == nil {
		return ""
	}
	return a.Scene
}

func mediaToMap(m *storage.Media) map[string]any {
	return map[string]any{
		"id":       m.ID,
		"filename": m.Filename,
		"type":     m.Type,
		"size":     m.Size,
		"width":    m.Width,
		"height":   m.Height,
		"taken_at": m.TakenAt,
		"created_at": m.CreatedAt,
	}
}

// ---- HTTP 端点 ----

// RegisterAIRoutes 注册 /api/ai/* 与 /api/persons/* 路由。在 SetRoutes 中调用。
func (s *Server) RegisterAIRoutes() {
	s.mux.HandleFunc("/api/ai/search", s.handleAISearch)
	s.mux.HandleFunc("/api/ai/status", s.handleAIStatus)
	s.mux.HandleFunc("/api/ai/index", s.handleAIIndex) // POST 触发一轮索引
	s.mux.HandleFunc("/api/ai/albums", s.handleAIAutoAlbums)
	s.mux.HandleFunc("/api/ai/annotation/", s.handleAIAnnotation) // GET/PUT
	s.mux.HandleFunc("/api/persons", s.handlePersonsList)
	s.mux.HandleFunc("/api/persons/recluster", s.handlePersonsRecluster)
	s.mux.HandleFunc("/api/persons/", s.handlePersonsItem) // {id} 命名 / {id}/media
}

// handleAISearch GET ?q=&limit=
func (s *Server) handleAISearch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if q == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "q required"})
		return
	}
	limit := 50
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := parseIntSafe(v); err == nil && n > 0 {
			limit = n
		}
	}
	results, err := s.SearchSemantic(r.Context(), uid, q, limit)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"results":      results,
		"total":        len(results),
		"query":        q,
		"parsed_query": parseSmartQuery(q),
	})
}

// handleAIStatus GET 索引进度
func (s *Server) handleAIStatus(w http.ResponseWriter, r *http.Request) {
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	prog, err := s.store.AIProgress(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 特征服务健康
	hc := &http.Client{Timeout: 3 * time.Second}
	hresp, err := hc.Get(aiFeatureSvcURL + "/health")
	svcHealth := map[string]any{"reachable": false}
	if err == nil {
		defer hresp.Body.Close()
		if hresp.StatusCode == 200 {
			_ = json.NewDecoder(hresp.Body).Decode(&svcHealth)
			svcHealth["reachable"] = true
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"progress": prog,
		"feature_svc": svcHealth,
	})
}

// handleAIIndex POST ?limit=N 触发一轮同步索引（阻塞到完成或超时）
func (s *Server) handleAIIndex(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	limit := 10
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := parseIntSafe(v); err == nil && n > 0 {
			limit = n
		}
	}
	// 同步索引指定用户
	a := s.NewAIIndexer()
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
	defer cancel()
	ids, err := s.store.ListUnindexedMedia(ctx, uid, limit)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	processed := 0
	for _, mid := range ids {
		if err := a.indexOne(ctx, uid, mid); err != nil {
			continue
		}
		processed++
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"processed":  processed,
		"candidates": len(ids),
		"remaining":  0, // 下一轮再扫
	})
}

// handleAIAutoAlbums GET 按场景聚合的自动相册
func (s *Server) handleAIAutoAlbums(w http.ResponseWriter, r *http.Request) {
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	scenes, err := s.store.ListAnnotationsByScene(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	// 额外按 objects 聚合（汉服等）
	// 简化：先返回 scene 相册 + objects 标签云
	writeJSON(w, http.StatusOK, map[string]any{
		"albums":     scenes,
		"total":      len(scenes),
	})
}

// handleAIAnnotation GET/PUT /api/ai/annotation/{media_id}
func (s *Server) handleAIAnnotation(w http.ResponseWriter, r *http.Request) {
	uid := userIDFromContext(r.Context())
	if uid == "" {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	if s.store == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "storage unavailable"})
		return
	}
	// path: /api/ai/annotation/{media_id}
	rest := strings.TrimPrefix(r.URL.Path, "/api/ai/annotation/")
	mediaID := strings.Trim(rest, "/")
	if mediaID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "media_id required"})
		return
	}
	switch r.Method {
	case http.MethodGet:
		ann, err := s.store.GetAnnotation(r.Context(), uid, mediaID)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		if ann == nil {
			writeJSON(w, http.StatusOK, map[string]any{"annotation": nil, "media_id": mediaID})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"annotation": ann, "media_id": mediaID})
	case http.MethodPut:
		var body struct {
			ManualNote string `json:"manual_note"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
			return
		}
		if err := s.store.UpdateAnnotationManualNote(r.Context(), uid, mediaID, body.ManualNote); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		// 若该 media 还没 annotation 行，先建空壳再更新
		if existing, _ := s.store.GetAnnotation(r.Context(), uid, mediaID); existing == nil {
			_ = s.store.UpsertAnnotation(r.Context(), &storage.Annotation{
				MediaID: mediaID, UserID: uid, ManualNote: body.ManualNote,
				Objects: []string{}, Colors: []string{},
			})
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	default:
		writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
	}
}

// ---- 人物聚类端点（占位，实现在 ai_persons.go）----
func (s *Server) handlePersonsList(w http.ResponseWriter, r *http.Request) {
	uid := userIDFromContext(r.Context())
	if uid == "" || s.store == nil {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	clusters, err := s.store.ListPersonClusters(r.Context(), uid)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"clusters": clusters, "total": len(clusters)})
}

func (s *Server) handlePersonsItem(w http.ResponseWriter, r *http.Request) {
	rest := strings.TrimPrefix(r.URL.Path, "/api/persons/")
	parts := strings.Split(strings.Trim(rest, "/"), "/")
	if len(parts) == 0 || parts[0] == "" {
		writeJSON(w, http.StatusBadRequest, map[string]any{"error": "id required"})
		return
	}
	uid := userIDFromContext(r.Context())
	if uid == "" || s.store == nil {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	id := parts[0]
	if len(parts) >= 2 && parts[1] == "media" {
		// GET /api/persons/{id}/media
		ids, err := s.store.ListMediaByCluster(r.Context(), uid, id)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		// 补 media 元数据
		medias := make([]map[string]any, 0, len(ids))
		for _, mid := range ids {
			if m, err := s.store.GetMedia(r.Context(), mid); err == nil && m != nil {
				medias = append(medias, mediaToMap(m))
			}
		}
		writeJSON(w, http.StatusOK, map[string]any{"media": medias, "total": len(medias)})
		return
	}
	// PUT /api/persons/{id} 命名
	if r.Method == http.MethodPut {
		var body struct{ Name string `json:"name"` }
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid body"})
			return
		}
		if err := s.store.RenamePersonCluster(r.Context(), uid, id, body.Name); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		return
	}
	writeJSON(w, http.StatusMethodNotAllowed, map[string]any{"error": "method not allowed"})
}

func (s *Server) handlePersonsRecluster(w http.ResponseWriter, r *http.Request) {
	uid := userIDFromContext(r.Context())
	if uid == "" || s.store == nil {
		writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "unauthorized"})
		return
	}
	// 清空旧聚类（保留命名由 ReclusterPersons 内部尽量保留）
	// 简化：直接重建。用户命名若丢失前端可重新命名。
	threshold := float32(0.82)
	if v := r.URL.Query().Get("threshold"); v != "" {
		var f float32
		if _, err := fmt.Sscanf(v, "%f", &f); err == nil && f > 0 {
			threshold = f
		}
	}
	n, err := s.ReclusterPersons(r.Context(), uid, threshold)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"clusters": n, "threshold": threshold})
}
