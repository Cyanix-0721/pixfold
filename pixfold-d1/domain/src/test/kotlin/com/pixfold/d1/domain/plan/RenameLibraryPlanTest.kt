package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.naming.setRootDirNameOnScheme
import com.pixfold.d1.domain.workflowa.workflowAStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工作流 A 的整库改名计划(规格 §6.4 末条)与 A 侧危险动作。
 *
 * 重点:计划必须**遍历所有集合**,否则用户在当前集合看不到其它集合的重名冲突。
 */
class RenameLibraryPlanTest {

    private val collections = MockData.workspaceA.collections
    private val state = workflowAStateOf(collections)
    private val naming = NamingState(NamingScheme(rootDirName = "任意"))

    @Test
    fun `plan covers every collection not just the active one`() {
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        val expected = collections.sumOf { it.images.size }
        assertEquals(expected, plan.total, "整库计划应含所有集合的全部项")
        assertEquals(collections.size, plan.collectionNames.size)
    }

    @Test
    fun `plan order follows collection order then page order`() {
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        val firstCollection = collections.first()
        val firstId = state.orders[firstCollection.id]!!.order.first().id
        assertEquals(firstId, plan.ops.first().id, "首项应为第一个集合的首项")
    }

    @Test
    fun `root dir name follows each collection so the plan matches what will execute`() {
        // 关键:批次级 namingState,但根目录名按集合各自生效 —— 计划必须反映这一点
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        val byId = plan.ops.associateBy { it.id }
        collections.forEach { c ->
            val firstItem = state.orders[c.id]!!.order.first()
            val op = byId[firstItem.id]!!
            assertTrue(
                op.finalName.startsWith(c.rootDirName),
                "集合 ${c.name} 的首项应以自己的根目录名 ${c.rootDirName} 开头，实际 ${op.finalName}",
            )
        }
    }

    @Test
    fun `an explicit root dir name override applies to every collection`() {
        val naming2 = setRootDirNameOnScheme(naming, "统一根名")
        val plan = buildLibraryRenamePlan(collections, state.orders, naming2)
        assertTrue(plan.ops.all { it.finalName.startsWith("统一根名") })
    }

    @Test
    fun `missing orders fall back to the collection's own images instead of dropping it`() {
        val plan = buildLibraryRenamePlan(collections, emptyMap(), naming)
        assertEquals(collections.sumOf { it.images.size }, plan.total, "缺页序时不得静默丢集合")
    }

    @Test
    fun `skipped count reflects duplicate conflicts across the library`() {
        // scan 集合内 ch01/page_01 与 ch02/page_01 的 baseName 相同;关掉序号组件后必然重名
        val noIndex = NamingState(
            NamingScheme(
                rootDirName = "根",
                components = listOf(com.pixfold.d1.domain.model.NameComponent(com.pixfold.d1.domain.model.NameComponentKind.OrigName)),
            ),
        )
        val plan = buildLibraryRenamePlan(collections, state.orders, noIndex)
        assertTrue(plan.skipped > 0, "关掉序号后应出现重名跳过，实际 skipped=${plan.skipped}")
        assertEquals(plan.total - plan.skipped, plan.runnable)
    }

    // ---- A 侧危险动作 ----

    @Test
    fun `clean empty dirs is off by default`() {
        assertTrue(workflowADangerActions(cleanEmptyDirs = false).isEmpty())
        assertEquals(setOf(DangerAction.CleanEmptyDirs), workflowADangerActions(cleanEmptyDirs = true))
    }

    @Test
    fun `rename summary states that source files are preserved`() {
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        val lines = renameSummaryLines(plan, emptySet())
        assertTrue(lines.any { "源文件保留" in it }, "必须说明不删文件，实际 $lines")
        assertTrue(lines.any { "将重命名" in it })
        assertTrue(lines.none { "清理空目录" in it }, "未勾选时不该提清理")
    }

    @Test
    fun `rename summary warns when clean empty dirs is selected`() {
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        val lines = renameSummaryLines(plan, setOf(DangerAction.CleanEmptyDirs))
        assertTrue(lines.any { "清理空目录" in it }, "勾选后必须警告，实际 $lines")
    }

    @Test
    fun `rename danger sequence is general plus one danger step`() {
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        val dangers = workflowADangerActions(cleanEmptyDirs = true)
        val seq = buildConfirmSequence(dangers, renameSummaryLines(plan, dangers))
        assertEquals(2, seq.size, "通用 + 清理空目录独立确认 = 恰好两次")
        assertEquals(DangerAction.CleanEmptyDirs, (seq[1] as ConfirmStep.Danger).action)
    }

    @Test
    fun `rename without danger needs only one confirmation`() {
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        val seq = buildConfirmSequence(emptySet(), renameSummaryLines(plan, emptySet()))
        assertEquals(1, seq.size, "安全的重命名只需一次通用确认")
        assertTrue(seq.single() is ConfirmStep.General)
    }
}
