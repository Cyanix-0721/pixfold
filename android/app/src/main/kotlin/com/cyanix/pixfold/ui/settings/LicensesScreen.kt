package com.cyanix.pixfold.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.ui.components.PixFoldSegmentedItem
import com.cyanix.pixfold.ui.components.PixFoldStatusPill
import com.cyanix.pixfold.ui.components.PixFoldTopBar
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries

const val TAG_LICENSES_PAGE = "licenses-page"
const val TAG_LICENSES_BACK = "licenses-back"
const val TAG_LICENSES_OWN = "licenses-own"
const val TAG_LICENSES_LIST = "licenses-list"

/** 许可更正说明(见下方"为什么需要它")。 */
const val TAG_LICENSES_CORRECTION = "licenses-correction"

/** 本项目自身的许可(MIT)—— 与仓库根 `LICENSE` 一致。 */
private const val OWN_LICENSE_NAME = "MIT License"
private const val OWN_COPYRIGHT = "Copyright (c) 2026 Cyanix-0721"

/** 图标几何数据的手写说明(它们不在依赖图中,AboutLibraries 收集不到)。 */
private const val ICONS_NOTE = "Material Symbols (Apache-2.0) · https://github.com/google/material-design-icons"

/**
 * 开源许可页（Material 3 Expressive 顶栏 + 分段列表项）。
 *
 * **两块内容**:
 *  1. **本项目**(MIT) + 许可更正说明 —— 手写，位于 `LibrariesContainer` 的 `header` 槽；
 *  2. **第三方组件** —— 由 [LibrariesContainer] 从 `aboutlibraries` Gradle 插件
 *     生成的 `res/raw/aboutlibraries.json` 自动渲染为 [PixFoldSegmentedItem]。
 */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val resources = LocalResources.current
    val thanksText = stringResource(R.string.licenses_thanks)
    val ownTitle = stringResource(R.string.licenses_own)
    val thirdPartyTitle = stringResource(R.string.licenses_third_party)
    val correctionTitle = stringResource(R.string.licenses_correction_title)
    val correctionBody = stringResource(R.string.licenses_correction_body)
    val ownLicenseLine = "$OWN_LICENSE_NAME · $OWN_COPYRIGHT"
    val iconsNote = ICONS_NOTE
    val backLabel = stringResource(R.string.settings_back)

    val librariesJson = remember {
        runCatching {
            resources.openRawResource(R.raw.aboutlibraries)
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull()
    }
    val libraries by produceLibraries(librariesJson.orEmpty())
    val totalLibraries = libraries?.libraries?.size ?: 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_LICENSES_PAGE),
    ) {
        PixFoldTopBar(
            title = stringResource(R.string.licenses_title),
            onBack = onBack,
            backTag = TAG_LICENSES_BACK,
            backCd = backLabel,
            windowInsets = WindowInsets(0, 0, 0, 0),
        )

        // ⚠️ **不能**把 LibrariesContainer 放进 Column(verticalScroll):
        // 它自身是 LazyColumn，嵌套会给它无界高度导致运行时崩溃。
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_LICENSES_LIST),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            divider = { Spacer(Modifier.height(ListItemDefaults.SegmentedGap)) },
            header = {
                item {
                    LicensesHeader(
                        thanks = thanksText,
                        ownTitle = ownTitle,
                        ownLicense = ownLicenseLine,
                        thirdPartyTitle = thirdPartyTitle,
                        correctionTitle = correctionTitle,
                        correctionBody = correctionBody,
                        iconsNote = iconsNote,
                    )
                }
            },
            libraryRow = { index, library, _, onClick, _ ->
                ThirdPartyLicenseRow(
                    library = library,
                    index = index,
                    totalCount = totalLibraries.coerceAtLeast(index + 1),
                    onClick = onClick,
                )
            },
        )
    }
}

/**
 * 许可页的**静态页头**(致谢 + 本项目 + 许可更正 + 第三方小节标题)。
 *
 * 水平内边距由外层 `LibrariesContainer(contentPadding = PaddingValues(horizontal = 16.dp))`
 * 统一提供，确保页头与第三方分段列表项左右边界逐像素对齐。
 */
@Composable
private fun LicensesHeader(
    thanks: String,
    ownTitle: String,
    ownLicense: String,
    thirdPartyTitle: String,
    correctionTitle: String,
    correctionBody: String,
    iconsNote: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = thanks,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 12.dp),
        )
        Text(
            text = ownTitle,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
        )
        PixFoldSegmentedItem(
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TAG_LICENSES_OWN),
            supportingContent = {
                Text(
                    text = ownLicense,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        ) {
            Text(
                text = "PixFold",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        // ---- 许可更正与图标几何声明 ----
        Text(
            text = correctionTitle,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            PixFoldSegmentedItem(
                shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_LICENSES_CORRECTION),
            ) {
                Text(
                    text = correctionBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PixFoldSegmentedItem(
                shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = iconsNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = thirdPartyTitle,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun ThirdPartyLicenseRow(
    library: Library,
    index: Int,
    totalCount: Int,
    onClick: () -> Unit,
) {
    val authorText = remember(library) {
        library.developers.mapNotNull { it.name?.takeIf(String::isNotBlank) }
            .joinToString(", ")
            .ifBlank { library.organization?.name.orEmpty() }
    }
    val version = library.artifactVersion?.takeIf { it.isNotBlank() }
    val licenseNames = remember(library) {
        library.licenses.map { it.name }.filter { it.isNotBlank() }
    }

    PixFoldSegmentedItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = totalCount),
        modifier = Modifier.fillMaxWidth(),
        supportingContent = if (authorText.isNotBlank() || licenseNames.isNotEmpty()) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (authorText.isNotBlank()) {
                        Text(
                            text = authorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (licenseNames.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            licenseNames.forEach { licenseName ->
                                PixFoldStatusPill(
                                    text = licenseName,
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        } else {
            null
        },
        trailingContent = version?.let { ver ->
            {
                Text(
                    text = ver,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        Text(
            text = library.name,
            style = MaterialTheme.typography.titleSmallEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
