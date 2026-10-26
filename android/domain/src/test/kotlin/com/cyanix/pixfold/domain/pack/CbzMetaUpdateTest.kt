package com.cyanix.pixfold.domain.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 工作流 C 的纯函数与计划类型。
 *
 * 这里刻意只测**不需要任何 I/O**的部分：条目名判定、条目顺序重排、计划的输入校验。
 * 真正的字节转写在 [CbzMetaRewriterTest]。
 */
class CbzMetaUpdateTest {

    @Test
    fun `isComicInfoEntry ignores case and directory prefix`() {
        assertTrue(isComicInfoEntry("ComicInfo.xml"))
        assertTrue(isComicInfoEntry("comicinfo.xml"))
        assertTrue(isComicInfoEntry("COMICINFO.XML"))
        assertTrue(isComicInfoEntry("meta/ComicInfo.xml"))
        assertFalse(isComicInfoEntry("ComicInfo.xml.bak"))
        assertFalse(isComicInfoEntry("001.jpg"))
    }

    @Test
    fun `rewrittenEntryOrder puts ComicInfo first when it was in the middle`() {
        val old = listOf("001.jpg", "002.jpg", "ComicInfo.xml", "003.jpg")
        assertEquals(
            listOf("ComicInfo.xml", "001.jpg", "002.jpg", "003.jpg"),
            rewrittenEntryOrder(old),
        )
    }

    @Test
    fun `rewrittenEntryOrder drops every case variant of ComicInfo`() {
        val old = listOf("ComicInfo.xml", "001.jpg", "comicinfo.xml")
        assertEquals(listOf("ComicInfo.xml", "001.jpg"), rewrittenEntryOrder(old))
    }

    @Test
    fun `rewrittenEntryOrder works when the archive had no ComicInfo`() {
        assertEquals(
            listOf("ComicInfo.xml", "001.jpg"),
            rewrittenEntryOrder(listOf("001.jpg")),
        )
    }

    @Test
    fun `plan rejects an empty xml and an empty display name`() {
        val ok = CbzMetaUpdatePlan(
            volumeId = "v",
            comicInfoXml = "<x/>",
            targetDisplayName = "a.cbz",
            entryCount = 0,
        )
        assertEquals(0, ok.entryCount)
        assertTrue(runCatching { ok.copy(comicInfoXml = "") }.isFailure)
        assertTrue(runCatching { ok.copy(targetDisplayName = "") }.isFailure)
    }
}
