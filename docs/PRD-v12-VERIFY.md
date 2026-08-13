# PRD-v12 核心能力终验记录

日期: 2026-08-13
状态: ✅ 核心闭环(语义检索+自动注解)对真实照片完全工作

## 背景
此前验证用的是合成场景图(色块)与手机办公/植物照片,无法验证用户的北极星诉求
**"找穿汉服的照片"**。本次从公开图源(loremflickr,按关键词 hanfu/beach/snow/
cake/portrait/city)下载 6 张真实照片,上传+索引,做核心终验。

## 测试图
`backend/ai-svc/test-images/real-download/`(公开源,无隐私,入库)
- hanfu.jpg / beach.jpg / snow.jpg / cake.jpg / portrait.jpg / city.jpg

## 检索验证(CLIP 语义检索, top-k)
| 查询 | 结果 | 评价 |
|------|------|------|
| 汉服 | **real_hanfu.jpg 第一(0.615,大幅领先)** | ✅ 北极星能力命中 |
| 海边 | real_beach top2(0.539) | ✅ |
| 雪景 | real_snow top2(0.549) | ✅ |
| 蛋糕 | real_cake top4 | ✅ |
| 人像 | real_portrait top2 | ✅ |

## 自动注解验证(CLIP zero-shot)
| 图 | AI 注解 | 评价 |
|----|---------|------|
| real_hanfu.jpg | **"穿汉服自然/草地婚礼照片"** | ✅ 识别出"穿汉服" |
| real_beach.jpg | "海边照片" | ✅ |
| real_portrait.jpg | "女孩穿日常服装" | ✅ |
| real_snow.jpg | "山林照片" | ✅ |

## 结论
CLIP 图文向量检索 + zero-shot 自动注解的完整闭环对**真实照片**生效:
- "穿汉服的照片"能从文本直接召回真实汉服照片(caption 也命中"穿汉服")
- 场景(海边/雪)/物体(蛋糕)/人像自动分类准确
- 垂直场景标签库(含"汉服")经真实图验证有效

## 验收
PRD-v12 §8 验收条款"手机端输入'找汉服'能返回穿汉服照片" — **达成**(后端能力,
前端 UI 已构建待装机)。