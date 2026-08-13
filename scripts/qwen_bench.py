import sys,json,urllib.request,urllib.error,base64,os,time
def run(img,prompt,model):
    key=open('/tmp/ccr_key.txt').read().strip()
    mt="image/jpeg"; ext=os.path.splitext(img)[1].lower()
    if ext==".png": mt="image/png"
    b64=base64.b64encode(open(img,'rb').read()).decode()
    p={"model":model,"max_tokens":600,
       "messages":[{"role":"user","content":[
         {"type":"image_url","image_url":{"url":f"data:{mt};base64,{b64}"}},
         {"type":"text","text":prompt}]}]}
    t0=time.time()
    req=urllib.request.Request("http://127.0.0.1:3456/v1/chat/completions",
      data=json.dumps(p).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+key})
    try:
        r=urllib.request.urlopen(req,timeout=900); d=json.loads(r.read())
        dt=time.time()-t0; m=d["choices"][0]["message"]
        ans=(m.get("content") or "").strip(); th=(m.get("reasoning_content") or "").strip()
        print(f"\n===== {model} | 用时{dt:.1f}s | content {len(ans)}字 | reasoning {len(th)}字 =====")
        out = ans if ans else th
        print(out[:700])
    except urllib.error.HTTPError as e:
        print(f"\n##### {model} HTTP {e.code} 用时{time.time()-t0:.1f}s #####")
        print(e.read().decode()[:300])
    except Exception as e:
        print(f"\n##### {model} ERR 用时{time.time()-t0:.1f}s #####", repr(e))
import time
img, prompt = sys.argv[1], sys.argv[2]
for m in ["dimcode-qwen3627b/Qwen3.6-27B", "dimcode-arcship56medium/arcship-5.6-medium"]:
    run(img, prompt, m)
