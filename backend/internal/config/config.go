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

	// JWTSecret 是 JWT HS256 签名密钥。为空时服务启动会生成一份内存随机密钥
	//（重启后失效、已签发的 token 全部作废），仅适合开发；生产必须显式配置。
	JWTSecret string `yaml:"jwt_secret"`
	// JWTTTLSeconds 是 token 有效期（秒）。<=0 时取默认 7 天（604800）。
	JWTTTLSeconds int `yaml:"jwt_ttl_seconds"`
	// AllowSignup 控制是否允许自助注册，取值 off / first / open（见上方常量）。
	// 留空或非法值等价于 "off"。
	AllowSignup string `yaml:"allow_signup"`
}

// Default 返回填入默认值的 Config：DataDir=./data，DBPath=./data/media.db，
// AllowSignup=off（最安全默认，阻止自助注册）。
func Default() *Config {
	dataDir := "./data"
	return &Config{
		DataDir:     dataDir,
		DBPath:      filepath.Join(dataDir, "media.db"),
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
	// JWT 字段透传；allow_signup 归一化为合法三态之一，便于下游直接比较。
	cfg.JWTSecret = parsed.JWTSecret
	cfg.JWTTTLSeconds = parsed.JWTTTLSeconds
	cfg.AllowSignup = normalizeSignup(parsed.AllowSignup)
	return cfg, nil
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
