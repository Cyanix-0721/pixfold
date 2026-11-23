package com.cyanix.pixfold.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.ui.PixFoldNativeTestHost
import com.cyanix.pixfold.ui.PixFoldUiTestDefaults
import com.cyanix.pixfold.ui.assertAccessibleTargets
import com.cyanix.pixfold.ui.assertAllNodesFitViewport
import com.cyanix.pixfold.ui.assertFitsViewport
import com.cyanix.pixfold.ui.assertRowsFitViewport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * 芯片行与两级系列浮层的纯 JVM 原生渲染（`@GraphicsMode(NATIVE)`）布局守卫。
 *
 * 与运行在 `LEGACY` 下的 `ChipLayoutRulesTest` 形成互补：
 * - `LEGACY` 将文字宽打桩为 ~0，所有 `SingleSelectChip` 恒被 `min=96dp` 撑成同宽；
 * - 本套件使用本地 `robolectric-nativeruntime.dll` + `NotoSansCJK` / `Roboto` 真实排版，
 *   验证长中文/英文标签真实测量宽度、区间 `[96dp, 156dp]`、3 行收口、原地展开、
 *   两级系列浮层不越出 `361dp` 视口、同级零重叠、以及 `fontScale = 1.0f / 1.3f` 下的无障碍目标尺寸。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeChipLayoutTest {

    @get:Rule
    val rule = createComposeRule()

    private val mixedItems = listOf(
        "短名",
        "超长中文系列名称测试用于验证原生排版宽度",
        "Very Long English Comic Volume Title For Native Layout",
        "第01卷",
        "另一个超长日文与中文混合作品名合集测试第02卷",
        "Short",
        "中长标题测试第03卷",
        "Series Alpha Vol 04",
        "番外篇合集特别收录第05卷",
        "Standalone Extra 06",
        "长标题第七卷归档预览样本",
        "Final Volume 08",
    )

    private fun showSingleSelectRow(
        items: List<String> = mixedItems,
        fontScale: Float = 1.0f,
    ) {
        rule.setContent {
            PixFoldNativeTestHost(fontScale = fontScale) {
                SingleSelectChipRow(
                    items = items,
                    activeId = items.first(),
                    idOf = { it },
                    onSelect = {},
                    selectorTag = TAG_ROW,
                    chipTag = { id -> "chip-$id" },
                    allEntryTag = TAG_ALL,
                    chipDescription = { it },
                    allEntryText = { n -> "全部 $n 项" },
                    chipLabel = { it },
                )
            }
        }
    }

    private fun sampleGroups(): List<TwoLevelGroup<String>> = listOf(
        TwoLevelGroup(
            key = "series:long-cn",
            name = "超长中文系列名称测试用于验证两级浮层排布",
            isSeries = true,
            members = listOf(
                "第01卷 初遇篇",
                "第02卷 超长副标题测试用来验证浮层内芯片自动换行",
                "第03卷 English Subtitle Edition",
                "第04卷 完结篇",
            ),
        ),
        TwoLevelGroup(
            key = "single:short",
            name = "单本短名",
            isSeries = false,
            members = listOf("单本短名"),
        ),
        TwoLevelGroup(
            key = "series:en",
            name = "Long English Anthology Series Collection",
            isSeries = true,
            members = listOf(
                "Volume 01 Extended Cut",
                "Volume 02 Bonus Chapters",
            ),
        ),
    )

    private fun showTwoLevelRows(fontScale: Float = 1.0f) {
        val groups = sampleGroups()
        rule.setContent {
            PixFoldNativeTestHost(fontScale = fontScale) {
                TwoLevelChipRows(
                    groups = groups,
                    activeItemId = groups.first().members.first(),
                    idOf = { it },
                    onSelect = {},
                    topTags = ChipRowTags(
                        selectorTag = TAG_TOP_ROW,
                        chipTag = { key -> "top-$key" },
                        allEntryTag = TAG_TOP_ALL,
                    ),
                    childTags = ChipRowTags(
                        selectorTag = TAG_CHILD_ROW,
                        chipTag = { id -> "child-$id" },
                        allEntryTag = TAG_CHILD_ALL,
                    ),
                    singleTopChipTag = { id -> "single-$id" },
                    topChipDescription = { it.name },
                    topChipLabel = { it.name },
                    topAllEntryText = { n -> "全部 $n 项" },
                    childChipDescription = { it },
                    childChipLabel = { it },
                    childAllEntryText = { n -> "全部 $n 卷" },
                )
            }
        }
    }

    /**
     * 统计一组芯片矩形的真实视觉行数：同一行内单行芯片与多行芯片高度不同
     * （且 FilterChip 的 minimumInteractiveComponentSize 会把较矮芯片垂直居中），
     * 但在纵向上必然重叠；而不同行之间由 verticalGap(10dp) 隔开、纵向严格分离。
     */
    private fun distinctRowCount(rects: List<DpRect>): Int {
        if (rects.isEmpty()) return 0
        val sorted = rects.sortedBy { it.top }
        var rows = 1
        var currentRowBottom = sorted.first().bottom
        for (i in 1 until sorted.size) {
            val rect = sorted[i]
            if (rect.top >= currentRowBottom - PixFoldUiTestDefaults.SUBPIXEL_TOLERANCE_DP) {
                rows++
                currentRowBottom = rect.bottom
            } else if (rect.bottom > currentRowBottom) {
                currentRowBottom = rect.bottom
            }
        }
        return rows
    }

    @Test
    fun `native CJK and Latin chip measurement genuinely expands beyond LEGACY stub and stays in 96 to 156dp`() {
        val items = listOf(
            "短名",
            "超长中文系列名称测试用于验证原生排版宽度",
            "Very Long English Comic Volume Title For Native Layout",
        )
        showSingleSelectRow(items = items, fontScale = 1.0f)

        val shortChip = rule.onNodeWithTag("chip-${items[0]}")
        val longCjkChip = rule.onNodeWithTag("chip-${items[1]}")
        val longLatinChip = rule.onNodeWithTag("chip-${items[2]}")

        val shortBounds = shortChip.getUnclippedBoundsInRoot()
        val longCjkBounds = longCjkChip.getUnclippedBoundsInRoot()
        val longLatinBounds = longLatinChip.getUnclippedBoundsInRoot()

        val shortW = shortBounds.right - shortBounds.left
        val longCjkW = longCjkBounds.right - longCjkBounds.left
        val longLatinW = longLatinBounds.right - longLatinBounds.left
        val shortH = shortBounds.bottom - shortBounds.top
        val longCjkH = longCjkBounds.bottom - longCjkBounds.top

        // LEGACY 后端文字宽为 ~0，所有芯片恒等于 96.0dp；NATIVE 下长中文/英文必然撑宽至接近 156dp 上限。
        assertTrue(
            shortW in 95.5.dp..110.dp,
            "短标签芯片宽度应贴近下限 96dp，实测 ${shortW.value}dp",
        )
        assertTrue(
            longCjkW >= 130.dp && longCjkW <= 156.5.dp,
            "长中文标签在 NATIVE 模式下必须真实测宽并落在 [130, 156]dp（非 LEGACY 96dp 打桩），实测 ${longCjkW.value}dp",
        )
        assertTrue(
            longLatinW >= 130.dp && longLatinW <= 156.5.dp,
            "长英文标签在 NATIVE 模式下必须真实测宽并落在 [130, 156]dp，实测 ${longLatinW.value}dp",
        )
        assertTrue(
            longCjkW > shortW + 24.dp,
            "长中文芯片(${longCjkW.value}dp)必须明显宽于短标签芯片(${shortW.value}dp)",
        )
        assertTrue(
            longCjkH > shortH + 8.dp,
            "长中文芯片在 156dp 上限内必须真实折成多行（实测高 ${longCjkH.value}dp vs 单行 ${shortH.value}dp）",
        )

        assertRowsFitViewport(listOf(shortChip, longCjkChip, longLatinChip))
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    @Test
    fun `single select chip row collapses at 3 lines and expands within 361dp in zh-rCN at 1_0x`() {
        verifyCollapseAndExpand(fontScale = 1.0f)
    }

    @Test
    fun `single select chip row collapses at 3 lines and expands within 361dp in zh-rCN at 1_3x`() {
        verifyCollapseAndExpand(fontScale = 1.3f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `single select chip row collapses at 3 lines and expands within 361dp in en at 1_0x`() {
        verifyCollapseAndExpand(fontScale = 1.0f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `single select chip row collapses at 3 lines and expands within 361dp in en at 1_3x`() {
        verifyCollapseAndExpand(fontScale = 1.3f)
    }

    private fun verifyCollapseAndExpand(fontScale: Float) {
        showSingleSelectRow(items = mixedItems, fontScale = fontScale)

        val collapseChip = rule.onNodeWithTag(TAG_ALL)
        collapseChip.assertIsDisplayed()
        assertFitsViewport(collapseChip, label = "CollapseChip")

        // 找出当前收起态下实际渲染在语义树里的芯片 + 收口芯片
        val visibleNodes = mixedItems.mapNotNull { id ->
            val node = rule.onNodeWithTag("chip-$id")
            if (runCatching { node.fetchSemanticsNode() }.isSuccess) node else null
        } + collapseChip
        assertTrue(visibleNodes.size < mixedItems.size + 1, "收起态下不应渲染全部 ${mixedItems.size} 块芯片")

        val collapsedRows = distinctRowCount(visibleNodes.map { it.getUnclippedBoundsInRoot() })
        assertTrue(
            collapsedRows <= CHIP_COLLAPSE_LINES,
            "收起态视觉行数($collapsedRows)不得超过 CHIP_COLLAPSE_LINES($CHIP_COLLAPSE_LINES)",
        )
        assertRowsFitViewport(visibleNodes)
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)

        // 点击展开：全部 12 块芯片 + 「收起」芯片均落在 361dp 内且无同级重叠
        collapseChip.performClick()
        rule.waitForIdle()

        val expandedNodes = mixedItems.map { id -> rule.onNodeWithTag("chip-$id") } + collapseChip
        expandedNodes.forEach { it.fetchSemanticsNode() }
        assertRowsFitViewport(expandedNodes)
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    @Test
    fun `two level series popup opens within 361dp with zero overlap in zh-rCN at 1_0x`() {
        verifyTwoLevelPopup(fontScale = 1.0f)
    }

    @Test
    fun `two level series popup opens within 361dp with zero overlap in zh-rCN at 1_3x`() {
        verifyTwoLevelPopup(fontScale = 1.3f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `two level series popup opens within 361dp with zero overlap in en at 1_0x`() {
        verifyTwoLevelPopup(fontScale = 1.0f)
    }

    @Test
    @Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_EN_PHONE)
    fun `two level series popup opens within 361dp with zero overlap in en at 1_3x`() {
        verifyTwoLevelPopup(fontScale = 1.3f)
    }

    private fun verifyTwoLevelPopup(fontScale: Float) {
        val groups = sampleGroups()
        showTwoLevelRows(fontScale = fontScale)

        val firstSeries = groups.first()
        rule.onNodeWithTag("top-${firstSeries.key}").performClick()
        rule.waitForIdle()

        val childNodes = firstSeries.members.map { id ->
            val node = rule.onNodeWithTag("child-$id")
            node.assertIsDisplayed()
            node
        }
        assertRowsFitViewport(childNodes)
        assertAllNodesFitViewport(rule)
        assertAccessibleTargets(rule)
    }

    private companion object {
        const val TAG_ROW = "native-chip-row"
        const val TAG_ALL = "native-chip-all"
        const val TAG_TOP_ROW = "native-two-level-top"
        const val TAG_TOP_ALL = "native-two-level-top-all"
        const val TAG_CHILD_ROW = "native-two-level-child"
        const val TAG_CHILD_ALL = "native-two-level-child-all"
    }
}
