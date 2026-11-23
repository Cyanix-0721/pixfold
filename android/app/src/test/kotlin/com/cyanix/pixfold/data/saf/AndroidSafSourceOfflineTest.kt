package com.cyanix.pixfold.data.saf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [AndroidSafSource] 基于 [FakeDocumentsProvider] 的端到端离线 SAF 集成测试（M8 Phase 3）。
 *
 * 覆盖：
 * 1. [AndroidSafSource.loadCollections] 递归目录扫描、相对路径与合集分组、非图片过滤；
 * 2. `MAX_DEPTH = 4` 深度上限护栏（深度 <= 4 的图片正常收录，深度 5 的子目录被截断）；
 * 3. `SecurityException` → [LoadResult.Denied] 容错降级；
 * 4. [AndroidSafSource.readBytes] 与 [AndroidSafSource.displayNameOf] 真实 SAF 读取；
 * 5. [AndroidSafSource.watchChanges] 400ms 尾沿防抖合并与 `close()` 停止通知。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    shadows = [FakeDocumentsProvider.ShadowDocumentsProvider::class],
)
class AndroidSafSourceOfflineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var provider: FakeDocumentsProvider
    private lateinit var source: AndroidSafSource

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val workspaceDir = tempFolder.newFolder("漫画工作区")
        provider = FakeDocumentsProvider.setup(
            rootDir = workspaceDir,
            rootDocId = "primary:manga_root",
            rootDisplayName = "漫画工作区",
        )
        source = AndroidSafSource(context)
    }

    @Test
    fun `loadCollections recursively scans tree groups collections filters non-images and reads bytes`() {
        val imgA1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
        val imgA2 = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val imgB1 = "webp-payload".toByteArray(Charsets.UTF_8)

        provider.writeFile("SeriesA/Vol01/002.png", imgA2, lastModifiedMillis = 1_700_000_002_000L)
        provider.writeFile("SeriesA/Vol01/001.jpg", imgA1, lastModifiedMillis = 1_700_000_001_000L)
        provider.writeText("SeriesA/Vol01/readme.txt", "not an image")
        provider.writeText("SeriesA/Vol01/ComicInfo.xml", "<ComicInfo/>")
        provider.writeFile("SeriesA/Vol01.cbz", byteArrayOf(0x50, 0x4B, 0x03, 0x04))
        provider.writeFile("SeriesB/01.webp", imgB1, lastModifiedMillis = 1_700_000_003_000L)

        // displayName 传空串，迫使 loadCollections 走 queryDisplayName 从 FakeDocumentsProvider 读根名
        val handle = WorkspaceHandle(treeUri = provider.treeUri.toString(), displayName = "")
        val result = source.loadCollections(handle)
        val success = assertIs<LoadResult.Success>(result)

        assertEquals("漫画工作区", source.displayNameOf(provider.treeUri.toString()))
        val byId = success.collections.associateBy { it.id }
        assertEquals(setOf("SeriesA/Vol01", "SeriesB"), byId.keys)

        val vol01 = assertNotNull(byId["SeriesA/Vol01"])
        assertEquals("Vol01", vol01.name)
        assertEquals("SeriesA", vol01.seriesKey)
        assertEquals(listOf("001.jpg", "002.png"), vol01.images.map { it.fileName })
        assertEquals(imgA1.size.toLong(), vol01.images[0].sizeBytes)
        assertEquals(1_700_000_001_000L, vol01.images[0].modifiedEpochMillis)
        assertContentEquals(imgA1, source.readBytes(vol01.images[0]))
        assertContentEquals(imgA2, source.readBytes(vol01.images[1]))

        val seriesB = assertNotNull(byId["SeriesB"])
        assertEquals(listOf("01.webp"), seriesB.images.map { it.fileName })
        assertContentEquals(imgB1, source.readBytes(seriesB.images[0]))
    }

    @Test
    fun `loadCollections enforces MAX_DEPTH 4 by collecting depth 4 and stopping before depth 5`() {
        provider.writeText("root.jpg", "d0")
        provider.writeText("d1/level1.jpg", "d1")
        provider.writeText("d1/d2/level2.jpg", "d2")
        provider.writeText("d1/d2/d3/level3.jpg", "d3")
        provider.writeText("d1/d2/d3/d4/level4.jpg", "d4")
        provider.writeText("d1/d2/d3/d4/d5/level5_too_deep.jpg", "d5")

        val handle = WorkspaceHandle(treeUri = provider.treeUri.toString(), displayName = "漫画工作区")
        val success = assertIs<LoadResult.Success>(source.loadCollections(handle))

        val allFileNames = success.collections.flatMap { col -> col.images.map { it.fileName } }.toSet()
        assertEquals(
            setOf("root.jpg", "level1.jpg", "level2.jpg", "level3.jpg", "level4.jpg"),
            allFileNames,
            "深度 0..4 的图片必须全部扫出，而深度 5（超出 MAX_DEPTH = 4）必须被截断",
        )
        assertEquals(
            0,
            provider.queryChildCountForRelPath("d1/d2/d3/d4/d5"),
            "超过 MAX_DEPTH 的第 5 层目录根本不应发起 queryChildDocuments",
        )
    }

    @Test
    fun `loadCollections maps SecurityException from provider to LoadResult Denied`() {
        provider.writeText("SeriesA/001.jpg", "img")
        provider.injectFault(FakeDocumentsProvider.Operation.QUERY_CHILDREN) {
            SecurityException("SAF tree permission revoked")
        }

        val handle = WorkspaceHandle(treeUri = provider.treeUri.toString(), displayName = "漫画工作区")
        val result = source.loadCollections(handle)

        assertEquals(LoadResult.Denied, result)
    }

    @Test
    fun `watchChanges coalesces burst notifications with 400ms trailing debounce and stops after close`() {
        val handle = WorkspaceHandle(treeUri = provider.treeUri.toString(), displayName = "漫画工作区")
        val emissions = AtomicInteger(0)
        val watcher = assertNotNull(
            source.watchChanges(handle) { emissions.incrementAndGet() },
            "watchChanges 应成功注册 ContentObserver",
        )

        val childUri = provider.documentUriFor("SeriesA/Vol01/001.jpg")
        val resolver = context.contentResolver

        // 第一波：连续突发通知（t = 0ms, 150ms, 300ms），每次间隔 < 400ms，尾沿计时必须不断重置
        resolver.notifyChange(childUri, null)
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)
        assertEquals(0, emissions.get(), "150ms 时未满 400ms 防抖窗口，不得提前触发")

        resolver.notifyChange(childUri, null)
        ShadowLooper.idleMainLooper(150, TimeUnit.MILLISECONDS)
        assertEquals(0, emissions.get(), "第二次通知重置计时后（累计 300ms、距上次 150ms）仍不得触发")

        resolver.notifyChange(childUri, null)
        ShadowLooper.idleMainLooper(399, TimeUnit.MILLISECONDS)
        assertEquals(0, emissions.get(), "距最后一次通知 399ms 时仍未到达 400ms 尾沿")

        ShadowLooper.idleMainLooper(1, TimeUnit.MILLISECONDS)
        assertEquals(1, emissions.get(), "距最后一次通知满 400ms 时必须合并触发恰好 1 次")

        // 第二波：发出通知后在 200ms 时调用 close()，待触发的防抖任务必须被摘除且后续通知不再触发
        resolver.notifyChange(childUri, null)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        watcher.close()
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        assertEquals(1, emissions.get(), "close() 必须摘除尚未到期的 400ms 防抖回调")

        resolver.notifyChange(childUri, null)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertEquals(1, emissions.get(), "close() 注销 observer 后新通知不得再触发回调")
        assertTrue(emissions.get() == 1)
    }
}
