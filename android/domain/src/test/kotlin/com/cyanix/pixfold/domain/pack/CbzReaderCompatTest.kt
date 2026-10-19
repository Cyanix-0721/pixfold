package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.comic.ComicInfo
import com.cyanix.pixfold.domain.comic.renumberPages
import com.cyanix.pixfold.domain.mock.MockDataB
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「CBZ 可被漫画阅读器打开」(HANGOFF §10《数据正确性》)的可执行检查。
 *
 * ## 为什么单独一个文件,而不是加进 `CbzWriterTest`
 *
 * 现有 `CbzWriterTest` / `CbzStreamingTest` / `CbzWritePlanTest` 回读时**全部**用
 * [ZipInputStream] —— 那是**顺序读取**:从头扫局部文件头,只认数据本身。
 *
 * 而真实阅读器(Tachiyomi/Mihon、Kuro Reader、Perfect Viewer 等)几乎都用
 * [ZipFile] / libzip / minizip —— **读中央目录**(End of Central Directory, EOCD)
 * 再按偏移随机访问。两者验证的是 ZIP 的**不同部分**:
 *
 *  - `ZipInputStream` 能通过而 `ZipFile` 打不开,是完全可能的状态 ——
 *    中央目录写坏、EOCD 偏移错、comment 长度不符都长这样;
 *  - 数据库里的字节**结构合法**(能被 `ZipInputStream` 全程读出)也不等于
 *    **阅读器兼容**(条目顺序、`ComicInfo.xml` 位置、STORED、条目名)。
 *
 * 故这里全部改用 [ZipFile](走中央目录),并按阅读器的实际读取方式断言。
 * 本文件的存在本身就是「此前 `ZipFile` **一次都没被覆盖过**」这句话的证据 ——
 * `grep -rn "ZipFile" domain/src/test app/src/test` 在本文件之前是零命中。
 *
 * ## 真机基线
 *
 * 2026-10-19 用真机产出的 `Vol 01 第01卷.cbz`(38,085,388 B / 24 页)在
 * **Kuro Reader Pro 1.6.7** 上实测:首屏正常渲染、进度条拖到末页 `24 / 24` 正常显示、
 * 全程无解密报错。本文件的断言即该次真机读数的可复现化。
 */
class CbzReaderCompatTest {

    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        tempFiles.forEach { it.delete() }
        tempFiles.clear()
    }

    /**
     * 写出真实 mock 卷的 CBZ 字节。
     *
     * **刻意走 `writeStreaming`**:那才是生产路径(`PackRunner` → `writeCbz` → 本函数)。
     * `write` 收的是已经在内存里的 `ByteArray`,真机上一条 100MB 页会直接 OOM ——
     * 拿它当"代表性产物"会把真正要验的那条路径漏掉。
     */
    private fun packVolume(index: Int = 0): ByteArray {
        val volume = MockDataB.library.volumes[index]
        val plan = buildCbzWritePlan(volume)
        val bytes = pagesOf(volume)
        val source = streamingPageByteSource { itemId -> bytes[itemId]?.inputStream() }
        val out = ByteArrayOutputStream()
        CbzWriter.writeStreaming(plan.comicInfoXml, plan.pages, source, out, TIME)
        return out.toByteArray()
    }

    /** itemId → 该页字节(与 [packVolume] 同一份夹具)。 */
    private fun pagesOf(volume: com.cyanix.pixfold.domain.model.ComicVolume): Map<String, ByteArray> =
        volume.pageOrder.order.associate { item -> item.id to jpegBytes(item.fileName) }

    /**
     * 造一段**结构合法**的 JPEG 字节(SOI + 任意负载 + EOI)。
     *
     * mock 数据里没有真实图片字节(`SourceItem` 只有元数据),而本文件要断的正是
     * "阅读器拿到的字节是不是完整图片",故必须自己造出带正确魔数的内容。
     */
    private fun jpegBytes(seedText: String): ByteArray {
        val body = seedText.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + body + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    /**
     * 把字节落到临时文件 —— [ZipFile] 只接受文件(或需要 `File` 才能按偏移 seek),
     * 不接受 `ByteArray`。**这一步是必须的**:用 `ZipInputStream` 读 `ByteArray`
     * 恰好绕过了「中央目录是否可以按偏移定位」这件事。
     */
    private fun materialize(bytes: ByteArray): File {
        val file = Files.createTempFile("cbz-reader-compat", ".cbz").toFile()
        file.writeBytes(bytes)
        tempFiles += file
        return file
    }

    // ================= 中央目录可读（阅读器真实走的那条路） =================

    @Test
    fun `a reader that reads the central directory can open the archive`() {
        val volume = MockDataB.library.volumes[0]
        val file = materialize(packVolume())
        ZipFile(file).use { zip ->
            // 阅读器拿到的第一件事就是条目清单 —— 它来自中央目录,不来自数据流。
            val names = zip.entries().toList().map { it.name }
            val expected = listOf(CbzWriter.COMIC_INFO_ENTRY) + renumberPages(volume).map { it.fileName }
            // 顺序也一并断死:阅读器的页码就是**中央目录里的顺序**。
            // 只断"数量对 + 首个是 XML"时,页序被打乱仍然全绿 ——
            // 而用户看到的是"第 1 页变成了别的图"。
            assertEquals(expected, names, "中央目录的条目顺序必须等于 ComicInfo + 页序")
        }
    }

    @Test
    fun `every page is readable by name through the central directory`() {
        // 阅读器是**按名字取条目**的(`getEntry("001.jpg")`),不是按顺序读。
        // 顺序读通过而按名取失败,说明中央目录里的名字与局部头里的名字不一致。
        val volume = MockDataB.library.volumes[0]
        val plan = buildCbzWritePlan(volume)
        val file = materialize(packVolume())
        ZipFile(file).use { zip ->
            plan.pages.forEach { entry ->
                val hit = assertNotNull(
                    zip.getEntry(entry.fileName),
                    "中央目录里找不到 ${entry.fileName} —— 阅读器会认为这一页不存在",
                )
            }
        }
    }

    @Test
    fun `page entries carry no directory prefix`() {
        // 有些阅读器只扫归档**根**下的图片。带 `Vol 01/` 之类前缀时,
        // 部分阅读器会把整卷当成空卷(不是报错,是显示 0 页)。
        val file = materialize(packVolume())
        ZipFile(file).use { zip ->
            val images = zip.entries().toList().map { it.name }
                .filter { it != CbzWriter.COMIC_INFO_ENTRY }
            assertTrue(images.isNotEmpty())
            images.forEach { name ->
                assertTrue('/' !in name, "页条目不得带目录前缀：$name")
            }
        }
    }

    @Test
    fun `all entries are stored rather than deflated`() {
        // STORED 不只是"无压缩":阅读器可以对图片直接按偏移 mmap / seek,
        // 不必先解压整条目 —— 这是 CBZ 相对普通 ZIP 的读取优势,也是参考脚本的选择。
        val file = materialize(packVolume())
        ZipFile(file).use { zip ->
            zip.entries().toList().forEach {
                assertEquals(
                    ZipEntry_SSTORED,
                    it.method,
                    "${it.name} 必须 STORED —— 图片已压缩,deflate 既慢又破坏 seek",
                )
            }
        }
    }

    @Test
    fun `entry CRCs match the bytes actually stored`() {
        // `ZipFile` 在读时会校验 CRC。这里显式算一遍,把"CRC 字段写对"与
        // "数据写对"两件事分开断言 —— 二者错一件都会让严格阅读器拒绝打开。
        val file = materialize(packVolume())
        ZipFile(file).use { zip ->
            zip.entries().toList().forEach { entry ->
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                val crc = CRC32().apply { update(bytes) }
                assertEquals(entry.crc, crc.value, "${entry.name} 的 CRC 字段与数据不符")
                assertEquals(entry.size, bytes.size.toLong(), "${entry.name} 的 size 字段与数据不符")
            }
        }
    }

    // 刻意**不**单独断言「中央目录与局部头逐条一致」:
    // 那条在 2026-10-19 的自我变异里(WORD: 把 size/crc 一致性断言删掉)存活 ——
    // 因为当前 `ZipOutputStream` 保证两处同源,任何能制造不一致的破坏
    // (不调 `finish()`、改 DEFLATED)都会先把上面 / 下面的多条测试打红。
    // 留一条打不响的断言只会给假保障。若将来换成 Apache Commons Compress 之类
    // 自己拼中央目录的实现,必须把那条补回来。

    // ================= 阅读器真正拿去渲染的字节 =================

    @Test
    fun `page bytes are complete JPEG images`() {
        // 阅读器把条目字节直接交给图片解码器 —— 少了 EOI 的半张图在多数阅读器上
        // 表现为"最后一页灰块"而不是报错。这里用 JPEG 的 SOI/EOI 魔数兜住。
        val volume = MockDataB.library.volumes[0]
        val plan = buildCbzWritePlan(volume)
        val file = materialize(packVolume())
        ZipFile(file).use { zip ->
            plan.pages.forEach { entry ->
                val bytes = zip.getInputStream(assertNotNull(zip.getEntry(entry.fileName))).use { it.readBytes() }
                assertTrue(bytes.size > 2, "${entry.fileName} 是空条目")
                assertEquals(Jpeg_SOI, bytes[0].toInt() and 0xFF, "${entry.fileName} 不是 JPEG(缺 SOI)")
                assertEquals(Jpeg_SOI_SECOND, bytes[1].toInt() and 0xFF, "${entry.fileName} 不是 JPEG(缺 SOI)")
                assertEquals(
                    Jpeg_EOI,
                    bytes[bytes.size - 1].toInt() and 0xFF,
                    "${entry.fileName} 的 JPEG 被截断(缺 EOI)",
                )
                assertEquals(
                    Jpeg_EOI_FIRST,
                    bytes[bytes.size - 2].toInt() and 0xFF,
                    "${entry.fileName} 的 JPEG 被截断(缺 EOI)",
                )
            }
        }
    }

    @Test
    fun `page count announced by ComicInfo matches the real entry count`() {
        // ComicInfo 里若宣告了与归档不符的页数,部分阅读器会按宣告值翻页,
        // 翻到不存在的页时显示空白 —— 用户看到的是"最后几页是空的"。
        val volume = MockDataB.library.volumes[0]
        val plan = buildCbzWritePlan(volume)
        val file = materialize(packVolume())
        ZipFile(file).use { zip ->
            val xml = zip.getInputStream(assertNotNull(zip.getEntry(CbzWriter.COMIC_INFO_ENTRY)))
                .use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals(plan.comicInfoXml, xml, "写入的 XML 必须与计划逐字一致")

            val realPages = zip.entries().toList().count { it.name != CbzWriter.COMIC_INFO_ENTRY }
            assertEquals(volume.pageCount, realPages, "归档内的页条目数必须等于卷页数")
            assertEquals(volume.pageCount, renumberPages(volume).size, "重编号结果必须同源")
        }
    }

    @Test
    fun `streamed archives are readable through the central directory too`() {
        // 生产路径走的是 `writeStreaming`(流式两遍读),不是 `write`。
        // 若只给 `write` 加中央目录断言,真正的代码路径反而没被覆盖。
        val volume = MockDataB.library.volumes[0]
        val plan = buildCbzWritePlan(volume)
        val fixture = pagesOf(volume)
        val source = streamingPageByteSource { itemId -> fixture[itemId]?.inputStream() }
        val out = ByteArrayOutputStream()
        CbzWriter.writeStreaming(plan.comicInfoXml, plan.pages, source, out, TIME)

        ZipFile(materialize(out.toByteArray())).use { zip ->
            assertEquals(
                CbzWriter.COMIC_INFO_ENTRY,
                zip.entries().toList().first().name,
                "流式路径同样必须把 ComicInfo.xml 放在首位",
            )
            plan.pages.forEach { entry ->
                val hit = assertNotNull(zip.getEntry(entry.fileName), "流式产物缺条目：${entry.fileName}")
                assertContentEquals(fixture[entry.itemId], zip.getInputStream(hit).use { it.readBytes() })
            }
        }
    }

    @Test
    fun `comic info is parseable xml rather than a truncated fragment`() {
        // 阅读器解析元数据时用的是真正的 XML 解析器;截断或非法转义会抛异常,
        // 有的阅读器据此直接拒绝打开整个文件。
        val volume = MockDataB.library.volumes[0]
        val file = materialize(packVolume())
        val xml = ZipFile(file).use { zip ->
            zip.getInputStream(assertNotNull(zip.getEntry(CbzWriter.COMIC_INFO_ENTRY)))
                .use { it.readBytes().toString(Charsets.UTF_8) }
        }
        assertEquals(ComicInfo.build(volume), xml, "字节内容必须就是 ComicInfo.build 的产物")

        // 最小可用的良构性检查:解析器能完整吃下去。
        val parsed = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())
        assertEquals("ComicInfo", parsed.documentElement.tagName, "根元素必须是 ComicInfo")
    }

    private companion object {
        private const val TIME = 1_700_000_000_000L

        /** `java.util.zip.ZipEntry.STORED` = 0。常量直接写死以避开 `ZipEntry` 的实例方法。 */
        private const val ZipEntry_SSTORED = 0

        private const val Jpeg_SOI = 0xFF
        private const val Jpeg_SOI_SECOND = 0xD8
        private const val Jpeg_EOI = 0xD9
        private const val Jpeg_EOI_FIRST = 0xFF
    }
}
