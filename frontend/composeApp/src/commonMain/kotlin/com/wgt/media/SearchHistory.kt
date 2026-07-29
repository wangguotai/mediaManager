package com.wgt.media

/**
 * 搜索历史管理：最近 10 条搜索词，持久化到 SettingsStorage。
 *
 * 纯 Kotlin JSON 序列化（无依赖），存为逗号分隔转义字符串。
 * 重复搜索词不重复记录，移到最前。
 */
object SearchHistory {
    private const val KEY = "search_history"
    private const val MAX_SIZE = 10
    private const val SEPARATOR = "\u0001" // 不会出现在搜索词中的分隔符

    /**
     * 加载历史搜索词列表（最近在前）。
     */
    fun load(): List<String> {
        val raw = SettingsStorage().getString(KEY, "")
        if (raw.isBlank()) return emptyList()
        return raw.split(SEPARATOR).filter { it.isNotBlank() }
    }

    /**
     * 添加搜索词到历史。重复词移到最前，超过 MAX_SIZE 截断。
     */
    fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val current = load().toMutableList()
        current.remove(q)
        current.add(0, q)
        val truncated = current.take(MAX_SIZE)
        SettingsStorage().putString(KEY, truncated.joinToString(SEPARATOR))
    }

    /**
     * 清空所有搜索历史。
     */
    fun clear() {
        SettingsStorage().putString(KEY, "")
    }
}
