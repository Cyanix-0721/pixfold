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
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.data.SharedPrefsSettingsStore
import com.pixfold.d1.domain.settings.AppLanguage
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.domain.settings.SettingsStore
import com.pixfold.d1.nav.DestinationSurface
import com.pixfold.d1.nav.HomeRoute
import com.pixfold.d1.nav.LicensesRoute
import com.pixfold.d1.nav.NavTransitionStyle
import com.pixfold.d1.nav.PixFoldNavHost
import com.pixfold.d1.nav.PreviewRoute
import com.pixfold.d1.nav.SettingsRoute
import com.pixfold.d1.nav.WorkflowARoute
import com.pixfold.d1.nav.WorkflowBRoute
import com.pixfold.d1.ui.HomeScreen
import com.pixfold.d1.ui.preview.ImagePreview
import com.pixfold.d1.ui.settings.LicensesScreen
import com.pixfold.d1.ui.settings.SettingsScreen
import com.pixfold.d1.ui.theme.PixFoldTheme
import com.pixfold.d1.ui.workflowa.WorkflowASteps
import com.pixfold.d1.ui.workflowb.WorkflowBSteps

/**
 * 应用根:主题、设置、演示数据与**根导航图**。
 *
 * 路由由 `navigation-compose` 的 type-safe route 接管(2026-09-22 迁移)。
 * 迁移前是 `sealed interface Screen` + `mutableStateOf` 的手写路由 —— 它没有返回栈、
 * 没有转场引擎,因此"目标页显示在背后"这类预测性返回效果无从实现;
 * 官方适配要求也用 Navigation 组件托管返回栈(见 `docs/notes/d1-navigation-compose-migration.md`)。
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
    // 包 remember:避免每次重组都产生新 lambda 身份。即便不包也无害 ——
    // `NavGraph.equals` 是**结构相等**(见 docs/notes/d1-navigation-compose-migration.md F4),
    // 图实例变化不会清空返回栈。
    val updateSettings: (PixFoldSettings) -> Unit = remember(settingsStore) {
        { next ->
            settings = next
            settingsStore.save(next)
        }
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
            val navController = rememberNavController()

            // 工作流 A 演示数据:trip(30 张)+ scan(14 张)+ misc(12 张),
            // 多集合用于演示"批次默认 + 本组独立"(验收第 7 项)。
            val collections = remember { MockData.workspaceA.collections }
            // 工作流 B 演示数据:5 卷覆盖缺卷号 / 无语言线索 / 输出冲突三类异常
            // (验收第 11–14 项;确定性 mock,不连真实文件系统)。
            val library = remember { MockDataB.library }
            // 预览跟随**当前集合**:切集合后打开预览应看到该集合的图,
            // 否则页码与图片会对不上(索引来自当前集合)。
            var activeCollectionId by remember { mutableStateOf(collections.first().id) }

            // 返回手势/返回键:由 NavHost 自带的 handler 统一处理(它会随返回栈深度
            // 自动 enable/disable)。返回首页 = pop 掉 WorkflowA/B;首页(栈深 1)时
            // NavHost 不再注册 handler,交回系统退出应用 —— 与迁移前 `rootBack = null` 同义。
            //
            // 注意:返回栈是**唯一**事实来源,不再有 `screen` 这样的第二份状态。
            PixFoldNavHost(
                navController = navController,
                startDestination = HomeRoute,
                predictiveBackAnimation = settings.predictiveBackAnimation,
                // 根级 = 大页面切换:虚化 + 缩小
                style = NavTransitionStyle.Page,
            ) {
                composable<HomeRoute> {
                    DestinationSurface {
                        HomeScreen(
                            onOpenWorkflowA = { navController.navigate(WorkflowARoute) },
                            onOpenWorkflowB = { navController.navigate(WorkflowBRoute) },
                            onOpenSettings = { navController.navigate(SettingsRoute) },
                        )
                    }
                }

                composable<SettingsRoute> {
                    DestinationSurface {
                        SettingsScreen(
                            settings = settings,
                            onChange = updateSettings,
                            onBack = { navController.popBackStack() },
                            onOpenLicenses = { navController.navigate(LicensesRoute) },
                        )
                    }
                }

                composable<LicensesRoute> {
                    DestinationSurface {
                        LicensesScreen(onBack = { navController.popBackStack() })
                    }
                }

                composable<WorkflowARoute> {
                    DestinationSurface {
                        WorkflowASteps(
                            collections = collections,
                            // 步骤级返回同样跟随手势进度:开关透传进嵌套 NavHost 的转场策略。
                            predictiveBackAnimation = settings.predictiveBackAnimation,
                            onOpenPreview = { index -> navController.navigate(PreviewRoute(index)) },
                            onActiveCollectionChange = { activeCollectionId = it },
                        )
                    }
                }

                composable<PreviewRoute> { entry ->
                    // 预览是最内层:返回即关闭预览,回到工作流 A。
                    // (ImagePreview 内部也有自己的关闭按钮;二者语义一致。)
                    val route = entry.toRoute<PreviewRoute>()
                    // 在目的地内容里读取,保证拿到**当前**集合的图(而非图建立时的快照)。
                    val previewItems = collections.firstOrNull { it.id == activeCollectionId }?.images
                        ?: collections.first().images
                    DestinationSurface {
                        ImagePreview(
                            items = previewItems,
                            initialIndex = route.index,
                            onClose = { navController.popBackStack() },
                        )
                    }
                }

                composable<WorkflowBRoute> {
                    DestinationSurface {
                        WorkflowBSteps(
                            rootPath = library.rootPath,
                            volumes = library.volumes,
                            // 同上:步骤级返回跟随手势进度。
                            predictiveBackAnimation = settings.predictiveBackAnimation,
                        )
                    }
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
