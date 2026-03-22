package com.wgt.rn

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.painterResource
import mediamanager.composeapp.generated.resources.Res
import mediamanager.composeapp.generated.resources.ic_refresh

/**
 * Android 平台实现：显示 RN 测试按钮
 */
@OptIn(ExperimentalResourceApi::class)
@Composable
actual fun RNTestButton() {
    val context = LocalContext.current
    
    Spacer(modifier = Modifier.width(8.dp))
    
    IconButton(
        onClick = { RNLauncher.launchTestPage(context) }
    ) {
        Icon(
            painter = painterResource(Res.drawable.ic_refresh),
            contentDescription = "启动 RN 测试",
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
