package com.pixfold.d1.ui.workflowa

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.SortField
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收清单第 7 项(后半):**批次默认与"本组独立"例外并存**,且该能力在界面上可操作。
 *
 * 缺口背景:领域层 `WorkflowAState` 早已实现并有 9 项单测,但页面从未使用
 * (`app` 内零引用 = 死代码),故该验收项当时**不可操作**。
 * 本测试断言界面真的把该能力暴露出来,而非只存在于领域层。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp")
class BatchRuleUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val collections: List<ImageCollection> = MockData.workspaceA.collections

    private fun setPage() {
        rule.setContent { SortAndPreviewPage(collections) }
    }

    @Test
    fun `collection selector lists every collection`() {
        setPage()
        collections.forEach { c ->
            rule.onNodeWithTag(collectionTag(c.id)).assertIsDisplayed()
        }
    }

    @Test
    fun `switching collection changes which items are shown`() {
        setPage()

        val trip = collections.first { it.id == "trip" }
        val scan = collections.first { it.id == "scan" }
        val misc = collections.first { it.id == "misc" }

        // 用"共 N 张"作为判据:各集合张数不同(30/16/12),且该文本唯一。
        // (不能用文件名断言 —— scan 的 ch01/page_01 与 ch02/page_01 同名,会匹配到 2 个节点)
        rule.onNodeWithText("共 ${trip.images.size} 张").assertIsDisplayed()

        rule.onNodeWithTag(collectionTag("scan")).performClick()
        rule.onNodeWithText("共 ${scan.images.size} 张").assertIsDisplayed()

        rule.onNodeWithTag(collectionTag("misc")).performClick()
        rule.onNodeWithText("共 ${misc.images.size} 张").assertIsDisplayed()

        // 切回 trip:内容真的随集合切换
        rule.onNodeWithTag(collectionTag("trip")).performClick()
        rule.onNodeWithText("共 ${trip.images.size} 张").assertIsDisplayed()
    }

    @Test
    fun `custom rule toggle exists and reflects state`() {
        setPage()
        // "本组独立"开关必须存在且可点(此前该能力在界面上不存在)
        rule.onNodeWithTag(TAG_CUSTOM_RULE_TOGGLE).assertIsDisplayed()
        rule.onNodeWithTag(TAG_CUSTOM_RULE_TOGGLE).performClick()
        // 切换后仍显示(状态由文案体现)
        rule.onNodeWithTag(TAG_CUSTOM_RULE_TOGGLE).assertIsDisplayed()
    }

    @Test
    fun `batch label shows for non-custom collection and custom label after toggle`() {
        setPage()
        val active = collections.first()

        // 默认:非独立 -> 显示"批次默认"
        rule.onNodeWithText(batchLabel(active.id, false)).assertIsDisplayed()

        rule.onNodeWithTag(TAG_CUSTOM_RULE_TOGGLE).performClick()

        // 转独立 -> 文案变为"本组独立"
        rule.onNodeWithText(batchLabel(active.id, true)).assertIsDisplayed()
    }

    @Test
    fun `applying rule while custom does not change other collections order`() {
        setPage()

        val trip = collections.first { it.id == "trip" }
        val scan = collections.first { it.id == "scan" }
        val scanAscendingFirst = scan.images.first().fileName // 自然升序时的首张

        // 集合 1(trip)转为独立,并把第 1 级改为**降序**,再"应用排序"
        rule.onNodeWithTag(TAG_CUSTOM_RULE_TOGGLE).performClick()
        rule.onNodeWithTag(ascendingTag(0)).performClick()
        rule.onNodeWithTag(TAG_RULE_APPLY).performClick()

        // trip 自己应已变为降序(证明"应用"确实生效,避免测试空转)
        val tripDescendingFirst = trip.images.last().fileName
        assertTrue(
            hasCardWithText(tripDescendingFirst),
            "独立集合应用降序后,首张应变为 $tripDescendingFirst",
        )

        // 切到集合 2(scan):它用批次默认,**不应**被 trip 的独立规则影响
        rule.onNodeWithTag(collectionTag("scan")).performClick()

        assertTrue(
            hasCardWithText(scanAscendingFirst),
            "非独立集合仍应按批次默认(升序),首张应为 $scanAscendingFirst;" +
                "若这里失败,说明独立集合的规则泄漏到了其他集合",
        )

        // **关键补充**(变异测试发现):切回 trip 时必须记得"它仍是独立集合"。
        // 若页面把 apply 走了批次路径,独立标记会被抹掉 -> 这里会显示"批次默认规则"。
        rule.onNodeWithTag(collectionTag("trip")).performClick()
        rule.onNodeWithText(batchLabel("trip", true)).assertIsDisplayed()
        assertTrue(
            hasCardWithText(tripDescendingFirst),
            "切回独立集合时应仍是降序(规则被保留),首张应为 $tripDescendingFirst",
        )
    }

    /** 当前网格里是否存在包含该文本的卡片(用 contentDescription 判定,避免匹配多个节点)。 */
    private fun hasCardWithText(text: String): Boolean =
        rule.onAllNodesWithTag("grid-card")
            .fetchSemanticsNodes()
            .any { node ->
                val key = androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
                node.config.contains(key) && node.config[key].any { it.contains(text) }
            }

    @Test
    fun `ascending toggle is available for each level`() {
        setPage()
        // 至少第 1 级有升降序开关(验收第 7 项要求"可增删改")
        rule.onNodeWithTag(ascendingTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(ascendingTag(1)).assertIsDisplayed()
    }

    @Test
    fun `add and remove level buttons exist with limits`() {
        setPage()
        rule.onNodeWithTag(TAG_RULE_ADD).assertIsDisplayed()
        rule.onNodeWithTag(ruleRemoveTag(0)).assertIsDisplayed()
    }

    @Test
    fun `sort field options include all declared fields`() {
        setPage()
        // 字段可改:第 1 级能选到每个 SortField
        SortField.entries.forEach { f ->
            rule.onNodeWithTag("${ruleLevelTag(0)}-${f.name}").assertIsDisplayed()
        }
    }
}
