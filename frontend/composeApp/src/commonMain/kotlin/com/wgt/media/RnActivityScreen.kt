package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * RN 活动中心页面（V7 §3.1 / V8 §1.3 RN 热更新闭环）。
 *
 * 嵌入 React Native 渲染的「活动中心」页面。
 *
 * 热更新闭环：
 * 1. 进入页面时通过 [ensureBundleWithVersion] 检查后端 manifest（bundleName="activity-bundle"）。
 * 2. 有新版本 → 下载到本地缓存，返回 [BundleResult]（含本地路径 + 版本号）。
 * 3. 用返回的本地路径加载 RN（优先缓存 bundle）。
 * 4. 返回 null（网络失败 / 无缓存 / SHA256 不匹配）→ 回退 assets 内置 bundle。
 *
 * 加载期间显示进度指示器 + "正在检查更新..."；就绪后右上角显示当前 bundle 版本号。
 *
 * @param onBack 返回上一页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RnActivityScreen(onBack: () -> Unit) {
    // 热更新状态：loading / ready(path, version) / fallback(assets)
    var updateState by remember {
        mutableStateOf<RnUpdateState>(RnUpdateState.Checking)
    }

    // 进入页面时检查热更新（V8 §1.3）
    LaunchedEffect(Unit) {
        updateState = RnUpdateState.Checking
        val result = ensureBundleWithVersion(BUNDLE_NAME_ACTIVITY)
        updateState = if (result != null) {
            RnUpdateState.Ready(result.path, result.version)
        } else {
            RnUpdateState.Fallback
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("活动中心 (RN)")
                        // 版本号显示：ready 显示缓存版本，fallback 显示 "内置版本"
                        val versionLabel = when (val s = updateState) {
                            is RnUpdateState.Ready -> "v${s.version}（热更新）"
                            RnUpdateState.Fallback -> "内置版本"
                            RnUpdateState.Checking -> "正在检查更新..."
                        }
                        Text(
                            text = versionLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (val s = updateState) {
                RnUpdateState.Checking -> {
                    // 检查更新期间显示进度指示器
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "正在检查更新...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                    }
                }
                is RnUpdateState.Ready -> {
                    // 热更新就绪：用本地缓存路径加载
                    RnContainer(
                        componentName = "MediaManagerApp",
                        bundleAssetName = "index.android.bundle",
                        hostId = "activity-center",
                        modifier = Modifier.fillMaxSize(),
                        bundleFilePath = s.path,
                        bundleName = BUNDLE_NAME_ACTIVITY
                    )
                }
                RnUpdateState.Fallback -> {
                    // 回退 assets 内置 bundle——已预解析失败，不再兜底查询（避免重复网络请求）
                    RnContainer(
                        componentName = "MediaManagerApp",
                        bundleAssetName = "index.android.bundle",
                        hostId = "activity-center",
                        modifier = Modifier.fillMaxSize(),
                        bundleFilePath = null,
                        bundleName = null
                    )
                }
            }
        }
    }
}

/** 热更新检查状态。 */
private sealed interface RnUpdateState {
    /** 正在检查后端 manifest / 下载中。 */
    data object Checking : RnUpdateState
    /** 热更新就绪：有本地缓存路径 + 版本号。 */
    data class Ready(val path: String, val version: String) : RnUpdateState
    /** 回退 assets 内置 bundle（后端不可达 / 无缓存 / 校验失败）。 */
    data object Fallback : RnUpdateState
}

/** 后端 manifest 中活动中心 bundle 的名称。 */
private const val BUNDLE_NAME_ACTIVITY = "activity-bundle"
