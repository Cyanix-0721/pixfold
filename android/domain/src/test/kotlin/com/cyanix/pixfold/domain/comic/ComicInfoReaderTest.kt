package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.reconcileLibrary
import com.cyanix.pixfold.domain.mock.MockDataB
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「已有 ComicInfo.xml 作为当前值」的语义。
 *
 * 用户 2026-10-20 裁决原话：「Series 默认根据 series 文件夹取名，元数据页的内容如果
 * ComicInfo.xml 没写就默认是空的，但是恢复建议是通过文件夹结构和过滤规则获取的，
 * 这点和无 ComicInfo 的一致。」
 */
class ComicInfoReaderTest {

    // ---------- 解析 ----------

    @Test
    fun `a full ComicInfo is parsed field by field`() {
        val data = assertNotNull(
            parseComicInfo(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <Title>彼女がボクの知らないところで</Title>
                  <Series>シリーズ名</Series>
                  <Number>2</Number>
                  <Writer>[Cior (Ken-1)]</Writer>
                  <LanguageISO>ja</LanguageISO>
                </ComicInfo>
                """.trimIndent(),
            ),
        )
        assertEquals("彼女がボクの知らないところで", data.title)
        assertEquals("シリーズ名", data.series)
        assertEquals(2, data.number)
        assertEquals("[Cior (Ken-1)]", data.writer)
        assertEquals(LangChoice.Ja, data.language)
    }

    @Test
    fun `self closing tags read as absent rather than as empty strings`() {
        // 我们**自己写出的**空字段是 `<Series />`。它必须读成 null,否则
        // 「文件里写了空」与「文件里没这标签」在调用方看来会变成两件事。
        val data = assertNotNull(
            parseComicInfo(
                """
                <ComicInfo>
                  <Title>Only title</Title>
                  <Series />
                  <Writer />
                  <LanguageISO />
                </ComicInfo>
                """.trimIndent(),
            ),
        )
        assertEquals("Only title", data.title)
        assertNull(data.series)
        assertNull(data.writer)
        assertNull(data.number)
        assertEquals(LangChoice.Unset, data.language)
    }

    @Test
    fun `an omitted Number tag reads as no volume`() {
        // 卷号为空时我们整个标签都不写(ComicInfo 规范里 Number 可选)。
        val data = assertNotNull(parseComicInfo("<ComicInfo><Title>x</Title></ComicInfo>"))
        assertNull(data.number)
    }

    @Test
    fun `a non numeric Number tag is treated as no volume instead of failing the parse`() {
        val data = assertNotNull(
            parseComicInfo("<ComicInfo><Title>x</Title><Number>外传</Number></ComicInfo>"),
        )
        assertNull(data.number)
        assertEquals("x", data.title)
    }

    @Test
    fun `xml entities in values are decoded`() {
        val data = assertNotNull(
            parseComicInfo("<ComicInfo><Title>A &amp; B &lt;2&gt;</Title></ComicInfo>"),
        )
        assertEquals("A & B <2>", data.title)
    }

    @Test
    fun `a namespace prefixed document still reads its fields`() {
        // 第三方工具可能加前缀。`getElementsByTagName` 在 namespace-aware 模式下
        // 匹配的是**限定名**,所以带前缀的包读不到 —— 这里钉住「读不到也不崩,
        // 且不会把别的字段读串」。真要支持前缀,须改成按 localName 遍历。
        val data = assertNotNull(
            parseComicInfo("<ci:ComicInfo xmlns:ci='urn:x'><ci:Title>x</ci:Title></ci:ComicInfo>"),
        )
        assertNull(data.title, "带前缀的字段当前读不到 —— 已知限制,不是崩溃")
    }

    @Test
    fun `a broken document yields null instead of throwing`() {
        // 扫描路径上一个坏包不该让整个工作区扫不出来。
        assertNull(parseComicInfo("<ComicInfo><Title>未闭合"))
        assertNull(parseComicInfo("not xml at all"))
        assertNull(parseComicInfo(""))
    }

    @Test
    fun `a document whose root is not ComicInfo yields null`() {
        assertNull(parseComicInfo("<Other><Title>x</Title></Other>"))
    }

    @Test
    fun `a doctype is rejected outright`() {
        // XXE 防护:ComicInfo 从不需要 DTD。
        assertNull(
            parseComicInfo(
                """
                <?xml version="1.0"?>
                <!DOCTYPE ComicInfo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <ComicInfo><Title>&xxe;</Title></ComicInfo>
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `only zh and ja survive the language round trip`() {
        assertEquals(LangChoice.Zh, parseComicInfo("<ComicInfo><LanguageISO>zh</LanguageISO></ComicInfo>")?.language)
        assertEquals(LangChoice.Ja, parseComicInfo("<ComicInfo><LanguageISO>JA</LanguageISO></ComicInfo>")?.language)
        // 认不出的码不猜 —— 猜成 Zh 会让下次打包把标签写成 zh,悄悄改掉用户原来的值。
        assertEquals(LangChoice.Unset, parseComicInfo("<ComicInfo><LanguageISO>zh-CN</LanguageISO></ComicInfo>")?.language)
        assertEquals(LangChoice.Unset, parseComicInfo("<ComicInfo><LanguageISO>en</LanguageISO></ComicInfo>")?.language)
    }

    @Test
    fun `our own generated xml parses back to the same values`() {
        // 往返:写出去的 XML 必须能被自己读回来,否则「已有值」永远是空。
        val volume = MockDataB.library.volumes.first().copy(
            title = "タイトル",
            series = "シリーズ",
            writer = "作者",
            volume = 3,
            language = LangChoice.Zh,
        )
        val data = assertNotNull(parseComicInfo(ComicInfo.build(volume)))
        assertEquals("タイトル", data.title)
        assertEquals("シリーズ", data.series)
        assertEquals(3, data.number)
        assertEquals("作者", data.writer)
        assertEquals(LangChoice.Zh, data.language)
        assertFalse(data.isEmpty)
    }

    // ---------- 填进当前值 ----------

    @Test
    fun `existing values become the current values while suggestions stay put`() {
        val volume = volumeWith(title = "建议标题", series = "建议系列", writer = "建议作者")
        val filled = applyExistingComicInfo(
            listOf(volume),
            probeReturning(
                ComicInfoData(
                    title = "文件标题",
                    series = "文件系列",
                    number = 7,
                    writer = "文件作者",
                    language = LangChoice.Ja,
                ),
            ),
        ).single()

        assertEquals("文件标题", filled.title)
        assertEquals("文件系列", filled.series)
        assertEquals(7, filled.volume)
        assertEquals("文件作者", filled.writer)
        assertEquals(LangChoice.Ja, filled.language)

        // 建议值也以 ComicInfo 为准（用户 2026-10-21 裁决「总之优先级都是 ComicInfo
        // 最高」）—— 否则「恢复建议值」会把用户上次写好的标签换成目录名推导的结果。
        assertEquals("文件标题", filled.titleSug.value)
        assertEquals("文件系列", filled.seriesSug.value)
        assertEquals("文件作者", filled.writerSug.value)
    }

    @Test
    fun `ComicInfo outranks the folder hierarchy as the suggestion source`() {
        val volume = volumeWith(title = "目录标题", series = "目录系列", writer = "目录作者")
        val filled = applyExistingComicInfo(
            listOf(volume),
            probeReturning(ComicInfoData(title = "文件标题", writer = "文件作者")),
        ).single()
        // 文件里有的字段：建议值 = 文件值，来源如实写。
        assertEquals("文件标题", filled.titleSug.value)
        assertEquals(COMIC_INFO_SOURCE, filled.titleSug.source)
        assertEquals("文件作者", filled.writerSug.value)
        assertEquals(COMIC_INFO_SOURCE, filled.writerSug.source)
        // 文件里没写的字段：建议值仍是目录层级，来源也要如实区分。
        assertEquals("目录系列", filled.seriesSug.value)
        assertEquals("目录名", filled.seriesSug.source)
    }

    @Test
    fun `ComicInfo does not touch the fetch-from-folder writer`() {
        // 「按目录结构获取」是文件里作者写错时的**唯一退路**。若它跟着 ComicInfo 一起
        // 被顶掉,这个入口就在最有用的场合失效了 —— 而界面上它仍然可点(不抛不空),
        // 用户点了没变化,只会以为按钮坏了。
        val volume = volumeWith(title = "目录标题", series = "目录系列", writer = "目录作者")
            .copy(dirWriterSug = Suggestion("目录作者", "目录名开头的 [作者] 前缀（已去掉方括号）"))
        val filled = applyExistingComicInfo(
            listOf(volume),
            probeReturning(ComicInfoData(writer = "文件作者")),
        ).single()
        assertEquals("文件作者", filled.writerSug.value, "建议值按优先级取文件")
        assertEquals("目录作者", filled.dirWriterSug.value, "按目录结构取的值不受文件影响")
    }

    @Test
    fun `a single volume Restore-advice puts Series on the ComicInfo title`() {
        // 用户裁决：「单本 series 建议值跟随 ComicInfo 后一个选取 Title，体现在恢复建议后
        // 和 Title 一致」。单本 = 扫描层没有系列目录（seriesDir 为空）。
        val volume = volumeWith(
            title = "目录标题",
            series = "目录标题",
            writer = "目录作者",
            seriesDir = "",
        )
        val filled = applyExistingComicInfo(
            listOf(volume),
            probeReturning(ComicInfoData(title = "文件标题")),
        ).single()
        assertEquals("文件标题", filled.titleSug.value)
        assertEquals("文件标题", filled.seriesSug.value, "单本的 Series 建议必须跟着 Title 走")
        assertEquals(filled.titleSug.value, filled.seriesSug.value)
    }

    @Test
    fun `a volume without an existing cbz keeps the suggested values`() {
        val volume = volumeWith(title = "建议标题", series = "建议系列", writer = "建议作者")
        val filled = applyExistingComicInfo(listOf(volume), probeReturning(null)).single()
        assertEquals("建议标题", filled.title)
        assertEquals("建议系列", filled.series)
        assertEquals("建议作者", filled.writer)
    }

    @Test
    fun `a cbz whose ComicInfo has no tags empties the current values`() {
        // 用户裁决原文:「元数据页的内容如果 ComicInfo.xml 没写就默认是空的」。
        // 文件在、标签没写 ⇒ 当前值清空,**不许**拿建议值回填 ——
        // 否则「我上次写没写」在界面上就分不出来了。
        val volume = volumeWith(title = "建议标题", series = "建议系列", writer = "建议作者")
        val filled = applyExistingComicInfo(listOf(volume), probeReturning(ComicInfoData())).single()
        assertEquals("", filled.title)
        assertEquals("", filled.series)
        assertEquals("", filled.writer)
        assertNull(filled.volume)
        assertEquals(LangChoice.Unset, filled.language)
        // 建议值仍在,所以「恢复建议值」还能用。
        assertEquals("建议标题", filled.titleSug.value)
    }

    @Test
    fun `an empty existing number clears the volume number`() {
        val volume = volumeWith(title = "t", series = "s", writer = "w", number = 5)
        val filled = applyExistingComicInfo(
            listOf(volume),
            probeReturning(ComicInfoData(title = "t2")),
        ).single()
        assertNull(filled.volume, "文件里没写 Number ⇒ 卷号为空,不沿用建议的 5")
    }

    @Test
    fun `the output file name still comes from the suggestions`() {
        // 文件名是「这次要写到哪」,不是元数据。跟着建议值走,撞车由 preflight 阻断。
        val volume = volumeWith(title = "建议标题", series = "建议系列", writer = "建议作者")
        val before = volume.cbzFileName
        val filled = applyExistingComicInfo(
            listOf(volume),
            probeReturning(ComicInfoData(title = "完全不同的标题")),
        ).single()
        assertEquals(before, filled.cbzFileName)
    }

    @Test
    fun `the probe is asked once per volume`() {
        val asked = mutableListOf<String>()
        val volumes = MockDataB.library.volumes.take(3)
        applyExistingComicInfo(volumes) { volume ->
            asked += volume.id
            null
        }
        assertEquals(volumes.map { it.id }, asked)
    }

    @Test
    fun `a re-scan with a ComicInfo present reads the same values back`() {
        // 「ComicInfo 优先」之后，建议值与当前值**都**来自文件，「重扫能不能冲掉
        // 用户填的值」这个问题在**有 ComicInfo** 时不再是边界 —— 重扫读的还是同一份
        // 文件。真正需要守住的是「重扫结果与上一次一致」，用户不会看到界面自己跳变。
        val volume = volumeWith(title = "目录标题", series = "目录系列", writer = "目录作者")
        val probe = probeReturning(ComicInfoData(title = "文件标题", series = "文件系列"))
        val fromFile = applyExistingComicInfo(listOf(volume), probe)
        val rescanned = applyExistingComicInfo(listOf(volume), probe)

        val reconciled = reconcileLibrary(
            previous = LibraryState(rootPath = "root", volumes = fromFile, activeVolumeId = fromFile.first().id),
            scanned = rescanned,
        ).volumes.single()

        assertEquals("文件标题", reconciled.title)
        assertEquals("文件系列", reconciled.series)
        assertEquals(COMIC_INFO_SOURCE, reconciled.titleSug.source)
    }

    @Test
    fun `a re-scan without a ComicInfo still drags the suggestions along with the folder`() {
        // 没有 ComicInfo 时建议值仍来自目录层级，「目录被改名 ⇒ 界面跟着变」这条
        // 既有行为必须保留（否则用户在文件管理器里改了目录名，界面还显示旧的）。
        val volume = volumeWith(title = "建议标题", series = "建议系列", writer = "建议作者")
        val first = applyExistingComicInfo(listOf(volume), probeReturning(null))
        val rescanned = first.map {
            it.copy(
                titleSug = Suggestion("新建议标题", "目录名"),
                seriesSug = Suggestion("新建议系列", "目录名"),
                title = "新建议标题",
                series = "新建议系列",
            )
        }
        val reconciled = reconcileLibrary(
            previous = LibraryState(rootPath = "root", volumes = first, activeVolumeId = first.first().id),
            scanned = rescanned,
        ).volumes.single()

        assertEquals("新建议标题", reconciled.titleSug.value, "建议值必须跟着目录刷新")
        assertEquals("新建议系列", reconciled.seriesSug.value)
    }

    // ---------- 夹具 ----------

    private fun volumeWith(
        title: String,
        series: String,
        writer: String,
        number: Int? = null,
        seriesDir: String = MockDataB.library.volumes.first().seriesDir,
    ): ComicVolume {
        val base = MockDataB.library.volumes.first()
        return base.copy(
            titleSug = Suggestion(title, "目录名"),
            seriesSug = Suggestion(series, "目录名"),
            writerSug = Suggestion(writer, "目录名"),
            title = title,
            series = series,
            writer = writer,
            volume = number,
            seriesDir = seriesDir,
        )
    }

    private fun probeReturning(data: ComicInfoData?): ExistingComicInfoProbe =
        ExistingComicInfoProbe { data }
}
