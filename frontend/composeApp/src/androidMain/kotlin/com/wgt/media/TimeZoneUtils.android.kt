package com.wgt.media

import java.util.TimeZone

/**
 * Android 端：本机默认时区的标准偏移（毫秒）。
 *
 * [TimeZone.getDefault].rawOffset 返回该时区相对 UTC 的标准偏移（毫秒），
 * 不含 DST 当时的临时调整——对"按本地午夜划日界"已足够。
 */
actual fun systemTimeZoneOffsetMillis(): Long = TimeZone.getDefault().rawOffset.toLong()

/**
 * Android 端：直接读 [System.currentTimeMillis]。
 */
actual fun nowEpochMillis(): Long = System.currentTimeMillis()
