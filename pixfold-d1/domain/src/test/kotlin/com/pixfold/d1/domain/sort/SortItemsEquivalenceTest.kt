package com.pixfold.d1.domain.sort

import com.pixfold.d1.domain.model.SortField
import com.pixfold.d1.domain.model.SortKey
import com.pixfold.d1.domain.model.SortRule
import com.pixfold.d1.domain.model.SourceItem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 排序键预计算优化的**等价性守护**(2026-09-17)。
 *
 * 背景:`sortItems` 原在每次比较里重新 `lowercase()`+分词(n=3000 约 1.7 万次),
 * 改为每项预计算一次。本测试把**朴素实现**(现算)与**优化实现**对**同一组数据、同一批规则**
 * 逐一比对,确保结果逐项一致 —— 优化不得改变任何语义。
 */
class SortItemsEquivalenceTest {

    /** 朴素实现:忠实复刻优化前的写法(每次比较现算)。 */
    private fun naiveSort(images: List<SourceItem>, rule: SortRule): List<SourceItem> =
        images.sortedWith { a, b ->
            var result = 0
            for (key in rule.keys) {
                val c = when (key.field) {
                    SortField.NaturalName ->
                        naturalCompare(a.fileName.lowercase(), b.fileName.lowercase())
                    SortField.FileName -> a.fileName.lowercase().compareTo(b.fileName.lowercase())
                    SortField.DirName -> a.dir.compareTo(b.dir)
                    SortField.Modified -> a.modifiedEpochMillis.compareTo(b.modifiedEpochMillis)
                    SortField.Created -> when {
                        a.createdEpochMillis == null && b.createdEpochMillis == null -> 0
                        a.createdEpochMillis == null -> -1
                        b.createdEpochMillis == null -> 1
                        else -> a.createdEpochMillis!!.compareTo(b.createdEpochMillis!!)
                    }
                    SortField.Size -> a.sizeBytes.compareTo(b.sizeBytes)
                }
                if (c != 0) {
                    result = if (key.ascending) c else -c
                    break
                }
            }
            if (result != 0) result else a.id.compareTo(b.id)
        }

    private fun img(id: String, dir: String, name: String, size: Long, mod: Long, created: Long? = 0L) =
        SourceItem(
            id = id, collectionId = "c", dir = dir, baseName = name.substringBeforeLast('.'),
            ext = name.substringAfterLast('.'), sizeBytes = size,
            modifiedEpochMillis = mod, createdEpochMillis = created, seed = id.hashCode(),
        )

    private fun corpus(): List<SourceItem> = buildList {
        // 数字段:补零与不补零、前缀零、超长数字、大小写混杂、中文、非法字符、空目录
        add(img("a1", "day1", "IMG_001.jpg", 100, 5))
        add(img("a2", "day1", "IMG_1.jpg", 90, 4))
        add(img("a3", "day1", "IMG_0001.jpg", 80, 3))
        add(img("a4", "day1", "img_2.jpg", 70, 2))
        add(img("a5", "day2", "IMG_010.jpg", 60, 1))
        add(img("a6", "day2", "IMG_10.jpg", 50, 6))
        add(img("a7", "", "page_01.png", 40, 7))
        add(img("a8", "", "page_1.png", 30, 8))
        add(img("a9", "ch02", "封:面?.png", 20, 9))
        add(img("a10", "ch02", "Page_01.PNG", 10, 10))
        add(img("a11", "day1", "IMG_99999999999999999999.jpg", 5, 11)) // 超长数字
        add(img("a12", "day1", "IMG_00000000000000000001.jpg", 5, 12))
        // 全等项(含 created=null)-> 触发 id 兜底
        add(img("b1", "x", "same.jpg", 7, 77, null))
        add(img("b2", "x", "same.jpg", 7, 77, null))
        add(img("b3", "x", "same.jpg", 7, 77, 1L))
    }

    private fun allRules(): List<SortRule> {
        val singles = SortField.entries.flatMap { f ->
            listOf(SortRule(listOf(SortKey(f, true))), SortRule(listOf(SortKey(f, false))))
        }
        val pairs = listOf(
            SortRule(listOf(SortKey(SortField.DirName, true), SortKey(SortField.NaturalName, true))),
            SortRule(listOf(SortKey(SortField.DirName, false), SortKey(SortField.NaturalName, true))),
            SortRule(listOf(SortKey(SortField.Size, false), SortKey(SortField.FileName, true))),
            SortRule(listOf(SortKey(SortField.Modified, true), SortKey(SortField.Created, true))),
            SortRule(listOf(SortKey(SortField.Size, true), SortKey(SortField.Size, false))),
        )
        val quads = listOf(
            SortRule(
                listOf(
                    SortKey(SortField.Size, false),
                    SortKey(SortField.DirName, true),
                    SortKey(SortField.NaturalName, false),
                    SortKey(SortField.FileName, true),
                ),
            ),
        )
        return singles + pairs + quads
    }

    @Test
    fun `optimized sort matches naive sort for every rule`() {
        val data = corpus()
        var checked = 0
        for (rule in allRules()) {
            val expected = naiveSort(data, rule).map { it.id }
            val actual = sortItems(data, rule).map { it.id }
            assertEquals(expected, actual, "规则 $rule 下优化实现与朴素实现结果不一致")
            checked++
        }
        // 断言确实跑了足够多的规则,避免循环空转
        assertEquals(6 * 2 + 5 + 1, checked, "应覆盖 18 条规则")
    }

    @Test
    fun `optimized sort is stable and deterministic across repeated runs`() {
        val data = corpus()
        for (rule in allRules()) {
            val a = sortItems(data, rule).map { it.id }
            val b = sortItems(data.shuffled(), rule).map { it.id }
            assertEquals(a, b, "同一规则、不同输入顺序应得到相同结果(确定性)")
        }
    }

    @Test
    fun `optimized sort does not mutate input`() {
        val data = corpus()
        val snapshot = data.map { it.id }
        sortItems(data, SortRule(listOf(SortKey(SortField.NaturalName, false))))
        assertEquals(snapshot, data.map { it.id }, "sortItems 不得修改入参")
    }
}
