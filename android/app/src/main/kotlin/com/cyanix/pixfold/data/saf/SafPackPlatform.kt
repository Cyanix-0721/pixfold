package com.cyanix.pixfold.data.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.net.toUri
import com.cyanix.pixfold.domain.comic.ComicInfoData
import com.cyanix.pixfold.domain.comic.ExistingComicInfoProbe
import com.cyanix.pixfold.domain.comic.OutputExistsProbe
import com.cyanix.pixfold.domain.comic.PageBytes
import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.comic.comicInfoOfEntry
import com.cyanix.pixfold.domain.pack.CbzOutputSink
import com.cyanix.pixfold.domain.plan.VolumePackPlan
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * 打包输出的 SAF 实现 —— **这是让「打包」真正写盘的那一层**。
 *
 * 在此之前 `CbzOutputSink` 只有领域层的 `fun interface` 声明、没有任何实现,
 * 所以打包点下去走的是 `executePackagePlans` 的确定性模拟:报告里一条条"成功",
 * 磁盘上一个字节都没有。
 *
 * ## 三个必须守住的契约(来自 `CbzOutputSink` 的 KDoc)
 *
 * 1. `openFor` 返回 `null` 表示**正常业务分支**打不开(目录被删、无权限)。
 *    调用方据此记 `Failed` 并**不会**调用 [discardFor] —— 所以本类在拿不到父目录 URI
 *    等"还没开始写"的情况下返回 null,而不是自己抛异常。
 * 2. [discardFor] 只会在「流已打开、但写入失败」时被调用,它在失败路径上:
 *    **必须幂等、必须不抛**。再炸一次会盖掉原始错误,用户看到的就是一句无关的异常。
 * 3. 已存在的同名文件:领域层用 `outputExists` 判断覆盖语义,本类**只管写入**;
 *    但 `createDocument` 在目标已存在时会自动改名(变成 `xxx (1).cbz`),这与
 *    `OutputConflictPolicy.Overwrite` 的语义冲突 ⇒ 覆盖场景走「先查到已有 document 再
 *    `openInputStream` 同款 URI 打开输出」,见 [openFor]。
 *
 * ## 为什么输出目录要单独解析
 *
 * `VolumePackPlan.outputPath` 是给人看/给测试比的字符串路径(`E:\漫画库\...`),
 * **不是** SAF URI。真实写入必须用 `DocumentsContract` 的 document URI,而
 * `createDocument` 的 parent **必须是 document URI** —— 传 tree URI 在国产 ROM 上会
 * 抛 `IllegalArgumentException: Invalid URI`(归档分支 spike 踩过,见 §12.5)。
 */
class SafCbzOutputSink(
    context: Context,
    private val treeUri: Uri,
    /** 卷 id → 输出目录相对路径(如 `系列A`),用于在该目录下创建文件。 */
    private val dirOfVolume: (VolumePackPlan) -> String,
) : CbzOutputSink {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun openFor(plan: VolumePackPlan): OutputStream? {
        val dirRel = dirOfVolume(plan)
        val parentUri = parentDocumentUri(dirRel) ?: run {
            Log.w(TAG, "找不到输出目录: $dirRel")
            return null
        }
        val displayName = plan.outputDisplay

        // 覆盖语义:先找同名文件,存在就原地重写(否则 createDocument 会改名成 xxx (1).cbz)
        val existing = findChild(parentUri, displayName)
        if (existing != null) {
            lastOpenedUri = existing
            return try {
                resolver.openOutputStream(existing, "wt")
            } catch (e: Exception) {
                Log.w(TAG, "覆盖写入失败: $displayName", e)
                null
            }
        }

        return try {
            val created = DocumentsContract.createDocument(
                resolver,
                parentUri,
                MIME_CBZ,
                displayName,
            )
            if (created == null) {
                Log.w(TAG, "createDocument 返回 null: $displayName")
                return null
            }
            lastOpenedUri = created
            resolver.openOutputStream(created, "w")
        } catch (e: Exception) {
            // 打不开 = 正常业务分支,清理可能被创建出来的空文档,然后把 null 交给调用方
            Log.w(TAG, "创建输出文件失败: $displayName", e)
            cleanupLastOpened()
            null
        }
    }

    override fun discardFor(plan: VolumePackPlan) {
        // 契约:幂等、不抛。调用方已经在处理写入失败的原始异常,
        // 这里再抛会把根因盖掉。
        cleanupLastOpened()
    }

    // ---- 内部 ----

    /** 最近一次 `openFor` 打开的目标(用于失败清理)。 */
    private var lastOpenedUri: Uri? = null

    private fun cleanupLastOpened() {
        val uri = lastOpenedUri ?: return
        lastOpenedUri = null
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            // 删不掉(可能介质已移除 / 已被用户删)不算错误 —— discardFor 只需要尽力。
            Log.w(TAG, "清理半成品失败: $uri", e)
        }
    }

    /**
     * 把「相对工作区根的目录路径」解析成 document URI。
     *
     * 注意 `createDocument` 只接受**文档** URI,不接受 tree URI。
     */
    private fun parentDocumentUri(dirRel: String): Uri? =
        safParentDocumentUri(resolver, treeUri, dirRel)

    /** 在 parent 下找同名子项;优先按 display name 精确匹配。 */
    private fun findChild(parentUri: Uri, displayName: String): Uri? =
        safFindChild(resolver, treeUri, parentUri, displayName)

    private companion object {
        const val TAG = "SafCbzOutputSink"

        /** CBZ 的 MIME。部分 provider 不认 zip 专用类型,退回通用二进制更稳。 */
        const val MIME_CBZ = "application/vnd.comicbook+zip"
    }
}

/**
 * 「该卷的输出文件是否已存在」的 SAF 实现。
 *
 * `VolumePackPlan.overwritesExisting` 的正确性完全依赖这个判断:
 * 漏判 ⇒ 界面不警告覆盖,撤销时按「新建」删掉用户原有文件(缺陷 R3)。
 */
class SafOutputExistsProbe(
    context: Context,
    private val treeUri: Uri,
    private val dirOfVolume: (ComicVolume) -> String,
) : OutputExistsProbe {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun exists(volume: ComicVolume): Boolean {
        val parentUri = parentDocumentUri(dirOfVolume(volume)) ?: return false
        val parentDocId = try {
            DocumentsContract.getDocumentId(parentUri)
        } catch (e: Exception) {
            return false
        }
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val mime = cursor.getString(1) ?: ""
                    if (name == volume.cbzFileName && mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        return true
                    }
                }
                false
            } ?: false
        } catch (e: Exception) {
            // 查不到就当不存在?不行 —— 判成不存在会让覆盖变成新建,后果更糟。
            // 但领域层的 outputExists 只有布尔值,无法表达「不确定」;此处选择
            // 保守=false 并在界面侧由 preflight 的「估算下界」警告兜住风险。
            Log.w(TAG, "查询输出是否已存在失败,按不存在处理", e)
            false
        }
    }

    private fun parentDocumentUri(dirRel: String): Uri? =
        safParentDocumentUri(resolver, treeUri, dirRel)

    private companion object {
        const val TAG = "SafOutputExistsProbe"
    }
}

/**
 * 按 documentUri 读页字节 —— `PageBytes` 的 SAF 实现。
 *
 * 打包内核要求「先探测所有页能否取到」,取不到就整卷不下手(不留 0 字节 CBZ)。
 * 探测走的就是这个函数,所以它必须**快且不抛**(抛异常路径拿不到字节,和返回
 * null 的效果一样,但会把异常类型暴露给使用者)。
 *
 * ## 为什么 `bytesOf` 不再是打包路径
 *
 * 本类同时提供 [openStream]，打包内核会优先用它（见 `streamingPageByteSource`）。
 * `bytesOf` 只在**没有流能力**的调用方（如小图预览、测试夹具）用得上。
 *
 * 真机实证：4 卷 × 4 张 100MB 页在 256MB 堆上，`bytesOf` 里那行
 * `resolver.openInputStream(uri)?.use { it.readBytes() }` 直接抛
 * `OutOfMemoryError: Failed to allocate a 104857616 byte allocation`，进程被杀，
 * 且输出目录留下 0 字节 CBZ。`readBytes()` 的语义就是「整块读进一个 ByteArray」，
 * 页有多大就申请多大，这在手机堆上没有任何余量。
 */
class SafPageBytes(context: Context) : PageBytes {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun bytesOf(documentUri: String): ByteArray? = try {
        resolver.openInputStream(documentUri.toUri())?.use { it.readBytes() }
    } catch (e: Exception) {
        Log.w(TAG, "读取页字节失败: $documentUri", e)
        null
    } catch (e: OutOfMemoryError) {
        // OOM 是 Error 不是 Exception，不会被上面的 catch 接住 —— 而它恰恰是这个
        // 方法最可能撞上的错误（大图）。转成 null 让调用方走「读不到」分支，
        // 而不是把整个进程拖死。
        Log.w(TAG, "读取页字节时内存不足: $documentUri", e)
        null
    }

    /**
     * 打开页的输入流，**不预读**。
     *
     * 返回的流由调用方负责关闭（打包内核用 `use` 包住）。这里刻意不做任何缓冲：
     * 调用方会按 64 KiB 分块读两遍（算 CRC / 写出），峰值内存与页大小无关。
     */
    fun openStream(documentUri: String): InputStream? = try {
        resolver.openInputStream(documentUri.toUri())
    } catch (e: Exception) {
        Log.w(TAG, "打开页流失败: $documentUri", e)
        null
    }

    private companion object {
        const val TAG = "SafPageBytes"
    }
}

/**
 * 可用空间查询 —— 存储空间预检的平台输入。
 *
 * 为什么用 `StatFs` 而不是 SAF 的 `DocumentsContract` 能力:Android 的
 * `DocumentsProvider` **没有**暴露「剩余空间」的标准列(第三方 provider 更不可能有),
 * 而 SAF 树最终落在某个真实卷上。取方法:
 *
 * 1. 主存储(`/storage/emulated/0` 等)⇒ 直接 `StatFs(Environment.getDataDirectory())`,
 *    这是最可靠的口径;
 * 2. 外置 SD / U 盘 ⇒ 从 tree URI 的 authority 与 documentId 反推路径往往不可行,
 *    此时返回 `null`,让调用方**不做空间判定**而不是误判成"空间充足"。
 *
 * **返回 null 的语义是「不知道」,不是「0」**:`PackPreflight` 拿到
 * `availableBytes` 必须是真实数字,所以在 null 时要由调用方决定降级方式
 * (本项目的选择 = 跳过空间阻断,仅给「估算下界」警告,见打包页的实现)。
 */
class FilesystemSpaceSource(context: Context) {

    private val appContext = context.applicationContext

    /** 该 tree URI 所在卷的可用字节数;无法判定返回 null。 */
    fun availableBytesOf(treeUri: Uri): Long? {
        // 目前只对「主存储」给出确定答案:externalstorage provider 的 tree 根
        // 前缀 primary: 就是主存储。其他 authority(第三方网盘/SAF 桥)无从得知。
        val authority = treeUri.authority ?: return null
        if (authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (e: Exception) {
            return null
        }
        // 只认 primary:;SD 卡是 1234-5678: 这类卷 id,路径不可靠,返回 null
        if (!docId.startsWith(PRIMARY_PREFIX)) return null
        return availableInternalBytes()
    }

    /** 内部存储(含主存储)可用字节。 */
    fun availableInternalBytes(): Long? = try {
        val stat = StatFs(appContext.filesDir.absolutePath)
        stat.availableBytes
    } catch (e: Exception) {
        Log.w(TAG, "StatFs 失败", e)
        null
    }

    private companion object {
        const val TAG = "FilesystemSpaceSource"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val PRIMARY_PREFIX = "primary:"
    }
}

/**
 * 读「该卷已有 CBZ 里的 `ComicInfo.xml`」—— [ExistingComicInfoProbe] 的 SAF 实现。
 *
 * 存在的意义:用户可能已经打包过一次、手工改过元数据,再次打包时界面应当以**文件里的
 * 值**为当前值(用户 2026-10-20 裁决),而不是每次都从目录名重推。
 *
 * ## 契约
 *
 * - 没有同名 CBZ ⇒ `null`(调用方保持建议值);
 * - CBZ 在但里面没有 `ComicInfo.xml` ⇒ `ComicInfoData()` 空对象(调用方清空当前值);
 * - 包坏了 / 读不出来 / 条目在但解析失败 ⇒ `null`(调用方**什么都不改**，保持建议值)。
 *
 * 最后一条是 2026-10-27 真机缺陷的修复点：原先「解析失败」被兜底成空对象，于是每个
 * 有元数据的包都被当成空包，用户点一次更新就把文件里的 XML 写成全空标签。
 *
 * ## 为什么只读 `ComicInfo.xml` 一个条目
 *
 * 一个 2 GB 的 CBZ 有几百个条目,完整遍历会把主线程拖死。`ZipInputStream` 是顺序读的,
 * 但我们**自己写出的**包把 ComicInfo 放在物理首位,所以正常情况下第一个条目就是它;
 * 万一不是(第三方工具重排过),最多扫到第一个图片条目为止 —— 见 [MAX_ENTRIES_SCANNED]。
 */
class SafExistingComicInfoProbe(
    context: Context,
    private val treeUri: Uri,
    private val dirOfVolume: (ComicVolume) -> String,
) : ExistingComicInfoProbe {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    override fun read(volume: ComicVolume): ComicInfoData? {
        val parentUri = parentDocumentUri(dirOfVolume(volume)) ?: return null
        val parentDocId = runCatching { DocumentsContract.getDocumentId(parentUri) }.getOrNull()
            ?: return null
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cbzUri = try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                var found: Uri? = null
                while (cursor.moveToNext() && found == null) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val mime = cursor.getString(2) ?: ""
                    if (name == volume.cbzFileName && mime != DocumentsContract.Document.MIME_TYPE_DIR) {
                        found = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }
                }
                found
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询已有 CBZ 失败: ${volume.cbzFileName}", e)
            null
        }
        return cbzUri?.let { readComicInfoFrom(it) }
    }

    private fun readComicInfoFrom(cbzUri: Uri): ComicInfoData? = try {
        resolver.openInputStream(cbzUri)?.use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zip ->
                var scanned = 0
                var xml: String? = null
                while (scanned < MAX_ENTRIES_SCANNED) {
                    val entry = zip.nextEntry ?: break
                    scanned++
                    val name = entry.name.substringAfterLast('/')
                    if (name.equals(COMIC_INFO_ENTRY, ignoreCase = true)) {
                        xml = zip.readBytes().toString(Charsets.UTF_8)
                        break
                    }
                }
                // 三态交给领域层：没有条目 ⇒ 空对象(清空当前值)；条目在但解析不出 ⇒ null
                // (保持建议值)。见 `comicInfoOfEntry` 的 KDoc —— 把两者混成一个空对象
                // 会让「读不懂」变成「把用户的元数据清空」。
                comicInfoOfEntry(xml)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "读取已有 CBZ 失败,按没有处理: $cbzUri", e)
        null
    }

    private fun parentDocumentUri(dirRel: String): Uri? {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return null
        if (dirRel.isEmpty()) return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        var currentDocId = rootDocId
        for (segment in dirRel.split('/')) {
            if (segment.isEmpty()) continue
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
            currentDocId = queryDirId(childrenUri, segment) ?: return null
        }
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
    }

    private fun queryDirId(childrenUri: Uri, displayName: String): String? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    val mime = cursor.getString(2) ?: ""
                    if (name == displayName && mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        return cursor.getString(0)
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "SafExistingComicInfo"
        const val COMIC_INFO_ENTRY = "ComicInfo.xml"
        /** 最多扫这么多个条目找 ComicInfo —— 防第三方包把它埋在最末。 */
        const val MAX_ENTRIES_SCANNED = 16
    }
}

// ---------------------------------------------------------------------------
// SAF 树查询的公共实现
// ---------------------------------------------------------------------------

/**
 * 把「相对工作区根的目录路径」解析成 **document URI**。
 *
 * ## 为什么抽成文件级函数
 *
 * 同一个逻辑原本在 `SafCbzOutputSink` 与 `SafOutputExistsProbe` 各有一份（逐字重复），
 * 工作流 C 的 `SafMetaUpdater` 还需要第三份。三份重复的 SAF 下钻代码里只要有一份写错
 * （比如漏了 `wantDirectory = true`），表现都是「某个系列的包找不到」——
 * 这类缺陷在真机上极难定位，因为它只在**有子目录**的工作区里出现。
 *
 * 抽出来后每个调用点只剩一行，并且同一条下钻规则只有一处实现。
 *
 * @param dirRel 相对工作区根的目录路径，空串 = 根目录本身
 * @return 该目录的 document URI；路径不存在 / 非目录 / 查询抛异常 ⇒ null
 */
internal fun safParentDocumentUri(resolver: ContentResolver, treeUri: Uri, dirRel: String): Uri? {
    val rootDocId = try {
        DocumentsContract.getTreeDocumentId(treeUri)
    } catch (e: Exception) {
        Log.w(SAF_TAG, "tree URI 无法取根 document id", e)
        return null
    }
    if (dirRel.isEmpty()) {
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
    }
    // 逐级下钻：每级用 buildChildDocumentsUriUsingTree 查子目录的 documentId
    var currentDocId = rootDocId
    for (segment in dirRel.split('/')) {
        if (segment.isEmpty()) continue
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, currentDocId)
        currentDocId = safQueryChildDocId(resolver, childrenUri, segment, wantDirectory = true) ?: return null
    }
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, currentDocId)
}

/** 在 [parentUri] 下找同名子项，返回它的 document URI。找不到 ⇒ null。 */
internal fun safFindChild(
    resolver: ContentResolver,
    treeUri: Uri,
    parentUri: Uri,
    displayName: String,
): Uri? {
    val parentDocId = try {
        DocumentsContract.getDocumentId(parentUri)
    } catch (e: Exception) {
        return null
    }
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    return safQueryChildUri(resolver, treeUri, childrenUri, displayName, wantDirectory = false)
}

/** 在 children 列表里按名字 + 是否目录筛出子项的 documentId。 */
internal fun safQueryChildDocId(
    resolver: ContentResolver,
    childrenUri: Uri,
    displayName: String,
    wantDirectory: Boolean,
): String? = safQueryChildren(resolver, childrenUri) { docId, name, mime ->
    if (name != displayName) return@safQueryChildren null
    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
    if (isDir != wantDirectory) return@safQueryChildren null
    docId
}

/** 同上，但直接拼成 document URI。 */
internal fun safQueryChildUri(
    resolver: ContentResolver,
    treeUri: Uri,
    childrenUri: Uri,
    displayName: String,
    wantDirectory: Boolean,
): Uri? {
    val docId = safQueryChildDocId(resolver, childrenUri, displayName, wantDirectory) ?: return null
    return DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
}

/** SAF 子项列表的唯一遍历实现。**查询失败一律返回 null，不抛**（扫描路径不得因一处坏树中断）。 */
private inline fun <T> safQueryChildren(
    resolver: ContentResolver,
    childrenUri: Uri,
    onRow: (docId: String, displayName: String, mimeType: String) -> T?,
): T? {
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
    )
    return try {
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2) ?: ""
                onRow(docId, name, mime)?.let { return it }
            }
            null
        }
    } catch (e: Exception) {
        Log.w(SAF_TAG, "查询子项失败", e)
        null
    }
}

private const val SAF_TAG = "SafTree"

