package com.cyanix.pixfold.domain.source

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.sort.naturalCompare

/**
 * 把平台层扫出的扁平文档列表组装成集合（D2a）。
 *
 * ## 分组规则（唯一权威，实现方不得另写一份）
 *
 * **工作区根目录的每个一级子目录 = 一个集合**；直接躺在根下的图片归入一个
 * 以根目录命名的集合。理由：
 * - 用户的相册/漫画目录习惯就是「一个子目录 = 一本/一次拍摄」，与现有 mock
 *   （`trip` 下有 `day1`/`day2`、`scan` 有 `good`/`bad`）语义一致；
 * - `SourceItem.dir` 继续表示「集合内的相对子目录」，多级目录结构不丢；
 * - 单层映射可预测 —— 用户选了一个目录，看到几个集合是能数出来的。
 *
 * 集合内的 `dir` = 相对**集合根**的路径。例：选中根下 `trip/day1/a.jpg`
 * ⇒ 集合 `trip`，item `dir = "day1"`；根下 `b.jpg` ⇒ 集合（名 = 根目录名），`dir = ""`。
 *
 * ## 其他约定
 * - 只收 `isImage` 的文档；目录条目本身跳过（它们的信息已经体现在 `relPath` 里）。
 * - `SourceItem.id` 直接用 `documentUri` —— 稳定、全局唯一，天然可作 map key。
 * - 集合内按 `naturalCompare(fileName)` 排序，与既有排序语义同一份实现。
 * - 同一 URI 只收一次（平台层递归时若重复命中，不让它变成两张卡）。
 *
 * @param rootDisplayName 工作区根目录的显示名，用于「根下图片」那个集合的命名。
 * @param docs 平台层递归收集到的全部文档（含目录条目，本函数会过滤）。
 */
fun collectionsFrom(
    rootDisplayName: String,
    docs: List<SourceDoc>,
): List<ImageCollection> {
    val images = docs
        .asSequence()
        .filter { it.isImage }
        .distinctBy { it.documentUri }
        .toList()

    if (images.isEmpty()) return emptyList()

    // 一级子目录名 -> 该集合的图片。key "" 表示「直接躺在根下」。
    val byTopLevel = images.groupBy { topLevelSegment(it.relPath) }

    return byTopLevel.entries
        .sortedWith { a, b -> naturalCompare(a.key, b.key) }
        .map { (topLevel, group) ->
            val collectionId = if (topLevel.isEmpty()) ROOT_COLLECTION_ID else topLevel
            val collectionName = if (topLevel.isEmpty()) rootDisplayName else topLevel
            val images = group
                .map { doc -> toSourceItem(doc, collectionId, topLevel) }
                .sortedWith { a, b -> naturalCompare(a.fileName, b.fileName) }
            ImageCollection(
                id = collectionId,
                name = collectionName,
                rootDirName = collectionName,
                images = images,
            )
        }
}

/** 根下图片那个集合的 id = 空串。 */
const val ROOT_COLLECTION_ID: String = ""

/** `a/b/c.jpg` → `a`；`c.jpg` → `""`。 */
private fun topLevelSegment(relPath: String): String = relPath.substringBefore('/').let {
    if (it == relPath) "" else it
}

private fun toSourceItem(doc: SourceDoc, collectionId: String, collectionDir: String): SourceItem {
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
