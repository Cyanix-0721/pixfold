package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.MetaValue
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.sort.moveItemTo
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey
import com.cyanix.pixfold.domain.sort.pageOrderOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
        // Unset 是合法终态,不再进待确认项(2026-10-20 用户裁决,推翻验收第 12 项)
        assertFalse(Msg(TextKey.PendingLanguage) in aot2.pendingIssues, "语言不该再进待确认项")
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
        assertEquals("目录名开头的 [作者] 前缀（已去掉方括号）", aot2.dirWriterSug.source)
    }

    @Test
    fun `volume without a parsed number keeps a null suggestion and is pending`() {
        assertEquals(null, aot0.volumeSug.value)
        assertEquals(null, aot0.volume)
        assertEquals(listOf(Msg(TextKey.PendingVolume)), aot0.pendingIssues)
    }

    // ================= L15 pendingIssues 顺序与内容 =================

    @Test
    fun `pendingIssues only carries the missing volume`() {
        // 语言项已于 2026-10-20 移除(Unset 即终态);只剩卷号缺失
        assertEquals(listOf(Msg(TextKey.PendingVolume)), aot0.pendingIssues)

        // 卷号有值时,即使语言是 Unset 也不算待确认
        val noLangOnly = op101
        assertEquals(emptyList(), noLangOnly.pendingIssues)
        assertFalse(noLangOnly.needsConfirmation, "语言 Unset 不再构成待确认")

        val confirmed = op102.copy(language = LangChoice.Zh)
        assertEquals(emptyList(), confirmed.pendingIssues)
        assertFalse(confirmed.needsConfirmation)
    }

    // ================= L11 ComicInfo.xml =================

    @Test
    fun `L11 language not written renders a self-closing empty tag`() {
        // Unset = 不写入标签 -> 空标签(不是"省略该标签")
        val xml = ComicInfo.build(op102.copy(language = LangChoice.Unset))
        assertContains(xml, "<LanguageISO />")
        assertFalse(xml.contains("<LanguageISO>"), "不写入时不应有带值标签")

        // Unset 同样不写入(与归档一致)
        assertContains(ComicInfo.build(op102), "<LanguageISO />")
    }

    @Test
    fun `every blank optional field raises its own pending hint`() {
        // 2026-10-22 用户裁决:「其他几个可选项缺失的警告补上」。
        // 判据是「有没有值」而不是「有没有被覆盖」—— 目录名解析不出就是空。
        val blank = aot2.copy(title = "", series = "", writer = "", volume = null)
        val keys = blank.pendingIssues.map { it.key }
        assertEquals(
            listOf(
                TextKey.PendingTitle,
                TextKey.PendingSeries,
                TextKey.PendingVolume,
                TextKey.PendingWriter,
            ),
            keys,
            "标题 / 系列 / 卷号 / 作者四项为空时各给一条,且顺序固定",
        )
    }

    @Test
    fun `a cleared title raises its own pending hint`() {
        // 标题这条我漏过一轮,理由是「标题几乎总能从目录名得到」—— 站不住:
        // titleSug 只是**建议值**,输入框可以被用户清空,清空后 XML 就是 <Title />。
        // 这条专门钉住「清空标题必须提醒」,与「通常能解析出来」无关。
        val cleared = aot2.copy(title = "")
        assertTrue(
            cleared.pendingIssues.any { it.key == TextKey.PendingTitle },
            "清空标题后必须出现标题缺失提示",
        )
    }

    @Test
    fun `filled series and writer raise no hint`() {
        val full = aot2.copy(title = "T", series = "S", writer = "W", volume = 3)
        assertTrue(full.pendingIssues.isEmpty(), "四项都有值时不该有任何待确认项")
        assertFalse(full.needsConfirmation)
    }

    @Test
    fun `whitespace only values count as missing`() {
        // 只填空格在界面上看着像「填了」,写进 XML 却是空白 —— 按缺失处理。
        val spaced = aot2.copy(series = "   ", writer = " ", volume = 3)
        val keys = spaced.pendingIssues.map { it.key }
        assertEquals(listOf(TextKey.PendingSeries, TextKey.PendingWriter), keys)
    }

    @Test
    fun `L11 zh writes the language value`() {
        val xml = ComicInfo.build(op102.copy(language = LangChoice.Zh))
        assertContains(xml, "<LanguageISO>zh</LanguageISO>")
    }

    @Test
    fun `xml field order is fixed`() {
        // 用起始标签前缀匹配:空值字段渲染为自闭合标签(<LanguageISO />),不带 ">"
        val xml = ComicInfo.build(aot2)
        val order = listOf("<Title", "<Series", "<Number", "<Writer", "<LanguageISO")
            .map { xml.indexOf(it) }
        assertTrue(order.all { it >= 0 }, "五个字段都应出现")
        assertEquals(order.sorted(), order, "字段顺序必须为 Title→Series→Number→Writer→LanguageISO")
    }

    @Test
    fun `xml uses two space indent and self-closing empty tags for blank values`() {
        val empty = aot2.copy(
            title = "", series = "", writer = "", volume = null,
            language = LangChoice.Unset,
        )
        val xml = ComicInfo.build(empty)
        // 2026-10-22 用户裁决:五个字段**全部统一**写自闭合空标签,没有例外
        // (此前 <Number> 是唯一省略的字段,已改回)。
        for (tag in listOf("Title", "Series", "Number", "Writer", "LanguageISO")) {
            assertContains(xml, "  <$tag />")
        }
        assertTrue(xml.endsWith("</ComicInfo>\n"), "每行以 \\n 结尾")
        assertContains(xml, "<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        assertContains(xml, "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"")
    }

    @Test
    fun `a volume without a number still writes a self-closing Number tag`() {
        // 2026-10-22 用户裁决:五个字段一律写自闭合空标签,<Number> 不再是例外。
        // 原实现省略 <Number>(担心被读成第 0 卷),理由是猜测性的;统一更重要。
        val noNumber = aot2.copy(volume = null)
        val xml = ComicInfo.build(noNumber)
        assertContains(xml, "  <Number />")
        // 其余字段照旧(空值仍写自闭合标签)
        assertContains(xml, "<Title>")

        // 有卷号时正常写出,且仍排在 Series 与 Writer 之间
        val withNumber = aot2.copy(volume = 7)
        val numbered = ComicInfo.build(withNumber)
        assertContains(numbered, "<Number>7</Number>")
        assertTrue(
            numbered.indexOf("<Series") < numbered.indexOf("<Number") &&
                numbered.indexOf("<Number") < numbered.indexOf("<Writer"),
            "Number 仍须位于 Series 与 Writer 之间",
        )
    }

    @Test
    fun `an empty Number tag does not silence the missing volume hint`() {
        // 用户裁决:空卷号写自闭合标签,但「卷号缺失」的待确认提示必须保留 ——
        // 那是给用户的提示,不是给阅读器的数据。两者容易一起被误删。
        val noNumber = aot2.copy(volume = null)
        assertContains(ComicInfo.build(noNumber), "  <Number />")
        assertTrue(
            noNumber.pendingIssues.any { it.key == TextKey.PendingVolume },
            "卷号为空时 pendingIssues 必须仍含 PendingVolume",
        )
        assertTrue(noNumber.needsConfirmation, "卷号为空仍应算「有待确认项」")
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
        // 页码预览的**唯一来源**是页序(含人工调整),不是 pages 原始列表。
        // 这里故意构造"卷自称 18 页、页序只排了 3 页"的分叉:预览仍只画页序里的 3 页,
        // 同时自检必须把这个分叉报出来(R4 之前它报不出来 —— 那时它拿页序跟自己比)。
        val subset = aot2.copy(pageOrder = pageOrderOf(aot2.pages.take(3)))
        val preview = previewOf(subset)
        assertEquals(3, preview.pages.size)
        assertEquals(
            listOf(TextKey.PageIssueCountMismatch),
            preview.pageIssues.map { it.key },
            "卷自称的页数与页序项数分叉时必须报出来,实际:${preview.pageIssues}",
        )
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
        assertEquals("zh", langIso(LangChoice.Zh))
        assertEquals("ja", langIso(LangChoice.Ja))
        assertEquals(null, langIso(LangChoice.Unset))
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
        assertTrue(result.volumes.none { Msg(TextKey.PendingLanguage) in it.pendingIssues })
    }

    // ================= 批量设置不把自己变成逐项例外(用户裁决 2026-09-25) =================
    //
    // 语义分工:约束 4「批次统一 + **逐项**例外」—— 批量是面向批次的统一值,
    // 只有**逐卷手工编辑**才算例外。故批量写入后该字段必须回到「批次统一」,
    // 否则第二次批量会被自己写下的标记挡住(applied=0/skipped=5)。

    @Test
    fun `batch apply twice on the same field applies both times`() {
        val first = batchApply(library.volumes, MetaField.Series, MetaValue.Text("第一次"))
        assertEquals(5, first.appliedCount)
        assertEquals(0, first.skippedCount)
        assertTrue(
            first.volumes.none { MetaField.Series in it.overridden },
            "批量设置不得把字段记成逐项例外,否则第二次批量会被自己挡住",
        )

        val second = batchApply(first.volumes, MetaField.Series, MetaValue.Text("第二次"))
        assertEquals(5, second.appliedCount, "同一字段批量设第二次必须照常生效")
        assertEquals(0, second.skippedCount, "第二次不应有任何卷被跳过")
        assertTrue(second.volumes.all { it.series == "第二次" }, "值必须被第二次改写")
    }

    @Test
    fun `batch apply clears a stale per-item mark for the field it writes`() {
        val volumes = replaceVolume(
            library.volumes,
            setMetaValue(aot2, MetaField.Series, MetaValue.Text("手工")),
        )
        val result = batchApply(volumes, MetaField.Series, MetaValue.Text("批量"), force = true)
        val v = result.volumes.first { it.id == "aot-2" }
        assertEquals("批量", v.series)
        assertFalse(MetaField.Series in v.overridden, "批量写入后该字段应回到批次统一,不再是逐项例外")
    }

    @Test
    fun `batch apply only clears the mark of the field it writes`() {
        val volumes = replaceVolume(
            library.volumes,
            setMetaValue(aot2, MetaField.Writer, MetaValue.Text("某人")),
        )
        val result = batchApply(volumes, MetaField.Series, MetaValue.Text("批量"))
        val v = result.volumes.first { it.id == "aot-2" }
        assertTrue(MetaField.Writer in v.overridden, "没被批量写过的字段必须保住自己的逐项例外")
        assertFalse(MetaField.Series in v.overridden)
    }

    @Test
    fun `manual edit after a batch is still respected`() {
        val batched = batchApply(library.volumes, MetaField.Series, MetaValue.Text("批量")).volumes
        val edited = replaceVolume(
            batched,
            setMetaValue(batched.first { it.id == "aot-2" }, MetaField.Series, MetaValue.Text("后来手工")),
        )
        val again = batchApply(edited, MetaField.Series, MetaValue.Text("再批量"))
        assertEquals(4, again.appliedCount, "批量之后的手工编辑必须重新构成逐项例外")
        assertEquals(1, again.skippedCount)
        val kept = again.volumes.first { it.id == "aot-2" }
        assertEquals("后来手工", kept.series)
        assertTrue(MetaField.Series in kept.overridden, "被跳过的卷必须原封不动,连标记也不能动")
    }

    @Test
    fun `batch apply with a mismatched value type leaves the mark untouched`() {
        // 类型不匹配时 setMetaValue 原样返回 —— 什么都没写,标记自然也不该被清。
        // ⚠️ 必须 force=true:否则已被标记的字段会先被"跳过"分支拦住,永远走不到写入分支,
        // 这条断言就只是重复了"跳过不动标记",抓不住实例守卫。
        val volumes = replaceVolume(
            library.volumes,
            setMetaValue(aot2, MetaField.Volume, MetaValue.Number(9)),
        )
        val result = batchApply(volumes, MetaField.Volume, MetaValue.Text("不是数字"), force = true)
        val v = result.volumes.first { it.id == "aot-2" }
        assertEquals(9, v.volume, "类型不匹配不得改写值")
        assertTrue(MetaField.Volume in v.overridden, "没写进去就不该动标记")
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
    fun `useSuggestion for language returns to Unset`() {
        val picked = setMetaValue(aot2, MetaField.Language, MetaValue.Language(LangChoice.Zh))
        assertEquals("zh", langIso(picked.language))
        val restored = useSuggestion(picked, MetaField.Language)
        assertEquals(LangChoice.Unset, restored.language)
        // Unset 是合法终态,不再进待确认项(2026-10-20 裁决)
        assertFalse(Msg(TextKey.PendingLanguage) in restored.pendingIssues)
    }

    @Test
    fun `type mismatch does not corrupt the volume`() {
        // UI 的类型路由写错时宁可"没改",也不要崩在真机上
        val same = setMetaValue(aot2, MetaField.Volume, MetaValue.Text("不是数字"))
        assertEquals(aot2, same)
    }

    @Test
    fun `fetch-from-folder refills every editable field from the directory`() {
        // 用户 2026-10-22 裁决:入口作用于**全部字段**,不是只给作者用。
        val cleaned = applyDirMetaSuggestions(aot2)
        assertEquals(aot2.dirTitleSug.value.orEmpty(), cleaned.title)
        assertEquals(aot2.dirSeriesSug.value.orEmpty(), cleaned.series)
        assertEquals("谏山创", cleaned.writer)
        assertEquals(aot2.volumeSug.value, cleaned.volume)
        assertEquals(aot2.outputDirSug, cleaned.outputDir)
        assertEquals(LangChoice.Unset, cleaned.language, "目录名没有可靠语言信息,不猜")
        // 七个字段全部记进逐项例外
        assertEquals(
            setOf(
                MetaField.Title,
                MetaField.Series,
                MetaField.Writer,
                MetaField.Volume,
                MetaField.Language,
                MetaField.CbzName,
                MetaField.OutputDir,
            ),
            cleaned.overridden,
        )
    }

    @Test
    fun `fetch-from-folder ignores the current suggestions and uses the directory values`() {
        // 建议值已被 ComicInfo 覆盖(写错的名字)时,「按目录结构获取」是唯一退路 ——
        // 它必须无视 *Sug,只用 dir*Sug(用户 2026-10-22 裁决)。
        val overwritten = aot2.copy(
            titleSug = Suggestion("文件里写错的标题", "已有 ComicInfo.xml"),
            writerSug = Suggestion("文件里写错的名字", "已有 ComicInfo.xml"),
            seriesSug = Suggestion("文件里写错的系列", "已有 ComicInfo.xml"),
            title = "文件里写错的标题",
            writer = "文件里写错的名字",
            series = "文件里写错的系列",
        )
        val fixed = applyDirMetaSuggestions(overwritten)
        assertEquals("谏山创", fixed.writer)
        assertEquals(aot2.dirTitleSug.value.orEmpty(), fixed.title)
        assertEquals(aot2.dirSeriesSug.value.orEmpty(), fixed.series)
        assertNotEquals(overwritten.writerSug.value, fixed.writer)
    }

    @Test
    fun `a preview with a recorded page issue is not consistent`() {
        // consistent 是 UI 判断"要不要显示「页码与页序不一致」"的唯一开关,
        // 它必须真的看问题清单,而不是恒真 —— 否则报警分支永远不会触发。
        val preview = previewOf(aot2)
        assertTrue(preview.consistent, "正常卷应一致:${preview.pageIssues}")
        assertFalse(
            preview.copy(pageIssues = listOf(Msg(TextKey.PageIssueCountMismatch, listOf(1, 2)))).consistent,
            "有问题的预览必须判为不一致",
        )
    }

    @Test
    fun `a hundred-page volume still reports a consistent preview`() {
        // 重编号与自检**各自**算补零位数,两处必须保持一致:
        // 校验侧若把 `>= 100` 写成 `> 100`,整整 100 页时预览就会误报
        // 「页码与页序不一致」(而页码其实是对的)。这里正好跨过 100 这条边界。
        val many = (1..100).map { item("p$it") }
        val volume = aot2.copy(pages = many, pageOrder = pageOrderOf(many))
        val preview = previewOf(volume)
        assertEquals("0001.jpg", preview.pages.first().fileName)
        assertEquals(100, preview.pages.size)
        assertTrue(preview.consistent, "整整 100 页时自检不该报警:${preview.pageIssues}")
        assertEquals(emptyList(), preview.pageIssues)
    }

    @Test
    fun `a volume whose page list and page order disagree is reported as inconsistent`() {
        // R4:`validatePageConsistency` 此前把 `renumberPages(volume)`（它自己的输出）
        // 与 `volume.pageOrder.order` 比长度 —— 那是**同一个列表与自身比**,恒等。
        // 真正该问的是:"卷里登记的页"与"页序里排的页"是不是同一批。
        // 这里少一页(页序仍有 16 项,页表只剩 15 项),自检必须发现。
        val holed = aot2.copy(pages = aot2.pages.dropLast(1))
        assertTrue(
            holed.pages.size != holed.pageOrder.order.size,
            "前置条件:构造出的卷必须真的两处长度不同,否则本用例什么都没测",
        )
        val preview = previewOf(holed)
        assertFalse(preview.consistent, "页表与页序长度不同时必须判为不一致")
        assertEquals(
            listOf(TextKey.PageIssueCountMismatch),
            preview.pageIssues.map { it.key },
            "应报告「页数与页序长度不一致」,实际:${preview.pageIssues}",
        )
        assertEquals(
            listOf<Any>(17, 18),
            preview.pageIssues.single().args,
            "参数顺序必须是(卷内页数, 页序项数) —— 反了用户就会看到「18 ≠ 17」这种倒过来的描述",
        )
    }

    @Test
    fun `a page that is not in the page order is reported as inconsistent`() {
        // 长度相同但成员不同也要报:长度检查会漏掉"替换掉一页"这种情形。
        // 这里把页序的最后一页换成一张页表里没有的页,两者长度都是 16。
        val stranger = item("stranger")
        val swapped = aot2.pageOrder.order.dropLast(1) + stranger
        val volume = aot2.copy(pageOrder = aot2.pageOrder.copy(order = swapped))
        assertEquals(
            volume.pages.size,
            volume.pageOrder.order.size,
            "前置条件:本用例要测的是**长度相同**但成员不同的情形",
        )
        val preview = previewOf(volume)
        assertFalse(preview.consistent, "有页不在页序里时必须判为不一致")
        assertEquals(
            listOf(TextKey.PageIssueMissingFromOrder),
            preview.pageIssues.map { it.key },
            "应报告「有页不在页序里」,实际:${preview.pageIssues}",
        )
        assertEquals(
            listOf<Any>(1, 18),
            preview.pageIssues.single().args,
            "参数顺序必须是(不在页序里的页数, 卷内总页数)",
        )
    }

    @Test
    fun `a manual reorder alone never makes the preview inconsistent`() {
        // 手工拖拽**合法地**改变页序(而不改页表),故自检必须是**置换不变**的:
        // 若改成逐项按位置比对两个列表,用户每拖一次就会看到一条假警告。
        val moved = moveItemTo(pageOrderOf(aot2.pages), aot2.pages.last().id, 0)
        val volume = aot2.copy(pageOrder = moved)
        assertTrue(volume.pageOrder.hasManual, "前置条件:这一卷必须真的被标记为人工调整过")
        assertEquals(
            volume.pages.size,
            volume.pageOrder.order.size,
            "拖拽只换顺序,不该改变项数",
        )
        val preview = previewOf(volume)
        assertTrue(preview.consistent, "仅仅重排页序不该报警:${preview.pageIssues}")
        assertEquals(emptyList(), preview.pageIssues)
    }

    @Test
    fun `fetch-from-folder does not blank a writer with no directory value`() {
        // 目录名里没有 [作者] 前缀时 dirWriterSug 没有值 —— 此时**不得**把作者清空。
        // (空工作区的可点性由 UI 用 dirMetaAvailable 判,不在领域层做。)
        val noClean = aot2.copy(dirWriterSug = Suggestion(null, "无"))
        val refilled = applyDirMetaSuggestions(noClean)
        assertEquals("", refilled.writer, "无可推导值时作者留空,而不是沿用旧的人工值")
        assertTrue(MetaField.Writer in refilled.overridden, "这一轮确实覆盖了 Writer 字段")
    }

    // ================= 输出目录 / 冲突样例 =================

    @Test
    fun `output conflict mock is recorded on the volume`() {
        assertTrue(op102.outputExists, "mock 需覆盖输出冲突样例")
        assertFalse(op101.outputExists)
        assertEquals("E:\\漫画库\\_output", op102.outputDir)
    }

    @Test
    fun `series grouping follows the scanned series directory`() {
        val groups = com.cyanix.pixfold.domain.comic.groupVolumes(library.volumes).filter { it.isSeries }
        assertEquals(listOf("进击的巨人", "海贼王"), groups.map { it.name })
        assertEquals(3, groups.first().volumes.size)
        assertEquals(2, groups.last().volumes.size)

        // 2026-10-21 改判:分组键是**扫描层** seriesDir,不是可编辑的 series 当前值。
        // 用户把某一卷的系列名改成「进击的巨人外传」,它仍属于「进击的巨人」这个系列目录
        // —— 改名只影响显示,不该把一卷从系列里拆出去(条①让单本的 series 当前值恒非空,
        // 若拿当前值当键,每个单本都会自成一个"系列")。
        //
        // 组名取组内**第一本**的 series 当前值(不是每一本各自算),且为空时回退扫描层
        // 目录名 —— 已有 ComicInfo.xml 却没写 <Series> 时当前值会被清空,不回退就会出现
        // 无名芯片。这里改的是 aot-0(外传,组内第三本),组名仍取 aot-1 的值。
        val renamed = replaceVolume(library.volumes, aot0.copy(series = "进击的巨人外传"))
        val after = com.cyanix.pixfold.domain.comic.groupVolumes(renamed).filter { it.isSeries }
        assertEquals(listOf("进击的巨人", "海贼王"), after.map { it.name })
        assertEquals(3, after.first().volumes.size)
    }

    @Test
    fun `a volume without a series directory is a single volume group`() {
        // 单本的判据是「扫描期没有系列目录」(seriesDir 为空),**不是**「series 当前值为空」
        // —— 后者会被条①「跟随 Title」填满。
        //
        // 2026-10-21 改判:单本**各自成组**(键 = `single:` + id),不再并进一个空串兜底组。
        // 旧实现在书架页把 3 个互不相干的单本显示成同一个系列的 3 卷。
        val single = replaceVolume(library.volumes, aot0.copy(series = "某本书", seriesDir = ""))
        val groups = com.cyanix.pixfold.domain.comic.groupVolumes(single)
        val singles = groups.filterNot { it.isSeries }
        assertEquals(1, singles.size, "只有 aot0 是单本")
        assertFalse(singles.single().isSeries)
        assertEquals("进击的巨人 外传", singles.single().name, "单本组名取自己的标题")
        assertTrue(singles.single().volumes.any { it.id == aot0.id })
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

    @Test
    fun `included volumes keeps only the checked ones`() {
        // 取消勾选是用户表达"这卷别打包"的唯一手段;这张筛选结果同时喂给
        // 计划(buildPackagePlans)与 UI 的计数(PackPreviewPage 的 includedCount)。
        // 若它取反,用户取消勾选反而会**只打包被取消的那些**。
        val rest = replaceVolume(library.volumes, setIncluded(aot2, false))
        val kept = includedVolumes(rest)
        assertFalse(kept.any { it.id == aot2.id }, "取消勾选的卷不得出现在结果里")
        assertEquals(rest.count { it.included }, kept.size, "结果条数必须等于勾选数")
        // 反面也要钉住:勾选状态为 true 的卷一个都不能少(空表也能"通过"上面那条)。
        assertTrue(kept.any { it.id == op101.id }, "仍被勾选的卷不得被丢掉")
        assertEquals(library.volumes.size - 1, kept.size)
    }

    /**
     * 字段级红警与页头汇总必须由**同一份**判据产生。
     *
     * 界面那边每个输入框的 `isError` 读 [ComicVolume.pendingIssueOf],页头那一行读
     * [ComicVolume.pendingIssues]。若两者各写一份「什么算缺失」,用户就会看到
     * 「页头说标题缺失、标题框却不标红」(2026-10-22 正是这个分歧被指出来)。
     */
    @Test
    fun `the per field hint and the header summary come from one rule`() {
        val volume = aot2.copy(title = "", series = "", writer = "", volume = null)

        val expected = listOf(
            MetaField.Title to TextKey.PendingTitle,
            MetaField.Series to TextKey.PendingSeries,
            MetaField.Volume to TextKey.PendingVolume,
            MetaField.Writer to TextKey.PendingWriter,
        )
        expected.forEach { (field, key) ->
            assertEquals(key, volume.pendingIssueOf(field)?.key, "$field 的字段级判据给错了键")
        }

        // 页头汇总 = 逐字段取回非空项,顺序固定
        assertEquals(
            expected.mapNotNull { (field, _) -> volume.pendingIssueOf(field)?.key },
            volume.pendingIssues.map { it.key },
            "页头汇总的顺序与逐字段判据不一致",
        )
    }

    @Test
    fun `a filled field reports no per field hint`() {
        val volume = aot2.copy(title = "T", series = "S", writer = "W", volume = 3)
        listOf(MetaField.Title, MetaField.Series, MetaField.Volume, MetaField.Writer)
            .forEach { field -> assertNull(volume.pendingIssueOf(field), "$field 有值时不该有红警") }
    }

    @Test
    fun `fields outside the rule report no per field hint`() {
        val volume = aot2.copy(title = "", series = "", writer = "", volume = null)
        listOf(MetaField.Language, MetaField.CbzName, MetaField.OutputDir).forEach { field ->
            assertNull(volume.pendingIssueOf(field), "$field 不该有字段级红警")
        }
    }
}
