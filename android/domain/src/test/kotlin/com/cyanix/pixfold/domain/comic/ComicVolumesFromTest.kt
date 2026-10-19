package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.SourceItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 真实扫描结果 → 卷列表的转换。
 *
 * 这一层是「工作流 B 打真实文件」的前提:此前 `ComicVolume` 只由 mock 构造,
 * 所以打包点下去走的是确定性模拟,磁盘上没有任何字节。
 */
class ComicVolumesFromTest {

    private fun page(collectionId: String, index: Int) = SourceItem(
        id = "content://doc/$collectionId/p$index.jpg",
        collectionId = collectionId,
        dir = "",
        baseName = "p$index",
        ext = "jpg",
        sizeBytes = 1000L,
        modifiedEpochMillis = 0L,
        createdEpochMillis = null,
        seed = index,
    )

    private fun collection(
        id: String,
        name: String = id.substringAfterLast('/'),
        seriesKey: String = "",
        seriesName: String = "",
        pages: Int = 2,
    ) = ImageCollection(
        id = id,
        name = name,
        rootDirName = name,
        images = (1..pages).map { page(id, it) },
        seriesKey = seriesKey,
        seriesName = seriesName,
    )

    private fun convert(
        collections: List<ImageCollection>,
        root: String = "test",
        exists: (com.cyanix.pixfold.domain.model.ComicVolume) -> Boolean = { false },
    ) = comicVolumesFrom(collections, root, exists)

    // ---- 体积与基本映射 ----

    @Test
    fun `每个集合转成一卷`() {
        val result = convert(listOf(collection("a"), collection("b"), collection("c")))
        assertEquals(listOf("a", "b", "c"), result.map { it.id })
    }

    @Test
    fun `卷的页来自集合的图片`() {
        val result = convert(listOf(collection("a", pages = 3)))
        assertEquals(3, result.single().pages.size)
        assertEquals(3, result.single().pageCount)
    }

    @Test
    fun `空集合也转成卷但页数为零`() {
        val result = convert(listOf(collection("a", pages = 0)))
        assertEquals(1, result.size)
        assertTrue(result.single().pages.isEmpty())
        assertEquals(0, result.single().pageCount)
    }

    @Test
    fun `集合顺序保持不变`() {
        val result = convert(listOf(collection("z"), collection("a"), collection("m")))
        assertEquals(listOf("z", "a", "m"), result.map { it.id })
    }

    // ---- 系列与标题 ----

    @Test
    fun `有系列时标题仍取卷目录名而系列单独建议`() {
        // 2026-10-19 改判(title 由系列名改为卷目录名,用户 m05761 指出):
        // 默认文件名应当描述**这一本**。取系列名会让同系列多本全部同名,
        // 而真实素材恰恰证明「系列名 = 第一本的名字」,取系列名时两本文件名完全相同。
        val result = convert(listOf(collection("S/第01卷", seriesKey = "S", seriesName = "系列S")))
        assertEquals("第01卷", result.single().titleSug.value)
        assertEquals("系列S", result.single().seriesSug.value)
    }

    @Test
    fun `没有系列时标题取卷目录名且系列无建议`() {
        val result = convert(listOf(collection("单本")))
        assertEquals("单本", result.single().titleSug.value)
        assertNull(result.single().seriesSug.value)
        assertFalse(result.single().seriesSug.hasValue)
    }

    @Test
    fun `同系列不同目录名产出不同默认文件名`() {
        // 2026-10-19 改判后的核心收益:同系列两本各自有能区分的默认名。
        // 旧实现(title 取系列名)在这组素材上会产出两个「[Cior] S.cbz」。
        val result = convert(
            listOf(
                collection("S/[Cior] S/第01卷", seriesKey = "S/[Cior] S", seriesName = "[Cior] S"),
                collection("S/[Cior] S/第02卷", seriesKey = "S/[Cior] S", seriesName = "[Cior] S"),
            ),
        )
        assertEquals(listOf("第01卷", "第02卷"), result.map { it.titleSug.value })
        assertEquals("第01卷 第01卷.cbz", result[0].cbzFileName)
        assertEquals("第02卷 第02卷.cbz", result[1].cbzFileName)
        assertTrue(result[0].cbzFileName != result[1].cbzFileName)
    }

    // ---- 卷号解析 ----

    @Test
    fun `从第NN卷解析卷号`() {
        assertEquals(1, parseVolumeNumber("第01卷"))
        assertEquals(12, parseVolumeNumber("第12卷"))
        assertEquals(3, parseVolumeNumber("某某 第3巻"))
    }

    @Test
    fun `从 v 与 vol 前缀解析卷号`() {
        assertEquals(5, parseVolumeNumber("v05"))
        assertEquals(7, parseVolumeNumber("Vol.7"))
        assertEquals(9, parseVolumeNumber("vol 9"))
    }

    @Test
    fun `不认裸数字以免把年份当卷号`() {
        assertNull(parseVolumeNumber("2024"))
        assertNull(parseVolumeNumber("01"))
        assertNull(parseVolumeNumber("合集"))
        assertNull(parseVolumeNumber(""))
    }

    @Test
    fun `超出上界的数字不当卷号`() {
        assertNull(parseVolumeNumber("第99999卷"))
        assertEquals(MAX_VOLUME_NUMBER, parseVolumeNumber("第${MAX_VOLUME_NUMBER}卷"))
    }

    @Test
    fun `解析不出卷号时它是待确认项`() {
        val result = convert(listOf(collection("合集", pages = 2)))
        assertNull(result.single().volumeSug.value)
        assertNull(result.single().volume)
        assertTrue(result.single().needsConfirmation)
    }

    @Test
    fun `解析出卷号时不再要求确认卷号`() {
        val result = convert(listOf(collection("第03卷", pages = 2)))
        assertEquals(3, result.single().volume)
        // 语言仍是 Unset ⇒ 仍需确认,但不该再报卷号
        assertTrue(result.single().needsConfirmation)
        assertTrue(result.single().pendingIssues.none { it.toString().contains("Volume") })
    }

    @Test
    fun `卷号不靠列表下标兜底`() {
        // 两卷都解析不出时都是 null —— 不许把第 2 个集合写成第 2 卷。
        val result = convert(listOf(collection("甲"), collection("乙")))
        assertNull(result[0].volume)
        assertNull(result[1].volume)
    }

    // ---- 建议与语言 ----

    @Test
    fun `作者与语言一律不给建议`() {
        val result = convert(listOf(collection("第01卷")))
        assertNull(result.single().writerSug.value)
        assertNull(result.single().langSug.value)
        assertNull(result.single().writerCleanedSug.value)
    }

    @Test
    fun `语言保持 Unset 等待人工确认`() {
        val result = convert(listOf(collection("第01卷")))
        assertEquals(LangChoice.Unset, result.single().language)
        assertTrue(result.single().needsConfirmation)
    }

    @Test
    fun `每种建议都带可读来源`() {
        val result = convert(listOf(collection("S/第01卷", seriesKey = "S", seriesName = "系列S")))
        val volume = result.single()
        assertTrue(volume.titleSug.source.isNotEmpty())
        assertTrue(volume.seriesSug.source.isNotEmpty())
        assertTrue(volume.volumeSug.source.isNotEmpty())
        // 没有建议的字段不许编来源
        assertTrue(volume.writerSug.source.isEmpty())
        assertTrue(volume.langSug.source.isEmpty())
    }

    // ---- 输出目录 ----

    @Test
    fun `输出目录是卷目录的父目录`() {
        val result = convert(listOf(collection("S/第01卷", seriesKey = "S", seriesName = "系列S")))
        assertEquals("S", result.single().outputDir)
    }

    @Test
    fun `根下一级目录的输出目录回退到工作区根名`() {
        val result = convert(listOf(collection("单本")), root = "test")
        assertEquals("test", result.single().outputDir)
    }

    @Test
    fun `输出文件名以 cbz 结尾`() {
        val result = convert(listOf(collection("第01卷")))
        assertTrue(result.single().cbzFileName.endsWith(".cbz"))
    }

    // ---- outputExists 探针 ----

    @Test
    fun `outputExists 由探针决定`() {
        val result = convert(listOf(collection("a"), collection("b"))) { it.id == "b" }
        assertFalse(result[0].outputExists)
        assertTrue(result[1].outputExists)
    }

    @Test
    fun `probe 是必填参数不允许默认 false`() {
        // 编译期约束:漏判 outputExists 会让界面不警告覆盖、撤销却按新建删掉(缺陷 R3)。
        // 这里用显式传参验证签名确实要求调用方给出判断。
        val result = comicVolumesFrom(
            collections = listOf(collection("a")),
            rootDisplayName = "test",
            outputExists = OutputExistsProbe { true },
        )
        assertTrue(result.single().outputExists)
    }

    @Test
    fun `markOutputExists 批量标注已存在的产物`() {
        val volumes = convert(listOf(collection("a"), collection("b")))
        val marked = markOutputExists(volumes, OutputExistsProbe { it.id == "a" })
        assertTrue(marked[0].outputExists)
        assertFalse(marked[1].outputExists)
    }

    // ---- 可打包性 ----

    @Test
    fun `空卷不进打包链`() {
        val volumes = convert(listOf(collection("empty", pages = 0), collection("full", pages = 2)))
        assertEquals(listOf("full"), packableVolumes(volumes).map { it.id })
    }

    @Test
    fun `打包摘要统计卷数与页数`() {
        val volumes = convert(
            listOf(collection("a", pages = 2), collection("b", pages = 3), collection("empty", pages = 0)),
        )
        val summary = packSummary(volumes)
        assertEquals(3, summary.volumeCount)
        assertEquals(5, summary.pageCount)
        assertEquals(1, summary.emptyVolumeCount)
    }

    // ---- 从真实扫描链路端到端 ----

    @Test
    fun `真实素材两级结构转出两卷且各自有系列与卷号`() {
        val series = "[Cior (Ken-1)] 彼女がボクの知らないところで"
        val collections = listOf(
            collection(
                id = "$series/$series",
                name = series,
                seriesKey = series,
                seriesName = series,
                pages = 2,
            ),
            collection(
                id = "$series/$series――2 [DL版]",
                name = "$series――2 [DL版]",
                seriesKey = series,
                seriesName = series,
                pages = 3,
            ),
        )
        val volumes = convert(collections, root = "test")

        assertEquals(2, volumes.size)
        // 2026-10-19 名字清洗生效:系列名开头的 [作者] 前缀被剥掉,作者另行建议。
        assertEquals("彼女がボクの知らないところで", volumes[0].seriesSug.value)
        assertEquals("彼女がボクの知らないところで", volumes[1].seriesSug.value)
        // 作者取自外层(系列目录名)的 [作者] 前缀,原始形态保留方括号。
        assertEquals("[Cior (Ken-1)]", volumes[0].writerSug.value)
        assertEquals("[Cior (Ken-1)]", volumes[1].writerSug.value)
        // 清理只去方括号,内部的 `(Ken-1)` 是署名的一部分(旧脚本 parse_name 同样保留)。
        assertEquals("Cior (Ken-1)", volumes[0].writerCleanedSug.value)
        // 真实素材目录名里没有「第NN卷」⇒ 卷号解析不出、待用户确认(建议≠事实)
        assertNull(volumes[0].volume)
        assertNull(volumes[1].volume)
        // CBZ 名保留作者前缀(文件管理器里要认得出是谁的),且 [DL版] 这类末尾标注被剥掉。
        assertEquals("[Cior (Ken-1)] 彼女がボクの知らないところで.cbz", volumes[0].cbzFileName)
        assertEquals("[Cior (Ken-1)] 彼女がボクの知らないところで――2.cbz", volumes[1].cbzFileName)
        assertTrue(
            volumes[0].cbzFileName != volumes[1].cbzFileName,
            "title 取卷目录名而非系列名,是为了让同系列多本的默认名能区分开",
        )
        // 但卷号仍未解析出 ⇒ 两本的 series 相同而 volume 都为空,
        // ComicInfo.xml 里会有两条没有卷号的记录,由 needsConfirmation 提示用户补。
    }
}
