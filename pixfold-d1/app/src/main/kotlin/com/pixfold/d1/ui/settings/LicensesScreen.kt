package com.pixfold.d1.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.produceLibraries
import com.pixfold.d1.R

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
 * 开源许可页。
 *
 * **两块内容**:
 *  1. **本项目**(MIT) —— 手写,因为 AboutLibraries 只收集"依赖",不含应用自身;
 *  2. **第三方组件** —— 由 [LibrariesContainer] 从 `aboutlibraries` Gradle 插件
 *     生成的 `res/raw/aboutlibraries.json` **自动渲染**。
 *
 * **为什么用库而不是手写清单**:Apache-2.0 要求保留版权与许可声明,
 * 手写清单一旦漏列某个依赖就是**合规风险**,且新增依赖时容易忘记同步。
 * 自动收集从依赖元数据生成,不存在"漏列"。
 *
 * > 唯一仍需手写的是**图标几何数据**(Material Symbols):它是以矢量资源形式
 * > 手工引入的,不出现在依赖图里,故单列一条说明。
 */
@Composable
fun LicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    // 用 LocalResources 而非 LocalContext.current.resources:
    // 前者是**配置感知**的,lint(LocalContextResourcesRead)要求如此 ——
    // 配置变化(如切换语言/深色)后能拿到新的 Resources,不会读到陈旧值。
    val resources = LocalResources.current
    val thanksText = stringResource(R.string.licenses_thanks)
    val ownTitle = stringResource(R.string.licenses_own)
    val thirdPartyTitle = stringResource(R.string.licenses_third_party)
    val correctionTitle = stringResource(R.string.licenses_correction_title)
    val correctionBody = stringResource(R.string.licenses_correction_body)
    val ownLicenseLine = "$OWN_LICENSE_NAME · $OWN_COPYRIGHT"
    val iconsNote = ICONS_NOTE
    // stringResource 先在 @Composable 作用域求值(semantics{} 的 lambda 不是 @Composable)。
    val backLabel = stringResource(R.string.settings_back)

    // 读取插件生成的许可数据。用 remember 避免每次重组都读盘。
    val librariesJson = remember {
        runCatching {
            resources.openRawResource(R.raw.aboutlibraries)
                .bufferedReader()
                .use { it.readText() }
        }.getOrNull()
    }
    val libraries by produceLibraries(librariesJson.orEmpty())

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .testTag(TAG_LICENSES_PAGE),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .testTag(TAG_LICENSES_BACK)
                    .semantics { contentDescription = backLabel },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = stringResource(R.string.licenses_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        // ⚠️ **不能**把 LibrariesContainer 放进 Column(verticalScroll):
        // 它自身是 LazyColumn,嵌套会给它**无界高度** -> 运行时抛
        // "Vertically scrollable component was measured with an infinity maximum
        //  height constraints"(真机实测崩溃,2026-09-21)。
        // 这正是项目 §12.5 记的禁忌:"一页只有一个页面级 LazyColumn,
        // 不在无界 verticalScroll 里嵌 LazyColumn"。
        //
        // 正确做法:让 LibrariesContainer **自己**当唯一滚动容器,
        // 页头/页脚用它提供的 header/footer 槽传入(而不是外层再套一个滚动)。
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag(TAG_LICENSES_LIST),
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
        )
    }
}

/**
 * 许可页的**静态页头**(本项目 + 第三方标题 + 许可更正)。
 *
 * 抽成独立组件是因为它现在渲染在 `LibrariesContainer` 的 `header` 槽里 ——
 * 槽内是 `LazyListScope`,不是 `@Composable` 上下文,故文案需在外面先求值再传入
 * (字符串资源不能在其中调用)。
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
    // 水平内边距不可省:改用 header 槽后,原先外层 Column 的 padding(horizontal=12.dp)
    // 一并丢失,导致页头文字贴到屏幕左缘(真机实测发现)。
    // **整块**内容(含"许可更正")都放进这同一个带内边距的 Column。
    // 真机踩坑:把"许可更正"放在 Column 之外当兄弟节点,它会失去水平内边距、文字贴左缘。
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(
            text = thanks,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        Text(
            text = ownTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "PixFold",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = ownLicense,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(TAG_LICENSES_OWN),
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        Text(
            text = thirdPartyTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        // ---- 许可更正(见文件头注释:为什么需要它)----
        HorizontalDivider(modifier = Modifier.padding(bottom = 12.dp))
        Text(
            text = correctionTitle,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = correctionBody,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(TAG_LICENSES_CORRECTION).padding(bottom = 12.dp),
        )
        Text(
            text = iconsNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}
