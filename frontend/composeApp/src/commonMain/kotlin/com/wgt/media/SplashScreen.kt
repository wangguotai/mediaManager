package com.wgt.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 启动屏：App 名称居中淡入展示，约 2 秒后淡出切到主界面。
 *
 * 由 [App] 持有 [showSplash] 状态驱动：[onFinish] 回调翻转状态，触发 [AnimatedVisibility]
 * 的 [fadeOut]，与主内容 [Crossfade] 衔接实现"启动 → 主界面"过渡。
 */
@Composable
fun SplashScreen(onFinish: () -> Unit) {
    var entered by remember { mutableStateOf(true) }
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(2000)
        visible = false
        // 等淡出动画播完再回调，避免主界面突入。
        delay(420)
        onFinish()
    }

    AnimatedVisibility(
        visible = visible && entered,
        enter = fadeIn(animationSpec = tween(500)) + scaleIn(initialScale = 0.85f),
        exit = fadeOut(animationSpec = tween(400))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "媒体管家",
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Media Manager",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
            }
        }
    }
}
