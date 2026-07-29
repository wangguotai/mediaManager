// Package config 加载后端运行配置（config.yaml），目前仅暴露数据目录与 SQLite
// 数据库路径，供后续 SQLite 元数据存储层与 MediaService 复用。
//
// 设计取舍：保持极简。config.yaml 缺失或字段留空时回退到合理默认值，确保
// 旧调用方（main.go 硬编码 ./data）即便不接 config 也能继续工作。
package config

import (
	"fmt"
	"os"
	"path/filepath"

	"gopkg.in/yaml.v3"
)

// Config 描述后端运行期可配置项。
type Config struct {
	// DataDir 是数据根目录（uploads / thumbnails / cloud-images 等子目录的父目录）。
	// 为空时默认 ./data。
	DataDir string `yaml:"data_dir"`
	// DBPath 是 SQLite 数据库文件路径。为空时默认 <DataDir>/media.db
	//（与 config.example.yaml 的默认口径一致）。
	DBPath string `yaml:"db_path"`
}

// Default 返回填入默认值的 Config：DataDir=./data，DBPath=./data/media.db。
func Default() *Config {
	dataDir := "./data"
	return &Config{
		DataDir: dataDir,
		DBPath:  filepath.Join(dataDir, "media.db"),
	}
}

// Load 从 path 读取并解析 config.yaml。path 不存在时等价于 Default()（不报错），
// 便于无配置文件直接启动。解析失败或字段非法时返回 error。
//
// 解析后会对 DataDir / DBPath 做空值补全：DataDir 留空用默认；DBPath 留空则
// 解析为 <DataDir>/media.db。
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
	return cfg, nil
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
