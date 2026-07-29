package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Hex(data: ByteArray): String {
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
    data.usePinned { input ->
        digest.usePinned { output ->
            CC_SHA256(input.addressOf(0), data.size.toUInt(), output.addressOf(0))
        }
    }
    val sb = StringBuilder()
    for (b in digest) {
        sb.append(if (b < 16u) "0" else "").append(b.toString(16))
    }
    return sb.toString()
}
