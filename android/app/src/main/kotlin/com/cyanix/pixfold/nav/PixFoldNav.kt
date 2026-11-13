package com.cyanix.pixfold.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlin.reflect.KClass

/**
 * 预测性返回手势中,被按在后面的页面缩小到的比例。
 *
 * 与迁移前 `PredictiveBackContainer.MIN_SCALE = 0.90f` 保持一致 —— 用户在真机上
 * 量化核对过这个幅度(2026-09-21:分隔线 y 346→418、跨度 1263→1178)。
 */
const val PREDICTIVE_MIN_SCALE = 0.90f

/**
 * 返回转场的**层级风格**。
 *
 * 用户给了两条分开的规格(2026-09-22):
 * - 工作流内部(带步骤的容器)→「拖动越多当前页越透明,露出上级页面」;
 * - 大页面切换(根级)→「当前页面虚化缩小」。
 *
 * 故同一条策略需要两个档位,由各 NavDisplay 声明自己是哪一档。
 */
enum class NavTransitionStyle {
    /** 工作流内部:无缩放,只淡出 —— 上级页原样静止露出。 */
    Step,

    /** 根级大页面:淡出 + 缩到 [PREDICTIVE_MIN_SCALE]。 */
    Page,
}

/**
 * 应用统一的导航宿主(Navigation3 [NavDisplay]):转场策略只在这里定义一处。
 *
 * ## 转场策略
 *
 * - `transitionSpec` = 两侧都 `None`:**前进保持瞬切**。迁移前的导航是
 *   `when (screen)` 直接换页,前进本来就没有动画;库默认是淡入淡出,若照用,
 *   每次进工作流、每次切 Tab 都会多出一次动画。用户 2026-09-22 明确裁决
 *   「只做返回/手势,进入保持瞬切」。
 * - `popTransitionSpec` 的进入侧 = `None`:后退时目标页应当**静止地被揭示**,
 *   由上层页面自己动。
 * - 预测性返回的进入侧也恒为 `None`:手势中目标页必须**静止地**待在后面,
 *   这本身就是 HANGOFF §9.1 第 3 步「目标页显示在背后」的全部要求。
 * - 上层页面的动画按 [style] 分档(见 [NavTransitionStyle]),手势跟手与普通返回用
 *   **同一套原语**,区别只在:手势由手指的 `progress` 直接驱动,普通返回到不了手指,
 *   由动画自己跑完。
 * - 开关 [predictiveBackAnimation] 关 → 手势中页面**纹丝不动**(`None`),松手后仍由
 *   `popTransitionSpec` 补上动画。这就是用户裁决的 (b) 案:「关掉手势跟随但**不硬切**」。
 *
 * ## 为什么 `scaleOut` 要 `+ fadeOut`
 *
 * `scaleOut` 的产物**只有** `TransitionData(scale = …)`,不含任何 alpha;根级要的
 * 「虚化」必须显式 `scaleOut(...) + fadeOut()` 相加。仓库内也**没有**模糊原语
 * (`grep renderEffect|BlurEffect` 在动画源码中零命中),故「虚化」一律以透明度近似。
 *
 * ## 为什么 entryProvider 要绕一层 rememberUpdatedState
 *
 * NavDisplay 用 `backStack` 的 equals 缓存 NavEntry,`entryProvider` 不参与 key ⇒
 * 它只在栈变化时被调用一次。若直接按 builder 生成内容,内容 lambda 会把当时的设置 /
 * 工作区状态一并捕获,后续状态变化永远进不去(实测:工作区已 Ready,首页仍停在
 * 「选择文件夹」的禁用态)。故把最新 builder 放进 [rememberUpdatedState],让 NavEntry
 * 内容每次重组时重新 apply 它,取到当下的捕获。
 *
 * @param predictiveBackAnimation 来自 `settings.predictiveBackAnimation` —— 只控制
 *   **手势中是否跟手**,不控制返回有没有动画(见上)。
 * @param style 该 NavDisplay 所处的层级,[NavTransitionStyle.Step] 或 [NavTransitionStyle.Page]。
 */
@Composable
fun PixFoldNavHost(
    backStack: NavBackStack<NavKey>,
    predictiveBackAnimation: Boolean,
    style: NavTransitionStyle,
    modifier: Modifier = Modifier,
    builder: PixFoldNavGraphBuilder.() -> Unit,
) {
    // NavDisplay 内部用 backStack 的 equals 缓存 NavEntry(entryProvider 不参与 key),
    // 因此 entryProvider 只会在栈变化时被调用一次。若在这里直接按 builder 生成内容,
    // 内容 lambda 会把当时的设置/工作区状态一并捕获,后续状态变化永远进不去。
    // 用 rememberUpdatedState 持有最新 builder,让 NavEntry 内容每次重组时重新读取它。
    val latestBuilder = rememberUpdatedState(builder)
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        onBack = { backStack.popOne() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        transitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) },
        popTransitionSpec = { ContentTransform(EnterTransition.None, style.exitTransition()) },
        predictivePopTransitionSpec = { _ ->
            ContentTransform(
                EnterTransition.None,
                if (predictiveBackAnimation) style.exitTransition() else ExitTransition.None,
            )
        },
        entryProvider = { key ->
            NavEntry(
                key = key,
                content = { k ->
                    val graph = PixFoldNavGraphBuilder().also { latestBuilder.value(it) }
                    val content = graph.entries[k::class]
                        ?: error("No destination registered for ${k::class.qualifiedName}")
                    content(k)
                },
            )
        },
    )
}

/** 与旧 navigation-compose 的 `NavGraphBuilder.composable<T>` 同形的轻量注册表。 */
class PixFoldNavGraphBuilder internal constructor() {
    @PublishedApi
    internal val entries = mutableMapOf<KClass<out NavKey>, @Composable (NavKey) -> Unit>()

    inline fun <reified T : NavKey> composable(noinline content: @Composable (T) -> Unit) {
        entries[T::class] = { key -> content(key as T) }
    }
}

/**
 * 某一档位下「被按在上层的页面」的退出转场。手势跟手与普通返回共用,只由 [style] 决定形态。
 */
private fun NavTransitionStyle.exitTransition(): ExitTransition = when (this) {
    NavTransitionStyle.Step -> fadeOut()
    NavTransitionStyle.Page -> scaleOut(targetScale = PREDICTIVE_MIN_SCALE) + fadeOut()
}

/**
 * 每个导航目的地的不透明底板。
 *
 * **为什么必须有**:预测性返回时目标页画在**下层**,而上层页面缩到 0.90 ——
 * 两者的内容区大幅重叠。各页面本身**没有**根级不透明背景(全仓库只有
 * `ImagePreview` 自带黑底),于是缩小中的上层文字会与下层文字叠在一起,视觉糊成一团。
 * 给每个目的地套一层 M3 背景色底板后,缩小中的页面是一张**实心卡片**,盖住目标页 ——
 * 这正是迁移前 `PredictiveBackContainer` 里那层背景 Box 的作用,不能丢。
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
