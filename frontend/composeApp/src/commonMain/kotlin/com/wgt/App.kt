package com.wgt

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.wgt.media.MediaListScreen
import com.wgt.media.MediaViewModel

private val viewModel = MediaViewModel()
@Composable
@Preview
fun App() {
    MaterialTheme {
        MediaListScreen(viewModel = viewModel)
    }
}
