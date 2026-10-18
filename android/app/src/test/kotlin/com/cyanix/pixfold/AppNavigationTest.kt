package com.cyanix.pixfold

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.cyanix.pixfold.ui.components.TAG_GRID_CARD
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_CLOSE
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_IMAGE
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_NEXT
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_PAGE_LABEL
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_RENAME
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_RENAME_FIELD
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_RENAME_SAVE
import com.cyanix.pixfold.ui.preview.TAG_PREVIEW_RENAMED
import com.cyanix.pixfold.ui.workflowa.TAG_GRID
import com.cyanix.pixfold.ui.workflowa.TAG_NEXT_STEP
import com.cyanix.pixfold.ui.workflowa.TAG_PROPOSAL_LIST
import com.cyanix.pixfold.ui.workflowa.TAG_STEP_BACK
import com.cyanix.pixfold.ui.workflowa.collectionTag
import com.cyanix.pixfold.ui.workflowa.proposalFieldTag
import com.cyanix.pixfold.ui.workflowb.TAG_LIBRARY_PAGE
import com.cyanix.pixfold.ui.workflowb.volumeCardTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

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
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `tapping a thumbnail opens preview and close returns`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()

        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()
        rule.onNodeWithTag(TAG_PREVIEW).assertIsDisplayed()

        rule.onNodeWithTag(TAG_PREVIEW_CLOSE).performClick()
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
    }

    @Test
    fun `preview opened from grid allows paging`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()

        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals(TestText.of(R.string.preview_page_label, 1, 30))
        rule.onNodeWithTag(TAG_PREVIEW_NEXT).performClick()
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL).assertTextEquals(TestText.of(R.string.preview_page_label, 2, 30))
    }

    @Test
    fun `workflow B entry actually opens the library step`() {
        // P5 起工作流 B 有真实页面:断言"真的到了漫画库步骤"(出现卷卡片),
        // 而不是只断言回调被调用 —— 后者会放过"回调触发但界面没换"的缺陷。
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_b)).performClick()
        rule.onNodeWithTag(TAG_LIBRARY_PAGE).assertIsDisplayed()
        rule.onAllNodesWithTag(volumeCardTag("aot-1")).assertCountEquals(1)
    }

    // ============ 预览取图必须用**当前页序**,不是集合声明序 ============
    //
    // 缺陷(2026-09-25 查实):网格点击回传的是**页序下标**(`SortAndPreviewPage` 传
    // `items = order.order`),而预览目的地按**集合声明序**取图(`PixFoldApp` 取
    // `collections[i].images`)。两者只在"页序 == 声明序"时一致。
    //
    // `cn` 集合的声明序是**故意打乱**的(MockData.chapters),故这一组必然错配。
    // 既有 `trip` 系的测试之所以全绿,是因为 trip 的声明序恰好等于默认排序结果 ——
    // 属**巧合通过**,不是覆盖。
    //
    // 两条断言刻意正交:第一条钉"哪一张图"(页序),第二条钉"哪个集合"(总数),
    // 因为这是两个独立的接线错误,只看其一都会漏掉另一半。

    @Test
    fun `preview shows the very card that was tapped when page order differs from declaration order`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(collectionTag("cn")).performClick()

        // 页序第 1 张是 `第一话`;声明序第 1 张是 `第十二话`(故修复前这里会读到后者)。
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()
        rule.onNodeWithTag(TAG_PREVIEW_IMAGE).assertContentDescriptionEquals(
            TestText.of(R.string.preview_image_cd, "第一话.jpg"),
        )
    }

    @Test
    fun `preview follows the collection currently being sorted`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(collectionTag("cn")).performClick()
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()

        // 总数即"跟随的是哪个集合",与页序无关:cn 有 16 张、首个集合 trip 有 30 张。
        // 缺陷:`WorkflowASteps` 的 `onActiveCollectionChange` 参数从未被转发给根层,
        // 故根层的 activeCollectionId 永远停在 trip。
        rule.onNodeWithTag(TAG_PREVIEW_PAGE_LABEL)
            .assertTextEquals(TestText.of(R.string.preview_page_label, 1, 16))
    }

    // ============ 预览页改名必须真的写回命名状态(用户 2026-09-25 裁决) ============
    //
    // 这条是**端到端**的:在预览页改名 → 关掉预览 → 进命名页,名字必须还在。
    // 若预览只改自己的局部状态(或状态提升时漏了回调),这里就会读到旧名字。
    // 刻意用 `cn` 集合:它同时穿过"预览按页序取图"那条修复 ——
    // 声明的首图是 `第十二话`,页序首图才是 `第一话`,断言用后者的 id。
    @Test
    fun `renaming in the preview reaches the naming page and is reflected there`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(collectionTag("cn")).performClick()

        // 页序第一张 = 第一话 = `cn-3`(声明序第一张是第十二话,不能用声明序取 id)。
        val pageOrderFirstId = "cn-3"

        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()
        rule.onNodeWithTag(TAG_PREVIEW_RENAME).performClick()
        rule.onNodeWithTag(TAG_PREVIEW_RENAME_FIELD).performTextReplacement("预览里改的名.jpg")
        rule.onNodeWithTag(TAG_PREVIEW_RENAME_SAVE).performClick()
        // 反馈行必须立刻反映新名字 —— 证明写回真的发生了,而不是只有回调被调过。
        rule.onNodeWithTag(TAG_PREVIEW_RENAMED)
            .assertTextEquals(TestText.of(R.string.preview_renamed, "预览里改的名.jpg"))
        rule.onNodeWithTag(TAG_PREVIEW_CLOSE).performClick()

        // 回排序页 → 进命名页:同一张的名称必须已是新名字。
        rule.onNodeWithTag(TAG_NEXT_STEP).performClick()
        rule.onNodeWithTag(TAG_PROPOSAL_LIST)
            .performScrollToNode(hasTestTag(proposalFieldTag(pageOrderFirstId)))
        val after = rule.onNodeWithTag(proposalFieldTag(pageOrderFirstId))
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("预览里改的名.jpg", after, "命名页应看到预览里改的名字")
    }

    // ============ 命名页的编辑必须上报到应用根(状态提升的守卫) ============
    //
    // 这条是**变异存活后补的**:把 `WorkflowASteps` 命名步骤的
    // `onStateChange = { updateNamingState(it) }` 改成只写本地的
    // `{ namingState = it }`,上面那条端到端改名测试**依然全绿** —— 因为
    // `NamingPage` 自己就持有本地 `state`,单页内看不出漏上报。
    //
    // 要暴露它必须让 `WorkflowASteps` **整个离开组合再重建**,使本地状态归零:
    // 预览是根导航的兄弟目的地,点开预览时 `WorkflowARoute` 目的地被销毁,
    // 回来是全新实例,只能从应用根拿 `initialNamingState`。
    // 漏上报时根里仍是旧值,重建后命名页就会退回建议名。
    @Test
    fun `edits made in the naming step survive a round trip that recreates the step container`() {
        rule.setContent { PixFoldApp() }
        rule.onNodeWithText(TestText.of(R.string.home_workflow_a)).performClick()
        rule.onNodeWithTag(collectionTag("cn")).performClick()
        rule.onNodeWithTag(TAG_NEXT_STEP).performClick()

        val id = "cn-3"
        rule.onNodeWithTag(TAG_PROPOSAL_LIST).performScrollToNode(hasTestTag(proposalFieldTag(id)))
        rule.onNodeWithTag(proposalFieldTag(id)).performTextReplacement("命名页改的名.jpg")

        // 退回排序页 -> 点开预览(销毁并重建步骤容器)-> 关闭预览 -> 再进命名页。
        rule.onNodeWithTag(TAG_STEP_BACK).performClick()
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performClick()
        rule.onNodeWithTag(TAG_PREVIEW_CLOSE).performClick()
        rule.onNodeWithTag(TAG_NEXT_STEP).performClick()

        rule.onNodeWithTag(TAG_PROPOSAL_LIST).performScrollToNode(hasTestTag(proposalFieldTag(id)))
        val after = rule.onNodeWithTag(proposalFieldTag(id))
            .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals("命名页改的名.jpg", after, "步骤容器重建后命名页应仍看到改过的名字")
    }
}
