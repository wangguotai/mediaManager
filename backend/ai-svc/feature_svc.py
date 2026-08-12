#!/usr/bin/env python3
"""
Media Manager AI 特征服务 (PRD-v12 §2)

职责：
  /health        健康检查 + 能力自报
  /embed         图像 → 视觉向量 (CLIP 优先；无 CLIP 时降级到感知哈希向量)
  /text-embed    文本 → 文本向量 (与图像同一空间，用于检索)
  /caption       图像 → 中文描述 + 场景/物体/色调/情绪 (多模态 LLM)
  /classify      图像 → 分类标签 (复用 caption 结果或词库匹配)
  /face          图像 → 人脸向量 (可选，需 facerec 库；缺失时返回空)

降级策略 (AI_BACKEND):
  - gateway: 调多模态 LLM (caption/classify)；向量用 CLIP 或哈希
  - local:   纯本地：CLIP 优先，否则哈希向量；caption 用规则
  - auto:    有 key 用 gateway，否则 local
  - disabled: 全部返回空/降级

向量空间统一约定：无论 CLIP(512) 还是哈希(64)，图像端与文本端必须同构，
保证 Go 端 Cosine 计算一致。CLIP 模型名记录在 model_ver，不同模型不混算。
"""
import base64
import hashlib
import io
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
from PIL import Image

# ---- 配置 ----
PORT = int(os.environ.get("AI_SVC_PORT", "8095"))
AI_BACKEND = os.environ.get("AI_BACKEND", "auto")  # auto|gateway|local|disabled
# 多模态 gateway（复用 Claude Code 的 ANTHROPIC_BASE_URL 体系）
GATEWAY_BASE = os.environ.get("AI_GATEWAY_BASE", os.environ.get("ANTHROPIC_BASE_URL", ""))
GATEWAY_MODEL = os.environ.get("AI_GATEWAY_MODEL", "claude-sonnet-5-20250929")  # 视觉模型
GATEWAY_KEY = os.environ.get("AI_GATEWAY_KEY", os.environ.get("ANTHROPIC_API_KEY", ""))
CLIP_MODEL_NAME = os.environ.get("AI_CLIP_MODEL", "ViT-B-32")  # open_clip 名

# ---- CLIP 懒加载（仅在使用时下载，避免启动卡死）----
_CLIP_LOCK = threading.Lock()
_CLIP_STATE = {"model": None, "preprocess": None, "tokenizer": None,
               "ready": False, "err": None, "mode": "clip"}

def _try_load_clip():
    """尝试加载 open_clip。失败则切换到哈希向量模式。"""
    if _CLIP_STATE["ready"] or _CLIP_STATE["err"]:
        return
    with _CLIP_LOCK:
        if _CLIP_STATE["ready"] or _CLIP_STATE["err"]:
            return
        try:
            import open_clip
            import torch
            # pretrained='openai' 让 open_clip 下载训过权的 ViT-B-32（与 OpenAI CLIP 同空间）。
            # 不带 pretrained 会随机初始化权重，向量无语义意义，检索失效。
            # force_quick_gelu=True：OpenAI 原版 CLIP 用 QuickGELU 激活，open_clip 默认
            # GELU，不强制会导致权重与配置不匹配（mismatch warning），向量质量下降。
            model, _, preprocess = open_clip.create_model_and_transforms(
                CLIP_MODEL_NAME, pretrained="openai", force_quick_gelu=True)
            tokenizer = open_clip.get_tokenizer(CLIP_MODEL_NAME)
            model.eval()
            _CLIP_STATE.update(model=model, preprocess=preprocess,
                               tokenizer=tokenizer, ready=True, mode="clip")
            print(f"[clip] loaded {CLIP_MODEL_NAME}", flush=True)
        except Exception as e:
            _CLIP_STATE["err"] = str(e)
            _CLIP_STATE["mode"] = "hash"
            print(f"[clip] unavailable, fallback to hash vectors: {e}", flush=True)

def _clip_image_vec(img: Image.Image):
    import torch
    with torch.no_grad():
        x = _CLIP_STATE["preprocess"](img.convert("RGB")).unsqueeze(0)
        feat = _CLIP_STATE["model"].encode_image(x)
        feat = feat / feat.norm(dim=-1, keepdim=True)
        return feat[0].cpu().numpy().astype(np.float32).tolist()

def _clip_text_vec(text: str):
    import torch
    with torch.no_grad():
        tok = _CLIP_STATE["tokenizer"]([text])
        feat = _CLIP_STATE["model"].encode_text(tok)
        feat = feat / feat.norm(dim=-1, keepdim=True)
        return feat[0].cpu().numpy().astype(np.float32).tolist()

# ---- CLIP zero-shot 分类（CLIP 就绪时，用图-文相似度给图打标签）----
# 这是 LLM caption 不可用时的核心替代：预置中文场景/物体/服饰标签库（含汉服等
# 垂直场景），对每张图算 CLIP(图)·CLIP(标签) 余弦，取 top-k 作为该图的 scene/objects。
# 完全离线、不依赖任何 key，且与检索同一 CLIP 空间，召回天然对齐。
ZERO_SHOT_TAGS = {
    "scene": ["海边", "沙滩", "山林", "草地", "城市街道", "室内", "夜景", "雪景",
              "天空", "日落", "日出", "办公室", "餐厅", "公园", "建筑", "河湖"],
    "subject": ["人", "女孩", "男孩", "儿童", "老人", "合影", "自拍", "动物", "猫",
                "狗", "鸟", "食物", "蛋糕", "饮品", "车", "花", "植物", "家具"],
    "clothing": ["汉服", "古装", "和服", "婚纱", "礼服", "日常服装", "运动装",
                 "泳装", "制服", "冬装外套", "裙子", "帽子"],
    "activity": ["旅行", "聚餐", "运动", "表演", "婚礼", "毕业", "工作", "休息",
                 "购物", "庆祝"],
    "mood": ["欢快", "宁静", "温馨", "浪漫", "冷清", "热闹", "神秘", "清新"],
}

# 英文 prompt 版（CLIP 对英文 prompt 更准，映射回中文标签）
_ZH_EN_PROMPT = {
    "海边": "a photo at the seaside, beach and ocean",
    "沙滩": "a photo on a sandy beach",
    "山林": "a photo in mountains and forest",
    "草地": "a photo on green grassland",
    "城市街道": "a photo of city street",
    "室内": "a photo taken indoors",
    "夜景": "a night scene photo, dark with lights",
    "雪景": "a photo of snow scene",
    "天空": "a photo of sky",
    "日落": "a photo of sunset",
    "日出": "a photo of sunrise",
    "办公室": "a photo in an office",
    "餐厅": "a photo in a restaurant",
    "公园": "a photo in a park",
    "建筑": "a photo of buildings",
    "河湖": "a photo of river or lake",
    "人": "a photo of a person",
    "女孩": "a photo of a girl",
    "男孩": "a photo of a boy",
    "儿童": "a photo of a child",
    "老人": "a photo of an elderly person",
    "合影": "a group photo of people",
    "自拍": "a selfie photo",
    "动物": "a photo of an animal",
    "猫": "a photo of a cat",
    "狗": "a photo of a dog",
    "鸟": "a photo of a bird",
    "食物": "a photo of food",
    "蛋糕": "a photo of a cake",
    "饮品": "a photo of a drink",
    "车": "a photo of a car",
    "花": "a photo of flowers",
    "植物": "a photo of plants",
    "家具": "a photo of furniture",
    "汉服": "a photo of someone wearing hanfu, traditional Chinese clothing",
    "古装": "a photo in ancient traditional costume",
    "和服": "a photo of someone wearing kimono",
    "婚纱": "a photo of a wedding dress",
    "礼服": "a photo in formal dress",
    "日常服装": "a photo in casual everyday clothing",
    "运动装": "a photo in sportswear",
    "泳装": "a photo in swimwear",
    "制服": "a photo in uniform",
    "冬装外套": "a photo in winter coat",
    "裙子": "a photo of someone wearing a skirt or dress",
    "帽子": "a photo wearing a hat",
    "旅行": "a photo of traveling, sightseeing",
    "聚餐": "a photo of people dining together",
    "运动": "a photo of sports activity",
    "表演": "a photo of a performance",
    "婚礼": "a photo of a wedding",
    "毕业": "a graduation photo",
    "工作": "a photo of working",
    "休息": "a photo of relaxing",
    "购物": "a photo of shopping",
    "庆祝": "a photo of celebration, party",
    "欢快": "a cheerful joyful photo",
    "宁静": "a peaceful quiet photo",
    "温馨": "a warm cozy photo",
    "浪漫": "a romantic photo",
    "冷清": "a cold lonely photo",
    "热闹": "a lively bustling photo",
    "神秘": "a mysterious photo",
    "清新": "a fresh bright photo",
}

def _clip_zero_shot_tags(img: Image.Image, top_k=6):
    """对图算 CLIP zero-shot 分类。返回 dict: scene/subject/clothing/activity/mood 各 top-1~2。
    CLIP 不可用时返回空 dict。"""
    if not _CLIP_STATE["ready"]:
        return {}
    import torch
    with torch.no_grad():
        x = _CLIP_STATE["preprocess"](img.convert("RGB")).unsqueeze(0)
        img_feat = _CLIP_STATE["model"].encode_image(x)
        img_feat = img_feat / img_feat.norm(dim=-1, keepdim=True)
    result = {}
    all_hits = []
    for category, tags in ZERO_SHOT_TAGS.items():
        prompts = [_ZH_EN_PROMPT.get(t, t) for t in tags]
        toks = _CLIP_STATE["tokenizer"](prompts)
        with torch.no_grad():
            txt_feat = _CLIP_STATE["model"].encode_text(toks)
            txt_feat = txt_feat / txt_feat.norm(dim=-1, keepdim=True)
            sims = (img_feat @ txt_feat.T)[0].cpu().numpy()
        # 取该类 top-2，相似度 >0.2 才采纳（CLIP 阈值经验值）
        idx = sims.argsort()[::-1][:2]
        picked = []
        for i in idx:
            if sims[i] > 0.20:
                picked.append((tags[i], float(sims[i])))
                all_hits.append((tags[i], float(sims[i]), category))
        if picked:
            result[category] = picked
    # objects = 跨类合并 top-k
    all_hits.sort(key=lambda x: -x[1])
    result["objects_raw"] = [(t, s) for t, s, _ in all_hits[:top_k]]
    return result

# ---- 哈希向量降级（CLIP 不可用时）----
# 把图像/文本映射到固定 64 维向量。同一空间，保证图文可比。
# 这只是"视觉相似度 + 关键词命中"的近似，质量远不如 CLIP，但保证系统可跑。
_HASH_DIM = 64

def _hash_image_vec(img: Image.Image):
    """用感知哈希 + 颜色直方图拼成 64 维。同图相似度 ≈ 高。"""
    small = img.convert("RGB").resize((32, 32))
    arr = np.asarray(small, dtype=np.float32) / 255.0
    # 32x32 三通道 → 按通道降采样成 64 维：每通道取 8x4=32 个块均值，三通道取主色统计
    gray = arr.mean(axis=2)
    blocks = np.mean(gray.reshape(8, 4, 8, 4), axis=(2, 3)).flatten()  # 32
    # 颜色统计：R/G/B 各 8 bin 直方图 → 24 维
    hist = []
    for c in range(3):
        h, _ = np.histogram(arr[:, :, c], bins=8, range=(0, 1))
        hist.extend(h / (arr.shape[0] * arr.shape[1]))
    # 频域方向：DCT 低频 8 维
    dct = np.abs(np.fft.fft2(gray)[:2, :4].flatten())[:8]
    v = np.concatenate([blocks, np.array(hist, dtype=np.float32),
                        dct.astype(np.float32)])
    # 补足/截断到 64
    if len(v) < _HASH_DIM:
        v = np.pad(v, (0, _HASH_DIM - len(v)))
    v = v[:_HASH_DIM]
    n = np.linalg.norm(v) + 1e-9
    return (v / n).astype(np.float32).tolist()

# 用确定性的文本哈希把关键词映射到 64 维同一空间（与图像哈希不强对齐，
# 但图文检索靠"物体词命中"维度重叠产生信号，作为兜底可接受）。
_ZH_KEYWORD_DIM_MAP = {
    "汉服": 0, "古装": 0, "国风": 0, "传统": 1, "海边": 2, "海": 2, "沙滩": 3,
    "山": 4, "夜景": 5, "室内": 6, "室外": 7, "雪": 8, "花": 9, "猫": 10, "狗": 11,
    "人": 12, "女孩": 13, "男孩": 14, "儿童": 15, "食物": 16, "美食": 16, "蛋糕": 17,
    "婚纱": 18, "毕业": 19, "旅行": 20, "运动": 21, "车": 22, "建筑": 23, "天空": 24,
    "日落": 25, "日出": 25, "夜景": 5, "合影": 26, "自拍": 27, "证件照": 28,
    "汉服": 0, "红色": 29, "蓝色": 30, "绿色": 31, "黄色": 32, "白色": 33, "黑色": 34,
    "大笑": 35, "微笑": 36, "哭": 37, "baby": 15,
}

def _hash_text_vec(text: str):
    """文本向量：关键词命中维度 + 词哈希散列到剩余维度。"""
    v = np.zeros(_HASH_DIM, dtype=np.float32)
    text_l = text.lower()
    for kw, dim in _ZH_KEYWORD_DIM_MAP.items():
        if kw in text or kw.lower() in text_l:
            v[dim] += 1.0
    # 额外用词的 hash 散列填充未命中维度，保留独特性
    for tok in text.replace(",", " ").replace("，", " ").split():
        h = int(hashlib.md5(tok.encode("utf-8")).hexdigest()[:8], 16)
        v[h % _HASH_DIM] += 0.5
    n = np.linalg.norm(v) + 1e-9
    return (v / n).astype(np.float32).tolist()

# ---- 多模态 LLM (caption/classify) ----
def _gateway_available():
    return AI_BACKEND in ("gateway", "auto") and bool(GATEWAY_BASE)

def _llm_describe(img_bytes: bytes, mime: str):
    """调多模态 LLM 生成中文描述 + 结构化标签。返回 dict 或 None。"""
    if not _gateway_available():
        return None
    try:
        import requests
        b64 = base64.b64encode(img_bytes).decode()
        media_type = mime or "image/jpeg"
        prompt = ("仔细看这张照片，用中文返回严格 JSON（不要任何多余文字）：\n"
                  '{"caption":"一句中文描述，包含人物外观/动作/服饰/场景/大概时间",'
                  '"scene":"主场景词(海边/室内/夜景/雪地/户外/城市/山林等)",'
                  '"objects":["图中可见物体/服饰关键词，最多6个"],'
                  '"colors":["主色调2-3个"],'
                  '"mood":"情绪词(欢快/宁静/温馨/冷清/热闹等)"}')
        body = {
            "model": GATEWAY_MODEL,
            "max_tokens": 400,
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "image", "source": {"type": "base64",
                        "media_type": media_type, "data": b64}},
                    {"type": "text", "text": prompt},
                ],
            }],
        }
        headers = {"content-type": "application/json"}
        if GATEWAY_KEY:
            headers["x-api-key"] = GATEWAY_KEY
            headers["anthropic-version"] = "2023-06-01"
        url = GATEWAY_BASE.rstrip("/") + "/v1/messages"
        r = requests.post(url, headers=headers, json=body, timeout=60)
        if r.status_code != 200:
            return {"_error": f"gateway {r.status_code}: {r.text[:200]}"}
        data = r.json()
        text_out = "".join(blk.get("text", "") for blk in
                           data.get("content", []) if blk.get("type") == "text")
        # 抽取 JSON
        s = text_out.find("{"); e = text_out.rfind("}")
        if s < 0 or e <= s:
            return {"_error": "no json", "raw": text_out[:200]}
        return json.loads(text_out[s:e+1])
    except Exception as ex:
        return {"_error": str(ex)}

# ---- HTTP 服务 ----
def _read_image(body: bytes) -> Image.Image:
    return Image.open(io.BytesIO(body))

class Handler(BaseHTTPRequestHandler):
    def _send(self, code, obj):
        b = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def _body(self):
        n = int(self.headers.get("Content-Length", "0"))
        return self.rfile.read(n) if n else b""

    def log_message(self, *a):
        pass  # 静默（Go 端记录日志）

    def do_GET(self):
        if self.path == "/health":
            _try_load_clip()
            self._send(200, {
                "status": "ok",
                "backend": AI_BACKEND,
                "clip": _CLIP_STATE["ready"],
                "vector_mode": _CLIP_STATE["mode"],
                "vector_dim": 512 if _CLIP_STATE["ready"] else _HASH_DIM,
                "model_ver": (CLIP_MODEL_NAME if _CLIP_STATE["ready"]
                              else f"hash{_HASH_DIM}"),
                "gateway": _gateway_available(),
                "ts": int(time.time()),
            })
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        try:
            body = self._body()
            if self.path.startswith("/embed"):
                self._handle_embed(body)
            elif self.path.startswith("/text-embed"):
                self._handle_text_embed(body)
            elif self.path.startswith("/caption"):
                self._handle_caption(body)
            elif self.path.startswith("/classify"):
                self._handle_classify(body)
            elif self.path.startswith("/face"):
                self._send(200, {"faces": [], "model": "unavailable"})
            else:
                self._send(404, {"error": "not found"})
        except Exception as ex:
            self._send(500, {"error": str(ex)})

    def _handle_embed(self, body):
        # body: {"image": <b64>, "mime": "..."} 或 raw image bytes (Content-Type image/*)
        ct = self.headers.get("Content-Type", "")
        img = None
        if ct.startswith("image/"):
            img = _read_image(body)
        else:
            payload = json.loads(body.decode("utf-8"))
            raw = base64.b64decode(payload.get("image", ""))
            img = _read_image(raw)
        _try_load_clip()
        if _CLIP_STATE["ready"]:
            vec = _clip_image_vec(img)
            model = CLIP_MODEL_NAME
        else:
            vec = _hash_image_vec(img)
            model = f"hash{_HASH_DIM}"
        self._send(200, {"vector": vec, "dim": len(vec), "model_ver": model})

    def _handle_text_embed(self, body):
        payload = json.loads(body.decode("utf-8"))
        text = payload.get("text", "")
        _try_load_clip()
        if _CLIP_STATE["ready"]:
            vec = _clip_text_vec(text)
            model = CLIP_MODEL_NAME
        else:
            vec = _hash_text_vec(text)
            model = f"hash{_HASH_DIM}"
        self._send(200, {"vector": vec, "dim": len(vec), "model_ver": model})

    def _handle_caption(self, body):
        ct = self.headers.get("Content-Type", "")
        if ct.startswith("image/"):
            img_bytes = body
            mime = ct
        else:
            payload = json.loads(body.decode("utf-8"))
            img_bytes = base64.b64decode(payload.get("image", ""))
            mime = payload.get("mime", "image/jpeg")
        img = _read_image(img_bytes)
        # 优先：gateway LLM（若可用且 key 已配置）
        if _gateway_available() and GATEWAY_KEY:
            res = _llm_describe(img_bytes, mime)
            if res and "_error" not in res:
                self._send(200, {**res, "model_ver": f"llm:{GATEWAY_MODEL}"})
                return
        # 次优：CLIP zero-shot 分类（离线，与检索同空间，质量稳定）
        if _CLIP_STATE["ready"]:
            zs = _clip_zero_shot_tags(img)
            scene = zs.get("scene", [([""])[0] if not zs.get("scene") else zs["scene"][0][0]] )[0] if zs.get("scene") else ""
            scene = zs["scene"][0][0] if zs.get("scene") else _guess_scene(img)
            mood = zs["mood"][0][0] if zs.get("mood") else ""
            objs = [t for t, s in zs.get("objects_raw", [])]
            # 拼合 caption：场景 + 主要物体 + 服饰/活动（如命中）
            parts = []
            if zs.get("clothing"):
                parts.append(f"穿{zs['clothing'][0][0]}")
            if zs.get("subject"):
                parts.append(zs["subject"][0][0])
            if zs.get("activity"):
                parts.append(f"在{zs['activity'][0][0]}")
            if scene:
                parts.append(f"的{scene}照片")
            caption = "".join(parts) if parts else f"{scene}照片" if scene else ""
            self._send(200, {
                "caption": caption,
                "scene": scene,
                "objects": objs,
                "colors": _guess_colors(img),
                "mood": mood,
                "model_ver": "clip-zeroshot-v1",
            })
            return
        # 兜底：纯启发式（CLIP 不可用）
        self._send(200, {
            "caption": "",
            "scene": _guess_scene(img),
            "objects": [],
            "colors": _guess_colors(img),
            "mood": "",
            "model_ver": "local-rule-v1",
            "note": "clip+llm unavailable, heuristic only",
        })

    def _handle_classify(self, body):
        # 复用 caption 逻辑，返回标签集合
        ct = self.headers.get("Content-Type", "")
        if ct.startswith("image/"):
            img_bytes = body; mime = ct
        else:
            p = json.loads(body.decode("utf-8"))
            img_bytes = base64.b64decode(p.get("image", ""))
            mime = p.get("mime", "image/jpeg")
        img = _read_image(img_bytes)
        if _CLIP_STATE["ready"]:
            zs = _clip_zero_shot_tags(img)
            tags = [t for t, s in zs.get("objects_raw", [])]
            if not tags and zs.get("scene"):
                tags = [zs["scene"][0][0]]
            self._send(200, {"tags": tags, "model_ver": "clip-zeroshot-v1",
                             "detail": zs})
            return
        self._send(200, {"tags": [_guess_scene(img)], "model_ver": "local-rule-v1"})

# ---- 图像启发式（无 LLM 兜底用）----
def _guess_scene(img: Image.Image):
    arr = np.asarray(img.convert("RGB").resize((64, 64)), dtype=np.float32) / 255.0
    mean = arr.mean(axis=(0, 1))
    r, g, b = mean
    bright = (r + g + b) / 3
    if b > r and b > g and bright > 0.6:
        return "天空/海"
    if bright > 0.75:
        return "明亮室外"
    if bright < 0.25:
        return "夜景/暗室"
    if g > r and g > b:
        return "自然/草地"
    return "室内"

def _guess_colors(img: Image.Image):
    arr = np.asarray(img.convert("RGB").resize((32, 32)), dtype=np.float32) / 255.0
    mean = arr.mean(axis=(0, 1))
    r, g, b = mean
    cols = []
    if r >= g and r >= b: cols.append("红色系")
    if g >= r and g >= b: cols.append("绿色系")
    if b >= r and b >= g: cols.append("蓝色系")
    if (r + g + b) / 3 > 0.7: cols.append("亮色")
    if (r + g + b) / 3 < 0.3: cols.append("暗色")
    return cols[:3]

def main():
    print(f"[ai-svc] starting on :{PORT} backend={AI_BACKEND} "
          f"gateway={_gateway_available()}", flush=True)
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    srv.serve_forever()

if __name__ == "__main__":
    main()
