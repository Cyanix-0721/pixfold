package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.workspace.CachingThumbnailSource
import com.cyanix.pixfold.ui.workspace.LocalThumbnailSource
import com.cyanix.pixfold.ui.workspace.ThumbnailSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * P1 #8：`ItemThumb` 首帧同步 `peek()` 命中零闪烁测试。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class ItemThumbPeekTest {

    @get:Rule
    val rule = createComposeRule()

    private fun item(uri: String) = SourceItem(
        id = "id-1",
        collectionId = "c1",
        dir = "",
        baseName = "page1",
        ext = "jpg",
        sizeBytes = 1024L,
        modifiedEpochMillis = 0L,
        createdEpochMillis = null,
        seed = 3,
        uri = uri,
    )

    @Test
    fun `ItemThumb renders cached bitmap on frame 0 without ProceduralThumb flash or async load`() {
        val uri = "content://doc/cached"
        val cachedBitmap = ImageBitmap(4, 4)
        val suspendGate = CompletableDeferred<ImageBitmap?>()
        val asyncCalls = AtomicInteger(0)

        // 先通过真实的 CachingThumbnailSource 预热缓存，随后把 delegate 替换/阻断逻辑
        // 放在可切换委托里，确保首帧渲染完全依赖同步 peek()，绝不靠异步 thumbnail() 兜底。
        var blockDelegate = false
        val delegate = ThumbnailSource { _, _, _ ->
            if (blockDelegate) {
                asyncCalls.incrementAndGet()
                suspendGate.await()
            } else {
                cachedBitmap
            }
        }
        val cachingSource = CachingThumbnailSource(delegate)
        runBlocking { cachingSource.thumbnail(uri, 512, 512) }
        blockDelegate = true

        // 即使停住异步加载（挂起不返回），首帧也必须直接渲染 Image、不闪 ProceduralThumb。
        rule.mainClock.autoAdvance = false
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalThumbnailSource provides cachingSource) {
                    ItemThumb(
                        item = item(uri),
                        modifier = Modifier.size(100.dp),
                    )
                }
            }
        }

        rule.onNodeWithTag(TAG_ITEM_THUMB_IMAGE, useUnmergedTree = true).assertIsDisplayed()
        rule.onAllNodesWithTag(TAG_ITEM_THUMB_PLACEHOLDER, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(0, asyncCalls.get(), "缓存已同步命中时不应再发起异步加载")
    }

    @Test
    fun `ItemThumb with custom requestHeightPx skips square thumbnail peek and loads requested size`() {
        val uri = "content://doc/preview"
        val squareThumb = ImageBitmap(4, 4)
        val fullPreview = ImageBitmap(8, 16)
        val requestedSizes = mutableListOf<Pair<Int, Int>>()

        val source = object : ThumbnailSource {
            override fun peek(uri: String): ImageBitmap = squareThumb
            override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap {
                requestedSizes += widthPx to heightPx
                return fullPreview
            }
        }

        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                ItemThumb(
                    item = item(uri),
                    requestPx = 800,
                    requestHeightPx = 1600,
                    source = source,
                    modifier = Modifier.size(100.dp),
                )
            }
        }
        rule.waitForIdle()

        assertEquals(
            listOf(800 to 1600),
            requestedSizes,
            "非方形预览请求不得误用方形缩略图 peek() 跳过全尺寸加载",
        )
    }
}
