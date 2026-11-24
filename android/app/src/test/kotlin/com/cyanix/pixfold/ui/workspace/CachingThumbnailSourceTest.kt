package com.cyanix.pixfold.ui.workspace

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import com.cyanix.pixfold.domain.thumb.ThumbBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CachingThumbnailSource] 的单测（D2b 千级缩略图策略）。
 *
 * 全部用假 [ThumbnailSource]（不碰 `ContentResolver`），验证三件真机上极难定性的事：
 * **命中不重读**、**并发不超上限**、**请求尺寸不超预算**。
 * 用 `runTest` 的虚拟时间，`delay` 不真的等待。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachingThumbnailSourceTest {

    /** 只关心尺寸的假位图；缓存按 `width × height × 4` 记账。 */
    private class FakeBitmap(override val width: Int, override val height: Int) : ImageBitmap {
        override val config: ImageBitmapConfig get() = ImageBitmapConfig.Argb8888
        override val hasAlpha: Boolean get() = false
        override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
        override fun readPixels(
            buffer: IntArray,
            startX: Int,
            startY: Int,
            width: Int,
            height: Int,
            bufferOffset: Int,
            stride: Int,
        ) = Unit

        override fun prepareToDraw() = Unit
    }

    /**
     * 记录调用的假实现。
     *
     * @param result 返回什么；`null` = 取不到（应**不**被缓存）。
     * @param px 返回的位图边长。
     * @param delayMillis 每次调用的模拟耗时（用虚拟时间）。
     */
    private class RecordingSource(
        private val result: Boolean = true,
        private val px: Int = 256,
        private val delayMillis: Long = 0,
    ) : ThumbnailSource {
        val calls = CopyOnWriteArrayList<Triple<String, Int, Int>>()
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)

        override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? {
            calls += Triple(uri, widthPx, heightPx)
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            try {
                if (delayMillis > 0) delay(delayMillis)
                return if (result) FakeBitmap(px, px) else null
            } finally {
                inFlight.decrementAndGet()
            }
        }
    }

    private fun budget(
        requestPx: Int = 512,
        concurrent: Int = 2,
        entries: Int = 4,
    ) = ThumbBudget(
        requestPx = requestPx,
        maxConcurrent = concurrent,
        cacheEntries = entries,
        cacheBytes = entries.toLong() * 256L * 256L * 4L,
        entryBytes = 256L * 256L * 4L,
        degraded = false,
    )

    // ==================== 缓存 ====================

    @Test
    fun `同一 uri 第二次命中缓存不再请求平台`() = runTest {
        val platform = RecordingSource()
        val source = CachingThumbnailSource(platform, budget())

        val first = source.thumbnail("content://doc/a", 256, 256)
        val second = source.thumbnail("content://doc/a", 256, 256)

        assertNotNull(first)
        assertEquals(first, second, "应返回同一张缓存位图")
        assertEquals(1, platform.calls.size, "第二次必须命中缓存，不该再问平台")
    }

    @Test
    fun `不同 uri 各自请求一次`() = runTest {
        val platform = RecordingSource()
        val source = CachingThumbnailSource(platform, budget(entries = 8))

        source.thumbnail("content://doc/a", 256, 256)
        source.thumbnail("content://doc/b", 256, 256)

        assertEquals(2, platform.calls.size)
        assertEquals(listOf("content://doc/a", "content://doc/b"), platform.calls.map { it.first })
    }

    @Test
    fun `取不到的图不被缓存`() = runTest {
        // 关键：若把 null 也缓存，该图就会永久显示占位（授权恢复后也不重试）。
        val platform = RecordingSource(result = false)
        val source = CachingThumbnailSource(platform, budget())

        assertNull(source.thumbnail("content://doc/bad", 256, 256))
        assertEquals(0, source.cachedCount)

        assertNull(source.thumbnail("content://doc/bad", 256, 256))
        assertEquals(2, platform.calls.size, "取不到时下次仍应重试")
    }

    @Test
    fun `clearCache 后重新请求平台`() = runTest {
        val platform = RecordingSource()
        val source = CachingThumbnailSource(platform, budget())

        source.thumbnail("content://doc/a", 256, 256)
        assertEquals(1, platform.calls.size)

        source.clearCache()
        assertEquals(0, source.cachedCount)
        assertEquals(0L, source.cachedBytes)

        source.thumbnail("content://doc/a", 256, 256)
        assertEquals(2, platform.calls.size)
    }

    // ==================== 请求尺寸夹取 ====================

    @Test
    fun `请求尺寸被预算上限夹住`() = runTest {
        // 超大 View（如平板横屏）不该突破为整屏定的预算。
        val platform = RecordingSource()
        val source = CachingThumbnailSource(platform, budget(requestPx = 256))

        source.thumbnail("content://doc/a", 4096, 4096)

        assertEquals(1, platform.calls.size)
        assertEquals(256, platform.calls[0].second, "宽度应被夹到预算上限")
        assertEquals(256, platform.calls[0].third, "高度应被夹到预算上限")
    }

    @Test
    fun `显示尺寸小于预算时按显示尺寸请求`() = runTest {
        // 列表行只需要 132px，不该为它读 512px 的图（内存 ×16）。
        val platform = RecordingSource()
        val source = CachingThumbnailSource(platform, budget(requestPx = 512))

        source.thumbnail("content://doc/a", 132, 132)

        assertEquals(132, platform.calls[0].second)
    }

    @Test
    fun `非正数显示尺寸被夹到一`() = runTest {
        // Size 的两个参数必须 > 0，否则 provider 抛 IllegalArgumentException。
        val platform = RecordingSource()
        val source = CachingThumbnailSource(platform, budget(requestPx = 512))

        source.thumbnail("content://doc/a", 0, -5)

        assertEquals(1, platform.calls[0].second)
        assertEquals(1, platform.calls[0].third)
    }

    // ==================== 并发闸门 ====================

    @Test
    fun `同时发起的请求数不超过预算上限`() = runTest {
        // 这是「滚动时不卡」的核心保障：不限并发会把 binder 池打满。
        val platform = RecordingSource(delayMillis = 10)
        val source = CachingThumbnailSource(platform, budget(concurrent = 2, entries = 16))

        val jobs = (1..8).map { i ->
            async { source.thumbnail("content://doc/$i", 128, 128) }
        }
        jobs.awaitAll()

        assertEquals(8, platform.calls.size)
        assertTrue(
            platform.maxInFlight.get() <= 2,
            "同时在途 ${platform.maxInFlight.get()} 个请求，超过预算上限 2",
        )
    }

    @Test
    fun `并发上限为一也能全部完成`() = runTest {
        // 边界：闸门退化成串行时不能死锁。
        val platform = RecordingSource(delayMillis = 1)
        val source = CachingThumbnailSource(platform, budget(concurrent = 1, entries = 16))

        val results = (1..4).map { i ->
            async { source.thumbnail("content://doc/$i", 128, 128) }
        }.awaitAll()

        assertTrue(results.all { it != null })
        assertEquals(4, platform.calls.size)
        assertEquals(1, platform.maxInFlight.get())
    }

    // ==================== 去重 ====================

    @Test
    fun `并发请求同一 uri 只问平台一次`() = runTest {
        // 快速上下滚动会让同一张图被并发请求多次。双检（gate 内再查一次缓存）是必须的：
        // 否则同一张既被重复读、账目还被重复算。
        val platform = RecordingSource(delayMillis = 10)
        val source = CachingThumbnailSource(platform, budget(concurrent = 4, entries = 8))

        val results = (1..4).map {
            async { source.thumbnail("content://doc/same", 128, 128) }
        }.awaitAll()

        assertEquals(1, platform.calls.size, "同一 uri 的并发请求应被去重")
        assertTrue(results.all { it != null })
    }

    // ==================== 真实规模 ====================

    @Test
    fun `851 张图逐张加载时缓存不会无限增长`() = runTest {
        // D2b 的头号目标规模：实测样本库 851 张。全部走完时缓存必须停在预算内，
        // 而不是「851 × 1MiB」。
        val platform = RecordingSource(px = 256)
        val source = CachingThumbnailSource(platform, budget(entries = 4))

        repeat(851) { i -> source.thumbnail("content://doc/$i", 256, 256) }

        assertEquals(851, platform.calls.size)
        assertTrue(
            source.cachedCount <= 4,
            "缓存条目 ${source.cachedCount} 超过上限 4",
        )
        assertEquals(4L * 256L * 256L * 4L, source.cachedBytes)
    }

    // ==================== 取消传播与并发在途安全 ====================

    @Test
    fun `等待者协程取消时必须重抛 CancellationException 且不影响其他并发等待者`() = runTest {
        val gate = CompletableDeferred<ImageBitmap?>()
        val started = CompletableDeferred<Unit>()
        val delegate = object : ThumbnailSource {
            override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? {
                started.complete(Unit)
                return gate.await()
            }
        }
        val source = CachingThumbnailSource(delegate, budget(concurrent = 4, entries = 8))

        val owner = async { source.thumbnail("content://doc/shared", 256, 256) }
        started.await()

        var waiter1RethrewCancel = false
        var waiter1ReturnedNormally = false
        val waiter1 = launch {
            try {
                source.thumbnail("content://doc/shared", 256, 256)
                waiter1ReturnedNormally = true
            } catch (e: CancellationException) {
                waiter1RethrewCancel = true
                throw e
            }
        }
        val waiter2 = async { source.thumbnail("content://doc/shared", 256, 256) }
        runCurrent()

        waiter1.cancelAndJoin()
        assertTrue(
            waiter1RethrewCancel,
            "调用方协程取消时不得吞掉 CancellationException，否则会破坏结构化并发",
        )
        assertFalse(waiter1ReturnedNormally, "被取消的等待者不该以返回 null 的形式正常结束")

        val expected = FakeBitmap(256, 256)
        gate.complete(expected)
        assertEquals(expected, owner.await(), "owner 不应受单个等待者取消影响")
        assertEquals(expected, waiter2.await(), "其余存活等待者应正常拿到共享结果")
    }

    @Test
    fun `owner 取消时存活等待者安全回退 null 而不崩溃`() = runTest {
        val gate = CompletableDeferred<ImageBitmap?>()
        val started = CompletableDeferred<Unit>()
        val delegate = object : ThumbnailSource {
            override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? {
                started.complete(Unit)
                return gate.await()
            }
        }
        val source = CachingThumbnailSource(delegate, budget(concurrent = 4, entries = 8))

        val owner = launch { source.thumbnail("content://doc/owner-cancel", 256, 256) }
        started.await()

        val activeWaiter = async { source.thumbnail("content://doc/owner-cancel", 256, 256) }
        runCurrent()

        owner.cancelAndJoin()
        assertNull(activeWaiter.await(), "owner 被取消时存活的等待者应安全回退 null")
    }

    // ==================== 同步 peek 命中（P1 #8 零闪烁） ====================

    @Test
    fun `peek returns null before load, cached bitmap synchronously after load, and null after clear`() = runTest {
        val platform = RecordingSource()
        val source = CachingThumbnailSource(platform, budget())

        assertNull(source.peek("content://doc/a"), "未加载前 peek 必须返回 null")

        val loaded = source.thumbnail("content://doc/a", 256, 256)
        assertNotNull(loaded)
        assertEquals(loaded, source.peek("content://doc/a"), "缓存命中后 peek 必须同步返回同一张位图")
        assertEquals(1, platform.calls.size, "peek 不得触发底层平台加载")

        source.clearCache()
        assertNull(source.peek("content://doc/a"), "clearCache 后 peek 必须返回 null")
    }

    // ==================== PreviewImageCacheSource 有界 LRU ====================

    @Test
    fun `PreviewImageCacheSource caches up to 3 entries and preserves non-square request dimensions`() = runTest {
        val platform = RecordingSource(px = 256)
        val preview = PreviewImageCacheSource(platform, maxEntries = 3)

        val first = preview.thumbnail("content://doc/p1", 1200, 2400)
        val again = preview.thumbnail("content://doc/p1", 1200, 2400)

        assertNotNull(first)
        assertEquals(first, again, "相同 uri 与尺寸应命中预览缓存")
        assertEquals(1, platform.calls.size, "命中预览缓存不得重解码")
        assertEquals(1200, platform.calls[0].second, "预览缓存不得把非方形宽度裁成方形")
        assertEquals(2400, platform.calls[0].third, "预览缓存不得把非方形高度裁成方形")

        // 装满 3 条再加第 4 条，淘汰最久未用的 p1
        preview.thumbnail("content://doc/p2", 1200, 2400)
        preview.thumbnail("content://doc/p3", 1200, 2400)
        preview.thumbnail("content://doc/p4", 1200, 2400)
        assertEquals(3, preview.cachedCount, "预览缓存条目数必须受 3 条上界约束")

        // p1 已被淘汰，再次请求会回源
        preview.thumbnail("content://doc/p1", 1200, 2400)
        assertEquals(5, platform.calls.size, "淘汰后的条目再次请求应重新解码")
    }
}
