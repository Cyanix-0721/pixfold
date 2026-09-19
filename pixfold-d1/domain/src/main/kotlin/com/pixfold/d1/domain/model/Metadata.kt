package com.pixfold.d1.domain.model

import com.pixfold.d1.domain.sort.PageOrderState
import com.pixfold.d1.domain.sort.pageOrderOf

/**
 * 工作流 B 的元数据领域模型(规格 §4.5 / §8)。
 *
 * 核心语义(规格 §6.1 交互原则"HANGOFF §6.1 建议 + 人工确认"):**建议 ≠ 事实**。
 * 故每个建议值都带一个**人类可读的来源字符串**(含置信度措辞:低置信 / 中置信 /
 * （推断）/ 无语言线索),UI 必须可见,且每个字段都有"恢复建议值"入口。
 */

/**
 * 带来源的建议值。
 *
 * [hasValue] 对字符串额外要求非空 —— 空串等于"没有建议",UI 不应把它当成建议展示。
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
enum class LangChoice(val label: String) {
    Unset("未设置（需确认）"),
    Zh("zh · 中文"),
    Ja("ja · 日文"),
    Other("其他 ISO 639-1"),
    Unknown("未知"),
    Skip("不写入标签"),
}

/** 可人工覆盖的元数据字段全集(批量设置与"逐项例外"都以它为键)。 */
enum class MetaField(val label: String) {
    Title("Title"),
    Series("Series"),
    Writer("Writer"),
    Volume("Number"),
    Language("LanguageISO"),
    CbzName("CBZ 文件名"),
    OutputDir("输出目录"),
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
     */
    val pendingIssues: List<String>
        get() = buildList {
            if (language == LangChoice.Unset) add(PENDING_LANGUAGE)
            if (volume == null) add(PENDING_VOLUME)
        }

    /** 是否有待确认项。 */
    val needsConfirmation: Boolean get() = pendingIssues.isNotEmpty()

    val isOverridden: Boolean get() = overridden.isNotEmpty()
}

/** 待确认项文案常量:UI 与测试共用同一份字面量,避免两处各写一遍而漂移。 */
const val PENDING_LANGUAGE = "语言未确认"
const val PENDING_VOLUME = "卷号缺失"

/** 系列分组(保持首次出现顺序;空系列名归入兜底组)。 */
fun groupVolumesBySeries(volumes: List<ComicVolume>): List<Pair<String, List<ComicVolume>>> {
    val map = LinkedHashMap<String, MutableList<ComicVolume>>()
    volumes.forEach { v ->
        val key = v.seriesKey.ifEmpty { UNNAMED_SERIES }
        map.getOrPut(key) { mutableListOf() }.add(v)
    }
    return map.map { (k, v) -> k to v.toList() }
}

/** 系列名为空时的分组标题(避免出现空白标题行)。 */
const val UNNAMED_SERIES = "（未命名系列）"
