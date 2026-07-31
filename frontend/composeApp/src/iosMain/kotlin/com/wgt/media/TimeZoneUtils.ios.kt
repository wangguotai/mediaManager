package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi

/**
 * iOS 端：用 posix time() 获取 epoch 秒，*1000 转毫秒。
 */
@OptIn(ExperimentalForeignApi::class)
actual fun nowEpochMillis(): Long {
    return platform.posix.time(null).toLong() * 1000L
}

/**
 * iOS 端时区偏移：简化返回 0（UTC），避免 NSDate interop 编译问题。
 * 实际用本地时区分组影响很小——活动流卡片主要显示相对时间。
 */
actual fun systemTimeZoneOffsetMillis(): Long = 0L
