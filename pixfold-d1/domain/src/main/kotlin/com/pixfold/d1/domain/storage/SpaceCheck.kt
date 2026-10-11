package com.pixfold.d1.domain.storage

/**
 * D2b 存储空间预检（HANGOFF §11 第 12 条）。
 *
 * 「提前预检 → 明确拒绝 → 引导到电脑」的**纯领域逻辑**。
 *
 * ## 归责边界
 *
 * - **归这里**：阈值判定、缺口计算、三态分类（充足 / 不足 / 临界）；
 * - **不归这里**：可用空间从哪查（平台层 `StatFs` / SAF）、图片大小从哪读（扫描层）。
 *
 * 阈值的具体值**参数化**，待 D2b 真机验证后定（§11 第 12 条原文）。
 * 本模块不 import 任何 Android 类型，`domain/` 零 Android 依赖边界在此同样成立。
 */

/**
 * 预检结果（三态）。
 *
 * - [Sufficient]：空间充足，可以继续打包；
 * - [Insufficient]：空间不足，**必须拒绝**并引导用户到电脑端处理；
 * - [Marginal]：空间高于需求但低于安全余量，**警告但允许继续**。
 */
sealed class SpaceCheckResult {
    /** 空间充足。 */
    data class Sufficient(
        val availableBytes: Long,
        val estimatedBytes: Long,
    ) : SpaceCheckResult()

    /** 空间不足，[shortfallBytes] = 需求量 − 可用量。 */
    data class Insufficient(
        val availableBytes: Long,
        val estimatedBytes: Long,
        val shortfallBytes: Long,
    ) : SpaceCheckResult()

    /** 空间临界：高于需求但低于需求 + [safetyMarginBytes]。 */
    data class Marginal(
        val availableBytes: Long,
        val estimatedBytes: Long,
        val safetyMarginBytes: Long,
    ) : SpaceCheckResult()
}

/**
 * 预检阈值策略（§11 第 12 条「按预估输出的百分比?绝对值?」）。
 *
 * - [Percentage]：要求可用 ≥ 预估 × (1 + marginPercent/100)；
 * - [Absolute]：要求可用 ≥ 预估 + marginBytes。
 */
sealed class SpaceThreshold {
    /** 按百分比留余量（`marginPercent = 20` 表示要多 20%）。 */
    data class Percentage(val marginPercent: Int) : SpaceThreshold()

    /** 按绝对值留余量（`marginBytes = 500L * 1024 * 1024` 表示多 500MB）。 */
    data class Absolute(val marginBytes: Long) : SpaceThreshold()
}

/**
 * 执行存储空间预检。
 *
 * 判定顺序：**先判不足，再判临界，否则充足**。
 * 不足的判据是 `available < required`（`required` 由阈值策略算出）；
 * 临界的判据是 `available < required + safetyMargin`（即高于需求但不够安全余量）。
 *
 * @param availableBytes  目标存储可用空间（字节，由平台层提供）
 * @param estimatedBytes  预估输出总大小（字节，由 [estimateTotalSize] 算出）
 * @param threshold       阈值策略
 * @param safetyMargin    临界安全余量（低于此值但高于需求时警告）
 */
fun checkSpace(
    availableBytes: Long,
    estimatedBytes: Long,
    threshold: SpaceThreshold,
    safetyMargin: Long,
): SpaceCheckResult {
    require(availableBytes >= 0) { "availableBytes 不能为负：$availableBytes" }
    require(estimatedBytes >= 0) { "estimatedBytes 不能为负：$estimatedBytes" }
    require(safetyMargin >= 0) { "safetyMargin 不能为负：$safetyMargin" }

    val requiredBytes = when (threshold) {
        is SpaceThreshold.Percentage ->
            // 整数运算向下取整，与 Long 算术一致，避免浮点。
            estimatedBytes + estimatedBytes * threshold.marginPercent / 100
        is SpaceThreshold.Absolute ->
            estimatedBytes + threshold.marginBytes
    }

    return when {
        availableBytes < requiredBytes -> SpaceCheckResult.Insufficient(
            availableBytes = availableBytes,
            estimatedBytes = estimatedBytes,
            shortfallBytes = requiredBytes - availableBytes,
        )
        availableBytes < requiredBytes + safetyMargin -> SpaceCheckResult.Marginal(
            availableBytes = availableBytes,
            estimatedBytes = estimatedBytes,
            safetyMarginBytes = safetyMargin,
        )
        else -> SpaceCheckResult.Sufficient(
            availableBytes = availableBytes,
            estimatedBytes = estimatedBytes,
        )
    }
}

/**
 * 预估单卷 CBZ 输出大小。
 *
 * CBZ 是 ZIP 压缩，但图片（JPEG / PNG）本身已压缩，
 * 再压缩收益极小（实测 < 2%），故预估 ≈ 原图总大小 + 元数据开销。
 *
 * @param pageBytes         各页图片大小（字节）
 * @param comicInfoOverhead ComicInfo.xml 等 ZIP 条目开销（默认 512 字节估算）
 */
fun estimateCbzSize(pageBytes: List<Long>, comicInfoOverhead: Long = 512L): Long {
    require(comicInfoOverhead >= 0) { "comicInfoOverhead 不能为负：$comicInfoOverhead" }
    return pageBytes.sumOf { it } + comicInfoOverhead
}

/**
 * 预估整库打包总大小（各卷预估之和）。
 *
 * @param volumeEstimates 各卷预估大小（由 [estimateCbzSize] 逐卷算出）
 */
fun estimateTotalSize(volumeEstimates: List<Long>): Long = volumeEstimates.sumOf { it }
