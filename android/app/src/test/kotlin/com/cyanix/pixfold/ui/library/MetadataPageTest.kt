package com.cyanix.pixfold.ui.library

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.domain.comic.defaultCbzName
import com.cyanix.pixfold.domain.comic.setMetaValue
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.MetaValue
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.source.SERIES_PREFIX
import com.cyanix.pixfold.domain.library.LibraryState
import com.cyanix.pixfold.domain.library.libraryStateOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 工作流 B · 步骤 2 的 UI 层断言(验收第 11、12、13 项)。
 *
 * 第 2 层的职责:**证明界面上真的可操作**。这里刻意不复用领域函数做断言 ——
 * P3 的教训是"领域做完 + 单测全绿,但界面根本没接上"(死代码)。
 * 因此每个用例都以"界面上出现了什么 / 点了之后界面怎么变"为准。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w400dp-h2000dp")
class MetadataPageTest {

    @get:Rule
    val rule = createComposeRule()

    private val library = MockDataB.library

    /**
     * 被测页面把变更**上报上层**;测试必须像真实上层那样持有并回灌状态,
     * 否则"点了切卷没反应"是脚手架造成的假象(且这正是"数据变了界面不动"的成因类别)。
     */
    private fun show(volumes: List<ComicVolume> = library.volumes) {
        rule.setContent {
            var state by remember { mutableStateOf(libraryStateOf(library.rootPath, volumes)) }
            MetadataPage(
                libraryState = state,
                onLibraryChange = { state = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
    }

    /** 读某个输入框的可编辑文本(label 也会算进 Text,故不能用 assertTextEquals)。 */
    private fun fieldText(field: MetaField): String =
        rule.onNodeWithTag(metaFieldTag(field)).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag(TAG_METADATA_LIST).performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    /**
     * 切到指定卷 —— **走用户真实路径**。
     *
     * 2026-10-19 卷芯片改两级后,另一系列的卷不在当前 2 级行里(它属于别的系列的分支)。
     * 用户实际是:先点 1 级系列芯片(自动切到该系列第一本),再点目标卷。
     * 测试必须走同一条路 —— 否则要么直接找不到节点,要么被迫去断言内部实现。
     */
    private fun switchToVolume(id: String) {
        if (rule.onAllNodesWithTag(volumeChipTag(id)).fetchSemanticsNodes().isEmpty()) {
            val series = library.volumes.first { it.id == id }.series
            rule.onNodeWithTag(volumeGroupTag(SERIES_PREFIX + series)).performClick()
            rule.waitForIdle()
        }
        rule.onNodeWithTag(volumeChipTag(id)).performClick()
        rule.waitForIdle()
    }

    // ================= 验收第 11 项:逐卷元数据可改 + 建议值带来源 =================

    @Test
    fun `every editable metadata field is present`() {
        show()
        listOf(
            MetaField.Title, MetaField.Series, MetaField.Writer,
            MetaField.Volume, MetaField.CbzName, MetaField.OutputDir,
        ).forEach { f ->
            scrollTo(metaFieldTag(f))
            rule.onNodeWithTag(metaFieldTag(f)).assertIsDisplayed()
        }
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithTag(TAG_LANGUAGE_FIELD).assertIsDisplayed()
    }

    @Test
    fun `suggestion and its source are visible`() {
        show()
        // Title 建议 + 来源必须可见(建议 ≠ 事实)
        scrollTo(metaSuggestionTag(MetaField.Title))
        rule.onNodeWithText("目录层级 L2（系列层）", substring = true).assertIsDisplayed()
        // Volume 的来源说明"从目录名解析"
        scrollTo(metaSuggestionTag(MetaField.Volume))
        rule.onNodeWithText("目录名「第01卷」解析", substring = true).assertIsDisplayed()
    }

    @Test
    fun `editing the title really changes the field`() {
        show()
        scrollTo(metaFieldTag(MetaField.Title))
        rule.onNodeWithTag(metaFieldTag(MetaField.Title)).performTextReplacement("我的标题")
        assertEquals("我的标题", fieldText(MetaField.Title))
    }

    @Test
    fun `the writer cleaning suggestion is displayed alongside the raw suggestion`() {
        show()
        scrollTo(TAG_WRITER_CLEAN)
        // 建议值与其来源都是 mock 数据(不是文案),故从模型取值代入模板 —— 断言的是**模板**渲染正确。
        val aot = library.volumes.first { it.id == "aot-0" }
        rule.onNodeWithText(
            TestText.of(
                R.string.meta_writer_cleaned_hint,
                aot.writerCleanedSug.value.orEmpty(),
                aot.writerCleanedSug.source,
            ),
            substring = true,
        ).assertIsDisplayed()
        rule.onNodeWithTag(TAG_WRITER_CLEAN).assertIsDisplayed()
    }

    @Test
    fun `tapping writer cleaning replaces the value`() {
        show()
        scrollTo(TAG_WRITER_CLEAN)
        rule.onNodeWithTag(TAG_WRITER_CLEAN).performClick()
        assertEquals("谏山创", fieldText(MetaField.Writer))
    }

    @Test
    fun `reset button restores the suggestion value`() {
        show()
        scrollTo(metaFieldTag(MetaField.Title))
        rule.onNodeWithTag(metaFieldTag(MetaField.Title)).performTextReplacement("改过了")
        rule.onNodeWithTag(metaResetTag(MetaField.Title)).performClick()
        assertEquals("进击的巨人", fieldText(MetaField.Title))
    }

    @Test
    fun `reset button is disabled for untouched fields`() {
        show()
        scrollTo(metaResetTag(MetaField.Title))
        rule.onNodeWithTag(metaResetTag(MetaField.Title)).assertIsNotEnabled()
    }

    @Test
    fun `switching volume really changes the fields shown`() {
        show()
        assertEquals("进击的巨人", fieldText(MetaField.Title))

        switchToVolume("op-101")
        assertEquals("海贼王", fieldText(MetaField.Title))
    }

    // ================= 验收第 12 项:语言默认未设置 + 逐卷不同 + 不写入可选 =================

    @Test
    fun `language defaults to unset with an explicit confirmation prompt`() {
        show()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithText(TestText.of(R.string.domain_lang_unset)).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.meta_lang_unset_error)).assertIsDisplayed()
    }

    @Test
    fun `language suggestion is shown but not adopted`() {
        show()
        scrollTo(metaSuggestionTag(MetaField.Language))
        // aot-1 的建议是 ja(低置信)—— 显示出来,但当前值仍是"未设置"
        rule.onNodeWithText("卷内文件名含日文片段（低置信）", substring = true).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.domain_lang_ja)).fetchSemanticsNodes().isEmpty(),
            "建议不是事实:语言不得自动采纳",
        )
    }

    @Test
    fun `picking a language in the dropdown changes the value`() {
        show()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithTag(TAG_LANGUAGE_FIELD).performClick()
        rule.onNodeWithText(TestText.of(R.string.domain_lang_zh)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(TestText.of(R.string.domain_lang_zh)).assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.meta_lang_unset_error)).fetchSemanticsNodes().isEmpty(),
            "确认语言后不应再显示未确认提示",
        )
    }

    @Test
    fun `skip is an independent option`() {
        show()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithTag(TAG_LANGUAGE_FIELD).performClick()
        rule.onNodeWithText(TestText.of(R.string.domain_lang_skip)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(TestText.of(R.string.domain_lang_skip)).assertIsDisplayed()
    }

    @Test
    fun `pending issues of the active volume are visible in the editor`() {
        show()
        // aot-1 语言未设置 -> 卷头必须显示待确认
        scrollTo(TAG_PENDING_ISSUES)
        rule.onNodeWithTag(TAG_PENDING_ISSUES).assertIsDisplayed()
        rule.onNodeWithText(TestText.of(R.string.domain_pending_language), substring = true).assertIsDisplayed()

        // aot-0 缺卷号 -> 出现"卷号缺失"
        switchToVolume("aot-0")
        scrollTo(TAG_PENDING_ISSUES)
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.domain_pending_volume), substring = true).fetchSemanticsNodes().isNotEmpty(),
            "缺卷号必须在编辑处可见",
        )
    }

    @Test
    fun `languages can differ between volumes`() {
        show()
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithTag(TAG_LANGUAGE_FIELD).performClick()
        rule.onNodeWithText(TestText.of(R.string.domain_lang_zh)).performClick()
        rule.waitForIdle()

        // 切到另一卷:语言仍是"未设置"(逐卷不同,不被全局选项静默覆盖)
        switchToVolume("aot-2")
        scrollTo(TAG_LANGUAGE_FIELD)
        rule.onNodeWithText(TestText.of(R.string.domain_lang_unset)).assertIsDisplayed()
    }

    // ================= 验收第 13 项:批量设置不覆盖逐项例外 =================

    @Test
    fun `batch apply reports the number of volumes it actually changed`() {
        show()
        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("新系列")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithTag(TAG_BATCH_RESULT).assertIsDisplayed()
        rule.onNodeWithText(TestText.plural(R.plurals.meta_batch_applied, 5, 5), substring = true).assertIsDisplayed()
    }

    @Test
    fun `batch apply skips a field that was manually overridden`() {
        // 先把 aot-1 的 Series 手工改掉 -> 它应变成逐项例外
        val overridden = library.volumes.map {
            if (it.id == "aot-1") setMetaValue(it, MetaField.Series, MetaValue.Text("我改过的系列")) else it
        }
        show(overridden)

        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("批量系列")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithText(TestText.plural(R.plurals.meta_batch_applied_skipped, 4, 4, 1)).assertIsDisplayed()

        // 被跳过的卷值真的没变
        switchToVolume("aot-1")
        assertEquals("我改过的系列", fieldText(MetaField.Series))
    }

    @Test
    fun `force switch makes batch apply overwrite the exception too`() {
        val overridden = library.volumes.map {
            if (it.id == "aot-1") setMetaValue(it, MetaField.Series, MetaValue.Text("我改过的系列")) else it
        }
        show(overridden)

        scrollTo(TAG_BATCH_FORCE)
        rule.onNodeWithTag(TAG_BATCH_FORCE).performClick()
        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("强制系列")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithText(TestText.plural(R.plurals.meta_batch_applied, 5, 5), substring = true).assertIsDisplayed()
        switchToVolume("aot-1")
        assertEquals("强制系列", fieldText(MetaField.Series))
    }

    @Test
    fun `batch apply twice in a row applies both times`() {
        // 真机摩擦(§11 第 15 条)的界面级回归:第二次批量原先报 applied=0/skipped=5
        show()
        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("第一次")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("第二次")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithText(TestText.plural(R.plurals.meta_batch_applied, 5, 5), substring = true)
            .assertIsDisplayed()
        // 值真的被第二次改写
        switchToVolume("aot-1")
        assertEquals("第二次", fieldText(MetaField.Series))
    }

    @Test
    fun `batch apply is disabled while the value is empty`() {
        show()
        scrollTo(TAG_BATCH_APPLY)
        rule.onNodeWithTag(TAG_BATCH_APPLY).assertIsNotEnabled()
    }

    @Test
    fun `unchecking a volume excludes it from batch apply`() {
        show()
        // 取消勾选 op-102(第 5 卷);勾选框在卷头,切卷后即可点
        switchToVolume("op-102")
        rule.onNodeWithTag(volumeIncludedTag("op-102")).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_VALUE)
        rule.onNodeWithTag(TAG_BATCH_VALUE).performTextReplacement("只改勾选的")
        rule.onNodeWithTag(TAG_BATCH_APPLY).performClick()
        rule.waitForIdle()

        scrollTo(TAG_BATCH_RESULT)
        rule.onNodeWithText(TestText.plural(R.plurals.meta_batch_applied, 4, 4), substring = true).assertIsDisplayed()
    }

    @Test
    fun `output conflict volume is flagged`() {
        show()
        switchToVolume("op-102")
        assertTrue(
            rule.onAllNodesWithText(TestText.of(R.string.lib_output_conflict), substring = true).fetchSemanticsNodes().isNotEmpty(),
            "输出冲突卷必须在元数据页有明确提示",
        )
    }

    @Test
    fun `state is reported to the caller on change`() {
        var latest: LibraryState? = null
        rule.setContent {
            MetadataPage(
                libraryState = libraryStateOf(library.rootPath, library.volumes),
                onLibraryChange = { latest = it },
                contentInsets = WindowInsets(0, 0, 0, 0),
            )
        }
        scrollTo(metaFieldTag(MetaField.Title))
        rule.onNodeWithTag(metaFieldTag(MetaField.Title)).performTextReplacement("上报测试")
        assertEquals("上报测试", latest?.activeVolume?.title, "编辑必须上报上层,否则离开步骤就丢")
    }

    @Test
    fun `volume chips are addressable even when titles repeat`() {
        // 两个 aot 卷的 Title 建议相同(都是系列名)-> chip 文案重复,
        // 但仍必须能按 tag 精确选中,且不会崩。
        show()
        rule.onAllNodesWithTag(volumeChipTag("aot-1")).assertCountEquals(1)
        rule.onAllNodesWithTag(volumeChipTag("aot-2")).assertCountEquals(1)
    }

    // ================= 空工作区:不得崩、给空态、不给「下一步」 =================

    /**
     * 真实 SAF 工作区可能**一卷都没有**:目录里没有任何图片,或所有图片都散在根目录
     * (按「每个直接含图片的目录 = 一个集合」的规则,根目录本身不构成卷)。上层随之传下
     * `volumes = emptyList()`,而 `LibraryState.activeVolume` 落到 `volumes.first()`
     * ⇒ 组合期抛 `NoSuchElementException: List is empty.`,整页崩溃。
     *
     * 故这里**直接构造** `LibraryState(...)`(不经 `libraryStateOf`),把 `activeVolumeId`
     * 显式留空 —— 模拟的就是上层真实传下来的那个形状。
     *
     * ⚠️ `onNext` 刻意传**非 null 的空 lambda**而不是 null:空态里"不渲染下一步"必须是
     * 页面自己的判断,而不是碰巧因为上层没给回调。否则把空态写回正常态结构时,
     * 下面的断言会照样绿("没给回调"与"刻意不渲染"分不开)。
     */
    private fun showEmpty(onBack: (() -> Unit)? = null) {
        rule.setContent {
            MetadataPage(
                libraryState = LibraryState(
                    rootPath = "/tmp",
                    volumes = emptyList(),
                    activeVolumeId = "",
                ),
                onLibraryChange = {},
                contentInsets = WindowInsets(0, 0, 0, 0),
                onNext = {},
                onBack = onBack,
            )
        }
    }

    /** 本轮修复的崩溃点本身:空列表必须渲染出空态,而不是在组合期抛异常。 */
    @Test
    fun `empty volume list shows the empty state instead of crashing`() {
        showEmpty()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_META_EMPTY).assertIsDisplayed()
    }

    /**
     * 空态不给「下一步」,但必须留「返回」。
     *
     * 没有卷可编辑,推进过去只是把空列表继续传下去;而返回是用户**唯一的出路**
     * (退回去换一个目录)。两条一起断:少给一个或者多给一个都会红。
     */
    @Test
    fun `empty state offers no next step but keeps the back button`() {
        showEmpty(onBack = {})
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_METADATA_NEXT).assertDoesNotExist()
        rule.onNodeWithTag(TAG_B_STEP_BACK).assertIsDisplayed()
    }

    /** 反向断言:正常有卷时不得出现空态(否则空态会盖住整页可编辑内容)。 */
    @Test
    fun `non-empty library does not show the empty state`() {
        show()
        rule.onNodeWithTag(TAG_META_EMPTY).assertDoesNotExist()
        rule.onNodeWithTag(TAG_METADATA_LIST).assertIsDisplayed()
    }

    // ================= CBZ 名提示必须与「恢复建议值」的实际结果一致 =================

    /**
     * 提示行显示的默认名，必须**逐字等于**点「恢复建议值」后输入框里的值。
     *
     * 2026-10-19 真实缺陷：提示行传的是 `titleSug`（`彼女がボクの知らないところで`），
     * 而恢复实际走 `defaultCbzName(..., cbzBaseSug)`，后者**保留 `[作者]` 前缀**
     * （`[Cior (Ken-1)] 彼女がボクの知らないところで`）。带作者前缀的目录上两者不同
     * ⇒ 用户照着提示改、点恢复却得到另一个名字。
     *
     * 断言方式刻意不查内部实现：读提示行文案 → 改输入框 → 点恢复 → 读输入框，
     * 比较「提示承诺的」与「实际得到的」。
     */
    @Test
    fun `cbz name hint matches what reset actually produces`() {
        // 造一个「title 建议」与「CBZ 基名建议」不同的卷（真实素材的常态：
        // 目录名带 [作者] 前缀时，Title 剥掉前缀而 CBZ 名保留）。
        val withAuthor = library.volumes.first().let {
            it.copy(
                titleSug = Suggestion("彼女がボクの知らないところで", "目录层级"),
                cbzBaseSug = Suggestion("[Cior (Ken-1)] 彼女がボクの知らないところで", "目录名清洗"),
                volumeSug = Suggestion(1, "目录名解析"),
            )
        }
        show(listOf(withAuthor))

        // 权威真值 = 领域层算出的默认文件名（语言无关，固定形态）。
        // 用它当期望值是合理的：要断言的正是「界面显示/恢复的值 == 领域权威值」。
        // 上面的夹具把 titleSug 与 cbzBaseSug 刻意设成不同，界面若走错来源必然红。
        val expected = defaultCbzName(withAuthor)

        // 反向钉住「权威值本身必须是带作者前缀的那个」——否则提示与恢复可以
        // **一起**走错来源而仍然彼此一致，这条用例照样绿（变异 M2 实测存活）。
        assertTrue(
            expected.startsWith("[Cior (Ken-1)]"),
            "默认名必须保留 [作者] 前缀（来自 cbzBaseSug），实际是「$expected」",
        )
        assertTrue(
            expected != "${withAuthor.titleSug.value}.cbz",
            "默认名不得退回 titleSug（那会丢掉作者前缀）",
        )

        scrollTo(metaSuggestionTag(MetaField.CbzName))
        val hintNode = rule.onNodeWithTag(metaSuggestionTag(MetaField.CbzName)).fetchSemanticsNode()
        val hintText = hintNode.config[SemanticsProperties.Text].joinToString("")
        assertTrue(
            hintText.contains(expected),
            "提示行必须原样给出完整默认名「$expected」，实际是「$hintText」",
        )

        scrollTo(metaFieldTag(MetaField.CbzName))
        rule.onNodeWithTag(metaFieldTag(MetaField.CbzName)).performTextReplacement("我随便改的名字.cbz")
        rule.waitForIdle()
        assertEquals("我随便改的名字.cbz", fieldText(MetaField.CbzName))

        rule.onNodeWithTag(metaResetTag(MetaField.CbzName)).performClick()
        rule.waitForIdle()

        assertEquals(
            expected,
            fieldText(MetaField.CbzName),
            "点「恢复建议值」得到的名字与提示行承诺的不一致",
        )
    }
}
