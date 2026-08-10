package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.feature.media.MediaService
import com.wgt.feature.media.MediaService.ShareInfo
import com.wgt.platform.logger.logger
import kotlinx.coroutines.launch
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import mediamanager.composeapp.generated.resources.ic_copy
import mediamanager.composeapp.generated.resources.ic_delete
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

private const val TAG = "ShareManagementScreen"

/**
 * 分享管理页（PRD-v10 §1.2）。
 *
 * 展示当前用户创建的分享链接列表，支持：
 * - 复制链接到剪贴板（[LocalClipboardManager]）。
 * - 撤销分享（DELETE /api/share/{token}，走 [MediaService.deleteShare]）。
 *
 * 数据源：[MediaService.listShares]（GET /api/share/list），返回 [ShareInfo] 列表或
 * null（失败/null → 加载失败态）。列表为空展示空态。撤销成功后本地从列表移除该项，
 * 避免整页重拉；失败弹 Snackbar 并刷新一次以与服务端对齐。
 *
 * 状态机与 [TrashScreen] 同构：loading（CircularProgressIndicator）→ 空态（emoji +
 * titleMedium + bodySmall，沿用项目既有空态视觉口径，见 TrashScreen/EmptyStateView）
 * → 列表（LazyColumn + ElevatedCard 行）。
 *
 * 空态说明：项目未引入 material-icons 依赖（全仓零 `Icons.*` 引用），L0
 * `EmptyState(icon: ImageVector)` 需 ImageVector 而此处更宜用 emoji 主视觉，
 * 故沿用 TrashScreen 的 emoji 空态写法（视觉规范与 EmptyState 一致）。
 *
 * @param onBack 返回设置枢纽页
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun ShareManagementScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    // null = 加载中/加载失败哨兵；非 null（含 emptyList）= 已就绪。
    var shares by remember { mutableStateOf<List<ShareInfo>?>(null) }
    var loading by remember { mutableStateOf(true) }
    // 正在撤销的 token（用于行内禁用 + 转圈）。同一时刻只允许撤销一个。
    var revokingToken by remember { mutableStateOf<String?>(null) }
    // 撤销确认对话框的目标 token（null=不显示）。DELETE 不可逆，二次确认防误触。
    var confirmRevokeToken by remember { mutableStateOf<String?>(null) }

    // 首次进入拉取分享列表。
    LaunchedEffect(Unit) {
        loading = true
        val result = MediaService.listShares()
        shares = result
        loading = false
        if (result == null) logger.error(TAG, "listShares returned null")
    }

    /** 全量刷新（撤销失败兜底对齐服务端）。 */
    fun refresh() {
        scope.launch {
            val result = MediaService.listShares()
            shares = result
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "分享管理",
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                // 加载中
                loading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center)
                )
                // 加载失败（listShares 返回 null）
                shares == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("⚠️", fontSize = 56.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "加载失败",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = {
                        loading = true
                        scope.launch {
                            val r = MediaService.listShares()
                            shares = r
                            loading = false
                        }
                    }) { Text("重试") }
                }
                // 空态：暂无分享
                shares!!.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🔗", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "暂无分享",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "创建分享后会在这里管理",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                // 列表
                else -> {
                    val list = shares!!
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 顶部计数
                        item {
                            Text(
                                "共 ${list.size} 条分享",
                                modifier = Modifier.padding(bottom = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        items(list, key = { it.token }) { share ->
                            ShareRow(
                                share = share,
                                isRevoking = revokingToken == share.token,
                                onCopy = {
                                    clipboard.setText(AnnotatedString(share.url))
                                    scope.launch { snackbarHostState.showSnackbar("已复制链接") }
                                },
                                onRevoke = { confirmRevokeToken = share.token }
                            )
                        }
                    }
                }
            }
        }
    }

    // 撤销确认对话框
    confirmRevokeToken?.let { token ->
        AlertDialog(
            onDismissRequest = { confirmRevokeToken = null },
            title = { Text("撤销分享") },
            text = {
                Text(
                    "撤销后链接立即失效，无法恢复。确认撤销分享「" +
                        token.take(8) + "」？"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRevokeToken = null
                    revokingToken = token
                    scope.launch {
                        val ok = MediaService.deleteShare(token)
                        revokingToken = null
                        if (ok) {
                            shares = shares?.filter { it.token != token }
                            snackbarHostState.showSnackbar("已撤销分享")
                        } else {
                            logger.error(TAG, "deleteShare failed for token=${token.take(8)}")
                            snackbarHostState.showSnackbar("撤销失败，请重试")
                            refresh()
                        }
                    }
                }) { Text("撤销", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRevokeToken = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 单条分享行：token 缩写（前 8 位）+ 密码标记 + 创建时间 + 有效期，尾部复制/撤销操作。
 *
 * @param share 分享信息
 * @param isRevoking 该行是否正在撤销（禁用撤销按钮 + 转圈）
 * @param onCopy 复制链接回调
 * @param onRevoke 撤销回调（仅触发确认对话框，实际删除在 [ShareManagementScreen] 内）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
private fun ShareRow(
    share: ShareInfo,
    isRevoking: Boolean,
    onCopy: () -> Unit,
    onRevoke: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // 行1：token 缩写 + 密码标记
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        share.token.take(8),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (share.hasPassword) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("🔒", fontSize = 14.sp)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // 行2：创建时间
                Text(
                    "创建：${share.createdAt.ifEmpty { "未知" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 行3：有效期（后端 expires_at 为空串时按「永久」展示）
                Text(
                    "有效期：${share.expiresAt.ifBlank { "永久" }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 复制链接
            IconButton(onClick = onCopy) {
                Icon(
                    painter = painterResource(Res.drawable.ic_copy),
                    contentDescription = "复制链接",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // 撤销
            IconButton(onClick = onRevoke, enabled = !isRevoking) {
                if (isRevoking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        painter = painterResource(Res.drawable.ic_delete),
                        contentDescription = "撤销分享",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    }
}
