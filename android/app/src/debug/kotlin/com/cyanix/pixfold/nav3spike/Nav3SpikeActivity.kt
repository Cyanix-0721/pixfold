package com.cyanix.pixfold.nav3spike

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

/**
 * Nav3 受限 spike 的 6 个占位目的地(根导航规模:6 个目的地)。
 * 与现 PixFoldRoutes 一一对应,但**不接业务**,只验导航/返回/转场能力。
 */
@Serializable data object SpikeHome : NavKey
@Serializable data object SpikeSettings : NavKey
@Serializable data object SpikeLicenses : NavKey
@Serializable data object SpikeOrganize : NavKey
@Serializable data object SpikeLibrary : NavKey
@Serializable data object SpikePreview : NavKey

/** 与现版 PixFoldNav.PREDICTIVE_MIN_SCALE 一致(0.90)。 */
private const val SPIKE_MIN_SCALE = 0.90f

/**
 * 阶段三 spike 专用 Activity(仅 debug 源集,不进 release)。
 *
 * 目的:在真机上验证 Navigation 3 1.2.1 能否承载根导航的 6 个目的地,
 * 并复刻现版两档预测性返回转场(Step=纯淡出 / Page=scaleOut(0.90)+fadeOut)。
 */
class Nav3SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Nav3SpikeApp()
            }
        }
    }
}

@Composable
private fun Nav3SpikeApp() {
    var pageStyle by rememberSaveable { mutableStateOf(true) }
    var predictive by rememberSaveable { mutableStateOf(true) }
    val backStack: NavBackStack<NavKey> = rememberNavBackStack(SpikeHome)

    val exit: ExitTransition =
        if (pageStyle) scaleOut(targetScale = SPIKE_MIN_SCALE) + fadeOut() else fadeOut()

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        // 前进保持瞬切(与现版一致,用户 2026-09-22 裁决「只做返回/手势」)。
        transitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) },
        // 普通返回:目标页静止,上层页按档位退出。
        popTransitionSpec = { ContentTransform(EnterTransition.None, exit) },
        // 预测性返回手势:开关关掉时上层页纹丝不动(与现版 (b) 案一致)。
        predictivePopTransitionSpec = { _ ->
            ContentTransform(EnterTransition.None, if (predictive) exit else ExitTransition.None)
        },
        modifier = Modifier.fillMaxSize(),
        entryProvider = entryProvider {
        entry<SpikeHome> { SpikeScreen("A · Home", backStack, pageStyle, predictive, { pageStyle = it }, { predictive = it }) }
        entry<SpikeSettings> { SpikeScreen("B · Settings", backStack, pageStyle, predictive, { pageStyle = it }, { predictive = it }) }
        entry<SpikeLicenses> { SpikeScreen("C · Licenses", backStack, pageStyle, predictive, { pageStyle = it }, { predictive = it }) }
        entry<SpikeOrganize> { SpikeScreen("D · Organize", backStack, pageStyle, predictive, { pageStyle = it }, { predictive = it }) }
        entry<SpikeLibrary> { SpikeScreen("E · Library", backStack, pageStyle, predictive, { pageStyle = it }, { predictive = it }) }
        entry<SpikePreview> { SpikeScreen("F · Preview", backStack, pageStyle, predictive, { pageStyle = it }, { predictive = it }) }
        },
    )
}

@Composable
private fun SpikeScreen(
    label: String,
    backStack: NavBackStack<NavKey>,
    pageStyle: Boolean,
    predictive: Boolean,
    onPageStyle: (Boolean) -> Unit,
    onPredictive: (Boolean) -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = label, style = MaterialTheme.typography.headlineMedium)
            Text(text = "栈深 = " + backStack.size, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "档位 = " + (if (pageStyle) "Page(scale 0.90)" else "Step(纯淡出)") +
                    " / 预测性返回 = " + (if (predictive) "开" else "关"),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onPageStyle(!pageStyle) }) { Text(if (pageStyle) "切到 Step" else "切到 Page") }
                Button(onClick = { onPredictive(!predictive) }) { Text(if (predictive) "关手势跟随" else "开手势跟随") }
            }
            Text(text = "push:", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { backStack.add(SpikeSettings) }) { Text("B") }
                Button(onClick = { backStack.add(SpikeLicenses) }) { Text("C") }
                Button(onClick = { backStack.add(SpikeOrganize) }) { Text("D") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { backStack.add(SpikeLibrary) }) { Text("E") }
                Button(onClick = { backStack.add(SpikePreview) }) { Text("F") }
                Button(onClick = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }) { Text("返回") }
            }
        }
    }
}
