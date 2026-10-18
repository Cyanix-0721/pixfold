package com.cyanix.pixfold.ui.workspace

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * 缩略图来源（D2a）。
 *
 * **为什么是 `CompositionLocal` 而不是参数一路透传**：缩略图出现在 5 个互不相干的
 * 位置（网格卡 `DraggableThumbCard`、列表行 `ThumbRow`、拖拽浮层 `DragGhost` ×2、
 * 预览页 `ImagePreview`），其中两处（DragGhost）根本不在页面参数的传递路径上。
 * 逐个加参数会让「要不要真实缩略图」变成 5 个各自独立的开关，容易漂移。
 *
 * **默认值 `null` 是刻意的**：全部既有 UI 测试都在 `LocalThumbnailSource = null` 下运行，
 * 于是回退到 D1 的 `ProceduralThumb` —— 491 个测试的截图/断言行为一行都不用改。
 * 真机与端到端路径才由 `PixFoldApp` 提供 Android 实现。
 *
 * **线程契约**：实现方自己切线程（`Dispatchers.IO`），调用方只当 `suspend` 用。
 * 返回 `null` 表示「取不到」，这是**正常业务分支**（provider 不支持缩略图、
 * 授权失效、文件已删），不是异常 —— 与 `WorkspaceSource.readBytes` 同一约定。
 */
fun interface ThumbnailSource {

    /**
     * 取 [uri] 的缩略图，期望尺寸约 [widthPx] × [heightPx]（实现方可自行取整/降采样）。
     *
     * @return 取不到时返回 `null`，调用方回退到程序化缩略图。
     */
    suspend fun thumbnail(uri: String, widthPx: Int, heightPx: Int): ImageBitmap?
}

/**
 * 当前可用的缩略图来源；`null` = 一律用程序化缩略图（D1 行为 / 测试环境）。
 *
 * 用 `staticCompositionLocalOf` 而非 `compositionLocalOf`：这个值在整棵树上恒定
 * （应用启动时注入一次），不需要细粒度订阅，`static` 版本读取开销更低。
 */
val LocalThumbnailSource = staticCompositionLocalOf<ThumbnailSource?> { null }
