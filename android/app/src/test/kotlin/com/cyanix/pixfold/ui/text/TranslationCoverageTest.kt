package com.cyanix.pixfold.ui.text

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.text.TextKey
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 「[TextKey] → 译文」这条链的**结构契约** —— 逐键覆盖、两语言对拍。
 *
 * ## 为什么必须另起一组测试(既有的两组都看不见这些)
 *
 * 1. `PixFoldTextTest` 的期望值一律经 `TestText.of(同一个资源)` 从**被测的那份资源**里取,
 *    于是「资源文案写错」时实际值与期望值**一起变**,断言恒真。W20 变异扫荡里
 *    M15–M18(中英占位符被删/对调)、M21(中文文案抄成英文)**全部存活**,就是踩的这个坑。
 * 2. `resId()` 是穷尽 `when`,只保证「每个键都有个资源」,**不保证指向正确的那个**。
 *    把两条相邻的映射互换(如 `PageIssueMissingFromOrder` 指到 `PageIssueCountMismatch`),
 *    编译、lint、既有单测**全都照旧通过** —— 因为没有任何一处**独立地**知道
 *    「这个键该是哪条资源」。
 * 3. `resId()` 与 `pluralQuantityIndex()` 都是 `private`,外部拿不到,所以本组测试**不碰它们**,
 *    而是从**命名约定**这一侧独立地反查资源:`TextKey.FooBar` → `domain_foo_bar`。
 *    一侧按名字取模板、另一侧按键走映射渲出来,再逐字对拍 —— 两侧各自成立,
 *    才第一次让「映射指错/复数档取错/翻译漏了」可观测。
 *
 * ## 契约的五条
 *
 * - **覆盖**:每个键在**两种语言**下都存在按约定命名的 `string`/`plurals`
 *   (zh 下若回退到默认槽位仍算「存在」,但那会立刻被跨语言可辨性抓住)。
 * - **占位符对等**:中英模板的占位符**下标与类型逐位相同**、下标从 1 起**按出现顺序连续**。
 *   lint 的 `StringFormatMatches` 只管「参数个数/类型能否格式化」,不管顺序,
 *   把 `%1$d`/`%2$d` 对调它一声不吭。
 * - **渲染逐字相等**:按键渲染出来的串,必须与**按名字取到的同一条模板**用同一组参数
 *   格式化出来的串**完全相同**(不是只看首尾 —— 差异若落在句子中间,如
 *   `ExecSummary` 的 `item:`/`items:`,只看首尾是看不见的)。
 * - **复数档**:12 个复数键必须**由数量参数选中正确的档** —— 数量为 1 落到 `one`、
 *   为 5 落到 `other`。`pluralQuantityIndex()` 是 `private`,这条是它唯一的把关。
 * - **可辨性**:中英渲染**必须不同**,除 [SAME_IN_BOTH_LANGUAGES] 白名单外;
 *   英文渲染**不得与另一个键撞句**,除 [SHARED_ENGLISH_WORDING] 白名单外。
 *
 * ⚠️ 两个白名单都是**逐字写死**的,这是刻意的:它们是「这里允许相同」的人工裁决记录,
 * 不是从资源里算出来的。新增一项都必须写明理由,否则就是漏翻或抄串了。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranslationCoverageTest {

    private val english: Context get() = ApplicationProvider.getApplicationContext()

    /** 同一个进程内切到中文 —— 不必为中文单开配置类,中英可在一条断言里对拍。 */
    private val chinese: Context
        get() = english.createConfigurationContext(
            Configuration(english.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) },
        )

    // ---------- 契约一/二/三:覆盖 + 占位符对等 + 渲染逐字相等 ----------

    @Test
    fun every_key_resolves_to_the_resource_named_after_it_in_both_languages() {
        TextKey.entries.forEach { key ->
            val en = template(english, key, "英文")
            val zh = template(chinese, key, "中文")

            assertEquals(en.kind, zh.kind, "$key:中英资源类型必须一致(string/plurals 不能跨语言不同)")

            val enSlots = slotsOf(en.text)
            val zhSlots = slotsOf(zh.text)
            assertEquals(enSlots, zhSlots, "$key:中英模板的占位符(下标+类型+出现顺序)必须逐位相同")

            val appearance = enSlots.map { it.index }.distinct()
            assertEquals(
                (1..appearance.size).toList(),
                appearance,
                "$key:占位符下标必须从 1 起按出现顺序连续,实际出现顺序=$appearance",
            )

            val args = argsOf(enSlots)
            assertEquals(
                format(en, args),
                resolveText(english, key, args),
                "$key:英文渲染必须与按名字取到的模板逐字相同",
            )
            assertEquals(
                format(zh, args),
                resolveText(chinese, key, args),
                "$key:中文渲染必须与按名字取到的模板逐字相同",
            )
        }
    }

    // ---------- 契约四:复数档由数量参数选中 ----------

    @Test
    fun every_plural_key_selects_its_variant_with_the_quantity_argument() {
        val res = english.resources
        val covered = mutableSetOf<String>()

        TextKey.entries.forEach { key ->
            val pluralId = res.getIdentifier(nameOf(key), "plurals", english.packageName)
            if (pluralId == 0) return@forEach
            covered += key.name

            val one = res.getQuantityText(pluralId, 1).toString()
            val other = res.getQuantityText(pluralId, OTHER_QUANTITY).toString()
            assertNotEquals(one, other, "$key:英文 one/other 两档必须不同,否则下面的断言没有判别力")

            listOf(1 to one, OTHER_QUANTITY to other).forEach { (quantity, text) ->
                // 所有数量参数一起取同一个值:不管数量下标是第几个,都必须选中这一档。
                val args = argsOf(slotsOf(text)) { quantity }
                assertEquals(
                    format(Template("plurals", text), args),
                    resolveText(english, key, args),
                    "$key:数量参数为 $quantity 时必须落在 ${if (quantity == 1) "one" else "other"} 档,不取决于它排第几个",
                )
            }
        }

        assertEquals(PLURAL_KEYS, covered, "复数键的集合变了:新增的复数键必须也纳进这条契约")
    }

    // ---------- 契约四之二:复数各档的占位符同形 ----------

    /**
     * 每个复数键的**各档**占位符必须同形 —— 英语 `one`/`other` 与中文 `other` 三处
     * 是同一句话的语法变体,数字的位置与类型不该跟着变。
     *
     * 这条是 W21 变异 M14 逼出来的(第一遍**存活**):上面那条复数契约对数量为 1 的情形,
     * 期望值与实际值**同源**(期望取自 `one` 档模板,实际也由 `one` 档渲染),
     * 于是「只把 `one` 档的第 2 个占位符删掉」时两边一起变、断言照样绿 ——
     * 与 W20 记录的 M15–M18 是同一个根因。修法是拿**另一档**当独立的一侧:
     * `other` 档的占位符签名与 `one` 档无关,删掉 `one` 的 `%2$d` 立刻显出形状不一致。
     *
     * ⚠️ 本契约只管**形状**,不管语法变体的**词形**是否正确(`1 item` vs `1 items`
     * 哪个才对需要词典,机械判定不了);词形正确性靠上面那条 `one != other` +
     * 人工评审,边界如实记在台账里。
     */
    @Test
    fun every_plural_variant_keeps_the_same_placeholder_shape() {
        TextKey.entries.forEach { key ->
            val name = nameOf(key)
            if (english.resources.getIdentifier(name, "plurals", english.packageName) == 0) return@forEach

            val baseline = slotsOf(pluralText(english, name, OTHER_QUANTITY))
            listOf(
                "英文 one" to pluralText(english, name, 1),
                "英文 other" to pluralText(english, name, OTHER_QUANTITY),
                "中文 other" to pluralText(chinese, name, OTHER_QUANTITY),
            ).forEach { (language, text) ->
                assertEquals(
                    baseline,
                    slotsOf(text),
                    "$key:${language} 档的占位符必须与英文 other 档同形(三者只是同一句话的语法变体)," +
                        "实际=${slotsOf(text)},基准=$baseline",
                )
            }
        }
    }

    // ---------- 契约五之一:跨语言可辨 ----------

    @Test
    fun only_the_documented_keys_read_the_same_in_both_languages() {
        val identical = TextKey.entries.filter { key ->
            val args = argsOf(slotsOf(template(english, key, "英文").text))
            resolveText(english, key, args) == resolveText(chinese, key, args)
        }.map { it.name }.toSet()

        assertEquals(
            SAME_IN_BOTH_LANGUAGES,
            identical,
            "中英渲染相同的键变了:新增的要么是漏翻(zh 回退到了默认槽位),要么是重复文案;" +
                "确属专有名词/纯符号的,请连同理由补进 SAME_IN_BOTH_LANGUAGES",
        )
    }

    // ---------- 契约五之二:键间不撞句 ----------

    @Test
    fun only_the_documented_keys_share_the_same_english_wording() {
        val collisions = TextKey.entries
            .groupBy { key -> resolveText(english, key, argsOf(slotsOf(template(english, key, "英文").text))) }
            .values
            .filter { it.size > 1 }
            .map { group -> group.map { it.name }.toSortedSet() }
            .toSet()

        assertEquals(
            SHARED_ENGLISH_WORDING,
            collisions,
            "英文渲染撞句的键组变了:新增的要么是把某条文案抄到了另一处,要么是有意同义异名;" +
                "后者请补进 SHARED_ENGLISH_WORDING 并说明",
        )
    }

    // ---------- 独立的一侧:按命名约定反查资源(完全不经过 resId()) ----------

    private data class Template(val kind: String, val text: String)

    /** `data class` 不能省 —— 否则 `assertEquals` 退化成引用比较,任何两串都判不等。 */
    private data class Slot(val index: Int, val type: Char)

    /** 约定:[TextKey] 的 `FooBar` 对应资源名 `domain_foo_bar`。 */
    private fun nameOf(key: TextKey): String =
        "domain_" + key.name.replace(Regex("(?<!^)(?=[A-Z])"), "_").lowercase()

    /**
     * 按**名字**取模板,`plurals` 取 `other` 档。
     *
     * 取 `other` 是配合 [argsOf] 生成的参数(数量取 907001+ 时中英都落在 `other`),
     * 两边一致才谈得上逐字对拍;`context` 决定读哪份语言资源。
     */
    private fun template(context: Context, key: TextKey, language: String): Template {
        val res = context.resources
        val name = nameOf(key)
        val stringId = res.getIdentifier(name, "string", context.packageName)
        if (stringId != 0) return Template("string", res.getText(stringId).toString())
        val pluralId = res.getIdentifier(name, "plurals", context.packageName)
        assertTrue(
            pluralId != 0,
            "$key:在${language}资源里找不到名为 $name 的 string/plurals —— " +
                "要么键名与资源名的约定断了,要么这条译文漏了",
        )
        return Template("plurals", res.getQuantityText(pluralId, OTHER_QUANTITY).toString())
    }

    private fun slotsOf(text: String): List<Slot> =
        PLACEHOLDER.findAll(text)
            .map { Slot(it.groupValues[1].toInt(), it.groupValues[2][0]) }
            .toList()

    /** 按资源名取某个数量档的文本(不走 `resId()`,与 [template] 同一条独立侧)。 */
    private fun pluralText(context: Context, name: String, quantity: Int): String {
        val id = context.resources.getIdentifier(name, "plurals", context.packageName)
        assertTrue(id != 0, "在资源里找不到名为 $name 的 plurals")
        return context.resources.getQuantityText(id, quantity).toString()
    }

    /**
     * 每个下标一个参数:`%N$d` → [number]`(N)`(默认 907000+N),`%N$s` → `sN`。
     *
     * 数量取 907001+ 而非 1,是为了让**任何**一个数量下标的键都落在 `other` 档,
     * 从而与 [template] 取的 `other` 模板对齐。
     */
    private fun argsOf(slots: List<Slot>, number: (Int) -> Int = { 907000 + it }): List<Any> {
        val typeByIndex = slots.associate { it.index to it.type }
        return typeByIndex.keys.sorted().map { i ->
            if (typeByIndex.getValue(i) == 'd') number(i) else "s$i"
        }
    }

    /**
     * 模板 + 参数 = 期望的渲染串。`Locale.ROOT` 是刻意的:参数只有纯整数与纯字符串,
     * 两种语言下 `%s`/`%d` 的结果一致,写死 ROOT 免得受运行环境默认 locale 影响。
     */
    private fun format(template: Template, args: List<Any>): String =
        String.format(Locale.ROOT, template.text, *args.toTypedArray())

    private companion object {
        private val PLACEHOLDER = Regex("""%(\d+)\$([sd])""")

        /** 非 `one` 的数量档 —— 中英资源在这一档都存在。 */
        private const val OTHER_QUANTITY = 5

        /** 复数键共 12 个(英文有 `one`+`other` 两档,中文只有 `other`)。 */
        private val PLURAL_KEYS = setOf(
            "SummaryProcessingVolumes",
            "PackPackPages",
            "PackFailMissingPages",
            "ExecSummary",
            "UndoRecorded",
            "WarnDuplicate",
            "WarnCaseCollision",
            "WarnTooLong",
            "NamingSummaryEnabled",
            "NamingSummaryWithHead",
            "NamingSummaryManual",
            "RenameSummary",
        )

        /**
         * 中英渲染**允许**相同的键。
         *
         * - 5 个 `MetaField*`:资源标了 `translatable="false"`,值是 ComicInfo.xml 的
         *   **标签名**(`Title`/`Series`/`Writer`/`Number`/`LanguageISO`),必须与规范逐字一致,
         *   `values-zh/` 里根本没有它们(有反而是 `ExtraTranslation` 错误)。
         * - `WarnIllegalCharSeparator`:一个孤空格 `\u0020`,没有可翻译的成分。
         */
        private val SAME_IN_BOTH_LANGUAGES = setOf(
            "MetaFieldTitle",
            "MetaFieldSeries",
            "MetaFieldWriter",
            "MetaFieldVolume",
            "MetaFieldLanguage",
            "WarnIllegalCharSeparator",
        )

        /**
         * 英文渲染**允许**撞句的键组:三对「同名不同域的枚举」,措辞相同是设计选择
         * (大小写策略与扩展名策略用的是同一套措辞)。
         */
        private val SHARED_ENGLISH_WORDING = setOf(
            setOf("CasePolicyKeep", "ExtPolicyKeep"),
            setOf("CasePolicyLower", "ExtPolicyLower"),
            setOf("CasePolicyUpper", "ExtPolicyUpper"),
        )
    }
}
