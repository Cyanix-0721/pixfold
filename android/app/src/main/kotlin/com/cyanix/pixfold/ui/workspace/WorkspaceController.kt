package com.cyanix.pixfold.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cyanix.pixfold.domain.source.AndroidGrantReleaser
import com.cyanix.pixfold.domain.source.FailureCause
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceChangeWatcher
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceListing
import com.cyanix.pixfold.domain.source.WorkspaceSource
import com.cyanix.pixfold.domain.source.movedOrder
import com.cyanix.pixfold.domain.source.orderedForDisplay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 工作区加载的状态机（D2a）。
 *
 * ## 为什么单独抽一个类，而不是全塞进 `PixFoldApp`
 *
 * 这里有三件必须**按顺序**发生的事：解析授权 → 阻塞扫描（`Dispatchers.IO`）→ 回主线程
 * 落状态。写在一个 `@Composable` 的局部函数里会同时碰到：状态读写在组合期、
 * 协程作用域生命周期、并发扫描互斥。抽出来之后 `PixFoldApp` 只剩「把它记住 + 读它的 state」。
 *
 * ## 并发
 *
 * [load] 会先取消上一次扫描（[job]）。用户连点「更换」时，旧扫描的结果绝不能后到
 * 覆盖新扫描的结果 —— 否则换到目录 B 却看到目录 A 的内容。取消点靠 `withContext`
 * 的协程取消语义；`AndroidSafSource` 的阻塞 query 无法中途打断，但**结果会被丢弃**
 * （协程已取消，`withContext` 返回时抛 `CancellationException`，不会走到状态赋值）。
 *
 * ## 为什么不是 `ViewModel`
 *
 * `ViewModel` 的价值是「配置变更（旋转）后存活」。本类持有的状态只影响工作流 A 的
 * 数据来源，而**目录授权本身持久化在 SAF 里**（[SafTreeStore] 对应的
 * `WorkspaceSource.workspaces()` 的第一条）—— 旋转后重建会重新 `restore()` 一次，
 * 结果相同。为一个可重建的状态引入 `lifecycle-viewmodel-compose` 依赖不划算。
 *
 * @param nameResolver 由 tree URI 解析出目录显示名（平台层做，需 `query`）。
 *   抽成参数是为了让本类**不碰 Android 类型**，可以在纯 JVM 里测。
 * @param ioDispatcher 阻塞 IO 的调度器。抽成参数同样是为了可测：测试传
 *   `UnconfinedTestDispatcher`，否则 `WithContext(Dispatchers.IO)` 会把执行甩到真实线程池，
 *   `runTest` 的虚拟时间控制不到它，测试只能靠 sleep 等 —— 那是最脆的一类测试。
 */
class WorkspaceController(
    private val source: WorkspaceSource,
    private val scope: CoroutineScope,
    private val nameResolver: (treeUri: String) -> String = { "" },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** 当前状态。界面直接读它（`by` 委托，读在组合期会自动订阅）。 */
    var state: WorkspaceState by mutableStateOf(WorkspaceState.None)
        private set

    /**
     * 是否正在**刷新已有内容**（区别于 [load] 的首次/换目录扫描）。
     *
     * 为什么单独暴露而不是复用 `state is Loading`：刷新期间**必须保留旧列表**。
     * 退回 `Loading` 会让界面先清空再重建，千级缩略图列表会肉眼可见地闪一下，
     * 而刷新只是一次「看看有没有新东西」，内容往往根本没变。界面可据此在
     * 角落显示一个细进度条，而不动主列表。
     */
    var isRefreshing: Boolean by mutableStateOf(false)
        private set

    /**
     * 列表快照。**含 granted 运行时判定**，每次 [openList] 与任何增删改后重算。
     *
     * 不缓存 `granted`：持久授权可能在应用外被撤销（用户在系统设置里删了），
     * 我们收不到任何通知。缓存它等于显示一个过期的 ✓。
     *
     * **分区先于图钉**：列表分「已持久授权」与「只记住了路径」两段，上段整体在前；
     * 图钉的稳定排序只在**每段内部**生效（见 [refreshListings]）。把两者混成一条排序键，
     * 就等于允许一枚图钉把一条没有授权的记录顶到有授权的那一段里 —— 那是伪造授权。
     */
    var listings: List<WorkspaceListing> by mutableStateOf(emptyList())
        private set

    /**
     * 上一次 [WorkspaceSource.rememberWorkspace] 被「8 条全钉住」拒绝了。
     *
     * UI 据此提示「先取消一个图钉再添加」。**必须是可观察状态而不是返回值**：
     * 拒绝发生在 [pick] 与 [load] 两条路径上，用户看到的却只有一个「没加上」的结果；
     * 界面拿不到一句能解释这句话的信息时，只会让人以为应用吞了操作。
     */
    var listRejected: Boolean by mutableStateOf(false)
        private set

    private var job: Job? = null

    /**
     * 正在 [load] 的那份授权。
     *
     * 为什么需要单独记：`WorkspaceState.Loading` **不带 handle**，而 [remove] 必须回答
     * 「用户删掉的这条是不是正在加载的那条」。没有它，扫描期间删掉目录会让扫描继续
     * 跑完并把一个已经不在列表里的目录落成 Ready。
     *
     * 只在 [loadingHandleOrNull] 里、且仅当状态是 Loading 时才读 —— 陈旧值因此无害。
     */
    private var loadingHandle: WorkspaceHandle? = null

    /** 当前挂着的变更监听；换工作区 / 忘记 / 关闭时必须先关掉它。 */
    private var watcher: WorkspaceChangeWatcher? = null

    /** [watcher] 监听的是哪个工作区 —— 判据是它，而不是 `watcher != null`（见 [watchIfReady]）。 */
    private var watchedHandle: WorkspaceHandle? = null

    /**
     * 刷新进行中又来了新变更时置位。
     *
     * 为什么不直接丢弃：一次扫描要几百毫秒到几秒，扫描**开始之后**发生的删除
     * 不会被这次扫描看到；丢掉后来的通知就意味着那张图要等到下一次刷新才消失。
     * 所以记下来，等本次刷新结束再补一轮（合并成一轮，不会堆积）。
     */
    private var refreshQueued: Boolean = false

    /**
     * 取消代数：每次 [cancelJob] 自增一次。
     *
     * **为什么需要它**：被取消协程的 `finally` 会**晚于**取消动作执行。若它无条件
     * 复位 [isRefreshing]，就会把随后新开那一轮刷新的「刷新中」清掉 —— 界面显示
     * 没在刷新而实际还在扫；更糟的是下一次 `refresh()` 会以为没有在跑，于是又并发
     * 开一轮。用代数比对区分「我这轮是不是还活着」。
     */
    private var generation: Int = 0

    /**
     * 冷启动恢复：上次授权过的工作区就重新扫描一次。
     *
     * **不缓存上次的扫描结果**：目录内容随时会变（用户可能刚删了图），
     * 显示一份过期列表比重新扫一遍更糟。
     */
    fun restore() {
        // 「上次用过的」= 列表最前那条：rememberWorkspace 会把用过的移到最前。
        val handle = source.workspaces().firstOrNull()?.handle ?: return
        load(handle)
    }

    /**
     * 重读列表 + 逐条判定授权。每次打开列表都调 —— 见 [listings] 的 KDoc。
     */
    fun openList() {
        refreshListings()
    }

    /**
     * 从列表里选一条切过去。
     *
     * **只在读到之后才移到最前**：失败就移到最前会让一条打不开的记录反复霸占第一位。
     * 「移到最前」的动作落在 [load] 的成功分支里（一个位置覆盖 restore / pick / open
     * 三个入口，spec §6 第 2 条），这里只负责发起。
     */
    fun open(treeUri: String) {
        val entry = source.workspaces().firstOrNull { it.handle.treeUri == treeUri } ?: return
        load(entry.handle)
    }

    /**
     * 从列表移除一条。
     *
     * [alsoRelease] 为 `true` 才撤销系统持久授权 —— 默认 `false`。
     * 「从列表删掉」与「收回系统授权」是两件事：混成一件会让用户手滑就丢掉授权，
     * 下次还得重新走系统选择器。
     *
     * ## 「是不是当前那条」为什么不能只看 [currentHandle]
     *
     * - [WorkspaceState.Failed] 带 handle（2026-11-08 起）⇒ 能如实判断，与其他状态同口径。
     *   它一度**不带** handle（只有 message），那时「当前是哪条」不可知，只好按「可能正是它」
     *   处理 —— 停在一条可能已被删掉的目录的 Failed 上，是对用户说了一句假话；而旧 `forget()`
     *   正是在这里返 nil，于是**一条都删不掉**（评审 M5），用户点了 ✕ 列表照旧。
     *   `handle == null` 的 Failed 仍按「不可知」处理（冷启动无目录的极端路径 / 测试手搓）。
     * - [WorkspaceState.Denied] 带 handle ⇒ 能如实判断，故**不能**无条件回 [WorkspaceState.None]：
     *   那条工作区条是授权失效时唯一的重选入口（见 `WorkspaceState.showsWorkspaceBar`），
     *   删掉一条无关记录不该把它一起关掉。
     * - [WorkspaceState.Loading] 取不到 handle，改读 [loadingHandle]。
     *
     * 删除动作本身**与「当前是哪条」无关**：入参就是 treeUri。
     */
    fun remove(treeUri: String, alsoRelease: Boolean) {
        val active = currentHandle() ?: loadingHandleOrNull()
        val wasCurrent = active?.treeUri == treeUri

        source.forgetWorkspace(treeUri)
        if (alsoRelease) (source as? AndroidGrantReleaser)?.releasePersistentGrant(treeUri)
        // 评审 Minor-3：「列表满且全钉住」的被拒提示，指的是**上一次** remember 的结论。
        // 用户照着提示删掉一条之后这个前提已经不成立，提示却会一直挂到下次成功 remember
        // 为止（可能再也不发生）—— 所以移除动作本身要把它撤掉。
        listRejected = false
        refreshListings()

        // 列表空了也回 None：目录已不再被记住，界面不能再停在一个凭空的「当前工作区」上。
        // Failed 只在**取不到 handle** 时才算「当前是哪条不可知」——带 handle 的 Failed
        // 能如实判断，删一条无关记录不该顺手把重选入口关掉（见上方 KDoc）。
        val failedUnknown = state is WorkspaceState.Failed && currentHandle() == null
        if (wasCurrent || failedUnknown || listings.isEmpty()) {
            cancelJob()
            closeWatcher()
            state = WorkspaceState.None
        }
    }

    /** 切换图钉。渲染顺序立刻变（钉住的排本区最前），存储位置不动。 */
    fun togglePin(treeUri: String) {
        val entry = source.workspaces().firstOrNull { it.handle.treeUri == treeUri } ?: return
        source.setPinned(treeUri, !entry.pinned)
        refreshListings()
    }

    /**
     * 拖拽提交。入参是 **treeUri**，不是下标 —— 渲染顺序经过 pinned 稳定排序，
     * 与存储下标没有线性对应关系（换算见 `movedOrder`）。
     *
     * **跨分区的拖拽被丢弃**：分区是运行时事实，把一条「只记住了路径」拖进
     * 「已持久授权」区等于伪造授权。判据用「两端授权状态是否一致」而不是「分区序号是否
     * 相同」—— 后者要先把整表分区一遍，两行能算清的判断不该付一次全表遍历。
     *
     * ## `to` 为什么找 `fromUri` 而不是 `toUri`
     *
     * `movedOrder` 的返回值**就是期望的最终序列**；而 `moveWorkspace(from, to)` 的语义是
     * 「删掉 `from` 之后，把这条插到第 `to` 位」。删完就插、其后元素整体后移一位，
     * 所以这条落定后的下标恰好等于它的插入位 —— 即它在 `moved` 里的下标。
     *
     * 换成「在 `moved` 里找 `toUri` 的下标」就会错一格：`[a,b,c]` 上 a→c 时
     * `moved` = `[b,c,a]`，c 的下标是 1，插到 1 位得到 `[b,a,c]`，与期望的 `[b,c,a]` 不符。
     * （brief Step 3 的代码片段与它自己的用例正是这么互相矛盾的，以用例与 spec §4.4 为准。）
     */
    fun move(fromUri: String, toUri: String) {
        val store = source.workspaces()
        val fromGranted = source.isPersistentGrant(fromUri)
        if (fromGranted != source.isPersistentGrant(toUri)) return
        val moved = movedOrder(store, fromUri, toUri) ?: return
        val from = store.indexOfFirst { it.handle.treeUri == fromUri }
        val to = moved.indexOfFirst { it.handle.treeUri == fromUri }
        source.moveWorkspace(from, to)
        refreshListings()
    }

    /**
     * 用户选好了一个目录。
     *
     * @param treeUri `ACTION_OPEN_DOCUMENT_TREE` 返回的树 URI。
     */
    fun pick(treeUri: String) {
        val name = nameResolver(treeUri)
        val handle = WorkspaceHandle(treeUri = treeUri, displayName = name)
        // 先记住授权（takePersistableUriPermission 也在这里做），再扫描。
        // 顺序重要：扫描失败/被取消时，用户下次启动仍应恢复到该目录。
        listRejected = source.rememberWorkspace(handle) == RememberResult.RejectedAllPinned
        load(handle)
    }

    /**
     * 释放本控制器持有的平台资源（变更监听）。
     *
     * 由界面层在 `DisposableEffect` 里调用。**必须存在**：`ContentObserver` 是注册在
     * `ContentResolver` 上的，不注销会一直活到进程结束 —— 而 Compose 里控制器随
     * 组合重建（旋转、多窗口切换），泄漏会随重建次数累积。
     */
    fun close() {
        cancelJob()
        closeWatcher()
    }

    /**
     * 取消在跑的扫描/刷新，并把「刷新中」复位。
     *
     * **为什么不止 `job?.cancel()`**：协程若在启动前就被取消，`launch` 的函数体
     * 根本不会执行 —— 于是 `refresh()` 里的 `try/finally` 也不会跑，[isRefreshing]
     * 会永远停在 `true`，界面一直显示「刷新中」且再也刷不动。故复位在这里做一次。
     */
    private fun cancelJob() {
        job?.cancel()
        job = null
        generation++
        isRefreshing = false
        refreshQueued = false
    }

    /**
     * 扫描 [handle] 指向的工作区。
     *
     * 状态序列：[WorkspaceState.Loading] → [WorkspaceState.Ready] /
     * [WorkspaceState.Denied] / [WorkspaceState.Failed]。
     */
    fun load(handle: WorkspaceHandle) {
        cancelJob()
        // 旧工作区的监听必须先断，否则换目录后它还在替旧目录触发 refresh()。
        closeWatcher()
        state = WorkspaceState.Loading
        loadingHandle = handle
        job = scope.launch {
            try {
                // 阻塞遍历必须离开主线程：851 张实测约 1 秒，千级更久，
                // 留在主线程就是肉眼可见的掉帧甚至 ANR。
                val first = withContext(ioDispatcher) { source.loadCollections(handle) }

                // 授权失效的自动重试（D2b 退出条件 ③「授权失效恢复流程」）。
                //
                // **为什么必须自动重试一次而不是直接报失效**：Cold start 时
                // takePersistableUriPermission 授予的权限可能尚未就绪，表现同样是
                // SecurityException —— 与「用户真的撤销了授权」在平台层无法区分。
                // 不重试的话，用户每次冷启动都会看到一次「授权已失效」，
                // 然后点一下重选又莫名其妙地好了。
                val cause = first.failureCause
                if (cause == null || !shouldAutoRetry(cause, retriedTimes = 0)) {
                    rememberIfLoaded(first, handle)
                    state = first.toWorkspaceState(handle)
                    watchIfReady(handle, first)
                    return@launch
                }

                val second = withContext(ioDispatcher) { source.loadCollections(handle) }
                // 自动重试成功的那一支同样要 remember —— 「冷启动抖一下才读到」的那次
                // 不更新顺序的话，restore 就永远恢复不到上次真正用过的目录。
                rememberIfLoaded(second, handle)
                state = when (second) {
                    is LoadResult.Success -> second.toWorkspaceState(handle)
                    LoadResult.Denied -> WorkspaceState.Denied(handle, autoRetried = 1)
                    is LoadResult.Failed -> second.toWorkspaceState(handle)
                }
                watchIfReady(handle, second)
            } finally {
                // 无论读到与否，列表都可能已经变了（成功时被移到最前）。放在 finally 里
                // 而不是每个分支末尾：`return@launch` 那条也是分支，漏掉就会留下一个
                // 「状态已经是 Ready，列表却还是旧顺序」的界面。
                refreshListings()
            }
        }
    }

    /**
     * 重扫当前工作区，**不清空已有内容**（D4「工作区实时刷新」）。
     *
     * 三条触发路径共用它：回前台（`ON_RESUME`）、手动刷新按钮、
     * `ContentObserver`（用户在文件管理器里改了文件）。
     *
     * 语义：
     * - 状态是 [WorkspaceState.None] / [WorkspaceState.Loading] 时什么都不做 ——
     *   前者没有目录可扫，后者有一轮 [load] 正在跑、插一轮刷新会与它抢同一个 job。
     *   **[WorkspaceState.Failed] 不在此列**（2026-11-08 修）：它现在带着 handle，
     *   一次临时 IO 失败（SD 卡抖动、别的应用占着文件）之后用户改完文件回来、
     *   或手动点刷新，都应当真的重扫一次，而不是永远停在「无法读取该文件夹」。
     * - **不进入 `Loading`**：界面继续渲染旧列表，只在 [isRefreshing] 为真时显示提示。
     * - 结果与当前列表**完全相等时不发新实例**：下游 `remember(collections)` 是全量
     *   重建工作流状态的 key，多发一次新实例就等于让用户的排序/命名被重置一次。
     * - 刷新失败时**不覆盖**已有内容：用户的列表还在，用旧内容比用一片空白好。
     *   唯一的例外是授权真的失效（`Denied`），那必须让用户知道 —— 但也只在
     *   原本是 `Ready` 时降级，避免把「暂时读不到」误报成失效。
     */
    fun refresh() {
        // `Loading` 时 currentHandle() 返回 null：此时 load() 的协程还没落状态，
        // 插一轮刷新会与它抢同一个 job 字段，故直接跳过（load 结束后自然会带上新内容）。
        if (currentHandle() == null) return

        // 已有刷新在跑：记一笔，等它结束后补一轮。两轮会合并，不会堆积。
        if (isRefreshing) {
            refreshQueued = true
            return
        }

        isRefreshing = true
        val myGeneration = generation
        job = scope.launch {
            try {
                do {
                    // 每轮开始先清标记：扫描期间新到的变更会把它重新置位，
                    // 循环条件据此决定要不要补扫一轮。
                    refreshQueued = false
                    val target = currentHandle() ?: break
                    val result = withContext(ioDispatcher) { source.loadCollections(target) }
                    if (!isActive) return@launch
                    applyRefresh(target, result)
                } while (refreshQueued)
            } finally {
                // 只在**我这一代仍是当前代**时复位：被取消的旧协程的 finally 可能
                // 晚于新一轮启动，无条件复位会把新一轮的「刷新中」误清。
                // （「协程在启动前就被取消」那条路径由 [cancelJob] 兜住。）
                if (myGeneration == generation) {
                    isRefreshing = false
                    refreshQueued = false
                }
            }
        }
    }

    /**
     * 把一次刷新的结果落到 [state]。
     *
     * 拆成独立函数是为了让「什么时候发新实例」这条规则集中在一处 ——
     * 它直接决定下游是否会重置用户的人工调整，散在几处迟早漂移。
     */
    private suspend fun applyRefresh(handle: WorkspaceHandle, result: LoadResult) {
        when (result) {
            is LoadResult.Success -> {
                // 刷新读到了 ⇒ 它就是最新的，顺序据此更新（与 [load] 同一口径）。
                // 漏掉这一支的表现是「刷新之后列表顺序回到旧样子」，只在**顺序确实
                // 需要变**（上次启动失败过）时才看得出来，平时完全静默。
                rememberIfLoaded(result, handle)
                // 内容一模一样时**不用手写守卫**：`state` 是 `mutableStateOf`，
                // 默认的 structural equality policy 在 `equals` 相等时不写入，
                // 读到的仍是同一个旧实例（`WorkspaceState.Ready` 是 data class）。
                // 手写 `if (!unchanged)` 只是把同一件事写了两遍 —— 而且它测不出来，
                // 删掉这句变异后所有断言照样绿（变异 M3 存活即证据）。
                state = WorkspaceState.Ready(handle, result.collections)
                watchIfReady(handle, result)
            }
            // 授权失效：按与 [load] 相同的策略给一次自动重试，仍失败才报失效。
            LoadResult.Denied -> {
                val retried = shouldAutoRetry(FailureCause.Transient, retriedTimes = 0)
                val second = if (retried) {
                    withContext(ioDispatcher) { source.loadCollections(handle) }
                } else {
                    null
                }
                if (second is LoadResult.Success) {
                    // 评审 Important-1：重试成功与「首轮就成功」同语义，都是「真的读到了」。
                    // 漏这行时存储顺序不更新 ⇒ 下次 restore() 的 `workspaces().firstOrNull()`
                    // 恢复到**旧**目录，平时完全静默（spec §6 第 2 条）。
                    rememberIfLoaded(second, handle)
                    state = WorkspaceState.Ready(handle, second.collections)
                    // 重试成功说明授权其实还在：监听必须留着重挂，否则这一轮之后
                    // 「实时刷新」就静默失效了（用户会以为功能坏了）。
                    watchIfReady(handle, second)
                } else {
                    state = WorkspaceState.Denied(handle, autoRetried = if (retried) 1 else 0)
                    closeWatcher()
                }
            }
            // 其他失败：**保留旧内容**。刷新是后台行为，用户没主动要求看到错误，
            // 一次临时的读失败就把列表换成错误页，比保留旧数据糟得多。
            is LoadResult.Failed -> Unit
        }
        // 同一处收口：成功那一支会把这条移到最前，失败分支虽不动存储，但授权状态
        // 可能在这次刷新期间被用户在系统设置里撤销 —— 顺手重判，免得显示过期的 ✓。
        refreshListings()
    }

    /**
     * 确保当前工作区挂着变更监听；实现不支持观察（返回 `null`）时静默降级。
     *
     * **同一工作区已经有监听时不重建**。刷新成功会走到这里，若无条件
     * `close` + 重新注册，那么每次刷新都会重挂一次观察者 —— 除了白白多两次
     * binder 调用，更实际的风险是：注销与重注册之间的窗口里到达的变更通知
     * 会直接丢掉，而那个窗口正好是「用户刚改完文件」的高发时刻。
     */
    private fun watchIfReady(handle: WorkspaceHandle, result: LoadResult) {
        if (result !is LoadResult.Success) return
        if (watcher != null && watchedHandle == handle) return
        closeWatcher()
        watcher = source.watchChanges(handle) { refresh() }
        watchedHandle = handle
    }

    private fun closeWatcher() {
        watcher?.close()
        watcher = null
        watchedHandle = null
    }

    /** 当前状态里能取到的授权（`Ready` / `Denied` 才有，见 [handleOrNull]）。 */
    private fun currentHandle(): WorkspaceHandle? = state.handleOrNull()

    /**
     * 正在 [load] 的那份授权，**只在状态确实是 Loading 时**才作答。
     *
     * 为什么要那道状态守卫：`loadingHandle` 是普通字段、不清空（[load] 结束时若清一次，
     * 就得在四条返回路径上各清一遍，漏一条就留下一个假的「正在加载」）。而 Loading 之外
     * 的状态里它必然已经过时，守卫一句话就把陈旧值挡在外面。
     */
    private fun loadingHandleOrNull(): WorkspaceHandle? =
        if (state is WorkspaceState.Loading) loadingHandle else null

    /**
     * 真的读到了才承认它是最新的 —— remember / pick / open 三个入口共用的唯一一处。
     *
     * **为什么必须落在这里而不是各入口自己调**：三个入口（[restore] 冷启动、
     * [pick] 新选、[open] 从列表点）语义完全一致，散在调用点写三遍必然漂移；
     * 而「失败也移到最前」会让一条打不开的记录反复霸占第一位（spec §6 第 2 条）。
     *
     * 幂等：已在最前时存储层原样不动，故 [restore] 不会平白改变顺序。
     * **不在这里刷 [listings]**：调用方（[load] 的 finally、[applyRefresh] 的末尾）
     * 统一收口，否则「成功要刷、失败也要刷」会变成四处各写一次。
     */
    private fun rememberIfLoaded(result: LoadResult, handle: WorkspaceHandle) {
        if (result !is LoadResult.Success) return
        // 真实实现下这一行**恒走 MovedToFront**：能走到这里说明刚扫成功，而 open / restore
        // 的 handle 必然已经在列表里（open 先从 workspaces() 找出来，restore 直接取第一条）。
        // 因此 RejectedAllPinned 这条分支在真实路径上不可达，置位只是极端竞态（另一进程
        // 同时改写了列表文件）的兜底。**不要当 bug 删**：删掉它并不让任何用例变红，
        // 但那一格竞态里会静默丢掉「加不进列表」这条信号。
        listRejected = source.rememberWorkspace(handle) == RememberResult.RejectedAllPinned
    }

    /**
     * 重算 [listings]：**先按 granted 分区，每区内部再按 pinned 稳定排序**。
     *
     * ## 为什么分区必须在外面
     *
     * `orderedForDisplay` 只认 `pinned`（它在 domain 里，看不见 `granted`，也不该看见 ——
     * 那是运行时事实）。若把整表直接丢给它，一条钉住但**授权已被撤销**的记录会排到
     * 有授权的那条**前面**，界面于是把「已失效」画在最上面、把能用的压到下面 ——
     * 这正是 spec §7.3「上区 = 已持久授权」要防的形态。
     *
     * 分区是渲染的第一条规则，图钉只在区内部起作用（「图钉之间仍可互相拖拽互换」
     * 也只在同一区内成立）。
     *
     * ## 代价与「为什么不改成 suspend」（评审 Important-2 的裁决）
     *
     * 本函数**仍是同步的，调用方全在主线程**（[openList] / [remove] / [togglePin] / [move] 直接调，
     * [pick] / [load] / [applyRefresh] 经 [load] 的 finally 与 [applyRefresh] 收口），
     * 一次调用的代价被压到：
     *
     * 1. **1 次** prefs 读 —— `workspaces()` 读的是本进程内存映射（SharedPreferences
     *    加载后就常驻内存），微秒级；
     * 2. **1 次** JSON 解析（条目上限 8 条）；
     * 3. **1 次** Binder —— `persistentGrants()` 一次拿全系统授权表
     *    （旧写法是**每条一次**，8 条 = 8 次跨进程调用，见 [WorkspaceSource.persistentGrants]）。
     *
     * 前两项是本进程内的微秒级开销，第三项才是跨进程大头 —— 批量化之后它已经是常数 1。
     * 若改成 `suspend` + `withContext(ioDispatcher)`，四个公开方法（openList / remove /
     * `togglePin / move`）会全部被迫变成异步，调用点与 UI 层的状态机都要跟着改，
     * 收益与风险不成比例。
     *
     * ⚠️ **但这笔账只在「1 次 Binder + 8 条」下成立**：将来若放大条目上限、或这里再**加任何**
     * 跨进程调用（读缩略图、查文档 provider……），必须改成 `suspend` +
     * `withContext(ioDispatcher)`，不要再往主线程上加常数项。
     */
    private fun refreshListings() {
        // 一次拿全集再在内存里比：Binder 次数从「每条一次」降到常数 1（评审 Important-2）。
        val grants = source.persistentGrants()
        val (grantedPart, rememberedPart) = source.workspaces()
            .map { WorkspaceListing(it, it.handle.treeUri in grants) }
            .partition { it.granted }
        listings = orderedForDisplay(grantedPart) + orderedForDisplay(rememberedPart)
    }
}
