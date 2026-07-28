package com.wgt.media

import androidx.compose.runtime.Composable
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 视频播放配置 —— 后端流地址与时长加载。
 *
 * 为什么在 composeApp 内自带 [BASE_URL] 而非复用 `MediaService.BASE_URL`：
 * 该常量写在 feature-media 模块内且为 `private`，超出本模块文件边界；
 * 与 `BackendConnectivity` 同理（见其注释），composeApp 内不得引入 ktor，
 * 故视频流地址与时长请求在此模块内独立定义，与后端约定保持一致。
 *
 * 视频原片走 `GET /api/media/stream/{id}`，后端基于 `http.ServeFile` 实现，
 * 天然支持 HTTP `Range` 请求 —— Android `VideoView` / iOS `AVPlayer` 可据此
 * 分片拖拽播放，无需一次性下载整片。
 */
internal const val VIDEO_BACKEND_BASE_URL = "http://10.0.2.2:8080"

/**
 * 构造视频原片流地址。
 *
 * @param mediaId 后端媒体 id（网盘视频为去扩展名的文件名，如 `sample`）
 * @return 形如 `http://10.0.2.2:8080/api/media/stream/sample`
 */
internal fun backendStreamUrl(mediaId: String): String =
    "$VIDEO_BACKEND_BASE_URL/api/media/stream/$mediaId"

/**
 * 视频信息：用于网格时长标签预取。字段对应后端
 * `GET /api/media/video-info/{id}` 返回体。
 */
internal data class VideoInfo(
    val durationSeconds: Double,
    val width: Int,
    val height: Int
)

/**
 * 平台特定：从后端 `GET /api/media/video-info/{id}` 加载视频信息。
 *
 * 用各平台原生 HTTP（Android: HttpURLConnection；iOS: NSURLSession）实现，
 * 与 [pingBackend] 同款——composeApp 未引入 ktor，不依赖 MediaService。
 * 返回 null 表示加载失败或非视频，调用方降级为不显示时长标签。
 *
 * @param backendUrl 后端基址，形如 `http://10.0.2.2:8080`（不含尾斜杠）
 */
internal expect suspend fun loadVideoInfo(backendUrl: String, mediaId: String): VideoInfo?

/**
 * 把秒数格式化为 `m:ss`（>=1h 时为 `h:mm:ss`），供网格时长标签与播放器进度显示复用。
 */
internal fun formatDuration(seconds: Double): String {
    if (seconds < 0 || seconds.isNaN() || seconds.isInfinite()) return "0:00"
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 全屏视频播放器 Composable（expect/actual 跨平台入口）。
 *
 * Android 用原生 [android.widget.VideoView]（包裹于 `AndroidView`，支持 HTTP Range 分片），
 * iOS 用 `AVPlayer` + `AVPlayerLayer`。两者均通过 [backendStreamUrl] 从后端 stream 端点加载。
 *
 * 控件：播放/暂停、进度条（可拖拽 seek）、当前/总时长、全屏按钮、关闭。
 *
 * @param media 待播放视频元数据（仅取 id / filename）
 * @param initialDurationSeconds 预取的总时长（来自 [loadVideoInfo]），无则播放器会按实际
 *   播放就绪后获取；传入可让进度条立即显示总时长。
 * @param onDismiss 关闭回调
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
internal expect fun VideoPlayer(
    media: MediaMetadata,
    initialDurationSeconds: Double?,
    onDismiss: () -> Unit
)
