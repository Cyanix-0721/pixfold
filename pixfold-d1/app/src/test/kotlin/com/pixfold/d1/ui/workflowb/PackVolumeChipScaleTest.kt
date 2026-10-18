package com.pixfold.d1.ui.workflowb

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.workflowb.WorkflowBState
import com.pixfold.d1.domain.workflowb.workflowBStateOf
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
@Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
class PackVolumeChipScaleTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /** 最近一次上报给上层的状态（用于断言「点了真的切了卷」）。 */
    private var latest: WorkflowBState? = null

    private fun show(volumes: List<ComicVolume> = library.volumes) {
        rule.setContent {
            var state by remember { mutableStateOf(workflowBStateOf(library.rootPath, volumes)) }
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
    fun `overflowing volumes keep the preview list reachable and expose the all-volumes entry`() {
        show(manyVolumes(40))

        // 芯片行必须限个数，否则 40 卷时它会把整个视口吃掉、下方预览永久不可达。
        // 限个数后溢出卷必须有一个入口，否则后面的卷切换不了。
        rule.onNodeWithTag(TAG_PACK_VOLUME_ALL).assertIsDisplayed()

        // 常驻行必须**真的只组合 5 个芯片**，第 6 卷起不进常驻行。
        // 为什么必须直接断言个数：Robolectric 把芯片宽度算成 ~0，40 个芯片也能塞进
        // 2 行 ⇒「预览内容可达」那类断言对「不限个数」的变异毫无杀伤力（步骤 2 的
        // 同款变异 M1 就存活过）。
        rule.onNodeWithTag(packVolumeChipTag("scale-6")).assertDoesNotExist()
        rule.onNodeWithTag(packVolumeChipTag("scale-40")).assertDoesNotExist()

        // 预览内容必须真的可达（滚到页码区最后一行，它在视口内）。
        scrollToBottom()
        rule.onNodeWithTag(packPageRowTag(15)).assertIsDisplayed()
    }

    @Test
    fun `all-volumes sheet lists every volume and switching works`() {
        show(manyVolumes(40))

        rule.onNodeWithTag(TAG_PACK_VOLUME_ALL).performClick()
        rule.waitForIdle()

        // 溢出的卷必须在 sheet 里能点到（scale-40 是第 40 卷，常驻行必然装不下）。
        rule.onNodeWithTag(TAG_PACK_VOLUME_SHEET_LIST)
            .performScrollToNode(hasTestTag(packVolumeSheetChipTag("scale-40")))
        rule.waitForIdle()
        rule.onNodeWithTag(packVolumeSheetChipTag("scale-40")).performClick()
        rule.waitForIdle()

        assertEquals("scale-40", latest?.activeVolumeId, "sheet 里点卷必须真的切卷")

        // 切完之后当前卷必须回到页面常驻行里（否则用户看不出「我在预览哪一卷」）。
        rule.onNodeWithTag(packVolumeChipTag("scale-40")).assertIsDisplayed()
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
