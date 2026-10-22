package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.LangChoice
import com.cyanix.pixfold.domain.model.Suggestion
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 从**已有** `ComicInfo.xml` 读出来的元数据 —— 「已有值」，不是「建议值」。
 *
 * ## 为什么必须与 `Suggestion` 分开
 *
 * 两者来源不同、可信度不同、失效条件也不同：
 *
 * - **建议值**(`ComicVolume.titleSug` 等)来自**目录结构与过滤规则**，每次扫描都能
 *   重算，所以刷新后应该跟着变；
 * - **已有值**(本类)来自用户上次写进 CBZ 的 `ComicInfo.xml`，一旦存在就是**事实**，
 *   不该被目录名重新推导覆盖。
 *
 * 用户 2026-10-20 的裁决原话：「Series 默认根据 series 文件夹取名，元数据页的内容
 * 如果 ComicInfo.xml 没写就默认是空的，但是恢复建议是通过文件夹结构和过滤规则获取的」
 * —— 说的正是这两条来源要**各走各的**：当前值取文件，恢复建议取文件夹。
 *
 * ## 字段为 null 的含义
 *
 * `null` = **文件里没写这个标签**(或写成了自闭合空标签)，不是「空字符串」。
 * 调用方据此把当前值落成空串 —— 用户清空过就是清空过，不许拿建议值回填。
 */
data class ComicInfoData(
    val title: String? = null,
    val series: String? = null,
    val number: Int? = null,
    val writer: String? = null,
    val language: LangChoice = LangChoice.Unset,
) {
    /** 五个字段全空 —— 等于「文件在但没写任何东西」。 */
    val isEmpty: Boolean
        get() = title == null && series == null && number == null &&
            writer == null && language == LangChoice.Unset
}

/**
 * 解析 `ComicInfo.xml` 文本；**不是**合法 ComicInfo 时返回 `null`。
 *
 * ## 为什么不抛异常
 *
 * 这是**扫描路径**上的一环：一个坏掉的 CBZ 不该让整个工作区扫不出来。
 * 「读不懂」与「没写过」对调用方的效果相同(都回退到建议值)，故统一成 `null`。
 *
 * ## 为什么用 `DocumentBuilderFactory` 而不是手写正则
 *
 * 我们**自己写出的** XML 格式固定，但读的是**别人的**包：字段顺序可能不同、可能带
 * 命名空间前缀、可能有属性、可能整段用 CDATA。正则只对自家格式成立，遇上第三方包
 * 会静默读空 —— 而「静默读空」在这里的后果是**用户的元数据被建议值覆盖**。
 *
 * 安全：禁用 DTD 与外部实体(XML 外部实体注入)。ComicInfo 从不需要 DTD。
 */
fun parseComicInfo(xml: String): ComicInfoData? = runCatching {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // 三连：禁止 DTD / 禁止外部实体 / 禁止 XInclude —— 我们只需要元素文本。
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
    }
    val doc = factory.newDocumentBuilder()
        .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    val root = doc.documentElement ?: return null
    if (!root.nodeName.endsWith("ComicInfo", ignoreCase = true)) return null
    ComicInfoData(
        title = root.textOf("Title"),
        series = root.textOf("Series"),
        number = root.textOf("Number")?.toIntOrNull(),
        writer = root.textOf("Writer"),
        language = langChoiceOf(root.textOf("LanguageISO")),
    )
}.getOrNull()

/**
 * 读子元素的文本；**缺失或空白都算 `null`**。
 *
 * 「`<Title />`」与「没有 `<Title>`」在语义上都是「没写」，两者都必须落到 `null`，
 * 否则调用方无法区分「用户清空了」和「文件没这字段」—— 而对本函数的使用者来说，
 * 这两种情况要做的都是同一件事：当前值留空。
 */
private fun Element.textOf(tag: String): String? {
    val nodes = getElementsByTagName(tag)
    if (nodes.length == 0) return null
    val text = nodes.item(0)?.textContent?.trim()
    return text?.takeIf { it.isNotEmpty() }
}

/**
 * `LanguageISO` → [LangChoice]；认不出的码一律 [LangChoice.Unset]。
 *
 * 只有 zh / ja 两档(用户 2026-10-20 裁决)。写 `en`、`ko`、`zh-CN` 之类的包读回来是
 * `Unset` —— 界面会显示「不写入标签」，用户若在意可以手动改成 zh/ja。**不猜**：
 * 把 `zh-CN` 猜成 `Zh` 会让下次打包把标签写成 `zh`，悄悄改掉用户原来的值。
 */
private fun langChoiceOf(raw: String?): LangChoice = when (raw?.lowercase()) {
    "zh" -> LangChoice.Zh
    "ja" -> LangChoice.Ja
    else -> LangChoice.Unset
}

/**
 * 读「该卷已有 CBZ 里的 `ComicInfo.xml`」—— **平台端口**。
 *
 * 返回 `null` = 没有已有 CBZ、或里面没有可读的 ComicInfo.xml。
 * 实现方**不得抛异常**：扫描路径上一个坏包不该让整个工作区扫不出来。
 *
 * 参数给整卷而不是给路径，是因为「已有 CBZ 在哪」由 [ComicVolume.outputDir] +
 * [ComicVolume.cbzFileName] 决定，而那两样只有领域层知道怎么拼。
 */
fun interface ExistingComicInfoProbe {
    fun read(volume: ComicVolume): ComicInfoData?
}

/**
 * 把已有 `ComicInfo.xml` 的值填进**当前值**，建议值一个字都不动。
 *
 * ## 为什么只改当前值
 *
 * 用户 2026-10-20 裁决：「元数据页的内容如果 ComicInfo.xml 没写就默认是空的，但是
 * 恢复建议是通过文件夹结构和过滤规则获取的」。所以：
 *
 * - **当前值** ← 已有值（有就取，没有就留空 —— 注意是**空**，不是建议值）；
 * - **建议值**（`titleSug` / `seriesSug` / …）← 原样保留，仍由目录结构算；
 * - 「恢复建议值」按钮因此仍然把字段还原成**文件夹推导**的结果，与无 ComicInfo 时一致。
 *
 * ## 为什么已有值缺失时当前值要留空而不是落建议值
 *
 * 「ComicInfo.xml 没写」是用户明确裁决的语义：没写就是空的。若此时回填建议值，
 * 那么「文件里有 `<Title>X</Title>`」与「文件里没 Title」在界面上就分不出来了 ——
 * 而用户想知道的恰恰是「我上次到底写没写」。
 *
 * ## 一处刻意的例外：`cbzFileName` 不动
 *
 * 输出文件名仍由 [com.cyanix.pixfold.domain.comic.defaultCbzName] 从**建议值**算。
 * 理由：文件名是「这次要写到哪」，不是元数据；而用户此前正是在这里踩过坑
 * (同名两卷静默互相覆盖)。文件名跟着建议值走，撞车由 `PackPreflight` 的
 * `OutputNameCollision` 阻断，链路单一。
 */
fun applyExistingComicInfo(
    volumes: List<ComicVolume>,
    probe: ExistingComicInfoProbe,
): List<ComicVolume> = volumes.map { volume ->
    // `null` = 没有已有 CBZ(保持建议值);非 null 但字段全空 = 文件在、标签没写
    // ⇒ **当前值清空**(用户裁决「ComicInfo.xml 没写就默认是空的」)。
    val existing = probe.read(volume) ?: return@map volume

    // 建议值也以文件为准（用户 2026-10-21 裁决「总之优先级都是 ComicInfo 最高，
    // 其次目录层级」）。文件里**没写**的字段一个字都不动，仍由目录结构推导 ——
    // 两者的来源会如实写在 `source` 里，界面上分得清「这个建议从哪来」。
    val titleSug = existing.title?.let { Suggestion(it, COMIC_INFO_SOURCE) } ?: volume.titleSug
    val writerSug = existing.writer?.let { Suggestion(it, COMIC_INFO_SOURCE) } ?: volume.writerSug
    // 单本（扫描层没有系列目录）的 Series 建议**跟随 Title** —— 用户裁决
    // 「单本 series 建议值跟随 ComicInfo 后一个选取 Title，体现在恢复建议后和
    // Title 一致」。取的是上面刚算出的 titleSug，所以文件里的 Title 一旦存在，
    // Series 也跟着它，两者恢复建议后必然一致。
    val seriesSug = when {
        existing.series != null -> Suggestion(existing.series, COMIC_INFO_SOURCE)
        volume.seriesDir.isEmpty() -> Suggestion(titleSug.value, volume.seriesSug.source)
        else -> volume.seriesSug
    }
    volume.copy(
        title = existing.title.orEmpty(),
        series = existing.series.orEmpty(),
        writer = existing.writer.orEmpty(),
        volume = existing.number,
        language = existing.language,
        titleSug = titleSug,
        seriesSug = seriesSug,
        writerSug = writerSug,
        // `dirWriterSug` / `dirTitleSug` / `dirSeriesSug` 刻意**不动**:它们是
        // 「按目录结构获取」的退路值(见 `applyDirMetaSuggestions`),一旦这里被
        // ComicInfo 覆盖,用户就没有任何办法回到目录名推导出的那一套了。
        // 注意 copy 不传即保留 —— 三者在 `comicVolumeOf` 构造时就已经落盘。
    )
}

/** 建议值来源：已有 `ComicInfo.xml`。与目录层级的来源字符串并列，界面据此如实展示。 */
const val COMIC_INFO_SOURCE: String = "已有 ComicInfo.xml（优先级高于目录层级）"
