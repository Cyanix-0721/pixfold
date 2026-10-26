package com.cyanix.pixfold.domain.pack

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 把一卷已有 CBZ 里的 `ComicInfo.xml` 换掉，其余条目原样复制。
 *
 * ## 为什么是「整包重写」而不是「就地改一个条目」
 *
 * ZIP 的中央目录记录了每个条目的**偏移、大小、CRC**。替换一个条目会改变其后所有条目的
 * 偏移，因此「只改一个条目」在 ZIP 格式里不存在 —— 要么整包重写，要么尾部追加同名条目
 * （ZIP 对重名的行为未定义，阅读器取哪个不确定）。规格 §2.1 有完整对比表。
 *
 * 代价是明确的：4.73 GiB 的包只改一行元数据，也要读写 4.73 GiB。这是格式决定的，
 * 不是实现偷懒。
 *
 * ## 三条兼容性契约（与 [CbzWriter] 同源）
 *
 *  1. `ComicInfo.xml` **恒为第 0 条** —— 阅读器常只读第一条取元数据
 *     （`CbzReaderCompatTest.kt:103` 钉着这条）；
 *  2. 所有条目 **STORED**（`CbzReaderCompatTest.kt:150`）—— 图片本身已压缩，
 *     再 deflate 既慢又无收益；
 *  3. 页条目名**原样保留**，不重新编号（裁决 D3）。转写器根本不解析文件名，
 *     所以这条是结构性保证，不是"记得别改"。
 *
 * ## 流的所有权
 *
 * **不关闭** [input]，**不关闭** [out]（与 [CbzWriter] 一致：输出流归调用方）。
 * 注意 `ZipInputStream.close()` 会连带关闭底层流，所以这里刻意不 `use` 它。
 *
 * ## 内存
 *
 * 单条目峰值 = 该条目大小（不超过 [INLINE_LIMIT_BYTES]）或一个磁盘临时文件。
 * 不会缓冲整包 —— 4.73 GiB 的包转写时堆占用是个位数 MB。
 */
object CbzMetaRewriter {

    /** 小于这个大小的条目留在内存里，超过就落 [spillDir] 的临时文件。 */
    internal const val INLINE_LIMIT_BYTES: Long = 8L * 1024 * 1024

    private const val BUFFER_BYTES = 64 * 1024

    /**
     * 把 [input] 里的 CBZ 转写成 [out]，只把 `ComicInfo.xml` 换成 [comicInfoXml]。
     *
     * @param timeMillis  条目时间戳。**必须显式给定**：取系统时钟会让同一输入产出不同字节
     *                    （与 `CbzWriter.write` 同一条理由）。**旧条目的 mtime 不保留** ——
     *                    保留它会让"同一份输入 + 同一个新 XML"产出两种字节，回读比对做不了。
     * @param spillDir    大条目与大小未知条目的落盘目录（平台层传 `cacheDir`）。
     *                    传 `null` 表示"我确认不会有需要落盘的条目"：遇到大小未知（DEFLATED）
     *                    或超过 [INLINE_LIMIT_BYTES] 的条目会抛 `IllegalStateException`，
     *                    **而不是退化成爆内存**（D2b 踩过 `OutOfMemoryError` 被杀进程）。
     * @param onEntryWritten 每写完一条回调 `(已写条目数, 总条目数)`。总条目数传 `0` =
     *                    **未知**（`ZipInputStream` 读到末尾才知道有几条），调用方据此显示不确定态进度。
     * @return 写出的条目总数（含新的 ComicInfo.xml）
     * @throws IllegalStateException 见 [spillDir]；或某条目读到一半失败
     */
    fun rewrite(
        input: InputStream,
        out: OutputStream,
        comicInfoXml: String,
        timeMillis: Long,
        spillDir: File?,
        onEntryWritten: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        require(timeMillis > 0) { "timeMillis 必须为正：$timeMillis" }

        val zip = ZipOutputStream(out)
        zip.setLevel(0)

        var written = 0
        val xmlBytes = comicInfoXml.toByteArray(Charsets.UTF_8)
        zip.putNextEntry(stored(CbzWriter.COMIC_INFO_ENTRY, xmlBytes, timeMillis))
        zip.write(xmlBytes)
        zip.closeEntry()
        written++
        onEntryWritten(written, 0)

        // ZipInputStream.close() 会关掉底层 input —— 所有权在调用方，故不用 use。
        val zin = ZipInputStream(BufferedInputStream(input))
        while (true) {
            val entry = zin.nextEntry ?: break
            if (entry.isDirectory) {
                zin.closeEntry()
                continue
            }
            if (isComicInfoEntry(entry.name)) {
                // 旧的元数据条目整条丢弃：产出包里只能有一个 ComicInfo，
                // 否则阅读器取哪个不确定（ZIP 对重名无定义行为）。
                zin.closeEntry()
                continue
            }
            copyEntry(zin, entry, zip, timeMillis, spillDir)
            written++
            onEntryWritten(written, 0)
        }

        // finish() 而非 close()：写出中央目录但不关闭调用方的流。
        zip.finish()
        zip.flush()
        return written
    }

    private fun copyEntry(
        zin: ZipInputStream,
        source: ZipEntry,
        zip: ZipOutputStream,
        timeMillis: Long,
        spillDir: File?,
    ) {
        val crc = CRC32()
        var size = 0L
        val buffer = ByteArray(BUFFER_BYTES)

        // 大小未知（DEFLATED 的 ZipInputStream 给 -1）时，落盘是唯一安全选择 ——
        // 不能先把未知大小的东西读进内存再判断，那正是 OOM 的触发方式。
        if (source.size < 0 && spillDir == null) {
            throw IllegalStateException(
                "条目大小未知（DEFLATED）但没有给 spillDir，拒绝读进内存：" + source.name,
            )
        }

        val mem = ByteArrayOutputStream()
        var spill: File? = null
        var spillOut: OutputStream? = null
        try {
            while (true) {
                val n = zin.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
                size += n
                if (spillOut == null) {
                    if (size > INLINE_LIMIT_BYTES) {
                        if (spillDir == null) {
                            throw IllegalStateException(
                                "条目超过 " + INLINE_LIMIT_BYTES + " 字节且没有给 spillDir：" + source.name,
                            )
                        }
                        val f = File.createTempFile("cbzmeta-", ".bin", spillDir)
                        spill = f
                        val fo = FileOutputStream(f)
                        mem.writeTo(fo)
                        mem.reset()
                        spillOut = fo
                        spillOut.write(buffer, 0, n)
                    } else {
                        mem.write(buffer, 0, n)
                    }
                } else {
                    spillOut.write(buffer, 0, n)
                }
            }

            val entry = stored(source.name, size, crc.value, timeMillis)
            zip.putNextEntry(entry)
            if (spill != null) {
                spillOut?.flush()
                spillOut?.close()
                spillOut = null
                FileInputStream(spill).use { it.copyTo(zip, BUFFER_BYTES) }
            } else {
                mem.writeTo(zip)
            }
            zip.closeEntry()
        } finally {
            runCatching { spillOut?.close() }
            runCatching { spill?.delete() }
        }
    }

    /** STORED 条目必须自带 size / compressedSize / crc，否则 ZipOutputStream 会抛。 */
    private fun stored(name: String, bytes: ByteArray, timeMillis: Long): ZipEntry {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        entry.time = timeMillis
        return entry
    }

    /** 同上，但 size / crc 由流式读取时算好。 */
    private fun stored(name: String, size: Long, crc: Long, timeMillis: Long): ZipEntry {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = size
        entry.compressedSize = size
        entry.crc = crc
        entry.time = timeMillis
        return entry
    }
}
