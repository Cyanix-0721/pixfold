package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全站统一弹窗的**背景模糊控制器**。
 *
 * ## 为什么需要它
 *
 * Compose 的 `Dialog` 是独立 window，**没有**「模糊自己下面那一层」的能力：
 * 它拿不到宿主窗口的内容。想让背景模糊，只有一条路 —— 由宿主（页面根容器）
 * 施加 `Modifier.blur(...)`，弹窗再去通知宿主「我开了 / 我关了」。
 *
 * 本类就是这个通知通道。用**计数器**而不是布尔值：弹窗可以叠加
 * （确认框里再弹一个错误框），布尔值会被内层的关闭动作提前置回 false，
 * 外层还开着而背景已经清晰了。
 *
 * ## 为什么不用 CompositionLocal 直接给布尔值
 *
 * 布尔值要写在宿主层，而弹窗是 `Dialog` 独立窗口 —— 它读到的是同一个
 * CompositionLocal，但它**改不了**宿主的状态。控制器是可变对象，两边都能读，
 * 弹窗那侧能改。
 */
@Stable
class DialogBlurController {

    /**
     * 当前打开的弹窗数量。
     *
     * 暴露成可观察状态：宿主读它来决定要不要模糊，读的是快照状态，
     * 所以 acquire/release 会正常触发重组。
     */
    var openCount by mutableIntStateOf(0)
        private set

    /** 是否有弹窗打开 —— 宿主侧唯一需要的判据。 */
    val hasOpenDialog: Boolean get() = openCount > 0

    internal fun acquire() {
        openCount++
    }

    internal fun release() {
        // 防御：正常配对时不会走到 0 以下。若真走到，说明有人没走 DisposableEffect，
        // 与其静默把背景永久糊住，不如钳到 0。
        if (openCount > 0) openCount--
    }
}

/**
 * 弹窗模糊控制器的 CompositionLocal。
 *
 * 默认值给一个**没人用的孤儿控制器**：这样在预览、单元测试里单独渲染某个弹窗时
 * 不需要额外包一层 provider —— 它 acquire/release 一个没人观察的对象，
 * 无副作用。
 */
val LocalDialogBlur = compositionLocalOf { DialogBlurController() }

/**
 * 包住页面内容：有弹窗打开时模糊并压暗背景。
 *
 * 放在 NavHost 外层即可覆盖所有页面 —— 弹窗是独立窗口，本来就盖在最上面，
 * 这一层只管「下面那层长什么样」。
 *
 * @param blurRadius 模糊半径。12dp 在 361dp 宽的屏幕上刚好「看得出形状、读不出字」，
 *   再大就只剩色块了。
 */
@Composable
fun DialogBlurHost(
    controller: DialogBlurController,
    modifier: Modifier = Modifier,
    blurRadius: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalDialogBlur provides controller) {
        // ⚠️ **树结构必须恒定**，只能让修饰符变。
        //
        // 第一版写成 `if (!hasOpenDialog) { content() } else { Box { Box { content() } } }`，
        // 结果是「弹窗一打开就自己消失」：`hasOpenDialog` 变 true 时 `content()` 的
        // 调用位置从 slot 1 跳到 slot 3，Compose 视为不同子树 ⇒ **整个 NavHost 被
        // 销毁重建** ⇒ 弹窗的 `showDialog`/`open` 状态随 `remember` 一起丢失。
        // 两条既有测试（`AppLanguageTest` 的语言选择、`AppNavigationTest` 的预览重命名）
        // 正是这么红的。
        //
        // 现在：Box 恒定存在，模糊与压暗通过「修饰符是否为空 / alpha 是否为 0」表达。
        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = if (controller.hasOpenDialog) {
                    Modifier.fillMaxSize().blur(blurRadius)
                } else {
                    Modifier.fillMaxSize()
                },
            ) {
                content()
            }
            // 轻微压暗：只靠模糊时，浅色页面上的白底弹窗边界仍不清楚。
            // 用透明色而不是「不渲染这一层」—— 同样是为了结构恒定。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (controller.hasOpenDialog) {
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.12f)
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                    ),
            )
        }
    }
}

/**
 * 只做一件事：告诉宿主「有一个弹窗开着」，从而让背景模糊生效。
 *
 * ## 为什么需要这个「手动注册」的入口
 *
 * 工程里已有 11 处弹窗，其中若干处（方案编辑器、重命名输入框、下拉选择器、
 * 撤销范围）的**内容结构差异很大**，硬塞进 [PixFoldDialog] 的统一槽位会削掉
 * 它们各自的布局（输入框宽度、滚动容器、分段标题…），而且会牵动几十条既有
 * UI 断言。
 *
 * 所以统一分成两层：
 *  · **外观统一** —— 同构的「标题 + 正文 + 确认/取消」走 [PixFoldConfirmDialog]；
 *  · **效果统一** —— 所有弹窗（含结构特殊的）在函数体开头调一次本函数，
 *    就获得背景高斯模糊。
 *
 * 它不产生任何布局节点，可以放在 Composable 的任意位置。
 *
 * ⚠️ 必须在**弹窗真正进入组合时**调用（即 `if (show) { ... }` 分支内），
 * 不能放在函数最外层 —— 那会让背景在弹窗关闭时仍然模糊。
 */
@Composable
fun DialogBlurRegistration() {
    val blur = LocalDialogBlur.current
    DisposableEffect(Unit) {
        blur.acquire()
        onDispose { blur.release() }
    }
}

/** 统一弹窗的容器 tag —— 所有走本抽象的弹窗都带它，便于统一断言。 */
const val TAG_PIXFOLD_DIALOG = "pixfold-dialog"

// ---------------------------------------------------------------------------
// 共同父类
// ---------------------------------------------------------------------------

/**
 * 全站弹窗的**唯一底层** —— 底下所有弹窗最终都委托到这里。
 *
 * ## 「共同父类」在 Compose 里长什么样
 *
 * Compose 没有 UI 继承。要让各处共享同一份外观，只有一条路：**委托到同一个
 * Composable**。本函数就是那个「父类」——
 *
 * | 归它管（全工程只写一次） | 归调用方管 |
 * | --- | --- |
 * | `Dialog` 窗口 + `DialogProperties` | 标题长什么样 |
 * | `Surface` 圆角 / 底色 / 高度 | 正文内容 |
 * | 24dp 内边距、三段之间的间距 | 按钮怎么排 |
 * | 正文可滚动 | — |
 * | 背景模糊注册 | — |
 *
 * 上层三个便捷外壳（[PixFoldDialog] / [PixFoldInfoDialog] /
 * [PixFoldConfirmDialog] / [PixFoldAlertDialog]）都只是「把参数翻译成三个槽」。
 *
 * ## 为什么 [buttons] 是任意槽而不是 confirm/dismiss 两槽
 *
 * Material3 的 `AlertDialog` 只给两个槽，摆不下「三颗全宽竖排」这种形状
 * （撤销范围对话框就是：安全档 / 危险档 / 取消，且取消必须在最后）。
 * 所以这里 [buttons] 收 `ColumnScope` 的任意内容，由调用方决定横排还是竖排 ——
 * **变的只有按钮怎么摆，外观与模糊仍然统一**。
 *
 * ## ⚠️ 树结构必须恒定
 *
 * [content] 与 [buttons] 的调用层级**不能**随参数增减嵌套层数。早先的模糊宿主
 * 就因为参数变化时切换了 `content()` 的调用层级，被 Compose 判为不同子树而
 * 整棵销毁重建 —— 表现是「弹窗一打开就自己消失」。
 *
 * @param title 标题槽；`null` 时不占位（连间距都不留）
 * @param buttons 按钮区槽，**必填**；固定在正文下方
 * @param contentScrollable 正文是否由本函数套 `verticalScroll`。默认 `true`；
 *   正文自己已是 `LazyColumn` / 已自行滚动时必须传 `false`
 * @param maxWidth 最大宽度。默认 320dp，内容需要更宽时可放大
 * @param tag 容器 testTag；传 `null` 不加 tag
 */
@Composable
fun PixFoldDialogShell(
    onDismissRequest: () -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = DialogMaxWidth,
    dismissOnOutside: Boolean = true,
    tag: String? = TAG_PIXFOLD_DIALOG,
    contentScrollable: Boolean = true,
    title: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 模糊注册放在所有弹窗的唯一出入口：以前每层各自调一次，
    // 新增一个弹窗就多一个「忘了调」的机会。
    DialogBlurRegistration()

    Dialog(
        onDismissRequest = { if (dismissOnOutside) onDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = dismissOnOutside,
            dismissOnClickOutside = dismissOnOutside,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = modifier
                .widthIn(max = maxWidth)
                .fillMaxWidth(DialogFillFraction)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
            shape = DialogShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = DialogElevation,
            shadowElevation = DialogElevation,
        ) {
            Column(modifier = Modifier.padding(DialogPadding)) {
                if (title != null) {
                    title()
                    Spacer(Modifier.height(DialogTitleGap))
                }
                // ⚠️ 嵌套滚动会崩：内容本身是 `LazyColumn` 时不能再包 `verticalScroll`
                // （实测 `IllegalStateException: Vertically scrollable component was
                // measured with an infinity maximum height constraints`）。设置页的
                // 单选弹窗正是这种情况，故给调用方一个关闭开关。
                val contentModifier = if (contentScrollable) {
                    Modifier
                        .weight(weight = 1f, fill = false)
                        .verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
                Column(modifier = contentModifier, content = content)
                Spacer(Modifier.height(DialogButtonsGap))
                // 固定调用层级：`buttons` 总在同一位置求值，不随按钮数量增减。
                Column(content = buttons)
            }
        }
    }
}

/** 弹窗最大宽度 —— 320dp 在 361dp 屏上两侧各留 20dp。 */
val DialogMaxWidth: Dp = 320.dp

/** 弹窗占屏宽的比例；窄屏上 `widthIn` 先命中，所以两者不冲突。 */
private const val DialogFillFraction = 0.92f

/** 弹窗圆角 —— 28dp 与 Material3 的 `extraLarge` 一致。 */
private val DialogShape = RoundedCornerShape(28.dp)

/** 弹窗表面高度。tonal 与 shadow 同值，避免叠出两道边。 */
private val DialogElevation: Dp = 6.dp

/** 弹窗内边距。 */
private val DialogPadding: Dp = 24.dp

/** 标题与正文之间。 */
private val DialogTitleGap: Dp = 16.dp

/** 正文与按钮之间。 */
private val DialogButtonsGap: Dp = 24.dp

/**
 * 全站统一弹窗。
 *
 * ## 它统一了什么
 *
 * 1. **背景高斯模糊 + 压暗**（通过 [LocalDialogBlur] 通知宿主）；
 * 2. **外观**：28dp 圆角、`surfaceContainerHigh` 底色、24dp 内边距、最大宽 320dp；
 * 3. **结构**：标题 / 正文 / 按钮区三段，正文可滚动（长文案不会把按钮挤出屏幕）；
 * 4. **行为**：点外部是否关闭、返回键是否关闭，由 [dismissOnOutside] 决定。
 *
 * ## 为什么不直接用 `AlertDialog`
 *
 * `AlertDialog` 是 Material3 的实现，外观锁死在自己的 token 体系里，且**无法**
 * 通知宿主模糊背景（它不暴露「我开了」的时机）。逐处给 11 个 `AlertDialog`
 * 套样式会重复 11 次，且下次调整要改 11 个地方 —— 这正是本次抽象的由来。
 *
 * ## 正文为什么可滚动
 *
 * 「撤销范围」这类对话框的正文会列出整批操作，几十行时按钮会被顶出屏幕。
 * 高度上限取屏幕的 60%，超过就滚。
 *
 * @param title 标题；`null` 时不占位置
 * @param confirmButton 主按钮（必填 —— 没有主按钮的弹窗只该是纯展示，那用别的组件）
 * @param dismissButton 次按钮；`null` 时不占位置
 */
@Composable
fun PixFoldDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    PixFoldDialogShell(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnOutside = dismissOnOutside,
        title = title?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        buttons = {
            // 右对齐一行：取消在左、主按钮在右。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dismissButton != null) {
                    dismissButton()
                    Spacer(Modifier.widthIn(min = 8.dp))
                }
                confirmButton()
            }
        },
        content = content,
    )
}

/**
 * 纯展示型弹窗（只有「知道了」一个按钮）。
 *
 * 存在的理由：`PixFoldDialog` 要求显式给 `confirmButton`，而「告知 + 知道了」
 * 这个组合在工程里有五六处，每处都写一遍 `TextButton { Text(知道了) }` 是噪音。
 */
@Composable
fun PixFoldInfoDialog(
    onDismissRequest: () -> Unit,
    confirmText: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    dismissOnOutside: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    PixFoldDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        dismissOnOutside = dismissOnOutside,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                Text(confirmText)
            }
        },
        content = content,
    )
}

/**
 * 「标题 + 正文 + 确认/取消」同构弹窗。
 *
 * 工程里这个形状出现 7 次（重置全部、清空命名、删除源目录、撤销范围…）。
 * 每次手写一遍 `confirmButton = { TextButton { ... } }` 是噪音，且容易出现
 * 「有一处忘了给取消按钮」这类不一致。
 *
 * @param confirmText 主按钮文案；危险动作应配合 `isDangerous = true`
 * @param isDangerous `true` 时主按钮用错误色 —— 删除类操作不能长得跟「确定」一样
 */
@Composable
fun PixFoldConfirmDialog(
    onDismissRequest: () -> Unit,
    title: String,
    body: String,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String? = null,
    isDangerous: Boolean = false,
    confirmTag: String? = null,
    dismissOnOutside: Boolean = true,
) {
    PixFoldDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        title = title,
        dismissOnOutside = dismissOnOutside,
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = onConfirm,
                modifier = if (confirmTag != null) Modifier.testTag(confirmTag) else Modifier,
            ) {
                Text(
                    text = confirmText,
                    color = if (isDangerous) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
            }
        },
        dismissButton = dismissText?.let {
            {
                androidx.compose.material3.TextButton(onClick = onDismissRequest) {
                    Text(it)
                }
            }
        },
    ) {
        DialogBody(body)
    }
}

/**
 * 兼容层：保留「title / text / confirm / dismiss」四槽的调用形状，
 * 但**底层走 [PixFoldDialogShell]** —— 外观不再由 Material3 的 `AlertDialog` 决定。
 *
 * ## 为什么现在改成委托 Shell
 *
 * 早先本函数直接包 `androidx.compose.material3.AlertDialog`，只统一了圆角与底色。
 * 结果是**两套外观并存**：走 [PixFoldDialog] 的弹窗和走本函数的弹窗，标题字号、
 * 内边距、按钮区排布都不一样（用户 2026-10-20 要求「所有弹窗都是这个效果」。
 * 「这个效果」只能有一个来源）。
 *
 * 现在外观只有一份：`PixFoldDialogShell`。本函数只负责把四个槽翻译成
 * 「标题 / 正文 / 右对齐按钮行」。
 *
 * @param title 标题槽；`null` 时不占位
 * @param text 正文槽；`null` 时不占位
 */
@Composable
fun PixFoldAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    dismissButton: (@Composable () -> Unit)? = null,
    dismissOnOutside: Boolean = true,
    textScrollable: Boolean = true,
) {
    PixFoldDialogShell(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        dismissOnOutside = dismissOnOutside,
        contentScrollable = textScrollable,
        title = title,
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dismissButton != null) {
                    dismissButton()
                    Spacer(Modifier.widthIn(min = 8.dp))
                }
                confirmButton()
            }
        },
    ) {
        if (text != null) text()
    }
}

/** 弹窗正文的统一样式（bodyMedium + onSurfaceVariant）。 */
@Composable
fun DialogBody(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 按钮区内边距常量 —— 供需要自定义按钮排布时对齐用。 */
val DialogButtonPadding: PaddingValues = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
