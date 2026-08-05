package com.wgt.media

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.Foundation.NSError

/**
 * iOS 端实现：暂未实现。
 *
 * TODO: 用 AVAssetExportSession + CMTimeRange 裁剪：
 *   - AVAsset(url:)
 *   - AVAssetExportSession(asset:presetName: AVAssetExportPresetHighestQuality)
 *   - exportSession.timeRange = CMTimeRange(start: CMTime(seconds: startMs/1000), duration: ...)
 *   - exportSession.outputURL / outputFileType
 *   - exportSession.exportAsynchronously
 */
actual fun trimVideo(
    inputPath: String,
    outputPath: String,
    startMs: Long,
    endMs: Long
): VideoTrimResult {
    return VideoTrimResult(
        success = false,
        outputPath = null,
        durationMs = 0,
        errorMessage = "iOS video trimming not yet supported"
    )
}

/** iOS：用 NSFileManager.defaultManager.removeItem，忽略不存在。 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun platformDeleteFile(path: String) {
    try {
        val fm = platform.Foundation.NSFileManager.defaultManager()
        if (fm.fileExistsAtPath(path)) {
            memScoped {
                val errPtr: ObjCObjectVar<NSError?> = alloc()
                fm.removeItemAtPath(path, error = errPtr.ptr)
            }
        }
    } catch (_: Exception) {
        // 忽略：临时文件清理失败不影响主流程
    }
}
