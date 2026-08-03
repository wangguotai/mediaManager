package com.wgt.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
import com.wgt.media.ui.SectionHeader
import com.wgt.media.ui.SettingsRow
import kotlinx.coroutines.launch
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_close
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * 外观（主题）设置页（L1 任务 C：从 SettingsScreen 拆出）。
 *
 * 承接原 SettingsScreen 的「主题」区：SYSTEM / LIGHT / DARK / AMOLED 四选一单选。
 * 点选即落地（[SettingsState.saveThemeMode]），即时驱动 [App] 主题色板切换，无需保存按钮。
 *
 * 用 L0 [SettingsRow] + 尾部 [RadioButton] 重构，替代原内联 `Row + RadioButton + Text`。
 *
 * @param onBack 返回设置枢纽页
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalResourceApi::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "外观",
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
            SectionHeader("主题", icon = null)

            ThemeMode.values().forEach { mode ->
                SettingsRow(
                    title = modeLabel(mode),
                    trailing = {
                        RadioButton(
                            selected = SettingsState.themeMode == mode,
                            onClick = {
                                SettingsState.saveThemeMode(mode)
                                scope.launch {
                                    snackbarHostState.showSnackbar("主题：${modeLabel(mode)}")
                                }
                            }
                        )
                    },
                    onClick = {
                        SettingsState.saveThemeMode(mode)
                        scope.launch {
                            snackbarHostState.showSnackbar("主题：${modeLabel(mode)}")
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

private fun modeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "暗色"
    ThemeMode.AMOLED -> "AMOLED 纯黑"
}
