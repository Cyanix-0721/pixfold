package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.MetaField
import com.cyanix.pixfold.domain.model.MetaValue
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.sort.pageOrderOf

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
 *
 * **为什么这里不返回 [com.cyanix.pixfold.domain.text.Msg]**(全量 i18n 的例外):
 * 返回值是**要写进磁盘的文件名**(`ComicVolume.cbzFileName`),不是界面文案 ——
 * 它参与 `"${outputDir}/${cbzFileName}"` 的路径相等比较与 `skipReason` 的拼装。
 * 若随界面语言变化,同一批图片在不同语言下会产出**文件名不同的 CBZ**,
 * 且切换语言后"输出已存在"的判断会与上一次执行的结果对不上。
 * 故文件名后缀(如「第 02 卷」)保持**与界面语言无关的固定形态**;
 * 若日后要本地化文件名风格,那是一次**输出命名策略**决策,须单独裁决。
 */
fun defaultCbzName(
    id: String,
    titleSug: Suggestion<String>,
    volumeSug: Suggestion<Int?>,
    cbzBaseSug: Suggestion<String> = Suggestion(null, ""),
): String {
    // 基名优先用独立建议(CBZ 名要保留作者前缀),没有才退回 title。
    val base = cbzBaseSug.value?.takeIf { it.isNotEmpty() } ?: titleSug.value ?: id
    val t = base.replace(ILLEGAL_FOR_FILE_NAME, "_")
    val v = volumeSug.value
    return if (v == null) "$t.cbz" else "$t 第${v.toString().padStart(2, '0')}卷.cbz"
}

/**
 * 直接从一卷算出它的**默认 CBZ 文件名**。
 *
 * 存在的唯一理由：让「界面上的提示」与「点恢复建议值后的实际值」调**同一个入口**。
 * 2026-10-19 真实缺陷就是这么来的 —— 提示行自己拼了 `titleSug`，而恢复走
 * [defaultCbzName]（用 `cbzBaseSug`，保留 `[作者]` 前缀），带作者前缀的目录上
 * 两者不同，用户照着提示改反而改错。
 *
 * 因此凡是要「显示默认名」的地方一律调本函数，**不要自己拼**；
 * [useSuggestion] 也走它，两处不会分叉。
 */
fun defaultCbzName(volume: ComicVolume): String =
    defaultCbzName(volume.id, volume.titleSug, volume.volumeSug, volume.cbzBaseSug)


/**
 * 语言选项 → ComicInfo.xml 的 `LanguageISO` 值(规格 §8.4)。
 *
 * `null` 表示**不写入标签** —— 即 [LangChoice.Unset],这是合法终态而非「待填」。
 */
fun langIso(choice: LangChoice): String? = when (choice) {
    LangChoice.Zh -> "zh"
    LangChoice.Ja -> "ja"
    LangChoice.Unset -> null
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
    pages: List<com.cyanix.pixfold.domain.model.SourceItem>,
    titleSug: Suggestion<String>,
    seriesSug: Suggestion<String>,
    writerSug: Suggestion<String>,
    volumeSug: Suggestion<Int?>,
    langSug: Suggestion<String>,
    outputDirSug: String,
    outputExists: Boolean = false,
    /** 仅由目录结构推导的作者建议(不受 ComicInfo 影响),见 [ComicVolume.dirWriterSug]。 */
    dirWriterSug: Suggestion<String> = Suggestion(null, ""),
    /**
     * 仅由目录结构推导的 Title / Series 建议(不受 ComicInfo 影响),
     * 见 [ComicVolume.dirTitleSug] / [ComicVolume.dirSeriesSug]。
     *
     * 默认取传进来的 `titleSug` / `seriesSug`:无 ComicInfo 时目录结构就是唯一建议源,
     * 两者本就同值,不必让每个调用点重复传一遍。有 ComicInfo 的路径
     * (`applyExistingComicInfo`)会各自写入被覆盖前的目录值。
     */
    dirTitleSug: Suggestion<String> = titleSug,
    dirSeriesSug: Suggestion<String> = seriesSug,
    cbzBaseSug: Suggestion<String> = Suggestion(null, ""),
    /**
     * 扫描层的系列目录名;单本卷留空。
     *
     * **与 [seriesSug] 是两件事**:建议值可能被条①「跟随 Title」填上标题,而这个
     * 字段只回答「它扫进来时是否在某个系列目录下」—— 分组与 `isSeries` 都只看它。
     */
    seriesDir: String = "",
): ComicVolume = ComicVolume(
    id = id,
    dirPath = dirPath,
    pages = pages,
    titleSug = titleSug,
    seriesSug = seriesSug,
    writerSug = writerSug,
    volumeSug = volumeSug,
    langSug = langSug,
    outputDirSug = outputDirSug,
    outputExists = outputExists,
    dirWriterSug = dirWriterSug,
    dirTitleSug = dirTitleSug,
    dirSeriesSug = dirSeriesSug,
    title = titleSug.value ?: "",
    series = seriesSug.value ?: "",
    writer = writerSug.value ?: "",
    volume = volumeSug.value,
    language = LangChoice.Unset,
    cbzBaseSug = cbzBaseSug,
    seriesDir = seriesDir,
    cbzFileName = defaultCbzName(id, titleSug, volumeSug, cbzBaseSug),
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
            volume.copy(language = it.choice)
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
        MetaField.Language -> volume.copy(language = LangChoice.Unset)
        // 与界面提示行调**同一个**入口 —— 两处各拼一次必然分叉（2026-10-19 真实缺陷）。
        MetaField.CbzName -> volume.copy(cbzFileName = defaultCbzName(volume))
        MetaField.OutputDir -> volume.copy(outputDir = volume.outputDirSug)
    }
    return next.copy(overridden = next.overridden - field)
}

/**
 * **按目录结构自动获取**:把 `dir*` 快照(纯目录推导,不受 `ComicInfo.xml` 影响)一次性
 * 写进**当前卷全部字段**。
 *
 * ## 为什么与「恢复建议值」并列存在
 *
 * 建议值的优先级是「ComicInfo 最高,其次目录层级」(用户 2026-10-21 裁决)。所以当
 * `ComicInfo.xml` 写了作者/标题/系列时,`writerSug` / `titleSug` / `seriesSug` 会被
 * **文件值**顶掉 —— 此时「恢复建议值」回到的是文件值,不是目录名。
 * 用户在文件里写错、或者想按文件夹重新来一遍时,就需要这条**绕开 ComicInfo 的退路**。
 *
 * 只保留目录快照而不复用 `*Sug`,正是为了让这两件事在类型上就分得开:
 * `*Sug` = 建议值(可能来自文件),`dir*Sug` = 目录推导(永远来自文件夹)。
 *
 * ## 覆盖哪些字段
 *
 * [MetaField.Title] / [MetaField.Series] / [MetaField.Writer] 取 `dir*Sug`;
 * [MetaField.Volume] 取 [ComicVolume.volumeSug](卷号本来就只可能来自目录名);
 * [MetaField.CbzName] / [MetaField.OutputDir] 取各自的推导值;
 * [MetaField.Language] 回 [LangChoice.Unset] —— 目录名里没有可靠的语言信息,
 * 这里**不猜**,保持"未设置"这个合法终态。
 *
 * ## 为什么打逐项例外标记
 *
 * 用户的意图是「就要目录里这一套」,重扫时不该被 `ComicInfo.xml` 又改回去。
 * 走 [setMetaValue] 逐字段写入即可同时拿到标记(与人工编辑同一语义)。
 */
fun applyDirMetaSuggestions(volume: ComicVolume): ComicVolume {
    var next = volume
    for (field in DIR_META_FIELDS) {
        next = when (field) {
            MetaField.Title -> next.copy(title = next.dirTitleSug.value.orEmpty())
            MetaField.Series -> next.copy(series = next.dirSeriesSug.value.orEmpty())
            MetaField.Writer -> next.copy(writer = next.dirWriterSug.value.orEmpty())
            MetaField.Volume -> next.copy(volume = next.volumeSug.value)
            MetaField.Language -> next.copy(language = LangChoice.Unset)
            MetaField.CbzName -> next.copy(cbzFileName = defaultCbzName(next))
            MetaField.OutputDir -> next.copy(outputDir = next.outputDirSug)
        }.let { it.copy(overridden = it.overridden + field) }
    }
    return next
}

/**
 * [applyDirMetaSuggestions] 覆盖的字段集合。
 *
 * 抽成常量而不是在循环里直接写 `MetaField.entries`:将来新增字段时,
 * **必须显式决定它是否属于「按目录结构获取」**,而不是被 `entries` 悄悄带进来
 * (新增字段未必有目录推导的来源)。
 */
private val DIR_META_FIELDS: List<MetaField> = listOf(
    MetaField.Title,
    MetaField.Series,
    MetaField.Writer,
    MetaField.Volume,
    MetaField.Language,
    MetaField.CbzName,
    MetaField.OutputDir,
)

/** [batchApply] 的结果:**实际生效**与**被逐项例外跳过**的卷数,以及更新后的整库。 */
data class BatchApplyResult(
    val volumes: List<ComicVolume>,
    val appliedCount: Int,
    val skippedCount: Int,
)

/**
 * 批量设置(规格 §8.2 / §6.3 约束 4 / 验收第 13 项)。
 *
 * - 只作用于 `included == true` 的卷(未勾选的卷不参与打包,也不该被批量改写);
 * - **跳过** [ComicVolume.overridden] 中已含该字段的卷,除非 [force];
 * - 写入后**清除该字段自己的逐项例外标记**(用户裁决 2026-09-25,见下);
 * - 返回[实际生效][BatchApplyResult.appliedCount]与[被跳过][BatchApplyResult.skippedCount]数量
 *   —— UI 据此提示 `已设置到 N 卷`,而不是笼统说"已应用"。
 *
 * **为什么强制要"返回实际生效数量"**:批量设置最危险的失败模式是"以为全改了,
 * 其实一半是逐项例外"。只报"成功"会让用户在计划页才发现差异。
 *
 * **为什么批量写入要清掉标记**(§11 第 15 条):约束 4 的分工是「批次统一 + **逐项**例外」,
 * 「逐项」指的是**逐卷手工编辑**,不包含批量本身。若批量也记标记,第二次对同一字段批量
 * 就会被自己上一次写下的标记挡住(`applied=0 / skipped=5`),用户看到的是"我只是批量设了一次,
 * 怎么第二次就设不动了"。清除只针对**本次写入的那个字段**,其它字段的逐项例外原样保留;
 * 而批量之后的手工编辑会重新写下标记,继续受保护。
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
        // 清掉本字段的标记:批量是"批次统一",不该把自己变成逐项例外。
        // 类型不匹配时 setMetaValue 原样返回同一个实例,此时什么也没写,标记也不该动。
        val written = setMetaValue(v, field, value)
        if (written === v) v else written.copy(overridden = v.overridden - field)
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
