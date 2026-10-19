package com.cyanix.pixfold.domain.workflowb

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.sort.PageOrderState
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.workflowa.reconcilePageOrder

/**
 * 工作区「实时刷新」之后的**卷列表增量对账**（D2b/D4，2026-10-19）。
 *
 * ### 为什么需要它
 * 工作流 B 的数据源已从 mock 切成真实工作区扫描（用户 2026-10-19 裁决「复用工作区」）。
 * 一旦接上真实来源，就必然要面对「用户在文件管理器里增删图片后，工作区重扫」——
 * 而 [WorkflowBState.volumes] **同时承载**两样东西：
 *
 * 1. **扫描得出的事实**（有哪些卷、每卷有哪些页、页的元数据）；
 * 2. **用户做出的决定**（标题/系列/作者/卷号/语言/文件名/输出目录、哪些卷参与打包、
 *    页序的人工拖拽与图钉）。
 *
 * 重扫后若直接用新扫描结果替换（`workflowBStateOf(rootPath, newVolumes)`），第 2 类
 * 会被**整个清空** —— 用户刚填好的元数据一次后台刷新就没了。反过来只保留旧列表则
 * 会让删掉的卷阴魂不散，打包时才发现缺页。
 *
 * ### 对账规则（与工作流 A 的 `reconcileWorkflowAState` 同构）
 * - **卷的身份** = [ComicVolume.id] = 卷目录的相对路径。目录被重命名 ⇒ 视为**新卷**
 *   （旧的那卷连同它的元数据一起消失）。这不是偷懒：卷的元数据全是从目录名与
 *   层级推出来的，目录改名后旧元数据里至少 title/volume 已经过期，硬套等于用
 *   一个已知过期的值去覆盖新的正确建议。
 * - **仍存在的卷**：保留用户编辑过的字段，其余字段采纳新扫描的建议值；
 *   [ComicVolume.included] 保留用户勾选；页序走 [reconcilePageOrder] 增量对账。
 * - **新出现的卷**：原样采用扫描结果（全新的建议值、默认参与打包）。
 * - **消失的卷**：丢弃。
 * - **顺序**：保留项按旧顺序排，新增项按扫描顺序追加在后面 —— 用户对列表顺序的
 *   感知主要来自"我熟悉的那几本还在原处"。
 *
 * 全部是纯函数：不碰 IO、不碰平台类。
 *
 * @param previous 刷新前的库状态（含用户的一切调整）
 * @param scanned 刷新后由扫描结果转换出的卷列表（建议值全新，无任何人工调整）
 */
fun reconcileLibrary(previous: WorkflowBState, scanned: List<ComicVolume>): WorkflowBState {
    val freshById = scanned.associateBy { it.id }

    val merged = ArrayList<ComicVolume>(scanned.size)
    // 1. 保留项：按**旧**顺序遍历，只取新扫描里还活着的
    for (old in previous.volumes) {
        val fresh = freshById[old.id] ?: continue
        merged += mergeVolume(old, fresh)
    }
    // 2. 新增项：按**扫描**顺序追加（`freshById` 里有、但旧状态里没有的）
    val keptIds = previous.volumes.mapTo(HashSet()) { it.id }
    for (fresh in scanned) {
        if (fresh.id !in keptIds) merged += fresh
    }

    // 3. 当前卷：旧的还有效就保留，否则退回第一本（空列表就是空串）
    val activeId = previous.activeVolumeId
        .takeIf { it.isNotEmpty() && freshById.containsKey(it) }
        ?: merged.firstOrNull()?.id.orEmpty()

    return previous.copy(volumes = merged, activeVolumeId = activeId)
}

/**
 * 把一卷的**用户调整**搬到新的扫描结果上。
 *
 * 判断"这个字段用户改过吗"用的是**逐字段复核**：当前值 ≠ 旧建议值，或者该字段
 * 在 [ComicVolume.overridden] 里有显式记录。两条都要看：
 *
 * - 只看 `overridden` 会漏掉批量设置写的值 —— `batchApply` 在写入后会**清掉**该标记
 *   （见 `MetaEdit.kt:200`，语义是"批量设置不算逐项例外"），可值确实是用户给的；
 * - 只看值比较会漏掉"用户改成了与建议值恰好相同的值"这种（罕见但会有，
 *   而且 `overridden` 的语义本来也不止于值：它表示"别再被批量设置改写"）。
 *
 * 没被改过的字段一律采纳新建议 —— 否则用户在文件管理器里把目录改成 `第03卷`，
 * 界面还会显示旧的 `第02卷`。
 */
private fun mergeVolume(old: ComicVolume, fresh: ComicVolume): ComicVolume {
    var merged = fresh

    // ---- 文本三件套 ----
    if (editedText(old, MetaField.Title, old.title, old.titleSug.value)) {
        merged = merged.copy(title = old.title)
    }
    if (editedText(old, MetaField.Series, old.series, old.seriesSug.value)) {
        merged = merged.copy(series = old.series)
    }
    if (editedText(old, MetaField.Writer, old.writer, old.writerSug.value)) {
        merged = merged.copy(writer = old.writer)
    }

    // ---- 卷号 ----
    if (MetaField.Volume in old.overridden || old.volume != old.volumeSug.value) {
        merged = merged.copy(volume = old.volume)
    }

    // ---- 语言 ----
    // 语言没有建议通道（真实素材的 `langSug` 恒为 null），故"不是 Unset"即用户选的。
    if (old.language != LangChoice.Unset) {
        merged = merged.copy(language = old.language, otherLangCode = old.otherLangCode)
    }

    // ---- 文件名 / 输出目录 ----
    // 这两个字段的建议值不单独存（文件名建议 = `defaultCbzName(旧建议)`，
    // 输出目录建议 = `outputDirSug`）。用 `overridden` 标记 + "与旧默认值不同"两条判据。
    if (MetaField.CbzName in old.overridden || old.cbzFileName != defaultNameOf(old)) {
        merged = merged.copy(cbzFileName = old.cbzFileName)
    }
    if (MetaField.OutputDir in old.overridden || old.outputDir != old.outputDirSug) {
        merged = merged.copy(outputDir = old.outputDir)
    }

    // ---- 参与打包 ----
    // `included` 没有建议值（扫描出来一律 true），故与 true 不同即用户改过。
    if (!old.included) merged = merged.copy(included = false)

    // ---- overridden 标记 ----
    // 原样保留：标记的语义是"该字段不再被批量设置改写"，与值是否碰巧相同无关。
    merged = merged.copy(overridden = old.overridden)

    // ---- 页序 ----
    merged = merged.copy(pageOrder = reconcileVolumePages(old.pageOrder, fresh.pages))

    // ---- outputExists ----
    // 新扫描结果才是**当前**真相（用户可能刚删了那个 cbz，也可能刚打完包）。
    merged = merged.copy(outputExists = fresh.outputExists)

    return merged
}

/**
 * 文本字段（建议值为 `String?`，当前值为 `String`）是否被用户改过。
 *
 * 两条判据都要看：
 * - 显式：`field in overridden`（`setMetaValue` 会记），表示"别再被批量设置改写"；
 * - 隐式：当前值与旧建议值不同。批量设置在写完值后会**清掉** `overridden` 标记
 *   （见 `MetaEdit.kt:200` 的 `v.overridden - field`），可值确实是用户给的。
 *
 * 建议值为 `null`、当前值为空串时视为"没改过"（两者都是"没有值"）。
 */
private fun editedText(old: ComicVolume, field: MetaField, current: String, suggestion: String?): Boolean {
    if (field in old.overridden) return true
    return current != (suggestion ?: "")
}

/** 旧卷按当前建议值算出的默认文件名（用于判断 `cbzFileName` 是否被改过）。 */
private fun defaultNameOf(volume: ComicVolume): String =
    com.cyanix.pixfold.domain.comic.defaultCbzName(volume.id, volume.titleSug, volume.volumeSug)

/**
 * 卷内页序的增量对账。
 *
 * **不重新实现**：直接转调工作流 A 那份 [reconcilePageOrder]，保证"页序对账"
 * 全工程只有一个口径 —— 两份实现迟早会在"新增页插到哪"这类边界上分叉。
 */
private fun reconcileVolumePages(old: PageOrderState, pages: List<SourceItem>): PageOrderState =
    reconcilePageOrder(old, pages)
