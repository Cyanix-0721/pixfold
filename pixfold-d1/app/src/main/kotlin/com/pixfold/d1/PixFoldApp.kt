package com.pixfold.d1

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.data.SharedPrefsSettingsStore
import com.pixfold.d1.domain.settings.AppLanguage
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.domain.settings.SettingsStore
import com.pixfold.d1.ui.HomeScreen
import com.pixfold.d1.ui.components.PredictiveBackContainer
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

    // 语言必须**真正应用**到系统 per-app locale,否则只是存了个值、界面不变
    // (真机实测发现:选了 English 界面仍是中文 —— 此前完全漏了这一步)。
    ApplyAppLanguage(settings.language)

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
                    // 步骤级返回同样跟随手势进度(2026-09-21):把开关透传进容器。
                    // 此前步骤容器用普通 BackHandler,优先级高于根级 PredictiveBackHandler,
                    // 导致工作流内部完全没有手势跟随动画 —— 详见 HANGOFF §9.1 第 2 步。
                    predictiveBackAnimation = settings.predictiveBackAnimation,
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
                    // 同上:步骤级返回跟随手势进度。
                    predictiveBackAnimation = settings.predictiveBackAnimation,
                    // 在工作流 B 内按返回 -> 回首页(步骤内的返回由 WorkflowBSteps 自己处理)
                    onBack = { screen = Screen.Home },
                )
            }
            }
        }
    }
}

/**
 * 把设置里的语言应用到系统的 **per-app locale**。
 *
 * **为什么需要它**:设置页只把 `language` 存进 SharedPreferences,
 * 那只是"记录用户的选择";要让界面真的切换语言,必须调用
 * [LocaleManager.setApplicationLocales] —— 系统据此重建 Activity 并
 * 按新 locale 解析字符串资源。
 *
 * **此前漏了这一步**(真机实测:选 English 后界面仍是中文),属真实缺陷。
 *
 * **版本说明**:`LocaleManager` 是 API 33+,故仅在其上生效;
 * 更低版本此处不处理(项目真机为 Android 16,不受影响)。
 * 要让低版本也支持,需 `attachBaseContext` 手动包装 —— 属后续工作。
 */
@Composable
private fun ApplyAppLanguage(language: AppLanguage) {
    val context = LocalContext.current
    LaunchedEffect(language) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@LaunchedEffect
        val manager = context.getSystemService(LocaleManager::class.java) ?: return@LaunchedEffect
        val tag = when (language) {
            AppLanguage.System -> null          // 空 LocaleList = 跟随系统
            AppLanguage.Chinese -> "zh"
            AppLanguage.English -> "en"
        }
        val want = if (tag == null) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        // 已是目标值就不要重复设置 —— 否则每次重组都可能触发一次 Activity 重建。
        if (manager.applicationLocales != want) {
            manager.setApplicationLocales(want)
        }
    }
}
