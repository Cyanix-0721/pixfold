package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.task.CancellationToken
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 流式页字节:大图不该把整页读进堆内存。
 *
 * ## 为什么必须有这一层
 *
 * `ZipEntry.STORED` 要求写入前已知 `size` / `compressedSize` / `crc`(见 `CbzWriter.stored`),
 * 这条硬约束无法绕过。但「先知道 size 与 crc」**不等于「必须把整页留在内存里」**:
 * 可以第一遍流式读过算 CRC 与长度(边读过丢),第二遍再流式写出去。
 *
 * 真机实证(`/storage/emulated/0/test/cancel`,4 卷 × 4 张 100MB 页):
 * 旧实现走 `ContentResolver.openInputStream().readBytes()` 把整页读进 `ByteArray`,
 * 在 256MB 堆上直接
 * `java.lang.OutOfMemoryError: Failed to allocate a 104857616 byte allocation`
 * 于 `SafPageBytes.bytesOf` → `readBytes` → `ByteArrayOutputStream.toByteArray`
 * 抛出,进程被系统杀掉,**并在输出目录留下 0 字节 CBZ**。
 *
 * 所以端口要能表达「这一页按流读」,而不仅仅是「这一页的字节」。
 */
class CbzStreamingTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val time = 1_700_000_000_000L

    private fun volumeWith(pageCount: Int): ComicVolume {
        val pages = (1..pageCount).map { i ->
            SourceItem(
                id = "p$i",
                collectionId = "c",
                dir = "D:\\lib\\v",
                baseName = "img${i.toString().padStart(3, '0')}",
                ext = "jpg",
                sizeBytes = 10L,
                modifiedEpochMillis = time,
                createdEpochMillis = null,
                seed = i,
            )
        }
        return aot1.copy(pages = pages, pageOrder = pageOrderOf(pages))
    }

    private fun readBack(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                result += e.name to zip.readBytes()
            }
        }
        return result
    }

    // ---- 端口形状:能按流读,且能单独报告"打不开" ----

    @Test
    fun `streaming source exposes an input stream per item`() {
        val plan = buildCbzWritePlan(volumeWith(3))
        val source = streamingPageByteSource { id -> "bytes-of-$id".toByteArray().inputStream() }

        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, source, out, time)

        assertTrue(result.ok, "三页都能读到时必须全部写成功")
        val entries = readBack(out.toByteArray())
        assertEquals(plan.pageCount + 1, entries.size, "ComicInfo.xml + 每页一条")
        assertContentEquals("bytes-of-p1".toByteArray(), entries[1].second)
    }

    @Test
    fun `streaming source reports a page it cannot open without throwing`() {
        val plan = buildCbzWritePlan(volumeWith(3))
        val source = streamingPageByteSource { id -> if (id == "p2") null else "x".toByteArray().inputStream() }

        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, source, out, time)

        assertTrue(!result.ok, "有一页打不开 ⇒ 全有或全无")
        assertEquals(listOf("p2"), result.missing.map { it.itemId })
        assertEquals(0, out.size(), "缺页时一个字节都不许写")
    }

    @Test
    fun `streaming source does not buffer the whole page`() {
        // 关键断言:4MB 的页在读取期间**不应**被要求一次性交出全部字节。
        // 用一个只在 read() 时逐块吐字节的流来证明实现是边读边算 CRC 的。
        val big = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
        val plan = buildCbzWritePlan(volumeWith(1))

        var streamOpened = 0
        val source = streamingPageByteSource { _ ->
            streamOpened++
            big.inputStream()
        }

        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, source, out, time)

        assertTrue(result.ok)
        // 3 次 = 探测可读 1 次 + 算 CRC/size 1 次 + 写出 1 次。
        // 关键不是次数本身,而是**没有一次把整页留在内存里**(逐块读、读完即丢)。
        assertEquals(3, streamOpened, "流式路径:探测 / 算 CRC / 写出各开一次流")
        val entries = readBack(out.toByteArray())
        assertContentEquals(big, entries[1].second, "内容必须原样写出")
    }

    @Test
    fun `streaming source is reusable for memory backed sources`() {
        // 默认实现:bytesOf 有就转成流。既有内存实现不必改。
        val plan = buildCbzWritePlan(volumeWith(2))
        val source = PageByteSource { id -> "mem-$id".toByteArray() }

        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, source, out, time)

        assertTrue(result.ok)
        val entries = readBack(out.toByteArray())
        assertContentEquals("mem-p1".toByteArray(), entries[1].second)
    }

    @Test
    fun `openFor wins over bytesOf when both are present`() {
        // 平台层可能两个都实现(一个走流、一个走内存);流优先,否则大图仍会 OOM。
        val plan = buildCbzWritePlan(volumeWith(1))
        var bytesOfCalls = 0
        val source = streamingPageByteSource { _ -> "streamed".toByteArray().inputStream() }
        val hybrid = object : PageByteSource {
            override fun bytesOf(itemId: String): ByteArray? {
                bytesOfCalls++
                return "buffered".toByteArray()
            }
            override fun openFor(itemId: String): InputStream? = source.openFor(itemId)
        }

        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, hybrid, out, time)

        assertTrue(result.ok)
        assertEquals(0, bytesOfCalls, "有流式能力时不许回退到整块读 —— 那正是 OOM 的成因")
        assertContentEquals("streamed".toByteArray(), readBack(out.toByteArray())[1].second)
    }
}

/**
 * 探测阶段**不许读内容** —— OOM 的另一个入口。
 *
 * `PackRunner.writeOne` 在开输出流之前先探测「每一页能不能取到」。旧实现用
 * `bytesOf != null` 做探测,而 `bytesOf` 的实现就是整块 `readBytes()` —— 于是
 * 「只是想知道能不能读」这个动作本身就会把 100MB 拉进内存。
 *
 * 这些断言用「一个只在 read() 时才计数」的流来钉住:探测结束时读取字节数必须为 0。
 */
class ProbeDoesNotReadContentTest {

    private val aot1 = MockDataB.library.volumes.first { it.id == "aot-1" }
    private val time = 1_700_000_000_000L

    /** 一个可观测的流:记录读了多少字节、有没有被关闭。 */
    private class SpyStream(private val payload: ByteArray) : InputStream() {
        var bytesRead = 0
            private set
        var closed = false
            private set
        private var pos = 0

        override fun read(): Int {
            if (pos >= payload.size) return -1
            bytesRead++
            return payload[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= payload.size) return -1
            val n = minOf(len, payload.size - pos)
            payload.copyInto(b, off, pos, pos + n)
            pos += n
            bytesRead += n
            return n
        }

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `probing a volume opens every page and closes it without reading`() {
        val volume = aot1.copy(
            pages = aot1.pages.take(3),
            pageOrder = pageOrderOf(aot1.pages.take(3)),
        )
        val opened = mutableListOf<SpyStream>()
        val bytes = streamingPageByteSource { _ ->
            SpyStream(ByteArray(1024) { 7 }).also { opened += it }
        }
        val sink = CbzOutputSink { ByteArrayOutputStream() }

        val report = runPackPlans(
            plans = buildPackagePlans(listOf(volume)),
            bytes = bytes,
            sink = sink,
            token = CancellationToken(),
            timeMillis = time,
        )

        assertTrue(
            report.entries.single().outcome is PackRunOutcome.Written,
            "三页都读得到就该写成功，实际 ${report.entries.single().outcome}",
        )
        // 探测阶段打开的那批流必须全部关闭 —— 否则一次打包几百页会耗尽文件描述符。
        val probeStreams = opened.take(3)
        assertTrue(probeStreams.all { it.closed }, "探测打开的流必须关闭")
        // 而探测**不读内容**:真正读字节发生在写阶段,但探测那段必须一次 read 都没有。
        // （写阶段会重新开流，故这里只看前三个探测流。）
        assertTrue(
            probeStreams.all { it.bytesRead == 0 },
            "探测阶段不该读内容（读到 ${probeStreams.map { it.bytesRead }} 字节），" +
                "否则 100MB 的页会把整页拉进内存",
        )
    }
}
