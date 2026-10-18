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
import com.pixfold.d1.ui.components.visibleVolumeChips
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
 * 步骤 2 的**卷切换芯片必须常驻视口**（P2 修复的回归护栏）。
 *
 * 为什么单开一个类而不并入 `MetadataPageTest`：那个类的
 * `@Config(qualifiers = "w400dp-h2000dp")` 用的是 2000dp 高的视口，整页内容
 * 一屏就装下了、`LazyColumn` 根本不会滚动 —— 在那种配置下「滚到底芯片仍可见」
 * 天然成立，**证明不了真机行为**（项目既有教训：Robolectric 高视口会掩盖
 * 「放不下」类缺陷）。故这里显式用真机等价尺寸 `w361dp-h794dp-560dpi`。
 *
 * 真机实测依据（2026-10-18，PJX110 / Android 16 / 1264×2780px / 560dpi ⇒
 * 361dp × 794dp）：可视区 2297px = 835.3dp，页面内容 3729px = 1356.0dp，
 * 一次全屏 swipe 后 `content-desc` 以「卷 」开头的节点数为 **0** ⇒ 芯片滚出视口，
 * 换卷必须先回顶；且换卷后 `LazyColumn` 内容重建导致滚动位置回到顶部，
 * 构成「换卷 → 回顶 → 再滚回原字段」的来回。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
class MetadataVolumeChipStickyTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /** 最近一次上报给上层的状态（用于断言「点了真的切了卷」）。 */
    private var latest: WorkflowBState? = null

    private fun show(volumes: List<ComicVolume> = library.volumes) {
        rule.setContent {
            var state by remember { mutableStateOf(workflowBStateOf(library.rootPath, volumes)) }
            MetadataPage(
                libraryState = state,
                onLibraryChange = {
                    state = it
                    latest = it
                },
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    /** 把列表滚到最后一个可操作控件（批量设置的应用按钮）。 */
    private fun scrollToBottom() {
        rule.onNodeWithTag(TAG_METADATA_LIST).performScrollToNode(hasTestTag(TAG_BATCH_APPLY))
        rule.waitForIdle()
    }

    @Test
    fun `volume chips stay visible after scrolling to the bottom`() {
        show()
        scrollToBottom()

        // 前提断言：确实滚到了底部（否则下面的「仍可见」是假绿）。
        rule.onNodeWithTag(TAG_BATCH_APPLY).assertIsDisplayed()

        // 真机缺陷：此时芯片全部滚出视口 ⇒ 换卷必须回顶。
        rule.onNodeWithTag(volumeChipTag("aot-1")).assertIsDisplayed()
    }

    @Test
    fun `switching volume keeps the scroll position`() {
        show()
        scrollToBottom()

        rule.onNodeWithTag(volumeChipTag("aot-2")).performClick()
        rule.waitForIdle()

        assertEquals("aot-2", latest?.activeVolumeId, "点芯片必须真的切卷")
        // 换卷后不得回顶 —— 否则「换卷 → 回顶 → 再滚回原字段」的来回仍在。
        rule.onNodeWithTag(TAG_BATCH_APPLY).assertIsDisplayed()
    }

    /**
     * 造 `count` 卷的规模数据（真机 mock 只有 5 卷，芯片恰好 2 行排满，
     * 「几十卷」形态从未在真机出现过 —— 2026-10-18 用临时改 mock 的方式实测到
     * 40 卷时芯片区吃掉整个视口、字段永久不可达，见本轮回归记录）。
     */
    private fun manyVolumes(count: Int): List<ComicVolume> = (1..count).map { i ->
        val base = library.volumes[(i - 1) % library.volumes.size]
        base.copy(
            id = "scale-$i",
            title = "规模测试 $i",
            dirPath = "${library.rootPath}\\scale-$i",
        )
    }

    @Test
    fun `overflowing volumes keep fields reachable and expose the all-volumes entry`() {
        show(manyVolumes(40))

        // 芯片区必须限高，否则 40 卷时它会吃掉整个视口 —— 真机实测（2026-10-18）
        // 13 行芯片占 834.9dp（视口 835.3dp），11 次全屏 swipe 后字段仍全部不可见。
        // 限高后溢出卷必须有一个入口，否则后面的卷切换不了。
        rule.onNodeWithTag(TAG_VOLUME_ALL).assertIsDisplayed()

        // 常驻行必须**真的只组合 5 个芯片**，第 6 卷起不进常驻行。
        // 为什么必须直接断言个数：Robolectric 把芯片宽度算成 ~0，40 个芯片也能塞进
        // 2 行 ⇒「字段可达」那类断言对「不限个数」的变异毫无杀伤力（变异 M1 存活过）。
        rule.onNodeWithTag(volumeChipTag("scale-6")).assertDoesNotExist()
        rule.onNodeWithTag(volumeChipTag("scale-40")).assertDoesNotExist()

        // 字段必须真的可达（滚到底后最后一个可操作控件在视口内）。
        scrollToBottom()
        rule.onNodeWithTag(TAG_BATCH_APPLY).assertIsDisplayed()
    }

    @Test
    fun `all-volumes sheet lists every volume and switching works`() {
        show(manyVolumes(40))

        rule.onNodeWithTag(TAG_VOLUME_ALL).performClick()
        rule.waitForIdle()

        // 溢出的卷必须在 sheet 里能点到（scale-40 是第 40 卷，2 行芯片必然装不下）。
        // sheet 内是可滚动的全量列表，先滚到最后一卷再点。
        rule.onNodeWithTag(TAG_VOLUME_SHEET_LIST)
            .performScrollToNode(hasTestTag(volumeSheetChipTag("scale-40")))
        rule.waitForIdle()
        rule.onNodeWithTag(volumeSheetChipTag("scale-40")).performClick()
        rule.waitForIdle()

        assertEquals("scale-40", latest?.activeVolumeId, "sheet 里点卷必须真的切卷")

        // 切完之后当前卷必须回到页面常驻行里（否则用户看不出"我在改哪一卷"）。
        rule.onNodeWithTag(volumeChipTag("scale-40")).assertIsDisplayed()
    }

    @Test
    fun `five volumes show no all-volumes entry`() {
        show()

        // 5 卷恰好 2 行排满 ⇒ 不降级：不出现多余入口，5 个芯片全部可见。
        rule.onNodeWithTag(TAG_VOLUME_ALL).assertDoesNotExist()
        rule.onNodeWithTag(volumeChipTag("aot-1")).assertIsDisplayed()
        rule.onNodeWithTag(volumeChipTag("op-102")).assertIsDisplayed()
    }

    // ---- visibleVolumeChips：溢出时常驻哪几卷（纯函数，直接断言，不依赖布局测量）----

    @Test
    fun `visible chips keep the active volume in the row`() {
        val volumes = manyVolumes(40)

        // 当前卷在尾部（会被截掉的那部分）⇒ 必须顶替最后一个常驻位。
        val kept = visibleVolumeChips(volumes, "scale-40")
        assertEquals(5, kept.size, "溢出时常驻 5 个芯片")
        assertEquals("scale-40", kept.last().id, "当前卷必须在常驻行内，否则看不出在改哪一卷")

        // 当前卷已在前 5 卷里 ⇒ 常驻 5 个不变，顺序不动。
        val keptHead = visibleVolumeChips(volumes, "scale-2")
        assertEquals(listOf("scale-1", "scale-2", "scale-3", "scale-4", "scale-5"), keptHead.map { it.id })

        // 当前卷不在列表里（脏状态）⇒ 退化成前 5 卷，不抛异常。
        assertEquals(5, visibleVolumeChips(volumes, "no-such-id").size)
    }
}
