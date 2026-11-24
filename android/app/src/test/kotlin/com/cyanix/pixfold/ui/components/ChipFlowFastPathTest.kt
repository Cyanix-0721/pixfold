package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * P1 #8：`ChipFlow` 在 `expanded == true` 或 `collapseLines == Int.MAX_VALUE` 时的单趟快速路径测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class ChipFlowFastPathTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `ChipFlow skips Probe subcomposition when expanded is true`() {
        val compositions = AtomicInteger(0)
        val count = 12

        rule.setContent {
            ChipFlow(
                count = count,
                horizontalGap = 4.dp,
                verticalGap = 10.dp,
                collapseLines = CHIP_COLLAPSE_LINES,
                expanded = true,
                modifier = Modifier.width(321.dp),
                indicatorTag = "indicator",
                indicatorLabel = { "收起" },
                onToggle = {},
                item = { index ->
                    compositions.incrementAndGet()
                    Box(
                        Modifier
                            .size(width = 96.dp, height = 32.dp)
                            .testTag("item-$index"),
                    ) {
                        Text("c$index")
                    }
                },
            )
        }
        rule.waitForIdle()

        assertEquals(
            count,
            compositions.get(),
            "expanded == true 时应跳过 ChipSlot.Probe，每个条目仅子组合 1 趟（期望 $count，实测 ${compositions.get()}）",
        )
        rule.onNodeWithTag("item-11").assertIsDisplayed()
        // 12 块（每行 3 块 = 4 行 > 3 行）在展开态仍必须显示「收起」芯片
        rule.onNodeWithTag("indicator").assertIsDisplayed()
    }

    @Test
    fun `ChipFlow skips Probe subcomposition when collapseLines is Int MAX_VALUE`() {
        val compositions = AtomicInteger(0)
        val count = 12

        rule.setContent {
            ChipFlow(
                count = count,
                horizontalGap = 4.dp,
                verticalGap = 10.dp,
                collapseLines = Int.MAX_VALUE,
                expanded = false,
                modifier = Modifier.width(321.dp),
                indicatorTag = "indicator",
                indicatorLabel = { "all" },
                onToggle = {},
                item = { index ->
                    compositions.incrementAndGet()
                    Box(
                        Modifier
                            .size(width = 96.dp, height = 32.dp)
                            .testTag("item-$index"),
                    ) {
                        Text("c$index")
                    }
                },
            )
        }
        rule.waitForIdle()

        assertEquals(
            count,
            compositions.get(),
            "collapseLines == Int.MAX_VALUE（二级浮层）应跳过 ChipSlot.Probe，仅子组合 1 趟",
        )
        rule.onNodeWithTag("item-11").assertIsDisplayed()
        rule.onNodeWithTag("indicator").assertDoesNotExist()
    }

    @Test
    fun `ChipFlow still collapses and hides overflow items from semantics when not expanded`() {
        rule.setContent {
            ChipFlow(
                count = 12,
                horizontalGap = 4.dp,
                verticalGap = 10.dp,
                collapseLines = CHIP_COLLAPSE_LINES,
                expanded = false,
                modifier = Modifier.width(321.dp),
                indicatorTag = "indicator",
                indicatorLabel = { "all 12" },
                onToggle = {},
                item = { index ->
                    Box(
                        Modifier
                            .size(width = 96.dp, height = 32.dp)
                            .testTag("item-$index"),
                    ) {
                        Text("c$index")
                    }
                },
            )
        }
        rule.waitForIdle()

        rule.onNodeWithTag("indicator").assertIsDisplayed()
        rule.onNodeWithTag("item-11").assertDoesNotExist()
    }
}
