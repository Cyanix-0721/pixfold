package com.pixfold.d1.ui.workflowb

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.pixfold.d1.domain.comic.setMetaValue
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.MetaField
import com.pixfold.d1.domain.model.MetaValue
import com.pixfold.d1.domain.workflowb.WorkflowBState
import com.pixfold.d1.domain.workflowb.workflowBStateOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工作流 B · 步骤 2 的 UI 层断言(验收第 11、12、13 项)。
 *
 * 第 2 层的职责:**证明界面上真的可操作**。这里刻意不复用领域函数做断言 ——
 * P3 的教训是"领域做完 + 单测全绿,但界面根本没接上"(死代码)。
 * 因此每个用例都以"界面上出现了什么 / 点了之后界面怎么变"为准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class MetadataPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /**
     * 被测页面把变更**上报上层**;测试必须像真实上层那样持有并回灌状态,
     * 否则"点了切卷没反应"是脚手架造成的假象(且这正是"数据变了界面不动"的成因类别)。
     */
    private fun show(volumes: List<ComicVolume> = library.volumes) {
        rule.setContent {
            var state by remember { mutableStateOf(workflowBStateOf(library.rootPath, volumes)) }
            MetadataPage(
                libraryState = state,
                onLibraryChange = { state = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    /** 读某个输入框的可编辑文本(label 也会算进 Text,故不能用 assertTextEquals)。 */
    private fun fieldText(field: MetaField): String =
        rule.onNodeWithTag(metaFieldTag(field)).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag(TAG_METADATA_LIST).performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    // ================= 验收第 11 项:逐卷元数据可改 + 建议值带来源 =================

    @Test
    fun `every editable metadata field is present`() {
        show()
        listOf(
            MetaField.Title, MetaField.Series, MetaField.Writer,
            MetaField.Volume, MetaField.CbzName, MetaField.OutputDir,
        ).forEach { f ->
            scrollTo(metaFieldTag(f))
            rule.onNodeWithTag(metaFieldTag(f)).assertIsDisplayed()
        }
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithTag(TAG_LANGUAGE_FIELD).assertIsDisplayed()
    }

    @Test
    fun `suggestion and its source are visible`() {
        show()
        // Title 建议 + 来源必须可见(建议 ≠ 事实)
        scrollTo(metaSuggestionTag(MetaField.Title))
        rule.onNodeWithText("目录层级 L2（系列层）", substring = true).assertIsDisplayed()
        // Volume 的来源说明"从目录名解析"
        scrollTo(metaSuggestionTag(MetaField.Volume))
        rule.onNodeWithText("目录名「第01卷」解析", substring = true).assertIsDisplayed()
    }

    @Test
    fun `editing the title really changes the field`() {
        show()
        scrollTo(metaFieldTag(MetaField.Title))
        rule.onNodeWithTag(metaFieldTag(MetaField.Title)).performTextReplacement("我的标题")
        assertEquals("我的标题", fieldText(MetaField.Title))
    }

    @Test
    fun `the writer cleaning suggestion is displayed alongside the raw suggestion`() {
        show()
        scrollTo(TAG_WRITER_CLEAN)
        rule.onNodeWithText("清理建议：谏山创", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_WRITER_CLEAN).assertIsDisplayed()
    }

    @Test
    fun `tapping writer cleaning replaces the value`() {
        show()
        scrollTo(TAG_WRITER_CLEAN)
        rule.onNodeWithTag(TAG_WRITER_CLEAN).performClick()
        assertEquals("谏山创", fieldText(MetaField.Writer))
    }

    @Test
    fun `reset button restores the suggestion value`() {
        show()
        scrollTo(metaFieldTag(MetaField.Title))
        rule.onNodeWithTag(metaFieldTag(MetaField.Title)).performTextReplacement("改过了")
        rule.onNodeWithTag(metaResetTag(MetaField.Title)).performClick()
        assertEquals("进击的巨人", fieldText(MetaField.Title))
    }

    @Test
    fun `reset button is disabled for untouched fields`() {
        show()
        scrollTo(metaResetTag(MetaField.Title))
        rule.onNodeWithTag(metaResetTag(MetaField.Title)).assertIsNotEnabled()
    }

    @Test
    fun `switching volume really changes the fields shown`() {
        show()
        assertEquals("进击的巨人", fieldText(MetaField.Title))

        rule.onNodeWithTag(volumeChipTag("op-101")).performClick()
        rule.waitForIdle()
        assertEquals("海贼王", fieldText(MetaField.Title))
    }

    // ================= 验收第 12 项:语言默认未设置 + 逐卷不同 + 不写入可选 =================

    @Test
    fun `language defaults to unset with an explicit confirmation prompt`() {
        show()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithText("未设置（需确认）").assertIsDisplayed()
        rule.onNodeWithText("语言默认未设置，需人工确认").assertIsDisplayed()
    }

    @Test
    fun `language suggestion is shown but not adopted`() {
        show()
        scrollTo(metaSuggestionTag(MetaField.Language))
        // aot-1 的建议是 ja(低置信)—— 显示出来,但当前值仍是"未设置"
        rule.onNodeWithText("卷内文件名含日文片段（低置信）", substring = true).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("ja · 日文").fetchSemanticsNodes().isEmpty(),
            "建议不是事实:语言不得自动采纳",
        )
    }

    @Test
    fun `picking a language in the dropdown changes the value`() {
        show()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithTag(TAG_LANGUAGE_FIELD).performClick()
        rule.onNodeWithText("zh · 中文").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("zh · 中文").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("语言默认未设置，需人工确认").fetchSemanticsNodes().isEmpty(),
            "确认语言后不应再显示未确认提示",
        )
    }

    @Test
    fun `skip is an independent option`() {
        show()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithTag(TAG_LANGUAGE_FIELD).performClick()
        rule.onNodeWithText("不写入标签").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("不写入标签").assertIsDisplayed()
    }

    @Test
    fun `pending issues of the active volume are visible in the editor`() {
        show()
        // aot-1 语言未设置 -> 卷头必须显示待确认
        scrollTo(TAG_PENDING_ISSUES)
        rule.onNodeWithTag(TAG_PENDING_ISSUES).assertIsDisplayed()
        rule.onNodeWithText("语言未确认", substring = true).assertIsDisplayed()

        // aot-0 缺卷号 -> 出现"卷号缺失"
        rule.onNodeWithTag(volumeChipTag("aot-0")).performClick()
        rule.waitForIdle()
        scrollTo(TAG_PENDING_ISSUES)
        assertTrue(
            rule.onAllNodesWithText("卷号缺失", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "缺卷号必须在编辑处可见",
        )
    }

    @Test
    fun `languages can differ between volumes`() {
        show()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithTag(TAG_LANGUAGE_FIELD).performClick()
        rule.onNodeWithText("zh · 中文").performClick()
        rule.waitForIdle()

        // 切到另一卷:语言仍是"未设置"(逐卷不同,不被全局选项静默覆盖)
        rule.onNodeWithTag(volumeChipTag("aot-2")).performClick()
        rule.waitForIdle()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithText("未设置（需确认）").assertIsDisplayed()
    }

    // ================= 验收第 13 项:批量设置不覆盖逐项例外 =================

    @Test
    fun `batch apply reports the number of volumes it actually changed`() {
        show()
        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("新系列")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithTag(TAG_BATCH_RESULT).assertIsDisplayed()
        rule.onNodeWithText("已设置到 5 卷", substring = true).assertIsDisplayed()
    }

    @Test
    fun `batch apply skips a field that was manually overridden`() {
        // 先把 aot-1 的 Series 手工改掉 -> 它应变成逐项例外
        val overridden = library.volumes.map {
            if (it.id == "aot-1") setMetaValue(it, MetaField.Series, MetaValue.Text("我改过的系列")) else it
        }
        show(overridden)

        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("批量系列")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithText("已设置到 4 卷（跳过 1 卷逐项例外）").assertIsDisplayed()

        // 被跳过的卷值真的没变
        rule.onNodeWithTag(volumeChipTag("aot-1")).performClick()
        rule.waitForIdle()
        assertEquals("我改过的系列", fieldText(MetaField.Series))
    }

    @Test
    fun `force switch makes batch apply overwrite the exception too`() {
        val overridden = library.volumes.map {
            if (it.id == "aot-1") setMetaValue(it, MetaField.Series, MetaValue.Text("我改过的系列")) else it
        }
        show(overridden)

        scrollTo(TAG_BATCH_FORCE)
        rule.onNodeWithTag(TAG_BATCH_FORCE).performClick()
        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("强制系列")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithText("已设置到 5 卷", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(volumeChipTag("aot-1")).performClick()
        rule.waitForIdle()
        assertEquals("强制系列", fieldText(MetaField.Series))
    }

    @Test
    fun `batch apply is disabled while the value is empty`() {
        show()
        scrollTo(TAG_BATCH_APPLY)
        rule.onNodeWithTag(TAG_BATCH_APPLY).assertIsNotEnabled()
    }

    @Test
    fun `unchecking a volume excludes it from batch apply`() {
        show()
        // 取消勾选 op-102(第 5 卷);勾选框在卷头,切卷后即可点
        rule.onNodeWithTag(volumeChipTag("op-102")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(volumeIncludedTag("op-102")).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("只改勾选的")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithText("已设置到 4 卷", substring = true).assertIsDisplayed()
    }

    @Test
    fun `output conflict volume is flagged`() {
        show()
        rule.onNodeWithTag(volumeChipTag("op-102")).performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("输出冲突", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "输出冲突卷必须在元数据页有明确提示",
        )
    }

    @Test
    fun `state is reported to the caller on change`() {
        var latest: WorkflowBState? = null
        rule.setContent {
            MetadataPage(
                libraryState = workflowBStateOf(library.rootPath, library.volumes),
                onLibraryChange = { latest = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
        scrollTo(metaFieldTag(MetaField.Title))
        rule.onNodeWithTag(metaFieldTag(MetaField.Title)).performTextReplacement("上报测试")
        assertEquals("上报测试", latest?.activeVolume?.title, "编辑必须上报上层,否则离开步骤就丢")
    }

    @Test
    fun `volume chips are addressable even when titles repeat`() {
        // 两个 aot 卷的 Title 建议相同(都是系列名)-> chip 文案重复,
        // 但仍必须能按 tag 精确选中,且不会崩。
        show()
        rule.onAllNodesWithTag(volumeChipTag("aot-1")).assertCountEquals(1)
        rule.onAllNodesWithTag(volumeChipTag("aot-2")).assertCountEquals(1)
    }
}
