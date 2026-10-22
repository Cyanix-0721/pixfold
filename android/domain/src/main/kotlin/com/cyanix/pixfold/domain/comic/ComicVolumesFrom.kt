package com.cyanix.pixfold.domain.comic

import com.cyanix.pixfold.domain.model.ComicVolume
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.model.Suggestion
import com.cyanix.pixfold.domain.naming.DerivedNames
import com.cyanix.pixfold.domain.naming.derivedNamesOf
import com.cyanix.pixfold.domain.pack.PageByteSource
import java.io.InputStream

/**
 * 把工作区扫描结果折成工作流 B 的卷列表 —— **真实素材进打包链的唯一入口**。
 *
 * ## 为什么要有这一层
 *
 * 工作流 A 的产物是 [ImageCollection](每个直接含图片的目录 = 一本),工作流 B 的
 * 输入是 [ComicVolume](带元数据建议、输出目录、页序)。此前 `ComicVolume` **只由
 * mock 构造**(`MockDataB`),所以「打包」点下去走的是 `executePackagePlans` 的
 * **确定性模拟** —— 报告里一条条"成功",磁盘上一个字节都没有。
 *
 * 接真实文件时缺的正是这条转换:扫描结果里没有任何元数据建议,而
 * [ComicVolume] 的 `titleSug`/`seriesSug`/... 是**不可为空**的字段。
 *
 * ## 建议值的来源与置信度
 *
 * 真实素材上唯一可靠的层级信息就是**目录结构**:
 *
 * ```
 * <工作区根>/<系列>/<卷目录>/<页>.jpg     ← 常见形态
 * <工作区根>/<卷目录>/<页>.jpg            ← 单本形态
 * ```
 *
 * - `series` ← `ImageCollection.seriesName`(扫描层已算好,为空表示无系列);
 * - `title` ← **卷目录名**(一律,不看有没有系列)。2026-10-19 真机实测改判:原先是
 *   「有系列取系列名」,理由是避免默认文件名出现「第02卷 第02卷」;但真实素材
 *   (`[Cior (Ken-1)] …で` 与 `…で――2 [DL版]` 同系列两本)当场暴露了代价 ——
 *   两卷 title 都是系列名 ⇒ `cbzFileName` 相同 ⇒ **后一卷静默覆盖前一卷**,
 *   报告还显示「成功 2」,磁盘只剩 1 个文件。避免重复卷号是小事,丢数据是大事。
 *   卷目录名在文件管理器里就是用户认的那本书,取它最不容易错;
 *   系列信息不会丢,仍在 `series` 字段里;
 * - `volume` ← **从卷目录名里解析**(`第01卷` / `v01` 等);解析不出就是 `null`,
 *   `pendingIssues` 会自动要求用户确认 —— **建议 ≠ 事实**,解析不出时不许瞎猜;
 * - `writer` / `language` ← **一律无建议**(`Suggestion(null, "")`)。目录名里没有可靠
 *   的作者与语言信息,硬猜会让用户在批量设置里被错误值误导;`langSug = null` 时
 *   `ComicVolume` 的语言仍是 [com.cyanix.pixfold.domain.model.LangChoice.Unset],
 *   正合「语言必须人工确认」的硬语义。
 *
 * [Suggestion.source] 是给人看的**来源说明**,必须如实 —— 写成「目录名解析」就真的
 * 只能是目录名解析出来的,不能拿它当"看起来更专业"的填充。
 *
 * ## 输出目录
 *
 * 输出落在**源卷目录的同级**,即 `parentOf(卷目录)`。不写到工作区根:真实素材的
 * 工作区根下往往还有别的系列,把 CBZ 堆在根上会污染用户的目录结构。
 *
 * @param collections 工作区扫描出来的集合(每个集合 = 一本)
 * @param rootDisplayName 工作区根目录显示名,用作输出目录兜底
 * @param outputExists 判断「该卷输出文件是否已存在」。**必填且显式传入** ——
 *   领域层不碰文件系统,而 `VolumePackPlan.overwritesExisting` 的正确性完全依赖它
 *   (漏判会让界面不警告覆盖、撤销却按新建删掉,正是缺陷 R3)。
 */
fun comicVolumesFrom(
    collections: List<ImageCollection>,
    rootDisplayName: String,
    outputExists: OutputExistsProbe,
): List<ComicVolume> = collections.map { collection ->
    // 全部展示形态从集合的派生属性取 —— 清洗规则的**唯一**实现是
    // `com.cyanix.pixfold.domain.naming.NameCleaning`,这里不重算一遍。
    // `derived` = 卷目录名推出的(title / cbzBase / writer),`seriesDerived` = 系列目录名推出的。
    val own: DerivedNames = collection.derived
    val seriesOf: DerivedNames = collection.seriesDerived
    val hasSeries = collection.seriesName.isNotEmpty()

    // 目录名解析出的卷号,解析不出就是 null —— 不许拿集合在列表里的下标兜底,
    // 那会让「删掉第 2 卷」导致第 3 卷变成第 2 卷。
    val parsedVolume = parseVolumeNumber(collection.name)

    // title 与 CBZ 基名**刻意不同**:title 剥作者前缀(元数据里作者另写 <Writer>),
    // CBZ 基名保留(文件管理器里认得出是谁的)。
    val titleSug: Suggestion<String> = Suggestion(own.title, TITLE_SOURCE_DIR)
    val cbzBaseSug: Suggestion<String> = Suggestion(own.cbzBase, CBZ_SOURCE_DIR)

    // 作者取自**外层**(有系列时 = 系列目录名),与旧脚本 derive_metadata 一致:
    // 两层结构里作者写在系列文件夹上,内层只是本卷标题。
    val writerSource = if (hasSeries) seriesOf else own

    // 系列名同样要清洗(`[Cior (Ken-1)]` 这类前缀不该出现在 Series 元数据里)。
    // 2026-10-20 用户裁决(Series 三条语义):无系列层时**跟随 Title**、作者前缀与
    // 工作区文件夹名一致时**取工作区名** —— 判定树见 [seriesSuggestionOf]。
    val seriesSug: Suggestion<String> = seriesSuggestionOf(collection, rootDisplayName)
    val volumeSug: Suggestion<Int?> = if (parsedVolume == null) {
        Suggestion(null, NO_SUGGESTION_SOURCE)
    } else {
        Suggestion(parsedVolume, VOLUME_SOURCE_DIR)
    }

    val volume = comicVolumeOf(
        id = collection.id,
        dirPath = collection.id,
        pages = collection.images,
        titleSug = titleSug,
        seriesSug = seriesSug,
        // 作者只从 `[作者]` 前缀取,**取不到就不给建议**(不硬猜);
        // 语言在目录名里没有任何可靠信息 ⇒ 永远不给建议。
        // 2026-10-21 用户裁决:「如果没有 ComicInfo 让你获取 writer,就默认获取去除 [] 的」。
        // 于是 writerSug 直接给**清理后**形态。原先的「一键清理」按钮在同一路径上
        // 因此恒等而变冗余,已于 2026-10-22 删除 —— 改为「按目录结构获取」([dirWriterSug]),
        // 它绕开 ComicInfo 建议值,是文件里作者写错时的退路。
        writerSug = writerSource.writerCleaned.takeIf { it.isNotEmpty() }?.let {
            Suggestion<String>(it, WRITER_SOURCE_CLEANED)
        } ?: Suggestion<String>(null, NO_SUGGESTION_SOURCE),
        // 「按目录结构获取」的退路值:上面的 writerSug 会被 ComicInfo 顶掉,这份不会。
        dirWriterSug = writerSource.writerCleaned.takeIf { it.isNotEmpty() }?.let {
            Suggestion<String>(it, WRITER_SOURCE_CLEANED)
        } ?: Suggestion<String>(null, NO_SUGGESTION_SOURCE),
        volumeSug = volumeSug,
        langSug = Suggestion<String>(null, NO_SUGGESTION_SOURCE),
        outputDirSug = parentDirOf(collection.id, rootDisplayName),
        outputExists = false,
        cbzBaseSug = cbzBaseSug,
        // 扫描层的系列目录名:分组与 `isSeries` 只认它(见 ComicVolume.seriesDir)。
        seriesDir = collection.seriesName,
    )
    // outputExists 需要拿到「输出名」才知道问谁，故在两段之后补。
    volume.copy(outputExists = outputExists.exists(volume))
}

/**
 * 输出目录 = 卷目录的**父目录**(相对工作区根时用根显示名兜底)。
 *
 * 例:`系列A/第01卷` ⇒ `系列A`;`单本` ⇒ `<工作区根>`。
 */
private fun parentDirOf(collectionId: String, rootDisplayName: String): String {
    val parent = collectionId.substringBeforeLast('/', "")
    return parent.ifEmpty { rootDisplayName }
}

// ---- Series 建议判定树(2026-10-20 用户裁决「Series 三条语义」) ----

/**
 * Series 建议的取值判定,按序判定、取先命中者:
 *
 * 1. **有系列层** → 清洗后的系列目录名(原行为,来源「目录层级 L2」);
 * 2. **卷目录作者前缀与工作区文件夹名一致** → 清洗后的工作区文件夹名
 *    (工作区选在系列文件夹上、卷目录名仍带系列名前缀的形态,来源如实写「工作区文件夹名」);
 * 3. **其余(单本/不一致/无前缀)** → 跟随 Title —— 恢复建议点下去后 Series 与 Title 一致,
 *    不再给「无建议」;单行本把 `<Series>` 写成自己的标题是阅读器的常见常态。
 *
 * 「作者前缀一致」的判据:两边都取 `[...]` 原始前缀比对(不比清洗值 —— 清洗会剥掉
 * 内部括号,`[社团 (画师)]` 这种署名会被误判成不一致)。
 */
private fun seriesSuggestionOf(
    collection: ImageCollection,
    rootDisplayName: String,
): Suggestion<String> {
    if (collection.seriesName.isNotEmpty()) {
        return Suggestion(collection.seriesDerived.title, SERIES_SOURCE_DIR)
    }
    val ownWriterRaw = collection.derived.writerRaw
    val rootWriterRaw = derivedNamesOf(rootDisplayName).writerRaw
    if (ownWriterRaw.isNotEmpty() && ownWriterRaw == rootWriterRaw) {
        return Suggestion(derivedNamesOf(rootDisplayName).title, SERIES_FROM_WORKSPACE_SOURCE)
    }
    return Suggestion(collection.derived.title, SERIES_FOLLOWS_TITLE_SOURCE)
}

// ---- 卷号解析 ----

/**
 * 目录名里的卷号形态(按优先级)。
 *
 * 只认**明确的**卷号标记:`第01卷` / `第1巻`(日文汉字) / `v01` / `vol.1` / `Vol 1`。
 * **不认**裸数字(如目录名 `01`),理由是裸数字歧义太大 —— `2024` 可能是年份、
 * `1` 可能是"第一本"也可能是系列号;解析错了比解析不出更糟(用户会直接采纳)。
 */
private val VOLUME_PATTERNS = listOf(
    Regex("""第\s*(\d{1,4})\s*[卷巻]"""),
    Regex("""[Vv][Oo][Ll][.\s_]*(\d{1,4})"""),
    Regex("""(?<![A-Za-z])[Vv](\d{1,4})(?![0-9])"""),
    Regex("""(?<![0-9])(\d{1,4})\s*[卷巻]"""),
)

/**
 * 从目录名解析卷号;解析不出返回 `null`。
 *
 * `null` 会让 `ComicVolume.pendingIssues` 报「卷号缺失」,UI 必须让用户确认 ——
 * 这正是「建议 ≠ 事实」在真实素材上的落点。
 */
fun parseVolumeNumber(dirName: String): Int? {
    for (pattern in VOLUME_PATTERNS) {
        val raw = pattern.find(dirName)?.groupValues?.get(1) ?: continue
        // 上界挡住 `第9999卷` 这种明显是解析事故的值。
        val value = raw.toIntOrNull() ?: continue
        if (value in 1..MAX_VOLUME_NUMBER) return value
    }
    return null
}

/** 卷号上界。真实漫画单系列极少超过这个数,超过基本是解析到了别的数字。 */
const val MAX_VOLUME_NUMBER: Int = 9999

// ---- 来源说明(给人看的,必须如实) ----

private const val TITLE_SOURCE_DIR = "目录层级（系列层或卷目录名）"
private const val SERIES_SOURCE_DIR = "目录层级 L2（系列层）"
private const val SERIES_FOLLOWS_TITLE_SOURCE = "无系列层，跟随 Title"
private const val SERIES_FROM_WORKSPACE_SOURCE = "工作区文件夹名（卷目录 [作者] 前缀与其一致）"
private const val CBZ_SOURCE_DIR = "目录名清洗（保留 [作者] 前缀，去括号原作与末尾标注）"
private const val WRITER_SOURCE_CLEANED = "目录名开头的 [作者] 前缀（已去掉方括号）"
private const val VOLUME_SOURCE_DIR = "目录名「第NN卷」解析"
private const val NO_SUGGESTION_SOURCE = ""

/**
 * 真实的页字节来源 —— [com.cyanix.pixfold.domain.pack.PageByteSource] 的领域侧实现。
 *
 * [com.cyanix.pixfold.domain.pack.runPackPlans] 要求「按 `itemId` 取字节」，而
 * `SourceItem.id` **就是** `documentUri`(扫描层如此约定),故这里要的是「给定
 * documentUri 返回字节」的能力。平台层用 `ContentResolver.openInputStream` 实现,
 * 领域层只声明形状。
 *
 * 单独一个 fun interface 而不是复用 `WorkspaceSource`:打包只看单页字节,
 * 不需要工作区/授权/集合这些概念;塞进那个接口会让测试夹具多实现一堆无关方法。
 */
fun interface PageBytes {
    /** 取某一页的字节;读不到返回 `null`(业务分支,不抛)。 */
    fun bytesOf(documentUri: String): ByteArray?

    /**
     * 按**流**打开某一页;打不开返回 `null`。
     *
     * 打包内核走流式路径时优先用它(见 `streamingPageByteSource`),因为 [bytesOf]
     * 会把整页读进 `ByteArray` —— 真机上 100MB 的页在 256MB 堆直接
     * `OutOfMemoryError`,且留下 0 字节 CBZ。
     *
     * 默认实现退化成「取字节再包成流」,所以既有实现不必改;SAF 实现
     * (`SafPageBytes`)应当覆写它。
     */
    fun openFor(documentUri: String): InputStream? = bytesOf(documentUri)?.inputStream()
}

/**
 * 把 [PageBytes] 适配成打包内核要的 [com.cyanix.pixfold.domain.pack.PageByteSource]。
 *
 * 为什么需要适配而不是直接把 [PageBytes] 塞进去:打包内核的 `itemId` 类型是
 * `String`,但它的语义是「**页条目的标识**」。真实素材里那个标识就是 documentUri;
 * mock 里是 `aot-1-p3` 这类合成 id。适配层不假设二者相同,而是**由调用方给出
 * 从 itemId 到 uri 的映射** —— mock 场景下映射缺失即判为该页读不到(计划里本来
 * 就不该出现拿不到字节的卷)。
 */
fun pageByteSourceOf(
    pages: Map<String, String>,
    bytes: PageBytes,
): PageByteSource =
    object : PageByteSource {
        override fun bytesOf(itemId: String): ByteArray? {
            val uri = pages[itemId] ?: return null
            return bytes.bytesOf(uri)
        }

        // 流式路径:把 uri 映射后直接开流,不经过 bytesOf 的整块读。
        override fun openFor(itemId: String): InputStream? {
            val uri = pages[itemId] ?: return null
            return bytes.openFor(uri)
        }
    }

/**
 * 判断某卷的输出文件是否已存在 —— 平台层实现。
 *
 * 领域层不碰文件系统,但 `VolumePackPlan.overwritesExisting` 依赖这个判断的正确性,
 * 故把它做成显式端口而不是让调用方在 UI 里临时拼。
 */
fun interface OutputExistsProbe {
    /** 该卷的 `outputDir/outputDisplay` 是否已存在。 */
    fun exists(volume: ComicVolume): Boolean
}

/** 用 [OutputExistsProbe] 批量标注 `outputExists`。 */
fun markOutputExists(
    volumes: List<ComicVolume>,
    probe: OutputExistsProbe,
): List<ComicVolume> = volumes.map { it.copy(outputExists = probe.exists(it)) }

/**
 * 真实扫描文档里一页都取不到的集合**不该进打包链** —— 空卷打出来的 CBZ 只有
 * `ComicInfo.xml`,阅读器打开是空白。
 *
 * 与 [com.cyanix.pixfold.domain.source.collectionsFrom] 的分工:那边负责「目录里的
 * 图片怎么分组」,这边负责「哪一组值得打包」。
 */
fun packableVolumes(volumes: List<ComicVolume>): List<ComicVolume> = volumes.filter { it.pages.isNotEmpty() }

/**
 * 扫描结果的**页数分布摘要**,供真机核对读数用(「这个工作区应该产出 2 个 CBZ」)。
 *
 * 领域层出这个而不是让 UI 自己数,是因为它是验收口径的一部分:真机上「产出的
 * CBZ 个数」必须等于这里的数,否则说明有卷在链路里被静默丢掉了。
 */
fun packSummary(volumes: List<ComicVolume>): PackSummary = PackSummary(
    volumeCount = volumes.count { it.included },
    pageCount = volumes.filter { it.included }.sumOf { it.pageCount },
    emptyVolumeCount = volumes.count { it.pages.isEmpty() },
)

/** [packSummary] 的结果。 */
data class PackSummary(val volumeCount: Int, val pageCount: Int, val emptyVolumeCount: Int)
