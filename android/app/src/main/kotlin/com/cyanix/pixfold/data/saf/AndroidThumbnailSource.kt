package com.cyanix.pixfold.data.saf

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import com.cyanix.pixfold.ui.workspace.ThumbnailSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 用 `ContentResolver.loadThumbnail` 取真实缩略图（D2a）。
 *
 * ## 为什么用 `loadThumbnail` 而不是 `BitmapFactory.decodeStream`
 *
 * `loadThumbnail(uri, Size, signal)`（API 29+）把**降采样交给 provider**：
 * 系统相册的缩略图本来就是预生成的，一次 binder 调用拿到小图；
 * 而 `decodeStream` 会把整张原图读进内存再缩 —— 851 张 3.4MB 的 JPEG
 * 就是 2.9GB 的瞬时分配，滚一下列表就能 OOM。
 *
 * `minSdk = 34`（2026-09-25 用户裁决），故**不需要** API 29 的版本守卫，
 * 也就不会触发 lint 的 `ObsoleteSdkInt`。
 *
 * ## 失败即回退，不抛
 *
 * 以下都是**正常**情况，一律返回 `null` 让 UI 回退到程序化缩略图：
 * - provider 不支持缩略图（部分第三方 DocumentsProvider 直接抛 `UnsupportedOperationException`）
 * - 授权失效（`SecurityException`）
 * - 文件已删 / 已移走（`FileNotFoundException`）
 * - 图片损坏、格式不认识（`IOException` / `OutOfMemoryError` 的常见来源）
 *
 * 把异常吞在这里**不是**掩盖问题：缩略图是**展示层**，取不到就该显示占位，
 * 而不是让整个列表崩掉（HANGOFF §9 D2b 退出条件 ③ 的精神：授权失效要能恢复）。
 *
 * ## 线程
 *
 * [thumbnail] 是 `suspend` 且内部切到 [Dispatchers.IO] —— binder 调用会阻塞，
 * 不能留在组合线程上。`ImageBitmap` 构造本身是轻量的（`asImageBitmap` 只做包装）。
 *
 * ## 职责边界：本类**只管读**
 *
 * 缓存与并发闸门**不在这里**，在 [CachingThumbnailSource] 装饰器里。
 * 这样分开的理由是**可测性**：缓存账目（LRU 淘汰顺序、字节预算、命中不重读）
 * 全是纯逻辑，不该为了测它去 mock `ContentResolver.loadThumbnail`；
 * 反过来本类只有「调一次 binder、失败返回 null」这一件事，没有可单测的逻辑。
 */
class AndroidThumbnailSource(context: Context) : ThumbnailSource {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            try {
                // Size 的两个参数必须 > 0，否则 provider 直接抛 IllegalArgumentException。
                val size = Size(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1))
                val bitmap: Bitmap = resolver.loadThumbnail(uri.toUri(), size, null)
                bitmap.asImageBitmap()
            } catch (e: SecurityException) {
                Log.w(TAG, "缩略图:授权失效 $uri", e)
                null
            } catch (e: Exception) {
                // UnsupportedOperationException / FileNotFoundException / IOException 全在这里。
                // 不区分：对界面而言都是「没有缩略图可用」。
                Log.d(TAG, "缩略图:取不到 $uri (${e::class.java.simpleName})")
                null
            } catch (e: OutOfMemoryError) {
                // OOM 是 Error 不是 Exception，必须单独接 —— 否则一张巨图就能拖垮整个列表。
                // 这里已经请求了降采样，走到这一步说明 provider 没照做，放弃这张。
                Log.w(TAG, "缩略图:内存不足 $uri")
                null
            }
        }

    private companion object {
        const val TAG = "AndroidThumbnailSource"
    }
}
