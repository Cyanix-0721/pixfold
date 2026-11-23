package com.cyanix.pixfold.ui.workspace

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ThumbnailCache] 的单测（D2b 千级缩略图策略）。
 *
 * ## 为什么自己实现 [ImageBitmap] 而不 `ImageBitmap(px, px)`
 *
 * 后者内部调 `android.graphics.Bitmap.createBitmap`：纯 JVM 下抛
 * `Method createBitmap not mocked`，而开了 Robolectric 又需要 `@GraphicsMode(NATIVE)`
 * 才有真实图形后端 —— 那会把一个**纯账目**测试拖进图形栈（慢且与断言无关）。
 *
 * 这个类要验证的只有「条目数 / 字节数 / 淘汰顺序」，与像素毫无关系，
 * 所以给一个只回答 `width` / `height` 的假位图最合适：零 Android 依赖、毫秒级。
 */
class ThumbnailCacheTest {

    /** 只关心尺寸的假位图；`readPixels` 等语义方法在账目测试里永远用不到。 */
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

    /** 造一张 [px] × [px] 的位图。ARGB_8888 ⇒ 每张 px² × 4 字节。 */
    private fun bmp(px: Int): ImageBitmap = FakeBitmap(px, px)

    /** 一张 [px] 见方位图的字节数。 */
    private fun bytes(px: Int): Long = px.toLong() * px.toLong() * 4L

    @Test
    fun `放入后能按 uri 取回`() {
        val cache = ThumbnailCache(maxBytes = bytes(512) * 4, maxEntries = 4)
        val image = bmp(512)
        cache.put("content://doc/1", image)

        assertEquals(image, cache.get("content://doc/1"))
        assertEquals(1, cache.size)
        assertEquals(bytes(512), cache.sizeBytes)
    }

    @Test
    fun `没放过的 uri 返回 null`() {
        val cache = ThumbnailCache(maxBytes = bytes(512) * 4, maxEntries = 4)
        assertNull(cache.get("content://doc/missing"))
    }

    @Test
    fun `超过字节预算时淘汰最久未用的`() {
        // 预算 2 张，放 3 张 ⇒ 第一张被淘汰。
        val cache = ThumbnailCache(maxBytes = bytes(256) * 2, maxEntries = 100)
        cache.put("a", bmp(256))
        cache.put("b", bmp(256))
        cache.put("c", bmp(256))

        assertNull(cache.get("a"), "最久未用的 a 应被淘汰")
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertEquals(2, cache.size)
    }

    @Test
    fun `get 会把条目移到最近使用端`() {
        // 关键行为：不这样写，滚动一遍列表后「刚看过的图」会被当成最久未用淘汰掉。
        val cache = ThumbnailCache(maxBytes = bytes(256) * 2, maxEntries = 100)
        cache.put("a", bmp(256))
        cache.put("b", bmp(256))
        cache.get("a") // a 变成最近使用
        cache.put("c", bmp(256)) // 应淘汰 b 而不是 a

        assertNotNull(cache.get("a"), "a 刚被访问过，不该被淘汰")
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `条目数上限独立于字节预算生效`() {
        // 极小的图（16px ⇒ 1KB/张）：字节预算绰绰有余，条目数上限必须先拦住，
        // 否则 map 会无限增长。
        val cache = ThumbnailCache(maxBytes = bytes(1024) * 100, maxEntries = 3)
        repeat(5) { i -> cache.put("d$i", bmp(16)) }

        assertEquals(3, cache.size)
        assertNull(cache.get("d0"))
        assertNull(cache.get("d1"))
        assertNotNull(cache.get("d4"))
    }

    @Test
    fun `重复放入同一 uri 不会让账目翻倍`() {
        // 缩略图可能因为重组/页面切换被重复请求；若不扣旧账，sizeBytes 会越滚越大，
        // 缓存会莫名开始疯狂淘汰。
        val cache = ThumbnailCache(maxBytes = bytes(512) * 4, maxEntries = 4)
        repeat(10) { cache.put("same", bmp(512)) }

        assertEquals(1, cache.size)
        assertEquals(bytes(512), cache.sizeBytes)
    }

    @Test
    fun `覆盖同一 uri 时按新尺寸重新记账`() {
        // 换列数/旋转屏后请求尺寸变化，provider 可能返回不同尺寸的图。
        val cache = ThumbnailCache(maxBytes = bytes(1024) * 4, maxEntries = 4)
        cache.put("x", bmp(256))
        cache.put("x", bmp(512))

        assertEquals(1, cache.size)
        assertEquals(bytes(512), cache.sizeBytes, "旧账必须被扣掉")
    }

    @Test
    fun `clear 清空且账目归零`() {
        val cache = ThumbnailCache(maxBytes = bytes(512) * 4, maxEntries = 4)
        cache.put("a", bmp(512))
        cache.put("b", bmp(512))
        assertTrue(cache.sizeBytes > 0)

        cache.clear()

        assertEquals(0, cache.size)
        assertEquals(0L, cache.sizeBytes)
        assertNull(cache.get("a"))
    }

    @Test
    fun `单张图超过整个预算时放进后立即被淘汰`() {
        // 边界：预算按小屏算好，但 provider 返回了远大于请求的图。
        // 不能因此崩，也不能让 bytes 变负导致后续淘汰失效。
        val cache = ThumbnailCache(maxBytes = bytes(64), maxEntries = 4)
        cache.put("huge", bmp(512))

        assertEquals(0, cache.size, "放不下的图应立刻被淘汰")
        assertEquals(0L, cache.sizeBytes, "账目必须归零而不是变负")
        assertNull(cache.get("huge"))
    }

    @Test
    fun `淘汰后继续放入仍然正常工作`() {
        // 淘汰逻辑若把状态搞坏（如 bytes 变负），后续放入就会静默不淘汰。
        val cache = ThumbnailCache(maxBytes = bytes(256), maxEntries = 100)
        cache.put("a", bmp(256))
        cache.put("b", bmp(256)) // a 被淘汰
        cache.put("c", bmp(256)) // b 被淘汰

        assertEquals(1, cache.size)
        assertEquals(bytes(256), cache.sizeBytes)
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `零预算时什么都不留`() {
        // 领域层保证 cacheBytes >= MIN_CACHE_ENTRIES × entryBytes，故正常路径不会传 0；
        // 但构造边界要能承受（否则除零或死循环）。
        val cache = ThumbnailCache(maxBytes = 0L, maxEntries = 0)
        cache.put("a", bmp(256))

        assertEquals(0, cache.size)
        assertEquals(0L, cache.sizeBytes)
    }

    @Test
    fun `多线程并发读写清空不抛异常且账目守恒`() {
        // LinkedHashMap(accessOrder = true) 连 get() 都会改内部双向链表指针；
        // 未同步时多线程同时 get/put/clear 极易抛 ConcurrentModificationException 或破坏链表。
        val maxEntries = 16
        val maxBytes = bytes(64) * maxEntries
        val cache = ThumbnailCache(maxBytes = maxBytes, maxEntries = maxEntries)
        val threads = 8
        val iterations = 1_500
        val pool = Executors.newFixedThreadPool(threads)
        val barrier = CyclicBarrier(threads)
        val errors = CopyOnWriteArrayList<Throwable>()

        try {
            val futures = (0 until threads).map { tid ->
                pool.submit {
                    try {
                        barrier.await(5, TimeUnit.SECONDS)
                        repeat(iterations) { step ->
                            val key = "content://doc/${(tid * 13 + step) % 24}"
                            when (step % 5) {
                                0, 1 -> cache.put(key, bmp(64))
                                2, 3 -> cache.get(key)
                                else -> if (step % 50 == 4) cache.clear() else cache.get(key)
                            }
                            val s = cache.size
                            val b = cache.sizeBytes
                            if (s < 0 || b < 0L) {
                                errors += AssertionError("缓存账目出现负值: size=$s, bytes=$b")
                            }
                        }
                    } catch (t: Throwable) {
                        errors += t
                    }
                }
            }
            futures.forEach { it.get(15, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertTrue(errors.isEmpty(), "并发访问抛出异常: ${errors.firstOrNull()}")
        assertTrue(cache.size in 0..maxEntries, "并发后条目数越界: ${cache.size}")
        assertTrue(cache.sizeBytes in 0L..maxBytes, "并发后字节数越界: ${cache.sizeBytes}")
    }
}
