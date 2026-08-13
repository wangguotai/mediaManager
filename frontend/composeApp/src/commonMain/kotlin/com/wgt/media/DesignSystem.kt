// 一刻相册设计令牌（来自 qwen 视觉识别真机一刻相册截图，见 docs/UI-REFERENCE.md）
// 统一 AiTabs 及后续页面的配色/字号/间距/圆角，避免"粗糙"的硬编码拼凑。
package com.wgt.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- 配色 ----------
val AppBackground = Color(0xFFF5F6FA)     // 页面背景浅灰
val CardWhite     = Color(0xFFFFFFFF)     // 卡片白
val Primary       = Color(0xFF2F80ED)     // 主强调蓝
val TextPrimary   = Color(0xFF1A1A1A)     // 文字主
val TextSecondary = Color(0xFF8C8C8C)     // 文字次

// ---------- 字号 ----------
val T_Banner = 28.sp // 大标题
val T_Title  = 24.sp // 页面/区块标题
val T_Heading= 17.sp // 二级标题/正文
val T_Body   = 15.sp // 正文
val T_Label  = 13.sp // 小字

// ---------- 间距 & 圆角 ----------
val PagePadding = 16.dp  // 页面水平边距
val SPCardPad   = 20.dp  // 卡片内边距
val SPSection   = 16.dp  // 模块间距
val SPGap       = 12.dp  // 元素间小间距
val RadiusCard  = 16.dp  // 卡片圆角
val RadiusPill  = 32.dp  // 按钮圆角Pill

// ---------- 可复用视图 ----------
// 浅灰页面底容器
@Composable
fun AppScaffold(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AppBackground, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = PagePadding),
            content = content
        )
    }
}

// 页面/区块标题
@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = T_Title,
        fontWeight = FontWeight.Bold,
        color = TextPrimary,
        modifier = Modifier.padding(top = SPSection, bottom = 10.dp)
    )
}