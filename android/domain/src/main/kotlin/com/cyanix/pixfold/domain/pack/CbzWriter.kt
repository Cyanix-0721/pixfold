package com.cyanix.pixfold.domain.pack

import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * CBZ 字节生成(D3 退出条件 1 的「CBZ 生成」落地)。
 *
 * ## 这一层归谁
 *
 * HANGOFF §9 的归属澄清把「打包**领域核心**」判给 D3、「打包**平台能力**」
 * (Zip64 / 中断恢复 / 空间预检)判给 D2b、「真实文件端到端」判给 D4。
 * 于是本文件的职责边界是:
 *
 *  - **归这里**:条目顺序、是否压缩、条目名、时间戳、字节内容 —— 全部是
 *    "成品长什么样"的**纯函数**,给定输入即唯一输出,不需要文件系统也不需要 Android;
 *  - **不归这里**:字节**写到哪**(输出流、SAF、权限、剩余空间)、以及需要实际文件
 *    或真实设备才能判定的东西(Zip64 阈值、中断后能否续写)。
 *
 * 所以 [write] 收的是 [OutputStream] 而不是路径 —— 平台层决定落到 SAF 还是本地文件,
 * 领域层只管把正确的字节交出去。这也是 D3 退出条件 2「domain 零 Android 依赖」在
 * 本文件上的具体体现:整个文件只 import `java.io` / `java.util.zip`。
 *
 * ## 与参考脚本的兼容性(逐条对齐,不是"大致一样")
 *
 * `docs/notes/scripts-behavior-matrix.md` §6.2 记录的既有脚本行为:
 *
 *  - `zipfile.ZIP_STORED`,即**无压缩** —— 图片本身已压缩,再 deflate 既慢又无收益,
 *    且 docstring 注明"漫画阅读器兼容性最佳";
 *  - `zf.writestr("ComicInfo.xml", xml)` 是**第一个条目** —— 阅读器常在不解压整包的情况下
 *    只读第一个条目取元数据,顺序因此是**兼容性契约**而不是风格偏好。
 *
 * 两条都在 [write] 里被强制,并有回读单测钉死。
 */
object CbzWriter {

    /** 元数据条目的固定名(ComicInfo 标准约定,大小写敏感)。 */
    const val COMIC_INFO_ENTRY: String = "ComicInfo.xml"

    /**
     * 把一卷的内容写成 CBZ 字节。
     *
     * 条目顺序**恒为** `ComicInfo.xml` 在前、[pages] 按**给定顺序**在后。
     * 这里**不排序也去重** —— 页序(含人工调整)是计划层的结论,写入层再排一次序,
     * 计划页与实际成品就会不一致。
     *
     * @param comicInfoXml 已生成的 ComicInfo.xml 文本(见 `ComicInfo.build`),按 UTF-8 写入
     * @param pages        图片条目,**顺序即最终页序**
     * @param out          输出流;**本函数不关闭它**(所有权归调用方,便于写入 SAF/内存/文件)
     * @param timeMillis   条目时间戳。**必须显式传入**而非读系统时钟:
     *                     同一个输入两次运行必须产出完全相同的字节,否则"回读 = 原样"
     *                     这条断言无法稳定成立,跨 JDK 也不可比。
     * @throws IllegalArgumentException 当 [timeMillis] 非正(见下)
     */
    fun write(
        comicInfoXml: String,
        pages: List<CbzEntry>,
        out: OutputStream,
        timeMillis: Long,
    ) {
        // 为什么拒绝非正时间戳:ZipEntry.setTime 对负值会走 DOS 时间回退路径,
        // 静默产出 1970 之前的怪时间(甚至 2097 的环绕结果)。与其让一个"看起来能跑"
        // 的坏值流进成品,不如在入口挡住 —— 时间戳本来就是调用方给的确定性输入。
        require(timeMillis > 0) { "timeMillis 必须为正：$timeMillis（时间戳需由调用方显式给定以保证可复现）" }

        val zip = ZipOutputStream(out)
        // 全部 STORED:与参考脚本一致(见文件头)。STORED 不允许流式写入,
        // 必须预先给出 size 与 crc —— 这恰好强制"内容已在手里",与领域层无副作用相合。
        zip.setLevel(0)
        zip.putNextEntry(stored(COMIC_INFO_ENTRY, comicInfoXml.toByteArray(Charsets.UTF_8), timeMillis))
        zip.write(comicInfoXml.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        pages.forEach { page ->
            zip.putNextEntry(stored(page.name, page.bytes, timeMillis))
            zip.write(page.bytes)
            zip.closeEntry()
        }

        // finish() 而非 close():写出中央目录,但**不关闭**调用方的流。
        zip.finish()
    }

    /**
     * 造一个 STORED 条目。
     *
     * STORED 要求写入前 size / compressedSize / crc 三者都已确定,
     * 否则 `ZipOutputStream` 会抛 `ZipException: STORED entry missing size, compressed size, or crc-32`。
     * 这里集中算一次,避免每个调用点各忘一个。
     */
    private fun stored(name: String, bytes: ByteArray, timeMillis: Long): ZipEntry {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        entry.time = timeMillis
        return entry
    }
}

/**
 * 一个待写入的图片条目。
 *
 * [name] 是**归档内文件名**(如 `001.jpg`),由计划层用 `renumberPages` 算好后传入;
 * 写入层不碰命名规则 —— 补零位数、"扩展名强制小写"这类规则改起来会牵动
 * 计划页与 ComicInfo 的一致性,只应有一处实现。
 */
data class CbzEntry(val name: String, val bytes: ByteArray) {
    init {
        // 空名条目在 ZIP 里合法但无意义,且回读时与"中央目录项"难以区分;
        // 真正会出问题的是**看起来像目录**的名字(以 / 结尾),那会被阅读器当成目录项。
        require(name.isNotEmpty()) { "条目名不能为空" }
        require(!name.endsWith("/")) { "条目名不能以 / 结尾（会被当成目录项）：$name" }
    }

    // ByteArray 的 equals 是引用比较,data class 生成的实现会让"内容相同"的两个条目不等,
    // 于是测试与集合去重都会静默出错。这里按内容比较。
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is CbzEntry && name == other.name && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "CbzEntry(name='$name', bytes=${bytes.size}B)"
}
