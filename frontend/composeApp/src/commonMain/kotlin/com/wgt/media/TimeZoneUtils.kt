package com.wgt.media

/**
 * 当前设备时区相对 UTC 的偏移量（毫秒）。
 *
 * commonMain 无 java.time，无法直接拿到本地时区偏移；日期分组/详情面板此前用固定 0L
 * 兜底，导致 UTC+8 晚间的媒体被算到次日的分组、详情面板时间也按 UTC 展示。
 * 故改由各平台 actual 提供真实偏移：
 *   - Android：[java.util.TimeZone.getDefault].rawOffset
 *   - iOS：[platform.Foundation.NSTimeZone.defaultTimeZone].secondsFromGMT * 1000
 *
 * 返回值为标准偏移（rawOffset / secondsFromGMT），不含 DST 临时分量，足够把
 * epoch 毫秒对齐到本地午夜做"今天/昨天"判定与时分展示。
 */
expect fun systemTimeZoneOffsetMillis(): Long

/**
 * 当前时间的 Unix 毫秒。
 *
 * commonMain 拿不到 `System.currentTimeMillis()`（JVM-only）也不想引 kotlinx-datetime，
 * 故由各平台 actual 提供本机时钟读数：
 *   - Android：[System.currentTimeMillis]
 *   - iOS：[platform.Foundation.NSDate].[timeIntervalSince1970] * 1000
 *
 * 供活动流卡片 [MediaListScreen] 的 `relativeTime` 计算与"现在"的差值。
 */
expect fun nowEpochMillis(): Long
