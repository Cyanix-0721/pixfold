package com.pixfold.d1.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.unit.IntOffset

/**
 * 拖拽**实时让位**的位移动画规格(用户 2026-10-15 反馈:「拖拽也可以但是缺少动效不够好看」)。
 *
 * ## 为什么需要它
 *
 * 实时让位靠**逐帧改本地展示顺序**实现(见 [DragReorderGrid] KDoc):某一刻邻居从 A 位移到 B 位,
 * 若没有动画,它是在**一帧之内瞬移**的 —— 真机观感是"卡片啪地跳过去",而不是"被挤开"。
 * 用户要的正是后者。
 *
 * ## 为什么是 spring 而不是 tween
 *
 * 位移动画会被**连续重启** —— 指针每跨过一格就让位一次,每次重启都从当前位置重新出发。
 * spring 天然是"从当前速度/位置继续"的,所以连续换格时跟手顺滑;`tween` 每次重启都从 0
 * 速度开始,连续触发会显得一顿一顿。
 * `DampingRatioNoBouncy` 明确**不过冲**:让位是"腾位置",弹过头会让卡片越过目标再退回来,
 * 在密集网格里观感很乱(这里刻意不要 `DampingRatioMediumBouncy` 那种 Q 弹效果)。
 *
 * ## 只设位移,不设淡入淡出
 *
 * `animateItem` 的 `fadeInSpec` / `fadeOutSpec` 刻意传 `null`:拖动中每次贴边自动滚动都会
 * 带入新项,给它们淡入会让"让位"这件事被淡入噪声盖住。更关键的是,**只要任一 spec 非 null,
 * 项在进出视线时就会被留在组合树里并量成 0 尺寸**(语义树里多出 `top=bottom=0` 的卡片节点),
 * 污染取 `min()` / 最小 top 的既有几何断言 —— 实测常挂 `animateItem`(即便三档全 `snap`)
 * 会让 2 条**不涉及拖拽**的既有测试变红。故调用点还额外**只在拖动期间挂载**(见调用处注释)。
 *
 * ⚠️ **动画观感属 §7.4「只能真机(用户判定)」一类**:本文件只把"有没有动画、规格是什么"
 * 钉成常量便于引用与变异;是否**好看**一律以真机为准。
 */
internal val DragReorderPlacementSpec: FiniteAnimationSpec<IntOffset> =
    spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        // 刚度取 StiffnessHigh(10000)而非 Medium(1500) —— 用户 2026-10-15 真机反馈
        // 「动画有点慢」。读数(animation-core 1.12.1 常量 + 数值模拟,72px 一格):
        //   Medium -> t90 100.4ms / t99 171.4ms
        //   High   -> t90  38.9ms / t99  66.4ms(快 61.5 / 105.0ms)
        // 更关键是**连续重启**下的稳态追踪滞后(拖拽中目标每跨一格就重启一次动画):
        //   Medium 落后 196.4px(≈2.73 格) -> High 落后 54.7px(≈0.76 格)。
        // 保持 `DampingRatioNoBouncy`(ζ=1.0 临界阻尼)⇒ 过冲严格 0,即便在
        // "每 8–33ms 随机重启 + 注入 2–5k px/s 入射速度"的抖动扫描下也不越过目标;
        // 而 M3 expressive `fastSpatialSpec`(ζ=0.6/800)过冲 9.48%≈6.8px,密集网格里观感乱。
        // **不改成 tween**:tween 每次重启都从 0 速度起步(不携带初速),同场景稳态滞后
        // 达 16–28 格,结构性劣于任何 spring 档位;而且 foundation 的
        // `LazyLayoutItemAnimationKt.InterruptionSpec` 只对**非** `SpringSpec` 生效 ——
        // 传 tween 会在连续重启时被顶换成 `spring(1.0/400)`,比现状(1500)还慢。
        // ⚠️ 动画观感属"只能真机(用户判定)"一类,本数值仅用于选型,不是定稿依据。
        stiffness = Spring.StiffnessHigh,
        // **必须显式传阈值**:不传时 `spring()` 走 `DefaultSpringAnimations` 分支,
        // 用 `Spring.DefaultDisplacementThreshold = 0.01f` —— 比库默认的
        // `IntOffset.VisibilityThreshold = (1,1)` 精细 **100 倍**,动画尾巴明显更长
        // (贴边自动滚动时每带入一行新项都从视口边界"飞"进来,尾巴叠加 = 残影/抽搐感)。
        // 传库默认值即可,代价仅"小于 1px 的让位不再动画"(观感上无差别)。
        visibilityThreshold = IntOffset.VisibilityThreshold,
    )
