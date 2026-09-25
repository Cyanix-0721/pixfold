package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 钉住一条**语义树读坐标的实测规律** —— 「浮层是否跟手」这类断言能不能成立全看它。
 *
 * ## 事实(2026-09-27 用一次性探针逐项实测)
 *
 * | 变换挂在哪 | `onGloballyPositioned { boundsInRoot() }` | 语义树 `boundsInRoot` |
 * | --- | --- | --- |
 * | **本节点**:`padding` / `offset` / `graphicsLayer` | ✅ 反映 | ❌ **不反映** |
 * | **祖先节点**:`graphicsLayer`(平移与缩放) | ✅ 反映 | ✅ **反映** |
 *
 * 推论:`testTag` **挂在图层节点自身上**时,读到的 bounds 是**布局前**的原始位置 ⇒
 * 拖拽浮层的位移在离线断言里**恒不可见**,断言会变成恒真式(变异无法杀死)。
 * 因此 [DragGhost] 把 tag 挂在图层的**子**节点上。
 *
 * **反向验证**:`TransitionStyleTest` 之所以能观察到根级 `scaleOut` 的缩放,正是因为
 * 那个图层挂在页面容器的**祖先**上,而不是被测节点自身。
 *
 * ## 本测试与真正的守卫的分工
 *
 * 本类测的是**平台契约**,不引用 [DragGhost] —— 它证明"这条规律确实如此",是给
 * 后来者看的书面依据。**真正守护生产代码的是 `DragReorderTest` / `DragReorderListTest`
 * 里的「ghost follows the pointer」两条**:把 tag 挪回图层节点后,失败的是它们
 * (已由变异 G2 实测确认),不是本类。别把两者搞混 —— 本类红说明平台行为变了(那时
 * 该重新评估 tag 位置策略),生产测试红才说明代码被改坏了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class DragGhostBoundsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `same node layer is invisible to semantics bounds but visible to onGloballyPositioned`() {
        var coords = ""
        rule.setContent {
            Box(modifier = Modifier.size(400.dp)) {
                Box(
                    modifier = Modifier
                        .testTag("same-node")
                        .size(100.dp, 50.dp)
                        .graphicsLayer {
                            translationX = 30f
                            translationY = 20f
                        }
                        .onGloballyPositioned { coords = it.boundsInRoot().toString() },
                )
            }
        }
        val sem = rule.onNodeWithTag("same-node").fetchSemanticsNode().boundsInRoot

        assertTrue(
            coords == "Rect.fromLTRB(30.0, 20.0, 130.0, 70.0)",
            "onGloballyPositioned 应反映本节点图层平移,实测 coords=$coords",
        )
        assertTrue(
            sem.toString() == "Rect.fromLTRB(0.0, 0.0, 100.0, 50.0)",
            "语义树 boundsInRoot 对本节点自身的图层变换**不敏感** —— 这正是必须把 tag 挂到子节点的原因,实测 sem=$sem",
        )
    }

    @Test
    fun `ancestor layer is visible to semantics bounds`() {
        rule.setContent {
            Box(modifier = Modifier.size(400.dp)) {
                Box(
                    modifier = Modifier
                        .size(100.dp, 50.dp)
                        .offset { IntOffset(30, 20) },
                ) {
                    Box(modifier = Modifier.testTag("child").size(100.dp, 50.dp))
                }
            }
        }
        val sem = rule.onNodeWithTag("child").fetchSemanticsNode().boundsInRoot

        assertTrue(
            sem.toString() == "Rect.fromLTRB(30.0, 20.0, 130.0, 70.0)",
            "祖先节点的位移**会**计入子节点语义 bounds —— DragGhost 的 tag 依赖这条规律,实测 sem=$sem",
        )
    }
}
