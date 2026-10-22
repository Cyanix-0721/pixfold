package com.cyanix.pixfold.domain.model

import com.cyanix.pixfold.domain.sort.PageOrderState
import com.cyanix.pixfold.domain.sort.pageOrderOf
import com.cyanix.pixfold.domain.text.Msg
import com.cyanix.pixfold.domain.text.TextKey

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
 * **只有三档:`Unset` / `Zh` / `Ja`**(用户 2026-10-20 裁决)。
 * 原先还有 `Other`(自由 ISO 639-1)、`Unknown`、`Skip` 三档,已删除:
 *  - `Other` 没有需求(用户原话「LanguageISO 多出了其他 ISO,这个没需求」);
 *  - `Unknown` 与 `Skip` 都是「不写入标签」,而 [Unset] 已经是这个意思
 *    (用户原话「不写入就使用 Unset 吧,和之前的未设置感觉是一个意思」)——
 *    三个词表达同一件事,用户在下拉里得先猜它们的区别。
 *
 * **[Unset] 是终态,不再要求人工确认**(用户 2026-10-20 裁决,推翻 D1 验收第 12 项
 * 「语言必须人工确认」的语义)。因此 [ComicVolume.pendingIssues] **不再包含语言项** ——
 * 「不写入」是一个合法的最终选择,不是一个待办。
 *
 * 语言仍然**不会**被自动采纳建议值:真实素材的 `langSug` 恒为 null(目录名里没有
 * 可靠的语言信息),将来若从已有 ComicInfo.xml 读到语言,那是「已有值」而非「建议」。
 */
enum class LangChoice(val textKey: TextKey) {
    Unset(TextKey.LangUnset),
    Zh(TextKey.LangZh),
    Ja(TextKey.LangJa),
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
    data class Language(val choice: LangChoice) : MetaValue
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
    val cbzFileName: String,
    val outputDir: String,
    /**
     * **扫描层**的系列目录名;单本卷为空串。
     *
     * 必须与 [seriesKey](当前值)分开。2026-10-21 的 Series 条①「无系列层时 Series
     * 跟随 Title」让单本的 `series` 当前值也等于标题,于是「series 非空 ⇒ 是系列」
     * 这条判据把每个单本都当成一个系列 —— 真机读数:5 个单本显示成「识别到 5 个系列」,
     * 元数据页 1 级芯片全部折叠成 1 行(出现「全部 5 项」)。
     *
     * **是否属于某个系列是扫描期的事实,不能由用户可改的当前值反推**;当前值只决定
     * 同一系列内怎么归组(用户改了系列名,该系列内各卷仍在一起)。
     */
    val seriesDir: String = "",
    /**
     * 仅由**目录结构**推导出的作者建议(去方括号/去前缀后的形态),不随 ComicInfo 改变。
     *
     * 与 [writerSug] 分开的理由:建议值优先级是「ComicInfo 最高,其次目录层级」
     * (用户 2026-10-21 裁决),于是有 `ComicInfo.xml` 时 [writerSug] 会被文件里的作者
     * 顶掉、目录层级的推导结果就丢了。而界面需要一个「按目录结构获取」的入口 ——
     * 用户在文件里的作者写错时,唯一的退路就是按目录名重新取。
     *
     * 空串表示「目录名里没有 `[作者]` 前缀」,此时界面不给这个入口。
     */
    val dirWriterSug: Suggestion<String> = Suggestion(null, ""),
    /**
     * 仅由**目录结构**推导出的 Title 建议,不随 ComicInfo 改变(理由同 [dirWriterSug])。
     *
     * 与 [titleSug] 分开:`ComicInfo.xml` 里写了 `<Title>` 时 [titleSug] 会被文件值
     * 顶掉,而界面的「按目录结构获取」入口要能回到目录名推导的结果。
     */
    val dirTitleSug: Suggestion<String> = Suggestion(null, ""),
    /** 仅由**目录结构**推导出的 Series 建议,不随 ComicInfo 改变(理由同 [dirTitleSug])。 */
    val dirSeriesSug: Suggestion<String> = Suggestion(null, ""),
    /**
     * CBZ 文件名的**基名建议**(不含卷号后缀与扩展名)。
     *
     * 必须独立于 [titleSug]:CBZ 名要**保留** `[作者]` 前缀(文件名用于在文件管理器里
     * 辨认,作者是有用信息),而 `<Title>` 元数据不保留(作者另写 `<Writer>`)。
     * 两者共用 `titleSug` 会让「恢复建议值」悄悄丢掉作者前缀。
     *
     * 为空串表示「没有单独的建议」,此时 [defaultCbzName] 回退到 `titleSug`。
     */
    val cbzBaseSug: Suggestion<String> = Suggestion(null, ""),
    /** 逐项例外:被人工改过的字段;批量设置时**跳过**这些字段(验收第 13 项)。 */
    val overridden: Set<MetaField> = emptySet(),
    val pageOrder: PageOrderState = pageOrderOf(pages),
    val included: Boolean = true,
) {
    /**
     * **扫描层**分组键 —— 与 [seriesDir] 同源。
     *
     * 曾经是 `get() = series`(当前值),2026-10-21 因真机回归改判:当前值会被条①的
     * 「跟随 Title」填满,分组键随之失义。改名后同一系列内仍归一组,因为各卷的
     * [seriesDir] 相同 —— 用户改的是显示名,不是它属于哪个系列目录。
     */
    val seriesKey: String get() = seriesDir

    /** 页面张数以**页序**为准(与页码预览、XML 的 PageCount 同源)。 */
    val pageCount: Int get() = pageOrder.order.size

    /**
     * 该字段自己的待确认项;没有则 `null`。
     *
     * **字段 ↔ 待确认项的对应关系只在这里定义一次。** 页头汇总([pendingIssues])与
     * 字段级红色警告(界面上每个输入框的 `isError` + supportingText)都从这里取 ——
     * 若两处各写一份判据,迟早出现「页头说标题缺失、标题输入框却不标红」这类
     * 用户看得见的分歧(2026-10-22 正是这样:先只做了页头汇总,用户指出
     * 「我说的不是顶部的待确认,而是卷号那样空值的红色警告」)。
     *
     * 语言 / CBZ 名 / 输出目录刻意返回 `null`:语言「不写入」是合法终态,
     * CBZ 名与输出目录有各自的默认值链路,缺了也不会静默丢信息。
     */
    fun pendingIssueOf(field: MetaField): Msg? = when (field) {
        MetaField.Title -> Msg(TextKey.PendingTitle).takeIf { title.isBlank() }
        MetaField.Series -> Msg(TextKey.PendingSeries).takeIf { series.isBlank() }
        MetaField.Volume -> Msg(TextKey.PendingVolume).takeIf { volume == null }
        MetaField.Writer -> Msg(TextKey.PendingWriter).takeIf { writer.isBlank() }
        MetaField.Language, MetaField.CbzName, MetaField.OutputDir -> null
    }

    /**
     * 待确认项:**标题 / 卷号 / 系列 / 作者**四项为空时各给一条,顺序固定为
     * 工程内 `ComicInfo.build` 的标签顺序(Title → Series → Number → Writer 里
     * 的字段序,`Number` 与 `Writer` 互换以保持「标量在前」的既有阅读习惯)。
     *
     * 语言项已于 2026-10-20 移除 —— [LangChoice.Unset] 即「不写入标签」,
     * 是合法终态而非待办(用户裁决「Unset 即终态,不再要求确认」)。
     *
     * 这四项与语言的区别:它们的空值在 `ComicInfo.xml` 里写的是**自闭合空标签**
     * (`<Title />` / `<Series />` / `<Number />` / `<Writer />`),阅读器里这本书
     * 就没有标题、不属于任何系列、没有卷号、没有作者 —— 通常意味着**目录名没解析
     * 出来**,值得提醒核对。语言不在此列是因为「不写入」本身就是一个正常选择
     * (用户 2026-10-22 裁决:「所有标签为空时都应该不写入」是写入规则,
     * 不代表空值都必须提醒)。
     *
     * ⚠️ **标题这一项我漏过一轮**:2026-10-22 首次补警告时只补了系列 / 作者,
     * 理由是「标题几乎总能从目录名得到」—— 这条理由**站不住**,因为
     * [titleSug] 给的只是**建议值**,用户完全可以把输入框清空(那是块可编辑的
     * 输入框,不是只读展示),清空后 XML 里就是 `<Title />`。判据必须是
     * 「当前值是否为空」,不能拿「通常能从目录名得到」当豁免。
     *
     * 归档语义:UI 需在卷列表、卷头、计划卡三处一致展示(规格 §4.7)。
     * 返回 [Msg] 而非成品字符串 —— 见 `text/Msg.kt`。
     */
    val pendingIssues: List<Msg>
        get() = PENDING_FIELD_ORDER.mapNotNull { pendingIssueOf(it) }

    /** 是否有待确认项。 */
    val needsConfirmation: Boolean get() = pendingIssues.isNotEmpty()

    val isOverridden: Boolean get() = overridden.isNotEmpty()
}

/**
 * 页头汇总里待确认项的**展示顺序**:与 `ComicInfo.build` 的标签序一致
 * (Title → Series → Number → Writer,`Number` 与 `Writer` 互换以保持「标量在前」)。
 *
 * 只影响汇总行的排列;字段级红警由 [ComicVolume.pendingIssueOf] 各自决定。
 */
private val PENDING_FIELD_ORDER: List<MetaField> = listOf(
    MetaField.Title,
    MetaField.Series,
    MetaField.Volume,
    MetaField.Writer,
)

