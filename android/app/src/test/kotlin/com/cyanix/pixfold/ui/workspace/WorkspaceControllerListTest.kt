package com.cyanix.pixfold.ui.workspace

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.source.AndroidGrantReleaser
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceChangeWatcher
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 工作区列表（Task 4）：分区、图钉、拖拽、移除、被拒提示。
 *
 * ## 为什么这一层需要自己的测试
 *
 * [WorkspaceController] 是平台层与界面层之间**唯一有分支逻辑**的地方。它的缺陷形态
 * 都很隐蔽 —— 列表打开时显示过期的 ✓、打不开的记录霸占第一位、移除当前打开的那条
 * 之后界面还停在一个已经不存在的目录上。都不崩、不报错，只是显得「这应用有点毛病」。
 *
 * 假源 [ListSource] 记住顺序、能按 URI 判授权、能记录撤销授权的调用 —— 这三件事
 * 正好覆盖列表操作的全部外部可观察行为，不需要真实 SAF。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceControllerListTest {

    /** [UnconfinedTestDispatcher] 才能让 load() 返回时状态已经落定（同 [WorkspaceControllerTest]）。 */
    private fun TestScope.testScope() = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    private fun handle(name: String) = WorkspaceHandle("content://tree/$name", name)

    /**
     * 能记住顺序、能按 URI 判授权的假源。
     *
     * [rememberWorkspace] **保留 pinned**：这是 [WorkspaceSource] 的契约（「pinned 原样保留」），
     * 假源不照做的话，任何「打开钉住的那条 → 顺序」的用例都会测出一个真实实现不会有的结果。
     */
    private class ListSource(
        val entries: MutableList<WorkspaceEntry> = mutableListOf(),
        var granted: Set<String> = emptySet(),
        var result: LoadResult = LoadResult.Success(emptyList()),
    ) : WorkspaceSource, AndroidGrantReleaser {

        var rememberCalls = 0
        var rejectRemember = false
        val released = mutableListOf<String>()

        override fun workspaces(): List<WorkspaceEntry> = entries.toList()

        override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult {
            rememberCalls++
            if (rejectRemember) return RememberResult.RejectedAllPinned
            val existing = entries.indexOfFirst { it.handle.treeUri == handle.treeUri }
            val pinned = existing >= 0 && entries[existing].pinned
            if (existing >= 0) entries.removeAt(existing)
            entries.add(0, WorkspaceEntry(handle, pinned))
            return if (existing >= 0) RememberResult.MovedToFront else RememberResult.Added
        }

        override fun forgetWorkspace(treeUri: String) {
            entries.removeAll { it.handle.treeUri == treeUri }
        }

        override fun setPinned(treeUri: String, pinned: Boolean) {
            val i = entries.indexOfFirst { it.handle.treeUri == treeUri }
            if (i >= 0) entries[i] = entries[i].copy(pinned = pinned)
        }

        override fun moveWorkspace(from: Int, to: Int) {
            if (from !in entries.indices) return
            val item = entries.removeAt(from)
            entries.add(to.coerceIn(0, entries.size), item)
        }

        override fun persistentGrants(): Set<String> = granted

        /**
         * 按调用顺序依次返回的脚本队列（先例见 [WorkspaceControllerTest] 的 ScriptedSource）。
         *
         * 为空时回落到 [result]；非空时依次取，**用完后重复最后一个** ——
         * 「先失败后成功」这类多轮路径没法用单一 [result] 表达。
         */
        val script = ArrayDeque<LoadResult>()

        override fun loadCollections(handle: WorkspaceHandle): LoadResult = when {
            script.isEmpty() -> result
            script.size > 1 -> script.removeFirst()
            else -> script.first()
        }

        override fun readBytes(item: com.cyanix.pixfold.domain.model.SourceItem): ByteArray? = null

        override fun watchChanges(handle: WorkspaceHandle, onChange: () -> Unit): WorkspaceChangeWatcher? = null

        /** 真实实现会真的放掉系统授权 —— 假源同步把 [granted] 里这条去掉，让后续读授权一致。 */
        override fun releasePersistentGrant(treeUri: String) {
            released += treeUri
            granted = granted - treeUri
        }
    }

    private fun controller(source: WorkspaceSource, scope: CoroutineScope) =
        WorkspaceController(source, scope, ioDispatcher = Dispatchers.Unconfined)

    private fun collections(): List<ImageCollection> = emptyList()

    // ---- T8：分区判定 ----

    @Test
    fun `上区整体在下区之前`() = runTest {
        // 存储顺序 [a, b(钉住), c]，只有 a 还持有持久授权。
        //
        // ⚠️ 分区是**渲染的第一条规则**：b 钉住了也不能越过分区边界跑到 a 前面 ——
        // 「已持久授权」区整体在上、「只记住了路径」区整体在下。b 的图钉只在它自己的
        // 区里起作用（见下一条用例）。把这两个事实混成一条排序键，就等于用图钉伪造授权。
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"), pinned = true)
        val c = WorkspaceEntry(handle("c"))
        val source = ListSource(mutableListOf(a, b, c), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())

        controller.openList()

        // brief 原文期望的名字是 ["b", "a", "c"]，与同一行的 [true, false, false] 不可能
        // 同时成立（b 在下区、a 在上区 ⇒ a 必在 b 前）。以 spec §4.3 / §7.3 为准：
        // 先分区再排序，名字因此是 ["a", "b", "c"]。
        assertEquals(listOf(true, false, false), controller.listings.map { it.granted })
        assertEquals(listOf("a", "b", "c"), controller.listings.map { it.entry.handle.displayName })
    }

    @Test
    fun `分区内部才做图钉排序`() = runTest {
        // b 与 c 都在上区、b 钉住 ⇒ b 排到 c 前；d 在下区虽然钉住，也压不过上区任何一条。
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"), pinned = true)
        val c = WorkspaceEntry(handle("c"))
        val d = WorkspaceEntry(handle("d"), pinned = true)
        val source = ListSource(
            mutableListOf(a, b, c, d),
            granted = setOf(b.handle.treeUri, c.handle.treeUri),
        )
        val controller = controller(source, testScope())

        controller.openList()

        // 下区内部 d 钉住 ⇒ d 排在 a 前；但整段仍在上区之下。
        assertEquals(listOf("b", "c", "d", "a"), controller.listings.map { it.entry.handle.displayName })
        assertEquals(listOf(true, true, false, false), controller.listings.map { it.granted })
    }

    @Test
    fun `openList 每次都重读授权，不缓存`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())
        controller.openList()
        assertTrue(controller.listings.single().granted)

        source.granted = emptySet() // 模拟用户去系统设置里撤销了
        controller.openList()

        assertFalse(controller.listings.single().granted, "缓存授权会显示一个过期的 ✓")
    }

    // ---- T9：open 成功才移到最前 ----

    @Test
    fun `open 成功后把它移到最前`() = runTest {
        val b = WorkspaceEntry(handle("b"))
        val source = ListSource(
            mutableListOf(WorkspaceEntry(handle("a")), b),
            result = LoadResult.Success(collections()),
        )
        val controller = controller(source, testScope())

        controller.open(b.handle.treeUri)

        assertEquals(b.handle.treeUri, source.entries.first().handle.treeUri)
        assertEquals(1, source.rememberCalls)
        assertIs<WorkspaceState.Ready>(controller.state)
    }

    @Test
    fun `open 失败时不改变顺序`() = runTest {
        val b = WorkspaceEntry(handle("b"))
        val source = ListSource(
            mutableListOf(WorkspaceEntry(handle("a")), b),
            result = LoadResult.Failed("磁盘满了"),
        )
        val controller = controller(source, testScope())

        controller.open(b.handle.treeUri)

        assertEquals("content://tree/a", source.entries.first().handle.treeUri, "打不开的记录不该霸占第一位")
        assertEquals(0, source.rememberCalls)
    }

    @Test
    fun `冷启动恢复成功也会 remember（覆盖 restore 入口）`() = runTest {
        // spec §6 第 2 条：一个位置（[WorkspaceController.load] 的成功分支）覆盖
        // restore / pick / open 三个入口。这条守的就是 restore 那一支 ——
        // 若把 remember 挪进 open()，冷启动的顺序就再也不会更新了。
        val source = ListSource(
            mutableListOf(WorkspaceEntry(handle("a")), WorkspaceEntry(handle("b"))),
            result = LoadResult.Success(collections()),
        )
        val controller = controller(source, testScope())

        controller.restore()

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(1, source.rememberCalls, "读到了才承认它是最新的")
    }

    @Test
    fun `刷新成功也会 remember（覆盖 applyRefresh 分支）`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), result = LoadResult.Success(collections()))
        val controller = controller(source, testScope())
        controller.open(a.handle.treeUri)
        assertEquals(1, source.rememberCalls)

        controller.refresh()

        assertEquals(2, source.rememberCalls, "refresh() 走 applyRefresh，漏了它顺序刷新后会变旧")
    }

    @Test
    fun `刷新时授权失效但自动重试成功，也要 remember`() = runTest {
        // 评审 Important-1：applyRefresh 的 Denied 分支会**自己再扫一次**（自动重试），
        // 那一次的 Success 与「首轮就成功」在语义上没有区别 —— 都是「真的读到了」。
        // 漏掉这支的表现是：冷启动抖一下才读到的那次不更新顺序 ⇒ 下次 restore() 用
        // `workspaces().firstOrNull()` 恢复到**旧**目录，平时完全静默。spec §6 第 2 条。
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        val source = ListSource(
            mutableListOf(a, b),
            granted = setOf(a.handle.treeUri, b.handle.treeUri),
        )
        source.script.addAll(
            listOf(
                LoadResult.Success(collections()), // open() 那次扫描
                LoadResult.Denied, // refresh() 首轮：先失效
                LoadResult.Success(collections()), // 自动重试：授权其实还在
            ),
        )
        val controller = controller(source, testScope())
        controller.open(a.handle.treeUri)
        assertIs<WorkspaceState.Ready>(controller.state)

        // 让「这条不是列表最前」这个条件成立：另一个入口刚用过 b。
        // 不制造这个前提的话，下面的 entries.first() 断言恒真（open 已经把它排到最前了）。
        source.rememberWorkspace(b.handle)
        assertEquals("b", source.entries.first().handle.displayName)

        source.rememberCalls = 0
        controller.refresh()

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(1, source.rememberCalls, "自动重试成功同样是「读到了」，必须 remember")
        assertEquals(
            "a",
            source.entries.first().handle.displayName,
            "重试成功要把这条重新移到最前，否则 restore() 会恢复到旧目录",
        )
        assertEquals(
            listOf("a", "b"),
            controller.listings.map { it.entry.handle.displayName },
        )
    }

    // ---- 上限被拒：listRejected ----

    @Test
    fun `remember 被拒时置 listRejected，下次成功即复位`() = runTest {
        // 「8 条全钉住」的拒绝是**静默**的：不断言这个标志，用户只会看到
        // 「添加了但列表没变」，而界面拿不到任何能解释这句话的信息。
        val source = ListSource(mutableListOf(WorkspaceEntry(handle("a"))))
        source.rejectRemember = true
        val controller = controller(source, testScope())

        controller.pick("content://tree/new")
        assertTrue(controller.listRejected)

        source.rejectRemember = false
        controller.pick("content://tree/new")
        assertFalse(controller.listRejected, "拒绝是「上一次」的结论，不是永久标记")
    }

    @Test
    fun `被拒之后删掉一条，提示也要撤掉`() = runTest {
        // 评审 Minor-3：被拒提示说的是「列表满且全钉住，加不进新的」。用户照它去删掉
        // 一条之后这个前提已经不成立，提示却还挂着 —— 同一条提示会一直留到下次成功
        // remember 为止（可能再也不发生）。remove() 必须自己把它复位。
        // 假源用 [ListSource.rejectRemember] 模拟「已满 8 条且全钉住」的拒绝（同上面那条用例）；
        // 条目本身也照实铺成 8 条全钉，免得用例名里的前提只存在于注释里。
        val entries = (0 until 8).map { WorkspaceEntry(handle("w$it"), pinned = true) }
        val source = ListSource(entries.toMutableList())
        source.rejectRemember = true
        val controller = controller(source, testScope())

        controller.pick("content://tree/new")
        assertTrue(controller.listRejected)

        controller.remove(entries.first().handle.treeUri, alsoRelease = false)

        assertFalse(controller.listRejected, "列表不再满了，提示必须跟着撤掉")
    }

    @Test
    fun `扫描也失败时被拒信号仍然在`() = runTest {
        // 被拒与「读不到」是两件独立的事：列表满 8 条全钉住时，扫描照样可能因为
        // 目录被拔掉而失败。若只在「扫描成功」那条分支上置 listRejected，
        // 用户在最需要提示的场景（既没加上、又打不开）反而什么提示都看不到。
        val source = ListSource(
            mutableListOf(WorkspaceEntry(handle("a"))),
            result = LoadResult.Failed("磁盘拔掉了"),
        )
        source.rejectRemember = true
        val controller = controller(source, testScope())

        controller.pick("content://tree/new")

        assertIs<WorkspaceState.Failed>(controller.state)
        assertTrue(controller.listRejected, "扫描失败不该把「列表已满」这条提示吞掉")
    }

    // ---- Review Focus 4：移除的正好是当前打开那条 / Failed 态 ----

    @Test
    fun `移除当前打开的那条会把状态落回 None`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), result = LoadResult.Success(collections()))
        val controller = controller(source, testScope())
        controller.open(a.handle.treeUri)
        assertIs<WorkspaceState.Ready>(controller.state)

        controller.remove(a.handle.treeUri, alsoRelease = false)

        assertEquals(WorkspaceState.None, controller.state, "不能还停在一个已不在列表里的目录上")
        assertTrue(controller.listings.isEmpty())
    }

    @Test
    fun `移除别的条目不影响当前工作区`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(
            mutableListOf(a, WorkspaceEntry(handle("b"))),
            result = LoadResult.Success(collections()),
        )
        val controller = controller(source, testScope())
        controller.open(a.handle.treeUri)

        controller.remove("content://tree/b", alsoRelease = false)

        assertIs<WorkspaceState.Ready>(controller.state)
        assertEquals(listOf("a"), controller.listings.map { it.entry.handle.displayName })
    }

    @Test
    fun `Failed 状态下移除条目仍然生效`() = runTest {
        // 评审 M5 的交接项：旧 forget() 靠 currentHandle() 定位要删的那条，而
        // WorkspaceState.Failed **不带 handle** ⇒ 那时一条都删不掉，用户点了 ✕
        // 列表照旧，下次启动又回来。remove() 的入参是 treeUri，与「当前是哪条」无关。
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(
            mutableListOf(a, WorkspaceEntry(handle("b"))),
            result = LoadResult.Failed("磁盘满了"),
        )
        val controller = controller(source, testScope())
        controller.open(a.handle.treeUri)
        assertIs<WorkspaceState.Failed>(controller.state)

        controller.remove(a.handle.treeUri, alsoRelease = false)

        assertTrue(
            source.entries.none { it.handle.treeUri == a.handle.treeUri },
            "Failed 下也必须真的删掉那条记录",
        )
        assertEquals(WorkspaceState.None, controller.state, "读取失败的那条已被移除，状态不该停在 Failed")
        assertEquals(listOf("b"), controller.listings.map { it.entry.handle.displayName })
    }

    @Test
    fun `Failed 带授权时删别的条目 不把重选入口一起关掉`() = runTest {
        // 2026-11-08：Failed 现在带 handle（否则刷新是静默 no-op），于是它不再是
        // 「当前是哪条不可知」的状态 —— 与 Denied 同口径，只有删到**当前那条**才回 None。
        // 旧写法 `state is WorkspaceState.Failed` 无条件复位，用户删一条无关记录就会
        // 把工作区条（授权失效时唯一的重选入口）一起关掉。
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(
            mutableListOf(a, WorkspaceEntry(handle("b"))),
            result = LoadResult.Failed("磁盘满了"),
        )
        val controller = controller(source, testScope())
        controller.open(a.handle.treeUri)
        assertIs<WorkspaceState.Failed>(controller.state)

        controller.remove("content://tree/b", alsoRelease = false)

        assertEquals(
            a.handle,
            assertIs<WorkspaceState.Failed>(controller.state).handleOrNull(),
            "删的是别的条目，当前那条读不到的事实没变，条必须还在",
        )
    }

    // ⚠️ 「Failed 且 handle == null」那条分支（`failedUnknown`）**刻意不设断言**：
    // `state` 的 setter 是 private，而 `toWorkspaceState` 现在总是把 handle 带上，
    // 离线造不出 handle 为 null 的 Failed（只有「冷启动时压根没有目录」那条极端路径会有）。
    // 留着只会是假保障。它要守的是「别停在一条已被删掉的目录上说假话」，
    // 属只能真机/人工判定的类别。

    @Test
    fun `移除后列表变空也回 None`() = runTest {
        // 「当前那条」不可信的场景：状态停在 Ready(a)，但存储里已经没有 a 了
        // （旧版本写坏 / 另一个进程删过）。此时删掉最后一条 b，当前目录已经不可能
        // 还在列表里 —— 只按「删的是不是当前那条」判断会漏掉这条路径。
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        val source = ListSource(
            mutableListOf(a, b),
            result = LoadResult.Success(collections()),
        )
        val controller = controller(source, testScope())
        controller.load(a.handle)
        assertIs<WorkspaceState.Ready>(controller.state)
        source.entries.removeAll { it.handle.treeUri == a.handle.treeUri }

        controller.remove(b.handle.treeUri, alsoRelease = false)

        assertEquals(WorkspaceState.None, controller.state)
        assertTrue(controller.listings.isEmpty())
    }

    // ---- T10：release 只在 alsoRelease=true 时发生 ----

    @Test
    fun `默认移除不撤销系统授权`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())

        controller.remove(a.handle.treeUri, alsoRelease = false)

        assertEquals(0, source.released.size, "只删记录不该动系统授权")
    }

    @Test
    fun `勾了同时撤销才真的撤销`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val source = ListSource(mutableListOf(a), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())

        controller.remove(a.handle.treeUri, alsoRelease = true)

        assertEquals(listOf(a.handle.treeUri), source.released)
    }

    @Test
    fun `不支持撤销授权的来源不会崩`() = runTest {
        // 能力面（[AndroidGrantReleaser]）是**可选**的：只读测试替身、未来的只读实现
        // 都不实现它。此时 alsoRelease=true 必须是「做不到」而不是「炸掉」。
        val a = WorkspaceEntry(handle("a"))
        val delegate = ListSource(mutableListOf(a))
        val source = object : WorkspaceSource by delegate {}
        val controller = controller(source, testScope())

        controller.remove(a.handle.treeUri, alsoRelease = true)

        assertTrue(controller.listings.isEmpty())
    }

    // ---- T15 的领域侧：togglePin 与 move ----

    @Test
    fun `togglePin 把该条移到本区最前`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        val source = ListSource(
            mutableListOf(a, b),
            granted = setOf(a.handle.treeUri, b.handle.treeUri),
        )
        val controller = controller(source, testScope())

        controller.togglePin(b.handle.treeUri)

        assertEquals("b", controller.listings.first().entry.handle.displayName)
        assertTrue(controller.listings.first().entry.pinned)
    }

    @Test
    fun `togglePin 只影响本区，不跨分区`() = runTest {
        // 下区的 b 钉住后仍在下区：本区最前 ≠ 整个列表最前。
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        val c = WorkspaceEntry(handle("c"))
        val source = ListSource(
            mutableListOf(a, b, c),
            granted = setOf(a.handle.treeUri, c.handle.treeUri),
        )
        val controller = controller(source, testScope())

        controller.togglePin(b.handle.treeUri)

        assertEquals(listOf("a", "c", "b"), controller.listings.map { it.entry.handle.displayName })
    }

    @Test
    fun `move 接受 treeUri 并换算成存储顺序`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        val c = WorkspaceEntry(handle("c"))
        val source = ListSource(
            mutableListOf(a, b, c),
            granted = setOf(a.handle.treeUri, b.handle.treeUri, c.handle.treeUri),
        )
        val controller = controller(source, testScope())

        controller.move("content://tree/a", "content://tree/c")

        assertEquals(listOf("b", "c", "a"), source.entries.map { it.handle.displayName })
        // 评审 Important-3：只断 [source].entries 挡不住「move() 末尾漏掉 refreshListings()」——
        // 删掉那一行时存储顺序照旧正确，而用户看到的 [listings] 还停在旧顺序。变异实测全绿。
        assertEquals(listOf("b", "c", "a"), controller.listings.map { it.entry.handle.displayName })
    }

    @Test
    fun `向后拖拽同样落在目标原来的位置上`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        val c = WorkspaceEntry(handle("c"))
        val d = WorkspaceEntry(handle("d"))
        val source = ListSource(
            mutableListOf(a, b, c, d),
            granted = setOf(a.handle.treeUri, b.handle.treeUri, c.handle.treeUri, d.handle.treeUri),
        )
        val controller = controller(source, testScope())

        controller.move("content://tree/d", "content://tree/b")

        assertEquals(listOf("a", "d", "b", "c"), source.entries.map { it.handle.displayName })
        // 同上：界面顺序必须跟着存储顺序一起变，见 Important-3 那条注释。
        assertEquals(listOf("a", "d", "b", "c"), controller.listings.map { it.entry.handle.displayName })
    }

    @Test
    fun `跨分区拖拽被丢弃`() = runTest {
        val a = WorkspaceEntry(handle("a"))
        val b = WorkspaceEntry(handle("b"))
        // a 有授权、b 没有 ⇒ 处在两个分区里
        val source = ListSource(mutableListOf(a, b), granted = setOf(a.handle.treeUri))
        val controller = controller(source, testScope())
        controller.openList()

        controller.move(a.handle.treeUri, b.handle.treeUri)

        assertEquals(
            listOf("a", "b"),
            source.entries.map { it.handle.displayName },
            "跨分区不得换位，分区是运行时事实",
        )
    }
}
