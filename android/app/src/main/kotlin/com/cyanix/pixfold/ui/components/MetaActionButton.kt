package com.cyanix.pixfold.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * 元数据字段行里的**纯图标动作按钮**:轻点执行、长按浮出完整说明。
 *
 * 当前两处调用(2026-10-21 用户裁决「一键清理和恢复建议也使用纯图标,长按时显示
 * 更加完整的提示说明」):
 * - 「恢复建议」—— Title / Series / Writer / Volume / Language 五个字段共用
 * - 「一键清理」—— Writer 字段
 *
 * ## 为什么必须抽出来
 *
 * 这两类按钮一共 6 个,全挤在元数据字段输入框右侧。文字版按钮(「恢复建议」
 * 4 字、「一键清理」4 字)每个 ~72dp,再叠加输入框本身,行宽立刻不够 ——
 * 这是用户在真机上看到「输入框被挤得很窄」的直接原因。改图标后每个 48dp,
 * 六个字段行都腾出宽度给正文。
 *
 * 抽成一个组件而不是在 6 处各写一遍 `TooltipBox + IconButton`:长按浮字与
 * 「点击不被打断」这套手势共存逻辑有踩坑历史(见 [ResetAllButton] 的 KDoc:
 * TooltipBox 只在 `PointerEventPass.Initial` 观察、不消费,长按触发后消费随后的
 * 抬手事件使子节点 `onClick` 不触发)。复制六遍意味着下一次调手势要改六处,
 * 且必漏一处。
 *
 * ## 无障碍
 *
 * 纯图标按钮**没有文字**,故 `contentDescription` 是硬要求(产品约束 7)。
 * 调用方负责把已解析好的文案传进来 —— 本组件不读资源,这样它在不同字段上
 * 可以带不同文案(「恢复建议值 标题」/「恢复建议值 系列」)。
 *
 * @param icon 图标。
 * @param description 无障碍描述,同时用作长按浮出的说明文字。
 * @param onClick 轻点回调。
 * @param enabled 禁用态(例如「恢复建议」在当前值已等于建议值时禁用)。
 * @param tag 测试 tag。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetaActionButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TooltipBox(
        // 浮字放右侧而不是下方:这些按钮在屏幕右缘,下方浮字会被裁;
        // 右侧同理,但 M3 的 `rememberTooltipPositionProvider` 会在越界时自动
        // 翻到另一侧 —— 给它一个「优先右侧」的意愿即可。
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            TooltipAnchorPosition.Below,
        ),
        tooltip = {
            PlainTooltip { Text(text = description) }
        },
        state = rememberTooltipState(),
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .testTag(tag)
                .semantics { contentDescription = description },
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
    }
}
