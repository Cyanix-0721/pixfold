package com.cyanix.pixfold.ui.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cyanix.pixfold.domain.source.FailureCause
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * `WorkspaceSource.lastWorkspace()`）—— 旋转后重建会重新 `restore()` 一次，
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

    private var job: Job? = null

    /**
     * 冷启动恢复：上次授权过的工作区就重新扫描一次。
     *
     * **不缓存上次的扫描结果**：目录内容随时会变（用户可能刚删了图），
     * 显示一份过期列表比重新扫一遍更糟。
     */
    fun restore() {
        val handle = source.lastWorkspace() ?: return
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
        source.rememberWorkspace(handle)
        load(handle)
    }

    /** 用户主动「忘记」当前工作区。 */
    fun forget() {
        job?.cancel()
        source.forgetWorkspace()
        state = WorkspaceState.None
    }

    /**
     * 扫描 [handle] 指向的工作区。
     *
     * 状态序列：[WorkspaceState.Loading] → [WorkspaceState.Ready] /
     * [WorkspaceState.Denied] / [WorkspaceState.Failed]。
     */
    fun load(handle: WorkspaceHandle) {
        job?.cancel()
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
                return@launch
            }

            val second = withContext(ioDispatcher) { source.loadCollections(handle) }
            state = when (second) {
                is LoadResult.Success -> second.toWorkspaceState(handle)
                LoadResult.Denied -> WorkspaceState.Denied(handle, autoRetried = 1)
                is LoadResult.Failed -> second.toWorkspaceState(handle)
            }
        }
    }
}
