package com.cyanix.pixfold.domain.source

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.sort.naturalCompare

/**
 * 把工作区扫描结果 [docs] 收敛成图片集合列表。
 *
 * ## 分组规则（唯一权威，实现方不得另写一份）
 *
 * **每个「直接含图片」的目录 = 一个集合**（2026-10-19 改判，此前是「每个一级子目录 = 一个集合」）。
 *
 * 为什么改：真实素材的层级是「系列 / 单本 / 页」，例如
 * `[Cior (Ken-1)] 彼女がボクの知らないところで/[Cior (Ken-1)] 彼女がボクの知らないところで――2 [DL版]/035.jpg`。
 * 按一级子目录分组会把同一系列下的多本书**并成一个集合**，用户在步骤 1 看到两本书的页混在
 * 一起、无法分别排序与命名（2026-10-19 用户实测报告）。而「一本书 = 一个目录」是漫画素材的
 * 天然单位：一个目录里的页就是要一起排序、一起起名、一起打包的一卷。
 *
 * 集合的**父目录**即「系列」，记在 [ImageCollection.seriesKey] / [ImageCollection.seriesName]；
 * 父目录就是工作区根时两字段为空串，表示这个集合是一个独立的「单本」。工作区根下直接躺着的
 * 图片归入一个以根目录命名的集合。
 *
 * ## 其他约定
 * - 只收 [SourceDoc.isImage] 的条目；目录条目本身不产生集合。
 * - 集合 id 用「集合目录相对工作区根的路径」而不是目录名 —— 不同系列下可能有同名书目录，
 *   只取目录名会撞 id。根集合 id 固定为空串（见 [ROOT_COLLECTION_ID]）。
 * - [SourceItem.id] 直接用 `documentUri`：它跨扫描稳定，是人工排序/改名覆盖的键。
 * - 集合内按 [naturalCompare] 排文件名，集合之间按 id（相对目录路径）自然序排 —— 同一系列的
 *   几本书路径前缀相同，这样同一系列的集合天然相邻。
 */
fun collectionsFrom(rootDisplayName: String, docs: List<SourceDoc>): List<ImageCollection> {
    val images = docs.asSequence().filter { it.isImage }.distinctBy { it.documentUri }.toList()
    if (images.isEmpty()) return emptyList()
    // 集合目录（相对工作区根的路径）-> 该目录下**直接**躺着的图片。key "" 表示工作区根。
    val byDir = images.groupBy { parentDirOf(it.relPath) }
    return byDir.entries
        .sortedWith { a, b -> naturalCompare(collectionIdOf(a.key), collectionIdOf(b.key)) }
        .map { (dirPath, group) ->
            val collectionId = collectionIdOf(dirPath)
            // 集合名 = 目录名；根集合没有目录名，用工作区名。
            val collectionName = if (dirPath.isEmpty()) rootDisplayName else dirPath.substringAfterLast('/')
            // 系列 = 集合目录的父路径。注意「一级目录」的父是根 ⇒ 它也是单本，没有系列。
            val seriesKey = dirPath.substringBeforeLast('/', "")
            val seriesName = if (seriesKey.isEmpty()) "" else seriesKey.substringAfterLast('/')
            val items = group.map { doc -> toSourceItem(doc, collectionId, dirPath) }
                .sortedWith { a, b -> naturalCompare(a.fileName, b.fileName) }
            ImageCollection(
                id = collectionId,
                name = collectionName,
                rootDirName = collectionName,
                images = items,
                seriesKey = seriesKey,
                seriesName = seriesName,
            )
        }
}

/** 工作区根下图片那个集合的 id = 空串（且任何子目录集合的 id 都非空，故不撞）。 */
const val ROOT_COLLECTION_ID: String = ""

/** `a/b/c.jpg` → `a/b`；`c.jpg` → `""`。 */
private fun parentDirOf(relPath: String): String = relPath.substringBeforeLast('/', "")

/** 根集合用空串，子目录集合用相对路径。 */
private fun collectionIdOf(dirPath: String): String =
    if (dirPath.isEmpty()) ROOT_COLLECTION_ID else dirPath

private fun toSourceItem(doc: SourceDoc, collectionId: String, collectionDir: String): SourceItem {
    // 集合 = 直接含图片的目录，所以集合内每张图的 dir 恒为空；仍按通用算法算，
    // 免得将来放宽「直接含图片」这条约束时这里静默错位。
    val dir = if (collectionDir.isEmpty()) {
        doc.relPath.substringBeforeLast('/', "")
    } else {
        doc.relPath.removePrefix("$collectionDir/").substringBeforeLast('/', "")
    }
    return SourceItem(
        id = doc.documentUri,
        collectionId = collectionId,
        dir = dir,
        baseName = doc.displayName.substringBeforeLast('.', doc.displayName),
        ext = doc.displayName.substringAfterLast('.', ""),
        sizeBytes = doc.sizeBytes,
        modifiedEpochMillis = doc.lastModifiedEpochMillis,
        createdEpochMillis = null,
        seed = doc.documentUri.hashCode(),
        uri = doc.documentUri,
    )
}
