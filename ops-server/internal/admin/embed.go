// Package admin 提供运营管理前端与 /admin/* REST API。
//
// 实现方式：用 //go:embed 把 static/ 目录下的纯 HTML/JS 页面编译进二进制，
// 运行时零外部文件依赖。页面用最小内联 CSS + fetch API 调本包暴露的 JSON 端点，
// 不依赖任何前端框架或 CDN。
//
// 路由约定：
//   - GET  /admin/              → 登录页 index.html
//   - GET  /admin/{page}.html   → dashboard/users/servers/traffic 页面
//   - POST /admin/login         → 校验运营账号、签发 JWT（无需鉴权）
//   - GET  /admin/api/*         → 受 JWT 鉴权的 JSON 数据端点（看板/用户/服务端/流量）
//
// 鉴权：除 /admin/login 与静态页面外，API 端点要求 Authorization: Bearer <jwt>。
// 页面侧由前端 JS 检查 localStorage 中的 token，缺失则跳登录页；后端 API 仅校验 token 合法性。
package admin

import (
	"embed"
	"io/fs"
)

//go:embed static/*
var staticFS embed.FS

// staticSubFS 是 static/ 子树的 fs.FS，供 StaticHandler 直接挂载。
// 内部细节：embed.FS 根为包目录，需 Sub 到 "static" 才能以页面文件名为路径访问。
var staticSubFS, _ = fs.Sub(staticFS, "static")

// pageNames 是 static/ 下所有可被直接 GET 的 HTML 页面名。
// 用于在路由层区分"页面请求"与"API 请求"，并对未知页面返回 404 而非目录列举。
var pageNames = map[string]bool{
	"index.html":     true,
	"dashboard.html": true,
	"users.html":     true,
	"servers.html":   true,
	"traffic.html":   true,
}
