package com.pixfold.d1.domain.model

import com.pixfold.d1.domain.sort.PageOrderState
import com.pixfold.d1.domain.sort.pageOrderOf
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey

/**
 * 工作流 B 的元数据领域模型(规格 §4.5 / §8)。
 *
 * 核心语义(规格 §6.1 交互原则"HANGOFF §6.1 建议 + 人工确认"):**建议 ≠ 事实**。
 * 故每个建议值都带一个**人类可读的来源字符串**(含置信度措辞:低置信 / 中置信 /
 * （推断）/ 无语言线索),UI 必须可见,且每个字段都有"恢复建议值"入口。
 *
 * **文案约定(阶段 2 全量 i18n)**:枚举只携带 [TextKey];运行时拼装的文案产出 [Msg]。
 * ⚠️ 例外:[Suggestion.source] 仍是 `String` —— 建议来源由 mock 演示数据给出(模拟的是
 * "真实分析器报出的来源"),按用户裁决 mock 保留中文。D4b 接入真实分析时该字段应改为 [Msg]。
 */

/**
 * 带来源的建议值。
 *
 * [hasValue] 对字符串额外要求非空 —— 空串等于"没有建议",UI 不应把它当成建议展示。
 *
 * ⚠️ [source] 目前是**成品字符串**,由 mock 提供(见文件头说明)。接入真实分析器
 * (D4b)时须改为 [Msg],否则语言会在建库那一刻被冻住。
 */
data class Suggestion<T>(val value: T?, val source: String) {
    val hasValue: Boolean get() = value != null && (value !is String || value.isNotEmpty())
}

/**
 * 语言选项(声明顺序即 UI 下拉顺序)。
 *
 * **[Unset] 是硬语义**:即使 [Suggestion] 给出了语言建议(如 `ja`),构造出的当前值
 * 仍是 [Unset] —— 语言不是可以放心自动推断的字段,必须人工确认(HANGOFF §4 工作流 B)。
 */
enum class LangChoice(val textKey: TextKey) {
    Unset(TextKey.LangUnset),
    Zh(TextKey.LangZh),
    Ja(TextKey.LangJa),
    Other(TextKey.LangOther),
    Unknown(TextKey.LangUnknown),
    Skip(TextKey.LangSkip),
}

/** 可人工覆盖的元数据字段全集(批量设置与"逐项例外"都以它为键)。 */
enum class MetaField(val textKey: TextKey) {
    Title(TextKey.MetaFieldTitle),
    Series(TextKey.MetaFieldSeries),
    Writer(TextKey.MetaFieldWriter),
    Volume(TextKey.MetaFieldVolume),
    Language(TextKey.MetaFieldLanguage),
    CbzName(TextKey.MetaFieldCbzName),
    OutputDir(TextKey.MetaFieldOutputDir),
}

/** 一次批量设置可用的值类型;避免 UI 层对 `Any` 做强转。 */
sealed interface MetaValue {
    data class Text(val value: String) : MetaValue
    data class Number(val value: Int?) : MetaValue
    data class Language(val choice: LangChoice, val otherCode: String = "") : MetaValue
}

/** 单卷的 ComicInfo 元数据:不可变建议 + 可改当前值 + 逐项例外标记。 */
data class ComicVolume(
    val id: String,
    val dirPath: String,
    val pages: List<SourceItem>,
    // ---- 不可变建议(带来源) ----
    val titleSug: Suggestion<String>,
    val seriesSug: Suggestion<String>,
    val writerSug: Suggestion<String>,
    /** 清理后的作者(去作者前缀 / 括号原作 / 尾部标签);一键清理用。 */
    val writerCleanedSug: Suggestion<String>,
    val volumeSug: Suggestion<Int?>,
    val langSug: Suggestion<String>,
    val outputDirSug: String,
    /** mock:输出目录已存在同名 CBZ(演示输出冲突)。 */
    val outputExists: Boolean,
    // ---- 当前值(用户可改) ----
    val title: String,
    val series: String,
    val writer: String,
    val volume: Int?,
    val language: LangChoice = LangChoice.Unset,
    val otherLangCode: String = "",
    val cbzFileName: String,
    val outputDir: String,
    /** 逐项例外:被人工改过的字段;批量设置时**跳过**这些字段(验收第 13 项)。 */
    val overridden: Set<MetaField> = emptySet(),
    val pageOrder: PageOrderState = pageOrderOf(pages),
    val included: Boolean = true,
) {
    /** 分组键 = 当前 series 值(用户改了系列名,分组随之变化)。 */
    val seriesKey: String get() = series

    /** 页面张数以**页序**为准(与页码预览、XML 的 PageCount 同源)。 */
    val pageCount: Int get() = pageOrder.order.size

    /**
     * 待确认项(固定顺序,验收第 12 项):语言未确认 / 卷号缺失。
     * 归档语义:UI 需在卷列表、卷头、计划卡三处一致展示(规格 §4.7)。
     *
     * 返回 [Msg] 而非成品字符串 —— 见 `text/Msg.kt`。
     */
    val pendingIssues: List<Msg>
        get() = buildList {
            if (language == LangChoice.Unset) add(Msg(TextKey.PendingLanguage))
            if (volume == null) add(Msg(TextKey.PendingVolume))
        }

    /** 是否有待确认项。 */
    val needsConfirmation: Boolean get() = pendingIssues.isNotEmpty()

    val isOverridden: Boolean get() = overridden.isNotEmpty()
}

/**
 * 系列分组(保持首次出现顺序;空系列名归入**空串键**的兜底组)。
 *
 * ⚠️ 兜底组的键是**空字符串**,不是「（未命名系列）」这类文案 —— 分组键参与
 * `testTag` 与相等性判断,若把译文当键,切换语言就会把分组"换"成另一个键。
 * UI 渲染时把空串映射成 [TextKey.UnnamedSeries](见 `ui/workflowb/LibraryPage.kt`)。
 */
fun groupVolumesBySeries(volumes: List<ComicVolume>): List<Pair<String, List<ComicVolume>>> {
    val map = LinkedHashMap<String, MutableList<ComicVolume>>()
    volumes.forEach { v ->
        val key = v.seriesKey
        map.getOrPut(key) { mutableListOf() }.add(v)
    }
    return map.map { (k, v) -> k to v.toList() }
}
