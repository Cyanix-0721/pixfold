package com.cyanix.pixfold.domain.source

import com.cyanix.pixfold.domain.model.ImageCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [groupCollections] 的规则守卫 —— 集合芯片两级分组的唯一权威。
 *
 * 2026-10-19 用户裁决(原话):「芯片分2级别,series或单本作为1级,series作为2级,
 * 如果选择series,收缩series芯片组到一行,下面添加显示2级芯片组选择series下的单本」。
 *
 * 本类只测**分组**(哪些集合归到同一个 1 级条目、它是 series 还是单本);
 * 芯片渲染与两级联动在 `ui/workflowa/CollectionChipRows` 侧另测。
 */
class SeriesGroupsTest {

    private fun collection(id: String, seriesKey: String = "", seriesName: String = "") =
        ImageCollection(
            id = id,
            name = id.substringAfterLast('/'),
            rootDirName = id.substringAfterLast('/'),
            images = emptyList(),
            seriesKey = seriesKey,
            seriesName = seriesName,
        )

    // ---- 空输入 ----

    @Test
    fun `空集合列表得到空分组列表`() {
        assertEquals(emptyList(), groupCollections(emptyList()))
    }

    // ---- 单本 ----

    @Test
    fun `无系列的集合各自成一个单本分组`() {
        val groups = groupCollections(
            listOf(collection("trip"), collection("scan"), collection("misc")),
        )
        assertEquals(3, groups.size)
        groups.forEach { assertFalse(it.isSeries, "没有 seriesKey 的集合不该被判成系列") }
        assertEquals(listOf("trip", "scan", "misc"), groups.map { it.name })
    }

    @Test
    fun `单本分组的 key 带 single 前缀且等于集合 id`() {
        val groups = groupCollections(listOf(collection("trip")))
        assertEquals("${SINGLE_PREFIX}trip", groups.single().key)
    }

    @Test
    fun `单本分组的 name 就是集合名`() {
        val groups = groupCollections(listOf(collection("trip")))
        assertEquals("trip", groups.single().name)
    }

    // ---- 系列 ----

    @Test
    fun `同一 seriesKey 的集合归成一个系列分组`() {
        val series = "[Cior (Ken-1)] 彼女がボクの知らないところで"
        val groups = groupCollections(
            listOf(
                collection("$series/$series", series, series),
                collection("$series/$series――2 [DL版]", series, series),
            ),
        )
        assertEquals(1, groups.size)
        assertTrue(groups.single().isSeries)
        assertEquals(2, groups.single().collections.size)
        assertEquals(series, groups.single().name)
    }

    @Test
    fun `系列分组的 key 带 series 前缀且等于 seriesKey`() {
        val series = "某系列"
        val groups = groupCollections(
            listOf(collection("$series/第01卷", series, series)),
        )
        assertEquals("${SERIES_PREFIX}$series", groups.single().key)
    }

    @Test
    fun `不同系列的集合不混在一起`() {
        val groups = groupCollections(
            listOf(
                collection("A/卷1", "A", "系列A"),
                collection("B/卷1", "B", "系列B"),
                collection("C/卷1", "C", "系列C"),
            ),
        )
        assertEquals(listOf("系列A", "系列B", "系列C"), groups.map { it.name })
        groups.forEach { assertTrue(it.isSeries) }
    }

    // ---- 混合 ----

    @Test
    fun `单本与系列可以共存于同一行`() {
        val groups = groupCollections(
            listOf(
                collection("trip"),
                collection("series/第01卷", "series", "系列"),
                collection("scan"),
            ),
        )
        assertEquals(3, groups.size)
        assertEquals(listOf(false, true, false), groups.map { it.isSeries })
        assertEquals(listOf("trip", "系列", "scan"), groups.map { it.name })
    }

    // ---- 顺序 ----

    @Test
    fun `分组保持集合原有顺序而不是重新排序`() {
        // 集合顺序的唯一权威是 collectionsFrom;本函数若再排一次,"集合按自然序"
        // 与"系列内按自然序"两条规则就会在两层各自演化、早晚漂移。
        val series = "某个系列"
        val input = listOf(
            collection("zzz"),
            collection("$series/第02卷", series, series),
            collection("aaa"),
            collection("$series/第01卷", series, series),
        )
        val groups = groupCollections(input)
        assertEquals(listOf("zzz", series, "aaa"), groups.map { it.name })
        // 系列内成员顺序 = 集合原顺序(不是被重新按名字排)
        assertEquals(listOf("$series/第02卷", "$series/第01卷"), groups[1].collections.map { it.id })
    }

    // ---- 派生字段 ----

    @Test
    fun `系列分组的图片数是各成员之和`() {
        val series = "系列"
        val a = collection("$series/a", series, series).copy(
            images = listOf(image("1"), image("2")),
        )
        val b = collection("$series/b", series, series).copy(images = listOf(image("3")))
        val groups = groupCollections(listOf(a, b))
        assertEquals(3, groups.single().imageCount)
    }

    @Test
    fun `单本分组的图片数就是该集合的图片数`() {
        val single = collection("trip").copy(images = listOf(image("1"), image("2"), image("3")))
        assertEquals(3, groupCollections(listOf(single)).single().imageCount)
    }

    @Test
    fun `默认选中项是该分组的第一本`() {
        val series = "系列"
        val first = collection("$series/第01卷", series, series)
        val second = collection("$series/第02卷", series, series)
        val group = groupCollections(listOf(first, second)).single()
        assertEquals(first.id, defaultCollectionIdOf(group))
    }

    private fun image(id: String) = com.cyanix.pixfold.domain.model.SourceItem(
        id = id,
        collectionId = "",
        dir = "",
        baseName = id,
        ext = "jpg",
        sizeBytes = 1,
        modifiedEpochMillis = 0,
        createdEpochMillis = null,
        seed = 0,
    )
}
