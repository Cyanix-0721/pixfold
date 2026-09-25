package com.pixfold.d1.ui.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pixfold.d1.R
import com.pixfold.d1.TestText
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 文案解析层本身的行为(`Msg` -> 最终字符串)。
 *
 * **为什么需要这组测试**:i18n 改造的地基是"领域层只产出语言无关的 [Msg],由界面按当前语言现场解析"。
 * 这个解析器在此之前**零直接覆盖** —— 所有既有测试都是经由页面间接使用它,
 * 于是"某个键登记错了"只会表现为某句文案少个空格或选错变体,而不会被任何断言抓住
 * (实测:变异 `pluralQuantityIndex()` 的下标后,全套 295 项里**没有一项失败**)。
 *
 * 期望值一律经 [TestText] 从**同一份资源**算出,故本组测试与语言无关
 * (Robolectric 默认 locale 是 `en_US`,而英文是资源的**默认槽位** `values/`)。
 *
 * ⚠️ **例外**:`plural key with quantity in second position ...` 一条必须钉死英文。
 * 它要断言"计数参数的下标是否登记正确",判据是"`one` 与 `other` 两个变体文本不同",
 * 而**中文的 26 个 `<plurals>` 全部 one/other 同值**(中文没有复数变化),
 * 中文下该判据恒成立、断言退化成恒真式。这不是推测:把整套 `app` 测试的
 * qualifiers 强制为 `zh-rCN` 复跑时,**304 项里只有那一条失败**(详见该用例的 KDoc)。
 * 其余 7 条经同一实验确认与语言无关。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PixFoldTextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // ================= 基础与嵌套 =================

    @Test
    fun `plain message resolves to its string resource`() {
        assertEquals(
            TestText.of(R.string.domain_summary_source_dir_delete),
            resolveText(context, Msg(TextKey.SummarySourceDirDelete)),
        )
    }

    @Test
    fun `nested message is resolved recursively`() {
        // 形态:`源目录：%1$s`,参数本身又是一条 Msg(「删除(危险)」/「保留」)
        val msg = Msg(TextKey.SummarySourceDirPrefix, listOf(Msg(TextKey.SummarySourceDirKeep)))
        assertEquals(
            TestText.of(R.string.domain_summary_source_dir_prefix, TestText.of(R.string.domain_summary_source_dir_keep)),
            resolveText(context, msg),
        )
    }

    @Test
    fun `list arguments join with the default separator`() {
        val msg = Msg(TextKey.SummarySourceDirPrefix, listOf(listOf("a", "b")))
        val joined = listOf("a", "b").joinToString(TestText.of(R.string.domain_list_separator))
        assertEquals(
            TestText.of(R.string.domain_summary_source_dir_prefix, joined),
            resolveText(context, msg),
        )
    }

    /**
     * 非法字符警告用的分隔符**不是**通用列表分隔符(`、` / `, `),而是单个空格。
     *
     * 该键的值曾是裸空格,被 **AAPT2 在编译期 trim 成空串**,导致英文渲染出 `A,B`
     * 这类粘连(真实产品缺陷,非测试假象)。这里同时守住"用了哪个分隔符键"和
     * "那个键确实编译出了空格"两件事。
     */
    @Test
    fun `illegal char warning joins with its own separator not the list separator`() {
        val separator = TestText.of(R.string.domain_warn_illegal_char_separator)
        assertEquals(" ", separator, "该键必须编译出单个空格(AAPT2 会 trim 裸空白,故资源里写 \\u0020)")
        assertEquals(
            TestText.of(R.string.domain_warn_illegal_char, listOf(":", "?").joinToString(separator)),
            resolveText(context, Msg(TextKey.WarnIllegalChar, listOf(listOf(":", "?")))),
        )
    }

    @Test
    fun `mixed list of messages and literals resolves each part`() {
        val parts = listOf<Any>(Msg(TextKey.WarnIllegalCharControl), ":", "?")
        val joined = listOf(TestText.of(R.string.domain_warn_illegal_char_control), ":", "?")
            .joinToString(TestText.of(R.string.domain_warn_illegal_char_separator))
        assertEquals(
            TestText.of(R.string.domain_warn_illegal_char, joined),
            resolveText(context, Msg(TextKey.WarnIllegalChar, listOf(parts))),
        )
    }

    // ================= 复数量词派发 =================

    /** 数量在**第 1 个**参数上的形态(绝大多数计数键)。 */
    @Test
    fun `plural key with quantity in first position selects the matching variant`() {
        assertEquals(
            TestText.plural(R.plurals.domain_naming_summary_enabled, 1, 1),
            resolveText(context, Msg(TextKey.NamingSummaryEnabled, listOf(1))),
        )
        assertEquals(
            TestText.plural(R.plurals.domain_naming_summary_enabled, 2, 2),
            resolveText(context, Msg(TextKey.NamingSummaryEnabled, listOf(2))),
        )
    }

    /**
     * 数量在**第 2 个**参数上的形态:`<首个建议名> · <N 个组件启用>`。
     *
     * `pluralQuantityIndex()` 必须为这个键返回 1。若误登记为 0,解析层会拿建议名
     * (String)去取 quantity,`as? Int` 落空后回退 0 —— 英文便由 `one` 变体
     * (`1 component enabled`)**静默切换**到 `other`(`1 components enabled`)。
     *
     * ⚠️ **本用例是全组唯一刻意钉死英文 locale 的断言。**
     * 它靠"`one` 与 `other` 两个变体文本不同"来区分下标是否登记正确,而
     * **中文的 26 个 `<plurals>` 全部 one/other 同值**(中文无复数变化),
     * 中文下 `oneArg == asIfIndexZero` 恒成立,断言会退化成恒真式。
     * 实证:把整套 `app` 测试的 qualifiers 改成 `zh-rCN` 复跑,
     * **304 项里只有本条第 121 行失败**、本组其余 7 条全过 —— 原因正是如此。
     * 故这里显式钉死英文,而非假装它与语言无关。
     */
    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun `plural key with quantity in second position uses that position as the count`() {
        // 与"下标被误登记成 0"划清界限:那样会拿 `%1$s`(建议名,String)当 count,
        // `as? Int` 落空后回退 0,英文的 `0` 不匹配 `one` -> 落到 other 变体,
        // 于是会渲染成 `1 components enabled`(单复数不一致)。两者必须不同。
        val oneArg = TestText.plural(R.plurals.domain_naming_summary_with_head, 1, "x.jpg", 1)
        val asIfIndexZero = TestText.plural(R.plurals.domain_naming_summary_with_head, 0, "x.jpg", 1)
        assertEquals(oneArg, resolveText(context, Msg(TextKey.NamingSummaryWithHead, listOf("x.jpg", 1))))
        assertTrue(
            oneArg != asIfIndexZero,
            "one 与 other 变体必须真的不同,否则本用例无法区分下标登记错",
        )
    }

    /** 带数字参数但**不是**计数语义的键(如页码序号)必须仍走普通字符串,不做变体选择。 */
    @Test
    fun `non plural key with numeric argument stays a plain string`() {
        val msg = Msg(TextKey.PageIssueWrongIndex, listOf(1, "a.png", "b.png"))
        assertEquals(
            TestText.of(R.string.domain_page_issue_wrong_index, 1, "a.png", "b.png"),
            resolveText(context, msg),
        )
    }
}
