package com.cyanix.pixfold.domain.thumb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D2b 千级缩略图策略单测（HANGOFF §9 D2b 退出条件 ①）。
 *
 * 每条断言都对应一条**会被真机证伪**的数字，而不是「看起来合理」。
 * 关键规模来自实测：样本库 851 张、单张 3.4MB JPEG；真机 PJX110 的
 * `Runtime.maxMemory()` 约 256MB（不是物理内存）。
 */
class ThumbBudgetTest {

    // ==================== thumbRequestPx：2 的幂对齐 ====================

    @Test
    fun `显示尺寸恰好是 2 的幂时原样返回`() {
        // 256 已经是档位，不该被"向上对齐"推到 512 去浪费 4 倍内存。
        assertEquals(256, thumbRequestPx(256))
        assertEquals(512, thumbRequestPx(512))
    }

    @Test
    fun `显示尺寸不是 2 的幂时向上对齐`() {
        // 180dp × 3x = 540px ⇒ 对齐到 1024?不对——先夹上限再对齐的策略见下一条。
        // 这里用中间档验证"向上"：300 → 512。
        assertEquals(512, thumbRequestPx(300))
        // 44dp 的列表行 × 3x = 132px ⇒ 256。
        assertEquals(256, thumbRequestPx(132))
    }

    @Test
    fun `对齐结果不小于显示尺寸`() {
        // 缩小会让缩略图被放大显示（模糊），这条是硬要求。
        for (displayPx in 1..ThumbBudgetRules.MAX_REQUEST_PX) {
            val req = thumbRequestPx(displayPx)
            assertTrue(
                req >= displayPx || req == ThumbBudgetRules.MAX_REQUEST_PX,
                "displayPx=$displayPx 的请求尺寸 $req 小于显示尺寸，会放大模糊",
            )
        }
    }

    @Test
    fun `对齐结果不超过显示尺寸的两倍`() {
        // 2 倍线性 = 4 倍内存。除**下限地板**外，超 2 倍都是纯浪费。
        // 地板区（displayPx < MIN/2）单独由下面那条测，否则 1px 显示要 64px 会被误判。
        for (displayPx in (ThumbBudgetRules.MIN_REQUEST_PX / 2) until ThumbBudgetRules.MAX_REQUEST_PX) {
            val req = thumbRequestPx(displayPx)
            assertTrue(
                req <= displayPx * 2,
                "displayPx=$displayPx 的请求尺寸 $req 超过了显示的 2 倍",
            )
        }
    }

    @Test
    fun `显示尺寸低于下限地板时一律取下限`() {
        // 44dp 列表行按 3x ≈ 132px 已在下限之上；真正的极小值只会出现在布局未完成时。
        // 地板是刻意的例外：请求 1px 的缩略图没有意义。
        for (displayPx in 1 until ThumbBudgetRules.MIN_REQUEST_PX / 2) {
            assertEquals(ThumbBudgetRules.MIN_REQUEST_PX, thumbRequestPx(displayPx))
        }
    }

    @Test
    fun `超过上限时夹到上限`() {
        // 横屏 9 列 × 大屏也可能超过 1024；夹住而不是继续翻倍。
        assertEquals(ThumbBudgetRules.MAX_REQUEST_PX, thumbRequestPx(4_000))
        assertEquals(ThumbBudgetRules.MAX_REQUEST_PX, thumbRequestPx(1_024))
    }

    @Test
    fun `非正数显示尺寸返回下限`() {
        // 布局尚未完成时 displayPx 可能是 0；返回下限而不是 0（Size 必须 > 0）。
        assertEquals(ThumbBudgetRules.MIN_REQUEST_PX, thumbRequestPx(0))
        assertEquals(ThumbBudgetRules.MIN_REQUEST_PX, thumbRequestPx(-100))
    }

    // ==================== thumbEntryBytes：边长平方 ====================

    @Test
    fun `单张字节数等于边长平方乘以四`() {
        // ARGB_8888 = 4 字节/像素。512×512×4 = 1 MiB，正好是「一兆一张」的直觉来源。
        assertEquals(512L * 512L * 4L, thumbEntryBytes(512))
        assertEquals(256L * 256L * 4L, thumbEntryBytes(256))
        assertEquals(1_048_576L, thumbEntryBytes(512))
    }

    @Test
    fun `边长翻倍字节数变四倍`() {
        // 这条是「降一档省 4 倍」的依据；写成 2 倍就说明有人把面积当成了周长。
        assertEquals(thumbEntryBytes(256) * 4, thumbEntryBytes(512))
    }

    @Test
    fun `非正数边长字节数为零`() {
        assertEquals(0L, thumbEntryBytes(0))
        assertEquals(0L, thumbEntryBytes(-1))
    }

    // ==================== thumbBudgetFor：并发 ====================

    @Test
    fun `并发取核数加一并夹在上下限内`() {
        // 8 核 → 9，被上限 8 夹住；IO-bound 多留一个是为了等待时仍能推进。
        assertEquals(ThumbBudgetRules.MAX_CONCURRENT, thumbBudgetFor(512, 256L * 1024 * 1024, 8).maxConcurrent)
        // 1 核 → 2（下限），否则单张慢图就能让列表停摆。
        assertEquals(ThumbBudgetRules.MIN_CONCURRENT, thumbBudgetFor(512, 256L * 1024 * 1024, 1).maxConcurrent)
        // 4 核 → 5，落在中间，直接验证 "+1" 而不是"等于核数"。
        assertEquals(5, thumbBudgetFor(512, 256L * 1024 * 1024, 4).maxConcurrent)
    }

    @Test
    fun `核数为零或负数时用下限`() {
        // availableProcessors() 理论上是正数，但传参边界要能承受脏值。
        assertEquals(ThumbBudgetRules.MIN_CONCURRENT, thumbBudgetFor(512, 256L * 1024 * 1024, 0).maxConcurrent)
        assertEquals(ThumbBudgetRules.MIN_CONCURRENT, thumbBudgetFor(512, 256L * 1024 * 1024, -4).maxConcurrent)
    }

    // ==================== thumbBudgetFor：缓存 ====================

    @Test
    fun `堆充足时缓存占堆的八分之一`() {
        // 256MB 堆 × 12.5% = 32MB；每张 512² × 4 = 1MB ⇒ 32 张。
        val budget = thumbBudgetFor(displayPx = 512, availableHeapBytes = 256L * 1024 * 1024, cpuCores = 8)
        assertEquals(512, budget.requestPx)
        assertEquals(1_048_576L, budget.entryBytes)
        assertEquals(32, budget.cacheEntries)
        assertEquals(32L * 1_048_576L, budget.cacheBytes)
        assertFalse(budget.degraded)
    }

    @Test
    fun `cacheBytes 恒等于条目数乘单张字节数`() {
        // 两个字段必须自洽——否则平台层按 cacheBytes 分配、按 cacheEntries 淘汰，
        // 两边的上限不一致，缓存会悄悄超预算。
        val cases = listOf(
            Triple(256, 512L * 1024 * 1024, 8),
            Triple(512, 256L * 1024 * 1024, 4),
            Triple(1024, 128L * 1024 * 1024, 2),
        )
        for ((displayPx, heap, cores) in cases) {
            val b = thumbBudgetFor(displayPx, heap, cores)
            assertEquals(b.cacheEntries.toLong() * b.entryBytes, b.cacheBytes, "displayPx=$displayPx heap=$heap")
        }
    }

    @Test
    fun `缓存不超过封顶条目数`() {
        // 传 Long.MAX_VALUE 会让 12.5% 溢出不合理的值；必须被 MAX_CACHE_ENTRIES 夹住。
        val budget = thumbBudgetFor(512, Long.MAX_VALUE, 4)
        assertEquals(ThumbBudgetRules.MAX_CACHE_ENTRIES, budget.cacheEntries)
    }

    // ==================== thumbBudgetFor：降级 ====================

    @Test
    fun `堆太小时降级请求边长而不是先降条目数`() {
        // 96MB 堆 × 12.5% = 12MB。512 档每张 1MB ⇒ 只能放 12 张（≥ 下限 8，不降级）；
        // 但 1024 档每张 4MB ⇒ 只能放 3 张（< 8）⇒ 降到 512。
        val budget = thumbBudgetFor(displayPx = 1024, availableHeapBytes = 96L * 1024 * 1024, cpuCores = 8)
        assertEquals(512, budget.requestPx, "应先降边长")
        assertTrue(budget.degraded)
        assertTrue(budget.cacheEntries >= ThumbBudgetRules.MIN_CACHE_ENTRIES, "降级后条目数不该低于保底")
    }

    @Test
    fun `降级按 2 的幂逐档下降且停在够用的档`() {
        // 16MB × 12.5% = 2MB 预算。逐档：1024(4MiB)→512(1MiB)→256(256KiB)
        // 256 档能放 2MB/256KiB = 8 张 = 保底 ⇒ 停在这里，不该无谓地降到 64。
        val budget = thumbBudgetFor(displayPx = 1024, availableHeapBytes = 16L * 1024 * 1024, cpuCores = 4)
        assertEquals(256, budget.requestPx)
        assertTrue(budget.degraded)
        assertTrue(budget.cacheEntries >= ThumbBudgetRules.MIN_CACHE_ENTRIES)
    }

    @Test
    fun `堆极小时一路降到下限地板`() {
        // 1MB × 12.5% = 128KB：1024/512/256/128 四档都装不下 8 张，
        // 64 档（16KiB/张）正好 8 张 ⇒ 停在地板。
        val budget = thumbBudgetFor(displayPx = 1024, availableHeapBytes = 1L * 1024 * 1024, cpuCores = 4)
        assertEquals(ThumbBudgetRules.MIN_REQUEST_PX, budget.requestPx)
        assertTrue(budget.degraded)
        assertEquals(ThumbBudgetRules.MIN_CACHE_ENTRIES, budget.cacheEntries)
    }

    @Test
    fun `堆未知时按零预算但条目数兜底`() {
        // availableHeapBytes = 0 表示"没拿到"（平台层可能读不到）。不能崩，也不能返回 0 条目
        // 让缓存完全失效（那样每次滚动都重载）。
        val budget = thumbBudgetFor(512, 0L, 4)
        assertEquals(ThumbBudgetRules.MIN_CACHE_ENTRIES, budget.cacheEntries)
        assertTrue(budget.cacheBytes >= 0L)
    }

    @Test
    fun `大堆不会让条目数突破封顶`() {
        // 3GB 堆 × 12.5% = 384MB / 1MB = 384 张，未破 512；但 30GB 堆会算出 3840 张。
        val budget = thumbBudgetFor(512, 30L * 1024 * 1024 * 1024, 8)
        assertEquals(ThumbBudgetRules.MAX_CACHE_ENTRIES, budget.cacheEntries)
    }

    // ==================== 真实规模 ====================

    @Test
    fun `851 张图在 256MB 堆上不会全缓存`() {
        // 这是本轮的头号动机：851 × 512² × 4B ≈ 892MiB，远超堆。
        // 预算必须把它压到几十张 —— 靠 LRU + 滚动按需重取，而不是"全留着"。
        val budget = thumbBudgetFor(displayPx = 540, availableHeapBytes = 256L * 1024 * 1024, cpuCores = 8)
        assertTrue(
            budget.cacheEntries < 851,
            "851 张若全缓存需要 ${851L * budget.entryBytes / 1024 / 1024}MB，超出堆预算；实际容纳 ${budget.cacheEntries} 张",
        )
        assertTrue(
            budget.cacheBytes <= (256L * 1024 * 1024) * ThumbBudgetRules.CACHE_HEAP_FRACTION,
            "缓存预算 ${budget.cacheBytes} 超过堆的 12.5%",
        )
    }
}
