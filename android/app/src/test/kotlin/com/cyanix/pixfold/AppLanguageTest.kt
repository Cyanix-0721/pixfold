package com.cyanix.pixfold

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.domain.settings.AppLanguage
import com.cyanix.pixfold.domain.settings.InMemorySettingsStore
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.ui.TAG_SETTINGS_BUTTON
import com.cyanix.pixfold.ui.settings.TAG_SETTINGS_LANGUAGE
import com.cyanix.pixfold.ui.settings.choiceOptionTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * 应用语言与系统 per-app locale 的**同步**。
 *
 * **为什么需要这组测试**:这条路径此前**零覆盖** ——
 * `ApplyAppLanguage` 只被真机实测检验过,于是"启动时用默认值把系统侧选择擦掉"
 * 这个缺陷一直漏到用户报告才被发现(见 `docs/notes/d1-ui-friction-inventory.md`)。
 *
 * **要守住的语义**:`LocaleList.getEmptyLocaleList()` 在 AOSP 里**不是**
 * "未设置",而是"重置为系统语言"这个**主动写入**
 * (AOSP `LocaleManager#setApplicationLocales`:"Pass a
 * `LocaleList.getEmptyLocaleList()` to reset to the system locale");
 * 因此启动时按应用内默认值(System)写入,等于把用户在
 * "设置 → 应用 → 语言"里的选择清掉。
 *
 * `LocaleManager` 由 Robolectric 的 `ShadowLocaleManager` 承接,
 * 故这里读到/写入的就是系统侧的那份状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLanguageTest {

    @get:Rule
    val rule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val manager: LocaleManager
        get() = requireNotNull(context.getSystemService(LocaleManager::class.java))

    private fun appLocales(): LocaleList = manager.applicationLocales

    /**
     * **回归守卫**:系统侧已选的语言,不能因为应用启动而被清掉。
     *
     * 复现路径(用户实报):系统"设置 → 应用 → 语言"选 English →
     * 打开应用 → 应用内设置仍是默认的"跟随系统" → 启动逻辑算出空表并写入 →
     * 系统侧选择被重置,界面退回中文。
     */
    @Test
    fun `system per-app locale survives app start with default in-app setting`() {
        val english = LocaleList.forLanguageTags("en")
        manager.setApplicationLocales(english) // 模拟用户在系统设置里选了 English
        assertEquals(english, appLocales(), "前置条件:系统侧应已存下 English")

        // 应用内设置保持默认 —— 即 AppLanguage.System
        rule.setContent { PixFoldApp(store = InMemorySettingsStore()) }
        rule.waitForIdle()

        assertEquals(
            english,
            appLocales(),
            "应用启动不得清掉系统侧的语言选择(空表 = 重置为系统语言,是一次主动写入)",
        )
    }

    /**
     * 反面守卫:应用内**主动改**语言必须真的写到系统 per-app locale。
     *
     * 若只守上面那条(启动不写),很容易把"应用内改语言"一并弄失效 ——
     * 那会把缺陷从"乱清"变成"根本不生效"。
     */
    @Test
    fun `changing language in settings writes system per-app locale`() {        rule.setContent { PixFoldApp(store = InMemorySettingsStore()) }
        rule.waitForIdle()

        // 首页 → 设置
        rule.onNodeWithTag(TAG_SETTINGS_BUTTON).performClick()
        rule.waitForIdle()

        // 语言行 → English
        val row = rule.onNodeWithTag(TAG_SETTINGS_LANGUAGE)
        runCatching { row.performScrollTo() }
        row.performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(choiceOptionTag("$TAG_SETTINGS_LANGUAGE-${AppLanguage.entries.indexOf(AppLanguage.English)}"))
            .performClick()
        rule.waitForIdle()

        assertEquals(
            LocaleList.forLanguageTags("en"),
            appLocales(),
            "在应用内选 English 应当写入系统 per-app locale",
        )
    }

    /**
     * "跳过启动首帧"**只能跳过 System 这个默认态**,不能连已存的显式选择一起跳过。
     *
     * 这条守的是修复本身的**回归风险**:把跳过条件写成"首帧一律不写"虽然也能
     * 让上面那条通过,却会让用户上次选的 English 在下次启动时失效 ——
     * 那是把"乱清"换成了"存了不生效",同一个缺陷换个方向复发。
     */
    @Test
    fun `stored explicit language is applied on startup`() {
        rule.setContent {
            PixFoldApp(store = InMemorySettingsStore(PixFoldSettings(language = AppLanguage.English)))
        }
        rule.waitForIdle()

        assertEquals(
            LocaleList.forLanguageTags("en"),
            appLocales(),
            "存储里已显式选定的语言,启动时必须应用到系统 per-app locale",
        )
    }
}
