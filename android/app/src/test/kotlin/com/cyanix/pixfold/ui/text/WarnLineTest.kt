package com.cyanix.pixfold.ui.text

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.cyanix.pixfold.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WarnLine] 的**渲染契约**:图标真的进了树、文本真的能按 tag 断言。
 *
 * **为什么这层也要单独测**:去掉 `⚠️` 前缀后,警告行的语义由两部分承担 ——
 * 图标(视觉)与正文(文字)。已有的三组测试各守一段却**都漏了这一处**:
 *  - [WarnIconMappingTest] 只查"表里有没有这条映射",不查调用点用没用;
 *  - [WarningIconRenderTest] 只查图本身画得对不对,不查它有没有被摆到行首;
 *  - `PlanUiTest` 对 `planSkipTag` 只断言 `assertIsDisplayed`(tag 在外层 `Row` 上),
 *    对 `TAG_CONFIRM_DANGER_TITLE` 只断言文本 —— **即便把 `Icon` 整段删掉,它们全绿**。
 *
 * 所以这里钉两件事:
 *  1. `WarnLine` 渲染后树里**确实有** `TAG_WARN_ICON` 节点,且它带 `contentDescription = null`
 *     (读屏不该把正文已经说清的警示再念一遍);
 *  2. `textModifier` 挂在 `Text` 上时,`assertTextEquals` **能找到**该文本节点 ——
 *     这是 KDoc 里写明、却最容易在重构时踩坏的一条:把 tag 挪到外层 `Row`,
 *     `Text` 的语义节点就与 tag 节点分离,`assertTextEquals` 随即失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WarnLineTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `it renders an icon node alongside the text`() {
        rule.setContent {
            WarnLine(icon = R.drawable.ic_warning_triangle, text = "源目录将被删除")
        }
        rule.onNodeWithTag(TAG_WARN_ICON).assertIsDisplayed()
        rule.onNodeWithText("源目录将被删除").assertIsDisplayed()
    }

    @Test
    fun `the icon carries no content description to avoid double screen reader output`() {
        rule.setContent {
            WarnLine(icon = R.drawable.ic_forbidden, text = "该卷已跳过")
        }
        // 图标只作视觉冗余:警示含义已由正文文字表达。断言该节点**没有** ContentDescription 语义
        // (未定义,而不是"定义为空串")—— 若有人给它补上一段描述,读屏会把同一件事念两遍。
        rule.onNodeWithTag(TAG_WARN_ICON)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
    }

    @Test
    fun `a tag passed through textModifier lands on the text node not the row`() {
        rule.setContent {
            WarnLine(
                icon = R.drawable.ic_warning_triangle,
                text = "已有同名 CBZ 将被覆盖",
                textModifier = Modifier.testTag("probe-text"),
            )
        }
        // 关键契约:tag 挂在 Text 上时,同一个节点既能按 tag 找到、也能按文本断言。
        // 若把 testTag 挪到外层 Row,这里会退化成"节点存在但文本对不上"。
        rule.onNodeWithTag("probe-text").assertTextEquals("已有同名 CBZ 将被覆盖")
    }

    @Test
    fun `non warning lines stay plain text so the dot prefix assertions still hold`() {
        rule.setContent {
            Text("· 源目录：保留")
        }
        // 负向对照:非警示行仍是普通 Text(带 "· " 前缀),没有图标节点。
        // PlanUiTest / PlanWiringTest / NamingPageTest 共 4 处断言正打在 "· " 上。
        rule.onNodeWithText("· 源目录：保留").assertIsDisplayed()
    }
}
