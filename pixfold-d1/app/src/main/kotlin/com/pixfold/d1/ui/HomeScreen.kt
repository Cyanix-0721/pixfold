package com.pixfold.d1.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 首页:两条工作流入口(验收第 19 项"从首页完整走通")。
 *
 * targetSdk 36 起系统强制 edge-to-edge,故本页自行消费系统栏 inset
 * ——否则顶部标题会被状态栏/挖孔遮挡(真机实证发现)。
 *
 * @param contentInsets 系统栏 inset 来源;默认 [WindowInsets.safeDrawing]。
 *   可注入是为了**可测**:Robolectric 下系统 inset 恒为 0,若不注入就无法断言
 *   "内容确实让开了系统栏"(否则测试会因 16dp 内边距而假通过)。
 */
@Composable
fun HomeScreen(
    onOpenWorkflowA: () -> Unit,
    onOpenWorkflowB: () -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "PixFold",
            modifier = Modifier.testTag(TAG_TITLE),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        WorkflowEntry("工作流 A · 图片整理与命名", onOpenWorkflowA)
        WorkflowEntry("工作流 B · CBZ 制作", onOpenWorkflowB)
    }
}

const val TAG_TITLE = "home-title"

@Composable
private fun WorkflowEntry(title: String, onClick: () -> Unit) {
    // M3 语义角色取色(不硬编码);整卡可点且高度远超 48dp 触控下限(HANGOFF §6.5)。
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = title },
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
