package com.cyanix.pixfold

import android.app.LocaleManager
import android.os.LocaleList
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.comic.ExistingComicInfoProbe
import com.cyanix.pixfold.domain.comic.OutputExistsProbe
import com.cyanix.pixfold.domain.comic.applyExistingComicInfo
import com.cyanix.pixfold.domain.comic.comicVolumesFrom
import com.cyanix.pixfold.domain.comic.markOutputExists
import com.cyanix.pixfold.domain.source.nestedDirWarnings
import com.cyanix.pixfold.domain.model.effectiveScheme
import com.cyanix.pixfold.domain.naming.buildProposals
import com.cyanix.pixfold.domain.naming.setOverride
import com.cyanix.pixfold.data.SharedPrefsSettingsStore
import com.cyanix.pixfold.domain.source.WorkspaceSource
import com.cyanix.pixfold.data.saf.FilesystemSpaceSource
import com.cyanix.pixfold.data.saf.SafVolumeProbeSession
import com.cyanix.pixfold.data.saf.SafMetaUpdater
import com.cyanix.pixfold.data.saf.SafPackExecutor
import com.cyanix.pixfold.data.saf.outputDirRelOf
import com.cyanix.pixfold.data.saf.SafRenameActions
import com.cyanix.pixfold.data.saf.SafUndoActions
import com.cyanix.pixfold.data.task.TaskCompletionNotification
import com.cyanix.pixfold.ui.components.DialogBlurController
import com.cyanix.pixfold.ui.components.DialogBlurHost
import com.cyanix.pixfold.domain.settings.AppLanguage
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.SettingsStore
import com.cyanix.pixfold.nav.DestinationSurface
import com.cyanix.pixfold.nav.HomeRoute
import com.cyanix.pixfold.nav.LicensesRoute
import com.cyanix.pixfold.nav.NavTransitionStyle
import com.cyanix.pixfold.nav.PixFoldNavHost
import com.cyanix.pixfold.nav.ROUTE_CLASS_METADATA
import com.cyanix.pixfold.nav.popOne
import com.cyanix.pixfold.nav.rootFadePop
import com.cyanix.pixfold.nav.settingsSlidePop
import com.cyanix.pixfold.nav.workflowShrinkPop
import com.cyanix.pixfold.nav.PreviewRoute
import com.cyanix.pixfold.nav.SettingsRoute
import com.cyanix.pixfold.nav.OrganizeRoute
import com.cyanix.pixfold.nav.LibraryRoute
import com.cyanix.pixfold.ui.HomeScreen
import com.cyanix.pixfold.ui.preview.ImagePreview
import com.cyanix.pixfold.ui.preview.PreviewRename
import com.cyanix.pixfold.ui.settings.LicensesScreen
import com.cyanix.pixfold.ui.settings.SettingsScreen
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.workspace.LocalThumbnailSource
import com.cyanix.pixfold.ui.workspace.WorkspaceListDialog
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import com.cyanix.pixfold.ui.workspace.rejectAllPinnedHint
import com.cyanix.pixfold.ui.workspace.collectionsOr
import com.cyanix.pixfold.ui.workspace.handleOrNull
import com.cyanix.pixfold.ui.organize.OrganizeSteps
import com.cyanix.pixfold.ui.library.LibrarySteps
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用根:主题、设置、演示数据与**根导航图**。
 *
 * 路由由 **Navigation 3** 的 type-safe `NavKey` + `NavBackStack` 接管(2026-11-14 全量迁移;
 * 更早的 `navigation-compose` 时期见 `docs/notes/d1-navigation-compose-migration.md`)。
 * 迁移前是 `sealed interface Screen` + `mutableStateOf` 的手写路由 —— 它没有返回栈、
 * 没有转场引擎,因此"目标页显示在背后"这类预测性返回效果无从实现。
 *
 * @param store 设置存储。默认用 [SharedPrefsSettingsStore];测试可注入内存实现,
 *   从而无需 Android 持久化环境即可断言"设置真的驱动了主题"。
 */
@Composable
fun PixFoldApp(
    store: SettingsStore? = null,
    /**
     * 启动后是否直接落到「打包漫画 · 步骤 3」。
     *
     * 由 `MainActivity` 从通知的 intent extra 读出（2026-10-20 真机缺陷：
     * 点进度通知回到首页，而用户想看的是进度/结果）。默认 false ⇒ 既有测试
     * 与正常启动路径零改动。
     */
    openPackOnStart: Boolean = false,
    /**
     * 上层（`MainActivity`）在「打包页请求」被消费后调用的回调。
     *
     * **为什么需要它**（2026-10-20 真机第二轮）：热启动时 `onNewIntent` 把
     * `openPackOnStart` 置 true，导航一次之后必须能**再置回 false** ——
     * 否则用户下次点通知（或下次进打包页）会拿到一个已经为 true 的值，
     * 而 `LaunchedEffect(openPackOnStart)` 的 key 没变 ⇒ 不再导航。
     * 默认空实现让既有测试与普通启动路径零改动。
     */
    onPackRouteHandled: () -> Unit = {},
    /**
     * 工作区来源。默认用 [AndroidSafSource]（真实 SAF 遍历）。
     *
     * **为什么必须留这道缝**（2026-10-24）：用户裁决「未选工作区时首页两张大卡禁用」
     * 之后，首页入口的可用性取决于 [WorkspaceState]。而 Robolectric 里没有任何
     * `DocumentsProvider`，真实 [AndroidSafSource] 的 `workspaces()` 恒为空列表 ⇒
     * state 恒为 [WorkspaceState.None] ⇒ 两张大卡恒禁用 ⇒ 24 处「在首页点卡片进工作流」
     * 的导航测试会**一起变红**。那不是行为回归，是测试环境里根本没有可授权的目录。
     *
     * 注入一个假源就能让那些测试重新拿到 `Ready` —— 与 `store: SettingsStore?`
     * 同一套思路（domain 定接口 + app 层实现 + 测试注入内存实现）。
     */
    workspaceSource: WorkspaceSource? = null,
    /**
     * 冷启动是否走懒加载：只恢复上次工作区的句柄，不扫描；点进工作流时才真正加载。
     *
     * **默认 `false` 是刻意的**：既有 24+ 处导航测试都依赖「冷启动立即 Ready」，
     * 在生产入口（[MainActivity]）显式传 `true`，测试不改一行就能继续验返回栈等旧主题。
     * 这也让本参数成为「行为开关」：出问题时可以一键退回旧的立即扫描。
     */
    lazyWorkspaceStart: Boolean = false,
    /**
     * 工作流 C 的平台层（就地更新已有 CBZ 的元数据）。默认 `null` = 生产路径
     * 在内部用 SafMetaUpdater 构造（**需要工作区**，没有工作区时它也构造不出来 ⇒
     * 两个入口都不渲染，与「没有真实工作区就不该往盘上写」同一条立场）。
     *
     * 留这道缝是为了让测试能注入假实现 —— 与 workspaceSource 同一套思路。
     */
    metaUpdater: com.cyanix.pixfold.domain.pack.CbzMetaUpdater? = null,
    /**
     * 工作流 B 输出是否存在探针。默认 `null` = 生产路径在进入 `LibraryRoute` 后于
     * [probeDispatcher] 上走批量 [SafVolumeProbeSession]；测试可注入假实现验证懒加载与离主线程行为。
     */
    outputExistsProbe: OutputExistsProbe? = null,
    /**
     * 工作流 B 已有 CBZ 的 `ComicInfo.xml` 探针。默认 `null` = 与 [outputExistsProbe] 共享同一次
     * [SafVolumeProbeSession] 目录快照；测试可注入假实现。
     */
    existingComicInfoProbe: ExistingComicInfoProbe? = null,
    /** 卷探测的后台调度器，默认 [Dispatchers.IO]（严禁在组合主线程执行 SAF/ZIP I/O）。 */
    probeDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 可选的外部托管会话（由 [MainActivity] 经 [PixFoldSessionViewModel] 提供以跨配置变更保态）。
     * 为 `null` 时（所有既有 Compose UI 测试）在本地 `remember` 独立会话并于离场时释放资源。
     */
    session: PixFoldSession? = null,
) {
    val context = LocalContext.current
    val settingsStore = store ?: remember { SharedPrefsSettingsStore(context) }
    // 设置在**应用根**持有:主题、语言、各页面都要读它,提上层是唯一合理位置。
    var settings by remember { mutableStateOf(settingsStore.load()) }
    // 包 remember:避免每次重组都产生新 lambda 身份,设置写入是稳定的引用。
    val updateSettings: (PixFoldSettings) -> Unit = remember(settingsStore) {
        { next ->
            settings = next
            settingsStore.save(next)
        }
    }

    // 语言必须**真正应用**到系统 per-app locale,否则只是存了个值、界面不变
    // (真机实测发现:选了 English 界面仍是中文 —— 此前完全漏了这一步)。
    ApplyAppLanguage(settings.language)

    // ---- 应用级会话（P1 #7）：生产由 PixFoldSessionViewModel 跨配置变更持有；
    // 测试/独立调用（session == null）在本地 remember 独立会话并于离场时释放资源。
    // 注意：remember 必须无条件执行，避免条件 remember 导致组合槽位错位。
    val workspaceScope = rememberCoroutineScope()
    val ownedSession = remember(session, context, probeDispatcher) {
        if (session != null) {
            null
        } else {
            createDefaultPixFoldSession(
                context = context,
                probeDispatcher = probeDispatcher,
                workspaceSourceOverride = workspaceSource,
                scope = workspaceScope,
            )
        }
    }
    DisposableEffect(ownedSession) {
        onDispose { ownedSession?.close() }
    }
    val activeSession = session ?: requireNotNull(ownedSession)
    val thumbnailSource = activeSession.thumbnailSource
    val previewImageSource = activeSession.previewImageSource
    val workspaceController = activeSession.workspaceController
    val volumeLoader = activeSession.volumeLoader
    // 工作区列表弹窗的开关（2026-10-25 规格 §7.1/§7.3）。
    //
    // **弹窗本体必须挂在 NavDisplay 之外**（见下方 `DialogBlurHost` 内的那一处）：
    // 这个 `showWorkspaceList` 只在换页时不动就能成立 —— 反过来若把弹窗写进某个
    // 页面，用户点「添加文件夹」拉起系统选择器再回来，页面重建会把弹窗连开关一起丢掉，
    // 表现为「加完目录，列表自己关了」。
    var showWorkspaceList by remember { mutableStateOf(false) }
    // 「点整条工作区条」的唯一出口。**先 `openList()` 再置开**：`openList()` 刷新
    // `listings` 与 `listRejected`，弹窗首帧就拿到新鲜数据，不会先闪一帧空列表。
    val openWorkspaceList: () -> Unit = {
        workspaceController.openList()
        showWorkspaceList = true
    }
    // 首页两张大卡在根坐标系里的位置;工作流返回时以对应卡片中心作为缩放轴。
    var organizeCardBounds by remember { mutableStateOf<Rect?>(null) }
    var libraryCardBounds by remember { mutableStateOf<Rect?>(null) }
    // 懒启动下等待「加载完成后再跳」的目标工作流。用户点首页大卡时先记下要去哪，
    // 等扫到非空内容才真正导航；失败/空目录就留在首页，用户能直接换目录。
    var pendingWorkflow by remember { mutableStateOf<NavKey?>(null) }
    // 冷启动：生产走懒启动（只恢复句柄，扫描推迟到用户点进工作流）。
    // 经 `ensureWorkspaceStarted` 幂等守护：配置变更重建 Activity 复用同一会话时不再重跑，
    // 避免把已 Ready 的工作区打回 Idle/Loading。
    LaunchedEffect(activeSession) {
        activeSession.ensureWorkspaceStarted(lazy = lazyWorkspaceStart && !openPackOnStart)
    }
    val workspaceState = workspaceController.state
    // 「当前打开的是哪个工作区目录」的稳定标识。**下游凡是「该不该重置」的判断
    // 都用它，而不是 `collections`**：`collections` 会在同一个目录里随文件增删
    // 而变化（实时刷新），拿它当 key 等于「用户改一下文件就把界面状态全清掉」。
    // 未授权 / 扫描中 / 失败时它是 `null`，那时 `collections` 是 mock ——
    // 对 UI 而言仍是一个完整可用的工作区。
    val workspaceHandle = workspaceState.handleOrNull()
    // 仅在工作区句柄真正切换时清空缩略图与预览缓存（旋转屏幕与同工作区刷新保留 LRU）。
    LaunchedEffect(activeSession, workspaceHandle?.treeUri) {
        activeSession.onWorkspaceHandleObserved(workspaceHandle?.treeUri)
    }

    // ---- 回前台自动刷新（D4「工作区实时刷新」2026-10-19 用户裁决）----
    //
    // 用户原话是「我可能看到图片有问题或者缺失，会用文件管理器删除或添加或重命名
    // 图片，工作区应该实时刷新」。三条触发路径里，这条管的是「用户去别的应用改完
    // 文件再切回来」—— 此时 ContentObserver 的通知往往已经在后台丢失或被子线程
    // 攒着，靠 ON_RESUME 补一次重扫最可靠。
    //
    // ⚠️ **首次 ON_RESUME 必须跳过**：`restore()` 在 `LaunchedEffect` 里发出，
    // 而 Activity 首次 RESUMED 与首帧组合的先后**没有保证**。不跳过的话冷启动会
    // 连扫两遍（千级目录下是肉眼可见的卡顿），且第二遍扫完紧接着又触发一次对账。
    // 注意：`workspaceController.close()` 已上移至 `ownedSession.close()`（独立模式）与
    // `PixFoldSessionViewModel.onCleared()`（生产模式），不在配置变更重建时提前注销。
    val lifecycleOwner = LocalLifecycleOwner.current
    val skipFirstResume = remember { AtomicBoolean(true) }
    DisposableEffect(lifecycleOwner, workspaceController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // compareAndSet 而不是读一个 Boolean：ON_RESUME 理论上只来一次，
                // 但多窗口/分屏下重建后可能再收一次，用原子量保证「只跳过一次」。
                if (!skipFirstResume.compareAndSet(true, false)) {
                    workspaceController.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 选目录：ACTION_OPEN_DOCUMENT_TREE 是**唯一**能拿到可持久化 tree URI 的通道
    // （HANDBOOK §4：没有「以 SAF 树形式授权目录」的标准分享通道）。
    val pickWorkspaceFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // uri == null 表示用户取消了选择 —— 保持现状，不动已有授权。
        if (uri != null) {
            // **顺序不能反**（规格 §7.3 + R26）：`pick()` 才会把新目录
            // `rememberWorkspace` 进列表，`openList()` 才读得到它；
            // 先 openList 再 pick 等于「加完文件夹，列表里看不到新的那条」。
            workspaceController.pick(uri.toString())
            // 弹窗是从弹窗内部拉起的，回来时**不关**（规格 §7.3「加完继续在列表里」）。
            if (showWorkspaceList) workspaceController.openList()
        }
    }

    // ---- 通知权限（D2b 第 2 项）----
    //
    // Android 13 起 `POST_NOTIFICATIONS` 是运行时权限。没有它前台服务**仍能跑**
    // （任务不中断），但进度条看不见 —— 而「看得见」正是这一项的验收要求。
    //
    // 请求时机：进应用就问，而不是等用户点了「确认打包」才问。后者的代价是
    // 用户点完确认、打包已经开始，界面上才冒出一个权限对话框 —— 那时任务在
    // 后台跑而通知栏空着，正好是最需要它的时候。
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // minSdk 34 ⇒ `POST_NOTIFICATIONS` 一定存在，无需 SDK_INT 判断（加了 lint 会报
    // `ObsoleteSdkInt`：那条件恒真，留着只会让人以为还要考虑更老的系统）。
    LaunchedEffect(Unit) {
        val granted = context.checkSelfPermission(
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ---- 上次打包被中断的提示（D2b 第 2 项）----
    //
    // 只在**没有任务正在跑**时判：服务活着说明任务还在写，此时把「上次中断了」
    // 摆出来是假警报（`recoverPackTask` 也因此把 `serviceAlive` 做成必填参数）。
    //
    // 只说事实、不给动作：中断时刻磁盘上可能是完整文件（旧文件被覆盖到一半）、
    // 也可能是零字节（`createDocument` 刚建完就死了），自动删会毁掉可能完好的数据。
    //
    // 文案在**组合期**用 `stringResource` 算，不在 `LaunchedEffect` 里调
    // `context.getString`：后者不会被 Compose 记为配置依赖，语言/字号一变就会
    // 拿着旧 Configuration 的字符串（lint `LocalContextGetResourceValueCall`）。
    var interruptedSnapshot by remember {
        mutableStateOf<com.cyanix.pixfold.domain.task.PackTaskSnapshot?>(null)
    }
    LaunchedEffect(Unit) {
        val taskStore = com.cyanix.pixfold.data.task.PackTaskStore(context)
        val snapshot = taskStore.load()
        val serviceAlive = com.cyanix.pixfold.data.task.PackTaskBus.isRunning()
        val recovery = com.cyanix.pixfold.domain.task.recoverPackTask(snapshot, serviceAlive)
        if (recovery is com.cyanix.pixfold.domain.task.PackTaskRecovery.Interrupted) {
            interruptedSnapshot = recovery.snapshot
            // 快照读过一次就清掉：提示关掉后重启应用不该再弹同一条。
            // 只在**取到之后**清 —— 取之前清会让「用户没看到就丢了」。
            taskStore.clear()
        }
    }
    val interruptedSnap = interruptedSnapshot
    val interruptedNotice: String? = if (interruptedSnap == null) {
        null
    } else {
        stringResource(
            R.string.b_interrupted_notice,
            interruptedSnap.progress.processed,
            interruptedSnap.progress.total,
            interruptedSnap.outputTargets.size,
        )
    }

    PixFoldTheme(settings = settings) {
        // Surface 提供 M3 语义背景色 —— 深色模式下不会透出窗口白底(真机实证修复)。
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 真实缩略图来源（D2a）。用 CompositionLocal 而不是参数透传：缩略图出现在
            // 5 个互不相干的位置（网格卡/列表行/拖拽浮层 ×2/预览页），其中两处在
            // DragGhost 里、根本不在页面参数路径上。逐个加参数会变成 5 个独立开关。
            //
            // 测试环境不提供它（默认 null）⇒ 一律走 D1 的程序化缩略图，
            // 既有 491 个测试的断言一行都不用改。
            CompositionLocalProvider(LocalThumbnailSource provides thumbnailSource) {
            val backStack = rememberNavBackStack(HomeRoute)

            // 点通知启动 ⇒ 直接进「打包漫画」。
            //
            // **两条入口都要覆盖**（2026-10-20 真机缺陷）：
            //  · 冷启动（应用被杀）⇒ `openPackOnStart` 首次就是 true；
            //  · 热启动（应用在后台）⇒ `onNewIntent` 把它改成 true。此时本函数
            //    重新组合，但 `remember` **不会重播种**（无 key）—— 第一版就死在
            //    这里：真机「从首页按 HOME 再点通知」纹丝不动。改用
            //    `LaunchedEffect(openPackOnStart)` 补上，key 变化即重跑。
            //
            // **消费后必须清零**（`onPackRouteHandled` + `startAtPack = false`）：
            // 否则用户从打包页返回首页、再点「打包漫画」，又会直接落到步骤 3。
            // 清零的时机在下层（`LibrarySteps` 真的跳完之后），不能在这里同步清 ——
            // 那样下层组合时读到的已经是 false。
            var startAtPack by remember { mutableStateOf(openPackOnStart) }
            LaunchedEffect(openPackOnStart) {
                if (!openPackOnStart) return@LaunchedEffect
                startAtPack = true
                val idle = workspaceState as? WorkspaceState.Idle
                if (idle != null) {
                    // 热启动时应用可能还停在懒启动的 Idle 态；先加载，等扫描成功后
                    // 由下方 pendingWorkflow 的统一出口补导航，别拿 mock 进打包页。
                    pendingWorkflow = LibraryRoute
                    workspaceController.load(idle.handle)
                } else if (backStack.lastOrNull() != LibraryRoute) {
                    backStack.add(LibraryRoute)
                }
                onPackRouteHandled()
            }

            // 工作流 A 的数据源（D2a）：**有真实工作区就用扫描结果，否则回退 mock**。
            //
            // 回退规则写在 `collectionsOr` 里并单独单测过，三条约束：
            //  1. 从未选过目录（None）/ 正在扫描（Loading）/ 授权失效（Denied）⇒ 用 mock，
            //     D1 的演示与既有 491 个测试因此不受影响；
            //  2. `Ready` 但列表为空 ⇒ **不回退**（用户选了个空目录，就该看到「没有图片」，
            //     悄悄塞回演示数据会让人以为扫到了东西）；
            //  3. mock 数据：trip(30)+scan(14)+misc(12)+cn(16)，多集合用于演示
            //     「批次默认 + 本组独立」（验收第 7 项），cn 是中文数字自然序的可证伪样例。
            val collections = remember(workspaceState) {
                workspaceState.collectionsOr(MockData.workspaceA.collections)
            }

            // 懒启动：用户已经在首页点了工作流，等这次加载真正成功后补上导航。
            // 失败 / 空目录一律清掉 pending —— 留在首页并让大卡继续禁用，
            // 那才是用户能做决定的界面（换目录 / 重选授权）。
            LaunchedEffect(workspaceState, pendingWorkflow) {
                val target = pendingWorkflow ?: return@LaunchedEffect
                // 用户点完卡片又立刻去了设置/别处：别在扫描结束后从背后把他弹进工作流。
                // 通知路径（LibraryRoute）例外，它本来就是要把人带到打包页。
                if (target != LibraryRoute && backStack.lastOrNull() != HomeRoute) {
                    pendingWorkflow = null
                    return@LaunchedEffect
                }
                when (val s = workspaceState) {
                    is WorkspaceState.Ready -> {
                        pendingWorkflow = null
                        if (s.collections.isNotEmpty()) {
                            backStack.add(target)
                        } else if (target == LibraryRoute) {
                            startAtPack = false
                        }
                    }
                    is WorkspaceState.Denied, is WorkspaceState.Failed -> {
                        pendingWorkflow = null
                        if (target == LibraryRoute) startAtPack = false
                    }
                    else -> Unit
                }
            }
            // ---- 工作流 B 的真实数据源(D2b,用户 2026-10-19 裁决「复用工作区」)----
            //
            // 「一个集合 = 一本」:工作区扫描出的每个**直接含图片的目录**就是一个卷,
            // 父目录即系列 —— 与工作流 A 完全同一套语义,两处不会对同一批文件给出
            // 不一致的划分。
            //
            // 三层分工(与 `SafPackExecutor` 的 KDoc 一致):
            //   comicVolumesFrom        —— 纯函数,集合 → 卷的建议值
            //   SafOutputExistsProbe    —— 问provider「同名 CBZ 是不是已经在那」,决定覆盖警告
            //   SafPackExecutor         —— 真正逐页读字节、写 ZIP
            //
            // ⚠️ 为什么 `outputExists` 必须问一次(哪怕慢):它为 true 而未告知用户,
            // 打包时会静默覆盖别人已有的文件;它为 false 而实际存在,撤销记录会把
            // 「覆盖」记成「新建」,撤销时**删掉用户原来的文件**(缺陷 R3)。
            val packTreeUri = workspaceHandle?.treeUri?.let { it.toUri() }
            // 工作流 C 的平台层（2026-10-26 规格 §8.7）。
            //
            // **必须 `remember`**，理由与 `packExecutor` 完全同源（2026-10-20 真实缺陷）：
            // 不 remember 时每次重组都新建一个 `SafMetaUpdater`，而下层若拿它当
            // `LaunchedEffect` 的 key，就会把「换了个新对象」当成「条件变了」无限重启。
            //
            // `dirOfVolume` 必须与打包链用**同一个** `outputDirRelOf`：两边对「卷在哪个目录」
            // 的理解一旦分叉，更新会把元数据写进一个用户看不见的地方（或者直接找不到包）。
            // 注入优先（测试给假实现），与 `workspaceSource` 同一套思路。
            // ⚠️ remember 与「注入优先」**分成两步**，不要写成 `metaUpdater ?: remember { }`：
            // 后者是**条件调用 remember**，参数一变（注入值从 null 变成实现）槽位就错位。
            // 分两步时 remember 无条件执行，注入只覆盖结果 —— 行为等价，槽位稳定。
            val builtMetaUpdater: com.cyanix.pixfold.domain.pack.CbzMetaUpdater? =
                remember(packTreeUri, workspaceHandle?.displayName) {
                    val uri = packTreeUri ?: return@remember null
                    val rootName = workspaceHandle?.displayName.orEmpty()
                    val space = FilesystemSpaceSource(context)
                    SafMetaUpdater(
                        context = context,
                        treeUri = uri,
                        spaceBytesOf = { space.availableBytesOf(it) },
                        dirOfVolume = { vol -> outputDirRelOf(vol.outputDir, rootName) },
                    )
                }
            val resolvedMetaUpdater = metaUpdater ?: builtMetaUpdater
            // 基础卷在组合期**零 I/O** 纯计算构造；`outputExists` 与已有 CBZ 的 `ComicInfo.xml`
            // 探测推迟到进入 `LibraryRoute` 后在 `probeDispatcher`（默认 `Dispatchers.IO`）异步执行，
            // 绝不在首页 / 整理页或主线程触发 SAF Binder IPC 与 ZIP/XML 读取（P0 #1）。
            val baseVolumes = remember(collections, packTreeUri, workspaceHandle?.displayName) {
                if (packTreeUri == null) {
                    // 没有可用工作区 ⇒ 退回演示数据。这不是「悄悄塞假数据」:
                    // 界面顶部的工作区条同时显示「未选择工作区」,用户看得见自己在
                    // 哪个状态,与工作流 A 的 `collectionsOr` 是同一套回退口径。
                    MockDataB.library.volumes
                } else {
                    comicVolumesFrom(
                        collections = collections,
                        rootDisplayName = workspaceHandle?.displayName.orEmpty(),
                        outputExists = OutputExistsProbe { false },
                    )
                }
            }
            val libraryRootPath = workspaceHandle?.displayName
                ?: MockDataB.library.rootPath
            val readyWorkspaceHandle = (workspaceState as? WorkspaceState.Ready)?.handle
            // 工作流 B 的异步探测与实时刷新增量对账由 activeSession.volumeLoader 统一托管：
            // 切换工作区时重置，同工作区集合变更时做增量对账，配置变更重建时保留未保存编辑。
            volumeLoader.syncBase(
                workspaceKey = readyWorkspaceHandle?.treeUri,
                rootPath = libraryRootPath,
                baseVolumes = baseVolumes,
            )
            val libraryVolumes = volumeLoader.volumes

            // ---- 工作流 A 的共享状态（托管于 activeSession，配置变更存活 + 同目录增量对账）----
            activeSession.syncOrganizeCollections(
                workspaceKey = workspaceHandle?.treeUri,
                collections = collections,
            )
            // 刷新后原先选中的集合可能已被删掉：派生当前真正可用的集合 id，并在副作用回写。
            val currentCollectionId = collections.firstOrNull { it.id == activeSession.activeCollectionId }?.id
                ?: collections.firstOrNull()?.id.orEmpty()
            LaunchedEffect(collections, activeSession) {
                if (activeSession.activeCollectionId != currentCollectionId) {
                    activeSession.activeCollectionId = currentCollectionId
                }
            }
            val organizeState = activeSession.organizeState
            val namingState = activeSession.namingState

            // 返回手势/返回键:由 NavDisplay 的 onBack 统一处理(它会随返回栈深度
            // 自动 enable/disable)。返回首页 = pop 掉 Organize/B;首页(栈深 1)时
            // NavDisplay 不再处理返回,交回系统退出应用 —— 与迁移前 `rootBack = null` 同义。
            //
            // 注意:返回栈是**唯一**事实来源,不再有 `screen` 这样的第二份状态。
            // 全站弹窗背景模糊：弹窗是独立 window，拿不到宿主内容，
            // 只能由宿主在「有弹窗打开时」给页面加 `Modifier.blur`。
            // 放在 NavDisplay 外层 ⇒ 一处覆盖所有页面。
            val dialogBlur = remember { DialogBlurController() }
            DialogBlurHost(controller = dialogBlur) {
            PixFoldNavHost(
                backStack = backStack,
                predictiveBackAnimation = settings.predictiveBackAnimation,
                style = NavTransitionStyle.Page,
                popTransition = { hostSize, swipeEdge ->
                    // Nav3 不同实现里 initialState/targetState 的方向不一定顺读,
                    // 两侧都查,避免返回配方落到 fallback 淡出。
                    // Scene.key 是场景标识,不是路由键;路由类由 NavEntry.metadata 带出来。
                    val involvedKeys = setOf(
                        initialState.entries.last().metadata[ROUTE_CLASS_METADATA],
                        targetState.entries.last().metadata[ROUTE_CLASS_METADATA],
                    )
                    when {
                        SettingsRoute::class in involvedKeys -> settingsSlidePop(swipeEdge)
                        OrganizeRoute::class in involvedKeys ->
                            workflowShrinkPop(organizeCardBounds, hostSize)
                        LibraryRoute::class in involvedKeys ->
                            workflowShrinkPop(libraryCardBounds, hostSize)
                        else -> rootFadePop()
                    }
                },
            ) {
                composable<HomeRoute> {
                    // 首页两支入口的统一处理：懒启动的 Idle 态（只恢复了句柄、还没扫）
                    // 先触发加载并记下去向，扫描成功后由上面的 LaunchedEffect 补导航；
                    // 其余状态（Ready / 旧路径）照旧立刻进入。
                    val openWorkflow: (NavKey) -> Unit = { target ->
                        val idle = workspaceState as? WorkspaceState.Idle
                        if (idle != null) {
                            pendingWorkflow = target
                            workspaceController.load(idle.handle)
                        } else {
                            backStack.add(target)
                        }
                    }

                    // 用户 2026-10-14 裁决:「默认返回到主页改为触发重置。**只有工作流内部切换不会自动重置**」。
                    //
                    // **为什么复位挂在「回到主页」这一侧,而不是 `OrganizeRoute` 的 `DisposableEffect`**:
                    // 预览页是**根导航的兄弟目的地**(见上方 :100-113),`navigate(PreviewRoute)` 一样会让
                    // `OrganizeRoute` 离开组合 —— 在离场时复位,预览读到的 `PageOrderState` 会当场变成
                    // 自动序,点开的图与网格对不上(正是 2026-09-25 修过的那类缺陷)。
                    // 挂在 `HomeRoute` 上则只在"真的回到主页"时触发;首次启动时复位到初值是幂等的。
                    //
                    // 复位三样:页序/排序规则(`organizeState`)、命名结构与覆盖(`namingState`)、
                    // 当前集合(`activeCollectionId`)。工作流 B 的共享状态全在 `LibrarySteps`
                    // 内部自持,离开组合即自然归零,无需在此处写第二遍。
                    LaunchedEffect(Unit) {
                        activeSession.resetWorkflowSessions(
                            workspaceKey = readyWorkspaceHandle?.treeUri,
                            rootPath = libraryRootPath,
                            collections = collections,
                            baseVolumes = baseVolumes,
                        )
                    }
                    DestinationSurface {
                        HomeScreen(
                            onOpenOrganize = { openWorkflow(OrganizeRoute) },
                            onOpenLibrary = { openWorkflow(LibraryRoute) },
                            onOrganizeCardBounds = { organizeCardBounds = it },
                            onLibraryCardBounds = { libraryCardBounds = it },
                            onOpenSettings = { backStack.add(SettingsRoute) },
                            // 工作区条 + 入口准入（2026-10-24 用户裁决 乙/甲/乙）。
                            //
                            // **选完停首页，不自动跳**（裁决 2 = 甲）：两条工作流是平级任务，
                            // 扫描完就替用户跳进某一条等于替他决定了要做什么。选完这条会变成
                            // 「当前工作区：xxx [更换]」，用户自己点进去。
                            workspaceState = workspaceState,
                            onPickWorkspace = { pickWorkspaceFolder.launch(null) },
                            onOpenWorkspaceList = openWorkspaceList,
                        )
                    }
                }

                composable<SettingsRoute> {
                    DestinationSurface {
                        SettingsScreen(
                            settings = settings,
                            onChange = updateSettings,
                            onBack = { backStack.popOne() },
                            onOpenLicenses = { backStack.add(LicensesRoute) },
                        )
                    }
                }

                composable<LicensesRoute> {
                    DestinationSurface {
                        LicensesScreen(onBack = { backStack.popOne() })
                    }
                }

                composable<OrganizeRoute> {
                    // 完成通知的文案在**组合期**取：
                    // 点击回调里 `context.getString(...)` 会撞 lint
                    // `LocalContextGetResourceValueCall`（回调捕获的 context 可能带着
                    // 旧 Configuration，语言切换后拿到过时译文）。
                    val organizeDoneTitle = stringResource(R.string.organize_notify_done)
                    val workspaceLabel = workspaceHandle?.displayName.orEmpty()
                    val resources = LocalResources.current
                    // 真实改名 / 撤销的平台端口（D4b）。两者**必须成对**注入：
                    // 真实报告靠 `ExecEntry.uri` 支撑撤销，模拟报告里它是 null。
                    //
                    // 没有工作区时（演示数据）不注入 —— mock 的 `SourceItem.uri` 全是
                    // null，真实改名会逐项报「缺少文件标识」。这时走 D1 模拟才是对的：
                    // 界面明确显示「未选择工作区」，用户不会误以为动过磁盘。
                    val renameActions = remember(packTreeUri) {
                        packTreeUri?.let { SafRenameActions(context) }
                    }
                    val undoActions = remember(packTreeUri) {
                        packTreeUri?.let { SafUndoActions(context) }
                    }
                    DestinationSurface {
                        OrganizeSteps(
                            collections = collections,
                            // 步骤级返回同样跟随手势进度:开关透传进工作流子 NavDisplay 的转场策略。
                            predictiveBackAnimation = settings.predictiveBackAnimation,
                            onOpenPreview = { index -> backStack.add(PreviewRoute(index)) },
                            onActiveCollectionChange = { activeSession.activeCollectionId = it },
                            // ⚠️ 必须回传当前集合:打开预览会让本目的地离开组合,
                            // 内部的 `remember { activeId }` 随之销毁;关闭预览回来是
                            // 新的组合实例,不给种子就会退回首个集合(2026-09-25 修复)。
                            initialActiveCollectionId = currentCollectionId,
                            externalState = organizeState,
                            onStateChange = { activeSession.organizeState = it },
                            initialNamingState = namingState,
                            onNamingStateChange = { activeSession.namingState = it },
                            // 网格默认列数(设置在设置页;排序页按屏幕方向二选一,
                            // 工具栏列的临时覆盖不回写这里)。
                            portraitDefaultColumns = settings.portraitDefaultColumns,
                            landscapeDefaultColumns = settings.landscapeDefaultColumns,
                            // 工作区条（D2a）：页签行上方的从属信息层。
                            // HANDBOOK §4（2026-10-15 用户裁决）把它定为「范围」而非「任务」，
                            // 故不做成第 4 个页签；选目录入口也长在工作流内部，不进首页。
                            workspaceState = workspaceState,
                            onPickWorkspace = { pickWorkspaceFolder.launch(null) },
                            onOpenWorkspaceList = openWorkspaceList,
                            // 「整理图片」结束 → 发一条完成通知（用户 m09108）。
                            //
                            // 走 `TaskCompletionNotification.post`：那条渠道**不静音**，
                            // 声音与震动由系统设置决定。进度通知用的 `pack_task_live`
                            // 是强制静音的，两者必须分开（同一个渠道无法同时做到
                            // 「进度不响」和「完成要响」）。
                            //
                            // 文案在**组合期**取好（`context.getString` 在点击回调里读
                            // 资源会撞 lint `LocalContextGetResourceValueCall`：那条回调
                            // 捕获的是旧 Configuration）。
                            onTaskCompleted = { finished ->
                                TaskCompletionNotification.post(
                                    context = context,
                                    id = TaskCompletionNotification.ORGANIZE_NOTIFICATION_ID,
                                    smallIcon = R.drawable.ic_pack_progress,
                                    title = resources.getString(R.string.organize_notify_done),
                                    body = resources.getString(
                                        R.string.task_done_summary,
                                        workspaceLabel,
                                        finished.succeededCount,
                                        finished.skippedCount,
                                        finished.failedCount,
                                    ),
                                )
                            },
                            renameActions = renameActions,
                            undoActions = undoActions,
                        )
                    }
                }

                composable<PreviewRoute> { route ->
                    // 预览是最内层:返回即关闭预览,回到工作流 A。
                    // (ImagePreview 内部也有自己的关闭按钮;二者语义一致。)
                    // Navigation3 的目的地 lambda 直接收到 route key,无需 toRoute。
                    // 空列表时（用户选了空目录）没有可预览的图 —— 直接不渲染内容。
                    // 注意这里**不能**用 `?: collections.first()`：空目录是合法状态，
                    // 抛 NoSuchElementException 会让预览页崩掉。
                    val activeCollection = collections.firstOrNull { it.id == currentCollectionId }
                        ?: collections.firstOrNull()
                    if (activeCollection == null) {
                        DestinationSurface { }
                        return@composable
                    }
                    // ⚠️ 必须用**当前页序**,不能用 `collection.images`(声明序)。
                    // 点击回传的 `route.index` 是页序下标(见 SortAndPreviewPage 传
                    // `items = order.order`);两者只在"页序 == 声明序"时碰巧一致,
                    // 而 `cn` 集合的声明序是故意打乱的 —— 用声明序会打开另一张图
                    // (2026-09-25 修复,由 AppNavigationTest 钉住)。
                    val pageOrder = organizeState.orders[activeCollection.id]?.order
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
                            onClose = { backStack.popOne() },
                            imageSource = previewImageSource,
                            // 重命名写回**命名页同一个** `namingState.overrides`
                            // (键 = SourceItem.id,值 = 完整文件名,空值即撤销覆盖),
                            // 故两处改名互相同步 —— 不是两份状态。
                            rename = PreviewRename(
                                overrides = namingState.overrides,
                                finalNameOf = { item -> proposals[item.id]?.finalName ?: item.fileName },
                                onRename = { item, name ->
                                    activeSession.namingState = setOverride(activeSession.namingState, item.id, name)
                                },
                            ),
                        )
                    }
                }

                composable<LibraryRoute> {
                    // 懒触发卷探测：只有进入 LibraryRoute 且工作区 Ready 时才切 IO 线程探测，
                    // 离开 LibraryRoute 时取消在途探测，确保首页 / 整理页零 SAF/ZIP I/O。
                    LaunchedEffect(
                        readyWorkspaceHandle,
                        baseVolumes,
                        outputExistsProbe,
                        existingComicInfoProbe,
                    ) {
                        val handle = readyWorkspaceHandle ?: return@LaunchedEffect
                        val uri = handle.treeUri.toUri()
                        val rootName = handle.displayName
                        volumeLoader.requestProbe(
                            workspaceKey = handle.treeUri,
                            rootPath = libraryRootPath,
                            baseVolumes = baseVolumes,
                        ) { vols ->
                            // 生产路径：单次批量会话共享目录列举缓存（每唯一目录仅 1 次 SAF query）。
                            // 测试注入路径：按需懒构造 session，两个探针都注入时完全不碰 SAF。
                            val session by lazy {
                                SafVolumeProbeSession(
                                    context = context,
                                    treeUri = uri,
                                    dirOfVolume = { vol -> outputDirRelOf(vol.outputDir, rootName) },
                                )
                            }
                            if (outputExistsProbe == null && existingComicInfoProbe == null) {
                                session.probe(vols)
                            } else {
                                val exists = outputExistsProbe ?: session
                                val info = existingComicInfoProbe ?: session
                                applyExistingComicInfo(
                                    volumes = markOutputExists(vols, exists),
                                    probe = info,
                                )
                            }
                        }
                    }
                    DisposableEffect(volumeLoader) {
                        onDispose { volumeLoader.cancelProbe() }
                    }
                    DestinationSurface {
                        LibrarySteps(
                            rootPath = libraryRootPath,
                            volumes = libraryVolumes,
                            // 同上:步骤级返回跟随手势进度。
                            predictiveBackAnimation = settings.predictiveBackAnimation,
                            // 工作区条 + 空态:与工作流 A 同一份状态(用户裁决「复用工作区」),
                            // 故在两处切目录互相同步。
                            workspaceState = workspaceState,
                            onPickWorkspace = { pickWorkspaceFolder.launch(null) },
                            onOpenWorkspaceList = openWorkspaceList,
                            // 真实写入器。null 只在「没有工作区」时出现 —— 但那时
                            // `scannedVolumes` 已回退到 mock,模拟执行反而更诚实
                            // (不该往真实磁盘写一批来自 mock 路径的 CBZ)。
                            // **必须 `remember`**（2026-10-20 真实缺陷）：不 remember 时
                            // 每次组合都新建一个 `SafPackExecutor`，而下层
                            // `LaunchedEffect(..., packExecutor)` 会把「换了个新对象」当成
                            // 「条件变了」⇒ 预检无限重启，每次都先把结论清空再被取消，
                            // 界面上永远看不到结果（真机表现为「4.7 G 的单本本该提示
                            // Zip64，计划页却干干净净」）。
                            packExecutor = remember(packTreeUri, workspaceHandle?.displayName) {
                                packTreeUri?.let { uri ->
                                    SafPackExecutor(
                                        context = context,
                                        treeUri = uri,
                                        rootDisplayName = workspaceHandle?.displayName.orEmpty(),
                                    )
                                }
                            },
                            // ---- 前台服务（D2b 第 2 项「切后台恢复」）----
                            // 只在有工作区时开启：没有工作区时 `volumes` 是 mock，
                            // 交给服务往真实磁盘写 mock 路径的 CBZ 是错的（服务也没有
                            // 可用的 tree URI）。故无工作区时保持进程内模拟路径。
                            backgroundPacking = packTreeUri != null,
                            interruptedNotice = interruptedNotice,
                            onDismissInterruptedNotice = { interruptedSnapshot = null },
                            // 点通知进来 ⇒ 直接落到步骤 3（2026-10-20 真机缺陷）。
                            // 只在「本次是从通知启动」时为 true；正常从首页进入不受影响。
                            // 用 `startAtPack` 而非 `openPackOnStart`：后者已被上层
                            // 消费清零（见顶部 `LaunchedEffect`），下层要靠这个留在 true。
                            startAtPackStep = startAtPack,
                            onStartAtPackStepHandled = { startAtPack = false },
                            // 结构警告（2026-10-20 裁决第 ③ 条）：系列下出现嵌套目录。
                            // 从**扫描结果**现算 —— 刷新后嵌套层被用户改掉,警告随之消失,
                            // 不缓存。无工作区时 collections 是 mock ⇒ 不会误报
                            // (mock 结构没有嵌套;且没有工作区时用户本来也看不到库)。
                            workspaceWarnings = remember(collections) {
                                nestedDirWarnings(collections)
                            },
                            // 工作流 C（2026-10-26）。null = 没有工作区 ⇒ 两个入口都不渲染。
                            metaUpdater = resolvedMetaUpdater,
                            // 工作流 B 未保存编辑与当前卷回写会话，配置变更重建后保留（P1 #7）。
                            initialActiveVolumeId = activeSession.activeLibraryVolumeId,
                            onActiveVolumeChange = { activeSession.activeLibraryVolumeId = it },
                            onVolumesChange = { volumeLoader.applyUserEdit(it) },
                            onResetVolumes = { volumeLoader.resetUserEdits() },
                        )
                    }
                }
            }
            // ---- 工作区列表弹窗（2026-10-25 规格 §7.2/§7.3）----
            //
            // **为什么挂在这里（DialogBlurHost 内、NavDisplay 之后）**：
            //  1. 与页面切换解耦 —— 写进某个页面的话，换页/重建会把弹窗连开关一起丢掉，
            //     用户点「添加文件夹」拉起系统选择器回来就看不到列表了；
            //  2. 仍在 `CompositionLocalProvider(LocalThumbnailSource)` 内，与页面同处一层
            //     主题/缩略图上下文，不会出现「弹窗里取不到主题色」；
            //  3. 与全站弹窗同一套模糊宿主（`DialogBlurHost`），背景虚化自动生效。
            //
            // **必须用 `if (showWorkspaceList) { … }` 包裹，不许改成常驻组合（R24）**：
            // `WorkspaceListDialog` 内部 `releaseGrant` 是 `remember`，只在**离开组合**时
            // 销毁；常驻会让「移除顺便撤销授权」这种不可逆意图残留在下一次打开里。
            if (showWorkspaceList) {
                WorkspaceListDialog(
                    listings = workspaceController.listings,
                    currentTreeUri = workspaceHandle?.treeUri,
                    onDismiss = { showWorkspaceList = false },
                    onOpen = { treeUri ->
                        showWorkspaceList = false
                        workspaceController.open(treeUri)
                    },
                    onRemove = { treeUri, alsoRelease ->
                        workspaceController.remove(treeUri, alsoRelease)
                    },
                    onTogglePin = { treeUri -> workspaceController.togglePin(treeUri) },
                    // 弹窗给的是**整表下标**（两分区拼起来的顺序），而
                    // `WorkspaceController.move` 收的是 treeUri —— 换算在这里做。
                    // **只读一次 `listings` 快照（R25）**：读两次的话，两次读之间若被后台
                    // 刷新（`listings` 是 `mutableStateOf`，页面随时可能刷新）改过，
                    // from/to 就会指向不同的表，拖错行。
                    onMove = { from, to ->
                        val all = workspaceController.listings
                        if (from in all.indices && to in all.indices) {
                            workspaceController.move(
                                all[from].entry.handle.treeUri,
                                all[to].entry.handle.treeUri,
                            )
                        }
                    },
                    onAdd = { pickWorkspaceFolder.launch(null) },
                    // 全钉住时「加不进」到不了 pick()，得在弹窗里说清楚为什么。
                    rejectHint = if (workspaceController.listRejected) rejectAllPinnedHint() else null,
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
