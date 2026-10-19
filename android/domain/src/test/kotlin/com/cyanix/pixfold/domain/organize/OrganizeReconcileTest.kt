package com.cyanix.pixfold.domain.organize

import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.NamingScheme
import com.cyanix.pixfold.domain.model.NamingState
import com.cyanix.pixfold.domain.model.SortField
import com.cyanix.pixfold.domain.model.SortKey
import com.cyanix.pixfold.domain.model.SortRule
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.model.defaultRule
import com.cyanix.pixfold.domain.sort.PageOrderState
import com.cyanix.pixfold.domain.sort.applyRule
import com.cyanix.pixfold.domain.sort.moveItemTo
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.sort.togglePin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 工作区「实时刷新」后的增量对账（用户 2026-10-19 裁决）。
 *
 * 缺陷形态：用户在文件管理器里删了一张图，回前台重扫 → 集合重建 → 之前拖过的
 * 手排顺序与图钉全部消失。**不崩、不报错**，只是「应用把用户的活干没了」。
 * 这类缺陷没有异常可抓，只能靠断言钉住「哪些该保留」。
 */
class WorkflowReconcileTest {

    private fun img(id: String, coll: String = "c1", base: String = id) = SourceItem(
        id = id,
        collectionId = coll,
        dir = "",
        baseName = base,
        ext = "jpg",
        sizeBytes = 1,
        modifiedEpochMillis = 0,
        createdEpochMillis = 0,
        seed = 1,
    )

    private val nameAsc = SortRule(listOf(SortKey(SortField.NaturalName, true)))

    private fun collection(id: String, images: List<SourceItem>) = ImageCollection(
        id = id,
        name = id,
        rootDirName = id,
        images = images,
    )

    /** 页序状态的 id 序列（按 order 排，不是按 images）。 */
    private fun PageOrderState.ids() = order.map { it.id }

    // ---------------- reconcilePageOrder ----------------

    @Test
    fun `内容没变时返回同一个实例`() {
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"))
        val old = pageOrderOf(images, nameAsc)
        // 同一实例是「不打扰下游」的关键：data class 相等也够，但下游用 ==/hashCode
        // 判断变化时，新实例会让 remember(collections) 白重建一次。
        assertSame(old, reconcilePageOrder(old, images))
    }

    @Test
    fun `只有元数据变化时保留顺序但换成新的元数据`() {
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"))
        val old = pageOrderOf(images, nameAsc)
        // 大小/修改时间变了（文件被覆盖），但图片身份没变 ⇒ 不该丢人工顺序。
        val rewritten = images.map { it.copy(sizeBytes = 999, modifiedEpochMillis = 42) }
        val result = reconcilePageOrder(old, rewritten)
        assertEquals(listOf("i1", "i2"), result.ids())
        assertEquals(999L, result.order[0].sizeBytes)
        assertEquals(42L, result.order[1].modifiedEpochMillis)
    }

    @Test
    fun `重命名后 order 里带的是新文件名`() {
        // 真机实测的 provider 行为：ExternalStorageProvider 重命名后 documentUri 不变。
        // 若这里原样返回旧项，界面会一直显示改名前的文件名。
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"))
        val old = pageOrderOf(images, nameAsc)
        val renamed = images.map { if (it.id == "i2") it.copy(baseName = "zz") else it }
        val result = reconcilePageOrder(old, renamed)
        assertEquals(listOf("i1", "i2"), result.ids())
        assertEquals("zz", result.order[1].baseName)
    }

    @Test
    fun `元数据没变时才返回同一实例`() {
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"))
        val old = pageOrderOf(images, nameAsc)
        // 热路径仍然成立：连元数据都没变（同一批对象）就不发新实例。
        assertSame(old, reconcilePageOrder(old, images.toList()))
    }

    @Test
    fun `删掉的图从顺序里消失`() {
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"), img("i3", base = "3"))
        val old = pageOrderOf(images, nameAsc)
        val result = reconcilePageOrder(old, images.filter { it.id != "i2" })
        assertEquals(listOf("i1", "i3"), result.ids())
    }

    @Test
    fun `没有人工调整时也保留旧骨架相对顺序`() {
        // 关键判据：既有实现**无条件**保留旧 order 的骨架，不因为 manualIds 为空
        // 就退回自动规则。用户看到「我没拖过，但它自己变序了」同样是意外。
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"), img("i3", base = "3"))
        val old = PageOrderState(
            images = images,
            rule = nameAsc,
            order = listOf(images[2], images[0], images[1]),
            manualIds = emptySet(),
        )
        val result = reconcilePageOrder(old, images.filter { it.id != "i2" })
        // 若退回规则序会是 [i1, i3]；保留骨架才是 [i3, i1]。
        assertEquals(listOf("i3", "i1"), result.ids())
    }

    @Test
    fun `人工拖拽的顺序在删除其他图后保持`() {
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"), img("i3", base = "3"))
        val dragged = moveItemTo(pageOrderOf(images, nameAsc), "i3", 0)
        val result = reconcilePageOrder(dragged, images.filter { it.id != "i2" })
        assertEquals(listOf("i3", "i1"), result.ids())
    }

    @Test
    fun `新增的图插到规则序里最近的保留项之后`() {
        val old = listOf(img("i1", base = "1"), img("i2", base = "2"), img("i3", base = "3"))
        // 用户把 3 拖到最前 → 3 1 2
        val dragged = moveItemTo(pageOrderOf(old, nameAsc), "i3", 0)
        // ⚠️ 夹具用 "2b" 而不是 "2.5"：自然序比较的是完整文件名，
        // "2.5.jpg" 的 token 是 [2, ".", 5, ".jpg"]、"2.jpg" 是 [2, ".jpg"]，
        // 第二段 "." < ".jpg" ⇒ "2.5.jpg" 反而排在 "2.jpg" **前面**，
        // 锚点就变成 1 而不是 2，测的就不是「插到最近保留项之后」了。
        val now = old + img("i2b", base = "2b")
        val result = reconcilePageOrder(dragged, now)
        // 骨架 3 1 2 不动；2b 在规则序里跟在 2 后面 ⇒ 落到 2 之后
        assertEquals(listOf("i3", "i1", "i2", "i2b"), result.ids())
    }

    @Test
    fun `新增的图排在规则序最前时插到骨架最前`() {
        val old = listOf(img("i2", base = "2"), img("i3", base = "3"))
        val dragged = moveItemTo(pageOrderOf(old, nameAsc), "i3", 0) // 3 2
        val now = listOf(img("i1", base = "1")) + old
        val result = reconcilePageOrder(dragged, now)
        assertEquals(listOf("i1", "i3", "i2"), result.ids())
    }

    @Test
    fun `同一个锚点下插入多张新图时保持规则序而不反转`() {
        val old = listOf(img("i1", base = "1"), img("i9", base = "9"))
        val dragged = moveItemTo(pageOrderOf(old, nameAsc), "i9", 0) // 9 1
        val now = listOf(
            img("i1", base = "1"),
            img("i2", base = "2"),
            img("i3", base = "3"),
            img("i9", base = "9"),
        )
        val result = reconcilePageOrder(dragged, now)
        // 2、3 都锚在 1 之后；若逐个前插会变成 9 1 3 2
        assertEquals(listOf("i9", "i1", "i2", "i3"), result.ids())
    }

    @Test
    fun `人工标记被剪裁到仍存在的图`() {
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"), img("i3", base = "3"))
        val dragged = moveItemTo(pageOrderOf(images, nameAsc), "i3", 0)
        val pinned = togglePin(dragged, "i2")
        val result = reconcilePageOrder(pinned, images.filter { it.id != "i2" })
        assertEquals(setOf("i3"), result.manualIds)
        assertTrue(result.pinnedIds.isEmpty())
    }

    @Test
    fun `图全被删掉时结果为空`() {
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"))
        val dragged = togglePin(moveItemTo(pageOrderOf(images, nameAsc), "i2", 0), "i2")
        val result = reconcilePageOrder(dragged, emptyList())
        assertTrue(result.order.isEmpty())
        assertTrue(result.manualIds.isEmpty())
        assertTrue(result.pinnedIds.isEmpty())
    }

    @Test
    fun `旧状态里没有图时全部按规则落位`() {
        // 「新目录第一次扫出图」与「用户一口气补了十几张」都走到这条路径：
        // 没有任何保留项可作锚点，结果必须严格等于规则序，不能因为插入顺序反转。
        val old = pageOrderOf(emptyList(), nameAsc)
        val now = listOf("1", "2", "3").map { img("i$it", base = it) }

        val result = reconcilePageOrder(old, now)

        assertEquals(listOf("i1", "i2", "i3"), result.ids())
    }

    @Test
    fun `重命名按删加增处理，旧顺序不会套到新图上`() {
        // 文件管理器里重命名一张图，SAF 给出的 documentUri 通常随之改变（id 变）。
        // 此时旧 id 消失、新 id 出现 —— 语义上就是「删了一张、加了一张」，
        // 绝不能把旧 id 的手工顺序/图钉顺势套到新 id 上（那是另一张图的位置）。
        val old = listOf(img("i1", base = "1"), img("i2", base = "2"))
        val dragged = moveItemTo(pageOrderOf(old, nameAsc), "i2", 0) // 2 1
        val pinned = togglePin(dragged, "i2")
        val renamed = listOf(img("i1", base = "1"), img("i2x", base = "2"))

        val result = reconcilePageOrder(pinned, renamed)

        assertEquals(listOf("i1", "i2x"), result.ids())
        // 手工标记属于**旧那张图**，它已经不在了 ⇒ 必须清空而不是转移到 i2x。
        assertTrue(result.manualIds.isEmpty())
        assertTrue(result.pinnedIds.isEmpty())
    }

    @Test
    fun `刷新后不丢图片也不重复`() {
        val old = listOf(img("i1", base = "1"), img("i2", base = "2"))
        val dragged = moveItemTo(pageOrderOf(old, nameAsc), "i2", 0)
        val now = listOf("1", "2", "3", "4").map { img("i$it", base = it) }
        val result = reconcilePageOrder(dragged, now)
        assertEquals(now.size, result.order.size)
        assertEquals(now.map { it.id }.toSet(), result.ids().toSet())
    }

    @Test
    fun `规则取自旧状态而不是默认规则`() {
        // 用户把排序规则改成了文件名降序，旧 order 就是降序。
        val desc = SortRule(listOf(SortKey(SortField.NaturalName, false)))
        val images = listOf(img("i1", base = "1"), img("i2", base = "2"))
        val old = pageOrderOf(images, desc)
        assertEquals(listOf("i2", "i1"), old.ids())
        // 新增一张 0：按旧规则（降序）应排在 1 之后；若误用默认规则会排到最前。
        val result = reconcilePageOrder(old, images + img("i0", base = "0"))
        assertEquals(listOf("i2", "i1", "i0"), result.ids())
        assertEquals(desc, result.rule)
    }

    @Test
    fun `对账后的规则被原样带出`() {
        val images = listOf(img("i1", base = "1"))
        val old = pageOrderOf(images, nameAsc)
        assertEquals(nameAsc, reconcilePageOrder(old, images + img("i2", base = "2")).rule)
    }

    // ---------------- reconcileOrganizeState ----------------

    private fun state(vararg collections: ImageCollection) = organizeStateOf(collections.toList())

    @Test
    fun `集合内容完全没变时返回同一个实例`() {
        val s = state(collection("c1", listOf(img("i1"))))
        assertSame(s, reconcileOrganizeState(s, s.collections))
    }

    @Test
    fun `刷新后保留未变集合的人工顺序`() {
        val images = listOf("1", "2", "3").map { img("i$it", base = it) }
        var s = state(collection("c1", images))
        s = updatePageOrder(s, "c1", moveItemTo(s.orders.getValue("c1"), "i3", 0))
        val next = reconcileOrganizeState(s, listOf(collection("c1", images)))
        assertEquals(listOf("i3", "i1", "i2"), next.orders.getValue("c1").ids())
        assertEquals(setOf("i3"), next.orders.getValue("c1").manualIds)
    }

    @Test
    fun `新出现的集合按批次规则建页序`() {
        val s = state(collection("c1", listOf(img("i1"))))
        val next = reconcileOrganizeState(
            s,
            listOf(collection("c1", listOf(img("i1"))), collection("c2", listOf(img("j1", base = "1")))),
        )
        assertEquals(setOf("c1", "c2"), next.orders.keys)
        assertEquals(s.batchRule, next.orders.getValue("c2").rule)
    }

    @Test
    fun `消失的集合连同它的独立规则标记一起丢掉`() {
        val c1 = collection("c1", listOf(img("i1")))
        val c2 = collection("c2", listOf(img("j1", coll = "c2")))
        val s = toggleCustomRule(state(c1, c2), "c2")
        val next = reconcileOrganizeState(s, listOf(c1))
        assertEquals(setOf("c1"), next.orders.keys)
        assertTrue(next.customRuleCollections.isEmpty())
    }

    @Test
    fun `批次默认规则在刷新后保持不变`() {
        val s = state(collection("c1", listOf(img("i1"))))
        val next = reconcileOrganizeState(s, listOf(collection("c1", listOf(img("i1"), img("i2")))))
        assertSame(s.batchRule, next.batchRule)
    }

    @Test
    fun `新增集合时保留集合自身的顺序与元数据`() {
        val s = state(collection("c1", listOf(img("i1"))))
        val next = reconcileOrganizeState(
            s,
            listOf(collection("c9", listOf(img("z1", coll = "c9"))), collection("c1", listOf(img("i1")))),
        )
        // 顺序由扫描结果（collectionsFrom 已排好）决定，对账不得重排。
        assertEquals(listOf("c9", "c1"), next.collections.map { it.id })
    }

    // ---------------- reconcileNamingState ----------------

    private fun naming(overrides: Map<String, String>) = NamingState(
        scheme = NamingScheme(rootDirName = "根"),
        overrides = overrides,
        rootDirNameOverride = null,
    )

    @Test
    fun `命名覆盖只保留仍存在的图`() {
        val collections = listOf(collection("c1", listOf(img("i1"))))
        val result = reconcileNamingState(naming(mapOf("i1" to "保留.jpg", "i9" to "丢弃.jpg")), collections)
        assertEquals(mapOf("i1" to "保留.jpg"), result.overrides)
    }

    @Test
    fun `没有可丢弃的覆盖时返回同一个实例`() {
        val collections = listOf(collection("c1", listOf(img("i1"))))
        val s = naming(mapOf("i1" to "a.jpg"))
        assertSame(s, reconcileNamingState(s, collections))
    }

    @Test
    fun `没有覆盖时返回同一个实例`() {
        val collections = listOf(collection("c1", listOf(img("i1"))))
        val s = naming(emptyMap())
        assertSame(s, reconcileNamingState(s, collections))
    }

    @Test
    fun `命名方案与根目录覆盖在刷新后不动`() {
        val collections = listOf(collection("c1", listOf(img("i1"))))
        val s = naming(mapOf("i9" to "x.jpg")).copy(rootDirNameOverride = "我的根")
        val result = reconcileNamingState(s, collections)
        assertEquals("我的根", result.rootDirNameOverride)
        assertEquals(s.scheme, result.scheme)
    }

    @Test
    fun `跨集合的覆盖按全局 id 保留`() {
        val collections = listOf(
            collection("c1", listOf(img("i1", coll = "c1"))),
            collection("c2", listOf(img("i2", coll = "c2"))),
        )
        val s = naming(mapOf("i1" to "a.jpg", "i2" to "b.jpg"))
        assertSame(s, reconcileNamingState(s, collections))
    }

    @Test
    fun `旧状态里没有的规则字段不影响对账`() {
        // 防御性断言：对账只碰 overrides，不碰 scheme 的其余字段。
        val collections = listOf(collection("c1", listOf(img("i1"))))
        val s = naming(mapOf("i9" to "x.jpg"))
        val result = reconcileNamingState(s, collections)
        assertEquals(s.scheme.indexStart, result.scheme.indexStart)
        assertEquals(s.scheme.components, result.scheme.components)
    }

    @Test
    fun `默认规则下的对账不改变独立集合标记`() {
        val images = listOf(img("i1"))
        val s = toggleCustomRule(state(collection("c1", images)), "c1")
        val next = reconcileOrganizeState(s, s.collections)
        assertSame(s, next)
        assertEquals(setOf("c1"), next.customRuleCollections)
    }

    @Test
    fun `applyRule 清空人工标记后再对账仍然稳定`() {
        val images = listOf("1", "2", "3").map { img("i$it", base = it) }
        var po = moveItemTo(pageOrderOf(images, nameAsc), "i3", 0) // manualIds = {i3}
        po = applyRule(po, defaultRule())
        assertTrue(po.manualIds.isEmpty())
        // 再对账（内容不变）必须短路
        assertSame(po, reconcilePageOrder(po, images))
    }
}
