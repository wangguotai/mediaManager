import json,urllib.request,base64
img="/Users/wgt/projects/media-manager/docs/baidu-uidumps/ui_tab1_photos.png"
prompt='针对截图中可见UI,给精确数值token表(直接数值,1-编号):1配色hex(背景/卡片/主强调/文字主/文字次/底部选中) 2字号sp(标题/正文/小字/按钮)+粗细 3间距(水平边距/卡片内边距/模块间距)+卡片圆角 4组件(按钮圆角/导航栏高/阴影或边框) 5布局(从上到下列区块)。用简体中文,给完整数值。'
key=open('/tmp/ccr_key.txt').read().strip()
b64=base64.b64encode(open(img,'rb').read()).decode()
p={"model":"dimcode-qwen3627b/Qwen3.6-27B","max_tokens":2500,
   "messages":[{"role":"user","content":[
     {"type":"image_url","image_url":{"url":"data:image/png;base64,"+b64}},
     {"type":"text","text":prompt}]}]}
req=urllib.request.Request("http://127.0.0.1:3456/v1/chat/completions",
  data=json.dumps(p).encode(),headers={"Content-Type":"application/json","Authorization":"Bearer "+key})
r=json.loads(urllib.request.urlopen(req,timeout=300).read())
m=r["choices"][0]["message"]
full=(m.get("reasoning_content") or "")+"\n###ANSWER###\n"+(m.get("content") or "")
open("/tmp/design_out.txt","w").write(full)
print("chars:",len(full))
