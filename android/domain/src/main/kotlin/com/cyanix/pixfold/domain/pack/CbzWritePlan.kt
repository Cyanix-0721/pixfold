package com.cyanix.pixfold.domain.pack

import com.cyanix.pixfold.domain.comic.ComicInfo
import com.cyanix.pixfold.domain.comic.renumberPages
import com.cyanix.pixfold.domain.model.ComicVolume
import java.io.InputStream
import java.io.OutputStream

/**
 * 把「一卷漫画」变成「一份可写入的字节计划」。
 *
 * ## 为什么这一层存在
 *
 * `ComicInfo.build`(XML)与 `renumberPages`(归档内文件名)必须**同源**。若调用方各自算一次,
 * 任何一处漂移都会产出「元数据说 16 页、包里只有 15 张」的成品 —— 阅读器不会报错,
 * 只是默默少一页。故 [buildCbzWritePlan] 一次算出两者,[CbzWritePlan] 成为
 * **"这一卷要写成什么"的唯一结论**。
 *
 * ## 归责边界
 *
 * - **归这里**:XML 内容、条目名、条目顺序(= 最终页序)、缺图判定;
 * - **不归这里**:图片字节**从哪来**([PageByteSource] 由平台层实现,SAF/ContentResolver/
 *   本地文件都行)、字节**写到哪**([CbzWriter] 收 `OutputStream`)。
 *
 * 所以本文件不 import 任何 Android 类型,也不需要文件系统 —— D3 退出条件 2
 * 「`domain/` 零 Android 依赖」在这里同样成立。
 */
data class CbzWritePlan(
    /** 来源卷的 id,供执行报告与撤销记录追溯(HANGOFF §5 关键原则 5)。 */
    val volumeId: String,
    /** 已生成的 ComicInfo.xml 文本,将成为归档的第 0 个条目。 */
    val comicInfoXml: String,
    /** 图片条目,**列表顺序即最终页序**。 */
    val pages: List<CbzPageEntry>,
) {
    init {
        // 空 XML 会产出零字节条目 —— 阅读器读元数据时会拿到空串而非报错,
        // 属于"静默错误",宁可在构造处挡住。
        require(comicInfoXml.isNotEmpty()) { "ComicInfo.xml 内容不能为空（会产出零字节元数据条目）" }
        // ZIP 允许重名条目,但阅读器只会取其中一个 —— 一个"某页永远打不开"的归档
        // 不该被静默产出。正常路径由索引生成的名字天然唯一,这里守的是直接构造计划的情况。
        //
        // 这条**特意不放进 [CbzWriter]**:那一层是字节原语,它的职责是把给定的条目照原样写出来
        // (顺序、压缩方式、时间戳),重名与否是**计划的正确性**问题,该在有名字来源的这层判。
        val duplicated = pages.map { it.fileName }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        require(duplicated.isEmpty()) { "计划里出现重复条目名：$duplicated" }
        // 重名检查必须把**元数据条目**也算进来:[CbzWriter] 无条件把 ComicInfo.xml 写在第 0 条,
        // 所以"页也叫 ComicInfo.xml"会产出两个同名条目 —— 与上面要挡的是同一类静默错误。
        // ZIP 结构依然合法,阅读器取到哪一个不确定(可能把图片当元数据,也可能反过来),
        // 而上面的重名检查看不见它(调用方不必自己写这个名字就会撞上)。
        val collision = pages.filter { it.fileName == CbzWriter.COMIC_INFO_ENTRY }.map { it.fileName }
        require(collision.isEmpty()) {
            "页的条目名与元数据条目 ${CbzWriter.COMIC_INFO_ENTRY} 撞名（会产出两个同名条目）：$collision"
        }
    }

    val pageCount: Int get() = pages.size
}

/** 计划里的一个图片条目:[itemId] 用于向 [PageByteSource] 取字节,[fileName] 是归档内文件名。 */
data class CbzPageEntry(val itemId: String, val fileName: String)

/**
 * 图片字节的来源。
 *
 * 领域层不知道图片存在哪(SAF / 直接路径 / 内存),只知道"给定条目 id 能不能拿到字节"。
 * 返回 `null` 表示**取不到**(文件被移走、授权失效、读取失败)—— 这是**正常业务分支**,
 * 不是异常:平台层若用异常表达它,领域层就被迫用 try/catch 做控制流。
 *
 * 实现必须**无副作用**且对同一 id 可重复调用:领域层可能在别处再次询问同一页。
 */
fun interface PageByteSource {
    fun bytesOf(itemId: String): ByteArray?

    /**
     * 按**流**打开某一页;打不开返回 `null`(与 [bytesOf] 同一业务分支语义)。
     *
     * ## 为什么除了 [bytesOf] 还要这个方法
     *
     * 真机实测(`/storage/emulated/0/test/cancel`,4 卷 × 4 张 100MB 页):旧实现走
     * 平台层 `openInputStream(...).readBytes()` 把整页读进 `ByteArray`,在 256MB 堆上
     * 直接抛 `OutOfMemoryError: Failed to allocate a 104857616 byte allocation`,
     * 抛出点是 `SafPackPlatform.kt:299` 的 `readBytes`,进程被系统杀掉,
     * **并在输出目录留下 0 字节 CBZ**。
     *
     * `ZipEntry.STORED` 确实要求写入前已知 size 与 CRC(见 `CbzWriter.stored`),
     * 这条硬约束绕不过。但「先知道 size/CRC」**不等于「整页必须留在内存」** ——
     * 可以第一遍流式读过算 CRC 与长度(边读过丢),第二遍再流式写出,
     * 峰值内存从「页大小 + 一份」降到「一个缓冲区」。
     *
     * 默认实现退化成「取字节再包成流」,所以**内存实现与既有测试都不用改**;
     * 平台层应当覆写它,否则大图仍会 OOM。
     *
     * 实现必须能对同一 itemId **重复打开**(STORED 天然要读两遍),且无副作用。
     */
    fun openFor(itemId: String): InputStream? = bytesOf(itemId)?.inputStream()
}

/**
 * 用「开流」能力构造一个 [PageByteSource]。
 *
 * 存在的理由:[PageByteSource] 是 `fun interface`,SAM 构造只能填 [PageByteSource.bytesOf],
 * 想**只**提供流式能力时语法上办不到。平台层两个方法都覆写即可(见 `SafPackBytes`);
 * 测试夹具用这个工厂更省事。
 */
fun streamingPageByteSource(open: (String) -> InputStream?): PageByteSource =
    object : PageByteSource {
        override fun bytesOf(itemId: String): ByteArray? =
            open(itemId)?.use { it.readBytes() }

        override fun openFor(itemId: String): InputStream? = open(itemId)
    }

/**
 * 一次写入的结果。
 *
 * @param writtenEntries 归档里**实际**写出的条目名(含 `ComicInfo.xml`);全有或全无,
 *                       故要么是完整列表,要么为空
 * @param missing        取不到字节的页(**保持计划顺序**),供平台层提示"哪几页读不到"
 */
data class CbzWriteResult(
    val writtenEntries: List<String>,
    val missing: List<CbzPageEntry>,
) {
    val ok: Boolean get() = missing.isEmpty()
}

/**
 * 从一卷算出写入计划 —— **唯一**产出 XML 与条目名的地方。
 *
 * 复用既有两个求值入口,不重新实现规则:
 * - [ComicInfo.build] 产出 XML(字段顺序 2 空格缩进等规格细节都在那里);
 * - [renumberPages] 产出归档内文件名(`padding = if (size >= 100) 4 else 3` 等规则都在那里)。
 *
 * 页序取 `volume.pageOrder.order`(**含人工拖拽调整后的最终顺序**),而不是 `volume.pages`。
 */
fun buildCbzWritePlan(volume: ComicVolume): CbzWritePlan = CbzWritePlan(
    volumeId = volume.id,
    comicInfoXml = ComicInfo.build(volume),
    pages = renumberPages(volume).map { CbzPageEntry(itemId = it.item.id, fileName = it.fileName) },
)

/**
 * 把计划写成 CBZ 字节。
 *
 * ## 全有或全无
 *
 * **任何一页取不到字节 ⇒ 一个字节都不写**,缺哪几页由 [CbzWriteResult.missing] 报回。
 *
 * 这样做的理由是参考脚本 `batch_pack_cbz.py` 的行为:它遇到读不到的文件会抛 `IOError`
 * 终止,**绝不**产出少页的 CBZ。这里保留同一个结果语义(不产出残缺归档),但把"失败"
 * 变成**可判定的返回值**:领域层不许有副作用,也不该用异常表达"用户还得再看一眼"这种
 * 正常的业务分支。
 *
 * 若允许"能读到几页就写几页",调用方会拿到一个**看起来完全正常**的 CBZ ——
 * 能打开、ComicInfo 也在,只是页数少了,而且没有任何信号。
 *
 * ## 探测顺序
 *
 * 所有页**先全部探测、再决定写不写**,且探测顺序 = 计划顺序。这既让 [CbzWriteResult.missing]
 * 的顺序可预期,也避免"边写边取"导致的半成品(第一条已落盘、第二条才发现缺图)。
 */
fun writeCbz(
    plan: CbzWritePlan,
    source: PageByteSource,
    out: OutputStream,
    timeMillis: Long,
    onPageWritten: (Int, Int) -> Unit = { _, _ -> },
): CbzWriteResult {
    // 参数校验前置:失败原因不该取决于"恰好缺不缺图"。CbzWriter.write 里也有同一断言,
    // 但缺图路径不会走到那里 —— 若只靠下游校验,`timeMillis = 0` + 缺图会静默返回
    // 「缺图」而不是指出时间戳有问题。
    require(timeMillis > 0) { "timeMillis 必须为正：$timeMillis（时间戳需由调用方显式给定以保证可复现）" }

    // 探测顺序:先确认每一页都**能打开**,缺任何一页立即返回(一个字节都不写)。
    //
    // 这里**不再**把字节取成 ByteArray —— 那正是 OOM 的来源。探测只需要"能不能打开",
    // 打开后立刻关掉:留着不关会泄漏文件描述符(一次打包几百页就能耗尽 fd)。
    val missing = plan.pages.filter { page ->
        val stream = source.openFor(page.itemId) ?: return@filter true
        runCatching { stream.close() }
        false
    }
    if (missing.isNotEmpty()) {
        return CbzWriteResult(writtenEntries = emptyList(), missing = missing)
    }

    // 流式写入:每页两遍 —— 第一遍算 size/CRC(边读过丢),第二遍写出去。
    // 峰值内存 = 一个缓冲区,与页大小无关。
    CbzWriter.writeStreaming(
        comicInfoXml = plan.comicInfoXml,
        pages = plan.pages,
        source = source,
        out = out,
        timeMillis = timeMillis,
        onPageWritten = onPageWritten,
    )
    return CbzWriteResult(
        writtenEntries = listOf(CbzWriter.COMIC_INFO_ENTRY) + plan.pages.map { it.fileName },
        missing = emptyList(),
    )
}
