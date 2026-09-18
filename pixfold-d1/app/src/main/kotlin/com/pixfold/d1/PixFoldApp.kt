package com.pixfold.d1

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.ui.HomeScreen
import com.pixfold.d1.ui.preview.ImagePreview
import com.pixfold.d1.ui.theme.PixFoldTheme
import com.pixfold.d1.ui.workflowa.WorkflowASteps

/**
 * 应用根。用**状态驱动的轻量导航**(密封类 + `mutableStateOf`),不引入 Navigation 组件
 * ——保持"零三方依赖"与最小实现面;页面层级浅(首页 → 工作流 → 预览),无需路由库。
 *
 * D1 不连真实文件系统,工作流 A 的数据取自确定性 mock(§9 D1)。
 */
sealed interface Screen {
    data object Home : Screen
    data object WorkflowA : Screen
    data object WorkflowB : Screen
    data class Preview(val index: Int) : Screen
}

@Composable
fun PixFoldApp() {
    PixFoldTheme {
        // Surface 提供 M3 语义背景色 —— 深色模式下不会透出窗口白底(真机实证修复)。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            var screen by remember { mutableStateOf<Screen>(Screen.Home) }

            // 工作流 A 演示数据:trip(30 张)+ scan(16 张)+ misc(12 张),
            // 多集合用于演示"批次默认 + 本组独立"(验收第 7 项)。
            val collections = remember { MockData.workspaceA.collections }
            // 预览跟随**当前集合**:切集合后打开预览应看到该集合的图,
            // 否则页码与图片会对不上(索引来自当前集合)。
            var activeCollectionId by remember { mutableStateOf(collections.first().id) }
            val previewItems = collections.firstOrNull { it.id == activeCollectionId }?.images
                ?: collections.first().images

            when (val current = screen) {
                Screen.Home -> HomeScreen(
                    onOpenWorkflowA = { screen = Screen.WorkflowA },
                    onOpenWorkflowB = { screen = Screen.WorkflowB },
                )

                Screen.WorkflowA -> WorkflowASteps(
                    collections = collections,
                    onOpenPreview = { index -> screen = Screen.Preview(index) },
                    onActiveCollectionChange = { activeCollectionId = it },
                )

                is Screen.Preview -> ImagePreview(
                    items = previewItems,
                    initialIndex = current.index,
                    onClose = { screen = Screen.WorkflowA },
                )

                Screen.WorkflowB -> PlaceholderPage("工作流 B · 待实现（P5）")
            }
        }
    }
}

@Composable
private fun PlaceholderPage(label: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onBackground)
    }
}
