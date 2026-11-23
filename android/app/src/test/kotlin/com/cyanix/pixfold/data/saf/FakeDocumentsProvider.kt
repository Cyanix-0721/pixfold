package com.cyanix.pixfold.data.saf

import android.Manifest
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.robolectric.Robolectric
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 基于真实 JVM 临时目录的离线 [DocumentsProvider] 测试底座（M8 / 无 ADB 测试第三层）。
 *
 * 通过 `Robolectric.setupContentProvider(FakeDocumentsProvider::class.java, AUTHORITY)` 注册到
 * Robolectric 的 `ShadowContentResolver`，使生产代码 `data/saf/` 与 `CoilThumbnailSource`
 * 中的真实 `DocumentsContract.*` 与 `ContentResolver.query/openInputStream/openOutputStream`
 * 完整走通 AOSP [DocumentsProvider] 的 URI 路由、树边界校验（[isChildDocument]）与 `call()` 分发。
 */
class FakeDocumentsProvider : DocumentsProvider() {

    enum class Operation {
        QUERY_DOCUMENT,
        QUERY_CHILDREN,
        CREATE,
        RENAME,
        DELETE,
        OPEN,
    }

    private data class OpenCountdownFault(
        val remainingAllowed: AtomicInteger,
        val fault: () -> Throwable,
    )

    lateinit var rootDir: File
        private set

    var rootDocId: String = DEFAULT_ROOT_DOC_ID
        private set

    var rootDisplayName: String = "workspace"
        private set

    private val queryDocCounter = AtomicInteger(0)
    private val queryChildrenCounter = AtomicInteger(0)
    private val createCounter = AtomicInteger(0)
    private val renameCounter = AtomicInteger(0)
    private val deleteCounter = AtomicInteger(0)

    private val queryDocCountByDocId = ConcurrentHashMap<String, AtomicInteger>()
    private val queryChildCountByParentDocId = ConcurrentHashMap<String, AtomicInteger>()
    private val lastOpenModeByDocId = ConcurrentHashMap<String, String>()
    private val openHistory = CopyOnWriteArrayList<Pair<String, String>>()

    private val customMimeTypes = ConcurrentHashMap<String, String>()
    private val readOnlyDocIds = ConcurrentHashMap.newKeySet<String>()
    private val globalFaults = ConcurrentHashMap<Operation, () -> Throwable>()
    private val docFaults = ConcurrentHashMap<Pair<Operation, String>, () -> Throwable>()
    private val openCountdownFaults = ConcurrentHashMap<String, OpenCountdownFault>()

    val totalQueryDocumentCount: Int get() = queryDocCounter.get()
    val totalQueryChildDocumentsCount: Int get() = queryChildrenCounter.get()
    val createDocumentCount: Int get() = createCounter.get()
    val renameDocumentCount: Int get() = renameCounter.get()
    val deleteDocumentCount: Int get() = deleteCounter.get()

    val treeUri: Uri
        get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, rootDocId)

    override fun attachInfo(context: Context?, info: ProviderInfo?) {
        // AOSP DocumentsProvider.attachInfo 强制要求 exported = true、grantUriPermissions = true
        // 且读写权限均为 MANAGE_DOCUMENTS，否则直接抛 SecurityException。
        val patched = (info ?: ProviderInfo()).apply {
            if (authority.isNullOrEmpty()) authority = AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        super.attachInfo(context, patched)
    }

    override fun onCreate(): Boolean {
        if (!::rootDir.isInitialized) {
            rootDir = Files.createTempDirectory("pixfold-fake-saf-").toFile()
        }
        return true
    }

    fun bindRootDir(
        dir: File,
        rootDocumentId: String = DEFAULT_ROOT_DOC_ID,
        displayName: String = dir.name,
    ) {
        require(dir.exists() || dir.mkdirs()) { "无法创建或绑定根目录: $dir" }
        rootDir = dir
        rootDocId = rootDocumentId
        rootDisplayName = displayName
        resetCounters()
        clearFaults()
        customMimeTypes.clear()
        readOnlyDocIds.clear()
    }

    fun resetCounters() {
        queryDocCounter.set(0)
        queryChildrenCounter.set(0)
        createCounter.set(0)
        renameCounter.set(0)
        deleteCounter.set(0)
        queryDocCountByDocId.clear()
        queryChildCountByParentDocId.clear()
        lastOpenModeByDocId.clear()
        openHistory.clear()
    }

    fun clearFaults() {
        globalFaults.clear()
        docFaults.clear()
        openCountdownFaults.clear()
    }

    fun injectFault(
        operation: Operation,
        targetDocId: String? = null,
        throwable: () -> Throwable,
    ) {
        if (targetDocId == null) {
            globalFaults[operation] = throwable
        } else {
            docFaults[operation to targetDocId] = throwable
        }
    }

    fun injectFaultForRelPath(
        operation: Operation,
        relPath: String,
        throwable: () -> Throwable,
    ) {
        injectFault(operation, docIdForRelPath(relPath), throwable)
    }

    fun failOpenAfter(
        relPath: String,
        allowCount: Int,
        throwable: () -> Throwable = { FileNotFoundException("注入中途打开失败: $relPath") },
    ) {
        openCountdownFaults[docIdForRelPath(relPath)] = OpenCountdownFault(
            remainingAllowed = AtomicInteger(allowCount),
            fault = throwable,
        )
    }

    fun markReadOnly(relPath: String, readOnly: Boolean = true) {
        val docId = docIdForRelPath(relPath)
        if (readOnly) {
            readOnlyDocIds.add(docId)
        } else {
            readOnlyDocIds.remove(docId)
        }
    }

    fun queryChildCountFor(parentDocId: String): Int =
        queryChildCountByParentDocId[parentDocId]?.get() ?: 0

    fun queryChildCountForRelPath(relPath: String): Int =
        queryChildCountFor(docIdForRelPath(relPath))

    fun lastOpenModeForRelPath(relPath: String): String? =
        lastOpenModeByDocId[docIdForRelPath(relPath)]

    fun openModesForRelPath(relPath: String): List<String> {
        val targetId = docIdForRelPath(relPath)
        return openHistory.filter { it.first == targetId }.map { it.second }
    }

    fun docIdForRelPath(relPath: String): String {
        val cleaned = relPath.replace('\\', '/').trim('/')
        return if (cleaned.isEmpty()) rootDocId else "$rootDocId/$cleaned"
    }

    fun documentUriFor(relPath: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, docIdForRelPath(relPath))

    fun fileAt(relPath: String): File {
        val cleaned = relPath.replace('\\', '/').trim('/')
        return if (cleaned.isEmpty()) rootDir else File(rootDir, cleaned)
    }

    fun mkdirs(relPath: String): File = fileAt(relPath).apply { mkdirs() }

    fun writeFile(
        relPath: String,
        bytes: ByteArray,
        lastModifiedMillis: Long? = null,
    ): File {
        val file = fileAt(relPath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        if (lastModifiedMillis != null) {
            file.setLastModified(lastModifiedMillis)
        }
        return file
    }

    fun writeText(
        relPath: String,
        text: String,
        lastModifiedMillis: Long? = null,
    ): File = writeFile(relPath, text.toByteArray(Charsets.UTF_8), lastModifiedMillis)

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cols = projection ?: DEFAULT_ROOT_PROJECTION
        val cursor = MatrixCursor(cols)
        val row = cursor.newRow()
        for (col in cols) {
            when (col) {
                Root.COLUMN_ROOT_ID -> row.add("primary")
                Root.COLUMN_DOCUMENT_ID -> row.add(rootDocId)
                Root.COLUMN_TITLE -> row.add(rootDisplayName)
                Root.COLUMN_FLAGS -> row.add(Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        queryDocCounter.incrementAndGet()
        queryDocCountByDocId.computeIfAbsent(documentId) { AtomicInteger(0) }.incrementAndGet()
        throwIfFaultInjected(Operation.QUERY_DOCUMENT, documentId)

        val file = fileFor(documentId)
        if (!file.exists()) {
            throw FileNotFoundException("Document does not exist: $documentId")
        }
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        includeFile(cursor, cols, documentId, file)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        queryChildrenCounter.incrementAndGet()
        queryChildCountByParentDocId
            .computeIfAbsent(parentDocumentId) { AtomicInteger(0) }
            .incrementAndGet()
        throwIfFaultInjected(Operation.QUERY_CHILDREN, parentDocumentId)

        val dir = fileFor(parentDocumentId)
        if (!dir.exists() || !dir.isDirectory) {
            throw FileNotFoundException("Directory does not exist: $parentDocumentId")
        }
        val cols = projection ?: DEFAULT_DOCUMENT_PROJECTION
        val cursor = MatrixCursor(cols)
        val children = dir.listFiles()?.sortedBy { it.name }.orEmpty()
        for (child in children) {
            includeFile(cursor, cols, docIdFor(child), child)
        }
        return cursor
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String,
    ): String {
        createCounter.incrementAndGet()
        throwIfFaultInjected(Operation.CREATE, parentDocumentId)
        throwIfFaultInjected(Operation.CREATE, "$parentDocumentId/$displayName")

        val parentDir = fileFor(parentDocumentId)
        if (!parentDir.exists() || !parentDir.isDirectory) {
            throw FileNotFoundException("Parent directory not found: $parentDocumentId")
        }

        // 复现 AOSP FileSystemProvider.createDocument 的真实防撞名行为：
        // 若同名文件已存在，自动加 " (1)" / " (2)" 后缀，而非原地覆盖。
        val targetFile = resolveUniqueCreateTarget(parentDir, displayName)
        if (mimeType == Document.MIME_TYPE_DIR) {
            if (!targetFile.mkdirs() && !targetFile.exists()) {
                throw IllegalStateException("Failed to create directory: $targetFile")
            }
        } else {
            targetFile.parentFile?.mkdirs()
            if (!targetFile.exists() && !targetFile.createNewFile()) {
                throw IllegalStateException("Failed to create file: $targetFile")
            }
        }
        val newDocId = docIdFor(targetFile)
        customMimeTypes[newDocId] = mimeType
        return newDocId
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        renameCounter.incrementAndGet()
        throwIfFaultInjected(Operation.RENAME, documentId)

        if (documentId in readOnlyDocIds) {
            throw IllegalStateException("Read-only document cannot be renamed: $documentId")
        }

        val sourceFile = fileFor(documentId)
        if (!sourceFile.exists()) {
            throw FileNotFoundException("Document not found for rename: $documentId")
        }
        val parentDir = sourceFile.parentFile
            ?: throw IllegalStateException("Cannot rename root directory: $documentId")
        val targetFile = File(parentDir, displayName)
        if (targetFile.exists() && targetFile != sourceFile) {
            throw IllegalStateException("Target already exists: $displayName")
        }
        if (targetFile != sourceFile) {
            Files.move(sourceFile.toPath(), targetFile.toPath())
        }
        val newDocId = docIdFor(targetFile)
        customMimeTypes.remove(documentId)?.let { customMimeTypes[newDocId] = it }
        return newDocId
    }

    override fun deleteDocument(documentId: String) {
        deleteCounter.incrementAndGet()
        throwIfFaultInjected(Operation.DELETE, documentId)

        val targetFile = fileFor(documentId)
        if (!targetFile.exists()) {
            throw FileNotFoundException("Document not found for delete: $documentId")
        }
        val deleted = if (targetFile.isDirectory) {
            targetFile.deleteRecursively()
        } else {
            targetFile.delete()
        }
        if (!deleted) {
            throw IllegalStateException("Failed to delete $targetFile")
        }
        customMimeTypes.remove(documentId)
        readOnlyDocIds.remove(documentId)
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        lastOpenModeByDocId[documentId] = mode
        openHistory.add(documentId to mode)
        throwIfFaultInjected(Operation.OPEN, documentId)

        openCountdownFaults[documentId]?.let { countdown ->
            val prev = countdown.remainingAllowed.getAndDecrement()
            if (prev <= 0) {
                throw countdown.fault()
            }
        }

        val file = fileFor(documentId)
        if (mode == "r") {
            if (!file.exists() || file.isDirectory) {
                throw FileNotFoundException("Cannot open for read: $documentId")
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        if (documentId in readOnlyDocIds) {
            throw SecurityException("Read-only document cannot be opened for write: $documentId")
        }
        file.parentFile?.mkdirs()
        if (!file.exists()) {
            file.createNewFile()
        }
        return ParcelFileDescriptor.open(file, parseOpenModeStrict(mode))
    }

    private fun includeFile(
        cursor: MatrixCursor,
        cols: Array<out String>,
        docId: String,
        file: File,
    ) {
        val isDir = file.isDirectory
        val displayName = if (file == rootDir) rootDisplayName else file.name
        val mime = if (isDir) {
            Document.MIME_TYPE_DIR
        } else {
            customMimeTypes[docId] ?: inferMimeType(file.name)
        }
        val flags = if (isDir) {
            Document.FLAG_DIR_SUPPORTS_CREATE
        } else if (docId in readOnlyDocIds) {
            0
        } else {
            Document.FLAG_SUPPORTS_WRITE or
                Document.FLAG_SUPPORTS_DELETE or
                Document.FLAG_SUPPORTS_RENAME
        }
        val row = cursor.newRow()
        for (col in cols) {
            when (col) {
                Document.COLUMN_DOCUMENT_ID -> row.add(docId)
                Document.COLUMN_DISPLAY_NAME -> row.add(displayName)
                Document.COLUMN_MIME_TYPE -> row.add(mime)
                Document.COLUMN_SIZE -> row.add(if (isDir) null else file.length())
                Document.COLUMN_LAST_MODIFIED -> row.add(file.lastModified())
                Document.COLUMN_FLAGS -> row.add(flags)
                else -> row.add(null)
            }
        }
    }

    private fun fileFor(documentId: String): File {
        if (documentId == rootDocId) return rootDir
        val prefix = "$rootDocId/"
        if (!documentId.startsWith(prefix)) {
            throw FileNotFoundException("Unknown documentId outside root ($rootDocId): $documentId")
        }
        val relPath = documentId.removePrefix(prefix)
        return File(rootDir, relPath)
    }

    private fun docIdFor(file: File): String {
        val rootPath = rootDir.toPath().toAbsolutePath().normalize()
        val targetPath = file.toPath().toAbsolutePath().normalize()
        if (targetPath == rootPath) return rootDocId
        val rel = rootPath.relativize(targetPath).toString().replace('\\', '/')
        return "$rootDocId/$rel"
    }

    private fun resolveUniqueCreateTarget(parentDir: File, displayName: String): File {
        val direct = File(parentDir, displayName)
        if (!direct.exists()) return direct

        val dotIndex = displayName.lastIndexOf('.')
        val base = if (dotIndex > 0) displayName.substring(0, dotIndex) else displayName
        val ext = if (dotIndex > 0) displayName.substring(dotIndex) else ""
        var index = 1
        while (true) {
            val candidate = File(parentDir, "$base ($index)$ext")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    /**
     * 严格区分 `"w"`（不截断）与 `"wt"`（截断既有文件为 0 字节）。
     *
     * Robolectric 的 `ShadowParcelFileDescriptor.open` 仅在含 [ParcelFileDescriptor.MODE_TRUNCATE]
     * 时执行 `RandomAccessFile.setLength(0)`；而 Android 10+ 的 `ParcelFileDescriptor.parseMode("w")`
     * 会隐式并入 `MODE_TRUNCATE`，掩盖第三方/国产 ROM `DocumentsProvider` 对 `"w"` 不截断的真实行为。
     * 这里按模式字面量显式映射，确保生产代码漏写 `"t"` 时会在磁盘末尾残留旧字节并被 `ZipFile` 检出。
     */
    private fun parseOpenModeStrict(mode: String): Int = when (mode) {
        "r" -> ParcelFileDescriptor.MODE_READ_ONLY
        "w" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE
        "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
            ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE
        "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
            ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_APPEND
        "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        "rwt" -> ParcelFileDescriptor.MODE_READ_WRITE or
            ParcelFileDescriptor.MODE_CREATE or
            ParcelFileDescriptor.MODE_TRUNCATE
        else -> ParcelFileDescriptor.parseMode(mode)
    }

    private fun throwIfFaultInjected(operation: Operation, documentId: String) {
        val fault = docFaults[operation to documentId] ?: globalFaults[operation] ?: return
        throw fault()
    }

    /**
     * 桥接 Robolectric `ShadowContentResolver.query(5-arg/6-arg)` 到 [DocumentsProvider.query] (Bundle 重载)。
     *
     * 真机 AOSP `ContentResolver.query(uri, projection, selection, selectionArgs, sortOrder)` 会先把参数
     * 打包进 `Bundle` 再调用 `ContentProvider.query(uri, projection, queryArgs, signal)`，因此
     * [DocumentsProvider] 的 5 参数与 6 参数 `query` 重载直接抛
     * `UnsupportedOperationException("Pre-Android-O query format not supported.")`。
     * 而 Robolectric 的 `ShadowContentResolver` 直接调用了 5 参数重载，故在此将其转发至 Bundle 重载。
     */
    @Implements(DocumentsProvider::class)
    class ShadowDocumentsProvider {
        @RealObject
        private lateinit var realProvider: DocumentsProvider

        @Implementation
        protected fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = realProvider.query(uri, projection, Bundle.EMPTY, null)

        @Implementation
        protected fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
            cancellationSignal: CancellationSignal?,
        ): Cursor? = realProvider.query(uri, projection, Bundle.EMPTY, cancellationSignal)
    }

    /**
     * Windows JVM 下 `RandomAccessFile.getFD()` 使用 Windows `HANDLE` 而 POSIX `fd` 为 -1；
     * Robolectric 默认未在 [ShadowLinux] 实现 `lseek`，返回 0 会让 Coil 3 的 `StaticImageDecoder`
     * 误判 `AssetFileDescriptor` 可交由 C++ `ImageDecoderNatives.nCreate(FileDescriptor)` 按 POSIX fd 解码。
     * 按 Android 不可寻址描述符契约抛出 `ErrnoException("lseek", ESPIPE)`，使 Coil 3 走标准回退至
     * `BitmapFactoryDecoder`（经 Skia 原生渲染管线解码与降采样）。
     */
    @Implements(Os::class)
    class ShadowPosixLseekBridge {
        companion object {
            @JvmStatic
            @Implementation
            protected fun lseek(fd: FileDescriptor?, offset: Long, whence: Int): Long {
                throw ErrnoException("lseek", OsConstants.ESPIPE)
            }
        }
    }

    companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val DEFAULT_ROOT_DOC_ID = "primary:workspace"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )

        fun setup(
            rootDir: File,
            rootDocId: String = DEFAULT_ROOT_DOC_ID,
            rootDisplayName: String = rootDir.name,
        ): FakeDocumentsProvider {
            val provider = Robolectric.setupContentProvider(
                FakeDocumentsProvider::class.java,
                AUTHORITY,
            )
            provider.bindRootDir(
                dir = rootDir,
                rootDocumentId = rootDocId,
                displayName = rootDisplayName,
            )
            return provider
        }

        private fun inferMimeType(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "avif" -> "image/avif"
                "heic", "heif" -> "image/heic"
                "cbz" -> "application/vnd.comicbook+zip"
                "xml" -> "application/xml"
                "txt" -> "text/plain"
                else -> "application/octet-stream"
            }
        }
    }
}
