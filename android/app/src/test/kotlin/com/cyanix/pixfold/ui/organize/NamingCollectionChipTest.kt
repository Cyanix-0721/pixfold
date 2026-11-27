package com.cyanix.pixfold.ui.organize

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.activity.ComponentActivity
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.organize.OrganizeState
import com.cyanix.pixfold.domain.organize.organizeStateOf
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

/**
 * 步骤 2(命名页)的集合芯片行 —— 用户 2026-10-19 要求「整理图片的步骤2也加上芯片选取,
 * 单向继承步骤1选择项」。
 *
 * ## 断言口径:必须走**真的 `OrganizeSteps`**,不能裸组合 `NamingPage`
 *
 * 「与步骤 1 共用同一个 `activeId`」是**跨步骤的连接性**,不是 `NamingPage` 自己的属性:
 * 裸组合 `NamingPage` 时根本没有「步骤 1 的选中项」这回事,断出来的东西无法证明需求。
 * 故这里组合 `OrganizeSteps`(它同时持有两个步骤的 UI),用 `TAG_STEP_NAMING` 在步骤间
 * 真实跳转,再断言芯片的选中态与标题里的集合名 —— 标题里带集合名,是「当前正在命名哪一本」
 * 最直接的可见证据。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class NamingCollectionChipTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val collections = MockData.workspaceA.collections

    /** 收集全部集合、让芯片行必然渲染(只有一个集合时 `CollectionChipRows` 内部直接 return)。 */
    private fun show(initialId: String = collections.first().id) {
        rule.setContent {
            // 与 `PixFoldApp` 同构:唯一的 activeId 持有者 + 唯一的状态持有者。
            var activeCollectionId by remember { mutableStateOf(initialId) }
            var state by remember { mutableStateOf(organizeStateOf(collections)) }
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                OrganizeSteps(
                    collections = collections,
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    initialActiveCollectionId = activeCollectionId,
                    onActiveCollectionChange = { activeCollectionId = it },
                    externalState = state,
                    onStateChange = { state = it },
                )
            }
        }
    }

    private fun goToNamingStep() {
        rule.onNodeWithTag(TAG_STEP_NAMING).performClick()
        rule.waitForIdle()
    }

    /** 命名页标题里带集合名 ⇒ 用它证明「当前正在命名哪一本」。 */
    private fun assertNamingTitle(collection: ImageCollection) {
        val expected = TestText.plural(
            com.cyanix.pixfold.R.plurals.a_naming_title,
            collection.images.size,
            collection.name,
            collection.images.size,
        )
        // 标题节点本身只带 tag,集合名是它的 Text —— 按文本断(见 `a_naming_title` 文案)。
        rule.onNodeWithTag(TAG_NAMING_TITLE).assertExists()
        rule.onNode(hasText(expected, substring = true)).assertExists()
    }

    @Test
    fun `步骤2 显示当前集合的芯片选中态`() {
        show()
        goToNamingStep()

        // 步骤 1 默认选中第一个集合 ⇒ 步骤 2 打开时那个芯片必须已选中。
        rule.onNodeWithTag(collectionTag(collections.first().id)).assertIsSelected()
    }

    @Test
    fun `在步骤2 切集合会改变命名对象`() {
        show()
        goToNamingStep()
        assertNamingTitle(collections.first())

        val second = collections[1]
        rule.onNodeWithTag(collectionTag(second.id)).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(collectionTag(second.id)).assertIsSelected()
        assertNamingTitle(second)
    }

    @Test
    fun `步骤2 的切换会带回步骤1`() {
        show()
        goToNamingStep()

        val second = collections[1]
        rule.onNodeWithTag(collectionTag(second.id)).performClick()
        rule.waitForIdle()

        // 回到步骤 1 —— 芯片选中态必须还是步骤 2 里那次切换的结果
        // (用户裁决「共用同一个选中项」,不是单向复制一个快照)。
        rule.onNodeWithTag(TAG_STEP_SORT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(collectionTag(second.id)).assertIsSelected()
    }

    @Test
    fun `只有单个集合时不渲染芯片行`() {
        val single = listOf(collections.first())
        rule.setContent {
            var activeCollectionId by remember { mutableStateOf(single.first().id) }
            var state by remember { mutableStateOf(organizeStateOf(single)) }
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                OrganizeSteps(
                    collections = single,
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    initialActiveCollectionId = activeCollectionId,
                    onActiveCollectionChange = { activeCollectionId = it },
                    externalState = state,
                    onStateChange = { state = it },
                )
            }
        }
        goToNamingStep()

        rule.onNodeWithTag(TAG_COLLECTION_ROW).assertDoesNotExist()
        rule.onNodeWithTag(TAG_NAMING_PAGE).assertExists()
    }

    @Test
    fun `工作流步骤标签不再包含步骤123前缀`() {
        show()
        listOf(TAG_STEP_SORT, TAG_STEP_NAMING, TAG_STEP_PLAN).forEach { tag ->
            val label = rule.onNodeWithTag(tag)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }
                .orEmpty()
            assertFalse(label.startsWith("步骤"), "步骤标签不应带「步骤」前缀: $label")
            assertFalse(label.startsWith("Step ", ignoreCase = true), "Step tab不应带「Step 」前缀: $label")
        }
    }

    @Test
    fun `排序页收起瀑布流后切到命名页保持收起，在命名页展开后切回排序页也保持展开`() {
        show()

        // 1. 排序页初始展开：芯片与拉手横条均可见
        rule.onNodeWithTag(TAG_COLLECTION_ROW).assertIsDisplayed()
        rule.onNodeWithTag(TAG_COLLECTION_WATERFALL_HANDLE).performClick()
        rule.waitForIdle()
        assertFalse(
            rule.onAllNodesWithTag(TAG_COLLECTION_ROW).fetchSemanticsNodes().isNotEmpty(),
            "排序页收起瀑布流后，集合芯片应隐藏",
        )

        // 2. 切到命名页，应继承排序页的「已收起」状态
        goToNamingStep()
        assertFalse(
            rule.onAllNodesWithTag(TAG_COLLECTION_ROW).fetchSemanticsNodes().isNotEmpty(),
            "从已收起的排序页切到命名页时，顶部瀑布流应保持收起状态",
        )
        rule.onNodeWithTag(TAG_COLLECTION_WATERFALL_HANDLE).assertIsDisplayed()

        // 3. 在命名页重新展开并切回排序页，排序页也应同步保持展开
        rule.onNodeWithTag(TAG_COLLECTION_WATERFALL_HANDLE).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COLLECTION_ROW).assertIsDisplayed()

        rule.onNodeWithTag(TAG_STEP_SORT).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COLLECTION_ROW).assertIsDisplayed()
    }
}
