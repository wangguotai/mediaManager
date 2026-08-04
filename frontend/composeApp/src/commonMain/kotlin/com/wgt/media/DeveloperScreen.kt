package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.wgt.feature.media.MediaService
import com.wgt.media.ui.SectionHeader
import com.wgt.media.ui.SettingsRow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 开发者页（L1 任务 C：从 SettingsScreen 拆出）。
 *
 * 承接原 SettingsScreen 的「OpenClaw」区：命令桥梁对话框入口。
 * 独立成页便于后续扩展更多开发者工具（日志查看、网络抓包开关、调试标志等）。
 *
 * @param onBack 返回设置枢纽页
 * @param viewModel 备用（OpenClaw 桥梁对话框自带内部视图模型）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun DeveloperScreen(
    onBack: () -> Unit,
    viewModel: MediaViewModel
) {
    // OpenClaw 桥梁对话框状态 + 视图模型
    var showOpenClawDialog by remember { mutableStateOf(false) }
    val openClawViewModel = remember { OpenClawViewModel() }

    // 运维面板数据：后端 GET /api/ops/observability-dashboard 返回原始 JsonObject，前端自行解析。
    // 三态：loading / data(JsonObject?) / error(String?)。进入页面即拉取一次。
    var dashboardLoading by remember { mutableStateOf(true) }
    var dashboardData by remember { mutableStateOf<JsonObject?>(null) }
    var dashboardError by remember { mutableStateOf<String?>(null) }
    val dashboardScope = rememberCoroutineScope()

    // 分享管理数据三态。listShares 返回 null=失败、空列表=无分享。
    var sharesLoading by remember { mutableStateOf(true) }
    var shares by remember { mutableStateOf<List<MediaService.ShareInfo>?>(null) }
    var sharesError by remember { mutableStateOf<String?>(null) }
    var sharesRevoking by remember { mutableStateOf(false) }
    val sharesScope = rememberCoroutineScope()

    // 设备管理数据三态。listDevices 返回 null=失败、空列表=无设备。
    var devicesLoading by remember { mutableStateOf(true) }
    var devices by remember { mutableStateOf<List<MediaService.DeviceInfo>?>(null) }
    var devicesError by remember { mutableStateOf<String?>(null) }
    val devicesScope = rememberCoroutineScope()

    // OpenClaw 桥梁命令对话框
    if (showOpenClawDialog) {
        OpenClawCommandDialog(
            viewModel = openClawViewModel,
            onDismiss = { showOpenClawDialog = false }
        )
    }

    // 进入页面拉取一次运维面板数据。失败不阻塞页面，仅在卡片区展示错误文案。
    LaunchedEffect(Unit) {
        dashboardScope.launch {
            val result = MediaService.getObservabilityDashboard()
            if (result == null) {
                dashboardError = "运维面板加载失败，请稍后重试"
            } else {
                dashboardData = result
            }
            dashboardLoading = false
        }
    }

    // 分享管理：进入页面拉取一次。listShares 返回 null 视为错误。
    LaunchedEffect(Unit) {
        sharesScope.launch {
            val result = MediaService.listShares()
            if (result == null) {
                sharesError = "分享列表加载失败，请稍后重试"
            } else {
                shares = result
            }
            sharesLoading = false
        }
    }

    // 设备管理：进入页面拉取一次。listDevices 返回 null 视为错误。
    LaunchedEffect(Unit) {
        devicesScope.launch {
            val result = MediaService.listDevices()
            if (result == null) {
                devicesError = "设备列表加载失败，请稍后重试"
            } else {
                devices = result
            }
            devicesLoading = false
        }
    }

    /**
     * 撤销单条分享。调用 [MediaService.revokeShareLink]（DELETE /api/share/{token}），
     * 成功后从本地列表移除该条；失败展示错误文案，列表不变。
     * 使用 deleteShare 亦可（两者路径相同），此处用 revokeShareLink 与 RN 模块口径一致。
     */
    fun revokeOne(share: MediaService.ShareInfo) {
        sharesScope.launch {
            sharesRevoking = true
            val ok = MediaService.revokeShareLink(share.token)
            if (ok) {
                shares = shares?.filter { it.token != share.token }
            } else {
                sharesError = "撤销失败：${share.token.take(8)}…"
            }
            sharesRevoking = false
        }
    }

    /**
     * 全部撤销：循环调 [MediaService.deleteShare]（DELETE /api/share/{token}）。
     * 逐条删除，统计失败数；结束后刷新列表。期间按钮置灰（sharesRevoking）。
     */
    fun revokeAllShares() {
        val current = shares ?: return
        sharesScope.launch {
            sharesRevoking = true
            var failed = 0
            current.forEach { share ->
                val ok = MediaService.deleteShare(share.token)
                if (!ok) failed++
            }
            // 重新拉取最新列表，保证与服务端一致
            val fresh = MediaService.listShares()
            shares = fresh
            if (failed > 0) {
                sharesError = "全部撤销完成，$failed 条失败"
            }
            sharesRevoking = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "开发者",
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
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SectionHeader("OpenClaw", icon = null)

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("OpenClaw 命令桥梁", style = androidx.compose.material3.MaterialTheme.typography.bodyLarge)
                OutlinedButton(onClick = { showOpenClawDialog = true }) {
                    Text("打开")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)

            // ============ 运维面板 ============
            SectionHeader("运维面板", icon = null)

            if (dashboardLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.height(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "加载运维数据…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (dashboardError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        dashboardError!!,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontSize = 13.sp
                    )
                }
            } else {
                dashboardData?.let { ObservabilityDashboardCard(it) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)

            // ============ 分享管理 ============
            SectionHeader("分享管理", icon = null)
            if (sharesLoading) {
                LoadingCard(label = "加载分享列表…")
            } else if (sharesError != null) {
                ErrorCard(sharesError!!)
            } else {
                val shareList = shares
                if (shareList.isNullOrEmpty()) {
                    EmptyCard(text = "暂无分享链接")
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "共 ${shareList.size} 条",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { revokeAllShares() }, enabled = !sharesRevoking) {
                            Text(if (sharesRevoking) "撤销中…" else "全部撤销")
                        }
                    }
                    shareList.forEach { share ->
                        ShareItemCard(
                            share,
                            onRevoke = { revokeOne(share) },
                            enabled = !sharesRevoking
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)

            // ============ 设备管理 ============
            SectionHeader("设备管理", icon = null)
            if (devicesLoading) {
                LoadingCard(label = "加载设备列表…")
            } else if (devicesError != null) {
                ErrorCard(devicesError!!)
            } else {
                val deviceList = devices
                if (deviceList.isNullOrEmpty()) {
                    EmptyCard(text = "暂无已注册设备")
                } else {
                    deviceList.forEach { device -> DeviceItemCard(device) }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * 运维面板卡片 —— 渲染后端 [MediaService.getObservabilityDashboard] 返回的原始 JSON。
 *
 * 四个展示区，逐项按存在性取值，缺失字段静默跳过：
 * 1. `metrics_summary`：总请求 / 上传成功率等指标概览（键值对列表）。
 * 2. `top_users_by_storage`：按存储量排序的用户列表（表格行：用户名 + 存储量）。
 * 3. `recent_errors`：近期错误日志条目列表（每行一条文本）。
 * 4. `daily_active_trend`：日活趋势序列（日期 + 数值，逐行展示）。
 *
 * 字段口径随后端 `handleOpsObservabilityDashboard`；前端只做容错读取，
 * 单字段缺失或类型不符不影响其他区展示。
 */
@Composable
private fun ObservabilityDashboardCard(dashboard: JsonObject) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

            // ── 1. 指标概览 metrics_summary ──
            dashboard["metrics_summary"]?.jsonObject?.let { ms ->
                Text(
                    "指标概览",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                ms.forEach { (key, v) ->
                    val value = v.jsonPrimitive.contentOrNull
                        ?: v.jsonPrimitive.intOrNull?.toString()
                        ?: v.jsonPrimitive.longOrNull?.toString()
                        ?: v.toString()
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            key,
                            modifier = Modifier.weight(1f),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            value,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // ── 2. 用户存储排行 top_users_by_storage ──
            dashboard["top_users_by_storage"]?.jsonArray?.let { users ->
                if (users.isNotEmpty()) {
                    Text(
                        "用户存储排行",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    users.forEach { el ->
                        val o = el.jsonObject
                        val name = o["username"]?.jsonPrimitive?.contentOrNull
                            ?: o["user"]?.jsonPrimitive?.contentOrNull
                            ?: o["user_id"]?.jsonPrimitive?.contentOrNull
                            ?: "-"
                        val storage = o["storage_bytes"]?.jsonPrimitive?.longOrNull
                            ?: o["storage"]?.jsonPrimitive?.longOrNull
                            ?: o["total_size"]?.jsonPrimitive?.longOrNull
                            ?: 0L
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                name,
                                modifier = Modifier.weight(1f),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                formatBytes(storage),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // ── 3. 近期错误 recent_errors ──
            dashboard["recent_errors"]?.jsonArray?.let { errors ->
                if (errors.isNotEmpty()) {
                    Text(
                        "近期错误",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    errors.take(20).forEach { el ->
                        val msg = if (el is JsonObject) {
                            el["message"]?.jsonPrimitive?.contentOrNull
                                ?: el["error"]?.jsonPrimitive?.contentOrNull
                                ?: el["msg"]?.jsonPrimitive?.contentOrNull
                                ?: el.toString()
                        } else {
                            el.jsonPrimitive.contentOrNull ?: el.toString()
                        }
                        Text(
                            "• $msg",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // ── 4. 日活趋势 daily_active_trend ──
            dashboard["daily_active_trend"]?.jsonArray?.let { trend ->
                if (trend.isNotEmpty()) {
                    Text(
                        "日活趋势",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    trend.take(14).forEach { el ->
                        val o = el.jsonObject
                        val date = o["date"]?.jsonPrimitive?.contentOrNull
                            ?: o["day"]?.jsonPrimitive?.contentOrNull
                            ?: "-"
                        val count = o["active_users"]?.jsonPrimitive?.intOrNull
                            ?: o["count"]?.jsonPrimitive?.intOrNull
                            ?: o["dau"]?.jsonPrimitive?.intOrNull
                            ?: 0
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                date,
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                count.toString(),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 把字节数格式化为人类可读串（KB/MB/GB），零值返回 "0 B"。 */
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIdx = 0
    while (value >= 1024.0 && unitIdx < units.lastIndex) {
        value /= 1024.0
        unitIdx++
    }
    // commonMain 无 String.format，手动格式化一位小数
    val intPart = value.toInt()
    val decPart = ((value - intPart) * 10).toInt()
    return "$intPart.$decPart ${units[unitIdx]}"
}

/** 把 epoch 毫秒格式化为 YYYY-MM-DD HH:MM（local，commonMain 不依赖 java.time）。 */
private fun msToDateTimeStr(ms: Long): String {
    if (ms <= 0) return "-"
    val totalSec = ms / 1000
    val days = totalSec / 86400
    val remSec = totalSec % 86400
    val hours = remSec / 3600
    val mins = (remSec % 3600) / 60
    // 从 1970-01-01 起算公历日期（不引入三方库，手工平年/闰年）
    var year = 1970
    var dayCount = days.toInt()
    while (true) {
        val daysInYear = if (isLeapYear(year)) 366 else 365
        if (dayCount < daysInYear) break
        dayCount -= daysInYear
        year++
    }
    val monthDays = if (isLeapYear(year)) intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    else intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 0
    while (dayCount >= monthDays[month]) {
        dayCount -= monthDays[month]
        month++
    }
    val day = dayCount + 1
    // 两位补零（commonMain 无 String.format）
    fun two(n: Int) = if (n < 10) "0$n" else n.toString()
    return "$year-${two(month + 1)}-${two(day)} ${two(hours.toInt())}:${two(mins.toInt())}"
}

private fun isLeapYear(y: Int): Boolean =
    (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)

/**
 * 加载态卡片 —— 居中 [CircularProgressIndicator] + 描述文本，复用于分享/设备区。
 */
@Composable
private fun LoadingCard(label: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.height(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 错误态卡片 —— errorContainer 底色展示错误文案。
 */
@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 13.sp
        )
    }
}

/**
 * 空态卡片 —— surfaceVariant 底色展示空提示文本。
 */
@Composable
private fun EmptyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

/**
 * 单条分享链接卡片。
 *
 * 展示：分享 URL（截断显示）/ created_at / expires_at / 是否密码保护。
 * 「撤销」按钮调 [onRevoke]；整个区段撤销中时按钮置灰（调用方控制）。
 *
 * 说明：MediaService.ShareInfo 仅有 token/url/expiresAt/hasPassword/createdAt，
 * 无文件名字段，故不展示文件名（任务要求字段缺失时不展示）。
 *
 * @param share 分享链接信息
 * @param onRevoke 撤销回调
 * @param enabled 撤销按钮是否可用（批量撤销中置 false 禁用单条按钮）
 */
@Composable
private fun ShareItemCard(
    share: MediaService.ShareInfo,
    onRevoke: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 分享 URL
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "URL",
                    modifier = Modifier.width(56.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    share.url.ifEmpty { share.token },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
            // 创建时间
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "创建",
                    modifier = Modifier.width(56.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    share.createdAt.ifEmpty { "-" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // 过期时间
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "过期",
                    modifier = Modifier.width(56.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    share.expiresAt.ifEmpty { "永久" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // 密码保护标记
            if (share.hasPassword) {
                Text(
                    "🔒 密码保护",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onRevoke, enabled = enabled) {
                    Text("撤销")
                }
            }
        }
    }
}

/**
 * 单条设备信息卡片（只读展示）。
 *
 * 展示：设备名 / 设备 ID / 平台 / 注册时间（created_at_ms → 日期）。
 * 设备接口无「最后活跃时间」字段，展示为「-」。
 *
 * @param device 设备信息
 */
@Composable
private fun DeviceItemCard(device: MediaService.DeviceInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // 设备名
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "名称",
                    modifier = Modifier.width(56.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    device.deviceName.ifEmpty { "-" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }
            // 设备 ID
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "ID",
                    modifier = Modifier.width(56.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    device.deviceId,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // 平台
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "平台",
                    modifier = Modifier.width(56.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    device.platform.ifEmpty { "-" },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // 注册时间（created_at_ms → 可读日期）
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "注册",
                    modifier = Modifier.width(56.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    msToDateTimeStr(device.createdAtMs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            // 最后活跃时间：接口无此字段，展示占位
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "活跃",
                    modifier = Modifier.width(56.dp),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "-",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
