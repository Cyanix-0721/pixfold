package com.cyanix.pixfold.domain.workflowa

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.sort.PageOrderState
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.sort.sortItems

/**
 * 工作区「实时刷新」之后的**增量对账**（D4，2026-10-19）。
 *
 * ### 为什么需要它
 * 用户在文件管理器里增删改图片后，工作区会重扫。若直接把扫描结果当成新状态，
 * 用户此前手工拖过的顺序、打过的图钉、改过的文件名会被**整个清空** —— 而这
 * 些调整往往是他花了几分钟做出来的，一次无关的「补了一张图」就全没了。
 *
 * 反过来说，**也不能**在重扫后保留旧状态了事：删掉的图会留在页序里指向不
 * 存在的文件，打包时才发现缺页。所以必须是**对账**：留下来的保留调整，走掉的
 * 丢弃，新来的按规则插到它该在的位置。
 *
 * ### 判据为什么用 id 而不是文件名
 * 文件在 SAF 里的身份是 documentUri（[SourceItem.id]）。用文件名判等会把
 * 「删除 a.jpg 再新建一个同名 a.jpg」当成同一张图，从而把旧的手工顺序、旧
 * 的改名硬套到一张**内容完全不同**的图上 —— 那比丢失调整更糟。
 *
 * 本文件全部是纯函数：不碰 IO、不碰平台类，便于离线断言每一条边界。
 */

/**
 * 把旧的页序状态对账到新的图片列表上。
 *
 * 语义（按顺序判定）：
 * 1. **id 集合完全相同 → 顺序与人工标记原样保留**，但每项换成刷新后的元数据
 *    （文件名/大小/时间戳）。连元数据也没变才返回 `old` 同一实例 —— 这是「内容没变」
 *    的热路径，返回同一实例能让下游 `remember(state)` / `equals` 判等直接短路，界面不抖。
 * 2. **保留项**（id 仍在）按 `old.order` 的相对顺序排列 —— 用户的拖拽结果得以保留，
 *    即使自动规则现在会排出另一个顺序。
 * 3. **新增项**（旧状态里没有的 id）按 [sortItems] 对 `old.rule` 的自动位置插入：
 *    在自动顺序里向前找最近的**保留项**作锚点，插到它后面；找不到锚点就插到最前。
 * 4. `manualIds` / `pinnedIds` 与新的 id 集求交集 —— 指向已删除图片的标记必须清掉，
 *    否则 `manualCount` 之类会虚高、并且这些 id 永远不可能再被命中。
 *
 * **元数据变化（例如重命名）必须进 `order`**：文件身份是 documentUri，但**不是所有
 * provider 都在重命名后换 uri**（ExternalStorageProvider 就保留）。若第 1 条只看 id
 * 集合、命中后原样返回 `old`，界面会一直显示改名前的老文件名 —— 而「重命名后工作区
 * 应刷新」正是用户提这个需求时的原话。故 id 集合不变时仍逐项换成新元数据（顺序、
 * `manualIds`、`pinnedIds` 全保留），只有连元数据也没变才返回同一实例。
 *
 * @param old 刷新前的页序状态（含用户的人工调整）
 * @param images 刷新后扫描到的图片列表
 */
fun reconcilePageOrder(old: PageOrderState, images: List<SourceItem>): PageOrderState {
    val oldIds = old.order.mapTo(HashSet()) { it.id }
    val newIds = images.mapTo(HashSet()) { it.id }

    if (oldIds == newIds) {
        // 顺序不变，但每项换成刷新后的元数据（文件名/大小/时间戳都以扫描结果为准）。
        val byId = images.associateBy { it.id }
        val refreshed = old.order.map { byId[it.id] ?: it }
        // 热路径：元数据也没变 ⇒ 返回同一实例，调用方可据此跳过重组。
        if (old.images == images && refreshed == old.order) return old
        return old.copy(images = images, order = refreshed)
    }

    val kept = old.order.filter { it.id in newIds }
    val keptIds = kept.mapTo(HashSet()) { it.id }

    // 自动顺序只算一次：新增项的相互顺序与锚点判定都必须基于同一份基准，
    // 否则恒等兜底（sortItems 在全部键相等时按 id 排）会在两次调用间给出不同参照。
    val auto = sortItems(images, old.rule)
    // 判据取 `keptIds` 而不是 `oldIds`：正常状态下二者等价（`oldIds` 就是 `old.order`
    // 的 id 集），但万一旧状态本身已经不一致（`order` 少了某个 `images` 里有的图），
    // 用 `oldIds` 会把那张图**两边都不收**——最后 `order.size != images.size`，
    // 打包时表现为「少了一页」且没有任何报错。用 `keptIds` 兜住：凡没被保留的，
    // 一律按规则重新落位。
    val addedInAuto = auto.filter { it.id !in keptIds }

    val merged = kept.toMutableList()
    // 多个新增项可能锚在同一个保留项后面（例如中间插进来三张图）：记下每个锚点
    // 上「最后插进去的那一项」，后来的接在它后面，避免顺序被逐个前插反转。
    val lastInsertedAfter = HashMap<String, String>()
    for (item in addedInAuto) {
        val anchorId = nearestKeptIdBefore(item.id, auto, keptIds)
        val slotKey = anchorId ?: FRONT_ANCHOR
        val previousInserted = lastInsertedAfter[slotKey]

        val insertAt = when {
            previousInserted != null -> merged.indexOfFirst { it.id == previousInserted } + 1
            anchorId == null -> 0
            else -> merged.indexOfFirst { it.id == anchorId } + 1
        }
        // indexOfFirst 落空时返回 -1，+1 之后是 0；两种落空都退化成「插到最前」，
        // 不会因为一个不该发生的状态而崩掉整个刷新。
        merged.add(insertAt.coerceIn(0, merged.size), item)
        lastInsertedAfter[slotKey] = item.id
    }

    return old.copy(
        images = images,
        order = merged,
        manualIds = old.manualIds.intersect(newIds),
        pinnedIds = old.pinnedIds.intersect(newIds),
    )
}

/**
 * 把批次级状态对账到新的集合列表上。
 *
 * 规则：
 * - 集合 id 与图片 id 结构**完全没变**的集合，其 [PageOrderState] 由
 *   [reconcilePageOrder] 原样带回（同一实例）；
 * - **被删除的集合**：它的 `orders` 条目连同人工调整一起丢弃 —— 目录都没了，
 *   再留着状态只会让「重置全部」/计数类 UI 报出并不存在的调整；
 * - **新增的集合**：用 `pageOrderOf(images, batchRule)` 新建 —— 新目录没有历史
 *   可对账，跟随当前批次规则是最不意外的默认；
 * - `customRuleCollections` 与现存集合 id 求交集（被删的独立集合不该继续占位）。
 *
 * `batchRule` 原样保留：它是用户在界面上明确选过的批次默认，不因文件系统变化而重置。
 */
fun reconcileWorkflowAState(
    previous: WorkflowAState,
    collections: List<ImageCollection>,
): WorkflowAState {
    // 内容完全一致（含元数据）→ 返回同一实例，避免下游 remember(collections) 重建。
    if (previous.collections == collections) return previous

    val nextOrders = LinkedHashMap<String, PageOrderState>(collections.size)
    for (collection in collections) {
        val old = previous.orders[collection.id]
        nextOrders[collection.id] = if (old == null) {
            pageOrderOf(collection.images, previous.batchRule)
        } else {
            reconcilePageOrder(old, collection.images)
        }
    }

    val nextCustom = previous.customRuleCollections.intersect(nextOrders.keys)

    return WorkflowAState(
        collections = collections,
        orders = nextOrders,
        batchRule = previous.batchRule,
        customRuleCollections = nextCustom,
    )
}

/**
 * 把命名状态对账到新的集合列表上。
 *
 * 只有 `overrides` 需要动：它的键是 [SourceItem.id]，图被删掉后再留着那条改名，
 * 既白占内存，又会在用户**重新授权 / 重新选目录**后碰巧复用同一个 documentUri 时
 * 把旧名字套到新图上。[scheme] 与 [rootDirNameOverride] 原样保留 —— 它们是用户
 * 在命名页显式设过的偏好，与「工作区里有哪些图」无关。
 *
 * 值（改名后的字符串）不需要校验：它不依赖图片是否还存在，取交集后留下的键必然
 * 对应仍存在的图片。
 */
fun reconcileNamingState(
    previous: NamingState,
    collections: List<ImageCollection>,
): NamingState {
    val aliveIds = collections.flatMapTo(HashSet()) { collection ->
        collection.images.map { it.id }
    }
    val nextOverrides = previous.overrides.filterKeys { it in aliveIds }
    if (nextOverrides.size == previous.overrides.size) return previous
    return previous.copy(overrides = nextOverrides)
}

/** 「没有锚点」在插入阶段用的哨兵键；不可能与真实 id 冲突（真实 id 是 documentUri）。 */
private const val FRONT_ANCHOR = "\u0000front"

/**
 * 在自动顺序里，找 [itemId] 之前**最近的一个保留项**。
 *
 * 为什么锚点必须是保留项而不是「前一个新增项」：保留项的位置已经被人工调整
 * 固定住了，拿它当参照物才能让新图落到「用户眼中该在的地方」；拿新增项当锚点
 * 会形成一条依赖链，链头一旦落位失败，整串新图都会插错地方。
 */
private fun nearestKeptIdBefore(
    itemId: String,
    auto: List<SourceItem>,
    keptIds: Set<String>,
): String? {
    val index = auto.indexOfFirst { it.id == itemId }
    if (index <= 0) return null
    for (i in index - 1 downTo 0) {
        if (auto[i].id in keptIds) return auto[i].id
    }
    return null
}
