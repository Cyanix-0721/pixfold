package com.pixfold.d1.domain.pack

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CBZ 字节生成(D3 退出条件 1 的「CBZ 生成」)。
 *
 * ## 为什么这一层可以直接断言字节
 *
 * 依据 `docs/notes/scripts-behavior-matrix.md` §6.2,参考脚本 `create_cbz()` 用的是
 * `zipfile.ZIP_STORED`(无压缩,docstring 注明"漫画阅读器兼容性最佳")且
 * `zf.writestr("ComicInfo.xml", …)` 是**第一个条目**。这两条不是实现细节,
 * 而是**与既有脚本的兼容性契约** —— 故这里逐条钉死,而不是只断言"能打开"。
 *
 * ## 为什么时间戳必须参数化
 *
 * `ZipEntry` 的时间戳会进字节流。若从系统时钟取,同一个输入两次运行产出不同字节,
 * 「回读 = 原样」这条断言就永远无法稳定成立,跨 JDK 也不可比。
 * 故 [CbzWriter.write] 强制传入 `timeMillis`:确定性成为**接口性质**,而不是运气。
 */
class CbzWriterTest {

    private val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo />\n"
    private val time = 1_700_000_000_000L

    private fun entry(name: String, content: String) = CbzEntry(name, content.toByteArray(Charsets.UTF_8))

    private fun write(
        comicInfoXml: String = xml,
        pages: List<CbzEntry> = listOf(entry("001.jpg", "A"), entry("002.jpg", "BB")),
        timeMillis: Long = time,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        CbzWriter.write(comicInfoXml, pages, out, timeMillis)
        return out.toByteArray()
    }

    /** 回读:返回 (条目名 → 字节) 的**有序**列表。 */
    private fun readBack(bytes: ByteArray): List<Pair<ZipEntry, ByteArray>> {
        val result = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                result += e to zip.readBytes()
            }
        }
        return result
    }

    // ---- 条目顺序 ----

    @Test
    fun `comic info is structurally the first entry`() {
        val entries = readBack(write())
        assertEquals(CbzWriter.COMIC_INFO_ENTRY, entries.first().first.name)
    }

    @Test
    fun `page entries follow comic info in plan order`() {
        val names = readBack(write()).map { it.first.name }
        assertEquals(listOf("ComicInfo.xml", "001.jpg", "002.jpg"), names)
    }

    @Test
    fun `entry order follows the caller's page list rather than any sorting`() {
        // 页序可能是**人工调整过**的(甚至逆序)。写入层不得再排一次序 ——
        // 排序是计划层的事,写入层只管照做,否则计划页与成品会不一致。
        val shuffled = listOf(entry("007.jpg", "g"), entry("001.jpg", "a"), entry("003.jpg", "c"))
        assertEquals(
            listOf("ComicInfo.xml", "007.jpg", "001.jpg", "003.jpg"),
            readBack(write(pages = shuffled)).map { it.first.name },
        )
    }

    // ---- 压缩方式 ----

    @Test
    fun `every entry is stored without compression`() {
        val entries = readBack(write())
        assertTrue(entries.isNotEmpty())
        entries.forEach { (e, _) ->
            assertEquals(ZipEntry.STORED, e.method, "条目 ${e.name} 必须无压缩(与参考脚本一致)")
        }
    }

    @Test
    fun `comic info is stored too, not deflated`() {
        // XML 是纯文本,很容易被"顺手"改成 DEFLATED;但参考脚本整包 STORED。
        // 混用压缩方式会让"与脚本产物同构"这条兼容性承诺失效。
        val info = readBack(write()).first()
        assertEquals(CbzWriter.COMIC_INFO_ENTRY, info.first.name)
        assertEquals(ZipEntry.STORED, info.first.method)
    }

    // ---- 内容逐字节 ----

    @Test
    fun `comic info bytes are written verbatim`() {
        val info = readBack(write()).first()
        assertContentEquals(xml.toByteArray(Charsets.UTF_8), info.second)
    }

    @Test
    fun `page bytes are written verbatim`() {
        val pages = readBack(write()).drop(1)
        assertContentEquals("A".toByteArray(), pages[0].second)
        assertContentEquals("BB".toByteArray(), pages[1].second)
    }

    @Test
    fun `utf8 content is preserved byte for byte`() {
        // 中文标题会经 ComicInfo.xml 进来;若用平台默认编码,内容会静默变样。
        val chinese = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<Title>进击的巨人</Title>\n"
        val info = readBack(write(comicInfoXml = chinese)).first()
        assertContentEquals(chinese.toByteArray(Charsets.UTF_8), info.second)
    }

    // ---- 时间戳参数化 = 确定性 ----

    @Test
    fun `the same input and timestamp produce byte identical archives`() {
        assertContentEquals(write(), write(), "同输入同时间戳必须产出完全相同的字节")
    }

    @Test
    fun `the entry timestamp comes from the parameter`() {
        val entries = readBack(write(timeMillis = time))
        entries.forEach { (e, _) ->
            assertEquals(time, e.time, "条目 ${e.name} 的时间戳应来自参数")
        }
    }

    @Test
    fun `a different timestamp changes the bytes`() {
        // 反向守卫:若时间戳被忽略,上一条会通过而这条会失败 —— 两条一起才说明参数真的生效
        assertTrue(!write(timeMillis = time).contentEquals(write(timeMillis = time + 2000)))
    }

    @Test
    fun `a non positive timestamp is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { write(timeMillis = 0) }
        assertTrue("timeMillis" in ex.message.orEmpty(), "报错应点明问题所在，实际 ${ex.message}")
    }

    // ---- 边界 ----

    @Test
    fun `a volume without pages still yields a valid archive holding only comic info`() {
        val entries = readBack(write(pages = emptyList()))
        assertEquals(1, entries.size)
        assertEquals(CbzWriter.COMIC_INFO_ENTRY, entries.single().first.name)
    }

    @Test
    fun `a zero length page is a legal stored entry`() {
        // STORED 要求预先写入 size/crc;空内容的 crc 是 0、size 是 0 ——
        // 若实现写成"size 必须为正",这里就会挡掉一个合法输入。
        val entries = readBack(write(pages = listOf(CbzEntry("001.jpg", ByteArray(0)))))
        assertEquals(2, entries.size)
        assertEquals(0, entries[1].second.size)
    }

    @Test
    fun `a non ascii entry name survives the round trip`() {
        // 条目名走 UTF-8(Java 的 ZipOutputStream 默认即 UTF-8 并置 EFS 标志);
        // 若换成平台默认编码,中文条目名回读会变乱码 —— 这里钉死该性质。
        val entries = readBack(write(pages = listOf(entry("封面.jpg", "x"))))
        assertEquals(listOf("ComicInfo.xml", "封面.jpg"), entries.map { it.first.name })
    }

    // ---- 流的所有权 ----

    /** 记录 [close] 是否被调用的输出流。 */
    private class TrackingStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    @Test
    fun `the caller's stream is left open`() {
        // write() 用 finish() 而非 close():写出中央目录,但把流的处置权留给调用方。
        // 这是**接口契约**(KDoc 明写),不是实现细节 —— 平台层要在同一个流上连续写多卷、
        // 或在写完后继续追加校验数据时,被静默关掉的流会让后续操作失败。
        // 用 ByteArrayOutputStream 测不出这一点(它的 close() 是空操作),故这里用带标记的流。
        val out = TrackingStream()
        CbzWriter.write(xml, listOf(entry("001.jpg", "A")), out, time)
        assertTrue(out.size() > 0, "内容应已写出")
        assertFalse(out.closed, "CbzWriter 不得关闭调用方的流")
    }

    @Test
    fun `a finished stream is still usable by the caller`() {
        // finish() 之后仍可继续写:调用方能在同一流里接着做别的事。
        val out = ByteArrayOutputStream()
        CbzWriter.write(xml, listOf(entry("001.jpg", "A")), out, time)
        val afterZip = out.size()
        out.write("TRAILER".toByteArray())
        assertEquals(afterZip + 7, out.size())
    }

    // ---- 条目不变式 ----

    @Test
    fun `an empty entry name is rejected`() {
        val ex = assertFailsWith<IllegalArgumentException> { CbzEntry("", ByteArray(1)) }
        assertTrue("条目名" in ex.message.orEmpty(), "报错应点明条目名，实际 ${ex.message}")
    }

    @Test
    fun `a directory shaped entry name is rejected`() {
        // 以 / 结尾的名字会被阅读器当成目录项 —— 漫画库里不该出现这种条目。
        val ex = assertFailsWith<IllegalArgumentException> { CbzEntry("pages/", ByteArray(1)) }
        assertTrue("目录项" in ex.message.orEmpty(), "报错应点明目录项，实际 ${ex.message}")
    }

    @Test
    fun `entries compare by byte content rather than identity`() {
        // ByteArray 的 equals 是引用比较,data class 生成的实现会让"内容相同"的两个条目不等,
        // 于是去重与断言都会静默出错。这里钉死按内容比较。
        assertEquals(entry("001.jpg", "A"), entry("001.jpg", "A"))
        assertEquals(entry("001.jpg", "A").hashCode(), entry("001.jpg", "A").hashCode())
        assertTrue(entry("001.jpg", "A") != entry("001.jpg", "B"), "内容不同必须不等")
        assertTrue(entry("001.jpg", "A") != entry("002.jpg", "A"), "条目名不同必须不等")
    }
}
