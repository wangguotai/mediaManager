package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 媒体工具页（L1 任务 C：从 SettingsScreen 拆出）。
 *
 * 承接原 SettingsScreen 的「媒体维护」相关离散入口与工具操作：
 * - 回收站入口（→ onNavigateToTrash）
 * - 孤立文件检查 + 一键清理
 * - 自动打标签
 * - 存储清理入口（→ onNavigateToCleanup）
 * - RN 活动中心入口（→ onNavigateToRnActivity）
 *
 * 用 L0 [SettingsRow] 做入口行、[CardScaffold] 包裹带状态的检查结果区，替代原内联 `Row + TextButton`。
 *
 * @param onBack 返回设置枢纽页
 * @param viewModel 备用（保留口径，当前工具操作直接走 [MediaService] 静态方法）
 * @param onNavigateToTrash 跳转回收站页
 * @param onNavigateToCleanup 跳转存储清理页
 * @param onNavigateToRnActivity 跳转 RN 活动中心页
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun MediaToolsScreen(
    onBack: () -> Unit,
    viewModel: MediaViewModel,
    onNavigateToTrash: () -> Unit = {},
    onNavigateToCleanup: () -> Unit = {},
    onNavigateToRnActivity: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 孤立文件检查状态
    var orphanResult by remember { mutableStateOf<MediaService.OrphanCheckResult?>(null) }
    var orphanChecking by remember { mutableStateOf(false) }
    // 自动打标签状态
    var autoTagResult by remember { mutableStateOf<String?>(null) }
    var autoTagging by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "媒体工具",
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
            SectionHeader("入口", icon = null)

            // 回收站入口（V7 §1.1）
            SettingsRow(
                title = "回收站",
                subtitle = "查看已删除文件",
                onClick = onNavigateToTrash
            )

            // 存储清理入口（V7 §2.4）
            SettingsRow(
                title = "存储清理",
                subtitle = "查看清理建议",
                onClick = onNavigateToCleanup
            )

            // RN 活动中心入口（V7 §3.1）
            SettingsRow(
                title = "活动中心",
                subtitle = "打开 React Native 模块",
                onClick = onNavigateToRnActivity
            )

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(16.dp))

            SectionHeader("维护工具", icon = null)

            // V8：孤立文件检查
            CardScaffold(title = "孤立文件检查", content = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("检查数据库外的孤立媒体文件", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        orphanChecking = true
                        scope.launch {
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
                                scope.launch {
                                    val result = MediaService.cleanupOrphan()
                                    if (result != null) {
                                        orphanResult = MediaService.orphanCheck()
                                    }
                                }
                            }) { Text("一键清理", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            })

            // V8：自动打标签
            CardScaffold(title = "自动打标签", content = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("为未打标签的媒体自动生成标签", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        autoTagging = true
                        scope.launch {
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
                    Text(
                        msg,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(start = 16.dp, bottom = 4.dp)
                    )
                }
            })

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
