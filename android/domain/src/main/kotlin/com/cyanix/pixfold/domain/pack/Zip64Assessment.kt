package com.cyanix.pixfold.domain.pack

/**
 * Zip64 需求判定与中断半成品识别（D2b，HANGOFF §9 D2b「大归档写入 Zip64」）。
 *
 * ## 归责边界
 *
 * - **归这里**：Zip64 需求判定（纯算术，基于条目数与大小）、中断半成品识别（纯逻辑）；
 * - **不归这里**：实际配置 `ZipOutputStream.setUseZip64`（平台层/写入层接线）、
 *   实际检测文件末尾的 EOCD 签名（平台层读文件）。
 *
 * 本模块只 import Kotlin stdlib，`domain/` 零 Android 依赖边界在此同样成立。
 */

/**
 * ZIP 传统格式（ZIP32）的限制常量。
 *
 * ZIP 格式规范（APPNOTE.TXT 4.4.17）的 16/32 位字段上限：
 * - 条目数字段为 16 位 ⇒ 最多 65535 条；
 * - 条目大小 / 压缩后大小 / 偏移均为 32 位 ⇒ 最大 4 GB − 1。
 */
object Zip32Limits {
    /** 中央目录最多条目数（16 位字段上限）。 */
    const val MAX_ENTRIES: Int = 0xFFFF // 65535

    /** 单条目最大大小（字节，32 位字段上限 = 4 GB − 1）。 */
    const val MAX_ENTRY_SIZE: Long = 0xFFFFFFFFL

    /** 中央目录起始偏移最大值（字节，32 位字段上限）。 */
    const val MAX_CENTRAL_DIR_OFFSET: Long = 0xFFFFFFFFL
}

/** 触发 Zip64 的原因（按判定优先级排列）。 */
enum class Zip64Reason(val description: String) {
    TooManyEntries("条目数超过 65535"),
    EntryTooLarge("条目大小超过 4GB - 1"),
    CentralDirOffsetTooLarge("中央目录偏移超过 4GB - 1"),
}

/**
 * Zip64 需求的判定结果。
 */
sealed class Zip64Requirement {
    /** 不需要 Zip64，ZIP32 足够。 */
    data object NotNeeded : Zip64Requirement()

    /** 需要 Zip64，[reason] 说明触发哪条限制。 */
    data class Needed(val reason: Zip64Reason) : Zip64Requirement()
}

/**
 * 判定一次打包是否需要 Zip64 扩展。
 *
 * 判定顺序（与 [Zip64Reason] 声明序一致）：条目数 → 条目大小 → 中央目录偏移。
 * 先触发的先报，调用方可据此给出最相关的提示。
 *
 * @param entryCount       条目总数（含 `ComicInfo.xml`）
 * @param maxEntrySize     最大条目大小（字节）
 * @param centralDirOffset 中央目录起始偏移（字节，`0` 表示尚未知 / 不判定）
 */
fun assessZip64Need(
    entryCount: Int,
    maxEntrySize: Long,
    centralDirOffset: Long = 0L,
): Zip64Requirement {
    require(entryCount >= 0) { "entryCount 不能为负：$entryCount" }
    require(maxEntrySize >= 0) { "maxEntrySize 不能为负：$maxEntrySize" }
    require(centralDirOffset >= 0) { "centralDirOffset 不能为负：$centralDirOffset" }

    return when {
        entryCount > Zip32Limits.MAX_ENTRIES ->
            Zip64Requirement.Needed(Zip64Reason.TooManyEntries)
        maxEntrySize > Zip32Limits.MAX_ENTRY_SIZE ->
            Zip64Requirement.Needed(Zip64Reason.EntryTooLarge)
        centralDirOffset > Zip32Limits.MAX_CENTRAL_DIR_OFFSET ->
            Zip64Requirement.Needed(Zip64Reason.CentralDirOffsetTooLarge)
        else -> Zip64Requirement.NotNeeded
    }
}

/**
 * 判定一个归档文件是否完整（非中断半成品）。
 *
 * 中断写入（OOM / 空间耗尽 / 进程被杀 / SAF 断流）的半成品文件
 * **没有 End of Central Directory Record（EOCD）**，或文件为空。
 * 这是「中断半成品识别」的纯逻辑判定：平台层负责检测 EOCD 签名是否存在，
 * 领域层据此 + 文件大小给出结论。
 *
 * @param fileSize 文件大小（字节）
 * @param hasEocd  文件末尾是否存在合法的 EOCD 签名（`0x06054b50`）
 */
fun isCompleteArchive(fileSize: Long, hasEocd: Boolean): Boolean {
    require(fileSize >= 0) { "fileSize 不能为负：$fileSize" }
    return fileSize > 0 && hasEocd
}
