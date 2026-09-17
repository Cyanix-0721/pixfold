package com.pixfold.d1.ui.workflowa

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 排序规则的升降序交互(用户 2026-09-17 指定):
 *
 *  - 每级默认**升序**;
 *  - **再次点击已选中的字段**即切换升/降序(无需单独的切换按钮);
 *  - 升降序用**实心三角小图标**表示,**仅显示在选中字段之后**,正/倒三角对应升/降;
 *  - **移除**原先行尾的"升序/降序"切换按钮。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SortRuleDirectionTest {

    @get:Rule
    val rule = createComposeRule()

    private class Recorder {
        var last: SortRule? = null
    }

    private fun setEditor(initial: SortRule, rec: Recorder) {
        rule.setContent {
            SortRuleEditor(rule = initial, onApply = { rec.last = it })
        }
    }

    private val twoLevels = SortRule(
        listOf(
            SortKey(SortField.NaturalName, true),
            SortKey(SortField.DirName, true),
        ),
    )

    private fun exists(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun description(tag: String): String {
        val cfg = rule.onNodeWithTag(tag).fetchSemanticsNode().config
        val key = SemanticsProperties.ContentDescription
        return if (cfg.contains(key)) cfg[key].joinToString(" ") else ""
    }

    @Test
    fun `direction indicator shows only on the selected field`() {
        setEditor(twoLevels, Recorder())

        // 第 1 级选中"文件名(自然)" -> 仅该字段带方向三角
        assertTrue(exists(directionTag(0, SortField.NaturalName)), "选中字段应带方向三角")
        assertTrue(!exists(directionTag(0, SortField.FileName)), "未选中字段不应带三角")
        assertTrue(!exists(directionTag(0, SortField.DirName)), "未选中字段不应带三角")

        // 第 2 级选中"目录名"
        assertTrue(exists(directionTag(1, SortField.DirName)), "第2级选中字段应带方向三角")
        assertTrue(!exists(directionTag(1, SortField.NaturalName)), "第2级未选中字段不应带三角")
    }

    @Test
    fun `tapping the selected field again toggles to descending`() {
        val rec = Recorder()
        setEditor(twoLevels, rec)

        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick()
        rule.onNodeWithTag(TAG_RULE_APPLY).performClick()

        val k = rec.last!!.keys[0]
        assertEquals(SortField.NaturalName, k.field, "字段不应改变")
        assertEquals(false, k.ascending, "再次点击已选中字段应切为降序")
    }

    @Test
    fun `tapping again toggles back to ascending`() {
        val rec = Recorder()
        setEditor(twoLevels, rec)

        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick() // -> 降序
        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick() // -> 升序
        rule.onNodeWithTag(TAG_RULE_APPLY).performClick()

        assertEquals(true, rec.last!!.keys[0].ascending, "再点一次应切回升序")
    }

    @Test
    fun `selecting a different field defaults to ascending`() {
        val rec = Recorder()
        setEditor(SortRule(listOf(SortKey(SortField.NaturalName, false))), rec)

        rule.onNodeWithTag(fieldTag(0, SortField.DirName)).performClick()
        rule.onNodeWithTag(TAG_RULE_APPLY).performClick()

        val k = rec.last!!.keys[0]
        assertEquals(SortField.DirName, k.field)
        assertEquals(true, k.ascending, "换字段后应回到默认升序")
    }

    @Test
    fun `direction is per level and does not affect other levels`() {
        val rec = Recorder()
        setEditor(twoLevels, rec)

        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick()
        rule.onNodeWithTag(TAG_RULE_APPLY).performClick()

        val keys = rec.last!!.keys
        assertEquals(false, keys[0].ascending, "第 1 级应为降序")
        assertEquals(true, keys[1].ascending, "第 2 级不应受影响,仍为升序")
    }

    @Test
    fun `standalone ascending descending chip is gone`() {
        setEditor(twoLevels, Recorder())
        // 旧的独立切换控件必须移除(用户指定);其 testTag 不应再存在于任何节点
        assertTrue(
            rule.onAllNodesWithTag("rule-ascending-0", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
            "旧的独立升降序切换控件应已移除",
        )
    }

    @Test
    fun `direction is conveyed to accessibility with field name and order`() {
        setEditor(twoLevels, Recorder())
        val d = description(fieldTag(0, SortField.NaturalName))
        assertTrue(d.contains("升序"), "无障碍描述应说明当前方向(实际=$d)")
        assertTrue(d.contains(SortField.NaturalName.label), "应包含字段名(实际=$d)")
    }

    @Test
    fun `descending state is announced`() {
        setEditor(SortRule(listOf(SortKey(SortField.NaturalName, false))), Recorder())
        val d = description(fieldTag(0, SortField.NaturalName))
        assertTrue(d.contains("降序"), "降序应体现在无障碍描述(实际=$d)")
    }
}
