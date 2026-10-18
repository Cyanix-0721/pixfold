package com.cyanix.pixfold.domain.plan

import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.naming.setRootDirNameOnScheme
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey
import com.cyanix.pixfold.domain.workflowa.workflowAStateOf
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
                components = listOf(com.cyanix.pixfold.domain.model.NameComponent(com.cyanix.pixfold.domain.model.NameComponentKind.OrigName)),
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
        val keys = lines.map { it.key }
        assertTrue(TextKey.RenameSummarySourceKept in keys, "必须说明不删文件，实际 $keys")
        assertEquals(
            Msg(TextKey.RenameSummary, listOf(plan.runnable, plan.skipped)),
            lines.first(),
            "首行必须是「将重命名 N 项（跳过 M）」并带上真实计数",
        )
        assertTrue(TextKey.RenameSummaryCleanEmptyDirs !in keys, "未勾选时不该提清理")
    }

    @Test
    fun `rename summary warns when clean empty dirs is selected`() {
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        val lines = renameSummaryLines(plan, setOf(DangerAction.CleanEmptyDirs))
        assertTrue(
            TextKey.RenameSummaryCleanEmptyDirs in lines.map { it.key },
            "勾选后必须警告，实际 ${lines.map { it.key }}",
        )
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

    @Test
    fun `collection names carry the collection name not its root dir name`() {
        // 计划页每一行的行首标注"这条属于哪个集合",读的就是这张表
        // (ui/workflowa/RenamePlanPage.kt 用 collectionId 取)。
        // 若这里记成 rootDirName,当集合名与根目录名不同(用户改过根名)时,
        // 用户就会看到"旅行的照片"被标成"旅行照片"这类**看起来对但认不出**的标签。
        val plan = buildLibraryRenamePlan(collections, state.orders, naming)
        collections.forEach { c ->
            assertEquals(
                c.name,
                plan.collectionNames[c.id],
                "集合 ${c.id} 的标签必须是集合名而非根目录名",
            )
        }
        // 本组 mock 里名与根目录名恰好相同,故再钉一条"改动根名不影响标签"的差分断言:
        // 否则上面那条在 mock 上恒真,拦不住"记 rootDirName"的写法。
        val renamed = collections.map { it.copy(rootDirName = "改过的根名") }
        val plan2 = buildLibraryRenamePlan(renamed, state.orders, naming)
        assertEquals(
            renamed.map { it.name },
            renamed.map { plan2.collectionNames[it.id] },
            "根目录名改了也不该改变集合标签",
        )
    }

    @Test
    fun `plan counts expose the runnable and skipped split of the library plan`() {
        // `.counts` 是统一操作词汇对外的那一个口子;两个数若对调,
        // 确认框就会读成"将重命名 M 项(跳过 N)"——数字都有,只是含义反了。
        val noIndex = NamingState(
            NamingScheme(
                rootDirName = "根",
                components = listOf(com.cyanix.pixfold.domain.model.NameComponent(com.cyanix.pixfold.domain.model.NameComponentKind.OrigName)),
            ),
        )
        val plan = buildLibraryRenamePlan(collections, state.orders, noIndex)
        assertTrue(plan.skipped > 0, "本用例需要真的有跳过项，实际 ${plan.skipped}")
        assertTrue(plan.runnable > 0, "本用例需要真的有可运行项，实际 ${plan.runnable}")
        assertEquals(plan.total, plan.counts.total)
        assertEquals(plan.skipped, plan.counts.skipped)
        assertEquals(plan.runnable, plan.counts.runnable)
    }
}
