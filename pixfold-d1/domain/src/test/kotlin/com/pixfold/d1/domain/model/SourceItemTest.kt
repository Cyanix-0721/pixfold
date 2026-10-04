package com.pixfold.d1.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 来源文件快照的三个派生显示量。
 *
 * `fileName` / `relPath` 此前**领域层零覆盖**(`relPath` 在 25 个领域测试文件里引用计数为 0),
 * 三个 `sizeLabel` 变异体也因此存活。它们都是 UI 直接渲染的字符串:
 *  - `fileName` 决定"同名撞车"的判定(`SortItems` 的 `FileName` 排序键也用它);
 *  - `relPath` 是缩略图行的可访问性文案(`ui/components/ThumbRow.kt`);
 *  - `sizeLabel` 是缩略图行里的体积。
 */
class SourceItemTest {

    private fun item(
        dir: String = "",
        base: String = "img",
        ext: String = "jpg",
        size: Long = 0,
    ) = SourceItem(
        id = "x", collectionId = "c", dir = dir, baseName = base, ext = ext,
        sizeBytes = size, modifiedEpochMillis = 0, createdEpochMillis = 0, seed = 0,
    )

    @Test
    fun `file name is base name plus extension`() {
        assertEquals("img.jpg", item().fileName)
        assertEquals("page_01.png", item(base = "page_01", ext = "png").fileName)
    }

    @Test
    fun `relative path includes the directory when there is one`() {
        assertEquals("day1/img.jpg", item(dir = "day1").relPath, "有目录时必须带上目录与分隔斜杠")
        assertEquals("day1/day2/img.jpg", item(dir = "day1/day2").relPath)
    }

    @Test
    fun `relative path is just the file name at the root`() {
        assertEquals("img.jpg", item(dir = "").relPath, "根目录下不得出现前导斜杠")
    }

    @Test
    fun `size label switches at exactly one kilobyte`() {
        // 边界写死:1023 还是 B,1024 就是 1 KB(判定式是 >= 1024,不是 > 1024)
        assertEquals("1023 B", item(size = 1023).sizeLabel)
        assertEquals("1 KB", item(size = 1024).sizeLabel)
        assertEquals("0 B", item(size = 0).sizeLabel)
    }

    @Test
    fun `size label switches at exactly one megabyte`() {
        // 边界写死:恰好 1 MiB 就走 MB 分支(判定式是 >= 1024*1024,不是 > )
        assertEquals("1023 KB", item(size = 1024L * 1024L - 1024L).sizeLabel)
        assertEquals("1.0 MB", item(size = 1024L * 1024L).sizeLabel)
    }

    @Test
    fun `kilobyte label divides by one thousand and twenty four`() {
        // 3000 / 1024 = 2(整数除法),而 3000 / 1000 = 3 —— 只有这类数值能分辨换算基数
        assertEquals("2 KB", item(size = 3000).sizeLabel)
        assertEquals("9 KB", item(size = 10_000).sizeLabel)
    }

    @Test
    fun `megabyte label keeps one decimal`() {
        assertEquals("1.5 MB", item(size = 1572864).sizeLabel)
        assertEquals("2.0 MB", item(size = 2 * 1024L * 1024L).sizeLabel)
    }
}
