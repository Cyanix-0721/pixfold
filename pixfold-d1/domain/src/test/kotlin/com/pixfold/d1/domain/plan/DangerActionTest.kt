package com.pixfold.d1.domain.plan

import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
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
 *
 * **断言的是密钥而非文案**:领域层产出 [Msg](纯数据,不含语言),文案在 UI 层解析。
 * 故这里只断言 `key` 与 `args` —— 换语言(或改译文)不会让本文件变红,
 * 而"漏了一行""参数传错"仍会被抓住。成品文案的逐字断言在 app 侧资源测试里。
 */
class DangerActionTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val op102 = library.volumes.first { it.id == "op-102" } // outputExists = true

    // ---- 断言辅助(全部只看 TextKey / args,不看语言)----

    private fun List<Msg>.keys(): List<TextKey> = map { it.key }

    private fun List<Msg>.find(key: TextKey): Msg? = firstOrNull { it.key == key }

    /** 取某行的**唯一**参数(用来断言嵌套 Msg 或数值)。 */
    private fun Msg.singleArg(): Any = args.single()

    /** 「源目录：X」这一行里嵌的 X 是哪个键。 */
    private fun List<Msg>.sourceDirKey(): TextKey? =
        find(TextKey.SummarySourceDirPrefix)?.singleArg()?.let { (it as Msg).key }

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
        val dangerSteps = seq.filterIsInstance<ConfirmStep.Danger>().map { it.action }
        assertEquals(
            listOf(DangerAction.DeleteSourceDir, DangerAction.OverwriteExisting, DangerAction.CleanEmptyDirs),
            dangerSteps,
            "危险确认顺序必须固定,否则同一次操作两次运行弹出的第一个框会不同",
        )
    }

    @Test
    fun `each danger action has its own distinct wording`() {
        // 每个危险动作的三份文案必须两两不同 —— 否则两个动作会弹出字面相同的框,
        // 用户无法从文字上分辨自己正在放行哪一个不可恢复动作。
        // (标题「⚠️ 危险操作独立确认」是共享的,故这里只查动作自有的 label/body/button。)
        for (keySelector in listOf(
            { a: DangerAction -> a.labelKey },
            { a: DangerAction -> a.bodyKey },
            { a: DangerAction -> a.buttonKey },
        )) {
            val keys = DangerAction.entries.map { keySelector(it) }
            assertEquals(keys.size, keys.toSet().size, "危险动作文案键重复：$keys")
        }
    }

    // ---- 影响范围 ----

    @Test
    fun `general summary always lists the impact scope`() {
        val plans = buildPackagePlans(library.volumes, conflictPolicy = OutputConflictPolicy.Skip)
        val lines = generalSummaryLines(plans, emptySet())
        assertEquals(
            Msg(TextKey.SummaryProcessingVolumes, listOf(4, 1)),
            lines.find(TextKey.SummaryProcessingVolumes),
            "必须列出卷数（已处理 / 跳过），实际 ${lines.keys()}",
        )
        assertEquals(
            TextKey.SummarySourceDirKeep,
            lines.sourceDirKey(),
            "必须说明源目录去向，实际 ${lines.keys()}",
        )
        assertEquals(
            null,
            lines.sourceDirKey()?.takeIf { it == TextKey.SummarySourceDirDelete },
            "未选删除时不该出现危险字样",
        )
    }

    @Test
    fun `general summary flags the danger when source deletion is selected`() {
        val plans = buildPackagePlans(library.volumes, sourcePolicy = SourcePolicy.Delete)
        val dangers = effectiveDangerActions(plans)
        val lines = generalSummaryLines(plans, dangers)
        assertEquals(
            TextKey.SummarySourceDirDelete,
            lines.sourceDirKey(),
            "通用确认必须写出影响范围，实际 ${lines.keys()}",
        )
    }

    @Test
    fun `general summary never claims it only writes files when source deletion is on`() {
        // 真机核对时发现:选了「删除源目录」,末条仍写"本操作只写入输出文件" ——
        // 与上一行的「源目录：删除（危险）」直接矛盾。此用例钉死该矛盾不得复现。
        val plans = buildPackagePlans(library.volumes, sourcePolicy = SourcePolicy.Delete)
        val dangers = effectiveDangerActions(plans)
        val lines = generalSummaryLines(plans, dangers)

        assertEquals(TextKey.SummarySourceDirDelete, lines.sourceDirKey(), "应列出删除影响，实际 ${lines.keys()}")
        assertTrue(
            TextKey.SummaryWriteOnly !in lines.keys(),
            "选了删除源目录就不得再说「只写入输出文件」，实际 ${lines.keys()}",
        )
        assertTrue(
            TextKey.SummaryDeleteSourceDirWarning in lines.keys(),
            "应明确告知源目录将被删除且不可恢复，实际 ${lines.keys()}",
        )
    }

    @Test
    fun `general summary names the concrete files that would be overwritten`() {
        val plans = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite)
        val dangers = effectiveDangerActions(plans)
        val lines = generalSummaryLines(plans, dangers)
        assertEquals(
            Msg(TextKey.SummaryOverwriteCbz, listOf(listOf("E:\\漫画库\\_output/海贼王 第102卷.cbz"))),
            lines.find(TextKey.SummaryOverwriteCbz),
            "覆盖确认必须列出**具体哪个文件**，实际 ${lines.keys()}",
        )
    }

    @Test
    fun `overwrite list names only the files that would really be overwritten`() {
        // 被跳过的卷**不会**写输出,故其文件不得出现在"将被覆盖"清单里让用户白担心。
        // 生产路径下 Overwrite 策略不会产生跳过项(跳过只来自 Skip 策略),
        // 这里直接构造计划,把该契约钉死为**防御性**保证。
        val live = buildPackagePlans(listOf(op102), conflictPolicy = OutputConflictPolicy.Overwrite).single()
        val skipped = VolumePackPlan(
            volume = op102.copy(id = "op-102-skipped"),
            outputDisplay = live.outputDisplay,
            pageCount = live.pageCount,
            skipped = true,
            skipReason = Msg(TextKey.PackSkipOutputExists, listOf(live.outputPath)),
            conflictPolicy = OutputConflictPolicy.Overwrite,
            sourcePolicy = SourcePolicy.Keep,
        )
        val plans = listOf(live, skipped)
        val lines = generalSummaryLines(plans, setOf(DangerAction.OverwriteExisting))
        assertEquals(
            Msg(TextKey.SummaryOverwriteCbz, listOf(listOf(live.outputPath))),
            lines.find(TextKey.SummaryOverwriteCbz),
            "清单只能列出真会被覆盖的文件,实际 ${lines.keys()}",
        )
    }

    @Test
    fun `general summary warns about clean empty dirs only when enabled`() {
        val plans = buildPackagePlans(listOf(aot1))
        assertTrue(TextKey.SummaryCleanEmptyDirsWarning !in generalSummaryLines(plans, emptySet()).keys())
        assertTrue(
            TextKey.SummaryCleanEmptyDirsWarning in
                generalSummaryLines(plans, setOf(DangerAction.CleanEmptyDirs)).keys(),
        )
    }

    @Test
    fun `safety note is present even when nothing dangerous is selected`() {
        val plans = buildPackagePlans(library.volumes)
        val lines = generalSummaryLines(plans, emptySet())
        assertFalse(lines.isEmpty())
        assertTrue(
            TextKey.SummaryWriteOnly in lines.keys(),
            "安全操作也要说清「不删任何东西」，实际 ${lines.keys()}",
        )
    }
}
