package com.cyanix.pixfold.ui.workflowa

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.source.SERIES_PREFIX
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 两级集合芯片的 UI 契约(用户 2026-10-19 裁决,原话「芯片分2级别,series或单本作为1级,
 * series作为2级,如果选择series,收缩series芯片组到一行,下面添加显示2级芯片组选择
 * series下的单本」)。
 *
 * ## 本类守护什么
 *
 * - **1 级 / 2 级的显隐**:没选中系列时只有 1 级行;选中系列后 2 级行出现并列出各单本。
 * - **单本沿用既有 tag**:`collectionTag(id)` —— 18 个既有测试按它点芯片,语义没变。
 * - **「1 系列含 2 本」必须能分别选中**:这是用户实测缺陷的回归
 *   (原判据在「一个系列两本书」结构下把两本并成一个集合,步骤 1 无法区分)。
 * - **系列间来回切时记住上次那本**:否则每次切回系列都跳第一本,用户在 2 级行里做的选择白费。
 *
 * ## 本类**不**守护什么
 *
 * 「收缩到一行」(`maxLines = 1`)的**实际视觉效果** —— Robolectric 不做真实文本测量,
 * 芯片宽度一律算成 ~0dp,换行与否离线断不出(项目既有结论,见
 * `docs/notes/d1-ui-friction-inventory.md`)。故那条只做结构守卫
 * (`ChipRowWrappingTest.collection_chips_are_two_level`),视觉留真机核对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w361dp-h794dp-560dpi")
class CollectionChipRowsTest {

    @get:Rule
    val rule = createComposeRule()

    /** 宿主:把 activeId 做成可变状态,模拟真实调用方的受控用法。 */
    private fun setContent(collections: List<ImageCollection>, initial: String) {
        rule.setContent {
            var activeId by remember { mutableStateOf(initial) }
            PixFoldTheme(darkTheme = false, dynamicColor = false) {
                CollectionChipRows(
                    collections = collections,
                    activeId = activeId,
                    onSelect = { activeId = it },
                )
            }
        }
    }

    // ---- 1 级:单本 ----

    @Test
    fun `单本集合的芯片沿用 collectionTag`() {
        setContent(listOf(collection("trip"), collection("scan")), initial = "trip")

        rule.onNodeWithTag(collectionTag("trip")).assertIsDisplayed()
        rule.onNodeWithTag(collectionTag("scan")).assertIsDisplayed()
    }

    @Test
    fun `未选中系列时不渲染 2 级芯片行`() {
        setContent(
            listOf(collection("trip"), seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "trip",
        )

        assertTrue(
            rule.onAllNodesWithTag(TAG_COLLECTION_BOOK_ROW).fetchSemanticsNodes().isEmpty(),
            "当前选中项是单本,不该出现 2 级行",
        )
    }

    // ---- 1 系列含 2 本:两本必须能分别选中(用户实测缺陷的回归) ----

    @Test
    fun `一个系列含两本时渲染系列芯片与两级行`() {
        setContent(
            listOf(seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "S/第01卷",
        )

        rule.onNodeWithTag(TAG_COLLECTION_ROW).assertIsDisplayed()
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).assertIsDisplayed()
        // 选中项属于系列 ⇒ 2 级行出现,两本都在、且可按各自 tag 点
        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()
        rule.onNodeWithTag(collectionTag("S/第01卷")).assertIsDisplayed()
        rule.onNodeWithTag(collectionTag("S/第02卷")).assertIsDisplayed()
    }

    @Test
    fun `系列芯片的标签含系列名与图片总数`() {
        setContent(
            listOf(
                seriesBook("S", "S/第01卷", pages = 3),
                seriesBook("S", "S/第02卷", pages = 4),
            ),
            initial = "S/第01卷",
        )

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S"))
            .assertTextContainsFragments("S", "7")
    }

    // ---- 2 级行为 ----

    @Test
    fun `点击 1 级系列芯片会切到该系列的第一本`() {
        val collections = listOf(
            collection("trip"),
            seriesBook("S", "S/第01卷"),
            seriesBook("S", "S/第02卷"),
        )
        setContent(collections, initial = "trip")

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()
        rule.onNodeWithTag(collectionTag("S/第01卷")).assertIsDisplayed()
    }

    @Test
    fun `点击 2 级芯片切换系列内的书`() {
        setContent(
            listOf(seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "S/第01卷",
        )

        rule.onNodeWithTag(collectionTag("S/第02卷")).performClick()
        rule.waitForIdle()

        // 仍在系列里 ⇒ 2 级行还在(不是被 click 弄丢)
        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()
    }

    @Test
    fun `切到别的系列再切回来会回到上次看的那一本`() {
        setContent(
            listOf(
                seriesBook("A", "A/第01卷"),
                seriesBook("A", "A/第02卷"),
                seriesBook("B", "B/第01卷"),
            ),
            initial = "A/第01卷",
        )

        // 在 A 里选第二本
        rule.onNodeWithTag(collectionTag("A/第02卷")).performClick()
        rule.waitForIdle()

        // 切到 B,再切回 A
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}B")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}A")).performClick()
        rule.waitForIdle()

        // 应回到 A 的第二本,而不是永远跳第一本。
        // **必须断选中态,不能只断 displayed**:切回来后 A 的两本都在 2 级行里,两本都
        // 「可见」—— 只断 displayed 的话,「永远跳第一本」的变异照样能绿(2026-10-19 M16 存活)。
        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()
        rule.onNodeWithTag(collectionTag("A/第02卷")).assertIsSelected()
        rule.onNodeWithTag(collectionTag("A/第01卷")).assertIsNotSelected()
    }

    @Test
    fun `重复点击当前系列的芯片不会把用户甩回第一本`() {
        setContent(
            listOf(seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "S/第02卷",
        )

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()

        // 已在该系列内:不该换书
        rule.onNodeWithTag(collectionTag("S/第02卷")).assertIsDisplayed()
    }

    // ---- 不渲染的边界 ----

    @Test
    fun `只有一个单本集合时不渲染芯片行`() {
        setContent(listOf(collection("solo")), initial = "solo")

        assertTrue(
            rule.onAllNodesWithTag(TAG_COLLECTION_ROW).fetchSemanticsNodes().isEmpty(),
            "只有一个单本集合时不该渲染芯片行",
        )
    }

    // ---- 夹具 ----

    private fun collection(id: String, pages: Int = 1) = ImageCollection(
        id = id,
        name = id,
        rootDirName = id,
        images = (1..pages).map { page(id, it) },
    )

    private fun seriesBook(series: String, id: String, pages: Int = 1) = ImageCollection(
        id = id,
        name = id.substringAfterLast('/'),
        rootDirName = id.substringAfterLast('/'),
        images = (1..pages).map { page(id, it) },
        seriesKey = series,
        seriesName = series,
    )

    private fun page(collectionId: String, index: Int) = SourceItem(
        id = "$collectionId#$index",
        collectionId = collectionId,
        dir = "",
        baseName = index.toString().padStart(4, '0'),
        ext = "jpg",
        sizeBytes = 1024,
        modifiedEpochMillis = 0,
        createdEpochMillis = null,
        seed = index,
    )
}

/**
 * 断言芯片节点的文本里同时含给定片段。
 *
 * 不用 `assertTextEquals` 是因为芯片标签是 `stringResource(a_collection_chip, name, count)`,
 * 完整字符串受资源格式影响;这里只钉「名字」与「条数」两个语义片段都出现了。
 */
private fun SemanticsNodeInteraction.assertTextContainsFragments(vararg fragments: String) {
    val node = fetchSemanticsNode()
    val text = node.config
        .getOrNull(SemanticsProperties.Text)
        ?.joinToString(" ") { it.text }
        .orEmpty()
    fragments.forEach { fragment ->
        assertTrue(
            text.contains(fragment),
            "芯片标签里应含「$fragment」,实际=\"$text\"",
        )
    }
}
