package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem

private fun compareField(a: SourceItem, b: SourceItem, field: SortField): Int = when (field) {
    // 自然序与字典序都先转小写(大小写不敏感);目录名保持原始码点序
    SortField.NaturalName -> naturalCompare(a.fileName.lowercase(), b.fileName.lowercase())
    SortField.FileName -> a.fileName.lowercase().compareTo(b.fileName.lowercase())
    SortField.DirName -> a.dir.compareTo(b.dir)
    SortField.Modified -> a.modifiedEpochMillis.compareTo(b.modifiedEpochMillis)
    // created 可能缺失,null 视为最小
    SortField.Created -> when {
        a.createdEpochMillis == null && b.createdEpochMillis == null -> 0
        a.createdEpochMillis == null -> -1
        b.createdEpochMillis == null -> 1
        else -> a.createdEpochMillis.compareTo(b.createdEpochMillis)
    }
    SortField.Size -> a.sizeBytes.compareTo(b.sizeBytes)
}

/**
 * 多级排序(纯函数,不改入参)。
 * 每级独立升降序;全部级相等时按 id 字典序兜底 -> 结果确定(必需,见规格 §5.2)。
 */
fun sortItems(images: List<SourceItem>, rule: SortRule): List<SourceItem> =
    images.sortedWith { a, b ->
        var result = 0
        for (key in rule.keys) {
            val c = compareField(a, b, key.field)
            if (c != 0) {
                result = if (key.ascending) c else -c
                break
            }
        }
        if (result != 0) result else a.id.compareTo(b.id)
    }
