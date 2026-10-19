package com.cyanix.pixfold.data.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.net.toUri
import android.provider.DocumentsContract
import android.util.Log
import com.cyanix.pixfold.domain.model.SourceItem
import com.cyanix.pixfold.domain.source.LoadResult
import com.cyanix.pixfold.domain.source.SourceDoc
import com.cyanix.pixfold.domain.source.WorkspaceChangeWatcher
import com.cyanix.pixfold.domain.source.WorkspaceHandle
import com.cyanix.pixfold.domain.source.WorkspaceSource
import com.cyanix.pixfold.domain.source.collectionsFrom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 SAF（`DocumentsContract` + `ContentResolver`）的只读工作区来源（D2a）。
 *
 * 这是 `domain/source/WorkspaceSource` 端口的 Android 实现。**只做资源访问**：
 * 遍历、查询列、读字节。分组/命名/排序全部交给领域层的 `collectionsFrom`
 * （HANGOFF §9 D3 退出条件 ②：平台层只承担资源访问，判断逻辑留在 domain）。
 *
 * ## 从归档 spike 继承的三条经验（`origin/archive-flutter-verify` §8.3）
 *
 * 1. **tree URI 不能直接当 parent 传给 `createDocument`** —— 本轮只读，用不到，
 *    但同一坑在 `buildDocumentUriUsingTree` 一侧也存在：查询子项必须用
 *    `buildChildDocumentsUriUsingTree(treeUri, parentDocId)`，不能拿 tree URI 硬拼。
 * 2. **`takePersistableUriPermission` 可能抛 `SecurityException`，但不代表 URI 不可用** ——
 *    授权已在别处生效时再 take 会抛。所以那一步的失败**不在这里判定**：
 *    真正的可用性判据是「能不能 query 出东西」，见 [loadCollections]。
 * 3. **递归要有深度上限** —— 用户可能选到整个存储根，无上限会栈溢出/扫到天荒地老。
 *
 * ## 线程
 * 所有方法都是阻塞 IO。调用方负责放到 `Dispatchers.IO`（见 `WorkspaceViewModel`）。
 */
class AndroidSafSource(context: Context) : WorkspaceSource {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val treeStore = SafTreeStore(appContext)

    override fun lastWorkspace(): WorkspaceHandle? = treeStore.load()

    /**
     * 记住授权：先 `takePersistableUriPermission`，再落 SharedPreferences。
     *
     * **顺序与容错**（继承归档 spike 的实测结论）：即使 `takePersistableUriPermission`
     * 抛 `SecurityException` 也要把 URI 存下来 —— 该异常的含义是「这次没能把授权
     * 提升为持久授权」，**不等于**这个 URI 现在不可用（本次进程内仍可能读得到）。
     * 反过来，先存 URI 再提升授权，能保证「下次启动至少会去试一次」，
     * 真的失效时由 `loadCollections` 返回 [LoadResult.Denied] 走重选流程。
     */
    override fun rememberWorkspace(handle: WorkspaceHandle) {
        treeStore.save(handle)
        try {
            resolver.takePersistableUriPermission(
                handle.treeUri.toUri(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // 预期内：部分 provider 不支持持久授权，或系统已回收。
            // URI 已存下，本次会话仍可读；下次启动若真失效会走 Denied。
            Log.w(TAG, "takePersistableUriPermission 失败 ${handle.treeUri}", e)
        } catch (e: Exception) {
            Log.w(TAG, "takePersistableUriPermission 异常 ${handle.treeUri}", e)
        }
    }

    override fun forgetWorkspace() = treeStore.clear()

    /**
     * 取树 URI 对应目录的显示名（D2a）。
     *
     * 用户刚选完目录时要用它填 [WorkspaceHandle.displayName] ——
     * tree URI 形如 `content://com.android.externalstorage.documents/tree/primary%3ADCIM`，
     * 里面的 `DCIM` 是**文档 ID** 而不是显示名（中文目录名会被百分号编码），
     * 直接截取当名字显示出来是乱码。只有 provider 的 `COLUMN_DISPLAY_NAME` 才是真名。
     *
     * 取不到（provider 不响应 / 授权异常）时返回空串，不抛 ——
     * 显示名只影响一条状态文案，不该阻断选目录流程。
     */
    fun displayNameOf(treeUri: String): String = try {
        val uri = treeUri.toUri()
        queryDisplayName(uri, DocumentsContract.getTreeDocumentId(uri))
    } catch (e: Exception) {
        Log.w(TAG, "displayNameOf: 取目录名失败 $treeUri", e)
        ""
    }

    override fun loadCollections(handle: WorkspaceHandle): LoadResult {
        // `Uri.parse` 是**宽容解析**：永不抛异常、永不返回 null，非法串只会得到一个
        // 内容怪异的 Uri。因此这里**没有**「解析失败」这一支 —— 真正的失败判据是
        // 下面的 `getTreeDocumentId`（不是 tree URI 时会抛 IllegalArgumentException，
        // 由外层 catch 转成 Failed）。原实现写了个 try/catch 包 Uri.parse，
        // 那个分支是死的（lint 之外，它还会让人误以为解析失败是可处理的已知状态）。
        val treeUri = handle.treeUri.toUri()
        return try {
            val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val out = mutableListOf<SourceDoc>()
            collect(
                treeUri = treeUri,
                parentDocId = rootDocId,
                parentRelPath = "",
                depth = 0,
                out = out,
            )
            val rootName = handle.displayName.ifEmpty { queryDisplayName(treeUri, rootDocId) }
            LoadResult.Success(collectionsFrom(rootName, out))
        } catch (e: SecurityException) {
            // 授权失效是**预期内**状态（用户撤销 / 系统回收），不是崩溃理由。
            Log.w(TAG, "loadCollections: 授权失效", e)
            LoadResult.Denied
        } catch (e: Exception) {
            Log.w(TAG, "loadCollections: 扫描失败", e)
            LoadResult.Failed(e.message ?: e::class.java.simpleName)
        }
    }

    override fun readBytes(item: SourceItem): ByteArray? {
        val raw = item.uri ?: return null
        return try {
            resolver.openInputStream(raw.toUri())?.use { it.readBytes() }
        } catch (e: SecurityException) {
            Log.w(TAG, "readBytes: 授权失效", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "readBytes: 读取失败 ${item.fileName}", e)
            null
        }
    }

    /**
     * 订阅 SAF provider 的变更通知（D4「工作区实时刷新」）。
     *
     * **为什么用 `ContentObserver` 而不是轮询**：轮询要么慢（几秒一次，用户删了图
     * 界面还挂着已删的图）、要么贵（每次都要递归 query 整棵树）。`DocumentsProvider`
     * 在文件变化时会向 `ContentResolver` 发通知，这是系统级的「真·实时」通道。
     *
     * **为什么必须 `notifyForDescendants = true`**：我们监听的是**树根** URI，而实际
     * 变更发生在深层的 `.../tree/primary:DCIM/document/...` 子文档上。传 `false`
     * 就只收根节点自身的通知 —— 而根节点几乎永远不会变，等于白挂一个 observer。
     *
     * **为什么要去抖**：一次「粘贴 20 张图」往往触发几十条通知（有的 provider 连
     * 元数据更新都发）。逐条转成重扫会把主线程/IO 打满，而用户看到的效果完全一样。
     * 这里用 400ms 的**尾沿**去抖：每来一条通知就重置计时，安静下来才回调一次。
     *
     * **注册失败不抛**：授权已失效时 `registerContentObserver` 会抛
     * `SecurityException`。实时监听只是增强能力，失败就让调用方退回「回前台重扫 +
     * 手动刷新」，不该让界面崩掉或误判成「工作区坏了」。
     */
    override fun watchChanges(handle: WorkspaceHandle, onChange: () -> Unit): WorkspaceChangeWatcher? {
        val mainHandler = Handler(Looper.getMainLooper())
        // close() 与 onReceive 可能在不同线程（observer 回调在主线程，close 由
        // 控制器/Compose 的 DisposableEffect 调用），必须原子地只生效一次。
        val closed = AtomicBoolean(false)
        val notify = Runnable {
            if (!closed.get()) onChange()
        }
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (closed.get()) return
                mainHandler.removeCallbacks(notify)
                mainHandler.postDelayed(notify, CHANGE_DEBOUNCE_MILLIS)
            }
        }

        return try {
            resolver.registerContentObserver(handle.treeUri.toUri(), true, observer)
            WorkspaceChangeWatcher {
                if (closed.compareAndSet(false, true)) {
                    // 先摘掉还没触发的去抖任务，否则 close 之后 400ms 还会回调一次，
                    // 让已经废弃的工作区再扫一遍。
                    mainHandler.removeCallbacks(notify)
                    try {
                        resolver.unregisterContentObserver(observer)
                    } catch (e: Exception) {
                        // 注销失败最坏只是多收几条通知（已被 closed 挡住），不抛。
                        Log.w(TAG, "watchChanges: 注销失败 ${handle.treeUri}", e)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "watchChanges: 授权失效，跳过实时监听 ${handle.treeUri}", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "watchChanges: 注册失败 ${handle.treeUri}", e)
            null
        }
    }

    /**
     * 递归收集文档。
     *
     * 用显式 `parentDocId` 而不是从 URI 反解 —— `DocumentsContract` 的子项查询
     * 要求「tree URI + 父 document id」两个参数，反解出来的 id 在部分国产 ROM 上
     * 会带上 provider 私有后缀。
     */
    private fun collect(
        treeUri: Uri,
        parentDocId: String,
        parentRelPath: String,
        depth: Int,
        out: MutableList<SourceDoc>,
    ) {
        if (depth > MAX_DEPTH) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        queryChildren(childrenUri) { docId, name, mime, size, modified ->
            val relPath = if (parentRelPath.isEmpty()) name else "$parentRelPath/$name"
            val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
            // 目录条目也收进 out：collectionsFrom 会过滤掉它们，
            // 但留着能让「目录里有图但没扫到」这类问题在调试时看得见。
            out += SourceDoc(
                documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                displayName = name,
                mimeType = mime,
                sizeBytes = size,
                lastModifiedEpochMillis = modified,
                relPath = relPath,
            )
            if (isDir) collect(treeUri, docId, relPath, depth + 1, out)
        }
    }

    private inline fun queryChildren(
        childrenUri: Uri,
        onRow: (docId: String, name: String, mime: String, size: Long, modified: Long) -> Unit,
    ) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        // 不传 sortOrder：排序在领域层用 naturalCompare 做，
        // 让 provider 排一遍再排一遍纯属浪费，且两者语义不同会互相打脸。
        resolver.query(childrenUri, projection, null, null, null)?.use { c: Cursor ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: continue
                val size = if (c.isNull(3)) 0L else c.getLong(3)
                val modified = if (c.isNull(4)) 0L else c.getLong(4)
                onRow(docId, name, mime, size, modified)
            }
        }
    }

    private fun queryDisplayName(treeUri: Uri, rootDocId: String): String {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        resolver.query(docUri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0) ?: ""
        }
        return ""
    }

    private companion object {
        const val TAG = "AndroidSafSource"

        /**
         * 递归深度上限。归档 spike 用 4（`maxDepth = 4`）。
         * 漫画库常见结构是 `库/系列/卷/图`，4 层够用；再深说明用户选错了目录。
         */
        const val MAX_DEPTH = 4

        /**
         * 变更通知的尾沿去抖窗口。
         *
         * 400ms 的依据：一次 `DocumentsContract.deleteDocument` / 复制操作在真机上
         * 的连续通知间隔通常 < 100ms，而用户在文件管理器里做完一个动作到切回本应用
         * 至少几百毫秒。太短（< 150ms）去抖不住批量操作，太长（> 1s）用户会觉得
         * 「删了图界面半天没反应」。
         */
        const val CHANGE_DEBOUNCE_MILLIS = 400L
    }
}
