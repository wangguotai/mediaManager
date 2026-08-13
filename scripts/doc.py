#!/usr/bin/env python3
"""screenshot-to-figma-spec: 用 qwen 多模态把 UI 截图转成可还原的 Figma 式 JSON 设计稿。

参考 /Users/wgt/Downloads/screenshot-to-figma-spec-skill.md:
Stage1 视觉分析(CoT) → Stage2 结构化Figma JSON(像素坐标/色值/字体) → 产出严格JSON。
用法:
  python3 doc.py <截图.png> <输出.json> [--organize]
  python3 doc.py <截图.png> --print   # 直接打印
"""
import sys, json, os, subprocess, re

REQUIRED_CONTAINER_FIELDS = ("layoutMode", "justifyContent", "alignItems", "gap", "padding")
"""每个 FRAME 容器应含这些字段;盲测五类缺失就是它们缺失,导致还原度 55-60%。"""

def walk_nodes(node):
    """yield node 平铺遍历整个 spec 树。"""
    if isinstance(node, dict):
        yield node
        for c in node.get("children", []) or []:
            yield from walk_nodes(c)

def audit_spec(spec):
    """返回 list[(节点名, 缺失字段列表, 建议补问)]。
    盲测还原度低的根因: qwen 收到升级 prompt 仍不遵守, 这里硬校验抓出来,
    驱动针对性补问重试,把还原度从 55-60% 推向 ≥85%。"""
    gaps = []
    # 容器=有 children 的节点(不论 type 名,qwen 常给 type=None/缺失,不能靠 type=FRAME 筛)。
    frames = [n for n in walk_nodes(spec) if n.get("children")]
    for i, fr in enumerate(frames):
        name = fr.get("name") or f"frame#{i}"
        miss = [f for f in REQUIRED_CONTAINER_FIELDS if f not in fr]
        if "padding" in fr and isinstance(fr["padding"], dict):
            miss = [m for m in miss if m != "padding"]
        if miss:
            gaps.append((name, miss,
                         f"容器「{name}」缺 {'/'.join(miss)},请按视觉补全(紧贴=gap0/flex-start,居中=center)"))
    # iconRef: 小矩形(≤48)疑似图标却无 iconRef
    for n in walk_nodes(spec):
        t = n.get("type")
        w = n.get("width"); h = n.get("height")
        if t == "RECTANGLE" and isinstance(w, (int, float)) and isinstance(h, (int, float)) \
           and w <= 48 and h <= 48 and "iconRef" not in n:
            gaps.append((n.get("name", "rect"), ["iconRef"],
                         f"小矩形「{n.get('name','rect')}」可能是图标,请补 iconRef 与形状描述"))
    # 底部 TabBar fixed
    for fr in frames:
        nm = (fr.get("name") or "").lower()
        if ("tab" in nm or "bar" in nm) and fr.get("position") != "fixed":
            gaps.append((fr.get("name"), ["position"],
                         f"底部栏「{fr.get('name')}」应 position=fixed + bottom=0"))
    return gaps


def qwen_vision(img, prompt, tries=3):
    """调 qwen_shot 脚本得到回答文本。qwen 长 prompt 偶发只出 thinking/空 content,重试。"""
    import time
    for i in range(tries):
        r = subprocess.run(
            [sys.executable, os.path.join(os.path.dirname(__file__), "qwen_shot.py"), img, prompt],
            capture_output=True, text=True)
        out = r.stdout
        if "[回答]" in out:
            ans = out.split("[回答]", 1)[1].strip()
            if ans:
                return ans
        time.sleep(3)
    return ""

SYSTEM = """你是资深UI/UX设计稿解析专家,把界面截图转成精确Figma式JSON设计稿。
严格输出一个合法JSON对象(不要markdown代码块,不要解释)。规范:
- 坐标系: 原点在截图左上角,单位px
- 颜色: rgba(0,0,0,1) 或 #RRGGBB
- 字体: CSS font shorthand,如 "600 16px 'PingFang SC'"
- 尺寸: 精确像素;不确定用null,禁止猜
- 层级用children嵌套;状态栏/Tab栏独立FRAME
- 元素类型: FRAME/RECTANGLE/TEXT/IMAGE/ICON/VECTOR/COMPONENT
- TEXT节点必须含characters,fontSize,fontWeight,textColor
- 圆角用cornerRadius(数值或四角对象);阴影effects数组描述
- 只输出JSON对象,开头直接 {"""

def build_prompt(_organize=False):
    # 升级版 S2 prompt(按 screenshot-to-spec skill 盲测结论): 强制补全blind还原最易缺失的
    # 5类结构信息——gap/对齐/padding/网格columns/图标iconRef/底部fixed,把盲还原度推向≥85%。
    return (
        "你是资深UI设计稿解析专家。输出这张截图的Figma式JSON设计稿,只输出一个JSON对象"
        "(不要markdown代码块,不要解释,直接用{\"type\":\"FRAME\"...}开头)。\n"
        "强制规则(缺一不可,这是盲还原高保真的关键):\n"
        "1. 每个容器(type=FRAME/布局行/网格)必须含: layoutMode(horizontal/vertical/wrap),"
        "justifyContent(flex-start/center/flex-end/space-between),alignItems(flex-start/center/flex-end),"
        "gap(子元素间距px,非0必填),padding{top,right,bottom,left}。\n"
        "2. 网格类容器必须给 columns(列数),不要只靠wrap+尺寸推断。\n"
        "3. 横排行哪怕子元素紧挨,也要显式标 gap(可为0)和 alignItems。\n"
        "4. 图标节点不要只给色块: 必须含 iconRef(命名引用,如 icon.tab.photo/icon.expand/ic_arrow),"
        "并尽量描述形状(如 \"相机镜头圆+凸起\"/\"放大镜圆+柄\")。图标是UI核心识别特征。\n"
        "5. 底部TabBar/固定栏必须含 position:\"fixed\" + bottom:0,不要靠文档流末位;root容器padding含安全区(左右≥20)。\n"
        "6. TEXT节点: characters+fontSize+fontWeight+textColor+fontFamily; RECTANGLE: fills+cornerRadius。\n"
        "7. 每个有意义的卡片/按钮给 designToken(若能匹配到常见token名)。\n"
        "8. 坐标px原点截图左上角;不确定用null,禁止猜。\n"
        "顶层结构: name,width,height,backgroundColor,layoutMode:vertical,padding{...},children[]。"
        "从顶部状态栏→各内容区块→底部TabBar(照片/相册/拍摄/查找/创意)。"
    )

def parse_json_text(text):
    """剥离 ```json 围栏,取首{到末},json.loads;失败返回 None。"""
    text = re.sub(r'```(?:json)?', '', text)
    try:
        start = text.index("{")
        end = text.rindex("}")
        return json.loads(text[start:end+1])
    except Exception:
        return None

def extract_spec(img, prompt):
    """qwen_vision → 解析 JSON → 返回 spec 或 None。"""
    text = qwen_vision(img, prompt)
    if not text:
        return None
    return parse_json_text(text)

if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print(__doc__); sys.exit(0)
    img = args[0]
    if "--print" in args:
        out_text = qwen_vision(img, build_prompt()); print(out_text); sys.exit(0)
    if len(args) < 2:
        print("需要输出json路径"); sys.exit(1)
    out_path = args[1]

    # 第1轮: 完整升级版 prompt
    spec = extract_spec(img, build_prompt())
    if spec is None:
        # 兜底: content 空(thinking过长)→ 短 prompt
        print("⚠ 首次JSON解析失败,重试短prompt...", file=sys.stderr)
        short = "输出这张截图的设计稿JSON,只输出对象,从{开始,含name/width/height/backgroundColor/children。不要代码块。"
        spec = extract_spec(img, short)
        if spec is None:
            print("⚠ 重试仍失败。", file=sys.stderr); sys.exit(2)

    # 审计 + 针对性补问重试(最多2轮): 把 qwen 没遵守的字段逼出来
    for rnd in range(1, 3):
        gaps = audit_spec(spec)
        if not gaps:
            print(f"[审计] 第{rnd}轮无缺失字段,合规。", file=sys.stderr)
            break
        print(f"[审计] 第{rnd}轮发现 {len(gaps)} 处缺失,补问重试...", file=sys.stderr)
        # 把前 8 条 gap 拼成针对性补问(避免 prompt 过长触发 thinking 截断)
        fixes = "\n".join(f"- {g[2]}" for g in gaps[:8])
        fix_prompt = (
            "上一版设计稿JSON有字段缺失,导致盲还原失真。请输出**修正后的完整JSON**(整份,不要只改片段),"
            "务必补全以下点:\n" + fixes +
            "\n只输出JSON对象,从{开始。"
        )
        fixed = extract_spec(img, fix_prompt)
        if fixed:
            spec = fixed
        # 若补问失败,保留旧 spec 继续下一轮审计(可能仍有缺失,但不再恶化)

    # 最终落盘 + 报告剩余 gap
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(spec, f, ensure_ascii=False, indent=2)
    final_gaps = audit_spec(spec)
    print(f"已生成设计稿JSON -> {out_path} ({len(json.dumps(spec))} bytes)")
    print("顶层children数:", len(spec.get("children", [])))
    if final_gaps:
        print(f"⚠ 仍有 {len(final_gaps)} 处缺失(qwen 未补全),盲还原可能仍失真:", file=sys.stderr)
        for g in final_gaps[:5]:
            print(f"  - {g[0]}: 缺 {g[1]}", file=sys.stderr)
    else:
        print("[✓] 所有容器含 gap/对齐/padding,图标有 iconRef,底部栏 fixed — 盲还原就绪。", file=sys.stderr)
