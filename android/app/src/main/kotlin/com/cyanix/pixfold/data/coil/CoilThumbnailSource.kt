package com.cyanix.pixfold.data.coil

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.Image
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.toBitmap
import com.cyanix.pixfold.ui.workspace.ThumbnailSource
import kotlinx.coroutines.CancellationException

/**
 * 用 Coil 3 解码缩略图（阶段二 Coil 迁移）。
 *
 * ## 分工：Coil 只管「取 + 解码」，缓存与并发在外层
 *
 * [CoilThumbnailSource] 只把一次 [CoilImageFetcher.fetch] 的 Image 包成
 * [ImageBitmap]；LRU 缓存、并发闸门、请求尺寸收敛仍由
 * [com.cyanix.pixfold.ui.workspace.CachingThumbnailSource] 装饰器负责。
 * 这样和原 [com.cyanix.pixfold.data.saf.AndroidThumbnailSource] 的职责边界完全一致，
 * 只是把「调 ContentResolver.loadThumbnail」换成「走 Coil 解码管线」。
 *
 * ## 为什么要 CoilImageFetcher 这层抽象
 *
 * 真实解码依赖 [ImageLoader]，在 Robolectric 单测里起不来、也不好造假；
 * 抽出 [CoilImageFetcher] 后，null 透传 / Image 到 ImageBitmap 包装 / 异常吞成 null /
 * 尺寸钳制 / 取消透传这些「纯逻辑」都能用假 fetcher 精确断言。
 * 真正调 ImageLoader 的那一层（[ImageLoaderCoilImageFetcher]）是平台边界，与
 * AndroidThumbnailSource 一样不单测。
 */
fun interface CoilImageFetcher {
    /** 取 [data]（通常是 content:// 或 file:// URI）的图，失败返回 null。 */
    suspend fun fetch(data: String, widthPx: Int, heightPx: Int): Image?
}

class CoilThumbnailSource(
    private val fetcher: CoilImageFetcher,
) : ThumbnailSource {

    override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? {
        // 与 AndroidThumbnailSource 一致：尺寸必须 >= 1（Coil 的 size 也要求正数）。
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        return try {
            fetcher.fetch(uri, w, h)?.toBitmap()?.asImageBitmap()
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出，不能当成「取不到」吞掉 —— 否则取消信号被吃掉，
            // 上层以为加载完成，滚动/切页的清理逻辑会错乱。
            throw e
        } catch (e: Exception) {
            // 解码失败 / 授权失效 / 文件已删：一律回退到程序化缩略图（正常业务分支）。
            null
        } catch (e: OutOfMemoryError) {
            // OOM 是 Error 不是 Exception，单独接住；一张巨图不该拖垮整个列表。
            null
        }
    }
}

/**
 * [CoilImageFetcher] 的真实实现：走 [ImageLoader.execute]。
 *
 * **两级缓存都关掉**：本项目的 LRU 预算在 [CachingThumbnailSource] 里，
 * 若再让 Coil 自建 memoryCache/diskCache，同一张缩略图会存两份（内存翻倍），
 * diskCache 还会把已经在 SAF 盘上的图片字节再复制一份到 cacheDir（纯浪费）。
 * 所以这里 policy 一律 DISABLED，Coil 只做「取 + 解码」这一件事。
 */
class ImageLoaderCoilImageFetcher(
    private val context: Context,
    private val imageLoader: ImageLoader,
) : CoilImageFetcher {

    override suspend fun fetch(data: String, widthPx: Int, heightPx: Int): Image? {
        val request = ImageRequest.Builder(context)
            .data(data)
            .size(widthPx, heightPx)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        return (imageLoader.execute(request) as? SuccessResult)?.image
    }
}
