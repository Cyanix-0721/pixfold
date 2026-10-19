package com.cyanix.pixfold.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

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
 * @param isSeries 是否真的是系列(决定 2 级行是否出现、1 级行是否收缩)。
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
 * 抽出这个 bundle 是因为两级芯片行有**两行**、每行都要 6 个 tag;平铺成 12 个参数会让
 * 调用点无法阅读,也容易把 1 级与 2 级的 tag 传错位(那是「点了没反应」类缺陷,
 * 而且离线断言会一起写错,变成自洽的假绿)。
 */
data class ChipRowTags(
    /** 常驻行的 testTag。 */
    val selectorTag: String,
    /** 常驻行芯片的 testTag 生成器。 */
    val chipTag: (id: String) -> String,
    /** 「全部 N 项」入口的 testTag。 */
    val allEntryTag: String,
    /** sheet 容器 / 列表 / sheet 内芯片的 testTag。 */
    val sheetTag: String,
    val sheetListTag: String,
    val sheetChipTag: (id: String) -> String,
)

/**
 * **两级单选芯片行**:1 级选系列或单本,选中系列后其下再列一行各单本。
 *
 * 用户 2026-10-19 裁决(原话「芯片分2级别,series或单本作为1级,series作为2级,如果选择
 * series,收缩series芯片组到一行,下面添加显示2级芯片组选择series下的单本」),随后要求
 * 推广到工作流 A 的步骤 2 与工作流 B 的元数据步骤 —— 三处共用**这一份**实现。
 *
 * ## 三条承重规则
 *
 * 1. **1 级行在选中系列时收缩到一行**(`maxLines = 1`):否则两级行会把下方内容挤到视口外
 *    (2026-10-18 规模回归:40 卷芯片块 834.9dp 吃满 835.3dp 视口)。
 * 2. **单本没有 2 级行**:单本本身就是叶子,再给它一行只有自己的芯片毫无信息量,
 *    还平白吃掉一行高度。单本 1 级芯片的 tag 由 [singleTopChipTag] 指定 ——
 *    各页面沿用它既有的「条目 tag」,既有点击这些 tag 的测试因此不受影响。
 * 3. **同一系列内切走再切回来,回到上次看的那一本**:用户在 2 级行里刚选的那本,
 *    不该因为点了一下 1 级系列芯片就被换掉。状态用 `remember(groups)` 记 ——
 *    换工作区/换库时必须清空(旧 key 在新数据里不存在)。
 *
 * @param groups 1 级条目(顺序即展示顺序)。
 * @param activeItemId 当前选中**条目**的 id(不是 1 级 key)。
 * @param idOf 取条目 id。
 * @param onSelect 选中某个条目。
 * @param topTags 1 级行的 tag 体系。
 * @param childTags 2 级行的 tag 体系。
 * @param singleTopChipTag 单本 1 级芯片的 tag 生成器。
 * @param topChipDescription 1 级芯片的无障碍文案。
 * @param topChipLabel 1 级芯片标签。
 * @param topAllEntryText 1 级「全部 N 项」文案。
 * @param childChipDescription 2 级芯片的无障碍文案。
 * @param childChipLabel 2 级芯片标签。
 * @param childAllEntryText 2 级「全部 N 项」文案。
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
    topChipLabel: @Composable (TwoLevelGroup<T>) -> Unit,
    topAllEntryText: @Composable (Int) -> String,
    childChipDescription: @Composable (T) -> String,
    childChipLabel: @Composable (T) -> Unit,
    childAllEntryText: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
) {
    // 只有一个单本条目 ⇒ 没有可切的东西,整块不渲染(与 D1 的 `if (collections.size > 1)`
    // 等价,但把「1 个系列含 N 本」也算进需要渲染 —— 那种结构下平铺展示会让各本混在一起)。
    if (groups.isEmpty()) return
    if (groups.size == 1 && groups.single().members.size == 1) return

    val activeItem = groups.asSequence()
        .flatMap { it.members.asSequence() }
        .firstOrNull { idOf(it) == activeItemId }
    // 当前条目不在任何分组里(脏状态)⇒ 退回第一个分组的第一个成员,不让页面白屏。
    val activeGroup = groups.firstOrNull { group -> group.members.any { idOf(it) == activeItemId } }
        ?: groups.first()

    // 只按 1 级 key 记「上次看的那一本」;单本没有 2 级行,用不到(但仍会记,无害)。
    var lastMemberByGroup by remember(groups) { mutableStateOf(emptyMap<String, String>()) }

    // **必须自带 Column**:两行芯片是本组件的两个兄弟节点。若调用点恰好不是 Column 作用域
    // (例如测试里把本组件直接当根组合、或将来塞进 Box),两个 `SingleSelectChipRow` 会重叠在
    // 同一个位置 —— 1 级与 2 级芯片的 bounds 完全一致,点击命中最上层,表现为「点系列芯片却
    // 切了/选了书」。2026-10-19 实测:测试断言失败后打印 bounds 才发现 A 与 A/第01卷 都在
    // `(67,42)-(185,154)`。自带 Column 后无论调用方是什么容器都只有一份正确排布。
    Column(modifier = modifier.fillMaxWidth()) {
        SingleSelectChipRow(
            items = groups,
            activeId = activeGroup.key,
            idOf = { it.key },
            onSelect = { key ->
                val group = groups.firstOrNull { it.key == key } ?: return@SingleSelectChipRow
                // 已经在这个分组里:不要因为点了一下 1 级芯片就跳到第一个成员
                // (那会把用户在 2 级行里刚选的那一本换掉)。
                if (group.members.any { idOf(it) == activeItemId }) return@SingleSelectChipRow
                val remembered = lastMemberByGroup[key]
                    ?.let { id -> group.members.firstOrNull { idOf(it) == id } }
                onSelect(idOf(remembered ?: group.members.first()))
            },
            selectorTag = topTags.selectorTag,
            chipTag = { key ->
                val group = groups.first { it.key == key }
                // 单本沿用调用方给的条目 tag:既有测试点的是它,语义也确实是「这个条目」。
                if (group.isSeries) topTags.chipTag(key) else singleTopChipTag(idOf(group.members.first()))
            },
            allEntryTag = topTags.allEntryTag,
            sheetTag = topTags.sheetTag,
            sheetListTag = topTags.sheetListTag,
            sheetChipTag = topTags.sheetChipTag,
            chipDescription = topChipDescription,
            // 选中系列后 1 级行收缩到一行,把高度让给 2 级行(用户裁决)。
            // 单本没有 2 级行,不压缩 —— 压了只会把别的单本挤出屏幕。
            maxLines = if (activeGroup.isSeries) 1 else Int.MAX_VALUE,
            allEntryText = topAllEntryText,
            chipLabel = topChipLabel,
        )

        if (activeGroup.isSeries) {
            SingleSelectChipRow(
                items = activeGroup.members,
                // 2 级行的选中态用条目 id;若当前 id 不属于本分组(刚切成系列但还没选具体本),
                // 会退化为「本行无选中」,由 1 级行指出所在系列。
                activeId = activeItem?.let(idOf).orEmpty(),
                idOf = idOf,
                onSelect = { id ->
                    lastMemberByGroup = lastMemberByGroup + (activeGroup.key to id)
                    onSelect(id)
                },
                selectorTag = childTags.selectorTag,
                chipTag = childTags.chipTag,
                allEntryTag = childTags.allEntryTag,
                sheetTag = childTags.sheetTag,
                sheetListTag = childTags.sheetListTag,
                sheetChipTag = childTags.sheetChipTag,
                chipDescription = childChipDescription,
                allEntryText = childAllEntryText,
                chipLabel = childChipLabel,
            )
        }
    }
}
