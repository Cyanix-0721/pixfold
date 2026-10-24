package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.libraryStateOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * 步骤 3 的卷切换芯片必须**限个数**（规模回归护栏）。
 *
 * 与 [MetadataVolumeChipStickyTest] 同源：步骤 2 修好后，步骤 3 的
 * `PackPreviewPage` 卷芯片行仍是「在 `LazyColumn` 之外、无个数上限」的旧形态。
 * 2026-10-18 真机实测（步骤 2 修前）：40 卷时 13 行芯片占 834.9dp（视口 835.3dp），
 * 下方列表永久不可达 —— 步骤 3 的结构完全相同，故风险相同。
 *
 * 为什么单开一个类：`PackPreviewPageTest` 用 `w400dp-h2000dp` 的高视口，
 * 整页一屏装下、`LazyColumn` 不滚动，「滚到底仍可见」在那种配置下天然成立、
 * **证明不了真机行为**（项目既有教训）。故这里用真机等价尺寸 `w361dp-h794dp-560dpi`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class PackVolumeChipScaleTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /** 最近一次上报给上层的状态（用于断言「点了真的切了卷」）。 */
    private var latest: LibraryState? = null

    private fun show(volumes: List<ComicVolume> = library.volumes) {
        rule.setContent {
            var state by remember { mutableStateOf(libraryStateOf(library.rootPath, volumes)) }
            PackPreviewPage(
                libraryState = state,
                onLibraryChange = {
                    state = it
                    latest = it
                },
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    /** 把预览列表滚到页码区最后一行（该页唯一会随卷数变长的内容）。 */
    private fun scrollToBottom() {
        // 规模数据的第 1 卷由 `library.volumes[0]`（aot-1，16 页）派生，
        // 故页码区最后一行是第 16 行（index 15）。
        rule.onNodeWithTag(TAG_PACK_LIST)
            .performScrollToNode(hasTestTag(packPageRowTag(15)))
        rule.waitForIdle()
    }

    /** 造 `count` 卷的规模数据（真机 mock 只有 5 卷，几十卷形态从未在真机出现过）。 */
    private fun manyVolumes(count: Int): List<ComicVolume> = (1..count).map { i ->
        val base = library.volumes[(i - 1) % library.volumes.size]
        base.copy(
            id = "scale-$i",
            title = "规模测试 $i",
            dirPath = "${library.rootPath}\\scale-$i",
        )
    }

    @Test
    fun `overflowing volumes keep the preview list reachable and collapse the tail`() {
        show(manyVolumes(40))

        // 芯片区必须**高度受控**，否则 40 卷时它会把整个视口吃掉、下方预览永久不可达。
        //
        // 2026-10-23 起控高的方式从「只常驻 5 个」换成「3 行收口」：
        // 芯片最窄 96dp、轨道 321dp ⇒ 一行 3 块、3 行装得下 9 块，再留一块收口芯片
        // ⇒ 页面上实际只有前 7 块左右，尾部被「全部 N 项」挡在后面。
        rule.onNodeWithTag(TAG_PACK_VOLUME_ALL).assertIsDisplayed()

        // 为什么必须直接断言尾部芯片不存在：Robolectric 把芯片**文字**宽度算成 ~0，
        // 不靠 `widthIn(min = 96.dp)` 的话 40 个芯片也能塞进 2 行 ⇒「预览内容可达」
        // 那类断言对「不限个数」的变异毫无杀伤力（步骤 2 的同款变异 M1 存活过）。
        //
        // 断第 6 块存在、第 40 块不存在：第 6 块最难的地方也只在第 2 行，一定在收口
        // 之前；第 40 块在 40 卷里怎么排都进不了前 3 行 —— 两个断言合起来才说明
        // 「真的按行截断了，而不是全都不显示或全都显示」。
        rule.onNodeWithTag(packVolumeChipTag("scale-6")).assertIsDisplayed()
        rule.onNodeWithTag(packVolumeChipTag("scale-40")).assertDoesNotExist()

        // 预览内容必须真的可达（滚到页码区最后一行，它在视口内）。
        scrollToBottom()
        rule.onNodeWithTag(packPageRowTag(15)).assertIsDisplayed()
    }

    @Test
    fun `the collapse chip expands every volume in place and switching works`() {
        show(manyVolumes(40))

        // 原地展开（2026-10-23 用户裁决「全部 X 项就不要下面弹出窗口了」）：
        // 不再有 sheet，被挡住的卷直接铺到页面上。
        rule.onNodeWithTag(TAG_PACK_VOLUME_ALL).performClick()
        rule.waitForIdle()

        // 第 40 卷现在真的在组合里（展开态），而且**必须可达**。
        //
        // 2026-10-24 评审 I6 改判：旧版这里用 `assertExists` 绕过「在不在视口内」，
        // 当时芯片行确实没法滚 —— 展开 40 块约 14 行,远超视口,收起入口跟着跑到
        // 视口外,用户被困(评审 C1)。C1 修完后展开态自带
        // `heightIn(max = CHIP_EXPANDED_MAX_HEIGHT)` + `verticalScroll`,
        // `performScrollTo` 因此有了 Scroll 动作可走。
        // 沿用 `assertExists` 就等于默认「看不见也算通过」,而「看不见就点不到」
        // 正是这个组件历史上最贵的缺陷形态。
        val target = rule.onNodeWithTag(packVolumeChipTag("scale-40"))
        target.performScrollTo().assertIsDisplayed().performClick()
        rule.waitForIdle()

        assertEquals("scale-40", latest?.activeVolumeId, "展开后点卷必须真的切卷")

        // 切完之后当前卷必须仍然**可见**（否则用户看不出「我在预览哪一卷」）。
        // 用户 2026-10-23 裁决的是「延续瀑布流显示」——看得见才算显示。
        rule.onNodeWithTag(packVolumeChipTag("scale-40")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(packVolumeChipTag("scale-40")).assertIsSelected()
    }

    @Test
    fun `five volumes show no all-volumes entry`() {
        show()

        // 5 卷是今天的真机形态 ⇒ 不降级：不出现多余入口，5 个芯片全部可见。
        rule.onNodeWithTag(TAG_PACK_VOLUME_ALL).assertDoesNotExist()
        rule.onNodeWithTag(packVolumeChipTag("aot-1")).assertIsDisplayed()
        rule.onNodeWithTag(packVolumeChipTag("op-102")).assertIsDisplayed()
    }
}
