package com.pixfold.d1.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R

/**
 * 首页:两条工作流入口(验收第 19 项"从首页完整走通")。
 *
 * targetSdk 36 起系统强制 edge-to-edge,故本页自行消费系统栏 inset
 * ——否则顶部标题会被状态栏/挖孔遮挡(真机实证发现)。
 *
 * ## 入口形态(用户 2026-10-14 走查后裁决)
 *
 * 用户逐字:「主要功能就是工作流AB,所以主页应该有 **2 个大卡片** 显示,并且改下文案,
 * **直接叫工作流不好听**」,并裁决:标题取「整理图片」/「打包漫画」、**每卡一行副标题**、
 * **卡片加描边**。
 *
 * 三条裁决各自的依据:
 *  1. **大卡片**:这两条工作流是应用的全部主线,理应是首屏唯一的视觉重心;
 *  2. **去「工作流 A/B」字样**:「工作流」是**实现视角**的词(文档里用它做章节名),
 *     不是用户视角的词 —— 用户看到的是"我要整理图片"而不是"我要走工作流 A";
 *  3. **描边**:见 [WorkflowEntry] 的 KDoc(纯黑模式下的**实测缺陷**)。
 *
 * @param contentInsets 系统栏 inset 来源;默认 [WindowInsets.safeDrawing]。
 *   可注入是为了**可测**:Robolectric 下系统 inset 恒为 0,若不注入就无法断言
 *   "内容确实让开了系统栏"(否则测试会因 16dp 内边距而假通过)。
 */
@Composable
fun HomeScreen(
    onOpenWorkflowA: () -> Unit,
    onOpenWorkflowB: () -> Unit,
    // Modifier 必须是**第一个可选参数**(Compose 约定,lint ModifierParameter 强制)。
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    // `stringResource` 不能在 `semantics {}` 里调用(那不是 @Composable lambda),须先取出。
    val settingsCd = stringResource(R.string.home_settings_cd)
    val workflowATitle = stringResource(R.string.home_workflow_a)
    val workflowASubtitle = stringResource(R.string.home_workflow_a_subtitle)
    val workflowBTitle = stringResource(R.string.home_workflow_b)
    val workflowBSubtitle = stringResource(R.string.home_workflow_b_subtitle)
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(contentInsets)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 标题行:标题左对齐,设置入口在右上角。
        // 设置是**次要入口**,故用图标按钮而非等宽卡片 —— 不与两条工作流入口抢视觉重量。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PixFold",
                modifier = Modifier.testTag(TAG_TITLE),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            IconButton(
                onClick = onOpenSettings,
                // M3 IconButton 默认触控目标已 ≥ 48dp(HANGOFF §6.5)。
                modifier = Modifier
                    .testTag(TAG_SETTINGS_BUTTON)
                    .semantics { contentDescription = settingsCd },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = null, // 语义由外层 contentDescription 统一播报
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        WorkflowEntry(workflowATitle, workflowASubtitle, onOpenWorkflowA)
        WorkflowEntry(workflowBTitle, workflowBSubtitle, onOpenWorkflowB)
    }
}

const val TAG_TITLE = "home-title"

/** 首页右上角的设置入口。 */
const val TAG_SETTINGS_BUTTON = "home-settings"

/**
 * 首页的大卡片入口:标题一行 + 副标题一行。
 *
 * ## 为什么必须**显式描边**(不是审美选择,是实测缺陷的修复)
 *
 * 用户设备日常配置是 `pure_black = true`。2026-10-14 真机逐行扫描卡 A 上下边缘带
 * (`y=406..415` / `y=620..635`,`x=40..1220`)**全部 `(0,0,0)`**;卡 A 高频色统计
 * `(0,0,0)` 244,706px vs 文字色 `(248,224,225)` 8,764px ⇒ **卡片没有任何可见边界**。
 *
 * 根因:M3 `Card` 的默认填充角色是 `surfaceContainerLow`,而
 * `ColorSchemes.withPureBlack()` 把 `background` / `surface` / `surfaceContainer*` /
 * `surfaceBright` / `surfaceDim` **全部压成 `Color.Black`** ⇒ 卡与背景同色。
 * 设置页自己写着「OLED 更省电;**层级改由边框表达**」,首页当时没有照做。
 *
 * ## 描边为什么用 `outlineVariant` 而不是 `outline`
 *
 * 与设置页的分隔线同源(`SettingsScreen.kt` 的 `HorizontalDivider` 也用 `outlineVariant`):
 * `outline` 是**强**边界(用于输入框、需要明确"这里可以输入"的位置),
 * `outlineVariant` 是**弱**边界 —— 卡片只需要"看得出有块区域",不需要抢眼。
 *
 * ## 为什么不用 `ElevatedCard` / 换填充色
 *
 * 纯黑模式下**阴影不可见**(黑底上的黑阴影)、`surfaceContainer*` 又全被压黑;
 * 换成 `primaryContainer` 这类有色底会出现大块彩色,与"纯黑省电"的诉求冲突。
 * 用户裁决也明确选了「卡片加描边(跟随纯黑开关说明)」这一项。
 *
 * ## 触控与无障碍
 *
 * 整卡可点且高度远超 48dp 触控下限(HANGOFF §6.5);`contentDescription` 取**标题**
 * (副标题是补充说明,读屏时标题已足够标识这个入口)。
 */
@Composable
private fun WorkflowEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { contentDescription = title },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
