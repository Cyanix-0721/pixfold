package com.cyanix.pixfold.ui

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
import com.cyanix.pixfold.R
import com.cyanix.pixfold.ui.workspace.WorkspaceBar
import com.cyanix.pixfold.ui.workspace.WorkspaceState

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
    onOpenOrganize: () -> Unit,
    onOpenLibrary: () -> Unit,
    // Modifier 必须是**第一个可选参数**(Compose 约定,lint ModifierParameter 强制)。
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    contentInsets: WindowInsets = WindowInsets.safeDrawing,
    /**
     * 工作区状态。`null` = **不接工作区**(不画条、不禁用大卡) ——
     * 与 [com.cyanix.pixfold.ui.organize.OrganizeSteps] /
     * [com.cyanix.pixfold.ui.library.LibraryPage] 的同名参数语义完全一致,
     * 默认 `null` 让既有首页测试的布局与断言一行都不用改。
     *
     * @see canStartWorkflow
     */
    workspaceState: WorkspaceState? = null,
    /** 用户要求选择/更换工作区目录。仅 [workspaceState] 非 null 时会被调用。 */
    onPickWorkspace: () -> Unit = {},
) {
    // `stringResource` 不能在 `semantics {}` 里调用(那不是 @Composable lambda),须先取出。
    val settingsCd = stringResource(R.string.home_settings_cd)
    val organizeTitle = stringResource(R.string.home_workflow_a)
    val organizeSubtitle = stringResource(R.string.home_workflow_a_subtitle)
    val libraryTitle = stringResource(R.string.home_workflow_b)
    val librarySubtitle = stringResource(R.string.home_workflow_b_subtitle)
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
        val canStart = canStartWorkflow(workspaceState)
        WorkflowEntry(organizeTitle, organizeSubtitle, onOpenOrganize, enabled = canStart)
        WorkflowEntry(libraryTitle, librarySubtitle, onOpenLibrary, enabled = canStart)

        // 两张卡都点不动时必须**说明为什么**,否则首屏就是一堵死墙。
        // 只在真的被挡住时才出现,工作区就绪时这一行不存在。
        if (!canStart) {
            Text(
                text = stringResource(R.string.home_workspace_gate_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TAG_WORKSPACE_GATE_HINT),
            )
        }

        // 工作区条:两张大卡**之下**的从属层(HANGOFF §4,2026-10-15 用户裁决:
        // 「Workspace 是范围,两个工作流是任务,层级不同」;摆成第三张等权卡会误导用户
        // 以为「选工作区」也是一件能做完的事)。
        //
        // 位置在卡之下、而不是标题行下方,是 `HANGOFF.md:211-212` 逐字定的形态。
        if (workspaceState != null) {
            WorkspaceBar(state = workspaceState, onPick = onPickWorkspace)
        }
    }
}

/**
 * 首页两条工作流入口此刻**能不能进**(2026-10-24 用户裁决 乙/甲/乙)。
 *
 * ## 判据为什么是「就绪且非空」而不是 [WorkspaceState.canOrganize]
 *
 * `canOrganize` 在 [WorkspaceState.None] / `Denied` / `Failed` 上**都返回 true** ——
 * 那是 D1 的语义：「没选目录时就拿 mock 数据演示,界面上总得有点东西看」。
 * 首页入口要回答的是**另一个问题**:「点进去以后用户操作的是不是他自己的文件」。
 * 在 mock 上排序改名、做完却发现什么都没写进自己文件夹,是这个应用最坏的一种失败,
 * 所以入口这道门按「真有一份可读的真实目录」判定。
 *
 * `Ready` 但**空列表**同样不可进:用户选了个没有图片的文件夹,进去只会看到空态,
 * 真正的下一步是换一个文件夹(条上就有「选择文件夹」)。
 *
 * ## 判据为什么不对 `null` 生效
 *
 * `null` = 宿主**没接**工作区(既有测试与 D1 演示路径),此时本组件无权假定制它有,
 * 否则 24 处导航测试会因为首页突然不可点而全部变红 —— 那不是「行为变更」,是没接线。
 */
internal fun canStartWorkflow(state: WorkspaceState?): Boolean = when (state) {
    null -> true
    WorkspaceState.None -> false
    WorkspaceState.Loading -> false
    is WorkspaceState.Ready -> state.collections.isNotEmpty()
    is WorkspaceState.Denied -> false
    is WorkspaceState.Failed -> false
}

const val TAG_TITLE = "home-title"

/** 首页右上角的设置入口。 */
const val TAG_SETTINGS_BUTTON = "home-settings"

/** 两张大卡被禁用时那一行说明（只有被挡住时才出现）。 */
const val TAG_WORKSPACE_GATE_HINT = "home-workspace-gate-hint"

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
private fun WorkflowEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    // enabled = false 走 clickable 的 enabled 参数，不是把 onClick 换成空 lambda：
    // 只有前者会往语义树写 Disabled，读屏才知道「这个入口现在用不了」；
    // 换成空 lambda 虽然点了没反应，但读屏照样报「按钮」，用户会一直点。
    // 测试侧的 assertIsNotEnabled 也依赖这条语义。
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = title },
        border = BorderStroke(
            1.dp,
            if (enabled) {
                MaterialTheme.colorScheme.outlineVariant
            } else {
                // 禁用态描边再淡一档：纯黑模式下阴影本来就不可见，若描边不变，
                // 「灰标题 + 正常描边」看起来仍像可用入口，用户会反复点。
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            },
        ),
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
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.38f,
                ),
            )
        }
    }
}
