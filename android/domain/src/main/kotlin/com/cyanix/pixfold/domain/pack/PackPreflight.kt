package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.storage.SpaceCheckResult
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import com.cyanix.pixfold.domain.storage.checkSpace
import com.cyanix.pixfold.domain.storage.estimateCbzSize
import com.cyanix.pixfold.domain.storage.estimateTotalSize

/**
 * 打包执行前的预检（D4b 执行前最后一道闸）。
 *
 * ## 为什么需要这一层
 *
 * `SpaceCheck` 与 `Zip64Assessment` 各自是纯函数，早就单测通过，但**从来没有被组合使用过**。
 * 而 HANGOFF §10《平台》把「Android 真机完成 1GB 级归档的写入验证……更大归档的验收口径为
 * 『空间预检 + 明确拒绝 + 引导到电脑』」列为验收标准 —— 组合它们不是相加，有三个只在
 * 「放在一起」时才存在的问题：
 *
 * 1. **预估的可靠性取决于尺寸数据在不在**。SAF provider 的 `COLUMN_SIZE` 可以为 `null`
 *    （平台层按 0 处理），此时 [estimateCbzSize] 算出来的是**下界**而不是估计值。
 *    拿一个下界去判「空间充足」是拿用户的数据冒险 —— 故必须显式告知。
 * 2. **空计划不该报空间不足/临界**。[checkSpace] 在 `estimatedBytes = 0` 且
 *    `safetyMargin > 0` 时返回 `Marginal`，一「没有东西要打包」的任务被告知「空间临界」是荒唐的。
 * 3. **Zip64 不是拒绝的理由，是告知的理由**。Zip64 是标准扩展，`CbzWriter` 已知条目大小
 *    （不需要未知长度流式 STORED），能正常写出。真正要拒绝的只有**空间不足**。
 *    把 Zip64 当拒绝条件会让用户在一份大归档上完全无法打包。
 *
 * ## 归责边界
 *
 * - **归这里**：把计划折成「总预估 + 每卷条目统计」，组合两个既有判定，分出
 *   **阻断项**与**提示项**；
 * - **不归这里**：可用空间数字从哪来（平台层 `StatFs` / SAF query）、页尺寸从哪来
 *   （扫描层已填 `sizeBytes`）、以及**要不要继续**（那是 UI 的事，这里只给结论）。
 *
 * 零 Android import（`AdapterBoundaryTest` 强制）。
 */

/** 单卷的预检明细，供界面逐卷展示（「这卷 3.2GB，需要 Zip64」）。 */
data class PackVolumeCheck(
    val plan: VolumePackPlan,
    /** 条目数 = 页数 + `ComicInfo.xml`。 */
    val entryCount: Int,
    /** 本卷预估输出字节（下界 —— 若 [hasUnknownSize] 为 true）。 */
    val estimatedBytes: Long,
    /** 本卷是否有页**没有**尺寸数据（provider 未给）。 */
    val hasUnknownSize: Boolean,
    val zip64: Zip64Requirement,
)

/** 阻断项：出现任何一条就**不能**开始写。 */
sealed interface PackBlocker {
    /**
     * 目标存储空间不足。
     *
     * 这是 §10 要求的「明确拒绝」—— [shortfallBytes] 让界面能说清「还差 1.2 GB」，
     * 而不是笼统的「空间不足」。
     */
    data class SpaceInsufficient(
        val space: SpaceCheckResult.Insufficient,
    ) : PackBlocker {
        val shortfallBytes: Long get() = space.shortfallBytes
    }

    /**
     * 同一批计划里有**多个卷写出到同一个文件**。
     *
     * 2026-10-19 真机实测发现的真实缺陷：真实素材同系列两本
     * （`[Cior (Ken-1)] …で` 与 `…で――2 [DL版]`）目录名都解析不出卷号，
     * Title 又都取了系列名 ⇒ 两卷 `cbzFileName` 相同 ⇒ 后一卷静默覆盖前一卷。
     * 报告显示「成功 2」，磁盘上只剩 1 个文件、79,764,572 B —— **用户以为两本都打好了**。
     *
     * 为什么是**阻断**而不是提示：无论 [com.cyanix.pixfold.domain.plan.OutputConflictPolicy]
     * 选什么，同批内两个不同卷写同一路径都不可能同时成立（后写必然毁掉前写）。
     * 这不是「要不要覆盖已有文件」的问题，是「这批计划本身自相矛盾」。
     *
     * 为什么归预检而不是归 `PackRunner`：执行到一半才发现冲突时，前几卷的字节
     * 已经落盘、覆盖已经发生；用户本该在点「执行」之前就知道。
     *
     * @param outputPath 被多个卷抢的路径（原样给用户看，便于他去改元数据）
     * @param volumeIds 抢同一路径的卷 id（≥2；顺序 = 计划顺序，便于定位是哪两本）
     */
    data class OutputNameCollision(
        val outputPath: String,
        val volumeIds: List<String>,
    ) : PackBlocker {
        /** 卷数（≥2）。界面文案「%1$d 卷写到同一个文件」用它。 */
        val collidingCount: Int get() = volumeIds.size
    }
}

/** 提示项：不阻止执行，但用户应当知道。 */
sealed interface PackWarning {
    /** 空间高于需求但低于安全余量。可以继续，但值得提醒。 */
    data class SpaceMarginal(val space: SpaceCheckResult.Marginal) : PackWarning

    /**
     * 有 [volumeCount] 卷的页尺寸未知 ⇒ 预估总大小只是**下界**。
     *
     * 为什么值得单独一条：下界「够用」不代表真的够用。用户看到「空间充足」
     * 却在写到 80% 时磁盘满，比一开始就提醒一句糟得多。
     */
    data class EstimateIsLowerBound(val volumeCount: Int) : PackWarning

    /** 本卷需要 Zip64（条目数 > 65535 或单条目 > 4GB−1）。标准扩展，不阻止执行。 */
    data class Zip64Required(
        val outputDisplay: String,
        val reason: Zip64Reason,
    ) : PackWarning
}

/** 预检结论。 */
data class PackPreflightResult(
    val volumes: List<PackVolumeCheck>,
    val blockers: List<PackBlocker>,
    val warnings: List<PackWarning>,
    val estimatedBytes: Long,
    val availableBytes: Long,
) {
    /** 没有阻断项就可以开始写。**提示项不影响这个结论**（见 KDoc 第 3 条）。 */
    val canProceed: Boolean get() = blockers.isEmpty()
}

/**
 * 对一组打包计划做执行前预检。
 *
 * @param plans 计划（**已跳过的卷不参与**：它们不产出字节、不打开流，拦住用户毫无意义）
 * @param availableBytes 目标存储可用空间（平台层提供）
 * @param threshold 空间阈值策略。**默认值待真机验证后定**（§11 第 12 条），
 *   故此处**不设默认值** —— 让调用方显式选，避免这个数字被悄悄固化。
 * @param safetyMargin 临界余量（高于需求但低于此值时给 [PackWarning.SpaceMarginal]）
 */
fun preflightPackPlans(
    plans: List<VolumePackPlan>,
    availableBytes: Long,
    threshold: SpaceThreshold,
    safetyMargin: Long,
): PackPreflightResult {
    val runnable = plans.filter { !it.skipped }

    val volumes = runnable.map { plan ->
        val order = plan.volume.pageOrder.order
        val unknown = order.any { it.sizeBytes <= 0 }
        PackVolumeCheck(
            plan = plan,
            entryCount = order.size + 1, // + ComicInfo.xml
            estimatedBytes = estimateCbzSize(order.map { it.sizeBytes.coerceAtLeast(0) }),
            hasUnknownSize = unknown,
            zip64 = assessZip64Need(
                entryCount = order.size + 1,
                maxEntrySize = order.maxOfOrNull { it.sizeBytes.coerceAtLeast(0) } ?: 0L,
            ),
        )
    }

    val estimatedBytes = estimateTotalSize(volumes.map { it.estimatedBytes })

    val warnings = buildList {
        // 尺寸未知 → 预估只是下界。放在空间判定**之前**加入,与下面的空间提示互不影响。
        val unknownCount = volumes.count { it.hasUnknownSize }
        if (unknownCount > 0) add(PackWarning.EstimateIsLowerBound(unknownCount))

        // Zip64 逐卷告知(标准扩展,不阻止)。
        for (v in volumes) {
            val needed = v.zip64
            if (needed is Zip64Requirement.Needed) {
                add(PackWarning.Zip64Required(v.plan.outputDisplay, needed.reason))
            }
        }
    }.toMutableList()

    val blockers = mutableListOf<PackBlocker>()

    // 同批输出名冲突 —— **必须最先判**,且与空间无关:哪怕空间充足到能写十份,
    // 两个卷写同一路径也只有最后一份能活下来。
    // 用 outputPath(含目录)而非仅文件名:不同输出目录下的同名 CBZ 是合法的。
    // 同批输出名冲突 —— **必须最先判**,且与空间无关:哪怕空间充足到能写十份,
    // 两个卷写同一路径也只有最后一份能活下来。
    // 用 outputPath(含目录)而非仅文件名:不同输出目录下的同名 CBZ 是合法的。
    blockers += runnable
        .groupBy { it.outputPath }
        .filterValues { it.size > 1 }
        .entries
        .sortedBy { it.key }
        .map { (path, group) ->
            PackBlocker.OutputNameCollision(
                outputPath = path,
                volumeIds = group.map { it.volume.id },
            )
        }

    // 空计划:干脆不判空间。estimatedBytes = 0 + safetyMargin > 0 会返回 Marginal,
    // 而「没有东西要打包」被告知「空间临界」是荒唐的。
    if (volumes.isNotEmpty()) {
        when (val space = checkSpace(availableBytes, estimatedBytes, threshold, safetyMargin)) {
            is SpaceCheckResult.Insufficient -> blockers += PackBlocker.SpaceInsufficient(space)
            is SpaceCheckResult.Marginal -> warnings += PackWarning.SpaceMarginal(space)
            is SpaceCheckResult.Sufficient -> Unit
        }
    }

    return PackPreflightResult(
        volumes = volumes,
        blockers = blockers.toList(),
        warnings = warnings.toList(),
        estimatedBytes = estimatedBytes,
        availableBytes = availableBytes,
    )
}
