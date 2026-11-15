package com.cyanix.pixfold.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.ui.NavDisplay
import kotlin.reflect.KClass

/**
 * NavEntry 元数据键:该 entry 对应的路由类。
 *
 * Navigation3 的 [Scene.key] 是**场景标识**(实际值是 NavEntry.contentKey 字符串),
 * 不是路由 NavKey;用它与根目的地比较永远落空。路由类由 [PixFoldNavHost] 生成
 * NavEntry 时写进 metadata,Scene 转场 lambda 从这里取。
 */
const val ROUTE_CLASS_METADATA = "pixfold.routeClass"

/**
 * 返回转场的**层级风格**。
 *
 * 用户 2026-09-22 曾分两档:工作流内部「拖动越多当前页越透明」、根级「当前页面虚化缩小」。
 * 2026-11-15 用户再次改判根级形态:设置→首页**横向滑出**,工作流→首页**缩向对应卡片中心**。
 * 档位保留是为了按层级继续调形,不必再引入第二套导航宿主。
 */
enum class NavTransitionStyle {
    /** 工作流内部:无缩放,只淡出 —— 上级页原样静止露出。 */
    Step,

    /** 根级大页面的默认形态:只淡出;具体路线可由 [PixFoldNavHost] 的 popTransition 覆写。 */
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
 * - 默认配方的进入侧为 `None`:后退时目标页**静止地**被揭示,由上层页面自己动。
 *   例外是根级 [popTransition] 覆写的设置→首页:目标页会从反侧滑入并淡入,与上层页
 *   形成方向一致的整页交换。
 * - 上层页面的默认动画按 [style] 分档(见 [NavTransitionStyle]);根级可用 [popTransition]
 *   按源路由覆写成横向滑动或缩向首页卡片。手势跟手与普通返回用
 *   **同一套原语**,区别只在:手势由手指的 `progress` 直接驱动,普通返回到不了手指,
 *   由动画自己跑完。
 * - 开关 [predictiveBackAnimation] 关 → 手势中页面**纹丝不动**(`None`),松手后仍由
 *   `popTransitionSpec` 补上动画。这就是用户裁决的 (b) 案:「关掉手势跟随但**不硬切**」。
 *
 * ## 「虚化」以透明度近似
 *
 * 根级 2026-09-22 的旧配方是 `scaleOut(0.90) + fadeOut()` —— `scaleOut` 的产物
 * **只有** scale、不含 alpha,必须显式叠加 `fadeOut`。2026-11-15 用户改判:设置→首页
 * 改横向滑出,工作流→首页缩向对应卡片中心(由 [PixFoldNavHost] 的 popTransition 覆写);
 * 其余根级页面与 [NavTransitionStyle.Page] 默认仍只 `fadeOut()`。仓库内没有模糊原语
 * (`grep renderEffect|BlurEffect` 在动画源码中零命中),故「虚化」仍以透明度近似。
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
 * @param popTransition 根级可选覆写。lambda 的 receiver 带 `initialState`/`targetState`,
 *   并收到 NavDisplay 的实际尺寸与 `swipeEdge`(0=左边缘,1=右边缘;普通返回键传 0);
 *   调用方按源路由返回不同 [ContentTransform];为 null 时走 [style] 的默认配方。
 */
@Composable
fun PixFoldNavHost(
    backStack: NavBackStack<NavKey>,
    predictiveBackAnimation: Boolean,
    style: NavTransitionStyle,
    modifier: Modifier = Modifier,
    popTransition: (AnimatedContentTransitionScope<Scene<NavKey>>.(IntSize, Int) -> ContentTransform)? = null,
    builder: PixFoldNavGraphBuilder.() -> Unit,
) {
    // NavDisplay 内部用 backStack 的 equals 缓存 NavEntry(entryProvider 不参与 key),
    // 因此 entryProvider 只会在栈变化时被调用一次。若在这里直接按 builder 生成内容,
    // 内容 lambda 会把当时的设置/工作区状态一并捕获,后续状态变化永远进不去。
    // 用 rememberUpdatedState 持有最新 builder,让 NavEntry 内容每次重组时重新读取它。
    val latestBuilder = rememberUpdatedState(builder)
    // NavDisplay 的实际尺寸:根级转场 lambda 的既有契约(设置页滑动、预测性返回)要用。
    val hostSize = remember { mutableStateOf(IntSize.Zero) }
    NavDisplay(
        backStack = backStack,
        modifier = modifier.fillMaxSize().onGloballyPositioned { hostSize.value = it.size },
        onBack = { backStack.popOne() },
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        transitionSpec = { ContentTransform(EnterTransition.None, ExitTransition.None) },
        popTransitionSpec = {
            popTransition?.invoke(this, hostSize.value, SWIPE_EDGE_LEFT)
                ?: ContentTransform(EnterTransition.None, style.exitTransition())
        },
        predictivePopTransitionSpec = { swipeEdge ->
            if (predictiveBackAnimation) {
                popTransition?.invoke(this, hostSize.value, swipeEdge)
                    ?: ContentTransform(EnterTransition.None, style.exitTransition())
            } else {
                ContentTransform(EnterTransition.None, ExitTransition.None)
            }
        },
        entryProvider = { key ->
            NavEntry(
                key = key,
                metadata = mapOf(ROUTE_CLASS_METADATA to key::class),
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

/** 工作流→首页的缩放淡化曲线:从整页等比缩到 [targetScale],同时线性淡出。 */
internal data class WorkflowShrinkMotion(
    val targetScale: Float,
    val durationMillis: Int,
    val fadeDelayMillis: Int,
    val fadeDurationMillis: Int,
) {
    fun frameAt(progress: Float): WorkflowShrinkFrame {
        val p = progress.coerceIn(0f, 1f)
        return WorkflowShrinkFrame(scale = 1f + (targetScale - 1f) * p, alpha = 1f - p)
    }
}

/** [WorkflowShrinkMotion.frameAt] 在某个进度上的缩放与透明度。 */
internal data class WorkflowShrinkFrame(val scale: Float, val alpha: Float)

/** 整页等比缩放 + 全程同步线性淡出,不改变内容宽高比,文字不被拉伸。 */
internal val WORKFLOW_SHRINK_MOTION = WorkflowShrinkMotion(
    targetScale = 1f / 3f,
    durationMillis = 380,
    fadeDelayMillis = 0,
    fadeDurationMillis = 380,
)

/**
 * 工作流 → 首页:整页以首页对应卡片中心为轴等比缩小,同时线性淡出。
 *
 * 用户 2026-11-15 明确要求「整体等比缩放、文字保持清晰不变形」,故不能用
 * `ResizeMode.scaleToBounds(FillBounds)` 把内容强行拉满目标 bounds —— 那会把
 * 文字横向拉宽。`scaleOut` 只对整页做统一 scale,宽高比不变。
 */
internal fun AnimatedContentTransitionScope<Scene<NavKey>>.workflowShrinkPop(
    cardBounds: Rect?,
    hostSize: IntSize,
): ContentTransform {
    val motion = WORKFLOW_SHRINK_MOTION
    val origin = if (cardBounds == null || hostSize.width == 0 || hostSize.height == 0) {
        TransformOrigin.Center
    } else {
        TransformOrigin(
            pivotFractionX = cardBounds.center.x / hostSize.width,
            pivotFractionY = cardBounds.center.y / hostSize.height,
        )
    }
    return ContentTransform(
        targetContentEnter = EnterTransition.None,
        initialContentExit = scaleOut(
            targetScale = motion.targetScale,
            animationSpec = tween(durationMillis = motion.durationMillis, easing = LinearEasing),
            transformOrigin = origin,
        ) + fadeOut(
            animationSpec = tween(
                durationMillis = motion.fadeDurationMillis,
                delayMillis = motion.fadeDelayMillis,
                easing = LinearEasing,
            ),
        ),
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

private const val SWIPE_EDGE_LEFT = 0
private const val SWIPE_EDGE_RIGHT = 1
private const val ROOT_SLIDE_DURATION_MS = 320

/**
 * 设置 → 首页:上层页沿滑动方向滑出并淡出,首页从反侧轻微回位并淡入。
 *
 * `swipeEdge` 为 Navigation3 预测性返回给出的边缘:0=左边缘(手指向右),
 * 设置页向右出、首页从左回;1=右边缘(手指向左),设置页向左出、首页从右回。
 * 普通返回键没有边缘语义,调用方传 [SWIPE_EDGE_LEFT],即固定按右出。
 */
fun settingsSlidePop(swipeEdge: Int = SWIPE_EDGE_LEFT): ContentTransform {
    val direction = if (swipeEdge == SWIPE_EDGE_RIGHT) -1 else 1
    return ContentTransform(
        targetContentEnter = slideInHorizontally(
            animationSpec = tween(durationMillis = ROOT_SLIDE_DURATION_MS),
            initialOffsetX = { fullWidth -> direction * -fullWidth / 5 },
        ) + fadeIn(animationSpec = tween(durationMillis = ROOT_SLIDE_DURATION_MS)),
        initialContentExit = slideOutHorizontally(
            animationSpec = tween(durationMillis = ROOT_SLIDE_DURATION_MS),
            targetOffsetX = { fullWidth -> direction * fullWidth },
        ) + fadeOut(animationSpec = tween(durationMillis = ROOT_SLIDE_DURATION_MS)),
    )
}

/** 其余根级页面(许可、预览等)保持原来的只淡出。 */
fun rootFadePop(): ContentTransform = ContentTransform(EnterTransition.None, fadeOut())

/**
 * 某一档位下「被按在上层的页面」的退出转场。手势跟手与普通返回共用,只由 [style] 决定形态。
 */
private fun NavTransitionStyle.exitTransition(): ExitTransition = when (this) {
    NavTransitionStyle.Step, NavTransitionStyle.Page -> fadeOut()
}

/**
 * 每个导航目的地的不透明底板。
 *
 * **为什么必须有**:预测性返回时目标页画在**下层**,上层页面正逐渐淡出。
 * 各页面本身**没有**根级不透明背景(全仓库只有 `ImagePreview` 自带黑底),
 * 若上层透明,淡出过程中两层文字会叠在一起,视觉糊成一团。给每个目的地套一层
 * M3 背景色底板后,淡出的是一个**实心页面**,盖住目标页直到彻底消失 ——
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