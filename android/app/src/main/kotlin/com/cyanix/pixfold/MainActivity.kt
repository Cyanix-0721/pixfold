package com.cyanix.pixfold

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    /**
     * 这次启动是否应当直接落到「打包漫画 · 步骤 3」。
     *
     * **为什么需要它**（2026-10-20 真机缺陷）：打包的进度通知与完成通知都调
     * `PackTaskService.openAppIntent()`，那个 intent 原先只指向 `MainActivity`、
     * 不带任何目标 —— 于是点通知回来落在**首页**，而用户想看的是进度或结果。
     *
     * 读一次就把 intent 上的 extra **清掉**：否则用户从通知进来、按返回回首页后，
     * 若 Activity 因配置变更重建，`onCreate` 会**再次**读到同一个 intent 并把人
     * 弹回打包页，表现成「回不去首页」。
     */
    private var openPackOnStart by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 只在**首次**创建时读 intent。`savedInstanceState != null` 说明是配置变更
        // 重建，此时沿用已恢复的状态，不该再按 intent 重新导航一次。
        if (savedInstanceState == null) {
            openPackOnStart = intent?.getBooleanExtra(EXTRA_OPEN_LIBRARY_PACK, false) == true
            intent?.removeExtra(EXTRA_OPEN_LIBRARY_PACK)
        }
        setContent {
            PixFoldApp(
                openPackOnStart = openPackOnStart,
                // 导航完成后回清 —— 热启动时 `onNewIntent` 才能**再次**置 true 并
                // 触发 `LaunchedEffect(openPackOnStart)` 的 key 变化。
                // 不清零的话第二次点通知静默失效（真机第二轮实测）。
                onPackRouteHandled = { openPackOnStart = false },
            )
        }
    }

    /**
     * 应用已在后台时点通知走这里（`FLAG_ACTIVITY_CLEAR_TOP` 复用已有 Activity）。
     *
     * **这条路径比 `onCreate` 更常见**：用户点了「执行」→ 按 HOME 回桌面 →
     * 从通知点回来。只处理 `onCreate` 等于只修了「应用被杀掉」那一半。
     *
     * 用 `mutableStateOf` 而不是普通字段：`setContent` 里的 `PixFoldApp` 正在
     * 组合，写入必须触发重组才能真的导航过去。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_LIBRARY_PACK, false)) {
            openPackOnStart = true
            intent.removeExtra(EXTRA_OPEN_LIBRARY_PACK)
        }
    }

    companion object {
        /**
         * 请求启动后直接进入「打包漫画 · 步骤 3」的 intent extra。
         *
         * 常量放在这里而不是 `PackTaskService`：服务只负责**发**这个 extra，
         * `MainActivity` 才是**收**的一端。两边引同一个常量，拼错在编译期就暴露。
         */
        const val EXTRA_OPEN_LIBRARY_PACK: String = "com.cyanix.pixfold.extra.OPEN_LIBRARY_PACK"
    }
}
