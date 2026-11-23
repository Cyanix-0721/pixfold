package com.cyanix.pixfold.data.coil

import android.graphics.Bitmap
import coil3.BitmapImage
import coil3.asImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CoilThumbnailSource 的单测。
 *
 * 只测可单测的那部分逻辑：null 透传、Image 到 ImageBitmap 包装、异常吞成 null、
 * 非正尺寸钳制、取消透传。真正的 Coil 解码（ImageLoaderCoilImageFetcher 调
 * ImageLoader.execute）是薄平台边界胶水层。
 *
 * 用假 CoilImageFetcher 返回真 BitmapImage（Robolectric 下 Bitmap 可用），
 * 不碰真实 ImageLoader。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CoilThumbnailSourceTest {

    private fun imageOf(w: Int = 8, h: Int = 8): BitmapImage =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).asImage()

    @Test
    fun null_image_yields_null() = runTest {
        val source = CoilThumbnailSource { _, _, _ -> null }
        assertNull(source.thumbnail("content://x", 128, 128))
    }

    @Test
    fun success_image_is_wrapped_as_image_bitmap() = runTest {
        val source = CoilThumbnailSource { _, _, _ -> imageOf(16, 24) }
        val out = assertNotNull(source.thumbnail("content://x", 128, 128))
        assertEquals(16, out.width)
        assertEquals(24, out.height)
    }

    @Test
    fun fetch_failure_yields_null_instead_of_throwing() = runTest {
        val source = CoilThumbnailSource { _, _, _ -> throw RuntimeException("boom") }
        assertNull(source.thumbnail("content://x", 128, 128))
    }

    @Test
    fun non_positive_request_size_is_clamped_to_one() = runTest {
        val seen = mutableListOf<Pair<Int, Int>>()
        val source = CoilThumbnailSource { _, w, h ->
            seen += w to h
            null
        }
        source.thumbnail("content://x", 0, -7)
        assertEquals(listOf(1 to 1), seen)
    }

    @Test
    fun cancellation_is_rethrown() = runTest {
        val source = CoilThumbnailSource { _, _, _ -> throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { source.thumbnail("content://x", 128, 128) }
    }
}
