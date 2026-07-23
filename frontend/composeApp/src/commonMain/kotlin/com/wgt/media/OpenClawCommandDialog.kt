package com.wgt.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import mediamanager.composeapp.generated.resources.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource

/**
 * OpenClaw 命令对话框。
 *
 * 顶部输入：path（OpenClaw gateway 路径，必须以 '/' 开头）+ message（命令文本，
 * 作为 upstream body 的 message 字段，经 [OpenClawBridge.sendCommand] 发送）。
 * 下方展示后端桥梁返回的结果：status / content_type / upstream / body(raw_body)。
 *
 * 响应结构见 plan/openclaw-bridge-design.md §3.3：后端统一 HTTP 200，上游状态码在
 * JSON `status` 字段。这里以 [OpenClawResult.ok] 区分"通信是否成功"，以
 * [OpenClawResult.isUpstreamSuccess] 区分"上游是否 2xx"。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun OpenClawCommandDialog(
    viewModel: OpenClawViewModel,
    onDismiss: () -> Unit
) {
    val result by remember { derivedStateOf { viewModel.result } }
    val isSending by remember { derivedStateOf { viewModel.isSending } }

    Dialog(
        onDismissRequest = {
            // 发送中不响应外部关闭，避免协程结果丢失感
            if (!isSending) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // 标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_openclaw),
                        contentDescription = "OpenClaw",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "OpenClaw 桥梁",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    if (!isSending) {
                        TextButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // path 输入
                Text(
                    "OpenClaw 路径",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                OutlinedTextField(
                    value = viewModel.path,
                    onValueChange = viewModel::onPathChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("/healthz") },
                    enabled = !isSending
                )
                // 快捷路径：实测 /healthz 是当前 OpenClaw Control gateway 上可用端点，便于连通性自测。
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = { if (!isSending) viewModel.onPathChange("/healthz") },
                        label = { Text("/healthz") },
                        enabled = !isSending
                    )
                    AssistChip(
                        onClick = {
                            if (!isSending) viewModel.onPathChange(OpenClawBridge.DEFAULT_COMMAND_PATH)
                        },
                        label = { Text(OpenClawBridge.DEFAULT_COMMAND_PATH) },
                        enabled = !isSending
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // message 输入
                Text(
                    "命令 / 消息（作为 body.message）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                OutlinedTextField(
                    value = viewModel.message,
                    onValueChange = viewModel::onMessageChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp, max = 140.dp),
                    placeholder = { Text("输入命令…") },
                    enabled = !isSending
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 发送按钮
                Button(
                    onClick = { viewModel.send() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSending
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("发送中…")
                    } else {
                        Text("发送到 OpenClaw")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 结果区
                val r = result
                if (r != null) {
                    ResultSection(r)
                } else if (!isSending) {
                    Text(
                        "尚未发送。填好 path 与命令后点“发送到 OpenClaw”。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

/**
 * 响应结果展示区：用等宽字体显示 body，便于阅读 JSON / 文本。
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
private fun ColumnScope.ResultSection(r: OpenClawResult) {
    val statusColor = when {
        !r.ok -> MaterialTheme.colorScheme.error
        r.isUpstreamSuccess -> Color(0xFF2E7D32) // green
        else -> Color(0xFFC77700) // orange：上游非 2xx（如 404）
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = statusColor.copy(alpha = 0.15f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.padding(end = 8.dp)
        ) {
            Text(
                if (r.ok) "HTTP ${r.status}" else "失败",
                color = statusColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        if (r.ok) {
            Text(
                if (r.isUpstreamSuccess) "上游成功" else "上游返回非 2xx",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }

    Spacer(modifier = Modifier.height(8.dp))

    r.error?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }

    // 元信息
    MetaRow("content_type", r.contentType)
    MetaRow("upstream", r.upstream)

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        "body",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    Spacer(modifier = Modifier.height(4.dp))
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(modifier = Modifier.padding(12.dp)) {
            if (r.body.isEmpty()) {
                Text(
                    "(空)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            } else {
                Text(
                    r.body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
private fun MetaRow(key: String, value: String) {
    if (value.isEmpty()) return
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(
            "$key: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontFamily = FontFamily.Monospace
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        )
    }
}
