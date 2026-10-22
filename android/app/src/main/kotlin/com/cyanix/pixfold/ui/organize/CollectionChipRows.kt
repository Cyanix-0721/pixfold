package com.cyanix.pixfold.ui.organize

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.cyanix.pixfold.R
import com.cyanix.pixfold.domain.model.ImageCollection
import com.cyanix.pixfold.domain.source.groupCollections
import com.cyanix.pixfold.ui.components.ChipRowTags
import com.cyanix.pixfold.ui.components.TwoLevelChipRows
import com.cyanix.pixfold.ui.components.TwoLevelGroup

/** 1 级芯片(series 或单本)的 testTag 生成器 —— 只用于 series,单本沿用 [collectionTag]。 */
fun groupTag(key: String) = "collection-group-$key"

/** 1 级芯片「全部 N 项」入口的 testTag。 */
const val TAG_COLLECTION_GROUP_ALL = "collection-group-all"

/** 1 级芯片 sheet 容器 / 列表的 testTag。 */
const val TAG_COLLECTION_GROUP_SHEET = "collection-group-sheet"
const val TAG_COLLECTION_GROUP_SHEET_LIST = "collection-group-sheet-list"

/** sheet 内 1 级芯片的 testTag 生成器(与常驻行区分,避免同 tag 撞车)。 */
fun groupSheetTag(key: String) = "collection-group-sheet-$key"

/** 2 级芯片(选中 series 下的各单本)整行的 testTag。 */
const val TAG_COLLECTION_BOOK_ROW = "collection-book-row"

/** 2 级芯片「全部 N 项」入口的 testTag。 */
const val TAG_COLLECTION_BOOK_ALL = "collection-book-all"

/** 2 级芯片 sheet 容器 / 列表 / sheet 内芯片的 testTag。 */
const val TAG_COLLECTION_BOOK_SHEET = "collection-book-sheet"
const val TAG_COLLECTION_BOOK_SHEET_LIST = "collection-book-sheet-list"

/** 2 级芯片 sheet 内芯片的 testTag 生成器。 */
fun bookSheetTag(id: String) = "collection-book-sheet-$id"

/**
 * 集合切换芯片行 —— **两级**(用户 2026-10-19 裁决,原话「芯片分2级别,series或单本作为
 * 1级,series作为2级,如果选择series,收缩series芯片组到一行,下面添加显示2级芯片组选择
 * series下的单本」)。
 *
 * 2026-10-19 起**状态逻辑已抽到 `ui/components/TwoLevelChipRows`**(用户随后要求把同一交互
 * 推广到工作流 A 步骤 2 与工作流 B 元数据步骤 ⇒ 三处共用一份;否则下次改交互只会改到一半,
 * 而这类漂移表现为「某页能切、另一页切不动」,最难归因)。本文件只剩「把图片集合映射成
 * 1 级条目 + 注入集合页的 tag 与文案」这层适配,**对外 tag 体系与文案全部不变**,
 * 既有 18 个按 `collectionTag(...)` 点芯片的测试无需改动。
 *
 * ## 为什么是两级
 *
 * 真实素材的层级是「系列 / 单本 / 页」(市面素材常见形态:一个系列目录下若干本)。
 * 「每个**直接含图片的目录** = 一个集合」之后,一个工作区里可能既有独立单本、又有系列,
 * 平铺成一行时系列名与书名混在一起,用户分不清哪些是同一系列的几本。
 *
 * ## 两条既有语义仍然成立
 *
 * 1. **单本 1 级芯片沿用 [collectionTag]**(不是新 tag):既有测试按它点芯片,语义没变。
 * 2. 切集合必须回到顶部 —— 由调用方在 `onSelect` 里处理(`scrollSignal++`),
 *    本组件只管「选中了哪个 id」。
 */
@Composable
fun CollectionChipRows(
    collections: List<ImageCollection>,
    activeId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val groups = remember(collections) {
        groupCollections(collections).map { group ->
            TwoLevelGroup(
                key = group.key,
                name = group.name,
                isSeries = group.isSeries,
                members = group.collections,
            )
        }
    }

    TwoLevelChipRows(
        groups = groups,
        activeItemId = activeId,
        idOf = { it.id },
        onSelect = onSelect,
        topTags = ChipRowTags(
            selectorTag = TAG_COLLECTION_ROW,
            chipTag = ::groupTag,
            allEntryTag = TAG_COLLECTION_GROUP_ALL,
            sheetTag = TAG_COLLECTION_GROUP_SHEET,
            sheetListTag = TAG_COLLECTION_GROUP_SHEET_LIST,
            sheetChipTag = ::groupSheetTag,
        ),
        childTags = ChipRowTags(
            selectorTag = TAG_COLLECTION_BOOK_ROW,
            chipTag = ::collectionTag,
            allEntryTag = TAG_COLLECTION_BOOK_ALL,
            sheetTag = TAG_COLLECTION_BOOK_SHEET,
            sheetListTag = TAG_COLLECTION_BOOK_SHEET_LIST,
            sheetChipTag = ::bookSheetTag,
        ),
        singleTopChipTag = ::collectionTag,
        topChipDescription = { group ->
            if (group.isSeries) {
                stringResource(R.string.a_series_cd, group.name)
            } else {
                stringResource(R.string.a_collection_cd, group.name)
            }
        },
        topChipLabel = { group ->
            stringResource(
                R.string.a_collection_chip,
                group.name,
                group.members.sumOf { it.images.size },
            )
        },
        topAllEntryText = { n -> pluralsAllEntry(n) },
        childChipDescription = { collection -> stringResource(R.string.a_collection_cd, collection.name) },
        childChipLabel = { collection ->
            stringResource(
                R.string.a_collection_chip,
                collection.name,
                collection.images.size,
            )
        },
        childAllEntryText = { n -> pluralsAllEntry(n) },
        modifier = modifier,
    )
}

/**
 * 「全部 N 项」的文案 —— 集合芯片不论量词是系列还是单本,都统一叫「项」。
 *
 * ⚠️ 必须是 `@Composable`:`pluralStringResource` 只能在组合里调。写成普通函数会让
 * `allEntryText: @Composable (Int) -> String` 的 lambda 无法直接引用。
 */
@Composable
private fun pluralsAllEntry(n: Int): String =
    pluralStringResource(R.plurals.collection_all, n, n)
