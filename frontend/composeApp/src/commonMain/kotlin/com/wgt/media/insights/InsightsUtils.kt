package com.wgt.media


/**
 * 操作类型 → emoji 映射，用于"操作时间线"卡片每行前缀。
 *
 * 后端 [handleAuditTimeline] 的 action 字段取自 audit_log.action，记录时为动词
 * （upload/delete/share/rename/favorite/tag/restore/rotate 等）。此处按已知动作给 emoji；
 * 未知动作回退通用 ":memo:"，保证行不破。匹配对大小写不敏感，覆盖后端可能的小写埋点。
 */
internal fun auditActionEmoji(action: String): String = when (action.lowercase()) {
    "upload" -> "📤"
    "delete" -> "🗑️"
    "share" -> "🔗"
    "rename" -> "✏️"
    "favorite" -> "⭐"
    "tag" -> "🏷️"
    "restore" -> "♻️"
    "rotate" -> "🔄"
    else -> "📝"
}



/**
 * Double 保留 2 位小数（用于后端返回的 MB 数）。commonMain 无 `String.format`/`%.2f`，
 * 用 toString + take 截断实现（NaN/Infinity 原样返回）。
 */
internal fun formatDouble2(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return v.toString()
    val s = v.toString()
    val dot = s.indexOf('.')
    return if (dot < 0) s else s.take(dot + 3)
}



/** 把 0.0-1.0 比值格式化为百分比整数字符串（如 0.42 → "42%"）。commonMain 无 `String.format`。 */
internal fun formatPercent(ratio: Double): String {
    val r = if (ratio.isNaN() || ratio < 0) 0.0 else ratio
    return "${(r * 100.0).toInt()}%"
}

