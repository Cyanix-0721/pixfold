package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.ui.workspace.LocalThumbnailSource
import com.cyanix.pixfold.ui.workspace.ThumbnailSource

/**
 * [ItemThumb] 的根节点标签。
 *
 * 用 UnmergedTree 访问：真实图（`Image`）与程序化缩略图（Canvas）是**互斥**的两个分支，
 * 测试需据「哪个分支在」判断走了哪条路。根节点本身始终存在，正好做这个锚点。
 */
const val TAG_ITEM_THUMB = "item-thumb"

/**
 * 一张来源图的缩略图（D2a）：有真实缩略图就画它，否则回退到 D1 的程序化缩略图。
 *
 * ## 为什么做成「回退」而不是「替换」
 *
 * 真实缩略图**可能取不到**，而且都是正常情况：provider 不支持
 * `loadThumbnail`、文件刚被删、授权失效、格式不认识。此时界面不该是空白格子 ——
 * D1 的 [ProceduralThumb] 仍然能画出「文件名 + 确定性配色」，用户至少知道那是哪一张。
 *
 * 因此默认行为（`LocalThumbnailSource == null`，即全部既有 UI 测试环境）与 D1 完全一致。
 *
 * ## 加载期间显示什么
 *
 * 先画程序化缩略图，真实图到了再换上去。这样不会出现「先空白、后闪出图片」的跳动，
 * 而且扫描 851 张图时滚动列表始终有内容（`loadThumbnail` 每张都要走一次 binder）。
 *
 * ## 线程
 *
 * [ThumbnailSource.thumbnail] 是 `suspend`，实现方自己切 `Dispatchers.IO`；
 * 本组件在组合线程上 `await`，不阻塞 UI。
 *
 * @param item 来源项。`item.uri` 为 `null`（mock 数据）时直接用程序化缩略图，
 *   连一次 suspend 调用都不发。
 * @param requestPx 向平台请求的缩略图宽度（px）。网格卡约 180dp × 3x ≈ 540px，
 *   故默认 512 够用；列表行 44dp 可传更小的值省内存。
 * @param requestHeightPx 向平台请求的缩略图高度（px），默认等于 [requestPx]。
 *   全屏预览是竖长区域，传实际宽高可避免按方形边长解码造成的浪费。
 * @param contentScale 图片填充方式。网格用默认 [ContentScale.Crop]；
 *   全屏预览应传 [ContentScale.Fit]，保证整张图可见、不裁边。
 */
@Composable
fun ItemThumb(
    item: SourceItem,
    modifier: Modifier = Modifier,
    showIndex: Boolean = true,
    captionMaxChars: Int = 22,
    requestPx: Int = 512,
    requestHeightPx: Int = requestPx,
    source: ThumbnailSource? = LocalThumbnailSource.current,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val uri = item.uri
    var bitmap by remember(uri, source, requestPx, requestHeightPx) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(uri, source, requestPx, requestHeightPx) {
        // mock 数据（uri == null）与测试环境（source == null）都不发请求。
        bitmap = if (uri != null && source != null) {
            source.thumbnail(uri, requestPx, requestHeightPx)
        } else {
            null
        }
    }

    Box(modifier = modifier.testTag(TAG_ITEM_THUMB)) {
        val loaded = bitmap
        if (loaded != null) {
            Image(
                bitmap = loaded,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ProceduralThumb(
                seed = item.seed,
                fileName = item.fileName,
                showIndex = showIndex,
                captionMaxChars = captionMaxChars,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
