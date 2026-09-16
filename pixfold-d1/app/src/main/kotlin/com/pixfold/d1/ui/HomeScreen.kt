package com.pixfold.d1.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 首页:两条工作流入口(验收第 19 项"从首页完整走通")。 */
@Composable
fun HomeScreen(
    onOpenWorkflowA: () -> Unit,
    onOpenWorkflowB: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "PixFold", style = MaterialTheme.typography.headlineMedium)
        WorkflowEntry("工作流 A · 图片整理与命名", onOpenWorkflowA)
        WorkflowEntry("工作流 B · CBZ 制作", onOpenWorkflowB)
    }
}

@Composable
private fun WorkflowEntry(title: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            text = title,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
