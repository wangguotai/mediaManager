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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.common.util.formatBytesToMB
import com.wgt.media.ui.CardScaffold
import com.wgt.media.ui.SectionHeader
import com.wgt.media.ui.SwitchRow
import kotlinx.coroutines.launch
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_cloud_upload
import mediamanager.composeapp.generated.resources.ic_close
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 云相册备份设置页（L1 任务 C：从 SettingsScreen 拆出）。
 *
 * 承接原 SettingsScreen 的「云相册」区：自动备份开关 / 仅 WiFi / 仅充电 /
 * 设备登记 / 云端用量 / 待备份 / 上次备份时间。
 *
 * 与 [SettingsScreen] 的区别：本页只负责云备份相关展示与开关，不混合后端地址/账号/主题
 * 等无关项，便于后续独立演进（如备份策略高级选项、按相册过滤备份等）。
 *
 * 底层状态读写仍走 [SettingsState]（进程内单例），保证与备份轮询编排
 * ([MediaViewModel.startAutoBackup]/[stopAutoBackup]) 的口径一致。
 *
 * @param onBack 返回设置枢纽页
 * @param viewModel 用于读取云端用量 / 上传队列，并启停后台备份轮询
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: MediaViewModel
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "云相册备份",
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
            SectionHeader("云相册", icon = null)

            // 自动备份开关 —— 整行可点切换，开/关即启停后台备份轮询。
            SwitchRow(
                title = "自动备份新增图片",
                subtitle = "本地新增图片后台增量上传到云端（自动去重）",
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
                }
            )

            // V6 §2.1：备份策略开关——仅在自动备份开启时展示。
            if (SettingsState.autoBackupEnabled) {
                SwitchRow(
                    title = "仅 WiFi 备份",
                    subtitle = "移动数据下暂停备份，避免消耗流量",
                    checked = SettingsState.backupWifiOnly,
                    onCheckedChange = { SettingsState.saveBackupWifiOnly(it) }
                )
                SwitchRow(
                    title = "仅充电备份",
                    subtitle = "电池供电时暂停备份，省电",
                    checked = SettingsState.backupChargingOnly,
                    onCheckedChange = { SettingsState.saveBackupChargingOnly(it) }
                )

                // 设备登记状态：供用户确认本机已被云同步纳入。
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
                CardScaffold(title = "备份状态", content = {
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
                })
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 上次备份时间格式化（自 SettingsScreen 迁入，保持纯整数实现，无 java.time 依赖）
// ─────────────────────────────────────────────────────────────────────────────

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
