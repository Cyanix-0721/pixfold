package com.pixfold.d1.domain.comic

import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.SourceItem

/**
 * ComicInfo.xml 预览与页码重编号(规格 §8.5 / §8.6)。
 *
 * **字段子集**与归档原型一致(`Title/Series/Number/Writer/LanguageISO`),
 * 是 `scripts/batch_pack_cbz.py` 的字段子集的子集;若日后要扩展字段,
 * 须回头对齐脚本(规格 §8.5 注)。
 *
 * 与脚本的**两处已知差异**(有意,原型只做预览):
 *  - 脚本用 4 空格缩进 + `<Volume>` + `PageCount` + `Pages`,本实现用规格 §8.5 的 2 空格
 *    + `<Number>`(归档原型契约);
 *  - 脚本在值为空时**省略**标签,本实现按规格输出**自闭合空标签**(`<Title />`)——
 *    "不写入"必须是一种明确表示,而不是"标签不见了"。
 */
object ComicInfo {

    /** XML 转义(只处理 4 个;`'` 不转义 —— 属性值未使用单引号)。 */
    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 空值/不写入 → 自闭合空标签。判定 = null 或空串。 */
    private fun field(tag: String, value: String?): String =
        if (value.isNullOrEmpty()) "  <$tag />" else "  <$tag>${escape(value)}</$tag>"

    /**
     * 生成整份 ComicInfo.xml 预览文本(字段顺序固定,每行以 `\n` 结尾)。
     */
    fun build(volume: ComicVolume): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append(
            "<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                "xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\">\n",
        )
        append(field("Title", volume.title)).append('\n')
        append(field("Series", volume.series)).append('\n')
        append(field("Number", volume.volume?.toString())).append('\n')
        append(field("Writer", volume.writer)).append('\n')
        append(field("LanguageISO", langIso(volume.language, volume.otherLangCode))).append('\n')
        append("</ComicInfo>\n")
    }
}

/** 重编号后的一项:原图 + 目标页码文件名。 */
data class RenumberedPage(val item: SourceItem, val fileName: String)

/**
 * 页码重编号(规格 §8.6)。
 *
 * - 序号**从 1 起**,补零位数**按总页数自适应**(`>= 100` 用 4 位,否则 3 位);
 * - 扩展名**强制小写**;
 * - 与工作流 A 的 `NamingScheme.indexPadding` **完全独立**(不读命名结构);
 * - 返回保留**原 [SourceItem] 引用**,供 UI 标注"人工调整"。
 *
 * 取的是 [ComicVolume.pageOrder] 的 `order` —— 即**含人工调整的最终页序**。
 */
fun renumberPages(volume: ComicVolume): List<RenumberedPage> {
    val order = volume.pageOrder.order
    val padding = if (order.size >= 100) 4 else 3
    return order.mapIndexed { i, item ->
        RenumberedPage(item, (i + 1).toString().padStart(padding, '0') + "." + item.ext.lowercase())
    }
}

/**
 * 一卷的 ComicInfo.xml 与页码预览(验收第 14 项:两者**一致**)。
 *
 * **为什么合成一个入口**:验收项要的是"XML 预览与页码列表预览一致"。
 * 若 UI 各自算一份,它们就可能基于**不同的页序**画出结果(归档原型的教训:
 * 数据对了但两处界面不同步)。这里把两者绑在同一次求值里,一致性成为**结构性质**。
 *
 * @param xml        ComicInfo.xml 预览文本
 * @param pages      页码列表预览(含重编号)
 * @param pageIssues 页码一致性问题的**可读原因**列表(空 = 一致)
 */
data class ComicPreview(
    val xml: String,
    val pages: List<RenumberedPage>,
    val pageIssues: List<String>,
) {
    val consistent: Boolean get() = pageIssues.isEmpty()
}

/** 唯一求值入口:XML + 页码一次算出(规格 §8.5 / §8.6)。 */
fun previewOf(volume: ComicVolume): ComicPreview = ComicPreview(
    xml = ComicInfo.build(volume),
    pages = renumberPages(volume),
    pageIssues = validatePageConsistency(volume),
)

/**
 * 校验"页码数量与 ComicInfo.xml 一致"(HANGOFF §10 数据正确性)。
 *
 * 原型阶段 XML 里没有逐页 `<Pages>` 段,故这里校验的是**页数的唯一来源**:
 * 页码预览的项数与卷的页序长度一致,且序号连续从 1 起。
 * 返回不一致的原因列表(空 = 一致),供 UI 与测试共用。
 */
fun validatePageConsistency(volume: ComicVolume): List<String> {
    val issues = mutableListOf<String>()
    val pages = renumberPages(volume)
    if (pages.size != volume.pageOrder.order.size) {
        issues.add("页码项数与页序长度不一致（${pages.size} ≠ ${volume.pageOrder.order.size}）")
    }
    pages.forEachIndexed { i, p ->
        val expected = (i + 1).toString().padStart(if (pages.size >= 100) 4 else 3, '0')
        if (!p.fileName.startsWith("$expected.")) {
            issues.add("第 ${i + 1} 项页码应为 $expected，实际 ${p.fileName}")
        }
    }
    return issues
}
