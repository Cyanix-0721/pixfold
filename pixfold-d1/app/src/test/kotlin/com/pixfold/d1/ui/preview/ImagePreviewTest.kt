package com.pixfold.d1.ui.preview

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import com.pixfold.d1.domain.model.SourceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 验收第 2 项:大图预览——自适应窗口、可缩放、可拖动平移、可翻页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImagePreviewTest {

    @get:Rule
    val rule = createComposeRule()

    private val items: List<SourceItem> = (1..3).map {
        SourceItem(
            id = "p$it", collectionId = "c", dir = "", baseName = "img$it", ext = "jpg",
            sizeBytes = 1024L * it, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = it,
        )
    }

    @Test
    fun `shows one based page label on open`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals("第 1 / 3 页")
    }

    @Test
    fun `next button actually updates the visible label`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).performClick()
        // 关键:断言**界面文本真的变了** —— 只断言内部 index 会放过"数据对、界面不动"
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals("第 2 / 3 页")
    }

    @Test
    fun `previous button actually updates the visible label`() {
        rule.setContent { ImagePreview(items, initialIndex = 2, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals("第 3 / 3 页")
        rule.onNodeWithTag(TAG_PREVIEW_PREV).performClick()
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals("第 2 / 3 页")
    }

    @Test
    fun `prev disabled on first page and next disabled on last page`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_PREV).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).assertIsEnabled()
    }

    @Test
    fun `next disabled on last page`() {
        rule.setContent { ImagePreview(items, initialIndex = 2, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).assertIsNotEnabled()
        rule.onNodeWithTag(TAG_PREVIEW_PREV).assertIsEnabled()
    }

    @Test
    fun `close button invokes callback`() {
        var closed = false
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = { closed = true }) }
        rule.onNodeWithTag(TAG_PREVIEW_CLOSE).performClick()
        assertTrue(closed, "关闭按钮应回调")
    }

    @Test
    fun `double tap zooms in and updates reported state`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }

        val before = rule.onNodeWithTag(TAG_PREVIEW_IMAGE)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(zoomDescription(MIN_SCALE), before, "初始应为未缩放")

        rule.onNodeWithTag(TAG_PREVIEW_IMAGE).performTouchInput { doubleClick() }

        val after = rule.onNodeWithTag(TAG_PREVIEW_IMAGE)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(zoomDescription(2f), after, "双击应放大到 2 倍")
    }

    @Test
    fun `switching page resets zoom`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_IMAGE).performTouchInput { doubleClick() }
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).performClick()

        val after = rule.onNodeWithTag(TAG_PREVIEW_IMAGE)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(zoomDescription(MIN_SCALE), after, "翻页后应复位缩放")
    }
}
