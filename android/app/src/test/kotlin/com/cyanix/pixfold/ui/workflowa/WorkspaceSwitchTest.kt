package com.cyanix.pixfold.ui.workflowa

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.workflowa.workflowAStateOf
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 工作区**热切换**回归:换了工作区之后,步骤 1 必须渲染新工作区的集合,而不是白屏。
 *
 * ## 缺陷现场(2026-10-19 真机)
 *
 * 应用内选好目录 → 工作区条显示「当前工作区:test」→ 但内容区整片空白;冷启动反而正常。
 * 根因是**两层都用了无 key 的 `remember` 播种**:
 * 上层 `PixFoldApp` 是 `remember(collections)`(换工作区就重建 `WorkflowAState`),
 * 下层 `WorkflowASteps` / `SortAndPreviewPage` 却是 `remember { … }`(组合实例没销毁 ⇒ 仍持旧 state)。
 * 旧 state 的 `orders` 只含旧工作区的集合 id,新集合查不到 ⇒ 渲染路径直接 `return`。
 *
 * ## 本类为什么用 `WorkflowASteps` 而不是直接测 `SortAndPreviewPage`
 *
 * 缺陷发生在**跨两层的状态播种**上。只测 `SortAndPreviewPage` 会漏掉
 * `WorkflowASteps.activeId` 那一层的 stale(它决定步骤 1 打开的默认集合)。
 * 这里按 `PixFoldApp` 的真实接线搭最小宿主:宿主持有 `collections`,`remember(collections)`
 * 派生 `activeCollectionId` 与 `workflowAState`,与生产代码同构。
 *
 * ## 断言选择
 *
 * 钉「新集合的图片数量文案」而不是「没有白屏」—— 后者只能证明"没崩",
 * 而缺陷的实质是**显示了旧数据/什么都不显示**。数量文案同时证明:
 * ① 渲染走通了;② `orders` 里确实有新集合 id(否则会渲染 `a_sort_empty` 空态)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class WorkspaceSwitchTest {

    @get:Rule
    val rule = createComposeRule()

    /** 宿主的切换开关 —— 组合外部持有,置 true 等于"用户换了工作区"。 */
    private var secondWorkspace by mutableStateOf(false)

    private val alphaWorkspace = listOf(collection("alpha", 3))
    private val betaWorkspace = listOf(collection("beta", 5))

    private fun setContent() {
        rule.setContent {
            val collections = if (secondWorkspace) betaWorkspace else alphaWorkspace
            // ---- 与 PixFoldApp.kt 同构的三行接线 ----
            val activeCollectionId = remember(collections) {
                collections.firstOrNull()?.id.orEmpty()
            }
            val workflowAState = remember(collections) { workflowAStateOf(collections) }
            var namingState by remember(collections) {
                mutableStateOf(
                    NamingState(
                        NamingScheme(
                            rootDirName = collections.firstOrNull()?.rootDirName.orEmpty(),
                        ),
                    ),
                )
            }

            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                WorkflowASteps(
                    collections = collections,
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    initialActiveCollectionId = activeCollectionId,
                    externalState = workflowAState,
                    onStateChange = {},
                    initialNamingState = namingState,
                    onNamingStateChange = { namingState = it },
                )
            }
        }
    }

    @Test
    fun `切换工作区后步骤 1 显示新工作区的图片数量`() {
        setContent()

        // 前置:第一个工作区已正常渲染
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
        assertCountText(3)

        // 换工作区(等价于用户重新选了一个文件夹)
        secondWorkspace = true
        rule.waitForIdle()

        // 新工作区的数量必须出现;若下层还持旧 state,这里会渲染空态或什么都不渲染
        rule.onNodeWithTag(TAG_GRID).assertIsDisplayed()
        assertCountText(5)
    }

    @Test
    fun `切换工作区后不渲染空态`() {
        setContent()
        secondWorkspace = true
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_SORT_EMPTY).fetchSemanticsNodes().isEmpty(),
            "换了工作区后不该落到「这个集合没有排序状态」的空态 —— " +
                "它意味着上层给了新的 collections、下层却还在用旧 state",
        )
    }

    // ---- 夹具 ----

    private fun assertCountText(expected: Int) {
        val text = TestText.plural(R.plurals.a_image_count, expected, expected)
        val nodes = rule
            .onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))
            .fetchSemanticsNodes()
        assertTrue(
            nodes.isNotEmpty(),
            "应显示「$text」($expected 张);实际没找到,说明页面仍在用旧工作区的 state",
        )
    }

    private fun collection(id: String, pages: Int) = ImageCollection(
        id = id,
        name = id,
        rootDirName = id,
        images = (1..pages).map { index ->
            SourceItem(
                id = "$id#$index",
                collectionId = id,
                dir = "",
                baseName = "$id-$index",
                ext = "jpg",
                sizeBytes = 1024,
                modifiedEpochMillis = 0,
                createdEpochMillis = null,
                seed = index,
            )
        },
    )
}
