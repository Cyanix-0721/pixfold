package com.cyanix.pixfold.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** 浮层顶部小箭头的宽 / 半宽 / 高(视觉元素,不参与芯片排布)。 */
private val ARROW_WIDTH: Dp = 12.dp
private val ARROW_HALF_WIDTH: Dp = 6.dp
private val ARROW_HEIGHT: Dp = 6.dp

/**
 * 两级芯片行上的一个 **1 级条目**:一个系列,或一个不属于任何系列的单本。
 *
 * 领域层已有两个同构的分组类型(工作流 A 的 `CollectionGroup`、工作流 B 的 `VolumeGroup`),
 * 这里再定义一个 UI 侧的通用形状,是为了让两级芯片行只写一份 —— 两个页面的 tag 体系、
 * 文案、条目类型都不同,但「选中系列后展开其下各单本」的**状态逻辑完全一样**
 * (见 [TwoLevelChipRows])。把状态逻辑复制两遍,下一次改交互就会只改到一半。
 *
 * @param key 1 级条目的稳定标识(key 只用于选中态比较与 testTag,不用于展示)。
 * @param name 显示名。
 * @param isSeries 是否真的是系列(决定点击后是否展开浮层)。
 * @param members 该条目下的条目(系列 = 系列内各本;单本 = 自身,只有 1 个)。
 */
data class TwoLevelGroup<T>(
    val key: String,
    val name: String,
    val isSeries: Boolean,
    val members: List<T>,
)

/**
 * 一行芯片的 tag 体系 —— **每个页面一套**,避免跨页 tag 撞车。
 *
 * 抽出这个 bundle 是因为两级芯片行有**两套** tag;平铺成十几个参数会让调用点无法阅读,
 * 也容易把 1 级与 2 级的 tag 传错位(那是「点了没反应」类缺陷,而且离线断言会一起写错,
 * 变成自洽的假绿)。
 */
data class ChipRowTags(
    /** 常驻行的 testTag。 */
    val selectorTag: String,
    /** 常驻行芯片的 testTag 生成器。 */
    val chipTag: (id: String) -> String,
    /** 「全部 N 项」入口的 testTag。 */
    val allEntryTag: String,
)

/** 浮层高度上限:318dp ≈ 40% 视口高(2026-10-23 用户裁决「选乙」,浮层内滚)。
 *
 * 为什么要有上限:一个系列可以有几十本,浮层若跟着内容长高,会把下方页面整个顶出视口,
 * 用户既看不到浮层底部、也看不到页面主体。给死上限 + 内部竖滚,浮层尺寸就与条目数脱钩。
 * 318dp 来自图纸实测:一屏约 794dp,浮层吃掉 40% 后仍留出 60% 给页面本身。 */
private val CHILD_POPUP_MAX_HEIGHT: Dp = 318.dp

/** 浮层与锚点芯片之间的间隙(画出小箭头的那一条)。 */
private val CHILD_POPUP_GAP: Dp = 6.dp

/**
 * **两级单选芯片行**:1 级选系列或单本,点开系列后在其下方**叠加浮层**列出该系列各本。
 *
 * ## 交互(2026-10-23 用户裁决,m00110 原话)
 *
 * 「series 的单本不放到下面的一个单独遮罩内,而是上面 series 为顶点,展开叠加一个遮罩显示,
 * 再次点击 series 或点击其他 series 或 series 外单本时收缩刚才展开的」
 *
 * 旧实现把 2 级行做成**兄弟行**:点 series → 下面长出一行芯片,**页面内容整体下移**。
 * 三个毛病:①2 级行有几行会把下方内容推多远不可预测(同一条 series,3 本和 12 本差 4 倍);
 * ②长系列把页面主体挤出视口(与 2026-10-18 的 834.9dp 规模回归同型);
 * ③「1 级系列芯片被选中 ⇒ 下面一定有一行」这个隐含约定让 1 级行的选中态与页面布局耦合。
 *
 * 改浮层后:浮层**盖在页面之上**,不参与父布局 ⇒ 页面内容不会因为点开一个系列而整体位移。
 *
 * ## 收起的三条触发(用户明确列举,缺一条都会让用户找不到出口)
 *
 * 1. **再点同一个 series** —— 最自然的 toggle。
 * 2. **点另一个 series** —— 心智上是「换一个看」,旧浮层必须让位。
 * 3. **点 series 外的单本** —— 用户已把注意力移走,浮层不该留。
 *
 * 第四条出口是**点浮层外部**(Popup 的 onDismissRequest),属系统约定不是用户裁决,但同样必要。
 *
 * ## 为什么浮层用 Popup 而不是同层 Box overlay
 *
 * 本组件**必须自带 Column**(见下),而它的高度只有芯片行那么高。同层 Box 的 overlay 会被
 * 父级 bounds 裁掉 —— 浮层根本画不到芯片行下方。Popup 是独立 window,不受父级裁剪,
 * 也能盖住页面其它内容。代价是它不随页面滚动而移动;但芯片行本身是**常驻在滚动容器之外**的
 * (2026-10-18 裁决),所以页面滚动时浮层不需要跟随。
 *
 * ## 三条承重规则(承自旧实现,不改)
 *
 * 1. **单本没有浮层**:单本本身就是叶子,再给它一个只有自己的浮层毫无信息量。
 *    单本 1 级芯片的 tag 由 [singleTopChipTag] 指定 —— 各页面沿用它既有的「条目 tag」,
 *    既有点击这些 tag 的测试因此不受影响。
 * 2. **同一系列内切走再切回来,回到上次看的那一本**:用户在浮层里刚选的那本,
 *    不该因为点了一下 1 级系列芯片就被换掉。状态用 `remember(groups.map { it.key })` 记
 *    —— **不能**直接用 `groups` 实例作 key(见函数体注释:重扫/元数据回填会白清一遍)。
 *    换工作区/换库时 key 序列必然变,该清就清。
 * 3. **必须自带 Column**:两个 `SingleSelectChipRow` 若挂在非 Column 容器下会重叠在
 *    同一位置,bounds 完全一致、点击命中最上层,表现为「点系列芯片却切了/选了书」。
 *    2026-10-19 实测:测试断言失败后打印 bounds 才发现 A 与 A/第01卷 都在 `(67,42)-(185,154)`。
 *
 * @param groups 1 级条目(顺序即展示顺序)。
 * @param activeItemId 当前选中**条目**的 id(不是 1 级 key)。
 * @param idOf 取条目 id。
 * @param onSelect 选中某个条目。
 * @param topTags 1 级行的 tag 体系。
 * @param childTags 浮层内 2 级行的 tag 体系。
 * @param singleTopChipTag 单本 1 级芯片的 tag 生成器。
 * @param topChipDescription 1 级芯片的无障碍文案。
 * @param topChipLabel 1 级芯片标签。
 * @param topAllEntryText 1 级「全部 N 项」文案。
 * @param childChipDescription 浮层内芯片的无障碍文案。
 * @param childChipLabel 浮层内芯片标签。
 * @param childAllEntryText 浮层内「全部 N 项」文案。**当前恒不会显示** ——
 *   浮层内传 `collapseLines = Int.MAX_VALUE`(不收口),因此没有收口芯片。
 *   保留参数是为了两级的调用形态对称,以及将来真要收口时不必改签名。
 */
@Composable
fun <T> TwoLevelChipRows(
    groups: List<TwoLevelGroup<T>>,
    activeItemId: String,
    idOf: (T) -> String,
    onSelect: (String) -> Unit,
    topTags: ChipRowTags,
    childTags: ChipRowTags,
    singleTopChipTag: (String) -> String,
    topChipDescription: @Composable (TwoLevelGroup<T>) -> String,
    topChipLabel: @Composable (TwoLevelGroup<T>) -> String,
    topAllEntryText: @Composable (Int) -> String,
    childChipDescription: @Composable (T) -> String,
    childChipLabel: @Composable (T) -> String,
    childAllEntryText: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    childChipSuffix: @Composable (T) -> String = { "" },
) {
    // ## 只有一个东西可选时整块不渲染
    //
    // 判据是「**条目总数** ≤ 1」,不是「分组数 ≤ 1」:1 个系列含 N 本(N ≥ 2)时
    // 用户需要在系列内切换,N 本必须渲染;而 1 个系列含 1 本与 1 个单本条目一样,
    // 无可切换 —— 这两种才是该早退的形态。
    //
    // (2026-10-24 评审 M3:原注释写的是「把 1 个系列含 N 本也算进需要渲染」,
    //  读起来像 N 可以为 1,与下面的 `members.size == 1` 早退矛盾。行为一直是对的,
    //  注释把判据说岔了。)
    if (groups.isEmpty()) return
    val totalMembers = groups.sumOf { it.members.size }
    if (totalMembers <= 1) return

    val activeItem = groups.asSequence()
        .flatMap { it.members.asSequence() }
        .firstOrNull { idOf(it) == activeItemId }
    // 当前条目不在任何分组里(脏状态)⇒ 退回第一个分组的第一个成员,不让页面白屏。
    val activeGroup = groups.firstOrNull { group -> group.members.any { idOf(it) == activeItemId } }
        ?: groups.first()

    // ## 三个状态的重置键都是**分组 key 序列**,不是 groups 实例
    //
    // 用 `groups` 当 key 时,任何产生新实例的重组都会把状态清掉 —— 工作区重扫、
    // ComicInfo 回填、甚至上游 `remember(collections) { map { … } }` 命中的重建都算。
    // 那些场合「有哪些系列」根本没变,用户却会看到:展开的浮层自己关了、
    // 「上次看的那一本」被忘掉。换成 key 序列后,只有系列的增减才算结构变化。
    val groupKeys = groups.map { it.key }

    // 只按 1 级 key 记「上次看的那一本」;单本没有浮层,用不到(但仍会记,无害)。
    var lastMemberByGroup by remember(groupKeys) { mutableStateOf(emptyMap<String, String>()) }

    // **浮层展开态**(2026-10-23 起替代旧的「2 级兄弟行」)。
    // 用 series 的 key 记,而不是布尔 —— 「换一个 series」与「同 series 再点一次」
    // 是两种不同结果(前者换浮层内容、后者收起),布尔区分不了。
    var openSeriesKey by remember(groupKeys) { mutableStateOf<String?>(null) }

    // ## 锚点必须**按 series key 分别记**,不能只留一个
    //
    // 2026-10-24 评审 C2(真缺陷):原写法是单个 `var anchor`,而 `onChipBounds` 对
    // **每一个** series 芯片都会回调。SubcomposeLayout 按 index 顺序 place,
    // 于是最后一次回调 —— 也就是**最后一个** series 芯片 —— 永远胜出。
    // 点第一个 series 时浮层却长在最后一个下面,与「以 series 芯片为顶点」的裁决正相反。
    //
    // 改成 Map 后,渲染时按 openSeriesKey 取对应那块的矩形。
    var anchors by remember(groupKeys) { mutableStateOf(emptyMap<String, Rect>()) }
    var container by remember { mutableStateOf(Rect.Zero) }

    // **必须自带 Column**:见上面第 3 条承重规则。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { container = it.boundsInWindow() },
    ) {
        SingleSelectChipRow(
            items = groups,
            activeId = activeGroup.key,
            idOf = { it.key },
            onSelect = { key ->
                val group = groups.firstOrNull { it.key == key } ?: return@SingleSelectChipRow
                if (!group.isSeries) {
                    // **收起触发 ③**:点 series 外的单本 —— 注意力已移走,浮层让位。
                    openSeriesKey = null
                    onSelect(idOf(group.members.first()))
                } else if (openSeriesKey == key) {
                    // **收起触发 ①**:再点同一个 series —— toggle,但**不换书**。
                    // 这里刻意不调 onSelect:用户只是想关浮层,不该顺手把当前那本换成第一本。
                    openSeriesKey = null
                } else {
                    // **收起触发 ②** / 首次展开:换到（或打开）这个 series 的浮层。
                    openSeriesKey = key
                    // 只有当前条目**不在**本系列内才需要切换成某本;已在其中时保持不动
                    // (那正是「切走再切回来,回到上次看的那一本」那条规则)。
                    if (group.members.none { idOf(it) == activeItemId }) {
                        val remembered = lastMemberByGroup[key]
                            ?.let { id -> group.members.firstOrNull { idOf(it) == id } }
                        onSelect(idOf(remembered ?: group.members.first()))
                    }
                }
            },
            selectorTag = topTags.selectorTag,
            chipTag = { key ->
                // firstOrNull 而不是 first:chipTag 的 key 来自 items,理论上必在 groups 里,
                // 但一旦上游传入错位,first 会抛 NoSuchElementException 把整页打黑。
                // 取不到就退回 1 级 tag —— 至少可点、可测,不至于崩。
                val group = groups.firstOrNull { it.key == key }
                // 单本沿用调用方给的条目 tag:既有测试点的是它,语义也确实是「这个条目」。
                if (group == null || group.isSeries) {
                    topTags.chipTag(key)
                } else {
                    singleTopChipTag(idOf(group.members.first()))
                }
            },
            allEntryTag = topTags.allEntryTag,
            chipDescription = topChipDescription,
            // 1 级行默认按 [CHIP_COLLAPSE_LINES] 收口(2026-10-23 起)。旧的
            // `maxLines = TOP_CHIP_MAX_LINES`(2 行)已作废:瀑布流里块高不一致,
            // 行数才是视觉上那条线。
            allEntryText = topAllEntryText,
            // 1 级行垫底色,与浮层内的行区分(2026-10-21 用户裁决「加个遮罩」)。
            emphasized = true,
            chipLabel = topChipLabel,
            // 只有 series 芯片需要回报位置 —— 单本点了不展开浮层,不必白量。
            // 按 id 分别存(见上面 anchors 的说明):单个变量会被最后一个芯片覆盖。
            onChipBounds = { id, bounds ->
                val group = groups.firstOrNull { it.key == id }
                // 宽度为 0 = 这一趟还没真正放置(探测副本),不能拿来当锚点 ——
                // 否则首帧会把浮层锚到 (0,0)。
                if (group != null && group.isSeries && bounds.width > 0f) {
                    anchors = anchors + (id to bounds)
                }
            },
        )

        val openGroup = openSeriesKey?.let { key -> groups.firstOrNull { it.key == key && it.isSeries } }
        // 锚点取「被点中的那一枚」的矩形。取不到(芯片还没 place 过)就不弹 ——
        // 弹在 (0,0) 比不弹更糟:用户会以为点错了地方。
        val openAnchor = openSeriesKey?.let { anchors[it] }
        if (openGroup != null && openAnchor != null) {
            ChildChipsPopup(
                anchor = openAnchor,
                // 顶边贴 1 级芯片区下沿(见 ChildChipsPopup KDoc 的 I1 论证)。
                // container 就是这个 Column 的外框,而浮层在独立 Popup 里不占它的高度,
                // 所以 container.bottom 恒为「1 级行(含底托)的下沿」。
                topY = container.bottom,
                // 1 级底托左右各有 8dp 外边距(SingleSelectChipRow 的 padding 在
                // boundsInWindow **之后**才生效,所以 container 给的是**去掉**内边距的外框)。
                // 浮层宽度对齐底托:再各减 8dp。
                containerLeft = container.left + with(LocalDensity.current) { 8.dp.toPx() },
                containerWidth = container.width - with(LocalDensity.current) { 16.dp.toPx() },
                onDismiss = { openSeriesKey = null },
            ) {
                SingleSelectChipRow(
                    items = openGroup.members,
                    // 2 级行的选中态用条目 id;若当前 id 不属于本分组(刚切成系列但还没选具体本),
                    // 会退化为「本行无选中」,由 1 级行指出所在系列。
                    activeId = activeItem?.let(idOf).orEmpty(),
                    idOf = idOf,
                    onSelect = { id ->
                        lastMemberByGroup = lastMemberByGroup + (openGroup.key to id)
                        onSelect(id)
                    },
                    selectorTag = childTags.selectorTag,
                    chipTag = childTags.chipTag,
                    allEntryTag = childTags.allEntryTag,
                    chipDescription = childChipDescription,
                    allEntryText = childAllEntryText,
                    chipLabel = childChipLabel,
                    chipSuffix = childChipSuffix,
                    // **浮层内不收口**:用户已经明确「我要看这个系列的全部」,
                    // 再在浮层里套一层「全部 N 项」等于让用户点两次才看到全部。
                    // 高度上限由外面的卡片负责(超出则内部竖滚)。
                    collapseLines = Int.MAX_VALUE,
                    // 卡片已有底色,内层不再垫底托 —— 两层灰叠在一起会读成嵌套的框。
                    surface = false,
                )
            }
        }
    }
}

/**
 * series 为顶点的浮层卡片:宽度与 1 级底托对齐、顶边贴**1 级芯片区的下沿**、
 * 水平方向用一个小箭头对准锚点芯片的中心。
 *
 * ## 为什么顶边不贴「锚点芯片下沿」而是贴「芯片区下沿」
 *
 * 2026-10-24 评审 I1 指出:series 芯片换行时,锚点若在第 1 行,而浮层从锚点下沿开始,
 * 就会**盖住第 2、3 行的 series 芯片**。而 `Popup(focusable = true)` 会把盖住区域的点击
 * 消费成「点外部 ⇒ 关闭」,于是「点另一个 series 换浮层」这条用户裁决的触发②
 * 在物理上要求点两次(第一次只是关掉浮层)。
 *
 * 顶边贴芯片区下沿后,1 级芯片**一个都不被遮挡**,三条收起触发各自一步可达;
 * 「以哪枚芯片为顶点」由箭头的水平位置表达,垂直方向统一贴底托 ——
 * 这也正是 v3 图纸阶段的原始裁决(`docs/phases` 记的「浮层顶边贴 1 级底托下沿」)。
 *
 * 位置**按实际布局算**(`boundsInWindow()`),不硬编码 —— 芯片换行时区高会变。
 */
@Composable
private fun ChildChipsPopup(
    anchor: Rect,
    topY: Float,
    containerLeft: Float,
    containerWidth: Float,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { CHILD_POPUP_GAP.roundToPx() }
    val gapStart = topY.toInt() + gapPx
    val leftPx = containerLeft.toInt()
    val widthPx = containerWidth.toInt().coerceAtLeast(0)

    // 箭头水平位置(相对卡片左沿):对准锚点中心,再钳进卡片内部留 16dp 边距,
    // 否则最左/最右的 series 芯片会让箭头戳到卡片外面。
    // 钳制必须先 coerceAtLeast 再 coerceAtMost:原写法 `coerceIn(min, maxOf(0, w - min))`
    // 在 w < 48dp 时给出「下限 > 上限」的空区间,直接抛
    // IllegalArgumentException: Cannot coerce value to an empty range。
    // 当前靠「首帧 container 为 Zero 且 openSeriesKey 也为 null」侥幸走不到,
    // 但那是巧合,不是保证 —— 极窄窗口或分屏下就会崩。
    val arrowPad = with(density) { 24.dp.roundToPx() }
    val arrowCenterPx = (anchor.center.x.toInt() - leftPx)
        .coerceAtLeast(arrowPad)
        .coerceAtMost(maxOf(arrowPad, widthPx - arrowPad))

    // ## 定位的三个已知面(2026-10-24 评审 M6)
    //
    // ① **不用 `anchorBounds`** —— 它是 Compose 给的锚点矩形,但这里的锚点是
    //    「1 级芯片区的下沿 + 底托的左右边距」,不是某一枚芯片;而 `anchorBounds`
    //    对应的是 Popup 的**父布局节点**(整个 Column),拿不到那两条修正。
    //    自记的 `boundsInWindow()` 与 `anchorBounds` 在无 insets 时同源,取哪个都一样;
    //    有 insets 时我们仍坚持自记值,因为它与 1 级芯片的实际落点一致。
    // ② **RTL 必须镜像** —— 原写法直接把 `leftPx`(从窗口左沿算的像素)当 x 用,
    //    在 `LayoutDirection.Rtl` 下整个浮层会贴到错误的一侧。这里按窗口宽镜像。
    //    (中文/日文是 LTR,但系统语言切阿拉伯语/希伯来语时这条就会露出来。)
    // ③ **insets 不在窗口坐标系里** —— `windowSize` 含系统栏。浮层底部若落到手势条
    //    上会被压住最多 ~24dp;靠 `CHILD_POPUP_GAP` 与内部竖滚兜,但仍可能遮住
    //    卡片最后一行。这一条**只能真机核对**(§7.4),列进真机清单。
    val positionProvider = remember(leftPx, gapStart, widthPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                // 底部放不下就整体上移(而不是被窗口裁掉一半) —— 浮层被裁掉等于
                // 「全部 N 项」入口的翻版缺陷:内容在,用户够不到。
                val maxY = (windowSize.height - popupContentSize.height - gapPx).coerceAtLeast(0)
                val y = gapStart.coerceAtMost(maxY)
                val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                val rawX = if (layoutDirection == LayoutDirection.Rtl) {
                    // 镜像:从右沿量同样的距离。宽度可能因内容不同而变,故用窗口宽减两份。
                    windowSize.width - popupContentSize.width - leftPx
                } else {
                    leftPx
                }
                val x = rawX.coerceIn(0, maxX)
                return IntOffset(x, y)
            }
        }
    }

    // ## focusable 必须是 false(2026-10-24 真机逮到的真缺陷)
    //
    // 用户裁决(m00110)要求三条收起触发各自**一步**生效,其中触发②是「点另一个
    // series」。原实现用 `PopupProperties(focusable = true)`,真机实测要**点两次**:
    // 第一次只关浮层,第二次才打开新的 —— dump 里第一次点完该系列成员一个都不出现。
    //
    // 机制:focusable 的 Popup 是 FLAG_NOT_TOUCH_MODAL 但**持焦点**的独立窗口。
    // 落在窗口**之外**的触摸被它当成「点外部」消费成 onDismissRequest,
    // 且该次手势**不透传**给下层窗口。浮层顶边虽已贴到芯片区下沿(评审 I1 的修法,
    // 1 级芯片一枚都不被遮挡),遮挡不是原因 —— 抢焦点才是。
    //
    // 改成 false 后窗口不持焦点,窗口外的触摸直接落到主窗口 ⇒ 触发② 一步到位。
    // 代价与补偿:
    // ① **失去「点外部关闭」** —— 但那个出口用户裁决里本来就没有(它不是三条之一),
    //    现在改由「点其他 series / 点单本」承担,这两条本来就该一步生效;
    // ② **返回键要自己接** —— 非 focusable 窗口收不到返回键,改由下面的
    //    [BackHandler] 在 Activity 的 dispatcher 上兜,保住「困在浮层里」的出口。
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
    ) {
        // 非 focusable 窗口收不到系统返回键(见上面 focusable = false 的论证),
        // 由 Activity 的分发器兜住这条出口 —— 否则用户开着浮层按返回会直接退出页面。
        BackHandler(enabled = true) { onDismiss() }
        Surface(
            modifier = Modifier.width(with(density) { widthPx.toDp() }),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f),
            shadowElevation = 6.dp,
        ) {
            // 高度上限放在**整个 Column** 上,不只管滚动的那个 Box ——
            // 否则顶上的 6dp 箭头在限高之外,实际高度是 318 + 6dp,
            // 与「≈40% 视口」的说法对不上(评审 M8)。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 318dp 是卡片(含箭头)的高度上限。超出的部分由内层竖滚消化。
                    .heightIn(max = CHILD_POPUP_MAX_HEIGHT + ARROW_HEIGHT),
            ) {
                // 小箭头:告诉用户这块浮层是哪一枚芯片长出来的。
                // 用 Box + 宽度对齐,而不是 Canvas 画的三角 —— 便于与卡片圆角对齐。
                Box(
                    modifier = Modifier
                        .padding(start = with(density) { arrowCenterPx.toDp() } - ARROW_HALF_WIDTH)
                        .width(ARROW_WIDTH),
                ) {
                    Row(
                        modifier = Modifier.width(ARROW_WIDTH),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        ArrowUp()
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    content()
                }
            }
        }
    }
}

/** 浮层顶部的小三角:一个 12dp 宽的菱形上半,颜色与卡片一致。 */
@Composable
private fun ArrowUp() {
    val color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.98f)
    Box(
        modifier = Modifier
            .width(ARROW_WIDTH)
            .height(ARROW_HEIGHT)
            .drawBehind {
                val path = Path().apply {
                    moveTo(0f, size.height)
                    lineTo(size.width / 2f, 0f)
                    lineTo(size.width, size.height)
                    close()
                }
                drawPath(path, color)
            },
    )
}
