"""一键: 本地图片 -> qwen 视觉识别。用法: python3 qwen_shot.py <image> <提示词>"""
import sys,json,urllib.request,urllib.error,base64,os
img=sys.argv[1]; prompt=sys.argv[2] if len(sys.argv)>2 else "用中文详细描述这张截图的完整UI布局"
key=open('/tmp/ccr_key.txt').read().strip()
mt="image/jpeg"
ext=os.path.splitext(img)[1].lower()
if ext in (".png",): mt="image/png"
elif ext in (".webp",): mt="image/webp"
b64=base64.b64encode(open(img,'rb').read()).decode()
payload={"model":"claude-qwen3627b/Qwen3.6-27B","max_tokens":1200,
 "messages":[{"role":"user","content":[
   {"type":"image_url","image_url":{"url":f"data:{mt};base64,{b64}"}},
   {"type":"text","text":prompt}]}]}
req=urllib.request.Request("http://127.0.0.1:3456/v1/chat/completions",
  data=json.dumps(payload).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+key})
try:
    r=urllib.request.urlopen(req,timeout=300); d=json.loads(r.read())
    m=d["choices"][0]["message"]
    print("=== QWEN VISION ===")
    print("[推理]",(m.get("reasoning_content") or "")[:800])
    print("[回答]",(m.get("content") or ""))
except urllib.error.HTTPError as e:
    print("HTTP",e.code,e.read().decode()[:500])
except Exception as e:
    print("ERR",repr(e))
