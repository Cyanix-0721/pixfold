package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.mock.MockDataB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 危险动作的确认序列(规格 §9.2 / 验收第 16 项)。
 *
 * 第 16 项的关键词是「**默认关闭** + **独立二次确认**」。故这里断言三件事:
 * 1. 默认值下**不产生**任何危险确认(默认关闭);
 * 2. 选中危险动作后,**恰好**多出一步独立确认(不是零次、也不是并进通用确认);
 * 3. 未选中的动作**不产生**步骤(不会让用户白确认)。
 */
class DangerActionTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val op102 = library.volumes.first { it.id == "op-102" } // outputExists = true

    // ---- 默认关闭 ----

    @Test
    fun `default plan has no danger actions at all`() {
        val plans = buildPackagePlans(library.volumes)
        val dangers = effectiveDangerActions(plans)
        assertTrue(dangers.isEmpty(), "默认（Keep + Rename + 不清理）不该有任何危险动作，实际 $dangers")
    }

    @Test
    fun `default sequence is exactly one general confirmation`() {
        val plans = buildPackagePlans(library.volumes)
        val seq = buildConfirmSequence(effectiveDangerActions(plans), generalSummaryLines(plans, emptySet()))
        assertEquals(1, seq.size, "安全操作只需通用确认，实际 $seq")
        assertTrue(seq.single() is ConfirmStep.General)
    }

    // ---- 删除源目录 ----

    @Test
    fun `delete source policy yields exactly one extra danger step`() {
        val plans = buildPackagePlans(library.volumes, sourcePolicy = SourcePolicy.Delete)
        val dangers = effectiveDangerActions(plans)
        assertEquals(setOf(DangerAction.DeleteSourceDir), dangers)

        val seq = buildConfirmSequence(dangers, generalSummaryLines(plans, dangers))
        assertEquals(2, seq.size, "通用确认 + 1 次危险确认 = 恰好两次，实际 $seq")
        assertTrue(seq[0] is ConfirmStep.General, "第 1 步必须是通用确认")
        assertEquals(DangerAction.DeleteSourceDir, (seq[1] as ConfirmStep.Danger).action)
    }

    @Test
    fun `delete danger is not triggered when every volume with Delete policy is skipped`() {
        // 唯一一卷被跳过 -> 不会真的删任何东西 -> 不该要求危险确认
        val only = library.volumes.filter { it.id == "op-102" }
        val plans = buildPackagePlans(only, conflictPolicy = OutputConflictPolicy.Skip, sourcePolicy = SourcePolicy.Delete)
        assertTrue(plans.all { it.skipped })
        assertTrue(
            effectiveDangerActions(plans).isEmpty(),
            "全部跳过时不存在实际删除，不该弹危险确认",
        )
    }

    // ---- 覆盖已有 ----

    @Test
    fun `overwrite only counts as danger when a conflict actually exists`() {
        val noConflict = buildPackagePlans(listOf(aot1), conflictPolicy = OutputConflictPolicy.Overwrite)
        assertTrue(
            effectiveDangerActions(noConflict).isEmpty(),
            "无冲突时 Overwrite 不构成覆盖,不该让用户白确认一次",
        )

        val withConflict = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite)
        assertEquals(setOf(DangerAction.OverwriteExisting), effectiveDangerActions(withConflict))
    }

    @Test
    fun `overwrite danger is absent under the default rename policy`() {
        val plans = buildPackagePlans(listOf(op102))
        assertTrue(effectiveDangerActions(plans).isEmpty(), "默认 Rename 不覆盖任何东西")
    }

    // ---- 清理空目录(工作流 A) ----

    @Test
    fun `clean empty dirs is off by default and adds a danger step when enabled`() {
        val plans = buildPackagePlans(listOf(aot1))
        assertTrue(effectiveDangerActions(plans).isEmpty())
        assertEquals(
            setOf(DangerAction.CleanEmptyDirs),
            effectiveDangerActions(plans, cleanEmptyDirs = true),
        )
    }

    // ---- 组合与顺序 ----

    @Test
    fun `each danger action gets its own step rather than being merged`() {
        val plans = buildPackagePlans(
            library.volumes,
            conflictPolicy = OutputConflictPolicy.Overwrite,
            sourcePolicy = SourcePolicy.Delete,
        )
        val dangers = effectiveDangerActions(plans, cleanEmptyDirs = true)
        assertEquals(3, dangers.size)
        val seq = buildConfirmSequence(dangers, generalSummaryLines(plans, dangers))
        assertEquals(4, seq.size, "1 通用 + 3 危险 = 4 步；不得合并成一次确认")
        assertEquals(DANGER_DIALOG_TITLE, "⚠️ 危险操作独立确认")
        val dangerSteps = seq.filterIsInstance<ConfirmStep.Danger>().map { it.action }
        assertEquals(
            listOf(DangerAction.DeleteSourceDir, DangerAction.OverwriteExisting, DangerAction.CleanEmptyDirs),
            dangerSteps,
            "危险确认顺序必须固定,否则同一次操作两次运行弹出的第一个框会不同",
        )
    }

    // ---- 影响范围 ----

    @Test
    fun `general summary always lists the impact scope`() {
        val plans = buildPackagePlans(library.volumes, conflictPolicy = OutputConflictPolicy.Skip)
        val lines = generalSummaryLines(plans, emptySet())
        assertTrue(lines.any { "将处理 4 卷" in it && "跳过 1 卷" in it }, "必须列出卷数，实际 $lines")
        assertTrue(lines.any { "源目录：保留" in it }, "必须说明源目录去向，实际 $lines")
        assertTrue(lines.none { "删除（危险）" in it }, "未选删除时不该出现危险字样")
    }

    @Test
    fun `general summary flags the danger when source deletion is selected`() {
        val plans = buildPackagePlans(library.volumes, sourcePolicy = SourcePolicy.Delete)
        val dangers = effectiveDangerActions(plans)
        val lines = generalSummaryLines(plans, dangers)
        assertTrue(lines.any { "源目录：删除（危险）" in it }, "通用确认必须写出影响范围，实际 $lines")
    }

    @Test
    fun `general summary names the concrete files that would be overwritten`() {
        val plans = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite)
        val dangers = effectiveDangerActions(plans)
        val lines = generalSummaryLines(plans, dangers)
        assertTrue(
            lines.any { "E:\\漫画库\\_output/海贼王 第102卷.cbz" in it },
            "覆盖确认必须列出**具体哪个文件**,实际 $lines",
        )
    }

    @Test
    fun `general summary warns about clean empty dirs only when enabled`() {
        val plans = buildPackagePlans(listOf(aot1))
        assertTrue(generalSummaryLines(plans, emptySet()).none { "清理空目录" in it })
        assertTrue(
            generalSummaryLines(plans, setOf(DangerAction.CleanEmptyDirs)).any { "清理空目录" in it },
        )
    }

    @Test
    fun `safety note is present even when nothing dangerous is selected`() {
        val plans = buildPackagePlans(library.volumes)
        val lines = generalSummaryLines(plans, emptySet())
        assertFalse(lines.isEmpty())
        assertTrue(
            lines.any { "源文件不会被改动" in it },
            "安全操作也要说清「不删任何东西」，实际 $lines",
        )
    }
}
