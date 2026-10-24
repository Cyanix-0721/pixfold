package com.cyanix.pixfold.ui.library

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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.source.SERIES_PREFIX
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
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
class MetadataVolumeChipStickyTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /** 最近一次上报给上层的状态（用于断言「点了真的切了卷」）。 */
    private var latest: LibraryState? = null

    private fun show(volumes: List<ComicVolume> = library.volumes) {
        rule.setContent {
            var state by remember { mutableStateOf(libraryStateOf(library.rootPath, volumes)) }
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

    /** 浮层里点一卷。浮层由 1 级系列芯片为顶点打开(2026-10-23 起 2 级不再常驻)。 */
    private fun pickVolumeInPopup(seriesName: String, volumeId: String) {
        rule.onNodeWithTag(volumeGroupTag(SERIES_PREFIX + seriesName)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(volumeChipTag(volumeId)).performScrollTo().performClick()
        rule.waitForIdle()
    }

    @Test
    fun `volume chips stay visible after scrolling to the bottom`() {
        show()
        scrollToBottom()

        // 前提断言：确实滚到了底部（否则下面的「仍可见」是假绿）。
        rule.onNodeWithTag(TAG_BATCH_APPLY).assertIsDisplayed()

        // 真机缺陷：此时芯片全部滚出视口 ⇒ 换卷必须回顶。
        //
        // 2026-10-23 起常驻的是**1 级系列芯片**,具体的卷在浮层里(点开才有);
        // 所以「芯片常驻」这条要断 1 级,断 `aot-1` 会变成「断一个页面上本就没有的东西」。
        rule.onNodeWithTag(volumeGroupTag(SERIES_PREFIX + "进击的巨人")).assertIsDisplayed()
    }

    @Test
    fun `switching volume keeps the scroll position`() {
        show()
        scrollToBottom()

        pickVolumeInPopup(seriesName = "进击的巨人", volumeId = "aot-2")

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
        // 全部取同一个 base(同一 series):规模缺陷的真实形态就是「一个系列几十卷」,
        // 此时 2 级卷行才会溢出。轮流取 5 个 mock 卷会得到 2 个系列、成员分散在两条
        // 分支上,「第 6 卷不进常驻行」这类断言会因成员 id 不连续而误判(2026-10-19 实测)。
        val base = library.volumes.first()
        base.copy(
            id = "scale-$i",
            title = "规模测试 $i",
            dirPath = "${library.rootPath}\\scale-$i",
        )
    }


    @Test
    fun `overflowing volumes keep the page fields reachable`() {
        show(manyVolumes(40))

        // 芯片区必须**高度受控**，否则 40 卷时它会吃掉整个视口 —— 真机实测（2026-10-18）
        // 13 行芯片占 834.9dp（视口 835.3dp），11 次全屏 swipe 后字段仍全部不可见。
        //
        // 2026-10-23 起控高的方式从「只常驻 5 个 + sheet 兜底」换成「3 行收口 + 原地展开」。
        // 40 卷同属一个系列 ⇒ 1 级行只有 1 块芯片、根本不收口；溢出的那 40 卷关在**浮层**里
        // （点开 series 才出现），页面默认根本没有它们。
        // ⇒ 这里该断的是「页面主体不被芯片区挤走」，而不是「出现了 2 级入口」。
        rule.onNodeWithTag(TAG_VOLUME_ALL).assertDoesNotExist()

        // 字段必须真的可达（滚到底后最后一个可操作控件在视口内）。
        // 这一条是那轮真机缺陷的正面判据，比任何结构断言都直接。
        scrollToBottom()
        rule.onNodeWithTag(TAG_BATCH_APPLY).assertIsDisplayed()
    }

    @Test
    fun `the series popup lists every volume and switching works`() {
        show(manyVolumes(40))

        // 浮层内的卷不在默认页面上 —— 必须先点开 1 级系列芯片（用户真实路径）。
        rule.onNodeWithTag(volumeGroupTag(SERIES_PREFIX + "进击的巨人")).performClick()
        rule.waitForIdle()

        // 40 卷全在同一系列 ⇒ 全部在浮层里。浮层高度上限 318dp、内部竖滚；
        // 浮层内是 Column + verticalScroll（不是 Lazy），节点都在树上，可直接滚到再点。
        rule.onNodeWithTag(volumeChipTag("scale-40")).performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals("scale-40", latest?.activeVolumeId, "浮层里点卷必须真的切卷")

        // 切完之后当前卷必须仍然可见（否则用户看不出「我在改哪一卷」）。
        rule.onNodeWithTag(volumeChipTag("scale-40")).assertIsDisplayed()
    }

    @Test
    fun `five volumes show no collapse entry`() {
        show()

        // mock 5 卷 = 两个系列（进击的巨人 3 卷 / 海贼王 2 卷）。
        // 1 级行只有 2 块芯片 ⇒ 远不到 3 行，不该收口。
        // （旧版这里断的是「2 级行不出现『全部 N 卷』入口」—— 2 级已进浮层，
        //   该语义由 the series popup lists every volume and switching works 覆盖。）
        rule.onNodeWithTag(TAG_VOLUME_GROUP_ALL).assertDoesNotExist()
        rule.onNodeWithTag(volumeGroupTag(SERIES_PREFIX + "进击的巨人")).assertIsDisplayed()

        // 具体的卷都在浮层里 —— 必须走用户真实路径：点 1 级系列芯片打开浮层，
        // 再在浮层里找目标卷。
        rule.onNodeWithTag(volumeGroupTag(SERIES_PREFIX + "海贼王")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(volumeChipTag("op-102")).assertIsDisplayed()
    }
}
