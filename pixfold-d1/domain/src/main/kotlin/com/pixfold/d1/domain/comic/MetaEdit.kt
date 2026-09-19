package com.pixfold.d1.domain.comic

import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.LangChoice
import com.pixfold.d1.domain.model.MetaField
import com.pixfold.d1.domain.model.MetaValue
import com.pixfold.d1.domain.model.Suggestion
import com.pixfold.d1.domain.sort.pageOrderOf

/**
 * 元数据编辑的纯函数入口(规格 §8.1 / §8.2)。
 *
 * 三条不可动摇的语义:
 *  1. **建议 ≠ 事实**:构造 `ComicVolume` 时当前值取自建议,但每个建议都保留
 *     [Suggestion.source];UI 必须显示"建议 + 来源",且每字段有"恢复建议值"入口。
 *  2. **语言默认 [LangChoice.Unset]**:即使建议里有 `ja`,当前值也不采纳 —— 必须人工确认。
 *  3. **批次统一 + 逐项例外**:批量设置**跳过**被人工改过的字段([batchApply]),
 *     并**返回实际生效数量**(UI 提示 `已设置到 N 卷`)。
 */

/** 非法字符集(与命名侧同一集合;CBZ 文件名兜底用)。 */
private val ILLEGAL_FOR_FILE_NAME = Regex("[\\\\/:*?\"<>|]")

/**
 * 默认 CBZ 文件名(规格 §8.3)。
 *
 * **用的是 [titleSug] 而不是用户改后的 `title`** —— 默认名只在构造时算一次,
 * 改 `title` **不会**自动重算(需手动改或点"恢复建议值")。这是归档既有语义,保留:
 * 否则用户手改的 CBZ 文件名会被 title 的编辑悄悄冲掉。
 */
fun defaultCbzName(
    id: String,
    titleSug: Suggestion<String>,
    volumeSug: Suggestion<Int?>,
): String {
    val t = (titleSug.value ?: id).replace(ILLEGAL_FOR_FILE_NAME, "_")
    val v = volumeSug.value
    return if (v == null) "$t.cbz" else "$t 第${v.toString().padStart(2, '0')}卷.cbz"
}

/**
 * 语言选项 → ComicInfo.xml 的 `LanguageISO` 值(规格 §8.4)。
 *
 * `null` 表示**不写入标签**;`Other` 不校验 ISO 639-1 合法性(归档语义:交给用户)。
 */
fun langIso(choice: LangChoice, otherCode: String?): String? = when (choice) {
    LangChoice.Zh -> "zh"
    LangChoice.Ja -> "ja"
    LangChoice.Other -> otherCode?.takeIf { it.isNotEmpty() }
    LangChoice.Unset, LangChoice.Unknown, LangChoice.Skip -> null
}

/**
 * 由建议值构造一卷:当前值 = 建议值(title/series/writer/volume),
 * **语言例外 —— 恒为 [LangChoice.Unset]**,`cbzFileName` = [defaultCbzName],`outputDir` = 建议输出目录。
 *
 * 把"初值从哪来"收在领域层,UI 与 mock 不各写一遍(否则"语言默认未设置"这类硬语义
 * 会在某一处被悄悄改成"采纳建议")。
 */
@Suppress("LongParameterList")
fun comicVolumeOf(
    id: String,
    dirPath: String,
    pages: List<com.pixfold.d1.domain.model.SourceItem>,
    titleSug: Suggestion<String>,
    seriesSug: Suggestion<String>,
    writerSug: Suggestion<String>,
    writerCleanedSug: Suggestion<String>,
    volumeSug: Suggestion<Int?>,
    langSug: Suggestion<String>,
    outputDirSug: String,
    outputExists: Boolean = false,
): ComicVolume = ComicVolume(
    id = id,
    dirPath = dirPath,
    pages = pages,
    titleSug = titleSug,
    seriesSug = seriesSug,
    writerSug = writerSug,
    writerCleanedSug = writerCleanedSug,
    volumeSug = volumeSug,
    langSug = langSug,
    outputDirSug = outputDirSug,
    outputExists = outputExists,
    title = titleSug.value ?: "",
    series = seriesSug.value ?: "",
    writer = writerSug.value ?: "",
    volume = volumeSug.value,
    language = LangChoice.Unset,
    otherLangCode = "",
    cbzFileName = defaultCbzName(id, titleSug, volumeSug),
    outputDir = outputDirSug,
    pageOrder = pageOrderOf(pages),
)

/**
 * 人工覆盖某字段:**记录进 [ComicVolume.overridden]**,使其不再被批量设置改写(验收第 13 项)。
 *
 * 值类型与字段不匹配时**原样返回**(不抛异常):UI 的类型路由一旦写错,
 * 宁可"没改"也不要崩在真机上;类型正确性由第 1 层单测钉死。
 */
fun setMetaValue(volume: ComicVolume, field: MetaField, value: MetaValue): ComicVolume {
    val next = when (field) {
        MetaField.Title -> (value as? MetaValue.Text)?.let { volume.copy(title = it.value) }
        MetaField.Series -> (value as? MetaValue.Text)?.let { volume.copy(series = it.value) }
        MetaField.Writer -> (value as? MetaValue.Text)?.let { volume.copy(writer = it.value) }
        MetaField.Volume -> (value as? MetaValue.Number)?.let { volume.copy(volume = it.value) }
        MetaField.Language -> (value as? MetaValue.Language)?.let {
            volume.copy(language = it.choice, otherLangCode = it.otherCode)
        }
        MetaField.CbzName -> (value as? MetaValue.Text)?.let { volume.copy(cbzFileName = it.value) }
        MetaField.OutputDir -> (value as? MetaValue.Text)?.let { volume.copy(outputDir = it.value) }
    } ?: return volume
    return next.copy(overridden = next.overridden + field)
}

/**
 * 恢复建议值(规格 §8.1"逐字段有恢复建议值入口")。
 *
 * 同时**清除该字段的逐项例外标记** —— 回到建议值即回到"未被人工改过"的状态,
 * 之后批量设置又能作用于它(否则会出现"点了恢复建议,却仍被当成逐项例外"的怪状态)。
 */
fun useSuggestion(volume: ComicVolume, field: MetaField): ComicVolume {
    val next = when (field) {
        MetaField.Title -> volume.copy(title = volume.titleSug.value ?: "")
        MetaField.Series -> volume.copy(series = volume.seriesSug.value ?: "")
        MetaField.Writer -> volume.copy(writer = volume.writerSug.value ?: "")
        MetaField.Volume -> volume.copy(volume = volume.volumeSug.value)
        // 语言的建议值就是"未设置":回 Unset 并清空手输代码
        MetaField.Language -> volume.copy(language = LangChoice.Unset, otherLangCode = "")
        MetaField.CbzName -> volume.copy(
            cbzFileName = defaultCbzName(volume.id, volume.titleSug, volume.volumeSug),
        )
        MetaField.OutputDir -> volume.copy(outputDir = volume.outputDirSug)
    }
    return next.copy(overridden = next.overridden - field)
}

/**
 * 一键清理作者:用 [ComicVolume.writerCleanedSug](去作者前缀 / 括号原作 / 尾部标签)。
 * 无清理建议时可点性由 UI 控制;此处无建议即原样返回,避免把作者清空。
 */
fun applyWriterCleaning(volume: ComicVolume): ComicVolume {
    val cleaned = volume.writerCleanedSug.value
    if (cleaned.isNullOrEmpty()) return volume
    return setMetaValue(volume, MetaField.Writer, MetaValue.Text(cleaned))
}

/** [batchApply] 的结果:**实际生效**与**被逐项例外跳过**的卷数,以及更新后的整库。 */
data class BatchApplyResult(
    val volumes: List<ComicVolume>,
    val appliedCount: Int,
    val skippedCount: Int,
)

/**
 * 批量设置(规格 §8.2 / 验收第 13 项)。
 *
 * - 只作用于 `included == true` 的卷(未勾选的卷不参与打包,也不该被批量改写);
 * - **跳过** [ComicVolume.overridden] 中已含该字段的卷,除非 [force];
 * - 返回[实际生效][BatchApplyResult.appliedCount]与[被跳过][BatchApplyResult.skippedCount]数量
 *   —— UI 据此提示 `已设置到 N 卷`,而不是笼统说"已应用"。
 *
 * **为什么强制要"返回实际生效数量"**:批量设置最危险的失败模式是"以为全改了,
 * 其实一半是逐项例外"。只报"成功"会让用户在计划页才发现差异。
 */
fun batchApply(
    volumes: List<ComicVolume>,
    field: MetaField,
    value: MetaValue,
    force: Boolean = false,
): BatchApplyResult {
    var applied = 0
    var skipped = 0
    val next = volumes.map { v ->
        if (!v.included) return@map v
        if (!force && field in v.overridden) {
            skipped++
            return@map v
        }
        applied++
        setMetaValue(v, field, value)
    }
    return BatchApplyResult(next, applied, skipped)
}

/** 勾选/取消勾选某卷是否参与打包。 */
fun setIncluded(volume: ComicVolume, included: Boolean): ComicVolume =
    volume.copy(included = included)

/** 整库替换单卷(按 id 定位;找不到则原样返回)。 */
fun replaceVolume(volumes: List<ComicVolume>, volume: ComicVolume): List<ComicVolume> {
    if (volumes.none { it.id == volume.id }) return volumes
    return volumes.map { if (it.id == volume.id) volume else it }
}

/** 参与打包的卷(计划与打包只处理这些)。 */
fun includedVolumes(volumes: List<ComicVolume>): List<ComicVolume> = volumes.filter { it.included }
