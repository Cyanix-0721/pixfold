package com.cyanix.pixfold.ui.workflowa

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.performTouchInput
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.workflowa.reconcileWorkflowAState
import com.cyanix.pixfold.domain.workflowa.reconcileNamingState
import com.cyanix.pixfold.domain.workflowa.workflowAStateOf
import com.cyanix.pixfold.ui.components.TAG_GRID_CARD
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「同一工作区刷新后，人工排序仍在」的端到端回归（2026-10-19）。
 *
 * ## 这条为什么必须端到端测
 *
 * 领域层的 [reconcileWorkflowAState] 单独测过（[com.cyanix.pixfold.domain.workflowa.WorkflowReconcileTest]），
 * 但它只保证「算得对」。真正会翻车的是**接线时序**：
 *
 * 上层 `PixFoldApp` 在组合期把 `workflowAState = reconcileWorkflowAState(...)` 写下去，
 * 而子组件 `WorkflowASteps` / `SortAndPreviewPage` 各自还有一份 `remember(collections)`
 * 播种的**本地** `state`。两者的 key 都是 `collections` —— 刷新时 `collections` 是**新实例**，
 * 下层那份 `remember` 会**重新播种**成 `externalState ?: workflowAStateOf(collections)`。
 *
 * 关键问题：下层重新播种时读到的 `externalState`，是**对账前**还是**对账后**的？
 * 这取决于上层「写 workflowAState」与「组合出子组件」的先后 —— 推理不可靠，必须实测。
 * 若顺序错了，用户拖好的顺序会在一次无关的刷新后静默消失（正是本需求要防的事）。
 *
 * ## 断言选择
 *
 * 用**首格文件名**判定顺序，而不是「调整计数文案」—— 后者在本地 state 被重建时
 * 也可能因为别的原因对得上；首格是谁才真正反映用户看到的顺序。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h1000dp")
class RefreshKeepsManualOrderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** 刷新后多出来的一张图 —— 刷新必须以「collections 换了」的形式呈现。 */
    private var refreshed by mutableStateOf(false)

    private val baseCollection = collection("c1", pages = 4, extra = 0)
    private val refreshedCollection = collection("c1", pages = 4, extra = 1)

    private fun collections() = listOf(if (refreshed) refreshedCollection else baseCollection)

    private fun setContent() {
        rule.setContent {
            val collections = collections()
            // ---- 与 PixFoldApp.kt 的对账段同构（顺序也要一致）----
            // 上层：key 用「工作区句柄」——同一工作区内刷新不重建
            var workflowAState by remember("handle") { mutableStateOf(workflowAStateOf(collections)) }
            var namingState by remember("handle") {
                mutableStateOf(
                    NamingState(
                        NamingScheme(rootDirName = collections.firstOrNull()?.rootDirName.orEmpty()),
                    ),
                )
            }
            val lastReconciled = remember("handle") { LastReconciled(collections) }
            if (lastReconciled.collections != collections) {
                lastReconciled.collections = collections
                workflowAState = reconcileWorkflowAState(workflowAState, collections)
                namingState = reconcileNamingState(namingState, collections)
            }

            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                WorkflowASteps(
                    collections = collections,
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    externalState = workflowAState,
                    onStateChange = { workflowAState = it },
                    initialNamingState = namingState,
                    onNamingStateChange = { namingState = it },
                )
            }
        }
    }

    @Test
    fun `刷新后首格仍是用户拖过去的那张`() {
        setContent()

        val before = firstCardName()
        assertTrue(before.isNotEmpty(), "首格应有可读文件名")

        // 用户拖拽第一张到后面 —— 首格会变成另一张
        dragFirstCardToThird()
        rule.waitForIdle()
        val afterDrag = firstCardName()
        assertTrue(afterDrag != before, "拖拽后首格应变化（否则本用例前提不成立）：$before → $afterDrag")

        // 工作区刷新（文件管理器里加了一张图）—— collections 换成新实例
        refreshed = true
        rule.waitForIdle()

        assertEquals(
            afterDrag,
            firstCardName(),
            "刷新不该把用户刚拖好的顺序清掉；首格从 $afterDrag 变成了 ${firstCardName()}",
        )
        assertCountText(5)
    }

    private fun assertCountText(expected: Int) {
        val text = com.cyanix.pixfold.TestText.plural(
            com.cyanix.pixfold.R.plurals.a_image_count,
            expected,
            expected,
        )
        val nodes = rule
            .onAllNodes(androidx.compose.ui.test.hasText(text, substring = true))
            .fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty(), "刷新后应显示「$text」；实际没找到，说明新图没进页序")
    }

    // ---- 夹具 ----

    private fun firstCardName(): String {
        val cfg = rule.onAllNodesWithTag(TAG_GRID_CARD)[0].fetchSemanticsNode().config
        val key = SemanticsProperties.ContentDescription
        return if (cfg.contains(key)) cfg[key].firstOrNull() ?: "" else ""
    }

    private fun dragFirstCardToThird() {
        val node = rule.onAllNodesWithTag(TAG_GRID_CARD)[0].fetchSemanticsNode()
        val origin = node.boundsInRoot.topLeft
        val from = node.boundsInRoot.center - origin
        val to = rule.onAllNodesWithTag(TAG_GRID_CARD)[2].fetchSemanticsNode().boundsInRoot.center - origin
        rule.onAllNodesWithTag(TAG_GRID_CARD)[0].performTouchInput {
            down(from)
            advanceEventTime(700)
            moveBy(Offset(0f, -20f))
            moveTo(to)
            up()
        }
    }

    /** 上一次对账依据的集合列表 —— 与 PixFoldApp 里的同名私有类同构。 */
    private class LastReconciled(var collections: List<ImageCollection>)

    private fun collection(id: String, pages: Int, extra: Int): ImageCollection {
        val images = (1..pages + extra).map { index ->
            SourceItem(
                id = "$id#$index",
                collectionId = id,
                dir = "",
                baseName = "%04d".format(index),
                ext = "jpg",
                sizeBytes = 1024,
                modifiedEpochMillis = 0,
                createdEpochMillis = null,
                seed = index,
            )
        }
        return ImageCollection(id = id, name = id, rootDirName = id, images = images)
    }
}
