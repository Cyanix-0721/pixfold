package com.cyanix.pixfold.ui.workspace

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.source.FailureCause
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
     * @param autoRetried 在报失效前**已经自动重试过几次**。这个数字不是内部细节：
     *   一次没重试就报失效（`0`）意味着平台层判定为确定性失效；重试过仍失败意味着
     *   我们尽力了 —— 文案可以据此说「已重试 N 次」，用户不会觉得应用轻易放弃了。
     *
     *   ⚠️ **为什么不区分「撤销」与「暂时不可用」**：在 Android 上两者的表现**都是**
     *   `SecurityException` —— 用户真的撤销了授权，与进程重启后 `takePersistableUriPermission`
     *   尚未就绪，平台层无从分辨。故统一策略是「先自动重试，仍失败才打扰用户」，
     *   这个计数就是该策略的产物。
     */
    data class Denied(
        val handle: WorkspaceHandle?,
        val autoRetried: Int = 0,
    ) : WorkspaceState

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
 * 1. D1 的既有测试全部建立在 mock 数据上，默认无授权时必须照旧工作；
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

/**
 * 工作流页面里要不要挂那条工作区条（2026-10-24 用户裁决 乙/甲/乙 的第 1 条）。
 *
 * ## 规则：只在**出问题或正忙**时显示
 *
 * | 状态 | 显示 | 理由 |
 * | --- | --- | --- |
 * | [None] | ❌ | 选目录的入口已经搬到首页；工作流里再放一条「当前显示演示数据 + 选择文件夹」是同一个功能摆两处 |
 * | [Loading] | ✅ | 扫描可能几秒（千级文件更久），不说明的话用户只会觉得点不动 |
 * | [Ready] | ❌ | 一切正常时这条是纯噪声 —— 首页那一条已经承担了「我在哪个目录」 |
 * | [Denied] | ✅ | **必须**。见下面那条真正的理由 |
 * | [Failed] | ✅ | 同上 |
 *
 * ## 为什么 Denied / Failed **不能**跟着一起删
 *
 * [collectionsOr] 在非 `Ready` 状态下一律回退 mock。也就是说授权失效后，
 * 界面上**照样有一整列看起来正常的图片**，用户会以为自己在整理真实文件 ——
 * 排序、改名一路做下去，最后因为读不到文件而全部失败，或者更糟：做完了才发现
 * 什么都没写进自己的文件夹。这条条是那种处境下**唯一**的重选入口。
 *
 * 把「正常时收起」和「异常时常驻」拆成两个状态判断，而不是在调用点各写一遍 `when`,
 * 是因为两处调用点（工作流 A / B）必须**同时**改 —— 只改一处的表现是「换个工作流进去
 * 就没有重选入口了」，而这种缺漏不会有任何测试报错，只要没人正好在那条路径上点。
 */
val WorkspaceState.showsWorkspaceBar: Boolean
    get() = when (this) {
        WorkspaceState.None -> false
        WorkspaceState.Loading -> true
        is WorkspaceState.Ready -> false
        is WorkspaceState.Denied -> true
        is WorkspaceState.Failed -> true
    }

/**
 * 当前状态里能取到的授权（[Ready] / [Denied] 才有）。
 *
 * **为什么需要它**：刷新（`WorkspaceController.refresh`）与「是否换了工作区」的
 * 判断都只需要知道「现在是哪个目录」，却各自手写一遍 `when` 分支的话，将来新增
 * 一个状态变体很容易漏改其中一处 —— 漏在刷新侧的表现是「某个状态下刷新静默失效」，
 * 没有任何报错。收成一个扩展函数，编译器会在新增变体时直接报穷尽性错误。
 */
fun WorkspaceState.handleOrNull(): WorkspaceHandle? = when (this) {
    is WorkspaceState.Ready -> handle
    is WorkspaceState.Denied -> handle
    WorkspaceState.None, WorkspaceState.Loading, is WorkspaceState.Failed -> null
}

// ==================== 授权失效恢复（D2b 退出条件 ③） ====================

/**
 * 自动重试上限。
 *
 * 为什么是 1：冷启动「持久授权尚未就绪」是最高频的失效场景，一次重试内通常就恢复；
 * 而真的撤销了授权时重试多少次都没用。给多了只会让用户多等。
 */
const val AUTO_RETRY_LIMIT: Int = 1

/**
 * `LoadResult` → 失效成因。
 *
 * ⚠️ **为什么 `Denied` 归为 [FailureCause.Transient] 而不是 `Revoked`**：
 * 在 Android 上「用户真的撤销了授权」与「进程重启后 `takePersistableUriPermission`
 * 尚未就绪」的平台表现**完全一样** —— 都是 `SecurityException`，平台层无从分辨。
 * 故先按「临时」处理（先自动重试），重试仍失败才认定为确定性失效交给人。
 *
 * `Failed` 归为 `Io`：磁盘/存储卡层面的问题，同样先给一次重试。
 */
val LoadResult.failureCause: FailureCause?
    get() = when (this) {
        is LoadResult.Success -> null
        LoadResult.Denied -> FailureCause.Transient
        is LoadResult.Failed -> FailureCause.Io
    }

/**
 * 一次扫描失败后是否应当再自动重试一次。
 *
 * @param cause 失效成因。
 * @param retriedTimes 本次会话里已经为该工作区自动重试过几次。
 */
fun shouldAutoRetry(cause: FailureCause, retriedTimes: Int): Boolean = when (cause) {
    // 确定性失效：重试没有意义，直接交给人。
    FailureCause.Revoked -> false
    // 临时性 / IO：给有限次机会。
    FailureCause.Transient, FailureCause.Io -> retriedTimes < AUTO_RETRY_LIMIT
}
