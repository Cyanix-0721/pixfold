package com.cyanix.pixfold.ui.workspace

import androidx.compose.ui.graphics.ImageBitmap
import com.cyanix.pixfold.domain.thumb.ThumbBudget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 给任意 [ThumbnailSource] 加上**内存缓存**与**并发闸门**（D2b 千级缩略图策略）。
 *
 * ## 为什么要单独一个装饰器
 *
 * 平台实现 [com.cyanix.pixfold.data.saf.AndroidThumbnailSource] 只有「调一次
 * `ContentResolver.loadThumbnail`、失败返回 null」这一件事，没有任何可单测的逻辑；
 * 而缓存与并发恰恰是**最容易写错、又最难在真机上看出来**的部分：
 *
 * - LRU 顺序错了 → 滚动时反复重载同一张，表现为「卡」；
 * - 字节账目错了 → 内存悄悄超预算，表现为「偶发 OOM」；
 * - 并发不设上限 → binder 池被打满，表现为「滚一下卡很久然后一堆图同时出现」。
 *
 * 这三种都不是崩溃，真机上很难定性。抽成装饰器后可以用假 [ThumbnailSource]
 * 精确断言「请求了几次、什么顺序、同时在途几个」。
 *
 * ## 两个数字从哪来
 *
 * 全部来自领域层 [ThumbBudget]（`thumbBudgetFor`）：
 * - [ThumbBudget.cacheBytes] / [ThumbBudget.cacheEntries] → 缓存上限；
 * - [ThumbBudget.maxConcurrent] → 并发闸门；
 * - [ThumbBudget.requestPx] → **请求尺寸上限**。
 *
 * 最后一条容易被忽略：调用方传的是「这个 View 有多大」，但预算是为整屏定的，
 * 某个超大的控件不该突破它。
 *
 * ## 缓存查询在闸门外
 *
 * 命中缓存的调用**不排队**。否则滚回已看过的位置时，明明内存里有图，却要等前面
 * 几十个未命中请求一个个读完 —— 这正是「滚动卡顿」最典型的成因。
 *
 * @param delegate 真正的加载实现（平台层）。
 * @param budget 预算。默认取 [DEFAULT_BUDGET]（512px / 32 张 / 8 路，
 *   由 256MB 堆 × 12.5% ÷ 1MiB 算出）。
 */
class CachingThumbnailSource(
    private val delegate: ThumbnailSource,
    private val budget: ThumbBudget = DEFAULT_BUDGET,
) : ThumbnailSource {

    /** 见 [ThumbnailCache] 的 KDoc：按字节淘汰的 LRU，条目数做兜底。 */
    private val cache = ThumbnailCache(maxBytes = budget.cacheBytes, maxEntries = budget.cacheEntries)

    /**
     * 并发闸门。
     *
     * 网格一屏可见 12 张，滚动时 `LaunchedEffect` 会同时发起几十个请求，每个都是一次
     * binder 往返。不限并发的话 binder 线程池被打满，反而让**每一张**都更慢。
     *
     * ⚠️ `Semaphore` 的许可在协程取消时会自动归还，故不需要 `try/finally`。
     */
    private val gate = Semaphore(budget.maxConcurrent)

    /** 当前缓存占用字节数（测试与调试用）。 */
    val cachedBytes: Long get() = cache.sizeBytes

    /** 当前缓存条目数（测试与调试用）。 */
    val cachedCount: Int get() = cache.size

    /**
     * **在途请求合并表**：uri → 正在进行的加载。
     *
     * 必须有它，「双检」是不够的：`gate` 的许可数是 `cpuCores + 1`（通常 8），
     * 所以滚动时同一张图的多个请求**完全可能一起穿过双检**，于是真的读了 N 次
     * （每次都写缓存，账目还被重复算 N 遍）。`Mutex` 保护是因为 `withPermit`
     * 之后协程可能在不同线程上恢复。
     */
    private val inFlight = HashMap<String, CompletableDeferred<ImageBitmap?>>()

    override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? {
        // 命中就直接返回，连闸门都不进（见类 KDoc「缓存查询在闸门外」）。
        cache.get(uri)?.let { return it }

        // 合并同 uri 的在途请求：只有一个协程真正去读，其余 await 它的结果。
        val request: CompletableDeferred<ImageBitmap?>
        val owner: Boolean
        synchronized(inFlight) {
            val existing = inFlight[uri]
            if (existing != null) {
                request = existing
                owner = false
            } else {
                request = CompletableDeferred()
                inFlight[uri] = request
                owner = true
            }
        }

        if (!owner) {
            // 等待者拿到 owner 的结果。owner 被取消时这里是 CancellationException ——
            // 吞掉并返回 null（回退程序化缩略图），比让整屏协程一起崩要好。
            return try {
                request.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                null
            }
        }

        return try {
            val loaded = gate.withPermit {
                // 再查一次：排队期间别人可能已读完并写进缓存（此时应直接复用）。
                cache.get(uri) ?: run {
                    val requestPx = minOf(
                        widthPx.coerceAtLeast(1),
                        heightPx.coerceAtLeast(1),
                        budget.requestPx,
                    )
                    delegate.thumbnail(uri, requestPx, requestPx).also { bmp ->
                        if (bmp != null) cache.put(uri, bmp)
                    }
                }
            }
            request.complete(loaded)
            loaded
        } catch (e: Throwable) {
            // 让等待者一起失败（而不是永远挂着），再照常抛出。
            request.completeExceptionally(e)
            throw e
        } finally {
            synchronized(inFlight) { inFlight.remove(uri) }
        }
    }

    /**
     * 清空缓存。
     *
     * **工作区切换时必须调用**：旧工作区的 document URI 已不可读，留着既占内存，
     * 又会让新工作区的图因为「预算被旧图占着」而被提前淘汰。
     */
    fun clearCache() = cache.clear()

    companion object {
        /**
         * 典型机型的默认预算：**请求 512px**、**缓存 32 张**、**并发 8 路**。
         *
         * 算法（领域层 `thumbBudgetFor`）：512×512×4B = 1MiB/张；
         * 256MB 堆 × 12.5% = 32MB ⇒ 32 张。
         *
         * **为什么写死而不在构造时读 `Runtime.maxMemory()`**：读实际值是「更正确」的，
         * 但会让单测结果依赖跑测试的机器；而可测的部分（降级规则 / 2 的幂对齐 /
         * 字节账目）已经在领域层 `thumbBudgetFor` 里单测过了，这里只需要一个合理值。
         * 真机实测后若要按机型自适应，改这一处即可。
         */
        val DEFAULT_BUDGET: ThumbBudget =
            com.cyanix.pixfold.domain.thumb.thumbBudgetFor(
                displayPx = 512,
                availableHeapBytes = 256L * 1024 * 1024,
                cpuCores = 8,
            )
    }
}
