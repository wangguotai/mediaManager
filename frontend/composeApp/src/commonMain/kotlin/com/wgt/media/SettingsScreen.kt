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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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

    // V16：重复文件详情对话框状态
    var showDuplicateReport by remember { mutableStateOf(false) }
    var duplicateReport by remember { mutableStateOf<MediaService.DupReport?>(null) }

    // V21：数据导出（full-report）对话框状态
    var showExportDialog by remember { mutableStateOf(false) }
    var exportJson by remember { mutableStateOf<String?>(null) }   // null=未加载, ""=失败占位
    var isExporting by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // 一键删除重复确认对话框 + 执行状态（完整性报告卡片触发）。
    // showBatchDeleteDialog 控制弹窗；batchDeleting 控制按钮 loading；
    // 用 integrityReport.duplicates 的 count/reclaimableBytes 作确认提示文案。
    // integrityReport/integrityLoading 提升到此处（原在完整性报告卡片内部声明），
    // 以便上方确认 Dialog 与下方卡片都能访问——Dialog 显示前需要读 duplicates 数据。
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var batchDeleting by remember { mutableStateOf(false) }
    var integrityReport by remember { mutableStateOf<MediaService.MediaIntegrityReport?>(null) }
    var integrityLoading by remember { mutableStateOf(true) }

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

    // V16：重复文件详情对话框——打开时加载，关闭时清空以便下次重新拉取
    if (showDuplicateReport) {
        LaunchedEffect(Unit) {
            if (duplicateReport == null) {
                duplicateReport = MediaService.getDupReport()
            }
        }
        DuplicateReportDialog(
            report = duplicateReport,
            onDismiss = { showDuplicateReport = false }
        )
    }

    // V21：数据导出对话框——打开时加载 full-report JSON，展示后可复制到剪贴板
    if (showExportDialog) {
        LaunchedEffect(Unit) {
            if (exportJson == null) {
                isExporting = true
                exportJson = MediaService.getFullReport(2026) ?: ""
                isExporting = false
            }
        }
        ExportReportDialog(
            json = exportJson,
            isExporting = isExporting,
            onCopy = {
                val text = exportJson
                if (!text.isNullOrEmpty()) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                    scope.launch { snackbarHostState.showSnackbar("已复制到剪贴板") }
                }
            },
            onDismiss = {
                showExportDialog = false
                exportJson = null  // 关闭时清空，下次重新拉取
            }
        )
    }

    // 一键删除重复确认对话框——基于完整性报告卡片的 duplicates 数据。
    // 文案动态展示将删除的重复份数（count）与可回收空间（reclaimableBytes）。
    // 确认后调 MediaService.batchDeleteDuplicates()，成功 Snackbar 汇报结果并刷新
    // 完整性报告（重新拉取，使卡片上的重复计数实时归零/下降）。
    if (showBatchDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!batchDeleting) showBatchDeleteDialog = false
            },
            title = { Text("确认删除重复文件") },
            text = {
                val rep = integrityReport
                if (rep != null && rep.duplicates.count > 0) {
                    Text(
                        "将删除 ${rep.duplicates.count} 个重复文件，" +
                            "释放约 ${formatBytesToMB(rep.duplicates.reclaimableBytes)} MB。\n\n" +
                            "每组重复仅保留最早上传的原始件，其余移入回收站，可随时恢复。"
                    )
                } else {
                    Text("没有检测到重复文件。")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !batchDeleting && (integrityReport?.duplicates?.count ?: 0) > 0,
                    onClick = {
                        batchDeleting = true
                        scope.launch {
                            val result = MediaService.batchDeleteDuplicates()
                            batchDeleting = false
                            if (result != null) {
                                showBatchDeleteDialog = false
                                snackbarHostState.showSnackbar(
                                    "已删除 ${result.deletedCount} 个，释放 ${formatBytesToMB(result.freedBytes)}"
                                )
                                // 刷新完整性报告，使卡片重复计数实时更新
                                integrityReport = MediaService.getMediaIntegrityReport()
                            } else {
                                snackbarHostState.showSnackbar("删除失败，请检查后端连接")
                            }
                        }
                    }
                ) {
                    if (batchDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("确认删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !batchDeleting,
                    onClick = { showBatchDeleteDialog = false }
                ) { Text("取消") }
            }
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
            // V24：仪表盘概览卡片 —— 一次调 /api/media/user-dashboard-v2 拿全部6维度数据。
            // 实现（含加载/空态/六维度渲染）抽到 [DashboardOverviewV2Card] 独立 @Composable，
            // 避免 SettingsScreen 主函数体过大触发 JVM 方法 64KB 上限（MethodTooLargeException）。
            DashboardOverviewV2Card()
            Spacer(modifier = Modifier.height(8.dp))

            // V23：智能洞察卡片 —— 调 /api/media/insights 显示自动分析建议（重复/存储/习惯/未标签/相册/健康度）。
            // 放在"存储健康度"前，作为首屏可操作洞察汇总；后端即将提供该端点（端点未上线时按空态提示）。
            var mediaInsights by remember { mutableStateOf<List<MediaService.Insight>?>(null) }
            var mediaInsightsLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                mediaInsights = MediaService.getMediaInsights()
                mediaInsightsLoading = false
            }
            SectionTitle("💡 智能洞察", iconRes = Res.drawable.ic_info)
            if (mediaInsightsLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (mediaInsights == null) {
                Text(
                    "无法获取智能洞察",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (mediaInsights!!.isEmpty()) {
                Text(
                    "暂无洞察建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                mediaInsights!!.forEach { ins ->
                    // type → emoji 映射：duplicate→🔄, storage→📦, habit→⏰, untagged→🏷️, album→📁, health→❤️
                    val emoji = when (ins.type) {
                        "duplicate" -> "🔄"
                        "storage" -> "📦"
                        "habit" -> "⏰"
                        "untagged" -> "🏷️"
                        "album" -> "📁"
                        "health" -> "❤️"
                        else -> "💡"
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(emoji, style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                ins.title,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (ins.detail.isNotEmpty()) {
                            Text(
                                ins.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 28.dp, top = 2.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V24：活跃度评分卡片 —— 调 /api/media/user-activity-score 显示
            // 加权总分 + 等级徽章 + 总进度条 + 分维度明细（每行 action emoji + count + points）。
            // 紧跟智能洞察之后，作为用户活跃度的量化总览；失败显示错误态。
            var activityScore by remember { mutableStateOf<MediaService.UserActivityScore?>(null) }
            var activityScoreLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                activityScore = MediaService.getUserActivityScore()
                activityScoreLoading = false
            }
            SectionTitle("📊 活跃度评分", iconRes = Res.drawable.ic_info)
            if (activityScoreLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (activityScore == null) {
                Text(
                    "无法获取活跃度评分",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val a = activityScore!!
                // 等级颜色：新手灰 / 活跃蓝 / 达人橙 / 专家绿（默认灰）
                val levelColor = when (a.level) {
                    "新手" -> MaterialTheme.colorScheme.outline
                    "活跃" -> Color(0xFF2196F3)
                    "达人" -> Color(0xFFFF9800)
                    "专家" -> Color(0xFF4CAF50)
                    else -> MaterialTheme.colorScheme.outline
                }
                // 大字号评分 + 等级徽章
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${a.score}",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = levelColor
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(levelColor)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            a.level.ifEmpty { "-" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "共 ${a.totalActions} 次操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 总进度条：score / 100（任务明确要求进度条，用 LinearProgressIndicator）
                LinearProgressIndicator(
                    progress = { (a.score / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = levelColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "分维度明细",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                // 分维度明细：每行 action emoji + 名称 + 次数 + 贡献分
                a.breakdown.forEach { b ->
                    val emoji = when (b.action) {
                        "upload" -> "📤"
                        "favorite" -> "⭐"
                        "share" -> "🔗"
                        "tag" -> "🏷️"
                        "rename" -> "✏️"
                        "rotate" -> "🔄"
                        else -> "•"
                    }
                    val name = when (b.action) {
                        "upload" -> "上传"
                        "favorite" -> "收藏"
                        "share" -> "分享"
                        "tag" -> "打标签"
                        "rename" -> "重命名"
                        "rotate" -> "旋转"
                        else -> b.action
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("$emoji $name", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${b.count} 次 · +${b.points} 分",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V22：存储健康度卡片 —— 调 /api/media/storage-health 显示评分+等级+比例条+建议。
            // 放在"数据概览"前，作为首屏健康度总览；后端即将提供该端点。
            var storageHealth by remember { mutableStateOf<MediaService.StorageHealth?>(null) }
            var storageHealthLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                storageHealth = MediaService.getStorageHealth()
                storageHealthLoading = false
            }
            SectionTitle("存储健康度", iconRes = Res.drawable.ic_info)
            if (storageHealthLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (storageHealth == null) {
                Text(
                    "无法获取存储健康度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val sh = storageHealth!!
                // 等级颜色：A 绿 / B 蓝 / C 橙 / D 红（默认灰）
                val gradeColor = when (sh.grade.firstOrNull()?.uppercaseChar()) {
                    'A' -> Color(0xFF4CAF50)
                    'B' -> Color(0xFF2196F3)
                    'C' -> Color(0xFFFF9800)
                    'D' -> Color(0xFFF44336)
                    else -> MaterialTheme.colorScheme.outline
                }
                // 大字号评分 + 等级徽章
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${sh.score}",
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = gradeColor
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(gradeColor)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            sh.grade.ifEmpty { "-" },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "分",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 3 条比例条：重复率 / 配额使用 / 数据温度
                HealthRatioBar(
                    label = "重复率",
                    fraction = sh.duplicateRate.coerceIn(0.0, 1.0).toFloat(),
                    barColor = Color(0xFFEF5350)
                )
                HealthRatioBar(
                    label = "配额使用",
                    fraction = sh.quotaUsage.coerceIn(0.0, 1.0).toFloat(),
                    barColor = Color(0xFF42A5F5)
                )
                HealthRatioBar(
                    label = "数据温度",
                    fraction = sh.ageScore.coerceIn(0.0, 1.0).toFloat(),
                    barColor = Color(0xFFFFA726)
                )
                // 建议列表：每条一行 📌 + 文字
                if (sh.suggestions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    sh.suggestions.forEach { tip ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text("📌", style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                tip,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V25：媒体错误检查卡片 —— 调 /api/media/media-error-check 显示损坏文件列表。
            // 放在"存储健康度"之后、"数据概览"之前。展示检查项数 + 错误数 + 错误列表（仅
            // totalErrors>0 时显示列表），并提供"重新检查"按钮触发重新拉取。
            var mediaErrorReport by remember { mutableStateOf<MediaService.MediaErrorReport?>(null) }
            var mediaErrorLoading by remember { mutableStateOf(true) }
            val mediaErrorScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                mediaErrorReport = MediaService.getMediaErrorCheck()
                mediaErrorLoading = false
            }
            SectionTitle("🔍 媒体错误检查", iconRes = Res.drawable.ic_info)
            if (mediaErrorLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (mediaErrorReport == null) {
                Text(
                    "无法获取媒体错误检查结果",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val report = mediaErrorReport!!
                // 汇总行：检查 N 项，发现 M 个错误
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "检查 ${report.totalChecked} 项，发现 ${report.totalErrors} 个错误",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            mediaErrorLoading = true
                            mediaErrorScope.launch {
                                mediaErrorReport = MediaService.getMediaErrorCheck()
                                mediaErrorLoading = false
                            }
                        }
                    ) { Text("重新检查", fontSize = 13.sp) }
                }
                // 错误列表：仅 totalErrors > 0 时显示，每行 filename + error_type
                if (report.totalErrors > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    report.errors.forEach { err ->
                        val errorTypeText = when (err.errorType) {
                            "zero_size" -> "数据损坏"
                            "missing_file" -> "文件缺失"
                            "size_mismatch" -> "大小不符"
                            else -> err.errorType.ifEmpty { "未知" }
                        }
                        val errorColor = when (err.errorType) {
                            "missing_file" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.tertiary
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                "⚠️",
                                style = MaterialTheme.typography.bodySmall,
                                color = errorColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                err.filename.ifEmpty { err.mediaId },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                errorTypeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = errorColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V25：完整性报告卡片 —— 调 /api/media/media-integrity-report 显示综合完整性
            // 评分 + A/B/C/D 等级 + 四维度（孤立/错误/重复/总媒体）统计。放在"媒体错误检查"
            // 之后、"数据概览"之前，作为存储健康类卡片的总结收尾。
            // 注：integrityReport/integrityLoading 提升到 Composable 顶部声明（供确认 Dialog 访问）。
            LaunchedEffect(Unit) {
                integrityReport = MediaService.getMediaIntegrityReport()
                integrityLoading = false
            }
            SectionTitle("🛡️ 完整性报告", iconRes = Res.drawable.ic_info)
            if (integrityLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (integrityReport == null) {
                Text(
                    "无法获取完整性报告",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val rep = integrityReport!!
                // 等级颜色：A 绿 / B 蓝 / C 橙 / D 红
                val gradeColor = when (rep.grade) {
                    "A" -> Color(0xFF2E7D32)
                    "B" -> Color(0xFF1565C0)
                    "C" -> Color(0xFFE65100)
                    else -> MaterialTheme.colorScheme.error
                }
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    // 大字号：评分 + 等级 徽章
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Text(
                            "${rep.integrityScore}",
                            fontSize = 48.sp,
                            fontWeight = FontWeight.Bold,
                            color = gradeColor
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            "/ 100",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            rep.grade,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = gradeColor
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // 进度条：score / 100
                    LinearProgressIndicator(
                        progress = { (rep.integrityScore / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = gradeColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    // 四维度统计行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${rep.orphans.count}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("孤立", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${rep.errors.count}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("错误", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${rep.duplicates.groups}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("重复组", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${rep.totalMedia}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("总媒体", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    // 重复可回收空间（仅有重复时显示）
                    if (rep.duplicates.reclaimableBytes > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "重复可回收 ${formatBytesToMB(rep.duplicates.reclaimableBytes)} MB（${rep.duplicates.count} 份）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    // 一键删除重复按钮：存在重复组时展示。点击弹确认 Dialog（由上方
                    // showBatchDeleteDialog 控制），后端按 SHA256 分组保留最早原件、其余软删。
                    if (rep.duplicates.count > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showBatchDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) { Text("一键删除重复") }
                        Text(
                            "保留每组最早原件，其余移入回收站可恢复",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // 近似重复检测卡片 —— 调 /api/media/media-duplicates-similar 显示近似重复文件对
            // （SHA256 不同但同类型+大小相近+同分辨率），与完整性报告/精确重复互补。
            var dupSimilarPairs by remember { mutableStateOf<List<MediaService.DupSimilarPair>?>(null) }
            var dupSimilarLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                dupSimilarPairs = MediaService.getMediaDuplicatesSimilar()
                dupSimilarLoading = false
            }
            SectionTitle("🔍 近似重复检测", iconRes = Res.drawable.ic_info)
            if (dupSimilarLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (dupSimilarPairs == null) {
                Text(
                    "无法获取近似重复检测",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (dupSimilarPairs!!.isEmpty()) {
                Text(
                    "未发现近似重复文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val pairs = dupSimilarPairs!!
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "发现 ${pairs.size} 对近似重复",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // 最多展示 5 对
                    pairs.take(5).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                pair.filenameA.ifEmpty { pair.mediaAId },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text("↔", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            Text(
                                pair.filenameB.ifEmpty { pair.mediaBId },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            "${formatBytesToMB(pair.size)} MB · ${pair.resolution}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // 深度存储分析卡片 —— 调 /api/media/storage-deep-analysis 的 by_year_type 矩阵，
            // 按"年份 → 图片 N MB + 视频 N MB"展示最近 3 年的存储分布。与上方"近似重复"互补：
            // 那个找冗余，这个看时间维度分布。与完整性报告/存储健康度同款 LaunchedEffect 拉取。
            var storageDeep by remember { mutableStateOf<MediaService.StorageDeepAnalysis?>(null) }
            var storageDeepLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                storageDeep = MediaService.getStorageDeepAnalysis()
                storageDeepLoading = false
            }
            SectionTitle("📊 深度存储分析", iconRes = Res.drawable.ic_info)
            if (storageDeepLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (storageDeep == null) {
                Text(
                    "无法获取深度存储分析",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (storageDeep!!.byYearType.isEmpty()) {
                Text(
                    "暂无媒体数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                // 年份按字符串降序（4 位年份同字典序），取最近 3 年。
                val years = storageDeep!!.byYearType.keys.sortedDescending().take(3)
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    years.forEach { year ->
                        val typeMap = storageDeep!!.byYearType[year].orEmpty()
                        val img = typeMap["IMAGE"]
                        val vid = typeMap["VIDEO"]
                        val lp = typeMap["LIVE_PHOTO"]
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "$year 年",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.width(56.dp)
                            )
                            Text(
                                "图片 ${img?.count ?: 0} · ${formatBytesToMB((img?.bytes ?: 0L).toDouble())} MB",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "视频 ${vid?.count ?: 0} · ${formatBytesToMB((vid?.bytes ?: 0L).toDouble())} MB",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        // 动态照片若该年有数据则补一行（与图片/视频同口径，次要展示）。
                        if (lp != null && (lp.count > 0 || lp.bytes > 0L)) {
                            Text(
                                "　　动态照片 ${lp.count} · ${formatBytesToMB(lp.bytes.toDouble())} MB",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.padding(start = 56.dp, bottom = 4.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "合计 ${storageDeep!!.totalCount} 个 · ${formatBytesToMB(storageDeep!!.totalBytes.toDouble())} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            StorageMatrixCard()

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V22：归档建议卡片 —— 调 /api/media/archive-suggest 显示冷数据 + 大视频归档候选，
            // 列出每项文件名 / 大小 / 归档年龄，并给出可释放空间汇总。
            // 放在"完整性报告"之后、\"数据概览\"之前。
            var archiveSuggest by remember { mutableStateOf<MediaService.ArchiveSuggest?>(null) }
            var archiveSuggestLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                archiveSuggest = MediaService.getArchiveSuggest()
                archiveSuggestLoading = false
            }
            SectionTitle("📦 归档建议", iconRes = Res.drawable.ic_info)
            if (archiveSuggestLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (archiveSuggest == null) {
                Text(
                    "无法获取归档建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (archiveSuggest!!.shouldArchive.not()) {
                Text(
                    "暂无需归档的冷数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val sug = archiveSuggest!!
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(
                        "📦 建议归档 ${sug.totalCount} 项冷数据（可释放 ${formatDouble2(sug.potentialSavingsMb)} MB）",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    sug.mediaToArchive.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                item.filename.ifEmpty { item.mediaId },
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "${formatBytesToMB(item.size)} MB · ${item.ageDays} 天",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

            // V25：照片组织建议卡片 —— 调 /api/media/photo-organize-suggest 显示按
            // 月份/类型/未标签维度产出的组织建议，每条附"创建相册"一键按钮
            // （调 createAlbum + batchAddMediaToAlbum(previewIds)）。
            // 放在"归档建议"之后、"数据概览"之前。最多展示 5 条（后端阈值保守，
            // 通常条目不多，仍 cap 以防极端库产出长列表）。
            var photoOrganize by remember { mutableStateOf<List<MediaService.OrganizeSuggestion>?>(null) }
            var photoOrganizeLoading by remember { mutableStateOf(true) }
            // 记录正在创建相册的建议 name（按钮 loading 态），空串=空闲。同一时刻仅一条在创建。
            var creatingAlbumFor by remember { mutableStateOf("") }
            LaunchedEffect(Unit) {
                photoOrganize = MediaService.getPhotoOrganizeSuggest()
                photoOrganizeLoading = false
            }
            SectionTitle("📁 照片组织建议", iconRes = Res.drawable.ic_info)
            if (photoOrganizeLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (photoOrganize == null) {
                Text(
                    "无法获取照片组织建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (photoOrganize!!.isEmpty()) {
                Text(
                    "暂无组织建议，媒体库已井井有条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                photoOrganize!!.take(5).forEach { sug ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 📁 建议名 (N 项)
                            Text(
                                "📁 ${sug.name}（${sug.mediaCount} 项）",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            sug.reason,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        // 一键创建相册：createAlbum(name) → batchAddMediaToAlbum(albumId, previewIds)。
                        // previewIds 为后端给出的预览子集（≤4），作为相册初始成员；
                        // 创建中禁用按钮防重复点击；结果经 Snackbar 反馈。
                        OutlinedButton(
                            onClick = {
                                if (creatingAlbumFor.isNotEmpty()) return@OutlinedButton
                                val ids = sug.previewIds
                                if (ids.isEmpty()) {
                                    scope.launch { snackbarHostState.showSnackbar("该建议无可加入相册的预览媒体") }
                                    return@OutlinedButton
                                }
                                creatingAlbumFor = sug.name
                                scope.launch {
                                    val albumName = sug.name.ifEmpty { "新建相册" }
                                    val album = MediaService.createAlbum(albumName)
                                    if (album == null) {
                                        creatingAlbumFor = ""
                                        snackbarHostState.showSnackbar("创建相册失败")
                                        return@launch
                                    }
                                    val added = MediaService.batchAddMediaToAlbum(album.id, ids)
                                    creatingAlbumFor = ""
                                    if (added == null) {
                                        snackbarHostState.showSnackbar("相册已创建，但加入媒体失败")
                                    } else {
                                        snackbarHostState.showSnackbar(
                                            "已创建相册「${album.name}」，加入 $added 项"
                                        )
                                    }
                                }
                            },
                            enabled = creatingAlbumFor.isEmpty() || creatingAlbumFor == sug.name
                        ) {
                            if (creatingAlbumFor == sug.name) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("创建中…")
                            } else {
                                Text("创建相册")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))

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
            // V8：媒体生命周期卡片
            var lifecycleData by remember { mutableStateOf<List<MediaService.LifecycleStage>?>(null) }
            LaunchedEffect(Unit) {
                orphanScope.launch { lifecycleData = MediaService.getMediaLifecycle() }
            }
            SectionTitle("媒体生命周期", iconRes = Res.drawable.ic_info)
            lifecycleData?.let { stages ->
                if (stages.isNotEmpty()) {
                    stages.take(8).forEach { stg ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${stg.stage} (${stg.action})",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "${stg.count} 次 · ${stg.percentage.toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                } else {
                    Text("暂无操作记录", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
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
            // V21：数据导出——一键拉取 full-report 综合报告 JSON，弹窗展示供复制/分享
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("数据导出", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "导出年度综合报告（JSON）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            TextButton(
                onClick = {
                    exportJson = null  // 清空以触发重新拉取
                    showExportDialog = true
                },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isExporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("导出中…")
                } else {
                    Text("导出报告")
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
            // V8：操作时间线卡片（GET /api/media/audit-timeline?limit=N）
            // 逐条展示最近操作，每行：emoji + detail + 相对时间；最多渲染 15 条，
            // 底部"加载更多"按钮按 +50 递增 limit 重新拉取。
            var auditTimeline by remember { mutableStateOf<List<MediaService.AuditTimelineItem>?>(null) }
            var auditTimelineLimit by remember { mutableStateOf(50) }
            var auditTimelineLoading by remember { mutableStateOf(true) }
            LaunchedEffect(auditTimelineLimit) {
                auditTimelineLoading = true
                auditTimeline = MediaService.getAuditTimeline(auditTimelineLimit)
                auditTimelineLoading = false
            }
            SectionTitle("操作时间线", iconRes = Res.drawable.ic_info)
            auditTimeline?.let { items ->
                if (items.isNotEmpty()) {
                    items.take(15).forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 3.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                auditActionEmoji(entry.action),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                entry.detail.ifEmpty { entry.action },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                entry.relativeTime,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                    }
                    // 已加载但未展示完（items > 15）或已达 limit 上限时可继续加载更多
                    if (items.size >= 15) {
                        TextButton(
                            onClick = { auditTimelineLimit += 50 },
                            enabled = !auditTimelineLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (auditTimelineLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("加载中…")
                            } else {
                                Text("加载更多")
                            }
                        }
                    }
                } else {
                    Text(
                        "暂无操作记录",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                }
            } ?: if (auditTimelineLoading) {
                Text(
                    "加载中...",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            } else {
                Text(
                    "加载失败",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                )
            }
            // V23：媒体覆盖率卡片（GET /api/media/media-coverage）
            // 显示标签/收藏/分享/相册 4 个维度的覆盖率，每行带 LinearProgressIndicator。
            var mediaCoverage by remember { mutableStateOf<MediaService.MediaCoverage?>(null) }
            var mediaCoverageLoading by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                mediaCoverage = MediaService.getMediaCoverage()
                mediaCoverageLoading = false
            }
            SectionTitle("媒体覆盖率", iconRes = Res.drawable.ic_info)
            if (mediaCoverageLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (mediaCoverage == null) {
                Text(
                    "无法获取媒体覆盖率",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val mc = mediaCoverage!!
                val total = mc.total
                val rows = listOf(
                    Triple("🏷️", "已标签", mc.tagged),
                    Triple("⭐", "已收藏", mc.favorited),
                    Triple("🔗", "已分享", mc.shared),
                    Triple("📁", "在相册", mc.inAlbum)
                )
                rows.forEach { (icon, label, item) ->
                    CoverageRow(icon, label, item.count, total, item.percent)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
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
            // V15：清理建议卡片（GET /api/media/storage-recommendations + POST duplicate-cleanup）
            var recs by remember { mutableStateOf<MediaService.StorageRecommendations?>(null) }
            var recsLoading by remember { mutableStateOf(true) }
            var cleaning by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                orphanScope.launch {
                    recs = MediaService.getStorageRecommendations()
                    recsLoading = false
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = 6.dp)
            )
            SectionTitle("清理建议", iconRes = Res.drawable.ic_delete)
            if (recsLoading) {
                Text(
                    "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else if (recs == null) {
                Text(
                    "无法获取清理建议",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                )
            } else {
                val r = recs!!
                // 🔄 重复文件
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🔄 重复文件", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${r.duplicates.count} 个 · 可回收 ${formatBytesToMB(r.totalReclaimableBytes.toDouble())}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // V16：查看重复文件详情按钮——弹 Dialog 列出每组重复的文件名/大小/可回收空间
                TextButton(
                    onClick = {
                        duplicateReport = null  // 清空以触发重新拉取
                        showDuplicateReport = true
                    },
                    enabled = r.duplicates.count > 0,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) { Text("查看重复详情") }
                // 📦 大文件（top 3，后端已按 size 倒序，本地取前 3）
                if (r.largeFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "📦 大文件 · top ${minOf(3, r.largeFiles.size)}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    r.largeFiles.take(3).forEach { lf ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 1.dp)
                                .padding(start = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                lf.filename.ifEmpty { "(未命名)" },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                formatBytesToMB(lf.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
                // 📅 旧文件
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📅 旧文件", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${r.oldFiles.count} 个 · ${formatBytesToMB(r.oldFiles.bytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // 总计可回收
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "总计可回收",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        formatBytesToMB(r.totalReclaimableBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // 一键清理重复按钮（仅在有重复时启用）
                Button(
                    onClick = {
                        if (cleaning) return@Button
                        cleaning = true
                        orphanScope.launch {
                            val deleted = MediaService.cleanupDuplicates()
                            cleaning = false
                            if (deleted != null) {
                                if (deleted > 0) {
                                    snackbarHostState.showSnackbar("已清理 $deleted 个重复文件")
                                } else {
                                    snackbarHostState.showSnackbar("无重复文件可清理")
                                }
                                // 刷新建议数据（清理后重复数应降为 0）
                                recs = MediaService.getStorageRecommendations()
                            } else {
                                snackbarHostState.showSnackbar("清理失败，请重试")
                            }
                        }
                    },
                    enabled = !cleaning && r.duplicates.count > 0,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    if (cleaning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("清理中...")
                    } else {
                        Text("一键清理重复（${r.duplicates.count}）")
                    }
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

/**
 * V24：仪表盘概览卡片 —— 一次调 [MediaService.getUserDashboardV2] 拿全部6维度数据，
 * 聚合展示健康度/活跃度/覆盖率/连续上传天数/Top1洞察。
 *
 * 抽成独立 @Composable 而非内联进 [SettingsScreen]，避免主函数体过大触发 JVM 方法
 * 64KB 上限（MethodTooLargeException）。加载中/失败/成功三态自洽，调用方无需传参。
 *
 * 渲染项（对齐任务规格）：
 * - 健康度：score/100 + grade（A绿/B蓝/C橙/D红，配色与进度条联动）
 * - 活跃度：score + level（新手/活跃/达人/专家）
 * - 覆盖率：tagged% + favorited%
 * - 连续：🔥 N 天（附最长记录）
 * - 洞察：top 1 insight 的 title + detail
 */
@Composable
private fun DashboardOverviewV2Card() {
    var dashboardV2 by remember { mutableStateOf<MediaService.UserDashboardV2?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        dashboardV2 = MediaService.getUserDashboardV2()
        loading = false
    }
    SectionTitle("📊 仪表盘概览", iconRes = Res.drawable.ic_info)
    if (loading) {
        Text(
            "加载中...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else if (dashboardV2 == null) {
        Text(
            "无法获取仪表盘数据",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
        )
    } else {
        val db = dashboardV2!!
        // 健康度：score/100 + grade（A绿色/B蓝色/C橙色/D红色）
        val gradeColor = when (db.health.grade) {
            "A" -> Color(0xFF4CAF50)
            "B" -> Color(0xFF2196F3)
            "C" -> Color(0xFFFF9800)
            else -> Color(0xFFF44336)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("健康度", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${db.health.score}/100  ${db.health.grade}级",
                style = MaterialTheme.typography.bodyLarge,
                color = gradeColor,
                fontWeight = FontWeight.Medium
            )
        }
        LinearProgressIndicator(
            progress = { (db.health.score / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = gradeColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        // 活跃度：score + level
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("活跃度", style = MaterialTheme.typography.bodyLarge)
            Text(
                "${db.activity.score} 分  ${db.activity.level}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        // 覆盖率：tagged% + favorited%
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("覆盖率", style = MaterialTheme.typography.bodyLarge)
            Text(
                "标签 ${db.coverage.taggedPercent}% · 收藏 ${db.coverage.favoritedPercent}%",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        // 连续上传天数
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("连续上传", style = MaterialTheme.typography.bodyLarge)
            Text(
                "🔥 ${db.streak.currentStreak} 天" +
                    if (db.streak.longestStreak > 0) "（最长 ${db.streak.longestStreak}）" else "",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
        // 洞察：top 1 insight
        db.insights.firstOrNull()?.let { ins ->
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text("💡", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        ins.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (ins.detail.isNotEmpty()) {
                        Text(
                            ins.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
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
 * 操作类型 → emoji 映射，用于"操作时间线"卡片每行前缀。
 *
 * 后端 [handleAuditTimeline] 的 action 字段取自 audit_log.action，记录时为动词
 * （upload/delete/share/rename/favorite/tag/restore/rotate 等）。此处按已知动作给 emoji；
 * 未知动作回退通用 ":memo:"，保证行不破。匹配对大小写不敏感，覆盖后端可能的小写埋点。
 */
private fun auditActionEmoji(action: String): String = when (action.lowercase()) {
    "upload" -> "📤"
    "delete" -> "🗑️"
    "share" -> "🔗"
    "rename" -> "✏️"
    "favorite" -> "⭐"
    "tag" -> "🏷️"
    "restore" -> "♻️"
    "rotate" -> "🔄"
    else -> "📝"
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
 * 存储健康度卡片（V22）的比例条：标签 + 百分比在上一行，下方一条灰底满宽槽内
 * 按 [fraction] 填充 [barColor] 色条。用于重复率/配额使用/数据温度三项 0.0-1.0 指标。
 *
 * [fraction] 会被 [coerceIn] 限制到 0..1，避免后端越界值撑破布局。
 */
@Composable
private fun HealthRatioBar(
    label: String,
    fraction: Float,
    barColor: Color
) {
    val clamped = fraction.coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${(clamped * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(barColor)
            )
        }
    }
}

/**
 * 媒体覆盖率卡片（V23）的单行：emoji + 维度名 + "count/total (percent%)" 在上一行，
 * 下方一条 [LinearProgressIndicator] 按 percent/100 填充。
 *
 * [percent] 为 0-100 的百分比数值（后端给定），内部转 0.0-1.0 的进度小数并 [coerceIn]
 * 兜底，避免后端越界（>100 或 <0）撑破指示器。total<=0 时进度置 0，避免除零。
 */
@Composable
private fun CoverageRow(
    icon: String,
    label: String,
    count: Int,
    total: Int,
    percent: Double
) {
    val progress = if (total > 0) (percent / 100.0).toFloat().coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("$icon $label", style = MaterialTheme.typography.bodyMedium)
            Text(
                "$count/$total (${percent.toInt()}%)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
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

/**
 * V16：重复文件详情对话框。
 *
 * 调 [MediaService.getDupReport] 展示按 SHA256 分组的重复文件列表。每组显示
 * SHA256 前 8 位 + 重复数 + 可回收空间，列出前 3 个文件名；底部汇总总可回收空间。
 * [report] 为 null 时显示加载中；拉取失败仍为 null 时显示错误提示。内容垂直可滚动。
 */
@Composable
private fun DuplicateReportDialog(
    report: MediaService.DupReport?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("重复文件详情", fontWeight = FontWeight.Bold)
        },
        text = {
            if (report == null) {
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
            } else if (report.duplicates.isEmpty()) {
                Text(
                    "暂无重复文件",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    report.duplicates.forEach { group ->
                        // 每组：SHA256 前 8 位 · N 重复 · 可回收 MB
                        Text(
                            "${group.sha256.take(8)} · ${group.count} 个重复 ·" +
                                " 可回收 ${formatBytesToMB(group.size)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        // 前 3 个文件名 + 大小
                        group.media.take(3).forEach { m ->
                            Text(
                                "  ${m.filename.ifEmpty { "(未命名)" }} · ${formatBytesToMB(m.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                maxLines = 1
                            )
                        }
                        if (group.media.size > 3) {
                            Text(
                                "  …及其余 ${group.media.size - 3} 个",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                    // 底部汇总
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "总可回收",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            formatBytesToMB(report.totalReclaimableBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
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

/**
 * V21：数据导出对话框——展示 full-report 综合报告的 JSON 文本，可滚动，带"复制到剪贴板"按钮。
 *
 * - [json] 为 null：加载中（CircularProgressIndicator）
 * - [json] 为空串 ""：拉取失败，显示错误提示
 * - [json] 非空：左侧 monospace 文本纵向滚动展示，用户可全选手动复制或点按钮一键复制
 *
 * 用 [AlertDialog] 而非自定义 Dialog，保持与 [YearlyReviewDialog]/[DuplicateReportDialog] 同款。
 * 确认按钮为"复制到剪贴板"，取消按钮为"关闭"。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportReportDialog(
    json: String?,
    isExporting: Boolean,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("数据导出", fontWeight = FontWeight.Bold)
        },
        text = {
            when {
                isExporting || json == null -> {
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
                        Text("加载综合报告…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                json.isEmpty() -> {
                    Text(
                        "导出失败，请检查后端连接后重试。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                else -> {
                    // JSON 文本区：限定高度 + 纵向滚动，monospace 字体便于阅读键值
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(4.dp)
                    ) {
                        Text(
                            text = json,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCopy,
                enabled = !json.isNullOrEmpty()
            ) { Text("复制到剪贴板") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
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
 * Double 保留 2 位小数（用于后端返回的 MB 数）。commonMain 无 `String.format`/`%.2f`，
 * 用 toString + take 截断实现（NaN/Infinity 原样返回）。
 */
private fun formatDouble2(v: Double): String {
    if (v.isNaN() || v.isInfinite()) return v.toString()
    val s = v.toString()
    val dot = s.indexOf('.')
    return if (dot < 0) s else s.take(dot + 3)
}

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

/** V8：存储矩阵卡片 —— 调 storage-breakdown-v2 显示类型×年份表格。 */
@Composable
fun StorageMatrixCard() {
    var data by remember { mutableStateOf<MediaService.StorageBreakdownV2?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        data = MediaService.getStorageBreakdownV2()
        loading = false
    }
    SectionTitle("🧮 存储矩阵", iconRes = Res.drawable.ic_info)
    when {
        loading -> Text("加载中...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
        data == null -> Text("无法获取存储矩阵", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
        data!!.matrix.isEmpty() -> Text("暂无媒体数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
        else -> {
            val m = data!!
            val years = m.matrix.values.flatMap { it.keys }.toSet().sortedDescending().take(3)
            fun cellCount(type: String, year: String): Int = m.matrix[type]?.get(year)?.count ?: 0
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("年份", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.width(52.dp))
                    Text("图片", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("视频", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("Live", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))
                years.forEach { year ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$year", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(52.dp))
                        Text("${cellCount("IMAGE", year)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text("${cellCount("VIDEO", year)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                        Text("${cellCount("LIVE_PHOTO", year)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text("合计 ${m.totalCount} 项 · ${formatBytesToMB(m.totalBytes.toDouble())} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}
