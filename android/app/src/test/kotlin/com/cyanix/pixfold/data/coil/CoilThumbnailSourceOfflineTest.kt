package com.cyanix.pixfold.data.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.createPixFoldImageLoader
import com.cyanix.pixfold.data.saf.FakeDocumentsProvider
import com.cyanix.pixfold.domain.thumb.thumbBudgetFor
import com.cyanix.pixfold.ui.workspace.CachingThumbnailSource
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CoilThumbnailSource] + [ImageLoaderCoilImageFetcher] + [CachingThumbnailSource]
 * 基于 [FakeDocumentsProvider] 与 `@GraphicsMode(NATIVE)` 的端到端离线媒体解码测试（M8 Phase 3）。
 *
 * 覆盖：
 * 1. 从真实 `content://com.android.externalstorage.documents/tree/.../document/...` URI
 *    通过生产 [createPixFoldImageLoader] 解码真实 PNG 与 JPEG 文件；
 * 2. 超预算大图（如 1024×1024）经 [CoilThumbnailSource] 与 `ThumbBudget`（如 `requestPx = 256` / `512`）
 *    请求时真实降采样到目标尺寸以内（`<= 512×512` / `<= 256×256`）；
 * 3. 损坏图片或缺失 URI 安全降级返回 `null`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    shadows = [
        FakeDocumentsProvider.ShadowDocumentsProvider::class,
        FakeDocumentsProvider.ShadowPosixLseekBridge::class,
    ],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoilThumbnailSourceOfflineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var provider: FakeDocumentsProvider
    private lateinit var imageLoader: coil3.ImageLoader
    private lateinit var coilSource: CoilThumbnailSource

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val rootDir = tempFolder.newFolder("thumb_root")
        provider = FakeDocumentsProvider.setup(
            rootDir = rootDir,
            rootDocId = "primary:thumb_root",
            rootDisplayName = "thumb_root",
        )
        imageLoader = createPixFoldImageLoader(context)
        coilSource = CoilThumbnailSource(ImageLoaderCoilImageFetcher(context, imageLoader))
    }

    @After
    fun tearDown() {
        imageLoader.shutdown()
    }

    private fun encodeSolidImage(
        width: Int,
        height: Int,
        color: Int,
        format: Bitmap.CompressFormat,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        return ByteArrayOutputStream().use { out ->
            check(bitmap.compress(format, 95, out)) { "Bitmap.compress 失败" }
            bitmap.recycle()
            out.toByteArray()
        }
    }

    @Test
    fun `decodes real PNG and JPEG from FakeDocumentsProvider content URI`() = runTest {
        val pngBytes = encodeSolidImage(128, 96, Color.rgb(32, 160, 220), Bitmap.CompressFormat.PNG)
        val jpgBytes = encodeSolidImage(160, 120, Color.rgb(220, 80, 40), Bitmap.CompressFormat.JPEG)
        provider.writeFile("SeriesA/cover.png", pngBytes)
        provider.writeFile("SeriesA/page01.jpg", jpgBytes)

        val pngUri = provider.documentUriFor("SeriesA/cover.png").toString()
        val jpgUri = provider.documentUriFor("SeriesA/page01.jpg").toString()

        val pngBitmap = assertNotNull(
            coilSource.thumbnail(pngUri, widthPx = 256, heightPx = 256),
            "应能从 FakeDocumentsProvider 的 content:// URI 解码真实 PNG",
        )
        assertTrue(pngBitmap.width > 0 && pngBitmap.height > 0)

        val jpgBitmap = assertNotNull(
            coilSource.thumbnail(jpgUri, widthPx = 256, heightPx = 256),
            "应能从 FakeDocumentsProvider 的 content:// URI 解码真实 JPEG",
        )
        assertTrue(jpgBitmap.width > 0 && jpgBitmap.height > 0)
    }

    @Test
    fun `downsamples 1024x1024 source image according to requestPx and ThumbBudget`() = runTest {
        val largePng = encodeSolidImage(1024, 1024, Color.rgb(60, 180, 90), Bitmap.CompressFormat.PNG)
        val largeJpg = encodeSolidImage(1024, 1024, Color.rgb(180, 60, 140), Bitmap.CompressFormat.JPEG)
        provider.writeFile("SeriesA/large_1024.png", largePng)
        provider.writeFile("SeriesA/large_1024.jpg", largeJpg)

        val pngUri = provider.documentUriFor("SeriesA/large_1024.png").toString()
        val jpgUri = provider.documentUriFor("SeriesA/large_1024.jpg").toString()

        // 1. 直接通过 CoilThumbnailSource 请求 256×256：1024×1024 必须降采样到 <= 256×256
        val direct256 = assertNotNull(coilSource.thumbnail(pngUri, widthPx = 256, heightPx = 256))
        assertTrue(
            direct256.width <= 256 && direct256.height <= 256,
            "1024×1024 原图按 256×256 请求时应降采样至 <= 256×256，实际为 ${direct256.width}×${direct256.height}",
        )

        // 2. 通过 CachingThumbnailSource 使用默认预算（requestPx = 512）请求超大控件尺寸（2048×2048）：
        //    必须被 ThumbBudget.requestPx (512) 钳制并降采样至 <= 512×512
        val budget512 = thumbBudgetFor(
            displayPx = 512,
            availableHeapBytes = 256L * 1024 * 1024,
            cpuCores = 8,
        )
        assertEquals(512, budget512.requestPx)
        val cachingSource = CachingThumbnailSource(delegate = coilSource, budget = budget512)

        val downsampled = assertNotNull(
            cachingSource.thumbnail(jpgUri, widthPx = 2048, heightPx = 2048),
            "应通过 CachingThumbnailSource + CoilThumbnailSource 解码并缓存缩略图",
        )
        assertTrue(
            downsampled.width <= 512 && downsampled.height <= 512,
            "1024×1024 原图经 ThumbBudget(requestPx=512) 钳制后必须降采样至 <= 512×512，实际为 ${downsampled.width}×${downsampled.height}",
        )
        assertNotNull(cachingSource.peek(jpgUri), "解码成功后 CachingThumbnailSource.peek 应同步命中缓存")
    }

    @Test
    fun `corrupted image or missing document URI returns null gracefully`() = runTest {
        provider.writeText("SeriesA/broken.jpg", "NOT_AN_IMAGE_PAYLOAD")
        val brokenUri = provider.documentUriFor("SeriesA/broken.jpg").toString()
        val missingUri = provider.documentUriFor("SeriesA/non_existent.png").toString()

        assertNull(coilSource.thumbnail(brokenUri, widthPx = 256, heightPx = 256))
        assertNull(coilSource.thumbnail(missingUri, widthPx = 256, heightPx = 256))
    }
}
