package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.model.defaultRule
import kotlin.test.Test
import kotlin.test.assertEquals

private fun item(
    id: String,
    dir: String = "",
    base: String = id,
    ext: String = "jpg",
    size: Long = 100,
    modified: Long = 0,
    created: Long? = 0,
) = SourceItem(
    id = id, collectionId = "c", dir = dir, baseName = base, ext = ext,
    sizeBytes = size, modifiedEpochMillis = modified, createdEpochMillis = created, seed = 0,
)

class SortItemsTest {

    @Test
    fun `natural name sorts numerically`() {
        val list = listOf(item("i10", base = "img10"), item("i2", base = "img2"), item("i1", base = "img1"))
        val sorted = sortItems(list, SortRule(listOf(SortKey(SortField.NaturalName, true))))
        assertEquals(listOf("i1", "i2", "i10"), sorted.map { it.id })
    }

    @Test
    fun `lexical name sorts differently from natural`() {
        val list = listOf(item("i10", base = "img10"), item("i2", base = "img2"), item("i1", base = "img1"))
        val sorted = sortItems(list, SortRule(listOf(SortKey(SortField.FileName, true))))
        assertEquals(listOf("i1", "i10", "i2"), sorted.map { it.id })
    }

    @Test
    fun `each level has its own direction`() {
        val list = listOf(
            item("a2", dir = "d1", base = "p2"),
            item("a1", dir = "d1", base = "p1"),
            item("b1", dir = "d2", base = "p1"),
        )
        // 目录名升序,同目录内文件名自然序降序
        val rule = SortRule(listOf(SortKey(SortField.DirName, true), SortKey(SortField.NaturalName, false)))
        val sorted = sortItems(list, rule)
        assertEquals(listOf("a2", "a1", "b1"), sorted.map { it.id })
    }

    @Test
    fun `ties fall back to id for determinism`() {
        val list = listOf(item("z"), item("a"), item("m"))
        val sorted = sortItems(list, SortRule(listOf(SortKey(SortField.Size, true))))
        assertEquals(listOf("a", "m", "z"), sorted.map { it.id })
    }

    @Test
    fun `sorting does not mutate input`() {
        val list = listOf(item("i10", base = "img10"), item("i2", base = "img2"))
        val before = list.map { it.id }
        sortItems(list, SortRule(listOf(SortKey(SortField.NaturalName, true))))
        assertEquals(before, list.map { it.id })
    }

    @Test
    fun `default rule is dir asc then natural name asc`() {
        val r = defaultRule()
        assertEquals(listOf(SortField.DirName, SortField.NaturalName), r.keys.map { it.field })
        assertEquals(listOf(true, true), r.keys.map { it.ascending })
    }

    @Test
    fun `null created sorts first`() {
        val list = listOf(item("has", created = 100), item("none", created = null))
        val sorted = sortItems(list, SortRule(listOf(SortKey(SortField.Created, true))))
        assertEquals(listOf("none", "has"), sorted.map { it.id })
    }

    @Test
    fun `sizeLabel formats units`() {
        assertEquals("500 B", item("x", size = 500).sizeLabel)
        assertEquals("2 KB", item("x", size = 2048).sizeLabel)
        assertEquals("1.5 MB", item("x", size = 1572864).sizeLabel)
    }
}
