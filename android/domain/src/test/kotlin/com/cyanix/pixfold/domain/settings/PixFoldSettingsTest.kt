package com.cyanix.pixfold.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 设置模型的不变式与默认值。
 *
 * 重点守两件事:
 *  1. **默认值**:首次运行的行为必须可预期(见 [PixFoldSettings] 注释);
 *  2. **`monetEnabled` 与 `accent` 的一致性** —— 二者表达同一件事,
 *     由派生保证,不存在"开着 Monet 却选了蓝色预设"这类非法组合。
 */
class PixFoldSettingsTest {

    @Test
    fun `defaults follow system theme and dynamic color`() {
        val s = PixFoldSettings()
        assertEquals(ThemeMode.System, s.themeMode, "主题默认跟随系统")
        assertEquals(AccentChoice.System, s.accent, "强调色默认跟随系统(即开启动态取色)")
        assertTrue(s.monetEnabled, "默认应开启动态取色 —— 与升级前 dynamicColor=true 行为一致")
        assertEquals(ColorStyle.TonalSpot, s.colorStyle)
        assertEquals(ColorSpecVersion.V2021, s.colorSpec)
        assertFalse(s.pureBlack, "纯黑背景默认关闭")
        assertNull(
            s.portraitDefaultColumns,
            "竖屏默认列数默认是 null —— 表示「跟随自适应」。纯 JVM 领域层编译期不知道屏幕宽," +
                "写死任何数字都会在窄屏上挤出问题;null 让界面用当机算出的值。",
        )
        assertNull(s.landscapeDefaultColumns, "横屏默认列数同理,默认跟随自适应")
        assertEquals(AppLanguage.System, s.language)
        assertTrue(s.predictiveBackAnimation, "预测性返回动画默认开启")
    }

    @Test
    fun `monetEnabled is derived from accent`() {
        // 派生而非独立字段:这是"非法组合不可构造"的关键。
        assertTrue(PixFoldSettings(accent = AccentChoice.System).monetEnabled)
        AccentChoice.entries.filter { it != AccentChoice.System }.forEach { preset ->
            assertFalse(
                PixFoldSettings(accent = preset).monetEnabled,
                "$preset 是预设种子色,不应视为开启动态取色",
            )
        }
    }

    @Test
    fun `withMonet off falls back to a real preset instead of staying on system`() {
        // 关键边界:关掉 Monet 后若仍保留 accent=System,就会出现
        // "开关是关的、但强调色仍跟随系统"的自相矛盾状态。
        val off = PixFoldSettings(accent = AccentChoice.System).withMonet(false)
        assertEquals(AccentChoice.Default, off.accent)
        assertFalse(off.monetEnabled)

        val on = off.withMonet(true)
        assertEquals(AccentChoice.System, on.accent)
        assertTrue(on.monetEnabled)
    }

    @Test
    fun `withAccent selecting system equals enabling monet`() {
        val s = PixFoldSettings(accent = AccentChoice.Blue)
        assertFalse(s.monetEnabled)
        val back = s.withAccent(AccentChoice.System)
        assertEquals(AccentChoice.System, back.accent)
        assertTrue(back.monetEnabled, "选「跟随系统」即等于开启动态取色")
    }

    @Test
    fun `setting changes do not disturb unrelated fields`() {
        // copy 的常见坑:改一个字段时误把别的字段重置。
        val base = PixFoldSettings(
            themeMode = ThemeMode.Dark,
            accent = AccentChoice.Green,
            colorStyle = ColorStyle.Vibrant,
            colorSpec = ColorSpecVersion.V2025,
            pureBlack = true,
            portraitDefaultColumns = 3,
            landscapeDefaultColumns = 7,
            language = AppLanguage.English,
        )
        val changed = base.copy(colorStyle = ColorStyle.Rainbow)
        assertEquals(ColorStyle.Rainbow, changed.colorStyle)
        assertEquals(base.copy(colorStyle = ColorStyle.Rainbow), changed)
        assertEquals(ThemeMode.Dark, changed.themeMode)
        assertEquals(AccentChoice.Green, changed.accent)
        assertEquals(ColorSpecVersion.V2025, changed.colorSpec)
        assertTrue(changed.pureBlack)
        assertEquals(3, changed.portraitDefaultColumns)
        assertEquals(7, changed.landscapeDefaultColumns)
        assertEquals(AppLanguage.English, changed.language)
    }
}
