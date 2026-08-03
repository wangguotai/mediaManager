package com.wgt.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.wgt.platform.logger.logger
import java.nio.ByteBuffer

private const val TAG = "VideoTrimmer"

/**
 * Android 端实现：MediaExtractor + MediaMuxer 裁剪指定时间段。
 *
 * 注意事项：
 * - seek 到 startMs 附近的 sync sample（关键帧），可能略早于 startMs，
 *   MediaMuxer 会写入该关键帧之后的帧，sampleTime < startMs 的帧仍会被写入
 *   （MediaMuxer 不做时间过滤），输出片段时长可能略大于 (endMs - startMs)。
 * - endMs 之后停止读取；保留第一个 sampleTime > endMs 前的全部数据。
 * - 该函数为阻塞 IO，调用方应在 Dispatchers.IO 中调用。
 */
actual fun trimVideo(
    inputPath: String,
    outputPath: String,
    startMs: Long,
    endMs: Long
): VideoTrimResult {
    if (startMs < 0 || endMs <= startMs) {
        return VideoTrimResult(false, null, 0, "Invalid time range: start=$startMs end=$endMs")
    }

    val startUs = startMs * 1000L
    val endUs = endMs * 1000L

    var extractor: MediaExtractor? = null
    var muxer: MediaMuxer? = null
    try {
        extractor = MediaExtractor().apply { setDataSource(inputPath) }

        // 1) 识别 video / audio track
        val trackInfos = ArrayList<Int>()       // extractor track index 列表
        val muxerTrackIndex = ArrayList<Int>()  // 对应 muxer 输出 track index
        var orientation = 0

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            val isVideo = mime.startsWith("video/")
            val isAudio = mime.startsWith("audio/")
            if (!isVideo && !isAudio) continue
            extractor.selectTrack(i)
            trackInfos.add(i)
        }

        if (trackInfos.isEmpty()) {
            return VideoTrimResult(false, null, 0, "No video/audio track found in $inputPath")
        }

        // 2) 取 video track 中的旋转元信息（输出需保持原方向）
        for (i in trackInfos) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                if (fmt.containsKey(MediaFormat.KEY_ROTATION)) {
                    orientation = fmt.getInteger(MediaFormat.KEY_ROTATION)
                }
                break
            }
        }

        // 3) 创建 muxer，加入全部选中 track 后再 start
        muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        for (i in trackInfos) {
            val fmt = extractor.getTrackFormat(i)
            // 拷贝一份，避免修改源 format 带来副作用；保留原始 KEY_DURATION 等元数据
            muxerTrackIndex.add(muxer.addTrack(fmt))
        }
        muxer.setOrientationHint(orientation)
        muxer.start()

        // 4) 对每个 track：seek 到 startUs，循环读写直到 sampleTime > endUs
        val buffer = ByteBuffer.allocateDirect(2 * 1024 * 1024) // 2 MB
        val bufferInfo = android.media.MediaCodec.BufferInfo()

        var firstWrittenUs = Long.MIN_VALUE
        var lastWrittenUs = 0L

        for (tIdx in trackInfos.indices) {
            val extIdx = trackInfos[tIdx]
            val outIdx = muxerTrackIndex[tIdx]
            extractor.unselectTrack(extIdx)
            extractor.selectTrack(extIdx)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break // EOS
                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) break // 超过结束点
                // 跳过尚未到 startUs 的帧（容错：极少出现，但 seekTo PREVIOUS_SYNC 可能落在 startUs 之前）
                if (sampleTime < startUs) {
                    if (!extractor.advance()) break
                    continue
                }

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = sampleTime
                val flags = extractor.sampleFlags
                bufferInfo.flags = flags

                muxer.writeSampleData(outIdx, buffer, bufferInfo)

                if (firstWrittenUs == Long.MIN_VALUE) firstWrittenUs = sampleTime
                lastWrittenUs = sampleTime

                if (!extractor.advance()) break
            }
        }

        val durationMs = if (firstWrittenUs != Long.MIN_VALUE) {
            (lastWrittenUs - firstWrittenUs) / 1000L
        } else 0L

        logger.info(TAG, "trim done: $inputPath -> $outputPath, duration=${durationMs}ms")
        return VideoTrimResult(true, outputPath, durationMs, null)
    } catch (e: Exception) {
        logger.error(TAG, "trim failed: ${e.message}")
        // 清理失败输出文件
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        try {
            extractor?.release()
        } catch (_: Exception) {
        }
        try {
            java.io.File(outputPath).delete()
        } catch (_: Exception) {
        }
        return VideoTrimResult(false, null, 0, e.message ?: e::class.simpleName)
    } finally {
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        try {
            extractor?.release()
        } catch (_: Exception) {
        }
    }
}
