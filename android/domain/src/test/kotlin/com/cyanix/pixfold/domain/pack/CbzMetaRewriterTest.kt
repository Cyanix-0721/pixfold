package com.cyanix.pixfold.domain.pack

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 工作流 C 的转写契约。
 *
 * ## 为什么断言一律走 `ZipFile`
 *
 * `ZipInputStream` 只能看见本地头，看不见中央目录。而 CBZ 阅读器（以及本项目
 * `CbzReaderCompatTest`）看的是中央目录：条目顺序、method、size、crc 全在那里。
 * 用 `ZipInputStream` 断言"能读回来"会漏掉一类真实缺陷 —— 本地头说 STORED
 * 但中央目录记成 DEFLATED，`ZipInputStream` 照样读得出，阅读器却打不开。
 *
 * ## 为什么时间戳固定
 *
 * 与 [CbzWriterTest] 同一条理由：字节里含时间，取系统时钟会让同一输入产出不同字节。
 */
class CbzMetaRewriterTest {

    private val time = 1_700_000_000_000L
    private val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo />\n"

    private var tempDir: File? = null

    private fun spill(): File =
        tempDir ?: File.createTempFile("cbzmeta-test-", "").let {
            it.delete()
            it.mkdirs()
            tempDir = it
            it
        }

    @AfterTest
    fun cleanup() {
        tempDir?.deleteRecursively()
        tempDir = null
    }

    /** 造一个 ZIP。`deflated` 里的条目名用最高压缩率写。 */
    private fun zipped(
        vararg entries: Pair<String, ByteArray>,
        deflated: Set<String> = emptySet(),
    ): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            for ((name, data) in entries) {
                if (name in deflated) {
                    zip.setLevel(9)
                    val e = ZipEntry(name).apply { time = this@CbzMetaRewriterTest.time }
                    zip.putNextEntry(e)
                    zip.write(data)
                    zip.closeEntry()
                } else {
                    zip.setLevel(0)
                    val checksum = CRC32().apply { update(data) }.value
                    val e = ZipEntry(name).apply {
                        time = this@CbzMetaRewriterTest.time
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = data.size.toLong()
                        crc = checksum
                    }
                    zip.putNextEntry(e)
                    zip.write(data)
                    zip.closeEntry()
                }
            }
        }
        return bos.toByteArray()
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun fileOf(bytes: ByteArray): File {
        val f = File(spill(), "archive-" + System.nanoTime() + ".zip")
        f.writeBytes(bytes)
        return f
    }

    private fun rewrite(
        input: ByteArray,
        xml: String = this.xml,
        spillDir: File? = null,
        onEntryWritten: (Int, Int) -> Unit = { _, _ -> },
    ): ByteArray {
        val out = ByteArrayOutputStream()
        CbzMetaRewriter.rewrite(input.inputStream(), out, xml, time, spillDir, onEntryWritten)
        return out.toByteArray()
    }

    private fun names(bytes: ByteArray): List<String> =
        ZipFile(fileOf(bytes)).use { zip -> zip.entries().toList().map { it.name } }

    // T1
    @Test
    fun `an empty archive still yields exactly one ComicInfo`() {
        val empty = zipped()
        assertEquals(listOf("ComicInfo.xml"), names(rewrite(empty)))
    }

    // T2
    @Test
    fun `an old ComicInfo in the middle lands first and the rest keep order`() {
        val src = zipped(
            "001.jpg" to bytes("A"),
            "002.jpg" to bytes("B"),
            "ComicInfo.xml" to bytes("<old/>"),
            "003.jpg" to bytes("C"),
        )
        assertEquals(
            listOf("ComicInfo.xml", "001.jpg", "002.jpg", "003.jpg"),
            names(rewrite(src)),
        )
    }

    // T3
    @Test
    fun `two ComicInfo variants collapse into one`() {
        val src = zipped(
            "ComicInfo.xml" to bytes("<a/>"),
            "001.jpg" to bytes("A"),
            "comicinfo.xml" to bytes("<b/>"),
        )
        val out = names(rewrite(src))
        assertEquals(1, out.count { it.equals("ComicInfo.xml", ignoreCase = true) })
        assertEquals(listOf("ComicInfo.xml", "001.jpg"), out)
    }

    // T4
    @Test
    fun `the written XML is exactly the bytes we handed in`() {
        val out = rewrite(zipped("001.jpg" to bytes("A")), xml = "<ComicInfo><Title>新</Title></ComicInfo>")
        ZipFile(fileOf(out)).use { zip ->
            val text = zip.getInputStream(zip.getEntry("ComicInfo.xml")!!).readBytes().toString(Charsets.UTF_8)
            assertEquals("<ComicInfo><Title>新</Title></ComicInfo>", text)
        }
    }

    // T5
    @Test
    fun `every output entry is stored`() {
        val src = zipped("001.jpg" to bytes("A"), "002.jpg" to bytes("BB"))
        ZipFile(fileOf(rewrite(src))).use { zip ->
            zip.entries().toList().forEach { assertEquals(ZipEntry.STORED, it.method, "条目必须是 STORED：" + it.name) }
        }
    }

    // T6
    @Test
    fun `crc and size in the central directory match the real bytes`() {
        val payload = bytes("PAYLOAD-" + "x".repeat(100))
        val src = zipped("001.jpg" to payload)
        ZipFile(fileOf(rewrite(src))).use { zip ->
            val e = zip.getEntry("001.jpg")!!
            val real = zip.getInputStream(e).readBytes()
            assertContentEquals(payload, real)
            assertEquals(payload.size.toLong(), e.size)
            assertEquals(CRC32().apply { update(payload) }.value, e.crc)
        }
    }

    // T7
    @Test
    fun `deflated input entries become stored with identical bytes`() {
        val payload = bytes("DEFLATED-PAYLOAD")
        val src = zipped("001.jpg" to payload, deflated = setOf("001.jpg"))
        ZipFile(fileOf(rewrite(src, spillDir = spill()))).use { zip ->
            val e = zip.getEntry("001.jpg")!!
            assertEquals(ZipEntry.STORED, e.method)
            assertContentEquals(payload, zip.getInputStream(e).readBytes())
        }
    }

    // T8
    @Test
    fun `deflated input without a spill dir is rejected`() {
        val src = zipped("001.jpg" to bytes("P"), deflated = setOf("001.jpg"))
        assertFailsWith<IllegalStateException> { rewrite(src, spillDir = null) }
    }

    // T9
    @Test
    fun `page entry names are preserved verbatim, never renumbered`() {
        val src = zipped("007.jpg" to bytes("A"), "cover.png" to bytes("C"))
        assertEquals(listOf("ComicInfo.xml", "007.jpg", "cover.png"), names(rewrite(src)))
    }

    // T10
    @Test
    fun `directory entries are dropped from the rewritten archive`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            zip.setLevel(0)
            zip.putNextEntry(
                ZipEntry("pages/").apply {
                    time = this@CbzMetaRewriterTest.time
                    method = ZipEntry.STORED
                    size = 0
                    compressedSize = 0
                    crc = 0
                },
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("pages/001.jpg").apply {
                time = this@CbzMetaRewriterTest.time
                method = ZipEntry.STORED
                val data = bytes("A")
                size = 1
                compressedSize = 1
                crc = CRC32().apply { update(data) }.value
            })
            zip.write(bytes("A"))
            zip.closeEntry()
        }
        assertEquals(listOf("ComicInfo.xml", "pages/001.jpg"), names(rewrite(bos.toByteArray())))
    }

    // T11
    @Test
    fun `progress advances once per written entry`() {
        val seen = mutableListOf<Int>()
        rewrite(
            zipped("001.jpg" to bytes("A"), "002.jpg" to bytes("B")),
            onEntryWritten = { done, _ -> seen += done },
        )
        assertEquals(listOf(1, 2, 3), seen)
    }

    // T12
    @Test
    fun `neither the input nor the output stream is closed`() {
        val src = TrackingInput(zipped("001.jpg" to bytes("A")).inputStream())
        val out = TrackingOutput()
        val count = CbzMetaRewriter.rewrite(src, out, xml, time, null)
        assertEquals(2, count)
        assertTrue(!src.closed, "输入流归调用方，重写器不得关闭它")
        assertTrue(!out.closed, "输出流归调用方，重写器不得关闭它")
    }

    private class TrackingInput(private val inner: InputStream) : InputStream() {
        var closed = false
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        override fun close() { closed = true }
    }

    private class TrackingOutput : OutputStream() {
        var closed = false
        val bytes = ByteArrayOutputStream()
        override fun write(b: Int) = bytes.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
        override fun close() { closed = true }
    }
}
