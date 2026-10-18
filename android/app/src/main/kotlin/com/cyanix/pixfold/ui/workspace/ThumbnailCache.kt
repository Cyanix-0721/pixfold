package com.cyanix.pixfold.ui.workspace

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 缩略图**内存缓存**（D2b 千级缩略图策略的第四块拼图）。
 *
 * ## 为什么要自己写而不引三方库
 *
 * 需求只有三条：按 uri 取、超预算淘汰最久未用的、能清空。`androidx.collection.LruCache`
 * 也能用，但它按**条目数**计（我们要按**字节**），且不感知 [ImageBitmap]。而三方图片库
 * （Coil / Glide）会连带引入网络栈、磁盘缓存与自己的线程池 —— 本项目图片全部来自 SAF、
 * 不需要网络，引进来是纯负担（D1 收尾轮的图标库评估已确立「不引入依赖」的基调）。
 *
 * ## 预算从哪来
 *
 * [maxBytes] / [maxEntries] 由领域层 `thumbBudgetFor(...)` 算出（`ThumbBudget.cacheBytes`
 * 与 `cacheEntries`）。**领域层决定策略、这里只执行** —— 阈值与降级规则在 `domain/thumb/`
 * 里可离线单测，不必拉起 Android 环境。
 *
 * ## 淘汰时机
 *
 * 只在 [put] 时淘汰。`get` 不淘汰：读一次就丢掉别的条目会让滚动时反复重载，
 * 反而更慢；预算本身已经留了 8 倍余量（12.5% 堆）。
 *
 * ## 为什么不是线程安全的
 *
 * 全部访问都在主线程（Compose 的组合/LaunchedEffect 恢复点），与 `WorkspaceController`
 * 同样的取舍。真要跨线程再说 —— 现在加锁是纯开销。
 *
 * @param maxBytes 字节上限。`<= 0` 时退化为「只放 maxEntries 条」。
 * @param maxEntries 条目数上限（取 [maxBytes] 与它的**双**约束）。
 */
class ThumbnailCache(
    private val maxBytes: Long,
    private val maxEntries: Int,
) {
    // LinkedHashMap 的 accessOrder = true 即「最近访问的移到队尾」，头部就是最久未用。
    private val entries = LinkedHashMap<String, ImageBitmap>(16, 0.75f, true)
    private var bytes = 0L

    /** 当前缓存占用字节数。测试与调试用。 */
    val sizeBytes: Long get() = bytes

    /** 当前缓存条目数。测试与调试用。 */
    val size: Int get() = entries.size

    /** 取一张；命中会把它移到队尾（成为「最近使用」）。 */
    fun get(uri: String): ImageBitmap? = entries[uri]

    /**
     * 放入一张，超预算则从最久未用开始淘汰。
     *
     * 字节数**从 bitmap 自己算**（`width × height × 4`）而不是由调用方传入：
     * 传两个数会有「调用方按请求尺寸算、缓存里却是 provider 实际返回的尺寸」的漂移，
     * 而 provider 返回的尺寸完全可能不等于请求值（它只保证不超过）。
     */
    fun put(uri: String, bitmap: ImageBitmap) {
        // 同一 uri 覆盖时先把旧账扣掉，否则重复加载同一张会让账目越滚越大。
        entries.remove(uri)?.let { this.bytes -= entryBytesOf(it) }
        entries[uri] = bitmap
        this.bytes += entryBytesOf(bitmap)
        trim()
    }

    /** 清空（工作区切换时必须调用 —— 旧工作区的 uri 已不可读）。 */
    fun clear() {
        entries.clear()
        bytes = 0L
    }

    private fun trim() {
        // 双约束：字节超了 或 条目超了都要淘汰。字节是主约束（大图几张就吃满），
        // 条目数是兜底（防止极小的图把 map 撑爆）。accessOrder = true ⇒ 迭代器头部
        // 即最久未用，`remove()` 摘掉刚取出的那个就是标准 LRU 淘汰。
        while ((bytes > maxBytes || entries.size > maxEntries) && entries.isNotEmpty()) {
            val eldest = entries.entries.iterator()
            val entry = eldest.next()
            eldest.remove()
            bytes -= entryBytesOf(entry.value)
            // 防御：账目若因某种原因不同步，别让 bytes 变负（否则永远淘汰不干净）。
            if (bytes < 0) bytes = 0
        }
    }

    /** 单张图的字节数：ARGB_8888，4 字节/像素。 */
    private fun entryBytesOf(bitmap: ImageBitmap): Long =
        bitmap.width.toLong() * bitmap.height.toLong() * BYTES_PER_PIXEL

    private companion object {
        const val BYTES_PER_PIXEL = 4L
    }
}
