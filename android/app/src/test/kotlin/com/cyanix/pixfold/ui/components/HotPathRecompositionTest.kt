package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.ui.preview.ImagePreview
import com.cyanix.pixfold.ui.preview.LocalPreviewRecompositionObserver
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_IMAGE
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_PAGE_LABEL
import com.cyanix.pixfold.ui.preview.zoomAmount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1 #8：`ImagePreview` 与 `DragReorderGrid` / `DragGhost` draw-phase `graphicsLayer { ... }` 重组守卫测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h800dp")
class HotPathRecompositionTest {

    @get:Rule
    val rule = createComposeRule()

    private fun items(n: Int): List<SourceItem> = (1..n).map {
        SourceItem(
            id = "i$it",
            collectionId = "c",
            dir = "",
            baseName = "img$it",
            ext = "jpg",
            sizeBytes = 1024L * it,
            modifiedEpochMillis = 0L,
            createdEpochMillis = null,
            seed = it,
        )
    }

    @Test
    fun `ImagePreview double-tap zoom and pan do not recompose ImagePreview`() {
        val outerRecompositions = AtomicInteger(0)
        val contentRecompositions = AtomicInteger(0)
        rule.setContent {
            CompositionLocalProvider(
                LocalPreviewRecompositionObserver provides { scope ->
                    when (scope) {
                        "ImagePreview" -> outerRecompositions.incrementAndGet()
                        "ImagePreviewContent" -> contentRecompositions.incrementAndGet()
                    }
                },
            ) {
                ImagePreview(
                    items = items(3),
                    initialIndex = 0,
                    onClose = {},
                )
            }
        }
        rule.waitForIdle()
        val baselineOuter = outerRecompositions.get()
        val baselineContent = contentRecompositions.get()
        assertTrue(baselineOuter >= 1 && baselineContent >= 1, "应已观测到内外两层初始组合")

        // 双击放大到 2×
        rule.onNodeWithTag(TAG_PREVIEW_IMAGE).performTouchInput { doubleClick() }
        rule.waitForIdle()

        val afterZoomCd = rule.onNodeWithTag(TAG_PREVIEW_IMAGE)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(
            TestText.of(R.string.preview_zoom_cd, zoomAmount(2f)),
            afterZoomCd,
            "双击缩放仍须正确更新 semantics 的 stateDescription",
        )

        // 放大态下拖动平移
        rule.onNodeWithTag(TAG_PREVIEW_IMAGE).performTouchInput {
            down(center)
            moveBy(Offset(40f, 30f))
            moveBy(Offset(35f, -20f))
            up()
        }
        rule.waitForIdle()

        assertEquals(
            baselineOuter,
            outerRecompositions.get(),
            "draw-phase graphicsLayer 缩放与平移不得触发 ImagePreview 外层重组（基线=$baselineOuter，实测=${outerRecompositions.get()}）",
        )
        assertEquals(
            baselineContent,
            contentRecompositions.get(),
            "draw-phase graphicsLayer 缩放与平移不得触发 BoxWithConstraints 内容域重组（基线=$baselineContent，实测=${contentRecompositions.get()}）",
        )
    }

    @Test
    fun `ImagePreview updates total page count when items size changes`() {
        var list by mutableStateOf(items(5))
        rule.setContent {
            ImagePreview(items = list, initialIndex = 0, onClose = {})
        }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals(
            TestText.of(R.string.preview_page_label, 1, 5),
        )

        list = items(2)
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals(
            TestText.of(R.string.preview_page_label, 1, 2),
        )
    }

    @Test
    fun `DragGhost follows pointer movement via draw-phase graphicsLayer without recomposing`() {
        val ghostRecompositions = AtomicInteger(0)
        val list = items(9)

        rule.setContent {
            CompositionLocalProvider(
                LocalDragGhostRecompositionObserver provides { ghostRecompositions.incrementAndGet() },
            ) {
                DragReorderGrid(
                    items = list,
                    onMoveTo = { _, _ -> },
                    onPin = {},
                    isPinned = { false },
                    onClick = {},
                    modifier = Modifier.size(360.dp, 700.dp),
                )
            }
        }
        rule.waitForIdle()

        val node = rule.onNodeWithText("img1.jpg").fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val start = node.boundsInRoot.center - origin

        // 长按起拖并微移触发浮层出现
        rule.onNodeWithText("img1.jpg").performTouchInput {
            down(start)
            advanceEventTime(700)
            moveBy(Offset(2f, 2f))
        }
        rule.waitForIdle()

        val beforeBounds = rule.onNodeWithTag(TAG_DRAG_GHOST).fetchSemanticsNode().boundsInRoot
        val baselineGhost = ghostRecompositions.get()
        assertTrue(baselineGhost >= 1, "起拖后 DragGhost 应已组合至少一次")

        // 在同一格内连续移动指针（不触发让位重排），验证浮层位置更新且零额外重组
        rule.onNodeWithText("img1.jpg").performTouchInput {
            moveBy(Offset(10f, 8f))
            moveBy(Offset(12f, 10f))
            moveBy(Offset(8f, 6f))
        }
        rule.waitForIdle()

        val afterBounds = rule.onNodeWithTag(TAG_DRAG_GHOST).fetchSemanticsNode().boundsInRoot
        assertTrue(
            afterBounds.left > beforeBounds.left + 15f && afterBounds.top > beforeBounds.top + 10f,
            "浮层应随指针平移（before=$beforeBounds, after=$afterBounds）",
        )
        assertEquals(
            baselineGhost,
            ghostRecompositions.get(),
            "指针移动期间 DragGhost 应通过 draw-phase graphicsLayer 平移，不得每帧重组（基线=$baselineGhost，实测=${ghostRecompositions.get()}）",
        )
    }
}
