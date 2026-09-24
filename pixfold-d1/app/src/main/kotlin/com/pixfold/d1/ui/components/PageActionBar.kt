package com.pixfold.d1.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val TAG_PAGE_ACTION_BAR = "page-action-bar"

/**
 * 页面底部的**常驻动作栏**:推进(下一步)与终局(确认执行 / 确认打包 / 撤销)动作的固定落点。
 *
 * **为什么必须常驻**(台账 §2,真机实测):这类动作原先写在页面 `LazyColumn` 的末尾,
 * A 侧整库计划有 56 行,用户要连滑 **8–12 次**才够得到「确认执行」;而且 `LazyColumn`
 * 只组合可见项 —— 按钮在滚到之前**根本没被组合**,连测试点击都得先 `performScrollToNode`。
 *
 * **为什么不是靠顶部 Tab 解决**:Tab 行在嵌套 `NavHost` **之外**(见 `WorkflowASteps` /
 * `WorkflowBSteps` 里挂 `Modifier.weight(1f)` 的那层),滚动任何列表都不会把它移出视口。
 * 但 Tab 解决的是「切换目的地」,而这里的动作是**不可逆操作**(确认执行会真的改文件名),
 * Tab 里没有对应项;`下一步` 则是向导式推进,与 Tab 的平级视图索引语义不同。
 *
 * ## 左 / 右两个槽位(用户 2026-09-25 指定)
 *
 * 「下一步、完成之类的按钮都放到底栏的**右侧**,返回、上一步的按钮改到**左侧**。」
 * 于是本组件按语义分槽而不是按控件类型:
 *  - [leading] = **退回**动作(返回上一步 / 返回排序 / 撤销本次执行);
 *  - [trailing] = **推进与终局**动作(下一步 / 确认执行 / 确认打包 / 完成)。
 *
 * 两槽之间是 `Spacer(weight(1f))` 而不是 `Arrangement.SpaceBetween`:这样**只有一侧有按钮时**
 * 它仍然贴自己那一侧(单靠 `SpaceBetween` 会把唯一的按钮推到左边),两侧都有、且都很宽时
 * 退化成 `spacedBy(8.dp)` 的紧邻排布 —— 用户在真机上看到的报告页「撤销 / 完成」并排就是这个兜底形态。
 *
 * 本组件只负责「位置 + 分隔线 + 内边距」这一层一致性,按钮由调用方给 ——
 * 因为各页动作数量与危险级别不同(报告页是「撤销 + 完成」两个,其余是一个主按钮)。
 * 调用方须把它放在页面级 `Column` 内、`LazyColumn` **之外**,且列表自身保留 `weight(1f)`
 * (否则底栏会被列表的 `fillMaxSize()` 挤成 0 高,这正是 P1 要修的缺陷形态)。
 *
 * ## 高度(用户 2026-09-25 追加:「底栏高度可以稍微低一点」)
 *
 * 垂直内边距 6dp、按钮行与 `extraContent` 间距 4dp,外层**不再叠** `spacedBy`。
 * 底栏是**常驻**控件,每一 dp 都从列表可视区里扣:原先 `8dp + 6dp(外层 spacedBy)`
 * 在报告页(有 `extraContent`)叠成 20dp 空档,把 56 行报告列表又压掉一截。
 * 收到 6dp 后单按钮页底栏 = 分隔线 + 6 + 48(按钮本身,`Button` 最小高度)+ 6。
 * **不可再往 0 收**:`Button` 自身 48dp 已是 Material 触控下限(约束 7),
 * 内边距再压会让分隔线与按钮视觉粘连。
 *
 * @param leading 左侧槽位(退回类动作);为 null 时左侧留空。
 * @param trailing 右侧槽位(推进 / 终局动作);为 null 时右侧留空。
 * @param extraContent 按钮行**下方**的补充说明(如「没有成功执行的操作,无需撤销」、
 *   撤销结果提示);为 null 时只渲染按钮行。
 */
@Composable
fun PageActionBar(
    modifier: Modifier = Modifier,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_PAGE_ACTION_BAR),
    ) {
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) leading()
                // 单侧按钮时保证它仍贴自己那一侧(见上方 KDoc)
                Spacer(Modifier.weight(1f))
                if (trailing != null) trailing()
            }
            if (extraContent != null) extraContent()
        }
    }
}
