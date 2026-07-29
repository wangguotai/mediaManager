package config

import (
	"os"
	"path/filepath"
	"testing"
)

// TestLoadMissingFile 验证配置文件缺失时回退默认值且不报错。
func TestLoadMissingFile(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "nope.yaml"))
	if err != nil {
		t.Fatalf("Load missing file should not error: %v", err)
	}
	if cfg.DataDir != "./data" {
		t.Fatalf("default DataDir: got %q want ./data", cfg.DataDir)
	}
	// filepath.Join 会清理前导 "./"，故默认 DBPath 为 "data/media.db"（与
	// "./data/media.db" 语义等价，均相对 cwd）。
	if cfg.DBPath != "data/media.db" {
		t.Fatalf("default DBPath: got %q want data/media.db", cfg.DBPath)
	}
}

// TestLoadParsesValues 验证 YAML 字段被正确解析并覆盖默认。
func TestLoadParsesValues(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	content := "data_dir: /var/mm\ndb_path: /var/mm/custom.db\n"
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.DataDir != "/var/mm" {
		t.Fatalf("DataDir: got %q want /var/mm", cfg.DataDir)
	}
	if cfg.DBPath != "/var/mm/custom.db" {
		t.Fatalf("DBPath: got %q want /var/mm/custom.db", cfg.DBPath)
	}
}

// TestLoadEmptyDBPathDerives 验证 db_path 留空时派生到 <data_dir>/media.db。
func TestLoadEmptyDBPathDerives(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte("data_dir: /opt/data\n"), 0644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.DBPath != "/opt/data/media.db" {
		t.Fatalf("derived DBPath: got %q want /opt/data/media.db", cfg.DBPath)
	}
}

// TestDefaultSignupIsOff 验证无配置文件（与 Default）时 allow_signup 默认为最安全的 "off"。
func TestDefaultSignupIsOff(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "nope.yaml"))
	if err != nil {
		t.Fatalf("Load missing file should not error: %v", err)
	}
	if cfg.AllowSignup != SignupOff {
		t.Fatalf("default AllowSignup: got %q want off", cfg.AllowSignup)
	}
}

// TestLoadJWTFields 验证 jwt_secret / jwt_ttl_seconds / allow_signup 被解析与归一化。
func TestLoadJWTFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	content := "jwt_secret: s3cret\njwt_ttl_seconds: 3600\nallow_signup: OPEN\n"
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.JWTSecret != "s3cret" {
		t.Fatalf("JWTSecret: got %q want s3cret", cfg.JWTSecret)
	}
	if cfg.JWTTTLSeconds != 3600 {
		t.Fatalf("JWTTTLSeconds: got %d want 3600", cfg.JWTTTLSeconds)
	}
	// allow_signup 应被归一化为小写合法值。
	if cfg.AllowSignup != SignupOpen {
		t.Fatalf("AllowSignup: got %q want open", cfg.AllowSignup)
	}
}

// TestLoadSignupNormalizeUnknown 验证未知 allow_signup 值退化为 "off"。
func TestLoadSignupNormalizeUnknown(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte("allow_signup: yes\n"), 0644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.AllowSignup != SignupOff {
		t.Fatalf("unknown AllowSignup should normalize to off, got %q", cfg.AllowSignup)
	}
}
