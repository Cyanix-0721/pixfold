package com.cyanix.pixfold.ui.workspace

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 预览大图专用的小容量有界 LRU 缓存装饰器（P1 #8）。
 *
 * ## 为什么与 [CachingThumbnailSource] 分开
 *
 * 网格缩略图会被 [CachingThumbnailSource] 按 `minOf(w, h, 512)` 裁成方形 512px 缓存；
 * 全屏预览则按实际显示尺寸的 2×（非方形，长边最高 4096px）解码。
 * 这里保留调用方请求的精确 `(widthPx, heightPx)` 并按 `"$uri@${widthPx}x$heightPx"` 建键，
 * 以 3 张条目 + 96 MiB 双上界限制峰值内存，让相邻页来回翻阅时直接命中内存而免去重复 SAF 解码。
 */
class PreviewImageCacheSource(
    private val delegate: ThumbnailSource,
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    maxBytes: Long = DEFAULT_MAX_BYTES,
) : ThumbnailSource {

    private val cache = ThumbnailCache(maxBytes = maxBytes, maxEntries = maxEntries)

    /** 当前缓存条目数（测试与调试用）。 */
    val cachedCount: Int get() = cache.size

    /** 当前缓存占用字节数（测试与调试用）。 */
    val cachedBytes: Long get() = cache.sizeBytes

    override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val key = cacheKey(uri, w, h)
        cache.get(key)?.let { return it }
        return delegate.thumbnail(uri, w, h)?.also { bitmap ->
            cache.put(key, bitmap)
        }
    }

    /** 清空缓存（切换工作区或关闭会话时调用）。 */
    fun clearCache() = cache.clear()

    private fun cacheKey(uri: String, widthPx: Int, heightPx: Int): String =
        "$uri@${widthPx}x$heightPx"

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 3
        const val DEFAULT_MAX_BYTES: Long = 96L * 1024L * 1024L
    }
}
