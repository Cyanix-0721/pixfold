package com.pixfold.d1.ui.workflowa

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.pixfold.d1.domain.mock.MockData
import com.pixfold.d1.domain.model.CasePolicy
import com.pixfold.d1.domain.model.ExtPolicy
import com.pixfold.d1.domain.model.ImageCollection
import com.pixfold.d1.domain.model.NameComponent
import com.pixfold.d1.domain.model.NameComponentKind
import com.pixfold.d1.domain.model.NamingScheme
import com.pixfold.d1.domain.model.NamingState
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.sort.PageOrderState
import com.pixfold.d1.domain.sort.pageOrderOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验收第 8、9、10 项的 UI 层断言(规格 §12.2 的 U7 + 本阶段补充)。
 *
 * 第 2 层的职责:**证明界面上真的可操作**,而非只断言领域函数正确
 * (P3 的教训:领域做完 + 单测全绿,但界面没用上 = 死代码)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h1000dp")
class NamingPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val trip: ImageCollection = MockData.workspaceA.collections.first { it.id == "trip" }
    private val scan: ImageCollection = MockData.workspaceA.collections.first { it.id == "scan" }

    private fun item(
        id: String,
        dir: String = "",
        base: String = "img",
        ext: String = "jpg",
    ) = SourceItem(id, "c1", dir, base, ext, 1024, 0, 0, 0)

    private fun show(
        collection: ImageCollection = trip,
        order: PageOrderState = pageOrderOf(collection.images),
        initial: NamingState? = null,
    ) {
        rule.setContent {
            NamingPage(
                collection = collection,
                order = order,
                initialState = initial,
                contentInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        }
    }


    /**
     * 断言某行输入框的**可编辑文本**。
     *
     * 不能用 `assertTextEquals`:它比对 `Text + EditableText`,而 OutlinedTextField 的
     * **label**("新名称")也算 Text,导致断言永远失败(实测踩过)。
     * 这里直接读 `SemanticsProperties.EditableText`,只比对真正的值。
     */
    private fun assertField(id: String, expected: String) {
        val node = rule.onNodeWithTag(proposalFieldTag(id)).fetchSemanticsNode()
        val actual = node.config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertEquals(expected, actual, "第 $id 行的新名称不符")
    }


    /**
     * 滚到建议表中某一行(内部是 LazyColumn,**只组合可见项** ——
     * 直接断言靠后的行会"找不到节点",这是 Lazy 列表的固有行为,不是缺陷)。
     */
    private fun scrollToRow(id: String) {
        rule.onNodeWithTag(TAG_PROPOSAL_LIST)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(proposalFieldTag(id)))
        rule.waitForIdle()
    }

    /** 断言某行文本须先滚到它。 */
    private fun assertFieldAfterScroll(id: String, expected: String) {
        scrollToRow(id)
        assertField(id, expected)
    }

    // ================= 验收第 8 项:命名结构各策略可调 =================

    @Test
    fun `scheme editor exposes every adjustable knob`() {
        show()
        // 组件级
        rule.onNodeWithTag(TAG_SCHEME_EDITOR).assertIsDisplayed()
        rule.onNodeWithTag(compToggleTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(compKindTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(compSepTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(compMoveUpTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(compMoveDownTag(0)).assertIsDisplayed()
        rule.onNodeWithTag(compRemoveTag(0)).assertIsDisplayed()
        // 全局级
        rule.onNodeWithTag(TAG_SCHEME_ROOT_DIR).assertIsDisplayed()
        rule.onNodeWithTag(TAG_SCHEME_INDEX_START).assertIsDisplayed()
        rule.onNodeWithTag(TAG_SCHEME_INDEX_PADDING).assertIsDisplayed()
        rule.onNodeWithTag(TAG_SCHEME_EXT_POLICY).assertExists()
        rule.onNodeWithTag(TAG_SCHEME_SANITIZE).assertExists()
        rule.onNodeWithTag(TAG_SCHEME_ADD).assertIsDisplayed()
    }

    @Test
    fun `editing root dir name changes the proposed names`() {
        show()
        val firstId = trip.images.first().id
        // 默认结构 = <根目录名>_<序号> -> 初始应为 trip 的 rootDirName
        assertField(firstId, "旅行照片_001.jpg")

        rule.onNodeWithTag(TAG_SCHEME_ROOT_DIR).performTextReplacement("新根名")
        assertField(firstId, "新根名_001.jpg")
    }

    @Test
    fun `changing index padding changes proposed names`() {
        show()
        val firstId = trip.images.first().id
        rule.onNodeWithTag(TAG_SCHEME_INDEX_PADDING).performTextReplacement("5")
        assertField(firstId, "旅行照片_00001.jpg")
    }

    @Test
    fun `disabling a component changes proposed names`() {
        show()
        val firstId = trip.images.first().id
        assertField(firstId, "旅行照片_001.jpg")

        // 关掉第 0 个组件(RootDir)-> 只剩序号
        rule.onNodeWithTag(compToggleTag(0)).performClick()
        // RootDir 被停用后,ImageIndex 成为首个输出组件 -> 不出分隔符
        assertField(firstId, "001.jpg")
    }

    @Test
    fun `changing separator changes proposed names`() {
        show()
        val firstId = trip.images.first().id
        // 第 1 个组件(ImageIndex)的前置分隔符
        rule.onNodeWithTag(compSepTag(1)).performTextReplacement("-")
        assertField(firstId, "旅行照片-001.jpg")
    }

    @Test
    fun `moving a component up changes proposed names`() {
        show()
        val firstId = trip.images.first().id
        assertField(firstId, "旅行照片_001.jpg")
        // 把第 1 个组件(ImageIndex)上移到首位
        rule.onNodeWithTag(compMoveUpTag(1)).performClick()
        // ImageIndex 成首个输出组件(不出分隔符),RootDir 的 separatorBefore 默认 "_"
        assertField(firstId, "001_旅行照片.jpg")
    }

    @Test
    fun `adding a component changes proposed names`() {
        val comps = listOf(NameComponent(NameComponentKind.RootDir))
        show(initial = NamingState(NamingScheme("根", components = comps)))
        val firstId = trip.images.first().id
        assertField(firstId, "根.jpg")

        rule.onNodeWithTag(TAG_SCHEME_ADD).performClick()
        // 新组件默认是 CustomText(空文本)-> 空组件整体跳过,名字不变
        assertField(firstId, "根.jpg")
        // 给它填文本才生效。本用例初始只有 1 个组件 -> 新组件下标为 1。
        rule.onNodeWithTag(TAG_PROPOSAL_LIST)
            .performScrollToNode(androidx.compose.ui.test.hasTestTag(compTextTag(1)))
        rule.onNodeWithTag(compTextTag(1)).performTextInput("X")
        assertField(firstId, "根_X.jpg")
    }

    @Test
    fun `at least one component is kept so remove is disabled at the last one`() {
        show(initial = NamingState(NamingScheme("根", components = listOf(NameComponent(NameComponentKind.RootDir)))))
        rule.onNodeWithTag(compRemoveTag(0)).assertIsNotEnabled()
    }

    @Test
    fun `extension policy strip removes extension from proposed names`() {
        show(initial = NamingState(NamingScheme("根", components = listOf(NameComponent(NameComponentKind.RootDir)), extPolicy = ExtPolicy.Strip)))
        val firstId = trip.images.first().id
        assertField(firstId, "根")
    }

    @Test
    fun `case policy lower lowercases custom text`() {
        val comps = listOf(
            NameComponent(NameComponentKind.RootDir, casePolicy = CasePolicy.Lower),
        )
        val col = ImageCollection("x", "x", "ROOT", trip.images.take(2))
        show(
            collection = col,
            order = pageOrderOf(col.images),
            initial = NamingState(NamingScheme("ROOT", components = comps)),
        )
        assertField(col.images.first().id, "root.jpg")
    }

    // ================= 验收第 9 项:逐项覆盖 + 改结构后不丢失(U7) =================

    @Test
    fun `editing a proposal name overrides it`() {
        show()
        val id = trip.images.first().id
        rule.onNodeWithTag(proposalFieldTag(id)).performTextReplacement("我的名字.jpg")
        assertField(id, "我的名字.jpg")
        // 标签应变为"已覆盖"
        rule.onNodeWithText("新名称（已覆盖）").assertIsDisplayed()
    }

    @Test
    fun `U7 override survives a scheme change`() {
        // 这是验收第 9 项的核心:改结构后覆盖不被冲掉
        show()
        val overriddenId = trip.images[0].id
        val otherId = trip.images[1].id

        rule.onNodeWithTag(proposalFieldTag(overriddenId)).performTextReplacement("必须保留.jpg")
        assertField(overriddenId, "必须保留.jpg")

        // 改结构:根目录名 + 补零位数(任何一个改动都会重算 proposedName)
        rule.onNodeWithTag(TAG_SCHEME_ROOT_DIR).performTextReplacement("改了")
        rule.onNodeWithTag(TAG_SCHEME_INDEX_PADDING).performTextReplacement("5")

        // 覆盖项逐字不变
        assertFieldAfterScroll(overriddenId, "必须保留.jpg")
        // 未被覆盖项应随结构更新
        assertFieldAfterScroll(otherId, "改了_00002.jpg")
    }

    @Test
    fun `override survives multiple consecutive scheme changes`() {
        show()
        val id = trip.images.first().id
        rule.onNodeWithTag(proposalFieldTag(id)).performTextReplacement("稳.jpg")

        rule.onNodeWithTag(TAG_SCHEME_ROOT_DIR).performTextReplacement("A")
        rule.onNodeWithTag(TAG_SCHEME_INDEX_PADDING).performTextReplacement("4")
        rule.onNodeWithTag(compMoveUpTag(1)).performClick()
        rule.onNodeWithTag(compToggleTag(0)).performClick()

        assertField(id, "稳.jpg")
    }

    @Test
    fun `clearing the field revokes the override`() {
        show()
        val id = trip.images.first().id
        rule.onNodeWithTag(proposalFieldTag(id)).performTextReplacement("临时.jpg")
        assertField(id, "临时.jpg")

        rule.onNodeWithTag(proposalFieldTag(id)).performTextClearance()
        // 清空 = 撤销覆盖 -> 回到建议值(不是空名)
        assertField(id, "旅行照片_001.jpg")
    }

    @Test
    fun `reset button restores the proposed value`() {
        show()
        val id = trip.images.first().id
        rule.onNodeWithTag(proposalFieldTag(id)).performTextReplacement("覆盖过.jpg")
        rule.onNodeWithTag(proposalResetTag(id)).performClick()
        assertField(id, "旅行照片_001.jpg")
    }

    @Test
    fun `reset button is disabled when not overridden`() {
        show()
        val id = trip.images.first().id
        rule.onNodeWithTag(proposalResetTag(id)).assertIsNotEnabled()
    }

    @Test
    fun `override on one collection does not leak into another`() {
        // 覆盖以 image.id 为键;不同集合 id 不同 -> 不应互相污染。
        //
        // 注意:命名结构(scheme)是**批次级**(规格 §6.3 全局一份),
        // 所以切换集合时 rootDirName **不会**自动跟随集合改名 —— 这是规格规定的语义,
        // 不是缺陷。故这里用**显式指定 rootDirName 的 scheme**来验证"覆盖不串集合",
        // 而不依赖集合默认名。
        val colA = ImageCollection("a", "A", "A", listOf(item("a1"), item("a2")))
        val colB = ImageCollection("b", "B", "B", listOf(item("b1"), item("b2")))
        val scheme = NamingScheme("根")
        var current by androidx.compose.runtime.mutableStateOf(colA)
        var naming by androidx.compose.runtime.mutableStateOf<NamingState?>(NamingState(scheme))

        rule.setContent {
            val c = current
            NamingPage(
                collection = c,
                order = pageOrderOf(c.images),
                initialState = naming,
                onStateChange = { naming = it },
                contentInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            )
        }

        assertField("a1", "根_001.jpg")
        rule.onNodeWithTag(proposalFieldTag("a1")).performTextReplacement("A1改.jpg")
        assertField("a1", "A1改.jpg")

        // 切到集合 B:B 的行不受 A 的覆盖影响
        current = colB
        rule.waitForIdle()
        assertField("b1", "根_001.jpg")

        // 切回 A -> 覆盖仍在(证明覆盖按 id 存,且切换不丢)
        current = colA
        rule.waitForIdle()
        assertField("a1", "A1改.jpg")
    }

    // ================= 验收第 10 项:冲突与警告可见 =================

    @Test
    fun `duplicate names are visible in the table and marked as skipped`() {
        // 用 OrigName 命名,两个同名 baseName 跨目录 -> 重名
        val images = listOf(
            item("p1", dir = "ch01", base = "page_01"),
            item("p2", dir = "ch02", base = "page_01"),
        )
        val col = ImageCollection("dup", "重复", "重复", images)
        val scheme = NamingScheme("根", components = listOf(NameComponent(NameComponentKind.OrigName)))
        show(collection = col, order = pageOrderOf(col.images), initial = NamingState(scheme))

        rule.onNodeWithTag(proposalWarningTag("p1")).assertIsDisplayed()
        rule.onNodeWithTag(proposalWarningTag("p2")).assertIsDisplayed()
        // 汇总里应体现冲突数
        rule.onNodeWithTag(TAG_PROPOSAL_SUMMARY).assertTextEquals("共 2 项 · 冲突 2 · 警告 0")
    }

    @Test
    fun `illegal char warning is visible when sanitize is off`() {
        val images = listOf(item("bad", base = "封:面?"))
        val col = ImageCollection("il", "非法", "非法", images)
        val scheme = NamingScheme(
            "根",
            components = listOf(NameComponent(NameComponentKind.OrigName)),
            sanitize = false,
        )
        show(collection = col, order = pageOrderOf(col.images), initial = NamingState(scheme))

        rule.onNodeWithTag(proposalWarningTag("bad")).assertIsDisplayed()
        rule.onNodeWithText("· 非法字符：: ?（执行时将跳过；可开启自动清洗）").assertIsDisplayed()
    }

    @Test
    fun `turning sanitize on removes the illegal char warning`() {
        val images = listOf(item("bad", base = "封:面?"))
        val col = ImageCollection("il", "非法", "非法", images)
        val scheme = NamingScheme(
            "根",
            components = listOf(NameComponent(NameComponentKind.OrigName)),
            sanitize = false,
        )
        show(collection = col, order = pageOrderOf(col.images), initial = NamingState(scheme))
        rule.onNodeWithTag(proposalWarningTag("bad")).assertIsDisplayed()

        rule.onNodeWithTag(TAG_SCHEME_SANITIZE).performClick()
        // 清洗后不再有 IllegalChar 警告
        assertTrue(
            rule.onAllNodesWithTag(proposalWarningTag("bad")).fetchSemanticsNodes().isEmpty(),
            "开启自动清洗后不应再报非法字符",
        )
    }

    @Test
    fun `case collision is visible and does not count as a conflict`() {
        val images = listOf(
            item("c1", base = "Pg"),
            item("c2", base = "pg"),
        )
        val col = ImageCollection("cc", "大小写", "大小写", images)
        val scheme = NamingScheme("根", components = listOf(NameComponent(NameComponentKind.OrigName)))
        show(collection = col, order = pageOrderOf(col.images), initial = NamingState(scheme))

        rule.onNodeWithTag(proposalWarningTag("c1")).assertIsDisplayed()
        // 大小写冲突仅警告(冲突数应为 0)
        rule.onNodeWithTag(TAG_PROPOSAL_SUMMARY).assertTextEquals("共 2 项 · 冲突 0 · 警告 2")
    }

    @Test
    fun `filter shows only problematic rows`() {
        val images = listOf(
            item("ok1", base = "good"),
            item("d1", base = "same"),
            item("d2", base = "same"),
        )
        val col = ImageCollection("mix", "混合", "混合", images)
        val scheme = NamingScheme("根", components = listOf(NameComponent(NameComponentKind.OrigName)))
        show(collection = col, order = pageOrderOf(col.images), initial = NamingState(scheme))

        // 全部:3 行都在
        rule.onNodeWithTag(proposalFieldTag("ok1")).assertIsDisplayed()
        rule.onNodeWithTag(proposalFieldTag("d1")).assertIsDisplayed()
        rule.onNodeWithTag(proposalFieldTag("d2")).assertIsDisplayed()

        rule.onNodeWithTag(TAG_FILTER_CONFLICTS).performClick()
        // 仅问题:good 应被过滤掉
        assertTrue(
            rule.onAllNodesWithTag(proposalFieldTag("ok1")).fetchSemanticsNodes().isEmpty(),
            "筛选后无问题项不应出现",
        )
        rule.onNodeWithTag(proposalFieldTag("d1")).assertIsDisplayed()
        rule.onNodeWithTag(proposalFieldTag("d2")).assertIsDisplayed()
    }

    @Test
    fun `trip collection under default scheme has no conflicts`() {
        show()
        rule.onNodeWithTag(TAG_PROPOSAL_SUMMARY).assertTextEquals("共 30 项 · 冲突 0 · 警告 0")
    }

    @Test
    fun `scan collection illegal char is sanitized by default so no conflict`() {
        // scan 含 封:面? ,默认 sanitize=true -> 应被清洗,不产生 IllegalChar 冲突
        show(collection = scan, order = pageOrderOf(scan.images))
        rule.onNodeWithTag(TAG_PROPOSAL_SUMMARY).assertTextEquals("共 14 项 · 冲突 0 · 警告 0")
        // 实测次序(domain 探针):ch01 六张 -> 1..6;ch02 的 Page_01(大写) 在自然序中
        // 排在小写 page_01 之前? 不 —— 自然序先 lowercase,故二者等价,由 id 兜底,
        // 结果 Page_01 得 007、page_01 得 008 …… 封:面? 排最后得 014。
        // 因默认结构用 RootDir+ImageIndex 命名,baseName 里的非法字符**根本不进入**新名,
        // 所以这里断言的是序号,而不是"下划线替换"的效果。
        assertFieldAfterScroll("scan-illegal", "扫描件_014.png")
    }

    @Test
    fun `trip index follows the page order not the original order`() {
        // 命名序号取自**当前页序**(规格 §6.2);把第 1 张移到末尾后其序号应变最大
        val order = pageOrderOf(trip.images)
        val moved = com.pixfold.d1.domain.sort.moveItemTo(order, trip.images.first().id, trip.images.size - 1)
        show(collection = trip, order = moved)
        assertFieldAfterScroll(trip.images.first().id, "旅行照片_030.jpg")
    }
}
