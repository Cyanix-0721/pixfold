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
import androidx.core.net.toUri
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.comic.comicVolumesFrom
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.model.effectiveScheme
import com.cyanix.pixfold.domain.naming.buildProposals
import com.cyanix.pixfold.domain.naming.namingSchemeSeedOf
import com.cyanix.pixfold.domain.naming.setOverride
import com.cyanix.pixfold.domain.workflowa.WorkflowAState
import com.cyanix.pixfold.domain.workflowa.reconcileNamingState
import com.cyanix.pixfold.domain.workflowa.reconcileWorkflowAState
import com.cyanix.pixfold.domain.workflowa.workflowAStateOf
import com.cyanix.pixfold.data.SharedPrefsSettingsStore
import com.cyanix.pixfold.data.saf.AndroidSafSource
import com.cyanix.pixfold.data.saf.AndroidThumbnailSource
import com.cyanix.pixfold.data.saf.SafOutputExistsProbe
import com.cyanix.pixfold.data.saf.SafPackExecutor
import com.cyanix.pixfold.domain.workflowb.WorkflowBState
import com.cyanix.pixfold.domain.workflowb.reconcileLibrary
import com.cyanix.pixfold.ui.workspace.CachingThumbnailSource
import com.cyanix.pixfold.domain.settings.AppLanguage
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.SettingsStore
import com.cyanix.pixfold.nav.DestinationSurface
import com.cyanix.pixfold.nav.HomeRoute
import com.cyanix.pixfold.nav.LicensesRoute
import com.cyanix.pixfold.nav.NavTransitionStyle
import com.cyanix.pixfold.nav.PixFoldNavHost
import com.cyanix.pixfold.nav.PreviewRoute
import com.cyanix.pixfold.nav.SettingsRoute
import com.cyanix.pixfold.nav.WorkflowARoute
import com.cyanix.pixfold.nav.WorkflowBRoute
import com.cyanix.pixfold.ui.HomeScreen
import com.cyanix.pixfold.ui.preview.ImagePreview
import com.cyanix.pixfold.ui.preview.PreviewRename
import com.cyanix.pixfold.ui.settings.LicensesScreen
import com.cyanix.pixfold.ui.settings.SettingsScreen
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.workspace.LocalThumbnailSource
import com.cyanix.pixfold.ui.workspace.WorkspaceController
import com.cyanix.pixfold.ui.workspace.collectionsOr
import com.cyanix.pixfold.ui.workspace.handleOrNull
import com.cyanix.pixfold.ui.workflowa.WorkflowASteps
import com.cyanix.pixfold.ui.workflowb.WorkflowBSteps
import java.util.concurrent.atomic.AtomicBoolean

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

    // ---- 工作区（D2a）：真实 SAF 目录授权 ----
    //
    // 三层各司其职，不要合并：
    //   AndroidSafSource    —— 平台层，DocumentsContract 递归遍历 + readBytes
    //   WorkspaceController —— 协程编排 + UI 状态机（扫描在 Dispatchers.IO）
    //   本函数              —— 把结果接到工作流 A 的数据源上
    val workspaceSource = remember { AndroidSafSource(context) }
    // 平台实现只管读；缓存与并发闸门在装饰器里（详见 CachingThumbnailSource 的 KDoc）。
    val thumbnailSource = remember {
        CachingThumbnailSource(AndroidThumbnailSource(context))
    }
    val workspaceScope = rememberCoroutineScope()
    val workspaceController = remember(workspaceSource, workspaceScope) {
        WorkspaceController(
            source = workspaceSource,
            scope = workspaceScope,
            // 显示名要靠 provider query 才能拿到（tree URI 本身不含目录名），
            // 故留在平台层；控制器只接收「URI → 名字」这个纯函数。
            nameResolver = { workspaceSource.displayNameOf(it) },
        )
    }
    // 冷启动恢复：上次授权过的目录自动重扫。**不缓存上次结果** ——
    // 目录内容随时会变，显示过期列表比重新扫一遍更糟。
    LaunchedEffect(Unit) { workspaceController.restore() }
    val workspaceState = workspaceController.state
    // 「当前打开的是哪个工作区目录」的稳定标识。**下游凡是「该不该重置」的判断
    // 都用它，而不是 `collections`**：`collections` 会在同一个目录里随文件增删
    // 而变化（实时刷新），拿它当 key 等于「用户改一下文件就把界面状态全清掉」。
    // 未授权 / 扫描中 / 失败时它是 `null`，那时 `collections` 是 mock ——
    // 对 UI 而言仍是一个完整可用的工作区。
    val workspaceHandle = workspaceState.handleOrNull()
    // 工作区切换后旧 uri 全部不可读，缓存留着只会占内存并让新图被提前淘汰。
    // ⚠️ key 用 handle 不用 `workspaceState`：后者每次刷新都会变一个新实例，
    // 用它当 key 等于「每次都清空缩略图缓存」—— 刷新时满屏重新解码，正是
    // 「刷新不该闪烁」要避免的那种抖动。
    LaunchedEffect(workspaceHandle) { thumbnailSource.clearCache() }

    // ---- 回前台自动刷新（D4「工作区实时刷新」2026-10-19 用户裁决）----
    //
    // 用户原话是「我可能看到图片有问题或者缺失，会用文件管理器删除或添加或重命名
    // 图片，工作区应该实时刷新」。三条触发路径里，这条管的是「用户去别的应用改完
    // 文件再切回来」—— 此时 ContentObserver 的通知往往已经在后台丢失或被子线程
    // 攒着，靠 ON_RESUME 补一次重扫最可靠。
    //
    // ⚠️ **首次 ON_RESUME 必须跳过**：`restore()` 在 `LaunchedEffect(Unit)` 里发出，
    // 而 Activity 首次 RESUMED 与首帧组合的先后**没有保证**。不跳过的话冷启动会
    // 连扫两遍（千级目录下是肉眼可见的卡顿），且第二遍扫完紧接着又触发一次对账。
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
            // `ContentObserver` 是注册在 `ContentResolver` 上的**全局**对象，不注销会
            // 一直活到进程结束；而 Compose 里控制器随组合重建（旋转 / 多窗口），
            // 泄漏会随重建次数累积，且旧观察者还会替已废弃的目录触发刷新。
            workspaceController.close()
        }
    }

    // 选目录：ACTION_OPEN_DOCUMENT_TREE 是**唯一**能拿到可持久化 tree URI 的通道
    // （HANGOFF §4：没有「以 SAF 树形式授权目录」的标准分享通道）。
    val pickWorkspaceFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        // uri == null 表示用户取消了选择 —— 保持现状，不动已有授权。
        if (uri != null) workspaceController.pick(uri.toString())
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
            val navController = rememberNavController()

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
            // probe 的目录解析必须与 `dirOfVolume` 用**同一套**规则,否则「判断存在」
            // 和「实际写入」会看两个不同的目录。规则由 `comicVolumesFrom` 的输出目录
            // 建议值定义:卷目录的父目录;根下一级时回退工作区根的显示名。
            val outputDirOf: (com.cyanix.pixfold.domain.model.ComicVolume) -> String = { volume ->
                volume.outputDir
            }
            val scannedVolumes = remember(collections, packTreeUri, workspaceHandle?.displayName) {
                if (packTreeUri == null) {
                    // 没有可用工作区 ⇒ 退回演示数据。这不是「悄悄塞假数据」:
                    // 界面顶部的工作区条同时显示「未选择工作区」,用户看得见自己在
                    // 哪个状态,与工作流 A 的 `collectionsOr` 是同一套回退口径。
                    MockDataB.library.volumes
                } else {
                    val probe = SafOutputExistsProbe(
                        context = context,
                        treeUri = packTreeUri,
                        dirOfVolume = outputDirOf,
                    )
                    comicVolumesFrom(
                        collections = collections,
                        rootDisplayName = workspaceHandle?.displayName.orEmpty(),
                        outputExists = probe,
                    )
                }
            }
            val libraryRootPath = workspaceHandle?.displayName
                ?: MockDataB.library.rootPath
            // 工作流 B 也有「实时刷新 + 增量对账」(与 A 同构):用户在文件管理器里
            // 增删图片/目录后,卷列表会重扫;重扫**不能**清掉用户填好的元数据与勾选。
            // 对账责任在这一层 —— `WorkflowBSteps` 只负责不缓存(见其 KDoc)。
            var workflowBVolumes by remember(workspaceHandle) {
                mutableStateOf(scannedVolumes)
            }
            val lastReconciledB = remember(workspaceHandle) { LastReconciledVolumes(scannedVolumes) }
            if (lastReconciledB.volumes != scannedVolumes) {
                lastReconciledB.volumes = scannedVolumes
                workflowBVolumes = reconcileLibrary(
                    previous = WorkflowBState(
                        rootPath = libraryRootPath,
                        volumes = workflowBVolumes,
                        activeVolumeId = "",
                    ),
                    scanned = scannedVolumes,
                ).volumes
            }
            // 预览跟随**当前集合**:切集合后打开预览应看到该集合的图,
            // 否则页码与图片会对不上(索引来自当前集合)。
            //
            // ⚠️ key 用 `workspaceHandle` 而不是 `collections`（2026-10-19 改，理由
            // 见下方「工作流 A 的两份共享状态」处同一段说明）：同一个工作区刷新出
            // 新列表时，不该把用户正在看的集合重置回第一个。
            var activeCollectionId by remember(workspaceHandle) {
                mutableStateOf(collections.firstOrNull()?.id.orEmpty())
            }
            // 刷新后原先选中的集合**可能已被删掉**（用户删了这个子目录）。
            // 派生一个「当前真正可用的集合 id」供下游使用：
            //  - 仍存在 ⇒ 原样返回（刷新不该把用户从正在看的集合拽走）；
            //  - 已消失 ⇒ 退回第一个；空目录时是空串。
            // **不在组合期直接给 `activeCollectionId` 赋值**：那等于在组合中写快照
            // 状态，Compose 会把本轮判为失效再跑一遍；而且「先渲染旧 id、下一帧才修」
            // 的那一帧里，子组件可能已经把索引记成空（预览打不开第一张图）。
            val currentCollectionId = collections.firstOrNull { it.id == activeCollectionId }?.id
                ?: collections.firstOrNull()?.id.orEmpty()
            // 把修正结果回写进状态：否则用户下一次手动切集合时，
            // `onActiveCollectionChange` 会以那个已消失的 id 为起点，界面看起来「没反应」。
            LaunchedEffect(collections) {
                if (activeCollectionId != currentCollectionId) {
                    activeCollectionId = currentCollectionId
                }
            }

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
            // 工作流 A 的两份共享状态提升到应用根(2026-09-25)—— 理由见上方注释 1./2.
            //
            // ⚠️ **key 为什么是「工作区句柄」而不是「集合列表」（2026-10-19 改）**：
            //
            // 用户裁决「工作区应该实时刷新」，于是集合列表会在**同一个工作区里**变化
            // （他在文件管理器里删了图 / 加了图）。若 key 仍是 `collections`，那次变化
            // 会把这个 `remember` 重建 ⇒ 用户刚拖好的顺序、刚改好的文件名全被清掉。
            // 反过来，key 用 handle 就只区分「换了目录」(必须全重置 —— 旧集合 id 对新
            // 目录毫无意义) 与「同一目录内容变了」(用对账保留人工调整)。
            //
            // 对账必须**在组合期同步完成**，不能放进 `LaunchedEffect`：子组件
            // `WorkflowASteps` 自己也是 `remember(collections)` 播种本地状态,而它的
            // 初值来自这里的 `externalState`。若先以旧状态组合子组件、事后再用副作用
            // 更新，子组件的 key 已经消费过 ⇒ 它不会重新播种 ⇒ 对账结果在界面上看不见
            // (预览页读上层那份反而是对的，两处会不一致)。写在子组件之前则同一次组合
            // 就能拿到对账后的值。
            var workflowAState by remember(workspaceHandle) {
                mutableStateOf(workflowAStateOf(collections))
            }
            var namingState by remember(workspaceHandle) {
                mutableStateOf(
                    NamingState(
                        namingSchemeSeedOf(collections),
                    ),
                )
            }
            // 上一次对账所依据的集合列表。刻意用**普通字段**而不是 `mutableStateOf`：
            // 它只在组合期读写，写成快照状态会让「读它的这段组合」被自己刚才的写入
            // 判为失效、白白多重跑一次组合（刷新在千级目录下不算便宜）。
            val lastReconciled = remember(workspaceHandle) { LastReconciled(collections) }
            if (lastReconciled.collections != collections) {
                // `!=` 而不是 `!==`：控制器已保证「内容没变不发新实例」，但这里再用
                // 结构相等兜一层 —— 等值的新实例不该白跑一次对账。
                lastReconciled.collections = collections
                workflowAState = reconcileWorkflowAState(workflowAState, collections)
                namingState = reconcileNamingState(namingState, collections)
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
                                namingSchemeSeedOf(collections),
                            )
                        activeCollectionId = collections.firstOrNull()?.id.orEmpty()
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
                            initialActiveCollectionId = currentCollectionId,
                            externalState = workflowAState,
                            onStateChange = { workflowAState = it },
                            initialNamingState = namingState,
                            onNamingStateChange = { namingState = it },
                            // 网格默认列数(设置在设置页;排序页按屏幕方向二选一,
                            // 工具栏列的临时覆盖不回写这里)。
                            portraitDefaultColumns = settings.portraitDefaultColumns,
                            landscapeDefaultColumns = settings.landscapeDefaultColumns,
                            // 工作区条（D2a）：页签行上方的从属信息层。
                            // HANGOFF §4（2026-10-15 用户裁决）把它定为「范围」而非「任务」，
                            // 故不做成第 4 个页签；选目录入口也长在工作流内部，不进首页。
                            workspaceState = workspaceState,
                            onPickWorkspace = { pickWorkspaceFolder.launch(null) },
                        )
                    }
                }

                composable<PreviewRoute> { entry ->
                    // 预览是最内层:返回即关闭预览,回到工作流 A。
                    // (ImagePreview 内部也有自己的关闭按钮;二者语义一致。)
                    val route = entry.toRoute<PreviewRoute>()
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
                            rootPath = libraryRootPath,
                            volumes = workflowBVolumes,
                            // 同上:步骤级返回跟随手势进度。
                            predictiveBackAnimation = settings.predictiveBackAnimation,
                            // 工作区条 + 空态:与工作流 A 同一份状态(用户裁决「复用工作区」),
                            // 故在两处切目录互相同步。
                            workspaceState = workspaceState,
                            onPickWorkspace = { pickWorkspaceFolder.launch(null) },
                            // 真实写入器。null 只在「没有工作区」时出现 —— 但那时
                            // `scannedVolumes` 已回退到 mock,模拟执行反而更诚实
                            // (不该往真实磁盘写一批来自 mock 路径的 CBZ)。
                            packExecutor = packTreeUri?.let { uri ->
                                SafPackExecutor(
                                    context = context,
                                    treeUri = uri,
                                    rootDisplayName = workspaceHandle?.displayName.orEmpty(),
                                )
                            },
                            // ---- 前台服务（D2b 第 2 项「切后台恢复」）----
                            // 只在有工作区时开启：没有工作区时 `volumes` 是 mock，
                            // 交给服务往真实磁盘写 mock 路径的 CBZ 是错的（服务也没有
                            // 可用的 tree URI）。故无工作区时保持进程内模拟路径。
                            backgroundPacking = packTreeUri != null,
                            interruptedNotice = interruptedNotice,
                            onDismissInterruptedNotice = { interruptedSnapshot = null },
                        )
                    }
                }
            }
            }
        }
    }
}

/**
 * 「上一次对账依据的集合列表」的**可变容器**（不是快照状态）。
 *
 * **为什么不用 `mutableStateOf`**：对账这段逻辑在**组合期**同步执行 ——
 * 它读上一份列表、必要时写入新列表并立刻改 `workflowAState`。若把「上一份列表」
 * 写成快照状态，那么这次的写入会让「正在读它的这段组合」自己也失效，
 * 于是刚算完又重跑一遍（千级目录下 `reconcileWorkflowAState` 是 O(n) 带排序的，
 * 白跑一次很显眼）。用普通可变字段则只有 `workflowAState` 的写入触发重组，
 * 而那次重组读到的「上一份列表」已经等于当前列表，不会重复对账。
 *
 * 容器本身仍需 `remember`：它要跨重组存活，且随工作区句柄一起重建。
 */
private class LastReconciled(var collections: List<ImageCollection>)

/**
 * 工作流 B 的对账基准（**普通可变字段**，不是 `mutableStateOf`）。
 *
 * 为什么用普通字段：它只用来回答「这一帧的扫描结果跟上一帧是不是同一份」，答案
 * 不该参与重组；写成 state 会多触发一轮与界面无关的重组，而且会让「记录基准」
 * 这个动作本身被 Compose 的写-读追踪当成状态变更。
 *
 * 为什么用 `!=` 而不是引用比较：`comicVolumesFrom` 每次扫描都新建列表，
 * 引用永远不等 ⇒ 每次重组都对账一次，把用户正在改的元数据反复重算。
 *
 * 与工作流 A 的 [LastReconciled] 分开两个类而非泛型化：两者记录的元素类型不同
 * （`ImageCollection` / `ComicVolume`），合并成一个泛型类只会多一层类型参数
 * 而不减少任何逻辑。
 */
private class LastReconciledVolumes(var volumes: List<ComicVolume>)

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
