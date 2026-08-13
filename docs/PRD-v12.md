# PRD-v12：AI 视觉检索与自动注解

> 创建时间：2026-08-12
> 基线：PRD-v8~v11 全部完成（v11 §5 曾明确排除 AI 人脸/场景识别）
> 突破点：本次**正式引入 AI 视觉理解**，打通"自然语言 → 语义 → 媒体"的检索闭环。
> 这是用户明确升级诉求：手机上百 G 图片（尤其女性用户）需要智能归类、检索、记录长相。

## 0. 北极星

> 用户说"找我穿汉服的照片"——系统能直接返回。用户说"那张在海边大笑的"——也能返回。
> 不靠文件名、不靠手动打标签，靠**视觉语义**。

## 1. 能力分层

| 层 | 能力 | 依赖 | 优先级 |
|----|------|------|--------|
| L0 | 图像 caption + CLIP 向量 | Python 特征服务 | P0 |
| L0 | 向量检索端点 | Go + 向量存储 | P0 |
| L1 | 自动场景/物体分类相册 | L0 | P0 |
| L1 | 自然语言图文检索（汉服等） | L0 | P0 |
| L2 | 人物向量聚类 + 长相记忆 | 人脸向量 | P1 |
| L2 | "我/妈妈" 命名 + 联合检索 | L0+L2 | P1 |
| L3 | 自动注解（在XX/做XX/关于XX） | L0 | P1 |
| L3 | 注解编辑 + 照片故事增强 | L0 | P2 |

## 2. 架构

```
手机(compose) ──HTTP──> Go backend(8080)
                          │
                          ├─ 索引管线: 扫描未索引media → 调特征服务 → 落库向量/注解
                          ├─ 检索: ai-search?q= → 文本向量化 → 余弦top-k → 混合排序
                          └─ HTTP ──> Python 特征服务(8095)
                                       ├─ /caption   (图像→中文描述)
                                       ├─ /embed     (图像→CLIP向量)
                                       ├─ /text-embed (文本→CLIP向量)
                                       ├─ /classify  (场景/物体标签)
                                       └─ /face      (人脸向量,可选)
```

### 2.1 向量存储方案（不引入新依赖）
- 现有 DB 是 modernc.org/sqlite（纯 Go），**不引入 sqlite-vss**（需 CGO）。
- 向量存 `media_embeddings` 表的 BLOB（[]float32 序列化）。
- 检索：**Go 内存里加载该用户全部向量**（单用户数百~数千张，每向量 512×4B=2KB，10000 张=20MB，可承受）做暴力余弦。
- 超过 10 万张时再上 ANN（hnsw-go），留接口位。

### 2.2 特征服务降级策略
- 优先：调用 gateway 提供的多模态 LLM（caption + 标签）。
- 兜底：纯本地 CLIP（`sentence-transformers`/`open_clip`），离线可跑，仅向量无 caption。
- 配置：`AI_BACKEND=auto|gateway|local|disabled`。

## 3. 数据模型

### 3.1 media_annotations
```sql
CREATE TABLE media_annotations (
  media_id    TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL,
  caption     TEXT NOT NULL DEFAULT '',   -- AI生成中文描述
  scene       TEXT NOT NULL DEFAULT '',   -- 主场景: 海边/室内/夜景...
  objects     TEXT NOT NULL DEFAULT '[]', -- JSON: ["汉服","猫","蛋糕"]
  colors      TEXT NOT NULL DEFAULT '[]', -- 主色调
  mood        TEXT NOT NULL DEFAULT '',   -- 情绪: 欢快/宁静/...
  manual_note TEXT NOT NULL DEFAULT '',   -- 用户编辑的注解
  model_ver   TEXT NOT NULL DEFAULT '',   -- 生成模型版本(失效用)
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL,
  FOREIGN KEY (media_id) REFERENCES media(id) ON DELETE CASCADE
);
```

### 3.2 media_embeddings
```sql
CREATE TABLE media_embeddings (
  media_id    TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL,
  vector      BLOB NOT NULL,    -- 序列化 []float32
  dim         INTEGER NOT NULL,
  model_ver   TEXT NOT NULL,
  created_at  TEXT NOT NULL,
  FOREIGN KEY (media_id) REFERENCES media(id) ON DELETE CASCADE
);
CREATE INDEX idx_embed_user ON media_embeddings(user_id);
```

### 3.3 person_clusters + media_persons（人物长相记忆）
```sql
CREATE TABLE person_clusters (
  id         TEXT PRIMARY KEY,
  user_id    TEXT NOT NULL,
  name       TEXT NOT NULL DEFAULT '',  -- 用户命名: 我/妈妈/小李
  avatar_media_id TEXT,                 -- 代表脸
  face_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE media_persons (
  id           TEXT PRIMARY KEY,
  media_id     TEXT NOT NULL,
  user_id      TEXT NOT NULL,
  cluster_id   TEXT NOT NULL,
  bbox         TEXT NOT NULL DEFAULT '', -- JSON [x,y,w,h] 归一化
  face_vector  BLOB,
  created_at   TEXT NOT NULL,
  FOREIGN KEY (media_id) REFERENCES media(id) ON DELETE CASCADE
);
CREATE INDEX idx_persons_cluster ON media_persons(user_id, cluster_id);
```

## 4. 端点

| 方法 路径 | 作用 |
|-----------|------|
| POST /api/ai/index?limit=N | 触发索引管线（提取并落库 N 张未索引） |
| GET /api/ai/status | 索引进度（已索引/总数） |
| GET /api/ai/search?q=&limit= | 自然语言检索（语义 top-k） |
| GET /api/ai/albums | 自动分类相册（按 scene/objects 聚合） |
| GET /api/ai/annotation/{media_id} | 取单张注解 |
| PUT /api/ai/annotation/{media_id} | 编辑注解（manual_note） |
| GET /api/persons | 人物聚类列表 |
| PUT /api/persons/{id} | 给聚类命名 |
| GET /api/persons/{id}/media | 该人物全部照片 |
| POST /api/persons/recluster | 重算聚类 |

## 5. 检索算法

`ai-search?q=` 流程：
1. q 文本 → CLIP 文本向量 `qt`
2. 加载用户全部图像向量 `M`，算余弦 `cos(qt, m_i)`
3. 若 q 含"我/他/妈妈"等人物词 → 联合过滤：先筛出该人物 cluster 的 media，再做语义排序
4. 若 q 含时间/类型词 → 复用 `parseSmartQuery` 做硬过滤
5. top-k（默认 50）混合排序：`score = 0.7*语义 + 0.2*时间近度 + 0.1*质量`
6. 返回 results + parsed_query + semantic_scores

### 5.1 汉服等垂直场景
内置扩展词库 `vertical-scene.json`：
- 汉服：{"triggers":["汉服","古装","国风","和服","韩服"], "boost_terms":["hanfu","traditional chinese clothing"]}
- 婚纱、cosplay、毕业、旅行... 同理
- boost_terms 用于 CLIP 文本端 prompt 增强，提升召回。

## 6. 索引管线

后台 worker（单 goroutine，避免压垮特征服务）：
- 每 5s 查 `media` 中未在 `media_embeddings` 的行
- 取 Top N，串行调特征服务（/embed + /caption + /classify）
- 失败标记 `pending`，下次重试，不阻塞
- 索引进度写 `ai_index_progress` 表（或用 annotation/embedding 行数比）

## 7. 非目标（本轮）
- 不做端到端加密媒体内容分析（明文索引，单用户自托管场景可接受）
- 不做云端多人共享索引
- 不做人名自动识别（用户手动命名 cluster）

## 8. 验收
- 手机端输入"找汉服"能返回穿汉服照片（若有该类内容）
- 自动相册 Tab 出现场景分类
- 人物页出现聚类，可命名
- 注解可见可编辑
- 索引进度可视化

## 9. 执行顺序（监管者按此推进）
1. PRD-v12（本文件）✓
2. DB schema migration + repository
3. Python 特征服务骨架（/health, /embed, /text-embed, /caption, /classify）
4. Go 索引管线 + /ai/index + /ai/search + /ai/status
5. 自动相册 + 注解端点
6. 人物聚类 + 联合检索
7. 前端 UI + APK 实测
8. 全链路 QA + 记忆归档

---

## §9 UI 对齐一刻相册 5 功能 Tab 对照（真机+qwen+apktool 逆向）

> 2026-08-13 持续迁移。一刻相册 5 功能（照片/相册/拍摄/查找/创意）逐屏对齐。
> 依据：真机截图 + qwen 结构化 spec + apktool 逆向 APK 资源。

| 一刻相册 Tab | 我们现状 | 还原状态 |
|---|---|---|
| **照片**(时间线+年份) | 时间线网格+功能卡(备份/清理)+活动Banner+按时间\|相册切换 | ✅ 已对齐 |
| **相册**(智能搜图成册+云空间相册) | 智能搜图成册浅蓝卡+云空间相册列表+人物+筛选+活动Banner+一键创建(真实图标) | ✅ 已对齐 |
| **拍摄**(真相机) | 居中凸起渐变按钮→ACTION_IMAGE_CAPTURE 拉起系统相机→上传 | ✅ 已对齐 |
| **查找**(智能聚合) | 场景/人物/足迹(点亮地图卡)/类型(截图视频动态)分区+AI语义搜索 | ✅ 已对齐 |
| **创意**(工具网格) | 活动 Banner+2行4列8工具(时光足迹/美颜/AI消除等)+AI改图/导入双主按钮 | ✅ 已对齐 |

### 图标还原
- 底部 5 tab：apktool 逆向真实 webp（checked/unchecked 两态）+ tint 选中蓝/未选灰 ✅
- 照片页搜索入口：真实放大镜图标 ✅
- 相册创建入口：真实蓝圆白加号图标 ✅
- 内部工具/分区：emoji 过渡（APK 无整齐对应小图标集）

### 设计稿工具链（screenshot-to-spec skill）
- `scripts/doc.py`：qwen 多模态产 Figma 式 JSON spec（含 audit 审计+补问循环）
- `scripts/render_spec.py`：spec→HTML→Chrome 截图盲测基线
- `scripts/extract_icons.py`：单独识别图标产出 iconRef+shape
- `scripts/pixel_sampler.py`：截图像素采样取色
- apktool 2.9.3（代理下载）逆向 APK 恢复真实资源名

### 已知遗留
- AiTabs 引用 Compose 资源 Res 需"同包函数封装"绕过（star=Int 冲突谜题）
- 盲还原度 ~40-50%（结构 70%/色值 20%/间距 40%，图标 iconRef 仍是最大失真）
- 拍完相机上传闭环需真人按键验证
