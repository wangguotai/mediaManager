package com.wgt.media

import androidx.compose.runtime.Composable
import media.MediaMetadata
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 视频播放配置 —— 后端流地址与时长加载。
 *
 * 后端地址源自用户设置（[SettingsState.backendUrl]，运行时可变、已持久化），
 * 不再硬编码。composeApp 内视频流与 video-info 请求虽不依赖 feature-media 的
 * MediaService（本模块不引入 ktor），但与 MediaService 共用同一地址源——
 * 由 [SettingsState] 统一持有，[currentBackendBaseUrl] 在此归一化后供各端复用。
 *
 * 视频原片走 `GET /api/media/stream/{id}`，后端基于 `http.ServeFile` 实现，
 * 天然支持 HTTP `Range` 请求 —— Android `VideoView` / iOS `AVPlayer` 可据此
 * 分片拖拽播放，无需一次性下载整片。
 */

/**
 * 设置页未配置时的回退地址——与历史行为一致（模拟器 10.0.2.2 回环到开发机）。
 * 仅当 [SettingsState.backendUrl] 为空时使用，避免拼出空 host。
 */
internal const val DEFAULT_VIDEO_BACKEND_URL = "http://10.0.2.2:8080"

/**
 * 当前后端基址（归一化）：读 [SettingsState.backendUrl]，去空白/尾斜杠；
 * 空串回退 [DEFAULT_VIDEO_BACKEND_URL]。composeApp 内视频相关请求的单一地址源。
 *
 * 注意：补 `http://` 前缀的逻辑仍由各端网络层（[pingBackend]/[loadVideoInfo]）完成，
 * 与设置页"测试连通性"同款；此处只保证非空与无尾斜杠，供 URL 拼接。
 */
internal fun currentBackendBaseUrl(): String {
    val trimmed = SettingsState.backendUrl.trim().trimEnd('/')
    return trimmed.ifEmpty { DEFAULT_VIDEO_BACKEND_URL }
}

/**
 * 构造视频原片流地址。
 *
 * @param mediaId 后端媒体 id（网盘视频为去扩展名的文件名，如 `sample`）
 * @return 形如 `http://10.0.2.2:8080/api/media/stream/sample`（地址取自用户设置）
 */
internal fun backendStreamUrl(mediaId: String): String =
    "${currentBackendBaseUrl()}/api/media/stream/$mediaId"

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
 * @param backendUrl 后端基址，取自 [SettingsState.backendUrl]（经 [currentBackendBaseUrl] 归一化）
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
