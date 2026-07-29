# 小米 Live Photo 研究报告

## 1. 小米 Live Photo 格式分析

### MVIMG 格式（Google Motion Photo v1）
- **文件命名**: `MVIMG_YYYYMMDD_HHMMSS.jpg`
- **XMP 命名空间**: `xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"`
- **关键元数据**:
  - `GCamera:MicroVideoVersion="1"`
  - `GCamera:MicroVideo="1"`
  - `GCamera:MicroVideoOffset="4447519"` — 视频数据偏移量
  - `GCamera:MicroVideoPresentationTimestampUs="916834"` — 预览时间戳
  - `MiCamera:XMPMeta` — 小米自定义元数据
- **文件结构**: `[JPEG 图像数据][过渡数据][MP4 视频数据]`

### 视频数据偏移问题（关键发现）
`MicroVideoOffset` **不直接指向 MP4 ftyp box**，而是指向视频媒体区域的近似起点。完整 MP4 结构：
- `ftyp` box 实际在 `MicroVideoOffset + 230034` 处
- 前面 230034 字节是 MP4 的 `mdat` 媒体数据（H.264 编码，1080x1440）
- MP4 结构: `[mdat 媒体数据][ftyp][moov][free][mdat 元数据]`

**正确提取方式**: 从 `ftyp` box 往前 4 字节开始提取，而非直接从 `MicroVideoOffset` 开始。

### 小米新机型可能的其他格式
- 小米 14/15 等新机型可能使用 **GContainer** 格式（Google Motion Photo v2）:
  - XMP 标记: `GContainer:Directory` + `GContainer:Item` 
  - 不再使用 `MicroVideoOffset`，改为列出多项容器条目
  - 需要遍历 `Directory` 中的 `Item` 确定 JPEG 和 video 的边界
- 也可能直接使用 **HEIC + paired MOV**（类似 Apple Live Photo）

### 本代码当前限制
1. 只检测 `MVIMG_` 前缀和 `GCamera:MicroVideo` XMP — 不支持 GContainer v2
2. 从 `MicroVideoOffset` 直接开始提取 — 包含 230KB 垃圾前缀数据导致 VideoView 解析失败
3. 只在前 64KB 搜索 XMP — 某些照片 XMP 可能更靠后

## 2. 修复方案

### 短期修复（当前 MVIMG 格式）
1. 提取视频时搜索 `ftyp` box 位置，从 `ftyp - 4` 开始截取
2. 增大 XMP 搜索范围到 128KB

### 中期支持（GContainer v2）
1. 解析 `GContainer:Directory` 条目列表
2. 按 `Item:Length` 和 `Item:Mime` 分离 JPEG 和 video
3. 提取 `video/mp4` 类型的 Item 数据

### 检测策略优先级
1. 检查 XMP `GContainer:Directory` → v2 格式
2. 检查 XMP `GCamera:MicroVideo="1"` → v1 格式
3. 检查文件名 `MVIMG_` 前缀 → 快速路径
4. 检查文件名 `IMG_` + `.HEIC` → Apple 格式（暂不支持）
