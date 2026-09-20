package com.pixfold.d1

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.data.SharedPrefsSettingsStore
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.domain.settings.SettingsStore
import com.pixfold.d1.ui.HomeScreen
import com.pixfold.d1.ui.settings.LicensesScreen
import com.pixfold.d1.ui.settings.SettingsScreen
import com.pixfold.d1.ui.preview.ImagePreview
import com.pixfold.d1.ui.theme.PixFoldTheme
import com.pixfold.d1.ui.workflowa.WorkflowASteps
import com.pixfold.d1.ui.workflowb.WorkflowBSteps

/**
 * 应用根。用**状态驱动的轻量导航**(密封类 + `mutableStateOf`),不引入 Navigation 组件
 * ——保持"零三方依赖"与最小实现面;页面层级浅(首页 → 工作流 → 预览),无需路由库。
 *
 * D1 不连真实文件系统,工作流 A 的数据取自确定性 mock(§9 D1)。
 */
sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Licenses : Screen
    data object WorkflowA : Screen
    data object WorkflowB : Screen
    data class Preview(val index: Int) : Screen
}

/**
 * 应用根。
 *
 * @param store 设置存储。默认用 [SharedPrefsSettingsStore];测试可注入内存实现,
 *   从而无需 Android 持久化环境即可断言"设置真的驱动了主题"。
 */
@Composable
fun PixFoldApp(store: SettingsStore? = null) {
    val context = LocalContext.current
    val settingsStore = store ?: remember { SharedPrefsSettingsStore(context) }
    // 设置在**应用根**持有:主题、语言、各页面都要读它,提上层是唯一合理位置。
    var settings by remember { mutableStateOf(settingsStore.load()) }
    val updateSettings: (PixFoldSettings) -> Unit = { next ->
        settings = next
        settingsStore.save(next)
    }

    PixFoldTheme(settings = settings) {
        // Surface 提供 M3 语义背景色 —— 深色模式下不会透出窗口白底(真机实证修复)。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            var screen by remember { mutableStateOf<Screen>(Screen.Home) }

            // 工作流 A 演示数据:trip(30 张)+ scan(14 张)+ misc(12 张),
            // 多集合用于演示"批次默认 + 本组独立"(验收第 7 项)。
            val collections = remember { MockData.workspaceA.collections }
            // 工作流 B 演示数据:5 卷覆盖缺卷号 / 无语言线索 / 输出冲突三类异常
            // (验收第 11–14 项;确定性 mock,不连真实文件系统)。
            val library = remember { MockDataB.library }
            // 预览跟随**当前集合**:切集合后打开预览应看到该集合的图,
            // 否则页码与图片会对不上(索引来自当前集合)。
            var activeCollectionId by remember { mutableStateOf(collections.first().id) }
            val previewItems = collections.firstOrNull { it.id == activeCollectionId }?.images
                ?: collections.first().images

            // 返回手势/返回键:按**层级**逐级后退,而不是一步退出应用。
            // 真机实测(2026-09-18):未处理时在"命名结构"步骤按返回会**直接退出到桌面**,
            // 用户丢失当前浏览位置,与 Android 的层级返回预期不符。
            // 注意:BackHandler 注册在**最内层**,故各级返回由内向外依次生效。
            // 根级返回去处(按当前页面决定)。
            // 工作流内部**步骤级**的返回由各自的步骤容器处理 —— 它们注册得更深、
            // 优先级更高,故不会与本处冲突(详见 PredictiveBackContainer 注释)。
            val rootBack: (() -> Unit)? = when (screen) {
                Screen.Home -> null // 首页:交回系统(退出应用)
                Screen.Settings -> ({ screen = Screen.Home })
                Screen.Licenses -> ({ screen = Screen.Settings })
                Screen.WorkflowA -> ({ screen = Screen.Home })
                Screen.WorkflowB -> ({ screen = Screen.Home })
                is Screen.Preview -> ({ screen = Screen.WorkflowA })
            }

            PredictiveBackContainer(
                enabled = settings.predictiveBackAnimation,
                onBack = rootBack,
            ) {
            when (val current = screen) {
                Screen.Home -> HomeScreen(
                    onOpenWorkflowA = { screen = Screen.WorkflowA },
                    onOpenWorkflowB = { screen = Screen.WorkflowB },
                    onOpenSettings = { screen = Screen.Settings },
                )

                Screen.Settings -> {
                    SettingsScreen(
                        settings = settings,
                        onChange = updateSettings,
                        onBack = { screen = Screen.Home },
                        onOpenLicenses = { screen = Screen.Licenses },
                    )
                }

                Screen.Licenses -> {
                    LicensesScreen(onBack = { screen = Screen.Settings })
                }

                Screen.WorkflowA -> WorkflowASteps(
                    collections = collections,
                    onOpenPreview = { index -> screen = Screen.Preview(index) },
                    onActiveCollectionChange = { activeCollectionId = it },
                    // 在工作流 A 内按返回 -> 回首页(步骤内的返回由 WorkflowASteps 自己处理)
                    onBack = { screen = Screen.Home },
                )

                is Screen.Preview -> {
                    // 预览是最内层:返回即关闭预览,回到工作流 A。
                    // (ImagePreview 内部也有自己的关闭按钮;二者语义一致。)
                    ImagePreview(
                        items = previewItems,
                        initialIndex = current.index,
                        onClose = { screen = Screen.WorkflowA },
                    )
                }

                Screen.WorkflowB -> WorkflowBSteps(
                    rootPath = library.rootPath,
                    volumes = library.volumes,
                    // 在工作流 B 内按返回 -> 回首页(步骤内的返回由 WorkflowBSteps 自己处理)
                    onBack = { screen = Screen.Home },
                )
            }
            }
        }
    }
}

/**
 * 预测性返回容器(用户 2026-09-20 要求做成可切换开关)。
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
 * ## 优先级
 *
 * 本容器注册在**较外层**,步骤容器(WorkflowASteps/BSteps)注册在**更内层**。
 * `OnBackInvokedDispatcher` 取**最后注册者**,故工作流内部的步骤级返回优先;
 * 只有步骤容器不处理时(如已在第 1 步)才落到本容器。
 */
@Composable
private fun PredictiveBackContainer(
    enabled: Boolean,
    onBack: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (onBack == null) {
        // 首页:没有应用内返回去处,交回系统(系统据此执行"退出应用")。
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
