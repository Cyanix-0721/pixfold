package com.cyanix.pixfold.domain.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [collectionsFrom] 的规则守卫。2026-10-19 规则改判：集合 = **直接含图片的目录**（此前是一级子目录），
 * 集合的父目录 = 系列。定稿依据见 `SourceGroups.kt` 的 KDoc。
 */
class SourceGroupsTest {

    // ---- 辅助 ----

    private fun img(
        path: String,
        mime: String = "image/jpeg",
        size: Long = 100L,
        mod: Long = 5L,
    ) = SourceDoc(
        documentUri = "content://tree/doc/$path",
        displayName = path.substringAfterLast('/'),
        mimeType = mime,
        sizeBytes = size,
        lastModifiedEpochMillis = mod,
        relPath = path,
    )

    private fun dir(path: String) = SourceDoc(
        documentUri = "content://tree/doc/$path",
        displayName = path.substringAfterLast('/'),
        mimeType = SourceDoc.MIME_TYPE_DIR,
        sizeBytes = 0L,
        lastModifiedEpochMillis = 0L,
        relPath = path,
    )

    // ---- 空输入 ----

    @Test
    fun `空文档列表得到空集合列表`() {
        assertEquals(emptyList<Any>(), collectionsFrom("相册", emptyList()))
    }

    @Test
    fun `只有目录没有图片时得到空集合列表`() {
        assertEquals(emptyList<Any>(), collectionsFrom("相册", listOf(dir("day1"), dir("day1/sub"))))
    }

    @Test
    fun `目录条目本身不会变成图片`() {
        val result = collectionsFrom("相册", listOf(dir("trip"), dir("trip/day1")))
        assertEquals(0, result.size)
    }

    // ---- 分组：集合 = 直接含图片的目录 ----

    @Test
    fun `同一系列下的两本书各成一个集合`() {
        val docs = listOf(
            dir("series"),
            dir("series/book1"),
            img("series/book1/001.jpg"),
            img("series/book1/002.jpg"),
            dir("series/book2"),
            img("series/book2/001.jpg"),
        )
        val result = collectionsFrom("工作区", docs)
        assertEquals(listOf("series/book1", "series/book2"), result.map { it.id })
        assertEquals(listOf(2, 1), result.map { it.images.size })
        assertEquals(listOf("book1", "book2"), result.map { it.name })
    }

    @Test
    fun `工作区根下直接躺的图片归入以根目录命名的集合`() {
        val docs = listOf(img("a.jpg"), img("b.jpg"))
        val result = collectionsFrom("我的相册", docs)
        assertEquals(1, result.size)
        assertEquals(ROOT_COLLECTION_ID, result[0].id)
        assertEquals("我的相册", result[0].name)
        assertEquals(2, result[0].images.size)
    }

    @Test
    fun `根集合 id 是空串且与任何子目录集合都不撞`() {
        val docs = listOf(img("root.jpg"), img("day1/a.jpg"))
        val result = collectionsFrom("相册", docs)
        assertEquals(2, result.size)
        val root = result.first { it.id == ROOT_COLLECTION_ID }
        val sub = result.first { it.id == "day1" }
        assertEquals("相册", root.name)
        assertEquals("day1", sub.name)
        // 空串 id 与目录名永不相等（目录名不能为空），所以两者可区分。
        assertTrue(root.id != sub.id)
    }

    @Test
    fun `只有一级目录时它就是单本，没有系列`() {
        val docs = listOf(dir("day1"), img("day1/a.jpg"))
        val result = collectionsFrom("相册", docs)
        assertEquals(1, result.size)
        assertEquals("day1", result[0].id)
        assertEquals("day1", result[0].name)
        // 一级目录的父是工作区根 ⇒ 不属于任何系列。
        assertEquals("", result[0].seriesKey)
        assertEquals("", result[0].seriesName)
    }

    // ---- 系列 ----

    @Test
    fun `二级目录的父目录名成为系列名`() {
        // 真实素材结构(2026-10-19 实测 /storage/emulated/0/test):
        //   test/                                    <- 工作区根
        //   └─ [Cior…]                               <- 系列(一级目录)
        //      ├─ [Cior…]                            <- 第 1 本(二级目录,24 张)
        //      └─ [Cior…]――2 [DL版]                  <- 第 2 本(二级目录,32 张)
        val series = "[Cior (Ken-1)] 彼女がボクの知らないところで"
        val docs = listOf(
            dir(series),
            img("$series/$series/0001.jpg"),
            img("$series/$series――2 [DL版]/035.jpg"),
        )
        val result = collectionsFrom("工作区", docs)
        assertEquals(2, result.size)
        result.forEach {
            assertEquals(series, it.seriesKey)
            assertEquals(series, it.seriesName)
        }
        // 集合名 = 各本书的目录名(第二本带后缀),两本书不再混在一起。
        assertEquals(
            listOf(series, "$series――2 [DL版]"),
            result.map { it.name },
        )
        // 集合 id 是相对工作区根的完整路径,两本不会撞 id。
        assertEquals(
            listOf("$series/$series", "$series/$series――2 [DL版]"),
            result.map { it.id },
        )
    }

    @Test
    fun `三级目录的系列是中间那一层而不是根`() {
        val docs = listOf(img("series/book/001.jpg"))
        val result = collectionsFrom("工作区", docs)
        assertEquals(1, result.size)
        // 集合 = 直接含图片的目录 series/book；父 = series。
        assertEquals("series/book", result[0].id)
        assertEquals("book", result[0].name)
        assertEquals("series", result[0].seriesKey)
        assertEquals("series", result[0].seriesName)
    }

    @Test
    fun `不同系列下的同名书目录不撞 id`() {
        val docs = listOf(
            img("seriesA/第01卷/001.jpg"),
            img("seriesB/第01卷/001.jpg"),
        )
        val result = collectionsFrom("工作区", docs)
        assertEquals(listOf("seriesA/第01卷", "seriesB/第01卷"), result.map { it.id })
        assertEquals(2, result.map { it.images.first().id }.toSet().size)
    }

    // ---- dir 语义（集合内相对子目录） ----

    @Test
    fun `集合直接含图片时集合内的 dir 恒为空串`() {
        val docs = listOf(img("trip/day1/a.jpg"))
        val result = collectionsFrom("相册", docs)
        assertEquals("trip/day1", result[0].id)
        assertEquals("", result[0].images.single().dir)
    }

    @Test
    fun `根下图片的 dir 为空串`() {
        val result = collectionsFrom("相册", listOf(img("a.jpg")))
        assertEquals("", result[0].images.single().dir)
    }

    @Test
    fun `多级目录不会丢层级信息`() {
        val docs = listOf(img("trip/day1/sub/a.jpg"))
        val result = collectionsFrom("相册", docs)
        // 层级保留在集合 id 与系列键上，而不是靠 dir 字段重复表达。
        assertEquals("trip/day1/sub", result[0].id)
        assertEquals("trip/day1", result[0].seriesKey)
        assertEquals("sub", result[0].name)
    }

    // ---- 过滤 ----

    @Test
    fun `非图片 MIME 被过滤`() {
        val docs = listOf(
            img("day1/a.jpg"),
            img("day1/note.txt", mime = "text/plain"),
            img("day1/clip.mp4", mime = "video/mp4"),
        )
        val result = collectionsFrom("相册", docs)
        assertEquals(1, result[0].images.size)
        assertEquals("a.jpg", result[0].images[0].fileName)
    }

    @Test
    fun `图片判定按 MIME 前缀而非扩展名`() {
        val docs = listOf(
            img("day1/fake.jpg", mime = "text/plain"),
            img("day1/real.bin", mime = "image/png"),
        )
        val result = collectionsFrom("相册", docs)
        assertEquals(listOf("real.bin"), result[0].images.map { it.fileName })
    }

    @Test
    fun `同一 URI 只收一次`() {
        val doc = img("day1/a.jpg")
        val result = collectionsFrom("相册", listOf(doc, doc))
        assertEquals(1, result[0].images.size)
    }

    // ---- 排序 ----

    @Test
    fun `集合按自然序排列而不是字典序`() {
        val docs = listOf(img("vol10/a.jpg"), img("vol2/a.jpg"), img("vol9/a.jpg"))
        val result = collectionsFrom("相册", docs)
        assertEquals(listOf("vol2", "vol9", "vol10"), result.map { it.id })
    }

    @Test
    fun `同一系列下的集合按自然序相邻排列`() {
        val docs = listOf(
            img("series/第02卷/001.jpg"),
            img("series/第10卷/001.jpg"),
            img("series/第01卷/001.jpg"),
        )
        val result = collectionsFrom("相册", docs)
        assertEquals(
            listOf("series/第01卷", "series/第02卷", "series/第10卷"),
            result.map { it.id },
        )
    }

    @Test
    fun `集合内图片按自然序排列`() {
        val docs = listOf(img("day1/p10.jpg"), img("day1/p2.jpg"), img("day1/p9.jpg"))
        val result = collectionsFrom("相册", docs)
        assertEquals(listOf("p2.jpg", "p9.jpg", "p10.jpg"), result[0].images.map { it.fileName })
    }

    // ---- 字段 ----

    @Test
    fun `SourceItem 带上 uri 与派生字段`() {
        val result = collectionsFrom("相册", listOf(img("day1/IMG_001.jpg", size = 2048L, mod = 777L)))
        val item = result[0].images.single()
        assertEquals("content://tree/doc/day1/IMG_001.jpg", item.id)
        assertEquals("content://tree/doc/day1/IMG_001.jpg", item.uri)
        assertEquals("day1", item.collectionId)
        assertEquals("IMG_001", item.baseName)
        assertEquals("jpg", item.ext)
        assertEquals(2048L, item.sizeBytes)
        assertEquals(777L, item.modifiedEpochMillis)
        assertNull(item.createdEpochMillis)
    }

    @Test
    fun `没有扩展名的图片文件名不炸`() {
        val result = collectionsFrom("相册", listOf(img("day1/README")))
        val item = result[0].images.single()
        assertEquals("README", item.baseName)
        assertEquals("", item.ext)
    }

    @Test
    fun `每个集合的 rootDirName 等于集合名`() {
        val docs = listOf(img("day1/a.jpg"), img("a.jpg"))
        collectionsFrom("我的相册", docs).forEach { assertEquals(it.name, it.rootDirName) }
    }

    @Test
    fun `集合内每项的 collectionId 指向所属集合`() {
        val docs = listOf(img("series/book1/a.jpg"), img("series/book2/b.jpg"))
        collectionsFrom("工作区", docs).forEach { collection ->
            collection.images.forEach { assertEquals(collection.id, it.collectionId) }
        }
    }

    // ---- 边界 ----

    @Test
    fun `子目录名不能为空所以根集合 id 无歧义`() {
        // 曾用 "\u0000root" 作保留前缀，被这条约束抓出会与同名目录撞 id；空串不会。
        val docs = listOf(img("a.jpg"), img("root/a.jpg"))
        val result = collectionsFrom("相册", docs)
        assertEquals(listOf(ROOT_COLLECTION_ID, "root"), result.map { it.id })
    }

    @Test
    fun `根目录名为空时仍能建出集合`() {
        val result = collectionsFrom("", listOf(img("a.jpg")))
        assertEquals(1, result.size)
        assertEquals("", result[0].name)
    }
}
