package com.pixfold.d1.domain.model

/**
 * 来源文件快照(= 归档原型的 MockImage)。
 * 时间用 epoch millis 而非 java.time,避免 minSdk 24 的 desugaring(规格 §4.1 注)。
 */
data class SourceItem(
    val id: String,
    val collectionId: String,
    val dir: String,
    val baseName: String,
    val ext: String,
    val sizeBytes: Long,
    val modifiedEpochMillis: Long,
    val createdEpochMillis: Long?,
    val seed: Int,
) {
    val fileName: String get() = "$baseName.$ext"
    val relPath: String get() = if (dir.isEmpty()) fileName else "$dir/$fileName"

    val sizeLabel: String
        get() = when {
            sizeBytes >= 1024L * 1024L -> "${"%.1f".format(sizeBytes / 1024.0 / 1024.0)} MB"
            sizeBytes >= 1024L -> "${sizeBytes / 1024} KB"
            else -> "$sizeBytes B"
        }
}
