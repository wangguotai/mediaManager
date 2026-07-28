package com.wgt.media

/**
 * 收藏状态本地持久化存储。
 *
 * 用 [SettingsStorage]（expect/actual 平台键值存储）持久化一组被收藏的 mediaId。
 * 存储格式：以逗号分隔的 id 字符串（如 "id1,id2,id3"），读写均在主线程同步完成，
 * 与 [SettingsStorage] 的线程安全保证一致（SharedPreferences / NSUserDefaults 均线程安全）。
 *
 * 不依赖 java 或 android API，commonMain 安全。
 *
 * 生命周期：由 [MediaViewModel] 在初始化时读取、在 toggleFavorite 时写入；
 * UI 通过 ViewModel 的 [MediaViewModel.favoriteIds] 状态观察变化。
 */
object FavoriteStore {

    /** SettingsStorage 中收藏列表的键名。 */
    private const val KEY_FAVORITE_IDS = "favorite_ids"

    /** 分隔符：选用逗号，简单且 mediaId 不含逗号（后端生成的 id 为数字或 UUID）。 */
    private const val SEPARATOR = ","

    /**
     * 从持久化存储读取收藏的 mediaId 集合。
     *
     * @return 不可变 Set；存储为空或未初始化时返回空集。
     */
    fun loadFavoriteIds(): Set<String> {
        val storage = SettingsStorage()
        val raw = storage.getString(KEY_FAVORITE_IDS, "")
        if (raw.isBlank()) return emptySet()
        return raw.split(SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 将收藏的 mediaId 集合写入持久化存储。
     * 覆盖式写入：直接以新集合替换旧值。
     *
     * @param ids 当前完整的收藏 id 集合
     */
    fun saveFavoriteIds(ids: Set<String>) {
        val storage = SettingsStorage()
        val raw = ids.joinToString(SEPARATOR)
        storage.putString(KEY_FAVORITE_IDS, raw)
    }
}
