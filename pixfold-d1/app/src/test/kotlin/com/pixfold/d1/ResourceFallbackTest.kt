package com.pixfold.d1

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 资源兜底语言的行为(用户裁决 2026-09-25:「改成英文兜底,默认选项是跟随系统一般也用不上兜底」)。
 *
 * ## 为什么要专门测这个
 *
 * 「兜底走哪份资源」是**四道闸门全都看不见**的一类行为:lint 只看键集合是否对齐,
 * 单测只看解析出的字符串是否等于同一份资源里的值 —— 两者都无法区分「默认槽位装的是英文
 * 还是中文」。把 `values/` 与翻译目录对调,上述检查**全部照旧通过**,而真机上的表现
 * 已经反了(第三语言从英文变中文)。所以必须有一条断言直接钉住"哪种 locale 取到哪份文案"。
 *
 * ⚠️ 本组测试**刻意写死中英两份字面量**,是全仓唯一这样做的地方 —— 它测的就是
 * "语言选了以后取到哪一份",不断言具体语言就等于没测。这与 [TestText] 那套
 * "与语言无关"的纪律不冲突:那一套保证界面文案断言不会因换语言而失效,
 * 这一组则是反过来,专门验证换语言**确实**换文案。
 *
 * ⚠️ locale 限定符必须写在 `qualifiers` 的**最前面**(排在 `w400dp` 之类之前),
 * 否则 Robolectric 的 `QualifierParser` 抛异常。
 */
@RunWith(RobolectricTestRunner::class)
class ResourceFallbackTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Config(sdk = [34], qualifiers = "zh-rCN")
    @Test
    fun chinese_locale_resolves_the_chinese_strings() {
        assertEquals("设置", context.getString(R.string.settings_title))
        assertEquals("本组独立规则", context.getString(R.string.a_rule_scope_custom))
        assertEquals("文件名(自然)", context.getString(R.string.domain_sort_natural_name))
    }

    @Config(sdk = [34], qualifiers = "ja")
    @Test
    fun third_language_falls_back_to_the_english_default() {
        // 日语没有对应资源目录 ⇒ 必须落到默认槽位 `values/`,而默认槽位是英文。
        assertEquals("Settings", context.getString(R.string.settings_title))
        assertEquals("Custom rule for this set", context.getString(R.string.a_rule_scope_custom))
        assertEquals("File name (natural)", context.getString(R.string.domain_sort_natural_name))
    }

    @Config(sdk = [34])
    @Test
    fun english_locale_also_resolves_the_default_slot() {
        // Robolectric 默认 locale 是 en_US,没有 `values-en/`,只有默认 `values/`。
        assertEquals("Settings", context.getString(R.string.settings_title))
    }

    @Config(sdk = [34], qualifiers = "zh-rCN")
    @Test
    fun chinese_and_fallback_disagree_on_at_least_one_key() {
        // 反向守卫:若哪天有人把 values-zh/ 的内容误抄成英文(或把两份目录对调),
        // 上面几条"取值正确"的断言会失败,但若只断言"两边相等"就会漏 —— 故明确要求不同。
        assertNotEquals(
            context.getString(R.string.settings_title),
            "Settings",
            "中文槽位必须与兜底槽位不同,否则兜底语言其实没生效",
        )
    }

    @Config(sdk = [34], qualifiers = "zh-rCN")
    @Test
    fun chinese_plurals_resolve_without_a_one_variant() {
        // 中文侧只有 `other` 一档(lint `UnusedQuantity` 不允许 zh 出现 one)。
        // ⚠️ 只变 quantity、**格式化参数保持一致** —— 否则比的是两次不同的格式化结果,
        // 而不是"两档是否同一份文本"(第一次就是这么写错的,被本断言当场抓住)。
        val one = context.resources.getQuantityString(R.plurals.a_image_count, 1, 1)
        val two = context.resources.getQuantityString(R.plurals.a_image_count, 2, 1)
        assertEquals("共 1 张", one)
        assertEquals(one, two)
    }

    @Config(sdk = [34], qualifiers = "zh-rCN")
    @Test
    fun manual_adjustment_segment_keeps_its_leading_space() {
        // ⚠️ **AAPT2 会 trim 资源字面量的首尾空白**:`<item> · %1$d …</item>` 编译后前导空格消失,
        // 拼接出来读作 `…2 个组件启用· 1 处手工调整`(缺空格)。
        // 这正是本项目在 `domain_list_separator` 上踩过的同一个坑,存活写法是 `\u0020`。
        //
        // 为什么必须写死字面量、不能经 `TestText` 从同一份资源取期望值:**那样期望值与实际值
        // 一起被 trim,断言恒真**(本轮就是先按那种写法写完、四道闸门全绿,直到变异验证打印出
        // 实际报文才暴露)。披露段是否以空格开头是"渲染出来的样子",只能逐字钉死。
        val manual = context.resources.getQuantityString(R.plurals.domain_naming_summary_manual, 3, 3)
        assertEquals("\u0020· 含 3 处手工调整", manual)

        val withHead = context.resources.getQuantityString(
            R.plurals.domain_naming_summary_with_head, 2, "x.jpg", 2,
        )
        assertEquals("x.jpg · 2 个组件启用\u0020· 含 3 处手工调整", withHead + manual)
    }

    @Config(sdk = [34])
    @Test
    fun manual_adjustment_segment_in_the_english_default_keeps_its_leading_space() {
        // 英文侧同一坑(与中文断言分开,因为两边的复数形态与用词都不同)。
        //
        // ⚠️ 这里**不能**用 `qualifiers = "ja"` 来"测英文兜底":兜底取的是**文案**(默认槽位
        // 是英文,没错),但复数**变体**按 locale 的 CLDR 规则选 —— 日语只有 `other`,
        // 于是 quantity=1 仍取 `other`,拿到 "adjustments"(本断言第一次就是这么写错的)。
        // 默认 locale 是 en_US(⇒ `one` 形),故不写 qualifiers。
        val manual = context.resources.getQuantityString(R.plurals.domain_naming_summary_manual, 1, 1)
        assertEquals("\u0020· 1 manual adjustment", manual)
    }
}
