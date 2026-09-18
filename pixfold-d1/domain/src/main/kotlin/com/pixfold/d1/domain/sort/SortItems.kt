package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem

/**
 * 排序键(每项**预计算一次**)。
 *
 * 为什么需要(2026-09-17 性能实测):原实现在**每次比较**里做
 * `fileName.lowercase()` + `tokenizeNatural()`,n=3000 时比较约 **1.7 万次**
 * → 1.7 万次字符串小写化与分词,而实际只需 **n 次**。图片量大(一集合可达 3000 张)时是纯浪费。
 *
 * 语义**完全不变**:仍逐级比较、每级独立升降序、id 兜底;只是把重复计算提到比较之外。
 * 由 `SortItemsEquivalenceTest` 守护"优化前后结果一致"。
 */
private class SortKeys(val item: SourceItem) {
    /** 大小写不敏感:自然序与字典序共用同一份小写结果。 */
    val lowerName: String = item.fileName.lowercase()

    /** 自然序分词结果(基于小写名,只算一次)。 */
    val naturalTokens: List<String> = tokenizeNatural(lowerName)
}

private fun comparePrecomputed(a: SortKeys, b: SortKeys, field: SortField): Int = when (field) {
    SortField.NaturalName -> compareNaturalTokens(a.naturalTokens, b.naturalTokens)
    SortField.FileName -> a.lowerName.compareTo(b.lowerName)
    SortField.DirName -> a.item.dir.compareTo(b.item.dir)
    SortField.Modified -> a.item.modifiedEpochMillis.compareTo(b.item.modifiedEpochMillis)
    SortField.Created -> when {
        a.item.createdEpochMillis == null && b.item.createdEpochMillis == null -> 0
        a.item.createdEpochMillis == null -> -1
        b.item.createdEpochMillis == null -> 1
        else -> a.item.createdEpochMillis.compareTo(b.item.createdEpochMillis)
    }
    SortField.Size -> a.item.sizeBytes.compareTo(b.item.sizeBytes)
}

/**
 * 多级排序(纯函数,不改入参)。
 * 每级独立升降序;全部级相等时按 id 字典序兜底 -> 结果确定(必需,见规格 §5.2)。
 *
 * 实现:**先为每项预计算排序键一次**(decorate-sort-undecorate),再排序。
 */
fun sortItems(images: List<SourceItem>, rule: SortRule): List<SourceItem> {
    if (images.size <= 1) return images.toList()
    // 用下标做键:SourceItem 是 data class,值相同的两项会互相覆盖
    val keys = images.map { SortKeys(it) }
    val indexed = images.indices.sortedWith { i, j ->
        val ka = keys[i]
        val kb = keys[j]
        var result = 0
        for (key in rule.keys) {
            val c = comparePrecomputed(ka, kb, key.field)
            if (c != 0) {
                result = if (key.ascending) c else -c
                break
            }
        }
        if (result != 0) result else images[i].id.compareTo(images[j].id)
    }
    return indexed.map { images[it] }
}
