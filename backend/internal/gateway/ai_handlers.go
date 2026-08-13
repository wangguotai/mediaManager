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
	"sync/atomic"
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
	Vector   []float32 `json:"vector"`
	Dim      int       `json:"dim"`
	ModelVer string    `json:"model_ver"`
}

// captionResp 是 /caption 返回。
type captionResp struct {
	Caption  string   `json:"caption"`
	Scene    string   `json:"scene"`
	Objects  []string `json:"objects"`
	Colors   []string `json:"colors"`
	Mood     string   `json:"mood"`
	ModelVer string   `json:"model_ver"`
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
// embedCache 是用户级图像向量缓存（G：检索性能优化 + 为 ANN 铺路）：
// SearchSemantic 每次检索全量 LoadEmbeddings 浪费 IO，缓存后复用，
// 索引/删除后调 InvalidateEmbedCache(uid) 失效。
type AIIndexer struct {
	server *Server
	ai     *AIClient
	logf   func(format string, args ...any)

	mu      sync.Mutex
	running bool
	stopCh  chan struct{}

	cacheMu    sync.RWMutex
	embedCache map[string]map[string][]float32 // uid -> (mediaID -> vector)
}

// NewAIIndexer 创建索引器。logf 为空时用标准日志。
func (s *Server) NewAIIndexer() *AIIndexer {
	return &AIIndexer{
		server:     s,
		ai:         NewAIClient(aiFeatureSvcURL),
		logf:       func(format string, args ...any) { slog.Info("ai-indexer: "+format, args...) },
		stopCh:     make(chan struct{}),
		embedCache: make(map[string]map[string][]float32),
	}
}

// LoadEmbeddingsCached 返回某用户的全部向量，优先用进程内缓存。
// 缓存 miss 时从 store 加载并填入。索引/删除后应调 InvalidateEmbedCache。
func (a *AIIndexer) LoadEmbeddingsCached(ctx context.Context, uid string) (map[string][]float32, error) {
	a.cacheMu.RLock()
	if m, ok := a.embedCache[uid]; ok {
		a.cacheMu.RUnlock()
		return m, nil
	}
	a.cacheMu.RUnlock()
	m, err := a.server.store.LoadEmbeddings(ctx, uid)
	if err != nil {
		return nil, err
	}
	a.cacheMu.Lock()
	a.embedCache[uid] = m
	a.cacheMu.Unlock()
	return m, nil
}

// InvalidateEmbedCache 失效某用户（或全部，uid="" ）的向量缓存。
// 在 indexOne 成功落库后与 media 删除后调用。
func (a *AIIndexer) InvalidateEmbedCache(uid string) {
	a.cacheMu.Lock()
	defer a.cacheMu.Unlock()
	if uid == "" {
		a.embedCache = make(map[string]map[string][]float32)
		return
	}
	delete(a.embedCache, uid)
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
	// 启动后立即跑一轮。batch=50：单轮处理量，配合 4 并发，平衡吞吐与特征服务压力。
	a.indexOnce(context.Background(), 50)
	for {
		select {
		case <-a.stopCh:
			a.logf("stopped")
			return
		case <-t.C:
			a.indexOnce(context.Background(), 50)
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
// indexOnce 扫描全部用户的未索引 media，4 并发处理一批（D：批量并行优化）。
// 串行单张 ~1-2s/张，百 G 图库会积压；4 并发把吞吐提到 ~4x，且限制并发避免压垮
// 特征服务（CLIP 推理本就吃 CPU）。limit 控制单轮总量，processed 原子计数。
func (a *AIIndexer) indexOnce(ctx context.Context, limit int) int {
	if a.server.store == nil {
		return 0
	}
	users, err := a.server.store.ListUsers(ctx)
	if err != nil || len(users) == 0 {
		return 0
	}
	var processed atomic.Int32
	for _, u := range users {
		ids, err := a.server.store.ListUnindexedMedia(ctx, u.ID, limit)
		if err != nil || len(ids) == 0 {
			continue
		}
		// 4 并发 worker 池消费 ids
		const concurrency = 4
		jobs := make(chan string)
		var wg sync.WaitGroup
		for w := 0; w < concurrency; w++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for mid := range jobs {
					if err := a.indexOne(ctx, u.ID, mid); err != nil {
						a.logf("index %s err: %v", mid, err)
						continue
					}
					processed.Add(1)
				}
			}()
		}
		for _, mid := range ids {
			jobs <- mid
		}
		close(jobs)
		wg.Wait()
	}
	n := int(processed.Load())
	if n > 0 {
		a.logf("round processed %d (4-concurrency)", n)
	}
	return n
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
	// G：新向量落库，失效该用户缓存，下次检索重新加载（含本次）。
	a.InvalidateEmbedCache(uid)
	return nil
}

// ---- 检索 ----

// aiSearchResult 单条检索结果。
type aiSearchResult struct {
	Media   map[string]any `json:"media"`
	Score   float32        `json:"score"`
	Caption string         `json:"caption,omitempty"`
	Scene   string         `json:"scene,omitempty"`
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
	// G：优先用 aiIndexer 的进程内向量缓存，避免每次检索全量 LoadEmbeddings。
	// aiIndexer nil（store 未注入时不会到这）或缓存不可用降级到直接加载。
	var embeds map[string][]float32
	if s.aiIndexer != nil {
		embeds, err = s.aiIndexer.LoadEmbeddingsCached(ctx, uid)
	} else {
		embeds, err = s.store.LoadEmbeddings(ctx, uid)
	}
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
		// 人物词过滤在排序前做：若使用了 personClusterMedia 限制，仅收该 cluster 成员为候选。
		// 旧版在 fetchN=limit*2 窗口内过滤，大库下 cluster 成员在 top 窗口稀疏，会漏相关项/返回不足。
		if personClusterMedia != nil && !personClusterMedia[mid] {
			continue
		}
		cands = append(cands, cand{mid, storage.Cosine(qv, mv)})
	}
	// 纯语义分排序，用于裁剪候选窗口（fetchN）。真正的最终排序在算出混合分后做，
	// 避免硬过滤/时间近度/质量加成把高分项排到窗外的正确项前面。
	sort.Slice(cands, func(i, j int) bool { return cands[i].sem > cands[j].sem })

	// 取 top limit*2 候选（够过滤），补 metadata 做硬过滤与混合分。
	// 这里不再直接输出：先对窗口内每个候选算完整 finalScore，再按 finalScore 排序取 limit。
	fetchN := limit * 2
	if fetchN > len(cands) {
		fetchN = len(cands)
	}
	type scored struct {
		result aiSearchResult
		total  float32
	}
	scoreds := make([]scored, 0, fetchN)
	for i := 0; i < fetchN; i++ {
		mid := cands[i].mediaID
		m, err := s.store.GetMedia(ctx, mid)
		if err != nil || m == nil || m.UserID != uid {
			continue
		}
		// 过滤已软删除的媒体：embedding 残留不应让删除图出现在检索结果。
		if m.Deleted {
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
		// 语义+NLP 关键词加成（caption/manual_note 含查询词）。
		// 门槛：仅当语义分 sem ≥ 0.22 才加 boost，且 boost 不超过 sem 的 30%。
		// 否则语义都低的图（如被 zero-shot 误判 caption 的色块）会靠词命中跳到
		// 真正相关图前面（v2 阈值优化时发现：test_photo caption 含"海边"后被 boost 超 beach）。
		boost := float32(0)
		sem := cands[i].sem
		if ann != nil && sem >= 0.22 {
			rawBoost := float32(0)
			// caption/manual_note 词级命中 query（phrase 即自然语言长句，caption 是短描述，
			// 整串 Contains 几乎永不命中，是 v2 引入的逻辑 bug）。
			// 按非中文标点/空白切分，任一词（≥2 字）出现在 query 中即命中。
			qLower := strings.ToLower(query)
			captionHit := wordInQuery(ann.Caption, qLower) || wordInQuery(ann.ManualNote, qLower)
			if captionHit {
				rawBoost += 0.15
			}
			for _, o := range ann.Objects {
				if o != "" && strings.Contains(query, o) {
					rawBoost += 0.1
				}
			}
			// boost 上限 = sem × 30%，确保语义仍是主导排序信号
			cap := sem * 0.30
			if rawBoost > cap {
				rawBoost = cap
			}
			boost = rawBoost
		}
		// PRD-v12 §5 混合排序:语义为主 + 时间近度(0~0.2) + 质量(0~0.1)。
		// 时间近度:与当前时刻差归一化(越近越高),1 年内线性衰减。
		// 质量:size 归一化(大图通常清晰度高,上限 5MB)。
		// 权重为 sem 同量级的相对加成(非 PRD 理想 0.7/0.2/0.1 绝对值,因 sem 本身在 0.2~0.5)。
		timeScore := recencyScore(m)
		qualityScore := float32(0)
		if m.Size > 0 {
			qualityScore = 0.1 * float32(m.Size) / float32(5*1024*1024)
			if qualityScore > 0.1 {
				qualityScore = 0.1
			}
		}
		mediaMap := mediaToMap(m)
		mediaMap["thumbnail_url"] = "/api/media/stream/" + mid
		scoreds = append(scoreds, scored{
			result: aiSearchResult{
				Media:   mediaMap,
				Score:   cands[i].sem + boost + timeScore + qualityScore,
				Caption: captionOf(ann),
				Scene:   sceneOf(ann),
			},
			total: cands[i].sem + boost + timeScore + qualityScore,
		})
	}
	// 最终排序用的是完整混合分（sem+boost+time+quality），而不是纯 sem。
	// 否则高分（新上大图时间近+质量高）项会被纯语义分高的旧合成图压到后面，
	// 前端看到"分数更高却排更后"的错乱。
	sort.SliceStable(scoreds, func(i, j int) bool { return scoreds[i].total > scoreds[j].total })
	out := make([]aiSearchResult, 0, limit)
	for i := 0; i < len(scoreds) && len(out) < limit; i++ {
		out = append(out, scoreds[i].result)
	}
	return out, nil
}

// recencyScore 时间近度评分（PRD-v12 §5 混合排序的 0.2 权重）。
// 取 media 拍摄时间（taken_at 优先，否则 created_at）与 now 之差，1 年内线性衰减到 0。
// 超 1 年或无时间返回 0；最新（近 0 天）返回 0.2。
func recencyScore(m *storage.Media) float32 {
	var t time.Time
	if m.TakenAt > 0 {
		t = time.UnixMilli(m.TakenAt)
	} else {
		t = m.CreatedAt
	}
	if t.IsZero() {
		return 0
	}
	d := time.Since(t)
	if d < 0 {
		return 0.2 // 未来时间（时钟偏差）按最新处理
	}
	const year = 365 * 24 * time.Hour
	if d >= year {
		return 0
	}
	// 线性：0 天→0.2，365 天→0
	return 0.2 * float32((year-d).Seconds()/year.Seconds())
}

// wordInQuery 判断 text 中是否有词（≥2 字）出现在 queryLower 中。
// 用于 caption/manual_note 词级命中查询（整串 Contains 对长句查询几乎不命中）。
// text 按中英文标点/空白切分；中文词≥2字、英文词≥2字符才参与，避免单字噪声。
func wordInQuery(text, queryLower string) bool {
	if text == "" || queryLower == "" {
		return false
	}
	fields := strings.FieldsFunc(text, func(r rune) bool {
		return r == ' ' || r == ',' || r == '，' || r == '.' || r == '。' ||
			r == '/' || r == '\\' || r == '-' || r == '_' || r == '·'
	})
	for _, w := range fields {
		wl := strings.ToLower(w)
		if len([]rune(wl)) >= 2 && strings.Contains(queryLower, wl) {
			return true
		}
	}
	return false
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
		"id":         m.ID,
		"filename":   m.Filename,
		"type":       m.Type,
		"size":       m.Size,
		"width":      m.Width,
		"height":     m.Height,
		"taken_at":   m.TakenAt,
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
	// UX 提示：结果空时不直接甩"未找到"，给出可行动的 hint。
	// ① 库根本未索引（Indexed=0）→ 建议先索引；② 特征服务不可达 → 告知。
	// 有结果或已索引但确实无匹配时 hint=""，前端不显示。
	hint := ""
	if len(results) == 0 {
		prog, _ := s.store.AIProgress(r.Context(), uid)
		hc := &http.Client{Timeout: 3 * time.Second}
		svcOK := false
		if resp, herr := hc.Get(aiFeatureSvcURL + "/health"); herr == nil {
			svcOK = resp.StatusCode == 200
			resp.Body.Close()
		}
		switch {
		case prog != nil && prog.Indexed == 0:
			hint = "媒体库尚未进行 AI 索引，先到 AI 智能管理里触发生成，或稍等后台索引完成"
		case !svcOK:
			hint = "AI 特征服务未就绪，暂时无法语义检索"
		default:
			hint = "没有匹配的照片，试试更具体的描述（如服装/场景/颜色）"
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"results":      results,
		"total":        len(results),
		"query":        q,
		"parsed_query": parseSmartQuery(q),
		"hint":         hint,
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
		"progress":    prog,
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
	limit := 30
	if v := r.URL.Query().Get("limit"); v != "" {
		if n, err := parseIntSafe(v); err == nil && n > 0 {
			limit = n
		}
	}
	// 同步索引指定用户。J：4 并发池（与后台 worker 一致），避免大库同步索引串行过慢。
	a := s.NewAIIndexer()
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Minute)
	defer cancel()
	ids, err := s.store.ListUnindexedMedia(ctx, uid, limit)
	if err != nil {
		writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
		return
	}
	var processed atomic.Int32
	const concurrency = 4
	jobs := make(chan string)
	var wg sync.WaitGroup
	for w := 0; w < concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for mid := range jobs {
				if err := a.indexOne(ctx, uid, mid); err != nil {
					continue
				}
				processed.Add(1)
			}
		}()
	}
	for _, mid := range ids {
		jobs <- mid
	}
	close(jobs)
	wg.Wait()
	writeJSON(w, http.StatusOK, map[string]any{
		"processed":  int(processed.Load()),
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
		"albums": scenes,
		"total":  len(scenes),
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
		var body struct {
			Name string `json:"name"`
		}
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
	// 默认阈值 0.70（用真实手机照片实测调优：0.82 过严导致相似办公/室内照片碎片化
	// 各成一簇；0.70 能把 4 张相似室内照片正确聚一簇。CLIP 整图向量相似度分布更密，
	// 阈值应低于真人脸向量聚类所需的 0.5~0.6 场景。接 face_vector 时改用人脸空间阈值。）
	threshold := float32(0.70)
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
