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

    /**
     * ⭐ 整卷超过 4 GiB 时必须告出 Zip64。
     *
     * 前两条判据（条目数、单页大小）在真实漫画里几乎碰不到：一张 4 GiB 的图不存在，
     * 65536 页的单卷也不存在。**真正会撞上的是第三条** —— 整卷输出超过 4 GiB，
     * 此时 ZIP32 的中央目录偏移字段装不下。
     *
     * 这条判据曾经**整条路径都是死的**：`assessZip64Need` 的 `centralDirOffset`
     * 在集成层从未被传过，一直吃默认值 `0L`（KDoc 明写「0 表示尚未知 / 不判定」），
     * 于是 4.9 GiB 的素材也永远不会被告知需要 Zip64。
     *
     * 夹具刻意让**单页远小于 4 GiB**（3 GiB × 2 页），这样只有中央目录那条会命中，
     * 原因断言才有鉴别力。
     */
    @Test
    fun `整卷超过 4GB 时按中央目录偏移给出 Zip64 原因`() {
        val threeGiB = 3L * 1024 * 1024 * 1024
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 2, pageBytes = threeGiB)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        assertTrue(result.canProceed, "Zip64 是合法扩展 —— 超过 4 GiB 也不该被拒绝")
        val warn = assertIs<PackWarning.Zip64Required>(result.warnings.single())
        assertEquals(
            Zip64Reason.CentralDirOffsetTooLarge,
            warn.reason,
            "单页只有 3 GiB、条目只有 3 个 ⇒ 只可能是整卷超 4 GiB 这条",
        )
    }

    /** 刚好 4 GiB 的卷不触发（边界必须是「大于」，不是「大于等于」）。 */
    @Test
    fun `整卷恰好 4GB 时不告 Zip64`() {
        // 一页 4 GiB − 1 减去 ComicInfo 的 512 字节开销 ⇒ 预估恰好等于上限。
        val justUnder = Zip32Limits.MAX_CENTRAL_DIR_OFFSET - 512L
        val result = preflightPackPlans(
            listOf(plan("v1", pages = 1, pageBytes = justUnder)),
            hugeAvailable,
            percent10,
            safetyMargin = 0L,
        )
        assertTrue(
            result.warnings.none { it is PackWarning.Zip64Required },
            "恰好等于上限时 ZIP32 仍然装得下 —— 边界写成大于等于会误报",
        )
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

    // ==================== 同批输出名冲突（2026-10-19 真机实测发现的缺陷） ====================

    /**
     * 真机再现：同系列两本，目录名都解析不出卷号 ⇒ 两卷 `cbzFileName` 相同。
     * 执行后报告显示「成功 2」，磁盘上只剩 1 个文件（79,764,572 B）。
     * 断言这个情形**在执行前就被拦住**。
     */
    @Test
    fun `两卷写同一路径时阻断执行`() {
        val a = planWithOutput("v1", outputName = "同名.cbz")
        val b = planWithOutput("v2", outputName = "同名.cbz")

        val result = preflightPackPlans(listOf(a, b), hugeAvailable, percent10, safetyMargin = 0L)

        assertFalse(result.canProceed, "同批两卷写同一文件,后写必然毁掉前写")
        val blocker = result.blockers.filterIsInstance<PackBlocker.OutputNameCollision>().single()
        assertEquals("out/同名.cbz", blocker.outputPath)
        assertEquals(listOf("v1", "v2"), blocker.volumeIds)
        assertEquals(2, blocker.collidingCount)
    }

    @Test
    fun `三卷撞同一路径时一次列出全部卷`() {
        val plans = listOf("v1", "v2", "v3").map { planWithOutput(it, outputName = "同名.cbz") }

        val result = preflightPackPlans(plans, hugeAvailable, percent10, 0L)

        val blocker = result.blockers.filterIsInstance<PackBlocker.OutputNameCollision>().single()
        assertEquals(3, blocker.collidingCount)
        assertEquals(listOf("v1", "v2", "v3"), blocker.volumeIds)
    }

    @Test
    fun `不同目录下的同名文件不算冲突`() {
        // 不同系列各有一本「第01卷.cbz」是完全合法的 —— 判据必须是**完整路径**,
        // 不能只是文件名,否则会把正常的多系列工作区误判成冲突。
        val a = planWithOutput("v1", outputName = "第01卷.cbz", outputDir = "系列A")
        val b = planWithOutput("v2", outputName = "第01卷.cbz", outputDir = "系列B")

        val result = preflightPackPlans(listOf(a, b), hugeAvailable, percent10, 0L)

        assertTrue(result.canProceed)
        assertTrue(result.blockers.isEmpty())
    }

    @Test
    fun `跳过的卷不参与冲突判定`() {
        // 跳过卷不写任何字节,它参与判定会让用户被一个根本不会发生的冲突拦住。
        val a = planWithOutput("v1", outputName = "同名.cbz")
        val b = planWithOutput("v2", outputName = "同名.cbz", skipped = true)

        val result = preflightPackPlans(listOf(a, b), hugeAvailable, percent10, 0L)

        assertTrue(result.canProceed)
    }

    @Test
    fun `冲突与空间不足同时成立时两条阻断项都在`() {
        // 一次给全部问题 —— 用户改完元数据后不该又被新的一条拦住第二次。
        val a = planWithOutput("v1", outputName = "同名.cbz")
        val b = planWithOutput("v2", outputName = "同名.cbz")

        val result = preflightPackPlans(listOf(a, b), availableBytes = 0L, percent10, 0L)

        assertFalse(result.canProceed)
        assertEquals(2, result.blockers.size)
        assertTrue(result.blockers.any { it is PackBlocker.OutputNameCollision })
        assertTrue(result.blockers.any { it is PackBlocker.SpaceInsufficient })
    }

    @Test
    fun `多组冲突按路径排序各成一条`() {
        // 输入顺序**刻意与字典序相反**（甲 U+7532 > 乙 U+4E59）：
        // 若断言顺序与 groupBy 的自然首现顺序一致，去掉排序的变异会存活 ——
        // 实测 M5 就因此活过一轮。
        val plans = listOf(
            planWithOutput("b1", outputName = "甲.cbz"),
            planWithOutput("b2", outputName = "甲.cbz"),
            planWithOutput("a1", outputName = "乙.cbz"),
            planWithOutput("a2", outputName = "乙.cbz"),
        )

        val result = preflightPackPlans(plans, hugeAvailable, percent10, 0L)

        val collisions = result.blockers.filterIsInstance<PackBlocker.OutputNameCollision>()
        // 按 code point 排序：「乙」U+4E59 < 「甲」U+7532。别按直觉的中文顺序断言。
        assertEquals(listOf("out/乙.cbz", "out/甲.cbz"), collisions.map { it.outputPath })
    }

    /** 造一个输出名/输出目录可控的计划（冲突判据直接吃 `outputPath`）。 */
    private fun planWithOutput(
        id: String,
        outputName: String,
        outputDir: String = "out",
        skipped: Boolean = false,
    ): VolumePackPlan {
        val v = volume(id).copy(cbzFileName = outputName, outputDir = outputDir)
        return VolumePackPlan(
            volume = v,
            outputDisplay = outputName,
            pageCount = v.pageCount,
            skipped = skipped,
            skipReason = null,
            conflictPolicy = OutputConflictPolicy.Rename,
            sourcePolicy = SourcePolicy.Keep,
        )
    }
}
