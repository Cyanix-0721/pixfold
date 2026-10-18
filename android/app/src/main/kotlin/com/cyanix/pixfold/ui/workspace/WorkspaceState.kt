package com.cyanix.pixfold.ui.workspace

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.WorkspaceHandle

/**
 * 工作区（D2a）在 UI 层的状态。
 *
 * **为什么单独抽一个类型**：`LoadResult` 是领域层的结果，只有三种（成功/授权失效/失败），
 * 而界面还需要表达「还没选过目录」和「正在扫描」。把两者之间的映射写成**纯函数**
 * （见 [toWorkspaceState]）就能离线单测，不必拉起 Android 环境。
 *
 * **为什么 [None] 不是错误**：D1 阶段全部数据来自 mock，用户从没选过目录是**正常初态**。
 * 见 [collectionsOr]：没有真实工作区时回退 mock，D1 的演示与既有 491 个测试因此不受影响。
 */
sealed interface WorkspaceState {

    /** 从未选过目录（或用户「忘记」了它）。界面按 mock 数据演示。 */
    data object None : WorkspaceState

    /** 正在扫描目录（递归遍历可能要几秒，千级文件更久）。 */
    data object Loading : WorkspaceState

    /**
     * 扫描成功。
     *
     * `collections` **可能为空** —— 目录里一张图片都没有是合法状态，不是错误。
     * 界面必须能显示「这个目录里没有图片」，不能崩（`collections.first()` 会崩）。
     */
    data class Ready(
        val handle: WorkspaceHandle,
        val collections: List<ImageCollection>,
    ) : WorkspaceState

    /**
     * 授权已失效（用户撤销 / 系统回收 / 换机）。
     *
     * 这是**预期内**状态，不是崩溃理由（HANGOFF §9 D2b 退出条件 ③「授权失效恢复流程」）。
     * 界面应引导重选目录，并说明原有调整仍在。
     *
     * @param handle 失效的那份授权（有的话），用于文案里带上目录名。
     */
    data class Denied(val handle: WorkspaceHandle?) : WorkspaceState

    /** 其他失败（IO 错误等）。`message` 只用于日志/调试，界面文案另定。 */
    data class Failed(val message: String) : WorkspaceState
}

/**
 * 领域结果 → UI 状态。
 *
 * `handle` 是**发起这次扫描时**用的授权，成功时原样带回，让界面能显示目录名。
 * 失败/失效时也带上它 —— 「哪个目录失效了」对用户是有用信息。
 */
fun LoadResult.toWorkspaceState(handle: WorkspaceHandle): WorkspaceState = when (this) {
    is LoadResult.Success -> WorkspaceState.Ready(handle = handle, collections = collections)
    LoadResult.Denied -> WorkspaceState.Denied(handle)
    is LoadResult.Failed -> WorkspaceState.Failed(message)
}

/**
 * 当前应该展示的集合。
 *
 * **回退规则（有意为之）**：只有 [WorkspaceState.Ready] 用真实扫描结果，其余一律用 mock。
 * 理由：
 * 1. D1 的 491 个测试全部建立在 mock 数据上，默认无授权时必须照旧工作；
 * 2. 用户没选目录时界面不该是一片空白 —— 两条工作流仍可完整演示；
 * 3. `Ready` 但**空列表**时**不**回退 mock：用户选了一个空目录，就该看到「没有图片」，
 *    悄悄塞回演示数据会让人以为扫到了东西。
 */
fun WorkspaceState.collectionsOr(mock: List<ImageCollection>): List<ImageCollection> = when (this) {
    is WorkspaceState.Ready -> collections
    else -> mock
}

/** 界面是否处于「可以开始整理」的状态（有真实集合，或仍在用 mock 演示）。 */
val WorkspaceState.canOrganize: Boolean
    get() = when (this) {
        WorkspaceState.None -> true
        WorkspaceState.Loading -> false
        is WorkspaceState.Ready -> collections.isNotEmpty()
        is WorkspaceState.Denied -> true
        is WorkspaceState.Failed -> true
    }
