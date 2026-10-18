package com.cyanix.pixfold.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.AppLanguage
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.SettingsStore
import com.cyanix.pixfold.domain.settings.StorageAccess
import com.cyanix.pixfold.domain.settings.ThemeMode

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
        portraitDefaultColumns = readNullableColumns(KEY_COLUMNS_PORTRAIT),
        landscapeDefaultColumns = readNullableColumns(KEY_COLUMNS_LANDSCAPE),
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
            // `null` = 跟随自适应 = "没选过" ⇒ **删掉这个 key**,而不是写一个哨兵数字。
            // 两者在语义上不同:写哨兵就得再挑一个永远不会被选中的值(如 0 或 -1),
            // 那个约定一旦被将来的合法取值撞上就静默出错;删除 key 则天然让旧版本
            // 与新版本都读到"没选过"。
            putNullableColumns(KEY_COLUMNS_PORTRAIT, settings.portraitDefaultColumns)
            putNullableColumns(KEY_COLUMNS_LANDSCAPE, settings.landscapeDefaultColumns)
        }
    }

    /**
     * 读取"可空的列数":**缺失 key ⇒ `null`(跟随自适应)**。
     *
     * 与 [readEnum] 同一原则 —— 任何非法值都回退到默认(`null`),绝不抛异常。
     * 这里额外防一手 `<= 0`:0 列或负列数在界面上无意义,只可能来自手改的偏好文件,
     * 让它退回自适应比让它渲染出一个空网格好。
     */
    private fun readNullableColumns(key: String): Int? {
        if (!prefs.contains(key)) return null
        return prefs.getInt(key, 0).takeIf { it > 0 }
    }

    /** 写入可空列数:`null` 用 [SharedPreferences.Editor.remove],不写哨兵值。 */
    private fun SharedPreferences.Editor.putNullableColumns(key: String, value: Int?) {
        if (value == null) remove(key) else putInt(key, value)
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
        const val KEY_COLUMNS_PORTRAIT = "columns_portrait"
        const val KEY_COLUMNS_LANDSCAPE = "columns_landscape"
        const val KEY_LANGUAGE = "language"
        const val KEY_STORAGE = "storage_access"
    }
}
