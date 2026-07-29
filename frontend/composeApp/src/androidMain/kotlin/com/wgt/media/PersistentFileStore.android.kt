package com.wgt.media

import com.wgt.platform.AppContext
import com.wgt.platform.applicationContext
import com.wgt.platform.logger.logger
import java.io.File

/**
 * Android 平台 [PersistentFileStore] 实现：落盘到 App 私有 filesDir。
 *
 * 该目录仅本 App 可读写（scoped storage 下的应用私有空间），无需运行时权限。
 * [filesDir] 在 App 首次安装后即存在；按需 [mkdirs] 兜底极少数边界情形。
 * 写采用先写临时文件再 rename 的原子策略，避免中途崩溃留下半截文件。
 */
actual object PersistentFileStore {

    private const val TAG = "PersistentFileStore"

    private fun dir(): File = com.wgt.platform.AppContext.applicationContext.filesDir.apply { if (!exists()) mkdirs() }

    private fun file(name: String): File = File(dir(), name)

    actual fun read(name: String): String? = try {
        val f = file(name)
        if (f.exists() && f.isFile) f.readText() else null
    } catch (e: Exception) {
        logger.error(TAG, "read '$name' failed: ${e.message}")
        null
    }

    actual fun write(name: String, content: String) {
        try {
            val target = file(name)
            val tmp = File(dir(), "$name.tmp")
            tmp.writeText(content)
            // 原子替换：tmp → target。renameTo 在同分区下原子，避免半截写入。
            if (!tmp.renameTo(target)) {
                // rename 失败（极少数权限/跨分区情形）退化直接写，保证内容落盘。
                target.writeText(content)
                tmp.delete()
            }
        } catch (e: Exception) {
            logger.error(TAG, "write '$name' failed: ${e.message}")
        }
    }
}
