---
name: screenshot-to-spec
description: 用多模态模型(qwen)把 UI 截图转成可还原的 Figma 式 JSON 设计稿/设计规范。当用户说"把截图转成设计稿""用多模态产出设计规范""让模型告诉我该画什么 UI"时触发。分三阶段(视觉分析→Figma JSON→token规范化),输出像素坐标/色值/字体/层级的严格 JSON,让模型能精确还原要画的 UI。
---

# Screenshot → Figma Design Spec

把任意 UI 截图（一刻相册、竞品、原型）通过多模态模型 qwen 解析，产出**可还原成代码的 Figma 式 JSON 设计稿**（像素坐标、rgba 色值、字体、圆角、阴影、层级嵌套），让后续能按这份 spec 精确画出/实现 UI。

## 什么时候用
- 用户给一张截图(刻意相册/竞品/原型),要"照着做"或"转成设计稿"
- 需要精确知道"每个元素画在哪/什么颜色/什么字体"
- 要把一份截图沉淀成可复用的 UI 规范

## 核心文件
- `scripts/doc.py` — 一键生成 Figma JSON（qwen 分阶段调用 + 容错）
- `scripts/qwen_shot.py` — 单张图给 qwen 描述（doc.py 的底）
- 参考原版 skill: `/Users/wgt/Downloads/screenshot-to-figma-spec-skill.md`

## 三阶段流程（照 skill 原版的职责拆分）

### S1 视觉分析（CoT，自然语言，temperature 高些）
先让 qwen 概括：页面类型 / 主要区域(上→下,左→右) / 布局模式(Flex/Grid/单列) / 组件清单 / 设计特征(色系/圆角/扁平)。

### S2 结构化提取（严格 JSON，temperature 0.1）
产出 Figma 式 JSON：FRAME/RECTANGLE/TEXT/IMAGE/ICON/VECTOR → 像素坐标/尺寸/填充rgba/cornerRadius/effects/children。TEXT 必含 characters/fontSize/fontWeight/textColor。坐标原点=截图左上角，单位 px；状态栏/Tab栏独立 FRAME；不确定用 null。

### S3 设计系统规范化
把 S2 JSON 里的色值/字号/间距映射到项目 DesignToken（DesignSystem.kt：AppBackground/CardWhite/Primary/TextPrimary/T_Label 等）。量化取整到 8 倍数/圆角 0/4/8/12/16/24/9999。

## 调用方式

### 方式A：doc.py 一键（有脚本时）
```bash
# 直接打印 JSON
python3 scripts/doc.py <截图.png> --print
# 存成文件
python3 scripts/doc.py <截图.png> docs/spec_xxx.json
```
doc.py 已内置 S2 prompt。若输出解析失败（qwen 偶发截断），回退到手动方式B。

### 方式B：手动分阶段（最稳，qwen JSON 不稳时用）
用 `scripts/qwen_shot.py` 分两条 prompt 跑：
1. 先视觉分析：`python3 scripts/qwen_shot.py <图> "用一句话概述这是什么界面+从上到下分区"`
2. 再结构化：`python3 scripts/qwen_shot.py <图> "<严格JSON要求>,只输出JSON对象,不要markdown代码块,坐标左上角像素,从{开始"`

## 容错（重要）
- **qwen 常不输出纯 JSON**（可能截断/带回三级思考/带 markdown）：
  1. 若 `[回答]` 后为空 → 可能 thinking 太长发不出 content，降低 prompt 复杂度 / 分区域逐块提取
  2. 若输出带 ```json 或文字 → 提取首 `{` 到末 `}` 再 json.loads，失败则打印原始让模型重试
  3. 截图太复杂 → 分成多块只提取布局(detail_level=layout)再逐块补细节
  4. 严格约束只出一条 JSON 命令，temperature 用低(ChatCompletions 不确定支持则 static) 

## 产出落盘规范
- 设计 JSON 存 `docs/*-spec.json`，配一份 markdown 对照（区块/色值/布局说明），如 `docs/yike-profile/tabs.md`、`docs/yike-profile/design-notes.md`
- JSON 是"给 Claude 的事实源"，m 是人类阅读
- 用后把关键色值/圆角/间距合并回 `DesignSystem.kt` token，别硬编码

## 还原验证结论（2026-08-13 实测两轮，v2 已通过）

盲测方法：agent 只读 spec、不看原图 → 还原成 HTML → headless Chrome 渲染 → 与原截图肉眼比对。

### v1（`spec_find_tab.json`，仅升级 prompt 无审计）：还原度 55-60%，未通过
五类结构性信息系统性缺失：gap 间距、水平居中、wrap 网格换行、图标矢量、底部 fixed。光在 prompt 里写"强制规则"不够——qwen 收到仍不遵守。

### v2（`spec_find_tab_v2.json`，加 audit_spec 审计+补问重试）：还原度 ~85%，通过
关键不是 prompt 文本，而是 `doc.py` 的 **audit_spec() + 针对性补问重试循环**：
1. 第1轮：build_prompt(8 条强制规则) → qwen 出 spec
2. audit_spec 硬校验每个容器有无 `layoutMode/justifyContent/alignItems/gap/padding`、小矩形有无 `iconRef`、底部栏有无 `position=fixed`
3. 缺失 → 把前 8 条 gap 拼成针对性补问 → qwen 重出完整 spec（最多 2 轮）
4. 合格才落盘；落盘时打印剩余 gap

实测：第1轮 41 处缺失 → 补问后第2轮 0 缺失。还原度从 55% 跳到 ~85%（布局层 90%+，资源层图标简笔/图片占位约 70%，盲测固有局限）。

### RN 风格 schema 确认可用
qwen 补问重试后倾向输出 RN 风格（`type=View/Text/Icon/Image`，字段在 `props.{flexDirection,gap,padding,...}`）而非 Figma 风（`FRAME/RECTANGLE` 平铺）。**信息完整度对就行，schema 形式不强求**。`audit_spec` 按 `children` 判定容器（不认死 `type=FRAME`），`render_spec.py` 双 schema 兼容，已覆盖漂移。

**结论：截图→qwen→spec→盲还原成可识别 UI 这条闭环已验证可用（v2 达 ~85%）。可引用为已通过能力。**

### 工具链
- `python3 scripts/doc.py <截图.png> docs/spec_xxx.json` — 生成+审计+补问重试，合规才落盘
- `python3 scripts/render_spec.py <spec.json> --render` — 确定性 spec→HTML→PNG（盲测基线，零发挥；agent 盲测应 ≥ 本脚本）
- 盲测达标线：肉眼比对还原度 ≥85%。剩余失真主要是图标矢量与真实图片，属 spec 固有局限，需另配图标库/图片源补。

## QA 清单（每份 spec 自检）
- [ ] 坐标覆盖完整 no -截断/多余
- [ ] 文本/颜色/字体都提取
- [ ] 层级父 子正确
- [ ] 间隔一致
- [ ] JSON 合法
- [ ] 状态栏/底部tab独立
- [ ] **每个横排/网格容器都有 gap（盲测最易漏）**
- [ ] **卡片/按钮有 justifyContent/alignItems 或显式居中**
- [ ] **图标节点有 iconRef 或形状描述，非纯色块**
- [ ] **底部 tab 标记 position=fixed + bottom**
- 主要色值已映射到 DesignSystem token