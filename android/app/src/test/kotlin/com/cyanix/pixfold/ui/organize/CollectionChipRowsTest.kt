package com.cyanix.pixfold.ui.organize

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
 * - **1 级 / 2 级的显隐**:没点开系列时只有 1 级行;点开后浮层列出该系列各单本。
 * - **单本沿用既有 tag**:`collectionTag(id)` —— 18 个既有测试按它点芯片,语义没变。
 * - **「1 系列含 2 本」必须能分别选中**:这是用户实测缺陷的回归
 *   (原判据在「一个系列两本书」结构下把两本并成一个集合,步骤 1 无法区分)。
 * - **系列间来回切时记住上次那本**:否则每次切回系列都跳第一本,用户在浮层里做的选择白费。
 *
 * ## 2026-10-23 起的交互变化(用户裁决)
 *
 * 原话:「series 的单本不放到下面的一个单独遮罩内,而是上面 series 为顶点,展开叠加一个
 * 遮罩显示,再次点击 series 或点击其他 series 或 series 外单本时收缩刚才展开的」。
 *
 * ⇒ 2 级**不再常驻**:旧实现在「选中项属于某个系列」时会自动在下面长出一行芯片,
 * 页面内容因此上下窜。现在默认只有 1 级行,2 级在点开 series 时以**浮层**叠上来。
 * 本类的多个用例因此改成「先点开 series,再断浮层里的芯片」——这就是用户真实路径。
 *
 * ## 本类**不**守护什么
 *
 * 「收口在 3 行」的**实际视觉效果** —— Robolectric 不做真实文本测量,芯片宽度一律
 * 算成 ~0dp(除 `widthIn(min = 96.dp)` 这个确定性下限),换行与否离线断不出
 * (项目既有结论,见 `docs/notes/d1-ui-friction-inventory.md`)。视觉留真机核对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w361dp-h794dp-560dpi")
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
    fun `未点开系列时只有 1 级芯片行`() {
        setContent(
            listOf(collection("trip"), seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "trip",
        )

        // 即使初始选中项属于某个系列,浮层也**不自动展开** —— 否则页面一进来就被
        // 一块遮罩盖住,而用户什么都没点。
        assertTrue(
            rule.onAllNodesWithTag(TAG_COLLECTION_BOOK_ROW).fetchSemanticsNodes().isEmpty(),
            "没点开系列时不该出现 2 级浮层",
        )
    }

    @Test
    fun `初始选中系列成员时也不自动展开浮层`() {
        setContent(
            listOf(seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "S/第01卷",
        )

        assertTrue(
            rule.onAllNodesWithTag(TAG_COLLECTION_BOOK_ROW).fetchSemanticsNodes().isEmpty(),
            "浮层只应响应用户点击,不该按当前选中项自动展开",
        )
        // 1 级行上仍要能看出「当前在哪一个系列」——否则收起后用户就丢失了位置感。
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).assertIsDisplayed()
    }

    // ---- 1 系列含 2 本:两本必须能分别选中(用户实测缺陷的回归) ----

    @Test
    fun `点开系列后浮层列出该系列的两本`() {
        setContent(
            listOf(seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "S/第01卷",
        )

        rule.onNodeWithTag(TAG_COLLECTION_ROW).assertIsDisplayed()
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).assertIsDisplayed()

        // 2 级不常驻 —— 必须先点开系列(用户真实路径)。
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()

        // 已在本系列内 ⇒ 浮层只是展开,不该换书(仍停在第 01 卷)。
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

    /** 三条收起触发之一:再点同一个 series —— 浮层收起。 */
    @Test
    fun `再点同一个系列会收起浮层`() {
        setContent(
            listOf(seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "S/第01卷",
        )

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_COLLECTION_BOOK_ROW).fetchSemanticsNodes().isEmpty(),
            "再点同一个系列必须收起浮层(三条收起触发之一)",
        )
    }

    /** 三条收起触发之二:点另一个 series —— 旧浮层让位,新浮层展开。 */
    @Test
    fun `点另一个系列会把浮层换过去`() {
        setContent(
            listOf(
                seriesBook("A", "A/第01卷"),
                seriesBook("A", "A/第02卷"),
                seriesBook("B", "B/第01卷"),
                seriesBook("B", "B/第02卷"),
            ),
            initial = "A/第01卷",
        )

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}A")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(collectionTag("A/第02卷")).assertIsDisplayed()

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}B")).performClick()
        rule.waitForIdle()

        // 浮层内容必须整体换掉:属于 A 的两本不在,属于 B 的两本在。
        assertTrue(
            rule.onAllNodesWithTag(collectionTag("A/第02卷")).fetchSemanticsNodes().isEmpty(),
            "换到 B 之后,A 的浮层必须让位",
        )
        rule.onNodeWithTag(collectionTag("B/第01卷")).assertIsDisplayed()
    }

    /** 三条收起触发之三:点 series 外的单本 —— 浮层让位。 */
    @Test
    fun `点系列外的单本会收起浮层`() {
        setContent(
            listOf(
                collection("trip"),
                seriesBook("S", "S/第01卷"),
                seriesBook("S", "S/第02卷"),
            ),
            initial = "S/第01卷",
        )

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()

        rule.onNodeWithTag(collectionTag("trip")).performClick()
        rule.waitForIdle()

        assertTrue(
            rule.onAllNodesWithTag(TAG_COLLECTION_BOOK_ROW).fetchSemanticsNodes().isEmpty(),
            "点 series 外的单本必须收起浮层(三条收起触发之三)",
        )
    }

    // ---- 2 级行为 ----

    @Test
    fun `点开系列会切到该系列的第一本并展开浮层`() {
        val collections = listOf(
            collection("trip"),
            seriesBook("S", "S/第01卷"),
            seriesBook("S", "S/第02卷"),
        )
        setContent(collections, initial = "trip")

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()

        // 从单本切进系列 ⇒ 浮层展开,并停在第一本;必须断**选中态**,
        // 只断 displayed 的话「切了但没选中」也能绿。
        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()
        rule.onNodeWithTag(collectionTag("S/第01卷")).assertIsSelected()
    }

    @Test
    fun `点击浮层里的芯片切换系列内的书`() {
        setContent(
            listOf(seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "S/第01卷",
        )

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(collectionTag("S/第02卷")).performClick()
        rule.waitForIdle()

        // 仍在系列里 ⇒ 浮层还在(不是被 click 弄丢),且选中态跟着走。
        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()
        rule.onNodeWithTag(collectionTag("S/第02卷")).assertIsSelected()
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
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}A")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(collectionTag("A/第02卷")).performClick()
        rule.waitForIdle()

        // 切到 B,再切回 A
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}B")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}A")).performClick()
        rule.waitForIdle()

        // 应回到 A 的第二本,而不是永远跳第一本。
        // **必须断选中态,不能只断 displayed**:切回来后 A 的两本都在浮层里,两本都
        // 「可见」—— 只断 displayed 的话,「永远跳第一本」的变异照样能绿(2026-10-19 M16 存活)。
        rule.onNodeWithTag(TAG_COLLECTION_BOOK_ROW).assertIsDisplayed()
        rule.onNodeWithTag(collectionTag("A/第02卷")).assertIsSelected()
        rule.onNodeWithTag(collectionTag("A/第01卷")).assertIsNotSelected()
    }

    @Test
    fun `点开当前系列不会把用户甩回第一本`() {
        setContent(
            listOf(seriesBook("S", "S/第01卷"), seriesBook("S", "S/第02卷")),
            initial = "S/第02卷",
        )

        rule.onNodeWithTag(groupTag("${SERIES_PREFIX}S")).performClick()
        rule.waitForIdle()

        // 已在该系列内 ⇒ 展开浮层但不换书。旧版这里是「重复点击系列芯片」,语义相同
        // (当时 2 级常驻),现在浮层默认收起后再点就是展开 —— 仍是同一条承重规则。
        rule.onNodeWithTag(collectionTag("S/第02卷")).assertIsSelected()
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
