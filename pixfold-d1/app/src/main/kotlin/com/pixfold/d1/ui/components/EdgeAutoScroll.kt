package com.pixfold.d1.ui.components

/**
 * 拖拽边缘自动滚动(HANGOFF §11 第 18 条,2026-09-25 用户指出):
 * 「拖拽不会带动网格/列表滚动,极端点可能会有首页被自动排序到末尾的情况
 *   所以需要拖拽调整」—— 不能跨视口拖拽时,「把被规则排到末尾的首图拖回来」
 * **物理上不可能**。
 *
 * ## 为什么是**事件驱动**,而不是定时/帧驱动
 *
 * 直觉方案是「指针停在边缘时开一个 `withFrameNanos` 循环持续滚动」。实测
 * (Robolectric + `createComposeRule`)证明这条路在本仓库**不可测且会挂死测试**:
 *  - 无条件帧循环 ⇒ `setContent`/`waitForIdle` 永不返回,抛
 *    `java.lang.InterruptedException at Builders.kt:94`;
 *  - 条件帧循环在 `autoAdvance = true` 下 ⇒ `OutOfMemoryError: Java heap space`
 *    (永不 idle,测试框架无界推进帧);
 *  - `mainClock.advanceTimeByFrame()` **不会**恢复 `withFrameNanos` 的续体
 *    (实测连续推进 6 帧,回调计数恒为 0),且 `advanceTimeBy` 也不驱动
 *    `kotlinx.coroutines.delay`(tick 恒为 0)。
 *  即:任何「拖拽期间持续运行」的帧循环,都会让**同一次拖拽内**的任何断言同步挂死。
 *
 * 而**事件驱动**完全可测:每次 `onDrag` 事件若指针落在边缘带内,就按深度滚动一段,
 * 实测 5 次 `moveBy` ⇒ 列表下标 0 → 7、偏移 20px,确定性、无需操作测试时钟。
 *
 * 代价(已记入 [docs/notes/d1-ui-friction-inventory.md] 与 HANGOFF §11 第 18 条):
 * 指针在边缘**完全静止**时不会继续滚动(没有事件就没有推进);真机上手指自然抖动
 * 会产生事件,且 [EDGE_SCROLL_MAX_MULTIPLIER] 提供的加速让长距离调整可行。
 * 这是**为可测性做的有意取舍**,不是遗漏 —— 帧循环的实现方式无法离线断言,
 * 按 AGENTS.md §7.4「变异能存活就说明断言没有杀伤力」的纪律,宁可选择可验证的形态。
 *
 * ## 与 `change.consume()` 的关系
 *
 * 手势层**继续** `consume()`(不放开给 Lazy 布局自己滚),原因:
 * 长按拖拽期间若同时让 LazyGrid/LazyColumn 自己响应同一个手势,会与这里的
 * 程序化滚动**叠加**成双倍滚动、且与「松手落地」的目标格判定相互干扰。
 * 归档 Flutter 原型的注释也记录了同一取舍:移动端用长按「避免与滚动冲突」。
 */
internal const val EDGE_SCROLL_ZONE_DP = 72f
internal const val EDGE_SCROLL_BASE_STEP_DP = 14f
internal const val EDGE_SCROLL_MAX_MULTIPLIER = 4

/**
 * 计算一次拖拽事件应滚动的像素量。
 *
 * 符号与 Compose 的 `ScrollableState.scrollBy` 一致:
 *  - **> 0** = 内容上移(指针在**下**边缘,想看后面的项,即 scroll forward);
 *  - **< 0** = 内容下移(指针在**上**边缘,想看前面的项,即 scroll backward);
 *  - **0** = 指针不在边缘带内,或容器尺寸无效(此时不滚动)。
 *
 * @param pointerYInRoot 指针的根坐标 Y(与容器同为根坐标系)。
 * @param containerTopInRoot 容器左上角的根坐标 Y。
 * @param containerHeight 容器高度(px);<= 0 时返回 0(尚未布局)。
 * @param density 当前密度,用于把 dp 常数换算成 px。
 * @param burst 已连续处于边缘带内的事件数(0 = 本次是第一个);用于加速,
 *   上限 [EDGE_SCROLL_MAX_MULTIPLIER]。离开边缘带后必须由调用方清零。
 */
internal fun edgeAutoScrollStep(
    pointerYInRoot: Float,
    containerTopInRoot: Float,
    containerHeight: Float,
    density: Float,
    burst: Int,
): Float {
    if (containerHeight <= 0f) return 0f
    val zone = EDGE_SCROLL_ZONE_DP * density
    val localY = pointerYInRoot - containerTopInRoot
    val distanceFromBottom = containerHeight - localY

    val direction: Float
    val depth: Float
    if (localY < zone) {
        // 上边缘:指针越靠上,越急着看前面的内容
        direction = -1f
        depth = (zone - localY) / zone
    } else if (distanceFromBottom < zone) {
        direction = 1f
        depth = (zone - distanceFromBottom) / zone
    } else {
        return 0f
    }
    val safeDepth = depth.coerceIn(0f, 1f)
    val multiplier = (1 + burst).coerceIn(1, EDGE_SCROLL_MAX_MULTIPLIER)
    return direction * EDGE_SCROLL_BASE_STEP_DP * density * safeDepth * multiplier
}
