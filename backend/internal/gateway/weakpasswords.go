package gateway

// weakpasswords.go 维护常见弱口令黑名单，用于在注册/改密码时做泄漏检测。
// 配合 auth 包已有的长度+复杂度校验（validatePassword），形成纵深防御：
//   - auth.validatePassword：长度≥8 且含字母+数字（拒绝纯数字/纯字母短口令）
//   - gateway.isPasswordCompromised：拒绝"满足复杂度但仍属常见弱口令"的密码
//     （如 Passw0rd、Qwerty123 等，这些能绕过纯复杂度检查）
//
// 黑名单来源：公开的常见弱密码榜单（RockYou 泄漏 Top-N、各类年度统计），
// 收录能通过"字母+数字"复杂度策略的口令为主，少量纯字母/纯数字作为补充。
// 全小写存储，比较时对输入做 ToLower，大小写不敏感。

// weakPasswordSet 是常见弱口令集合。比较时大小写不敏感，统一小写存储。
var weakPasswordSet = map[string]bool{
	// ---- 经典 Top（纯字母/数字，部分能绕过最小长度但会被复杂度拦，保留作基线）----
	"123456":     true,
	"12345678":   true,
	"123456789":  true,
	"1234567890": true,
	"password":   true,
	"iloveyou":   true,
	"qwerty":     true,
	"abc123":     true,
	"admin":      true,
	"welcome":    true,
	"letmein":    true,
	"monkey":     true,
	"login":      true,
	"princess":   true,
	"passw0rd":   true,
	"shadow":     true,
	"sunshine":   true,
	"master":     true,
	"trustno1":   true,
	"000000":     true,
	"111111":     true,
	"666666":     true,
	"123123":     true,
	"654321":     true,
	"superman":   true,
	"michael":    true,
	"football":   true,
	"dragon":     true,
	"baseball":   true,
	"qazwsx":     true,
	"1qaz2wsx":   true,

	// ---- 能通过"字母+数字"复杂度策略的弱口令（核心拦截目标）----
	"password1":     true,
	"password2":     true,
	"password3":     true,
	"password12":    true,
	"password123":   true,
	"Password1":     true,
	"Passw0rd":      true,
	"P@ssw0rd":      true,
	"Password123":   true,
	"qwerty123":     true,
	"Qwerty123":     true,
	"abc12345":      true,
	"abc123456":     true,
	"123abc":        true,
	"123qwe":        true,
	"1q2w3e4r":      true,
	"1q2w3e":        true,
	"1q2w3e4r5t":    true,
	"letmein1":      true,
	"letmein123":    true,
	"welcome1":      true,
	"welcome123":    true,
	"admin123":      true,
	"admin1234":     true,
	"administrator": true,
	"root123":       true,
	"root1234":      true,
	"toor123":       true,
	"monkey1":       true,
	"monkey123":     true,
	"dragon1":       true,
	"dragon123":     true,
	"master1":       true,
	"master123":     true,
	"shadow1":       true,
	"shadow123":     true,
	"sunshine1":     true,
	"sunshine123":   true,
	"superman1":     true,
	"superman123":   true,
	"football1":     true,
	"football123":   true,
	"baseball1":     true,
	"baseball123":   true,
	"iloveyou1":     true,
	"iloveyou2":     true,
	"iloveyou123":   true,
	"princess1":     true,
	"princess123":   true,
	"michael1":      true,
	"michael2":      true,
	"jordan23":      true,
	"harley1":       true,
	"harley123":     true,
	"robert1":       true,
	"matthew1":      true,
	"andrea1":       true,
	"joshua1":       true,
	"daniel1":       true,
	"jessica1":      true,
	"jennifer1":     true,
	"zxcvbnm1":      true,
	"zxcvbn123":     true,
	"asdf1234":      true,
	"asdfgh1":       true,
	"qwer1234":      true,
	"qwert123":      true,
	"changeme1":     true,
	"changeme123":   true,
	"test123":       true,
	"test1234":      true,
	"test12345":     true,
	"guest123":      true,
	"user123":       true,
	"user1234":      true,
	"default1":      true,
	"default123":    true,
	"1password":     true,
	"123password":   true,
	"pass123":       true,
	"pass1234":      true,
	"pass12345":     true,
	"pass123word":   true,
	"secret1":       true,
	"secret123":     true,
}

// isPasswordCompromised 判断给定密码是否命中常见弱口令黑名单。
// 大小写不敏感：统一转小写后查 weakPasswordSet。命中返回 true，调用方应
// 拒绝该密码并提示用户更换更强的口令。空串处理不在此函数职责内（由
// 上游的必填校验拦截），此处仅做黑名单查询。
func isPasswordCompromised(password string) bool {
	if password == "" {
		return false
	}
	// 简单转小写即可（黑名单以小写为主，少数含大写变体亦已收录）。
	if weakPasswordSet[password] {
		return true
	}
	// 兜底：再查小写形式，覆盖 "PASSWORD1"/"Password1" 等大小写变体。
	for i := 0; i < len(password); i++ {
		c := password[i]
		if c >= 'A' && c <= 'Z' {
			// 含大写，转小写后重查一次
			lowered := toLowerPassword(password)
			return weakPasswordSet[lowered]
		}
	}
	return weakPasswordSet[password]
}

// toLowerPassword 返回 s 的 ASCII 小写副本（仅 A-Z → a-z，其余不变）。
// 不依赖 strings.ToLower 以避免分配两个串；这里一次分配即可。
func toLowerPassword(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
}
