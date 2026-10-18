package com.cyanix.pixfold.domain.source

/**
 * 平台层扫出来的一条文档记录（D2a）。
 *
 * 为什么要有这一层：SAF 的 `DocumentsContract` 查询结果带着 Android 类型
 * （`Uri`、`Cursor`），不能进 `domain/`（`AdapterBoundaryTest` 强制零 Android 依赖）。
 * 所以平台层把每一行压成这个纯 Kotlin 值对象，分组/命名/排序等**全部判断逻辑**
 * 由 [collectionsFrom] 在领域层完成、可单测。
 *
 * @param documentUri 文档 URI 字符串。领域层只当不透明标识符用，不解析、不假设格式。
 * @param relPath 相对工作区根目录的路径，用 `/` 分隔；根下的文件就是文件名本身
 *   （例如根下的 `a.jpg` 是 `"a.jpg"`，`day1/` 里的是 `"day1/a.jpg"`）。
 *   平台层负责算出它 —— 递归遍历时本来就知道当前层级。
 */
data class SourceDoc(
    val documentUri: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
    val relPath: String,
) {
    val isDirectory: Boolean get() = mimeType == MIME_TYPE_DIR

    /** 图片判定按 MIME 前缀，不按扩展名 —— 扩展名可以被改成任意值。 */
    val isImage: Boolean get() = mimeType.startsWith("image/")

    companion object {
        /** 与 `DocumentsContract.Document.MIME_TYPE_DIR` 同值。写死常量是为了不 import Android。 */
        const val MIME_TYPE_DIR: String = "vnd.android.document/directory"
    }
}
