package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.comic.ComicInfo
import com.cyanix.pixfold.domain.comic.renumberPages
import com.cyanix.pixfold.domain.mock.MockDataB
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.sort.pageOrderOf
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 写入计划与「缺图」路径(D3 退出条件 1 的「写入计划」)。
 *
 * ## 为什么要有这一层,而不是让调用方自己拼 `CbzWriter`
 *
 * `ComicInfo.build` 与 `renumberPages` 必须**同源**:XML 里宣告的页数与归档内实际条目数
 * 若来自两次独立计算,任何一处漂移都会产出「元数据说 16 页、包里只有 15 张」的成品 ——
 * 而阅读器不会报错,只是默默少一页。故 [buildCbzWritePlan] 一次算出两者,
 * [CbzWritePlan] 成为"这一卷要写成什么"的**唯一结论**。
 *
 * ## 「缺图」为什么不抛异常、也不写半成品
 *
 * 参考脚本 `batch_pack_cbz.py` 遇到读不到的文件会直接抛 `IOError` 终止 —— 它**不会**
 * 产出少页的 CBZ。这里保留该结果语义(不产出残缺归档),但把"失败"变成**可判定的返回值**:
 * 领域层不许有副作用,更不许用异常表达"用户还得再看一眼"这种**正常的业务分支**。
 * 于是 [writeCbz] 全有或全无:**任何一页取不到字节 ⇒ 一个字节都不写**,并把缺哪些页报回去,
 * 让平台层决定怎么提示(提示形态属 D2b/UI,不在这里定)。
 */
class CbzWritePlanTest {

    private val library = MockDataB.library
    private val aot1 = library.volumes.first { it.id == "aot-1" }
    private val time = 1_700_000_000_000L

    private fun volumeWith(pageCount: Int): ComicVolume {
        val pages = (1..pageCount).map { i ->
            SourceItem(
                id = "p$i",
                collectionId = "c",
                dir = "D:\\lib\\v",
                baseName = "img${i.toString().padStart(3, '0')}",
                ext = "jpg",
                sizeBytes = 10L,
                modifiedEpochMillis = time,
                createdEpochMillis = null,
                seed = i,
            )
        }
        return aot1.copy(pages = pages, pageOrder = pageOrderOf(pages))
    }

    /** 一个每页都给得出字节的源。 */
    private fun sourceOf(vararg ids: String) = PageByteSource { id ->
        if (id in ids) "bytes-of-$id".toByteArray(Charsets.UTF_8) else null
    }

    private fun allPages(plan: CbzWritePlan) = PageByteSource { id ->
        "bytes-of-$id".toByteArray(Charsets.UTF_8)
    }

    private fun readBack(bytes: ByteArray): List<Pair<ZipEntry, ByteArray>> {
        val result = mutableListOf<Pair<ZipEntry, ByteArray>>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                result += e to zip.readBytes()
            }
        }
        return result
    }

    // ---- 计划的内容:与既有求值同源 ----

    @Test
    fun `the plan reuses the comic info xml built for the volume`() {
        assertEquals(ComicInfo.build(aot1), buildCbzWritePlan(aot1).comicInfoXml)
    }

    @Test
    fun `plan page names come from the existing renumbering`() {
        // 补零位数、"扩展名强制小写"这类规则已在 renumberPages 里实现;写入计划**不得**
        // 再实现一遍 —— 那会出现两套命名规则,计划页与成品就会不一致。
        assertEquals(
            renumberPages(aot1).map { it.fileName },
            buildCbzWritePlan(aot1).pages.map { it.fileName },
        )
    }

    @Test
    fun `plan page ids trace back to the volume pages`() {
        assertEquals(
            renumberPages(aot1).map { it.item.id },
            buildCbzWritePlan(aot1).pages.map { it.itemId },
        )
    }

    @Test
    fun `the plan follows the volume page order rather than the source listing`() {
        // 页序可能被人工拖拽调整过。计划必须照**最终页序**走,
        // 否则用户看到的顺序与成品顺序不一致(且 ComicInfo 的页数仍会"对得上",更难发现)。
        val reversed = aot1.copy(pageOrder = aot1.pageOrder.copy(order = aot1.pageOrder.order.reversed()))
        assertEquals(
            renumberPages(reversed).map { it.fileName },
            buildCbzWritePlan(reversed).pages.map { it.fileName },
        )
        assertEquals(
            reversed.pageOrder.order.map { it.id },
            buildCbzWritePlan(reversed).pages.map { it.itemId },
        )
    }

    @Test
    fun `the plan records the volume id and page count for traceability`() {
        // HANGOFF §5 关键原则 5「结果可追溯」:报告要能说清"这是哪一卷、计划写多少页"。
        val plan = buildCbzWritePlan(aot1)
        assertEquals("aot-1", plan.volumeId)
        assertEquals(aot1.pageOrder.order.size, plan.pageCount)
        assertEquals(plan.pages.size, plan.pageCount)
    }

    @Test
    fun `a hundred pages widen the padding to four digits`() {
        // renumberPages 的规则:>= 100 页用 4 位补零。计划必须继承该结论 ——
        // 若这里自己算成 3 位,`099.jpg` 之后会变成 `100.jpg`(位数不齐,排序错乱)。
        val plan = buildCbzWritePlan(volumeWith(100))
        assertEquals("0001.jpg", plan.pages.first().fileName)
        assertEquals("0100.jpg", plan.pages.last().fileName)
    }

    @Test
    fun `ninety nine pages stay at three digits`() {
        // 边界反向守卫:差一页就该回落到 3 位,否则 100 页那条断言无法说明阈值在哪。
        val plan = buildCbzWritePlan(volumeWith(99))
        assertEquals("001.jpg", plan.pages.first().fileName)
        assertEquals("099.jpg", plan.pages.last().fileName)
    }

    @Test
    fun `a volume without pages yields a plan holding only comic info`() {
        val plan = buildCbzWritePlan(volumeWith(0))
        assertEquals(0, plan.pageCount)
        assertTrue(plan.pages.isEmpty())
    }

    // ---- 写入:全有或全无 ----

    @Test
    fun `a complete plan writes every entry in plan order`() {
        val plan = buildCbzWritePlan(aot1)
        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, allPages(plan), out, time)

        val names = readBack(out.toByteArray()).map { it.first.name }
        assertEquals(listOf(CbzWriter.COMIC_INFO_ENTRY) + plan.pages.map { it.fileName }, names)
        assertEquals(names, result.writtenEntries, "报告必须与归档实际内容一致")
        assertTrue(result.ok)
        assertTrue(result.missing.isEmpty())
    }

    @Test
    fun `page bytes reach the archive verbatim`() {
        val plan = buildCbzWritePlan(aot1)
        val out = ByteArrayOutputStream()
        writeCbz(plan, allPages(plan), out, time)

        val entries = readBack(out.toByteArray())
        assertContentEquals(plan.comicInfoXml.toByteArray(Charsets.UTF_8), entries.first().second)
        plan.pages.forEachIndexed { i, p ->
            assertEquals(p.fileName, entries[i + 1].first.name)
            assertContentEquals("bytes-of-${p.itemId}".toByteArray(Charsets.UTF_8), entries[i + 1].second)
        }
    }

    @Test
    fun `missing page bytes are reported rather than thrown`() {
        // 「图读不到」是正常业务分支(文件被移动 / 权限失效),不是程序错误。
        val plan = buildCbzWritePlan(aot1)
        val have = plan.pages.first().itemId
        val result = writeCbz(plan, sourceOf(have), ByteArrayOutputStream(), time)

        assertEquals(plan.pages.size - 1, result.missing.size)
        assertEquals(
            plan.pages.drop(1).map { it.itemId },
            result.missing.map { it.itemId },
            "缺图列表应保持计划顺序,且不含已提供字节的那一页",
        )
    }

    @Test
    fun `missing entries keep plan order`() {
        val plan = buildCbzWritePlan(volumeWith(5))
        val have = setOf(plan.pages[0].itemId, plan.pages[3].itemId)
        val result = writeCbz(plan, sourceOf(*have.toTypedArray()), ByteArrayOutputStream(), time)

        assertEquals(
            listOf(plan.pages[1].fileName, plan.pages[2].fileName, plan.pages[4].fileName),
            result.missing.map { it.fileName },
        )
    }

    @Test
    fun `a plan with a missing page writes nothing at all`() {
        // 关键不变式:全有或全无。若这里写了"能读到的那几页",调用方会拿到一个
        // **看起来正常**的 CBZ(能打开、ComicInfo 也在),但页数少了 ——
        // 这正是参考脚本用抛异常避免的后果,这里用"不写"达到同样效果而不引入副作用。
        val plan = buildCbzWritePlan(aot1)
        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, sourceOf(plan.pages.first().itemId), out, time)

        assertEquals(0, out.size(), "取不全就不该写出任何字节")
        assertTrue(result.writtenEntries.isEmpty())
        assertFalse(result.ok)
    }

    @Test
    fun `an empty plan writes only comic info and reports no missing pages`() {
        val plan = buildCbzWritePlan(volumeWith(0))
        val out = ByteArrayOutputStream()
        val result = writeCbz(plan, PageByteSource { null }, out, time)

        assertTrue(result.ok, "没有页就没有可缺的东西")
        assertEquals(listOf(CbzWriter.COMIC_INFO_ENTRY), result.writtenEntries)
        assertEquals(1, readBack(out.toByteArray()).size)
    }

    @Test
    fun `every page is probed before any byte is written`() {
        // 守卫"先探测全部再写"的实现方式:若实现改成"边写边取",第一条会先落盘,
        // 于是一旦后面缺图就会留下半成品 —— 上一条测试会红,这条补充说明顺序。
        //
        // 流式实现下每页会被打开**三次**(探测可读 / 算 CRC 与长度 / 真正写出),
        // 故这里不数总次数,只断「前 N 次恰好是按计划顺序的全部页」—— 那才是
        // "写入之前先探测完整轮"的可观测形态。
        val plan = buildCbzWritePlan(aot1)
        val asked = mutableListOf<String>()
        writeCbz(
            plan,
            PageByteSource { id -> asked += id; "b".toByteArray() },
            ByteArrayOutputStream(),
            time,
        )
        assertEquals(
            plan.pages.map { it.itemId },
            asked.take(plan.pages.size),
            "探测必须覆盖全部页且按计划顺序",
        )
    }

    // ---- 与 CbzWriter 的契约衔接 ----

    @Test
    fun `a non positive timestamp is rejected even when pages are missing`() {
        // 校验前置:失败原因不该取决于"恰好缺不缺图"。
        val plan = buildCbzWritePlan(aot1)
        val ex = assertFailsWith<IllegalArgumentException> {
            writeCbz(plan, PageByteSource { null }, ByteArrayOutputStream(), 0)
        }
        assertTrue("timeMillis" in ex.message.orEmpty(), "报错应点明时间戳，实际 ${ex.message}")
    }

    @Test
    fun `writing twice with the same inputs produces byte identical archives`() {
        val plan = buildCbzWritePlan(aot1)
        val a = ByteArrayOutputStream().also { writeCbz(plan, allPages(plan), it, time) }
        val b = ByteArrayOutputStream().also { writeCbz(plan, allPages(plan), it, time) }
        assertContentEquals(a.toByteArray(), b.toByteArray())
    }

    @Test
    fun `a duplicate entry name is rejected`() {
        // ZIP 允许重名条目,但阅读器只会取其中一个 —— 一个"某页永远打不开"的归档
        // 不该被静默产出。正常路径由索引生成的名字天然唯一,这里守的是直接构造计划的情况。
        val ex = assertFailsWith<IllegalArgumentException> {
            CbzWritePlan(
                volumeId = "v",
                comicInfoXml = "<ComicInfo />",
                pages = listOf(
                    CbzPageEntry("a", "001.jpg"),
                    CbzPageEntry("b", "001.jpg"),
                ),
            )
        }
        assertTrue("重复" in ex.message.orEmpty(), "报错应点明重复，实际 ${ex.message}")
    }

    @Test
    fun `writeCbz leaves the caller's stream open`() {
        val plan = buildCbzWritePlan(aot1)
        val out = ByteArrayOutputStream()
        writeCbz(plan, allPages(plan), out, time)
        val afterZip = out.size()
        out.write("TRAILER".toByteArray())
        assertEquals(afterZip + 7, out.size())
    }

    @Test
    fun `a plan with no pages still carries a usable comic info xml`() {
        assertTrue(
            buildCbzWritePlan(volumeWith(0)).comicInfoXml.isNotEmpty(),
            "空计划的 XML 也必须非空 —— 否则归档里会多出一个零字节条目",
        )
    }

    @Test
    fun `a page named like the comic info entry is rejected`() {
        // 重名检查漏掉的**那一个**名字:ComicInfo.xml 由 CbzWriter 无条件写在第 0 条,
        // 所以"页也叫 ComicInfo.xml"必然产出两个同名条目 —— 与上面那条重名检查要挡的
        // 是同一类静默错误,只是调用方不必自己把这个名字写进 pages 就会撞上。
        // ZIP 结构合法,阅读器取到哪一个不确定(可能拿到图片当元数据,也可能反过来),
        // 故在计划层挡住。
        val ex = assertFailsWith<IllegalArgumentException> {
            CbzWritePlan(
                volumeId = "v",
                comicInfoXml = "<ComicInfo />",
                pages = listOf(CbzPageEntry("a", "ComicInfo.xml")),
            )
        }
        assertTrue("ComicInfo.xml" in ex.message.orEmpty(), "报错应点明撞的是元数据条目名，实际 ${ex.message}")
    }

    @Test
    fun `only the exact comic info name collides`() {
        // 上一条守卫的**精度**:挡的是"与元数据条目同名",不是"名字里含 ComicInfo"。
        // 大小写、前后缀都不同名 —— ZIP 条目名是区分大小写的,不能顺手扩大打击面,
        // 否则用户真有一张叫 `ComicInfo.png` 的图就打不了包了。
        val plan = CbzWritePlan(
            volumeId = "v",
            comicInfoXml = "<ComicInfo />",
            pages = listOf(
                CbzPageEntry("a", "comicinfo.xml"),
                CbzPageEntry("b", "ComicInfo.png"),
                CbzPageEntry("c", "001-ComicInfo.xml"),
            ),
        )
        assertEquals(
            listOf("comicinfo.xml", "ComicInfo.png", "001-ComicInfo.xml"),
            plan.pages.map { it.fileName },
            "只有逐字同名的才该被拒",
        )
    }

    @Test
    fun `an empty comic info xml is rejected`() {
        // 零字节的 ComicInfo.xml 是**静默错误**:归档结构完全合法,阅读器读元数据时
        // 只会拿到空串,没有任何异常可循。而 XML 本不该为空(至少含声明与根元素),
        // 故在计划构造处挡住,而不是等字节写出去之后才发现。
        val ex = assertFailsWith<IllegalArgumentException> {
            CbzWritePlan(volumeId = "v", comicInfoXml = "", pages = emptyList())
        }
        assertTrue("ComicInfo.xml" in ex.message.orEmpty(), "报错应点明是 XML 为空，实际 ${ex.message}")
    }
}
