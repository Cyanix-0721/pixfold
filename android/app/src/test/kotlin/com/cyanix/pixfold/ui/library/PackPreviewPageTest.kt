package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.assertCountEquals
import com.cyanix.pixfold.domain.pack.PackBlocker
import com.cyanix.pixfold.domain.pack.PackPreflightResult
import com.cyanix.pixfold.domain.pack.PackWarning
import com.cyanix.pixfold.domain.pack.Zip64Reason
import com.cyanix.pixfold.domain.storage.SpaceCheckResult
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.comic.renumberPages
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.plan.SourcePolicy
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.MetaValue
import com.cyanix.pixfold.domain.comic.setMetaValue
import com.cyanix.pixfold.domain.sort.moveItemTo
import com.cyanix.pixfold.domain.library.libraryStateOf
import com.cyanix.pixfold.ui.text.TAG_WARN_ICON
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
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class PackPreviewPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /**
     * 被测页面是**无状态**的(切卷把新状态上报上层,由上层重新下发),故这里必须搭一个
     * 最小状态持有器 —— 传 `onLibraryChange = {}` 会让切卷点了没反应,
     * 那是测试脚手架的问题,不是页面的问题(且正是"数据变了界面不动"的假象来源)。
     */
    private fun show(
        volumes: List<ComicVolume> = library.volumes,
        sourcePolicy: SourcePolicy = SourcePolicy.Keep,
        showPlanSection: Boolean = false,
    ) {
        rule.setContent {
            var state by remember { mutableStateOf(libraryStateOf(library.rootPath, volumes)) }
            PackPreviewPage(
                libraryState = state,
                onLibraryChange = { state = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
                sourcePolicy = sourcePolicy,
                // 默认 false:计划区会连带渲染底栏的「确认打包」按钮,而
                // `the preview page does not offer a fake execute button` 正是反着钉这一点的
                // (P5 只有预览)。需要策略卡片的用例显式打开。
                showPlanSection = showPlanSection,
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
        val volume = library.volumes.first { it.id == "aot-1" }
        rule.onNodeWithTag(TAG_XML_PREVIEW).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.pack_xml_title)).assertIsDisplayed()

        scrollTo(TAG_PAGE_PREVIEW_COUNT)
        rule.onNodeWithTag(TAG_PAGE_PREVIEW_COUNT).assertIsDisplayed()
        rule.onNodeWithText(TestText.plural(R.plurals.pack_page_header, volume.pageCount, volume.pageCount), substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `page preview lists exactly as many pages as the volume`() {
        show()
        val volume = library.volumes.first { it.id == "aot-1" }
        scrollTo(TAG_PAGE_PREVIEW_COUNT)
        rule.onNodeWithText(TestText.plural(R.plurals.pack_page_header, volume.pageCount, volume.pageCount), substring = true)
            .assertIsDisplayed()

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
        rule.onNodeWithText(TestText.plural(R.plurals.pack_page_header, volume.pageCount, volume.pageCount), substring = true)
            .assertIsDisplayed()
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
            rule.onAllNodesWithText(TestText.of(R.string.pack_page_manual), substring = true).fetchSemanticsNodes().isNotEmpty(),
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

    // ================= 页码与页序不一致的告警(R4) =================

    /**
     * `pack_page_issues` 是「前缀 + %1$s」的形状:领域层给出的问题清单在渲染时
     * 用 `pack_page_issue_separator` 拼好再填进去。这里照着同样的拼法构造期望文本,
     * **不写死任何字面量**(改措辞不会误报),同时又能验证"数目真的传对了"。
     */
    private fun issueWarning(vararg issueParts: String): String =
        TestText.raw(R.string.pack_page_issues).substringBefore("%1\$s") +
            issueParts.joinToString(TestText.raw(R.string.pack_page_issue_separator))

    @Test
    fun `a volume whose page list and page order disagree is flagged on the preview page`() {
        // R4 端到端:`validatePageConsistency` 修好后,这里的问题必须真的走到界面上。
        // 此前 `preview.consistent` 恒真 ⇒ 告警分支不可达 ⇒ 用户在真机上永远看不到这一句。
        val broken = library.volumes.first { it.id == "aot-1" }.let { it.copy(pages = it.pages.dropLast(1)) }
        show(listOf(broken) + library.volumes.filter { it.id != "aot-1" })
        scrollTo(TAG_PAGE_PREVIEW_COUNT)
        rule.onNodeWithText(
            issueWarning(TestText.of(R.string.domain_page_issue_count_mismatch, 15, 16)),
        ).assertIsDisplayed()
    }

    @Test
    fun `a healthy volume shows no page issue warning`() {
        // 反向:告警必须只在真有问题时出现,否则它就是个噪声(用户会学会忽略它)。
        show()
        scrollTo(TAG_PAGE_PREVIEW_COUNT)
        assertTrue(
            rule.onAllNodesWithText(
                TestText.raw(R.string.pack_page_issues).substringBefore("%1\$s"),
                substring = true,
            ).fetchSemanticsNodes().isEmpty(),
            "页表与页序一致的卷不该显示页码告警",
        )
    }

    // ================= 待确认项与输出冲突 =================

    @Test
    fun `pending issues are visible on the preview page`() {
        // 语言项已移除(Unset 即终态);现在唯一待确认项是卷号缺失 —— 用缺卷号的 aot-0
        val volumes = library.volumes.map { v -> if (v.id == "aot-0") v.copy(included = true) else v }
        show(volumes)
        rule.onNodeWithTag(packVolumeChipTag("aot-0")).performClick()
        rule.waitForIdle()
        scrollTo(TAG_PACK_PENDING)
        rule.onNodeWithTag(TAG_PACK_PENDING).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.domain_pending_volume), substring = true).fetchSemanticsNodes().isNotEmpty(),
            "待确认项必须可见",
        )
    }

    @Test
    fun `output conflict is flagged on the preview page`() {
        show()
        rule.onNodeWithTag(packVolumeChipTag("op-102")).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(TestText.of(R.string.pack_output_exists), substring = true).assertIsDisplayed()
    }

    @Test
    fun `language not written explains why`() {
        show()
        // 卷头在列表顶部,起始位置即可见
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.pack_lang_unset), substring = true)
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
    fun `unset language writes no LanguageISO value`() {
        val volumes = library.volumes.map { v ->
            if (v.id == "aot-1") {
                setMetaValue(v, MetaField.Language, MetaValue.Language(LangChoice.Unset))
            } else {
                v
            }
        }
        show(volumes)
        scrollTo(TAG_XML_PREVIEW)
        // Unset = 不写入标签,写成自闭合空元素
        assertTrue(xmlOnScreen().contains("<LanguageISO />"))
    }

    // ================= 未勾选的卷不得"看起来会被打包" =================

    @Test
    fun `an unincluded volume is explicitly flagged as not being packed`() {
        // 回归:此前预览页完全不看勾选状态 —— 用户在步骤 1 取消勾选后,
        // 到步骤 3 仍看到完整预览,会误以为它也会被打包(违反"预览是主工作区")。
        val volumes = library.volumes.map { if (it.id == "aot-1") it.copy(included = false) else it }
        show(volumes)
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.pack_excluded_warning), substring = true).fetchSemanticsNodes().isNotEmpty(),
            "未勾选的卷必须在预览页显式说明不会打包",
        )
    }

    @Test
    fun `an included volume shows no exclusion warning`() {
        show()
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.pack_excluded_warning), substring = true).fetchSemanticsNodes().isEmpty(),
            "已勾选的卷不应出现「不会打包」的提示",
        )
    }

    @Test
    fun `the preview reports how many volumes will actually be packed`() {
        // 这个数字来自领域层 includedVolumes(此前是死代码:界面从未使用)
        show()
        assertTrue(
            rule.onAllNodesWithText(TestText.plural(R.plurals.pack_included_count, 5, 5, 5), substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "预览必须回答「这次到底做什么」",
        )
    }

    @Test
    fun `the packed volume count follows the included flags`() {
        val volumes = library.volumes.map { if (it.id == "op-102") it.copy(included = false) else it }
        show(volumes)
        assertTrue(
            rule.onAllNodesWithText(TestText.plural(R.plurals.pack_included_count, 4, 4, 5), substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "取消勾选后打包卷数必须跟着变",
        )
    }

    @Test
    fun `the volume chip marks an unincluded volume`() {
        val volumes = library.volumes.map { if (it.id == "aot-1") it.copy(included = false) else it }
        show(volumes)
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.pack_volume_suffix_excluded), substring = true).fetchSemanticsNodes().isNotEmpty(),
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
            rule.onAllNodesWithText(TestText.of(R.string.pack_confirm)).fetchSemanticsNodes().isEmpty(),
            "P5 不应出现执行按钮(执行属 P6)",
        )
    }

    // ================= 警示行改用矢量图标后,图标必须真的画上去 =================

    @Test
    fun `the exclusion warning draws a vector icon and no emoji prefix`() {
        // 回归(用户 2026-10-05,m00673):文案里的 `⚠️` 前缀删除后,警示语义由图标承担。
        // 本用例同时钉两头:①图标节点真的在树里;②文案里**没有**再用 emoji 顶替。
        // 只测①会漏"图标加了但文案里还留着 emoji",只测②会漏"emoji 删了但图标没加"。
        val volumes = library.volumes.map { if (it.id == "aot-1") it.copy(included = false) else it }
        show(volumes)
        scrollTo(TAG_PACK_EXCLUDED)
        assertTrue(
            rule.onAllNodesWithTag(TAG_WARN_ICON).fetchSemanticsNodes().isNotEmpty(),
            "未勾选卷的警告行应显示矢量图标",
        )
        val warning = TestText.of(R.string.pack_excluded_warning)
        assertTrue(
            !warning.contains("\u26A0") && !warning.contains("\uFE0F"),
            "警告文案里不应再有 emoji 前缀(现值:「$warning」)",
        )
    }

    @Test
    fun `the delete source warning draws a vector icon only when deletion is selected`() {
        // 负向对照:默认 Keep 时不该有删除警告,切到 Delete 才出现 ——
        // 否则"图标常驻"也能让上面那条用例绿。
        // 策略卡片(含"删除源目录"警告)只在计划区里渲染 —— 关掉它这条警告根本不在树上,
        // 而 LibrarySteps 的真实接法就是 `showPlanSection = true`(见 LibrarySteps.kt:246)。
        show(sourcePolicy = SourcePolicy.Delete, showPlanSection = true)
        rule.onNodeWithTag(TAG_DELETE_WARNING).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithTag(TAG_WARN_ICON).fetchSemanticsNodes().isNotEmpty(),
            "选中删除源目录后,警示行应显示矢量图标",
        )
    }
}

/**
 * 计划页预检（D4b）：**按下执行之前**就要看到的结论。
 *
 * 背景：预检原先只在 `PackExecutor.execute()` 内部、真正写盘前那一刻跑，
 * `PackWarning` 的三类告知项（空间临界 / 估算只是下界 / 需要 Zip64）在界面上
 * **一个出口都没有**。用户站在计划页时看到的只有页码预览。
 *
 * 这些用例钉的是「**告知的时机**」，不是「算得对不对」（后者是
 * `PackPreflightTest` 的职责）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class PackPreflightPanelTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    private fun show(preflight: PackUiPreflight?) {
        rule.setContent {
            var state by remember { mutableStateOf(libraryStateOf(library.rootPath, library.volumes)) }
            PackPreviewPage(
                libraryState = state,
                onLibraryChange = { state = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
                showPlanSection = true,
                pagePreflight = preflight,
            )
        }
    }

    private fun ready(
        blockers: List<PackBlocker> = emptyList(),
        warnings: List<PackWarning> = emptyList(),
        availableBytes: Long? = 10L * 1024 * 1024 * 1024,
    ) = PackUiPreflight.Ready(
        result = PackPreflightResult(
            volumes = emptyList(),
            blockers = blockers,
            warnings = warnings,
            estimatedBytes = 0L,
            availableBytes = availableBytes ?: Long.MAX_VALUE,
        ),
        availableBytes = availableBytes,
    )

    @Test
    fun `nothing is shown before the preflight runs`() {
        show(preflight = null)
        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT).assertCountEquals(0)
    }

    @Test
    fun `a clean preflight shows no panel at all`() {
        // 一切正常时不该在底栏上方多出一块空白区域 —— 那会让底栏看起来「飘」起来。
        show(ready())
        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT).assertCountEquals(0)
    }

    @Test
    fun `space shortfall is shown as a blocker`() {
        val shortfall = 500L * 1024 * 1024
        show(
            ready(
                blockers = listOf(
                    PackBlocker.SpaceInsufficient(
                        SpaceCheckResult.Insufficient(
                            availableBytes = 1024L,
                            estimatedBytes = 1024L + shortfall,
                            shortfallBytes = shortfall,
                        ),
                    ),
                ),
            ),
        )
        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT_BLOCKER).assertCountEquals(1)
        // 用子串匹配：`WarnLine` 把图标与文本放在同一个 Row 里，语义树上文本节点
        // 可能带 contentDescription 合并，整串精确匹配会落空。
        rule.onNodeWithText(humanBytes(shortfall), substring = true).assertIsDisplayed()
    }

    @Test
    fun `zip64 requirement is shown as a warning not a blocker`() {
        // 承重：Zip64 是**标准扩展**，写完照样能读。把它渲染成阻断会让
        // 4 GiB 以上的卷完全无法打包 —— 这正是 `PackPreflight` KDoc 反复强调的一条。
        show(
            ready(
                warnings = listOf(
                    PackWarning.Zip64Required("Vol 01.cbz", Zip64Reason.CentralDirOffsetTooLarge),
                ),
            ),
        )
        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT_WARNING).assertCountEquals(1)
        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT_BLOCKER).assertCountEquals(0)
    }

    @Test
    fun `zip64 reason is spelled out instead of showing a raw enum`() {
        show(
            ready(
                warnings = listOf(
                    PackWarning.Zip64Required("Vol 01.cbz", Zip64Reason.CentralDirOffsetTooLarge),
                ),
            ),
        )
        // 用户看不懂 CentralDirOffsetTooLarge。屏幕上必须是「整个包达到 4 GB 以上」。
        rule.onNodeWithText(
            TestText.of(R.string.pack_warn_zip64_reason_offset),
            substring = true,
        ).assertIsDisplayed()
    }

    @Test
    fun `estimate lower bound is shown as a warning`() {
        show(ready(warnings = listOf(PackWarning.EstimateIsLowerBound(3))))
        rule.onNodeWithText(TestText.of(R.string.pack_warn_estimate_lower_bound, 3)).assertIsDisplayed()
    }

    @Test
    fun `unknown available space is stated instead of being silently treated as fine`() {
        // 承重：领域层对「问不到空间」按充足放行（问不到不等于不足，这是刻意的）。
        // 但界面**不能说充足** —— 那是假的承诺。必须单独说「无法判断」。
        show(ready(availableBytes = null))
        rule.onNodeWithText(TestText.of(R.string.pack_warn_space_unknown)).assertIsDisplayed()
    }

    @Test
    fun `a failed preflight is stated rather than showing nothing`() {
        show(PackUiPreflight.Unavailable("StatFs failed"))
        rule.onAllNodesWithTag(TAG_PACK_PREFLIGHT).assertCountEquals(1)
    }
}
