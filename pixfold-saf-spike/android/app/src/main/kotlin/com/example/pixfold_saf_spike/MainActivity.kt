package com.example.pixfold_saf_spike

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * D2 SAF spike(HANGOFF §8.3):验证 Android SAF 全闭环。
 * 通道:pixfold/saf —— openTree / listImages / readBytes / renameDoc / createAndWrite / deleteDoc
 * 纯系统 DocumentsContract API,无 androidx 依赖。
 */
class MainActivity : FlutterActivity() {

    companion object {
        const val CHANNEL = "pixfold/saf"
        const val REQ_TREE = 1001
    }

    private var pendingTree: MethodChannel.Result? = null

    private val prefs by lazy {
        getSharedPreferences("pixfold_saf_spike", Context.MODE_PRIVATE)
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    dispatch(call.method, call.arguments as? Map<*, *> ?: emptyMap<Any?, Any?>(), result)
                } catch (e: Exception) {
                    result.error("ERR", e.message ?: e.toString(), null)
                }
            }
    }

    private fun dispatch(method: String, a: Map<*, *>, result: MethodChannel.Result) {
        when (method) {
            "openTree" -> {
                if (pendingTree != null) {
                    result.error("BUSY", "已有未完成的目录选择", null)
                    return
                }
                pendingTree = result
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE)
            }
            "getLastTree" -> result.success(prefs.getString("tree_uri", null))
            "listImages" -> {
                val treeUri = Uri.parse(a["uri"] as String)
                val maxDepth = (a["maxDepth"] as? Number)?.toInt() ?: 4
                val items = ArrayList<Map<String, Any>>()
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                collectImages(this, treeUri, treeDocId, "", treeUri, 0, maxDepth, items)
                result.success(items)
            }
            "readBytes" -> {
                val uri = Uri.parse(a["uri"] as String)
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                val headHex = bytes.take(16).joinToString("") { "%02x".format(it) }
                result.success(mapOf("length" to bytes.size, "headHex" to headHex))
            }
            "renameDoc" -> {
                val uri = Uri.parse(a["uri"] as String)
                val name = a["name"] as String
                result.success(DocumentsContract.renameDocument(contentResolver, uri, name)?.toString())
            }
            "createAndWrite" -> {
                val treeUri = Uri.parse(a["parentUri"] as String)
                val name = a["name"] as String
                val content = (a["content"] as? String ?: "").toByteArray(Charsets.UTF_8)
                // 部分 ROM 严格校验 createDocument 的 parent 必须是 document URI(tree URI 直接传会抛 IllegalArgumentException)
                val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val parentDocUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
                val created = DocumentsContract.createDocument(
                    contentResolver, parentDocUri, "application/octet-stream", name
                ) ?: throw IllegalStateException("createDocument 返回 null")
                contentResolver.openOutputStream(created)?.use { it.write(content) }
                result.success(created.toString())
            }
            "deleteDoc" -> {
                val uri = Uri.parse(a["uri"] as String)
                result.success(DocumentsContract.deleteDocument(contentResolver, uri))
            }
            else -> result.notImplemented()
        }
    }

    /** 递归收集图片:dirUri 表示当前目录的 document uri(根为 tree uri)。 */
    private fun collectImages(
        ctx: Context,
        treeUri: Uri,
        dirDocId: String,
        rel: String,
        dirUri: Uri,
        depth: Int,
        maxDepth: Int,
        out: MutableList<Map<String, Any>>
    ) {
        if (depth > maxDepth) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        )
        ctx.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0)
                val name = c.getString(1) ?: ""
                val mime = c.getString(2) ?: ""
                val size = if (c.isNull(3)) -1L else c.getLong(3)
                val childRel = if (rel.isEmpty()) name else "$rel/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val childDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    collectImages(ctx, treeUri, childId, childRel, childDirUri, depth + 1, maxDepth, out)
                } else if (mime.startsWith("image/")) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                    out.add(mapOf(
                        "name" to name,
                        "path" to childRel,
                        "uri" to fileUri.toString(),
                        "size" to size,
                        "parent" to dirUri.toString()
                    ))
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return
        val pending = pendingTree ?: return
        pendingTree = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            pending.success(null) // 用户取消
            return
        }
        val treeUri = data.data!!
        // 无论持久授权成功与否都记住 URI:重开后若授权真失效,listImages 会抛
        // SecurityException(Permission Denial),与"未尝试访问"可明确区分。
        prefs.edit().putString("tree_uri", treeUri.toString()).apply()
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // 持久授权失败也返回 uri,由验收记录;不抛出
        }
        pending.success(treeUri.toString())
    }
}
