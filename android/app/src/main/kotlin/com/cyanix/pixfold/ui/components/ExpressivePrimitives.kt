package com.cyanix.pixfold.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.cyanix.pixfold.R
import com.cyanix.pixfold.ui.theme.pixFoldCardContainerColor
import com.cyanix.pixfold.ui.theme.pixFoldSegmentedColors
import com.cyanix.pixfold.ui.theme.pixFoldSurfaceBorder

/**
 * M3E 有机形状徽章：将 [androidx.compose.material3.MaterialShapes] 的 [RoundedPolygon]
 * 裁成容器底形，居中渲染 24dp 矢量图标。
 *
 * 仅此处按需 `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`（`material3 1.5.0-rc01` 中
 * `RoundedPolygon.toShape()` 仍带此标记）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PixFoldShapeBadge(
    polygon: RoundedPolygon,
    containerColor: Color,
    contentColor: Color,
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(polygon.toShape())
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun Modifier.pureBlackSegmentedBorder(
    shape: Shape,
    enabled: Boolean,
): Modifier {
    val border = pixFoldSurfaceBorder(enabled = enabled) ?: return this
    return this.border(border, shape)
}

/**
 * M3E 分段列表项（只读 / 展示态）。
 *
 * 统一施加 [pixFoldSegmentedColors]（`surfaceContainerLow`）并在纯黑模式下按 [shapes] 补
 * `1.dp` `outlineVariant` 描边，避免分段卡片融进 `#000000` 背景。
 */
@Composable
fun PixFoldSegmentedItem(
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    colors: ListItemColors = pixFoldSegmentedColors(),
    contentPadding: PaddingValues = ListItemDefaults.ContentPadding,
    content: @Composable () -> Unit,
) {
    SegmentedListItem(
        shapes = shapes,
        modifier = modifier.pureBlackSegmentedBorder(shapes.shape, enabled = enabled),
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * M3E 分段列表项（可点击态）。
 */
@Composable
fun PixFoldSegmentedItem(
    onClick: () -> Unit,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    colors: ListItemColors = pixFoldSegmentedColors(),
    contentPadding: PaddingValues = ListItemDefaults.ContentPadding,
    content: @Composable () -> Unit,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        modifier = modifier.pureBlackSegmentedBorder(shapes.shape, enabled = enabled),
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * M3E 分段列表项（单选 / 选中态）。
 */
@Composable
fun PixFoldSegmentedItem(
    selected: Boolean,
    onClick: () -> Unit,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    colors: ListItemColors = pixFoldSegmentedColors(),
    contentPadding: PaddingValues = ListItemDefaults.ContentPadding,
    content: @Composable () -> Unit,
) {
    val borderShape = if (selected) shapes.selectedShape else shapes.shape
    SegmentedListItem(
        selected = selected,
        onClick = onClick,
        shapes = shapes,
        modifier = modifier.pureBlackSegmentedBorder(borderShape, enabled = enabled),
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * M3E 分段列表项（勾选 / 开关态）。
 */
@Composable
fun PixFoldSegmentedItem(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shapes: ListItemShapes,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    overlineContent: (@Composable () -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    colors: ListItemColors = pixFoldSegmentedColors(),
    contentPadding: PaddingValues = ListItemDefaults.ContentPadding,
    content: @Composable () -> Unit,
) {
    val borderShape = if (checked) shapes.selectedShape else shapes.shape
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = shapes,
        modifier = modifier.pureBlackSegmentedBorder(borderShape, enabled = enabled),
        enabled = enabled,
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        supportingContent = supportingContent,
        onLongClick = onLongClick,
        onLongClickLabel = onLongClickLabel,
        colors = colors,
        contentPadding = contentPadding,
        content = content,
    )
}

/**
 * M3E 紧凑状态胶囊（冲突 / 警告 / 成功 / 卷数计数等）。
 *
 * 使用 [CircleShape] + `labelSmallEmphasized`，可选前置 14dp 矢量图标与描边。
 */
@Composable
fun PixFoldStatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    @DrawableRes iconRes: Int? = null,
    border: BorderStroke? = null,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmallEmphasized,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * M3E 色调分组卡片：`surfaceContainerLow` + `MaterialTheme.shapes.largeIncreased` (20dp)，
 * 纯黑模式下自动通过 [pixFoldSurfaceBorder] 补 `1.dp` `outlineVariant` 描边。
 */
@Composable
fun PixFoldSectionCard(
    modifier: Modifier = Modifier,
    containerColor: Color = pixFoldCardContainerColor(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.largeIncreased,
        color = containerColor,
        border = pixFoldSurfaceBorder(enabled = enabled),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/**
 * M3E 空态占位视图：大号有机形状徽章 + 强调标题 + 辅助说明 + 可选行动按钮。
 */
@Composable
fun PixFoldEmptyState(
    polygon: RoundedPolygon,
    @DrawableRes iconRes: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PixFoldShapeBadge(
            polygon = polygon,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            iconRes = iconRes,
            size = 72.dp,
            iconSize = 32.dp,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

/**
 * M3E 统一顶栏：包装 [TopAppBar]，提供 `headlineSmallEmphasized`（无副标题）或
 * `titleLargeEmphasized`（带副标题）标题，以及形变 tonal 返回按钮（触控目标 `>= 48.dp`）。
 */
@Composable
fun PixFoldTopBar(
    title: String,
    onBack: () -> Unit,
    backTag: String,
    backCd: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        titleContentColor = MaterialTheme.colorScheme.onBackground,
        subtitleContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val navIcon: @Composable () -> Unit = {
        FilledTonalIconButton(
            onClick = onBack,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier
                .padding(start = 4.dp)
                .testTag(backTag)
                .semantics { contentDescription = backCd },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = null,
            )
        }
    }

    if (subtitle != null) {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            subtitle = {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = modifier,
            navigationIcon = navIcon,
            actions = actions,
            windowInsets = windowInsets,
            colors = colors,
        )
    } else {
        TopAppBar(
            title = {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            modifier = modifier,
            navigationIcon = navIcon,
            actions = actions,
            windowInsets = windowInsets,
            colors = colors,
        )
    }
}
