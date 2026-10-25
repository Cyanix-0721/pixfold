package com.cyanix.pixfold.domain.source

/** [WorkspaceSource.rememberWorkspace] 的结果。 */
sealed interface RememberResult {
    /** 新增成功。 */
    data object Added : RememberResult

    /** 已存在同一 treeUri：已更新名字并移到最前，`pinned` 原样保留。 */
    data object MovedToFront : RememberResult

    /**
     * 已满上限且全部钉住，无处可挤。列表一个字都没改。
     *
     * 返回值不是可选项 —— 静默失败会让用户以为「添加成功但列表没变」。
     */
    data object RejectedAllPinned : RememberResult
}
