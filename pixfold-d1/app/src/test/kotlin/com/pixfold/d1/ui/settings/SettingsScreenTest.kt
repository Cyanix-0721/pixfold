package com.pixfold.d1.ui.settings

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.pixfold.d1.domain.settings.AccentChoice
import com.pixfold.d1.domain.settings.AppLanguage
import com.pixfold.d1.domain.settings.ColorSpecVersion
import com.pixfold.d1.domain.settings.ColorStyle
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.domain.settings.StorageAccess
import com.pixfold.d1.domain.settings.ThemeMode
import com.pixfold.d1.ui.theme.PixFoldTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `reset asks for confirmation and then restores defaults`() {
        val custom = PixFoldSettings(
            themeMode = ThemeMode.Dark,
            accent = AccentChoice.Purple,
            colorStyle = ColorStyle.Vibrant,
            pureBlack = true,
            language = AppLanguage.English,
            storageAccess = StorageAccess.AllFiles,
        )
        show(custom)
        tap(TAG_SETTINGS_RESET)
        // 先出现二次确认 —— 不能点一下就重置
        rule.onNodeWithTag(TAG_SETTINGS_RESET_DIALOG).assertExists()
        assertEquals(custom, latest, "确认之前不得改动设置")

        tap(TAG_SETTINGS_RESET_CONFIRM)
        assertEquals(PixFoldSettings(), latest, "确认后应整体回到默认值(含语言与文件访问)")
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
}
