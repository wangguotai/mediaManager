package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.platform.logger.logger
import kotlinx.coroutines.launch
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_check_circle
import mediamanager.composeapp.generated.resources.ic_cloud
import mediamanager.composeapp.generated.resources.ic_info
import mediamanager.composeapp.generated.resources.ic_openclaw
import mediamanager.composeapp.generated.resources.ic_palette
import mediamanager.composeapp.generated.resources.ic_photo
import mediamanager.composeapp.generated.resources.ic_settings
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi

private const val TAG = "SettingsScreen"

/** 应用版本号 —— 与“关于”区展示一致。暂不接入 Gradle versionName 以避免跨模块依赖。 */
private const val APP_VERSION = "v0.3.0"

/** 构建时间戳 —— 手动维护，发布时更新。 */
private const val BUILD_TIME = "2026-07-28"

/**
 * 设置项的跨屏幕共享状态。单例，进程内唯一：
 * - [App.kt] 读取 [themeMode] 决定浅色/暗色色板（SYSTEM 时跟随系统）。
 * - [SettingsScreen] 读写 [themeMode] 与后端地址，落地到 [SettingsStorage]。
 *
 * 用 `mutableStateOf` 暴露，保证 Compose 观察能在设置页修改后即时驱动 App 主题切换。
 * 初始化时从 [SettingsStorage] 读回已持久化的值，确保冷启动后主题/地址不丢失。
 */
object SettingsState {
    private val storage = SettingsStorage()

    var themeMode by mutableStateOf(loadThemeMode())
        private set

    var backendUrl by mutableStateOf(loadBackendUrl())
        private set

    private fun loadThemeMode(): ThemeMode {
        val raw = storage.getString(SettingsKeys.THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(raw.uppercase()) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private fun loadBackendUrl(): String =
        storage.getString(SettingsKeys.BACKEND_URL, DEFAULT_BACKEND_URL)

    /**
     * 持久化新的后端地址并更新内存状态。仅做存取，不做可达性校验——
     * 校验交由设置页的"测试连通性"按钮与 [pingBackend]。
     */
    fun saveBackendUrl(url: String) {
        backendUrl = url
        storage.putString(SettingsKeys.BACKEND_URL, url)
        logger.info(TAG, "backend url saved: $url")
    }

    /**
     * 持久化新的主题模式并更新内存状态。调用后 [App] 的主题 @Composable 会
     * 因 [themeMode] 变化重组并切换色板。
     */
    fun saveThemeMode(mode: ThemeMode) {
        themeMode = mode
        storage.putString(SettingsKeys.THEME_MODE, mode.name)
        logger.info(TAG, "theme mode saved: ${mode.name}")
    }

    /** 后端地址默认值——与 MediaService 既有的 10.0.2.2:8080 模拟器回环地址一致。 */
    private const val DEFAULT_BACKEND_URL = "http://192.168.31.251:8080"
}

/**
 * 设置屏幕。
 *
 * 三个区块：
 * 1. 后端地址：输入框 + 保存按钮 + 测试连通性按钮。保存写入 [SettingsState.saveBackendUrl]；
 *    测试走 [pingBackend]（平台原生 HTTP HEAD）。结果以 Snackbar 反馈，连通显示绿色对勾提示。
 * 2. 主题：三选一单选（SYSTEM/LIGHT/DARK），点选即落地，无需额外保存按钮——
 *    主题切换应即时生效，符合系统设置页一般语义。
 * 3. 关于：版本号 [APP_VERSION]。
 *
 * [onBack] 由 [App.kt] 提供，返回媒体列表；TopAppBar 左侧放返回（关闭）按钮。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 后端地址编辑态：独立于 SettingsState，点"保存"才落地，避免边输入边写盘。
    var urlInput by remember { mutableStateOf(SettingsState.backendUrl) }
    var savedUrl by remember { mutableStateOf(SettingsState.backendUrl) }

    // 连通性测试状态
    var isPinging by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<String?>(null) } // null=未测, ""=成功, 非空=失败描述

    // OpenClaw 桥梁对话框状态 + 视图模型
    var showOpenClawDialog by remember { mutableStateOf(false) }
    val openClawViewModel = remember { OpenClawViewModel() }

    // 监听 Snackbar 触发（pingResult 改变后）
    LaunchedEffect(pingResult) {
        val r = pingResult ?: return@LaunchedEffect
        if (r.isEmpty()) snackbarHostState.showSnackbar("连通成功 ✓")
        else snackbarHostState.showSnackbar("连通失败：$r")
    }

    // OpenClaw 桥梁命令对话框
    if (showOpenClawDialog) {
        OpenClawCommandDialog(
            viewModel = openClawViewModel,
            onDismiss = { showOpenClawDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "设置",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_close),
                            contentDescription = "返回"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---- 1. 后端地址 ----
            SectionTitle("后端地址", iconRes = Res.drawable.ic_cloud)
            OutlinedTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("http://192.168.31.251:8080") },
                enabled = !isPinging
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        val v = urlInput.trim()
                        SettingsState.saveBackendUrl(v)
                        savedUrl = v
                        scope.launch { snackbarHostState.showSnackbar("已保存") }
                    },
                    enabled = !isPinging && urlInput.trim().isNotEmpty() && urlInput.trim() != savedUrl,
                    modifier = Modifier.weight(1f)
                ) { Text("保存") }

                OutlinedButton(
                    onClick = {
                        if (isPinging) return@OutlinedButton
                        val target = urlInput.trim().ifEmpty { savedUrl }
                        isPinging = true
                        pingResult = null
                        scope.launch {
                            val err = pingBackend(target)
                            pingResult = err ?: ""
                            isPinging = false
                        }
                    },
                    enabled = !isPinging,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isPinging) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("测试中…")
                    } else {
                        Text("测试连通性")
                    }
                }
            }

            // 连通性结果提示：成功显示对勾行，失败由 Snackbar 兜底（此处不重复展示）
            val r = pingResult
            if (r == "") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_check_circle),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "后端可达",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // ---- 2. 主题 ----
            SectionTitle("主题", iconRes = Res.drawable.ic_palette)
            ThemeMode.values().forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = SettingsState.themeMode == mode,
                        onClick = {
                            SettingsState.saveThemeMode(mode)
                            scope.launch { snackbarHostState.showSnackbar("主题：${modeLabel(mode)}") }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(modeLabel(mode), style = MaterialTheme.typography.bodyLarge)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // ---- 3. 关于 ----
            SectionTitle("关于", iconRes = Res.drawable.ic_info)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("版本", style = MaterialTheme.typography.bodyLarge)
                Text(
                    APP_VERSION,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("构建时间", style = MaterialTheme.typography.bodyLarge)
                Text(
                    BUILD_TIME,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // ---- 4. OpenClaw 桥梁 ----
            SectionTitle("OpenClaw", iconRes = Res.drawable.ic_openclaw)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("OpenClaw 命令桥梁", style = MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { showOpenClawDialog = true }) {
                    Text("打开")
                }
            }
        }
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
private fun SectionTitle(text: String, iconRes: DrawableResource? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    ) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

private fun modeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "暗色"
    ThemeMode.AMOLED -> "AMOLED 纯黑"
}
