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
import androidx.compose.material3.Switch
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
import com.wgt.common.util.formatBytesToMB
import kotlinx.coroutines.launch
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_check_circle
import mediamanager.composeapp.generated.resources.ic_cloud
import mediamanager.composeapp.generated.resources.ic_cloud_upload
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

    /**
     * 云相册自动备份开关。默认关——需用户在设置页主动开启，避免未授权情况下静默上传
     * 用户图库。开启后 [MediaViewModel] 后台监听本地图库新增并增量上传（带 sha256 去重）。
     * persistence：落 [SettingsKeys.AUTO_BACKUP_ENABLED]（"true"/"false"）。
     */
    var autoBackupEnabled by mutableStateOf(loadAutoBackup())
        private set

    /**
     * 当前设备 id（服务端 [MediaService.registerDevice] 分配）。自动备份开启后首次注册获得，
     * 持久化以避免每次启动重复注册。空串表示尚未注册。
     */
    var deviceId by mutableStateOf(loadDeviceId())
        private set

    /**
     * 上次增量同步推进到的复合游标（"ms|id" 字符串，V6 §2.7）。空串/"" 表示从未同步过
     * （下次拉全量）。持久化使冷启动后能续拉增量，而非每次重头全量。由
     * [MediaViewModel.loadCloudChanges] 在每页成功后经 [saveSyncCursor] 推进。
     */
    var syncCursor by mutableStateOf(loadSyncCursor())
        private set

    /**
     * 仅 WiFi 备份开关（V6 §2.1）。开启后自动备份仅在 WiFi 网络下执行，移动数据下暂停。
     * 默认 true（对标小米「仅 WiFi 备份」，避免用户流量被偷跑）。
     */
    var backupWifiOnly by mutableStateOf(loadBackupWifiOnly())
        private set

    /**
     * 仅充电备份开关（V6 §2.1）。开启后自动备份仅在充电状态下执行，电池供电时暂停。
     * 默认 false（不强制充电，WiFi 下即可备份）。
     */
    var backupChargingOnly by mutableStateOf(loadBackupChargingOnly())
        private set

    private fun loadThemeMode(): ThemeMode {
        val raw = storage.getString(SettingsKeys.THEME_MODE, ThemeMode.SYSTEM.name)
        return runCatching { ThemeMode.valueOf(raw.uppercase()) }.getOrDefault(ThemeMode.SYSTEM)
    }

    private fun loadBackendUrl(): String =
        storage.getString(SettingsKeys.BACKEND_URL, DEFAULT_BACKEND_URL)

    private fun loadAutoBackup(): Boolean =
        storage.getString(SettingsKeys.AUTO_BACKUP_ENABLED, "false").equals("true", ignoreCase = true)

    private fun loadDeviceId(): String = storage.getString(SettingsKeys.DEVICE_ID, "")

    private fun loadSyncCursor(): String =
        storage.getString(SettingsKeys.SYNC_CURSOR, "")

    private fun loadBackupWifiOnly(): Boolean =
        storage.getString(SettingsKeys.BACKUP_WIFI_ONLY, "true").equals("true", ignoreCase = true)

    private fun loadBackupChargingOnly(): Boolean =
        storage.getString(SettingsKeys.BACKUP_CHARGING_ONLY, "false").equals("true", ignoreCase = true)

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

    /**
     * 开/关云相册自动备份。仅落地开关值；真正启动后台备份监听由 [MediaViewModel] 响应
     * [autoBackupEnabled] 变化编排（开启→注册设备+预检，关闭→停止轮询）。设备注册在
     * ViewModel 内按需进行（需网络与登录态），不在此处耦合。
     */
    fun saveAutoBackup(enabled: Boolean) {
        if (autoBackupEnabled == enabled) return
        autoBackupEnabled = enabled
        storage.putString(SettingsKeys.AUTO_BACKUP_ENABLED, if (enabled) "true" else "false")
        logger.info(TAG, "auto backup $enabled")
    }

    /** 持久化服务端分配的设备 id（注册成功后由 ViewModel 回调落地）。 */
    fun saveDeviceId(id: String) {
        deviceId = id
        storage.putString(SettingsKeys.DEVICE_ID, id)
        logger.info(TAG, "device id saved")
    }

    /**
     * 推进增量同步复合游标并持久化（V6 §2.7）。
     * cursor 为 "ms|id" 字符串。空串不落（视为未同步）。仅在游标推进时写盘。
     */
    fun saveSyncCursor(cursor: String) {
        if (cursor.isEmpty()) return
        syncCursor = cursor
        storage.putString(SettingsKeys.SYNC_CURSOR, cursor)
    }

    /** V6 §2.1：持久化仅 WiFi 备份开关。 */
    fun saveBackupWifiOnly(enabled: Boolean) {
        if (backupWifiOnly == enabled) return
        backupWifiOnly = enabled
        storage.putString(SettingsKeys.BACKUP_WIFI_ONLY, if (enabled) "true" else "false")
        logger.info(TAG, "backup wifi-only: $enabled")
    }

    /** V6 §2.1：持久化仅充电备份开关。 */
    fun saveBackupChargingOnly(enabled: Boolean) {
        if (backupChargingOnly == enabled) return
        backupChargingOnly = enabled
        storage.putString(SettingsKeys.BACKUP_CHARGING_ONLY, if (enabled) "true" else "false")
        logger.info(TAG, "backup charging-only: $enabled")
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
fun SettingsScreen(
    viewModel: MediaViewModel,
    onBack: () -> Unit
) {
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

            // ---- 账号 ----
            SectionTitle("账号", iconRes = Res.drawable.ic_settings)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("当前用户", style = MaterialTheme.typography.bodyLarge)
                Text(
                    AuthState.currentUsername.ifEmpty { "未登录" },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Button(
                onClick = {
                    AuthState.clearSession()
                    scope.launch { snackbarHostState.showSnackbar("已退出登录") }
                },
                enabled = AuthState.isLoggedIn,
                modifier = Modifier.fillMaxWidth()
            ) { Text("退出登录") }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // ---- 云相册自动备份 ----
            SectionTitle("云相册", iconRes = Res.drawable.ic_cloud_upload)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动备份新增图片", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "本地新增图片后台增量上传到云端（自动去重）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = SettingsState.autoBackupEnabled,
                    onCheckedChange = { enabled ->
                        SettingsState.saveAutoBackup(enabled)
                        // 开关切换即启停后台备份轮询（登录态下）。开启时 startAutoBackup
                        // 内部按需注册设备+建立快照+起轮询；关闭时 stopAutoBackup 取消轮询清队列。
                        if (enabled) viewModel.startAutoBackup() else viewModel.stopAutoBackup()
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                if (enabled) "已开启自动备份" else "已关闭自动备份"
                            )
                        }
                    },
                    enabled = AuthState.isLoggedIn
                )
            }
            // V6 §2.1：备份策略开关——仅在自动备份开启时展示。
            // 仅 WiFi：移动数据下暂停备份（默认开，对标小米）。仅充电：电池供电时暂停。
            if (SettingsState.autoBackupEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅 WiFi 备份", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "移动数据下暂停备份，避免消耗流量",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = SettingsState.backupWifiOnly,
                        onCheckedChange = { SettingsState.saveBackupWifiOnly(it) },
                        enabled = AuthState.isLoggedIn
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅充电备份", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "电池供电时暂停备份，省电",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = SettingsState.backupChargingOnly,
                        onCheckedChange = { SettingsState.saveBackupChargingOnly(it) },
                        enabled = AuthState.isLoggedIn
                    )
                }
            }
            // 设备登记状态：供用户确认本机已被云同步纳入。
            if (SettingsState.autoBackupEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("本机设备", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        SettingsState.deviceId.ifEmpty { "未登记" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            // 云端用量 + 待传队列：登录态展示，让用户直观看到已用空间与离线待传条数。
            // 用量来自 /api/sync/usage（viewModel.cloudUsage），待传来自 uploadQueue.size。
            if (AuthState.isLoggedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("云端用量", style = MaterialTheme.typography.bodyMedium)
                    val usage = viewModel.cloudUsage
                    Text(
                        if (usage != null) "${usage.fileCount} 项 / ${formatBytesToMB(usage.totalBytes)}"
                        else "—",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (viewModel.uploadQueue.size > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("待上传（离线队列）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${viewModel.uploadQueue.size} 项",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
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
