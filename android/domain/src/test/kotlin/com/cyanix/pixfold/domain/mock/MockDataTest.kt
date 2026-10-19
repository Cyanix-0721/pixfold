package com.cyanix.pixfold.domain.mock

import com.cyanix.pixfold.domain.organize.organizeStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockDataTest {

    @Test
    fun `workspace A has four collections`() {
        val ws = MockData.workspaceA
        assertEquals(listOf("trip", "scan", "misc", "cn"), ws.collections.map { it.id })
    }

    @Test
    fun `trip collection spans two dirs with 30 images`() {
        val trip = MockData.workspaceA.collections.first { it.id == "trip" }
        assertEquals(setOf("day1", "day2"), trip.images.map { it.dir }.toSet())
        assertEquals(30, trip.images.size)
    }

    @Test
    fun `scan collection covers duplicate illegal and case-collision samples`() {
        val scan = MockData.workspaceA.collections.first { it.id == "scan" }
        // 跨目录同名 baseName
        assertEquals(2, scan.images.count { it.baseName == "page_01" })
        // 非法字符样例
        assertTrue(scan.images.any { it.baseName.contains(':') || it.baseName.contains('?') })
        // 大小写冲突对
        assertTrue(scan.images.any { it.ext == "PNG" })
    }

    @Test
    fun `misc collection exposes natural vs lexical difference`() {
        val misc = MockData.workspaceA.collections.first { it.id == "misc" }
        assertEquals(12, misc.images.size)
        assertTrue(misc.images.any { it.baseName == "img10" }, "应含 img10 以体现字典序与自然序差异")
    }

    @Test
    fun `chapters collection mixes chinese and arabic numerals out of order`() {
        val cn = MockData.workspaceA.collections.first { it.id == "cn" }
        // 中文与阿拉伯两种写法都要有:归一化后 `第十话` 与 `第10话` 应当并列,只测一种覆盖不到等价性
        assertTrue(cn.images.any { it.baseName == "第十话" }, "应含中文数字名")
        assertTrue(cn.images.any { it.baseName == "第10话" }, "应含阿拉伯写法以验证两者等价")
        // 声明顺序必须**不是**正确顺序,否则真机上顺序对了也证明不了是排序器排的
        val declared = cn.images.map { it.baseName }
        assertTrue(declared != declared.sorted(), "声明顺序不应恰好等于字典序,否则看不出排序是否生效")
    }

    /**
     * 章节集合经**真实排序管线**后必须是数值升序。
     *
     * 上面那条只检查了"素材够乱、两种写法都在",但没钉住"排完该是什么样" ——
     * 真机核对时要用它当**逐字对照表**,所以这里把期望顺序写死。
     *
     * `第十话` 与 `第10话` 值相等,谁在前由 `sortItems` 的 **id 兜底**决定
     * (`SortItems.kt:61 images[i].id.compareTo(images[j].id)`,
     * 规格 §5.2 要求结果确定,所以不依赖稳定排序):`cn-11` < `cn-4` ⇒ `第10话` 在前。
     * 这一条顺带把"等价写法相邻"也钉住了。
     */
    @Test
    fun `chapters collection sorts into numeric order through the real pipeline`() {
        val cn = MockData.workspaceA.collections.first { it.id == "cn" }
        val state = organizeStateOf(MockData.workspaceA.collections)
        val order = state.orders.getValue("cn")
        assertEquals(
            listOf(
                "第一话", "第二话", "第三话", "第四话", "第五话", "第六话", "第七话", "第八话", "第九话",
                "第10话", "第十话", "第十一话", "第十二话", "第十三话", "第二十话", "第21话",
            ),
            order.order.map { it.baseName },
            "章节必须按数值升序显示(中文数字与阿拉伯数字混排等价)",
        )
        // 集合本身仍在,顺序来自排序器而非声明顺序
        assertEquals(cn.images.size, order.order.size)
    }

    @Test
    fun `mock data is deterministic across calls`() {
        val a = MockData.workspaceA.collections.first { it.id == "misc" }.images.map { it.id }
        val b = MockData.workspaceA.collections.first { it.id == "misc" }.images.map { it.id }
        assertEquals(a, b)
    }
}
