package com.pixfold.d1.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost

/**
 * 预测性返回手势中,被按在后面的页面缩小到的比例。
 *
 * 与迁移前 `PredictiveBackContainer.MIN_SCALE = 0.90f` 保持一致 —— 用户在真机上
 * 量化核对过这个幅度(2026-09-21:分隔线 y 346→418、跨度 1263→1178)。库的默认值是
 * `0.7f`,比原来的观感夸张得多,故显式覆盖。
 */
const val PREDICTIVE_MIN_SCALE = 0.90f

/**
 * 返回转场的**层级风格**。
 *
 * 用户给了两条分开的规格(2026-09-22):
 * - 工作流内部(带步骤的容器)→「拖动越多当前页越透明,露出上级页面」;
 * - 大页面切换(根级)→「当前页面虚化缩小」。
 *
 * 故同一条策略需要两个档位,由各 NavHost 声明自己是哪一档。
 */
enum class NavTransitionStyle {
    /** 工作流内部:无缩放,只淡出 —— 上级页原样静止露出。 */
    Step,

    /** 根级大页面:淡出 + 缩到 [PREDICTIVE_MIN_SCALE]。 */
    Page,
}

/**
 * 应用统一的 [NavHost]:转场策略只在这里定义一处。
 *
 * **为什么要包一层**:`composable<T>` 目的地**没有**预测性返回转场参数,预测性转场只能在
 * `NavHost` 级配置(navigation-compose 2.10.2:过渡瀑布 `createPredictivePop*() ?: predictivePop*`——
 * 目的地不声明就落到 NavHost 级)。根图与两个工作流步骤图都要同一套策略,故收敛到这个包装。
 *
 * ## 转场策略
 *
 * - `enterTransition`/`exitTransition` = `None`:**前进保持瞬切**。迁移前的导航是
 *   `when (screen)` 直接换页,前进本来就没有动画;库默认是 700ms 淡入淡出,若照用,
 *   每次进工作流、每次切 Tab 都会多出一次淡入淡出。用户 2026-09-22 明确裁决
 *   「只做返回/手势,进入保持瞬切」。
 * - `popEnterTransition` = `None`(由 `enterTransition` 派生,无需显式传):
 *   后退时目标页应当**静止地被揭示**,由上层页面自己动。
 * - `predictivePopEnterTransition` = `None`(始终):手势中目标页必须**静止地**待在后面,
 *   这本身就是 HANGOFF §9.1 第 3 步「目标页显示在背后」的全部要求。
 * - 上层页面的动画按 [style] 分档(见 [NavTransitionStyle]),手势跟手与普通返回用**同一套原语**,
 *   区别只在:手势由手指的 `progress` 直接驱动(`NavHost` 内部把它当
 *   `SeekableTransitionState.seekTo` 的 fraction),普通返回到不了手指,由动画自己跑完。
 * - 开关 [predictiveBackAnimation] 关 → 手势中页面**纹丝不动**(`None`),松手后仍由
 *   `popExitTransition` 补上动画。这就是用户裁决的 (b) 案:「关掉手势跟随但**不硬切**」。
 *
 * ## 为什么 `scaleOut` 要 `+ fadeOut`
 *
 * `scaleOut` 的产物**只有** `TransitionData(scale = …)`,不含任何 alpha
 * (compose animation 1.12.1 `EnterExitTransition.kt:447`);根级要的「虚化」必须显式
 * `scaleOut(...) + fadeOut()` 相加(`ExitTransition.plus` 见同文件 `:212`)。
 * 仓库内也**没有**模糊原语(`grep renderEffect|BlurEffect` 在动画源码中零命中),
 * 故「虚化」一律以透明度近似。
 *
 * @param predictiveBackAnimation 来自 `settings.predictiveBackAnimation` —— 只控制
 *   **手势中是否跟手**,不控制返回有没有动画(见上)。
 * @param style 该 NavHost 所处的层级,[NavTransitionStyle.Step] 或 [NavTransitionStyle.Page]。
 */
@Composable
fun PixFoldNavHost(
    navController: NavHostController,
    startDestination: Any,
    predictiveBackAnimation: Boolean,
    style: NavTransitionStyle,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popExitTransition = { style.exitTransition() },
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = {
            if (predictiveBackAnimation) style.exitTransition() else ExitTransition.None
        },
        builder = builder,
    )
}

/**
 * 某一档位下「被按在上层的页面」的退出转场。手势跟手与普通返回共用,只由 [style] 决定形态。
 */
private fun NavTransitionStyle.exitTransition(): ExitTransition = when (this) {
    // 步骤级:只淡出,不缩放 —— 上级页原样静止地露出来(用户规格)。
    NavTransitionStyle.Step -> fadeOut()
    // 根级:虚化 + 缩小。0.90 沿用迁移前 PredictiveBackContainer 的幅度。
    NavTransitionStyle.Page -> scaleOut(targetScale = PREDICTIVE_MIN_SCALE) + fadeOut()
}

/**
 * 每个导航目的地的不透明底板。
 *
 * **为什么必须有**:预测性返回时 `NavHost` 把目标页画在**下层**
 * (zIndex = `initialZIndex - 1f`),而上层页面缩到 0.9 —— 两者的内容区大幅重叠。
 * 各页面本身**没有**根级不透明背景(全仓库只有 `ImagePreview` 自带黑底),
 * 于是缩小中的上层文字会与下层文字叠在一起,视觉糊成一团。
 * 给每个目的地套一层 M3 背景色底板后,缩小中的页面是一张**实心卡片**,
 * 盖住下面的目标页 —— 这正是迁移前 `PredictiveBackContainer` 里那层
 * `Box(fillMaxSize().background(colorScheme.background))` 的作用,不能丢。
 */
@Composable
fun DestinationSurface(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/** 便于书写转场 lambda 的类型别名(仅可读性)。 */
internal typealias NavTransitionScope = AnimatedContentTransitionScope<NavBackStackEntry>
