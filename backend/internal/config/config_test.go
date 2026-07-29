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

// TestDefaultPort 验证默认 Port 为 8080（无配置文件场景）。
func TestDefaultPort(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "nope.yaml"))
	if err != nil {
		t.Fatalf("Load missing file: %v", err)
	}
	if cfg.Port != "8080" {
		t.Fatalf("default Port: got %q want 8080", cfg.Port)
	}
	if cfg.OpsServerURL != "" {
		t.Fatalf("default OpsServerURL should be empty, got %q", cfg.OpsServerURL)
	}
}

// TestLoadPortAndOps 验证 port / ops_server_url 被解析，且 port 去掉前导冒号归一。
func TestLoadPortAndOps(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	content := "port: \":9000\"\nops_server_url: http://ops.example/api\n"
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if cfg.Port != "9000" {
		t.Fatalf("Port: got %q want 9000 (leading colon stripped)", cfg.Port)
	}
	if cfg.OpsServerURL != "http://ops.example/api" {
		t.Fatalf("OpsServerURL: got %q", cfg.OpsServerURL)
	}
}

// setEnv 是测试用的环境变量设置+回滚助手，t.Cleanup 自动还原每个变量。
func setEnv(t *testing.T, kvs ...string) {
	t.Helper()
	for i := 0; i+1 < len(kvs); i += 2 {
		key, val := kvs[i], kvs[i+1]
		old, ok := os.LookupEnv(key)
		os.Setenv(key, val)
		t.Cleanup(func() {
			if ok {
				os.Setenv(key, old)
			} else {
				os.Unsetenv(key)
			}
		})
	}
}

// TestApplyEnvOverrides 验证 MM_* 环境变量覆盖 config.yaml 各字段。
func TestApplyEnvOverrides(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	content := "port: \"8080\"\ndata_dir: ./data\ndb_path: ./data/media.db\njwt_secret: fromfile\n" +
		"jwt_ttl_seconds: 3600\nallow_signup: off\nops_server_url: http://file\n"
	if err := os.WriteFile(path, []byte(content), 0644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}

	setEnv(t,
		"MM_PORT", "9001",
		"MM_DATA_DIR", "/var/mm",
		"MM_DB_PATH", "/var/mm/custom.db",
		"MM_JWT_SECRET", "fromenv",
		"MM_JWT_TTL_SECONDS", "7200",
		"MM_ALLOW_SIGNUP", "open",
		"MM_OPS_SERVER_URL", "http://env",
	)
	cfg.ApplyEnv()

	if cfg.Port != "9001" {
		t.Fatalf("Port: got %q want 9001", cfg.Port)
	}
	if cfg.DataDir != "/var/mm" {
		t.Fatalf("DataDir: got %q want /var/mm", cfg.DataDir)
	}
	if cfg.DBPath != "/var/mm/custom.db" {
		t.Fatalf("DBPath: got %q want /var/mm/custom.db", cfg.DBPath)
	}
	if cfg.JWTSecret != "fromenv" {
		t.Fatalf("JWTSecret: got %q want fromenv", cfg.JWTSecret)
	}
	if cfg.JWTTTLSeconds != 7200 {
		t.Fatalf("JWTTTLSeconds: got %d want 7200", cfg.JWTTTLSeconds)
	}
	if cfg.AllowSignup != SignupOpen {
		t.Fatalf("AllowSignup: got %q want open", cfg.AllowSignup)
	}
	if cfg.OpsServerURL != "http://env" {
		t.Fatalf("OpsServerURL: got %q want http://env", cfg.OpsServerURL)
	}
}

// TestApplyEnvDataDirRecomputesDBPath 验证仅设 MM_DATA_DIR（不设 MM_DB_PATH）时，
// DBPath 跟随新 data_dir 重算为 <data_dir>/media.db。
func TestApplyEnvDataDirRecomputesDBPath(t *testing.T) {
	cfg, err := Load(filepath.Join(t.TempDir(), "nope.yaml"))
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	setEnv(t, "MM_DATA_DIR", "/opt/data")
	cfg.ApplyEnv()
	if cfg.DataDir != "/opt/data" {
		t.Fatalf("DataDir: got %q want /opt/data", cfg.DataDir)
	}
	want := "/opt/data/media.db"
	if filepath.ToSlash(cfg.DBPath) != want {
		t.Fatalf("DBPath should recompute to %q, got %q", want, cfg.DBPath)
	}
}

// TestApplyEnvInvalidTTLIgnored 验证 MM_JWT_TTL_SECONDS 非法值不破坏原值。
func TestApplyEnvInvalidTTLIgnored(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte("jwt_ttl_seconds: 3600\n"), 0644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	setEnv(t, "MM_JWT_TTL_SECONDS", "not-a-number")
	cfg.ApplyEnv()
	if cfg.JWTTTLSeconds != 3600 {
		t.Fatalf("invalid TTL should be ignored, got %d want 3600", cfg.JWTTTLSeconds)
	}
}

// TestApplyEnvEmptyKeepsFile 验证空环境变量不覆盖文件值（"存在且非空才覆盖"语义）。
func TestApplyEnvEmptyKeepsFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte("port: \"8080\"\njwt_secret: fromfile\n"), 0644); err != nil {
		t.Fatalf("write config: %v", err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	// 设为空串——不应覆盖。
	setEnv(t,
		"MM_PORT", "",
		"MM_JWT_SECRET", "",
	)
	cfg.ApplyEnv()
	if cfg.Port != "8080" {
		t.Fatalf("empty MM_PORT should not override, got %q", cfg.Port)
	}
	if cfg.JWTSecret != "fromfile" {
		t.Fatalf("empty MM_JWT_SECRET should not override, got %q", cfg.JWTSecret)
	}
}
