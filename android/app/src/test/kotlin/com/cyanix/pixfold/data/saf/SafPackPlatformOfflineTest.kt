package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.comic.ComicInfo
import com.cyanix.pixfold.domain.comic.ComicInfoData
import com.cyanix.pixfold.domain.comic.comicVolumeOf
import com.cyanix.pixfold.domain.comic.pageByteSourceOf
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.pack.CbzWriter
import com.cyanix.pixfold.domain.pack.PackRunOutcome
import com.cyanix.pixfold.domain.pack.runPackPlans
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.task.CancellationToken
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SafCbzOutputSink]、[SafOutputExistsProbe]、[SafPageBytes]、[SafExistingComicInfoProbe]
 * 与 [SafVolumeProbeSession] 基于 [FakeDocumentsProvider] 的端到端离线集成测试（M8 Phase 3）。
 *
 * 所有产出归档均使用真实 [java.util.zip.ZipFile]（走中央目录 EOCD）校验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    shadows = [FakeDocumentsProvider.ShadowDocumentsProvider::class],
)
class SafPackPlatformOfflineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var provider: FakeDocumentsProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val rootDir = tempFolder.newFolder("library_root")
        provider = FakeDocumentsProvider.setup(
            rootDir = rootDir,
            rootDocId = "primary:library_root",
            rootDisplayName = "library_root",
        )
    }

    private fun jpegBytes(label: String): ByteArray {
        val payload = label.toByteArray(Charsets.UTF_8)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            payload +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())
    }

    private fun buildVolume(
        id: String,
        seriesDir: String,
        cbzFileName: String,
        pages: List<SourceItem>,
        title: String = "第一卷",
        series: String = "测试系列",
        writer: String = "测试作者",
        volumeNumber: Int? = 1,
        language: LangChoice = LangChoice.Zh,
        outputExists: Boolean = false,
    ): ComicVolume = comicVolumeOf(
        id = id,
        dirPath = "$seriesDir/$id",
        pages = pages,
        titleSug = Suggestion(title, "目录"),
        seriesSug = Suggestion(series, "目录"),
        writerSug = Suggestion(writer, "目录"),
        volumeSug = Suggestion(volumeNumber, "目录"),
        langSug = Suggestion<String>(null, ""),
        outputDirSug = seriesDir,
        outputExists = outputExists,
        cbzBaseSug = Suggestion(cbzFileName.removeSuffix(".cbz"), "目录"),
        seriesDir = seriesDir,
    ).copy(
        title = title,
        series = series,
        writer = writer,
        volume = volumeNumber,
        language = language,
        cbzFileName = cbzFileName,
        outputDir = seriesDir,
        outputExists = outputExists,
    )

    private fun sourceItemOf(
        uri: String,
        baseName: String,
        ext: String = "jpg",
        sizeBytes: Long,
    ): SourceItem = SourceItem(
        id = uri,
        collectionId = "SeriesA/Vol01",
        dir = "",
        baseName = baseName,
        ext = ext,
        sizeBytes = sizeBytes,
        modifiedEpochMillis = FIXED_TIME,
        createdEpochMillis = null,
        seed = uri.hashCode(),
        uri = uri,
    )

    @Test
    fun `end to end pack creates new CBZ with mode w and valid ZipFile central directory`() {
        val page1Bytes = jpegBytes("page-1-body-content")
        val page2Bytes = jpegBytes("page-2-body-content-longer")
        provider.writeFile("SeriesA/Vol01/001.jpg", page1Bytes)
        provider.writeFile("SeriesA/Vol01/002.jpg", page2Bytes)

        val p1Uri = provider.documentUriFor("SeriesA/Vol01/001.jpg").toString()
        val p2Uri = provider.documentUriFor("SeriesA/Vol01/002.jpg").toString()
        val pages = listOf(
            sourceItemOf(uri = p1Uri, baseName = "001", sizeBytes = page1Bytes.size.toLong()),
            sourceItemOf(uri = p2Uri, baseName = "002", sizeBytes = page2Bytes.size.toLong()),
        )
        val volume = buildVolume(
            id = "Vol01",
            seriesDir = "SeriesA",
            cbzFileName = "Vol01.cbz",
            pages = pages,
        )

        val existsProbe = SafOutputExistsProbe(context, provider.treeUri) { it.outputDir }
        assertFalse(existsProbe.exists(volume), "打包前目标 CBZ 不应存在")

        val safPageBytes = SafPageBytes(context)
        assertContentEquals(page1Bytes, safPageBytes.bytesOf(p1Uri))

        val sink = SafCbzOutputSink(context, provider.treeUri) { plan -> plan.volume.outputDir }
        val plans = buildPackagePlans(listOf(volume), OutputConflictPolicy.Overwrite)
        val byteSource = pageByteSourceOf(mapOf(p1Uri to p1Uri, p2Uri to p2Uri), safPageBytes)

        val report = runPackPlans(
            plans = plans,
            bytes = byteSource,
            sink = sink,
            token = CancellationToken(),
            timeMillis = FIXED_TIME,
        )

        assertEquals(1, report.writtenCount)
        assertTrue(existsProbe.exists(volume), "打包后 SafOutputExistsProbe 应探测到新生成的 CBZ")
        assertEquals("w", provider.lastOpenModeForRelPath("SeriesA/Vol01.cbz"))

        val cbzFile = provider.fileAt("SeriesA/Vol01.cbz")
        assertTrue(cbzFile.exists() && cbzFile.length() > 0L)

        ZipFile(cbzFile).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf(CbzWriter.COMIC_INFO_ENTRY, "001.jpg", "002.jpg"), entries.map { it.name })
            for (entry in entries) {
                assertEquals(ZipEntry.STORED, entry.method, "${entry.name} 必须以 STORED 方式存储")
                val storedBytes = zip.getInputStream(entry).use { it.readBytes() }
                val crc = CRC32().apply { update(storedBytes) }.value
                assertEquals(crc, entry.crc)
                assertEquals(storedBytes.size.toLong(), entry.size)
            }
            val xml = zip.getInputStream(entries[0]).use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals(ComicInfo.build(volume), xml)
            assertContentEquals(page1Bytes, zip.getInputStream(entries[1]).use { it.readBytes() })
            assertContentEquals(page2Bytes, zip.getInputStream(entries[2]).use { it.readBytes() })
        }
    }

    @Test
    fun `overwrite mode uses wt truncation in place without creating duplicate (1) cbz or leaving trailing garbage`() {
        // 先写入一个体积较大（含 3 页大负载 + 旧 EOCD）的既有 Vol01.cbz
        val oldLargePayload = ByteArray(4096) { (it % 251).toByte() }
        val existingCbz = provider.fileAt("SeriesA/Vol01.cbz")
        existingCbz.parentFile?.mkdirs()
        writeRawStoredZip(
            target = existingCbz,
            entries = listOf(
                "ComicInfo.xml" to "<ComicInfo><Title>旧版大包</Title></ComicInfo>".toByteArray(Charsets.UTF_8),
                "001.jpg" to oldLargePayload,
                "002.jpg" to oldLargePayload,
                "003.jpg" to oldLargePayload,
            ),
        )
        val oldLength = existingCbz.length()
        assertTrue(oldLength > 12_000L, "既有旧包应显著大于即将覆盖写入的新包")

        // 用只有 1 张小图的卷原地覆盖 Vol01.cbz
        val newSmallPage = jpegBytes("tiny-new-page")
        provider.writeFile("SeriesA/Vol01/001.jpg", newSmallPage)
        val p1Uri = provider.documentUriFor("SeriesA/Vol01/001.jpg").toString()
        val volume = buildVolume(
            id = "Vol01",
            seriesDir = "SeriesA",
            cbzFileName = "Vol01.cbz",
            pages = listOf(
                sourceItemOf(uri = p1Uri, baseName = "001", sizeBytes = newSmallPage.size.toLong()),
            ),
            title = "新版小包",
            outputExists = true,
        )

        val sink = SafCbzOutputSink(context, provider.treeUri) { plan -> plan.volume.outputDir }
        val safPageBytes = SafPageBytes(context)
        val plans = buildPackagePlans(listOf(volume), OutputConflictPolicy.Overwrite)
        val report = runPackPlans(
            plans = plans,
            bytes = pageByteSourceOf(mapOf(p1Uri to p1Uri), safPageBytes),
            sink = sink,
            token = CancellationToken(),
            timeMillis = FIXED_TIME,
        )

        assertEquals(1, report.writtenCount)
        assertEquals("wt", provider.lastOpenModeForRelPath("SeriesA/Vol01.cbz"))
        assertFalse(
            provider.fileAt("SeriesA/Vol01 (1).cbz").exists(),
            "覆盖模式必须复用既有文档 URI，绝不能走 createDocument 产生 Vol01 (1).cbz",
        )

        val newLength = existingCbz.length()
        assertTrue(
            newLength < oldLength / 2,
            "wt 覆盖必须截断旧文件尾部字节（旧长=$oldLength，新长=$newLength）",
        )

        ZipFile(existingCbz).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(
                listOf(CbzWriter.COMIC_INFO_ENTRY, "001.jpg"),
                names,
                "若未截断（如退化为 w），ZipFile 从文件末尾读取旧 EOCD 会看到旧的 3 页中央目录或损坏偏移",
            )
            assertContentEquals(
                newSmallPage,
                zip.getInputStream(assertNotNull(zip.getEntry("001.jpg"))).use { it.readBytes() },
            )
        }
    }

    @Test
    fun `discardFor removes partial CBZ on mid-pack failure and is idempotent`() {
        val page1Bytes = jpegBytes("page-1-ok")
        val page2Bytes = jpegBytes("page-2-fails-on-second-open")
        provider.writeFile("SeriesA/Vol01/001.jpg", page1Bytes)
        provider.writeFile("SeriesA/Vol01/002.jpg", page2Bytes)

        // 第 1 次 open（writeOne 预探测阶段）放行，第 2 次 open（已创建输出流、正在写第 2 页）抛异常
        provider.failOpenAfter("SeriesA/Vol01/002.jpg", allowCount = 1)

        val p1Uri = provider.documentUriFor("SeriesA/Vol01/001.jpg").toString()
        val p2Uri = provider.documentUriFor("SeriesA/Vol01/002.jpg").toString()
        val volume = buildVolume(
            id = "Vol01",
            seriesDir = "SeriesA",
            cbzFileName = "Partial.cbz",
            pages = listOf(
                sourceItemOf(uri = p1Uri, baseName = "001", sizeBytes = page1Bytes.size.toLong()),
                sourceItemOf(uri = p2Uri, baseName = "002", sizeBytes = page2Bytes.size.toLong()),
            ),
        )

        val sink = SafCbzOutputSink(context, provider.treeUri) { plan -> plan.volume.outputDir }
        val safPageBytes = SafPageBytes(context)
        val plans = buildPackagePlans(listOf(volume), OutputConflictPolicy.Overwrite)
        val report = runPackPlans(
            plans = plans,
            bytes = pageByteSourceOf(mapOf(p1Uri to p1Uri, p2Uri to p2Uri), safPageBytes),
            sink = sink,
            token = CancellationToken(),
            timeMillis = FIXED_TIME,
        )

        assertEquals(0, report.writtenCount)
        assertIs<PackRunOutcome.MissingPages>(report.entries.single().outcome)
        assertFalse(
            provider.fileAt("SeriesA/Partial.cbz").exists(),
            "写入中途失败必须由 discardFor 删除半成品 CBZ，不得在用户目录留下残缺文件",
        )
        // 再次调用 discardFor 验证幂等且不抛
        sink.discardFor(plans.single())
    }

    @Test
    fun `SafExistingComicInfoProbe and SafVolumeProbeSession handle 4 states and query directory once for N volumes`() {
        provider.mkdirs("SeriesA")

        // 1. Vol01.cbz：合法 CBZ 含 ComicInfo.xml
        val vol01Xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <ComicInfo>
              <Title>来自归档的卷一</Title>
              <Series>归档系列名</Series>
              <Number>1</Number>
              <Writer>归档作者</Writer>
              <LanguageISO>ja</LanguageISO>
            </ComicInfo>
        """.trimIndent()
        writeRawStoredZip(
            target = provider.fileAt("SeriesA/Vol01.cbz"),
            entries = listOf(
                "ComicInfo.xml" to vol01Xml.toByteArray(Charsets.UTF_8),
                "001.jpg" to jpegBytes("v1p1"),
            ),
        )

        // 2. Vol02.cbz：合法 ZIP 但不含 ComicInfo.xml ⇒ 应返回空 ComicInfoData()
        writeRawStoredZip(
            target = provider.fileAt("SeriesA/Vol02.cbz"),
            entries = listOf("001.jpg" to jpegBytes("v2p1")),
        )

        // 3. Vol03.cbz：损坏/非 ZIP 字节 ⇒ 应返回 null（或存在但不可读，保持建议值）
        provider.writeText("SeriesA/Vol03.cbz", "NOT_A_VALID_ZIP_HEADER_GARBAGE")

        // 4. Vol04.cbz：含非法 XML 的 ZIP ⇒ comicInfoOfEntry 返回 null（不清空用户建议值）
        writeRawStoredZip(
            target = provider.fileAt("SeriesA/Vol04.cbz"),
            entries = listOf("ComicInfo.xml" to "<ComicInfo><Broken".toByteArray(Charsets.UTF_8)),
        )

        // 5. Vol05.cbz：不存在 ⇒ 返回 null
        val volumes = (1..5).map { i ->
            buildVolume(
                id = "Vol0$i",
                seriesDir = "SeriesA",
                cbzFileName = "Vol0$i.cbz",
                pages = emptyList(),
                title = "建议标题-$i",
                series = "建议系列",
                writer = "建议作者",
                volumeNumber = i,
                language = LangChoice.Zh,
            )
        }

        val standaloneProbe = SafExistingComicInfoProbe(context, provider.treeUri) { it.outputDir }
        assertEquals(
            ComicInfoData(
                title = "来自归档的卷一",
                series = "归档系列名",
                number = 1,
                writer = "归档作者",
                language = LangChoice.Ja,
            ),
            standaloneProbe.read(volumes[0]),
        )
        assertEquals(ComicInfoData(), standaloneProbe.read(volumes[1]), "无 ComicInfo.xml 的合法包应返回空对象")
        assertNull(standaloneProbe.read(volumes[3]), "XML 损坏时必须返回 null 以保护建议值不被清空")
        assertNull(standaloneProbe.read(volumes[4]), "不存在的 CBZ 必须返回 null")

        // 重置计数器，检验真实 SafVolumeProbeSession(context, treeUri) 对同目录 5 卷只做 1 次 children 查询
        provider.resetCounters()
        val session = SafVolumeProbeSession(
            context = context,
            treeUri = provider.treeUri,
            dirOfVolume = { it.outputDir },
        )
        val probed = session.probe(volumes)

        assertEquals(
            1,
            provider.queryChildCountForRelPath(""),
            "同系列 5 卷批量探测时，工作区根目录只应 queryChildDocuments 1 次",
        )
        assertEquals(
            1,
            provider.queryChildCountForRelPath("SeriesA"),
            "同系列 5 卷批量探测时，SeriesA 目录只应 queryChildDocuments 1 次",
        )
        assertEquals(listOf(true, true, true, true, false), probed.map { it.outputExists })
        assertEquals("来自归档的卷一", probed[0].title)
        assertEquals(LangChoice.Ja, probed[0].language)
        assertEquals("", probed[1].title, "无 ComicInfo.xml 的包经 applyExistingComicInfo 应清空当前值")
        assertEquals("建议标题-4", probed[3].title, "XML 损坏的包应保留原建议值")
        assertEquals("建议标题-5", probed[4].title, "不存在的包应保留原建议值")
    }

    private fun writeRawStoredZip(target: File, entries: List<Pair<String, ByteArray>>) {
        FileOutputStream(target).use { fos ->
            ZipOutputStream(fos).use { zos ->
                for ((name, bytes) in entries) {
                    val crc = CRC32().apply { update(bytes) }.value
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        this.crc = crc
                    }
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
    }

    private companion object {
        const val FIXED_TIME = 1_700_000_000_000L
    }
}
