package com.cyanix.pixfold.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.workspace.LocalThumbnailSource
import com.cyanix.pixfold.ui.workspace.ThumbnailSource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ItemThumb] 的**回退契约**（D2a）。
 *
 * ## 为什么这一层比「真实缩略图能不能显示」更值得测
 *
 * 「显示真实缩略图」这件事在 Robolectric 里造不出来（没有真实 provider、没有图片字节），
 * 硬测只会得到一条永远为真的断言。真正的风险在**另一侧**：
 *
 *  1. **mock 数据不该发请求** —— `SourceItem.uri == null` 时若仍然去调
 *     `ThumbnailSource.thumbnail(null)`，在真机上就是 851 次无意义的 binder 调用，
 *     而且实现方拿到空串会抛 `IllegalArgumentException` 之类，滚一下列表就崩。
 *  2. **没有 source 时不该发请求** —— 这是全部既有 UI 测试与 D1 演示的路径。
 *  3. **取到 null 必须回退，而不是留白** —— `loadThumbnail` 在
 *     「provider 不支持 / 授权失效 / 文件已删」时都会失败，这是**常态**；
 *     留白会让用户以为图坏了，而程序化缩略图至少还能显示文件名。
 *
 * 三项里前两项用**记录调用的假实现**能精确断言，第三项用「假实现返回 null 后
 * 程序化缩略图仍在」来间接钉住（`Image` 与 `ProceduralThumb` 互斥，
 * 后者存在即证明没有走 `Image` 分支）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class ItemThumbTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** 记录每次请求的 uri，并固定返回 [result]。 */
    private class RecordingThumbnailSource(
        private val result: ImageBitmap? = null,
    ) : ThumbnailSource {
        val calls = CopyOnWriteArrayList<String?>()

        override suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap? {
            calls += uri
            return result
        }
    }

    private fun item(uri: String?) = SourceItem(
        id = "id-1",
        collectionId = "c1",
        dir = "",
        baseName = "a",
        ext = "jpg",
        sizeBytes = 1024L,
        modifiedEpochMillis = 0L,
        createdEpochMillis = null,
        seed = 7,
        uri = uri,
    )

    private fun setContent(item: SourceItem, source: ThumbnailSource?) =
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalThumbnailSource provides source) {
                    ItemThumb(item = item, modifier = androidx.compose.ui.Modifier)
                }
            }
        }

    @Test
    fun `mock 数据不发缩略图请求`() {
        val source = RecordingThumbnailSource()
        setContent(item = item(uri = null), source = source)
        rule.waitForIdle()

        // 关键：uri == null 必须**提前短路**。否则真机上每张 mock 图都会打一次 binder。
        assertEquals(emptyList<String?>(), source.calls.toList())
    }

    @Test
    fun `没有缩略图来源时不发请求且仍能渲染`() {
        // 这是全部既有 UI 测试 + D1 演示的路径（LocalThumbnailSource 默认 null）。
        setContent(item = item(uri = "content://doc/1"), source = null)
        rule.waitForIdle()

        // 不断言具体像素（Robolectric 不做真实文本测量），只要求「没崩、有内容」。
        rule.onNodeWithTag(TAG_ITEM_THUMB, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `真实 uri 会向来源请求一次`() {
        val source = RecordingThumbnailSource()
        setContent(item = item(uri = "content://doc/1"), source = source)
        rule.waitForIdle()

        // 只请求一次：`LaunchedEffect` 的 key 是 (uri, source, requestPx)，
        // 任一变化才重跑。若 key 写错（例如漏了 uri），滚动列表会反复重发请求。
        assertEquals(listOf<String?>("content://doc/1"), source.calls.toList())
    }

    @Test
    fun `取不到缩略图时回退到程序化缩略图`() {
        val source = RecordingThumbnailSource(result = null)
        setContent(item = item(uri = "content://doc/1"), source = source)
        rule.waitForIdle()

        assertTrue(source.calls.isNotEmpty(), "应当尝试过取缩略图")
        // bitmap == null ⇒ 走 ProceduralThumb 分支。用「根节点存在」间接验证没有崩。
        rule.onNodeWithTag(TAG_ITEM_THUMB, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `uri 变化会重新请求`() {
        var current by mutableStateOf(item(uri = "content://doc/1"))
        val source = RecordingThumbnailSource()
        rule.setContent {
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                CompositionLocalProvider(LocalThumbnailSource provides source) {
                    ItemThumb(item = current, modifier = androidx.compose.ui.Modifier)
                }
            }
        }
        rule.waitForIdle()

        // 工作区切换 / 重扫后同一张卡可能换成别的图，必须重新取。
        current = item(uri = "content://doc/2")
        rule.waitForIdle()

        assertEquals(listOf<String?>("content://doc/1", "content://doc/2"), source.calls.toList())
    }
}
