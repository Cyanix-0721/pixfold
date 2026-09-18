package com.pixfold.d1.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pixfold.d1.R

const val TAG_PIN_TOGGLE = "pin-toggle"

/** 图钉按钮的 testTag(按条目区分,便于测试点中具体某张)。 */
fun pinTag(id: String): String = "pin-$id"

/**
 * 图钉开关(验收第 6 项)。
 *
 * **图形来源**:官方 **Material Symbols `push_pin`** 的 VectorDrawable
 * (`res/drawable/ic_push_pin_{filled,outlined}.xml`,几何数据原样引用、未自行改动),
 * 用 [painterResource] + M3 [Icon] 渲染。
 *
 * **为什么不是别的写法**(2026-09-17 两轮返工的结论):
 * - ❌ **五角星 ★/☆**:语义错误 —— 五角星在通用语义里是"收藏/评分",而功能是"固定位置"(§4 词汇)。
 * - ❌ **emoji 📌/📍**:语义对,但 emoji **不接受 `color` 染色**,无法用 M3 语义色表达两态。
 * - ❌ **`material-icons-extended`**:官方已弃用(体积巨大);且 `PushPin` **不在 `material-icons-core`**
 *   子集内 —— 本工程 classpath 上确实没有该依赖(实测 `:app:dependencies` 只有 material3 + material-ripple)。
 * - ❌ **此前自绘 Canvas 图钉**:能用,但**观感差**(圆帽+三角像感叹号),用户 2026-09-17 指出。
 * - ✅ **VectorDrawable + `painterResource`**:Android **原生矢量格式**,零新增依赖、
 *   随 M3 语义色着色、直接复用官方几何数据 —— 图标就该用矢量资源,而不是手画。
 */
@Composable
fun PinToggle(
    isPinned: Boolean,
    itemName: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    tag: String = TAG_PIN_TOGGLE,
) {
    val container = if (isPinned) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
    }
    val contentColor = if (isPinned) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .size(size)
            .testTag(tag)
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = if (isPinned) "$itemName 已固定" else "$itemName 固定位置"
            },
        shape = MaterialTheme.shapes.small,
        color = container,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(
                    // 未固定 = outlined,已固定 = filled:两态形状确有区别,不只靠颜色
                    if (isPinned) R.drawable.ic_push_pin_filled else R.drawable.ic_push_pin_outlined,
                ),
                contentDescription = null, // 由外层 semantics 统一提供,避免读屏重复
                tint = contentColor,
                modifier = Modifier.size(size * 0.62f),
            )
        }
    }
}
