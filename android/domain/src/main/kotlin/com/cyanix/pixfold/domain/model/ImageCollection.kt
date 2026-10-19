package com.cyanix.pixfold.domain.model

/**
 * 图片集合 / 漫画候选集合。rootDirName 独立于 name,是命名组件 rootDir 的取值来源。
 *
 * [seriesKey] / [seriesName] 表达「这本书属于哪个系列」(2026-10-19 两级芯片):
 * 真实扫描下集合 = **直接含图片的目录**,该目录的父目录即系列。手写数据(mock)
 * 没有系列概念,两字段取默认空串 ⇒ 每个集合自身就是一个 1 级条目。
 *
 * 为什么不把系列做成嵌套结构:集合是**唯一**被排序/命名/打包消费的单位,
 * 系列只是芯片行的分组视图。把系列做成外层容器会让 `WorkflowAState.orders`
 * 这类按集合 id 索引的状态全部要改索引口径,得不偿失。
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
)
