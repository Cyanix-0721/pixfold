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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.licenses_thanks),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            // ---- ① 本项目 ----
            Text(
                text = stringResource(R.string.licenses_own),
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
                        text = "$OWN_LICENSE_NAME · $OWN_COPYRIGHT",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag(TAG_LICENSES_OWN),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- ② 第三方组件(自动生成) ----
            Text(
                text = stringResource(R.string.licenses_third_party),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            LibrariesContainer(
                libraries = libraries,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_LICENSES_LIST),
            )

            // ---- 许可更正 ----
            //
            // 为什么需要它:AboutLibraries **从构件 POM 自动收集**许可,
            // 但 POM 声明的是"项目级"许可,未必等于**该模块**实际使用的许可。
            // `material-color-utilities` 正是这种情况:POM 写 MIT,
            // 而其模块内 LICENSE 沿用 Google 原库的 Apache-2.0。
            //
            // 这条**如实更正**而非静默采信自动结果 —— 许可声明的准确性是合规问题,
            // 不能因为"工具自动生成的"就默认正确。
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text(
                text = stringResource(R.string.licenses_correction_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Text(
                text = stringResource(R.string.licenses_correction_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .testTag(TAG_LICENSES_CORRECTION)
                    .padding(bottom = 12.dp),
            )

            // 图标几何数据:手工引入的矢量资源,不在依赖图中,故单列说明。
            Text(
                text = "Material Symbols (Apache-2.0) · " +
                    "https://github.com/google/material-design-icons",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}
