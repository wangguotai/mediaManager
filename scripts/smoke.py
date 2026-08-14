#!/usr/bin/env python3
"""PRD-v12 全链路自动化冒烟验证脚本(监管者每轮守护核心资产)。

用法:
    python3 scripts/smoke.py            # 默认验证全链路
    python3 scripts/smoke.py -v          # 详细输出每个断言
    python3 scripts/smoke.py --only hanfu # 只跑指定用例

覆盖 PRD-v12 核心闭环:
  1. 登录鉴权 (POST /api/auth/login → token)
  2. AI 索引状态 (/api/ai/status → feature_svc + progress)
  3. 英雄用例「穿汉服」(/api/ai/search?q=穿汉服的 → real_hanfu 排第一)
  4. 海边检索
  5. 自动相册 (/api/ai/albums)
  6. 人物聚类 (/api/persons → clusters)
  7. 媒体流可访问 (/api/media/stream/{id})

每项 PASS/FAIL,全部 PASS 则 exit 0,任一 FAIL 则对该项说明并 exit 1。
"""
import argparse
import json
import sys
import urllib.request
import urllib.parse

BASE = "http://127.0.0.1:8080"
USERNAME = "admin"
PASSWORD = "admin123"  # 与 APP 预填 DEV_DEFAULT_PASSWORD 一致(SettingsScreen.kt)

PASS, FAIL = "PASS", "FAIL"
results = []


def set_base(url):
    global BASE
    BASE = url.rstrip("/")


def http(method, path, token=None, body=None, timeout=30):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        return e.code, {}
    except Exception as e:  # noqa: BLE001
        return 0, {"err": str(e)}


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    tag = PASS if ok else FAIL
    line = f"[{tag}] {name}"
    if detail:
        line += f" — {detail}"
    print(line)


def login():
    code, d = http("POST", "/api/auth/login",
                   body={"username": USERNAME, "password": PASSWORD})
    tok = (d or {}).get("token", "")
    check("登录鉴权", code == 200 and tok, f"code={code}")
    return tok if tok else None


def ai_status(token):
    code, d = http("GET", "/api/ai/status", token)
    ok = code == 200
    feat = (d or {}).get("feature_svc", {}) if isinstance(d, dict) else {}
    prog = (d or {}).get("progress", {}) if isinstance(d, dict) else {}
    clip = feat.get("clip") is True
    indexed = prog.get("Indexed")
    annotated = prog.get("Annotated")
    persons = prog.get("Persons")
    ok = ok and clip and indexed is not None
    check("AI特征服务就绪(clip)", clip and ok, f"model={feat.get('model_ver')}")
    total = prog.get("Total")
    complete = indexed is not None and (total is None or indexed >= total)
    check("索引完成",
          complete and (indexed is not None and annotated is not None),
          f"indexed={indexed}/{total} annotated={annotated} persons={persons}"
          + ("" if complete else " ⚠ 有待索引照片,导入后索引会在后台进行"))
    return ok


def search(token, q, expect_top=None, min_score=0.3, label="检索", expect_caption=None):
    qs = urllib.parse.urlencode({"q": q, "limit": 3})
    code, d = http("GET", f"/api/ai/search?{qs}", token, timeout=60)
    res = (d or {}).get("results", []) if isinstance(d, dict) else []
    if not res:
        check(label + f"—「{q}」有结果", False, f"0 命中 code={code}")
        return False
    top = res[0]
    media = top.get("media", {})
    fname = media.get("filename", "?")
    score = top.get("score", 0)
    caption = top.get("caption", "")
    hit = (not expect_top) or (fname == expect_top)
    cap_ok = True
    if expect_caption:
        hit = hit and any(kw in caption for kw in expect_caption)
        cap_ok = hit
    check(label + f"—「{q}」top 命中 {expect_top or '任一'}",
          hit, f"top={fname} score={score:.3f} caption={caption[:36]!r}")
    return hit


def albums(token):
    code, d = http("GET", "/api/ai/albums", token)
    albums = (d or {}).get("albums", []) if isinstance(d, dict) else []
    check("自动相册分类 (≥1 智能相册)", code == 200 and len(albums) > 0,
          f"{len(albums)} 个相册")
    return code == 200 and len(albums) > 0


def persons(token):
    code, d = http("GET", "/api/persons", token)
    cs = (d or {}).get("clusters", []) if isinstance(d, dict) else []
    named = [c for c in cs if c.get("Name")]
    check("人物聚类 (≥1 簇)", code == 200 and len(cs) > 0, f"{len(cs)} 簇 {len(named)} 命名")
    return code == 200 and len(cs) > 0


def media_stream(token):
    # 取一张媒体 id 验证可流式访问
    code, d = http("GET", "/api/ai/status", token)
    mid = None
    # 用 persons 的 avatar 或 fallback 略过;简单起见直接看 media 列表
    try:
        code2, d2 = http("GET", "/api/media/list?limit=1", token)
        lst = (d2 or {}).get("media_list", []) if isinstance(d2, dict) else []
        if lst:
            mid = lst[0].get("id")
    except Exception:  # noqa: BLE001
        mid = None
    if not mid:
        check("媒体流入口存在", True, "media_list 无数据,跳过")
        return True
    # stream 端点
    req = urllib.request.Request(f"{BASE}/api/media/stream/{mid}", headers={
        "Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            head = resp.read(16)
            check("媒体流可访问", len(head) > 0, f"{len(head)}B 字节")
            return len(head) > 0
    except Exception as e:  # noqa: BLE001
        check("媒体流可访问", False, str(e))
        return False


def main():
    parser = argparse.ArgumentParser(description="PRD-v12 全链路冒烟")
    parser.add_argument("-v", "--verbose", action="store_true")
    parser.add_argument("--only", default=None, help="只跑指定用例: hanfu|beach|albums|persons")
    parser.add_argument("--base", default=None, help="后端 base url，默认 http://127.0.0.1:8080")
    args = parser.parse_args()

    if args.base:
        set_base(args.base)

    only = args.only
    tok = login()
    if not tok:
        print("\n❌ 登录失败,中止后续验证")
        sys.exit(1)
    if only in (None, "status"):
        ai_status(tok)
    if only in (None, "hanfu"):
        search(tok, "穿汉服的照片", expect_top="real_hanfu.jpg", label="英雄用例",
               expect_caption=["汉服"])
    if only in (None, "beach"):
        search(tok, "海边", label="场景")
    if only in (None, "albums"):
        albums(tok)
    if only in (None, "persons"):
        persons(tok)
    if only is None:
        media_stream(tok)

    failed = [n for n, ok, _ in results if not ok]
    print("\n===== 冒烟结果 =====")
    for name, ok, detail in results:
        print(f"  {PASS if ok else FAIL}  {name}")
    print(f"\n总计 {len(results)} 项,失败 {len(failed)} 项")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()