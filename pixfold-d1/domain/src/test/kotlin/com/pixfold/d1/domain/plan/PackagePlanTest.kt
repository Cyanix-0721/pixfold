package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.LangChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 打包计划与 `describe()` 文案契约(规格 §9.1 / 验收第 15 项)。
 *
 * 第 15 项要的是「操作清单 + 冲突/跳过原因」——故测试必须断言**文案内容**,
 * 而不是"有个列表非空"。这样才能保证计划真的说清了"要做什么"。
 */
class PackagePlanTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val op102 = library.volumes.first { it.id == "op-102" } // outputExists = true

    // ---- 只处理 included ----

    @Test
    fun `plans only cover included volumes`() {
        val plans = buildPackagePlans(library.volumes.map { it.copy(included = false) })
        assertTrue(plans.isEmpty(), "全部未勾选时不该产生任何计划")
    }

    @Test
    fun `excluded volumes are absent from plans so the report cannot surprise the user`() {
        val volumes = library.volumes.map { if (it.id == "aot-1") it.copy(included = false) else it }
        val plans = buildPackagePlans(volumes)
        assertEquals(4, plans.size)
        assertTrue(plans.none { it.volume.id == "aot-1" })
    }

    // ---- describe() 文案契约(§9.1 按序) ----

    @Test
    fun `describe follows the contract order for a normal volume`() {
        val plan = buildPackagePlans(listOf(aot1)).single()
        val lines = plan.describe()
        assertEquals(3, lines.size, "无危险动作时应为 3 条")
        assertTrue(lines[0].startsWith("创建 CBZ → "), "第 1 条应为创建 CBZ，实际 ${lines[0]}")
        assertTrue(lines[1].startsWith("写入 ComicInfo.xml（"), "第 2 条应为写入 XML，实际 ${lines[1]}")
        assertTrue(lines[2].startsWith("按页序打包 16 张并重命名为页码（0001 起）"), "第 3 条实际 ${lines[2]}")
    }

    @Test
    fun `describe includes the actual metadata values in the xml line`() {
        val plan = buildPackagePlans(listOf(aot1)).single()
        val xmlLine = plan.describe()[1]
        assertTrue("Title=进击的巨人" in xmlLine, "应含实际 Title，实际 $xmlLine")
        assertTrue("Series=进击的巨人" in xmlLine, "应含实际 Series，实际 $xmlLine")
        assertTrue("Number=1" in xmlLine, "应含实际 Number，实际 $xmlLine")
        assertTrue("LanguageISO=不写入" in xmlLine, "语言未确认应写「不写入」，实际 $xmlLine")
    }

    @Test
    fun `describe renders empty writer as the literal 空`() {
        val plan = buildPackagePlans(listOf(aot1.copy(writer = ""))).single()
        assertTrue("Writer=空" in plan.describe()[1], "空作者应显示「空」，实际 ${plan.describe()[1]}")
    }

    @Test
    fun `describe renders missing volume number as 未设置`() {
        val aot0 = library.volumes.first { it.id == "aot-0" } // volume = null
        val plan = buildPackagePlans(listOf(aot0)).single()
        assertTrue("Number=未设置" in plan.describe()[1], "缺卷号应显示「未设置」，实际 ${plan.describe()[1]}")
    }

    @Test
    fun `describe writes the langIso when language is confirmed`() {
        val plan = buildPackagePlans(listOf(aot1.copy(language = LangChoice.Ja))).single()
        assertTrue("LanguageISO=ja" in plan.describe()[1], "已确认 ja 应写入，实际 ${plan.describe()[1]}")
    }

    @Test
    fun `describe appends the source deletion line only under Delete policy`() {
        val keep = buildPackagePlans(listOf(aot1), sourcePolicy = SourcePolicy.Keep).single()
        assertEquals(3, keep.describe().size, "Keep 不应出现删除行")

        val del = buildPackagePlans(listOf(aot1), sourcePolicy = SourcePolicy.Delete).single()
        val lines = del.describe()
        assertEquals(4, lines.size)
        assertEquals("打包后删除源目录：E:\\漫画库\\进击的巨人\\第01卷", lines[3])
    }

    // ---- 冲突与跳过 ----

    @Test
    fun `rename policy adds a sequence number before the extension`() {
        val plan = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Rename).single()
        assertFalse(plan.skipped, "默认 Rename 不跳过")
        assertEquals("海贼王 第102卷 (1).cbz", plan.outputDisplay)
    }

    @Test
    fun `rename policy inserts before the LAST dot not the first`() {
        // 回归:mock 里的 CBZ 名只含一个点,故"首个点"与"最后一个点"结果相同 ——
        // 变异"改用第一个点"因此**无法被捕获**(实测发现)。这里用**多点文件名**
        // 真正钉住"最后一个点"这个语义(§9.1 明确写的是 last dot)。
        val multi = op102.copy(cbzFileName = "海贼王 第102卷.v2.final.cbz")
        val plan = buildPackagePlans(listOf(multi), conflictPolicy = OutputConflictPolicy.Rename).single()
        assertEquals(
            "海贼王 第102卷.v2.final (1).cbz",
            plan.outputDisplay,
            "序号必须加在**最后一个**点之前(扩展名前)，而不是第一个点前",
        )
    }

    @Test
    fun `rename policy appends the suffix even when there is no extension`() {
        assertEquals(
            "无扩展名 (1)",
            outputDisplayOf("无扩展名", OutputConflictPolicy.Rename, outputExists = true),
        )
    }

    @Test
    fun `no conflict means the name is unchanged regardless of policy`() {
        for (policy in OutputConflictPolicy.entries) {
            assertEquals(
                "x.cbz",
                outputDisplayOf("x.cbz", policy, outputExists = false),
                "$policy 在无冲突时不该改名",
            )
        }
    }

    @Test
    fun `skip policy marks the plan skipped and describe returns exactly one line with the reason`() {
        val plan = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Skip).single()
        assertTrue(plan.skipped)
        assertEquals("跳过：输出已存在 E:\\漫画库\\_output/海贼王 第102卷.cbz", plan.describe().single())
        assertEquals("输出已存在 E:\\漫画库\\_output/海贼王 第102卷.cbz", plan.skipReason)
    }

    @Test
    fun `skip policy does NOT skip volumes without a conflict`() {
        val plans = buildPackagePlans(listOf(aot1, op102), conflictPolicy = OutputConflictPolicy.Skip)
        assertFalse(plans.first { it.volume.id == "aot-1" }.skipped, "无冲突的卷不该被跳过")
        assertTrue(plans.first { it.volume.id == "op-102" }.skipped)
    }

    @Test
    fun `overwrite policy neither skips nor renames`() {
        val plan = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite).single()
        assertFalse(plan.skipped)
        assertEquals("海贼王 第102卷.cbz", plan.outputDisplay, "Overwrite 不该加序号")
    }

    // ---- 计数与快照 ----

    @Test
    fun `plan counts distinguish runnable from skipped`() {
        val plans = buildPackagePlans(library.volumes, conflictPolicy = OutputConflictPolicy.Skip)
        val counts = planCountsOf(plans)
        assertEquals(5, counts.total)
        assertEquals(1, counts.skipped, "只有 op-102 有输出冲突")
        assertEquals(4, counts.runnable)
    }

    @Test
    fun `pageCount is a snapshot of the page order at plan time`() {
        // 计划生成后改页序,计划里的张数不应跟着变(归档语义:快照,非惰性求值)
        val plan = buildPackagePlans(listOf(aot1)).single()
        assertEquals(16, plan.pageCount)
        val shrunk = aot1.copy(pageOrder = aot1.pageOrder.copy(order = aot1.pageOrder.order.take(3)))
        assertEquals(16, plan.pageCount, "计划是快照,不应随卷变化")
        assertEquals(3, shrunk.pageCount)
    }
}
