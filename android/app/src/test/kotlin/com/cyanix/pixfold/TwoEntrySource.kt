package com.cyanix.pixfold

import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource

/**
 * 工作区假源：[ReadyWorkspaceSource] 的**两条 entry** 版本。
 *
 * ## 为什么不能再借用 ReadyWorkspaceSource（2026-10-25，Task 7 评审 I-1 / I-2）
 *
 * 它恒定返回**一条** entry（见 `ReadyWorkspaceSource.kt:46`）。一条 entry 让两类
 * 断言在语义上永远成立，也因此永远压不住错：
 *
 * 1. **拖拽的下标**：只有一行时 `from == to`，把 `all[from]` / `all[to]` 写反
 *    （`PixFoldApp.kt:793-794`）得到的结果与正确实现**一模一样**，变异存活。
 * 2. **「使用中」标记**：只有一行时「只标了一行」与「标了所有行」也是同一个观测，
 *    把 `currentTreeUri` 置 null 与不置 null 同理。
 *
 * 两条 entry 才把这几组区分开。评审是用临时探针（ProbeMoveWiringTest /
 * ProbeInUseMarkerTest）实测证明这一点的，那两个探针已删；本类即它们的正式替身。
 *
 * ## 为什么额外暴露 [moveCalls]
 *
 * `moveWorkspace(from, to)` 收到的是**下标**。下标在断言里读不出对错
 * （`(0, 1)` 与 `(1, 0)` 都得回头看调用方才知道谁是谁），所以这里在调用瞬间就把
 * 下标翻成 treeUri 记下来，让测试能写成 `assert(listOf(alpha to beta))`。
 *
 * ## 与 ReadyWorkspaceSource 的一致性
 *
 * 形状照抄它：内容取 [MockData.workspaceA]、`rememberWorkspace` 恒 `Added`、
 * `readBytes` 恒 null、不覆盖 `watchChanges`。**两条 entry 都算「已授权」**
 * （[persistentGrants] 含两者），否则其中一条会掉进「只记住了路径」下区，
 * 拖拽就在两个分区之间、走的是另一条代码路径。
 */
class TwoEntrySource(
    val handleA: WorkspaceHandle = WorkspaceHandle(
        treeUri = "content://com.cyanix.pixfold.test/tree/alpha",
        displayName = "目录甲",
    ),
    val handleB: WorkspaceHandle = WorkspaceHandle(
        treeUri = "content://com.cyanix.pixfold.test/tree/beta",
        displayName = "目录乙",
    ),
    private val collections: List<ImageCollection> = MockData.workspaceA.collections,
) : WorkspaceSource {

    val uriA: String get() = handleA.treeUri
    val uriB: String get() = handleB.treeUri

    /** 顺序 = 界面上的行序：[uriA] 是第 1 行（当前工作区），[uriB] 是第 2 行。 */
    private val entries = mutableListOf(
        WorkspaceEntry(handleA, pinned = false),
        WorkspaceEntry(handleB, pinned = false),
    )

    /**
     * 每次 `moveWorkspace` 收到的实参，已经翻成 `(被拖的 treeUri, 目标 treeUri)`。
     *
     * **只有越界的下标才不记录**（那是调用方 bug，让它以「一次都没收到」暴露出来，
     * 而不是被 toString 掩盖成一条看似合理的记录）。
     */
    val moveCalls = mutableListOf<Pair<String, String>>()

    override fun workspaces(): List<WorkspaceEntry> = entries.toList()

    override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult = RememberResult.Added

    override fun forgetWorkspace(treeUri: String) = Unit

    override fun setPinned(treeUri: String, pinned: Boolean) = Unit

    /**
     * 真的把顺序换掉 —— 但**落点与生产实现不完全一致**，别拿它断行序。
     *
     * 生产实现是 `SafTreeStore.move`（`removeAt(from)` 后 `add(to.coerceIn(0, size))`），
     * 这里照抄了同样的两步，差别在于「先删再按 to 插入」在 `from < to` 时会比直觉多前进一格：
     * 把 A 拖到 B（from=0, to=1）的结果是 `[B, A]` 而不是「A 停在 B 原来的位置上」那种
     * 需要调用方做 `to - 1` 修正的语义。生产侧由 `movedOrder`（`WorkspaceOrdering.kt:31`）
     * 先算好落点，本替身没有那一层。
     *
     * ⇒ **本类是「非生产语义替身」**：它只保证「`moveCalls` 记下的 treeUri 对」与
     * 「`workspaces()` 的顺序真的变了」两件事。任何基于 `workspaces()` 断具体行序的
     * 断言都会读到假源自己的序，而不是生产序（2026-10-26 Task 7 复审 N-1）。
     */
    override fun moveWorkspace(from: Int, to: Int) {
        val fromUri = entries.getOrNull(from)?.handle?.treeUri ?: return
        val toUri = entries.getOrNull(to)?.handle?.treeUri ?: return
        moveCalls += fromUri to toUri

        // 真的把顺序换掉：真实源在这里落盘，界面随后的 refreshListings 会读到新序。
        // 不换的话「拖完看起来没动」,后续断言容易被假序误导。
        val moved = entries.removeAt(from)
        entries.add(to.coerceIn(0, entries.size), moved)
    }

    /** 两条都在，界面才把两行都画在上分区（可拖拽区）。 */
    override fun persistentGrants(): Set<String> = setOf(uriA, uriB)

    override fun loadCollections(handle: WorkspaceHandle): LoadResult =
        LoadResult.Success(collections)

    override fun readBytes(item: SourceItem): ByteArray? = null
}
