package com.pixfold.d1.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pixfold.d1.domain.settings.AccentChoice
import com.pixfold.d1.domain.settings.AppLanguage
import com.pixfold.d1.domain.settings.ColorSpecVersion
import com.pixfold.d1.domain.settings.ColorStyle
import com.pixfold.d1.domain.settings.PixFoldSettings
import com.pixfold.d1.domain.settings.SettingsStore
import com.pixfold.d1.domain.settings.StorageAccess
import com.pixfold.d1.domain.settings.ThemeMode

/**
 * 基于 [SharedPreferences] 的设置存储。
 *
 * **两个刻意的设计决定**:
 *
 * 1. **每个字段独立 key**,而不是把整个对象序列化成一个字符串。
 *    这样将来**增删字段**时,旧版本写入的数据不会导致整份设置解析失败 ——
 *    缺字段走默认值、多字段被忽略,天然向后/向前兼容。
 *
 * 2. **任何非法值都回退到默认值**([readEnum]),绝不抛异常。
 *    旧版本残留的枚举名(例如某个取值后来被删掉)只会让**该项**回到默认,
 *    而不是让应用启动即崩溃。设置读取发生在应用启动路径上,这一点尤其重要。
 */
class SharedPrefsSettingsStore(context: Context) : SettingsStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): PixFoldSettings = PixFoldSettings(
        themeMode = readEnum(KEY_THEME_MODE, ThemeMode.entries, ThemeMode.System),
        accent = readEnum(KEY_ACCENT, AccentChoice.entries, AccentChoice.System),
        colorStyle = readEnum(KEY_COLOR_STYLE, ColorStyle.entries, ColorStyle.TonalSpot),
        colorSpec = readEnum(KEY_COLOR_SPEC, ColorSpecVersion.entries, ColorSpecVersion.V2021),
        pureBlack = prefs.getBoolean(KEY_PURE_BLACK, false),
        predictiveBackAnimation = prefs.getBoolean(KEY_PREDICTIVE_BACK, true),
        language = readEnum(KEY_LANGUAGE, AppLanguage.entries, AppLanguage.System),
        storageAccess = readEnum(KEY_STORAGE, StorageAccess.entries, StorageAccess.Saf),
    )

    override fun save(settings: PixFoldSettings) {
        // 用 KTX 的 edit {}:内部保证 apply 一定被调用,避免漏写 commit/apply。
        prefs.edit {
            putString(KEY_THEME_MODE, settings.themeMode.name)
            putString(KEY_ACCENT, settings.accent.name)
            putString(KEY_COLOR_STYLE, settings.colorStyle.name)
            putString(KEY_COLOR_SPEC, settings.colorSpec.name)
            putBoolean(KEY_PURE_BLACK, settings.pureBlack)
            putBoolean(KEY_PREDICTIVE_BACK, settings.predictiveBackAnimation)
            putString(KEY_LANGUAGE, settings.language.name)
            putString(KEY_STORAGE, settings.storageAccess.name)
        }
    }

    /** 读取枚举:值缺失或已不认识时回退 [fallback],**不抛异常**。 */
    private fun <T : Enum<T>> readEnum(key: String, values: List<T>, fallback: T): T {
        val raw = prefs.getString(key, null) ?: return fallback
        return values.firstOrNull { it.name == raw } ?: fallback
    }

    private companion object {
        const val PREFS_NAME = "pixfold_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ACCENT = "accent"
        const val KEY_COLOR_STYLE = "color_style"
        const val KEY_COLOR_SPEC = "color_spec"
        const val KEY_PURE_BLACK = "pure_black"
        const val KEY_PREDICTIVE_BACK = "predictive_back_animation"
        const val KEY_LANGUAGE = "language"
        const val KEY_STORAGE = "storage_access"
    }
}
