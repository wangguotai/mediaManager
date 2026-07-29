package com.wgt.media

import androidx.compose.runtime.Composable

/**
 * Platform back handler. On Android, delegates to [androidx.activity.compose.BackHandler].
 * On iOS, this is a no-op (iOS doesn't have a hardware back button).
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
