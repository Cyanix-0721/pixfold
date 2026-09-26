package com.pixfold.d1.ui.preview

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R
import com.pixfold.d1.TestText
import com.pixfold.d1.domain.model.SourceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 验收第 2 项:大图预览——自适应窗口、可缩放、可拖动平移、可翻页。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImagePreviewTest {

    @get:Rule
    val rule = createComposeRule()

    /** 期望值走 `R.string` 解析,而不是手写中文字面量(换语言时本文件不该红)。 */
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun pageLabel(index: Int, count: Int) = context.getString(R.string.preview_page_label, index, count)

    private fun zoomCd(scale: Float) = context.getString(R.string.preview_zoom_cd, zoomAmount(scale))

    private val items: List<SourceItem> = (1..3).map {
        SourceItem(
            id = "p$it", collectionId = "c", dir = "", baseName = "img$it", ext = "jpg",
            sizeBytes = 1024L * it, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = it,
        )
    }

    /**
     * 断言输入框的**可编辑文本**。
     *
     * 不能用 `assertTextEquals`:它比对 `Text + EditableText`,而 `OutlinedTextField` 的
     * **label**("新文件名")也算 `Text`,断言会永远失败(命名页测试实测踩过)。
     * 这里直接读 `SemanticsProperties.EditableText`,只比对真正的值。
     */
    private fun assertField(tag: String, expected: String) {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val actual = node.config[SemanticsProperties.EditableText].text
        assertEquals(expected, actual, "输入框内容不符")
    }

    @Test
    fun `shows one based page label on open`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals(pageLabel(1, 3))
    }

    @Test
    fun `next button actually updates the visible label`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).performClick()
        // 关键:断言**界面文本真的变了** —— 只断言内部 index 会放过"数据对、界面不动"
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals(pageLabel(2, 3))
    }

    @Test
    fun `previous button actually updates the visible label`() {
        rule.setContent { ImagePreview(items, initialIndex = 2, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals(pageLabel(3, 3))
        rule.onNodeWithTag(TAG_PREVIEW_PREV).performClick()
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals(pageLabel(2, 3))
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
        assertEquals(zoomCd(MIN_SCALE), before, "初始应为未缩放")

        rule.onNodeWithTag(TAG_PREVIEW_IMAGE).performTouchInput { doubleClick() }

        val after = rule.onNodeWithTag(TAG_PREVIEW_IMAGE)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(zoomCd(2f), after, "双击应放大到 2 倍")
    }

    @Test
    fun `switching page resets zoom`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }
        rule.onNodeWithTag(TAG_PREVIEW_IMAGE).performTouchInput { doubleClick() }
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).performClick()

        val after = rule.onNodeWithTag(TAG_PREVIEW_IMAGE)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals(zoomCd(MIN_SCALE), after, "翻页后应复位缩放")
    }

    // ================= 用户 2026-09-25 裁决:预览页加改名入口 =================
    //
    // 裁决原文:「那就不做菜单，点开图片预览的是否加个重命名按钮吧」——
    // 即放弃"图钉/重命名收进溢出菜单",改为在预览页给一个直接的改名入口。

    /** 不传 `rename` 时本页**不该**出现入口(它是可选接线,不是本页固有功能)。 */
    @Test
    fun `rename entry is absent when rename wiring is not provided`() {
        rule.setContent { ImagePreview(items, initialIndex = 0, onClose = {}) }
        rule.onAllNodesWithTag(TAG_PREVIEW_RENAME).assertCountEquals(0)
    }

    @Test
    fun `rename entry opens a dialog prefilled with the current final name`() {
        val show = { _: SourceItem -> "当前最终名.jpg" }
        rule.setContent {
            ImagePreview(
                items,
                initialIndex = 0,
                onClose = {},
                rename = PreviewRename(emptyMap(), show) { _, _ -> },
            )
        }
        rule.onNodeWithTag(TAG_PREVIEW_RENAME).performClick()
        // 初值必须是**当前最终名**:用户点改名是想在已有结果上微调,不是从零重打。
        assertField(TAG_PREVIEW_RENAME_FIELD, "当前最终名.jpg")
    }

    @Test
    fun `confirming the dialog reports the edited name for the visible image`() {
        var renamed: Pair<String, String>? = null
        rule.setContent {
            ImagePreview(
                items,
                initialIndex = 1,
                onClose = {},
                rename = PreviewRename(emptyMap(), { it.fileName }) { item, name ->
                    renamed = item.id to name
                },
            )
        }
        rule.onNodeWithTag(TAG_PREVIEW_RENAME).performClick()
        rule.onNodeWithTag(TAG_PREVIEW_RENAME_FIELD).performTextReplacement("改过的名字.jpg")
        rule.onNodeWithTag(TAG_PREVIEW_RENAME_SAVE).performClick()

        // 必须是**当前显示的那一张**(initialIndex = 1 ⇒ p2),不是首张。
        assertEquals("p2" to "改过的名字.jpg", renamed, "应回传当前图的 id 与新名字")
        // 保存后对话框关闭 —— 否则用户会以为没生效。
        rule.onAllNodesWithTag(TAG_PREVIEW_RENAME_FIELD).assertCountEquals(0)
    }

    @Test
    fun `cancel discards the edit`() {
        var renamed: Pair<String, String>? = null
        rule.setContent {
            ImagePreview(
                items,
                initialIndex = 0,
                onClose = {},
                rename = PreviewRename(emptyMap(), { it.fileName }) { item, name ->
                    renamed = item.id to name
                },
            )
        }
        rule.onNodeWithTag(TAG_PREVIEW_RENAME).performClick()
        rule.onNodeWithTag(TAG_PREVIEW_RENAME_FIELD).performTextReplacement("不该生效.jpg")
        rule.onNodeWithTag(TAG_PREVIEW_RENAME_CANCEL).performClick()

        assertNull(renamed, "取消不应写回")
        rule.onAllNodesWithTag(TAG_PREVIEW_RENAME_FIELD).assertCountEquals(0)
    }

    /**
     * 有覆盖时必须显示反馈行。
     *
     * 这一条钉的是"改完能看见":D1 不写真实文件,若保存后画面毫无变化,
     * 用户无从确认是否生效 —— 界面上也就没有任何可断言的东西。
     */
    @Test
    fun `an overridden image shows the pending rename notice`() {
        val shown = items[1]
        rule.setContent {
            ImagePreview(
                items,
                initialIndex = 1,
                onClose = {},
                rename = PreviewRename(
                    overrides = mapOf(shown.id to "新名字.jpg"),
                    finalNameOf = { if (it.id == shown.id) "新名字.jpg" else it.fileName },
                    onRename = { _, _ -> },
                ),
            )
        }
        rule.onNodeWithTag(TAG_PREVIEW_RENAMED)
            .assertTextEquals(TestText.of(R.string.preview_renamed, "新名字.jpg"))
    }

    /** 未覆盖的图不该出现反馈行 —— 否则"哪些改过"就看不出来了。 */
    @Test
    fun `an untouched image shows no rename notice`() {
        rule.setContent {
            ImagePreview(
                items,
                initialIndex = 0,
                onClose = {},
                rename = PreviewRename(emptyMap(), { it.fileName }) { _, _ -> },
            )
        }
        rule.onAllNodesWithTag(TAG_PREVIEW_RENAMED).assertCountEquals(0)
    }

    /**
     * 顶部两个按钮的触摸目标 ≥ 48dp(设计硬指标,规格 §10 / `HANGOFF.md` 验收走查)。
     *
     * ⚠️ 这条**曾经是空洞测试**:原先两个按钮带 `Modifier.heightIn(min = 48.dp)`,
     * 注释写「M3 `TextButton` 默认最小高度只有 40dp,不达标」。变异实测推翻了该说法 ——
     * 把 `heightIn` 删掉后本测试依然全绿。探针实测(裸 `TextButton` / 加 `heightIn` 的
     * `TextButton` 均为 52px,只有显式 `Modifier.size(height = 20.dp)` 才压到 20px)证明:
     * 裸 `TextButton` 的高度由 M3 的 `minimumInteractiveComponentSize` 抬到 48dp+,
     * `heightIn(min = 48.dp)` 加不加都一样 ⇒ 修饰符冗余,已删除。
     *
     * 现在这条守的是**真正会打破指标的那种改动**:一旦有人给按钮加上显式小尺寸
     * (`Modifier.size` / `height`),M3 的最小交互尺寸便不再生效,本断言就会红。
     */
    @Test
    fun `top bar buttons keep a 48dp minimum touch target`() {
        rule.setContent {
            ImagePreview(
                items,
                initialIndex = 0,
                onClose = {},
                rename = PreviewRename(emptyMap(), { it.fileName }) { _, _ -> },
            )
        }
        val minPx = with(rule.density) { 48.dp.roundToPx() }
        for (tag in listOf(TAG_PREVIEW_RENAME, TAG_PREVIEW_CLOSE)) {
            val h = rule.onNodeWithTag(tag).fetchSemanticsNode().size.height
            assertTrue(h >= minPx, "$tag 触控高度应 ≥ 48dp,实际 ${h}px(下限 ${minPx}px)")
        }
    }

    /** 翻页后反馈行要跟着换成"这一张"的状态。 */
    @Test
    fun `the notice follows the current page`() {
        val second = items[1]
        rule.setContent {
            ImagePreview(
                items,
                initialIndex = 0,
                onClose = {},
                rename = PreviewRename(
                    overrides = mapOf(second.id to "新名字.jpg"),
                    finalNameOf = { if (it.id == second.id) "新名字.jpg" else it.fileName },
                    onRename = { _, _ -> },
                ),
            )
        }
        // 首张未覆盖 ⇒ 无提示
        rule.onAllNodesWithTag(TAG_PREVIEW_RENAMED).assertCountEquals(0)
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).performClick()
        rule.onNodeWithTag(TAG_PREVIEW_RENAMED)
            .assertTextEquals(TestText.of(R.string.preview_renamed, "新名字.jpg"))
    }
}
