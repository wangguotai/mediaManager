// Package config 加载后端运行配置（config.yaml），目前仅暴露数据目录、SQLite
// 数据库路径与 JWT 认证相关项，供存储层、MediaService 与认证层复用。
//
// 设计取舍：保持极简。config.yaml 缺失或字段留空时回退到合理默认值，确保
// 旧调用方（main.go 硬编码 ./data）即便不接 config 也能继续工作。
package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"gopkg.in/yaml.v3"
)

// 注册开放模式字符串常量。AllowSignup 经 normalizeSignup 归一后必为三者之一。
const (
	SignupOff   = "off"   // 禁止自助注册（默认，最安全——需运维/管理员建号）。
	SignupFirst = "first" // 仅当 user 表为空时允许注册首个账号（自动 admin），其后关闭。
	SignupOpen  = "open"  // 任意人可注册（角色固定为 user）。
)

// Config 描述后端运行期可配置项。
type Config struct {
	// DataDir 是数据根目录（uploads / thumbnails / cloud-images 等子目录的父目录）。
	// 为空时默认 ./data。
	DataDir string `yaml:"data_dir"`
	// DBPath 是 SQLite 数据库文件路径。为空时默认 <DataDir>/media.db
	//（与 config.example.yaml 的默认口径一致）。
	DBPath string `yaml:"db_path"`
	// Port 是 REST gateway 监听端口（仅端口号，如 "8080"）。为空默认 8080。
	// main.go 据此拼成 ":8080" 监听地址。gRPC 端口(:50051)暂不此间配置——
	// 容器内仅暴露 REST，gRPC 仅供后端进程内部，不对外。
	Port string `yaml:"port"`
	// OpsServerURL 是运维管理面（OpenClaw 网关）地址，供 /api/openclaw/command 转发。
	// 可选：留空时沿用 main.go 中 OPENCLAW_GATEWAY_URL 环境变量或默认本地地址。
	OpsServerURL string `yaml:"ops_server_url"`

	// JWTSecret 是 JWT HS256 签名密钥。为空时服务启动会生成一份内存随机密钥
	//（重启后失效、已签发的 token 全部作废），仅适合开发；生产必须显式配置。
	JWTSecret string `yaml:"jwt_secret"`
	// JWTTTLSeconds 是 token 有效期（秒）。<=0 时取默认 7 天（604800）。
	JWTTTLSeconds int `yaml:"jwt_ttl_seconds"`
	// AllowSignup 控制是否允许自助注册，取值 off / first / open（见上方常量）。
	// 留空或非法值等价于 "off"。
	AllowSignup string `yaml:"allow_signup"`
}

// defaultPort 是 REST gateway 的默认端口。容器与本地直跑均沿用此值。
const defaultPort = "8080"

// Default 返回填入默认值的 Config：DataDir=./data，DBPath=./data/media.db，
// Port=8080，AllowSignup=off（最安全默认，阻止自助注册）。
func Default() *Config {
	dataDir := "./data"
	return &Config{
		DataDir:     dataDir,
		DBPath:      filepath.Join(dataDir, "media.db"),
		Port:        defaultPort,
		AllowSignup: SignupOff,
	}
}

// Load 从 path 读取并解析 config.yaml。path 不存在时等价于 Default()（不报错），
// 便于无配置文件直接启动。解析失败或字段非法时返回 error。
//
// 解析后会对 DataDir / DBPath 做空值补全：DataDir 留空用默认；DBPath 留空则
// 解析为 <DataDir>/media.db。JWT 相关字段直接透传，allow_signup 做归一化。
func Load(path string) (*Config, error) {
	cfg := Default()

	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			// 无配置文件视为正常，沿用默认值。
			return cfg, nil
		}
		return nil, fmt.Errorf("read config %s: %w", path, err)
	}

	// 用默认值作为反序列化底座，yaml 中未出现的字段保留默认。
	var parsed Config
	if err := yaml.Unmarshal(data, &parsed); err != nil {
		return nil, fmt.Errorf("parse config %s: %w", path, err)
	}
	if parsed.DataDir != "" {
		cfg.DataDir = parsed.DataDir
	}
	if parsed.DBPath != "" {
		cfg.DBPath = parsed.DBPath
	} else {
		// DBPath 未指定时落在 DataDir 下，与 Default() 口径一致（media.db）。
		cfg.DBPath = filepath.Join(cfg.DataDir, "media.db")
	}
	// Port：显式空串/省略保持默认 8080；非空透传（去掉可能的前导冒号，统一为纯端口号）。
	if p := normalizePort(parsed.Port); p != "" {
		cfg.Port = p
	}
	if parsed.OpsServerURL != "" {
		cfg.OpsServerURL = parsed.OpsServerURL
	}
	// JWT 字段透传；allow_signup 归一化为合法三态之一，便于下游直接比较。
	cfg.JWTSecret = parsed.JWTSecret
	cfg.JWTTTLSeconds = parsed.JWTTTLSeconds
	cfg.AllowSignup = normalizeSignup(parsed.AllowSignup)
	return cfg, nil
}

// normalizePort 去掉监听地址形态（如 ":8080"）中的前导冒号，统一成纯端口号字符串。
// 空串原样返回（由调用方决定是否回退默认）。非数字字符保留不强制校验，交由 net.Listen 兜底。
func normalizePort(p string) string {
	p = strings.TrimSpace(p)
	p = strings.TrimPrefix(p, ":")
	return p
}

// ApplyEnv 用 MM_* 前缀的环境变量覆盖已加载的配置，返回新的覆盖后 Config（不修改接收者外的状态）。
//
// 约定：每个字段对应一个环境变量，存在且非空即覆盖；未设置或空串保持文件/默认值。
//   - MM_PORT              -> Port
//   - MM_DATA_DIR          -> DataDir（同时据此重算 DBPath 默认，仅当 DBPath 未被显式覆盖时）
//   - MM_DB_PATH           -> DBPath
//   - MM_JWT_SECRET        -> JWTSecret
//   - MM_JWT_TTL_SECONDS   -> JWTTTLSeconds（解析失败忽略，保留原值）
//   - MM_ALLOW_SIGNUP      -> AllowSignup（经 normalizeSignup 归一化）
//   - MM_OPS_SERVER_URL    -> OpsServerURL
//
// 该函数在 config.yaml 加载完成后调用，是"文件 < 环境变量"覆盖链的最后一环，
// 既支持 compose/k8s 注入，也支持本地 `MM_PORT=9000 ./server` 临时覆盖。
func (c *Config) ApplyEnv() {
	if v := os.Getenv("MM_PORT"); v != "" {
		if p := normalizePort(v); p != "" {
			c.Port = p
		}
	}
	if v := os.Getenv("MM_DATA_DIR"); v != "" {
		c.DataDir = v
		// DataDir 变更后，若 DBPath 未被独立设置，重新派生为 <DataDir>/media.db。
		// 注意：此时无法区分"DBPath 是默认派生"还是"文件显式设了同值"，故当
		// MM_DB_PATH 未提供时一律重算——对绝大多数"只改 data_dir"的部署足够且符合直觉。
		if os.Getenv("MM_DB_PATH") == "" {
			c.DBPath = filepath.Join(c.DataDir, "media.db")
		}
	}
	if v := os.Getenv("MM_DB_PATH"); v != "" {
		c.DBPath = v
	}
	if v := os.Getenv("MM_JWT_SECRET"); v != "" {
		c.JWTSecret = v
	}
	if v := os.Getenv("MM_JWT_TTL_SECONDS"); v != "" {
		if n, err := parseIntStrict(v); err == nil {
			c.JWTTTLSeconds = n
		}
	}
	if v := os.Getenv("MM_ALLOW_SIGNUP"); v != "" {
		c.AllowSignup = normalizeSignup(v)
	}
	if v := os.Getenv("MM_OPS_SERVER_URL"); v != "" {
		c.OpsServerURL = v
	}
}

// parseIntStrict 把字符串解析为 int；非法返回错误。os 没有直接提供，此处小封装。
func parseIntStrict(s string) (int, error) {
	var n int
	_, err := fmt.Sscanf(s, "%d", &n)
	return n, err
}

// normalizeSignup 把配置值归一为合法的 signup 模式之一。
// 空串或未知值退化为 "off"（最安全默认：不允许自助注册）。
func normalizeSignup(v string) string {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "first", "open", "off":
		return strings.ToLower(strings.TrimSpace(v))
	default:
		return SignupOff
	}
}

// ResolveDataDir 确保 DataDir 目录存在（含其父目录），返回绝对化后的 DataDir。
func (c *Config) ResolveDataDir() (string, error) {
	if c.DataDir == "" {
		c.DataDir = "./data"
	}
	abs, err := filepath.Abs(c.DataDir)
	if err != nil {
		return "", fmt.Errorf("resolve data_dir: %w", err)
	}
	if err := os.MkdirAll(abs, 0755); err != nil {
		return "", fmt.Errorf("create data_dir %s: %w", abs, err)
	}
	c.DataDir = abs
	return abs, nil
}

// ResolveDBPath 确保 DBPath 的父目录存在并返回绝对化后的路径。
// 若 DBPath 为空，按 <DataDir>/media.db 推导。
func (c *Config) ResolveDBPath() (string, error) {
	if c.DBPath == "" {
		if c.DataDir == "" {
			c.DataDir = "./data"
		}
		c.DBPath = filepath.Join(c.DataDir, "media.db")
	}
	abs, err := filepath.Abs(c.DBPath)
	if err != nil {
		return "", fmt.Errorf("resolve db_path: %w", err)
	}
	if err := os.MkdirAll(filepath.Dir(abs), 0755); err != nil {
		return "", fmt.Errorf("create db_path dir %s: %w", filepath.Dir(abs), err)
	}
	c.DBPath = abs
	return abs, nil
}
