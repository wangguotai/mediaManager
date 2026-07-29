package com.wgt.media

/**
 * SHA-256 哈希 — 平台特定实现。
 * Android: java.security.MessageDigest
 * iOS: expect/actual bridge
 */

/**
 * 计算 ByteArray 的 SHA-256，返回小写十六进制串。
 */
expect fun sha256Hex(data: ByteArray): String
