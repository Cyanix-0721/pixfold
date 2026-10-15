package com.pixfold.d1

import android.app.LocaleManager
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
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.model.effectiveScheme
import com.pixfold.d1.domain.naming.buildProposals
import com.pixfold.d1.domain.naming.setOverride
import com.pixfold.d1.domain.workflowa.WorkflowAState
import com.pixfold.d1.domain.workflowa.workflowAStateOf
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
import com.pixfold.d1.ui.preview.PreviewRename
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

            // 工作流 A 演示数据:trip(30 张)+ scan(14 张)+ misc(12 张)+ cn(16 张),
            // 多集合用于演示"批次默认 + 本组独立"(验收第 7 项);
            // cn 是中文数字自然序的可证伪样例(见 MockData.chapters 的 KDoc)。
            val collections = remember { MockData.workspaceA.collections }
            // 工作流 B 演示数据:5 卷覆盖缺卷号 / 无语言线索 / 输出冲突三类异常
            // (验收第 11–14 项;确定性 mock,不连真实文件系统)。
            val library = remember { MockDataB.library }
            // 预览跟随**当前集合**:切集合后打开预览应看到该集合的图,
            // 否则页码与图片会对不上(索引来自当前集合)。
            var activeCollectionId by remember { mutableStateOf(collections.first().id) }

            // ---- 工作流 A 的两份共享状态提升到应用根(2026-09-25)----
            //
            // **为什么必须提上来**:预览页是**根导航的兄弟目的地**,与 `WorkflowASteps`
            // 内的嵌套图不在同一棵树上,因此它够不到工作流内部的任何状态。而预览页需要两样东西:
            //
            //  1. **当前页序**(`PageOrderState.order`)—— 点击回传的 `index` 是**页序下标**
            //     (见 `SortAndPreviewPage` 传 `items = order.order`),若预览按集合**声明序**
            //     取图,只有"页序恰好等于声明序"时才碰巧正确。`cn` 集合的声明序是故意打乱的,
            //     故修复前会点开另一张图(已由 `AppNavigationTest` 钉住)。
            //  2. **命名状态**(`NamingState`)—— 预览页的重命名入口写回的就是它的
            //     `overrides`,与命名页共用同一份,故两处改名结果必然一致。
            //
            // 默认值刻意与 `WorkflowASteps` 内部的兜底取值保持**同一表达式**,
            // 否则"上层初始值"与"组件自持初值"会算出不同结果。
            var workflowAState by remember { mutableStateOf(workflowAStateOf(collections)) }
            var namingState by remember {
                mutableStateOf(
                    NamingState(
                        NamingScheme(rootDirName = collections.firstOrNull()?.rootDirName.orEmpty()),
                    ),
                )
            }

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
                    // 用户 2026-10-14 裁决:「默认返回到主页改为触发重置。**只有工作流内部切换不会自动重置**」。
                    //
                    // **为什么复位挂在「回到主页」这一侧,而不是 `WorkflowARoute` 的 `DisposableEffect`**:
                    // 预览页是**根导航的兄弟目的地**(见上方 :100-113),`navigate(PreviewRoute)` 一样会让
                    // `WorkflowARoute` 离开组合 —— 在离场时复位,预览读到的 `PageOrderState` 会当场变成
                    // 自动序,点开的图与网格对不上(正是 2026-09-25 修过的那类缺陷)。
                    // 挂在 `HomeRoute` 上则只在"真的回到主页"时触发;首次启动时复位到初值是幂等的。
                    //
                    // 复位三样:页序/排序规则(`workflowAState`)、命名结构与覆盖(`namingState`)、
                    // 当前集合(`activeCollectionId`)。工作流 B 的共享状态全在 `WorkflowBSteps`
                    // 内部自持,离开组合即自然归零,无需在此处写第二遍。
                    LaunchedEffect(Unit) {
                        workflowAState = workflowAStateOf(collections)
                        namingState =
                            NamingState(
                                NamingScheme(
                                    rootDirName = collections.firstOrNull()?.rootDirName.orEmpty(),
                                ),
                            )
                        activeCollectionId = collections.first().id
                    }
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
                            // ⚠️ 必须回传当前集合:打开预览会让本目的地离开组合,
                            // 内部的 `remember { activeId }` 随之销毁;关闭预览回来是
                            // 新的组合实例,不给种子就会退回首个集合(2026-09-25 修复)。
                            initialActiveCollectionId = activeCollectionId,
                            externalState = workflowAState,
                            onStateChange = { workflowAState = it },
                            initialNamingState = namingState,
                            onNamingStateChange = { namingState = it },
                            // 网格默认列数(设置在设置页;排序页按屏幕方向二选一,
                            // 工具栏列的临时覆盖不回写这里)。
                            portraitDefaultColumns = settings.portraitDefaultColumns,
                            landscapeDefaultColumns = settings.landscapeDefaultColumns,
                        )
                    }
                }

                composable<PreviewRoute> { entry ->
                    // 预览是最内层:返回即关闭预览,回到工作流 A。
                    // (ImagePreview 内部也有自己的关闭按钮;二者语义一致。)
                    val route = entry.toRoute<PreviewRoute>()
                    val activeCollection = collections.firstOrNull { it.id == activeCollectionId }
                        ?: collections.first()
                    // ⚠️ 必须用**当前页序**,不能用 `collection.images`(声明序)。
                    // 点击回传的 `route.index` 是页序下标(见 SortAndPreviewPage 传
                    // `items = order.order`);两者只在"页序 == 声明序"时碰巧一致,
                    // 而 `cn` 集合的声明序是故意打乱的 —— 用声明序会打开另一张图
                    // (2026-09-25 修复,由 AppNavigationTest 钉住)。
                    val pageOrder = workflowAState.orders[activeCollection.id]?.order
                        ?: activeCollection.images
                    // 预览的重命名要显示**当前生效的最终名**(覆盖优先于建议名),
                    // 故与命名页用同一条求值链:`effectiveScheme` + `buildProposals`。
                    // 挂 `remember`:两千张规模下这是全量重算,不能每次重组都跑一遍。
                    val proposals = remember(pageOrder, namingState, activeCollection) {
                        buildProposals(
                            pageOrder,
                            effectiveScheme(namingState, activeCollection),
                            overrides = namingState.overrides,
                        ).associateBy { it.image.id }
                    }
                    DestinationSurface {
                        ImagePreview(
                            items = pageOrder,
                            initialIndex = route.index,
                            onClose = { navController.popBackStack() },
                            // 重命名写回**命名页同一个** `namingState.overrides`
                            // (键 = SourceItem.id,值 = 完整文件名,空值即撤销覆盖),
                            // 故两处改名互相同步 —— 不是两份状态。
                            rename = PreviewRename(
                                overrides = namingState.overrides,
                                finalNameOf = { item -> proposals[item.id]?.finalName ?: item.fileName },
                                onRename = { item, name ->
                                    namingState = setOverride(namingState, item.id, name)
                                },
                            ),
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
 * **版本说明**:`LocaleManager` / `LocaleConfig` 均为 API 33+。本项目 minSdk
 * 已于 2026-09-25 提到 **34**,故此处**不再需要版本分支** —— 早先那句
 * `if (Build.VERSION.SDK_INT < TIRAMISU) return@LaunchedEffect` 已删除
 * (留着会被 lint 判 `ObsoleteSdkInt` 而本项目 `warningsAsErrors=true`)。
 *
 * **为什么"启动首帧遇到 System 必须不写入"**(2026-09-25 修复的真实缺陷):
 * AOSP 对空表的定义**不是"未设置"**,而是一次**主动写入** ——
 * 原文:"Pass a `LocaleList.getEmptyLocaleList()` to reset to the system locale."
 * (`android.app.LocaleManager#setApplicationLocales`)。而应用内 `language` 的
 * 默认值就是 [AppLanguage.System],于是"应用启动"本身会写入空表,把用户在
 * **系统"设置 → 应用 → 语言"**里的选择一并清掉 —— 用户实报:系统侧选了 English,
 * 界面仍是中文。注意这是**双向**的:反过来也一样会被改回去。
 *
 * 平台侧**没有**语言变化监听 API(`LocaleManager` 全部方法只有
 * set/get applicationLocales、set/get systemLocales、set/get overrideLocaleConfig),
 * 因此不能靠"监听系统侧变化"兜底,只能靠**启动时不乱写**。
 *
 * 判据是"**变化**"而不是"值":用户从英文**主动切回**"跟随系统"时,必须写入空表
 * 才能真的退回系统语言 —— 所以不能简单地把 `System` 一律跳过。
 */
@Composable
private fun ApplyAppLanguage(language: AppLanguage) {
    val context = LocalContext.current
    // 首帧的 `language` 来自存储,是"当前值"而非"用户刚改动";之后再触发即是真的变化。
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(language) {
        val isStartup = isFirstComposition
        isFirstComposition = false
        // 启动首帧 + 存储里是默认的 System ⇒ 不写入,保住系统侧的选择(见上方 KDoc)。
        if (isStartup && language == AppLanguage.System) return@LaunchedEffect

        val manager = context.getSystemService(LocaleManager::class.java) ?: return@LaunchedEffect
        val tag = when (language) {
            AppLanguage.System -> null          // 空 LocaleList = 退回跟随系统
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
