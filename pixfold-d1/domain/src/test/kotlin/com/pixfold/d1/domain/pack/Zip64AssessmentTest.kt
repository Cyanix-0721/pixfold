package com.pixfold.d1.domain.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * D2b Zip64 写入领域层单测（HANGOFF §9 D2b「大归档写入 Zip64」）。
 *
 * 覆盖：Zip64 需求判定（条目数 / 条目大小 / 中央目录偏移三阈值）+ 中断半成品识别。
 * 纯 JVM，不连真机。
 */
class Zip64AssessmentTest {

    // ---- assessZip64Need: NotNeeded ----

    @Test
    fun `小归档不需要 Zip64`() {
        val result = assessZip64Need(entryCount = 100, maxEntrySize = 1_000_000L)
        assertIs<Zip64Requirement.NotNeeded>(result)
    }

    @Test
    fun `条目数刚好等于上限不需要 Zip64`() {
        val result = assessZip64Need(entryCount = Zip32Limits.MAX_ENTRIES, maxEntrySize = 0L)
        assertIs<Zip64Requirement.NotNeeded>(result)
    }

    @Test
    fun `条目大小刚好等于上限不需要 Zip64`() {
        val result = assessZip64Need(entryCount = 1, maxEntrySize = Zip32Limits.MAX_ENTRY_SIZE)
        assertIs<Zip64Requirement.NotNeeded>(result)
    }

    @Test
    fun `中央目录偏移刚好等于上限不需要 Zip64`() {
        val result = assessZip64Need(
            entryCount = 1,
            maxEntrySize = 0L,
            centralDirOffset = Zip32Limits.MAX_CENTRAL_DIR_OFFSET,
        )
        assertIs<Zip64Requirement.NotNeeded>(result)
    }

    // ---- assessZip64Need: Needed - TooManyEntries ----

    @Test
    fun `条目数超上限需要 Zip64 且原因正确`() {
        val result = assessZip64Need(entryCount = Zip32Limits.MAX_ENTRIES + 1, maxEntrySize = 0L)
        assertIs<Zip64Requirement.Needed>(result)
        assertEquals(Zip64Reason.TooManyEntries, result.reason)
    }

    // ---- assessZip64Need: Needed - EntryTooLarge ----

    @Test
    fun `条目大小超上限需要 Zip64 且原因正确`() {
        val result = assessZip64Need(entryCount = 1, maxEntrySize = Zip32Limits.MAX_ENTRY_SIZE + 1)
        assertIs<Zip64Requirement.Needed>(result)
        assertEquals(Zip64Reason.EntryTooLarge, result.reason)
    }

    // ---- assessZip64Need: Needed - CentralDirOffsetTooLarge ----

    @Test
    fun `中央目录偏移超上限需要 Zip64 且原因正确`() {
        val result = assessZip64Need(
            entryCount = 1,
            maxEntrySize = 0L,
            centralDirOffset = Zip32Limits.MAX_CENTRAL_DIR_OFFSET + 1,
        )
        assertIs<Zip64Requirement.Needed>(result)
        assertEquals(Zip64Reason.CentralDirOffsetTooLarge, result.reason)
    }

    // ---- assessZip64Need: 优先级 ----

    @Test
    fun `条目数优先于条目大小判定`() {
        // 两条都超：条目数超 + 大小超 → 先报条目数（when 顺序）
        val result = assessZip64Need(
            entryCount = Zip32Limits.MAX_ENTRIES + 1,
            maxEntrySize = Zip32Limits.MAX_ENTRY_SIZE + 1,
        )
        assertIs<Zip64Requirement.Needed>(result)
        assertEquals(Zip64Reason.TooManyEntries, result.reason)
    }

    @Test
    fun `条目大小优先于偏移判定`() {
        val result = assessZip64Need(
            entryCount = 1,
            maxEntrySize = Zip32Limits.MAX_ENTRY_SIZE + 1,
            centralDirOffset = Zip32Limits.MAX_CENTRAL_DIR_OFFSET + 1,
        )
        assertIs<Zip64Requirement.Needed>(result)
        assertEquals(Zip64Reason.EntryTooLarge, result.reason)
    }

    // ---- assessZip64Need: 参数校验 ----

    @Test
    fun `条目数为负抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            assessZip64Need(entryCount = -1, maxEntrySize = 0L)
        }
    }

    @Test
    fun `条目大小为负抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            assessZip64Need(entryCount = 1, maxEntrySize = -1L)
        }
    }

    @Test
    fun `偏移为负抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            assessZip64Need(entryCount = 1, maxEntrySize = 0L, centralDirOffset = -1L)
        }
    }

    // ---- assessZip64Need: 零与空 ----

    @Test
    fun `零条目零大小不需要 Zip64`() {
        val result = assessZip64Need(entryCount = 0, maxEntrySize = 0L)
        assertIs<Zip64Requirement.NotNeeded>(result)
    }

    // ---- Zip32Limits 常量正确性 ----

    @Test
    fun `MAX_ENTRIES 是 65535`() {
        assertEquals(65535, Zip32Limits.MAX_ENTRIES)
    }

    @Test
    fun `MAX_ENTRY_SIZE 是 4GB 减 1`() {
        assertEquals(0xFFFFFFFFL, Zip32Limits.MAX_ENTRY_SIZE)
        assertEquals(4_294_967_295L, Zip32Limits.MAX_ENTRY_SIZE)
    }

    @Test
    fun `MAX_CENTRAL_DIR_OFFSET 等于 MAX_ENTRY_SIZE`() {
        assertEquals(Zip32Limits.MAX_ENTRY_SIZE, Zip32Limits.MAX_CENTRAL_DIR_OFFSET)
    }

    // ---- isCompleteArchive ----

    @Test
    fun `有 EOCD 且非空是完整归档`() {
        assertTrue(isCompleteArchive(fileSize = 1000L, hasEocd = true))
    }

    @Test
    fun `空文件不是完整归档`() {
        assertTrue(!isCompleteArchive(fileSize = 0L, hasEocd = true))
    }

    @Test
    fun `无 EOCD 不是完整归档（中断半成品）`() {
        assertTrue(!isCompleteArchive(fileSize = 1000L, hasEocd = false))
    }

    @Test
    fun `零字节且无 EOCD 不是完整归档`() {
        assertTrue(!isCompleteArchive(fileSize = 0L, hasEocd = false))
    }

    @Test
    fun `isCompleteArchive 文件大小为负抛异常`() {
        assertFailsWith<IllegalArgumentException> {
            isCompleteArchive(fileSize = -1L, hasEocd = true)
        }
    }

    // ---- Zip64Reason 描述 ----

    @Test
    fun `Zip64Reason 描述非空`() {
        Zip64Reason.entries.forEach { r ->
            assertTrue(r.description.isNotEmpty(), "$r 描述不应为空")
        }
    }
}
