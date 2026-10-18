package com.cyanix.pixfold.domain.thumb

/**
 * D2b 千级缩略图**生成策略**（HANGOFF §9 D2b 退出条件 ①）。
 *
 * 「千级」不是形容词：实测样本库 851 张、单张 3.4MB JPEG。若把缩略图整张交给
 * `BitmapFactory.decodeStream`，851 张就是 2.9GB 的瞬时分配；即便用
 * `ContentResolver.loadThumbnail` 拿回 512×512 的 ARGB_8888，**851 张全缓存也是
 * 851 × 512 × 512 × 4 B ≈ 892 MiB** —— 中端机整堆都装不下。所以策略必须由数字决定，
 * 不能靠「差不多就行」。
 *
 * ## 归责边界
 *
 * - **归这里**：请求边长怎么算、单张占多少内存、缓存能放几张、并发开多高、
 *   堆太小时怎么降级。**全是纯函数**，给定输入必然给出同一个输出。
 * - **不归这里**：`Runtime.maxMemory()` 怎么取（平台层）、LRU 怎么实现（平台层）、
 *   `loadThumbnail` 怎么调（`AndroidThumbnailSource`）、实际缓存命中（平台层）。
 *
 * 本模块不 import 任何 Android 类型 —— `domain/` 零 Android 依赖边界在此同样成立
 * （`AdapterBoundaryTest` 强制），故堆大小与核数都是**传进来的参数**。
 *
 * ## 三条被数字逼出来的规则
 *
 * 1. **向上对齐到 2 的幂**：`inSampleSize` 的语义是「按 2 的幂降采样」，
 *    请求 180px 时 provider 通常给 256px 档位；请求非档位值只会让 provider 自己取整，
 *    调用方看到的尺寸与预期不符（预览页按它算缩放比时会错）。
 *    但**不超过显示尺寸的 2 倍** —— 再大就是纯浪费（2 倍线性 = 4 倍内存）。
 * 2. **单张成本是边长的平方**：边长翻倍，内存 ×4。所以「请求大一点更清晰」的代价
 *    是**四次方**级的（边长 ×2 ⇒ 面积 ×4，条目数不变时总量 ×4）。降级时按 2 的幂
 *    往下降，一档就是 4 倍。
 * 3. **缓存预算取堆的一小片（12.5%）**：缩略图是**可重建**的数据 —— 丢了再取一次就行，
 *    没有理由为它占用大块堆。剩下的留给图片解码、Compose 本身与用户数据。
 */

/**
 * 一次缩略图加载会话的预算（[thumbBudgetFor] 的产物）。
 *
 * @param requestPx 向平台请求的边长（px）。已按 2 的幂对齐并夹在
 *   [ThumbBudgetRules.MIN_REQUEST_PX]..[ThumbBudgetRules.MAX_REQUEST_PX]。
 * @param maxConcurrent 允许同时在途的请求数上限（含 `Dispatchers.IO` 上的排队者）。
 * @param cacheEntries 内存缓存能容纳的**条目数**（每条约 [entryBytes] 字节）。
 * @param cacheBytes [cacheEntries] × [entryBytes] 的上界，即缓存实际会占用的字节数。
 * @param entryBytes 单张缩略图占用的字节数。
 * @param degraded 是否因堆太小而**降过**请求边长。为 `true` 时界面可以提示
 *   「缩略图清晰度已降低」，但正常路径不该看到它。
 */
data class ThumbBudget(
    val requestPx: Int,
    val maxConcurrent: Int,
    val cacheEntries: Int,
    val cacheBytes: Long,
    val entryBytes: Long,
    val degraded: Boolean,
)

/** [thumbBudgetFor] 用到的全部常量。集中在一处，便于真机实测后调参。 */
object ThumbBudgetRules {

    /** 请求边长下限。再小就认不出是哪张图了（44dp 的列表行按 3x ≈ 132px）。 */
    const val MIN_REQUEST_PX: Int = 64

    /**
     * 请求边长上限。
     *
     * 网格卡约 180dp × 3x ≈ 540px，因此 1024 已能覆盖横屏 5 列的极端情形；
     * 再往上单张就是 4MB 起，与「缩略图」这个用途不成比例。
     */
    const val MAX_REQUEST_PX: Int = 1024

    /** ARGB_8888：每像素 4 字节。 */
    const val BYTES_PER_PIXEL: Int = 4

    /** 并发下限：至少 2，否则单张慢图就能让整个列表停摆。 */
    const val MIN_CONCURRENT: Int = 2

    /** 并发上限：binder 调用再多也不会更快，且线程本身要占栈内存。 */
    const val MAX_CONCURRENT: Int = 8

    /**
     * 缓存的**保底**条目数。
     *
     * 即便堆极小，也要能装下几屏 —— 否则滚动时同一张图会被反复重新加载，
     * 反而更慢。这个下限由 [thumbBudgetFor] 的降级逻辑来保证（先降边长，再谈条目数）。
     */
    const val MIN_CACHE_ENTRIES: Int = 8

    /**
     * 缓存的**封顶**条目数。
     *
     * 只在调用方传入离谱的堆大小时生效（例如单元测试传 `Long.MAX_VALUE`）。
     * 正常值由 [CACHE_HEAP_FRACTION] 决定，不会被这条卡住。
     */
    const val MAX_CACHE_ENTRIES: Int = 512

    /** 缓存预算占可用堆的比例。缩略图可重建，不该占大块堆。 */
    const val CACHE_HEAP_FRACTION: Double = 0.125
}

/**
 * 给定显示边长，算出该向平台请求多大的缩略图。
 *
 * 规则：**向上**对齐到 2 的幂（不小于显示尺寸，避免放大模糊），但不超过显示尺寸的
 * 2 倍（再大是纯浪费），最后夹在 [ThumbBudgetRules.MIN_REQUEST_PX]..
 * [ThumbBudgetRules.MAX_REQUEST_PX]。
 *
 * @param displayPx 缩略图在屏上的实际边长（px）。`<= 0` 表示布局尚未完成
 *   （或调用方不需要真实像素），返回下限值。
 */
fun thumbRequestPx(displayPx: Int): Int {
    // 布局尚未完成（或调用方不关心像素）：给下限，绝不返回 0（平台 Size 要求 > 0）。
    if (displayPx <= 0) return ThumbBudgetRules.MIN_REQUEST_PX

    var px = ThumbBudgetRules.MIN_REQUEST_PX
    // 向上找第一个装得下 displayPx 的 2 的幂。
    while (px < displayPx && px < ThumbBudgetRules.MAX_REQUEST_PX) {
        px *= 2
    }
    // 夹住上限。displayPx 远超 1024 时上面已停在 MAX，这里幂等。
    return px.coerceAtMost(ThumbBudgetRules.MAX_REQUEST_PX)
}

/**
 * 单张缩略图占用的字节数（ARGB_8888，[ThumbBudgetRules.BYTES_PER_PIXEL] 字节/像素）。
 *
 * @param requestPx 边长（px）。非正数返回 0。
 */
fun thumbEntryBytes(requestPx: Int): Long {
    if (requestPx <= 0) return 0L
    // 边长的平方 × 4 字节。用 Long 运算：1024² × 4 = 4 MiB，Int 也装得下，
    // 但调用方可能传更大的值，先转 Long 避免 Int 溢出成负数。
    val side = requestPx.toLong()
    return side * side * ThumbBudgetRules.BYTES_PER_PIXEL
}

/**
 * 按显示尺寸、可用堆与 CPU 核数算出这一轮加载的预算。
 *
 * ## 降级规则
 *
 * 先按 [thumbRequestPx] 定边长，再看「缓存预算能不能装下
 * [ThumbBudgetRules.MIN_CACHE_ENTRIES] 张」；装不下就把边长减半（内存降到 1/4）
 * 重算，直到装得下或已到 [ThumbBudgetRules.MIN_REQUEST_PX]。
 *
 * 降级**先降边长、后降条目数**而不是反过来：条目数是「手感」的下限（少于几屏就会疯狂
 * 重载），边长是「清晰度」，在缩略图这个用途下后者可以让步。
 *
 * @param displayPx 缩略图在屏上的实际边长（px）。
 * @param availableHeapBytes 可用堆字节数（平台层一般传
 *   `Runtime.getRuntime().maxMemory()`；传 0 或负数视为「未知」，按零预算处理）。
 * @param cpuCores CPU 核数（`Runtime.getRuntime().availableProcessors()`）。
 *   并发取 `cpuCores + 1`（IO-bound 的常见经验值：多留一个在等待时仍能推进），
 *   再夹在 [ThumbBudgetRules.MIN_CONCURRENT]..[ThumbBudgetRules.MAX_CONCURRENT]。
 */
fun thumbBudgetFor(
    displayPx: Int,
    availableHeapBytes: Long,
    cpuCores: Int,
): ThumbBudget {
    // 并发：核数 + 1（IO-bound 的常见经验值），再夹上下限。
    // 核数脏值（<= 0）时 coerceIn 会把下限当结果，不需要单独分支。
    val maxConcurrent = (cpuCores + 1).coerceIn(
        ThumbBudgetRules.MIN_CONCURRENT,
        ThumbBudgetRules.MAX_CONCURRENT,
    )

    // 堆未知（0 / 负数）⇒ 预算按 0 处理，降级逻辑会一路降到底并保留保底条目数。
    val cacheBudgetBytes = if (availableHeapBytes <= 0L) {
        0L
    } else {
        (availableHeapBytes * ThumbBudgetRules.CACHE_HEAP_FRACTION).toLong()
    }

    val wantedPx = thumbRequestPx(displayPx)
    var px = wantedPx
    var entryBytes = thumbEntryBytes(px)
    var entries = cacheEntriesFor(cacheBudgetBytes, entryBytes)

    // 降级：先降边长（内存 ×1/4 一档），直到保底条目数装得下或已到地板。
    // 先降边长而不是先降条目数 —— 条目数少于几屏会导致滚动时疯狂重载。
    while (entries < ThumbBudgetRules.MIN_CACHE_ENTRIES &&
        px > ThumbBudgetRules.MIN_REQUEST_PX
    ) {
        px /= 2
        entryBytes = thumbEntryBytes(px)
        entries = cacheEntriesFor(cacheBudgetBytes, entryBytes)
    }

    // 即便降到底仍装不下保底（堆极端小 / 未知），也返回保底条目数 —— 宁可略超预算，
    // 也不要让缓存退化成 0 条（那等于每帧都重取）。
    val cacheEntries = entries.coerceAtLeast(ThumbBudgetRules.MIN_CACHE_ENTRIES)

    return ThumbBudget(
        requestPx = px,
        maxConcurrent = maxConcurrent,
        cacheEntries = cacheEntries,
        cacheBytes = cacheEntries.toLong() * entryBytes,
        entryBytes = entryBytes,
        degraded = px != wantedPx,
    )
}

/** 给定缓存预算与单张成本，能放几张（已封顶到 [ThumbBudgetRules.MAX_CACHE_ENTRIES]）。 */
private fun cacheEntriesFor(cacheBudgetBytes: Long, entryBytes: Long): Int {
    if (entryBytes <= 0L) return ThumbBudgetRules.MIN_CACHE_ENTRIES
    val raw = cacheBudgetBytes / entryBytes
    // 先夹 Int 范围再夹业务上限，避免 Long 超 Int 时 toInt() 截断成负数。
    val capped = raw.coerceAtMost(ThumbBudgetRules.MAX_CACHE_ENTRIES.toLong())
    return capped.toInt()
}
