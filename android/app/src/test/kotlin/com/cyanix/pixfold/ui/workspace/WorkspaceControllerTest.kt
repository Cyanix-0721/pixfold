package com.cyanix.pixfold.ui.workspace

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceChangeWatcher
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [WorkspaceController] 的扫描/恢复/重试流程。
 *
 * ## 为什么这一层必须有测试
 *
 * 这里是**平台层与界面层之间唯一有分支逻辑**的地方：自动重试、记住授权、
 * 丢弃过期结果全在此处。它的缺陷形态极隐蔽 ——
 * 冷启动时多弹一次「授权已失效」、连点「更换」时看到上一个目录的内容，
 * 这些都不崩、不报错，只是在真机上显得「这应用有点毛病」。
 * 纯靠四道闸门与手工走查很难稳定复现，故在此钉死。
 *
 * 用 `UnconfinedTestDispatcher` 而不是真实 `Dispatchers.IO`：后者会把执行甩到
 * 真实线程池，`runTest` 的虚拟时间管不到，测试只能靠 sleep 等，最脆。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceControllerTest {

    private val handle = WorkspaceHandle(treeUri = "content://tree/x", displayName = "照片库")

    /**
     * 与 [testDispatcher] 同一套虚拟时间的调度器。
     *
     * **为什么不用 `backgroundScope`**：它默认挂 `StandardTestDispatcher`，`launch` 要等
     * 调度器推进才执行，测试里读 `state` 时还停在 `Loading`。`UnconfinedTestDispatcher`
     * 会立即执行，状态在 `load()` 返回后即已落定 —— 这类「顺序流程」测试就该这样。
     */
    private fun TestScope.testScope() = CoroutineScope(testDispatcher())

    private fun TestScope.testDispatcher() = UnconfinedTestDispatcher(testScheduler)

    private fun collections(): List<ImageCollection> = listOf(
        ImageCollection(
            id = "c1",
            name = "c1",
            rootDirName = "c1",
            images = listOf(
                SourceItem(
                    id = "u1", collectionId = "c1", dir = "", baseName = "a", ext = "jpg",
                    sizeBytes = 1L, modifiedEpochMillis = 0L, createdEpochMillis = null, seed = 0,
                    uri = "content://doc/1",
                ),
            ),
        ),
    )

    /**
     * 按脚本依次返回结果的假源。`results` 用完后重复最后一个（模拟「一直这样」）。
     */
    private class ScriptedSource(
        vararg script: LoadResult,
        private val last: WorkspaceHandle? = null,
    ) : WorkspaceSource {
        private val queue = ArrayDeque(script.toList())
        val loadCalls = mutableListOf<WorkspaceHandle>()

        /** 列表里**本来就有**的那一条（= 上次用过的），构造时由 `last` 铺进去。 */
        val entries = mutableListOf<WorkspaceEntry>().apply { last?.let { add(WorkspaceEntry(it)) } }

        /** 最近一次 remember 传进来的 handle，供「先记住授权再扫描」的断言用。 */
        var remembered: WorkspaceHandle? = null

        override fun workspaces(): List<WorkspaceEntry> = entries.toList()
        override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult {
            remembered = handle
            return RememberResult.Added
        }
        override fun forgetWorkspace(treeUri: String) {
            entries.removeAll { it.handle.treeUri == treeUri }
        }
        override fun setPinned(treeUri: String, pinned: Boolean) = Unit
        override fun moveWorkspace(from: Int, to: Int) = Unit
        override fun loadCollections(handle: WorkspaceHandle): LoadResult {
            loadCalls += handle
            return if (queue.size > 1) queue.removeFirst() else queue.first()
        }
        override fun readBytes(item: SourceItem): ByteArray? = null
    }

    /**
     * 能主动触发变更通知的假源。
     *
     * `watchChanges` 是 [WorkspaceSource] 的**可选能力**（默认实现返回 null），
     * 但实时刷新整条链路都靠它，所以必须能在这里把它演出来：
     * 记下回调，测试里按需 `fire()`。
     */
    private class WatchableSource(
        vararg script: LoadResult,
        private val last: WorkspaceHandle? = null,
    ) : WorkspaceSource {
        private val inner = ScriptedSource(*script, last = last)
        var watches = 0
            private set
        var closed = 0
            private set
        private var onChange: (() -> Unit)? = null

        val loadCalls: List<WorkspaceHandle> get() = inner.loadCalls

        /** 每次扫描**开始前**的钩子，用来模拟「扫描进行中又发生了别的事」。 */
        var onLoad: ((WorkspaceHandle) -> Unit)? = null

        override fun workspaces(): List<WorkspaceEntry> = inner.workspaces()
        override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult = RememberResult.Added
        override fun forgetWorkspace(treeUri: String) = inner.forgetWorkspace(treeUri)
        override fun setPinned(treeUri: String, pinned: Boolean) = Unit
        override fun moveWorkspace(from: Int, to: Int) = Unit
        override fun loadCollections(handle: WorkspaceHandle): LoadResult {
            onLoad?.invoke(handle)
            return inner.loadCollections(handle)
        }

        override fun readBytes(item: SourceItem): ByteArray? = null

        override fun watchChanges(
            handle: WorkspaceHandle,
            onChange: () -> Unit,
        ): WorkspaceChangeWatcher {
            watches++
            this.onChange = onChange
            return WorkspaceChangeWatcher { closed++ }
        }

        /** 模拟「文件管理器改动了文件」：provider 发来一次通知。 */
        fun fire() {
            onChange?.invoke()
        }
    }

    /**
     * 按**工作区**返回结果的假源。
     *
     * 与 [ScriptedSource]（按调用次序发牌）互补：这里要测的是「扫描 A 的中途切到 B，
     * A 的结果后到会不会盖住 B」—— 这种场景里结果必须绑定到它来自哪个目录，
     * 否则测出来的只是调用次数，而不是「结果串了工作区」。
     */
    private class HandleKeyedSource(
        private val results: Map<String, LoadResult>,
        private val last: WorkspaceHandle? = null,
    ) : WorkspaceSource {
        val loadCalls = mutableListOf<WorkspaceHandle>()

        /** 每次扫描**开始前**的钩子。 */
        var onLoad: ((WorkspaceHandle) -> Unit)? = null

        override fun workspaces(): List<WorkspaceEntry> =
            listOfNotNull(last).map { WorkspaceEntry(it) }
        override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult = RememberResult.Added
        override fun forgetWorkspace(treeUri: String) = Unit
        override fun setPinned(treeUri: String, pinned: Boolean) = Unit
        override fun moveWorkspace(from: Int, to: Int) = Unit
        override fun loadCollections(handle: WorkspaceHandle): LoadResult {
            loadCalls += handle
            onLoad?.invoke(handle)
            return results.getValue(handle.treeUri)
        }
        override fun readBytes(item: SourceItem): ByteArray? = null
    }

    @Test
    fun `冷启动没有上次工作区时保持 None 不做扫描`() = runTest {
        val source = ScriptedSource(LoadResult.Denied, last = null)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.restore()

        assertEquals(WorkspaceState.None, controller.state)
        assertTrue(source.loadCalls.isEmpty(), "没有授权就不该去扫")
    }

    @Test
    fun `冷启动有上次工作区时自动恢复`() = runTest {
        val source = ScriptedSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.restore()

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(1, source.loadCalls.size)
    }

    @Test
    fun `授权失效会自动重试一次而不是直接报失效`() = runTest {
        // 冷启动时 takePersistableUriPermission 可能尚未就绪，表现同样是 SecurityException。
        // 不重试的话用户每次开机都要看到一次「授权已失效」。
        val source = ScriptedSource(LoadResult.Denied, LoadResult.Success(collections()))
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.load(handle)

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(2, source.loadCalls.size, "第一次失效 + 一次自动重试")
    }

    @Test
    fun `重试后仍失效才报 Denied 且记下重试次数`() = runTest {
        val source = ScriptedSource(LoadResult.Denied, LoadResult.Denied)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.load(handle)

        val denied = assertIs<WorkspaceState.Denied>(controller.state)
        assertEquals(1, denied.autoRetried, "界面要能如实说「重试后仍失败」")
        assertEquals(2, source.loadCalls.size)
    }

    @Test
    fun `成功路径只扫一次不做多余重试`() = runTest {
        val source = ScriptedSource(LoadResult.Success(collections()))
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.load(handle)

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(1, source.loadCalls.size)
    }

    @Test
    fun `选目录时先记住授权再扫描`() = runTest {
        // 顺序重要：扫描失败/被取消时，用户下次启动仍应恢复到该目录。
        val source = ScriptedSource(LoadResult.Success(collections()))
        val controller = WorkspaceController(
            source,
            testScope(),
            nameResolver = { "来自平台的目录名" },
            ioDispatcher = testDispatcher(),
        )

        controller.pick("content://tree/new")

        assertEquals("content://tree/new", source.remembered?.treeUri)
        assertEquals("来自平台的目录名", source.remembered?.displayName)
    }

    @Test
    fun `忘记工作区会清空状态并把它从列表里删掉`() = runTest {
        // 列表世界里「忘记」= 逐条移除这一条，不再是「抹掉唯一的那个」。
        // 判据从 lastWorkspace() 换成 workspaces()：列表里还留着的话，
        // 下次冷启动 restore() 会把它当作最前那条恢复回来。
        val source = ScriptedSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()
        assertIs<WorkspaceState.Ready>(controller.state)

        controller.forget()

        assertEquals(WorkspaceState.None, controller.state)
        assertTrue(source.workspaces().isEmpty(), "列表里也必须没了，否则下次启动又回来")
    }

    @Test
    fun `失败结果携带原始信息供调试`() = runTest {
        val source = ScriptedSource(LoadResult.Failed("磁盘满了"))
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.load(handle)

        assertEquals("磁盘满了", assertIs<WorkspaceState.Failed>(controller.state).message)
    }

    // ---- 实时刷新（D4「工作区实时刷新」2026-10-19 用户裁决）----
    //
    // 这些用例钉的是**语义**，不是实现：刷新必须保住旧内容、必须只在内容真的
    // 变了时才发新实例、失败时不能把列表换成错误页。任何一条破了，用户看到的
    // 就是「我改完文件回来，刚才排好的顺序/改好的名字全没了」。

    @Test
    fun `刷新前必须先有工作区，None 时不做任何扫描`() = runTest {
        val source = WatchableSource(LoadResult.Success(collections()))
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.refresh()

        assertTrue(source.loadCalls.isEmpty(), "没有工作区时刷新应当是无操作")
        assertFalse(controller.isRefreshing)
    }

    @Test
    fun `刷新期间保留旧列表而不是退回 Loading`() = runTest {
        // 语义要害：千级缩略图列表要是先清空再重建，用户会看到明显闪烁。
        val source = WatchableSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()
        val before = controller.state

        controller.refresh()

        // UnconfinedTestDispatcher 下刷新已跑完，内容没变 ⇒ 同一个实例。
        assertSame(before, controller.state, "内容没变就不该发新实例")
        assertFalse(controller.isRefreshing, "刷新结束必须复位")
    }

    @Test
    fun `刷新拿到新内容时替换列表`() = runTest {
        val twoCollections = collections() + collections().map {
            it.copy(id = "c2", name = "c2", rootDirName = "c2")
        }
        val source = WatchableSource(
            LoadResult.Success(collections()),
            LoadResult.Success(twoCollections),
            last = handle,
        )
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()

        controller.refresh()

        val ready = assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(2, ready.collections.size)
    }

    @Test
    fun `刷新失败时保留旧内容而不是换成错误页`() = runTest {
        // 后台刷新失败是「这次没读到」，不是「工作区没了」。
        val source = WatchableSource(
            LoadResult.Success(collections()),
            LoadResult.Failed("IO 抖动"),
            last = handle,
        )
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()

        controller.refresh()

        val ready = assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(1, ready.collections.size)
    }

    @Test
    fun `刷新时授权失效会重试一次`() = runTest {
        val source = WatchableSource(
            LoadResult.Success(collections()),
            LoadResult.Denied,
            LoadResult.Success(collections()),
            last = handle,
        )
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()

        controller.refresh()

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(3, source.loadCalls.size, "首次加载 + 失效 + 重试")
    }

    @Test
    fun `刷新时授权彻底失效才报 Denied`() = runTest {
        val source = WatchableSource(
            LoadResult.Success(collections()),
            LoadResult.Denied,
            LoadResult.Denied,
            last = handle,
        )
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()

        controller.refresh()

        val denied = assertIs<WorkspaceState.Denied>(controller.state)
        assertEquals(1, denied.autoRetried)
    }

    @Test
    fun `加载成功后自动挂上变更监听`() = runTest {
        val source = WatchableSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.restore()

        assertEquals(1, source.watches, "实时刷新完全靠这个监听，必须挂上")
    }

    @Test
    fun `provider 变更通知会触发一次重扫`() = runTest {
        val twoCollections = collections() + collections().map {
            it.copy(id = "c2", name = "c2", rootDirName = "c2")
        }
        val source = WatchableSource(
            LoadResult.Success(collections()),
            LoadResult.Success(twoCollections),
            last = handle,
        )
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()

        source.fire()

        assertEquals(2, source.loadCalls.size)
        assertEquals(2, assertIs<WorkspaceState.Ready>(controller.state).collections.size)
    }

    @Test
    fun `刷新时不重复挂监听`() = runTest {
        // 每轮刷新都 close + 重挂的话，注销与重注册之间的通知窗口会丢掉变更。
        val source = WatchableSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()

        controller.refresh()
        controller.refresh()

        assertEquals(1, source.watches, "同一工作区只该挂一次")
        assertEquals(0, source.closed)
    }

    @Test
    fun `关闭控制器会停掉监听`() = runTest {
        val source = WatchableSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()

        controller.close()

        assertEquals(1, source.closed, "ContentObserver 不注销会活到进程结束")
    }

    @Test
    fun `换工作区会先停掉旧监听`() = runTest {
        val source = WatchableSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()

        controller.load(WorkspaceHandle("content://tree/y", "另一个"))

        assertTrue(source.closed >= 1, "旧目录的通知绝不能还在触发刷新")
    }

    @Test
    fun `扫描期间到达的变更会补扫一轮而不是被丢掉`() = runTest {
        // 语义要害：一次扫描要几百毫秒到几秒，**扫描开始之后**发生的删除这次看不到。
        // 若把「刷新中」当成「可以忽略新通知」，那张图要等到下一次触发才消失 ——
        // 用户会觉得「刷新按钮点了没用」。
        //
        // ⚠️ 必须用 StandardTestDispatcher：同步（Unconfined）调度器下 `refresh()`
        // 一返回刷新已经跑完，「刷新中」这个窗口根本不存在，测不到合并语义。
        val standard = StandardTestDispatcher(testScheduler)
        val source = WatchableSource(
            LoadResult.Success(collections()),
            LoadResult.Success(collections()),
            LoadResult.Success(collections()),
            last = handle,
        )
        val controller = WorkspaceController(source, testScope(), ioDispatcher = standard)
        controller.load(handle)
        advanceUntilIdle()
        assertEquals(1, source.loadCalls.size)

        var fired = false
        // 在扫描进行中再触发一次 —— 等价于「用户在看进度条时又删了一张图」。
        source.onLoad = {
            if (!fired) {
                fired = true
                controller.refresh()
            }
        }
        controller.refresh()
        assertTrue(controller.isRefreshing, "标准调度器下此刻刷新应当还在飞")
        advanceUntilIdle()

        assertEquals(3, source.loadCalls.size, "首轮 + 合并后的补一轮")
        assertFalse(controller.isRefreshing)
    }

    @Test
    fun `换工作区后正在进行的那轮刷新不会覆盖新工作区的内容`() = runTest {
        // 缺陷形态：用户扫 A 目录扫到一半点了「更换」到 B，A 的结果后到并盖上去 ——
        // 界面显示 B 的目录名、内容却是 A 的图，打包时按 A 的图写进 B 的输出。
        val standard = StandardTestDispatcher(testScheduler)
        val other = WorkspaceHandle("content://tree/y", "另一个")
        val otherCollections = listOf(
            ImageCollection(id = "z1", name = "z1", rootDirName = "z1", images = collections().first().images),
        )
        val source = HandleKeyedSource(
            mapOf(
                handle.treeUri to LoadResult.Success(collections()),
                other.treeUri to LoadResult.Success(otherCollections),
            ),
        )
        val controller = WorkspaceController(source, testScope(), ioDispatcher = standard)
        controller.load(handle)
        advanceUntilIdle()
        assertIs<WorkspaceState.Ready>(controller.state)

        controller.refresh()   // 排入队列，尚未开扫
        controller.load(other) // 立刻换目录 ⇒ 旧刷新必须被取消
        advanceUntilIdle()

        val ready = assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(other, ready.handle)
        assertEquals(otherCollections, ready.collections)
        assertFalse(controller.isRefreshing, "被取消的刷新也必须复位状态")
    }

    @Test
    fun `授权恢复后重新挂上监听`() = runTest {
        // 缺陷形态：撤销授权时监听被关掉，之后用户在系统设置里把授权加回来、
        // 或重新选了同一个目录 —— 界面显示一切正常，只是**再也不跟着文件管理器的
        // 改动变了**。没有异常、没有报错，功能静默死亡，只能靠这条断言钉住。
        val source = WatchableSource(
            LoadResult.Success(collections()), // 首次加载
            LoadResult.Denied,                 // 刷新时失效
            LoadResult.Denied,                 // 自动重试仍失效 ⇒ 落 Denied 并关监听
            LoadResult.Success(collections()), // 用户恢复授权后再刷新
            last = handle,
        )
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()
        assertEquals(1, source.watches)
        assertEquals(0, source.closed)

        controller.refresh()
        assertIs<WorkspaceState.Denied>(controller.state)
        assertEquals(1, source.closed, "没有授权就该断开监听")

        controller.refresh()

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(2, source.watches, "恢复成功必须重新挂上")
    }
}
