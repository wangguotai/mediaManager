import sys,json,urllib.request,base64,os
img=sys.argv[1]; prompt=sys.argv[2]
key=open('/tmp/ccr_key.txt').read().strip()
mt="image/png"
b64=base64.b64encode(open(img,'rb').read()).decode()
p={"model":"dimcode-qwen3627b/Qwen3.6-27B","max_tokens":1800,
   "messages":[{"role":"user","content":[
     {"type":"image_url","image_url":{"url":f"data:{mt};base64,{b64}"}},
     {"type":"text","text":prompt}]}]}
import urllib.request
req=urllib.request.Request("http://127.0.0.1:3456/v1/chat/completions",
  data=json.dumps(p).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+key})
try:
    d=json.loads(urllib.request.urlopen(req,timeout=300).read())
    m=d["choices"][0]["message"]
    print(m.get("content") or m.get("reasoning_content"))
except Exception as e:
    print("ERR",e)
