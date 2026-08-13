// PRD-v12 拍摄: 跨平台相机桥。
// commonMain 声明 expect, androidMain 提供 actual(用系统相机拍照并回调照片字节)。
package com.wgt.media

import androidx.compose.runtime.Composable

/** 相机桥: [launch] 拉起拍照,成功后回调 [onCaptured] 照片字节(取消回调 null)。 */
class CameraCaptureLauncher internal constructor(
    val launch: (() -> Unit)? = null
)

/**
 * 拍摄按钮所需的跨平台相机启动器。
 * @param onCaptured 拍照成功回调照片字节;取消传 null。
 */
@Composable
expect fun rememberCameraLauncher(onCaptured: (ByteArray?) -> Unit): CameraCaptureLauncher