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
fun parseComicInfo(xml: String): ComicInfoData? = parseComicInfo(xml, DocumentBuilderFactory.newInstance())

/**
 * [parseComicInfo] 的可注入版本 —— 只供测试喂「加固项一个都不支持」的工厂。
 *
 * 生产路径永远走无参重载。把它 `internal` 而不是公开，是为了不让调用方误以为
 * 「换一个工厂」是受支持的行为。
 */
internal fun parseComicInfo(xml: String, factory: DocumentBuilderFactory): ComicInfoData? = runCatching {
    // 文本层先拒 DOCTYPE：feature 一个都设不上时（见 `harden` 的说明）这是**唯一**的
    // XXE 防线，所以它不能依赖工厂能力。
    if (hasDoctype(xml)) return null
    factory.harden()
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
 * 文本层检测 `<!DOCTYPE`（大小写不敏感）。
 *
 * ## 为什么不能只靠 `disallow-doctype-decl`
 *
 * 那条 feature 是 **Xerces 专有**的。Android 的 XML 实现是 Harmony
 * `DocumentBuilderFactoryImpl`，对不认识的 feature 会抛
 * `ParserConfigurationException`；即使它不抛、只是静默忽略，防线也就没了。
 * 2026-10-27 真机缺陷正是「设置 feature 失败 ⇒ `runCatching` 吞成 null ⇒ 整份 XML
 * 读不出来」，所以这里改用**与解析器能力无关**的判据。
 *
 * ## 为什么可以粗暴地拒绝
 *
 * ComicInfo.xml 从不需要 DTD —— 它是一份固定五个标签的平铺文档。误杀一份带 DTD 的
 * 第三方包，后果是「当前值留空、建议值可用」，远小于 XXE 或「整包读不出」。
 */
private fun hasDoctype(xml: String): Boolean = xml.contains("<!doctype", ignoreCase = true)

/**
 * 尽可能加固 `DocumentBuilderFactory`；**任何一项设不上都继续往下走**。
 *
 * ## 为什么逐项 try 而不是一把梭
 *
 * 每个 `setFeature` 都可能抛 `ParserConfigurationException`（规范允许「不认识就抛」）。
 * 原先五连设写在 `runCatching` 里，任何一条抛都会让**整个解析**失败 —— 安全加固把功能
 * 弄坏了。这里改成逐项独立，能设上的设上，设不上的记下来（调用方不需要知道）。
 *
 * 剩下的防线不止一条：`hasDoctype` 已在文本层拒掉 DTD，本函数的
 * `isExpandEntityReferences = false` 再挡一层实体展开。
 */
private fun DocumentBuilderFactory.harden() {
    // 每一项都独立：设不上不影响其余项，更不影响后面的解析。
    runCatching { isNamespaceAware = true }
    runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { isXIncludeAware = false }
    runCatching { isExpandEntityReferences = false }
}

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
 * 把「从 CBZ 里读出来的那一条 `ComicInfo.xml`」翻译成 [ComicInfoData] —— **三态**。
 *
 * ## 三态是本函数存在的全部理由
 *
 * | 情况 | 返回 | 调用方行为 |
 * | --- | --- | --- |
 * | 包里没有 `ComicInfo.xml` | `ComicInfoData()` 空对象 | 当前值清空（用户裁决「没写就默认是空的」） |
 * | 条目在、能解析 | 解析结果 | 当前值取文件值 |
 * | 条目在、**解析不出来** | `null` | 什么都不改，保持建议值 |
 *
 * ## 为什么第三条不能也返回空对象（2026-10-27 真机缺陷）
 *
 * 旧实现是 `xml?.let { parseComicInfo(it) } ?: ComicInfoData()` —— 把「有 XML 但读不懂」
 * 与「根本没有 XML」混成同一个空对象。真机上 `parseComicInfo` 因为安全加固项设不上而
 * 整体失败，于是**每一个有元数据的包都被当成空包**：元数据页当前值全空，用户点一次
 * 「更新元数据」就把 295 B 的 XML 写成 232 B 的空标签，**静默丢掉全部元数据**。
 *
 * 「读不懂」的正确语义是「这次不知道，别动」。宁可让界面显示上一次的值，也不能
 * 用「空」覆盖文件里真实存在的内容 —— 前者可以重试，后者要用户手动补。
 *
 * @param xml 条目全文；`null` 表示包里没有这个条目
 */
fun comicInfoOfEntry(xml: String?): ComicInfoData? =
    if (xml == null) ComicInfoData() else parseComicInfo(xml)

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

/**
 * 用一份**刚写进磁盘的** `ComicInfo.xml` 回填当前值（工作流 C 成功后调用）。
 *
 * ## 与 [applyExistingComicInfo] 的关系
 *
 * 规则**逐条相同**：只改当前值、建议值一个字不动、缺失标签把当前值留空而不是落回
 * 建议值、`cbzFileName` / `outputDir` / `overridden` 都不碰。
 * 差别只在数据来源：那边是探针去读文件，这里 XML 全文已经在手里。
 *
 * ## 为什么不去重读磁盘
 *
 * 刚写的就是它。重读要多打开一次整包（`ZipInputStream` 扫到 ComicInfo 为止），
 * 换来的是「可能读到一半被别的应用改了」的不确定性 —— 花了成本买回一个风险。
 *
 * ## 解析失败
 *
 * 原样返回。最坏是界面继续显示旧值，好过把五个字段一起清空。
 */
fun withComicInfoValues(volume: ComicVolume, xml: String): ComicVolume {
    val data = parseComicInfo(xml) ?: return volume
    return volume.copy(
        title = data.title.orEmpty(),
        series = data.series.orEmpty(),
        writer = data.writer.orEmpty(),
        volume = data.number,
        language = data.language,
        // 建议值（titleSug / seriesSug / writerSug / dir*Sug）与 overridden / cbzFileName /
        // outputDir 全部不传 —— copy 的默认语义就是保留，「恢复建议值」按钮因此仍然
        // 回到目录结构推导的结果，与无 ComicInfo 时一致。
    )
}
