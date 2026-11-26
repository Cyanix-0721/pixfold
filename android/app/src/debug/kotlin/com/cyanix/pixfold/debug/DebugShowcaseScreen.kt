package com.cyanix.pixfold.debug

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.library.libraryStateOf
import com.cyanix.pixfold.domain.mock.MockData
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.naming.setOverride
import com.cyanix.pixfold.domain.plan.ConfirmFlowState
import com.cyanix.pixfold.domain.plan.OutputConflictPolicy
import com.cyanix.pixfold.domain.plan.buildPackagePlans
import com.cyanix.pixfold.domain.plan.executePackagePlans
import com.cyanix.pixfold.domain.settings.AccentChoice
import com.cyanix.pixfold.domain.settings.ColorSpecVersion
import com.cyanix.pixfold.domain.settings.ColorStyle
import com.cyanix.pixfold.domain.settings.PixFoldSettings
import com.cyanix.pixfold.domain.settings.ThemeMode
import com.cyanix.pixfold.domain.source.WorkspaceEntry
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceListing
import com.cyanix.pixfold.domain.task.TaskProgress
import com.cyanix.pixfold.ui.HomeScreen
import com.cyanix.pixfold.ui.components.ChipRowTags
import com.cyanix.pixfold.ui.components.DialogBlurController
import com.cyanix.pixfold.ui.components.DialogBlurHost
import com.cyanix.pixfold.ui.components.PackProgressDialog
import com.cyanix.pixfold.ui.components.PixFoldSectionCard
import com.cyanix.pixfold.ui.components.PixFoldSegmentedItem
import com.cyanix.pixfold.ui.components.PixFoldStatusPill
import com.cyanix.pixfold.ui.components.PixFoldTopBar
import com.cyanix.pixfold.ui.components.SingleSelectChipRow
import com.cyanix.pixfold.ui.components.TwoLevelChipRows
import com.cyanix.pixfold.ui.components.TwoLevelGroup
import com.cyanix.pixfold.ui.library.LibraryPage
import com.cyanix.pixfold.ui.library.MetadataPage
import com.cyanix.pixfold.ui.library.PackPreviewPage
import com.cyanix.pixfold.ui.organize.RenamePlanPage
import com.cyanix.pixfold.ui.organize.SortAndPreviewPage
import com.cyanix.pixfold.ui.plan.ExecutionReportPage
import com.cyanix.pixfold.ui.plan.UndoScopeDialog
import com.cyanix.pixfold.ui.preview.ImagePreview
import com.cyanix.pixfold.ui.preview.PreviewRename
import com.cyanix.pixfold.ui.settings.SettingsScreen
import com.cyanix.pixfold.ui.theme.PixFoldTheme
import com.cyanix.pixfold.ui.theme.isAffectedByColorSpec
import com.cyanix.pixfold.ui.theme.pixFoldSegmentedColors
import com.cyanix.pixfold.ui.theme.pixFoldSurfaceBorder
import com.cyanix.pixfold.ui.workspace.WorkspaceListDialog
import com.cyanix.pixfold.ui.workspace.WorkspaceState
import kotlinx.coroutines.delay

internal const val TAG_DEBUG_SHOWCASE_CATALOG = "debug-showcase-catalog"
internal const val TAG_DEBUG_SHOWCASE_EXIT = "debug-showcase-exit"
internal const val TAG_DEBUG_SHOWCASE_SPEC_2025 = "debug-showcase-spec-2025"
internal const val TAG_DEBUG_SHOWCASE_SPEC_2021 = "debug-showcase-spec-2021"

internal fun debugShowcaseColorStyleTag(style: ColorStyle): String =
    "debug-showcase-color-style-${style.name.lowercase()}"

@Composable
internal fun DebugShowcaseScreen(
    request: DebugShowcaseRequest,
    onExitShowcase: () -> Unit,
) {
    var activeScene by remember { mutableStateOf(request.scene) }
    var currentSettings by remember { mutableStateOf(request.settings) }
    var currentFontScale by remember { mutableStateOf(request.fontScale) }
    var autoAdvanceProgress by remember { mutableStateOf(false) }

    LaunchedEffect(request) {
        activeScene = request.scene
        currentSettings = request.settings
        currentFontScale = request.fontScale
    }

    val backToCatalog = {
        activeScene = DebugScene.CATALOG
    }

    BackHandler(enabled = true) {
        if (activeScene != DebugScene.CATALOG) {
            backToCatalog()
        } else {
            onExitShowcase()
        }
    }

    val baseDensity = LocalDensity.current
    val dialogBlur = remember { DialogBlurController() }

    CompositionLocalProvider(
        LocalDensity provides Density(
            density = baseDensity.density,
            fontScale = currentFontScale,
        ),
    ) {
        PixFoldTheme(settings = currentSettings) {
            DialogBlurHost(controller = dialogBlur) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (activeScene) {
                        DebugScene.CATALOG -> DebugShowcaseCatalog(
                            settings = currentSettings,
                            fontScale = currentFontScale,
                            autoAdvanceProgress = autoAdvanceProgress,
                            onUpdateSettings = { currentSettings = it },
                            onUpdateFontScale = { currentFontScale = it },
                            onToggleAutoAdvance = { autoAdvanceProgress = it },
                            onPickScene = { activeScene = it },
                            onExitShowcase = onExitShowcase,
                        )
                        DebugScene.HOME -> ShowcaseHome(
                            onOpenSettings = { activeScene = DebugScene.SETTINGS },
                            onOpenOrganize = { activeScene = DebugScene.SORT },
                            onOpenLibrary = { activeScene = DebugScene.LIBRARY },
                            onOpenWorkspaceList = { activeScene = DebugScene.WORKSPACE_DIALOG },
                        )
                        DebugScene.SETTINGS -> SettingsScreen(
                            settings = currentSettings,
                            onChange = { currentSettings = it },
                            onBack = backToCatalog,
                            onOpenLicenses = {},
                        )
                        DebugScene.WORKSPACE_DIALOG -> ShowcaseWorkspaceDialog(
                            onDismiss = backToCatalog,
                        )
                        DebugScene.CHIPS -> ShowcaseSingleChips()
                        DebugScene.CHIPS_TWO_LEVEL -> ShowcaseTwoLevelChips()
                        DebugScene.SORT -> SortAndPreviewPage(
                            collections = MockData.workspaceA.collections,
                            onNext = { activeScene = DebugScene.RENAME_PLAN },
                        )
                        DebugScene.LIBRARY -> ShowcaseLibrary(
                            onNext = { activeScene = DebugScene.METADATA },
                        )
                        DebugScene.METADATA -> ShowcaseMetadata(
                            onBack = { activeScene = DebugScene.LIBRARY },
                            onNext = { activeScene = DebugScene.PACK_PREVIEW },
                        )
                        DebugScene.PACK_PREVIEW -> ShowcasePackPreview(
                            onBack = { activeScene = DebugScene.METADATA },
                        )
                        DebugScene.PACK_PROGRESS -> ShowcasePackProgress(
                            autoAdvance = autoAdvanceProgress,
                            onCancel = backToCatalog,
                        )
                        DebugScene.RENAME_PLAN -> ShowcaseRenamePlan(
                            onBack = { activeScene = DebugScene.SORT },
                        )
                        DebugScene.EXECUTION_REPORT -> ShowcaseExecutionReport(
                            onUndoScope = { activeScene = DebugScene.UNDO_DIALOG },
                            onDone = backToCatalog,
                        )
                        DebugScene.UNDO_DIALOG -> ShowcaseUndoDialog(
                            onClose = backToCatalog,
                        )
                        DebugScene.IMAGE_PREVIEW -> ShowcaseImagePreview(
                            onClose = backToCatalog,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugShowcaseCatalog(
    settings: PixFoldSettings,
    fontScale: Float,
    autoAdvanceProgress: Boolean,
    onUpdateSettings: (PixFoldSettings) -> Unit,
    onUpdateFontScale: (Float) -> Unit,
    onToggleAutoAdvance: (Boolean) -> Unit,
    onPickScene: (DebugScene) -> Unit,
    onExitShowcase: () -> Unit,
) {
    val childScenes = remember { DebugScene.entries.filter { it != DebugScene.CATALOG } }
    val grouped = remember(childScenes) { childScenes.groupBy { it.group } }

    Scaffold(
        topBar = {
            PixFoldTopBar(
                title = "PixFold 实时场景直达",
                subtitle = "按系统返回退出展示模式，不遮挡真实页面安全区",
                onBack = onExitShowcase,
                backTag = TAG_DEBUG_SHOWCASE_EXIT,
                backCd = "退出调试场景展示",
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag(TAG_DEBUG_SHOWCASE_CATALOG),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PixFoldSectionCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "快捷外观参数",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            val themeOptions = listOf(
                                Triple("浅色", settings.themeMode == ThemeMode.Light && !settings.pureBlack) {
                                    onUpdateSettings(settings.copy(themeMode = ThemeMode.Light, pureBlack = false))
                                },
                                Triple("深色", settings.themeMode == ThemeMode.Dark && !settings.pureBlack) {
                                    onUpdateSettings(settings.copy(themeMode = ThemeMode.Dark, pureBlack = false))
                                },
                                Triple("纯黑", settings.themeMode == ThemeMode.Dark && settings.pureBlack) {
                                    onUpdateSettings(settings.copy(themeMode = ThemeMode.Dark, pureBlack = true))
                                },
                            )
                            themeOptions.forEachIndexed { idx, (label, selected, onClick) ->
                                val shapes = when (idx) {
                                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                    themeOptions.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                }
                                FilledTonalToggleButton(
                                    checked = selected,
                                    onCheckedChange = { onClick() },
                                    shapes = shapes,
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp),
                                ) {
                                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            val isLargeFont = fontScale >= 1.25f
                            FilledTonalToggleButton(
                                checked = !isLargeFont,
                                onCheckedChange = { onUpdateFontScale(1.0f) },
                                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("1.0x", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledTonalToggleButton(
                                checked = isLargeFont,
                                onCheckedChange = { onUpdateFontScale(1.3f) },
                                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("1.3x", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            val isSystemAccent = settings.accent == AccentChoice.System
                            FilledTonalToggleButton(
                                checked = !isSystemAccent,
                                onCheckedChange = {
                                    onUpdateSettings(settings.copy(accent = AccentChoice.Default))
                                },
                                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("默认强调色", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledTonalToggleButton(
                                checked = isSystemAccent,
                                onCheckedChange = {
                                    onUpdateSettings(settings.copy(accent = AccentChoice.System))
                                },
                                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("壁纸取色", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        Text(
                            text = "色彩风格",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ColorStyle.entries.chunked(3).forEach { rowStyles ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(
                                        ButtonGroupDefaults.ConnectedSpaceBetween,
                                    ),
                                ) {
                                    rowStyles.forEachIndexed { idx, style ->
                                        val shapes = when (idx) {
                                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                            rowStyles.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                                        }
                                        FilledTonalToggleButton(
                                            checked = settings.colorStyle == style,
                                            onCheckedChange = {
                                                onUpdateSettings(settings.copy(colorStyle = style))
                                            },
                                            shapes = shapes,
                                            modifier = Modifier
                                                .weight(1f)
                                                .heightIn(min = 48.dp)
                                                .testTag(debugShowcaseColorStyleTag(style)),
                                        ) {
                                            Text(
                                                text = stringResource(showcaseColorStyleLabelRes(style)),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        val specEnabled = settings.colorStyle.isAffectedByColorSpec()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                        ) {
                            val is2025 = settings.colorSpec == ColorSpecVersion.V2025
                            FilledTonalToggleButton(
                                checked = is2025,
                                onCheckedChange = {
                                    onUpdateSettings(settings.copy(colorSpec = ColorSpecVersion.V2025))
                                },
                                enabled = specEnabled,
                                shapes = ButtonGroupDefaults.connectedLeadingButtonShapes(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag(TAG_DEBUG_SHOWCASE_SPEC_2025),
                            ) {
                                Text("2025 M3E", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            FilledTonalToggleButton(
                                checked = !is2025,
                                onCheckedChange = {
                                    onUpdateSettings(settings.copy(colorSpec = ColorSpecVersion.V2021))
                                },
                                enabled = specEnabled,
                                shapes = ButtonGroupDefaults.connectedTrailingButtonShapes(),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag(TAG_DEBUG_SHOWCASE_SPEC_2021),
                            ) {
                                Text("2021 M3", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .toggleable(
                                    value = autoAdvanceProgress,
                                    role = Role.Switch,
                                    onValueChange = onToggleAutoAdvance,
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "双波浪进度框自动步进演示",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = autoAdvanceProgress,
                                onCheckedChange = null,
                            )
                        }
                    }
                }
            }

            grouped.forEach { (groupTitle, items) ->
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                        Text(
                            text = groupTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                        items.forEachIndexed { idx, scene ->
                            PixFoldSegmentedItem(
                                onClick = { onPickScene(scene) },
                                shapes = ListItemDefaults.segmentedShapes(idx, items.size),
                                colors = pixFoldSegmentedColors(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("debug-showcase-item-${scene.id}"),
                                trailingContent = {
                                    PixFoldStatusPill(
                                        text = scene.id,
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        border = pixFoldSurfaceBorder(),
                                    )
                                },
                            ) {
                                Text(
                                    text = scene.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun showcaseColorStyleLabelRes(style: ColorStyle): Int = when (style) {
    ColorStyle.TonalSpot -> R.string.style_tonal_spot
    ColorStyle.Vibrant -> R.string.style_vibrant
    ColorStyle.Expressive -> R.string.style_expressive
    ColorStyle.Neutral -> R.string.style_neutral
    ColorStyle.Monochrome -> R.string.style_monochrome
    ColorStyle.Fidelity -> R.string.style_fidelity
    ColorStyle.Content -> R.string.style_content
    ColorStyle.Rainbow -> R.string.style_rainbow
    ColorStyle.FruitSalad -> R.string.style_fruit_salad
}

@Composable
private fun ShowcaseHome(
    onOpenSettings: () -> Unit,
    onOpenOrganize: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenWorkspaceList: () -> Unit,
) {
    val readyWorkspace = remember {
        WorkspaceState.Ready(
            handle = WorkspaceHandle(
                treeUri = "content://com.cyanix.pixfold.debug/tree/comics",
                displayName = "漫画书库工作区 (Debug)",
            ),
            collections = MockData.workspaceA.collections,
        )
    }
    HomeScreen(
        onOpenOrganize = onOpenOrganize,
        onOpenLibrary = onOpenLibrary,
        onOpenSettings = onOpenSettings,
        workspaceState = readyWorkspace,
        onPickWorkspace = {},
        onOpenWorkspaceList = onOpenWorkspaceList,
    )
}

@Composable
private fun ShowcaseWorkspaceDialog(onDismiss: () -> Unit) {
    val listings = remember {
        listOf(
            WorkspaceListing(
                entry = WorkspaceEntry(
                    handle = WorkspaceHandle(
                        treeUri = "content://com.cyanix.pixfold.debug/tree/comics",
                        displayName = "漫画书库工作区",
                    ),
                    pinned = true,
                ),
                granted = true,
            ),
            WorkspaceListing(
                entry = WorkspaceEntry(
                    handle = WorkspaceHandle(
                        treeUri = "content://com.cyanix.pixfold.debug/tree/photos",
                        displayName = "旅行照片整理",
                    ),
                    pinned = false,
                ),
                granted = true,
            ),
            WorkspaceListing(
                entry = WorkspaceEntry(
                    handle = WorkspaceHandle(
                        treeUri = "content://com.cyanix.pixfold.debug/tree/archive",
                        displayName = "旧版归档目录",
                    ),
                    pinned = false,
                ),
                granted = false,
            ),
        )
    }
    Box(Modifier.fillMaxSize()) {
        ShowcaseHome(
            onOpenSettings = {},
            onOpenOrganize = {},
            onOpenLibrary = {},
            onOpenWorkspaceList = {},
        )
        WorkspaceListDialog(
            listings = listings,
            currentTreeUri = "content://com.cyanix.pixfold.debug/tree/comics",
            onDismiss = onDismiss,
            onOpen = {},
            onRemove = { _, _ -> },
            onTogglePin = {},
            onMove = { _, _ -> },
            onAdd = {},
        )
    }
}

@Composable
private fun ShowcaseSingleChips() {
    val items = remember {
        listOf(
            "短名",
            "超长中文系列名称测试用于验证原生排版宽度",
            "Very Long English Comic Volume Title For Native Layout",
            "第01卷",
            "另一个超长日文与中文混合作品名合集测试第02卷",
            "Short",
            "中长标题测试第03卷",
            "Series Alpha Vol 04",
            "番外篇合集特别收录第05卷",
            "Standalone Extra 06",
            "长标题第七卷归档预览样本",
            "Final Volume 08",
        )
    }
    var active by remember { mutableStateOf(items.first()) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        SingleSelectChipRow(
            items = items,
            activeId = active,
            idOf = { it },
            onSelect = { active = it },
            selectorTag = "debug-chip-row",
            chipTag = { "debug-chip-$it" },
            allEntryTag = "debug-chip-all",
            chipDescription = { it },
            allEntryText = { n -> "全部 $n 项" },
            chipLabel = { it },
        )
    }
}

@Composable
private fun ShowcaseTwoLevelChips() {
    val groups = remember {
        listOf(
            TwoLevelGroup(
                key = "series:long-cn",
                name = "超长中文系列名称测试用于验证两级浮层排布",
                isSeries = true,
                members = listOf(
                    "第01卷 初遇篇",
                    "第02卷 超长副标题测试用来验证浮层内芯片自动换行",
                    "第03卷 English Subtitle Edition",
                    "第04卷 完结篇",
                ),
            ),
            TwoLevelGroup(
                key = "single:short",
                name = "单本短名",
                isSeries = false,
                members = listOf("单本短名"),
            ),
            TwoLevelGroup(
                key = "series:en",
                name = "Long English Anthology Series Collection",
                isSeries = true,
                members = listOf(
                    "Volume 01 Extended Cut",
                    "Volume 02 Bonus Chapters",
                ),
            ),
        )
    }
    var active by remember { mutableStateOf(groups.first().members.first()) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp),
    ) {
        TwoLevelChipRows(
            groups = groups,
            activeItemId = active,
            idOf = { it },
            onSelect = { active = it },
            topTags = ChipRowTags(
                selectorTag = "debug-top-row",
                chipTag = { "debug-top-$it" },
                allEntryTag = "debug-top-all",
            ),
            childTags = ChipRowTags(
                selectorTag = "debug-child-row",
                chipTag = { "debug-child-$it" },
                allEntryTag = "debug-child-all",
            ),
            singleTopChipTag = { "debug-single-$it" },
            topChipDescription = { it.name },
            topChipLabel = { it.name },
            topAllEntryText = { n -> "全部 $n 项" },
            childChipDescription = { it },
            childChipLabel = { it },
            childAllEntryText = { n -> "全部 $n 卷" },
        )
    }
}

@Composable
private fun ShowcaseLibrary(onNext: () -> Unit) {
    val base = MockDataB.library
    val volumes = remember(base) {
        base.volumes.mapIndexed { idx, v ->
            if (idx == 0) v.copy(outputExists = true) else v
        }
    }
    var state by remember { mutableStateOf(libraryStateOf(base.rootPath, volumes)) }
    LibraryPage(
        state = state,
        onStateChange = { state = it },
        onNext = onNext,
        onUpdateMeta = {},
    )
}

@Composable
private fun ShowcaseMetadata(
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val base = MockDataB.library
    val volumes = remember(base) {
        base.volumes.mapIndexed { idx, v ->
            if (idx == 0) v.copy(outputExists = true) else v
        }
    }
    var state by remember { mutableStateOf(libraryStateOf(base.rootPath, volumes)) }
    MetadataPage(
        libraryState = state,
        onLibraryChange = { state = it },
        onBack = onBack,
        onNext = onNext,
        onUpdateMeta = {},
    )
}

@Composable
private fun ShowcasePackPreview(onBack: () -> Unit) {
    val base = MockDataB.library
    var state by remember { mutableStateOf(libraryStateOf(base.rootPath, base.volumes)) }
    PackPreviewPage(
        libraryState = state,
        onLibraryChange = { state = it },
        showPlanSection = true,
        onBack = onBack,
    )
}

@Composable
private fun ShowcasePackProgress(
    autoAdvance: Boolean,
    onCancel: () -> Unit,
) {
    var doneVols by remember { mutableIntStateOf(1) }
    var donePages by remember { mutableIntStateOf(62) }
    LaunchedEffect(autoAdvance) {
        if (!autoAdvance) return@LaunchedEffect
        while (true) {
            delay(300)
            donePages += 4
            if (donePages > 100) {
                donePages = 0
                doneVols = (doneVols + 1) % 4
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        ShowcasePackPreview(onBack = onCancel)
        PackProgressDialog(
            progress = TaskProgress(total = 4, done = doneVols, failed = 0, skipped = 0),
            pageDone = donePages,
            pageTotal = 100,
            volumeLabel = "[Cior (Ken-1)] 彼女がボクの知らないところで",
            onCancel = onCancel,
        )
    }
}

@Composable
private fun ShowcaseRenamePlan(onBack: () -> Unit) {
    val misc = remember { MockData.workspaceA.collections.filter { it.id == "misc" } }
    val naming = remember {
        NamingState(NamingScheme(rootDirName = "杂图"))
            .let { setOverride(it, "misc-2", "dup.jpg") }
            .let { setOverride(it, "misc-3", "dup.jpg") }
    }
    RenamePlanPage(
        collections = misc,
        orders = emptyMap(),
        namingState = naming,
        cleanEmptyDirs = false,
        onCleanEmptyDirsChange = {},
        flow = ConfirmFlowState(),
        onStartConfirm = {},
        onConfirmStep = {},
        onCancelConfirm = {},
        onExecute = {},
        onBack = onBack,
    )
}

@Composable
private fun ShowcaseExecutionReport(
    onUndoScope: () -> Unit,
    onDone: () -> Unit,
) {
    val report = remember {
        val library = MockDataB.library
        val aot1 = library.volumes.first { it.id == "aot-1" }
        val op102 = library.volumes.first { it.id == "op-102" }
        val noPages = aot1.copy(pageOrder = aot1.pageOrder.copy(order = emptyList()))
        executePackagePlans(
            buildPackagePlans(
                listOf(library.volumes[1], op102, noPages),
                conflictPolicy = OutputConflictPolicy.Skip,
            ),
        )
    }
    ExecutionReportPage(
        report = report,
        undoMessage = null,
        onUndoScope = { _ -> onUndoScope() },
        onDone = onDone,
    )
}

@Composable
private fun ShowcaseUndoDialog(onClose: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ShowcaseExecutionReport(onUndoScope = {}, onDone = onClose)
        UndoScopeDialog(
            overwrittenCount = 2,
            onChoose = { onClose() },
            onCancel = onClose,
        )
    }
}

@Composable
private fun ShowcaseImagePreview(onClose: () -> Unit) {
    val images = remember { MockData.workspaceA.collections.first().images.take(5) }
    ImagePreview(
        items = images,
        initialIndex = 1,
        onClose = onClose,
        rename = PreviewRename(
            overrides = emptyMap(),
            finalNameOf = { it.fileName },
            onRename = { _, _ -> },
        ),
    )
}
