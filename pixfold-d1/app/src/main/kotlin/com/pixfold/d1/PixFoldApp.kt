package com.pixfold.d1

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.pixfold.d1.ui.HomeScreen
import com.pixfold.d1.ui.theme.PixFoldTheme

@Composable
fun PixFoldApp() {
    PixFoldTheme {
        // Surface 提供 M3 语义背景色 —— 深色模式下不会透出窗口白底(真机实证修复)。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // P2–P6 会在此加入路由与各步骤页面;P1 只有首页。
            // 系统栏 inset 由各页面自行消费(首页用 safeDrawingPadding),
            // 避免内容绘制到状态栏/挖孔之下(真机实证:标题曾被状态栏压住)。
            HomeScreen(onOpenWorkflowA = {}, onOpenWorkflowB = {})
        }
    }
}
