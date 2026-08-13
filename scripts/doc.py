#!/usr/bin/env python3
"""screenshot-to-figma-spec: 用 qwen 多模态把 UI 截图转成可还原的 Figma 式 JSON 设计稿。

参考 /Users/wgt/Downloads/screenshot-to-figma-spec-skill.md:
Stage1 视觉分析(CoT) → Stage2 结构化Figma JSON(像素坐标/色值/字体) → 产出严格JSON。
用法:
  python3 doc.py <截图.png> <输出.json> [--organize]
  python3 doc.py <截图.png> --print   # 直接打印
"""
import sys, json, os, subprocess

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
    # 已验证能稳定触发 qwen 输出纯 JSON 的 prompt(ChatCompletions)。
    return ("你是资深UI/UX设计稿解析专家,输出这张截图的Figma式JSON设计稿。"
            "严格只输出一个JSON对象(不要markdown代码块,不要解释,直接用{\"type\":\"FRAME\"...}开头)。"
            "包含:name,width,height,backgroundColor,layoutMode,children(栅格嵌套,含TEXT节点文字/fontSize/textColor,"
            "RECTANGLE含fills/cornerRadius)。坐标原点截图左上角px,从顶部状态栏到左下底部TabBar,"
            "底部标签含(照片/相册/拍摄/查找/创意)。不确定用null。")

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
    prompt = build_prompt()
    text = qwen_vision(img, prompt)
    # 剥离可能的 ```json 围栏，再取首{到末}
    import re
    text = re.sub(r'```(?:json)?', '', text)
    try:
        start = text.index("{")
        end = text.rindex("}")
        spec = json.loads(text[start:end+1])
    except Exception:
        # 兜底: 若 content 为空(thinking过长)重试一次更短 prompt
        print("⚠ 首次JSON解析失败,重试短prompt...", file=sys.stderr)
        short = "输出这张截图的设计稿JSON,只输出对象,从{开始,含name/width/height/backgroundColor/children(各区块TEXT/FRAME)。不要代码块。"
        text2 = qwen_vision(img, short)
        text2 = re.sub(r'```(?:json)?', '', text2)
        try:
            start = text2.index("{")
            end = text2.rindex("}")
            spec = json.loads(text2[start:end+1])
        except Exception:
            print("⚠ 重试仍失败。原始输出:\n", text2[:1500], file=sys.stderr); sys.exit(2)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(spec, f, ensure_ascii=False, indent=2)
    print(f"已生成设计稿JSON -> {out_path} ({len(json.dumps(spec))} bytes)")
    print("顶层children数:", len(spec.get("children", [])))