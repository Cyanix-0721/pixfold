package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.Suggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 工作流 C 成功后的回填语义。
 *
 * 判据只有一条：**写完之后界面上显示的当前值 = 刚写进包里的那份 XML**。
 * 与 `applyExistingComicInfo` 的区别是数据来源（手里已有 XML vs 探针读文件），
 * 但"当前值取文件、建议值来自目录结构"这条分工必须一模一样 —— 否则用户改完一处、
 * 界面显示另一处。
 */
class WithComicInfoValuesTest {

    private fun volume(
        seriesDir: String = "",
        seriesSugValue: String = "建议系列",
    ) = ComicVolume(
        id = "v",
        dirPath = "a",
        pages = emptyList(),
        titleSug = Suggestion("建议标题", "目录"),
        seriesSug = Suggestion(seriesSugValue, "目录"),
        writerSug = Suggestion("建议作者", "目录"),
        volumeSug = Suggestion(1, "目录"),
        langSug = Suggestion<String>(null, "目录"),
        outputDirSug = "",
        outputExists = true,
        title = "旧标题",
        series = "旧系列",
        writer = "旧作者",
        volume = 1,
        language = LangChoice.Unset,
        cbzFileName = "旧.cbz",
        outputDir = "",
        seriesDir = seriesDir,
    )

    @Test
    fun `every current value comes from the xml`() {
        val xml = ComicInfo.build(
            volume().copy(title = "新标题", series = "新系列", writer = "新作者", volume = 2, language = LangChoice.Ja),
        )
        val out = withComicInfoValues(volume(), xml)
        assertEquals("新标题", out.title)
        assertEquals("新系列", out.series)
        assertEquals("新作者", out.writer)
        assertEquals(2, out.volume)
        assertEquals(LangChoice.Ja, out.language)
    }

    @Test
    fun `suggestions and their sources are left untouched`() {
        val xml = ComicInfo.build(volume().copy(title = "新标题", writer = "新作者"))
        val out = withComicInfoValues(volume(), xml)
        // 建议值仍来自目录结构 —— 这样「恢复建议值」按下去还能回到目录名推导的结果。
        // 若这里改成以新 XML 为建议值，按钮就成了空操作。
        assertEquals("建议标题", out.titleSug.value)
        assertEquals("建议作者", out.writerSug.value)
        assertEquals("目录", out.titleSug.source)
    }

    @Test
    fun `cbz file name, output dir and override marks stay`() {
        val marked = volume().copy(overridden = setOf(MetaField.Title))
        val out = withComicInfoValues(marked, ComicInfo.build(marked.copy(title = "新标题")))
        assertEquals("旧.cbz", out.cbzFileName)
        assertEquals("", out.outputDir)
        assertEquals(setOf(MetaField.Title), out.overridden)
    }

    @Test
    fun `a missing Series tag leaves the current series empty, not the suggestion`() {
        // 与 applyExistingComicInfo 逐字一致：当前值只认文件，文件没写就是空。
        // 「单本的 Series 建议跟随 Title」说的是**建议值**（seriesSug），
        // 不是当前值 —— 两者搞混会让「文件里有没有写 Series」在界面上分不出来。
        val xml = ComicInfo.build(volume().copy(title = "只有标题", series = ""))
        val out = withComicInfoValues(volume(seriesDir = ""), xml)
        assertEquals("只有标题", out.title)
        assertEquals("", out.series)
        // 建议值仍按「单本跟随 Title」的规则走，这是 seriesSug 的事
        assertEquals("建议系列", out.seriesSug.value)
    }

    @Test
    fun `a missing Number tag clears the volume number`() {
        val xml = ComicInfo.build(volume().copy(volume = null))
        assertNull(withComicInfoValues(volume(), xml).volume)
    }

    @Test
    fun `unparseable xml leaves the volume as it was`() {
        val original = volume()
        assertEquals(original, withComicInfoValues(original, "这不是 xml"))
    }

    @Test
    fun `agrees with applyExistingComicInfo on the same xml`() {
        val xml = ComicInfo.build(volume().copy(title = "T", series = "S", writer = "W", volume = 3))
        val viaXml = withComicInfoValues(volume(), xml)
        val viaProbe = applyExistingComicInfo(listOf(volume()), ExistingComicInfoProbe { parseComicInfo(xml) })
            .single()
        assertEquals(viaProbe.title, viaXml.title)
        assertEquals(viaProbe.series, viaXml.series)
        assertEquals(viaProbe.writer, viaXml.writer)
        assertEquals(viaProbe.volume, viaXml.volume)
        assertEquals(viaProbe.language, viaXml.language)
    }
}
