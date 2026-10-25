package com.cyanix.pixfold

import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.RememberResult
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource

/**
 * 导航测试用的工作区假源：**冷启动即就绪**，内容取 [MockData.workspaceA]。
 *
 * ## 为什么需要它（2026-10-24）
 *
 * 用户裁决「未选工作区时首页两张大卡禁用」之后，首页入口的可用性取决于
 * `WorkspaceState`；而 Robolectric 的 `ContentResolver` 里没有任何
 * `DocumentsProvider`，真实 `AndroidSafSource.workspaces()` 恒为空列表
 * ⇒ state 恒为 `None` ⇒ 两张大卡恒禁用 ⇒ 所有「在首页点卡片进工作流」的
 * 导航测试一起红。
 *
 * **那不是行为回归**：测试环境里根本没有可授权的目录。给一个「已经选好目录」的
 * 假源，这些测试就回到它们真正要验的东西（返回栈、步骤推进、通知跳转）。
 *
 * ## 为什么内容仍是 mock
 *
 * 就绪前 `collectionsOr(mock)` 回退的也是同一份 mock —— 换个来源、同一份数据,
 * 导航测试里那些「第 N 个集合叫什么」的断言因此一行都不用改。
 *
 * ## 刻意不做的
 *
 * 不模拟 SAF 的读写（`readBytes` 返 null、`watchChanges` 用接口默认的空实现）。
 * 真实遍历与缩略图是只能在真机上断的类别（AGENTS.md §7.4），
 * 在这里造只会得到一堆「什么都没读到」的假保障。
 */
class ReadyWorkspaceSource(
    private val handle: WorkspaceHandle = WorkspaceHandle(
        treeUri = "content://com.cyanix.pixfold.test/tree/workspace",
        displayName = "测试工作区",
    ),
    private val collections: List<ImageCollection> = MockData.workspaceA.collections,
) : WorkspaceSource {

    /** 就绪状态 = 列表里本来就有这一条，且它排在最前（= 上次用过的）。 */
    private val entries = mutableListOf(WorkspaceEntry(handle, pinned = false))

    override fun workspaces(): List<WorkspaceEntry> = entries.toList()

    override fun rememberWorkspace(handle: WorkspaceHandle): RememberResult = RememberResult.Added

    override fun forgetWorkspace(treeUri: String) = Unit

    override fun setPinned(treeUri: String, pinned: Boolean) = Unit

    override fun moveWorkspace(from: Int, to: Int) = Unit

    override fun loadCollections(handle: WorkspaceHandle): LoadResult =
        LoadResult.Success(collections)

    override fun readBytes(item: SourceItem): ByteArray? = null
}
