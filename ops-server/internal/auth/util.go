package auth

import (
	"crypto/sha256"
	"encoding/hex"
)

// sha256Hex 返回字符串的 SHA-256 hex 摘要，用作 server token 的存储 hash。
// SHA-256 单射特性使"按 hash 查询"等价于按明文查询，无需遍历比对。
func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}
