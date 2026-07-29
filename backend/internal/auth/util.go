package auth

import (
	"crypto/rand"
	"strings"

	"github.com/google/uuid"
)

// randomBytes 返回 n 字节密码学随机数据，用于无配置时生成进程级 JWT 密钥。
// 若 rand 失败（极罕见，通常系统熵池故障）panic——启动期失败优于静默弱密钥。
func randomBytes(n int) []byte {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic("auth: crypto/rand failed: " + err.Error())
	}
	return b
}

// newUUID 生成随机 UUID v4 字符串，作为用户主键。
func newUUID() string {
	return uuid.NewString()
}

// isUniqueViolation 判断错误是否为唯一约束冲突（用户名已存在）。
// modernc.org/sqlite 的错误信息含 "UNIQUE constraint failed"，做子串匹配；
// 对非 sqlite 错误保守返回 false（交由上层当普通错误处理）。
func isUniqueViolation(err error) bool {
	if err == nil {
		return false
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "unique constraint failed") ||
		(strings.Contains(msg, "unique") && strings.Contains(msg, "constraint"))
}
