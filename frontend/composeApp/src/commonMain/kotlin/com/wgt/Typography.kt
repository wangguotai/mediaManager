package com.wgt

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 应用排版规范 —— Material3 Expressive 定制。
 *
 * 现状背景：[ColorSchemes] / [com.wgt.media.Dimens] / [com.wgt.media.AppShapes] 已搭好
 * 色彩与几何骨架，但 Typography 一直沿用 M3 默认（紫色 era 的默认字号/字重，
 * 偏粗、letterSpacing 偏窄）。本文件增量补齐：基于 M3 [Typography] 重新定义全套
 * 15 个TextStyle，统一字重梯度与行高，配合既有色板呈现「克制精致」观感。
 *
 * 设计口径（对标百度网盘 / 小米相册的克制感，避免 iOS 上过粗）：
 * - display / headline 用 weight 600–700（大标题需要存在感，但不顶满）；
 * - title 用 weight 600（卡片标题清晰可辨）；
 * - body 用 weight 400（正文轻盈，长读不累）；
 * - label 用 weight 500 + 略宽 letterSpacing（按钮/徽章需要「标签感」）。
 *
 * 平台约束（KMP commonMain）：
 * - 禁用 [java.awt.Font] 等 JVM-only 类，统一用 [FontFamily.Default]
 *   （各平台映射到系统 sans-serif：Android=Roboto/Noto，iOS=SF Pro），
 *   配 [FontWeight] + [TextStyle] 描述字重与样式。
 * - 不引入自定义字体文件（避免 iOS bundle 资源装配复杂度），纯系统字体 + 字重梯度。
 *
 * 使用方式：[com.wgt.media.AppTheme] 会把 [AppTypography] 注入
 * [androidx.compose.material3.MaterialTheme.typography]，所有 Screen 内
 * `MaterialTheme.typography.xxx` 自动生效，向后兼容现有调用。
 */
val AppTypography: Typography = Typography(
    // ── Display：大标题（回忆页/年度报告首屏hero）──
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),

    // ── Headline：页面标题（TopAppBar / 分区大标题）──
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // ── Title：卡片标题 / 列表项主标题 / 设置项标题 ──
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // ── Body：正文（媒体名 / 描述 / 列表副标题）──
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ── Label：按钮 / 徽章 / 标签（weight 500 + 略宽 letterSpacing 营造「标签感」）──
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)
