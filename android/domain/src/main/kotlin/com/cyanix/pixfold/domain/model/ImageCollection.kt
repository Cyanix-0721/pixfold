package com.cyanix.pixfold.domain.model

import com.cyanix.pixfold.domain.naming.DerivedNames
import com.cyanix.pixfold.domain.naming.derivedNamesOf

/**
 * 图片集合 / 漫画候选集合。rootDirName 独立于 name,是命名组件 rootDir 的取值来源。
 *
 * [seriesKey] / [seriesName] 表达「这本书属于哪个系列」(2026-10-19 两级芯片):
 * 真实扫描下集合 = **直接含图片的目录**,该目录的父目录即系列。手写数据(mock)
 * 没有系列概念,两字段取默认空串 ⇒ 每个集合自身就是一个 1 级条目。
 *
 * 为什么不把系列做成嵌套结构:集合是**唯一**被排序/命名/打包消费的单位,
 * 系列只是芯片行的分组视图。把系列做成外层容器会让 `OrganizeState.orders`
 * 这类按集合 id 索引的状态全部要改索引口径,得不偿失。
 *
 * ## [name] 与 [derived] 的分工(2026-10-19 统一抽象)
 *
 * [name] 是**目录名原样**,[derived] 是它推导出的全部展示形态。展示位置按用途各取所需:
 *
 * - **芯片 / 步骤 2 标题 → [name]**。用户在文件管理器里按这个名字找目录,
 *   显示清洗后的名字会让人对不上号。
 * - **元数据 `Title` / 落盘 CBZ 文件名 → [derived]**。
 *
 * 这个分工是用户 2026-10-19 裁决的。在那之前真实扫描的名字完全没清洗,
 * 导致 CBZ 文件名里留着 `[DL版]` 这类发行标注。
 */
data class ImageCollection(
    val id: String,
    val name: String,
    val rootDirName: String,
    val images: List<SourceItem>,
    /** 系列分组键 = 集合目录的**相对父路径**;空串 = 不属于任何系列。 */
    val seriesKey: String = "",
    /** 系列显示名 = 父目录名;空串 = 无系列(UI 用集合名)。 */
    val seriesName: String = "",
) {
    /**
     * [name] 推导出的展示形态。
     *
     * `lazy` 而非普通 `get()`:真实工作区扫描出几十上百个集合,而这个名字在芯片行、
     * 标题、计划页里每帧都会被读,每次读重跑三条正则不划算。
     *
     * 委托属性不在主构造函数里 ⇒ **不参与 `data class` 的 `equals`/`hashCode`/`copy`**,
     * 所以对账时「名字没变」的判定不受影响。
     */
    val derived: DerivedNames by lazy { derivedNamesOf(name) }

    /**
     * 系列的展示形态;无系列时等同于 [derived]。
     *
     * 不能直接用 [derived]:系列目录名与卷目录名是两串**独立**的名字。作者标记通常写在
     * **系列**目录上(`comicVolumesFrom` 的作者来源就是它),而标题来自卷目录。
     */
    val seriesDerived: DerivedNames by lazy {
        if (seriesName.isEmpty()) derived else derivedNamesOf(seriesName)
    }
}
