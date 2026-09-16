package com.pixfold.d1.domain.model

/** 图片集合 / 漫画候选集合。rootDirName 独立于 name,是命名组件 rootDir 的取值来源。 */
data class ImageCollection(
    val id: String,
    val name: String,
    val rootDirName: String,
    val images: List<SourceItem>,
)
