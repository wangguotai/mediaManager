// Package config 加载运营服务端配置。
//
// 运营服务端 (ops-server) 独立于媒体后端 (backend)，负责跨设备的发现、信令、TCP 中继
// 与流量记账。配置来源链与 backend 一致：代码默认 < MM_OPS_* 环境变量，便于 Docker 注入。
//
// 设计取舍：
//   - 不引入 yaml，仅用环境变量 + 少量默认，保持 module 零额外依赖、配置面最小。
//   - JWT 密钥为空时由 auth 包生成进程级随机密钥（重启失效），仅适合开发。
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// Config 持有运营服务端运行参数。
type Config struct {
	// HTTPAddr 运营 REST/WebSocket 监听地址（账号、server 注册、发现查询、信令 WS）。
	HTTPAddr string
	// RelayAddr TCP 中继监听地址（TURN 式转发）；与 HTTPAddr 分离以便独立暴露/排障。
	RelayAddr string
	// DataDir 数据目录，存放 SQLite 库与会话状态文件。
	DataDir string
	// DBPath SQLite 数据库路径。空则用 <DataDir>/ops.db。
	DBPath string
	// JWTSecret JWT HS256 签名密钥；空则进程级随机。
	JWTSecret string
	// JWTTTLSeconds token 有效期秒数；<=0 用默认 7 天。
	JWTTTLSeconds int
	// LogBytesVerbose 是否在中继日志中记录字节计数（默认 true）。仅影响日志详细度。
	LogBytesVerbose bool
}

const (
	// defaultHTTPAddr REST/WS 默认监听 :18789（与 backend 的 OpenClaw 回退默认端口对齐）。
	defaultHTTPAddr = ":18789"
	// defaultRelayAddr TCP 中继默认监听 :18790（紧邻 REST 端口，便于记忆）。
	defaultRelayAddr = ":18790"
	// defaultDataDir 默认数据目录（相对 cwd）。
	defaultDataDir = "./ops-data"
	// defaultJWTTTL 默认 JWT 有效期：7 天，与 backend 一致。
	defaultJWTTTL = 7 * 24 * 60 * 60
)

// Default 返回一份带合理默认值的 Config。
func Default() *Config {
	return &Config{
		HTTPAddr:        defaultHTTPAddr,
		RelayAddr:       defaultRelayAddr,
		DataDir:         defaultDataDir,
		JWTTTLSeconds:   defaultJWTTTL,
		LogBytesVerbose: true,
	}
}

// Load 从环境变量读取配置，返回带默认值填充的 *Config。
// 不读文件——运营服务端配置面小，环境变量足够且利于容器化。
func Load() (*Config, error) {
	cfg := Default()

	cfg.HTTPAddr = envOr("MM_OPS_HTTP_ADDR", cfg.HTTPAddr)
	cfg.RelayAddr = envOr("MM_OPS_RELAY_ADDR", cfg.RelayAddr)
	cfg.DataDir = envOr("MM_OPS_DATA_DIR", cfg.DataDir)
	cfg.DBPath = envOr("MM_OPS_DB_PATH", cfg.DBPath)
	cfg.JWTSecret = os.Getenv("MM_OPS_JWT_SECRET")

	if v := os.Getenv("MM_OPS_JWT_TTL_SECONDS"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n <= 0 {
			return nil, fmt.Errorf("invalid MM_OPS_JWT_TTL_SECONDS %q: must be positive integer", v)
		}
		cfg.JWTTTLSeconds = n
	}
	if v := os.Getenv("MM_OPS_LOG_BYTES_VERBOSE"); v != "" {
		// 任何 "0" / "false" / "no" / "off" 关闭，其余开启。
		cfg.LogBytesVerbose = !boolishFalse(v)
	}

	return cfg, nil
}

// ResolveDataDir 返回绝对路径并确保目录存在。
func (c *Config) ResolveDataDir() (string, error) {
	dir := strings.TrimSpace(c.DataDir)
	if dir == "" {
		dir = defaultDataDir
	}
	abs, err := filepath.Abs(dir)
	if err != nil {
		return "", fmt.Errorf("resolve data_dir %q: %w", dir, err)
	}
	if err := os.MkdirAll(abs, 0o755); err != nil {
		return "", fmt.Errorf("create data_dir %s: %w", abs, err)
	}
	return abs, nil
}

// ResolveDBPath 在未显式指定时落在 DataDir/ops.db 下，并确保父目录存在。
func (c *Config) ResolveDBPath(dataDir string) (string, error) {
	p := strings.TrimSpace(c.DBPath)
	if p == "" {
		p = filepath.Join(dataDir, "ops.db")
	}
	abs, err := filepath.Abs(p)
	if err != nil {
		return "", fmt.Errorf("resolve db_path %q: %w", p, err)
	}
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		return "", fmt.Errorf("ensure db_path dir %s: %w", filepath.Dir(abs), err)
	}
	return abs, nil
}

// envOr 返回环境变量值，未设置时返回 fallback。
func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// boolishFalse 判断字符串是否表达"关闭"语义（0/false/no/off，大小写不敏感）。
func boolishFalse(v string) bool {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "0", "false", "no", "off":
		return true
	}
	return false
}
