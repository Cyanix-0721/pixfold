package com.cyanix.pixfold.data.task

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.cyanix.pixfold.MainActivity
import com.cyanix.pixfold.R
import com.cyanix.pixfold.data.saf.SafPackExecutor
import com.cyanix.pixfold.domain.pack.PackBlocker
import com.cyanix.pixfold.domain.plan.ExecutionReport
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.PackTaskPhase
import com.cyanix.pixfold.domain.task.PackTaskSnapshot
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.domain.task.shouldEmitProgress
import com.cyanix.pixfold.ui.library.PackUiOutcome
import com.cyanix.pixfold.ui.library.humanBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 打包任务的前台服务（D2b「后台任务与进度 · 切后台恢复」）。
 *
 * ## 为什么必须是前台服务
 *
 * 打包一本 30 页的卷要读几十 MB 并写 ZIP，几十卷就要几分钟。这段时间里：
 * - 用户切到别的应用 ⇒ Activity 被 `onStop`，`rememberCoroutineScope()` 随之取消；
 * - 系统内存紧张 ⇒ 后台进程随时被回收。
 *
 * 两种情况下「执行方是 Activity 的协程」都必然失败（任务半途死掉，磁盘留半成品）。
 * 前台服务的语义恰好是「这段工作用户可见、系统不该杀」—— 它把任务的**生命周期
 * 从 Activity 里解耦**：Activity 死不死，任务都跑到自己 `stopSelf()`。
 *
 * ## 与总线的关系
 *
 * Service 是**唯一执行方**，也是唯一调 `PackTaskBus.begin/publish/settle` 的地方。
 * Activity 侧只读 `PackTaskBus.state`，把「开始打包」翻译成
 * `PackTaskService.enqueue(...)`。这样：
 * - UI 重建（旋转 / 返回再进）不需要重连，`collectAsState()` 直接拿到当前进度；
 * - 不需要处理 `bindService` 的 `ServiceConnection` 时序。
 *
 * ## 为什么 `START_NOT_STICKY`
 *
 * 系统重建服务时（`START_STICKY` 的语义）会调 `onStartCommand` 但**不会重放**
 * 那次请求 —— 请求只存在于进程内存里，进程死时一起没了，此时服务无事可做。
 * 更关键的是**自动续跑是危险的**：中断时刻可能死在 `openOutputStream` 刚建好文件时，
 * 续跑会产出一个连 EOCD 都没有的坏 ZIP。故这里返回 `NOT_STICKY`，把
 * 「上次中断了」交给快照 + UI 提示去处理（`recoverPackTask`）。
 *
 * ## 通知形态
 *
 * 进度通知一律用 `NotificationCompat.ProgressStyle`（Android 16 的 Live Update
 * 形态）。`minSdk` 已提到 36，故**没有低版本降级分支** —— 原先那条
 * `SDK_INT >= 36` 判断在 minSdk 36 下恒真，lint `ObsoleteSdkInt` 会硬失败。
 *
 * 注意区分两件事：
 * - **ProgressStyle 本身**是 API 36 的公开 API，进度条在 36.0 上正常渲染；
 * - **升格成状态栏胶囊（Live Update / OPPO 流体云）**在 36.0 上**也能生效**，
 *   但多一道用户开关。
 *
 * ## 升格的真正前置条件（真机实测，2026-10-19）
 *
 * 一度以为「API 36.0 上 `canPostPromotedNotifications()` 恒为 false，要等 36.1」，
 * 并把 StackOverflow 上几位 Pixel 用户「36.0 上拿不到胶囊」的报告当作平台限制。
 * **这个结论是错的**，真机反证如下：
 *
 * 1. 本机 `ro.build.version.sdk_full = 36.0`（不是 36.1），`ColorOS V16.0.0`；
 * 2. 系统设置里有「流体云 - 显示实时活动」总开关，**默认关闭**；
 * 3. 打开它之后再跑打包 ⇒ `dumpsys notification` 里该通知出现
 *    `flags=...|PROMOTED_ONGOING|...`，`logcat` 见
 *    `NotificationLiveAlertProvider-->post sbn: [...PROMOTED_ONGOING...]`，
 *    截图抓到了状态栏胶囊实体（`ic_pack_progress` 图标 + `60%` 文本）。
 *
 * ⇒ 所以「拿不到升格」是**用户级开关没开**，不是 API level 不够。Pixel 那几位
 * 用户大概率也是同一个开关（Android 16 设置里叫 Live Updates）。
 *
 * 这条经验对代码的影响：**必须无条件写 `setRequestPromotedOngoing(true)`**。
 * 升格是「位」而不是「能力查询」——用户随时可能在设置里打开开关，下一次
 * `notify()` 就该自动升格，不需要应用重启或改代码。所以**不要**把
 * `canPostPromotedNotifications()` 当门禁来跳过设置请求位。
 */
class PackTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private lateinit var store: PackTaskStore
    private lateinit var notifications: NotificationManager

    /** `startForeground` 只能成功调一次（重复调用抛 `IllegalStateException`）。 */
    private val foregroundStarted = AtomicBoolean(false)

    /**
     * 当前任务的快照。
     *
     * **必须是字段而不是 `runTask` 的局部变量**：[onTimeout] 是系统在别的时机调进来的
     * 回调，它要能读到「任务跑到哪儿了」才能写出正确的收尾快照。做成局部变量时
     * `onTimeout` 只能写一条空快照，恢复提示会丢失进度。
     *
     * 只在主线程读写（`CoroutineScope` 用 `Dispatchers.Main.immediate`），故不加锁。
     *
     * 声明为 `internal`（而非 private）**只为了可测**：[onTimeout] 的整条路径在
     * Robolectric 里没有别的入口能种进「任务跑到一半」的状态。
     */
    internal var snapshot: PackTaskSnapshot? = null

    override fun onCreate() {
        super.onCreate()
        store = PackTaskStore(this)
        notifications = getSystemService(NotificationManager::class.java)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须**无条件**先 startForeground：Android 要求 startForegroundService 之后
        // 5 秒内调用它，否则抛 `ForegroundServiceDidNotStartInTimeException`。
        // 所以哪怕这里没有请求，也要先变前台服务，再 stopSelf。
        startForegroundIfNeeded(buildProgressNotification(0f, getString(R.string.pack_notify_preparing)))

        if (job != null) {
            Log.w(TAG, "已有打包任务在执行，忽略新的启动请求")
            return START_NOT_STICKY
        }

        val request = PackTaskBus.takeRequest()
        if (request == null) {
            Log.w(TAG, "没有待执行的请求，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }

        runTask(request)
        return START_NOT_STICKY
    }

    private fun runTask(request: PackTaskRequest) {
        val startedAt = System.currentTimeMillis()
        val taskId = "pack-$startedAt"
        // 输出目标列表**执行前**就要落盘：它决定恢复提示能告诉用户「上次要写这几个
        // 文件」。执行完再写就晚了 —— 那正是进程可能被杀的时刻。
        var snapshot = PackTaskSnapshot(
            taskId = taskId,
            phase = PackTaskPhase.Running,
            startedAtMillis = startedAt,
            updatedAtMillis = startedAt,
            progress = TaskProgress(total = request.plans.size),
            outputTargets = request.plans.map { it.outputPath },
        )
        this.snapshot = snapshot
        store.save(snapshot)

        val executor = SafPackExecutor(this, request.treeUri.toUri(), request.rootDisplayName)
        val token: CancellationToken = PackTaskBus.begin()

        var lastEmitted: TaskProgress? = null
        var lastEmitAt = 0L

        job = scope.launch {
            try {
                val outcome = executor.execute(
                    plans = request.plans,
                    token = token,
                    onProgress = { progress ->
                        // 领域层每卷回调一次。这里按 `shouldEmitProgress` 的三条判据
                        // （跨 5% / 超 250ms / 有失败或跳过）节流：几十卷时每卷一次
                        // 重组 + 一次通知更新是可观开销。
                        val now = System.currentTimeMillis()
                        if (shouldEmitProgress(lastEmitted, progress, lastEmitAt, now)) {
                            lastEmitted = progress
                            lastEmitAt = now
                            snapshot = snapshot.withProgress(progress, nowMillis = now)
                            // 字段同步更新：`onTimeout` 读的是字段，不是这个局部变量。
                            this@PackTaskService.snapshot = snapshot
                            store.save(snapshot)
                            PackTaskBus.publish(progress, cancelling = token.isCancelled)
                            updateNotification(progress.fraction, countsText(progress))
                        }
                    },
                )
                // 取消标记从总线的令牌读，而不是猜「报告条数少了」——
                // `packRunAsExecutionReport` 会把未处理的卷补成「尚未开始」，
                // 故 `entries.size` 恒等于 `total`，那个判据永远为假。
                finishWith(outcome, snapshot, request, cancelled = token.isCancelled)
            } catch (e: Exception) {
                // 兜底：`SafPackExecutor` 内部已 catch，但协程里漏出去的异常会让任务
                // 永远停在「正在打包」，用户没有任何出路。
                Log.e(TAG, "打包任务异常终止", e)
                val detail = e.message ?: e::class.simpleName ?: getString(R.string.pack_error_unknown)
                store.save(snapshot.finished(PackTaskPhase.Aborted, System.currentTimeMillis(), detail))
                PackTaskBus.settle(PackTaskSettled.Error(detail))
                notifyFinal(R.string.pack_notify_failed, detail)
            } finally {
                job = null
                stopSelf()
            }
        }
    }

    private fun finishWith(
        outcome: PackUiOutcome,
        snapshot: PackTaskSnapshot,
        request: PackTaskRequest,
        cancelled: Boolean,
    ) {
        val now = System.currentTimeMillis()
        when (outcome) {
            is PackUiOutcome.Done -> {
                val report = outcome.report
                if (cancelled) {
                    store.save(snapshot.finished(PackTaskPhase.Cancelled, now))
                    PackTaskBus.settle(PackTaskSettled.Cancelled(report))
                    notifyFinal(R.string.pack_notify_cancelled, summaryText(report, request))
                } else {
                    store.save(snapshot.finished(PackTaskPhase.Finished, now))
                    PackTaskBus.settle(PackTaskSettled.Done(report))
                    notifyFinal(R.string.pack_notify_done, summaryText(report, request))
                }
            }
            is PackUiOutcome.Rejected -> {
                store.save(snapshot.finished(PackTaskPhase.Rejected, now))
                val message = rejectionMessageOf(outcome)
                PackTaskBus.settle(PackTaskSettled.Rejected(message))
                notifyFinal(R.string.pack_notify_rejected, message)
            }
            is PackUiOutcome.Error -> {
                store.save(snapshot.finished(PackTaskPhase.Aborted, now, outcome.message))
                PackTaskBus.settle(PackTaskSettled.Error(outcome.message))
                notifyFinal(R.string.pack_notify_failed, outcome.message)
            }
        }
    }

    /**
     * 预检拒绝的人话。
     *
     * 不直接复用 `LibrarySteps.rejectionText`：那个函数额外带「请回步骤 2 改名字」
     * 这类引导，通知里塞引导没用（用户点通知才看得到界面）。这里只给结论。
     */
    private fun rejectionMessageOf(outcome: PackUiOutcome.Rejected): String {
        val blocker = outcome.preflight.blockers.firstOrNull()
            ?: return getString(R.string.pack_error_unknown)
        return when (blocker) {
            is PackBlocker.SpaceInsufficient -> getString(
                R.string.pack_error_space,
                humanBytes(blocker.shortfallBytes),
                humanBytes(blocker.space.availableBytes),
            )
            is PackBlocker.OutputNameCollision -> resources.getQuantityString(
                R.plurals.pack_error_collision,
                blocker.collidingCount,
                blocker.collidingCount,
                blocker.outputPath,
            )
        }
    }

    private fun summaryText(report: ExecutionReport, request: PackTaskRequest): String = getString(
        R.string.pack_notify_summary,
        request.workspaceName,
        report.succeededCount,
        report.failedCount,
    )

    // ---- 通知 ----

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.pack_notify_channel),
            // **必须是 DEFAULT，不能是 LOW。**
            //
            // 真机实测（2026-10-19，ColorOS / Android 16.0）：`IMPORTANCE_LOW`
            // 建出来的渠道在系统侧是 `mUnimportant=true`，而本机上所有渠道
            // `mUnimportant=false` 的应用才具备被升格成实时活动的前提。官方
            // 文档只写「不得是 IMPORTANCE_MIN」，但 LOW 在这台机器上同样被归入
            // unimportant —— 升格条件第 9 条比文档字面更严，按实测取 DEFAULT。
            //
            // 渠道 importance 一旦被用户改过就无法用代码改回（系统忽略入参），
            // 所以这里**同时换渠道 id**（`pack_task` → `pack_task_live`）：
            // 换 id 等于新建一个干净渠道，旧渠道顺手删掉，避免设置页里留一个
            // 永远不会再有通知的空条目。
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.pack_notify_channel_desc)
            // 进度通知不该响铃/震动：用户可能正在别的应用里做事。
            setSound(null, null)
            enableVibration(false)
        }
        notifications.createNotificationChannel(channel)
        notifications.deleteNotificationChannel(LEGACY_CHANNEL_ID)
    }

    private fun startForegroundIfNeeded(notification: Notification) {
        if (!foregroundStarted.compareAndSet(false, true)) return
        // `dataSync` 类型：明确告诉系统「这是一次由用户发起的数据写入」。
        // Android 14+ 必须声明类型，否则 startForeground 抛
        // `MissingForegroundServiceTypeException`。
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun updateNotification(fraction: Float, text: String) {
        if (!foregroundStarted.get()) {
            startForegroundIfNeeded(buildProgressNotification(fraction, text))
            return
        }
        notifications.notify(NOTIFICATION_ID, buildProgressNotification(fraction, text))
    }

    /**
     * [updateNotification] 的测试入口。
     *
     * 声明为 `internal`（而非 private）**只为了可测**：`OngoingProgressNotification`
     * 本身的断言只能证明「抽象是对的」，证明不了「服务真的改用了它」。少了这一条，
     * 把 `buildProgressNotification` 改回自己拼 `Builder` 仍然是全绿。
     */
    internal fun updateNotificationForTest(fraction: Float, text: String) =
        updateNotification(fraction, text)

    private fun buildProgressNotification(fraction: Float, text: String): Notification =
        OngoingProgressNotification.build(
            context = this,
            channelId = CHANNEL_ID,
            smallIcon = R.drawable.ic_pack_progress,
            title = getString(R.string.pack_notify_title),
            text = text,
            fraction = fraction,
            // 胶囊区最大宽度只有 96dp，放不下「第 2 / 5 卷 · 系列名」这种长文本。
            // 给纯百分比数字：长度可控、跨语言无歧义。
            shortText = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
            contentIntent = openAppIntent(),
        )

    /**
     * 发「结束通知」。
     *
     * 声明为 `internal`（而非 private）**只为了可测**：这条路径的唯一正确性判据
     * 「用哪个 id 发」在纯 JVM 单测里看不见，而真机才能看见的缺陷（2026-10-19
     * 完成通知被停服连带清除）必须有个离线可跑的守卫。
     */
    internal fun notifyFinal(titleRes: Int, body: String) {
        // 用**另一个** id，不能复用 [NOTIFICATION_ID]。
        //
        // 2026-10-19 真机实测（PJX110 / Android 16 / ColorOS）：收尾走
        // `notifyFinal(...)` 之后紧接 `stopSelf()`，服务停掉时系统把前台服务
        // 绑定的那条通知一并 cancel，于是「打包完成」提示**用户根本看不到**。
        // logcat 逐字：
        //
        //   16:11:16 onNotificationPosted Id:4101 IsOngoing:true
        //   16:11:19 Stop FGS timeout: ServiceRecord{...PackTaskService}
        //   16:11:19 onNotificationRemoved ... reason:REASON_APP_CANCEL
        //
        // 前台服务那条是 ONGOING，用户无法手动划掉；结束通知必须独立成一条
        // 可划掉的通知，否则等于没发。
        notifications.notify(
            FINAL_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_pack_progress)
                .setContentTitle(getString(titleRes))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(openAppIntent())
                .build(),
        )
        // 顺带把还挂着的前台进度通知撤掉，避免屏幕上同时留两条（用户从后台
        // 直接点通知回来时，那条 ONGOING 的进度条会误导成「还在跑」）。
        notifications.cancel(NOTIFICATION_ID)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun countsText(progress: TaskProgress): String =
        getString(R.string.pack_notify_counts, progress.processed, progress.total)

    override fun onDestroy() {
        scope.cancel()
        PackTaskBus.onServiceGone()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * `dataSync` 类型前台服务的超时回调。
     *
     * ## 为什么必须覆写
     *
     * Android 15（API 35）起，`FOREGROUND_SERVICE_TYPE_DATA_SYNC` 受**时长上限**
     * 约束：前台累计 6 小时、后台（经 `dataSync` 从后台启动）累计 24 小时。超限时
     * 系统不再让服务继续跑 —— 但**不会**替你收尾：`stopSelf` 不是你调的，
     * `finally` 块也不一定走到（进程可能被直接回收），结果是
     *
     * - 进度通知停在某个百分比，永远不动；
     * - `PackTaskStore` 里留着 `Running` 快照，下次冷启会报「上次打包未完成」；
     * - 用户既等不到完成通知，也没有任何「失败了」的反馈。
     *
     * 覆写本回调后，系统在回收前先叫这里一次，于是能把「为什么停」如实告诉用户。
     *
     * ## 两个重载都要覆写
     *
     * 框架有 `onTimeout(int)`（API 35）和 `onTimeout(int, int)`（API 36，多一个
     * `fgsType` 参数，用于同时声明多种类型时区分）。`minSdk = 36` ⇒ 两个都存在，
     * 都指向同一个处理函数，避免不同系统版本走不同分支。
     *
     * 真机实测（2026-10-19）：5 卷 × 24 页 ≈ 190 MB 的任务约 3 秒完成，离限额极远。
     * 这条路径是为「上百卷」这种规模准备的 —— 那时任务可能跑几十分钟，用户也可能
     * 把应用放在后台很久。
     */
    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    private fun handleTimeout() {
        Log.w(TAG, "前台服务超时，系统即将回收；主动收尾以免任务状态永远停在 Running")
        val now = System.currentTimeMillis()
        val detail = getString(R.string.pack_error_timeout)
        // 取消令牌要真的置位，否则已经在跑的那层 `runPackPlans` 感知不到，
        // 会继续往磁盘写直到进程被杀 —— 半成品 CBZ 就是这么产生的。
        PackTaskBus.requestCancel()
        runCatching {
            // 快照可能为 null（服务起来了但请求还没进来就被判定超时）。
            // 那时没有任务可收尾，只需把错误告诉 UI。
            snapshot?.let { store.save(it.finished(PackTaskPhase.Aborted, now, detail)) }
            PackTaskBus.settle(PackTaskSettled.Error(detail))
            notifyFinal(R.string.pack_notify_failed, detail)
        }
        job?.cancel()
        stopSelf()
    }

    companion object {
        private const val TAG = "PackTaskService"

        /**
         * 进度通知渠道。
         *
         * `_live` 后缀是 2026-10-19 从 `pack_task` 改过来的：渠道 importance
         * 只能降不能升（用户改过之后系统忽略代码入参），而 LOW → DEFAULT 的
         * 改动对已有渠道无效，只能换 id 重建。旧 id 见 [LEGACY_CHANNEL_ID]。
         */
        const val CHANNEL_ID = "pack_task_live"

        /** 上一版渠道 id，仅用于在 [createChannel] 里删掉它。 */
        private const val LEGACY_CHANNEL_ID = "pack_task"

        const val NOTIFICATION_ID = 4101

        /**
         * 结束通知的 id，**必须与 [NOTIFICATION_ID] 不同**。
         *
         * 真机实测（2026-10-19）：两者共用 4101 时，`notifyFinal(...)` 之后
         * 紧跟的 `stopSelf()` 会让系统把前台服务那条通知清掉 —— 连同刚发的
         * 完成提示一起消失（logcat `reason:REASON_APP_CANCEL`）。分开之后，
         * 「打包完成」是一条独立可划掉的通知，用户从后台也能看到。
         */
        const val FINAL_NOTIFICATION_ID = 4102

        /**
         * 请求执行一次打包。
         *
         * 用 `ContextCompat.startForegroundService` 而非 `startService`：后者在
         * Android 8+ 上对后台应用直接抛 `IllegalStateException`。
         *
         * @return `false` = 已有任务在跑（并发写同一批输出路径会互相覆盖），未入队。
         */
        fun enqueue(context: Context, request: PackTaskRequest): Boolean {
            if (!PackTaskBus.requestStart(request)) return false
            ContextCompat.startForegroundService(
                context,
                Intent(context, PackTaskService::class.java),
            )
            return true
        }
    }
}
