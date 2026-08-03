package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import mediamanager.composeapp.generated.resources.ic_check_circle
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_cloud
import mediamanager.composeapp.generated.resources.ic_cloud_upload
import mediamanager.composeapp.generated.resources.ic_info
import mediamanager.composeapp.generated.resources.ic_openclaw
import mediamanager.composeapp.generated.resources.ic_palette
import mediamanager.composeapp.generated.resources.ic_settings
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi

private const val TAG = "SettingsScreen"

/**
 * 设置项的跨屏幕共享状态。单例，进程内唯一：
 * - [App.kt] 读取 [themeMode] 决定浅色/暗色色板（SYSTEM 时跟随系统）。
 * - [SettingsScreen] 读写 [themeMode] 与后端地址，落地到 [SettingsStorage]。
 * - 各子页（[BackupSettingsScreen]/[AppearanceScreen]/…）也读写各自相关字段。
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
 * 设置枢纽页（L1 任务 C：纯设置枢纽，仅保留"后端地址/账号"+ 子页入口）。
 *
 * 瘦身后的职责：
 * 1. 后端地址：输入框 + 保存按钮 + 测试连通性按钮。保存写入
 *    [SettingsState.saveBackendUrl]；测试走 [pingBackend]。
 * 2. 账号：当前用户展示 + 退出登录。
 * 3. 五个子页入口行（点击跳转）：
 *    - 云相册备份 → [BackupSettingsScreen]
 *    - 外观 → [AppearanceScreen]
 *    - 媒体工具 → [MediaToolsScreen]
 *    - 关于 → [AboutScreen]
 *    - 开发者 → [DeveloperScreen]
 *
 * 原云相册/主题/回收站/孤立/自动打标签/RN更新/缓存清理/OpenClaw 等区已迁入对应子页。
 *
 * @param onBack 返回媒体列表
 * @param onNavigateToBackup 跳转云相册备份页
 * @param onNavigateToAppearance 跳转外观页
 * @param onNavigateToMediaTools 跳转媒体工具页
 * @param onNavigateToAbout 跳转关于页
 * @param onNavigateToDeveloper 跳转开发者页
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun SettingsScreen(
    viewModel: MediaViewModel,
    onBack: () -> Unit,
    onNavigateToBackup: () -> Unit = {},
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToMediaTools: () -> Unit = {},
    onNavigateToAbout: () -> Unit = {},
    onNavigateToDeveloper: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 后端地址编辑态：独立于 SettingsState，点"保存"才落地，避免边输入边写盘。
    var urlInput by remember { mutableStateOf(SettingsState.backendUrl) }
    var savedUrl by remember { mutableStateOf(SettingsState.backendUrl) }

    // 连通性测试状态
    var isPinging by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<String?>(null) } // null=未测, ""=成功, 非空=失败描述

    // 监听 Snackbar 触发（pingResult 改变后）
    LaunchedEffect(pingResult) {
        val r = pingResult ?: return@LaunchedEffect
        if (r.isEmpty()) snackbarHostState.showSnackbar("连通成功 ✓")
        else snackbarHostState.showSnackbar("连通失败：$r")
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

            // ---- 子页入口 ----
            SectionTitle("更多设置", iconRes = Res.drawable.ic_info)

            // 云相册备份
            EntryRow(
                iconRes = Res.drawable.ic_cloud_upload,
                title = "云相册备份",
                subtitle = "自动备份 · 仅 WiFi · 设备登记",
                onClick = onNavigateToBackup
            )
            // 外观
            EntryRow(
                iconRes = Res.drawable.ic_palette,
                title = "外观",
                subtitle = "主题模式 · 浅色 / 暗色 / AMOLED",
                onClick = onNavigateToAppearance
            )
            // 媒体工具
            EntryRow(
                iconRes = Res.drawable.ic_settings,
                title = "媒体工具",
                subtitle = "回收站 · 孤立文件 · 自动打标签 · 存储清理",
                onClick = onNavigateToMediaTools
            )
            // 关于
            EntryRow(
                iconRes = Res.drawable.ic_info,
                title = "关于",
                subtitle = "版本 · 后端信息 · 更新检查 · 缓存清理",
                onClick = onNavigateToAbout
            )
            // 开发者
            EntryRow(
                iconRes = Res.drawable.ic_openclaw,
                title = "开发者",
                subtitle = "OpenClaw 命令桥梁",
                onClick = onNavigateToDeveloper
            )
        }
    }
}

/**
 * 子页入口行：左侧图标 + 标题/副标题 + 右侧箭头「›」。
 * 用 [DrawableResource] 图标（与原 SectionTitle 同源 painter），保持设置页视觉口径。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun EntryRow(
    iconRes: DrawableResource,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "›",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalResourceApi::class)
@Composable
internal fun SectionTitle(text: String, iconRes: DrawableResource? = null) {
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
