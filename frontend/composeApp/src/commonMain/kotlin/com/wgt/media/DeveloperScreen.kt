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
