package com.cyanix.pixfold.ui.text

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey

/** 警示图标节点的 testTag(一次渲染里可能有多枚,断言用 `onAllNodesWithTag`)。 */
const val TAG_WARN_ICON = "warn-icon"

/**
 * 「哪些文案算警示」这张表**必须独立存在**(2026-10-05 用户要求把 emoji 换成矢量图)。
 *
 * **为什么不能从类型推**:`Msg` 没有 severity 字段,`TextKey` 也没有 —— "这条文案是
 * 警告"这层语义此前**只存在于字符串里的 `⚠️` 前缀**。前缀一走,语义就无处安放,
 * 所以它需要一个显式的落点。
 *
 * **为什么键是资源 id**:13 个曾带前缀的键横跨三种命名空间 ——
 * 5 个是 `TextKey`(`DangerDialogTitle` 等,经 [textOf] 渲染)、6 个是纯资源键
 * (`plan_danger_action` 等,调用点直接 `stringResource`)、1 个是 plurals
 * (`a_naming_conflicts`)。只有资源 id 能同时覆盖三者;`TextKey` 那一半经
 * [resId] 折进同一张表,避免两处各记一份而漂移。
 *
 * **两个图标的语义分工**(不是"同一形状换个颜色",色盲用户也要能分辨):
 *  - [R.drawable.ic_warning_triangle] ← 原 `⚠️` 语义:需留意,但不阻止执行;
 *  - [R.drawable.ic_forbidden] ← 原 `⛔` 语义:被跳过 / 不可执行。
 *
 * `⛔` 那两个键(`plan_skip_reason_prefix`、`a_plan_skip_reason`)去掉前缀后只剩
 * 裸 `%1$s`,已按注释里声明的原意**整键删除**,它们的图标由调用点直接给出
 * (见 `PlanRow` 与 `RenamePlanRow`)。
 */
val WARN_ICONS: Map<Int, Int> = mapOf(
    R.string.domain_danger_dialog_title to R.drawable.ic_warning_triangle,
    R.string.domain_summary_clean_empty_dirs_warning to R.drawable.ic_warning_triangle,
    R.string.domain_summary_overwrite_cbz to R.drawable.ic_warning_triangle,
    R.string.domain_summary_delete_source_dir_warning to R.drawable.ic_warning_triangle,
    R.string.domain_rename_summary_clean_empty_dirs to R.drawable.ic_warning_triangle,
    R.string.plan_danger_action to R.drawable.ic_warning_triangle,
    R.string.report_undo_warning_title to R.drawable.ic_warning_triangle,
    R.plurals.a_naming_conflicts to R.drawable.ic_warning_triangle,
    R.string.pack_page_issues to R.drawable.ic_warning_triangle,
    R.string.pack_delete_warning to R.drawable.ic_warning_triangle,
    R.string.pack_excluded_warning to R.drawable.ic_warning_triangle,
)

/** 资源键 -> 警示图标;不具警示语义的键返回 null。 */
fun warnIcon(resId: Int): Int? = WARN_ICONS[resId]

/** 领域层文案 -> 警示图标(经 [TextKey.resId] 折进同一张表)。 */
fun warnIcon(msg: Msg): Int? = WARN_ICONS[msg.key.resId()]

/**
 * 带警示图标的一行文案;图标即该行的「符号前缀」,故**不再**叠加 `"· "`。
 *
 * 图标 `contentDescription = null`:警示语义在**正文里已经用文字说清**
 * (如"打包完成后源目录将被删除,且不可恢复"),图标只是视觉冗余;给了描述反而
 * 会让读屏把同一件事念两遍。这与 [com.cyanix.pixfold.ui.organize.SchemeEditor] /
 * [com.cyanix.pixfold.ui.components.PinToggle] 的判法一致。
 *
 * @param textModifier 给 `Text` **自身**的修饰符。需要按文本断言的 testTag
 *   必须走这里 —— 挂到 `Row` 上会让 `assertTextEquals` 找不到文本节点。
 *
 * 参数顺序:`modifier` 必须是**第一个可选参数**(Compose lint 的 `ModifierParameter`
 * 规则,`warningsAsErrors = true` 下违反即构建失败)。调用点一律用具名实参,故顺序无影响。
 */
@Composable
fun WarnLine(
    icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    iconSize: Dp = 16.dp,
    textModifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (color == Color.Unspecified) LocalContentColor.current else color,
            modifier = Modifier
                .size(iconSize)
                .testTag(TAG_WARN_ICON),
        )
        Text(text = text, style = style, color = color, modifier = textModifier)
    }
}
