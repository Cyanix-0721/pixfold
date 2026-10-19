package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.task.CancellationToken
import com.cyanix.pixfold.domain.task.TaskProgress
import java.io.OutputStream

/**
 * 打包执行时「往哪写」的端口。
 *
 * ## 为什么不是直接给 `OutputStream`
 *
 * 因为**目标一定要能丢弃**。CBZ 的写入是「先创建文件、再往里写字节」,而
 * [writeCbz] 的契约是**全有或全无**:任何一页取不到就一个字节都不写。若直接拿
 * 一个已经 `createDocument` 出来的流,「不写」就意味着磁盘上留下一个 0 字节的 `.cbz` ——
 * 阅读器打开它不报错,只显示空白,用户以为打包成功了。
 *
 * 所以端口必须能表达两件事:[openFor] 拿流、[discardFor] 把那半成品删掉。
 * 平台层用 SAF(`DocumentsContract.deleteDocument`)实现,测试用内存实现。
 *
 * ## 线程与异常契约
 *
 * - 方法都是**普通阻塞函数**(与 [PageByteSource] 一致 —— 领域层不引协程依赖),
 *   调用方负责放到 IO 线程;
 * - [openFor] 返回 `null` 表示**打不开**(目标目录被删、空间不足、授权失效)——
 *   这是正常业务分支,不是异常,与 [PageByteSource] 同一个理由;
 * - [discardFor] 必须**尽力而为、不抛异常**:它是在失败路径上被调用的,
 *   清理者再炸一次只会把原始错误盖掉。删不掉时平台层自己记日志。
 */
fun interface CbzOutputSink {
    /**
     * 为这一卷打开输出流。
     *
     * @return `null` = 打不开(此时调用方记 [PackRunOutcome.Failed],并**不会**调用 [discardFor])
     */
    fun openFor(plan: VolumePackPlan): OutputStream?

    /**
     * 丢弃这一卷的半成品。
     *
     * 只在「流已打开、但写入过程中失败了」时被调用。实现必须幂等且不抛异常。
     */
    fun discardFor(plan: VolumePackPlan) {
        // 默认空实现:不清理的平台(例如纯内存测试)无需覆写。
        // 但**平台实现必须覆写** —— 否则半成品会留在用户目录里。
    }
}

/** 单卷的执行结果。 */
sealed interface PackRunOutcome {
    /** 成功写入。[pageCount] 是实际写进去的图片张数(不含 ComicInfo.xml)。 */
    data class Written(val pageCount: Int) : PackRunOutcome

    /** 计划里就标了跳过(输出已存在 + 用户选了跳过策略)。**没有碰过磁盘**。 */
    data object Skipped : PackRunOutcome

    /** 有页取不到字节。**一个字节都没写**,也没有创建文件。 */
    data class MissingPages(val missing: List<CbzPageEntry>) : PackRunOutcome

    /** 打不开输出流,或写盘过程中出错。[detail] 是给日志的,不是用户文案。 */
    data class Failed(val detail: String) : PackRunOutcome
}

/** 报告里的一行。 */
data class PackRunEntry(val plan: VolumePackPlan, val outcome: PackRunOutcome)

/**
 * 一次打包执行的结果。
 *
 * @param entries 已执行到的卷(**按计划顺序**)。中断后只含已处理的部分。
 * @param interruptedAt 中断发生在**第几卷开始之前**(索引)。`null` = 没中断。
 */
data class PackRunReport(
    val entries: List<PackRunEntry>,
    val interruptedAt: Int? = null,
) {
    val writtenCount: Int get() = entries.count { it.outcome is PackRunOutcome.Written }
    val skippedCount: Int get() = entries.count { it.outcome is PackRunOutcome.Skipped }
    val failedCount: Int get() = entries.count { it.outcome is PackRunOutcome.Failed }
    val missingCount: Int get() = entries.count { it.outcome is PackRunOutcome.MissingPages }

    /**
     * 是否**全部计划都处理完了**。
     *
     * 中断为 `false`;有失败项**仍为 `true`** —— 失败是「处理过了但没成功」,
     * 不是「还没处理」。这条区分决定了界面该显示「已完成,其中 N 卷失败」
     * 还是「已取消,还剩 M 卷」。
     */
    val isComplete: Boolean get() = interruptedAt == null

    /** 已处理卷数(成功 + 跳过 + 缺页 + 失败),供进度核对。 */
    val processedCount: Int get() = entries.size
}

/**
 * 按计划执行打包（D4b 的实质内核）。
 *
 * ## 每一卷的执行顺序（不可调换）
 *
 * 1. **计划里标了跳过** ⇒ 记 [PackRunOutcome.Skipped],不碰磁盘;
 * 2. **先探测所有页的字节** ⇒ 有任何一页取不到就记 [PackRunOutcome.MissingPages],
 *    **连输出流都不打开**。这一步是「全有或全无」真正落地的地方 ——
 *    [writeCbz] 的契约也是全有或全无,但它拿到流的时候文件已经被创建了;
 * 3. 打开输出流 ⇒ 打不开记 [PackRunOutcome.Failed];
 * 4. 写入 ⇒ 抛异常则记 `Failed` 并**调用 [CbzOutputSink.discardFor] 清理半成品**;
 * 5. 上报进度。
 *
 * ## 探测为什么要在这里再做一次
 *
 * [writeCbz] 内部已经探测过。这里**重复探测**是刻意的:探测必须在**开流之前**,
 * 而 [writeCbz] 的签名要求先给流。把探测提前,才能在「缺页」时做到**连文件都不创建**。
 * 代价是缺页时每页多读一次;相比在用户目录里留下 0 字节 CBZ,这个代价可以接受。
 * 正常路径(不缺页)只探测一次 —— 探测结果直接喂给 `writeCbz` 会重复,故这里让
 * `writeCbz` 自己再取一次;它要求 [PageByteSource] 可重复调用且无副作用(见其 KDoc)。
 *
 * ## 中断
 *
 * **只在卷与卷之间检查**([CancellationToken.shouldStop])。已经开始写的那一卷**必须写完** ——
 * 半途硬停会留下半个 ZIP,比多花一秒钟糟得多。检查点在第 2 卷及以后每卷开始前。
 *
 * @param timeMillis CBZ 内条目的修改时间。**必须为正**且由调用方显式给定 ——
 *   取「现在」会让同一份输入产出不同字节,测试与增量比对都做不了。
 * @param onProgress 每处理完一卷回调一次(参数 = 累计进度, 刚处理完的卷索引)。
 *   中断时**不补发** —— 一卷都没处理就没有进度可报。
 */
fun runPackPlans(
    plans: List<VolumePackPlan>,
    bytes: PageByteSource,
    sink: CbzOutputSink,
    token: CancellationToken,
    timeMillis: Long,
    onProgress: (TaskProgress, Int) -> Unit = { _, _ -> },
): PackRunReport {
    require(timeMillis > 0) { "timeMillis 必须为正：$timeMillis（时间戳需由调用方显式给定以保证可复现）" }

    val entries = mutableListOf<PackRunEntry>()
    var done = 0
    var failed = 0
    var skipped = 0

    for ((index, plan) in plans.withIndex()) {
        // 中断检查在**每卷开始之前**。索引 0 也检查 —— 用户可能一点开始就取消。
        if (token.shouldStop(beforeStartingItem = index)) {
            return PackRunReport(entries = entries.toList(), interruptedAt = index)
        }

        val outcome = when {
            plan.skipped -> PackRunOutcome.Skipped
            else -> writeOne(plan, bytes, sink, timeMillis)
        }
        entries += PackRunEntry(plan, outcome)

        when (outcome) {
            is PackRunOutcome.Written -> done++
            is PackRunOutcome.MissingPages -> failed++
            is PackRunOutcome.Failed -> failed++
            PackRunOutcome.Skipped -> skipped++
        }
        onProgress(
            TaskProgress(total = plans.size, done = done, failed = failed, skipped = skipped),
            index,
        )
    }

    return PackRunReport(entries = entries.toList(), interruptedAt = null)
}

/** 写一卷。探测失败时**不打开流** —— 这是「不在用户目录留 0 字节 CBZ」的唯一保证。 */
private fun writeOne(
    plan: VolumePackPlan,
    bytes: PageByteSource,
    sink: CbzOutputSink,
    timeMillis: Long,
): PackRunOutcome {
    val cbzPlan = buildCbzWritePlan(plan.volume)

    // ① 先探测:缺页就到此为止,输出文件根本不会被创建。
    //
    // 探测**只问能不能打开**,不读内容 —— 旧实现调 `bytesOf` 会把整页读进内存,
    // 在 256MB 堆上遇到 100MB 的页直接 OOM(真机实测,且留下 0 字节 CBZ)。
    // 开完立即关,避免泄漏文件描述符。
    val missing = cbzPlan.pages.filter { page ->
        val stream = bytes.openFor(page.itemId) ?: return@filter true
        runCatching { stream.close() }
        false
    }
    if (missing.isNotEmpty()) return PackRunOutcome.MissingPages(missing)

    // ② 再开流。
    val out = sink.openFor(plan) ?: return PackRunOutcome.Failed("无法为目标卷打开输出流")

    // ③ 写入。失败时必须请求清理 —— 否则半成品留在目标目录。
    return try {
        val result = out.use { writeCbz(cbzPlan, bytes, it, timeMillis) }
        if (result.ok) {
            PackRunOutcome.Written(pageCount = cbzPlan.pageCount)
        } else {
            // 理论不可达(①已探测过),但 bytesOf 允许有副作用地变化,故保留这条防线:
            // 万一真的走到这里,流已经关了、文件已经创建 ⇒ 必须丢弃。
            sink.discardFor(plan)
            PackRunOutcome.MissingPages(result.missing)
        }
    } catch (e: Exception) {
        sink.discardFor(plan)
        PackRunOutcome.Failed(e.message ?: e::class.simpleName ?: "写入失败")
    }
}
