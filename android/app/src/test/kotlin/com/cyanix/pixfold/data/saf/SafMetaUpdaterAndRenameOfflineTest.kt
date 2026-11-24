package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.comic.comicVolumeOf
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.pack.CbzMetaUpdateOutcome
import com.cyanix.pixfold.domain.pack.CbzMetaUpdatePlan
import com.cyanix.pixfold.domain.pack.CbzWriter
import com.cyanix.pixfold.domain.pack.UpdateFailure
import com.cyanix.pixfold.domain.pack.UpdatePreflight
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
import kotlin.test.assertTrue

/**
 * [SafMetaUpdater]、[SafRenameActions] 与 [SafUndoActions] 基于 [FakeDocumentsProvider]
 * 的端到端离线集成测试（M8 Phase 3）。
 *
 * 全程不注入 mock/fake lambda，直接通过真实 `DocumentsContract.createDocument` /
 * `renameDocument` / `deleteDocument` 驱动磁盘上的真实 ZIP 与图片文件，并用 [ZipFile] 验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    shadows = [FakeDocumentsProvider.ShadowDocumentsProvider::class],
)
class SafMetaUpdaterAndRenameOfflineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var provider: FakeDocumentsProvider

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val rootDir = tempFolder.newFolder("workflow_c_root")
        provider = FakeDocumentsProvider.setup(
            rootDir = rootDir,
            rootDocId = "primary:workflow_c_root",
            rootDisplayName = "workflow_c_root",
        )
    }

    private fun volume(
        seriesDir: String = "SeriesA",
        cbzFileName: String = "Vol01.cbz",
    ): ComicVolume = comicVolumeOf(
        id = "$seriesDir/$cbzFileName",
        dirPath = seriesDir,
        pages = emptyList(),
        titleSug = Suggestion("旧标题", "目录"),
        seriesSug = Suggestion("旧系列", "目录"),
        writerSug = Suggestion("旧作者", "目录"),
        volumeSug = Suggestion(1, "目录"),
        langSug = Suggestion<String>(null, ""),
        outputDirSug = seriesDir,
        outputExists = true,
        cbzBaseSug = Suggestion(cbzFileName.removeSuffix(".cbz"), "目录"),
        seriesDir = seriesDir,
    ).copy(
        cbzFileName = cbzFileName,
        outputDir = seriesDir,
        outputExists = true,
        language = LangChoice.Zh,
    )

    private fun writeStoredCbz(
        relPath: String,
        comicInfoXml: String?,
        pages: List<Pair<String, ByteArray>>,
    ): File {
        val file = provider.fileAt(relPath)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            ZipOutputStream(fos).use { zos ->
                if (comicInfoXml != null) {
                    val xmlBytes = comicInfoXml.toByteArray(Charsets.UTF_8)
                    val entry = ZipEntry(CbzWriter.COMIC_INFO_ENTRY).apply {
                        method = ZipEntry.STORED
                        size = xmlBytes.size.toLong()
                        compressedSize = xmlBytes.size.toLong()
                        crc = CRC32().apply { update(xmlBytes) }.value
                    }
                    zos.putNextEntry(entry)
                    zos.write(xmlBytes)
                    zos.closeEntry()
                }
                for ((name, bytes) in pages) {
                    val entry = ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }
            }
        }
        return file
    }

    @Test
    fun `SafMetaUpdater preflight and update rewrite real CBZ via three-step rename with zero leftovers`() {
        val page1 = "page-1-jpeg-bytes".toByteArray(Charsets.UTF_8)
        val page2 = "page-2-jpeg-bytes-extra".toByteArray(Charsets.UTF_8)
        val cbzFile = writeStoredCbz(
            relPath = "SeriesA/Vol01.cbz",
            comicInfoXml = "<ComicInfo><Title>旧标题</Title></ComicInfo>",
            pages = listOf("001.jpg" to page1, "002.jpg" to page2),
        )
        val originalSize = cbzFile.length()

        val updater = SafMetaUpdater(
            context = context,
            treeUri = provider.treeUri,
            spaceBytesOf = { 500L * 1024 * 1024 },
            dirOfVolume = { it.outputDir },
            now = { FIXED_TIME },
        )
        val vol = volume(seriesDir = "SeriesA", cbzFileName = "Vol01.cbz")
        val newXml = "<ComicInfo><Title>新标题</Title><Series>新系列</Series><Number>1</Number></ComicInfo>"
        val plan = CbzMetaUpdatePlan(
            volumeId = vol.id,
            comicInfoXml = newXml,
            targetDisplayName = vol.cbzFileName,
            entryCount = 3,
        )

        val preflight = assertIs<UpdatePreflight.Ready>(updater.preflight(plan, vol))
        assertEquals(originalSize, preflight.estimatedReadBytes)

        val progressSteps = mutableListOf<Pair<Int, Int>>()
        val outcome = updater.update(
            plan = plan,
            volume = vol,
            shouldStop = { false },
            onProgress = { done, total -> progressSteps += done to total },
        )

        val updated = assertIs<CbzMetaUpdateOutcome.Updated>(outcome)
        assertEquals(3, updated.entryCount)
        assertTrue(progressSteps.isNotEmpty())

        assertFalse(provider.fileAt("SeriesA/Vol01.cbz.updating").exists(), "临时包 .updating 必须已改名并不再残留")
        assertFalse(provider.fileAt("SeriesA/Vol01.cbz.updating.bak").exists(), "备份包 .updating.bak 必须已清理")

        ZipFile(cbzFile).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf(CbzWriter.COMIC_INFO_ENTRY, "001.jpg", "002.jpg"), entries.map { it.name })
            entries.forEach { assertEquals(ZipEntry.STORED, it.method) }
            assertEquals(
                newXml,
                zip.getInputStream(entries[0]).use { it.readBytes().toString(Charsets.UTF_8) },
            )
            assertContentEquals(page1, zip.getInputStream(entries[1]).use { it.readBytes() })
            assertContentEquals(page2, zip.getInputStream(entries[2]).use { it.readBytes() })
        }
    }

    @Test
    fun `SafMetaUpdater falls back to delete plus rename when read-only target rejects renameDocument`() {
        val page1 = "readonly-page-1".toByteArray(Charsets.UTF_8)
        val cbzFile = writeStoredCbz(
            relPath = "SeriesA/ReadOnly.cbz",
            comicInfoXml = "<ComicInfo><Title>只读旧包</Title></ComicInfo>",
            pages = listOf("001.jpg" to page1),
        )
        // 模拟 Kuro Reader 等生成的只读包：provider 拒绝 renameDocument，但允许删除后把新建临时包改名为原名
        provider.markReadOnly("SeriesA/ReadOnly.cbz", readOnly = true)

        val updater = SafMetaUpdater(
            context = context,
            treeUri = provider.treeUri,
            spaceBytesOf = { 500L * 1024 * 1024 },
            dirOfVolume = { it.outputDir },
            now = { FIXED_TIME },
        )
        val vol = volume(seriesDir = "SeriesA", cbzFileName = "ReadOnly.cbz")
        val newXml = "<ComicInfo><Title>突破只读更新成功</Title></ComicInfo>"
        val plan = CbzMetaUpdatePlan(
            volumeId = vol.id,
            comicInfoXml = newXml,
            targetDisplayName = vol.cbzFileName,
            entryCount = 2,
        )

        val outcome = updater.update(
            plan = plan,
            volume = vol,
            shouldStop = { false },
            onProgress = { _, _ -> },
        )
        assertIs<CbzMetaUpdateOutcome.Updated>(outcome)
        assertFalse(provider.fileAt("SeriesA/ReadOnly.cbz.updating").exists())
        assertFalse(provider.fileAt("SeriesA/ReadOnly.cbz.updating.bak").exists())

        ZipFile(cbzFile).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf(CbzWriter.COMIC_INFO_ENTRY, "001.jpg"), entries.map { it.name })
            assertEquals(
                newXml,
                zip.getInputStream(entries[0]).use { it.readBytes().toString(Charsets.UTF_8) },
            )
            assertContentEquals(page1, zip.getInputStream(entries[1]).use { it.readBytes() })
        }
    }

    @Test
    fun `SafMetaUpdater cleans up temp file and leaves original byte-for-byte intact on NotAZip and cancellation`() {
        // 1. NotAZip 场景
        val rawNotZip = "THIS_IS_PLAIN_TEXT_NOT_A_ZIP_ARCHIVE".toByteArray(Charsets.UTF_8)
        val badFile = provider.writeFile("SeriesA/NotZip.cbz", rawNotZip)

        val updater = SafMetaUpdater(
            context = context,
            treeUri = provider.treeUri,
            spaceBytesOf = { 500L * 1024 * 1024 },
            dirOfVolume = { it.outputDir },
            now = { FIXED_TIME },
        )
        val badVol = volume(seriesDir = "SeriesA", cbzFileName = "NotZip.cbz")
        val badPlan = CbzMetaUpdatePlan(
            volumeId = badVol.id,
            comicInfoXml = "<ComicInfo><Title>不应写入</Title></ComicInfo>",
            targetDisplayName = badVol.cbzFileName,
            entryCount = 1,
        )

        val notZipOutcome = assertIs<CbzMetaUpdateOutcome.Failed>(
            updater.update(badPlan, badVol, shouldStop = { false }, onProgress = { _, _ -> }),
        )
        assertEquals(UpdateFailure.NotAZip, notZipOutcome.reason)
        assertContentEquals(rawNotZip, badFile.readBytes(), "NotAZip 失败时原文件必须逐字节未动")
        assertFalse(provider.fileAt("SeriesA/NotZip.cbz.updating").exists(), "NotAZip 必须清理临时包 .updating")

        // 2. 中途取消场景
        val p1 = "cancel-page-1".toByteArray(Charsets.UTF_8)
        val p2 = "cancel-page-2".toByteArray(Charsets.UTF_8)
        val cancelFile = writeStoredCbz(
            relPath = "SeriesA/Cancel.cbz",
            comicInfoXml = "<ComicInfo><Title>取消前原包</Title></ComicInfo>",
            pages = listOf("001.jpg" to p1, "002.jpg" to p2),
        )
        val originalBytes = cancelFile.readBytes()
        val cancelVol = volume(seriesDir = "SeriesA", cbzFileName = "Cancel.cbz")
        val cancelPlan = CbzMetaUpdatePlan(
            volumeId = cancelVol.id,
            comicInfoXml = "<ComicInfo><Title>取消后新标题</Title></ComicInfo>",
            targetDisplayName = cancelVol.cbzFileName,
            entryCount = 3,
        )

        val cancelOutcome = assertIs<CbzMetaUpdateOutcome.Failed>(
            updater.update(cancelPlan, cancelVol, shouldStop = { true }, onProgress = { _, _ -> }),
        )
        assertEquals(UpdateFailure.Cancelled, cancelOutcome.reason)
        assertContentEquals(originalBytes, cancelFile.readBytes(), "取消重写时原 CBZ 必须逐字节完好无损")
        assertFalse(provider.fileAt("SeriesA/Cancel.cbz.updating").exists(), "取消重写必须删掉半成品 .updating")
        assertFalse(provider.fileAt("SeriesA/Cancel.cbz.updating.bak").exists())
    }

    @Test
    fun `SafRenameActions and SafUndoActions execute real batch rename undo and delete on FakeDocumentsProvider`() {
        val bytes1 = "img-1-payload".toByteArray(Charsets.UTF_8)
        val bytes2 = "img-2-payload".toByteArray(Charsets.UTF_8)
        provider.writeFile("Organize/raw_a.jpg", bytes1)
        provider.writeFile("Organize/raw_b.jpg", bytes2)
        provider.writeFile("Organize/temp_pack.cbz", "pack".toByteArray(Charsets.UTF_8))

        val uriA = provider.documentUriFor("Organize/raw_a.jpg").toString()
        val uriB = provider.documentUriFor("Organize/raw_b.jpg").toString()
        val uriPack = provider.documentUriFor("Organize/temp_pack.cbz").toString()

        val renameActions = SafRenameActions(context)
        val undoActions = SafUndoActions(context)

        val renamedUriA = assertNotNull(renameActions.rename(uriA, "001.jpg"))
        val renamedUriB = assertNotNull(renameActions.rename(uriB, "002.jpg"))

        assertFalse(provider.fileAt("Organize/raw_a.jpg").exists())
        assertFalse(provider.fileAt("Organize/raw_b.jpg").exists())
        assertContentEquals(bytes1, provider.fileAt("Organize/001.jpg").readBytes())
        assertContentEquals(bytes2, provider.fileAt("Organize/002.jpg").readBytes())

        // 撤销改名：必须拿着改名后的新 URI 改回原名
        assertTrue(undoActions.rename(renamedUriA, "raw_a.jpg"))
        assertTrue(undoActions.rename(renamedUriB, "raw_b.jpg"))
        assertContentEquals(bytes1, provider.fileAt("Organize/raw_a.jpg").readBytes())
        assertContentEquals(bytes2, provider.fileAt("Organize/raw_b.jpg").readBytes())

        // 撤销新建产物：删除文件
        assertTrue(undoActions.delete(uriPack))
        assertFalse(provider.fileAt("Organize/temp_pack.cbz").exists())
        assertFalse(undoActions.delete(uriPack), "重复删除已不存在的文档应返回 false 而非崩溃")
    }

    private companion object {
        const val FIXED_TIME = 1_700_000_000_000L
    }
}
