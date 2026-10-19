package com.cyanix.pixfold.data.task

import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 打包任务的进程内状态（D2b「切后台恢复」）。
 *
 * 与落盘快照（[PackTaskSnapshot]）的分工：
 * - **快照**回答「上次跑到哪」（跨进程、跨重启，只留最小事实）；
 * - **本状态**回答「现在在跑什么、进度多少、结果是什么」（只在进程内，含完整报告）。
 *
 * 两者都需要：UI 转后台再回前台时，进程还活着，应当立刻看到真实进度（本状态）；
 * 进程被杀掉重启后，本状态一无所有，只能靠快照告诉用户「上次中断了」（`recoverPackTask`）。
 *
 * ## 为什么是 object 而不是靠 Service Binding
 * 绑定（`bindService`）能拿到强类型引用，但代价是所有观察方都要处理
 * `ServiceConnection` 的回调时序与解绑生命周期，而前台服务本身还有一个 `startForeground`
 * 的启动窗口。用进程内广播对象，UI 只需要 `collectAsState()`，Service 只需要往里写；
 * 谁先起来都能工作（UI 先起来看到 `Idle`，Service 先起来 UI 起来时看到 `Running`）。
 *
 * 这与「授权由 Activity 拿、执行由 Service 做」是同一个拆分：**Activity 的生命周期
 * 不该决定任务能活多久**。
 */
object PackTaskBus {

    private val mutableState = MutableStateFlow<PackTaskState>(PackTaskState.Idle)

    /** UI 观察这个。首帧是 [PackTaskState.Idle]。 */
    val state: StateFlow<PackTaskState> = mutableState.asStateFlow()

    /** 等待被 Service 取走的请求。只在 [requestStart] 与 [takeRequest] 之间非空。 */
    private var pendingRequest: PackTaskRequest? = null

    /** 当前任务的取消令牌。Service 开始执行时创建。 */
    private var currentToken: CancellationToken? = null

    /**
     * 请求启动一次打包任务。
     *
     * **同时立刻建立取消令牌并进入 `Running`**，而不是等 Service 里的 [begin]。
     *
     * 原因是这里有一段真实存在的窄窗口：`startForegroundService` 之后，系统可能
     * 先去做别的事，Service 的 `onStartCommand` 推迟几十毫秒。这期间用户看到通知
     * 说「正在准备」、进度条也出现了，点「取消」却什么都不发生 —— 因为
     * `currentToken` 还是 null。把令牌与状态前移到请求受理这一刻，竞态就闭合了。
     *
     * @return 是否接受。已有任务在跑时返回 `false`（并发写同一批输出路径会互相覆盖）。
     */
    fun requestStart(request: PackTaskRequest): Boolean {
        if (mutableState.value is PackTaskState.Running) return false
        pendingRequest = request
        currentToken = CancellationToken()
        mutableState.value = PackTaskState.Running(
            progress = TaskProgress(total = request.plans.size),
            cancelling = false,
        )
        return true
    }

    /** Service 侧取请求。取走后清空，保证一次请求只被启动一次。 */
    fun takeRequest(): PackTaskRequest? {
        val request = pendingRequest
        pendingRequest = null
        return request
    }

    /**
     * Service 开始执行，返回本次任务的取消令牌。
     *
     * 令牌在 [requestStart] 时就已经建好（见那里的注释）；这里只兜底「Service 被系统
     * 重建、内存请求已丢」的路径 —— 那时不该崩，也不该伪造一个能取消的任务。
     */
    fun begin(): CancellationToken = currentToken ?: CancellationToken().also { currentToken = it }

    /** 当前令牌（Service 内部取用；任务未开始时为 null）。 */
    fun currentToken(): CancellationToken? = currentToken

    /** 服务已销毁（`onDestroy`）。把运行中状态标成中断，避免 UI 永远显示「正在打包」。 */
    fun onServiceGone() {
        currentToken = null
        val running = mutableState.value as? PackTaskState.Running ?: return
        // 任务在跑但服务没了 ⇒ 进程级中断：磁盘上可能留半成品，交给恢复提示处理。
        mutableState.value = PackTaskState.Settled(
            PackTaskSettled.Error(
                "打包任务被系统中断（进度 ${running.progress.processed}/${running.progress.total}）",
            ),
        )
    }

    /** 进度推进（由 Service 节流后调用）。 */
    fun publish(progress: TaskProgress, cancelling: Boolean) {
        mutableState.value = PackTaskState.Running(progress = progress, cancelling = cancelling)
    }

    /** 用户点了取消。[CancellationToken] 在卷间生效，故界面先显示「正在取消」。 */
    fun requestCancel() {
        currentToken?.cancel()
        val running = mutableState.value as? PackTaskState.Running ?: return
        mutableState.value = running.copy(cancelling = true)
    }

    /** 任务收尾。`outcome` 里带着界面要显示的报告或拒绝理由。 */
    fun settle(outcome: PackTaskSettled) {
        currentToken = null
        mutableState.value = PackTaskState.Settled(outcome)
    }

    /**
     * 消费掉上一次的结果（把 `Settled` 拨回 `Idle`）。
     *
     * 由界面在**已经展示过**报告后调用。不消费的后果：重新进入打包页时
     * 仍然看到上一次的报告，用户会以为刚才那次打包又跑了一遍。
     */
    fun consumeResult() {
        if (mutableState.value is PackTaskState.Settled) {
            mutableState.value = PackTaskState.Idle
        }
    }

    /** 进程内是否有任务正在跑（`WorkflowBSteps` 用它决定底栏形态）。 */
    fun isRunning(): Boolean = mutableState.value is PackTaskState.Running

    /** 仅供测试：把总线拨回初始态。生产代码不要调（会与真实 Service 状态失配）。 */
    internal fun resetForTest() {
        pendingRequest = null
        currentToken = null
        mutableState.value = PackTaskState.Idle
    }
}

/**
 * 打包任务的生命周期状态（进程内）。
 *
 * 刻意只有三态：`Settled` 里包着三种结局（成功报告 / 预检拒绝 / 执行器故障）。
 * 若把三种结局拆成三个顶层状态，每个 `when` 都要处理「Idle / Running / Done /
 * Rejected / Error」五种，而 UI 的三处判断里有两处对后三者处理完全相同。
 */
sealed interface PackTaskState {

    /** 没有任务（含「上一次结果已被消费」）。 */
    data object Idle : PackTaskState

    /**
     * 正在执行。
     *
     * @param cancelling 用户已点取消，但当前卷还没做完（中断只在**卷间**生效）。
     *   界面据此禁用按钮并显示「正在取消…」，而不是让按钮看起来还有效。
     */
    data class Running(val progress: TaskProgress, val cancelling: Boolean) : PackTaskState

    /** 已收尾。结果要被界面取走（并在展示后 [PackTaskBus.consumeResult]）。 */
    data class Settled(val outcome: PackTaskSettled) : PackTaskState
}

/**
 * 打包任务的结局（平台层内部类型）。
 *
 * 与 UI 层 `PackUiOutcome` 的区别：这里多一个 [Cancelled]。
 * 取消后 `executePackPlans` 仍然会返回一份**不完整**的报告（已完成的卷都在里面），
 * 而 `PackUiOutcome.Done` 无法表达「这份报告是被用户中断的」—— 界面需要把
 * 「打包完成」与「打包被取消」说成两句话。领域层的 `PackRunReport.isComplete`
 * 已经区分了这两种，但它在那层就被转成了 `ExecutionReport`（没有 `isComplete`），
 * 故在总线边界补一个分支把信息留住。
 */
sealed interface PackTaskSettled {

    /** 跑完了（可能有失败的卷）。 */
    data class Done(val report: com.cyanix.pixfold.domain.plan.ExecutionReport) : PackTaskSettled

    /** 用户取消。`report` 是中断时的部分结果（已完成的卷都在里面）。 */
    data class Cancelled(val report: com.cyanix.pixfold.domain.plan.ExecutionReport) : PackTaskSettled

    /** 预检拒绝，一个字节都没写。 */
    data class Rejected(val message: String) : PackTaskSettled

    /** 执行器自身故障。 */
    data class Error(val message: String) : PackTaskSettled
}

/**
 * 一次打包请求的完整输入。
 *
 * 全部字段都是**可跨进程传递的朴素类型**（字符串 + 领域对象），因为 Service
 * 拿到的可能是「进程刚被系统重建」之后的世界 —— 那时 Activity 的内存引用都没了。
 */
data class PackTaskRequest(
    /** 工作区根的 tree URI 字符串（`WorkspaceHandle.treeUri`）。 */
    val treeUri: String,

    /** 工作区根的显示名，用于把 `outputDir` 反解成相对路径。 */
    val rootDisplayName: String,

    /** 待执行的卷计划（含每卷的页列表与 `uri`）。 */
    val plans: List<VolumePackPlan>,

    /** 通知与恢复提示里给用户看的名字（工作区显示名）。 */
    val workspaceName: String,
)
