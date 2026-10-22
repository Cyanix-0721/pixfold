package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyanix.pixfold.R

/**
 * 单选芯片行最多常驻几个:超过就限个数并给「全部 N 个」入口。
 *
 * 5 是 2026-10-18 真机实测出来的数(361dp 宽下 5 个芯片正好排 2 行、占 294px);
 * 不按宽度测量动态算 —— Robolectric 把文本宽度算成 ~0,测量式判据离线无法断言。
 */
internal const val VISIBLE_SINGLE_SELECT_CHIPS = 5

/**
 * 芯片标签最多保留几个字符(截断时**首尾都保留**)。
 *
 * **10 → 24(2026-10-22 用户改判:「显示太短了,首尾几个字都要显示」)**。
 * 原值 10 的前提是「芯片单行、宽度跟随内容」—— 那时首 5 + … + 尾 4,一块芯片只剩
 * 七八个字,`[青水庵 (にやすけ 青水庵)] 子産み島2&3～今、お腹あいてるよ～` 在真机上
 * 被压成 `子産み島2…～(2)`,同系列的两本照样分不清是哪本。
 *
 * 现在 [CHIP_TEXT_MAX_LINES] 允许芯片内文字折到 3 行 ⇒ 高度方向已有界,**宽度上限
 * 就该放宽到「够看出一本书是谁」而不是「够排进一行」**。24 字在 361dp 宽下约折 2 行
 * (每行 ~12 个全角字),既保住首尾的关键区别信息(前缀作者 + 尾部卷号/版本),
 * 又不至于一块芯片吃掉整屏。
 *
 * 上限仍然必须有:真实目录名可以长到 30 多个字符,不设上限时一块芯片就占满整宽,
 * 瀑布流退化成一行一块。
 */
internal const val CHIP_LABEL_MAX_CHARS = 24

/**
 * 芯片**内部文字**最多折几行(兜底上限,两级相同)。
 *
 * 用户 2026-10-21 的完整口径是「chip内容可换行显示,太长时显示首尾中间,所以长和宽
 * 都可以伸缩,只是要设置个上限避免太大」⇒ **宽度上限靠字符数**(见
 * [CHIP_LABEL_MAX_CHARS]),**高度上限靠行数**(本条)。两个方向都要有界,否则一个
 * 30 字的目录名会把芯片撑成一整块。
 *
 * ⚠️ 这条**不是**用户说的「第一层 chip 最多显示 2 行」—— 那句经 2026-10-21 真机
 * 反问后已澄清为「一级芯片**行**最多排 2 行芯片」,对应 [TOP_CHIP_MAX_LINES]。
 * 两件事曾被混为一谈,导致整个一级行被压掉。
 */
internal const val CHIP_TEXT_MAX_LINES = 3

/**
 * 一级芯片**行**最多排几行芯片(2026-10-21 用户裁决「第一层chip最多显示2行」,
 * 经真机反问澄清:「芯片行最多 2 行」)。
 *
 * **恒定 2 行,不随「是否选中系列」变化**。原规则是「选中系列后收缩到 1 行,把高度
 * 让给 2 级行」,但用户明确要求「点击 series 后也是 2 行 + 下拉,而不是 1 行 + 下拉」——
 * 收缩会让下方 2 级行上下窜,也让「刚点完系列」这一瞬看起来像另一套排版。
 * 超出 2 行的系列由全宽下箭头入口展开。
 */
internal const val TOP_CHIP_MAX_LINES = 2
/** 首尾之间用的省略标记。用单字符 `…` 而不是 `...`,省两个字符宽度给正文。 */
private const val CHIP_LABEL_ELLIPSIS = "…"

/**
 * 把过长的芯片标签压到 [maxChars] 以内,**保留首尾**。
 *
 * ## 为什么必须保留尾部
 *
 * 纯尾部省略(`TextOverflow.Ellipsis`)会把同系列的两本截成一模一样:
 * `彼女がボクの知らないところで…第01巻` 与 `…第02巻` —— 截完都只剩前缀,
 * 而**区分它们的信息恰恰在尾部**(卷号、`[DL版]` 之类的版本标记)。
 * 首尾双保留让两本在芯片上仍可分辨。
 *
 * ## 为什么要有 `maxChars < 3` 的退路
 *
 * 首 + 省略 + 尾 至少要 3 个字符。上限被传到 1 或 2 时(理论路径,但这是公开的
 * 纯函数)退化成**纯截断**,而不是拼出比上限更长的串 —— 后者会让调用方以为
 * 「上限生效了」,实际芯片反而更宽。
 */
internal fun truncateChipLabel(label: String, maxChars: Int = CHIP_LABEL_MAX_CHARS): String {
    if (maxChars <= 0) return ""
    if (label.length <= maxChars) return label
    if (maxChars < 3) return label.take(maxChars)
    // 首尾各分一半,余下的 1 个字符给省略标记。maxChars=24 ⇒ 首 12 + … + 尾 11。
    val tail = (maxChars - 1) / 2
    val head = maxChars - 1 - tail
    return label.take(head) + CHIP_LABEL_ELLIPSIS + label.takeLast(tail)
}

/**
 * 单选芯片行的**全站唯一实现**:从 N 个具名条目里选 1 个。
 *
 * 当前用户(2026-10-19 用户裁决「只统一集合芯片与卷芯片」):
 * - [VolumeChipRow] —— 工作流 B 步骤 2 元数据页 / 步骤 3 打包预览页的换卷行
 * - 工作流 A 步骤 1 的集合/系列芯片行(两级)
 *
 * 其余 `FilterChip` 用法(布尔开关、排序字段枚举、冲突策略等)语义不同 —— 它们编辑的是
 * **参数**而不是「切换当前对象」,也没有溢出处理问题,不往这里塞。
 *
 * ## 为什么必须共用一份实现
 *
 * 三次真机缺陷都出自同一类错误(见下),两处各写一遍必然再犯:
 * 1. **2026-09-25**:原为 `Row + horizontalScroll`,5 卷时第 4、5 卷完全落在视口外,
 *    只能靠横滑够到,而界面上没有任何提示说明这里能滑 ⇒ 改 `FlowRow` 自动换行。
 *    横向滚动把可操作控件挤出视口,是本项目反复踩过的坑。
 * 2. **2026-10-18**:芯片行作为 `LazyColumn` 第一个 item 时一滚就整体滚出视口,
 *    换卷必须先回顶;而换卷是高频动作 ⇒ 移到滚动容器**之外**常驻。
 * 3. **2026-10-18(规模回归)**:常驻 + `FlowRow` 不设个数上限时,条目一多就把整块顶高
 *    —— 40 卷实测芯片块 2296px(834.9dp),几乎吃满 835.3dp 视口,**下方内容永久不可达**。
 *    ⇒ 超过 [VISIBLE_SINGLE_SELECT_CHIPS] 时只常驻 5 个(前 4 个 + 当前项,见
 *    [visibleSingleSelectItems]),另给「全部 N 个」入口,点开用 `ModalBottomSheet` 列全量。
 *
 * **为什么不额外加 `FlowRow(maxLines = 2)` 兜底**(试过,变异存活):常驻芯片已被
 * [visibleSingleSelectItems] 限到 5 个,`maxLines = 2` 永远不触发 ⇒ 它不承重;而在更窄的
 * 屏幕上 5 个芯片可能排到第 3 行,`maxLines = 2` 反而会把第 5 个(可能正是当前项)裁掉
 * 且不给任何提示。**承重的是条数上限,不是行数上限。**
 *
 * @param items 全部条目(顺序即展示顺序)。
 * @param activeId 当前项 id —— 必须留在常驻行内,否则用户看不出自己在看哪个。
 * @param idOf 取条目 id。
 * @param onSelect 选中某项。
 * @param selectorTag 常驻行的 testTag(各页不同,避免跨页冲突)。
 * @param chipTag 常驻行芯片的 testTag 生成器。
 * @param allEntryTag 「全部 N 个」入口的 testTag。
 * @param sheetTag sheet 容器的 testTag。
 * @param sheetListTag sheet 内列表的 testTag。
 * @param sheetChipTag sheet 内芯片的 testTag 生成器(与常驻行区分)。
 * @param chipDescription 芯片的无障碍文案(`semantics {}` 的 lambda 不是 @Composable,
 *   故由调用方在外层解析好再传进来)。
 * @param chipLabel 芯片标签 —— 各页文案不同(打包页还要标出「未勾选」项)。
 * @param allEntryText 「全部 N 个」入口与 sheet 标题的文案(计数由本组件传入)。
 *   卷芯片用「全部 N 卷」,集合芯片用「全部 N 项」—— 统一组件不能把量词写死。
 * @param maxLines 常驻行的最大行数。默认不限(自动换行到底)。
 *   两级芯片的 1 级行传 2(2026-10-21 用户裁决「第一层chip最多显示2行」),
 *   超出部分改由全宽下箭头入口展开 —— 一级行原来在选中系列时收缩到 1 行,
 *   那条规则已被 2 行上限取代:**2 行是硬上限,不再随选中状态变化**。
 *   行数被裁到 1 行时溢出入口放宽为「条目多于 1 个」,2 行及以上按常驻上限判断
 *   (见 [rowCramped] 的理由),否则被裁掉的那部分条目彻底不可达。
 */
@Composable
fun <T> SingleSelectChipRow(
    items: List<T>,
    activeId: String,
    idOf: (T) -> String,
    onSelect: (String) -> Unit,
    selectorTag: String,
    chipTag: (String) -> String,
    allEntryTag: String,
    sheetTag: String,
    sheetListTag: String,
    sheetChipTag: (String) -> String,
    chipDescription: @Composable (T) -> String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    allEntryText: @Composable (Int) -> String = { n -> pluralStringResource(R.plurals.volume_all, n, n) },
    emphasized: Boolean = false,
    chipLabel: @Composable (T) -> String,
    chipSuffix: @Composable (T) -> String = { "" },
    chipTextMaxLines: Int = CHIP_TEXT_MAX_LINES,
) {
    val total = items.size
    // 限行之后**看不见的条目不能再靠换行够到**,而一行的容量离线测不出来
    // (Robolectric 把文本宽度算成 ~0)⇒ 只要行数会被裁,就必须给溢出入口。
    //
    // **为什么不按行数分档**(2026-10-21 试过 `maxLines < 2`,已退回):一级行从 1 行
    // 改成 2 行上限后,我一度认为「2 行装得下 5 个芯片,不必再放宽」。这个推断只能靠
    // 真机读数撑住 —— 芯片宽度跟随内容,10 个字符的芯片约 170dp,361dp 屏一行只放得下
    // 2 个 ⇒ 2 行只装 4 个,**小于常驻上限 5**。也就是说 5 个长名系列时第 5 个会被
    // `FlowRow(maxLines = 2)` 直接裁掉,而 `total > 5` 又不成立 ⇒ 它既看不见、也没有
    // 任何入口可达 —— 正是 2026-09-25 缺陷的形态。宁可对两个条目的界面多给一个入口
    // (它只是多占一行高度),也不能赌「2 行一定够」。
    val rowCramped = maxLines != Int.MAX_VALUE
    val overflowing = total > VISIBLE_SINGLE_SELECT_CHIPS || (rowCramped && total > 1)
    val visible = if (total > VISIBLE_SINGLE_SELECT_CHIPS) {
        visibleSingleSelectItems(items, activeId, idOf, VISIBLE_SINGLE_SELECT_CHIPS)
    } else {
        items
    }
    var sheetOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // **1 级 / 2 级视觉区分**(2026-10-21 用户裁决「加个遮罩」)。
            //
            // 两条由真机反馈修正过的细节:
            // ① **必须留白不顶边** —— 首版把色块铺满整行宽,真机看起来像一条横贯屏幕的
            //    分隔条(用户:「你这个遮罩都顶到边缘了很难看」)。8dp 外边距让色块成为
            //    一块「底托」而不是「横线」。
            // ② **两级都要有底色,只差强度** —— 首版只给 1 级垫、2 级透明,用户反馈
            //    「只有第一层又第二层又没有也很难看」:一层有一层没有读起来像画漏了。
            //    两级都垫,1 级 0.45 / 2 级 0.18,层级关系由深浅表达。
            // 用半透明 surfaceVariant 而不是实色:深浅两套主题下都不需要另配色。
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = if (emphasized) 0.45f else 0.18f,
                ),
                shape = RoundedCornerShape(12.dp),
            ),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(selectorTag),
            // **左对齐**(用户 2026-10-21:「不是 chip 本身居中」)。曾按「1 级芯片没有
            // 居中显示」的反馈改成每行居中,但那句话指的是**芯片内的文字**,不是芯片
            // 在行里的位置。瀑布流的本义就是从左上起排、各行左端对齐;居中会让每一行
            // 的起点随该行芯片总宽左右漂,读起来像没对齐。文字居中由
            // `SingleSelectChip` 的 `textAlign` 负责。
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            maxLines = maxLines,
        ) {
            visible.forEach { item ->
                SingleSelectChip(
                    item = item,
                    id = idOf(item),
                    selected = idOf(item) == activeId,
                    tag = chipTag(idOf(item)),
                    chipDescription = chipDescription,
                    chipLabel = chipLabel,
                    chipSuffix = chipSuffix,
                    textMaxLines = chipTextMaxLines,
                    onSelect = onSelect,
                )
            }
        }
        if (overflowing) {
            // **全宽下箭头长按钮**(2026-10-21 用户裁决「多的加个屏框的下箭头长按钮
            // 替代现在的文字按钮」)。改成整宽是因为文字按钮的点击区只有文字那么宽,
            // 而它是溢出后**唯一**能到达其余条目的入口 —— 让人瞄准一小段文字才点得到,
            // 是把可达性交给运气。下箭头是「还有更多、往下展开」的固定语义。
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = { sheetOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag(allEntryTag),
                ) {
                    Text(allEntryText(total))
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_arrow_drop_down),
                        contentDescription = null,
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        AllItemsSheet(
            items = items,
            activeId = activeId,
            idOf = idOf,
            onSelect = onSelect,
            onDismiss = { sheetOpen = false },
            sheetTag = sheetTag,
            sheetListTag = sheetListTag,
            sheetChipTag = sheetChipTag,
            chipDescription = chipDescription,
            chipLabel = chipLabel,
            chipSuffix = chipSuffix,
            chipTextMaxLines = chipTextMaxLines,
            allEntryText = allEntryText,
        )
    }
}

/**
 * 溢出时页面上仍常驻哪几项:前 [maxVisible] - 1 项 + 当前项。
 *
 * 当前项必须留在行内(否则用户看不出"我在改哪个");它若已在前几项里,
 * 就顺延补足到 [maxVisible] 个。纯函数,便于离线断言。
 */
internal fun <T> visibleSingleSelectItems(
    items: List<T>,
    activeId: String,
    idOf: (T) -> String,
    maxVisible: Int,
): List<T> {
    if (maxVisible <= 0) return emptyList()
    val head = items.take(maxVisible - 1)
    val active = items.firstOrNull { idOf(it) == activeId }
    return when {
        active == null -> items.take(maxVisible)
        head.any { idOf(it) == activeId } -> items.take(maxVisible)
        else -> head + active
    }
}

/**
 * 芯片本体:常驻行与「全部 N 个」sheet 共用,tag 与文案由调用方给。
 *
 * ## 三条排布规则都在这里,而且只能在这里
 *
 * 1. **宽度跟随内容**:`FilterChip` 不设 `fillMaxWidth` / `weight`,宽度由 label 撑开
 *    —— 用户的裁决是「chip跟随内容长度变化」,不是等宽网格。
 * 2. **内部文字居中**:`textAlign = TextAlign.Center`。注意它只在**芯片比文字宽**时看得见
 *    —— 单行且宽度正好包住内容时居中不产生任何位移,那是正常的;真正生效的场合是
 *    **文字折行**(第二行比第一行短,不居中就左对齐成锯齿)与将来的统一最小宽度。
 *    刻意**不**给 label 加 `fillMaxWidth`:那会把芯片撑到父级宽度、破坏「跟随内容」。
 * 3. **内容可换行**:`softWrap = true`。芯片数量占几行由行容器的 `maxLines` 管,
 *    与「单块芯片内文字折行」是两个互不冲突的维度 —— 用户 2026-10-21 特意点明过。
 */
@Composable
private fun <T> SingleSelectChip(
    item: T,
    id: String,
    selected: Boolean,
    tag: String,
    chipDescription: @Composable (T) -> String,
    chipLabel: @Composable (T) -> String,
    chipSuffix: @Composable (T) -> String,
    textMaxLines: Int,
    onSelect: (String) -> Unit,
) {
    val description = chipDescription(item)
    // **截断只作用于名称,后缀完整保留**:后缀是「未勾选」这类固定短标记,
    // 长度不随数据增长;把它一起截断会让「这卷被排除」这条关键信息时有时无
    // —— 名字越长越容易被砍掉,正好是最需要看标记的场合(2026-10-21 改判)。
    val label = truncateChipLabel(chipLabel(item)) + chipSuffix(item)
    FilterChip(
        selected = selected,
        onClick = { onSelect(id) },
        label = {
            Text(
                text = label,
                textAlign = TextAlign.Center,
                // 显式给 `softWrap`:将来有人想改行数时,会先看到这一行注释,
                // 知道它动的是「可换行」这条用户裁决,而不是排版微调。
                softWrap = true,
                // 行数上限是**芯片内文字**的行数(见 [CHIP_TEXT_MAX_LINES]):
                // 传 `CHIP_TEXT_MAX_LINES`(两级相同)。`overflow` 用 `Ellipsis` 兜超出的行,
                // 因为这里已有前置的字符级截断(首尾保留),剩下的溢出只可能是
                // 「2 行塞不下」,再切字符没有意义。
                maxLines = textMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = Modifier
            .testTag(tag)
            .semantics { contentDescription = description },
    )
}

/**
 * 「全部 N 个」sheet:列出所有条目供切换(只在条目数超 [VISIBLE_SINGLE_SELECT_CHIPS] 时可达)。
 *
 * 用 `ModalBottomSheet` 而不是新页面:切换是**轻量且可取消**的动作,不需要页面级
 * 导航状态(也就不会把工作流的返回栈搅乱);也不引新依赖 —— material3 已带。
 * 选中后立即关闭:页面内容已经随 `onSelect` 更新,再播一段 hide 动画只是延迟。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> AllItemsSheet(
    items: List<T>,
    activeId: String,
    idOf: (T) -> String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    sheetTag: String,
    sheetListTag: String,
    sheetChipTag: (String) -> String,
    chipDescription: @Composable (T) -> String,
    chipLabel: @Composable (T) -> String,
    chipSuffix: @Composable (T) -> String,
    chipTextMaxLines: Int,
    allEntryText: @Composable (Int) -> String,
) {
    // 底部弹层走 `PixFoldBottomSheet`：背景模糊、圆角、底色全站只有一个来源
    // （用户 2026-10-20 要求「所有弹窗都是这个效果」）。
    PixFoldBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        tag = sheetTag,
    ) {
        Text(
            text = allEntryText(items.size),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                // **水平内边距必须给在列表上**,不能只给标题:标题缩进 16dp 而芯片贴到
                // 屏幕左沿,视觉上像两栏错位(2026-10-21 真机反馈「太靠左贴边了」)。
                // 16dp 与标题对齐,芯片块因此与标题左沿齐平。
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .testTag(sheetListTag),
        ) {
            items(items, key = { idOf(it) }) { item ->
                SingleSelectChip(
                    item = item,
                    id = idOf(item),
                    selected = idOf(item) == activeId,
                    tag = sheetChipTag(idOf(item)),
                    chipDescription = chipDescription,
                    chipLabel = chipLabel,
                    chipSuffix = chipSuffix,
                    textMaxLines = chipTextMaxLines,
                    onSelect = { id ->
                        onSelect(id)
                        onDismiss()
                    },
                )
            }
        }
    }
}
