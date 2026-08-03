package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wgt.media.ui.SectionHeader
import com.wgt.media.ui.SettingsRow
import kotlinx.coroutines.launch
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

    // OpenClaw 桥梁命令对话框
    if (showOpenClawDialog) {
        OpenClawCommandDialog(
            viewModel = openClawViewModel,
            onDismiss = { showOpenClawDialog = false }
        )
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
        }
    }
}
