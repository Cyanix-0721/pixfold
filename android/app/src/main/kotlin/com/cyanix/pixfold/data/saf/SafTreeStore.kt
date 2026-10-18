package com.cyanix.pixfold.data.saf

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.cyanix.pixfold.domain.source.WorkspaceHandle

/**
 * 记住上次授权的工作区（D2a）。
 *
 * 只存**两样东西**：树 URI 字符串 + 显示名。不存遍历结果 ——
 * 目录内容随时会变，缓存一份列表只会显示过期数据。
 *
 * 与 [com.cyanix.pixfold.data.SharedPrefsSettingsStore] 同一范式：
 * 非法值/缺失值一律回退到「没选过」，绝不抛异常（这段代码在冷启动路径上）。
 */
class SafTreeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): WorkspaceHandle? {
        val uri = prefs.getString(KEY_TREE_URI, null) ?: return null
        if (uri.isEmpty()) return null
        val name = prefs.getString(KEY_DISPLAY_NAME, null).orEmpty()
        return WorkspaceHandle(treeUri = uri, displayName = name)
    }

    fun save(handle: WorkspaceHandle) {
        prefs.edit {
            putString(KEY_TREE_URI, handle.treeUri)
            putString(KEY_DISPLAY_NAME, handle.displayName)
        }
    }

    fun clear() {
        prefs.edit { remove(KEY_TREE_URI); remove(KEY_DISPLAY_NAME) }
    }

    private companion object {
        const val PREFS_NAME = "pixfold_workspace"
        const val KEY_TREE_URI = "tree_uri"
        const val KEY_DISPLAY_NAME = "tree_display_name"
    }
}
