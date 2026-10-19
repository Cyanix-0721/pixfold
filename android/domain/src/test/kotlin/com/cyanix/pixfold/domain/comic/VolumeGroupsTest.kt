package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.source.SERIES_PREFIX
import com.cyanix.pixfold.domain.source.SINGLE_PREFIX
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [groupVolumes] 的规则守卫 —— 卷芯片两级分组的唯一权威。
 *
 * 工作流 B 元数据页要做「1 级 = 系列、2 级 = 该系列下各卷」的芯片行,与工作流 A 的集合芯片
 * (`groupCollections`)是同一套交互。本类只测**分组**(哪些卷归到同一个 1 级条目、它是系列
 * 还是单本、组名与派生计数对不对);芯片渲染与两级联动在 UI 层另测。
 */
class VolumeGroupsTest {

    /**
     * 造一卷。默认「无系列、标题 = id、1 页」—— 大部分用例只关心分组归属,
     * 让无关字段保持最简,断言失败时差异行才看得清。
     */
    private fun volume(
        id: String,
        series: String = "",
        title: String = id,
        pages: Int = 1,
    ): ComicVolume = comicVolumeOf(
        id = id,
        dirPath = id,
        pages = (1..pages).map { item("$id-p$it") },
        titleSug = Suggestion(title, "测试夹具"),
        seriesSug = Suggestion(series, "测试夹具"),
        writerSug = Suggestion("作者", "测试夹具"),
        writerCleanedSug = Suggestion("作者", "测试夹具"),
        volumeSug = Suggestion(null, "测试夹具"),
        langSug = Suggestion(null, "测试夹具"),
        outputDirSug = "/out",
    )

    private fun item(id: String) = SourceItem(
        id = id,
        collectionId = id.substringBeforeLast('-'),
        dir = "",
        baseName = id,
        ext = "jpg",
        sizeBytes = 1024,
        modifiedEpochMillis = 0,
        createdEpochMillis = null,
        seed = 0,
    )

    // ---- 空输入 ----

    @Test
    fun `空卷列表得到空分组列表`() {
        assertEquals(emptyList(), groupVolumes(emptyList()))
    }

    // ---- 单本 ----

    @Test
    fun `无系列的卷各自成一个单本分组`() {
        val groups = groupVolumes(listOf(volume("trip"), volume("scan"), volume("misc")))
        assertEquals(3, groups.size)
        groups.forEach { assertFalse(it.isSeries, "没有 seriesKey 的卷不该被判成系列") }
        assertEquals(listOf("trip", "scan", "misc"), groups.map { it.name })
        assertEquals(listOf(1, 1, 1), groups.map { it.volumeCount })
    }

    @Test
    fun `单本分组的 key 带 single 前缀且等于卷 id`() {
        val groups = groupVolumes(listOf(volume("trip")))
        assertEquals("${SINGLE_PREFIX}trip", groups.single().key)
    }

    @Test
    fun `单本分组的显示名取标题、标题为空时回退 id`() {
        val titled = volume("trip", title = "旅行照片")
        assertEquals("旅行照片", groupVolumes(listOf(titled)).single().name)

        // 用户可以把标题清空;此时若直接拿空串当组名,芯片上会出现一块无名可点的空白。
        val untitled = volume("orphan", title = "")
        assertEquals("orphan", groupVolumes(listOf(untitled)).single().name, "空标题必须回退 id")
    }

    // ---- 系列 ----

    @Test
    fun `同一 series 的多卷归成一个系列分组`() {
        val series = "进击的巨人"
        val groups = groupVolumes(
            listOf(
                volume("aot-1", series = series),
                volume("aot-2", series = series),
            ),
        )
        assertEquals(1, groups.size)
        assertTrue(groups.single().isSeries)
        assertEquals(2, groups.single().volumeCount)
        assertEquals(series, groups.single().name)
    }

    @Test
    fun `系列分组的 key 带 series 前缀且等于 seriesKey`() {
        val series = "某系列"
        val groups = groupVolumes(listOf(volume("v1", series = series)))
        assertEquals("${SERIES_PREFIX}$series", groups.single().key)
    }

    @Test
    fun `不同系列的卷不混在一起`() {
        val groups = groupVolumes(
            listOf(
                volume("a1", series = "A"),
                volume("b1", series = "B"),
                volume("c1", series = "C"),
            ),
        )
        assertEquals(listOf("A", "B", "C"), groups.map { it.name })
        groups.forEach { assertTrue(it.isSeries) }
        assertEquals(listOf(1, 1, 1), groups.map { it.volumeCount })
    }

    @Test
    fun `只有一卷但 seriesKey 非空时仍判为系列分组`() {
        // isSeries 的判据是 seriesKey 非空,不是「卷数 > 1」。系列里只扫进一卷是真实会发生的
        // (用户刚整理到一半),此时把它降级成单本会让这卷在 1 级芯片上换个位置出现,
        // 用户以为自己的卷"丢了"。
        val group = groupVolumes(listOf(volume("only", series = "系列"))).single()
        assertTrue(group.isSeries)
        assertEquals("${SERIES_PREFIX}系列", group.key)
    }

    // ---- 混合 ----

    @Test
    fun `单本与系列可以共存于同一分组列表`() {
        val groups = groupVolumes(
            listOf(
                volume("trip"),
                volume("s-01", series = "系列"),
                volume("scan"),
            ),
        )
        assertEquals(3, groups.size)
        assertEquals(listOf(false, true, false), groups.map { it.isSeries })
        assertEquals(listOf("trip", "系列", "scan"), groups.map { it.name })
    }

    @Test
    fun `系列键与卷 id 相同时靠前缀区分而不是合并`() {
        // 若 key 不加前缀,单本 `dup` 与系列 `dup` 会落进同一个 LinkedHashMap 桶里 ——
        // 一个本不属于任何系列的单本被并进系列组,组名还会显示成系列名。
        val groups = groupVolumes(
            listOf(
                volume("dup"),
                volume("v1", series = "dup"),
            ),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("${SINGLE_PREFIX}dup", "${SERIES_PREFIX}dup"), groups.map { it.key })
        assertEquals(listOf(false, true), groups.map { it.isSeries })
    }

    // ---- 顺序 ----

    @Test
    fun `分组保持卷原有顺序而不是重新排序`() {
        // 卷顺序的唯一权威是上游扫描结果;本函数若再排一次,用户在步骤 2 拖好的序会被
        // 展示层悄悄改掉,而且"卷按自然序"与"系列内按自然序"两条规则会各自演化。
        val series = "某个系列"
        val input = listOf(
            volume("zzz"),
            volume("$series/第02卷", series = series),
            volume("aaa"),
            volume("$series/第01卷", series = series),
        )
        val groups = groupVolumes(input)
        assertEquals(listOf("zzz", series, "aaa"), groups.map { it.name })
        // 系列内成员顺序 = 卷原顺序(不是被重新按名字排)
        assertEquals(listOf("$series/第02卷", "$series/第01卷"), groups[1].volumes.map { it.id })
    }

    // ---- 派生字段 ----

    @Test
    fun `系列分组的卷数与页数是各成员之和`() {
        val series = "系列"
        val a = volume("$series/a", series = series, pages = 2)
        val b = volume("$series/b", series = series, pages = 3)
        val group = groupVolumes(listOf(a, b)).single()
        assertEquals(2, group.volumeCount)
        assertEquals(5, group.pageCount)
    }

    // ---- 真实 mock 库 ----

    @Test
    fun `真实 mock 漫画库分成进击的巨人与海贼王两组`() {
        val groups = groupVolumes(MockDataB.library.volumes)
        assertEquals(listOf("进击的巨人", "海贼王"), groups.map { it.name })
        assertEquals(listOf(3, 2), groups.map { it.volumeCount })
        assertEquals(listOf(46, 40), groups.map { it.pageCount }, "16+18+12 / 20+20")
        groups.forEach { assertTrue(it.isSeries, "mock 库五卷都带系列名") }
        assertEquals("${SERIES_PREFIX}进击的巨人", groups[0].key)

        // 系列内成员顺序 = mock 库原顺序,不做卷号排序:aot-0 的卷号建议是 null,
        // 按卷号排会把它甩到最前面 —— 那正是本函数刻意不做的事。
        assertEquals(listOf("aot-1", "aot-2", "aot-0"), groups[0].volumes.map { it.id })
        assertEquals(listOf("op-101", "op-102"), groups[1].volumes.map { it.id })
    }
}
