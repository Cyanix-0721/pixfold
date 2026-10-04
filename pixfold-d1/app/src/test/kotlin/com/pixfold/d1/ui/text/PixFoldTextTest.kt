package com.pixfold.d1.ui.text

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.pixfold.d1.R
import com.pixfold.d1.TestText
import com.pixfold.d1.domain.mock.MockDataB
import com.pixfold.d1.domain.model.NameProposal
import com.pixfold.d1.domain.naming.OpStatus
import com.pixfold.d1.domain.naming.RenameOp
import com.pixfold.d1.domain.plan.operationPlanOfRenames
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

    /**
     * R4 新增的「有页不在页序里」必须真的解析到**它自己**的文案。
     *
     * 与上一条相邻,故这里额外钉死"不能解析成邻居的句子" —— 页码自检的三个键
     * 长得很像(`count_mismatch` / `missing_from_order` / `wrong_index`),
     * 映射写串了照样能编译、照样有输出。
     */
    @Test
    fun `the missing from order issue resolves to its own wording`() {
        val msg = Msg(TextKey.PageIssueMissingFromOrder, listOf(1, 18))
        val rendered = resolveText(context, msg)
        assertEquals(TestText.of(R.string.domain_page_issue_missing_from_order, 1, 18), rendered)
        assertTrue(
            rendered != resolveText(context, Msg(TextKey.PageIssueCountMismatch, listOf(1, 18))),
            "三个页码问题键的文案必须彼此不同,否则用户分不清是哪种问题",
        )
        assertTrue(
            rendered != resolveText(context, Msg(TextKey.PageIssueWrongIndex, listOf(1, "018.jpg", "x.jpg"))),
            "三个页码问题键的文案必须彼此不同,否则用户分不清是哪种问题",
        )
    }

    /**
     * **唯一能看见「这条资源本身写错了」的用例** —— 与改名计划行那条同一个道理。
     *
     * 上面那条(以及本文件其余用例)拿 `TestText.of(同一个资源)` 当期望值,
     * 把资源改坏会**两边同时变**,断言照样绿。实测:把英文的两个占位符删掉、
     * 或把 `%1$d`/`%2$d` 对调,上面那条都**抓不到**。
     *
     * 故这里换一种**看得见的判据**:不比对整句,只断言
     * ①两个数目都真的出现在句子里(占位符没被删掉);
     * ②它们**按参数顺序**出现(没被对调)。
     * 数字是**参数**(数据),不是界面措辞,故这里写死数字不违反"文案不写死"的原则。
     */
    @Test
    fun `the missing from order wording keeps both numbers in argument order`() {
        val rendered = resolveText(context, Msg(TextKey.PageIssueMissingFromOrder, listOf(3, 47)))
        assertTrue(rendered.contains("3"), "「有 %1\$d 页」的数目必须出现在句子里:实际 $rendered")
        assertTrue(rendered.contains("47"), "「共 %2\$d 页」的数目必须出现在句子里:实际 $rendered")
        assertTrue(
            rendered.indexOf("3") < rendered.indexOf("47"),
            "'部分'的数目必须排在'总数'前面,对调后用户会把 47 读成部分数:实际 $rendered",
        )
    }

    /**
     * 中文资源同样必须①是中文 ②两个数目按序 —— 默认 locale 是 `en_US`,
     * 故中文槽位**不会被任何用例加载**;删掉中文占位符或把中英抄错都抓不到。
     */
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN")
    fun `the chinese missing from order wording is chinese and keeps both numbers in order`() {
        val rendered = resolveText(context, Msg(TextKey.PageIssueMissingFromOrder, listOf(3, 47)))
        assertTrue(
            rendered.any { it.code in 0x4E00..0x9FFF },
            "中文槽位必须真的是中文(把英文抄过来时,中文用户会看到英文):实际 $rendered",
        )
        assertTrue(rendered.contains("3"), "「有 %1\$d 页」的数目必须出现在句子里:实际 $rendered")
        assertTrue(rendered.contains("47"), "「共 %2\$d 页」的数目必须出现在句子里:实际 $rendered")
        assertTrue(
            rendered.indexOf("3") < rendered.indexOf("47"),
            "'部分'的数目必须排在'总数'前面:实际 $rendered",
        )
    }

    // ================= 改名计划行 =================

    /**
     * 改名计划行(R2)必须解析出「原名 → 新名」,且**方向**不能反。
     *
     * ⚠️ **本组唯一能看见"资源内容写错"的用例。**
     *
     * 本文件其余全部用例都拿 `TestText.of(同一个资源)` 当期望值 —— 那是刻意的:
     * 期望值与界面走**同一条解析路径**,故与语言无关、不钉死措辞。
     * 代价是:它们只能证明"`Msg` → 资源"这一步没错,**证明不了资源本身写对了**。
     * 实证(轮 14 W18):把英文资源改成只有一个占位符 `Rename %1$s`
     * (第二个文件名被静默吞掉),同组的比对型断言**全部照常通过**,
     * 因为两边读的是同一份坏资源。
     *
     * 故这里兼用**句子的可观测形状**判据 —— 不碰措辞、不碰语言:
     * 句子必须同时提到两个名字,且原名排在新名之前。
     *  - 少一个占位符 ⇒ 有一个名字根本没出现(`indexOf == -1`);
     *  - 两个占位符写反 ⇒ 顺序断言失败;
     *  - 两侧写成同一个占位符 ⇒ 缺一个名字。
     * 顺序这一条正是 [TextKey.RenamePlanRename] 的 KDoc 里写下的那个 ⚠️ 契约。
     */
    @Test
    fun `rename plan line mentions both names with the original first`() {
        val original = "第01卷_p001.jpg"
        val renamed = "进击的巨人 第01卷 001.jpg"
        val text = resolveText(context, Msg(TextKey.RenamePlanRename, listOf(original, renamed)))
        assertEquals(
            TestText.of(R.string.domain_rename_plan_rename, original, renamed),
            text,
            "改名计划行必须由 domain_rename_plan_rename 渲染",
        )
        val atOriginal = text.indexOf(original)
        val atRenamed = text.indexOf(renamed)
        assertTrue(atOriginal >= 0, "句子里必须出现原名,实际渲染为「$text」")
        assertTrue(atRenamed >= 0, "句子里必须出现新名,实际渲染为「$text」")
        assertTrue(
            atOriginal < atRenamed,
            "原名必须排在新名前面(「改之前 → 改之后」),实际渲染为「$text」",
        )
    }

    /**
     * 领域层**真造出来**的改名计划句必须能在此解析,且参数个数与资源占位符对得上。
     *
     * 与上一条的区别:上一条手工拼 `Msg`,这条走完整链路
     * (`RenameOp` → `operationPlanOfRenames(...).describe()` → `resolveText`)。
     * 它能抓住手工拼 `Msg` 抓不到的错:领域层少传一个参数
     * (资源要两个而只给一个 ⇒ 抛 `MissingFormatArgumentException`)。
     */
    @Test
    fun `the rename plan line produced by the domain resolves with both real file names`() {
        val page = MockDataB.library.volumes.first { it.id == "aot-1" }.pages.first()
        val renamed = "进击的巨人 第01卷 001.jpg"
        val op = RenameOp(
            proposal = NameProposal(image = page, proposedName = renamed),
            status = OpStatus.Ok,
            reason = null,
        )
        val line = operationPlanOfRenames(listOf(op)).describe().single()
        assertEquals(
            TestText.of(R.string.domain_rename_plan_rename, page.fileName, renamed),
            resolveText(context, line),
            "领域层产出的那条计划句必须正好填满资源的两个占位符",
        )
        // 同一条"可观测形状"守卫在**真实链路**上也必须成立,而不只在手工拼的 Msg 上。
        val text = resolveText(context, line)
        val atOriginal = text.indexOf(page.fileName)
        val atRenamed = text.indexOf(renamed)
        assertTrue(
            atOriginal >= 0 && atRenamed >= 0 && atOriginal < atRenamed,
            "真实链路渲染出的句子也必须「原名在前、新名在后」,实际为「$text」",
        )
    }

    /**
     * 中文槽位的那份资源也必须守住同一个形状契约。
     *
     * **为什么必须单独钉**:Robolectric 默认 locale 是 `en_US`,英文在默认槽位 `values/` ——
     * 故本文件其余用例跑的全是**英文**那一份,`values-zh/` 的内容一行都不会被读到。
     * 实证(轮 14 W18):把中文资源写成 `重命名 %2$s → %1$s`(方向反了)或
     * `重命名 %1$s → %1$s`(原名被新名顶掉),不钉中文的断言**全部照常通过** ——
     * 中文用户看到的是反的句子,而测试全绿。
     *
     * 判据仍是**与语言无关的形状**(两个名字都在、原名在前),不写死中文措辞。
     */
    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN")
    fun `the chinese rename plan line keeps both names with the original first`() {
        val original = "第01卷_p001.jpg"
        val renamed = "进击的巨人 第01卷 001.jpg"
        val text = resolveText(context, Msg(TextKey.RenamePlanRename, listOf(original, renamed)))
        assertEquals(
            TestText.of(R.string.domain_rename_plan_rename, original, renamed),
            text,
            "中文槽位也必须由 domain_rename_plan_rename 渲染",
        )
        val atOriginal = text.indexOf(original)
        val atRenamed = text.indexOf(renamed)
        assertTrue(atOriginal >= 0, "中文句子里必须出现原名,实际渲染为「$text」")
        assertTrue(atRenamed >= 0, "中文句子里必须出现新名,实际渲染为「$text」")
        assertTrue(
            atOriginal < atRenamed,
            "中文句子也必须是「原名在前、新名在后」,实际渲染为「$text」",
        )
    }
}
