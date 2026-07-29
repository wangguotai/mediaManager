package com.wgt.media

import androidx.compose.runtime.Composable

// iOS has no hardware back button — this is a no-op.
@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No-op on iOS
}
