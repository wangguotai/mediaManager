package com.wgt.media

import androidx.compose.foundation.clickable
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
import com.wgt.media.ui.CardScaffold
import com.wgt.media.ui.SectionHeader
import com.wgt.media.ui.SettingsRow
import kotlinx.coroutines.launch
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_delete
import mediamanager.composeapp.generated.resources.ic_info
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** 应用版本号 —— 与"关于"区展示一致。暂不接入 Gradle versionName 以避免跨模块依赖。 */
private const val APP_VERSION = "v0.4.0 (V7+V8)"

/** 构建时间戳 —— 手动维护，发布时更新。 */
private const val BUILD_TIME = "2026-07-31"

/**
 * 关于页（L1 任务 C：从 SettingsScreen 拆出）。
 *
 * 承接原 SettingsScreen 的「关于」区及相邻信息/维护入口：
 * - 版本 / 构建时间 / 后端版本 / 服务器磁盘 / 媒体同步状态
 * - 检查 RN 热更新
 * - 清除缩略图缓存
 * - 数据看板入口（→ onNavigateToInsights；仪表盘细节已在 L0 抽到 InsightsDashboardScreen）
 *
 * 用 L0 [CardScaffold] 包裹信息行、[SettingsRow] 做可点击入口，替代原内联 `Row + Text`。
 *
 * @param onBack 返回设置枢纽页
 * @param viewModel 备用（当前信息行直接走 [MediaService] 静态方法）
 * @param onNavigateToInsights 跳转数据看板
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: MediaViewModel,
    onNavigateToInsights: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // V7：后端版本（从 /api/healthz 获取）
    var backendVersion by remember { mutableStateOf("加载中...") }
    LaunchedEffect(Unit) {
        backendVersion = MediaService.getBackendInfo() ?: "未知"
    }
    // V8：服务器磁盘使用
    var diskUsage by remember { mutableStateOf<MediaService.DiskUsage?>(null) }
    LaunchedEffect(Unit) { diskUsage = MediaService.getDiskUsage() }
    // V8：同步状态
    var syncStatus by remember { mutableStateOf<MediaService.SyncStatus?>(null) }
    LaunchedEffect(Unit) { syncStatus = MediaService.getSyncStatus() }
    // V7：检查 RN 热更新
    var updateStatus by remember { mutableStateOf("") }
    var checkingUpdate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "关于",
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
            SectionHeader("版本信息", icon = null)

            CardScaffold(content = {
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
            })

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader("更新与缓存", icon = null)

            // V7：检查 RN 热更新
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

            // 数据看板入口（V33：所有洞察卡片已抽到 InsightsDashboardScreen，此处仅留入口）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onNavigateToInsights() }
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("数据看板", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "仪表盘 · 健康度 · 智能洞察 · 存储分析 · 年度报告",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                Icon(
                    painter = painterResource(Res.drawable.ic_info),
                    contentDescription = "查看数据看板",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
