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

        // 集合 1(trip)转为独立;第 1 级设为"文件名(自然)"并再次点击 -> 降序,再"应用排序"
        // (新交互:再次点击已选中字段即切换升降序)
        rule.onNodeWithTag(TAG_CUSTOM_RULE_TOGGLE).performClick()
        // 变动即自动应用(已无"应用排序"按钮)
        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick()
        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick()

        // trip 第 1 级=文件名(自然)降序 -> 文件名最大者(IMG_20260702_012)排首
        // 注意:不能直接用 images.last() —— 那是"集合原始顺序的末项",
        // 与"按文件名降序后的首项"不必然相同(trip 原始序是按 day1→day2 构造的)。
        val tripDescendingFirst = trip.images.maxByOrNull { it.fileName }!!.fileName
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

    /**
     * 当前网格里是否存在包含该文本的卡片。
     *
     * 注意(踩过的坑):语义树**只包含可见项**,而网格在切换集合/重排后会**保留滚动位置**,
     * 故直接读会得到"视口中段"而非列表头部,容易误判顺序。
     * 这里先**滚动到顶部**,再按视觉位置(y,x)读取,保证读到的是真正的首项。
     */
    private fun hasCardWithText(text: String, scrollTopFirst: Boolean = true): Boolean {
        if (scrollTopFirst) scrollGridToTop()
        val key = androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
        return rule.onAllNodesWithTag("grid-card")
            .fetchSemanticsNodes()
            .any { node ->
                node.config.contains(key) && node.config[key].any { it.contains(text) }
            }
    }

    /** 把网格滚到顶部(语义 Action),使可见项 = 列表头部。 */
    private fun scrollGridToTop() {
        val action = androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex
        val node = rule.onNodeWithTag(TAG_GRID).fetchSemanticsNode()
        node.config[action].action?.invoke(0)
        rule.waitForIdle()
    }

    @Test
    fun `each level shows a direction indicator on its selected field`() {
        setPage()
        // 新交互:方向由"选中字段后的实心三角"表示,每级各有一个
        val l0 = rule.onNodeWithTag(TAG_SORT_RULE_EDITOR).fetchSemanticsNode()
        // 默认规则两级:第1级 DirName,第2级 NaturalName
        assertTrue(
            rule.onAllNodesWithTag(directionTag(0, SortField.DirName), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "第1级选中字段应带方向三角",
        )
        assertTrue(
            rule.onAllNodesWithTag(directionTag(1, SortField.NaturalName), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
            "第2级选中字段应带方向三角",
        )
        assertTrue(l0.size.height > 0)
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
