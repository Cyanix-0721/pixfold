package com.cyanix.pixfold.domain.library

import com.cyanix.pixfold.domain.comic.setMetaValue
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.MetaValue
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.comic.comicVolumesFrom
import com.cyanix.pixfold.domain.comic.OutputExistsProbe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 工作流 B 的卷列表增量对账（D2b，2026-10-19）。
 *
 * 缺陷形态与工作流 A 的页序对账同源但更贵：`LibraryState.volumes` 里混着
 * **扫描事实**与**用户决定**两样东西。后台刷新若直接替换整个列表，用户刚填好的
 * 标题/卷号/语言/是否参与打包会**静默消失** —— 不崩不报错，只是活白干了。
 *
 * 反过来的错误更隐蔽：一次都不刷新，用户删除的卷还在列表里，打包时才发现缺页。
 */
class LibraryReconcileTest {

    // ---- 夹具 ----

    private fun page(collectionId: String, index: Int, size: Long = 1000L) = SourceItem(
        id = "content://doc/$collectionId/p$index.jpg",
        collectionId = collectionId,
        dir = "",
        baseName = "p$index",
        ext = "jpg",
        sizeBytes = size,
        modifiedEpochMillis = 0L,
        createdEpochMillis = null,
        seed = index,
    )

    private fun collection(id: String, root: String = "test", pages: Int = 2) = ImageCollection(
        id = id,
        name = id.substringAfterLast('/'),
        rootDirName = root,
        images = (1..pages).map { page(id, it) },
        seriesKey = id.substringBeforeLast('/', ""),
        seriesName = id.substringBeforeLast('/', "").substringAfterLast('/'),
    )

    private val noOutput = OutputExistsProbe { false }

    private fun volumes(vararg ids: String): List<ComicVolume> =
        comicVolumesFrom(ids.map { collection(it) }, "test", noOutput)

    private fun library(vararg ids: String): LibraryState =
        libraryStateOf("/storage/emulated/0/test", volumes(*ids))

    /** 模拟一次重扫：同样的目录结构 ⇒ 全新的一套卷（无任何人工调整）。 */
    private fun rescan(vararg ids: String): List<ComicVolume> = volumes(*ids)

    // ---- 空列表 ----

    @Test
    fun `扫描结果为空时库也变空且不崩`() {
        val before = library("系列A/第01卷")
        val after = reconcileLibrary(before, emptyList())

        assertTrue(after.volumes.isEmpty())
        assertEquals("", after.activeVolumeId)
    }

    @Test
    fun `空库遇到新卷时全部采纳`() {
        val before = libraryStateOf("/root", emptyList())
        val after = reconcileLibrary(before, rescan("系列A/第01卷", "系列A/第02卷"))

        assertEquals(2, after.volumes.size)
        assertEquals("系列A/第01卷", after.activeVolumeId, "当前卷应退回第一本")
    }

    // ---- 用户调整的保留 ----

    @Test
    fun `人工改过的标题在重扫后保留`() {
        val before = library("系列A/第01卷")
        val edited = setMetaValue(
            before.volumes.first(),
            MetaField.Title,
            MetaValue.Text("我改的标题"),
        )
        val dirty = before.copy(volumes = listOf(edited))

        val after = reconcileLibrary(dirty, rescan("系列A/第01卷"))

        assertEquals("我改的标题", after.volumes.single().title)
    }

    @Test
    fun `人工选的卷号在重扫后保留且不因目录名变化被覆盖`() {
        val before = library("系列A/第01卷")
        val edited = setMetaValue(before.volumes.first(), MetaField.Volume, MetaValue.Number(7))
        val dirty = before.copy(volumes = listOf(edited))

        val after = reconcileLibrary(dirty, rescan("系列A/第01卷"))

        assertEquals(7, after.volumes.single().volume)
    }

    @Test
    fun `人工选的语言在重扫后保留`() {
        val before = library("系列A/第01卷")
        val edited = setMetaValue(
            before.volumes.first(),
            MetaField.Language,
            MetaValue.Language(LangChoice.Zh),
        )
        val dirty = before.copy(volumes = listOf(edited))

        val after = reconcileLibrary(dirty, rescan("系列A/第01卷"))

        assertEquals(LangChoice.Zh, after.volumes.single().language)
    }

    @Test
    fun `批量设置写入的值即使 overridden 标记被清掉也要保留`() {
        // batchApply 的语义是:写入值后把该字段从 overridden 里**移除**
        //（"批量设置不算逐项例外"）。若对账只看 overridden,这类值会被静默丢弃。
        val before = library("系列A/第01卷")
        val applied = before.volumes.first().copy(
            writer = "批量设置的作者",
            overridden = emptySet(),
        )
        val dirty = before.copy(volumes = listOf(applied))

        val after = reconcileLibrary(dirty, rescan("系列A/第01卷"))

        assertEquals("批量设置的作者", after.volumes.single().writer)
    }

    @Test
    fun `取消勾选的卷在重扫后仍不参与打包`() {
        val before = library("系列A/第01卷")
        val unchecked = before.copy(
            volumes = listOf(before.volumes.first().copy(included = false)),
        )

        val after = reconcileLibrary(unchecked, rescan("系列A/第01卷"))

        assertEquals(false, after.volumes.single().included)
    }

    // ---- 未改过的字段应采纳新建议 ----

    @Test
    fun `未改过的字段在重扫后采纳新扫描出的建议`() {
        val before = library("系列A/第01卷")
        // 模拟用户把目录改名后再扫：id 变了 ⇒ 视为新卷，但这里要测的是
        // **同一 id 下建议值更新**的情形，故手工把新扫描结果的建议改掉。
        val fresh = rescan("系列A/第01卷").map {
            it.copy(titleSug = it.titleSug.copy(value = "新建议标题"), title = "新建议标题")
        }

        val after = reconcileLibrary(before, fresh)

        assertEquals("新建议标题", after.volumes.single().title)
    }

    @Test
    fun `未勾选的默认值不因缺省而翻转`() {
        val before = library("系列A/第01卷")
        val after = reconcileLibrary(before, rescan("系列A/第01卷"))

        assertTrue(after.volumes.single().included, "新扫描默认参与打包")
    }

    // ---- 增删 ----

    @Test
    fun `删除的卷从库中消失`() {
        val before = library("系列A/第01卷", "系列A/第02卷")
        val after = reconcileLibrary(before, rescan("系列A/第01卷"))

        assertEquals(listOf("系列A/第01卷"), after.volumes.map { it.id })
    }

    @Test
    fun `新增的卷追加在保留项之后`() {
        val before = library("系列A/第01卷")
        val after = reconcileLibrary(before, rescan("系列A/第01卷", "系列A/第02卷"))

        assertEquals(
            listOf("系列A/第01卷", "系列A/第02卷"),
            after.volumes.map { it.id },
        )
    }

    @Test
    fun `保留项维持旧顺序不受扫描顺序影响`() {
        val before = library("系列A/第01卷", "系列A/第02卷")
        // 扫描顺序反转（provider 不保证顺序）
        val after = reconcileLibrary(before, rescan("系列A/第02卷", "系列A/第01卷"))

        assertEquals(
            listOf("系列A/第01卷", "系列A/第02卷"),
            after.volumes.map { it.id },
            "用户熟悉的顺序应保留",
        )
    }

    @Test
    fun `目录改名视为新卷`() {
        val before = library("系列A/第01卷")
        val after = reconcileLibrary(before, rescan("系列A/第01卷改"))

        assertEquals(listOf("系列A/第01卷改"), after.volumes.map { it.id })
    }

    // ---- 当前卷 ----

    @Test
    fun `当前卷仍存在时保持不变`() {
        val before = library("系列A/第01卷", "系列A/第02卷")
        val selected = selectVolume(before, "系列A/第02卷")

        val after = reconcileLibrary(selected, rescan("系列A/第01卷", "系列A/第02卷"))

        assertEquals("系列A/第02卷", after.activeVolumeId)
    }

    @Test
    fun `当前卷被删时退回第一本`() {
        val before = library("系列A/第01卷", "系列A/第02卷")
        val selected = selectVolume(before, "系列A/第02卷")

        val after = reconcileLibrary(selected, rescan("系列A/第01卷"))

        assertEquals("系列A/第01卷", after.activeVolumeId)
    }

    // ---- 页序 ----

    @Test
    fun `卷内页序的人工调整在重扫后保留`() {
        val before = library("系列A/第01卷")
        val old = before.volumes.single()
        val reversed = old.pageOrder.copy(order = old.pageOrder.order.reversed())
        val dirty = before.copy(volumes = listOf(old.copy(pageOrder = reversed)))

        val after = reconcileLibrary(dirty, rescan("系列A/第01卷"))

        assertEquals(
            reversed.order.map { it.id },
            after.volumes.single().pageOrder.order.map { it.id },
        )
    }

    @Test
    fun `卷内新增页按对账规则插入`() {
        val before = library("系列A/第01卷")
        val fresh = rescan("系列A/第01卷") +
            emptyList() // 占位：新增页通过下面 collections 造
        // 造一个多一页的同 id 集合
        val morePages = comicVolumesFrom(
            listOf(collection("系列A/第01卷", pages = 3)),
            "test",
            noOutput,
        )
        assertTrue(fresh.isNotEmpty())

        val after = reconcileLibrary(before, morePages)

        assertEquals(3, after.volumes.single().pageOrder.order.size)
    }

    // ---- 建议值刷新的边界 ----

    @Test
    fun `无系列时标题取卷目录名`() {
        val after = reconcileLibrary(
            libraryStateOf("/root", emptyList()),
            rescan("单本"),
        )
        assertEquals("单本", after.volumes.single().title)
        // 2026-10-20 裁决「Series 三条语义」①:无系列层时 Series 跟随 Title
        // (不再给「无建议」)—— 单行本把 <Series> 写成自己的标题是阅读器常态。
        assertEquals("单本", after.volumes.single().seriesSug.value)
        assertEquals("无系列层，跟随 Title", after.volumes.single().seriesSug.source)
    }

    @Test
    fun `outputExists 以最新扫描为准`() {
        val before = library("系列A/第01卷")
        val marked = before.copy(
            volumes = listOf(before.volumes.single().copy(outputExists = true)),
        )
        // 用户刚删掉了那个 cbz ⇒ 新扫描说 false
        val after = reconcileLibrary(marked, rescan("系列A/第01卷"))

        assertEquals(false, after.volumes.single().outputExists)
    }

    @Test
    fun `overridden 标记在重扫后保留`() {
        val before = library("系列A/第01卷")
        val edited = setMetaValue(
            before.volumes.first(),
            MetaField.Series,
            MetaValue.Text("自定义系列"),
        )
        val dirty = before.copy(volumes = listOf(edited))

        val after = reconcileLibrary(dirty, rescan("系列A/第01卷"))

        assertTrue(
            MetaField.Series in after.volumes.single().overridden,
            "标记语义是「别再被批量设置改写」,不该因刷新丢失",
        )
    }

    @Test
    fun `rootPath 不因对账改变`() {
        val before = library("系列A/第01卷")
        val after = reconcileLibrary(before, rescan("系列A/第01卷"))

        assertEquals("/storage/emulated/0/test", after.rootPath)
    }

    @Test
    fun `未改过的语言保持未设置`() {
        val before = library("系列A/第01卷")
        val after = reconcileLibrary(before, rescan("系列A/第01卷"))

        assertEquals(LangChoice.Unset, after.volumes.single().language)
    }

    @Test
    fun `卷号解析不出时重扫不会凭空造出卷号`() {
        // "Cior (Ken-1) 彼女がボクの知らないところで" 这类目录名没有卷号标记
        val after = reconcileLibrary(
            libraryStateOf("/root", emptyList()),
            rescan("系列/彼女がボクの知らないところで"),
        )

        assertNull(after.volumes.single().volume)
    }
}
