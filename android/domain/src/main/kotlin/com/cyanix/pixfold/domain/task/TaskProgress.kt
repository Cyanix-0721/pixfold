package com.cyanix.pixfold.domain.task

/**
 * 批量任务的进度快照（D2b 退出条件第 2 项「后台任务与进度」）。
 *
 * ## 归责边界
 *
 * **归这里**：进度怎么算、失败项算不算进度、百分比怎么夹、什么时候该上报。
 * **不归这里**：任务在哪个线程跑、进度条怎么画、后台任务用什么框架
 * （`WorkManager` / 前台服务）、怎么从 `Bundle` 里恢复 —— 那些是平台层的事。
 *
 * ## 为什么失败项也推进进度
 *
 * 「进度」回答的是「还有多少没处理完」，不是「成功了多少」。若失败项不推进，
 * 一批里失败 30% 时进度条会**卡在 70% 永远不动**，用户以为任务死了 ——
 * 而它其实早就跑完了。失败信息另有 [failed] 与其派生展示，两条线不能混。
 *
 * @param total 计划处理的总项数。`<= 0` 视为「无事可做」。
 * @param done 已成功处理的项数。
 * @param failed 已失败处理的项数（**真失败**：读写报错、打不开流）。
 * @param skipped 按计划跳过、无需处理的项数（输出已存在且用户选了跳过策略等）。
 *   **必须与 [failed] 分开**：跳过的卷不是失败。早先版本把它并进 `failed`,
 *   于是「跳过了 1 卷」在界面上会被读成「1 卷失败了」—— 一个纯粹误导的结论。
 *   但对**进度**而言两者都是「处理过了」，故都计入 [processed]。
 */
data class TaskProgress(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
) {
    /** 已处理 = 成功 + 失败 + 跳过（三者都是「处理过了」）。负数输入按 0 处理（平台层重复回调会产生脏值）。 */
    val processed: Int
        get() = (done.coerceAtLeast(0) + failed.coerceAtLeast(0) + skipped.coerceAtLeast(0))

    /** 还剩多少没处理。 */
    val remaining: Int get() = (total.coerceAtLeast(0) - processed).coerceAtLeast(0)

    /**
     * 完成比例，`0f..1f`。
     *
     * **空任务直接返回 `1f`**：0/0 若返回 0，空目录看起来像「还没开始」；
     * 且不能让调用点去做除零保护 —— 那是本类型的职责。
     */
    val fraction: Float
        get() {
            val t = total.coerceAtLeast(0)
            if (t == 0) return 1f
            return (processed.toFloat() / t).coerceIn(0f, 1f)
        }

    /** 整数百分比，`0..100`。避免界面各自做取整导致两处显示不一致。 */
    val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)

    /**
     * 把**当前卷内的页级进度**也算进来的百分比，`0..100`。
     *
     * ## 为什么需要它（用户 `User said (m17028)` 原话）
     *
     * 「内圈的百分比根据双层共同计算取值吧，不如外圈没转完进度一直 0」
     *
     * [percent] 只按卷算，而卷进度**只在整卷写完时**才 +1。打包实际是
     * 「一批里有几卷、一卷里有几千页」的两级结构：
     *
     *  · 4 卷的一批、每卷 1600 页 ⇒ 前 1599 页里 [percent] 一直是 0%，
     *    用户看到的是「进度条不动」，然后整卷写完直接跳到 25%；
     *  · 页级进度本来就在外环上画着 —— 环在涨、数字不动，两处读数互相矛盾。
     *
     * 故这里把两级合成一个读数：**（已处理卷数 + 当前卷页比例）/ 总卷数**。
     *
     * ## 夹取规则
     *
     *  · `pageTotal` 缺失或 `<= 0` ⇒ 退化成 [percent]（不知道页数就别编）；
     *  · 页比例本身夹到 `0f..1f` —— 平台层重复回调会产生 `pageDone > pageTotal` 的脏值；
     *  · 全部卷处理完时自然满 100：`base = t` ⇒ `base/t = 1f`，再叠加任何页比例都由
     *    末尾的 `coerceIn(0, 100)` 夹住。**这里刻意不再写「满了就提前 return」
     *    的分支** —— 变异验证实测为等价变异（删掉后没有任何测试变红 = 纯冗余判据），
     *    留一条杀不死的判据只会给假保障。
     *
     * @param pageDone 当前卷已写页数；`null` = 还不知道
     * @param pageTotal 当前卷总页数；`null` 或 `<= 0` = 不知道
     */
    fun percentWithPage(pageDone: Int?, pageTotal: Int?): Int {
        val t = total.coerceAtLeast(0)
        if (t == 0) return 100
        val base = processed.coerceIn(0, t)
        if (pageDone == null || pageTotal == null || pageTotal <= 0) {
            return (base.toFloat() / t * 100f).toInt().coerceIn(0, 100)
        }
        val pageFraction = (pageDone.toFloat() / pageTotal).coerceIn(0f, 1f)
        return ((base + pageFraction) / t * 100f).toInt().coerceIn(0, 100)
    }

    /**
     * 是否已全部处理完（成功与失败都算处理完）。
     *
     * **空任务（`total == 0`）自然为真**：`processed` 已保证非负，故 `processed >= 0`
     * 恒成立，无需再写 `total == 0 ||` 的分支。
     * 那条特判曾存在过，变异验证实测为**等价变异**（删掉后无任何测试变红 = 纯冗余判据），
     * 故删掉 —— 留一条杀不死的判据只会给假保障（同类先例见 D3 的 `undoRecordOf`）。
     */
    val isComplete: Boolean get() = processed >= total.coerceAtLeast(0)
}

/**
 * 进度上报的最小间隔（毫秒）。
 *
 * 千级任务每处理一张就上报一次 = 上千次状态更新，Compose 侧就是上千次重组。
 * 250ms 是「肉眼看着连续」与「别把主线程打满」的折中。
 */
const val PROGRESS_EMIT_INTERVAL_MILLIS: Long = 250L

/**
 * 进度上报的最小百分比步长：跨过一档才上报，避免细碎抖动。
 *
 * 注意它是**上报阈值**而不是显示精度 —— 界面显示的仍是精确值。
 */
const val PROGRESS_EMIT_STEP_PERCENT: Int = 5

/**
 * 这次进度变化要不要推给界面。
 *
 * ## 判定顺序（每条都有真实动因）
 *
 * 1. **首次上报** —— 不报的话界面一直停在「准备中」；
 * 2. **完成必须上报** —— 被节流掉就意味着进度条永远停在 99%；
 * 3. **失败数或跳过数变化必须上报** —— 否则用户不知道有东西失败了、或有卷被跳过了；
 * 4. **总数或已处理数倒退必须上报** —— 重扫/换工作区会把进度重置，节流不能吃掉它；
 * 5. **百分比跨档** —— 常规推进；
 * 6. **距上次上报超过 [PROGRESS_EMIT_INTERVAL_MILLIS]** —— 长时间无进展时给「我还活着」的信号，
 *    否则用户以为卡死。
 *
 * @param last 上一次**已上报**的进度；`null` 表示还没报过。
 * @param next 这次想上报的进度。
 * @param lastEmitAtMillis 上次上报的时刻（毫秒，调用方时钟）。
 * @param nowMillis 现在（毫秒）。
 */
fun shouldEmitProgress(
    last: TaskProgress?,
    next: TaskProgress,
    lastEmitAtMillis: Long,
    nowMillis: Long,
): Boolean {
    if (last == null) return true
    if (next.isComplete && !last.isComplete) return true
    if (next.failed != last.failed) return true
    if (next.skipped != last.skipped) return true
    if (next.total != last.total) return true
    if (next.processed < last.processed) return true

    val crossedStep = next.percent - last.percent >= PROGRESS_EMIT_STEP_PERCENT
    if (crossedStep) return true

    return nowMillis - lastEmitAtMillis >= PROGRESS_EMIT_INTERVAL_MILLIS
}

/**
 * 中断令牌。
 *
 * ## 为什么不是简单的 `var cancelled: Boolean`
 *
 * 令牌会被**两个线程**同时碰：界面线程 `cancel()`、后台线程 `shouldStop()`。
 * `Boolean` 字段在 JVM 上不保证跨线程可见性（没有 happens-before），
 * 后台循环可能永远读不到取消 —— 这是最典型的「点了取消没反应」。
 * `@Volatile` 提供可见性；幂等的 `cancel()` 允许重复调用。
 */
class CancellationToken {
    @Volatile
    private var cancelled: Boolean = false

    /** 是否已请求中断。 */
    val isCancelled: Boolean get() = cancelled

    /** 请求中断。重复调用无害。 */
    fun cancel() {
        cancelled = true
    }

    /**
     * 开始处理第 [beforeStartingItem] 项**之前**该不该停。
     *
     * **只在一项开始前检查，不在项内检查**：半途硬停会在目标目录留下半个文件，
     * 比多花一秒钟糟得多。这是与平台层之间最重要的契约。
     */
    fun shouldStop(beforeStartingItem: Int): Boolean = cancelled
}

/** 一次批量任务的最终结果。 */
sealed interface TaskOutcome {
    /** 全部处理完（成功与否不影响）。[progress] 里能读出成功与失败各多少。 */
    data class Completed(val progress: TaskProgress) : TaskOutcome

    /** 被用户中断。[progress] 是停下时的快照。 */
    data class Cancelled(
        val progress: TaskProgress,
        val remaining: Int = progress.remaining,
    ) : TaskOutcome

    /** 仍在进行。[remaining] 是剩余项数。 */
    data class Running(
        val progress: TaskProgress,
        val remaining: Int = progress.remaining,
    ) : TaskOutcome
}

/**
 * 由「进度快照 + 中断令牌」判定当前结果。
 *
 * ## 判定顺序不可调换
 *
 * **先判完成，再判取消。** 用户点取消的瞬间任务恰好跑完是真实会发生的，
 * 这时报「已取消」是骗人的 —— 实际什么都没少做。完成永远优先。
 *
 * 空任务（`total == 0`）即使被取消也算完成：用户点了取消但其实无事可做，
 * 报「已取消」会让人以为出了岔子。
 */
fun taskOutcomeOf(progress: TaskProgress, token: CancellationToken): TaskOutcome = when {
    progress.isComplete -> TaskOutcome.Completed(progress)
    token.isCancelled -> TaskOutcome.Cancelled(progress)
    else -> TaskOutcome.Running(progress)
}
