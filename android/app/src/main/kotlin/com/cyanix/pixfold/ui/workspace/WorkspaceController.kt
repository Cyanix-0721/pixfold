package com.cyanix.pixfold.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cyanix.pixfold.domain.source.FailureCause
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.WorkspaceChangeWatcher
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource
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

    private var job: Job? = null

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
        // TODO(Task 4): 列表为空时直接 return，state 保持原值 —— 从 Ready 变成空列表
        //  仍会显示 Ready。列表页的 remove() 落地时必须同时复位 state。
        val handle = source.workspaces().firstOrNull()?.handle ?: return
        load(handle)
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
        // 返回值（是否被「满 8 条且全钉住」拒绝）本任务先不处理，Task 4 接被拒提示。
        source.rememberWorkspace(handle)
        load(handle)
    }

    /** 用户主动「忘记」当前工作区：只从列表删这一条，**不改**系统授权。 */
    fun forget() {
        // TODO(Task 4): 换成 remove(treeUri, alsoRelease) —— 当前 currentHandle() 在
        //  Failed 状态下返 null，那时一项都不删却仍把 state 置 None。
        cancelJob()
        closeWatcher()
        currentHandle()?.let { source.forgetWorkspace(it.treeUri) }
        state = WorkspaceState.None
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
        job = scope.launch {
            // 阻塞遍历必须离开主线程：851 张图实测约 1 秒，千级更久，
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
                state = first.toWorkspaceState(handle)
                watchIfReady(handle, first)
                return@launch
            }

            val second = withContext(ioDispatcher) { source.loadCollections(handle) }
            state = when (second) {
                is LoadResult.Success -> second.toWorkspaceState(handle)
                LoadResult.Denied -> WorkspaceState.Denied(handle, autoRetried = 1)
                is LoadResult.Failed -> second.toWorkspaceState(handle)
            }
            watchIfReady(handle, second)
        }
    }

    /**
     * 重扫当前工作区，**不清空已有内容**（D4「工作区实时刷新」）。
     *
     * 三条触发路径共用它：回前台（`ON_RESUME`）、手动刷新按钮、
     * `ContentObserver`（用户在文件管理器里改了文件）。
     *
     * 语义：
     * - 状态是 [WorkspaceState.None] / [WorkspaceState.Failed] 时什么都不做 ——
     *   前者没有目录可扫，后者连 handle 都没留下（[WorkspaceState.Failed] 只带 message）。
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
}
