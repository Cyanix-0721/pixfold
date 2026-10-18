package com.cyanix.pixfold

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.test.core.app.ApplicationProvider

/**
 * 测试里取**界面文案**的统一入口。
 *
 * ## 为什么必须这样取(而不是写中文字面量)
 *
 * 裁决 `test_strategy = B:全部测试改为语言无关`。Robolectric 的默认 locale 是
 * **en_US**(实测:`resources.configuration.locales[0] == en_US`),而英文正是资源的
 * **默认槽位**(`values/`),故界面上渲染的是英文文案。此前测试里写死的中文断言在
 * i18n 落地后**全部失效** —— 这不是"测试坏了",而是它们本来就只在中文下成立。
 *
 * 期望值经资源解析后:①换语言不再让测试变红;②资源文案与断言**同源**,
 * 改文案只需改资源;③仍然真的在断言"界面上显示了正确的文案",不是退化成
 * `assertExists()` 这类空断言。
 *
 * ⚠️ 只用于**界面文案**。文件名词缀(`第01卷.cbz`)、语言码(`ja`)、mock 数据里的
 * 中文串是**数据**不是文案,继续保持字面量 —— 它们本来就与界面语言无关。
 */
object TestText {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 取普通 `<string>`。可带格式化参数,与界面侧的 `stringResource` 同参。 */
    fun of(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    /**
     * 取 `<plurals>`。
     *
     * 界面侧的 `pluralStringResource(id, count, ...)` 会按 `count` 选变格,
     * 测试必须走同一路径,否则英文的 one/other 变体会选错。
     */
    @Suppress("FunctionName")
    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String =
        context.resources.getQuantityString(id, count, *args)

    /**
     * 取 `<string>` 的**原始模板**:占位符原样保留,不做格式化。
     *
     * 与 [of] 的区别是"要不要填参数":模板里有 `%1$s` 时,`getString(id)` 不传参
     * 会抛 `MissingFormatArgumentException` —— 而有些断言想问的正是**模板本身**
     * (例如"告警前缀是什么"),不是某一次渲染的结果。
     *
     * [getText] 只解码字符串、不套用格式化,故 `%1$s` 会原样返回。
     */
    fun raw(@StringRes id: Int): String = context.resources.getText(id).toString()
}
