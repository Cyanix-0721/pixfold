package com.cyanix.pixfold.domain.plan

import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.comic.defaultCbzName
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 打包计划与 `describe()` 契约(规格 §9.1 / 验收第 15 项)。
 *
 * 第 15 项要的是「操作清单 + 冲突/跳过原因」——故测试必须断言**计划内容**,
 * 而不是"有个列表非空"。这样才能保证计划真的说清了"要做什么"。
 *
 * **断言的是 [Msg] 结构(key + args)而非成品文案**:领域层不产出语言,
 * 成品文案在 app 侧由 `textOf(...)` 解析。故本文件在换语言后仍然有效,
 * 而"漏了一条""参数传错/传反"会被抓住。
 * 逐字文案由 `android/app/src/test/.../PlanUiTest.kt` 那一侧守卫。
 */
class PackagePlanTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val op102 = library.volumes.first { it.id == "op-102" } // outputExists = true

    /** `describe()` 产出的键序列 —— 顺序本身是 §9.1 契约的一部分。 */
    private fun List<Msg>.keys(): List<TextKey> = map { it.key }

    private fun List<Msg>.line(key: TextKey): Msg = single { it.key == key }

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

    // ---- describe() 契约(§9.1 按序) ----

    @Test
    fun `describe follows the contract order for a normal volume`() {
        val plan = buildPackagePlans(listOf(aot1)).single()
        val lines = plan.describe()
        assertEquals(3, lines.size, "无危险动作时应为 3 条")
        assertEquals(
            listOf(TextKey.PackCreateCbz, TextKey.PackWriteComicInfo, TextKey.PackPackPages),
            lines.keys(),
            "第 1 条创建 CBZ / 第 2 条写入 XML / 第 3 条按页序打包",
        )
        assertEquals(listOf("进击的巨人 第01卷.cbz"), lines.line(TextKey.PackCreateCbz).args)
        assertEquals(
            Msg(TextKey.PackPackPages, listOf(16)),
            lines.line(TextKey.PackPackPages),
            "第 3 条必须带上真实的张数(快照值)",
        )
    }

    @Test
    fun `describe includes the actual metadata values in the xml line`() {
        val plan = buildPackagePlans(listOf(aot1)).single()
        val xmlLine = plan.describe().line(TextKey.PackWriteComicInfo)
        val (title, series, number, _, lang) = xmlLine.args
        assertEquals("进击的巨人", title, "应含实际 Title")
        assertEquals("进击的巨人", series, "应含实际 Series")
        assertEquals("1", number, "应含实际 Number")
        assertEquals(TextKey.PackValueNotWritten, (lang as Msg).key, "语言未确认应写「不写入」")
    }

    @Test
    fun `describe renders empty writer as the placeholder key`() {
        val plan = buildPackagePlans(listOf(aot1.copy(writer = ""))).single()
        val writer = plan.describe().line(TextKey.PackWriteComicInfo).args[3]
        // 占位词必须是**键**而不是中文字面量 —— 否则英文界面下会冒出中文「空」。
        assertEquals(TextKey.PackValueEmpty, (writer as Msg).key, "空作者应走可翻译的占位键")
    }

    @Test
    fun `describe renders missing volume number as the placeholder key`() {
        val aot0 = library.volumes.first { it.id == "aot-0" } // volume = null
        val plan = buildPackagePlans(listOf(aot0)).single()
        val number = plan.describe().line(TextKey.PackWriteComicInfo).args[2]
        assertEquals(TextKey.PackValueUnset, (number as Msg).key, "缺卷号应走可翻译的占位键")
    }

    @Test
    fun `describe writes the langIso when language is confirmed`() {
        val plan = buildPackagePlans(listOf(aot1.copy(language = LangChoice.Ja))).single()
        val lang = plan.describe().line(TextKey.PackWriteComicInfo).args[4]
        // 真实语言码是**数据**(ComicInfo.xml 的 LanguageISO 字段),不翻译,故仍是裸字符串。
        assertEquals("ja", lang, "已确认 ja 应写入真实语言码")
    }

    @Test
    fun `describe appends the source deletion line only under Delete policy`() {
        val keep = buildPackagePlans(listOf(aot1), sourcePolicy = SourcePolicy.Keep).single()
        assertEquals(3, keep.describe().size, "Keep 不应出现删除行")

        val del = buildPackagePlans(listOf(aot1), sourcePolicy = SourcePolicy.Delete).single()
        val lines = del.describe()
        assertEquals(4, lines.size)
        assertEquals(
            Msg(TextKey.PackDeleteSourceDir, listOf("E:\\漫画库\\进击的巨人\\第01卷")),
            lines[3],
        )
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
    fun `rename policy does not strip a leading dot`() {
        // 回归:`dot <= 0` 同时管着两种"没有扩展名"的形态 —— 完全没有点(-1)与
        // **点在最前面**(0)。上一条只钉了前者,于是把判定放宽成 `dot < 0` 的变异
        // 存活(实测)。点在最前是**可达**的:`defaultCbzName` 在标题建议为空串时
        // 就给出 ".cbz";用户也能在 B 侧元数据页把 CBZ 名改成任意文本。
        assertEquals(
            ".cbz",
            defaultCbzName("fallback-id", Suggestion("", "空标题建议"), Suggestion(null, "无卷号")),
            "标题建议为空串时应回落到该形态 —— 下面的用例才有现实依据",
        )
        assertEquals(
            ".cbz (1)",
            outputDisplayOf(".cbz", OutputConflictPolicy.Rename, outputExists = true),
            "前导点是名字的一部分,不能当成扩展名把 (1) 插到它前面 —— 那会产出以空格开头的文件名",
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
        val expected = Msg(TextKey.PackSkipOutputExists, listOf("E:\\漫画库\\_output/海贼王 第102卷.cbz"))
        assertEquals(expected, plan.describe().single(), "跳过的计划只应产出一行，且内容是跳过原因")
        assertEquals(expected, plan.skipReason, "skipReason 与 describe() 的那一行必须是同一个键+参数")
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
