package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.task.CancellationToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
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
    fun `streaming source uses single pass for pages within limit and verifies via ZipFile`() {
        // ≤ 8 MiB 的页应在单趟有界缓冲内算完 CRC 与长度并写出：
        // writeStreaming 本身只开 1 次流（不再双趟重读），经 writeCbz 合计 2 次（1 次探测 + 1 次写入）。
        val pageA = ByteArray(256 * 1024 + 137) { (it % 251).toByte() }
        val pageB = ByteArray(1024 * 1024 + 19) { ((it * 7) % 251).toByte() }
        val payloads = mapOf("p1" to pageA, "p2" to pageB)
        val plan = buildCbzWritePlan(volumeWith(2))

        val openedByItem = mutableMapOf<String, Int>()
        val source = streamingPageByteSource { id ->
            openedByItem[id] = (openedByItem[id] ?: 0) + 1
            payloads.getValue(id).inputStream()
        }

        val out = ByteArrayOutputStream()
        CbzWriter.writeStreaming(plan.comicInfoXml, plan.pages, source, out, time)

        assertEquals(1, openedByItem["p1"], "≤ 8 MiB 的第 1 页应只开 1 次流")
        assertEquals(1, openedByItem["p2"], "≤ 8 MiB 的第 2 页应只开 1 次流")

        val temp = Files.createTempFile("cbz-single-pass-", ".cbz").toFile()
        try {
            temp.writeBytes(out.toByteArray())
            ZipFile(temp).use { zip ->
                val entries = zip.entries().toList()
                assertEquals(listOf("ComicInfo.xml", "001.jpg", "002.jpg"), entries.map { it.name })
                entries.forEach { assertEquals(ZipEntry.STORED, it.method) }
                assertContentEquals(pageA, zip.getInputStream(zip.getEntry("001.jpg")!!).use { it.readBytes() })
                assertContentEquals(pageB, zip.getInputStream(zip.getEntry("002.jpg")!!).use { it.readBytes() })
                assertEquals(CRC32().apply { update(pageA) }.value, zip.getEntry("001.jpg")!!.crc)
                assertEquals(CRC32().apply { update(pageB) }.value, zip.getEntry("002.jpg")!!.crc)
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `streaming source falls back to two passes when page exceeds 8 MiB limit`() {
        // 超过 8 MiB 的大图必须回退两趟流式读写，严守 256 MB 堆 OOM 护栏。
        val largeSize = 8 * 1024 * 1024 + 4096
        val smallPage = ByteArray(128 * 1024 + 7) { ((it * 3) % 251).toByte() }
        val plan = buildCbzWritePlan(volumeWith(2))

        fun patternStream(totalBytes: Int): InputStream = object : InputStream() {
            private var remaining = totalBytes
            private var pos = 0
            override fun read(): Int {
                if (remaining <= 0) return -1
                remaining--
                return (pos++ % 251) and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val n = minOf(len, remaining)
                for (i in 0 until n) {
                    b[off + i] = ((pos + i) % 251).toByte()
                }
                pos += n
                remaining -= n
                return n
            }
        }

        val openedByItem = mutableMapOf<String, Int>()
        val source = streamingPageByteSource { id ->
            openedByItem[id] = (openedByItem[id] ?: 0) + 1
            if (id == "p1") patternStream(largeSize) else smallPage.inputStream()
        }

        val temp = Files.createTempFile("cbz-two-pass-", ".cbz").toFile()
        try {
            temp.outputStream().use { fos ->
                CbzWriter.writeStreaming(plan.comicInfoXml, plan.pages, source, fos, time)
            }
            assertEquals(2, openedByItem["p1"], "> 8 MiB 的页必须回退 2 趟读取")
            assertEquals(1, openedByItem["p2"], "其后的 ≤ 8 MiB 小页仍走单趟读取")

            ZipFile(temp).use { zip ->
                val entries = zip.entries().toList()
                assertEquals(listOf("ComicInfo.xml", "001.jpg", "002.jpg"), entries.map { it.name })
                entries.forEach { assertEquals(ZipEntry.STORED, it.method) }

                val largeEntry = zip.getEntry("001.jpg")!!
                assertEquals(largeSize.toLong(), largeEntry.size)
                val expectedCrc = CRC32()
                val actualCrc = CRC32()
                patternStream(largeSize).use { expected ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = expected.read(buf)
                        if (n < 0) break
                        expectedCrc.update(buf, 0, n)
                    }
                }
                var readTotal = 0L
                zip.getInputStream(largeEntry).use { actual ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = actual.read(buf)
                        if (n < 0) break
                        actualCrc.update(buf, 0, n)
                        readTotal += n
                    }
                }
                assertEquals(largeSize.toLong(), readTotal)
                assertEquals(expectedCrc.value, largeEntry.crc)
                assertEquals(expectedCrc.value, actualCrc.value)

                assertContentEquals(smallPage, zip.getInputStream(zip.getEntry("002.jpg")!!).use { it.readBytes() })
            }
        } finally {
            temp.delete()
        }
    }

    @Test
    fun `streaming source does not buffer the whole page`() {
        // 4MB 页 ≤ 8 MiB 单趟上限：writeCbz 打开 2 次（探测 1 次 + 单趟算 CRC 并写出 1 次）。
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
        assertEquals(2, streamOpened, "≤ 8 MiB 路径：探测 1 次 + 单趟写入 1 次")
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
    /**
     * 页级进度必须**逐页**上报，且分母是这一卷的页数。
     *
     * 界面要画「外层 = 卷进度、内层 = 当前卷的页进度」的双层圆环，内层没有页级
     * 回调就只能按卷跳 —— 一卷 1600 页时内圈会从 0 直接到 100%，用户看到的是
     * 「卡住不动然后突然完成」。
     *
     * 断言三条：①回调次数 == 页数；②`done` 从 1 递增到 total；③`total` 恒等于页数。
     */
    @Test
    fun `page progress is reported once per page`() {
        val volume = volumeWith(4)
        val bytes = streamingPageByteSource { ByteArray(64) { 3 }.inputStream() }
        val sink = CbzOutputSink { ByteArrayOutputStream() }
        val seen = mutableListOf<Pair<Int, Int>>()

        runPackPlans(
            plans = buildPackagePlans(listOf(volume)),
            bytes = bytes,
            sink = sink,
            token = CancellationToken(),
            timeMillis = time,
            onPageWritten = { _, done, total -> seen += done to total },
        )

        assertEquals(listOf(1 to 4, 2 to 4, 3 to 4, 4 to 4), seen, "每写完一页就该报一次，且分母是卷内页数")
    }

    /** 卷索引必须跟着走：多卷时界面要知道「现在是第几卷」。 */
    @Test
    fun `page progress carries the volume index`() {
        val v1 = volumeWith(2)
        val v2 = volumeWith(3).copy(id = "v2")
        val bytes = streamingPageByteSource { ByteArray(64) { 3 }.inputStream() }
        val sink = CbzOutputSink { ByteArrayOutputStream() }
        val volumes = mutableListOf<Int>()

        runPackPlans(
            plans = buildPackagePlans(listOf(v1, v2)),
            bytes = bytes,
            sink = sink,
            token = CancellationToken(),
            timeMillis = time,
            onPageWritten = { volumeIndex, _, _ -> volumes += volumeIndex },
        )

        assertEquals(listOf(0, 0, 1, 1, 1), volumes, "前两页属第 0 卷，后三页属第 1 卷")
    }

    /**
     * 缺页的卷**一页进度都不该报**。
     *
     * 这是「不留 0 字节 CBZ」那条规则的延伸：探测失败时连输出流都没开，
     * 若仍按计划页数报进度，界面会显示「第 5 页写完了」而磁盘上什么都没有。
     */
    @Test
    fun `a volume with missing pages reports no page progress`() {
        val volume = volumeWith(3)
        val bytes = streamingPageByteSource { id -> if (id == "p2") null else ByteArray(16).inputStream() }
        val sink = CbzOutputSink { ByteArrayOutputStream() }
        val seen = mutableListOf<Int>()

        runPackPlans(
            plans = buildPackagePlans(listOf(volume)),
            bytes = bytes,
            sink = sink,
            token = CancellationToken(),
            timeMillis = time,
            onPageWritten = { _, done, _ -> seen += done },
        )

        assertTrue(seen.isEmpty(), "缺页卷不该报页进度，实际报了 $seen")
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
