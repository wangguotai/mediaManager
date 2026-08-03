package com.wgt.media

/**
 * 视频裁剪（按时间段）— 跨平台 expect 声明。
 *
 * Android: android.media.MediaExtractor + android.media.MediaMuxer
 * iOS: TODO（暂未实现）
 *
 * @param inputPath 源视频文件绝对路径
 * @param outputPath 输出视频文件绝对路径（由调用方管理目录与命名）
 * @param startMs 起始时间（毫秒），≥0
 * @param endMs 结束时间（毫秒），＞startMs
 * @return [VideoTrimResult]，成功时 outputPath 非空、durationMs 为实际写入片段时长
 */
expect fun trimVideo(
    inputPath: String,
    outputPath: String,
    startMs: Long,
    endMs: Long
): VideoTrimResult

/**
 * 视频裁剪结果。
 *
 * @param success 是否成功
 * @param outputPath 输出文件路径，失败时为 null
 * @param durationMs 实际裁剪出的片段时长（毫秒），失败时 0
 * @param errorMessage 失败原因，成功时 null
 */
data class VideoTrimResult(
    val success: Boolean,
    val outputPath: String?,
    val durationMs: Long,
    val errorMessage: String?
)
