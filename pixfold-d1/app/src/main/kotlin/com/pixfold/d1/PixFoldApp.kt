package com.pixfold.d1

import androidx.compose.runtime.Composable
import com.pixfold.d1.ui.HomeScreen
import com.pixfold.d1.ui.theme.PixFoldTheme

@Composable
fun PixFoldApp() {
    PixFoldTheme {
        // P2–P6 会在此加入路由与各步骤页面;P1 只有首页。
        HomeScreen(onOpenWorkflowA = {}, onOpenWorkflowB = {})
    }
}
