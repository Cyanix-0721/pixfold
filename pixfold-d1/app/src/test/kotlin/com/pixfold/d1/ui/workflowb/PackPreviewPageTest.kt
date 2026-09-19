package com.pixfold.d1.ui.workflowb

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pixfold.d1.domain.comic.renumberPages
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.LangChoice
import com.pixfold.d1.domain.model.MetaField
import com.pixfold.d1.domain.model.MetaValue
import com.pixfold.d1.domain.comic.setMetaValue
import com.pixfold.d1.domain.sort.moveItemTo
import com.pixfold.d1.domain.workflowb.workflowBStateOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工作流 B · 步骤 3 的 UI 层断言(验收第 14 项:**XML 预览与页码列表预览一致**)。
 *
 * 断言方式是"**从界面上读回**两个预览的文本,再比对它们":
 *  - XML 预览节点的文本里必须含页数对应的信息,页码区必须列出**同样数量**的项;
 *  - 页码区的项数 = 卷的页序长度 = XML 里 Number/Title 的同一卷。
 * 若 UI 把两份预览分别算错(或算的是不同的卷),这个用例会失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class PackPreviewPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /**
     * 被测页面是**无状态**的(切卷把新状态上报上层,由上层重新下发),故这里必须搭一个
     * 最小状态持有器 —— 传 `onLibraryChange = {}` 会让切卷点了没反应,
     * 那是测试脚手架的问题,不是页面的问题(且正是"数据变了界面不动"的假象来源)。
     */
    private fun show(volumes: List<ComicVolume> = library.volumes) {
        rule.setContent {
            var state by remember { mutableStateOf(workflowBStateOf(library.rootPath, volumes)) }
            PackPreviewPage(
                libraryState = state,
                onLibraryChange = { state = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag(TAG_PACK_LIST).performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    /** 从界面上读回 XML 预览文本。 */
    private fun xmlOnScreen(): String =
        rule.onNodeWithTag(TAG_XML_PREVIEW).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("\n") { it.text }

    // ================= 验收第 14 项:两份预览都在,且一致 =================

    @Test
    fun `xml preview and page preview are both shown`() {
        show()
        scrollTo(TAG_XML_PREVIEW)
        rule.onNodeWithTag(TAG_XML_PREVIEW).assertIsDisplayed()
        rule.onNodeWithText("ComicInfo.xml 预览").assertIsDisplayed()

        scrollTo(TAG_PAGE_PREVIEW_COUNT)
        rule.onNodeWithTag(TAG_PAGE_PREVIEW_COUNT).assertIsDisplayed()
        rule.onNodeWithText("页码预览", substring = true).assertIsDisplayed()
    }

    @Test
    fun `page preview lists exactly as many pages as the volume`() {
        show()
        val volume = library.volumes.first { it.id == "aot-1" }
        scrollTo(TAG_PAGE_PREVIEW_COUNT)
        rule.onNodeWithText("共 ${volume.pageCount} 项", substring = true).assertIsDisplayed()

        // 第一项与最后一项的页码文件名都必须在界面上出现(与领域层 renumberPages 一致)
        val expected = renumberPages(volume)
        scrollTo(packPageRowTag(0))
        rule.onNodeWithText(expected.first().fileName).assertIsDisplayed()
        scrollTo(packPageRowTag(expected.size - 1))
        rule.onNodeWithText(expected.last().fileName).assertIsDisplayed()
    }

    @Test
    fun `the two previews agree on the same volume`() {
        show()
        val volume = library.volumes.first { it.id == "aot-1" }
        // XML 里的 Title/Series 与卷头显示的目录必须是同一卷
        scrollTo(TAG_XML_PREVIEW)
        val xml = xmlOnScreen()
        assertTrue(xml.contains("<Title>${volume.title}</Title>"), "XML 应来自当前选中卷:实际\n$xml")
        assertTrue(xml.contains("<Series>${volume.series}</Series>"))
        assertTrue(xml.contains("<Number>${volume.volume}</Number>"))
        // LanguageISO 未确认 -> 自闭合空标签(与领域层一致)
        assertTrue(xml.contains("<LanguageISO />"), "语言未确认时必须是不写入的空标签:实际\n$xml")
    }

    @Test
    fun `xml reflects the page count source volume`() {
        show()
        // 切卷 -> 两份预览必须同时跟着换(不是一份换了另一份没换)
        rule.onNodeWithTag(packVolumeChipTag("op-101")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(packVolumeChipTag("op-101")).assertIsDisplayed()
        val volume = library.volumes.first { it.id == "op-101" }
        rule.waitForIdle()
        val xml = xmlOnScreen()
        assertTrue(xml.contains("<Number>${volume.volume}</Number>"), "XML 未跟随切卷:\n$xml")
        rule.onNodeWithText("共 ${volume.pageCount} 项", substring = true).assertIsDisplayed()
    }

    @Test
    fun `switching volume really changes the preview`() {
        show()
        scrollTo(TAG_XML_PREVIEW)
        val before = xmlOnScreen()
        rule.onNodeWithTag(packVolumeChipTag("op-101")).performClick()
        rule.waitForIdle()
        scrollTo(TAG_XML_PREVIEW)
        val after = xmlOnScreen()
        assertTrue(before != after, "切卷后预览必须真的变化(数据变了界面不动是必须被捕获的缺陷)")
    }

    // ================= 页码规则可见 =================

    @Test
    fun `first page is 001 and extension is lower case`() {
        show()
        scrollTo(packPageRowTag(0))
        rule.onNodeWithText("001.jpg").assertIsDisplayed()
    }

    @Test
    fun `manual page adjustments are marked in the preview`() {
        // 先制造一次人工调整,再断言预览里标出来
        val volumes = library.volumes.map { v ->
            if (v.id == "aot-1") {
                v.copy(pageOrder = moveItemTo(v.pageOrder, v.pages.last().id, 0))
            } else {
                v
            }
        }
        show(volumes)
        scrollTo(packPageRowTag(0))
        assertTrue(
            rule.onAllNodesWithText("人工调整", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "人工调整过的页必须在预览里标出来",
        )
    }

    @Test
    fun `page renumbering follows the manual order`() {
        val volumes = library.volumes.map { v ->
            if (v.id == "aot-1") {
                v.copy(pageOrder = moveItemTo(v.pageOrder, v.pages.last().id, 0))
            } else {
                v
            }
        }
        show(volumes)
        val moved = volumes.first { it.id == "aot-1" }
        val expectedFirst = renumberPages(moved).first()
        assertEquals(moved.pages.last().id, expectedFirst.item.id)
        scrollTo(packPageRowTag(0))
        // 第 1 项是原最后一页,但仍编号 001
        assertTrue(
            rule.onAllNodesWithText("← ${expectedFirst.item.fileName}").fetchSemanticsNodes().isNotEmpty(),
            "页码第 1 项应指向被拖到最前的原最后一页",
        )
    }

    // ================= 待确认项与输出冲突 =================

    @Test
    fun `pending issues are visible on the preview page`() {
        show()
        scrollTo(TAG_PACK_PENDING)
        rule.onNodeWithTag(TAG_PACK_PENDING).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("语言未确认", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "待确认项必须可见",
        )
    }

    @Test
    fun `output conflict is flagged on the preview page`() {
        show()
        rule.onNodeWithTag(packVolumeChipTag("op-102")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("已存在同名文件", substring = true).assertIsDisplayed()
    }

    @Test
    fun `language not written explains why`() {
        show()
        // 卷头在列表顶部,起始位置即可见
        assertTrue(
            rule.onAllNodesWithText("不写入（语言未确认）", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "未确认语言必须说明「不写入」的原因,而不是静默留空",
        )
    }

    @Test
    fun `confirmed language is written into the xml preview`() {
        val volumes = library.volumes.map { v ->
            if (v.id == "aot-1") setMetaValue(v, MetaField.Language, MetaValue.Language(LangChoice.Ja)) else v
        }
        show(volumes)
        scrollTo(TAG_XML_PREVIEW)
        assertTrue(xmlOnScreen().contains("<LanguageISO>ja</LanguageISO>"))
    }

    @Test
    fun `other language with a code is written verbatim`() {
        val volumes = library.volumes.map { v ->
            if (v.id == "aot-1") {
                setMetaValue(v, MetaField.Language, MetaValue.Language(LangChoice.Other, "fr"))
            } else {
                v
            }
        }
        show(volumes)
        scrollTo(TAG_XML_PREVIEW)
        assertTrue(xmlOnScreen().contains("<LanguageISO>fr</LanguageISO>"))
    }

    // ================= 未勾选的卷不得"看起来会被打包" =================

    @Test
    fun `an unincluded volume is explicitly flagged as not being packed`() {
        // 回归:此前预览页完全不看勾选状态 —— 用户在步骤 1 取消勾选后,
        // 到步骤 3 仍看到完整预览,会误以为它也会被打包(违反"预览是主工作区")。
        val volumes = library.volumes.map { if (it.id == "aot-1") it.copy(included = false) else it }
        show(volumes)
        assertTrue(
            rule.onAllNodesWithText("本卷未勾选", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "未勾选的卷必须在预览页显式说明不会打包",
        )
    }

    @Test
    fun `an included volume shows no exclusion warning`() {
        show()
        assertTrue(
            rule.onAllNodesWithText("本卷未勾选", substring = true).fetchSemanticsNodes().isEmpty(),
            "已勾选的卷不应出现「不会打包」的提示",
        )
    }

    @Test
    fun `the preview reports how many volumes will actually be packed`() {
        // 这个数字来自领域层 includedVolumes(此前是死代码:界面从未使用)
        show()
        assertTrue(
            rule.onAllNodesWithText("本次将打包 5 / 5 卷", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "预览必须回答「这次到底做什么」",
        )
    }

    @Test
    fun `the packed volume count follows the included flags`() {
        val volumes = library.volumes.map { if (it.id == "op-102") it.copy(included = false) else it }
        show(volumes)
        assertTrue(
            rule.onAllNodesWithText("本次将打包 4 / 5 卷", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "取消勾选后打包卷数必须跟着变",
        )
    }

    @Test
    fun `the volume chip marks an unincluded volume`() {
        val volumes = library.volumes.map { if (it.id == "aot-1") it.copy(included = false) else it }
        show(volumes)
        assertTrue(
            rule.onAllNodesWithText("（未勾选）", substring = true).fetchSemanticsNodes().isNotEmpty(),
            "卷切换 chip 上要能看出哪些卷未勾选",
        )
    }

    @Test
    fun `the preview page does not offer a fake execute button`() {
        // P5 只做预览:执行要在 P6 接入(计划 + 危险动作二次确认 + 报告)。
        // 这里反过来钉死"没有假按钮",避免出现点了没反应的控件。
        show()
        scrollTo(TAG_PAGE_PREVIEW_COUNT)
        assertTrue(
            rule.onAllNodesWithText("确认打包").fetchSemanticsNodes().isEmpty(),
            "P5 不应出现执行按钮(执行属 P6)",
        )
    }
}
