package com.cyanix.pixfold.domain.source

import com.cyanix.pixfold.domain.model.ImageCollection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * D2a `collectionsFrom` 分组规则单测。
 *
 * 分组规则是**唯一权威**（见 [collectionsFrom] KDoc）：平台层只遍历、不决定分组，
 * 所以这里的断言就是「选了目录后用户会看到几个集合」的定义。
 */
class SourceGroupsTest {

    private fun img(path: String, mime: String = "image/jpeg", size: Long = 100L, mod: Long = 5L) =
        SourceDoc(
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

    @Test
    fun `空文档列表得到空集合列表`() {
        assertEquals(emptyList(), collectionsFrom("相册", emptyList()))
    }

    @Test
    fun `只有目录没有图片时得到空集合列表`() {
        val result = collectionsFrom("相册", listOf(dir("day1"), dir("day2")))
        assertEquals(emptyList(), result)
    }

    @Test
    fun `根下一级子目录各成一个集合`() {
        val result = collectionsFrom(
            "相册",
            listOf(
                dir("day1"), img("day1/a.jpg"), img("day1/b.jpg"),
                dir("day2"), img("day2/c.jpg"),
            ),
        )
        assertEquals(listOf("day1", "day2"), result.map { it.id })
        assertEquals(listOf(2, 1), result.map { it.images.size })
    }

    @Test
    fun `根下直接躺的图片归入以根目录命名的集合`() {
        val result = collectionsFrom("我的相册", listOf(img("a.jpg"), img("b.jpg")))
        assertEquals(1, result.size)
        assertEquals(ROOT_COLLECTION_ID, result.single().id)
        assertEquals("我的相册", result.single().name)
        assertEquals(2, result.single().images.size)
    }

    @Test
    fun `集合内的 dir 是相对集合根的路径`() {
        val result = collectionsFrom("相册", listOf(img("trip/day1/a.jpg")))
        val item = result.single().images.single()
        assertEquals("trip", result.single().id)
        assertEquals("day1", item.dir)
        assertEquals("a.jpg", item.fileName)
    }

    @Test
    fun `根下图片的 dir 为空串`() {
        val result = collectionsFrom("相册", listOf(img("a.jpg")))
        assertEquals("", result.single().images.single().dir)
    }

    @Test
    fun `多级目录不丢层级`() {
        val result = collectionsFrom("相册", listOf(img("trip/day1/sub/a.jpg")))
        assertEquals("trip", result.single().id)
        assertEquals("day1/sub", result.single().images.single().dir)
    }

    @Test
    fun `非图片 MIME 被过滤`() {
        val result = collectionsFrom(
            "相册",
            listOf(
                img("a.jpg"),
                SourceDoc("content://x/n", "note.txt", "text/plain", 1L, 0L, "note.txt"),
                SourceDoc("content://x/v", "clip.mp4", "video/mp4", 1L, 0L, "clip.mp4"),
            ),
        )
        assertEquals(listOf("a.jpg"), result.single().images.map { it.fileName })
    }

    @Test
    fun `图片判定按 MIME 前缀而非扩展名`() {
        // 扩展名是 .jpg 但 MIME 不是图片 -> 不收；反之 .bin 但 MIME 是图片 -> 收
        val result = collectionsFrom(
            "相册",
            listOf(
                SourceDoc("content://x/1", "fake.jpg", "text/plain", 1L, 0L, "fake.jpg"),
                SourceDoc("content://x/2", "real.bin", "image/png", 1L, 0L, "real.bin"),
            ),
        )
        assertEquals(listOf("real.bin"), result.single().images.map { it.fileName })
    }

    @Test
    fun `同一 URI 只收一次`() {
        val dup = img("a.jpg")
        val result = collectionsFrom("相册", listOf(dup, dup))
        assertEquals(1, result.single().images.size)
    }

    @Test
    fun `集合按自然序排列而不是字典序`() {
        // 字典序会把 "10" 排在 "9" 前；自然序应 2,9,10
        val result = collectionsFrom(
            "相册",
            listOf(dir("vol10"), img("vol10/a.jpg"), dir("vol2"), img("vol2/a.jpg"), dir("vol9"), img("vol9/a.jpg")),
        )
        assertEquals(listOf("vol2", "vol9", "vol10"), result.map { it.id })
    }

    @Test
    fun `集合内图片按自然序排列`() {
        val result = collectionsFrom(
            "相册",
            listOf(img("p10.jpg"), img("p9.jpg"), img("p2.jpg")),
        )
        assertEquals(listOf("p2.jpg", "p9.jpg", "p10.jpg"), result.single().images.map { it.fileName })
    }

    @Test
    fun `集合内排序是大小写不敏感之外的既有语义`() {
        // 只是钉住「沿用 naturalCompare」这件事，防止有人换成 sortedBy { it.fileName }
        val result = collectionsFrom("相册", listOf(img("Page_10.PNG"), img("Page_2.PNG")))
        assertEquals(listOf("Page_2.PNG", "Page_10.PNG"), result.single().images.map { it.fileName })
    }

    @Test
    fun `SourceItem 带上 uri 与派生字段`() {
        val result = collectionsFrom(
            "相册",
            listOf(img("day1/IMG_001.jpg", size = 2048L, mod = 777L)),
        )
        val item = result.single().images.single()
        assertEquals("content://tree/doc/day1/IMG_001.jpg", item.uri)
        assertEquals("content://tree/doc/day1/IMG_001.jpg", item.id)
        assertEquals("day1", item.collectionId)
        assertEquals("IMG_001", item.baseName)
        assertEquals("jpg", item.ext)
        assertEquals(2048L, item.sizeBytes)
        assertEquals(777L, item.modifiedEpochMillis)
        assertNull(item.createdEpochMillis)
    }

    @Test
    fun `没有扩展名的图片文件名不炸`() {
        val result = collectionsFrom(
            "相册",
            listOf(SourceDoc("content://x/1", "README", "image/png", 1L, 0L, "README")),
        )
        val item = result.single().images.single()
        assertEquals("README", item.baseName)
        assertEquals("", item.ext)
    }

    @Test
    fun `根集合 id 是空串且与任何子目录集合都不撞`() {
        // 根集合用空串作 id —— 子目录名必非空，所以这是唯一无冲突的取值。
        // （曾用 "\u0000root" 作保留前缀，被这条测试抓出会与同名目录撞 id。）
        val result = collectionsFrom(
            "相册",
            listOf(img("a.jpg"), img("root/b.jpg"), img("day1/c.jpg")),
        )
        assertEquals(3, result.size)
        assertEquals(result.map { it.id }.distinct().size, result.size)
        assertEquals(ROOT_COLLECTION_ID, result.first { it.name == "相册" }.id)
    }

    @Test
    fun `子目录名不能为空所以根集合 id 无歧义`() {
        val result = collectionsFrom("相册", listOf(img("a.jpg")))
        assertEquals("", ROOT_COLLECTION_ID)
        assertEquals(ROOT_COLLECTION_ID, result.single().id)
    }

    @Test
    fun `根目录名为空时仍能建出集合`() {
        val result = collectionsFrom("", listOf(img("a.jpg")))
        assertEquals(1, result.size)
        assertEquals("", result.single().name)
    }

    @Test
    fun `每个集合的 rootDirName 等于集合名`() {
        val result = collectionsFrom("相册", listOf(img("trip/a.jpg")))
        val c: ImageCollection = result.single()
        assertEquals(c.name, c.rootDirName)
    }

    @Test
    fun `集合内每项的 collectionId 指向所属集合`() {
        val result = collectionsFrom(
            "相册",
            listOf(img("trip/a.jpg"), img("scan/b.jpg")),
        )
        result.forEach { c -> c.images.forEach { assertEquals(c.id, it.collectionId) } }
    }

    @Test
    fun `目录条目本身不会变成图片`() {
        val result = collectionsFrom("相册", listOf(dir("day1"), img("day1/a.jpg")))
        assertEquals(1, result.single().images.size)
        assertTrue(result.single().images.none { it.fileName == "day1" })
    }
}
