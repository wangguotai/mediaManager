#!/usr/bin/env python3
"""render_spec.py — 把 doc.py 产出的 Figma 式 spec JSON 盲还原成 HTML,供 headless Chrome 截图比对。

不是 agent 盲测的替代,而是**自动基线**:用确定性规则把 spec 逐节点翻成 HTML/CSS,
相当于"一个最老实、零发挥的还原者"。它还原出来的效果 = spec 信息本身的下限。
agent 盲测还原度 ≥ 本脚本才算 agent 真发挥;若 agent 反而更低,说明 agent 在乱猜。

支持两种 schema(都从 audit_spec 主流程产出):
  - Figma 风:type=FRAME/RECTANGLE/TEXT/CIRCLE,字段 layoutMode/fills/...
  - RN 风(审计重试后偶发):type=View/Text/Icon/Image,字段在 props.{flexDirection,...}

用法:
  python3 render_spec.py <spec.json> [out.html]   # 默认 out = spec 同名 .html
  python3 render_spec.py docs/spec.json --render   # 顺带调 Chrome 截图成 .png
"""
import sys, json, os, re, subprocess, html

def _num(v, default=0):
    """把 "16" / 16 / "100%" → "16px" / "100%"; None→默认。"""
    if v is None: return f"{default}px"
    if isinstance(v, str):
        s = v.strip()
        if s.endswith("%"): return s
        if re.fullmatch(r"-?\d+(\.\d+)?", s): return f"{s}px"
        return s  # 已带单位或合法 CSS 值
    if isinstance(v, (int, float)): return f"{v}px"
    return str(v)

def _flex_dir(v):
    if not v: return "row"
    return "row" if v in ("horizontal", "row") else "column" if v in ("vertical", "column") else "wrap" if v == "wrap" else str(v)

def render_node(node):
    """递归返回 (html_str, is_block)。RN/Figma 双 schema 兼容。"""
    if not isinstance(node, dict): return "", False
    t = node.get("type", "")
    name = node.get("name", "")
    children = node.get("children", []) or []
    # ---- 容器(View/FRAME/COMPONENT) ----
    if t in ("View", "FRAME", "COMPONENT", "ScrollView"):
        p = node.get("props", {}) or node  # RN:props 内; Figma:平铺
        styles = []
        fd = p.get("flexDirection") or p.get("layoutMode")
        styles.append(f"display:flex;flex-direction:{_flex_dir(fd)}")
        if p.get("justifyContent"): styles.append(f"justify-content:{p['justifyContent']}")
        if p.get("alignItems"): styles.append(f"align-items:{p['alignItems']}")
        if "gap" in p and p["gap"] not in (None,""): styles.append(f"gap:{_num(p['gap'])}")
        if "columns" in p: styles.append(f"flex-wrap:wrap")  # 网格粗近似
        pad = p.get("padding")
        if isinstance(pad, dict):
            pt = pad.get("top",0); pr = pad.get("right",0); pb = pad.get("bottom",0); pl = pad.get("left",0)
            styles.append(f"padding:{_num(pt)} {_num(pr)} {_num(pb)} {_num(pl)}")
        elif "paddingTop" in p or "paddingLeft" in p:
            styles.append(f"padding-top:{_num(p.get('paddingTop',0))} padding-left:{_num(p.get('paddingLeft',0))}")
        if p.get("backgroundColor") or node.get("backgroundColor") or node.get("fills"):
            bg = p.get("backgroundColor") or node.get("backgroundColor")
            if not bg and isinstance(node.get("fills"), list) and node["fills"]:
                bg = node["fills"][0].get("color", "#eee")
            if bg: styles.append(f"background:{bg}")
        if p.get("width") or node.get("width"): styles.append(f"width:{_num(p.get('width') or node.get('width'))}")
        if p.get("height") or node.get("height"): styles.append(f"height:{_num(p.get('height') or node.get('height'))}")
        if node.get("cornerRadius") or p.get("cornerRadius"):
            styles.append(f"border-radius:{_num(node.get('cornerRadius') or p.get('cornerRadius'))}px")
        if node.get("position")=="fixed" or p.get("position")=="fixed":
            styles.append("position:fixed;bottom:0;left:0;right:0")
        if node.get("overflow")=="hidden" or p.get("overflow")=="hidden":
            styles.append("overflow:hidden")
        inner = "".join(render_node(c)[0] for c in children)
        cls = "container"
        attrs = f'class="{cls}" data-name="{html.escape(name)}" data-type="{t}"'
        return f'<div {attrs} style="{";".join(styles)}">{inner}</div>', True
    # ---- 文字(Text/TEXT) ----
    if t in ("Text", "TEXT"):
        p = node.get("props", {}) or node
        s = []
        for k,css in [("fontSize","font-size"),("fontWeight","font-weight"),("color","color"),("fontFamily","font-family")]:
            v = p.get(k) if k in p else node.get(k)
            if v is not None:
                if k=="fontWeight" and v=="bold": v="700"
                if k=="fontSize": v=_num(v)
                s.append(f"{css}:{v}")
        txt = p.get("text") or node.get("text") or node.get("characters") or ""
        return f'<span style="{";".join(s)}">{html.escape(str(txt))}</span>', False
    # ---- 图标(Icon/RECTANGLE 小) ----
    if t in ("Icon","ICON"):
        p = node.get("props",{}) or node
        ref = p.get("iconRef") or node.get("iconRef") or name or "?"
        col = p.get("color") or node.get("color") or (node.get("fills",[{}])[0].get("color") if node.get("fills") else "#888")
        w = _num(p.get("width") or node.get("width") or 24)
        h = _num(p.get("height") or node.get("height") or 24)
        # 简笔:圆点 + 标签,比纯色块可识别
        return (f'<span class="icon" style="display:inline-flex;align-items:center;justify-content:center;'
                f'width:{w};height:{h};background:{col};border-radius:4px;color:#fff;font-size:9px;'
                f'overflow:hidden" title="{html.escape(ref)}">{html.escape(ref[:6])}</span>'), False
    if t in ("Image","IMAGE"):
        p = node.get("props",{}) or node
        w = _num(p.get("width") or node.get("width") or "100%")
        h = _num(p.get("height") or node.get("height") or 80)
        bg = p.get("backgroundColor") or "#ddd"
        ref = p.get("imageRef") or node.get("imageRef") or name or ""
        return (f'<div class="img" style="width:{w};height:{h};background:{bg};'
                f'display:flex;align-items:flex-end;font-size:9px;color:#999;padding:2px">'
                f'{html.escape(str(ref))}</div>'), True
    if t in ("RECTANGLE","CIRCLE","VECTOR"):
        p = node.get("props",{}) or node
        w = _num(p.get("width") or node.get("width") or 20)
        h = _num(p.get("height") or node.get("height") or 20)
        bg = p.get("color") or (node.get("fills",[{}])[0].get("color") if node.get("fills") else "#ccc")
        r = node.get("cornerRadius") or p.get("cornerRadius")
        br = f"border-radius:{_num(r)}" if r else ("border-radius:50%" if t=="CIRCLE" else "")
        ref = node.get("iconRef") or p.get("iconRef")
        label = f'<span style="font-size:8px;color:#fff">{html.escape(ref[:6])}</span>' if ref else ""
        return f'<div style="width:{w};height:{h};background:{bg};{br};display:flex;align-items:center;justify-content:center">{label}</div>', True
    # 兜底
    return "", False

def spec_to_html(spec):
    # 画布
    w = spec.get("width") or (spec.get("props",{}) or {}).get("width") or 750
    h = spec.get("height") or (spec.get("props",{}) or {}).get("height") or 1624
    wv = _num(w); hv = _num(h)
    bg = spec.get("backgroundColor") or (spec.get("props",{}) or {}).get("backgroundColor") or "#F2F4F8"
    body, _ = render_node(spec)
    # 若 root 本身就是 View,body 已含样式;否则套一个画布
    if spec.get("type") in ("View","FRAME") or (spec.get("props",{}) or {}).get("flexDirection"):
        canvas = body
    else:
        canvas = f'<div style="width:{wv};height:{hv};background:{bg};overflow:hidden">{body}</div>'
    return f"""<!doctype html><html><head><meta charset="utf-8"><style>
*{{box-sizing:border-box;margin:0;padding:0}}
body{{display:flex;justify-content:center;background:#888}}
img{{max-width:100%}}
</style></head><body>
<!-- 自动渲染自 spec,确定性规则,零发挥 -->
{canvas}
</body></html>"""

if __name__ == "__main__":
    args = sys.argv[1:]
    if not args: print(__doc__); sys.exit(0)
    spec_path = args[0]
    do_render = "--render" in args
    out = next((a for a in args[1:] if not a.startswith("--")), None) or \
          spec_path.rsplit(".",1)[0] + ".html"
    spec = json.load(open(spec_path, encoding="utf-8"))
    html_str = spec_to_html(spec)
    open(out,"w",encoding="utf-8").write(html_str)
    print(f"HTML -> {out} ({len(html_str)} bytes)")
    if do_render:
        chrome = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
        png = out.rsplit(".",1)[0] + ".png"
        w = spec.get("width") or 750; h = spec.get("height") or 1624
        if isinstance(w,str) and "%" in w: w=750
        if isinstance(h,str) and "%" in h: h=1624
        subprocess.run([chrome,"--headless","--disable-gpu","--hide-scrollbars",
                         f"--window-size={int(float(w))},{int(float(h))}",
                         f"--screenshot={png}", f"file://{os.path.abspath(out)}"],
                        stderr=subprocess.DEVNULL)
        print(f"PNG  -> {png}")
