package com.pixfold.d1.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 预测性返回容器(用户 2026-09-20 要求做成可切换开关)。
 *
 * **为什么放在 components 而非 PixFoldApp 内**:2026-09-21 起它不再只服务根级导航 ——
 * 两个工作流步骤容器(WorkflowASteps / WorkflowBSteps)也要用它,才能让**步骤级**
 * 返回同样跟随手势进度。三处共用同一实现,避免动画参数各写一份而漂移。
 *
 * ## 为什么"开关"是可以做的(修正我此前的判断)
 *
 * `android:enableOnBackInvokedCallback` 是 **manifest 静态属性**,确实**不能**运行时改
 * (已反编译 `android-36/android.jar` 确认 `OnBackInvokedDispatcher` 无 enable/disable API)。
 *
 * 但 androidx 提供了 [PredictiveBackHandler]:它让应用**订阅返回手势的进度流**,
 * 于是"是否跟随手势做退场动画"成为**应用层的运行时选择**。本容器控制的正是后者。
 *
 * - [enabled] = true:`PredictiveBackHandler` 收进度 -> 内容随进度缩放/淡出;
 *   手势完成时执行 [onBack];手势取消时复位(不导航)。
 * - [enabled] = false:退回普通 `BackHandler` —— 立刻执行 [onBack],无跟随动画。
 *
 * ## 优先级(为什么根级与步骤级可以并存)
 *
 * 根级容器注册在**较外层**,步骤容器注册在**更内层**。`OnBackInvokedDispatcher`
 * 取**最后注册者**,故工作流内部的步骤级返回优先;只有步骤容器不处理时
 * (如已在第 1 步 —— 此时它把返回交给自己的 `onBack`)才落到根级容器。
 *
 * **此处曾有一个真实缺陷**(2026-09-21 修复,见 HANGOFF §9.1 第 2 步):
 * 步骤容器此前用的是普通 `BackHandler`,而根级用的是 `PredictiveBackHandler`。
 * 前者优先级更高,于是**工作流内部的手势跟随动画完全不生效** —— 在步骤 2 拖到一半,
 * 内容纹丝不动,松手才突然跳回步骤 1。修复方式就是让步骤容器也用本容器。
 *
 * @param enabled 是否使用预测性返回动画(来自 `settings.predictiveBackAnimation`)。
 * @param onBack 应用内返回去处;`null` 表示交回系统(据此执行"退出应用")。
 */
@Composable
fun PredictiveBackContainer(
    enabled: Boolean,
    onBack: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (onBack == null) {
        // 没有应用内返回去处,交回系统(系统据此执行"退出应用")。
        content()
        return
    }
    if (!enabled) {
        BackHandler(onBack = onBack)
        content()
        return
    }

    var progress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { event -> progress = event.progress }
            // 手势完成(未被取消)才真正导航。
            onBack()
        } finally {
            // 取消或完成后都复位,避免残留缩放。
            progress = 0f
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress
                val scale = 1f - 0.10f * p
                scaleX = scale
                scaleY = scale
                alpha = 1f - 0.35f * p
            },
    ) { content() }
}
