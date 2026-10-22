package com.cyanix.pixfold

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.ui.components.TAG_DIR_META
import com.cyanix.pixfold.ui.components.TAG_DIR_META_CONFIRM
import com.cyanix.pixfold.ui.components.TAG_DIR_META_DIALOG
import com.cyanix.pixfold.ui.components.TAG_RESET_ALL
import com.cyanix.pixfold.ui.library.TAG_B_STEP_METADATA
import com.cyanix.pixfold.ui.library.TAG_B_STEP_TABS
import com.cyanix.pixfold.ui.library.TAG_METADATA_LIST
import com.cyanix.pixfold.ui.library.LibrarySteps
import com.cyanix.pixfold.ui.library.metaFieldTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「按目录结构获取」按钮的守护(用户 2026-10-22 裁决)。
 *
 * ## 需求(用户逐字裁决)
 *
 * 「右上角重置旁边加个根据目录结构自动获取填写到输入框的按钮」—— 它在**页签行右侧**、
 * 紧挨「重置全部调整」,作用于**当前卷全部字段**(不只作者)。
 *
 * ## 为什么必须组合 `LibrarySteps`
 *
 * 按钮长在页签行上、改的是元数据页的字段:只有把两层放在同一棵树上,
 * 「点页签行的按钮 ⇒ 元数据输入框变了」这条链路才算被测到。单测 `MetadataPage`
 * 看不到这个按钮(它不在那一页里)。
 *
 * ## 断言为什么有杀伤力
 *
 * mock 的 `dirWriterSug` 是**去方括号**形态,与当前值 `writer`(带 `[]` 的
 * `writerSug`)不同 ⇒ 把 `applyDirMetaToActiveVolume()` 换成空实现,
 * 第三条用例立即失败(字段没变)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class DirMetaButtonTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val library = MockDataB.library

    private fun show() {
        rule.setContent {
            LibrarySteps(rootPath = library.rootPath, volumes = library.volumes)
        }
    }

    private fun fieldText(field: MetaField): String =
        rule.onNodeWithTag(metaFieldTag(field)).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    private fun goToMetadata() {
        rule.onNodeWithTag(TAG_B_STEP_METADATA).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `the fetch-from-folder button sits next to reset all on the tab row`() {
        show()
        goToMetadata()

        val tabs = rule.onNodeWithTag(TAG_B_STEP_TABS).getBoundsInRoot()
        val dirMeta = rule.onNodeWithTag(TAG_DIR_META).getBoundsInRoot()
        val reset = rule.onNodeWithTag(TAG_RESET_ALL).getBoundsInRoot()

        // 在页签行右侧(用户说的「右上角」)
        assertTrue(
            dirMeta.left > tabs.right - 1.dp,
            "应在页签行右侧;实际 dirMeta.left=${dirMeta.left} tabs.right=${tabs.right}",
        )
        // 与重置相邻(用户说的「重置旁边」):两者之间不应隔开一整个按钮
        val gap = (reset.left - dirMeta.right).value
        assertTrue(
            gap in 0f..16f,
            "应与重置紧邻;实际间隔 ${gap}dp(dirMeta.right=${dirMeta.right} reset.left=${reset.left})",
        )
    }

    @Test
    fun `tapping it asks for confirmation before touching any field`() {
        show()
        goToMetadata()
        val before = fieldText(MetaField.Writer)

        rule.onNodeWithTag(TAG_DIR_META).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_DIR_META_DIALOG).assertIsDisplayed()
        // 弹窗还没确认时不得改动任何字段 —— 这是「破坏性动作可解释」(约束 5)的落点
        assertEquals(before, fieldText(MetaField.Writer), "确认前不得改动字段")

        // 取消后同样不动
        rule.onNodeWithTag(TAG_DIR_META_DIALOG).performClick()
        rule.waitForIdle()
        assertEquals(before, fieldText(MetaField.Writer), "取消后不得改动字段")
    }

    @Test
    fun `confirming refills the writer from the folder name`() {
        show()
        goToMetadata()
        // 当前卷 = 第一本(aot-1);断言必须取**当前卷**的目录建议,取别的卷会假红/假绿
        val active = library.volumes.first { it.id == "aot-1" }
        val expected = active.dirWriterSug.value
        val before = fieldText(MetaField.Writer)
        assertEquals("[作者]谏山创(原作:某人)(电子版)", before, "前提:当前值来自 writerSug(带方括号)")

        rule.onNodeWithTag(TAG_DIR_META).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_DIR_META_CONFIRM).performClick()
        rule.waitForIdle()

        assertEquals(expected, fieldText(MetaField.Writer), "确认后应填入目录推导的作者")
    }

    @Test
    fun `the button is present inside the metadata list scroll scope`() {
        // 页签行不随元数据列表滚动 —— 这条顺带钉住「按钮不落在滚动容器里」,
        // 否则用户滑到列表底部就找不到它了(与「重置全部」同一约束)。
        show()
        goToMetadata()
        rule.onNodeWithTag(TAG_METADATA_LIST)
            .performScrollToNode(hasTestTag(metaFieldTag(MetaField.Writer)))
        rule.onNodeWithTag(TAG_DIR_META).assertIsDisplayed()
    }
}
