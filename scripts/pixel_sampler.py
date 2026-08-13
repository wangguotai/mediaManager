#!/usr/bin/env python3
"""像素级取色工具: 从一刻相册真机截图精确提取设计色值。

背景
----
"逐像素抄一刻相册"需要精确 hex,而 LLM 目测截图取色会漂移(受压缩/白平衡影响)。
本脚本直接对截图像素采样,得到可复用的精确主色/背景色/按钮色。

用法
----
# 1) 单点采样: 按图片宽高比例取若干关键点打印 hex
python3 pixel_sampler.py <截图.png> --points "0.5,0.05 0.3,0.35"

# 2) 主色扫描: 统计整图最常见颜色(去除已知背景后可定位主强调蓝)
python3 pixel_sampler.py <截图.png> --scan

# 3) 横向带状主色: 扫描某高度区间的行主色(定位按钮/分区)
python3 pixel_sampler.py <截图.png> --band 0.7 0.85

坐标用 0~1 的比例(相对宽高),自动换算像素,避免不同分辨率截图失配。
"""
import sys
from collections import Counter

def rgb_str(v):
    return "#%02X%02X%02X" % v

def load(p):
    from PIL import Image
    return Image.open(p).convert('RGB')

def points(im, pdefs):
    w, h = im.size
    for token in pdefs.split():
        fx, fy = map(float, token.split(','))
        x, y = int(w * fx), int(h * fy)
        print(f"  ({fx:.2f},{fy:.2f}) -> {rgb_str(im.getpixel((x, y)))}, gap叶({x},{y})")

def scan(im, step=12, exclusions=("#FFFFFF", "#F5F6FA", "#F7F8FA", "#000000")):
    w, h = im.size
    cnt = Counter()
    ex = {c.upper() for c in exclusions}
    for y in range(0, h, step):
        for x in range(0, w, step):
            c = rgb_str(im.getpixel((x, y))).upper()
            if c in ex:
                continue
            cnt[c] += 1
    print("top-12 主色(排除背景白):")
    for c, n in cnt.most_common(12):
        print(f"  {c} x{n}")

def band(im, y0f, y1f, step=10):
    w, h = im.size
    y0, y1 = int(h * y0f), int(h * y1f)
    cnt = Counter()
    for y in range(y0, y1, step):
        for x in range(0, w, step):
            cnt[rgb_str(im.getpixel((x, y)))] += 1
    print(f"band y[{y0f:.2f}~{y1f:.2f}] 主色:")
    for c, n in cnt.most_common(8):
        print(f"  {c} x{n}")

if __name__ == "__main__":
    args = sys.argv[1:]
    if not args:
        print(__doc__); sys.exit(0)
    img = args[0]
    im = load(img)
    mode = args[1] if len(args) > 1 else "--scan"
    if mode == "--points":
        points(im, args[2])
    elif mode == "--band":
        band(im, float(args[2]), float(args[3]))
    else:
        scan(im)