package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.source.SERIES_PREFIX
import com.cyanix.pixfold.domain.source.SINGLE_PREFIX

/**
 * 卷芯片行上的一个 1 级条目:一个系列,或一个不属于任何系列的单本卷。
 *
 * @param key 1 级条目的稳定标识 —— 系列用 [ComicVolume.seriesKey],单本用卷 id。
 * @param name 显示名。系列取名;单本取标题(空标题回退 id,见 [groupVolumes])。
 * @param volumes 该条目下的卷(系列 = 系列内各卷;单本 = 自身,只有 1 卷)。
 * @param isSeries 是否真的是系列(即 [volumes] 来自非空 `seriesKey`)。
 */
data class VolumeGroup(
    val key: String,
    val name: String,
    val volumes: List<ComicVolume>,
    val isSeries: Boolean,
) {
    /** 该条目下的卷数 —— 芯片上显示的就是它。 */
    val volumeCount: Int get() = volumes.size

    /**
     * 该条目下的页面总数。
     *
     * 按 [ComicVolume.pages] 原样求和,不走 `pageOrder`:芯片只用它做规模提示
     * (「进击的巨人 · 3 卷 / 46 页」),而页序唯一权威在 [ComicVolume.pageOrder]。
     * 这里若也去读页序,就等于在展示层又开了一条「页数以谁为准」的判据。
     */
    val pageCount: Int get() = volumes.sumOf { it.pages.size }
}

/**
 * 把卷列表收敛成两级芯片的 1 级条目列表。
 *
 * 与图片集合版的 `groupCollections`(见 `domain/source/SeriesGroups.kt`)**严格同构** ——
 * 工作流 A 的集合芯片与工作流 B 元数据页的卷芯片共用同一套「1 级选系列、2 级选系列下各本」
 * 交互。两张页面若各写一份分组规则,用户在 A 页学到的分组方式到了 B 页就不成立,
 * 而这种漂移只会表现为「有时分对了、有时混在一起」,最难复现也最难归因。
 *
 * 规则:同一 [ComicVolume.seriesKey](非空)的卷合成一个 [VolumeGroup];
 * `seriesKey` 为空的卷各自成为独立条目(单本)。
 *
 * **不在这里重新排序**:卷顺序的唯一权威是上游扫描结果,同一系列天然相邻、系列之间也
 * 保持用户看到的自然顺序。这里再排一次就有两份排序规则,迟早与扫描侧漂移 ——
 * 而且用户刚在步骤 2 拖好的卷序会被展示层悄悄改掉。
 */
fun groupVolumes(volumes: List<ComicVolume>): List<VolumeGroup> {
    val groups = LinkedHashMap<String, MutableList<ComicVolume>>()
    // 单本与系列可能撞 key(某系列的 seriesKey 恰好等于另一个单本的卷 id)。系列键加前缀
    // 区分 —— key 只用于芯片选中态比较与 testTag,不参与展示。
    //
    // 前缀复用 domain/source 里已有的常量而不是就地再定义一份:这套 key 是**跨页面的同一
    // 键空间**(集合芯片与卷芯片的选中态比较、UI testTag 都依赖它),前缀若在两层各写各的,
    // 改一处就会让另一处的选中态静默失效。
    volumes.forEach { volume ->
        val key = if (volume.seriesKey.isEmpty()) {
            SINGLE_PREFIX + volume.id
        } else {
            SERIES_PREFIX + volume.seriesKey
        }
        groups.getOrPut(key) { mutableListOf() } += volume
    }
    return groups.map { (key, members) ->
        val first = members.first()
        val isSeries = first.seriesKey.isNotEmpty()
        VolumeGroup(
            key = key,
            // 系列名取 series 而不是 title:同系列各卷的 title 可以不同(如「进击的巨人 外传」),
            // 拿第一本的标题当组名会让系列看上去缺了后半截。
            //
            // ⚠️ **series 为空时必须回退 seriesDir,不能直接用。** `isSeries` 看的是扫描层的
            // `seriesDir`,而 `series` 是**当前值** —— 两者可以不一致:已有 `ComicInfo.xml`
            // 但没写 `<Series>` 时 `applyExistingComicInfo` 会把当前值清空(用户裁决「文件里
            // 没写就是空的」)。若这里不回退,组被判为系列而组名为空 ⇒ 芯片渲染成「（1）」,
            // 系列名整个消失(2026-10-21 真机缺陷)。
            //
            // 单本没有系列可依,退回标题;标题为空(用户清空了它)时用 id,免得出现无名芯片。
            name = if (isSeries) {
                first.series.ifEmpty { first.seriesDir }.ifEmpty { first.title }.ifEmpty { first.id }
            } else {
                first.title.ifEmpty { first.id }
            },
            volumes = members,
            isSeries = isSeries,
        )
    }
}
