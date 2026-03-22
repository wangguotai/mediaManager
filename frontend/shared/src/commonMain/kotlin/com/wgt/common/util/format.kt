package com.wgt.common.util

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 格式化文件大小为MB单位
 * @param bytes 文件大小（字节）
 * @param decimalPlaces 保留小数点位数，默认为1
 * @return 格式化后的字符串，如 "10.5 MB", "20.0 MB", "3.2 MB"
 */
fun formatBytesToMB(bytes: Double, decimalPlaces: Int = 1): String {
    val sizeMB = bytes / (1024.0 * 1024.0)
    val rounded = roundToDecimalPlaces(sizeMB, decimalPlaces)
    return "$rounded MB"
}

/**
 * 格式化文件大小为MB单位（Long版本）
 * @param bytes 文件大小（字节）
 * @param decimalPlaces 保留小数点位数，默认为1
 * @return 格式化后的字符串
 */
fun formatBytesToMB(bytes: Long, decimalPlaces: Int = 1): String {
    return formatBytesToMB(bytes.toDouble(), decimalPlaces)
}

/**
 * 将数字四舍五入到指定小数位数
 * @param value 原始值
 * @param decimalPlaces 小数位数
 * @return 四舍五入后的值
 */
private fun roundToDecimalPlaces(value: Double, decimalPlaces: Int): Double {
    if (decimalPlaces <= 0) {
        return value.roundToLong().toDouble()
    }
    val factor = 10.0.pow(decimalPlaces)
    return (value * factor).roundToInt() / factor
}
