package com.cyanix.pixfold.data.saf

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.comic.ComicInfoData
import com.cyanix.pixfold.domain.comic.comicVolumeOf
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.Suggestion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SafVolumeProbeSession] 批量目录列举缓存与 [SafExistingComicInfoProbe] 去重行为的单测。
 *
 * 覆盖核心不变量：
 * 1. 同父目录下 N 卷在一次会话里完成 `outputExists` + `existingComicInfo` 探测，
 *    每个唯一目录只执行 **1** 次 SAF 子项列举（而非 2N / 4N 次）。
 * 2. 跨多个系列子目录共享根目录列举缓存；缺失目录同样缓存，不重复下钻。
 * 3. 同名目录不误判为 CBZ 文件；不存在的 CBZ 不触发 ZIP/ComicInfo 读取。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SafVolumeProbeSessionTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val treeUri: Uri = "content://com.android.externalstorage.documents/tree/primary%3Amanga".toUri()

    private fun fakeDocUri(docId: String): Uri =
        "content://com.android.externalstorage.documents/tree/primary%3Amanga/document/$docId".toUri()

    private fun volume(
        id: String,
        outputDir: String,
        cbzName: String,
    ): ComicVolume = comicVolumeOf(
        id = id,
        dirPath = id,
        pages = emptyList(),
        titleSug = Suggestion("目录标题-$id", "目录"),
        seriesSug = Suggestion("目录系列", "目录"),
        writerSug = Suggestion("目录作者", "目录"),
        volumeSug = Suggestion<Int?>(1, "目录"),
        langSug = Suggestion<String>(null, ""),
        outputDirSug = outputDir,
        outputExists = false,
        cbzBaseSug = Suggestion(cbzName.removeSuffix(".cbz"), "目录"),
        seriesDir = outputDir,
    ).copy(cbzFileName = cbzName, outputDir = outputDir)

    @Test
    fun `N volumes in same series folder query root and series directory once each across exists and comicInfo`() {
        val rootId = "primary:manga"
        val seriesAId = "primary:manga/SeriesA"
        val queryCounts = LinkedHashMap<String, Int>()
        val readUris = mutableListOf<Uri>()

        val tree = mapOf(
            rootId to listOf(
                SafChildEntry(seriesAId, "SeriesA", isDirectory = true),
            ),
            seriesAId to listOf(
                SafChildEntry("$seriesAId/v1.cbz", "Vol01.cbz", isDirectory = false),
                SafChildEntry("$seriesAId/v3.cbz", "Vol03.cbz", isDirectory = false),
            ),
        )
        val comicInfoByUri = mapOf(
            fakeDocUri("$seriesAId/v1.cbz") to ComicInfoData(
                title = "来自XML的卷一",
                series = "来自XML的系列",
                number = 1,
                writer = "XML作者",
                language = LangChoice.Zh,
            ),
            fakeDocUri("$seriesAId/v3.cbz") to ComicInfoData(
                title = "来自XML的卷三",
                series = "来自XML的系列",
                number = 3,
                writer = "XML作者",
                language = LangChoice.Ja,
            ),
        )

        val session = SafVolumeProbeSession(
            rootDocId = { rootId },
            listChildren = { parentDocId ->
                queryCounts[parentDocId] = (queryCounts[parentDocId] ?: 0) + 1
                tree[parentDocId]
            },
            buildDocumentUri = ::fakeDocUri,
            readComicInfoFromUri = { uri ->
                readUris += uri
                comicInfoByUri[uri]
            },
            dirOfVolume = { it.outputDir },
        )

        val volumes = (1..5).map { i ->
            volume(id = "SeriesA/Vol0$i", outputDir = "SeriesA", cbzName = "Vol0$i.cbz")
        }

        val probed = session.probe(volumes)

        assertEquals(
            mapOf(rootId to 1, seriesAId to 1),
            queryCounts,
            "同系列 5 卷做完存在性 + ComicInfo 探测，根与系列目录各只应查 1 次 SAF",
        )
        assertEquals(listOf(true, false, true, false, false), probed.map { it.outputExists })
        assertEquals("来自XML的卷一", probed[0].title)
        assertEquals(LangChoice.Zh, probed[0].language)
        assertEquals("目录标题-SeriesA/Vol02", probed[1].title)
        assertEquals("来自XML的卷三", probed[2].title)
        assertEquals(LangChoice.Ja, probed[2].language)
        assertEquals(
            listOf(fakeDocUri("$seriesAId/v1.cbz"), fakeDocUri("$seriesAId/v3.cbz")),
            readUris,
            "只应对目录快照里实际存在的 CBZ 打开流读取 ComicInfo",
        )
    }

    @Test
    fun `N volumes in workspace root execute exactly one SAF children query total`() {
        val rootId = "primary:manga"
        val queryCounts = LinkedHashMap<String, Int>()
        val tree = mapOf(
            rootId to listOf(
                SafChildEntry("$rootId/s2.cbz", "Single2.cbz", isDirectory = false),
            ),
        )
        val session = SafVolumeProbeSession(
            rootDocId = { rootId },
            listChildren = { parentDocId ->
                queryCounts[parentDocId] = (queryCounts[parentDocId] ?: 0) + 1
                tree[parentDocId]
            },
            buildDocumentUri = ::fakeDocUri,
            readComicInfoFromUri = { ComicInfoData(title = "单本XML标题") },
            dirOfVolume = { "" },
        )

        val volumes = (1..4).map { i ->
            volume(id = "Single$i", outputDir = "", cbzName = "Single$i.cbz")
        }

        val probed = session.probe(volumes)

        assertEquals(
            mapOf(rootId to 1),
            queryCounts,
            "工作区根下 4 卷合计只应执行 1 次 SAF children 查询",
        )
        assertEquals(listOf(false, true, false, false), probed.map { it.outputExists })
        assertEquals("单本XML标题", probed[1].title)
    }

    @Test
    fun `multiple series folders share cached root listing and query each unique series once`() {
        val rootId = "primary:manga"
        val seriesAId = "primary:manga/SeriesA"
        val seriesBId = "primary:manga/SeriesB"
        val queryCounts = LinkedHashMap<String, Int>()
        val tree = mapOf(
            rootId to listOf(
                SafChildEntry(seriesAId, "SeriesA", isDirectory = true),
                SafChildEntry(seriesBId, "SeriesB", isDirectory = true),
            ),
            seriesAId to listOf(SafChildEntry("$seriesAId/a1.cbz", "A1.cbz", isDirectory = false)),
            seriesBId to listOf(SafChildEntry("$seriesBId/b1.cbz", "B1.cbz", isDirectory = false)),
        )
        val session = SafVolumeProbeSession(
            rootDocId = { rootId },
            listChildren = { parentDocId ->
                queryCounts[parentDocId] = (queryCounts[parentDocId] ?: 0) + 1
                tree[parentDocId]
            },
            buildDocumentUri = ::fakeDocUri,
            readComicInfoFromUri = { null },
            dirOfVolume = { it.outputDir },
        )

        val volumes = listOf(
            volume("SeriesA/A1", "SeriesA", "A1.cbz"),
            volume("SeriesA/A2", "SeriesA", "A2.cbz"),
            volume("SeriesB/B1", "SeriesB", "B1.cbz"),
            volume("SeriesB/B2", "SeriesB", "B2.cbz"),
        )

        val probed = session.probe(volumes)

        assertEquals(
            mapOf(rootId to 1, seriesAId to 1, seriesBId to 1),
            queryCounts,
        )
        assertEquals(listOf(true, false, true, false), probed.map { it.outputExists })
    }

    @Test
    fun `directory matching cbz filename is ignored and missing directory is cached`() {
        val rootId = "primary:manga"
        val queryCounts = LinkedHashMap<String, Int>()
        var readCalls = 0
        val tree = mapOf(
            rootId to listOf(
                SafChildEntry("$rootId/fakeDir", "NotAFile.cbz", isDirectory = true),
            ),
        )
        val session = SafVolumeProbeSession(
            rootDocId = { rootId },
            listChildren = { parentDocId ->
                queryCounts[parentDocId] = (queryCounts[parentDocId] ?: 0) + 1
                tree[parentDocId]
            },
            buildDocumentUri = ::fakeDocUri,
            readComicInfoFromUri = {
                readCalls++
                ComicInfoData(title = "不应读到")
            },
            dirOfVolume = { it.outputDir },
        )

        val volumes = listOf(
            volume("NotAFile", "", "NotAFile.cbz"),
            volume("Missing/V1", "Missing", "V1.cbz"),
            volume("Missing/V2", "Missing", "V2.cbz"),
            volume("Missing/V3", "Missing", "V3.cbz"),
        )

        val probed = session.probe(volumes)

        assertFalse(probed[0].outputExists)
        assertEquals(0, readCalls)
        assertEquals(
            mapOf(rootId to 1),
            queryCounts,
            "缺失目录应命中缓存，不得每卷重查根目录",
        )
    }

    @Test
    fun `standalone SafExistingComicInfoProbe safely returns null when no provider exists`() {
        val probe = SafExistingComicInfoProbe(
            context = context,
            treeUri = treeUri,
            dirOfVolume = { it.outputDir },
        )
        val result = probe.read(volume("SeriesA/V1", "SeriesA", "V1.cbz"))
        assertNull(result)
        assertTrue(true)
    }
}
