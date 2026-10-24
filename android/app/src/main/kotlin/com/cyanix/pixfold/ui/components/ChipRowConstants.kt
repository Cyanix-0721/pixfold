package com.cyanix.pixfold.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 芯片**宽度**的下限与上限(2026-10-23 用户裁决)。
 *
 * 原实现让 FilterChip 宽度完全跟随内容,长目录名一块就吃掉整行 —— 所谓瀑布流
 * 退化成「一行一块」。给死区间后,**一行只可能排 2 或 3 块**:
 *
 * - 361dp 屏的芯片可行轨道 = 361 − 8×2(底托) − 12×2(行内边距) = **321dp**
 * - 156×2 + 4 = 316 ≤ 321 ⇒ **两个长名放得下**
 * - 156×3 + 8 = 476 > 321 ⇒ **三个长名放不下**
 * - 96×3 + 8 = 296 ≤ 321 ⇒ 三个短名放得下
 * - 96×4 + 12 = 396 > 321 ⇒ 四个短名放不下
 *
 * (行内间距 2026-10-24 由 6dp 收到 **4dp**,见 SingleSelectChipRow 里 ChipFlow 调用处的
 *  KDoc。放宽/收窄 1–2dp 不改变上面四个结论 —— 距离边界最近的是「两个长名」316 对
 *  321,仍有 5dp 余量。)
 *
 * ⇒ [96, 156] 这个区间是「一行 2–3 个」的**充要条件**,不是拍出来的。
 *
 * 上限 156 同时决定每行字数:156 − 左右 padding 28 − 边框 2 = 126dp 内容宽,
 * 13.5sp 全角字约 **9 字/行**。
 */
internal val CHIP_MIN_WIDTH: Dp = 96.dp
internal val CHIP_MAX_WIDTH: Dp = 156.dp

/**
 * 芯片标签最多保留几个字符(截断时**首尾都保留**)。
 *
 * 10 → 24(2026-10-22)→ **30(2026-10-23)**。
 *
 * 30 是被宽度区间反推出来的:156dp 上限下每行 9 个全角字,文字折到
 * [CHIP_TEXT_MAX_LINES] = 4 行 ⇒ 理论容量 36 字;再留出行尾折行余量与
 * 「(512)」这类后缀的位置,实测 33 字 + 后缀仍是 4 行、34 字跳 5 行 ⇒ 取 **30**
 * (首 15 + … + 尾 14)。
 *
 * 上限仍然必须有:真实目录名可以长到 30 多个字符,不设上限时一块芯片就占满整宽。
 */
internal const val CHIP_LABEL_MAX_CHARS = 30

/**
 * 芯片**内部文字**最多折几行(兜底上限,两级相同)。
 *
 * 3 → **4**(m00110 用户原话:「内容上限提到4行吧」)。
 *
 * 与 [CHIP_COLLAPSE_LINES] 是两个互不冲突的维度:前者管**一块芯片**里文字折多少行,
 * 后者管**芯片块**排多少行。曾经的 TOP_CHIP_MAX_LINES 把后者钉在 2 行,
 * 已随瀑布流改版作废。
 */
internal const val CHIP_TEXT_MAX_LINES = 4

/**
 * 收起态最多排几行**芯片块**(2026-10-23 用户裁决,替代作废的 TOP_CHIP_MAX_LINES)。
 *
 * **为什么上限从「条数」换成「行数」**:旧实现钉 VISIBLE_SINGLE_SELECT_CHIPS = 5
 * (2026-10-18 真机实测「5 个正好 2 行」),前提是「芯片宽度跟随内容、5 个差不多 2 行」。
 * 现在宽度被钉进 [CHIP_MIN_WIDTH]..[CHIP_MAX_WIDTH]、文字可折 4 行,同样 5 块可能是
 * 2.7 行也可能是 5 行 —— **条数不再能预测视觉高度,行数才是用户看得见的那条线**。
 *
 * 3 行 ≈ 280dp(35% 视口,含 4 行文字芯片的实测值),不给收口的话 40 卷约 20 行
 * ≈ 1860dp,是 2026-10-18 缺陷(834.9dp)的两倍多。
 */
internal const val CHIP_COLLAPSE_LINES = 3

/**
 * **展开态**芯片区的最大高度,超出则在区内部竖向滚动。
 *
 * ## 为什么展开态必须有上限(2026-10-24 评审 C1)
 *
 * 芯片行在宿主页面里是**常驻在滚动容器之外**的(2026-10-18 裁决,否则换卷要先回顶),
 * 代价是它自己不滚。收起态有 [CHIP_COLLAPSE_LINES] 兜着,3 行封顶;一旦展开,
 * 40 卷在 321dp 轨道上约 14 行 ≈ 1200dp,而视口只有 794dp —— 而下方内容靠
 * `weight(1f)` 占剩余空间,会被直接挤成 0。
 *
 * 结果比「变长」糟得多:用户既看不到下面的内容,**也够不到收起入口**,
 * 被困在一个只能靠返回键逃出的界面里。这是 2026-10-18 那个 834.9dp 缺陷的同型形态。
 *
 * 318dp ≈ 40% 视口,与 series 浮层的高度上限取同一个值 —— 两块浮出内容不该有两种上限。
 */
internal val CHIP_EXPANDED_MAX_HEIGHT: Dp = 318.dp

/** 首尾之间用的省略标记。用单字符省略号而不是三个点,省两个字符宽度给正文。 */
private const val CHIP_LABEL_ELLIPSIS = "…"

/**
 * 把过长的芯片标签压到 [maxChars] 以内,**保留首尾**。
 *
 * ## 为什么必须保留尾部
 *
 * 纯尾部省略(TextOverflow.Ellipsis)会把同系列的两本截成一模一样:
 * 「彼女がボクの知らないところで…第01巻」与「…第02巻」—— 截完都只剩前缀,
 * 而**区分它们的信息恰恰在尾部**(卷号、「[DL版]」之类的版本标记)。
 * 首尾双保留让两本在芯片上仍可分辨。
 *
 * ## 为什么要有 maxChars < 3 的退路
 *
 * 首 + 省略 + 尾 至少要 3 个字符。上限被传到 1 或 2 时(理论路径,但这是公开的
 * 纯函数)退化成**纯截断**,而不是拼出比上限更长的串 —— 后者会让调用方以为
 * 「上限生效了」,实际芯片反而更宽。
 */
internal fun truncateChipLabel(label: String, maxChars: Int = CHIP_LABEL_MAX_CHARS): String {
    if (maxChars <= 0) return ""
    if (label.length <= maxChars) return label
    if (maxChars < 3) return label.take(maxChars)
    // 首尾各分一半,余下的 1 个字符给省略标记。maxChars=30 ⇒ 首 15 + … + 尾 14。
    val tail = (maxChars - 1) / 2
    val head = maxChars - 1 - tail
    return label.take(head) + CHIP_LABEL_ELLIPSIS + label.takeLast(tail)
}
