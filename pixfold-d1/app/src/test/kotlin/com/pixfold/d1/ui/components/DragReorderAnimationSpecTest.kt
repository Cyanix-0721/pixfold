package com.pixfold.d1.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * 拖拽**实时让位**的过渡动效:规格守护(用户 2026-10-15:「拖拽也可以但是**缺少动效不够好看**」)。
 *
 * ## 这条断言为什么不测"画面在动",只测规格
 *
 * 先试过三条路,**全部证明无杀伤力**,如实记录以免后来者重走:
 *
 * 1. **读语义树几何** ❌ —— `animateItem` 的位移施加在**项自己的** `graphicsLayer` 上,而语义树
 *    `boundsInRoot` 不计入节点自身图层变换。冻结时钟后逐帧读 `TAG_GRID_CARD`,拖动中与落定后
 *    **读数完全相同**(只有终值)。
 * 2. **逐帧比像素(NATIVE + `captureToImage`)** ❌ —— 实测「推进 100ms 的帧 vs 落定帧」差异:
 *    `spring = 40798`、`spring→snap()` = `40798`、**完全不挂 `animateItem`** = `30594`。
 *    即该指标主要反映**跟手浮层/拖动态**等噪声,`snap` 与 `spring` **读数一模一样** ⇒
 *    把规格换成"立即到达"这种**正是本条要防的退化**可以照样全绿。
 * 3. 断言「两帧之间有变化」❌ 更弱,连"重排发生了"都不能区别于"动画发生了"。
 *
 * ⇒ 按 §7.4「变异能存活就说明断言没有杀伤力 —— 删掉它」,**删掉像素断言**,
 * 只守护**能离线钉死且确实可被杀掉**的部分:**让位规格必须是一个真正的 spring**。
 * 变异 `spring(…) → snap()` 会被本条捕获。
 *
 * ## 刻意不设的断言(及原因)
 *
 * **「`animateItem` 到底有没有挂上」无离线判据** —— 把拖动期的
 * `Modifier.animateItem(...)` 整段去掉,全仓 478 项测试**全绿**(实测)。
 * 原因是它只影响**逐帧渲染**而不影响任何语义/几何读数。故该点无离线护栏,
 * 由**真机核对**覆盖(观感类结论按 §7.4 属"只能真机由用户判定")。
 *
 * ## 为什么是 spring 而不是 tween
 *
 * 位移动画会被**连续重启**(指针每跨一格就让位一次)。spring 从当前速度/位置继续,
 * 连续换格跟手顺滑;tween 每次重启都从 0 速度开始,会一顿一顿。
 * `DampingRatioNoBouncy`(ζ=1.0)明确**不过冲** —— 让位是"腾位置",弹过头会让卡片越过目标再退回来,
 * 密集网格里观感很乱(刻意不要 Q 弹效果)。理由同时写在 [DragReorderPlacementSpec] 的 KDoc。
 */
class DragReorderAnimationSpecTest {

    @Test
    fun `reflow placement spec is a spring, not instant arrival`() {
        val spec = DragReorderPlacementSpec
        assertTrue(
            spec is SpringSpec<IntOffset>,
            "实时让位的位移规格必须是 spring(连续换格时从当前位置/速度继续)," +
                "实际为 ${spec::class.simpleName}。若被改成 snap/tween,就是本条要防的退化。",
        )
        assertEquals(
            Spring.DampingRatioNoBouncy,
            spec.dampingRatio,
            "让位必须不过冲(弹过头会让卡片越过目标再退回来);" +
                "若改成 MediumBouncy 之类,观感会在密集网格里变乱。",
        )
        assertEquals(
            IntOffset.VisibilityThreshold,
            spec.visibilityThreshold,
            "必须显式传库默认阈值:不传会回落到 0.01f(精细 100 倍,动画尾巴更长," +
                "贴边滚动带入新项时残影叠加)。",
        )
        assertEquals(
            Spring.StiffnessHigh,
            spec.stiffness,
            "刚度是观感参数,数值变更须同时回写真机核对结论(观感类只能真机判定)。" +
                "取 High 的依据:用户 2026-10-15 反馈「动画有点慢」,数值模拟得 t90 " +
                "100.4ms -> 38.9ms、连续重启稳态滞后 196.4px -> 54.7px。",
        )
    }
}
