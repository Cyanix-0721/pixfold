package com.pixfold.d1.domain.comic

import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.LangChoice
import com.pixfold.d1.domain.model.MetaField
import com.pixfold.d1.domain.model.MetaValue
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.Suggestion
import com.pixfold.d1.domain.sort.moveItemTo
import com.pixfold.d1.domain.sort.pageOrderOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 第 1 层(领域单测):元数据、ComicInfo.xml、页码重编号(规格 §8 / 验收第 11–14 项)。
 *
 * 这些用例钉死的是**归档既有语义**(规格 §12.1 的 L11–L15),
 * 包括几处容易被"顺手改好"改坏的地方(自闭合空标签、默认名不跟随 title 编辑、
 * 语言建议不自动采纳)。
 */
class ComicMetadataTest {

    private val library = MockDataB.library
    private val aot2: ComicVolume = library.volumes.first { it.id == "aot-2" }
    private val aot0: ComicVolume = library.volumes.first { it.id == "aot-0" }
    private val op101: ComicVolume = library.volumes.first { it.id == "op-101" }
    private val op102: ComicVolume = library.volumes.first { it.id == "op-102" }

    private fun item(id: String, ext: String = "jpg") =
        SourceItem(id, "c", "", "img$id", ext, 1024, 0, 0, 0)

    // ================= L15 构建初值:语言必须人工确认 =================

    @Test
    fun `language defaults to Unset even when a suggestion exists`() {
        // aot-2 的建议语言是 ja(低置信) —— 但当前值不得自动采纳。
        assertEquals("ja", aot2.langSug.value, "前提:该卷确实有语言建议")
        assertEquals(LangChoice.Unset, aot2.language, "语言默认必须是「未设置」,不能采纳建议")
        assertTrue("语言未确认" in aot2.pendingIssues, "未设置语言必须进待确认项")
    }

    @Test
    fun `initial values come from suggestions for title series writer volume`() {
        assertEquals("进击的巨人", aot2.title, "Title 建议取系列层标题(卷号由 volume 承载)")
        assertEquals("进击的巨人", aot2.series)
        assertEquals("[作者]谏山创(原作:某人)(电子版)", aot2.writer)
        assertEquals(2, aot2.volume)
        // 建议的来源字符串必须存在且人类可读(建议 ≠ 事实,UI 要显示来源)
        assertTrue(aot2.titleSug.source.isNotEmpty())
        assertTrue(aot2.volumeSug.source.contains("第02卷"))
        assertEquals("清理规则：去作者前缀 / 括号原作 / 尾部标签", aot2.writerCleanedSug.source)
    }

    @Test
    fun `volume without a parsed number keeps a null suggestion and is pending`() {
        assertEquals(null, aot0.volumeSug.value)
        assertEquals(null, aot0.volume)
        assertEquals(listOf("语言未确认", "卷号缺失"), aot0.pendingIssues)
    }

    // ================= L15 pendingIssues 顺序与内容 =================

    @Test
    fun `pendingIssues uses the fixed order language then volume`() {
        assertEquals(listOf("语言未确认", "卷号缺失"), aot0.pendingIssues)

        val noLangOnly = op101 // 语言建议为 null -> 仍待确认;卷号有值
        assertEquals(listOf("语言未确认"), noLangOnly.pendingIssues)

        // 两项都确认后清空
        val confirmed = op102.copy(language = LangChoice.Zh)
        assertEquals(emptyList(), confirmed.pendingIssues)
        assertFalse(confirmed.needsConfirmation)
    }

    // ================= L11 ComicInfo.xml =================

    @Test
    fun `L11 language not written renders a self-closing empty tag`() {
        // Skip = 不写入标签 -> 空标签(不是"省略该标签")
        val xml = ComicInfo.build(op102.copy(language = LangChoice.Skip))
        assertContains(xml, "<LanguageISO />")
        assertFalse(xml.contains("<LanguageISO>"), "不写入时不应有带值标签")

        // Unset 同样不写入(与归档一致)
        assertContains(ComicInfo.build(op102), "<LanguageISO />")
    }

    @Test
    fun `L11 zh writes the language value`() {
        val xml = ComicInfo.build(op102.copy(language = LangChoice.Zh))
        assertContains(xml, "<LanguageISO>zh</LanguageISO>")
    }

    @Test
    fun `xml field order is fixed`() {
        val xml = ComicInfo.build(aot2)
        // 用起始标签前缀匹配:空值字段渲染为自闭合标签(<LanguageISO />),不带 ">"
        val order = listOf("<Title", "<Series", "<Number", "<Writer", "<LanguageISO")
            .map { xml.indexOf(it) }
        assertTrue(order.all { it >= 0 }, "五个字段都应出现")
        assertEquals(order.sorted(), order, "字段顺序必须为 Title→Series→Number→Writer→LanguageISO")
    }

    @Test
    fun `xml uses two space indent and self-closing empty tags for blank values`() {
        val empty = aot2.copy(title = "", series = "", writer = "", volume = null)
        val xml = ComicInfo.build(empty)
        assertContains(xml, "  <Title />")
        assertContains(xml, "  <Series />")
        assertContains(xml, "  <Number />")
        assertContains(xml, "  <Writer />")
        assertTrue(xml.endsWith("</ComicInfo>\n"), "每行以 \\n 结尾")
        assertContains(xml, "<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        assertContains(xml, "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
    }

    @Test
    fun `xml escapes exactly four characters and leaves single quote alone`() {
        val v = aot2.copy(title = """a&b<c>d"e'f""")
        val xml = ComicInfo.build(v)
        assertContains(xml, "<Title>a&amp;b&lt;c&gt;d&quot;e'f</Title>")
    }

    // ================= L12 页码重编号 =================

    @Test
    fun `L12 renumbering starts at 001 with three digits and lowercased extension`() {
        val pages = renumberPages(aot2)
        assertEquals(aot2.pages.size, pages.size)
        assertEquals("001.jpg", pages.first().fileName)
        assertEquals("018.jpg", pages.last().fileName)
        // 保留原图引用(供 UI 标"人工调整")
        assertEquals(aot2.pageOrder.order.first().id, pages.first().item.id)
    }

    @Test
    fun `L12 padding becomes four digits at one hundred pages`() {
        val many = (1..100).map { item("p$it") }
        val volume = aot2.copy(pages = many, pageOrder = pageOrderOf(many))
        val pages = renumberPages(volume)
        assertEquals("0001.jpg", pages.first().fileName)
        assertEquals("0100.jpg", pages.last().fileName)

        val ninetyNine = (1..99).map { item("q$it") }
        val v99 = aot2.copy(pages = ninetyNine, pageOrder = pageOrderOf(ninetyNine))
        assertEquals("099.jpg", renumberPages(v99).last().fileName)
    }

    @Test
    fun `L12 extension is forced to lower case`() {
        val mixed = listOf(item("a", ext = "JPG"), item("b", ext = "PNG"))
        val volume = aot2.copy(pages = mixed, pageOrder = pageOrderOf(mixed))
        assertEquals(listOf("001.jpg", "002.png"), renumberPages(volume).map { it.fileName })
    }

    @Test
    fun `renumbering follows the manual page order`() {
        // 页码取的是**含人工调整的最终页序**,不是原始顺序
        val moved = moveItemTo(aot2.pageOrder, aot2.pages.last().id, 0)
        val volume = aot2.copy(pageOrder = moved)
        val pages = renumberPages(volume)
        assertEquals(aot2.pages.last().id, pages.first().item.id)
        assertEquals("001.jpg", pages.first().fileName)
    }

    // ================= 验收第 14 项:XML 与页码预览一致 =================

    @Test
    fun `preview bundles xml and pages and reports consistency`() {
        val preview = previewOf(aot2)
        assertEquals(ComicInfo.build(aot2), preview.xml)
        assertEquals(renumberPages(aot2), preview.pages)
        assertTrue(preview.consistent, "页码应与页序一致:${preview.pageIssues}")
        assertEquals(emptyList(), preview.pageIssues)
    }

    @Test
    fun `page preview follows the page order, not the raw page list`() {
        // 页码预览的**唯一来源**是页序(含人工调整),不是 pages 原始列表
        val subset = aot2.copy(pageOrder = pageOrderOf(aot2.pages.take(3)))
        val preview = previewOf(subset)
        assertEquals(3, preview.pages.size)
        assertTrue(preview.consistent, "页序与页码预览同源,不应报不一致:${preview.pageIssues}")
        assertEquals(aot2.pageCount, renumberPages(aot2).size, "整卷页数应与页码项数一致")
    }

    // ================= L13 默认 CBZ 文件名 =================

    @Test
    fun `L13 default cbz name uses suggestion title and two digit volume`() {
        assertEquals("进击的巨人 第02卷.cbz", aot2.cbzFileName)
        assertEquals("海贼王 第102卷.cbz", op102.cbzFileName)
    }

    @Test
    fun `L13 default cbz name without volume has no volume suffix`() {
        // aot-0 无卷号建议 -> 只有 title
        assertEquals("进击的巨人 外传.cbz", aot0.cbzFileName)
        assertEquals("进击的巨人 外传.cbz", defaultCbzName("aot-0", aot0.titleSug, aot0.volumeSug))
    }

    @Test
    fun `L13 default cbz name sanitizes illegal characters and falls back to id`() {
        val sug = Suggestion("海贼王:第1卷?", "测试")
        val name = defaultCbzName("fallback-id", sug, Suggestion(1, "测试"))
        assertEquals("海贼王_第1卷_ 第01卷.cbz", name)

        val noTitle = defaultCbzName("fallback-id", Suggestion(null, "无"), Suggestion(null, "无"))
        assertEquals("fallback-id.cbz", noTitle)
    }

    @Test
    fun `default cbz name does not follow later title edits`() {
        // 归档既有语义:默认名只在构造时算一次,改 title 不自动重算
        val edited = aot2.copy(title = "改了标题")
        assertEquals("进击的巨人 第02卷.cbz", edited.cbzFileName)
        // 但"恢复建议值"会重算(此时仍用 titleSug)
        assertEquals("进击的巨人 第02卷.cbz", useSuggestion(edited, MetaField.CbzName).cbzFileName)
    }

    // ================= langIso 映射表 =================

    @Test
    fun `langIso maps every choice`() {
        assertEquals("zh", langIso(LangChoice.Zh, null))
        assertEquals("ja", langIso(LangChoice.Ja, null))
        assertEquals("fr", langIso(LangChoice.Other, "fr"))
        assertEquals(null, langIso(LangChoice.Other, ""))
        assertEquals(null, langIso(LangChoice.Other, null))
        assertEquals(null, langIso(LangChoice.Unset, null))
        assertEquals(null, langIso(LangChoice.Unknown, null))
        assertEquals(null, langIso(LangChoice.Skip, null))
    }

    @Test
    fun `other language code is written verbatim without validation`() {
        val v = op102.copy(language = LangChoice.Other, otherLangCode = "zzz-不合法")
        assertContains(ComicInfo.build(v), "<LanguageISO>zzz-不合法</LanguageISO>")
    }

    // ================= L14 批量设置不覆盖逐项例外 =================

    @Test
    fun `L14 batch apply skips fields that were manually overridden`() {
        var volumes = library.volumes
        // 手工改 aot-2 的 Title
        volumes = replaceVolume(volumes, setMetaValue(aot2, MetaField.Title, MetaValue.Text("手工标题")))

        val result = batchApply(volumes, MetaField.Title, MetaValue.Text("批量标题"))
        assertEquals(5, volumes.size)
        assertEquals(4, result.appliedCount, "5 卷中应有 4 卷被批量改写")
        assertEquals(1, result.skippedCount, "被人工改过的那 1 卷应被跳过")

        val kept = result.volumes.first { it.id == "aot-2" }
        assertEquals("手工标题", kept.title, "逐项例外必须保持")
        // 其余卷被改写
        assertEquals("批量标题", result.volumes.first { it.id == "aot-1" }.title)
    }

    @Test
    fun `L14 force overwrites the per-item exception`() {
        val volumes = replaceVolume(library.volumes, setMetaValue(aot2, MetaField.Title, MetaValue.Text("手工")))
        val forced = batchApply(volumes, MetaField.Title, MetaValue.Text("强制"), force = true)
        assertEquals(5, forced.appliedCount)
        assertEquals(0, forced.skippedCount)
        assertEquals("强制", forced.volumes.first { it.id == "aot-2" }.title)
    }

    @Test
    fun `L14 excluded volumes are not touched by batch apply`() {
        val volumes = replaceVolume(library.volumes, aot2.copy(included = false))
        val result = batchApply(volumes, MetaField.Series, MetaValue.Text("新系列"))
        assertEquals(4, result.appliedCount, "未勾选的卷不应被计入生效数量")
        assertEquals("进击的巨人", result.volumes.first { it.id == "aot-2" }.series)
    }

    @Test
    fun `L14 batch apply on language sets every affected volume`() {
        val result = batchApply(library.volumes, MetaField.Language, MetaValue.Language(LangChoice.Zh))
        assertEquals(5, result.appliedCount)
        assertTrue(result.volumes.all { it.language == LangChoice.Zh })
        assertTrue(result.volumes.none { it.pendingIssues.contains("语言未确认") })
    }

    // ================= 覆盖标记的写入与清除 =================

    @Test
    fun `setting a value records the per-item exception`() {
        val edited = setMetaValue(aot2, MetaField.Writer, MetaValue.Text("某人"))
        assertTrue(MetaField.Writer in edited.overridden)
        assertEquals("某人", edited.writer)
    }

    @Test
    fun `useSuggestion restores the value and clears the exception mark`() {
        val edited = setMetaValue(aot2, MetaField.Writer, MetaValue.Text("某人"))
        val restored = useSuggestion(edited, MetaField.Writer)
        assertEquals("[作者]谏山创(原作:某人)(电子版)", restored.writer)
        assertFalse(MetaField.Writer in restored.overridden, "恢复建议值后不应再算逐项例外")
    }

    @Test
    fun `useSuggestion for language returns to Unset and clears the manual code`() {
        val picked = setMetaValue(aot2, MetaField.Language, MetaValue.Language(LangChoice.Other, "fr"))
        assertEquals("fr", picked.otherLangCode)
        val restored = useSuggestion(picked, MetaField.Language)
        assertEquals(LangChoice.Unset, restored.language)
        assertEquals("", restored.otherLangCode)
        assertEquals("语言未确认", restored.pendingIssues.first())
    }

    @Test
    fun `type mismatch does not corrupt the volume`() {
        // UI 的类型路由写错时宁可"没改",也不要崩在真机上
        val same = setMetaValue(aot2, MetaField.Volume, MetaValue.Text("不是数字"))
        assertEquals(aot2, same)
    }

    @Test
    fun `writer cleaning applies the cleaned suggestion and marks it overridden`() {
        val cleaned = applyWriterCleaning(aot2)
        assertEquals("谏山创", cleaned.writer)
        assertTrue(MetaField.Writer in cleaned.overridden)
    }

    @Test
    fun `writer cleaning is a no-op when there is no cleaned suggestion`() {
        val noClean = aot2.copy(writerCleanedSug = Suggestion(null, "无"))
        assertEquals(noClean, applyWriterCleaning(noClean))
    }

    // ================= 输出目录 / 冲突样例 =================

    @Test
    fun `output conflict mock is recorded on the volume`() {
        assertTrue(op102.outputExists, "mock 需覆盖输出冲突样例")
        assertFalse(op101.outputExists)
        assertEquals("E:\\漫画库\\_output", op102.outputDir)
    }

    @Test
    fun `series grouping follows the current series value`() {
        val groups = com.pixfold.d1.domain.model.groupVolumesBySeries(library.volumes)
        assertEquals(listOf("进击的巨人", "海贼王"), groups.map { it.first })
        assertEquals(3, groups.first().second.size)
        assertEquals(2, groups.last().second.size)

        // 用户改系列名 -> 分组随之变化(分组键是当前值,不是建议值)
        val renamed = replaceVolume(library.volumes, aot0.copy(series = "进击的巨人外传"))
        val after = com.pixfold.d1.domain.model.groupVolumesBySeries(renamed)
        assertEquals(listOf("进击的巨人", "进击的巨人外传", "海贼王"), after.map { it.first })
    }

    @Test
    fun `empty series falls into the unnamed group`() {
        val renamed = replaceVolume(library.volumes, aot0.copy(series = ""))
        val groups = com.pixfold.d1.domain.model.groupVolumesBySeries(renamed)
        assertContains(groups.map { it.first }, com.pixfold.d1.domain.model.UNNAMED_SERIES)
    }

    @Test
    fun `mock library covers every anomaly the acceptance list needs`() {
        assertEquals(5, library.volumes.size)
        assertTrue(library.volumes.any { it.volumeSug.value == null }, "需有缺卷号样例")
        assertTrue(library.volumes.any { it.langSug.value == null }, "需有无语言线索样例")
        assertTrue(library.volumes.any { it.outputExists }, "需有输出冲突样例")
        assertTrue(library.volumes.all { it.langSug.source.isNotEmpty() }, "每个建议都要有来源")
        assertTrue(library.volumes.all { it.pages.isNotEmpty() })
        // 页数差异用于验证页码补零与"页数来自页序"
        assertEquals(setOf(16, 18, 12, 20), library.volumes.map { it.pages.size }.toSet())
    }
}
