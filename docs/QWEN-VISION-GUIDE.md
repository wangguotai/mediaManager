# Qwen 多模态视觉识别 —— 截图/图片识别经验手册

> 适用场景：用多模态大模型识别截图、UI、图片内容（如从真机 app 截图抽取 UI 布局、识别 tab、描述画面）。用于前端对齐参考应用的 UI，或其他需要"看图说话"的链路。
>
> 来源：media-manager「对齐一刻相册 UI」实践沉淀，2026-08-13 实测验证。

---

## 一、一句话结论

**透过本地 `ccr-code-router` gateway（127.0.0.1:3456），用 OpenAI `/v1/chat/completions` 接口 + data-URI 图片块，把一张本地图片喂给视觉模型（qwen-27B）让它描述/识别。**

关键就三点：`gateway 地址`、`OpenAI 兼容接口`、`image_url 的 data URI 编码`。

---

## 二、前置前提

- 本机跑着一个 ccr-code-router（**gateway**），监听 `127.0.0.1:3456`，聚合了多个模型。
- 需要一把能访问该 gateway 的 API key（见下）。
- 目标图片在本地磁盘（脚本读本地路径）。

### gateway 状态自检

```bash
curl http://127.0.0.1:3456/health   # 期望 {"core":"http://127.0.0.1:3457","status":"running"}
```

---

## 三、取得 API key（踩过的坑，非常重要）

- ❌ **错误做法**：曾在代码/环境里搜 `apiKeyHelper` 此名字找 key，一定找不到。它本人不是那种文件名。
- ✅ **正确路径**：key 由一个可执行文件输出，真实路径是：

  ```
  ~/.claude-code-router/bin/ccr-claude-code-api-key-default-claude-code
  ```

  运行它即输出有效 key（约 44 字符）。例：

  ```bash
  KEYBIN=~/.claude-code-router/bin/ccr-claude-code-api-key-default-claude-code
  KEY=$("$KEYBIN")
  echo "$KEY" > /tmp/ccr_key.txt   # 工具脚本从这读
  ```

- 该 key 过期/重建后需重新执行一次并写回 `/tmp/ccr_key.txt`。

---

## 四、查询当前可用模型

gateway 重启后模型 ID 的**前缀会变**（见"踩坑"）。必须先查，别用旧 ID：

```bash
KEY=$(cat /tmp/ccr_key.txt)
curl -s http://127.0.0.1:3456/v1/models -H "Authorization: Bearer $KEY"
```

本项目实测可用模型（2026-08-13）：

| 用途            | 模型 ID（传给 payload 的 `model` 字段）            |
|-----------------|-----------------------------------------------------|
| **视觉识别（建议）** | `claude-qwen3627b/Qwen3.6-27B`                     |
| 文本/备用        | `claude-glm52/glm-5.2`                             |
| 视觉（较慢不稳）  | `arcship-5.6-{ultra,max,medium}` 类                |

对比结论：同截图同提示，**qwen（5.1s/约372字）更准**；arcship-medium（11.5s/273字）更慢且会误读细节（62张→25张）。**UI 识别用 qwen 即可，无需 arcship。**

---

## 五、关键调用方式（2 个硬坑）

### 坑 1：必须用 OpenAI 兼容接口，不是 Anthropic 接口

- ❌ `POST /v1/messages`（Anthropic）里传 image 块会被 gateway **剥离**——模型拿不到图，只看到 thinking 说"没图"，`input_tokens=18`，纯粹浪费。
- ✅ **必须用 `POST /v1/chat/completions`（OpenAI 兼容）**，图片放在 `content` 数组里。
- gateway 的 generation 包装路径也因此不同（qwen 走 openai provider）。

### 坑 2：图片得用 data URI（base64）内嵌，base641 写文件

- 调用格式：

  ```json
  POST http://127.0.0.1:3456/v1/chat/completions
  Authorization: Bearer <KEY>
  {
    "model": "claude-qwen3627b/Qwen3.6-27B",
    "max_tokens": 1200,
    "messages": [{
      "role": "user",
      "content": [
        {"type": "image_url", "image_url": {"url": "data:image/png;base64,<B64>"}},
        {"type": "text", "text": "<提示词>"}
      ]
    }]
  }
  ```

- **base64 很长，命令行传参超 ARG_MAX**。把图片 base64 先写进临时文件再让脚本读，别用命令行内联传。
- MIME 按扩展名选择：`.png`→`image/png`、`.webp`→`image/webp`、其余默认 `image/jpeg`。

---

## 六、qwen 返回结构体（thinking 模型）

qwen 是思考型模型，`message` 里有：

- `content`：真正答案（最终输出，已剥离推理，**直接用这个**）
- `reasoning_content`：推理过程（思维链）

```python
m = d["choices"][0]["message"]
ans = (m.get("content") or "").strip()          # 取最终答案
thinking = (m.get("reasoning_content") or "")   # 需要时可看推理
```

---

## 七、开箱即用的脚本

已提交到 `scripts/`：

| 脚本 | 用途 |
|------|------|
| `scripts/qwen_shot.py <图> "<提示>"` | 一张图 → qwen 视觉描述 |
| `scripts/qwen_shot_m.py <图> "<提示>" [模型]` | 指定第三个参数选模型，输出带耗时/字数 |

示例：

```bash
python3 venv/bin/python3 scripts/qwen_shot.py docs/4tab-check.png "一句话说截图底部几个tab"
python3 venv/bin/python3 scripts/qwen_shot_m.py docs/kila-1-home.png "描述布局" claude-arcship56max/arcship-5.6-max
```

> key 从 `/tmp/ccr_key.txt` 读（见上）。脚本开发仓库在 media-manager/scripts/，`.gitignore` 不阻塞 python 脚本提交。

---

## 八、gateway 模型 ID 过期问题（运维陷阱）

- gateway **重启后模型 ID 前缀会变**（曾 `dimcode-…`，后变 `claude-…`）。
- **症状**：脚本报 `HTTP 400 {"error":{"message":"All target providers failed…Model X is not configured"}}` 或 `target_providers:["openai"]` 只认 `glm-5.2`。
- **解法**：
  1. 重新跑第 4 节的 `/v1/models` 拿到最新 ID；
  2. 改脚本里的 `model` 默认值（`qwen_shot.py` / `qwen_shot_m.py` 各有一处）；
  3. 重跑验证。

---

## 附：设计令牌与 UI 抽取

本项目还用 `scripts/design_token.py` 让视觉模型从一刻截图抽规范（配色/字号/间距），产出存 `docs/UI-REFERENCE.md`。做法同视觉识别，只是提示词换成"抽取设计令牌"。

实战产物：一刻相册 4-tab UI → 本项目 AiTabs（照片/相册/查找/创意）已对齐其配色/卡片/圆角/头像。见 `docs/UI-REFERENCE.md`。