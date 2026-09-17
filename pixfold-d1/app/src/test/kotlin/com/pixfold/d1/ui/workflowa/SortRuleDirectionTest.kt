package com.pixfold.d1.ui.workflowa

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * 排序规则编辑交互(用户 2026-09-17 指定):
 *
 *  1. **去掉"应用排序"按钮,变动即自动应用**(原为"草稿 + 手动应用",规格 §5.4 已按此更新);
 *  2. 每级默认**升序**;
 *  3. **再次点击已选中字段** → 翻转升/降序;点击**其他**字段 → 换字段并回到默认升序;
 *  4. 方向用**实心三角**(正=升/倒=降),**仅显示在选中字段之后**;
 *  5. 增删排序级同样**即时生效**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class SortRuleDirectionTest {

    @get:Rule
    val rule = createComposeRule()

    /** 记录自动应用结果;宿主把新规则回灌,使连续点击可累积(与页面行为一致)。 */
    private class Harness(initial: SortRule) {
        var current: SortRule = initial
        val emissions = mutableListOf<SortRule>()
        val last: SortRule? get() = emissions.lastOrNull()
    }

    private fun setEditor(initial: SortRule, h: Harness) {
        rule.setContent {
            var r by remember { mutableStateOf(initial) }
            SortRuleEditor(
                rule = r,
                onRuleChange = {
                    r = it
                    h.current = it
                    h.emissions += it
                },
            )
        }
    }

    private fun exists(tag: String): Boolean =
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun description(tag: String): String {
        val cfg = rule.onNodeWithTag(tag).fetchSemanticsNode().config
        val key = SemanticsProperties.ContentDescription
        return if (cfg.contains(key)) cfg[key].joinToString(" ") else ""
    }

    private val twoLevels = SortRule(
        listOf(
            SortKey(SortField.NaturalName, true),
            SortKey(SortField.DirName, true),
        ),
    )

    // ---- 1. 不再需要"应用排序"按钮 ----

    @Test
    fun `apply button is removed`() {
        setEditor(twoLevels, Harness(twoLevels))
        assertTrue(
            rule.onAllNodesWithTag("rule-apply", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
            "「应用排序」按钮应已移除(改为变动即自动应用)",
        )
    }

    @Test
    fun `changing field applies immediately without pressing apply`() {
        val h = Harness(twoLevels)
        setEditor(twoLevels, h)

        rule.onNodeWithTag(fieldTag(0, SortField.FileName)).performClick()

        assertEquals(1, h.emissions.size, "字段变更应立即自动应用一次")
        assertEquals(SortField.FileName, h.last!!.keys[0].field)
    }

    // ---- 2/3. 方向交互 ----

    @Test
    fun `tapping the selected field again toggles to descending`() {
        val h = Harness(twoLevels)
        setEditor(twoLevels, h)

        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick()

        assertEquals(false, h.last!!.keys[0].ascending, "再点已选中字段应切为降序")
        assertEquals(SortField.NaturalName, h.last!!.keys[0].field, "字段不应改变")
    }

    @Test
    fun `tapping again toggles back to ascending`() {
        val h = Harness(twoLevels)
        setEditor(twoLevels, h)

        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick() // -> 降序
        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick() // -> 升序

        assertEquals(true, h.last!!.keys[0].ascending, "再点一次应切回升序")
        assertEquals(2, h.emissions.size, "每次点击都应自动应用")
    }

    @Test
    fun `selecting a different field defaults to ascending`() {
        val initial = SortRule(listOf(SortKey(SortField.NaturalName, false)))
        val h = Harness(initial)
        setEditor(initial, h)

        rule.onNodeWithTag(fieldTag(0, SortField.DirName)).performClick()

        assertEquals(SortField.DirName, h.last!!.keys[0].field)
        assertEquals(true, h.last!!.keys[0].ascending, "换字段后应回到默认升序")
    }

    @Test
    fun `direction is per level and does not affect other levels`() {
        val h = Harness(twoLevels)
        setEditor(twoLevels, h)

        rule.onNodeWithTag(fieldTag(0, SortField.NaturalName)).performClick()

        val keys = h.last!!.keys
        assertEquals(false, keys[0].ascending, "第 1 级应为降序")
        assertEquals(true, keys[1].ascending, "第 2 级不应受影响,仍为升序")
    }

    // ---- 4. 三角仅显示在选中字段之后 ----

    @Test
    fun `direction indicator shows only on the selected field`() {
        setEditor(twoLevels, Harness(twoLevels))

        assertTrue(exists(directionTag(0, SortField.NaturalName)), "选中字段应带方向三角")
        assertTrue(!exists(directionTag(0, SortField.FileName)), "未选中字段不应带三角")
        assertTrue(!exists(directionTag(0, SortField.DirName)), "未选中字段不应带三角")

        assertTrue(exists(directionTag(1, SortField.DirName)), "第2级选中字段应带方向三角")
        assertTrue(!exists(directionTag(1, SortField.NaturalName)), "第2级未选中字段不应带三角")
    }

    @Test
    fun `standalone ascending descending chip is gone`() {
        setEditor(twoLevels, Harness(twoLevels))
        assertTrue(
            rule.onAllNodesWithTag("rule-ascending-0", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
            "旧的独立升降序切换控件应已移除",
        )
    }

    // ---- 5. 增删级也即时生效 ----

    @Test
    fun `adding a level applies immediately`() {
        val h = Harness(twoLevels)
        setEditor(twoLevels, h)

        rule.onNodeWithTag(TAG_RULE_ADD).performClick()

        assertEquals(3, h.last!!.keys.size, "添加排序级应立即生效")
        assertEquals(true, h.last!!.keys.last().ascending, "新增级默认升序")
    }

    @Test
    fun `removing a level applies immediately`() {
        val h = Harness(twoLevels)
        setEditor(twoLevels, h)

        rule.onNodeWithTag(ruleRemoveTag(0)).performClick()

        assertEquals(1, h.last!!.keys.size, "删除排序级应立即生效")
        assertEquals(SortField.DirName, h.last!!.keys[0].field, "删除第1级后应只剩原第2级")
    }

    // ---- 无障碍 ----

    @Test
    fun `direction is conveyed to accessibility with field name and order`() {
        setEditor(twoLevels, Harness(twoLevels))
        val d = description(fieldTag(0, SortField.NaturalName))
        assertTrue(d.contains("升序"), "无障碍描述应说明当前方向(实际=$d)")
        assertTrue(d.contains(SortField.NaturalName.label), "应包含字段名(实际=$d)")
    }

    @Test
    fun `descending state is announced`() {
        val initial = SortRule(listOf(SortKey(SortField.NaturalName, false)))
        setEditor(initial, Harness(initial))
        val d = description(fieldTag(0, SortField.NaturalName))
        assertTrue(d.contains("降序"), "降序应体现在无障碍描述(实际=$d)")
    }
}
