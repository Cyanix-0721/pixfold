package com.pixfold.d1.domain.comic

import com.pixfold.d1.domain.model.ComicVolume
import com.pixfold.d1.domain.model.SourceItem
import com.pixfold.d1.domain.text.Msg
import com.pixfold.d1.domain.text.TextKey

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
 * @param pageIssues 页码一致性问题的**可读原因**列表(空 = 一致);键 + 参数,渲染时才解析
 */
data class ComicPreview(
    val xml: String,
    val pages: List<RenumberedPage>,
    val pageIssues: List<Msg>,
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
 * 校验"页序、页表与页码编号彼此自洽"(HANGOFF §10 数据正确性)。
 *
 * ## 两类检查,各自的两个来源
 *
 * **① 页表 ↔ 页序**(`volume.pages` ↔ `volume.pageOrder.order`):
 * 这两个是**独立**的列表 —— 建卷时同源,但页序会被人工拖拽改动,故它们必须始终
 * 覆盖**同一批页**。这一条此前是拿 `renumberPages(volume).size` 跟
 * `volume.pageOrder.order.size` 比 —— 而 `renumberPages` 的返回长度**就是**
 * `order.size`,等于"列表与自身比长度",恒等 ⇒ 这些原因从来没产出过,
 * `consistent` 恒真,UI 的告警分支不可达(R4)。
 * 现在改成比"卷内登记的页"与"页序里排的页":长度不同报数量不符,长度相同但
 * 成员不同报"有页不在页序里"(人工拖拽只换顺序,故这里比的是**集合**而非位置)。
 *
 * **② 编号契约**(`renumberPages` 的产出):序号从 1 起、补零位数随页数自适应、
 * 扩展名小写。这一条**不是自证**:补零规则在 [renumberPages] 与本函数里各写了一次,
 * 它校验的是"两份实现是否仍然一致" —— 只改坏其中一处即会报错
 * (W13 的记录里`页码补零不随页数自适应`正是被这一条捕获的)。
 *
 * 返回不一致的原因列表(空 = 一致),供 UI 与测试共用。
 *
 * 虽然这是"自检"性质,但 [PackPreviewPage] 会把它显示给用户(「页码与页序不一致：…」——前缀是矢量图标,不再是 emoji),
 * 故这些原因**是用户文案**,与 `Suggestion.source` 的处置相反 —— 走 [Msg] 而非字面量。
 */
fun validatePageConsistency(volume: ComicVolume): List<Msg> {
    val issues = mutableListOf<Msg>()

    val listed = volume.pages
    val order = volume.pageOrder.order
    if (listed.size != order.size) {
        issues.add(Msg(TextKey.PageIssueCountMismatch, listOf(listed.size, order.size)))
    } else {
        val orderIds = order.map { it.id }.toSet()
        val missing = listed.count { it.id !in orderIds }
        if (missing > 0) {
            issues.add(Msg(TextKey.PageIssueMissingFromOrder, listOf(missing, listed.size)))
        }
    }

    val pages = renumberPages(volume)
    pages.forEachIndexed { i, p ->
        val expected = (i + 1).toString().padStart(if (pages.size >= 100) 4 else 3, '0')
        if (!p.fileName.startsWith("$expected.")) {
            issues.add(Msg(TextKey.PageIssueWrongIndex, listOf(i + 1, expected, p.fileName)))
        }
    }
    return issues
}
