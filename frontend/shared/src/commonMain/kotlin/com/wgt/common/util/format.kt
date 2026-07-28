package com.wgt.common.util

import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 自适应格式化文件大小：
 * - <1KB 显示 B
 * - <1MB 显示 KB
 * - ≥1MB 显示 MB
 *
 * @param bytes 文件大小（字节）
 * @param decimalPlaces 保留小数点位数，默认为1
 * @return 格式化后的字符串，如 "512 B", "10.5 KB", "20.0 MB"
 */
fun formatBytesToMB(bytes: Double, decimalPlaces: Int = 1): String {
    val rounded = roundToDecimalPlaces(bytes, 0).toLong()
    return when {
        rounded < 1024L -> "$rounded B"
        rounded < 1024L * 1024L -> {
            val sizeKB = bytes / 1024.0
            "${roundToDecimalPlaces(sizeKB, decimalPlaces)} KB"
        }
        else -> {
            val sizeMB = bytes / (1024.0 * 1024.0)
            "${roundToDecimalPlaces(sizeMB, decimalPlaces)} MB"
        }
    }
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
