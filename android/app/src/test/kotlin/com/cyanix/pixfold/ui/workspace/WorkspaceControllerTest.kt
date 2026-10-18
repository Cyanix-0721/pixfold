package com.cyanix.pixfold.ui.workspace

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        var lastWorkspace: WorkspaceHandle? = last
        var remembered: WorkspaceHandle? = null

        override fun lastWorkspace(): WorkspaceHandle? = lastWorkspace
        override fun rememberWorkspace(handle: WorkspaceHandle) { remembered = handle }
        override fun forgetWorkspace() { lastWorkspace = null }
        override fun loadCollections(handle: WorkspaceHandle): LoadResult {
            loadCalls += handle
            return if (queue.size > 1) queue.removeFirst() else queue.first()
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
    fun `忘记工作区会清空状态并抹掉持久授权`() = runTest {
        val source = ScriptedSource(LoadResult.Success(collections()), last = handle)
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())
        controller.restore()
        assertIs<WorkspaceState.Ready>(controller.state)

        controller.forget()

        assertEquals(WorkspaceState.None, controller.state)
        assertNull(source.lastWorkspace(), "持久授权也必须抹掉，否则下次启动又回来")
    }

    @Test
    fun `失败结果携带原始信息供调试`() = runTest {
        val source = ScriptedSource(LoadResult.Failed("磁盘满了"))
        val controller = WorkspaceController(source, testScope(), ioDispatcher = testDispatcher())

        controller.load(handle)

        assertEquals("磁盘满了", assertIs<WorkspaceState.Failed>(controller.state).message)
    }
}
