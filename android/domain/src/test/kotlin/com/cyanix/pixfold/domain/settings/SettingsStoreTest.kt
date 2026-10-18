package com.cyanix.pixfold.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 设置存储接口的内存实现。
 *
 * 领域层不依赖 Android 的 `SharedPreferences`,测试用内存实现(见 `SettingsStore.kt` 的 KDoc);
 * 但反过来,这个内存实现本身此前**领域层零覆盖** —— 三个变异体(save 空实现 / load 永远给默认值 /
 * 构造时忽略初始值)全都存活。本文件把它的读写契约钉住。
 */
class SettingsStoreTest {

    @Test
    fun `a fresh store returns the default settings`() {
        assertEquals(PixFoldSettings(), InMemorySettingsStore().load())
    }

    @Test
    fun `save is visible to the next load`() {
        val store = InMemorySettingsStore()
        val saved = PixFoldSettings(themeMode = ThemeMode.Dark, colorStyle = ColorStyle.Rainbow)
        store.save(saved)
        assertEquals(saved, store.load(), "save 之后 load 必须拿回同一份设置")
        assertEquals(ThemeMode.Dark, store.load().themeMode)
    }

    @Test
    fun `the initial value is used instead of the default`() {
        val initial = PixFoldSettings(themeMode = ThemeMode.Dark)
        assertEquals(ThemeMode.Dark, InMemorySettingsStore(initial).load().themeMode)
    }

    @Test
    fun `each store instance keeps its own value`() {
        val a = InMemorySettingsStore()
        val b = InMemorySettingsStore()
        a.save(PixFoldSettings(themeMode = ThemeMode.Dark))
        assertEquals(ThemeMode.System, b.load().themeMode, "两个实例互不干扰")
    }
}
