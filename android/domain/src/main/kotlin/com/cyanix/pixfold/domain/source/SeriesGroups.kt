package com.cyanix.pixfold.domain.source

import com.cyanix.pixfold.domain.model.ImageCollection

/**
 * 芯片行上的一个 1 级条目:一个系列,或一个不属于任何系列的单本。
 *
 * @param key 1 级条目的稳定标识 —— 系列用 [ImageCollection.seriesKey],单本用集合 id。
 * @param name 显示名。
 * @param collections 该条目下的集合(系列 = 系列内各本;单本 = 自身,只有 1 个)。
 * @param isSeries 是否真的是系列(即 [collections] 多于 1 本)。
 */
data class CollectionGroup(
    val key: String,
    val name: String,
    val collections: List<ImageCollection>,
    val isSeries: Boolean,
) {
    /** 该条目下的图片总数 —— 芯片上显示的就是它。 */
    val imageCount: Int get() = collections.sumOf { it.images.size }
}

/**
 * 把集合列表收敛成两级芯片的 1 级条目列表。
 *
 * 规则:同一 [ImageCollection.seriesKey](非空)的集合合成一个 [CollectionGroup];
 * `seriesKey` 为空串的集合各自成为独立条目(单本)。1 级条目顺序 = 组内第一个集合在
 * 入参里的出现顺序 —— 入参本身按集合路径自然序,所以同一系列天然相邻、系列之间也保持
 * 用户看到的自然顺序。
 *
 * 为什么不在这里重新排序:**集合顺序的唯一权威是 [collectionsFrom]**,这里再排一次
 * 就有两份排序规则,迟早漂移。
 */
fun groupCollections(collections: List<ImageCollection>): List<CollectionGroup> {
    val groups = LinkedHashMap<String, MutableList<ImageCollection>>()
    // 单本与系列可能撞 key(例如某系列名恰好等于另一个单本集合的 id)。为免这种撞车,
    // 系列键加前缀区分 —— key 只用于芯片选中态比较,不影响展示。
    collections.forEach { collection ->
        val key = if (collection.seriesKey.isEmpty()) {
            SINGLE_PREFIX + collection.id
        } else {
            SERIES_PREFIX + collection.seriesKey
        }
        groups.getOrPut(key) { mutableListOf() } += collection
    }
    return groups.map { (key, members) ->
        val first = members.first()
        val isSeries = first.seriesKey.isNotEmpty()
        CollectionGroup(
            key = key,
            name = if (isSeries) first.seriesName else first.name,
            collections = members,
            isSeries = isSeries,
        )
    }
}

/** 1 级条目 key 的前缀:区分「系列」与「单本」两种来源,避免同名撞车。 */
const val SERIES_PREFIX: String = "series:"
const val SINGLE_PREFIX: String = "single:"

/** [groupCollections] 的逆运算:给定 1 级条目 key,取出它默认选中的集合 id。 */
fun defaultCollectionIdOf(group: CollectionGroup): String? = group.collections.firstOrNull()?.id
