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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.platform.logger.logger
import com.wgt.common.util.formatBytesToMB
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.draw.clip
import com.wgt.media.BackendImageLoader
import com.wgt.feature.media.MediaService
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_delete
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
private const val APP_VERSION = "v0.4.0 (V7+V8)"

/** 构建时间戳 —— 手动维护，发布时更新。 */
private const val BUILD_TIME = "2026-07-31"

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

    /**
     * 上次备份完成时间（epoch 毫秒，PRD-v7 §1.5）。
     *
     * [MediaViewModel.checkAndBackupNewLocalMedia] 完成一轮后经 [saveLastBackupTime] 落盘；
     * 0L 表示从未备份。设置页读取并格式化展示 "上次备份时间"。
     */
    var lastBackupTime by mutableStateOf(loadLastBackupTime())
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

    /** 读取上次备份时间（ms）。无效/空串视为 0（从未备份）。 */
    private fun loadLastBackupTime(): Long =
        storage.getString(SettingsKeys.LAST_BACKUP_TIME, "0").toLongOrNull() ?: 0L

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

    /**
     * 持久化上次备份完成时间（PRD-v7 §1.5）。
     *
     * @param timeMs epoch 毫秒（[kotlin.time.Clock.System.now].toEpochMilliseconds()）
     */
    fun saveLastBackupTime(timeMs: Long) {
        lastBackupTime = timeMs
        storage.putString(SettingsKeys.LAST_BACKUP_TIME, timeMs.toString())
        logger.info(TAG, "last backup time saved: $timeMs")
    }

    /** 后端地址默认值——开发阶段用 localhost:8080 + adb reverse tcp:8080 tcp:8080。 */
    private const val DEFAULT_BACKEND_URL = "http://localhost:8080"

    /**
     * V8 开发环境预设凭据——登录界面自动填充，省去每次手输。
     * 生产环境改为空串即可禁用预填。
     */
    const val DEV_DEFAULT_USERNAME = "admin"
    const val DEV_DEFAULT_PASSWORD = "ab123456"
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
    onBack: () -> Unit,
    onNavigateToTrash: () -> Unit = {},
    onNavigateToRnActivity: () -> Unit = {},
    onNavigateToCleanup: () -> Unit = {}
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

    // V9：年度回顾对话框状态
    var showYearlyReview by remember { mutableStateOf(false) }
    var yearlyReview by remember { mutableStateOf<MediaService.YearlyReview?>(null) }
    var yearlyReviewYear by remember { mutableStateOf(2026) }

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

    // V9：年度回顾对话框——打开时加载数据，关闭时清空以便下次重新拉取
    if (showYearlyReview) {
        LaunchedEffect(Unit) {
            if (yearlyReview == null) {
                yearlyReview = MediaService.getYearlyReview(yearlyReviewYear)
            }
        }
        YearlyReviewDialog(
            year = yearlyReviewYear,
            review = yearlyReview,
            onDismiss = { showYearlyReview = false }
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
            // 云端用量 + 待备份/离线队列 + 上次备份时间：登录态展示。
            // 用量来自 /api/sync/usage（viewModel.cloudUsage），待备份来自 uploadQueue.size，
            // 上次备份时间来自 SettingsState.lastBackupTime（持久化 ms）。
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
                // 待备份项数：离线队列大小即待备份条数（PRD-v7 §1.5）。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("待备份", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${viewModel.uploadQueue.size} 项",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 上次备份完成时间（PRD-v7 §1.5）：从未备份显示"未备份"。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("上次备份时间", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        formatBackupTime(SettingsState.lastBackupTime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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

            // ---- RN 活动中心入口（V7 §3.1）----
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("活动中心", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onNavigateToRnActivity) {
                    Text("打开 React Native 模块")
                }
            }

            // ---- 存储清理入口（V7 §2.4）----
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("存储清理", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onNavigateToCleanup) {
                    Text("查看清理建议")
                }
            }

            // V8：孤立文件检查
            var orphanResult by remember { mutableStateOf<MediaService.OrphanCheckResult?>(null) }
            var orphanChecking by remember { mutableStateOf(false) }
            val orphanScope = rememberCoroutineScope()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("孤立文件检查", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    orphanChecking = true
                    orphanScope.launch {
                        orphanResult = MediaService.orphanCheck()
                        orphanChecking = false
                    }
                }) {
                    if (orphanChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("检查")
                    }
                }
            }
            orphanResult?.let { r ->
                Text(
                    "检查 ${r.checked} 个文件，发现 ${r.orphanCount} 个孤立文件",
                    fontSize = 12.sp,
                    color = if (r.orphanCount > 0) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
                if (r.orphanCount > 0) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = {
                            orphanScope.launch {
                                val result = MediaService.cleanupOrphan()
                                if (result != null) {
                                    orphanResult = MediaService.orphanCheck()
                                }
                            }
                        }) { Text("一键清理", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            // V8：自动打标签
            var autoTagResult by remember { mutableStateOf<String?>(null) }
            var autoTagging by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("自动打标签", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    autoTagging = true
                    orphanScope.launch {
                        val count = MediaService.autoTag()
                        autoTagResult = "已为 $count 个媒体添加标签"
                        autoTagging = false
                    }
                }) {
                    if (autoTagging) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("执行")
                    }
                }
            }
            autoTagResult?.let { msg ->
                Text(msg, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            // ---- 回收站入口（V7 §1.1）----
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("回收站", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onNavigateToTrash) {
                    Text("查看已删除文件")
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
            // V7：后端版本（从 /api/healthz 获取）
            var backendVersion by remember { mutableStateOf("加载中...") }
            LaunchedEffect(Unit) {
                backendVersion = MediaService.getBackendInfo() ?: "未知"
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("后端版本", style = MaterialTheme.typography.bodyLarge)
                Text(
                    backendVersion,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            // V8：服务器磁盘使用
            var diskUsage by remember { mutableStateOf<MediaService.DiskUsage?>(null) }
            LaunchedEffect(Unit) { diskUsage = MediaService.getDiskUsage() }
            diskUsage?.let { d ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("服务器磁盘", style = MaterialTheme.typography.bodyLarge)
                    val usedStr = d.usedGB.toString().let { it.take(it.indexOf('.') + 2) }
                    val totalStr = d.totalGB.toString().let { it.take(it.indexOf('.') + 2) }
                    val pctStr = d.usagePercent.toString().let { it.take(it.indexOf('.') + 2) }
                    Text(
                        "$usedStr / $totalStr GB ($pctStr%)",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (d.usagePercent > 90) MaterialTheme.colorScheme.error
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            // V8：同步状态
            var syncStatus by remember { mutableStateOf<MediaService.SyncStatus?>(null) }
            LaunchedEffect(Unit) { syncStatus = MediaService.getSyncStatus() }
            syncStatus?.let { ss ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("媒体同步", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${ss.totalMedia} 项 (回收站 ${ss.deletedMedia})",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (ss.lastUpdate.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("最后更新", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(ss.lastUpdate.take(19), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
            }
            // V9：数据概览卡片 —— 一次调 /api/media/stat-summary 拿多组汇总数据
            // （媒体总数 / 图片·视频·Live 计数 / 收藏 / 分享 / 相册 / 回收站），
            // 替代为分散统计多次请求。后端 best-effort：子统计失败回退零值，前端据此渲染。
            var statSummary by remember { mutableStateOf<MediaService.StatSummary?>(null) }
            var statSummaryLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                statSummary = MediaService.getStatSummary()
                statSummaryLoading = false
            }
            SectionTitle("数据概览", iconRes = Res.drawable.ic_info)
            if (statSummaryLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (statSummary == null) {
                Text(
                    "无法获取数据概览",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val s = statSummary!!.summary
                // 第一行：媒体总数 + 图片/视频/Live 计数
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("媒体总数", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${s.totalCount} 项",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 按类型拆分 + 收藏/分享/相册/回收站：多列 Row 布局展示
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell(
                        label = "图片",
                        value = s.imageCount,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "视频",
                        value = s.videoCount,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "Live",
                        value = s.liveCount,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "收藏",
                        value = statSummary!!.favorites,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatCell(
                        label = "分享",
                        value = statSummary!!.shares,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "相册",
                        value = statSummary!!.albums,
                        modifier = Modifier.weight(1f)
                    )
                    StatCell(
                        label = "回收站",
                        value = statSummary!!.trash,
                        modifier = Modifier.weight(1f)
                    )
                    // 配额百分比：占位单元格，使本行与前一行对齐为四列
                    StatCell(
                        label = "配额",
                        valueText = "${statSummary!!.quota.usagePercent.toInt()}%",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            // V9：年度回顾入口——点击弹 Dialog 展示该年上传统计详情
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("年度回顾", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = {
                    yearlyReview = null  // 清空以触发重新拉取
                    showYearlyReview = true
                }) {
                    Text("查看年度回顾")
                }
            }
            // V8：操作历史统计卡片（GET /api/media/audit-log/stats）
            var auditStats by remember { mutableStateOf<List<MediaService.AuditLogStat>?>(null) }
            LaunchedEffect(Unit) { auditStats = MediaService.getAuditLogStats() }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("操作历史", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        auditStats == null -> "加载中..."
                        auditStats!!.isEmpty() -> "暂无记录"
                        else -> "${auditStats!!.sumOf { it.count }} 次操作"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            auditStats?.forEach { stat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stat.action, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${stat.count}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            // V8：存储分析卡片（GET /api/media/storage-breakdown）
            var storageBreakdown by remember { mutableStateOf<MediaService.StorageBreakdown?>(null) }
            var storageLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                orphanScope.launch {
                    storageBreakdown = MediaService.getStorageBreakdown()
                    storageLoading = false
                }
            }
            SectionTitle("存储分析", iconRes = Res.drawable.ic_photo)
            if (storageLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (storageBreakdown == null) {
                Text(
                    "无法获取存储统计",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val b = storageBreakdown!!
                // 图片 / 视频 / Live 三行：彩色小圆点 + 名称 + 数量与占用
                StorageBreakdownRow(
                    dotColor = MaterialTheme.colorScheme.primary,          // 蓝
                    label = "图片",
                    count = b.imageCount,
                    bytes = b.imageBytes
                )
                StorageBreakdownRow(
                    dotColor = MaterialTheme.colorScheme.error,            // 红
                    label = "视频",
                    count = b.videoCount,
                    bytes = b.videoBytes
                )
                StorageBreakdownRow(
                    dotColor = MaterialTheme.colorScheme.tertiary,         // 绿
                    label = "Live",
                    count = b.liveCount,
                    bytes = b.liveBytes
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "总计",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${b.totalCount} 项 · ${formatBytesToMB(b.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
            // V7：检查 RN 热更新
            var updateStatus by remember { mutableStateOf("") }
            var checkingUpdate by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        if (checkingUpdate) return@clickable
                        checkingUpdate = true
                        updateStatus = "检查中..."
                        scope.launch {
                            try {
                                val manifest = MediaService.getRNManifest()
                                if (manifest == null || manifest.bundles.isEmpty()) {
                                    updateStatus = "无法获取更新信息"
                                } else {
                                    updateStatus = "最新版本: ${manifest.bundles[0].version}"
                                }
                            } catch (e: Exception) {
                                updateStatus = "检查失败: ${e.message ?: "未知错误"}"
                            }
                            checkingUpdate = false
                        }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("检查 RN 更新", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (checkingUpdate) "检查中..." else updateStatus.ifEmpty { "点击检查" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("后端地址", style = MaterialTheme.typography.bodyLarge)
                Text(
                    SettingsState.backendUrl.ifBlank { "未设置" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
            // V7：清除缩略图缓存
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp)
                    .clickable {
                        BackendImageLoader.clearCaches()
                        scope.launch { snackbarHostState.showSnackbar("缓存已清除") }
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("清除缩略图缓存", style = MaterialTheme.typography.bodyLarge)
                Icon(
                    painterResource(Res.drawable.ic_delete),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))
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

/**
 * 存储分析的单行：彩色小圆点 + 类型名 + 数量与 MB 占用（V8）。
 *
 * 纯展示行，无交互。圆点用 [Box] + [CircleShape] 着色，与 MaterialTheme 色板取色
 * （图片蓝=primary，视频红=error，Live 绿=tertiary），便于主题切换时随色板联动。
 */
@Composable
private fun StorageBreakdownRow(
    dotColor: Color,
    label: String,
    count: Int,
    bytes: Long
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            "$count 个 · ${formatBytesToMB(bytes)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * 数据概览卡片（V9）的单格：标签在上、数值在下，居中对齐。
 *
 * 用于把 stat-summary 返回的多组计数并排展示（图片/视频/Live/收藏/分享/相册/回收站/配额），
 * 一个 [Row] 内放若干个 [StatCell] + `Modifier.weight(1f)` 即可均分多列。
 *
 * [value] 为 Int 整数；需要展示非整数文本（如配额百分比）时改用 [valueText]，
 * 此时 [value] 留默认 0（仅占位，不参与显示）。
 */
@Composable
private fun StatCell(
    label: String,
    value: Int = 0,
    valueText: String? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            valueText ?: value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

/**
 * V9：年度回顾对话框——展示该年上传统计详情。
 *
 * 内容：
 * - 总 N 项 + 图片/视频拆分
 * - 最忙的一天（日期 + N 项）
 * - 第一个 / 最后一个上传日期
 * - 12 个月柱状图（FlowRow 方块，深浅按强度着色）
 *
 * [review] 为 null 时显示加载中；拉取失败（仍为 null）显示错误提示。
 * 对话框内容垂直可滚动，避免小屏溢出。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun YearlyReviewDialog(
    year: Int,
    review: MediaService.YearlyReview?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "$year 年度回顾",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            if (review == null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 总数 + 图片/视频/Live 拆分
                    Text(
                        "共 ${review.totalCount} 项 · " +
                            "图片 ${review.byType.image} · " +
                            "视频 ${review.byType.video}" +
                            (if (review.byType.live > 0) " · Live ${review.byType.live}" else ""),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // 最忙的一天
                    if (review.topDay.date.isNotEmpty() && review.topDay.count > 0) {
                        Text(
                            "最忙的一天：${review.topDay.date}（${review.topDay.count} 项）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    // 第一个 / 最后一个上传日期
                    if (review.firstUpload.isNotEmpty() || review.lastUpload.isNotEmpty()) {
                        Text(
                            buildString {
                                if (review.firstUpload.isNotEmpty()) append("首次上传：${review.firstUpload.take(10)}")
                                if (review.firstUpload.isNotEmpty() && review.lastUpload.isNotEmpty()) append("\n")
                                if (review.lastUpload.isNotEmpty()) append("末次上传：${review.lastUpload.take(10)}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                    // 12 个月柱状图：FlowRow 方块，深浅表示当月上传统计强度
                    val maxMonthCount = review.byMonth.maxOf { it.count }.coerceAtLeast(1)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        review.byMonth.forEach { mc ->
                            val intensity = if (mc.count == 0) 0.08f
                            else (mc.count.toFloat() / maxMonthCount).coerceIn(0.15f, 1f)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    modifier = Modifier
                                        .width(22.dp)
                                        .height(36.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = intensity)
                                        )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    "${mc.month}月",
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    // 收藏数（如果 >0）
                    if (review.favorites > 0) {
                        Text(
                            "收藏 ${review.favorites} 项",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

private fun modeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "暗色"
    ThemeMode.AMOLED -> "AMOLED 纯黑"
}

/** 一天对应的毫秒数（UTC）。 */
private const val MILLIS_PER_DAY = 86_400_000L

/**
 * 把上次备份时间（epoch 毫秒）格式化为 "YYYY-MM-DD HH:MM" 本地时间（PRD-v7 §1.5）。
 *
 * commonMain 无 java.time / kotlinx-datetime，用 Howard Hinnant civil_from_days 纯整数
 * 算法分解年月日（与 MediaViewModel.groupMediaByDate 同源做法），时分由当日剩余毫秒折算。
 * 时区偏移由 [systemTimeZoneOffsetMillis] 提供以对齐本地午夜。0L/无效显示"未备份"。
 */
private fun formatBackupTime(timeMs: Long): String {
    if (timeMs <= 0L) return "未备份"
    val tzOffset = systemTimeZoneOffsetMillis()
    // 本地日历日 + 当日内毫秒
    val shifted = timeMs + tzOffset
    val day = if (shifted >= 0) shifted / MILLIS_PER_DAY
    else (shifted - MILLIS_PER_DAY + 1) / MILLIS_PER_DAY
    val millisInDay = (shifted - day * MILLIS_PER_DAY).let { if (it < 0) it + MILLIS_PER_DAY else it }
    val (y, m, d) = civilFromDays(day)
    val totalMinutes = (millisInDay / 60_000L).toInt()
    val hour = totalMinutes / 60
    val minute = totalMinutes % 60
    return "$y-${m.pad2()}-${d.pad2()} ${hour.pad2()}:${minute.pad2()}"
}

/** 十进制两位补零（1 → "01"）。commonMain 无 `String.format`，纯 Kotlin 实现。 */
private fun Int.pad2(): String = if (this < 10) "0$this" else this.toString()

/**
 * Howard Hinnant civil_from_days：自 1970-01-01 起的天数 → (年, 月, 日)。
 * 纯整数运算，无平台依赖。详见 http://howardhinnant.github.io/date_algorithms.html
 */
private fun civilFromDays(z: Long): Triple<Int, Int, Int> {
    val z0 = z + 719468L
    val era = if (z0 >= 0) z0 / 146097 else (z0 - 146096) / 146097
    val doe = z0 - era * 146097                       // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365  // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)  // [0, 365]
    val mp = (5 * doy + 2) / 153                       // [0, 11]
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()      // [1, 31]
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()   // [1, 12]
    val year = if (m <= 2) y + 1 else y
    return Triple(year.toInt(), m, d)
}
