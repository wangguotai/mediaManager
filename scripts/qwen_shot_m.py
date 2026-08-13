import sys,json,urllib.request,urllib.error,base64,os,time,subprocess
img=sys.argv[1]; prompt=sys.argv[2]; model=sys.argv[3] if len(sys.argv)>3 else "claude-qwen3627b/Qwen3.6-27B"
# key 优先读 /tmp/ccr_key.txt；缺失/为空自动从 keybin 重生成并写回。
KEYBIN=os.path.expanduser("~/.claude-code-router/bin/ccr-claude-code-api-key-default-claude-code")
def load_key():
    try:
        k=open('/tmp/ccr_key.txt').read().strip()
        if k: return k
    except FileNotFoundError: pass
    if os.path.exists(KEYBIN):
        k=subprocess.check_output(KEYBIN,text=True).strip()
        if k:
            os.makedirs('/tmp',exist_ok=True); open('/tmp/ccr_key.txt','w').write(k)
            print("[key] /tmp/ccr_key.txt 缺失，已从 keybin 重新生成",file=sys.stderr)
            return k
    raise SystemExit("无法获取 gateway key")
key=load_key()
mt="image/jpeg"; ext=os.path.splitext(img)[1].lower()
if ext==".png": mt="image/png"
elif ext==".webp": mt="image/webp"
b64=base64.b64encode(open(img,'rb').read()).decode()
p={"model":model,"max_tokens":250,
   "messages":[{"role":"user","content":[
     {"type":"image_url","image_url":{"url":f"data:{mt};base64,{b64}"}},
     {"type":"text","text":prompt}]}]}
t0=time.time()
req=urllib.request.Request("http://127.0.0.1:3456/v1/chat/completions",
  data=json.dumps(p).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+key})
try:
    r=urllib.request.urlopen(req,timeout=600); d=json.loads(r.read())
    dt=time.time()-t0
    m=d["choices"][0]["message"]
    ans=(m.get("content") or "").strip()
    print(f"=== {model} 用时{dt:.1f}s 输出{len(ans)}字 ===")
    print(ans[:900])
except urllib.error.HTTPError as e:
    print(f"=== {model} HTTP {e.code} 用时{time.time()-t0:.1f}s ===", e.read().decode()[:300])
except Exception as e:
    print(f"=== {model} ERR 用时{time.time()-t0:.1f}s ===", repr(e))
