package com.wgt.media

import platform.Foundation.NSTimeZone
import platform.Foundation.defaultTimeZone
import platform.Foundation.secondsFromGMT

/**
 * iOS 端：本机默认时区相对 GMT 的偏移（毫秒）。
 *
 * [NSTimeZone.defaultTimeZone].secondsFromGMT 返回秒，*1000 转毫秒，
 * 与 Android 端 [systemTimeZoneOffsetMillis] 口径一致，供 commonMain 的
 * 日期分组与详情面板把 epoch 毫秒对齐到本地。
 */
actual fun systemTimeZoneOffsetMillis(): Long =
    NSTimeZone.defaultTimeZone().secondsFromGMT() * 1000L

/**
 * iOS 端：[platform.Foundation.NSDate].timeIntervalSince1970 返回秒（Double），
 * *1000 转毫秒取整，与 Android [nowEpochMillis] 口径一致。
 */
actual fun nowEpochMillis(): Long =
    (platform.Foundation.NSDate().timeIntervalSince1970() * 1000.0).toLong()
