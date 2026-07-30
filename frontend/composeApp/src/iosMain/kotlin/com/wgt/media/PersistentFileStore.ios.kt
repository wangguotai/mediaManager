package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.getBytes
import platform.Foundation.writeToURL

/**
 * iOS 平台 [PersistentFileStore] 实现：落盘到 App sandbox 的 Documents 目录，
 * 以 [name] 为文件名的纯文本（JSON）文件。
 *
 * V5：从 NSUserDefaults 改为文件方案。原因：[PersistentFileStore] 承载的是
 * 增量同步 cursor、上传去重 manifest、离线上传队列——它们是"会增长、需整体覆盖写"
 * 的小型结构化数据。NSUserDefaults 适合零散键值且对单值原子性敏感，但把整段 JSON
 * 塞进一个键后，既无法被 iTunes/Xcode 直观导出排查，又与 Android 端（filesDir 文件）
 * 的存储模型不一致，跨平台行为对齐困难。改为 Documents 下 JSON 文件后：
 * - 与 [SettingsStorage] 的 secure 文件方案同构，存储模型统一；
 * - [write] 用临时文件 + 原子写入（[NSURL.writeToURL] 的 atomically），避免半截文件；
 * - Documents 受 sandbox 保护，仅本 App 可访问。
 *
 * 语义对齐契约：[read] 文件不存在/读失败返回 null；[write] 失败静默（调用方视为"未落盘"，
 * 不阻断入队等业务流，与 Android 实现一致）。
 *
 * 平台 API 注意：[NSData]/[NSURL] 的相关方法属 `ExperimentalForeignApi`，本文件 opt-in。
 */
@OptIn(ExperimentalForeignApi::class)
actual object PersistentFileStore {

    /** Documents 目录 URL，惰性获取。sandbox 下 Documents 总存在，nil 作防御。 */
    private fun documentsUrl(): NSURL? =
        (NSFileManager.defaultManager()
            .URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
            .firstOrNull() as? NSURL)

    /** 目标文件 URL：Documents/<name>。 */
    private fun fileUrl(name: String): NSURL? =
        documentsUrl()?.URLByAppendingPathComponent(name)

    actual fun read(name: String): String? {
        val url = fileUrl(name) ?: return null
        return try {
            val data = NSData.dataWithContentsOfURL(url) ?: return null
            if (data.length == 0UL) return "" // 文件存在但为空：返回空串而非 null，
                                              // 与"文件不存在则 null"区分（调用方按 null 视为"空"）
            // NSData → ByteArray（沿用 ThumbnailLoader 验证过的 getBytes 写法）→ UTF-8 String。
            val size = data.length.toInt()
            val bytes = ByteArray(size).apply {
                usePinned { pinned -> data.getBytes(pinned.addressOf(0), size.toULong()) }
            }
            bytes.decodeToString()
        } catch (e: Exception) {
            // 读失败按"空"处理，与契约及 Android 实现一致。
            null
        }
    }

    actual fun write(name: String, content: String) {
        val url = fileUrl(name) ?: return
        try {
            val bytes = content.encodeToByteArray()
            // bytes → NSData。用 ByteArray.usePinned 取裸指针交给 dataWithBytes，
            // 使 NSData 拷贝一份持有（不依赖 Kotlin 数组生命周期），随后即可原子写盘。
            val nsData = bytes.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), bytes.size.toULong())
            }
            // atomically=true：先写临时文件再 rename，避免中途崩溃留下半截文件。
            nsData.writeToURL(url, atomically = true)
        } catch (e: Exception) {
            // 静默：与 Android 端一致，弱网/磁盘异常不应使入队操作崩溃。
            // PersistentFileStore 契约为纯存储，调用方各自负责重试/告警，保持静默不引入新耦合。
        }
    }
}
