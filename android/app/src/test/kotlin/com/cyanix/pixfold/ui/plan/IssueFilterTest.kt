package com.cyanix.pixfold.ui.plan

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.naming.setOverride
import com.cyanix.pixfold.domain.plan.ConfirmFlowState
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.plan.executePackagePlans
import com.cyanix.pixfold.ui.workflowa.ProposalSummary
import com.cyanix.pixfold.ui.workflowa.RenamePlanPage
import com.cyanix.pixfold.ui.workflowa.TAG_A_PLAN_CLEAN_TOGGLE
import com.cyanix.pixfold.ui.workflowa.TAG_A_PLAN_CONFIRM
import com.cyanix.pixfold.ui.workflowa.TAG_A_PLAN_FILTER_ALL
import com.cyanix.pixfold.ui.workflowa.TAG_A_PLAN_FILTER_ISSUES
import com.cyanix.pixfold.ui.workflowa.TAG_A_PLAN_FILTER_NONE
import com.cyanix.pixfold.ui.workflowa.TAG_FILTER_ALL
import com.cyanix.pixfold.ui.workflowa.TAG_FILTER_CONFLICTS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「全部 / 仅问题(N)」筛选条(台账 §3,验收第 15、17 项)。
 *
 * ## 这一层要证明什么(以及为什么不能只测领域层)
 *
 * 领域层已经保证「哪些项算问题」;这里要证明的是**界面真的按它筛了**,而且
 * 筛完之后**行号没有挪位** —— 后者是本轮最容易写错的一处:
 * 最自然的写法 `items(shown) { i -> Row(shown[i], i) }` 会把「可见序号」当成行号,
 * 于是筛掉前几行后,报告第 5 行会对到计划第 3 行,而**任何"行还在不在"的断言都不会红**。
 * 故下面的用例一律断言**原始下标 tag**(`planRowTag(4)` / `reportRowTag(2)`),
 * 并同时断言"可见序号 tag"**不存在**(`planRowTag(0)` 在筛选后必须消失)。
 *
 * ## 三处共用一份实现
 *
 * 命名页的 `ProposalSummary`、计划页、报告页现在都渲染 `IssueFilterRow`
 * (台账 §3 的原话就是"已有的筛选模式未推广")。故这里也钉住命名页那一处,
 * 防止共享组件被改回"各写一份"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class IssueFilterTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ================= 计划页 =================

    /**
     * 造一份「问题项不在开头」的计划。
     *
     * 为什么要刻意错开:`misc-5` 与 `misc-9` 被覆盖成同名 ⇒ 两者因 [Duplicate]
     * 被跳过,下标分别是 **4 与 8**。若实现把可见序号当行号,筛选后它们会变成
     * 0 与 1 —— 而 0 与 1 **恰好**是"跳过项在开头"时的正确答案,所以只有错开位置
     * 才能区分"用了原始下标"与"用了可见序号"。
     */
    private fun planWithSkips(): Pair<List<ImageCollection>, NamingState> {
        val misc = MockData.workspaceA.collections.filter { it.id == "misc" }
        val naming = NamingState(NamingScheme(rootDirName = "杂图"))
            .let { setOverride(it, "misc-5", "dup.jpg") }
            .let { setOverride(it, "misc-9", "dup.jpg") }
        return misc to naming
    }

    private fun showPlan(collections: List<ImageCollection>, naming: NamingState) {
        rule.setContent {
            RenamePlanPage(
                collections = collections,
                orders = emptyMap(),
                namingState = naming,
                cleanEmptyDirs = false,
                onCleanEmptyDirsChange = {},
                flow = ConfirmFlowState(),
                onStartConfirm = {},
                onConfirmStep = {},
                onCancelConfirm = {},
                onExecute = {},
            )
        }
    }

    @Test
    fun `plan page filters to skipped rows and keeps their original row tags`() {
        val (collections, naming) = planWithSkips()
        showPlan(collections, naming)

        // 默认「全部」:跳过行与非跳过行都在(筛选不得默认收窄 —— 验收第 15 项要"看全计划")
        rule.onNodeWithTag(planRowTag(0)).assertExists()
        rule.onNodeWithTag(planRowTag(4)).assertExists()
        rule.onNodeWithTag(planRowTag(8)).assertExists()
        rule.onNodeWithTag(TAG_A_PLAN_FILTER_ISSUES)
            .assertTextEquals(TestText.of(R.string.common_filter_issues, 2))

        rule.onNodeWithTag(TAG_A_PLAN_FILTER_ISSUES).performClick()

        // 原始下标:4 与 8 仍在
        rule.onNodeWithTag(planRowTag(4)).assertExists()
        rule.onNodeWithTag(planRowTag(8)).assertExists()
        // 可见序号 tag 必须**不存在** —— 否则就是把可见序号当行号了
        rule.onNodeWithTag(planRowTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(planRowTag(1)).assertDoesNotExist()
        rule.onNodeWithTag(planRowTag(5)).assertDoesNotExist()
        // 筛掉的正常行也一并消失
        rule.onNodeWithTag(planRowTag(11)).assertDoesNotExist()

        // 切回「全部」后全量恢复(证明筛选是本地显示状态,没有损坏计划)
        rule.onNodeWithTag(TAG_A_PLAN_FILTER_ALL).performClick()
        rule.onNodeWithTag(planRowTag(0)).assertExists()
        rule.onNodeWithTag(planRowTag(11)).assertExists()
    }

    @Test
    fun `plan page explains that the empty list is a filter result, not a lost plan`() {
        val misc = MockData.workspaceA.collections.filter { it.id == "misc" }
        showPlan(misc, NamingState(NamingScheme(rootDirName = "杂图")))

        rule.onNodeWithTag(TAG_A_PLAN_FILTER_ISSUES).performClick()

        // 没有跳过项 ⇒ 筛空。必须说清"是被筛掉了",不能只留一片空白
        rule.onNodeWithTag(TAG_A_PLAN_FILTER_NONE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_A_PLAN_FILTER_NONE)
            .assertTextEquals(TestText.of(R.string.common_filter_none))
    }

    @Test
    fun `plan filter keeps the clean-empty-dirs toggle and confirm button reachable`() {
        val (collections, naming) = planWithSkips()
        showPlan(collections, naming)
        rule.onNodeWithTag(TAG_A_PLAN_FILTER_ISSUES).performClick()

        // 筛选条常驻在列表**之外**(用户裁决 2026-09-26),故筛选后头部开关与底栏按钮都还在
        rule.onNodeWithTag(TAG_A_PLAN_CLEAN_TOGGLE).assertExists()
        rule.onNodeWithTag(TAG_A_PLAN_CONFIRM).assertIsDisplayed()
    }

    // ================= 报告页 =================

    /** 与 [PlanUiTest] 同形的三类结果:0 = 成功、1 = 跳过、2 = 失败。 */
    private fun threeClassReport() = run {
        val library = MockDataB.library
        val aot1 = library.volumes.first { it.id == "aot-1" }
        val op102 = library.volumes.first { it.id == "op-102" }
        val noPages = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        val report = executePackagePlans(
            buildPackagePlans(
                listOf(library.volumes[1], op102, noPages),
                conflictPolicy = OutputConflictPolicy.Skip,
            ),
        )
        assertEquals(1, report.succeededCount)
        assertEquals(1, report.skippedCount)
        assertEquals(1, report.failedCount)
        report
    }

    @Test
    fun `report page filters to non-succeeded rows and keeps their original row tags`() {
        val report = threeClassReport()
        rule.setContent { ExecutionReportPage(report = report, undoMessage = null, onUndoScope = {}, onDone = {}) }

        // 默认「全部」:三类都在(验收第 17 项)
        rule.onNodeWithTag(reportRowTag(0)).assertExists()
        rule.onNodeWithTag(reportRowTag(1)).assertExists()
        rule.onNodeWithTag(reportRowTag(2)).assertExists()
        rule.onNodeWithTag(TAG_REPORT_FILTER_ISSUES)
            .assertTextEquals(TestText.of(R.string.common_filter_issues, 2))

        rule.onNodeWithTag(TAG_REPORT_FILTER_ISSUES).performClick()

        // 成功行(下标 0)消失,跳过(1)与失败(2)保留 —— 且下标不变
        rule.onNodeWithTag(reportRowTag(0)).assertDoesNotExist()
        rule.onNodeWithTag(reportRowTag(1)).assertExists()
        rule.onNodeWithTag(reportRowTag(2)).assertExists()
        // 结果标签与原因也按原始下标留在原行上
        rule.onNodeWithTag(reportOutcomeTag(2)).assertExists()
    }

    @Test
    fun `report page explains that the empty list is a filter result, not a lost report`() {
        val library = MockDataB.library
        val allSucceeded = executePackagePlans(buildPackagePlans(listOf(library.volumes.first { it.id == "aot-2" })))
        assertTrue(allSucceeded.failedCount == 0 && allSucceeded.skippedCount == 0)
        rule.setContent {
            ExecutionReportPage(report = allSucceeded, undoMessage = null, onUndoScope = {}, onDone = {})
        }

        rule.onNodeWithTag(TAG_REPORT_FILTER_ISSUES).performClick()

        rule.onNodeWithTag(TAG_REPORT_FILTER_NONE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_REPORT_FILTER_NONE)
            .assertTextEquals(TestText.of(R.string.common_filter_none))
    }

    // ================= 命名页(共享组件的第三个调用点) =================

    @Test
    fun `proposal summary renders the shared filter row and reports the issue count`() {
        var changed: Boolean? = null
        rule.setContent {
            ProposalSummary(
                total = 30,
                conflictCount = 1,
                warningOnlyCount = 1,
                conflictOnly = false,
                onConflictOnlyChange = { changed = it },
            )
        }

        // N = 冲突 + 仅警告(命名页对"问题"的定义),且沿用旧 tag(既有测试依赖)
        rule.onNodeWithTag(TAG_FILTER_CONFLICTS)
            .assertTextEquals(TestText.of(R.string.common_filter_issues, 2))
        rule.onNodeWithTag(TAG_FILTER_ALL).assertExists()

        rule.onNodeWithTag(TAG_FILTER_CONFLICTS).performClick()
        assertEquals(true, changed, "点击「仅问题」应上报 true")
    }
}
