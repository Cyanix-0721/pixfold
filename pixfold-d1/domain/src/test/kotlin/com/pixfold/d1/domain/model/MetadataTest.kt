package com.pixfold.d1.domain.model

import com.pixfold.d1.domain.comic.setMetaValue
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `model/Metadata.kt` 的派生量:`Suggestion.hasValue`、`ComicVolume.needsConfirmation`、
 * `ComicVolume.isOverridden`。
 *
 * 这三处此前都**没有直接断言**(`hasValue` 只在别处被间接用过;`isOverridden` 在领域层
 * 与 app 层**都没有消费者**;`needsConfirmation` 只断言过 false 那一侧),
 * 因此四个变异体全部存活。
 */
class MetadataTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val op101 = library.volumes.first { it.id == "op-101" }

    // ---- Suggestion.hasValue ----

    @Test
    fun `a blank string suggestion counts as no value`() {
        // 空串 ≠ null:[hasValue] 必须把空串也当成"没有建议",
        // 否则 UI 会把空标题当建议展示、把空作者当可清理建议。
        assertFalse(Suggestion("", "空标题建议").hasValue, "空串等于没有值")
        assertTrue(Suggestion("标题", "目录名").hasValue)
        assertFalse(Suggestion<String>(null, "未解析出").hasValue)
    }

    @Test
    fun `a non string suggestion only needs to be non null`() {
        // 只有 String 才追加"非空"要求;数值 0 是**有值**的(卷号 0 与"没有卷号"不同)
        assertTrue(Suggestion(0, "目录名解析").hasValue, "0 是有效卷号,不能被当成没有值")
        assertTrue(Suggestion(101, "目录名解析").hasValue)
        assertFalse(Suggestion<Int?>(null, "未解析出卷号").hasValue)
    }

    @Test
    fun `library suggestions follow the same rule`() {
        assertTrue(aot1.titleSug.hasValue)
        assertTrue(aot1.writerCleanedSug.hasValue)
        assertEquals("ja", aot1.langSug.value)
        assertFalse(library.volumes.first { it.id == "op-101" }.langSug.hasValue, "op-101 无语言线索")
    }

    // ---- ComicVolume.needsConfirmation ----

    @Test
    fun `a single pending issue is enough to need confirmation`() {
        // op-101 只缺语言(卷号已解析出 101)—— 一项也算待确认。
        // 若写成"多于一项才算",这一卷就会被 UI 当成已确认,漏掉语言必答题。
        assertEquals(listOf(Msg(TextKey.PendingLanguage)), op101.pendingIssues)
        assertTrue(op101.needsConfirmation, "只缺语言也必须待确认")
    }

    @Test
    fun `both issues pending still needs confirmation`() {
        val aot0 = library.volumes.first { it.id == "aot-0" }
        assertEquals(2, aot0.pendingIssues.size, "语言与卷号都缺")
        assertTrue(aot0.needsConfirmation)
    }

    @Test
    fun `a volume with no pending issue does not need confirmation`() {
        val done = op101.copy(language = LangChoice.Zh)
        assertEquals(emptyList(), done.pendingIssues)
        assertFalse(done.needsConfirmation)
    }

    // ---- ComicVolume.isOverridden ----

    @Test
    fun `the volume override flag follows the manual edit set`() {
        assertFalse(aot1.isOverridden, "mock 不设逐项例外")
        val edited = setMetaValue(aot1, MetaField.Title, MetaValue.Text("手改标题"))
        assertTrue(edited.isOverridden, "人工改过的卷必须被标为有逐项例外")
        assertEquals(setOf(MetaField.Title), edited.overridden)
    }

    @Test
    fun `page count follows the page order`() {
        // pageCount 与页码预览、XML 的 PageCount 同源(页序),不是原始页表长度
        assertEquals(aot1.pageOrder.order.size, aot1.pageCount)
        assertEquals(aot1.pages.size, aot1.pageCount)
        val subset = aot1.copy(pageOrder = aot1.pageOrder.copy(order = aot1.pageOrder.order.take(3)))
        assertEquals(3, subset.pageCount, "人工调序后页数必须跟页序走")
    }
}
