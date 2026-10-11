package com.pixfold.d1.domain.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D2b 存储空间预检领域层单测（HANGOFF §11 第 12 条）。
 *
 * "提前预检 → 明确拒绝 → 引导到电脑"的纯领域逻辑。
 * 阈值参数化，具体值待 D2b 真机验证后定。
 */
class SpaceCheckTest {

    // ---- checkSpace: Sufficient ----

    @Test
    fun `充足空间返回 Sufficient`() {
        val result = checkSpace(
            availableBytes = 10_000L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Absolute(marginBytes = 1_000L),
            safetyMargin = 500L,
        )
        assertIs<SpaceCheckResult.Sufficient>(result)
        assertEquals(10_000L, result.availableBytes)
        assertEquals(5_000L, result.estimatedBytes)
    }

    @Test
    fun `百分比阈值充足时返回 Sufficient`() {
        // 预估 5000，要求多 20% = 6000，可用 8000 > 6000 + safety 500
        val result = checkSpace(
            availableBytes = 8_000L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Percentage(marginPercent = 20),
            safetyMargin = 500L,
        )
        assertIs<SpaceCheckResult.Sufficient>(result)
    }

    // ---- checkSpace: Insufficient ----

    @Test
    fun `绝对值阈值不足时返回 Insufficient 并算出缺口`() {
        // 需要 5000 + 1000 = 6000，只有 4000，缺口 2000
        val result = checkSpace(
            availableBytes = 4_000L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Absolute(marginBytes = 1_000L),
            safetyMargin = 500L,
        )
        assertIs<SpaceCheckResult.Insufficient>(result)
        assertEquals(4_000L, result.availableBytes)
        assertEquals(5_000L, result.estimatedBytes)
        assertEquals(2_000L, result.shortfallBytes)
    }

    @Test
    fun `百分比阈值不足时返回 Insufficient`() {
        // 预估 5000，要求 5000 * 1.2 = 6000，只有 5500，缺口 500
        val result = checkSpace(
            availableBytes = 5_500L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Percentage(marginPercent = 20),
            safetyMargin = 0L,
        )
        assertIs<SpaceCheckResult.Insufficient>(result)
        assertEquals(500L, result.shortfallBytes)
    }

    @Test
    fun `刚好等于需求量不算不足`() {
        // 需要 6000，恰好 6000 → 不不足（但可能 Marginal）
        val result = checkSpace(
            availableBytes = 6_000L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Absolute(marginBytes = 1_000L),
            safetyMargin = 0L,
        )
        assertTrue(result !is SpaceCheckResult.Insufficient)
    }

    @Test
    fun `差一字节也算不足`() {
        // 需要 6000，只有 5999
        val result = checkSpace(
            availableBytes = 5_999L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Absolute(marginBytes = 1_000L),
            safetyMargin = 0L,
        )
        assertIs<SpaceCheckResult.Insufficient>(result)
        assertEquals(1L, result.shortfallBytes)
    }

    // ---- checkSpace: Marginal ----

    @Test
    fun `高于需求但低于安全余量返回 Marginal`() {
        // 需要 6000，可用 6200，safety 500 → 6200 < 6000 + 500 = 6500 → Marginal
        val result = checkSpace(
            availableBytes = 6_200L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Absolute(marginBytes = 1_000L),
            safetyMargin = 500L,
        )
        assertIs<SpaceCheckResult.Marginal>(result)
        assertEquals(500L, result.safetyMarginBytes)
    }

    @Test
    fun `刚好高于安全余量返回 Sufficient`() {
        // 需要 6000，可用 6500，safety 500 → 6500 >= 6500 → Sufficient
        val result = checkSpace(
            availableBytes = 6_500L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Absolute(marginBytes = 1_000L),
            safetyMargin = 500L,
        )
        assertIs<SpaceCheckResult.Sufficient>(result)
    }

    @Test
    fun `安全余量为零时不产生 Marginal`() {
        // safety = 0 → 不可能 Marginal，要么 Insufficient 要么 Sufficient
        val result = checkSpace(
            availableBytes = 6_000L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Absolute(marginBytes = 1_000L),
            safetyMargin = 0L,
        )
        assertIs<SpaceCheckResult.Sufficient>(result)
    }

    // ---- checkSpace: 零与边界 ----

    @Test
    fun `预估为零且有空闲返回 Sufficient`() {
        val result = checkSpace(
            availableBytes = 1_000L,
            estimatedBytes = 0L,
            threshold = SpaceThreshold.Absolute(marginBytes = 0L),
            safetyMargin = 0L,
        )
        assertIs<SpaceCheckResult.Sufficient>(result)
    }

    @Test
    fun `可用为零且预估非零返回 Insufficient`() {
        val result = checkSpace(
            availableBytes = 0L,
            estimatedBytes = 100L,
            threshold = SpaceThreshold.Absolute(marginBytes = 0L),
            safetyMargin = 0L,
        )
        assertIs<SpaceCheckResult.Insufficient>(result)
        assertEquals(100L, result.shortfallBytes)
    }

    // ---- checkSpace: 参数校验 ----

    @Test
    fun `可用空间为负抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            checkSpace(-1L, 100L, SpaceThreshold.Absolute(0L), 0L)
        }
    }

    @Test
    fun `预估为负抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            checkSpace(100L, -1L, SpaceThreshold.Absolute(0L), 0L)
        }
    }

    @Test
    fun `安全余量为负抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            checkSpace(100L, 50L, SpaceThreshold.Absolute(0L), -1L)
        }
    }

    // ---- estimateCbzSize ----

    @Test
    fun `预估单卷大小等于各页之和加开销`() {
        val size = estimateCbzSize(pageBytes = listOf(1_000L, 2_000L, 3_000L), comicInfoOverhead = 512L)
        assertEquals(6_512L, size)
    }

    @Test
    fun `空卷预估等于开销`() {
        val size = estimateCbzSize(pageBytes = emptyList(), comicInfoOverhead = 512L)
        assertEquals(512L, size)
    }

    @Test
    fun `默认开销为 512 字节`() {
        val size = estimateCbzSize(pageBytes = listOf(1_000L))
        assertEquals(1_512L, size)
    }

    @Test
    fun `开销为负抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            estimateCbzSize(pageBytes = listOf(1_000L), comicInfoOverhead = -1L)
        }
    }

    // ---- estimateTotalSize ----

    @Test
    fun `整库预估等于各卷之和`() {
        val total = estimateTotalSize(listOf(1_000L, 2_000L, 3_000L))
        assertEquals(6_000L, total)
    }

    @Test
    fun `空库预估为零`() {
        val total = estimateTotalSize(emptyList())
        assertEquals(0L, total)
    }

    // ---- 百分比阈值整数运算 ----

    @Test
    fun `百分比阈值零余量等于预估本身`() {
        // marginPercent = 0 → required = estimated
        val result = checkSpace(
            availableBytes = 5_000L,
            estimatedBytes = 5_000L,
            threshold = SpaceThreshold.Percentage(marginPercent = 0),
            safetyMargin = 0L,
        )
        assertIs<SpaceCheckResult.Sufficient>(result)
    }

    @Test
    fun `百分比阈值向下取整不溢出`() {
        // 预估 3，margin 50% → 3 + 3*50/100 = 3 + 1 = 4（整数除法向下取整）
        val result = checkSpace(
            availableBytes = 4L,
            estimatedBytes = 3L,
            threshold = SpaceThreshold.Percentage(marginPercent = 50),
            safetyMargin = 0L,
        )
        assertIs<SpaceCheckResult.Sufficient>(result)
        // 差 1 就不足
        val result2 = checkSpace(
            availableBytes = 3L,
            estimatedBytes = 3L,
            threshold = SpaceThreshold.Percentage(marginPercent = 50),
            safetyMargin = 0L,
        )
        assertIs<SpaceCheckResult.Insufficient>(result2)
    }
}
