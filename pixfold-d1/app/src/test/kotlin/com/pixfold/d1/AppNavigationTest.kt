package com.pixfold.d1

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.pixfold.d1.ui.components.TAG_GRID_CARD
import com.pixfold.d1.ui.preview.TAG_PREVIEW
import com.pixfold.d1.ui.preview.TAG_PREVIEW_CLOSE
import com.pixfold.d1.ui.preview.TAG_PREVIEW_NEXT
import com.pixfold.d1.ui.preview.TAG_PREVIEW_PAGE_LABEL
import com.pixfold.d1.ui.workflowa.TAG_GRID
import com.pixfold.d1.ui.workflowb.TAG_LIBRARY_PAGE
import com.pixfold.d1.ui.workflowb.volumeCardTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导航闭环:首页 → 工作流 A(网格) → 大图预览 → 关闭回列表。
 *
 * 断言"真的到了下一页"(出现网格节点),而不是只断言回调被调用 —— 后者会放过
 * "回调触发但界面没换"的缺陷。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppNavigationTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `workflow A entry actually opens the grid page`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `tapping a thumbnail opens preview and close returns`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()

        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()
        rule.onNodeWithTag(TAG_PREVIEW).assertIsDisplayed()

        rule.onNodeWithTag(TAG_PREVIEW_CLOSE).performClick()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `preview opened from grid allows paging`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText("工作流 A · 图片整理与命名").performClick()
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()

        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals("第 1 / 30 页")
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).performClick()
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals("第 2 / 30 页")
    }

    @Test
    fun `workflow B entry actually opens the library step`() {
        // P5 起工作流 B 有真实页面:断言"真的到了漫画库步骤"(出现卷卡片),
        // 而不是只断言回调被调用 —— 后者会放过"回调触发但界面没换"的缺陷。
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText("工作流 B · CBZ 制作").performClick()
        rule.onNodeWithTag(TAG_LIBRARY_PAGE).assertIsDisplayed()
        rule.onAllNodesWithTag(volumeCardTag("aot-1")).assertCountEquals(1)
    }
}
