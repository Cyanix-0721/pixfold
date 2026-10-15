package com.pixfold.d1.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pixfold.d1.R
import com.pixfold.d1.TestText
import com.pixfold.d1.domain.settings.AccentChoice
import com.pixfold.d1.domain.settings.AppLanguage
import com.pixfold.d1.domain.settings.ColorSpecVersion
import com.pixfold.d1.domain.settings.ColorStyle
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.domain.settings.StorageAccess
import com.pixfold.d1.domain.settings.ThemeMode
import com.pixfold.d1.ui.theme.PixFoldTheme
import com.pixfold.d1.ui.theme.isAffectedByColorSpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设置页 UI 断言。
 *
 * **重点不是"控件存在",而是"改动真的落到状态上"** ——
 * 项目里出现过"领域做完、界面没接上"的缺陷(P6 的确认框未渲染),
 * 所以这里每项都断言**回调真的带出了新值**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2000dp")
class SettingsScreenTest {

    @get:Rule
    val rule = createComposeRule()

    /** 用可观察的状态承载设置,并顺手把"最新值"暴露给断言。 */
    private var latest: PixFoldSettings = PixFoldSettings()
    private var licensesOpened = false
    private var backPressed = false

    private fun show(initial: PixFoldSettings = PixFoldSettings()) {
        latest = initial
        rule.setContent {
            var settings by remember { mutableStateOf(initial) }
            PixFoldTheme(settings = settings) {
                SettingsScreen(
                    settings = settings,
                    onChange = { settings = it; latest = it },
                    onBack = { backPressed = true },
                    onOpenLicenses = { licensesOpened = true },
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
    }

    /**
     * 点击某设置行。
     *
     * 先尝试 `performScrollTo`(行大多在可滚动区内、可能在屏幕外),
     * 但**顶部返回键等节点不在滚动容器内**,对其调用会抛
     * "no parent layout with a Scroll SemanticsAction" —— 故容错后直接点击。
     */
    private fun tap(tag: String) {
        val node = rule.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
        rule.waitForIdle()
    }

    /**
     * 在"多选一"对话框里按**选项序号**选择。
     *
     * 用 testTag 而不是文本匹配:文本会随语言/措辞变化,序号与枚举顺序绑定,
     * 断言更稳(且被改文案时不会误报失败)。
     */
    private fun chooseOption(rowTag: String, index: Int) {
        rule.onNodeWithTag(TAG_CHOICE_DIALOG).assertExists()
        rule.onNodeWithTag(choiceOptionTag("$rowTag-$index")).performClick()
        rule.waitForIdle()
    }

    @Test
    fun `all four sections and key rows are present`() {
        show()
        listOf(
            TAG_SETTINGS_MONET,
            TAG_SETTINGS_ACCENT,
            TAG_SETTINGS_COLOR_STYLE,
            TAG_SETTINGS_COLOR_SPEC,
            TAG_SETTINGS_PURE_BLACK,
            TAG_SETTINGS_LANGUAGE,
            TAG_SETTINGS_STORAGE,
            TAG_SETTINGS_COLUMNS_PORTRAIT,
            TAG_SETTINGS_COLUMNS_LANDSCAPE,
            TAG_SETTINGS_PREDICTIVE_BACK,
            TAG_SETTINGS_VERSION,
            TAG_SETTINGS_REPO,
            TAG_SETTINGS_RELEASE,
            TAG_SETTINGS_AUTHOR,
            TAG_SETTINGS_LICENSES,
            TAG_SETTINGS_RESET,
        ).forEach { tag ->
            rule.onNodeWithTag(tag).assertExists()
        }
        // 主题三态三项都在
        ThemeMode.entries.forEach { rule.onNodeWithTag(themeOptionTag(it)).assertExists() }
    }

    @Test
    fun `choosing dark theme reports it upward`() {
        show()
        tap(themeOptionTag(ThemeMode.Dark))
        assertEquals(ThemeMode.Dark, latest.themeMode, "选深色应上报新值")
        assertTrue(latest.monetEnabled, "改主题不应影响强调色来源")
    }

    @Test
    fun `turning monet off switches accent to a preset`() {
        show(PixFoldSettings(accent = AccentChoice.System))
        tap(TAG_SETTINGS_MONET)
        assertFalse(latest.monetEnabled, "关掉开关后不应再是动态取色")
        assertEquals(
            AccentChoice.Default,
            latest.accent,
            "关掉 Monet 应回落到具体预设,而不是保留 System(否则自相矛盾)",
        )
    }

    @Test
    fun `pure black toggle reports it upward`() {
        show()
        assertFalse(PixFoldSettings().pureBlack)
        tap(TAG_SETTINGS_PURE_BLACK)
        assertTrue(latest.pureBlack)
    }

    @Test
    fun `color style selection reports it upward`() {
        show()
        tap(TAG_SETTINGS_COLOR_STYLE)
        // ColorStyle 枚举顺序:TonalSpot0 Vibrant1 Expressive2 Neutral3
        // Monochrome4 Fidelity5 Content6 Rainbow7 FruitSalad8
        chooseOption(TAG_SETTINGS_COLOR_STYLE, ColorStyle.entries.indexOf(ColorStyle.Rainbow))
        assertEquals(ColorStyle.Rainbow, latest.colorStyle, "选色彩风格应上报新值")
    }

    @Test
    fun `color spec 2025 selection reports it upward`() {
        show()
        assertEquals(ColorSpecVersion.V2021, latest.colorSpec)
        tap(TAG_SETTINGS_COLOR_SPEC)
        chooseOption(TAG_SETTINGS_COLOR_SPEC, ColorSpecVersion.entries.indexOf(ColorSpecVersion.V2025))
        assertEquals(ColorSpecVersion.V2025, latest.colorSpec, "应能切到 2025 色彩标准")
    }

    /**
     * 「色彩标准」在不受其影响的色彩风格下**置灰**(2026-09-21 用户裁决)。
     *
     * 依据(实测,见 `ColorSchemes.COLOR_SPEC_SENSITIVE_STYLES`):算法库的
     * `ColorSpec2025` 只重写 4 个变体,选中 Monochrome 等 5 个风格时两套规范产出
     * **逐字节相同**的配色 —— 该行给了选择却毫无反馈,是"假开关"。
     */
    @Test
    fun `color spec row is greyed out for styles the spec does not affect`() {
        val unaffectedStyle = ColorStyle.entries.first { !it.isAffectedByColorSpec() }
        show(PixFoldSettings(colorStyle = unaffectedStyle))

        val row = rule.onNodeWithTag(TAG_SETTINGS_COLOR_SPEC)
        row.assertIsNotEnabled()
        // 行为断言(不只依赖 semantics):点它不该弹出可选的对话框。
        runCatching { row.performClick() }
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_CHOICE_DIALOG).assertDoesNotExist()
    }

    /** 反向:受影响的风格下必须可点(否则就是"修反了",把正常功能也关掉)。 */
    @Test
    fun `color spec row stays enabled for styles the spec affects`() {
        val affectedStyle = ColorStyle.entries.first { it.isAffectedByColorSpec() }
        show(PixFoldSettings(colorStyle = affectedStyle))

        rule.onNodeWithTag(TAG_SETTINGS_COLOR_SPEC).assertIsEnabled()
        tap(TAG_SETTINGS_COLOR_SPEC)
        rule.onNodeWithTag(TAG_CHOICE_DIALOG).assertExists()
    }

    @Test
    fun `storage access selection reports it upward`() {
        show()
        tap(TAG_SETTINGS_STORAGE)
        chooseOption(TAG_SETTINGS_STORAGE, StorageAccess.entries.indexOf(StorageAccess.AllFiles))
        assertEquals(StorageAccess.AllFiles, latest.storageAccess)
    }

    @Test
    fun `language selection reports it upward`() {
        show()
        tap(TAG_SETTINGS_LANGUAGE)
        chooseOption(TAG_SETTINGS_LANGUAGE, AppLanguage.entries.indexOf(AppLanguage.English))
        assertEquals(AppLanguage.English, latest.language, "选语言应上报新值")
    }

    /**
     * 语言区**不得**再出现「其他页面稍后补全」这类说明(2026-10-14 真机走查发现)。
     *
     * 该说明是设置与主题体系**阶段 1** 的产物 —— 当时只有设置页自身被本地化
     * (规格 §9.3)。**全量 i18n 已于 2026-09-25 落地**
     * (`2221ef1` / `0368be5` / `53149ba`),此后这句话就是**假信息**:
     * 界面在告诉用户「多语言还没做」,而实际上全应用早已双语。
     *
     * 用户走查原话(2026-10-14):「如果你发现什么问题也要提出来」——
     * 这条即当时提出的候选之一,真机 `set1.xml` 节点 `[56,2433][751,2489]` 逐字取证。
     *
     * 断言用**字面量**而非 `TestText.of(R.string.…)`:本用例要守的正是
     * 「**这段文字永远不要再出现**」。若经资源 id 取,删掉资源后测试连编译都过不了;
     * 而保留资源又等于允许它回来 —— 字面量是这里唯一能表达该意图的写法。
     * (Robolectric 默认 locale 为 `en_US`,解析默认槽位 `values/`,故取英文。)
     */
    @Test
    fun `language section no longer claims other screens are untranslated`() {
        show()
        val stale = rule
            .onAllNodesWithText("translated in a later version", substring = true)
            .fetchSemanticsNodes()
        assertTrue(
            stale.isEmpty(),
            "语言区不应再声称「其他页面稍后补全」—— 全量 i18n 已于 2026-09-25 完成;实测 ${stale.size} 个节点命中",
        )
    }

    /**
     * 「竖屏默认列数」选一个固定数字应上报到 `portraitDefaultColumns`。
     *
     * 选项 0 固定是「跟随自适应」,故选项 1 = 下界 `MIN_COLUMNS` = 2 列。
     * 这两个顺序断言一起把"列表结构"钉住 —— 只断言"汇报了 2"的话,
     * 若把「跟随自适应」从第 0 位挪走,本用例会点错格子却依然通过(因为还是 2)。
     */
    @Test
    fun `portrait default columns reports a fixed number upward`() {
        show()
        tap(TAG_SETTINGS_COLUMNS_PORTRAIT)
        rule.onNodeWithTag(choiceOptionTag("$TAG_SETTINGS_COLUMNS_PORTRAIT-0")).assertExists()
        chooseOption(TAG_SETTINGS_COLUMNS_PORTRAIT, 1)
        assertEquals(2, latest.portraitDefaultColumns, "选项 1 应是下界 2 列")
    }

    /**
     * 「横屏默认列数」与竖屏那一项**互相独立**(用户裁决:「设置里竖屏横屏分出 2 个选项」)。
     *
     * 改横屏不该动竖屏 —— 若两行误接同一个字段,这里会红。
     */
    @Test
    fun `landscape default columns is independent from the portrait one`() {
        show()
        tap(TAG_SETTINGS_COLUMNS_LANDSCAPE)
        chooseOption(TAG_SETTINGS_COLUMNS_LANDSCAPE, 1)
        assertEquals(2, latest.landscapeDefaultColumns)
        assertNull(latest.portraitDefaultColumns, "改横屏那一项不应动竖屏那一项")
    }

    /**
     * 「跟随自适应」是设置页里一个**显式选项**,选它把默认值清回 `null`。
     *
     * 这是用户第二轮补充裁决的核心:「设置里两个方向各多一项「跟随自适应」(显示算出的数);
     * 选它就回到实时跟随」。`null` 的语义在领域层 = 未选择 = 跟随自适应。
     */
    @Test
    fun `choosing follow adaptive clears the fixed default`() {
        show(PixFoldSettings(portraitDefaultColumns = 3))
        tap(TAG_SETTINGS_COLUMNS_PORTRAIT)
        chooseOption(TAG_SETTINGS_COLUMNS_PORTRAIT, 0)
        assertNull(latest.portraitDefaultColumns, "选「跟随自适应」应把默认值清回 null")
    }

    /**
     * 「跟随自适应」这一项**必须把算出来的列数显示出来**
     * (用户裁决:「显示算出的数」)。
     *
     * 否则"跟随自适应"是句空话 —— 用户看不出跟随的结果是不是自己想要的。
     * 本限定符下 `w400dp-h2000dp` ⇒ 竖屏宽 = `min(400, 2000)` = 400,
     * 算式 `(400 - 16) / 80 = 4.8` ⇒ 4 列。
     *
     * ## 为什么断言里会出现两个同名节点
     *
     * `ChoiceRow` 要求「当前值文案」与「选项文案」**逐字相同**(它靠 `text == current`
     * 判 RadioButton 选中),而两处都取自同一个资源 `settings_columns_adaptive_value`。
     * 于是"行上显示的当前值"与"对话框里的选项"必然同串 —— 这不是巧合,是设计约束。
     * 故这里用 `onAllNodesWithText` 断言存在即可:若有人把资源里的 `%1$d` 去掉
     * (变回纯文字「跟随自适应」),两处会一起变,断言即红。
     */
    @Test
    fun `follow adaptive option shows the computed number`() {
        show()
        tap(TAG_SETTINGS_COLUMNS_PORTRAIT)

        val expected = TestText.of(R.string.settings_columns_adaptive_value, 4)
        assertTrue(
            rule.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty(),
            "「跟随自适应」应显示算出的列数(期望出现「$expected」)",
        )
    }

    /**
     * 两个方向各显示**自己那个**算出的数,而不是共用同一个。
     *
     * 本限定符 `w400dp-h2000dp` 下两者差别很大:
     *  - 竖屏宽 = `min(400, 2000)` = 400 ⇒ `(400 - 16) / 80` = 4 列;
     *  - 横屏宽 = `max(400, 2000)` = 2000 ⇒ `(2000 - 16) / 80` = 24 列。
     *
     * 只断言竖屏那一项时,「两行都按竖屏宽度算」的缺陷会**完全溜过去**
     * (实测:该变异存活)。这里把横屏那一项自己钉住 —— 它必须播报 24,
     * 播报 4 说明两行共用了竖屏宽度。
     */
    @Test
    fun `each orientation shows its own adaptive number`() {
        show()

        // 两行各自都常驻显示自己算出的数:竖屏行 4、横屏行 24。
        // 症状:若两行共用竖屏宽度,横屏行会变成 Auto (4),页面里再也找不到 24。
        val portraitNumber = TestText.of(R.string.settings_columns_adaptive_value, 4)
        val landscapeNumber = TestText.of(R.string.settings_columns_adaptive_value, 24)
        assertTrue(
            rule.onAllNodesWithText(portraitNumber).fetchSemanticsNodes().isNotEmpty(),
            "竖屏「跟随自适应」应显示 4 列(期望出现「$portraitNumber」)",
        )
        assertTrue(
            rule.onAllNodesWithText(landscapeNumber).fetchSemanticsNodes().isNotEmpty(),
            "横屏「跟随自适应」应显示横屏宽度算出的 24 列" +
                "(显示 4 说明误用了竖屏宽度)",
        )

        // 展开横屏行,确认它自己那一项在选项列表里也带着 24(而不只是行上显示)
        tap(TAG_SETTINGS_COLUMNS_LANDSCAPE)
        assertTrue(
            rule.onAllNodesWithText(landscapeNumber).fetchSemanticsNodes().isNotEmpty(),
            "展开横屏行后「跟随自适应」选项仍应显示 24",
        )
    }

    @Test
    fun `reset asks for confirmation and then restores defaults`() {
        val custom = PixFoldSettings(
            themeMode = ThemeMode.Dark,
            accent = AccentChoice.Purple,
            colorStyle = ColorStyle.Vibrant,
            pureBlack = true,
            portraitDefaultColumns = 3,
            landscapeDefaultColumns = 5,
            language = AppLanguage.English,
            storageAccess = StorageAccess.AllFiles,
        )
        show(custom)
        tap(TAG_SETTINGS_RESET)
        // 先出现二次确认 —— 不能点一下就重置
        rule.onNodeWithTag(TAG_SETTINGS_RESET_DIALOG).assertExists()
        assertEquals(custom, latest, "确认之前不得改动设置")

        tap(TAG_SETTINGS_RESET_CONFIRM)
        assertEquals(PixFoldSettings(), latest, "确认后应整体回到默认值(含语言、文件访问与两向默认列数)")
    }

    @Test
    fun `licenses entry opens the licenses page`() {
        show()
        tap(TAG_SETTINGS_LICENSES)
        assertTrue(licensesOpened, "「开源许可」应能跳转")
    }

    @Test
    fun `back button reports upward`() {
        show()
        tap(TAG_SETTINGS_BACK)
        assertTrue(backPressed)
    }

    @Test
    fun `about shows version and author`() {
        show()
        rule.onNodeWithTag(TAG_SETTINGS_AUTHOR).assertExists()
        rule.onNodeWithTag(TAG_SETTINGS_VERSION).assertExists()
    }

    @Test
    fun `predictive back animation defaults on and can be turned off`() {
        // 默认**开**:预测性返回是系统本来的行为,关掉只是去掉动画。
        show()
        assertTrue(PixFoldSettings().predictiveBackAnimation, "默认应开启")
        tap(TAG_SETTINGS_PREDICTIVE_BACK)
        assertFalse(latest.predictiveBackAnimation, "关掉后应上报 false")
        tap(TAG_SETTINGS_PREDICTIVE_BACK)
        assertTrue(latest.predictiveBackAnimation, "再次点击应能开回来")
    }
}
