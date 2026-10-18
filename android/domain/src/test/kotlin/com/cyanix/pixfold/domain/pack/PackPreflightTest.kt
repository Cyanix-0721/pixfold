package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.SourcePolicy
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.storage.SpaceCheckResult
import com.cyanix.pixfold.domain.storage.SpaceThreshold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 打包前的预检（D4b 执行前最后一道闸）。
 *
 * ## 这一层补的是什么缺口
 *
 * `SpaceCheck`（D2b 第 4 项,22 tests）与 `Zip64Assessment`（D2b 第 5 项,22 tests）
 * 早已落地并各自单测通过,但**从未被任何生产代码调用过** —— 它们只是两个孤立的工具函数。
 * 而 §10《平台》把「1GB 级归档写入 + 空间预检 + 明确拒绝 + 引导到电脑」列为验收标准,
 * 意味着这两个函数必须在**真正开始写之前**被组合起来使用。
 *
 * 组合它们不是简单相加,有三个只有在「放在一起」时才出现的问题:
 *
 * 1. **预估的可靠性取决于尺寸数据在不在**。SAF provider 的 `COLUMN_SIZE` 可以为 null
 *    （平台层按 0 处理）,此时 `estimateCbzSize` 算出来的是**下界**而不是估计值。
 *    拿一个下界去判「空间充足」是拿用户的数据冒险。
 * 2. **空计划不该报空间不足**。`checkSpace(available, 0, …)` 在 safetyMargin > 0 时
 *    会返回 `Marginal` —— 一「没有东西要打包」的任务被告知「空间临界」是荒唐的。
 * 3. **Zip64 不是「拒绝」的理由,而是「告知」的理由**。Zip64 是标准扩展,
 *    Java 的 zip 实现能写（CbzWriter 已知条目大小,不需要未知长度流式 STORED）;
 *    真正要拒绝的是**空间不足**（§10 原文「明确拒绝并引导到电脑」）。
 *    把 Zip64 当拒绝条件会让用户在大归档上完全无法打包。
 */
class PackPreflightTest {

    // ==================== 夹具 ====================

    private val base = com.cyanix.pixfold.domain.mock.MockDataB.library.volumes.first()

    /** 造一个卷:页数、每页字节数可控。sizeBytes <= 0 表示「provider 没给尺寸」。 */
    private fun volume(
        id: String,
        pages: Int = 2,
        pageBytes: Long = 1_000L,
    ): ComicVolume {
        // 不能直接 base.pages.take(pages) —— mock 卷只有几张页,要造 7 万页时会被静默截断,
        // 于是「需要 Zip64」的用例永远不触发(首轮 3 条测试红就是这个原因:断言对了、夹具错了)。
        val items = (0 until pages).map { i ->
            base.pages[i % base.pages.size].copy(
                id = "$id-$i",
                sizeBytes = pageBytes,
                baseName = "${id}_$i",
            )
        }
        return base.copy(
            id = id,
            cbzFileName = "$id.cbz",
            outputDir = "out",
            pages = items,
            pageOrder = pageOrderOf(items),
        )
    }

    private fun plan(
        id: String,
        pages: Int = 2,
        pageBytes: Long = 1_000L,
        skipped: Boolean = false,
    ): VolumePackPlan {
        val v = volume(id, pages, pageBytes)
        return VolumePackPlan(
            volume = v,
            outputDisplay = v.cbzFileName,
            pageCount = v.pageCount,
            skipped = skipped,
            skipReason = null,
            conflictPolicy = OutputConflictPolicy.Rename,
            sourcePolicy = SourcePolicy.Keep,
        )
    }

    private val percent10 = SpaceThreshold.Percentage(marginPercent = 10)
    private val hugeAvailable = 10L * 1024 * 1024 * 1024 // 10 GiB

    // ==================== 正常路径 ====================

    @Test
    fun `空间充足时允许继续`() {
        val result = preflightPackPlans(listOf(plan("v1")), hugeAvailable, percent10, safetyMargin = 0L)
        assertTrue(result.canProceed)
        assertTrue(result.warnings.isEmpty(), "一切都好时不该有噪音提示")
        assertTrue(result.blockers.isEmpty())
    }

    @Test
    fun `预估大小等于各页之和加元数据开销`() {
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 3, pageBytes = 1_000L)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        // estimateCbzSize = 3 × 1000 + 512
        assertEquals(3_512L, result.estimatedBytes)
    }

    @Test
    fun `多卷预估求和`() {
        val result = preflightPackPlans(
            listOf(plan("a", pages = 1), plan("b", pages = 2)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        assertEquals((1_000L + 512L) + (2_000L + 512L), result.estimatedBytes)
    }

    @Test
    fun `空计划允许继续且不报空间临界`() {
        // 回归守卫:checkSpace(available, 0, …, safetyMargin > 0) 会返回 Marginal,
        // 于是「没有东西要打包」被告知「空间临界」—— 荒唐。
        val result = preflightPackPlans(emptyList(), availableBytes = 0L, percent10, safetyMargin = 1_000_000L)
        assertTrue(result.canProceed)
        assertTrue(result.warnings.isEmpty())
        assertEquals(0L, result.estimatedBytes)
    }

    @Test
    fun `全部卷都被跳过时允许继续`() {
        val result = preflightPackPlans(
            listOf(plan("v1", skipped = true)),
            availableBytes = 0L,
            percent10,
            safetyMargin = 1_000_000L,
        )
        assertTrue(result.canProceed)
        assertTrue(result.warnings.isEmpty())
        assertEquals(0L, result.estimatedBytes, "跳过的卷不会产出字节,不该计入预估")
    }

    // ==================== 空间不足 ⇒ 必须拒绝 ====================

    /** ⭐ 核心:§10 原文「空间预检 + 明确拒绝 + 引导到电脑」。 */
    @Test
    fun `空间不足必须拒绝并给出缺口`() {
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 10, pageBytes = 1_000_000L)),
            availableBytes = 1_000_000L,
            percent10,
            safetyMargin = 0L,
        )
        assertFalse(result.canProceed)
        val blocker = assertIs<PackBlocker.SpaceInsufficient>(result.blockers.single())
        assertTrue(blocker.shortfallBytes > 0)
        assertIs<SpaceCheckResult.Insufficient>(blocker.space)
    }

    @Test
    fun `空间不足时也把 Zip64 提示一并给出`() {
        // 用户改小目标或换目录重试时,Zip64 信息仍然有用 —— 不该因为空间判定先命中就吞掉。
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 70_000, pageBytes = 1_000_000L)),
            availableBytes = 0L,
            percent10,
            safetyMargin = 0L,
        )
        assertFalse(result.canProceed)
        assertIs<Zip64Requirement.Needed>(result.volumes.single().zip64)
    }

    // ==================== 空间临界 ⇒ 警告但允许 ====================

    @Test
    fun `空间高于需求但低于安全余量时警告且允许继续`() {
        // 预估 2 × 1000 + 512 = 2_512;百分比 10% ⇒ 需求 2_763
        val result = preflightPackPlans(
            listOf(plan("v1")),
            availableBytes = 3_000L,
            percent10,
            safetyMargin = 10_000L,
        )
        assertTrue(result.canProceed, "临界不是拒绝条件 —— 余量只是保险,不是硬需求")
        assertIs<PackWarning.SpaceMarginal>(result.warnings.single())
    }

    // ==================== 尺寸未知 ⇒ 预估只是下界 ====================

    /** ⭐ 核心:拿一个下界去判「空间充足」是拿用户数据冒险。 */
    @Test
    fun `有页尺寸未知时警告预估只是下界`() {
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 2, pageBytes = 0L)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        assertTrue(result.canProceed)
        val warn = assertIs<PackWarning.EstimateIsLowerBound>(result.warnings.single())
        assertEquals(1, warn.volumeCount)
    }

    @Test
    fun `尺寸未知的卷数与提示里的卷数一致`() {
        val result = preflightPackPlans(
            listOf(plan("a", pageBytes = 0L), plan("b", pageBytes = 5_000L), plan("c", pageBytes = -1L)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        val warn = assertIs<PackWarning.EstimateIsLowerBound>(result.warnings.single())
        assertEquals(2, warn.volumeCount, "两卷尺寸未知(a 与 c),b 有真实尺寸")
    }

    @Test
    fun `尺寸未知不会让空间判定变成充足`() {
        // 下界都装不下 ⇒ 一定装不下,这个结论是可靠的,照常拒绝。
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 5, pageBytes = 1_000_000L), plan("v2", pages = 2, pageBytes = 0L)),
            availableBytes = 0L,
            percent10,
            safetyMargin = 0L,
        )
        assertFalse(result.canProceed)
        assertIs<PackBlocker.SpaceInsufficient>(result.blockers.single())
    }

    // ==================== Zip64 ⇒ 告知但允许 ====================

    /** ⭐ 核心:Zip64 是标准扩展,不是拒绝理由。 */
    @Test
    fun `需要 Zip64 时警告但不阻止`() {
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 70_000, pageBytes = 1_000L)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        assertTrue(result.canProceed, "Zip64 是合法扩展 —— 把它当拒绝会让大归档完全打不了")
        val warn = assertIs<PackWarning.Zip64Required>(result.warnings.single())
        assertEquals(Zip64Reason.TooManyEntries, warn.reason)
        assertEquals("v1.cbz", warn.outputDisplay)
    }

    @Test
    fun `单页超过 4GB 时给出条目过大原因`() {
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 1, pageBytes = Zip32Limits.MAX_ENTRY_SIZE + 1)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        val warn = assertIs<PackWarning.Zip64Required>(result.warnings.single())
        assertEquals(Zip64Reason.EntryTooLarge, warn.reason)
    }

    @Test
    fun `每卷的条目数含 ComicInfo xml`() {
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 3)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        assertEquals(4, result.volumes.single().entryCount, "3 页 + ComicInfo.xml = 4")
    }

    // ==================== 组合与顺序 ====================

    @Test
    fun `多个问题同时给出而不是只报第一个`() {
        // 一次只报一个问题的界面会让用户陷入「改一个 → 再试 → 又冒一个」的循环。
        val result = preflightPackPlans(
            listOf(
                plan("small", pages = 1, pageBytes = 1_000L),
                plan("huge", pages = 70_000, pageBytes = 1_000L),
                plan("noknown", pages = 1, pageBytes = 0L),
            ),
            availableBytes = 0L,
            percent10,
            safetyMargin = 0L,
        )
        assertFalse(result.canProceed)
        assertEquals(1, result.blockers.size)
        assertEquals(2, result.warnings.size, "尺寸未知 + Zip64 两条都要给")
    }

    @Test
    fun `跳过卷不参与空间与 Zip64 判定`() {
        val result = preflightPackPlans(
            listOf(plan("skipped", pages = 70_000, pageBytes = 1_000L, skipped = true)),
            availableBytes = 0L,
            percent10,
            safetyMargin = 0L,
        )
        assertTrue(result.canProceed, "跳过的卷根本不执行,它的 Zip64 与空间都不该拦住用户")
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `每卷都保留自己的预检明细`() {
        val result = preflightPackPlans(listOf(plan("a"), plan("b")), hugeAvailable, percent10, 0L)
        assertEquals(listOf("a.cbz", "b.cbz"), result.volumes.map { it.plan.outputDisplay })
    }

    @Test
    fun `绝对值阈值同样生效`() {
        val result = preflightPackPlans(
            listOf(plan("v1")),
            availableBytes = 2_000L,
            threshold = SpaceThreshold.Absolute(marginBytes = 10_000L),
            safetyMargin = 0L,
        )
        assertFalse(result.canProceed)
    }
}
