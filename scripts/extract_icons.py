#!/usr/bin/env python3
"""extract_icons.py — 单独一轮让 qwen 识别截图里所有图标,产出 iconRef+形状+颜色。

攻 iconRef 最大失真项: 主 spec prompt 里 qwen 顾不上给 iconRef,但聚焦单任务
"只认图标"时 qwen 能给全(实测9个图标含 iconRef/shape/color)。产出可 merge 进 spec。

用法:
  python3 extract_icons.py <截图.png> [out.json]   # 默认打印
"""
import sys, os, json, re, subprocess

def qwen(img, prompt, tries=3):
    import time
    for _ in range(tries):
        r = subprocess.run(
            [sys.executable, os.path.join(os.path.dirname(__file__), "qwen_shot.py"), img, prompt],
            capture_output=True, text=True)
        out = r.stdout
        if "[回答]" in out:
            ans = out.split("[回答]", 1)[1].strip()
            if ans: return ans
        time.sleep(3)
    return ""

PROMPT = ("只输出一个JSON对象(从{开始,不要markdown代码块,不要解释): "
          '{"icons":[{name,iconRef,shape,color}]}, '
          "识别这张截图里所有图标——底部Tab栏(照片/相册/拍摄/查找/创意)、顶部(搜索/红包/头像)、"
          "各分区(更多箭头/位置/类型图标)等。iconRef用 iconfont icon-xxx 命名,shape给形状描述(如'放大镜圆+柄'),color给颜色。")

if __name__ == "__main__":
    args = sys.argv[1:]
    if not args: print(__doc__); sys.exit(0)
    img = args[0]
    text = qwen(img, PROMPT)
    text = re.sub(r'```(?:json)?', '', text)
    try:
        start = text.index("{"); end = text.rindex("}")
        icons = json.loads(text[start:end+1])
    except Exception:
        print("⚠ 解析失败,原始:\n", text[:1500], file=sys.stderr); sys.exit(2)
    if len(args) >= 2:
        with open(args[1], "w", encoding="utf-8") as f:
            json.dump(icons, f, ensure_ascii=False, indent=2)
        n = len(icons.get("icons", []))
        print(f"已生成图标清单 -> {args[1]} ({n} 个图标)")
    else:
        for ic in icons.get("icons", []):
            print(f"  {ic.get('name'): <8} {ic.get('iconRef'):<28} {ic.get('shape')}")