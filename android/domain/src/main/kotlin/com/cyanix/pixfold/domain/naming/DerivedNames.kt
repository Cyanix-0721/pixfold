package com.cyanix.pixfold.domain.naming

/**
 * 一个目录名能推导出的**全部**展示名 —— 唯一权威。
 *
 * ## 为什么要有这一个类型
 *
 * 2026-10-19 之前,目录名到展示名的推导散落在三处,而且**只有一处做了清洗**:
 *
 * | 位置 | 产出 | 清洗 |
 * | --- | --- | --- |
 * | `collectionsFrom` | 集合名 / 系列名(工作流 A 芯片、步骤 2 标题) | ❌ 裸目录名 |
 * | `groupCollections` | 1 级芯片名(系列名) | ❌ 裸目录名 |
 * | `comicVolumesFrom` | title / series / CBZ 基名 / writer(工作流 B) | ✅ |
 *
 * 后果很具体:真机上步骤 2 的标题写着
 * `命名结构 · [Cior (Ken-1)] 彼女がボクの知らないところで（32 张）`,
 * 而同一个目录产出的 CBZ 却叫 `彼女がボクの知らないところで――2.cbz` ——
 * 同一个名字在界面上有两个形态,用户无从判断哪个才是最终结果。
 *
 * ## 四种形态各有各的用途,**不要合并**
 *
 * - [raw] —— 目录原样。**芯片与步骤 2 标题用它**:用户要能在文件管理器里按这个名字找到目录。
 * - [title] —— 剥掉 `[作者]` 前缀、(原作)括号、末尾 `[DL版]` 等标注。**元数据 `<Title>` 用它**:
 *   作者已经单独写在 `<Writer>` 里,标题里再带一遍就是重复。
 * - [cbzBase] —— 剥掉 `(原作)` 与末尾标注,但**保留 `[作者]` 前缀**。**落盘文件名用它**:
 *   文件管理器里一列 CBZ,认得出是谁的作品比标题干净更重要。
 * - [writerRaw] / [writerCleaned] —— 原始作者标记(`[作者]诹山创(原作:某人)`)与清理后的
 *   `诹山创`。前者给建议值,后者给「一键清理」按钮的目标值。
 *
 * 清洗规则本身在 [NameCleaning.kt](stripOriginalWork / parseCollectionName /
 * cleanCbzBaseName / cleanWriterName),本类型只负责把结果**摆在一起**,不重复实现。
 *
 * @property raw 目录名原样
 * @property title 剥作者前缀 / 括号原作 / 末尾标注后的标题
 * @property cbzBase 保留 `[作者]` 前缀的落盘基名
 * @property writerRaw 作者标记的**原始形态**(带方括号,可直接写进 writerSug)
 * @property writerCleaned 清理后的作者名(供「一键清理」)
 */
data class DerivedNames(
    val raw: String,
    val title: String,
    val cbzBase: String,
    val writerRaw: String,
    val writerCleaned: String,
) {
    /** 清洗是否真的改变了什么 —— 决定来源说明写「目录名」还是「目录名清洗」。 */
    val isCleaned: Boolean get() = title != raw || cbzBase != raw
}

/**
 * 从目录名推出全部展示形态。**这是目录名到展示名的唯一入口。**
 *
 * 空串输入返回全空(而不是抛异常):真实扫描里集合名可能是空串(工作区根集合的兜底),
 * 调用方自己决定回退成什么。
 *
 * 所有字段在清洗后为空时**回退到 `raw`** —— 一个 `[DL版]` 这样的目录名剥完什么都不剩,
 * 此时展示空标题比展示原目录名更糟(用户找不到对应目录)。
 */
fun derivedNamesOf(dirName: String): DerivedNames {
    if (dirName.isEmpty()) {
        return DerivedNames(raw = "", title = "", cbzBase = "", writerRaw = "", writerCleaned = "")
    }
    val parsed = parseCollectionName(dirName)
    return DerivedNames(
        raw = dirName,
        title = parsed.title.ifEmpty { dirName },
        cbzBase = cleanCbzBaseName(dirName).ifEmpty { dirName },
        writerRaw = parsed.writerRaw,
        writerCleaned = cleanWriterName(parsed.writerRaw),
    )
}
