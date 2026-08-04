# media-manager 功能盘点与规划

> 生成时间: 2026-08-03 V8 sprint 进行中
> 数据来源: 后端 196 条路由 + 前端 220 个 MediaService 方法 + 11 个 Screen + git log + PRD-v8

---

## 一、整体规模

| 维度 | 数量 |
|---|---|
| 后端 HTTP 端点 | 196 条 |
| 前端 MediaService 方法 | 220 个 suspend fun (11,222 行) |
| 前端 Screen / 大组件 | 11 个 Screen + ImageEditor + VideoTrimmer + RnContainer |
| 设置页 Sections | 40 个 (SettingsScreen 5,502 行, 61 个 Composable) |
| "我的"Tab | MyTabContent 6,000 行 (69 个 Composable) |
| Sprint 历史 | V5 → V8 |

## 二、后端 API 按功能域分类

| 功能域 | 端点数 | 代表性端点 | 状态 |
|---|---|---|---|
| 认证 | 4 | login / register / refresh / change-password | JWT + refresh token (V8 §3.2) |
| 媒体 CRUD | ~25 | list / upload / delete / rename / rotate / batch-rename / stream / thumbnail / metadata / batch-download / bulk-export | 完整 |
| 编辑 | 2 | media-edit-save / media-video-trim | V8 §4.2/§1.1 |
| 相册 | ~30 | CRUD + cover(auto/smart/batch) + share + merge/clone/reorder/pin + distribution + 8 个分析端点 | 完整 |
| 标签 | ~30 | CRUD + batch + stats + cloud + hierarchy V1/V2 + network + correlation + smart-group + merge + recommendations + import/export | 完整 |
| 搜索 | 7 | suggestions ×3 + advanced-search + smart-search(NLP) + search-history | V8 NLP |
| 收藏 | 6 | favorite + favorites + batch + timeline + analysis | 完整 |
| 回收站 | 6 | trash / restore / purge / empty / batch-restore / audit-timeline | 完整 |
| 分享 | 8 | share CRUD + batch-share + analytics + expiring + activity + deep-analysis | 完整 |
| 统计分析 | ~50 | storage ×7 版本 + dashboard v1/v2 + heatmap ×3 + calendar ×4 + year stats/comparison/review + weekly-summary + upload pattern/velocity/ranking/streak + interaction-summary + activity-score | 极丰富 |
| 清理/归档 | 12 | cleanup-orphan + archive-suggest + duplicate-cleanup/report + integrity-report + collection-health + cleanup-plan + ai-cleanup-suggestions + photo/album-organize-suggest | V8 AI |
| AI/个性化 | 3 | media-ai-insights + media-personalized-dashboard + insights | V8 |
| RN 下发 | 4 | rn/manifest + rn/bundle/ + promotions + promotions/{id} | 端点就绪 |
| 同步/设备 | 4 | sync/changes + sync/usage + device/register + device/list | 完整 |
| 审计日志 | 5 | audit-log list/stats/by-media/record + audit-timeline | 完整 |
| 运维 | 5 | metrics + healthz + openclaw/command + quota + disk-usage | 完整 |

## 三、前端 Screen 功能盘点

| Screen | 行数 | 已实现功能 |
|---|---|---|
| MediaListScreen | ~10,500 | 5-Tab 底部导航(本地/已上传/活动/网盘/我的) + 媒体网格(日期分组) + 大图预览 + 缩略图条 + 多选批量 + 上传(FAB+进度) + 文件名编辑 + 旋转/删除/收藏/打标签 + 搜索栏 + 高级/智能搜索 + 相关类似媒体 + 分享链接 + 幻灯片 + 分页加载 + 空态/错误态/Shimmer 骨架 |
| MyTabContent(内嵌) | ~6,000 | 欢迎卡片 + 媒体库总览 + 成长里程碑 + 相册覆盖率 + 交互汇总 + 收藏分析 + 标签云 + 热力图 + 三维存储 + 回忆月份卡片 |
| SettingsScreen | 5,502 | 40 Sections: 后端地址/账号/云相册/主题/关于 + 16+ 数据洞察卡片 + OpenClaw |
| AlbumScreen | ~2,000 | 相册列表/详情/创建/分享/封面分析/媒体分布/空态建议/批量操作 |
| ImageEditor | ~1,100 | 涂鸦/马赛克/文字/裁剪/旋转/烘焙保存 |
| VideoTrimmer | - | 视频裁剪(起止点+导出) |
| TrashScreen | ~400 | 回收站列表/恢复/清除 |
| FileManagementScreen | ~500 | 文件管理/用量摘要/筛选排序 |
| CleanupScreen | ~350 | 清理建议分类展示 |
| RnActivityScreen | ~100 | RN 活动容器(首页/详情/挑战/成就) |
| Login/Register/Splash | - | 认证流 + 启动页 |
| SearchBar | ~1,094 | 搜索栏 + 高级/智能搜索面板 |
| SlideshowPlayer | ~391 | 幻灯片播放 |
| DetailPanel | ~325 | 媒体详情侧/底面板 |
| MemoryDetailScreen | ~178 | 月份回忆详情 |

## 四、PRD-v8 完成状态

| PRD 条目 | 优先级 | 状态 | 备注 |
|---|---|---|---|
| §1.1 视频编辑 | P1 | ✅ 完成 | commit 53fa5f8 |
| §1.2 共享相册前端 | P0 | ✅ 完成 | commit 1010dc5 |
| §1.3 RN 热更新 | P1 | ❌ 未做 | 端点就绪,前端缺版本比对+自动下载+回滚 |
| §1.4 AI 清理建议 | P2 | ✅ 完成 | commit d47b0e9 |
| §1.5 离线模式 | P2 | ⚠️ 部分 | manifest+缩略图缓存;缺离线浏览态+冲突解决 |
| §2.1 图片加载性能 | P0 | ⚠️ 部分 | 分页 OK;缩略图 LRU 未显式实现 |
| §2.2 上传体验(WorkManager) | P1 | ❌ 未做 | 仅 BackupStatusNotifier 雏形 |
| §2.3 搜索增强 | P2 | ⚠️ 部分 | 智能搜索 OK;全文/位置筛选未做 |
| §3.1 可观测性 | P2 | ❌ 未做 | /metrics 存在,未扩展 |
| §3.2 安全加固 | — | ✅ 完成 | commit ccfe27b + 2cb91af |
| §4.1 iOS RN 容器 | P2 | ❌ 仅文档 | 04b531e 方案文档,无实现 |
| §4.2 编辑持久化 | P2 | ✅ 完成 | commit c1f07d1 |
| §4.3 分页加载 | P0 | ✅ 完成 | commit 3db6bd6 |

**P0 全部完成。P1 剩 RN 热更新 + 上传体验。P2 剩 iOS RN + 离线完整化。**

## 五、前后端 Gap(后端有 / 前端未对接)

| Gap | 后端端点 | 前端现状 | 影响 |
|---|---|---|---|
| RN 热更新闭环 | rn/manifest + rn/bundle/ ✅ | RnBundleDownloader.kt 0 函数;缺启动检查+下载+回滚 | 无法无发版更新活动页 |
| iOS RN 容器 | — | 仅方案文档,无 UIKitView 嵌入 | 跨端不一致 |
| 后台上传服务 | upload ✅ | 有 FAB+进度;缺 WorkManager 队列/断点续传/重试 | 前台被杀即中断 |
| 离线浏览 UI | media-offline-manifest ✅ | OfflineCacheManager(3)+OfflineQueueStore(7);缺离线态浏览+冲突 UI | 离线不可用 |
| 部分分析端点 UI | tag-co-occurrence / share-deep-analysis / interaction-summary / album-relationship | MediaService 有方法;部分仅 raw JSON 返回 | 体验粗糙 |

## 六、前端信息架构问题(本次优化重点)

### 6.1 SettingsScreen 严重过载

5502 行里 ~70% 是数据洞察卡片,不是"设置":
- 仪表盘概览 / 媒体库总健康 / 智能洞察 / 活跃度评分 / 存储健康度
- 媒体错误检查 / 完整性报告 / 近似重复检测 / 深度存储分析
- 归档建议 / 照片组织建议 / 数据概览 / 媒体生命周期
- 操作时间线 / 媒体覆盖率 / 存储分析 / 清理建议 / 年度报告

**应抽成独立 InsightsDashboardScreen,Settings 回归纯设置(~800 行)。**

### 6.2 拆分方案

| 建议组名 | 归入项 | 路由 |
|---|---|---|
| 账号与安全 | 当前用户/退出登录 | Settings 顶部 |
| 服务器连接 | 后端地址/连通性/后端版本/磁盘/同步状态 | Settings 一段 |
| 云相册备份 | 自动备份/WiFi/充电/用量/待备份/上次时间 | 独立 BackupSettingsScreen |
| 外观个性化 | 主题模式(4选项) | 独立 AppearanceScreen |
| 媒体管理工具 | 回收站/孤立文件/自动打标签/存储清理 | 独立 MediaToolsScreen |
| 存储与数据洞察 | 16+ 卡片 | 独立 InsightsDashboardScreen |
| 关于 | 版本/构建时间/RN更新/缓存清理 | 独立 AboutScreen |
| 开发者工具 | OpenClaw/RN活动中心 | 独立 DeveloperScreen(默认隐藏) |

### 6.3 UI 设计现状

| 维度 | 现状 | 评价 |
|---|---|---|
| 颜色体系 | 动态色(Android 12+) + AMOLED + 回退色板 | ✅ 已有 |
| Typography | 未自定义,用 M3 默认 | ❌ 缺字体规范,散落魔法数字 |
| Shapes | AppShapes + Dimens 常量 | ✅ 亮点,已收敛 |
| 间距规范 | Dimens 存在但覆盖率不均 | ⚠️ 需统一 |
| 动画/过渡 | 仅 Splash 淡入 + NavTab alpha | ❌ 偏静态 |
| 组件复用 | SectionTitle 已抽;SettingsRow/SwitchRow 未抽象 | ❌ 低复用 |
| 整体观感 | 功能堆叠清单,非现代设计语言 | ❌ 需对标 M3 Expressive |

## 七、本次优化计划(DAG)

### Layer 0(并行,disjoint 文件)
- A: 设计系统基础 — 补 Typography + 可复用组件(SettingsRow/SwitchRow/CardScaffold) + 整合 UiTheme
- B: 数据洞察抽离 — 16+ 卡片从 SettingsScreen 移到 InsightsDashboardScreen

### Layer 1(并行,依赖 L0 DS)
- C: Settings 子页拆分 — BackupSettings/Appearance/MediaTools/About/Developer 5 个子 Screen
- D: MyTab 瘦身 — 数据卡片移交 InsightsDashboard,MyTab 只留欢迎+入口

### Layer 2(串行,依赖 L0+L1)
- E: MediaListScreen 视觉打磨 — Tab 栏/网格/TopAppBar/空态用 DS 组件统一 + 页面切换动画

每层 worktree 物理隔离 + merge gate + QA 验收。

## 八、想继续做的方向(对标主流网盘/相册)

| 方向 | 依据 | 优先级 | 本次涉及 |
|---|---|---|---|
| UI 彻底优化(M3 Expressive) | 对标 Google Photos 2025 设计 | 高 | ✅ 本次 |
| Settings 信息架构重构 | 5500 行过载 | 高 | ✅ 本次 |
| 功能盘点文档 | 需求第 3 点 | 高 | ✅ 本文档 |
| RN 热更新闭环(§1.3) | 端点就绪,缺前端逻辑 | 高 | 下一轮 |
| 后台上传服务(§2.2) | 对标百度网盘/Google Photos 必备 | 高 | 下一轮 |
| iOS RN 容器(§4.1) | 跨端一致性 | 中 | 待定 |
| 离线模式完整化(§1.5) | 对标 Google Photos 离线浏览 | 中 | 待定 |
| 人脸/场景智能分类 | 对标 iCloud/Google Photos | 低 | 需 ML 模型 |
| 全文搜索增强(§2.3) | EXIF/描述全文索引 | 低 | 待定 |

## 九、小结

- **后端能力极强**: 196 端点覆盖媒体管理全生命周期,分析类 50+ 端点
- **前端覆盖面广**: 220 个 API 方法,11 个 Screen 覆盖完整用户流
- **主要负债**: ① 前端信息架构(我的/设置页臃肿) ② RN 热更新未闭环 ③ iOS RN 未实现 ④ 后台上传缺失 ⑤ 离线模式半成品
- **P0 已全部完成**: 共享相册前端 + 分页加载
- **P1 剩**: RN 热更新 + 上传体验
- **P2 剩**: iOS RN + 离线完整化
