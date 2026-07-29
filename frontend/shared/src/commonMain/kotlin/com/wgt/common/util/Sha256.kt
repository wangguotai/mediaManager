package com.wgt.common.util

/**
 * 纯 Kotlin 实现的 SHA-256。
 *
 * 为何不用 java.security.MessageDigest：本函数需在 KMP commonMain 中调用（同步去重在
 * feature-media / composeApp 共用），commonMain 禁止 java.* 与 android.*。故手写标准
 * FIPS 180-4 算法，无平台依赖、确定可移植（Android/iOS 结果一致），用于上传前对文件
 * 字节做内容指纹，配合 [com.wgt.media.Sha256Dedup] 避免同一张图片重复上传。
 *
 * 仅用于去重指纹（非安全场景），性能优先；单次几 MB 图片耗时仍在毫秒级，可接受。
 * 若后续有大批量哈希需求再考虑 expect/actual 走平台原生实现。
 *
 * 用法：`sha256(bytes)` 返回 64 字符小写十六进制串。
 */
private val SHA256_K = intArrayOf(
    0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(), 0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(), 0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(), 0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
    0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(), 0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
)

private val SHA256_H0 = intArrayOf(
    0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(), 0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab.toInt(),
    0x5be0cd19.toInt()
)

/**
 * 计算给定字节的 SHA-256，返回 64 位小写十六进制字符串。
 *
 * 算法实现要点：
 * - 填充：补 0x80 后续 0，末 8 字节为原始位长（按 SSE 字节序）。
 * - 按每 64 字节块处理；工作变量在每轮用 K 常量与消息调度字更新。
 * - 全程用 Int 模拟 32 位无符号运算（Kotlin Int 为有符号，加法/移位按位运算结果与
 *   无符号一致，只要后续不直接做大小比较即可）。
 */
fun sha256(data: ByteArray): String {
    val bitLen = (data.size.toLong() and 0xFFFFFFFFL) * 8L
    // 填充：原始 + 0x80 + 0...0 + 8 字节大端位长，对齐到 64 字节。
    val padLen = ((data.size + 8) / 64 + 1) * 64
    val padded = data.copyOf(padLen)
    padded[data.size] = 0x80.toByte()
    // 末 8 字节写位长（大端）。
    for (i in 0 until 8) {
        padded[padLen - 8 + i] = ((bitLen ushr (56 - 8 * i)) and 0xFFL).toByte()
    }

    val w = IntArray(64)
    var h0 = SHA256_H0[0]; var h1 = SHA256_H0[1]; var h2 = SHA256_H0[2]; var h3 = SHA256_H0[3]
    var h4 = SHA256_H0[4]; var h5 = SHA256_H0[5]; var h6 = SHA256_H0[6]; var h7 = SHA256_H0[7]

    var off = 0
    while (off < padLen) {
        // 前 16 字从字节块大端读入。
        for (i in 0 until 16) {
            val j = off + i * 4
            w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                ((padded[j + 1].toInt() and 0xFF) shl 16) or
                ((padded[j + 2].toInt() and 0xFF) shl 8) or
                (padded[j + 3].toInt() and 0xFF)
        }
        // 后 48 字扩展。
        for (i in 16 until 64) {
            val s0 = w[i - 15] rotateRight 7 xor (w[i - 15] rotateRight 18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2] rotateRight 17 xor (w[i - 2] rotateRight 19) xor (w[i - 2] ushr 10)
            w[i] = (w[i - 16] + s0 + w[i - 7] + s1)
        }

        var a = h0; var b = h1; var c = h2; var d = h3
        var e = h4; var f = h5; var g = h6; var h = h7
        for (i in 0 until 64) {
            val s1 = (e rotateRight 6) xor (e rotateRight 11) xor (e rotateRight 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = (h + s1 + ch + SHA256_K[i] + w[i])
            val s0 = (a rotateRight 2) xor (a rotateRight 13) xor (a rotateRight 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = (s0 + maj)
            h = g; g = f; f = e
            e = (d + t1)
            d = c; c = b; b = a
            a = (t1 + t2)
        }
        h0 += a; h1 += b; h2 += c; h3 += d
        h4 += e; h5 += f; h6 += g; h7 += h

        off += 64
    }

    // 输出 64 位小写十六进制。
    val sb = StringBuilder(64)
    for (hv in intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)) {
        sb.append(hv.toHex8())
    }
    return sb.toString()
}

/** 把 32 位整数转成 8 位大端小写十六进制串。 */
private fun Int.toHex8(): String {
    val chars = "0123456789abcdef"
    val sb = StringBuilder(8)
    for (shift in 28 downTo 0 step 4) {
        sb.append(chars[(this ushr shift) and 0xF])
    }
    return sb.toString()
}

private infix fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
