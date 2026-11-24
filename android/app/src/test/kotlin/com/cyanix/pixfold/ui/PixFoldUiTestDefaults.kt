package com.cyanix.pixfold.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import kotlin.math.max
import kotlin.test.assertTrue

/**
 * 纯 JVM `@GraphicsMode(GraphicsMode.Mode.NATIVE)` UI 测试共享底座（无 ADB Phase 1）。
 *
 * 几何参数与真机 PJX110（1264×2780px @ 560dpi = 361dp × 794dp）对齐；
 * 语言限定符必须位于 Robolectric `qualifiers` 首位（见 `ResourceFallbackTest` 注释）。
 */
object PixFoldUiTestDefaults {
    const val QUALIFIER_ZH_PHONE = "zh-rCN-w361dp-h794dp-560dpi"
    const val QUALIFIER_EN_PHONE = "en-w361dp-h794dp-560dpi"

    val VIEWPORT_WIDTH_DP: Dp = 361.dp
    val VIEWPORT_HEIGHT_DP: Dp = 794.dp
    val MIN_TOUCH_TARGET_DP: Dp = 48.dp

    /** 3.5px/dp 换算下的亚像素容差（1px ≈ 0.29dp，取 0.5dp 吸收舍入误差、不放过真实溢出）。 */
    val SUBPIXEL_TOLERANCE_DP: Dp = 0.5.dp
}

/**
 * 原生渲染测试宿主：复刻 `PixFoldApp` 真实层级 `PixFoldTheme -> Surface(background)`，
 * 避免 Robolectric 窗口默认 `#FAFAFA` 底色造成视觉假绿，并支持注入 `fontScale` 验证字号缩放。
 */
@Composable
fun PixFoldNativeTestHost(
    settings: PixFoldSettings = PixFoldSettings(
        themeMode = ThemeMode.Light,
        accent = AccentChoice.Default,
    ),
    fontScale: Float = 1.0f,
    content: @Composable () -> Unit,
) {
    val currentDensity = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = currentDensity.density,
            fontScale = fontScale,
        ),
    ) {
        PixFoldTheme(settings = settings) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }
}

/**
 * 断言单个节点未水平溢出视口且具有非零尺寸。
 *
 * 使用 `getUnclippedBoundsInRoot()` 而非被裁剪的 `getBoundsInRoot()`，
 * 以便捕获子项实际越出父容器右边缘的溢出缺陷。
 */
fun assertFitsViewport(
    node: SemanticsNodeInteraction,
    maxWidthDp: Dp = PixFoldUiTestDefaults.VIEWPORT_WIDTH_DP,
    toleranceDp: Dp = PixFoldUiTestDefaults.SUBPIXEL_TOLERANCE_DP,
    label: String = "node",
) {
    val bounds = node.getUnclippedBoundsInRoot()
    assertRectFitsViewport(bounds, maxWidthDp = maxWidthDp, toleranceDp = toleranceDp, label = label)
}

internal fun assertRectFitsViewport(
    bounds: DpRect,
    maxWidthDp: Dp = PixFoldUiTestDefaults.VIEWPORT_WIDTH_DP,
    toleranceDp: Dp = PixFoldUiTestDefaults.SUBPIXEL_TOLERANCE_DP,
    label: String = "node",
) {
    val width = bounds.right - bounds.left
    val height = bounds.bottom - bounds.top
    assertTrue(
        width > 0.dp && height > 0.dp,
        "$label 必须具有非零尺寸，实测 width=${width.value}dp height=${height.value}dp ($bounds)",
    )
    assertTrue(
        bounds.left >= -toleranceDp,
        "$label 左边界溢出视口: left=${bounds.left.value}dp < 0 ($bounds)",
    )
    assertTrue(
        bounds.right <= maxWidthDp + toleranceDp,
        "$label 右边界溢出视口: right=${bounds.right.value}dp > ${maxWidthDp.value}dp ($bounds)",
    )
}

/**
 * 断言一组同级节点在同一视觉行内不发生水平重叠。
 */
fun assertNoHorizontalOverlap(
    nodes: List<SemanticsNodeInteraction>,
    toleranceDp: Dp = PixFoldUiTestDefaults.SUBPIXEL_TOLERANCE_DP,
) {
    val rects = nodes.mapIndexed { index, interaction ->
        "#$index" to interaction.getUnclippedBoundsInRoot()
    }
    assertRectsNoHorizontalOverlap(rects, toleranceDp = toleranceDp)
}

internal fun assertRectsNoHorizontalOverlap(
    namedRects: List<Pair<String, DpRect>>,
    toleranceDp: Dp = PixFoldUiTestDefaults.SUBPIXEL_TOLERANCE_DP,
) {
    for (i in namedRects.indices) {
        val (nameA, a) = namedRects[i]
        for (j in i + 1 until namedRects.size) {
            val (nameB, b) = namedRects[j]
            val verticalOverlap = a.top < b.bottom - toleranceDp && b.top < a.bottom - toleranceDp
            if (verticalOverlap) {
                val horizontalOverlap = a.left < b.right - toleranceDp && b.left < a.right - toleranceDp
                assertTrue(
                    !horizontalOverlap,
                    "同级节点发生水平重叠: $nameA($a) 与 $nameB($b)",
                )
            }
        }
    }
}

/**
 * 断言一组行内/多行节点全部落在视口宽度内且同级无水平重叠。
 */
fun assertRowsFitViewport(
    nodes: List<SemanticsNodeInteraction>,
    maxWidthDp: Dp = PixFoldUiTestDefaults.VIEWPORT_WIDTH_DP,
    toleranceDp: Dp = PixFoldUiTestDefaults.SUBPIXEL_TOLERANCE_DP,
) {
    val namedRects = nodes.mapIndexed { index, interaction ->
        val bounds = interaction.getUnclippedBoundsInRoot()
        assertRectFitsViewport(
            bounds = bounds,
            maxWidthDp = maxWidthDp,
            toleranceDp = toleranceDp,
            label = "row-item#$index",
        )
        "row-item#$index" to bounds
    }
    assertRectsNoHorizontalOverlap(namedRects, toleranceDp = toleranceDp)
}

/**
 * 遍历语义树（含弹窗/浮层等所有根节点）中全部已放置节点，断言无任何子节点水平溢出 `maxWidthDp`。
 */
fun assertAllNodesFitViewport(
    rule: ComposeTestRule,
    maxWidthDp: Dp = PixFoldUiTestDefaults.VIEWPORT_WIDTH_DP,
    toleranceDp: Dp = PixFoldUiTestDefaults.SUBPIXEL_TOLERANCE_DP,
) {
    val density = rule.density.density
    val roots = rule.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
    assertTrue(roots.isNotEmpty(), "语义树根节点不应为空")
    val violations = mutableListOf<String>()

    fun visit(node: SemanticsNode) {
        if (node.layoutInfo.isPlaced && node.size.width > 0 && node.size.height > 0) {
            val leftDp = node.positionInRoot.x / density
            val rightDp = (node.positionInRoot.x + node.size.width) / density
            val winLeftDp = node.positionInWindow.x / density
            val winRightDp = (node.positionInWindow.x + node.size.width) / density
            if (leftDp < -toleranceDp.value || rightDp > maxWidthDp.value + toleranceDp.value) {
                violations += "${describeNode(node)} root=[${"%.1f".format(leftDp)}..${"%.1f".format(rightDp)}]dp > ${maxWidthDp.value}dp"
            } else if (winLeftDp < -toleranceDp.value || winRightDp > maxWidthDp.value + toleranceDp.value) {
                violations += "${describeNode(node)} window=[${"%.1f".format(winLeftDp)}..${"%.1f".format(winRightDp)}]dp > ${maxWidthDp.value}dp"
            }
        }
        node.children.forEach(::visit)
    }

    roots.forEach(::visit)
    assertTrue(
        violations.isEmpty(),
        "存在水平溢出 ${maxWidthDp.value}dp 视口的节点（共 ${violations.size} 处）:\n" +
            violations.joinToString("\n"),
    )
}

/**
 * 遍历合并语义树中全部可点击节点，按 HANDBOOK §6.5 守卫两条无障碍硬约束：
 * 1. 每个可点击节点的有效触控目标必须 `>= minTouchSize × minTouchSize`（默认 `48.dp × 48.dp`）；
 * 2. 每个纯图标可点击节点（无非空 `Text` 且无 `EditableText`）必须提供非空 `ContentDescription`。
 */
fun assertAccessibleTargets(
    rule: ComposeTestRule,
    minTouchSize: Dp = PixFoldUiTestDefaults.MIN_TOUCH_TARGET_DP,
    toleranceDp: Dp = PixFoldUiTestDefaults.SUBPIXEL_TOLERANCE_DP,
) {
    val density = rule.density.density
    val roots = rule.onAllNodes(isRoot(), useUnmergedTree = false).fetchSemanticsNodes()
    assertTrue(roots.isNotEmpty(), "合并语义树根节点不应为空")

    var clickableCount = 0
    var checkedSizeCount = 0
    val violations = mutableListOf<String>()

    fun visit(node: SemanticsNode) {
        if (node.layoutInfo.isPlaced && SemanticsActions.OnClick in node.config) {
            clickableCount++
            // 仅对未被滚动容器裁切（完整可见）的节点校验 touchBoundsInRoot：
            // 滚动到视口外的节点其 touchBoundsInRoot 会被父滚动容器裁成 0，
            // 会错误回退到 M3 控件未含 minimumInteractiveComponentSize 扩展的视觉尺寸（如 Switch 32dp / IconButton 40dp）。
            val visibleBounds = node.boundsInRoot
            val isFullyVisible = node.size.width > 0 &&
                node.size.height > 0 &&
                visibleBounds.width >= node.size.width - 1f &&
                visibleBounds.height >= node.size.height - 1f

            if (isFullyVisible) {
                checkedSizeCount++
                val layoutWidthDp = node.size.width / density
                val layoutHeightDp = node.size.height / density
                val touchBounds = node.touchBoundsInRoot
                val touchWidthDp = touchBounds.width / density
                val touchHeightDp = touchBounds.height / density
                val effectiveWidthDp = max(layoutWidthDp, touchWidthDp)
                val effectiveHeightDp = max(layoutHeightDp, touchHeightDp)

                val minRequired = minTouchSize.value - toleranceDp.value
                if (effectiveWidthDp < minRequired || effectiveHeightDp < minRequired) {
                    violations += "触控目标小于 ${minTouchSize.value}dp: ${describeNode(node)} " +
                        "size=${"%.1f".format(effectiveWidthDp)}×${"%.1f".format(effectiveHeightDp)}dp"
                }
            }

            // 无障碍描述与滚出视口无关，对所有已放置可点击节点全量检查。
            val hasVisibleText = node.config.getOrNull(SemanticsProperties.Text)
                ?.any { it.text.isNotBlank() } == true
            val isEditableField = SemanticsProperties.EditableText in node.config
            if (!hasVisibleText && !isEditableField) {
                val hasCd = node.config.getOrNull(SemanticsProperties.ContentDescription)
                    ?.any { it.isNotBlank() } == true
                if (!hasCd) {
                    violations += "纯图标/无文字可点击节点缺少 contentDescription: ${describeNode(node)}"
                }
            }
        }
        node.children.forEach(::visit)
    }

    roots.forEach(::visit)
    assertTrue(clickableCount > 0, "页面中未发现任何可点击节点，无法验证无障碍约束")
    assertTrue(checkedSizeCount > 0, "页面中未发现任何完整可见的可点击节点，无法验证触控目标尺寸")
    assertTrue(
        violations.isEmpty(),
        "存在违反 HANDBOOK §6.5 无障碍约束的可点击节点（共 ${violations.size} 处）:\n" +
            violations.joinToString("\n"),
    )
}

private fun describeNode(node: SemanticsNode): String {
    val tag = node.config.getOrNull(SemanticsProperties.TestTag)
    val role = node.config.getOrNull(SemanticsProperties.Role)
    val cd = node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString("/")
    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("/") { it.text }
    return buildString {
        append("Node(id=${node.id}")
        if (!tag.isNullOrBlank()) append(", tag=$tag")
        if (role != null) append(", role=$role")
        if (!cd.isNullOrBlank()) append(", cd=$cd")
        if (!text.isNullOrBlank()) append(", text=${text.take(24)}")
        append(")")
    }
}
